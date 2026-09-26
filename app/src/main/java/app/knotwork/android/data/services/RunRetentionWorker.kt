package app.knotwork.android.data.services

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.knotwork.android.domain.services.TaskScheduler
import app.knotwork.android.domain.usecases.CleanupPipelineRunsUseCase
import app.knotwork.android.domain.usecases.CleanupTriggerJournalUseCase
import app.knotwork.android.domain.usecases.automation.CleanupExternalAutomationJournalUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Background worker that runs one database-retention maintenance pass.
 *
 * Scheduled by [RunRetentionScheduler] as a daily periodic job inside the
 * same charging + idle maintenance window as [MemoryCompactionWorker]. The
 * worker is deliberately thin: it delegates each policy to a dedicated use case
 * and only translates the combined outcome into a WorkManager result. It runs
 * two independent retention passes over derived, at-rest data:
 *  - [CleanupPipelineRunsUseCase] — persisted pipeline runs and their traces
 *    (per-session count, max age, the terminal-only invariant), and
 *  - [CleanupTriggerJournalUseCase] — the trigger-evaluation journal (age window
 *    + hard record cap), and
 *  - [CleanupExternalAutomationJournalUseCase] — the external-automation request
 *    journal (same two limits). This pass is the one that is not merely tidiness:
 *    its table's write rate is set by whatever third-party app is installed, and is
 *    highest while the contract is switched **off**, because a refusal is an event
 *    too.
 *
 * No agent-busy gate is needed: the run pass deletes **terminal** runs only, so
 * it can never race a run that is still executing or waiting, and the journal
 * pass touches a diagnostic table no live run depends on; both work on the
 * database rather than the shared inference engine.
 *
 * @property cleanupPipelineRunsUseCase The pipeline-run retention pass.
 * @property cleanupTriggerJournalUseCase The trigger-journal retention pass.
 * @property cleanupExternalAutomationJournalUseCase The external-request journal
 *   retention pass.
 * @property taskScheduler Prunes the stored prompts of background runs cancelled
 *   before they ran.
 */
@HiltWorker
class RunRetentionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val cleanupPipelineRunsUseCase: CleanupPipelineRunsUseCase,
    private val cleanupTriggerJournalUseCase: CleanupTriggerJournalUseCase,
    private val cleanupExternalAutomationJournalUseCase: CleanupExternalAutomationJournalUseCase,
    private val taskScheduler: TaskScheduler,
) : CoroutineWorker(context, workerParams) {

    /**
     * Runs every retention pass.
     *
     * @return [Result.success] when the passes complete; [Result.retry] when one
     *   throws unexpectedly, so WorkManager re-attempts under the same constraints.
     *   Every pass absorbs ordinary storage trouble internally and reports zero, so
     *   a retry means a programming error rather than a busy database.
     */
    override suspend fun doWork(): Result = try {
        val outcome = cleanupPipelineRunsUseCase()
        val deletedJournalRows = cleanupTriggerJournalUseCase()
        val deletedExternalRows = cleanupExternalAutomationJournalUseCase()
        val prunedPrompts = taskScheduler.pruneOrphanPrompts()
        Timber.tag(TAG).d(
            "Retention finished: %d runs deleted, %d legacy trace rows deleted, " +
                "%d trigger-journal rows deleted, %d external-request rows deleted, %d orphan prompts pruned",
            outcome.deletedRuns,
            outcome.deletedLegacyTraceRows,
            deletedJournalRows,
            deletedExternalRows,
            prunedPrompts,
        )
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Retention pass failed")
        Result.retry()
    }

    companion object {
        private const val TAG = "RunRetention"

        /** Unique work name for the daily periodic retention job. */
        const val UNIQUE_PERIODIC_NAME = "run-retention-periodic"
    }
}
