package app.knotwork.android.data.services

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.services.ModelFileHashScheduler
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager-backed [ModelFileHashScheduler]: enqueues a one-off
 * [ModelFileHashWorker] that hashes the installed model files off the hot path.
 *
 * Relaxed constraints (battery-not-low only), so a freshly installed model gets
 * its hash soon — a run on a model whose hash is not known yet is recorded
 * without one and cannot be verified later.
 *
 * [ExistingWorkPolicy.APPEND_OR_REPLACE], not `KEEP`: a pass already running has
 * listed the files that needed a hash before a new model was registered, so
 * `KEEP` would drop the new model's request and leave it unhashed until the next
 * start. Appending chains a fresh pass after the current one (and replaces a
 * failed or cancelled one), so every registration gets a pass that sees it.
 *
 * @property workManager The WorkManager instance the pass is enqueued on.
 * @property localModelRepository Source of the models still to hash, for [rearmIfPending].
 */
@Singleton
class WorkManagerModelFileHashScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val localModelRepository: LocalModelRepository,
) : ModelFileHashScheduler {

    override fun schedule() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<ModelFileHashWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(
            ModelFileHashWorker.UNIQUE_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        Timber.tag(TAG).d("Scheduled a model file hashing pass")
    }

    override suspend fun rearmIfPending() {
        // Start-up work with no UI to report a failure to. When the database cannot be
        // opened (the splash recovery screen handles that), crashing here would preempt
        // the recovery — skip the re-arm instead.
        val pending = try {
            localModelRepository.modelsNeedingFileHash()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Skipping model hash re-arm: the model registry is unavailable")
            return
        }
        if (pending.isNotEmpty()) {
            Timber.tag(TAG).d("%d model files have no current hash; scheduling a pass", pending.size)
            schedule()
        }
    }

    private companion object {
        const val TAG = "ModelFileHash"

        /** Initial exponential-backoff delay between worker retries. */
        const val BACKOFF_SECONDS = 30L
    }
}
