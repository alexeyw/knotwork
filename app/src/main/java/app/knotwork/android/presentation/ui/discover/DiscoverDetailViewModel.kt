package app.knotwork.android.presentation.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.data.network.AndroidModelDownloadManager.DownloadError
import app.knotwork.android.domain.models.DiscoverableModelFile
import app.knotwork.android.domain.models.DownloadState
import app.knotwork.android.domain.repositories.GenerationSettings
import app.knotwork.android.domain.repositories.ModelDownloadManager
import app.knotwork.android.domain.usecases.GetDiscoverableModelDetailUseCase
import app.knotwork.android.domain.usecases.InstallDiscoveredModelUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the model-discovery detail screen. Loads a repository's files
 * and drives the per-file install flow through [InstallDiscoveredModelUseCase],
 * gating each download behind a license confirmation. Once the files are known,
 * it also follows any download already running for one of them — started from
 * the Models screen, or from here before the user left — so its progress shows
 * instead of an Install button over work in flight. The shared Hugging Face
 * token (used for access-gated repos) is read/written through
 * [GenerationSettings], matching the Models screen.
 *
 * @property getDetail use case fetching the repository detail.
 * @property installModel use case streaming a file download + local registration.
 * @property generationSettings source/sink of the Hugging Face token.
 * @property downloadManager the background downloader, held so a cancel can stop
 *   the transfer itself and not merely this screen's view of it.
 */
