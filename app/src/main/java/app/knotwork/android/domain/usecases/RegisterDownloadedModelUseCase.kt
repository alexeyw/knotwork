package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.repositories.LocalModelRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records a freshly-downloaded model file in the local model store.
 *
 * This lives at the *download* end rather than in a ViewModel because a
 * download now outlives the screen that started it: the user can finish
 * onboarding, leave, or lock the phone while the transfer runs. If registration
 * stayed with the observer, a download that survived would leave a
 * multi-gigabyte file on disk that the app knows nothing about — the worst of
 * both worlds.
 *
 * The write is idempotent on the on-disk file name (`local_models` has no
 * unique index on `name`, so a blind insert would duplicate the row on a
 * re-download) and never touches the active flag: installing a model is not
 * choosing it. Idempotent under concurrency too: the lookup and the insert run
 * under one lock, because a download finishing while the start-up pass
 * ([RediscoverDownloadedModelsUseCase]) registers the same file would otherwise
 * insert it twice — both would find no row, then both would insert.
 *
 * @property localModelRepository The local model registry.
 */
@Singleton
class RegisterDownloadedModelUseCase @Inject constructor(private val localModelRepository: LocalModelRepository) {

    /** Serialises lookup-then-insert across every caller in the process. */
    private val registration = Mutex()

    /**
     * Registers (or refreshes) the row for [fileName].
     *
     * @param fileName On-disk file name, the identity used for the upsert.
     * @param path Absolute path of the downloaded file.
     * @param sizeBytes Size to record. Callers that know the authoritative size
     *   (the Hub listing) pass it; otherwise the on-disk length is the truth.
     * @return The row id of the registered model.
     */
    suspend operator fun invoke(fileName: String, path: String, sizeBytes: Long): Long = registration.withLock {
        val existing = localModelRepository.findByFileName(fileName)
        if (existing != null) {
            localModelRepository.updateModel(existing.copy(path = path, size = sizeBytes))
            return@withLock existing.id
        }
        localModelRepository.insertModel(
            LocalModel(name = fileName, path = path, size = sizeBytes, isActive = false),
        )
    }
}
