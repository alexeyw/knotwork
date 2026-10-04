package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.engine.TaskQueueManager
import app.knotwork.android.domain.models.AgentTask
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunSettings
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

/**
 * Validates and launches the checkpoint resume of a non-live pipeline run.
 *
 * The use case is the single entry point of the resume feature: it owns every
 * precondition that decides whether a run may continue from its persisted
 * trace, and on success hands the run back to the [TaskQueueManager] as a
 * resume-flagged [AgentTask]. The queue worker then re-validates the graph
 * hash (the user could edit the pipeline in the window between this
 * validation and the worker picking the task up), rebuilds the
 * `ResumeContext` from the persisted trace, and drives the engine in replay
 * mode.
 *
 * Two starting states are resumable:
 *  - [PipelineRunStatus.INTERRUPTED] — the owning process died mid-run; the
 *    user resumes explicitly from the status card. Bounded by the
 *    `resumeMaxAgeHours` setting counted from the interruption.
 *  - [PipelineRunStatus.WAITING_APPROVAL] / [PipelineRunStatus.WAITING_CLARIFICATION]
 *    / [PipelineRunStatus.WAITING_CEILING] with a parked pending-interaction
 *    record — the run waits for a response it cannot hold in memory; the
 *    decision use cases resume it after recording the user's answer. Bounded by
 *    the `backgroundApprovalWindowHours` setting counted from the park. The
 *    ceiling case differs from the other two in only one way, and it is the
 *    caller's business rather than this use case's: the grant that makes the
 *    resumed run able to take another step must already be on the record when
 *    the resume is enqueued, or the rebuilt ledger breaches on its first node
 *    and parks again.
 *
 * **Run tree.** The acted-on run may be a sub-pipeline run (a background HITL
 * park raised *inside* a sub-pipeline). Resume always re-enqueues the **root**
 * of the tree — a sub-run is continued internally by its parent `PIPELINE` node
 * when the root replays down to it, never as a standalone task. The window
 * precondition is evaluated on the acted-on run; the graph precondition on the
 * root (whose graph the re-enqueued task executes), and each deeper graph is
 * re-validated by the PIPELINE executor as the stack is rebuilt.
 *
 * Preconditions, in evaluation order:
 *  1. the acted-on run exists and is in a resumable status; a WAITING_* run
 *     must additionally hold a pending-interaction record — without one the
 *     wait is still live in-process and resume does not apply;
 *  2. the acted-on run is younger than its window (older → [ResumeOutcome.Expired]);
 *  3. the root run exists, carries the original user prompt (legacy rows →
 *     [ResumeOutcome.NotResumable]), and its pipeline still exists with a
 *     content hash equal to the one captured when the run started (deleted or
 *     edited graph → [ResumeOutcome.GraphChanged]; only a full restart helps);
 *  4. the guarded root status → QUEUED transition applies (a concurrent
 *     discard/resume loses the race → [ResumeOutcome.NotResumable]).
 */
