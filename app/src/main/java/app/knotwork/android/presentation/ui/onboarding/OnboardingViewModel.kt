package app.knotwork.android.presentation.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.data.network.AndroidModelDownloadManager
import app.knotwork.android.domain.constants.OnboardingModelCatalog
import app.knotwork.android.domain.models.AppError
import app.knotwork.android.domain.models.CustomModelLink
import app.knotwork.android.domain.models.DownloadState
import app.knotwork.android.domain.models.OnboardingMilestone
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.ModelDownloadManager
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.UsageTelemetryRepository
import app.knotwork.android.domain.usecases.PrepareInferenceBackendUseCase
import app.knotwork.android.domain.usecases.SetUpScenarioUseCase
import app.knotwork.android.presentation.state.TransientMessageRelay
import app.knotwork.design.screens.onboarding.OnboardingDefaultPipelinePreview
import app.knotwork.design.screens.onboarding.OnboardingLiteRtModel
import app.knotwork.design.screens.onboarding.OnboardingScenario
import app.knotwork.design.screens.onboarding.OnboardingStep
import app.knotwork.design.screens.onboarding.OnboardingViewState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the scenario onboarding flow.
 *
 * Drives the 4-step pager:
 *  - Step 1 / Welcome — pure presentation.
 *  - Step 2 / Choose a scenario — the value gallery. Picking a scenario
 *    ([pickScenario]) pre-selects the model it needs and re-checks whether that
 *    model is already installed. Tapping "Set up {scenario}" ([setUpScenario])
 *    materialises the scenario's pipeline as the default, binds its entry
 *    surface, and advances to the download step. "Start from scratch"
 *    ([startFromScratch]) leaves the seeded default in place and completes.
 *  - Step 3 / Download — the motivated download. The scenario pre-selects the
 *    model; the user may switch to an alternative under "Or choose another".
 *    The CTA either starts a download ([startDownload]) or advances when the
 *    picked model is already installed.
 *  - Step 4 / Ready — recap of what was materialised (scenario pipeline preview,
 *    active model, and — for Share Handler — the bound surface / HITL safety
 *    rows). Committing flips `hasCompletedOnboarding` and lets the host navigate.
 *
 * Warm-up. As soon as a model becomes installed (either re-detected on entry or
 * after a successful download), [scheduleWarmUp] kicks off
 * [PrepareInferenceBackendUseCase] so the inference handle is ready by the time
 * the user reaches step 4 — preventing the "LiteRT handle released by system"
 * failure on the first `sendMessage`. On a first-time install the same step also
 * settles which execution backend the device will use.
 *
 * @property settingsRepository persists the `hasCompletedOnboarding` flag.
 * @property localModelRepository detects previously-installed models and persists
 * freshly-downloaded ones.
 * @property downloadManager streams `DownloadState` updates folded into
 * `OnboardingViewState.downloadProgress` / `downloadError`.
 * @property prepareInferenceBackendUseCase resolves the execution backend on a
 * first-time install (GPU when the device plausibly supports it and a real
 * generation confirms it, CPU otherwise) and warms the LiteRT inference handle.
 * @property setUpScenarioUseCase materialises a picked scenario (default pipeline
 * + entry-surface binding) and returns its recap projection.
 * @property transientMessageRelay process-wide one-shot snackbar bus; the
 * skip/escape hint outlives the popped onboarding back-stack entry.
 * @property usageTelemetry records the write-once onboarding markers (opened,
 * scenario chosen, download started/finished) backing the repeatable
 * "time to first value" measurement. Recording is opt-in-gated, best-effort and
 * fully on-device.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val localModelRepository: LocalModelRepository,
    private val downloadManager: ModelDownloadManager,
    private val prepareInferenceBackendUseCase: PrepareInferenceBackendUseCase,
    private val setUpScenarioUseCase: SetUpScenarioUseCase,
    private val transientMessageRelay: TransientMessageRelay,
    private val usageTelemetry: UsageTelemetryRepository,
) : ViewModel() {

    private val _state: MutableStateFlow<OnboardingViewState> = MutableStateFlow(OnboardingViewState())

    /** Externally-observable view state passed to `OnboardingContent`. */
    val state: StateFlow<OnboardingViewState> = _state.asStateFlow()

    /** Active download collection — cancelled when a new pick re-arms the flow. */
    private var downloadJob: Job? = null

    /** Active warm-up job — cancelled if the user re-installs a different model. */
    private var warmUpJob: Job? = null

    /**
     * Active install-check job — cancelled by the next [pickLiteRtModel] /
     * [pickScenario] so a slow `findByFileName` from a stale pick can never
     * overwrite a newer selection's state.
     */
    private var installCheckJob: Job? = null

    /**
     * Resolved absolute path of the currently installed model. Kept outside the
     * catalog view-state because the design-system layer has no business with
     * on-disk paths; the VM passes this into [LoadModelUseCase] on warm-up.
     */
    private var installedModelPath: String? = null

    /** Active scenario set-up job — guards against a double-tap materialising twice. */
    private var setUpJob: Job? = null

    /**
     * Id of the scenario whose pipeline has already been materialised in this
     * session. Lets a re-tap of the *same* scenario re-advance to the download
     * step without spawning a second pipeline (and overwriting the default);
     * a different pick re-materialises deliberately.
     */
    private var materializedScenarioId: String? = null

    init {
        // Reaching the onboarding flow is the start of the measured journey.
        // Write-once, so re-entering onboarding later never moves the start.
        // Declared after the state properties so the block can never run before
        // the fields a future marker might read are initialised.
        markMilestone(OnboardingMilestone.ONBOARDING_STARTED)
        attachToRunningDownload()
    }

    /** Advances to the next step. Idempotent at the final step. */
    fun next() {
        _state.update { current ->
            val nextStep = OnboardingStep.entries.getOrNull(current.step.pageIndex + 1) ?: current.step
            current.copy(step = nextStep)
        }
    }

    /** Steps back; idempotent on step 1. */
    fun back() {
        _state.update { current ->
            val previousStep = OnboardingStep.entries.getOrNull(current.step.pageIndex - 1) ?: current.step
            current.copy(step = previousStep)
        }
    }

    /**
     * Records the user's scenario pick on the gallery step and pre-selects the
     * model that scenario needs, re-checking whether that model is already
     * installed. Cancels any in-flight download / warm-up / install-check that
     * belonged to a previous pick so a rapid A → B → A switch cannot resurrect
     * the previous selection's state.
     */
    fun pickScenario(scenario: OnboardingScenario) {
        // Never interrupt an in-flight set-up: cancelling it after the preset
        // has already been persisted would orphan that pipeline and leave
        // `materializedScenarioId` unset, so the next "Set up" tap would
        // persist a duplicate. The gallery is non-interactive while busy; this
        // guard is the authoritative one.
        if (setUpJob?.isActive == true) return
        installCheckJob?.cancel()
        downloadJob?.cancel()
        warmUpJob?.cancel()
        _state.update {
            it.copy(
                selectedScenario = scenario,
                liteRtModel = scenario.requiredModel,
                downloadProgress = null,
                downloadError = null,
                installedModelId = null,
                isModelWarmed = false,
            )
        }
        installedModelPath = null
        checkIfPickedModelAlreadyInstalled(scenario.requiredModel)
    }

    /**
     * Materialises the currently-picked scenario (default pipeline + entry
     * surface) and advances to the download step. Stores the recap projection
     * for the "Ready" step. No-op when no scenario is picked; surfaces a failure
     * inline when materialisation fails so the user is not stranded.
     *
     * Guards against materialising more than once for the same intent: a
     * double-tap while a set-up is in flight is ignored, and a re-tap of a
     * scenario already materialised this session just re-advances to the
     * download step instead of persisting a second pipeline and overwriting the
     * default. Picking a *different* scenario ([pickScenario] cancels the job)
     * re-materialises deliberately.
     */
    fun setUpScenario() {
        val scenario = _state.value.selectedScenario ?: return
        if (setUpJob?.isActive == true) return
        if (materializedScenarioId == scenario.id && _state.value.scenarioPreview != null) {
            _state.update { it.copy(step = OnboardingStep.Download) }
            return
        }
        setUpJob = viewModelScope.launch {
            // Surfacing the wait is what stops the user from tapping again:
            // materialising reads the preset asset, parses it and writes to
            // Room, which is not instant on a cold first launch.
            _state.update { it.copy(isSettingUpScenario = true) }
            try {
                setUpScenarioUseCase(scenario.id)
                    .onSuccess { setup ->
                        materializedScenarioId = scenario.id
                        // The marker carries the materialised pipeline id: it is
                        // what scopes "first value" to the scenario the user was
                        // promised, rather than to any run that happens next.
                        markMilestone(OnboardingMilestone.SCENARIO_CHOSEN, detail = setup.pipelineId)
                        _state.update {
                            it.copy(scenarioPreview = setup.toPreview(), step = OnboardingStep.Download)
                        }
                    }
                    .onFailure { error ->
                        _state.update { it.copy(downloadError = error.message ?: GENERIC_SETUP_ERROR) }
                    }
            } finally {
                // Runs on the cancellation path too, so a cancelled set-up can
                // never strand the gallery in a permanently busy state.
                _state.update { it.copy(isSettingUpScenario = false) }
            }
        }
    }

    /**
     * Retries the model warm-up after a failure surfaced on the "Ready" step.
     * Clears the error and re-runs [LoadModelUseCase] against the same installed
     * model path, so a transient warm failure never dead-ends onboarding behind
     * a disabled "Preparing…" CTA.
     */
    fun retryWarmUp() {
        _state.update { it.copy(downloadError = null) }
        scheduleWarmUp()
    }

    /**
     * Escape hatch behind the "Start from scratch" card. Completes onboarding
     * without materialising a scenario (the first-launch seed remains the
     * default pipeline) and publishes the model hint through the
     * [TransientMessageRelay] so the user knows where to install a model later.
     */
    fun startFromScratch() {
        transientMessageRelay.post(SKIP_SNACKBAR_MESSAGE)
        viewModelScope.launch { settingsRepository.setHasCompletedOnboarding(true) }
    }

    /**
     * Records the user's LiteRT model override on the download step ("Or choose
     * another") and synchronously re-checks whether that file is installed.
     */
    fun pickLiteRtModel(model: OnboardingLiteRtModel) {
        installCheckJob?.cancel()
        downloadJob?.cancel()
        warmUpJob?.cancel()
        _state.update {
            it.copy(
                liteRtModel = model,
                downloadProgress = null,
                downloadError = null,
                installedModelId = null,
                isModelWarmed = false,
            )
        }
        installedModelPath = null
        checkIfPickedModelAlreadyInstalled(model)
    }

    /** Updates the bound custom-URL field; clears any prior download error. */
    fun onCustomDownloadUrlChanged(url: String) {
        _state.update { it.copy(customDownloadUrl = url, downloadError = null) }
    }

    /**
     * Starts a download for the currently-picked model and folds every
     * `DownloadState` into [OnboardingViewState]. No-op when a download is
     * already in flight.
     */
    fun startDownload() {
        if (_state.value.downloadProgress != null) return

        val picked = _state.value.liteRtModel
        val resolved = resolveDownloadTarget(picked) ?: return

        _state.update { it.copy(downloadProgress = 0f, downloadError = null) }
        markMilestone(OnboardingMilestone.MODEL_DOWNLOAD_STARTED)

        collectDownload(downloadManager.downloadModel(resolved.url, resolved.fileName), picked, resolved)
    }

    /**
     * Re-attaches to a download that is already running, so re-entering
     * onboarding (the flow the user lands back in until they finish it) shows
     * the live transfer instead of an idle step while the shade ticks along.
     *
     * Never starts anything: if nothing is running, this is a no-op.
     */
    private fun attachToRunningDownload() {
        viewModelScope.launch {
            // `firstOrNull`, not `first`: an empty stream is a perfectly
            // ordinary "nothing is downloading", and an exception here would
            // take the onboarding ViewModel down on construction.
            val active = downloadManager.observeActiveDownload().firstOrNull() ?: return@launch
            // A download the user started here in this session already has a
            // collector; attaching twice would double-report every frame.
            if (_state.value.downloadProgress != null) return@launch

            val preset = OnboardingModelCatalog.PRESETS.firstOrNull { it.fileName == active.fileName }
            val picked = OnboardingLiteRtModel.entries.firstOrNull { it.id == preset?.id }
                ?: OnboardingLiteRtModel.CustomUrl
            val resolved = ResolvedDownloadTarget(
                url = preset?.downloadUrl.orEmpty(),
                fileName = active.fileName,
            )
            _state.update { it.copy(liteRtModel = picked, downloadError = null) }
            collectDownload(downloadManager.observeDownload(active.fileName), picked, resolved)
        }
    }

    /**
     * Folds a download state stream into the view state. Shared by the "start
     * one" and "re-attach to one" paths so both behave identically.
     */
    private fun collectDownload(
        states: Flow<DownloadState>,
        picked: OnboardingLiteRtModel,
        resolved: ResolvedDownloadTarget,
    ) {
        downloadJob = states
            .onEach { downloadState -> handleDownloadEmission(downloadState, picked, resolved) }
            .catch { e ->
                _state.update {
                    it.copy(downloadProgress = null, downloadError = e.message ?: GENERIC_DOWNLOAD_ERROR)
                }
            }
            // A download cancelled elsewhere — the notification's Cancel action —
            // ends the stream with no terminal state. Without this the step would
            // sit on a frozen progress bar behind a disabled CTA forever.
            .onCompletion { _state.update { it.copy(downloadProgress = null) } }
            .launchIn(viewModelScope)
    }

    /**
     * Final-step action — persists the `hasCompletedOnboarding` flag. Warm-up is
     * intentionally NOT re-triggered here: the "Ready" CTA only enables once
     * `isModelWarmed == true`, so the handle is already loaded by the time the
     * user can tap it.
     */
    fun finishOnboarding() {
        viewModelScope.launch { settingsRepository.setHasCompletedOnboarding(true) }
    }

    /**
     * Skip button (visible on steps 1-3). Persists `hasCompletedOnboarding` and
     * publishes a snackbar hint through the [TransientMessageRelay].
     *
     * Skipping mid-download no longer stops the transfer — it runs as background
     * work — so the hint says so instead of pointing at Settings for a model
     * that is already on its way.
     */
    fun skipOnboarding() {
        val message = if (_state.value.downloadProgress != null) {
            DOWNLOAD_CONTINUES_MESSAGE
        } else {
            SKIP_SNACKBAR_MESSAGE
        }
        transientMessageRelay.post(message)
        viewModelScope.launch { settingsRepository.setHasCompletedOnboarding(true) }
    }

    private fun checkIfPickedModelAlreadyInstalled(model: OnboardingLiteRtModel) {
        val entry = OnboardingModelCatalog.entryById(model.id) ?: return
        installCheckJob = viewModelScope.launch {
            val installed = localModelRepository.findByFileName(entry.fileName) ?: return@launch
            // Re-verify the pick is still current (cooperative cancellation).
            if (_state.value.liteRtModel != model) return@launch
            installedModelPath = installed.path
            _state.update { it.copy(installedModelId = model.id) }
            scheduleWarmUp()
        }
    }

    private fun handleDownloadEmission(
        downloadState: DownloadState,
        picked: OnboardingLiteRtModel,
        resolved: ResolvedDownloadTarget,
    ) {
        when (downloadState) {
            is DownloadState.Pending -> _state.update { it.copy(downloadProgress = 0f) }
            is DownloadState.Downloading -> _state.update {
                it.copy(downloadProgress = downloadState.progress / PERCENT_DIVISOR)
            }
            is DownloadState.Success -> onDownloadSucceeded(downloadState, picked, resolved)
            is DownloadState.Error -> _state.update {
                it.copy(
                    downloadProgress = null,
                    downloadError = extractErrorMessage(downloadState.error),
                )
            }
        }
    }

    private fun extractErrorMessage(error: AppError): String {
        val message = (error as? AndroidModelDownloadManager.DownloadError)?.message
        return message?.takeIf { it.isNotBlank() } ?: GENERIC_DOWNLOAD_ERROR
    }

    private fun onDownloadSucceeded(
        downloadState: DownloadState.Success,
        picked: OnboardingLiteRtModel,
        resolved: ResolvedDownloadTarget,
    ) {
        installedModelPath = downloadState.fileUri
        _state.update { it.copy(downloadProgress = null, installedModelId = picked.id) }
        markMilestone(OnboardingMilestone.MODEL_DOWNLOAD_FINISHED)
        viewModelScope.launch {
            // The download worker owns registration (it has to — the transfer
            // outlives this ViewModel), so the row already exists. Activating it
            // stays here: it is a choice about *this* journey, and a download
            // the user walked away from should not silently swap their model.
            val registered = localModelRepository.findByFileName(resolved.fileName)
            if (registered != null) {
                localModelRepository.setActiveModel(registered.id)
            }
            scheduleWarmUp()
        }
    }

    /**
     * Warms the inference handle for the installed model — and, on a first-time
     * install that has never had a backend chosen, lets
     * [PrepareInferenceBackendUseCase] pick one first (see its KDoc for the
     * probe-then-verify-then-fall-back contract).
     *
     * Both call sites of this method — a finished download and a re-detected
     * install — funnel through here, so the acceleration decision is made
     * exactly once per journey regardless of which path reached the model.
     *
     * The `isCheckingAcceleration` flag is cleared in a `finally` so a failure
     * or a cancelled job can never strand the "Checking acceleration…" copy on
     * the Ready step.
     */
    private fun scheduleWarmUp() {
        warmUpJob?.cancel()
        val path = installedModelPath ?: return
        warmUpJob = viewModelScope.launch {
            try {
                val outcome = prepareInferenceBackendUseCase(
                    modelPath = path,
                    onAccelerationCheckStarted = { _state.update { it.copy(isCheckingAcceleration = true) } },
                )
                when (outcome) {
                    is PrepareInferenceBackendUseCase.Outcome.Warmed ->
                        _state.update { it.copy(isModelWarmed = true) }

                    is PrepareInferenceBackendUseCase.Outcome.Failed -> _state.update {
                        it.copy(downloadError = outcome.message ?: GENERIC_LOAD_ERROR)
                    }
                }
            } finally {
                _state.update { it.copy(isCheckingAcceleration = false) }
            }
        }
    }

    /**
     * Records one write-once onboarding marker, fire-and-forget.
     *
     * The timestamp is taken **here**, not inside the coroutine, so the recorded
     * value is when the event happened rather than when the dispatcher got to it.
     * Recording is opt-in-gated and absorbs its own storage failures downstream,
     * so a marker can never break the flow it observes.
     *
     * Two deliberate consequences of the write-once contract: a retried download
     * keeps the *first* attempt's start marker (the measurement is wall-clock
     * honest, retries included), and a journey where the model was already
     * installed records no download markers at all — its download interval is
     * then unknown, and time-to-value excluding the download equals the total.
     *
     * @param milestone The marker reached.
     * @param detail Optional marker payload (the scenario's pipeline id), or `null`.
     */
    private fun markMilestone(milestone: OnboardingMilestone, detail: String? = null) {
        val atMillis = System.currentTimeMillis()
        viewModelScope.launch { usageTelemetry.recordOnboardingMilestone(milestone, atMillis, detail) }
    }

    private fun resolveDownloadTarget(model: OnboardingLiteRtModel): ResolvedDownloadTarget? {
        val preset = OnboardingModelCatalog.entryById(model.id)
        if (preset != null) {
            return ResolvedDownloadTarget(url = preset.downloadUrl, fileName = preset.fileName)
        }
        val refusal = when (val link = CustomModelLink.parse(_state.value.customDownloadUrl)) {
            is CustomModelLink.Accepted -> return ResolvedDownloadTarget(url = link.url, fileName = link.fileName)
            CustomModelLink.Blank -> CUSTOM_URL_REQUIRED_MESSAGE
            CustomModelLink.NotLitertlm -> CUSTOM_URL_NOT_LITERTLM_MESSAGE
        }
        _state.update { it.copy(downloadError = refusal) }
        return null
    }

    /**
     * Projects a [SetUpScenarioUseCase.ScenarioSetup] into the catalog preview
     * shape, giving a routing node ([NodeType.INTENT_ROUTER] / `IF`) the green
     * accent so the recap reads like the editor.
     */
    private fun SetUpScenarioUseCase.ScenarioSetup.toPreview(): OnboardingDefaultPipelinePreview {
        val accent = nodeTypeNames.firstOrNull { it == "INTENT_ROUTER" }
            ?: nodeTypeNames.firstOrNull { it == "IF" }
        return OnboardingDefaultPipelinePreview(
            nodes = nodeTypeNames,
            nodeCount = nodeCount,
            edgeCount = edgeCount,
            accentNodeName = accent,
        )
    }

    /** Compact tuple bundling the inputs the data-layer download manager needs. */
    private data class ResolvedDownloadTarget(val url: String, val fileName: String)

    companion object {
        /** Skip / escape snackbar copy — referenced by tests and `OnboardingScreen`. */
        const val SKIP_SNACKBAR_MESSAGE: String = "You can install a model from Settings → Models"

        /** Skip copy while a download is in flight — it keeps running without the screen. */
        const val DOWNLOAD_CONTINUES_MESSAGE: String = "Download continues in the background"

        /** Fallback copy when an unknown exception breaks the download stream. */
        private const val GENERIC_DOWNLOAD_ERROR: String = "Download failed."

        /** Fallback copy when `LoadModelUseCase` returns a result without a message. */
        private const val GENERIC_LOAD_ERROR: String = "Failed to load the model."

        /** Fallback copy when scenario set-up fails without a message. */
        private const val GENERIC_SETUP_ERROR: String = "Couldn't set up this scenario."

        /** Surfaced when the user taps the CTA on the Custom URL row with an empty input. */
        private const val CUSTOM_URL_REQUIRED_MESSAGE: String = "Enter a model URL to download."

        /** Surfaced, before anything downloads, for a custom URL whose file is not `.litertlm`. */
        private const val CUSTOM_URL_NOT_LITERTLM_MESSAGE: String =
            "This link isn't a .litertlm file. Only .litertlm models run on the phone."

        /** Divisor turning a 0..100 Int progress into a 0f..1f float for `OnboardingViewState`. */
        private const val PERCENT_DIVISOR: Float = 100f
    }
}
