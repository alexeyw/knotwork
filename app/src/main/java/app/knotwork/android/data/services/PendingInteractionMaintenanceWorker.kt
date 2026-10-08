package app.knotwork.android.data.services

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.RunSettings
import app.knotwork.android.domain.usecases.NotifyStaleTriggersUseCase
import app.knotwork.android.domain.usecases.ParkedRunResumer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Background worker that expires parked HITL interactions whose
 * background-approval window elapsed unanswered.
 *
 * Scheduled by [PendingInteractionMaintenanceScheduler] as a periodic job
 * with deliberately relaxed constraints (battery-not-low only): unlike the
 * memory-compaction maintenance pass it runs no inference — a handful of
 * Room reads and notification cancels — and an expiry that waits days for a
 * charging-and-idle window would let "dead" runs linger far past their
 * window. Each expired park is settled through
 * [ParkedRunResumer.failExpiredPark]: the run record fails, the pending record
 * is deleted, and the notification is removed. The failure it records depends
 * on what the run was waiting for — an unanswered approval or question stopped
 * waiting for the user, while a run paused at a limit was simply never told to
 * carry on, and settles at that limit. Records whose run already
 * settled elsewhere are cleaned up by the same call — `finishRun` is a
 * guarded no-op on terminal records, so the pass doubles as zombie-record
 * collection.
 *
 * The expiry is lazy-checked at decision time too
 * ([app.knotwork.android.domain.usecases.SubmitApprovalDecisionUseCase]);
 * this pass is the backstop for parks the user never responds to at all.
 *
 * The same pass then tells the user about any trigger the phone has stopped
 * checking ([NotifyStaleTriggersUseCase]). It belongs here rather than in the
 * triggers' own periodic poll: a starved poll cannot report its own silence, and
 * this job runs on a different schedule with looser constraints. Its failure is
 * logged and does not fail the expiry pass.
 *
 * @property pendingInteractionRepository Source of the parked records.
 * @property runSettings Source of the `backgroundApprovalWindowHours`
 *   setting, re-read on every run so a changed window applies immediately.
 * @property parkedRunResumer Owner of the shared park-settlement semantics.
 * @property notifyStaleTriggers Announces triggers the phone has stopped checking.
 */
@HiltWorker
class PendingInteractionMaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val pendingInteractionRepository: PendingInteractionRepository,
    private val runSettings: RunSettings,
    private val parkedRunResumer: ParkedRunResumer,
    private val notifyStaleTriggers: NotifyStaleTriggersUseCase,
) : CoroutineWorker(context, workerParams) {

    /**
     * Runs one expiry pass.
     *
     * @return [Result.success] when the pass completes (also when nothing
     *   expired); [Result.retry] when it throws unexpectedly, so WorkManager
     *   re-attempts under the same constraints.
     */
    override suspend fun doWork(): Result = try {
        val windowHours = runSettings.backgroundApprovalWindowHours.first()
        val cutoff = System.currentTimeMillis() - windowHours * MILLIS_PER_HOUR
        val expired = pendingInteractionRepository.getRequestedAtOrBefore(cutoff)
        expired.forEach { pending -> parkedRunResumer.failExpiredPark(pending) }
        if (expired.isNotEmpty()) {
            Timber.tag(TAG).i("Expired %d parked interaction(s) past the %d h window", expired.size, windowHours)
        }
        announceStaleTriggers()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Pending-interaction expiry pass failed; will retry")
        Result.retry()
    }

    /** Runs the overdue-trigger check; a failure is logged, never retried through the expiry pass. */
    private suspend fun announceStaleTriggers() {
        try {
            val noticed = notifyStaleTriggers()
            if (noticed > 0) Timber.tag(TAG).i("Announced %d overdue trigger(s)", noticed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Overdue-trigger check failed")
        }
    }

    companion object {
        /** Unique name of the periodic expiry job. */
        const val UNIQUE_PERIODIC_NAME: String = "pending_interaction_maintenance"

        private const val TAG = "PendingMaintenance"

        /** Milliseconds in one hour, for the window cutoff. */
        private const val MILLIS_PER_HOUR: Long = 3_600_000L
    }
}
