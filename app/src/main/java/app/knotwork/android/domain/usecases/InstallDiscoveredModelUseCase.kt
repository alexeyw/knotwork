package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.DiscoverableModelFile
import app.knotwork.android.domain.models.DownloadState
import app.knotwork.android.domain.repositories.GenerationSettings
import app.knotwork.android.domain.repositories.ModelDownloadManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * Installs a `.litertlm` file picked from a Discover card by streaming it
 * through the existing [ModelDownloadManager] and registering the result in
 * the local model store on success.
 *
 * The use case reuses the same download path the Models screen uses (including
 * the stored Hugging Face token for gated repositories), so behaviour stays
 * consistent. Unlike the custom-URL path it persists the **real** file size
 * reported by the Hub (carried on [DiscoverableModelFile.sizeBytes]) so the
 * Models screen shows an accurate size without a follow-up disk stat. The
 * installed model is **not** auto-activated — the user activates it from the
 * Models screen, matching the onboarding/custom-URL behaviour.
 *
 * @property downloadManager background downloader observed through its state stream.
 * @property registerDownloadedModel local model registry write, shared with the
 *   download worker (which registers the file even when nobody is observing).
 * @property generationSettings source of the stored Hugging Face token.
 */
class InstallDiscoveredModelUseCase @Inject constructor(
    private val downloadManager: ModelDownloadManager,
    private val registerDownloadedModel: RegisterDownloadedModelUseCase,
    private val generationSettings: GenerationSettings,
) {

    /**
     * Streams the download of [file] and refreshes its local model row when the
     * download completes successfully.
     *
     * @param file the `.litertlm` file to install (its resolve URL, on-disk
     *   name and size).
     * @return a [Flow] of [DownloadState] mirroring the download progress;
     *   the terminal [DownloadState.Success] is emitted only after the local
     *   row has been registered.
     */
    operator fun invoke(file: DiscoverableModelFile): Flow<DownloadState> = flow {
        // Only the *presence* of a token decides whether to authenticate — the
        // value itself is read by the downloader from the encrypted store, so it
        // never travels through background-work input.
        val useStoredAuth = !generationSettings.huggingFaceAuthToken.first().isNullOrBlank()
        emitAll(
            downloadManager.downloadModel(
                url = file.resolveUrl,
                fileName = file.fileName,
                useStoredAuth = useStoredAuth,
            ).refreshingSizeOnSuccess(file),
        )
    }

    /**
     * Follows the download already running for [file], without ever starting one.
     *
     * The transfer outlives the screen that started it — it may have been started
     * from the Models screen, or from Discover before the user left it — so a
     * screen showing [file] finds it here. A download that completes while being
     * followed gets the same size refresh as one started through [invoke].
     *
     * @param file the `.litertlm` file the screen shows.
     * @return a [Flow] of [DownloadState] mirroring the running download through
     *   its terminal state, or an empty flow when nothing is running for [file].
     */
    fun attach(file: DiscoverableModelFile): Flow<DownloadState> =
        downloadManager.observeDownload(file.fileName).refreshingSizeOnSuccess(file)

    /**
     * Refreshes the local row of [file] when the download succeeds. The worker
     * has already registered the file by its on-disk length; the size the Hub
     * reported is the authoritative figure.
     */
    private fun Flow<DownloadState>.refreshingSizeOnSuccess(file: DiscoverableModelFile): Flow<DownloadState> =
        onEach { state ->
            if (state is DownloadState.Success) {
                registerDownloadedModel(
                    fileName = file.fileName,
                    path = state.fileUri,
                    sizeBytes = file.sizeBytes,
                )
            }
        }
}
