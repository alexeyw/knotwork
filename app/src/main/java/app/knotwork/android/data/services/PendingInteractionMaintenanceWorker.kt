package app.knotwork.android.data.services

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.RunSettings
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
 * @property pendingInteractionRepository Source of the parked records.
 * @property runSettings Source of the `backgroundApprovalWindowHours`
 *   setting, re-read on every run so a changed window applies immediately.
 * @property parkedRunResumer Owner of the shared park-settlement semantics.
 */
@HiltWorker
class PendingInteractionMaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val pendingInteractionRepository: PendingInteractionRepository,
    private val runSettings: RunSettings,
    private val parkedRunResumer: ParkedRunResumer,
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
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Pending-interaction expiry pass failed; will retry")
        Result.retry()
    }

    companion object {
        /** Unique name of the periodic expiry job. */
        const val UNIQUE_PERIODIC_NAME: String = "pending_interaction_maintenance"

        private const val TAG = "PendingMaintenance"

        /** Milliseconds in one hour, for the window cutoff. */
        private const val MILLIS_PER_HOUR: Long = 3_600_000L
    }
}
