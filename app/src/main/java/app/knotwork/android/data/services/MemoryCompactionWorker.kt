package app.knotwork.android.data.services

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.knotwork.android.domain.engine.TaskQueueManager
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.repositories.MemorySettings
import app.knotwork.android.domain.usecases.MemoryCompactionUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Background worker that runs one long-term memory compaction pass.
 *
 * Scheduled by [MemoryCompactionScheduler] both as a daily periodic job
 * (constrained to charging + idle so it never costs the user battery) and as an
 * out-of-schedule one-off when the chunk count crosses the hard limit. The
 * worker itself is deliberately thin: it gates on the
 * [MemorySettings.memoryCompactionEnabled] toggle and delegates the actual
 * clustering and consolidation to [MemoryCompactionUseCase] — mirroring how
 * [AgentWorker] delegates to its orchestrator use case.
 *
 * **Engine coordination.** Compaction runs the local model, and
 * [app.knotwork.android.domain.engine.LlmInferenceEngine] allows only one active
 * conversation — a concurrent generation would tear down or corrupt an
 * in-flight foreground response. The out-of-schedule (hard-limit) path runs
 * with relaxed constraints and so can fire while the user is actively chatting,
 * so the worker checks [TaskQueueManager.globalState] and **defers**
 * ([Result.retry]) while a pipeline is mid-run, mirroring the busy gate in
 * [app.knotwork.android.domain.services.MemoryAutoExtractionCoordinator]. The
 * periodic path is already charging + idle, but the same guard covers it
 * harmlessly.
 *
 * @property memoryCompactionUseCase The compaction pass itself.
 * @property memorySettings Source of the compaction toggle, re-read on every
 *   run so a user disabling the feature cancels an already-queued job.
 * @property taskQueueManager Source of the agent-busy signal used to defer the
 *   pass while a foreground pipeline is generating on the shared engine.
 */
@HiltWorker
class MemoryCompactionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val memoryCompactionUseCase: MemoryCompactionUseCase,
    private val memorySettings: MemorySettings,
    private val taskQueueManager: TaskQueueManager,
) : CoroutineWorker(context, workerParams) {

    /**
     * Runs the compaction pass.
     *
     * @return [Result.success] when the toggle is off (no-op) or the pass
     *   completes; [Result.retry] when the pass throws unexpectedly, so
     *   WorkManager re-attempts it under the same constraints.
     */
    override suspend fun doWork(): Result {
        if (!memorySettings.memoryCompactionEnabled.first()) {
            Timber.tag(TAG).d("Memory compaction disabled; skipping run")
            return Result.success()
        }

        // Never touch the shared inference engine while a foreground pipeline is
        // generating — defer and let WorkManager re-attempt once it goes idle.
        if (isAgentBusy()) {
            Timber.tag(TAG).d("Agent busy; deferring memory compaction")
            return Result.retry()
        }

        return try {
            val outcome = memoryCompactionUseCase()
            Timber.tag(TAG).d(
                "Memory compaction finished: %d clusters, %d chunks merged into %d " +
                    "(%d clusters rejected, %d chunks kept unverified)",
                outcome.clustersProcessed,
                outcome.chunksConsolidated,
                outcome.chunksCreated,
                outcome.clustersRejected,
                outcome.chunksKeptUnverified,
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Memory compaction run failed")
            Result.retry()
        }
    }

    /**
     * `true` when the agent is mid-run on the shared inference engine — i.e.
     * [TaskQueueManager.globalState] is any non-terminal state. Mirrors the idle
     * predicate used by `AgentIdleManager` / `MemoryAutoExtractionCoordinator`
     * (idle = `Idle` / `Completed` / `Error`); everything else (loading,
     * streaming, awaiting approval, …) means a foreground generation could be
     * holding the engine's single conversation.
     */
    private fun isAgentBusy(): Boolean = when (taskQueueManager.globalState.value) {
        is AgentOrchestratorState.Idle,
        is AgentOrchestratorState.Completed,
        is AgentOrchestratorState.Error,
        -> false

        else -> true
    }

    companion object {
        private const val TAG = "MemoryCompaction"

        /** Unique work name for the daily periodic compaction job. */
        const val UNIQUE_PERIODIC_NAME = "memory-compaction-periodic"

        /** Unique work name for the out-of-schedule hard-limit compaction job. */
        const val UNIQUE_IMMEDIATE_NAME = "memory-compaction-immediate"
    }
}
