package app.knotwork.android.domain.repositories

import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunCeilingAxis
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunSpend
import app.knotwork.android.domain.models.RunTerminationReason
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for the persistent pipeline-run records.
 *
 * Backed by the encrypted `pipeline_runs` table, this is the durability layer
 * that lets the application know a run existed — and where it stopped — after
 * the process hosting the in-memory orchestrator state dies. Writers follow a
 * strict split: the task queue owns creation, the RUNNING transition, and all
 * terminal transitions; the execution engine owns per-node progress
 * ([updateCurrentNode]) and the suspension statuses
 * ([PipelineRunStatus.WAITING_APPROVAL] / [PipelineRunStatus.WAITING_CLARIFICATION]
 * / [PipelineRunStatus.WAITING_CEILING]).
 *
 * **Best-effort contract.** Run records are an observability layer, never a
 * correctness dependency of the execution they describe. Implementations must
 * absorb storage and data-corruption failures: writes log and return, reads
 * log and degrade to `null` / empty results. The only exceptions allowed to
 * escape are `CancellationException` (cooperative cancellation must survive)
 * and [IllegalArgumentException] for caller contract violations (see
 * [finishRun]). Callers therefore never need their own guards.
 *
 * All status mutations are guarded: once a run reaches a terminal status
 * (see [PipelineRunStatus.isTerminal]) further updates are silently ignored,
 * so racing writers (e.g. an engine status write racing the queue's
 * `finally`-side cancellation write) can never resurrect a finished run.
 */
interface PipelineRunRepository {

    /**
     * Persists a freshly enqueued run in [PipelineRunStatus.QUEUED] status and
     * registers the run as **owned by the current process** (see
     * [getOrphanedRuns]). Inserting an id that already has a row is a silent
     * no-op — the existing record (whatever its status) is never overwritten,
     * so racing creators and re-deliveries cannot resurrect a settled run.
     *
     * @param run The run record to insert. Its [PipelineRun.pipelineId] and
     *   [PipelineRun.graphContentHash] are typically `null` at this point —
     *   both are resolved when the run starts (see [markRunning]).
     */
    suspend fun createRun(run: PipelineRun)

    /**
     * Transitions the run to [PipelineRunStatus.RUNNING], recording the
     * resolved pipeline and the content hash of the graph about to execute.
     * No-op when the run is already terminal.
     *
     * @param runId Id of the run to update.
     * @param pipelineId Id of the pipeline resolved for this run.
     * @param graphContentHash Content hash of the resolved graph (see
     *   `PipelineGraph.contentHash`), captured for checkpoint invalidation.
     */
    suspend fun markRunning(runId: String, pipelineId: String, graphContentHash: String)

    /**
     * Updates the run's lifecycle status. No-op when the run is already
     * terminal. Use [finishRun] for terminal transitions — this method is for
     * the active-side statuses (RUNNING and the two WAITING_* suspensions).
     *
     * @param runId Id of the run to update.
     * @param status The new non-terminal status.
     */
    suspend fun updateStatus(runId: String, status: PipelineRunStatus)

    /**
     * Records the node the engine is about to execute, so an interrupted run
     * can report where it stopped. No-op when the run is already terminal.
     *
     * @param runId Id of the run to update.
     * @param nodeId Id of the graph node that just started executing.
     */
    suspend fun updateCurrentNode(runId: String, nodeId: String)

    /**
     * Transitions the run to a terminal [status], stamping `finishedAt` and
     * the optional [errorMessage]. Idempotent: when the run is already
     * terminal the call is silently ignored, so the queue's unconditional
     * `finally`-side write never overwrites an earlier COMPLETED/FAILED.
     *
     * @param runId Id of the run to finish.
     * @param status The terminal status to write. Must satisfy
     *   [PipelineRunStatus.isTerminal] — a non-terminal status is a caller
     *   bug and throws [IllegalArgumentException] (not absorbed).
     * @param errorMessage Failure or interruption reason; `null` for
     *   successful or cancelled runs.
     * @param reason The typed cause when the app itself decided to end the run
     *   — a ceiling, the stuck-detector, the silence watchdog, an expired approval window, a
     *   changed graph, a dead process, a user discard. `null` for a completion
     *   and for an ordinary node failure, which have no entry in that
     *   vocabulary. Consumers that need to tell a protective stop from a
     *   product failure read this instead of matching [errorMessage] by
     *   string, which is what they had to do before it existed.
     */
    suspend fun finishRun(
        runId: String,
        status: PipelineRunStatus,
        errorMessage: String? = null,
        reason: RunTerminationReason? = null,
    )

    /**
     * Writes a run tree's accumulated spend onto its root record, so a ceiling
     * survives the run being parked and resumed.
     *
     * Called as the tree executes, from whatever depth is running: the ledger
     * knows its root, and a sub-pipeline charges the same record its parent
     * does. Best-effort — a storage failure loses accuracy, never the run.
     *
     * @param rootRunId Id of the run at the root of the tree.
     * @param stepsSpent Node executions charged to the tree so far.
     * @param tokensSpent Tokens charged to the tree so far.
     */
    suspend fun recordSpend(rootRunId: String, stepsSpent: Int, tokensSpent: Int)

