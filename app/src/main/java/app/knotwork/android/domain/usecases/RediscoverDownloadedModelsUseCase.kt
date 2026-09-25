package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.services.DownloadedModelFiles
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Registers again every downloaded model file the registry has lost track of.
 *
 * The recovery screen's *Erase data* deletes the database — the registry of installed
 * models with it — and keeps the downloaded model files, so a re-download is never the
 * price of recovering. Without this pass those files stayed on disk, invisible: the
 * Models screen reads only the registry. Run at every start (it lists one directory),
 * so any other way a row goes missing heals the same way.
 *
 * A file counts as registered when a row has its path or its name: a Hugging Face file
 * from a sub-folder is registered under its repository name (`q4/model.litertlm`) but
 * stored under a flattened one (`q4_model.litertlm`), and the path is what matches it.
 * A rediscovered model comes back as it was downloaded — inactive, with the vision and
 * audio switches off — since those choices lived in the erased row.
 *
 * @property downloadedModelFiles The model files on disk.
 * @property localModelRepository The registry of installed models.
 * @property registerDownloadedModel The one way a model enters the registry.
 */
class RediscoverDownloadedModelsUseCase @Inject constructor(
    private val downloadedModelFiles: DownloadedModelFiles,
    private val localModelRepository: LocalModelRepository,
    private val registerDownloadedModel: RegisterDownloadedModelUseCase,
) {

    /**
     * Registers the model files on disk that no registry row names.
     *
     * @return How many files were registered.
     */
    suspend operator fun invoke(): Int {
        val registered = localModelRepository.getAllModels().first()
        val knownPaths = registered.mapTo(HashSet()) { it.path }
        val knownNames = registered.mapTo(HashSet()) { it.name }
        val unregistered = downloadedModelFiles.list().filter { it.path !in knownPaths && it.name !in knownNames }
        for (file in unregistered) {
            registerDownloadedModel(fileName = file.name, path = file.path, sizeBytes = file.sizeBytes)
        }
        return unregistered.size
    }
}
