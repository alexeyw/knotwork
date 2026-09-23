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
     * Runs the orphan-cleanup pass, then the transient-cache sweep. The two are
     * independent — one reads the database, the other only the cache — so a
     * failing first pass does not skip the second.
     *
     * @return [Result.success] when both passes complete; [Result.retry] when
     *   either throws unexpectedly, so WorkManager re-attempts the job under the
     *   same constraints.
     */
    override suspend fun doWork(): Result {
        val orphansCleaned = try {
            val deleted = cleanupOrphanAttachmentsUseCase()
            Timber.tag(TAG).d("Attachment orphan cleanup finished: %d file(s) deleted", deleted)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Attachment orphan cleanup pass failed")
            false
        }
        val cacheSwept = try {
            val swept = transientCacheSweeper.sweepExpired()
            Timber.tag(TAG).d("Transient cache sweep finished: %d entr(ies) removed", swept)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only the type: a filesystem exception's message can quote a cache path.
            Timber.tag(TAG).e("Transient cache sweep failed (%s)", e.javaClass.simpleName)
            false
        }
        return if (orphansCleaned && cacheSwept) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "AttachmentCleanup"

        /** Unique work name for the daily periodic orphan-cleanup job. */
        const val UNIQUE_PERIODIC_NAME = "attachment-orphan-cleanup-periodic"
    }
}