    /**
     * Reads back the spend already charged to a run tree.
     *
     * The engine calls this once, at the top of a run, to seed its ledger:
     * zero for a fresh run, and whatever the previous attempt spent for a
     * resumed one. Degrades to zero on a store failure (best-effort contract),
     * which makes the ceiling bind late rather than refusing to run.
     *
     * @param rootRunId Id of the run at the root of the tree.
     * @return The persisted counters, zero when the row is missing.
     */
    suspend fun getSpend(rootRunId: String): RunSpend

    /**
     * Grants a run tree one more portion of the ceiling on [axis] — the durable
     * half of a user answering "continue" on a ceiling pause.
     *
     * Written to the record rather than to the ledger because the ledger the
     * question came from is usually gone: the run parked, its coroutine ended,
     * and the answer may arrive hours later in a process that has yet to build
     * one. The resumed run reads the grant back through [getSpend].
     *
     * The caller must have resolved [rootRunId] to the **root** of the tree —
     * a park can sit on a sub-pipeline run, but the counters live where the
     * spend does. Must be called *before* the resume is enqueued, or the
     * rebuilt ledger breaches again on its first node.
     *
     * Best-effort like the rest of this store, with a specific consequence
     * worth naming: a lost write means the resumed run stops again at the same
     * ceiling and asks again. The user answers twice; nothing runs past a limit
     * it was not granted.
     *
     * @param rootRunId Id of the run at the root of the tree.
     * @param axis The ceiling the user granted one more portion of.
     */
    suspend fun extendCeiling(rootRunId: String, axis: RunCeilingAxis)

    /**
     * Returns the run with [runId], or `null` when no such run exists (or the
     * store is unreadable — best-effort contract). Checkpoint resume uses it
     * to load and validate the one specific run being resumed.
     *
     * @param runId Id of the run to load.
     * @return The run record, or `null` when not found.
     */
    suspend fun getRun(runId: String): PipelineRun?

    /**
     * Returns every descendant run of [rootRunId] (its direct sub-pipeline
     * children, their children, and so on) in start order, **excluding**
     * [rootRunId] itself. Nesting is bounded by the runtime depth ceiling, so
     * the tree is shallow and the walk terminates quickly. Used to project the
     * nested console (merge each run's trace by timestamp) and by tests
     * asserting the run tree after a nested execution. Degrades to an empty
     * list on a store failure (best-effort contract).
     *
     * @param rootRunId Id of the run whose descendant sub-tree to collect.
     * @return The descendant runs, nearest-first by start time.
     */
    suspend fun getDescendantRuns(rootRunId: String): List<PipelineRun>

    /**
     * Counts the top-level runs of [origin] that started at or after [sinceEpochMs]
     * — a nested pipeline's run is part of the run that started it and is not counted.
     *
     * Exists for the scheduling tool's runaway guard, which has to answer "how
     * often is this actually firing?" — a question the queue cannot answer,
     * since a self-re-scheduling task never has more than one item queued.
     *
     * Degrades to `0` on a storage failure (fail-open): a diagnostic count must
     * never be the reason a legitimate task cannot be scheduled.
     *
     * @param origin The run origin to count.
     * @param sinceEpochMs Inclusive lower bound on the run's start, epoch-millis.
     * @return The number of matching top-level runs, or `0` when the read failed.
     */
    suspend fun countRootRunsByOriginSince(origin: RunOrigin, sinceEpochMs: Long): Int

    /**
     * Walks the [PipelineRun.parentRunId] links up from [runId] to the root of
     * its run tree and returns the root id. A top-level run is its own root.
     * Returns `null` when [runId] does not exist (or the store is unreadable —
     * best-effort contract). Resume and park-settlement act on the root: a
     * sub-run is never resumed or failed standalone, only through its root.
     *
     * @param runId Id of any run in the tree.
     * @return The root run id, or `null` when [runId] is missing.
     */
    suspend fun getRootRunId(runId: String): String?

    /**
     * Flips a resumable run back to [PipelineRunStatus.QUEUED] for checkpoint
     * resume, clearing the `finishedAt` / `errorMessage` markers a sweep may
     * have stamped, and re-registers the run as owned by the current process
     * (see [getOrphanedRuns]) — the resumed execution is hosted here, and a
     * second interruption must again be detectable.
     *
     * Two starting points are sanctioned: [PipelineRunStatus.INTERRUPTED]
     * (the terminal-exit transition next to [discardInterruptedRun]) and the
     * persistent waiting statuses ([PipelineRunStatus.WAITING_APPROVAL] /
     * [PipelineRunStatus.WAITING_CLARIFICATION] /
     * [PipelineRunStatus.WAITING_CEILING]) of a parked run whose pending
     * interaction was answered. The transition is guarded in SQL by
     * the expected [fromStatus]: a run in any other status is left untouched
     * and the method reports failure, which is what serialises racing
     * resume / discard attempts.
     *
     * @param runId Id of the run to resume.
     * @param fromStatus The exact status the row must currently hold.
     * @return `true` when the guarded [fromStatus] → QUEUED transition was
     *   applied; `false` when the run is missing, in a different status, or
     *   the store failed (best-effort contract).
     */
    suspend fun markResumed(runId: String, fromStatus: PipelineRunStatus): Boolean