@HiltViewModel
class DiscoverDetailViewModel @Inject constructor(
    private val getDetail: GetDiscoverableModelDetailUseCase,
    private val installModel: InstallDiscoveredModelUseCase,
    private val generationSettings: GenerationSettings,
    private val downloadManager: ModelDownloadManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverDetailUiState())

    /** Current UI state of the detail screen. */
    val uiState: StateFlow<DiscoverDetailUiState> = _uiState.asStateFlow()

    private val _installEvents = MutableSharedFlow<DiscoverInstallEvent>(extraBufferCapacity = 1)

    /** One-shot install outcomes for the screen's snackbar host. */
    val installEvents: SharedFlow<DiscoverInstallEvent> = _installEvents.asSharedFlow()

    /** Per-file jobs following a download — started here or found running — keyed by on-disk file name. */
    private val installJobs = mutableMapOf<String, Job>()

    /** In-flight detail-fetch job; cancelled before a new fetch supersedes it. */
    private var loadJob: Job? = null

    private var repoId: String? = null
    private var tokenObserved = false

    /**
     * Binds the screen to [repoId]: starts observing the stored token (once)
     * and loads the repository detail. Idempotent — safe to call from
     * `LaunchedEffect(repoId)` on every composition.
     */
    fun bind(repoId: String) {
        if (this.repoId == repoId) return
        this.repoId = repoId
        observeTokenOnce()
        load()
    }

    private fun observeTokenOnce() {
        if (tokenObserved) return
        tokenObserved = true
        generationSettings.huggingFaceAuthToken
            .onEach { token -> _uiState.update { it.copy(tokenInput = token.orEmpty()) } }
            .launchIn(viewModelScope)
    }

    /** Re-fetches the repository detail. */
    fun onRetry() = load()

    private fun load() {
        val id = repoId ?: return
        loadJob?.cancel()
        _uiState.update { it.copy(status = DiscoverDetailStatus.Loading) }
        loadJob = viewModelScope.launch {
            getDetail(id).fold(
                onSuccess = { detail ->
                    _uiState.update {
                        it.copy(
                            status = DiscoverDetailStatus.Loaded,
                            detail = detail,
                            installed = detail.files.filter { f -> f.isInstalled }.map { f -> f.fileName }.toSet(),
                        )
                    }
                    followRunningDownloads(detail.files)
                },
                onFailure = {
                    _uiState.update { it.copy(status = DiscoverDetailStatus.Error) }
                },
            )
        }
    }

    /** Opens the license-confirmation dialog for [fileName]. */
    fun onInstallClick(fileName: String) {
        _uiState.update { it.copy(pendingLicenseFileName = fileName) }
    }

    /** Dismisses the license-confirmation dialog. */
    fun onLicenseDismiss() {
        _uiState.update { it.copy(pendingLicenseFileName = null) }
    }

    /** User accepted the license — starts the download for [fileName]. */
    fun onLicenseConfirm(fileName: String) {
        _uiState.update { it.copy(pendingLicenseFileName = null) }
        val file = _uiState.value.detail?.files?.firstOrNull { it.fileName == fileName } ?: return
        if (installJobs[fileName]?.isActive == true) return
        installJobs[fileName] = follow(fileName, installModel(file))
    }

    /**
     * Follows the download already running for each of [files], if any. A file
     * already installed, or one this screen is following, is skipped; a file with
     * nothing running gets an empty stream and keeps its Install button.
     */
    private fun followRunningDownloads(files: List<DiscoverableModelFile>) {
        files
            .filterNot { it.isInstalled || installJobs[it.fileName]?.isActive == true }
            .forEach { file -> installJobs[file.fileName] = follow(file.fileName, installModel.attach(file)) }
    }

    /** Folds [states] into this screen for [fileName] — the same handling whoever started the download. */
    private fun follow(fileName: String, states: Flow<DownloadState>): Job =
        states.onEach { state -> handleDownloadState(fileName, state) }.launchIn(viewModelScope)

    private fun handleDownloadState(fileName: String, state: DownloadState) {
        when (state) {
            is DownloadState.Pending -> setProgress(fileName, 0)
            is DownloadState.Downloading -> setProgress(fileName, state.progress)
            is DownloadState.Success -> {
                clearProgress(fileName)
                installJobs.remove(fileName)
                _uiState.update { it.copy(installed = it.installed + fileName) }
                _installEvents.tryEmit(DiscoverInstallEvent.Success(fileName))
            }
            is DownloadState.Error -> {
                clearProgress(fileName)
                installJobs.remove(fileName)
                _installEvents.tryEmit(DiscoverInstallEvent.Failed(gated = state.isGatedRefusal()))
            }
        }
    }

    /**
     * Cancels an in-flight install for [fileName].
     *
     * Cancels the **download** first and the collecting coroutine second, in that
     * order and unconditionally. Cancelling only the coroutine — which is all this
     * did until 0.10.1 — stops the progress updates while the work carries on:
     * the transfer runs in `WorkManager`, outlives this ViewModel by design, and
     * would finish and register the model minutes after the user pressed Cancel,
     * on whatever connection they happened to be on. `ModelsViewModel.cancelDownload`
     * has had the two-step form since the download moved to `WorkManager`; this
     * screen was written before that and never caught up.
     *
     * The cancel is not conditional on a job being present. The screen follows a
     * running download only once the repository detail has loaded, and stopping
     * work that nothing here follows costs nothing — leaving it running is the
     * defect this method once had.
     *
     * Bytes already fetched stay on disk, so re-installing the same file resumes.
     *
     * @param fileName On-disk name of the file whose install is being cancelled.
     */
    fun onCancelInstall(fileName: String) {
        downloadManager.cancelDownload(fileName)
        installJobs.remove(fileName)?.cancel()
        clearProgress(fileName)
    }

    /** Updates and persists the Hugging Face token. */
    fun onTokenChange(token: String) {
        _uiState.update { it.copy(tokenInput = token) }
        viewModelScope.launch {
            generationSettings.setHuggingFaceAuthToken(token.takeIf { it.isNotBlank() })
        }
    }

    /** Toggles between masked and clear-text token display. */
    fun onToggleTokenReveal() {
        _uiState.update { it.copy(tokenRevealed = !it.tokenRevealed) }
    }

    private fun setProgress(fileName: String, percent: Int) {
        _uiState.update { it.copy(progress = it.progress + (fileName to percent)) }
    }

    private fun clearProgress(fileName: String) {
        _uiState.update { it.copy(progress = it.progress - fileName) }
    }

    /**
     * Treats a 401/403 download refusal as an access-gated failure so the
     * screen can nudge the user toward accepting the licence and supplying a
     * token. Reads the typed HTTP status carried on [DownloadError.code]
     * rather than parsing the free-text message (which also covers arbitrary
     * transport-error strings).
     */
    private fun DownloadState.Error.isGatedRefusal(): Boolean {
        val code = (error as? DownloadError)?.code
        return code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN
    }

    private companion object {
        /** HTTP 401 — missing/invalid credentials for a gated repository. */
        const val HTTP_UNAUTHORIZED = 401

        /** HTTP 403 — licence not accepted for a gated repository. */
        const val HTTP_FORBIDDEN = 403
    }
}