class ResumePipelineRunUseCase @Inject constructor(
    private val pipelineRunRepository: PipelineRunRepository,
    private val pipelineRepository: PipelineRepository,
    private val runSettings: RunSettings,
    private val pendingInteractionRepository: PendingInteractionRepository,
    private val taskQueueManager: TaskQueueManager,
) {

    /**
     * Attempts to resume the run [runId].
     *
     * [runId] is the run the user / notification acted on — for a process-death
     * interruption that is the (top-level) run shown on the status card; for a
     * background HITL park it is the parked run carrying the pending
     * interaction, which may be a **sub-pipeline run**. In either case the run
     * actually re-enqueued is the **root** of the tree: a sub-run is resumed
     * internally by its parent `PIPELINE` node when the root replays down to
     * it, never as a standalone task. The window precondition is checked on the
     * acted-on run (it carries the interruption timestamp / pending record); the
     * graph precondition is checked on the root (whose graph the re-enqueued
     * task executes — each deeper graph is re-validated by the PIPELINE executor
     * as the stack is rebuilt).
     *
     * @param runId Id of the persistent run record the resume was triggered on.
     * @return The typed outcome — [ResumeOutcome.Resumed] when the root run was
     *   re-enqueued, or the specific reason resume is unavailable.
     */
    suspend operator fun invoke(runId: String): ResumeOutcome {
        val acted = pipelineRunRepository.getRun(runId)
        if (acted == null || acted.status !in RESUMABLE_STATUSES) {
            // `NotResumable` is returned from five distinct preconditions and
            // reaches the user as one indistinguishable "nothing to resume".
            // When a park legitimately cannot be continued, which precondition
            // rejected it is the only thing that explains the run's fate — so
            // each site names itself.
            Timber.w(
                "Resume rejected for %s: acted run %s",
                runId,
                if (acted == null) "not found" else "status ${acted.status}",
            )
            return ResumeOutcome.NotResumable
        }
        // Window precondition on the acted-on run (interruption age, or the
        // background-approval window counted from the park).
        windowRejectionFor(acted)?.let { return it }

        // The run actually re-enqueued is the root of the tree; a sub-run is
        // continued internally by its parent PIPELINE node on replay.
        val root = resolveRoot(acted)
        if (root == null || root.status !in RESUMABLE_STATUSES || root.userPrompt == null || root.pipelineId == null) {
            Timber.w(
                "Resume rejected for %s: root %s",
                runId,
                when {
                    root == null -> "not resolvable from parentRunId chain"
                    root.status !in RESUMABLE_STATUSES -> "status ${root.status} (id=${root.id})"
                    root.userPrompt == null -> "carries no userPrompt (id=${root.id})"
                    else -> "carries no pipelineId (id=${root.id})"
                },
            )
            return ResumeOutcome.NotResumable
        }
        // Graph precondition on the root graph the re-enqueued task executes.
        graphRejectionFor(root)?.let { return it }

        // The guarded transition is the concurrency gate: of two racing resume
        // taps (or a resume racing a discard) exactly one wins.
        return if (pipelineRunRepository.markResumed(root.id, root.status)) {
            taskQueueManager.enqueueTask(
                AgentTask(
                    id = root.id,
                    sessionId = root.sessionId,
                    prompt = root.userPrompt,
                    pipelineId = root.pipelineId,
                    origin = root.origin,
                    isResume = true,
                ),
            )
            ResumeOutcome.Resumed
        } else {
            Timber.w(
                "Resume rejected for %s: guarded transition from %s lost the race (root=%s)",
                runId,
                root.status,
                root.id,
            )
            ResumeOutcome.NotResumable
        }
    }

    /**
     * Resolves the root run of [acted]'s tree: [acted] itself when it is
     * already top-level, otherwise the run reached by walking
     * [PipelineRun.parentRunId] up. `null` when the root record cannot be
     * loaded (a missing chain link, or a store failure).
     *
     * @param acted The run the resume was triggered on.
     * @return The root run record, or `null`.
     */
    private suspend fun resolveRoot(acted: PipelineRun): PipelineRun? {
        val rootId = pipelineRunRepository.getRootRunId(acted.id) ?: return null
        return if (rootId == acted.id) acted else pipelineRunRepository.getRun(rootId)
    }

    /**
     * Evaluates the graph-identity precondition (item 3 of the class contract)
     * against the root run record whose graph the re-enqueued task runs.
     *
     * @param run The root resume candidate.
     * @return [ResumeOutcome.GraphChanged] when the graph was deleted or edited,
     *   or `null` when the recorded hash still matches.
     */
    private suspend fun graphRejectionFor(run: PipelineRun): ResumeOutcome? {
        val recordedHash = run.graphContentHash ?: return ResumeOutcome.GraphChanged
        val graph = run.pipelineId?.let { pipelineRepository.getPipelineById(it) }
            ?: return ResumeOutcome.GraphChanged
        return if (graph.contentHash() != recordedHash) ResumeOutcome.GraphChanged else null
    }

    /**
     * Applies the time-window precondition matching the run's status: the
     * interruption age window for INTERRUPTED runs, the background-approval
     * window (counted from the park) for persistently waiting runs.
     *
     * @param run The resume candidate.
     * @return The rejection outcome, or `null` when the run is within its window.
     */
    private suspend fun windowRejectionFor(run: PipelineRun): ResumeOutcome? {
        if (run.status == PipelineRunStatus.INTERRUPTED) {
            val maxAgeHours = runSettings.resumeMaxAgeHours.first()
            val interruptedAt = run.finishedAt ?: run.startedAt
            if (System.currentTimeMillis() - interruptedAt > maxAgeHours * MILLIS_PER_HOUR) {
                return ResumeOutcome.Expired
            }
            return null
        }
        // WAITING_* park: without a pending record the wait is still live in
        // this process — there is no parked state to resume.
        val pending = pendingInteractionRepository.getForRun(run.id) ?: run {
            Timber.w("Resume rejected for %s: WAITING_* run holds no pending-interaction record", run.id)
            return ResumeOutcome.NotResumable
        }
        val windowHours = runSettings.backgroundApprovalWindowHours.first()
        return if (System.currentTimeMillis() - pending.requestedAt > windowHours * MILLIS_PER_HOUR) {
            ResumeOutcome.Expired
        } else {
            null
        }
    }

    private companion object {
        /** Milliseconds in one hour, for the resume-window age check. */
        const val MILLIS_PER_HOUR: Long = 3_600_000L

        /** Statuses a run may be resumed from. */
        val RESUMABLE_STATUSES = setOf(
            PipelineRunStatus.INTERRUPTED,
            PipelineRunStatus.WAITING_APPROVAL,
            PipelineRunStatus.WAITING_CLARIFICATION,
            PipelineRunStatus.WAITING_CEILING,
        )
    }
}

/**
 * Typed outcome of a [ResumePipelineRunUseCase] attempt. The UI maps each
 * variant to its own user-facing message, so the variants carry no text.
 */
sealed class ResumeOutcome {
    /** The run was re-enqueued; the live state flow of its session will pick it up. */
    data object Resumed : ResumeOutcome()

    /**
     * The pipeline graph was deleted or edited after the run started — the
     * checkpoint is invalid and only a full restart of the task can help.
     */
    data object GraphChanged : ResumeOutcome()

    /**
     * The run is outside its time window: an interruption older than the
     * `resumeMaxAgeHours` setting, or a parked HITL request older than the
     * `backgroundApprovalWindowHours` setting.
     */
    data object Expired : ResumeOutcome()

    /**
     * The run is not in a resumable state: missing, in a non-resumable
     * status (e.g. a concurrent resume or discard won the race), recorded
     * without the original user prompt, or persistently waiting without a
     * parked pending-interaction record.
     */
    data object NotResumable : ResumeOutcome()
}
