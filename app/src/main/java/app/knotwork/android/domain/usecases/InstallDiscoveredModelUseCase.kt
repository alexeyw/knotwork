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
            )
                .onEach { state ->
                    if (state is DownloadState.Success) {
                        // The worker has already registered the file by its
                        // on-disk length; refresh it with the size the Hub
                        // reported, which is the authoritative figure.
                        registerDownloadedModel(
                            fileName = file.fileName,
                            path = state.fileUri,
                            sizeBytes = file.sizeBytes,
                        )
                    }
                },
        )
    }
}
