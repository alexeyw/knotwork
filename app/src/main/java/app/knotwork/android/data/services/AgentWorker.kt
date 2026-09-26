package app.knotwork.android.data.services

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.knotwork.android.R
import app.knotwork.android.domain.constants.NotificationChannels
import app.knotwork.android.domain.constants.NotificationIds
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.ChatSession
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.repositories.BackgroundPromptRepository
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.services.ScheduledTaskKind
import app.knotwork.android.domain.services.ScheduledTaskNotifier
import app.knotwork.android.domain.services.ScheduledTaskTag
import app.knotwork.android.domain.usecases.AgentOrchestratorUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import java.util.UUID

/**
 * Worker that executes a scheduled agent task in the background through the
 * exact same path as an interactive chat message.
 *
 * The prompt is enqueued via [AgentOrchestratorUseCase.enqueueScheduled] —
 * `TaskQueueManager` → `GraphExecutionEngine` — so everything the engine
 * persists for interactive runs (the user message, intermediate `isFinal =
 * false` node messages, the final `isFinal = true` answer, the run record,
 * the trace) lands identically for scheduled runs: opening the bound session
 * later shows the conversation as if the run had happened on screen.
 *
 * Completion is tracked through the persistent `pipeline_runs` record rather
 * than the per-session state flow: the flow replays its latest state on
 * subscription, so a worker firing into a session with an earlier finished
 * run would mistake the stale terminal state for its own. The run id returned
 * by `enqueueScheduled` carries unambiguous identity.
 *
 * Outcome announcement is delegated to [ScheduledTaskNotifier]
 * ("Task completed" / "Task failed" with a deep-link into the session).
 * Cancelled and interrupted runs are not announced — the user (or the system)
 * already intervened.
 */
