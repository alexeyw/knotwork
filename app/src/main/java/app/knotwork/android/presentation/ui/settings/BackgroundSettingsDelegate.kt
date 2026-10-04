package app.knotwork.android.presentation.ui.settings

import app.knotwork.android.domain.models.EntrySurface
import app.knotwork.android.domain.repositories.EntryPointSettings
import app.knotwork.android.domain.repositories.ExternalAutomationJournalRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.RunSettings
import app.knotwork.android.domain.usecases.SetSurfacePipelineUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Background-&-triggers category delegate of [SettingsViewModel].
 *
 * Owns the two notification toggles, the checkpoint-resume and
 * background-approval windows, and the per-surface entry-point pipeline bindings
 * (share target, Quick Settings tile, external automation) plus the
 * bindable-pipeline list backing their pickers. Observes the persisted flows into
 * the shared [state] and routes edits back through [runSettings] and
 * [entryPointSettings]. Shares
 * the ViewModel's [scope] and single [SettingsUiState] reducer.
 *
 * The external-automation switch is the one setting here that does not write
 * straight through: switching it **on** stages a consent decision instead, and
 * only [confirmExternalAutomationConsent] persists it. See
 * [setExternalAutomationEnabled].
 *
 * @property scope The ViewModel's `viewModelScope`.
 * @property state The ViewModel's single source-of-truth state flow.
 * @property runSettings Persistence for the notification toggle and the two windows.
 * @property entryPointSettings Persistence for the surface bindings, share reuse and the
 *   external-automation switch.
 * @property pipelineRepository Source of the bindable-pipeline list for the pickers.
 * @property setSurfacePipelineUseCase Single dispatch point for writing a surface binding.
 * @property externalAutomationJournal Source of the newest inbound external
 *   request, summarised on the Background category row.
 */
class BackgroundSettingsDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<SettingsUiState>,
    private val runSettings: RunSettings,
    private val entryPointSettings: EntryPointSettings,
    private val pipelineRepository: PipelineRepository,
    private val setSurfacePipelineUseCase: SetSurfacePipelineUseCase,
    private val externalAutomationJournal: ExternalAutomationJournalRepository,
) {

    init {
        runSettings.scheduledTaskNotificationsEnabled.onEach { value ->
            state.update { it.copy(scheduledTaskNotificationsEnabled = value) }
        }.launchIn(scope)

        runSettings.resumeMaxAgeHours.onEach { value ->
            state.update { it.copy(resumeMaxAgeHours = value) }
        }.launchIn(scope)

        runSettings.backgroundApprovalWindowHours.onEach { value ->
            state.update { it.copy(backgroundApprovalWindowHours = value) }
        }.launchIn(scope)

        entryPointSettings.shareTargetPipelineId.onEach { value ->
            state.update { it.copy(shareTargetPipelineId = value) }
        }.launchIn(scope)

        entryPointSettings.shareReuseSession.onEach { value ->
            state.update { it.copy(shareReuseSession = value) }
        }.launchIn(scope)

        entryPointSettings.quickSettingsTilePipelineId.onEach { value ->
            state.update { it.copy(quickSettingsTilePipelineId = value) }
        }.launchIn(scope)

        entryPointSettings.externalAutomationEnabled.onEach { value ->
            state.update { it.copy(externalAutomationEnabled = value) }
        }.launchIn(scope)

        entryPointSettings.externalAutomationPipelineId.onEach { value ->
            state.update { it.copy(externalAutomationPipelineId = value) }
        }.launchIn(scope)

        // Only the newest row reaches the settings state. The journal is capped at
        // 2 000 rows and its write rate is set by whatever third-party app is
        // installed, so holding the whole list here to render one subtitle would
        // put the noisiest table in the app into the settings screen's state.
        externalAutomationJournal.observeAll()
            .map { entries -> entries.firstOrNull() }
            .distinctUntilChanged()
            .onEach { latest -> state.update { it.copy(externalAutomationLatestRequest = latest) } }
            .launchIn(scope)

        pipelineRepository.getAllPipelines().onEach { pipelines ->
            state.update { current ->
                current.copy(bindablePipelines = pipelines.map { PipelineBindingOption(it.id, it.name) })
            }
        }.launchIn(scope)
    }

    /**
     * Persists (or clears, with `null`) the pipeline bound to [surface] through
     * the shared [SetSurfacePipelineUseCase] dispatch point.
     */
    fun setSurfacePipeline(surface: EntrySurface, pipelineId: String?) {
        scope.launch { setSurfacePipelineUseCase(surface, pipelineId) }
    }

    /** Persists the "notify me when a scheduled task fires" toggle. */
    fun setScheduledTaskNotificationsEnabled(enabled: Boolean) {
        scope.launch { runSettings.setScheduledTaskNotificationsEnabled(enabled) }
    }

    /**
     * Handles a move of the external-automation master switch.
     *
     * Asymmetric on purpose. Switching it **on** widens the app's attack surface
     * to code the user did not write, so it only *stages* the consent dialog and
     * the persisted value does not move until
     * [confirmExternalAutomationConsent]. Switching it **off** is written
     * immediately and asks nothing: closing an entry point is never the decision
     * that needs a second thought, and a confirmation on the way out would make
     * the promise "one tap turns it off" false.
     *
     * @param enabled The switch position the user asked for.
     */
    fun setExternalAutomationEnabled(enabled: Boolean) {
        if (enabled) {
            state.update { it.copy(pendingExternalAutomationConsent = true) }
        } else {
            state.update { it.copy(pendingExternalAutomationConsent = false) }
            scope.launch { entryPointSettings.setExternalAutomationEnabled(false) }
        }
    }

    /** Persists the switched-on state the user has just consented to. */
    fun confirmExternalAutomationConsent() {
        state.update { it.copy(pendingExternalAutomationConsent = false) }
        scope.launch { entryPointSettings.setExternalAutomationEnabled(true) }
    }

    /** Drops the staged consent; the contract stays exactly as it was. */
    fun dismissExternalAutomationConsent() {
        state.update { it.copy(pendingExternalAutomationConsent = false) }
    }

    /** Persists the "keep every share in one Shared chat" toggle. */
    fun setShareReuseSession(reuse: Boolean) {
        scope.launch { entryPointSettings.setShareReuseSession(reuse) }
    }

    /**
     * Persists the checkpoint-resume window (hours). The repository coerces the
     * value into the sanctioned 1–168 range.
     *
     * @param hours The new window picked on the slider.
     */
    fun setResumeMaxAgeHours(hours: Int) {
        scope.launch { runSettings.setResumeMaxAgeHours(hours) }
    }

    /**
     * Persists the background-approval window (hours) during which a run parked
     * on an unanswered HITL request waits for the user's response. The
     * repository coerces the value into the sanctioned 1–168 range.
     *
     * @param hours The new window picked on the slider.
     */
    fun setBackgroundApprovalWindowHours(hours: Int) {
        scope.launch { runSettings.setBackgroundApprovalWindowHours(hours) }
    }
}