    /**
     * Returns the most recently started non-terminal run of [sessionId], or
     * `null` when every run of the session already finished (or the store is
     * unreadable — best-effort contract). This is the entry point of the
     * chat reattach protocol.
     *
     * @param sessionId Id of the chat session to query.
     * @return The active run, or `null` when none is active.
     */
    suspend fun getActiveRunForSession(sessionId: String): PipelineRun?

    /**
     * Returns the most recently started run of [sessionId] regardless of
     * status, or `null` when the session has never had a run (or the store
     * is unreadable — best-effort contract). The console replay path uses it
     * to pick the baseline trace when no run is currently active.
     *
     * @param sessionId Id of the chat session to query.
     * @return The latest run, or `null` when the session has no runs.
     */
    suspend fun getLatestRunForSession(sessionId: String): PipelineRun?

    /**
     * Observes all runs of [sessionId], most recently started first.
     *
     * @param sessionId Id of the chat session to observe.
     * @return A cold flow re-emitting the full run list on every change;
     *   storage failures degrade to an empty emission.
     */
    fun observeRunsForSession(sessionId: String): Flow<List<PipelineRun>>

    /**
     * Observes the set of session ids that currently own a non-terminal run
     * (QUEUED / RUNNING / WAITING_*). Powers the drawer thread-list
     * indicator: a session in the set renders an in-progress badge so the
     * user can see at a glance which background conversations are still
     * working. Deliberately a session-id projection rather than full run
     * records — the underlying table is written on every node transition,
     * and implementations deduplicate emissions so consumers only react
     * when the set itself changes (run started / run settled).
     *
     * @return A cold flow of the active session-id set, deduplicated;
     *   storage failures degrade to an empty emission.
     */
    fun observeActiveRunSessionIds(): Flow<Set<String>>

    /**
     * Discards an interrupted run: transitions it from
     * [PipelineRunStatus.INTERRUPTED] to [PipelineRunStatus.FAILED] with a
     * fixed "discarded by user" error message. This is the only sanctioned
     * terminal-to-terminal transition — the user explicitly dismissed the
     * resume offer, so the record must stop presenting itself as resumable.
     * Guarded in SQL: a run in any other status (including the other terminal
     * ones) is left untouched, so a racing resume or a stale UI action can
     * never corrupt a settled record.
     *
     * @param runId Id of the run to discard.
     */
    suspend fun discardInterruptedRun(runId: String)

    /**
     * Returns every non-terminal run that is **not owned by the current
     * process** — i.e. whose [createRun] happened in a process that has since
     * died. Such runs are orphans by definition: the in-memory machinery that
     * could finish or resume them (queue worker, suspension deferreds) died
     * with their process. Runs created by the live process are excluded no
     * matter their status, so the startup sweep can never interrupt a run
     * that is actively executing (e.g. kept alive by the foreground service
     * or a WorkManager worker while no Activity exists).
     *
     * WAITING_* runs from dead processes are included in the returned list;
     * the caller (the startup sweep) exempts the ones with a persisted
     * pending interaction — those are parked, not orphaned, and stay
     * resumable from their notification until the approval window expires.
     *
     * @return Runs whose owning process died mid-execution.
     */
    suspend fun getOrphanedRuns(): List<PipelineRun>

    /**
     * Applies the run-history retention policy: deletes every **terminal**
     * run ([PipelineRunStatus.isTerminal]) that either falls outside the
     * [keepPerSession] most recently started runs of its session or finished
     * before [maxAgeCutoffEpochMs]. Non-terminal runs — including WAITING_*
     * runs parked on a background approval or clarification — are never
     * deleted; their expiry is owned by the pending-interaction maintenance
     * pass, which settles them to FAILED first. Each deleted run's persisted
     * trace is removed atomically via the storage-level cascade.
     *
     * Best-effort like every other method here: a storage failure is logged
     * and reported as zero deletions.
     *
     * @param keepPerSession How many most-recent runs each session keeps.
     * @param maxAgeCutoffEpochMs Epoch millis; terminal runs finished before
     *   it are deleted regardless of the per-session count.
     * @return The total number of deleted runs, `0` when nothing qualified
     *   or the store failed.
     */
    suspend fun applyRetention(keepPerSession: Int, maxAgeCutoffEpochMs: Long): Int
}
