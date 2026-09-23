package app.knotwork.android.data.services

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.knotwork.android.domain.services.TransientCacheSweeper
import app.knotwork.android.domain.usecases.CleanupOrphanAttachmentsUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Background worker that runs the daily file-maintenance pass: orphaned
 * attachments first, then the transient handoff directories in the cache.
 *
 * Scheduled by [AttachmentOrphanCleanupScheduler] as a daily periodic job in
 * the same charging + idle maintenance window as [RunRetentionWorker]. Like the
 * other maintenance workers it is deliberately thin: the policies live in
 * [CleanupOrphanAttachmentsUseCase] and [TransientCacheSweeper], and this only
 * translates the outcome into a WorkManager result. The class keeps its name
 * because WorkManager persists it with the enqueued periodic work.
 *
 * No agent-busy gate is needed: the first pass only deletes files no message
 * references, and the second only entries older than the transient retention,
 * so neither can race a live attachment, capture or share.
 *
 * @property cleanupOrphanAttachmentsUseCase The orphan-attachment pass.
 * @property transientCacheSweeper The transient-cache pass (camera captures,
 *   share copies, journal exports, voice clips).
 */
@HiltWorker
class AttachmentOrphanCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val cleanupOrphanAttachmentsUseCase: CleanupOrphanAttachmentsUseCase,
    private val transientCacheSweeper: TransientCacheSweeper,
) : CoroutineWorker(context, workerParams) {

    /**
     * Runs the orphan-cleanup pass, then the transient-cache sweep.
     *
     * @return [Result.success] when the pass completes; [Result.retry] when it
     *   throws unexpectedly, so WorkManager re-attempts it under the same
     *   constraints.
     */
    override suspend fun doWork(): Result = try {
        val deleted = cleanupOrphanAttachmentsUseCase()
        Timber.tag(TAG).d("Attachment orphan cleanup finished: %d file(s) deleted", deleted)
        val swept = transientCacheSweeper.sweepExpired()
        Timber.tag(TAG).d("Transient cache sweep finished: %d entr(ies) removed", swept)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Attachment orphan cleanup pass failed")
        Result.retry()
    }

    companion object {
        private const val TAG = "AttachmentCleanup"

        /** Unique work name for the daily periodic orphan-cleanup job. */
        const val UNIQUE_PERIODIC_NAME = "attachment-orphan-cleanup-periodic"
    }
}
