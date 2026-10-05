package app.knotwork.android.data.services

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.knotwork.android.domain.usecases.ComputeModelFileHashesUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Background worker that hashes every installed model file without a current
 * SHA-256.
 *
 * Scheduled as a one-off by [WorkManagerModelFileHashScheduler] when a model is
 * registered, and re-armed at start-up while any file is still unhashed. The
 * hash identifies the model a run used; reading a multi-gigabyte file takes
 * seconds, so it happens here, once per file, and never on the run path. The
 * worker is deliberately thin, delegating to [ComputeModelFileHashesUseCase]
 * (as [MemoryReembedWorker] delegates to its use case).
 *
 * @property computeModelFileHashes The hashing pass itself.
 */
@HiltWorker
class ModelFileHashWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val computeModelFileHashes: ComputeModelFileHashesUseCase,
) : CoroutineWorker(context, workerParams) {

    /**
     * Runs one hashing pass.
     *
     * @return [Result.success] once every readable file has a hash (or there was
     *   none to hash); [Result.retry] when the pass throws — the registry could
     *   not be read or written — so WorkManager re-attempts it with backoff.
     */
    override suspend fun doWork(): Result = try {
        val hashed = computeModelFileHashes()
        Timber.tag(TAG).d("Hashed %d model files", hashed)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "Model file hashing pass failed; will retry")
        Result.retry()
    }

    companion object {
        private const val TAG = "ModelFileHash"

        /** Unique work name: passes chain instead of running side by side. */
        const val UNIQUE_NAME = "model-file-hash"
    }
}
