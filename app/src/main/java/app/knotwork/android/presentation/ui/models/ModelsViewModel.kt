package app.knotwork.android.presentation.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.data.network.AndroidModelDownloadManager
import app.knotwork.android.domain.engine.TaskQueueManager
import app.knotwork.android.domain.models.CustomModelLink
import app.knotwork.android.domain.models.DownloadState
import app.knotwork.android.domain.models.isBusy
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.ModelDownloadManager
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.usecases.BenchmarkOutcome
import app.knotwork.android.domain.usecases.BenchmarkRunPhase
import app.knotwork.android.domain.usecases.GetModelPerformanceUseCase
import app.knotwork.android.domain.usecases.RunBenchmarkUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel responsible for managing the state and business logic of the Models UI.
 * It interacts with the repository to fetch downloaded models and the download manager
 * to handle new model downloads.
 *
 * @property localModelRepository The repository for accessing locally stored models.
 * @property downloadManager The manager for downloading new models from the network.
 * @property settingsRepository The repository for managing application settings like auth tokens.
 * @property getModelPerformanceUseCase Supplies the rolling-average performance
 *   summary for the active model shown on the Performance card.
 * @property runBenchmarkUseCase Runs the controlled one-shot benchmark.
 * @property taskQueueManager Source of the live engine-busy signal that gates
 *   the Performance card's benchmark action.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val localModelRepository: LocalModelRepository,
    private val downloadManager: ModelDownloadManager,
    private val settingsRepository: SettingsRepository,
    private val getModelPerformanceUseCase: GetModelPerformanceUseCase,
    private val runBenchmarkUseCase: RunBenchmarkUseCase,
    private val taskQueueManager: TaskQueueManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ModelsUiState())

    /**
     * The current UI state of the Models screen.
     */
    val uiState: StateFlow<ModelsUiState> = _uiState.asStateFlow()

    private val _benchmarkShareEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /**
     * One-shot stream of formatted benchmark-report text to hand to the system
     * share sheet. Emitted when the user taps Share on the benchmark result.
     */
    val benchmarkShareEvents: SharedFlow<String> = _benchmarkShareEvents.asSharedFlow()

    private val _benchmarkErrorEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * One-shot stream signalling that a benchmark run failed, so the screen can
     * show a transient error snackbar.
     */
    val benchmarkErrorEvents: SharedFlow<Unit> = _benchmarkErrorEvents.asSharedFlow()

    private val _customUrlRefusedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * One-shot stream signalling that [submitCustomUrl] refused the link in the field
     * because its file is not `.litertlm`, so the screen can say why nothing started.
     */
    val customUrlRefusedEvents: SharedFlow<Unit> = _customUrlRefusedEvents.asSharedFlow()

    /**
     * Reference to the currently in-flight download collection job. Held so
     * [cancelDownload] can interrupt it; nulled out when the download
     * terminates (Success / Error / cancellation).
     */
    private var downloadJob: Job? = null

    /**
     * Reference to the in-flight benchmark job. Held so [onCancelBenchmark] can
     * interrupt it; nulled out when the run terminates.
     */
    private var benchmarkJob: Job? = null

    init {
        observeDownloadedModels()
        observeAuthToken()
        observeBackend()
        observeActiveModelPerformance()
        observeEngineBusy()
        observeRunningDownload()
    }

    /**
     * Tracks the rolling performance summary of the active model. Re-subscribes
     * whenever the active model changes (or none is active → emits `null`).
     */
    private fun observeActiveModelPerformance() {
        localModelRepository.getAllModels()
            .map { models -> models.find { it.isActive }?.path }
            .distinctUntilChanged()
            .flatMapLatest { activePath ->
                if (activePath == null) flowOf(null) else getModelPerformanceUseCase(activePath)
            }
            .onEach { summary -> _uiState.update { it.copy(performanceSummary = summary) } }
            .launchIn(viewModelScope)
    }

    /** Mirrors the engine-busy predicate so the Performance card can gate the benchmark. */
    private fun observeEngineBusy() {
        taskQueueManager.globalState
            .map { it.isBusy }
            .distinctUntilChanged()
            .onEach { busy -> _uiState.update { it.copy(engineBusy = busy) } }
            .launchIn(viewModelScope)
    }

    private fun observeBackend() {
        settingsRepository.localModelBackend
            .onEach { key ->
                _uiState.update { it.copy(localBackendKey = key) }
            }
            .launchIn(viewModelScope)
    }

    private fun observeDownloadedModels() {
        localModelRepository.getAllModels()
            .onEach { models ->
                val active = models.find { it.isActive }
                _uiState.update { state ->
                    state.copy(
                        downloadedModels = models,
                        activeModel = active,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    private fun observeAuthToken() {
        settingsRepository.huggingFaceAuthToken
            .onEach { token ->
                _uiState.update { it.copy(authTokenInput = token ?: "") }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Updates the custom URL input text in the UI state.
     *
     * @param url The new URL string entered by the user.
     */
    fun onCustomUrlChanged(url: String) {
        _uiState.update { it.copy(customUrlInput = url, downloadError = null) }
    }

    /**
     * Updates the authorization token input text in the UI state and saves it to settings.
     *
     * @param token The new token string entered by the user.
     */
    fun onAuthTokenChanged(token: String) {
        _uiState.update { it.copy(authTokenInput = token) }
        viewModelScope.launch {
            settingsRepository.setHuggingFaceAuthToken(token.takeIf { it.isNotBlank() })
        }
    }

    /**
     * Downloads the link in the custom URL field, if it names a `.litertlm` file.
     *
     * The link is checked by [CustomModelLink.parse] before anything downloads, and
     * its file is saved under the name that check derives (the path's last segment,
     * query string dropped). A link to any other file is refused through
     * [customUrlRefusedEvents]; an empty field does nothing (the `Get` button is off).
     */
    fun submitCustomUrl() {
        when (val link = CustomModelLink.parse(_uiState.value.customUrlInput)) {
            is CustomModelLink.Accepted -> startDownload(link.url, link.fileName)
            CustomModelLink.Blank -> Unit
            CustomModelLink.NotLitertlm -> _customUrlRefusedEvents.tryEmit(Unit)
        }
    }

    /**
     * Initiates a download for the given URL and desired file name.
     *
     * @param url The direct URL to download the model from.
     * @param fileName The name to save the file as on the local device.
     */
    fun startDownload(url: String, fileName: String) {
        if (_uiState.value.isDownloading) return

        val useStoredAuth = _uiState.value.authTokenInput.isNotBlank()

        // Defence-in-depth: even though `isDownloading` blocks the second
        // entry above, any stale `downloadJob` from a finished collection
        // is cancelled here before we overwrite the reference. Without
        // this, a job that finished its terminal Success / Error frame
        // but is still inside an unrelated tear-down step could keep
        // emitting after the new flow takes over, interleaving updates
        // into `_uiState` and leaking resources.
        downloadJob?.cancel()

        _uiState.update {
            it.copy(
                isDownloading = true,
                downloadProgress = 0,
                downloadError = null,
                activeDownloadFileName = fileName,
            )
        }

        collectDownload(downloadManager.downloadModel(url, fileName, useStoredAuth))
    }

    /**
     * Attaches to a download already running in the background.
     *
     * The transfer outlives this ViewModel, so returning to the screen must find
     * it again — otherwise the shade shows a download in progress while the
     * screen that owns it looks idle.
     */
    private fun observeRunningDownload() {
        downloadManager.observeActiveDownload()
            .onEach { active ->
                if (active == null) return@onEach
                if (_uiState.value.activeDownloadFileName == active.fileName) return@onEach
                _uiState.update {
                    it.copy(
                        isDownloading = true,
                        downloadProgress = (active.state as? DownloadState.Downloading)?.progress ?: 0,
                        downloadError = null,
                        activeDownloadFileName = active.fileName,
                    )
                }
                collectDownload(downloadManager.observeDownload(active.fileName))
            }
            .launchIn(viewModelScope)
    }

    /**
     * Folds a download state stream into the UI state. Shared by the "start one"
     * and "re-attach to one" paths so both behave identically once the bytes are
     * moving.
     */
    private fun collectDownload(states: Flow<DownloadState>) {
        downloadJob?.cancel()
        downloadJob = states
            .onEach { state ->
                when (state) {
                    is DownloadState.Pending -> {
                        _uiState.update { it.copy(downloadProgress = 0) }
                    }
                    is DownloadState.Downloading -> {
                        _uiState.update { it.copy(downloadProgress = state.progress) }
                    }
                    is DownloadState.Success -> {
                        _uiState.update {
                            it.copy(
                                isDownloading = false,
                                downloadProgress = null,
                                activeDownloadFileName = null,
                            )
                        }
                        // The download worker registers the file itself — it has
                        // to, since the transfer outlives this screen — so there
                        // is nothing to insert here.
                    }
                    is DownloadState.Error -> {
                        _uiState.update {
                            it.copy(
                                isDownloading = false,
                                downloadProgress = null,
                                downloadError = state.error,
                                activeDownloadFileName = null,
                            )
                        }
                    }
                }
            }
            .catch { e ->
                _uiState.update {
                    it.copy(
                        isDownloading = false,
                        downloadProgress = null,
                        downloadError = AndroidModelDownloadManager.DownloadError(
                            e.message ?: "Unknown error occurred",
                        ),
                        activeDownloadFileName = null,
                    )
                }
            }
            // A download cancelled elsewhere — the notification's Cancel action —
            // ends the stream with no terminal state, so the screen has to clear
            // its own in-flight flags or it would show a download that is gone.
            .onCompletion {
                _uiState.update {
                    it.copy(isDownloading = false, downloadProgress = null, activeDownloadFileName = null)
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Sets the specified model as the active one for the application.
     *
     * @param modelId The unique identifier of the model to activate.
     */
    fun setActiveModel(modelId: Long) {
        viewModelScope.launch {
            localModelRepository.setActiveModel(modelId)
        }
    }

    /**
     * Sets the manual vision-capability flag of the model with the given id.
     * Backs the "Image support" toggle on the active-model card; the persisted
     * value is read by the chat send-time pre-flight to decide whether an image
     * message may run against the active model. The model list is observed, so
     * the toggle reflects the new value on the next emission.
     *
     * @param modelId The unique identifier of the model to update.
     * @param enabled `true` to mark the model vision-capable, `false` otherwise.
     */
    fun setVisionSupport(modelId: Long, enabled: Boolean) {
        viewModelScope.launch {
            localModelRepository.setVisionSupport(modelId, enabled)
        }
    }

    /**
     * Sets the manual audio-capability flag of the model with the given id.
     * Backs the "Audio support" toggle on the active-model card; the persisted
     * value is read by the voice-input transcription pre-flight to decide
     * whether the active model may transcribe a recorded/picked clip. The model
     * list is observed, so the toggle reflects the new value on the next
     * emission.
     *
     * @param modelId The unique identifier of the model to update.
     * @param enabled `true` to mark the model audio-capable, `false` otherwise.
     */
    fun setAudioSupport(modelId: Long, enabled: Boolean) {
        viewModelScope.launch {
            localModelRepository.setAudioSupport(modelId, enabled)
        }
    }

    /**
     * Cancels the currently in-flight download (if any).
     *
     * The download runs as background work, so stopping the observing job is
     * no longer enough — it would leave the transfer running with no UI. The
     * work itself is cancelled by target file name; the bytes already fetched
     * stay on disk, so re-downloading the same file resumes.
     */
    fun cancelDownload() {
        _uiState.value.activeDownloadFileName?.let(downloadManager::cancelDownload)
        downloadJob?.cancel()
        downloadJob = null
        _uiState.update {
            it.copy(
                isDownloading = false,
                downloadProgress = null,
                activeDownloadFileName = null,
            )
        }
    }

    /**
     * Removes the model with the given id from the local store. Called from
     * the per-row overflow menu on the Models screen.
     */
    fun deleteModel(modelId: Long) {
        viewModelScope.launch {
            localModelRepository.deleteModelById(modelId)
        }
    }

    /**
     * Clears the current download error from the UI state.
     */
    fun clearError() {
        _uiState.update { it.copy(downloadError = null) }
    }

    /**
     * Starts the controlled benchmark of the active model. No-op when a
     * benchmark is already running. Drives the card through Warming up →
     * Measuring → Result; a failure surfaces a one-shot error event and resets
     * the card. The engine-busy case is gated upstream (the Run action is hidden
     * while busy), but is handled defensively by resetting to idle.
     */
    fun onRunBenchmark() {
        if (_uiState.value.benchmark is BenchmarkUiState.Running) return
        benchmarkJob?.cancel()
        _uiState.update { it.copy(benchmark = BenchmarkUiState.Running(BenchmarkRunPhase.WARMING_UP)) }
        benchmarkJob = viewModelScope.launch {
            val outcome = runBenchmarkUseCase { phase ->
                _uiState.update { it.copy(benchmark = BenchmarkUiState.Running(phase)) }
            }
            val next = when (outcome) {
                is BenchmarkOutcome.Success -> BenchmarkUiState.Result(outcome.report)
                is BenchmarkOutcome.Failed -> {
                    _benchmarkErrorEvents.tryEmit(Unit)
                    BenchmarkUiState.Idle
                }
                is BenchmarkOutcome.EngineBusy,
                is BenchmarkOutcome.NoActiveModel,
                -> BenchmarkUiState.Idle
            }
            _uiState.update { it.copy(benchmark = next) }
            benchmarkJob = null
        }
    }

    /**
     * Cancels the in-flight benchmark (the user tapped Cancel) and returns the
     * card to its idle rolling-average view.
     */
    fun onCancelBenchmark() {
        benchmarkJob?.cancel()
        benchmarkJob = null
        _uiState.update { it.copy(benchmark = BenchmarkUiState.Idle) }
    }

    /**
     * Emits the formatted benchmark report text for the system share sheet.
     * No-op unless a benchmark result is currently shown.
     */
    fun onShareBenchmark() {
        val report = (_uiState.value.benchmark as? BenchmarkUiState.Result)?.report ?: return
        _benchmarkShareEvents.tryEmit(PerformanceFormatting.shareText(report))
    }

    /** Dismisses the inline benchmark result, returning to the rolling-average view. */
    fun onDismissBenchmark() {
        _uiState.update { it.copy(benchmark = BenchmarkUiState.Idle) }
    }
}