@HiltWorker
class AgentWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val agentOrchestratorUseCase: AgentOrchestratorUseCase,
    private val chatRepository: ChatRepository,
    private val pipelineRunRepository: PipelineRunRepository,
    private val scheduledTaskNotifier: ScheduledTaskNotifier,
    private val llmEngine: LlmInferenceEngine,
    private val backgroundPrompts: BackgroundPromptRepository,
    private val taskScheduler: WorkManagerTaskScheduler,
    private val workManager: WorkManager,
) : CoroutineWorker(context, workerParams) {

    companion object {
        /**
         * Input-data key carrying the id of the run's prompt in
         * [BackgroundPromptRepository] — the prompt itself never goes into the
         * runtime's unencrypted store.
         */
        const val KEY_PROMPT_ID = "agent_prompt_id"

        /**
         * Input-data key: `true` when the stored prompt serves every run of a
         * recurring task, `false` (or absent) when it belongs to this one run and
         * is dropped once the run is enqueued.
         */
        const val KEY_PROMPT_REUSED = "agent_prompt_reused"

        /**
         * Input-data key under which releases before the prompt moved out of the
         * runtime put the prompt text itself. Read only for such a request, which
         * may still be queued after the update.
         */
        const val KEY_PROMPT = "agent_prompt"

        /**
         * Input-data key carrying the id of the chat session the run should
         * land its result in. Optional: when absent (work enqueued before the
         * key existed) or when the session was deleted before the task fired,
         * the worker creates a fresh auto-named session instead.
         */
        const val KEY_SESSION_ID = "agent_session_id"

        /**
         * Input-data key carrying the explicit pipeline binding the run must
         * execute against. Optional: when absent the run resolves the
         * application default (the `schedule_task` tool path). Entry surfaces
         * (the Quick Settings tile) set it to the user's bound pipeline.
         */
        const val KEY_PIPELINE_ID = "agent_pipeline_id"

        /**
         * Input-data key carrying the [RunOrigin] name attributed to the run.
         * Optional: defaults to [RunOrigin.SCHEDULER] when absent or unparsable.
         */
        const val KEY_ORIGIN = "agent_origin"

        /**
         * Input-data key carrying a pre-minted run id for the enqueued run.
         * Optional: when absent the orchestrator mints a fresh id. A trigger
         * fire supplies it so its trigger-evaluation journal row (written the
         * moment the trigger fires, before this worker even starts) already
         * carries the id the run's terminal outcome is later attributed back by.
         */
        const val KEY_RUN_ID = "agent_run_id"

        /** Progress key exposing the node currently executing (or the run status). */
        const val KEY_CURRENT_STAGE = "current_stage"

        /** Maximum number of prompt characters used for the auto-generated session name. */
        private const val SESSION_NAME_PROMPT_LENGTH = 40
    }

    override suspend fun doWork(): Result {
        val promptId = inputData.getString(KEY_PROMPT_ID)
        val prompt = if (promptId != null) {
            backgroundPrompts.get(promptId)
        } else {
            inputData.getString(KEY_PROMPT)
        }

        if (prompt.isNullOrBlank()) {
            // A stored prompt is missing when the request was cancelled and pruned
            // or the data was erased — there is nothing left to run.
            Timber.e("AgentWorker failed: no prompt to run.")
            return Result.failure()
        }
        val legacyPeriodic = promptId == null && migrateLegacyPeriodic(prompt)

        promoteToForeground()

        // Pre-enqueue failures (session lookup, queue write) may be transient
        // infrastructure problems — let WorkManager retry them.
        val sessionId: String
        val runId: String
        try {
            sessionId = resolveSession(inputData.getString(KEY_SESSION_ID), prompt)
            runId = agentOrchestratorUseCase.enqueueScheduled(
                sessionId = sessionId,
                userPrompt = prompt,
                pipelineId = inputData.getString(KEY_PIPELINE_ID),
                origin = parseOrigin(inputData.getString(KEY_ORIGIN)),
                runId = inputData.getString(KEY_RUN_ID),
            )
            Timber.d("AgentWorker enqueued background run %s into session %s", runId, sessionId)
            // The prompt now lives in the run's user message; a one-time run's
            // stored copy has served its purpose. Dropped only after the enqueue
            // succeeded, so a retry still finds it.
            if (promptId != null && !inputData.getBoolean(KEY_PROMPT_REUSED, false)) backgroundPrompts.delete(promptId)
        } catch (e: CancellationException) {
            // WorkManager cancels the worker by cancelling this coroutine —
            // mapping the cancellation to `retry()` would resurrect a job the
            // system (or the user) just killed.
            throw e
        } catch (e: Exception) {
            Timber.e(e, "AgentWorker failed before the run was enqueued.")
            return Result.retry()
        }

        // Post-enqueue, the run executes in the singleton task queue
        // independently of this coroutine: the user message is already
        // persisted, so a worker retry would duplicate it. Failures past this
        // point are logged but never mapped to retry().
        try {
            val run = awaitTerminalRun(sessionId, runId)
            announceOutcome(run)
            releaseEngineIfUnowned()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "AgentWorker failed while tracking run %s.", runId)
        }
        // Not awaited: stopping this request while its own doWork still runs
        // would cancel the run above; the cancellation lands once it returns.
        if (legacyPeriodic) workManager.cancelWorkById(id)
        return Result.success()
    }

    /**
     * Moves a recurring task scheduled by an earlier release — whose request
     * carries the prompt text — onto the encrypted store: a replacement is
     * enqueued with only the prompt's id, first due one interval from now.
     *
     * The runtime does not expose a queued request's input, so this, the legacy
     * request's own run, is the only point where its prompt can be read. A
     * one-time legacy request needs nothing: it runs once and its row is pruned.
     *
     * @param prompt The prompt the legacy request carried.
     * @return `true` when a replacement was enqueued and this request must be
     *   cancelled once its run is done; `false` when it is not a recurring task
     *   or the replacement could not be enqueued (it then keeps running as is).
     */
    private suspend fun migrateLegacyPeriodic(prompt: String): Boolean {
        val label = ScheduledTaskTag.parse(tags) ?: return false
        if (label.kind != ScheduledTaskKind.PERIODIC || label.promptId != null) return false
        return try {
            taskScheduler.migrateLegacyPeriodic(prompt, label.intervalHours, label.sessionId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not move a recurring task's prompt to the encrypted store")
            false
        }
    }

    /**
     * Builds the foreground promotion descriptor required for long-running
     * workers: the dedicated worker notification on the (idempotently
     * registered) foreground channel, typed `specialUse` — the same FGS
     * subtype the app's own `AgentForegroundService` is approved for.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureForegroundChannel()
        val notification = AgentForegroundNotification.build(
            context = applicationContext,
            contentTitle = applicationContext.getString(R.string.notifications_scheduled_running_title),
            contentText = applicationContext.getString(R.string.notifications_scheduled_running_body),
            contentIntent = AgentForegroundNotification.launchContentIntent(applicationContext),
        )
        return ForegroundInfo(
            NotificationIds.AGENT_WORKER_FOREGROUND,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    /**
     * Tries to promote the worker to a foreground service so a long inference
     * is not subject to the ~10-minute background-job runtime cap. The
     * promotion is best-effort: when the app is deep in the background the
     * system may forbid the foreground-service start
     * (`ForegroundServiceStartNotAllowedException`, an [IllegalStateException]
     * subtype) — the worker then simply continues non-foreground within the
     * standard quota.
     */
    private suspend fun promoteToForeground() {
        try {
            setForeground(getForegroundInfo())
        } catch (e: CancellationException) {
            // CancellationException extends IllegalStateException — re-throw it
            // before the broad catch so a worker stop is never swallowed.
            throw e
        } catch (e: IllegalStateException) {
            Timber.w(e, "AgentWorker could not enter foreground; continuing within background quota.")
        }
    }

    /**
     * Registers the foreground-status channel the promotion notification posts
     * to. Normally `AgentForegroundService` registers it, but in a headless
     * process (woken by WorkManager, no activity ever created) that service
     * never runs — without this call the promotion notification would target
     * an unregistered channel.
     */
    private fun ensureForegroundChannel() {
        val channel = NotificationChannelCompat.Builder(
            NotificationChannels.AGENT_FOREGROUND,
            NotificationManager.IMPORTANCE_LOW,
        )
            .setName(applicationContext.getString(R.string.notifications_agent_foreground_channel_name))
            .build()
        NotificationManagerCompat.from(applicationContext).createNotificationChannel(channel)
    }

    /**
     * Maps the persisted origin name back to a [RunOrigin], falling back to
     * [RunOrigin.SCHEDULER] for an absent or unrecognised value (legacy work
     * enqueued before the key existed).
     */
    private fun parseOrigin(raw: String?): RunOrigin =
        RunOrigin.entries.firstOrNull { it.name == raw } ?: RunOrigin.SCHEDULER

    /**
     * Resolves the chat session the run lands in. The requested session is used
     * when it still exists; otherwise a session is created, auto-named from the
     * truncated task prompt with an explicit scheduled-source mark.
     *
     * When a session id was explicitly requested but its row is gone (deleted
     * between scheduling and the constraint-deferred run firing), the
     * replacement is created **under that same id** rather than a random one, so
     * any binding or already-posted deep-link that points at the requested id
     * stays valid and future runs keep accumulating in one conversation. A
     * legacy task with no requested id falls back to a fresh UUID.
     */
    private suspend fun resolveSession(requestedSessionId: String?, prompt: String): String {
        if (requestedSessionId != null && chatRepository.sessionExists(requestedSessionId)) {
            return requestedSessionId
        }
        val session = ChatSession.create(
            name = applicationContext.getString(
                R.string.scheduled_session_name,
                prompt.take(SESSION_NAME_PROMPT_LENGTH),
            ),
            id = requestedSessionId ?: UUID.randomUUID().toString(),
        )
        chatRepository.saveSession(session)
        Timber.d("AgentWorker created session %s for orphaned scheduled task.", session.id)
        return session.id
    }

    /**
     * Suspends until the run identified by [runId] reaches a terminal status,
     * mirroring node progress into WorkManager's progress data along the way.
     */
    private suspend fun awaitTerminalRun(sessionId: String, runId: String): PipelineRun =
        pipelineRunRepository.observeRunsForSession(sessionId)
            .mapNotNull { runs -> runs.firstOrNull { it.id == runId } }
            .onEach { run ->
                setProgress(
                    Data.Builder()
                        .putString(KEY_CURRENT_STAGE, run.currentNodeId ?: run.status.name)
                        .build(),
                )
            }
            .first { it.status.isTerminal }

    /** Posts the outcome notification for terminal statuses that warrant one. */
    private suspend fun announceOutcome(run: PipelineRun) {
        when (run.status) {
            PipelineRunStatus.COMPLETED ->
                scheduledTaskNotifier.notifyCompleted(run.sessionId, finalAnswerPreview(run))
            PipelineRunStatus.FAILED -> {
                // A run the app ended on purpose is announced as what it was.
                // `errorMessage` here is the diagnostic, not user copy, so
                // posting it under the failure title would have shown a person
                // "step-ceiling: 15/15 steps" and called it a failure.
                val reason = run.terminationReason
                if (reason != null) {
                    scheduledTaskNotifier.notifyTerminated(
                        sessionId = run.sessionId,
                        kind = reason,
                        runLabel = chatRepository.getSessionById(run.sessionId)?.name.orEmpty(),
                        stepsSpent = run.stepsSpent,
                        tokensSpent = run.tokensSpent,
                    )
                } else {
                    scheduledTaskNotifier.notifyFailed(
                        run.sessionId,
                        run.errorMessage ?: applicationContext.getString(R.string.notifications_task_failed_title),
                    )
                }
            }
            else -> Unit // CANCELLED / INTERRUPTED: the user or the system already intervened.
        }
    }

    /**
     * Returns the first non-blank line of [run]'s final agent answer, used as
     * the completion-notification body.
     *
     * Scoped to messages no later than the run's [PipelineRun.finishedAt]: a
     * trigger's recurring fires now share one bound session, so a *later* run
     * may already have appended its own final answer by the time this preview is
     * built. Bounding by the finish time picks this run's answer rather than a
     * subsequent run's. Falls back to the latest final answer when the finish
     * time is unknown.
     */
    private suspend fun finalAnswerPreview(run: PipelineRun): String {
        val upperBound = run.finishedAt
        val finalMessage = chatRepository.getMessagesForSession(run.sessionId).first()
            .lastOrNull {
                it.role == Role.AGENT &&
                    it.isFinal &&
                    it.writtenOnThisDevice &&
                    (upperBound == null || it.timestamp <= upperBound)
            }
        return finalMessage?.content
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
    }

    /**
     * Unloads the LLM engine after the run when nothing else owns its
     * lifecycle. With `AgentForegroundService` alive its `AgentIdleManager`
     * unloads the model on idle timeout; in a headless process no such owner
     * exists, and leaving the model resident would pin hundreds of megabytes
     * until the OS kills the process. Skipped while other sessions still have
     * active runs in the queue.
     */
    private suspend fun releaseEngineIfUnowned() {
        if (AgentForegroundService.isRunning) return
        if (pipelineRunRepository.observeActiveRunSessionIds().first().isNotEmpty()) return
        if (llmEngine.isInitialized) {
            Timber.d("AgentWorker unloading LLM engine after scheduled run (no foreground service).")
            llmEngine.unload()
        }
    }
}
