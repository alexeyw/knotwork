package app.knotwork.android.presentation.ui.triggers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.R
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.TriggerRepository
import app.knotwork.android.domain.usecases.ExportTriggerJournalUseCase
import app.knotwork.android.domain.usecases.ObserveTriggerHealthInputsUseCase
import app.knotwork.android.domain.usecases.ObserveTriggerJournalUseCase
import app.knotwork.android.domain.usecases.SaveTriggerUseCase
import app.knotwork.android.domain.usecases.SyncTriggersUseCase
import app.knotwork.android.domain.usecases.TriggerDraftIntent
import app.knotwork.android.presentation.ui.common.JournalExportDelegate
import app.knotwork.android.presentation.ui.common.JournalExportEvent
import app.knotwork.android.presentation.ui.common.TRIGGER_JOURNAL_EXPORT_STEM
import app.knotwork.android.presentation.ui.common.UiText
import app.knotwork.android.presentation.ui.navigation.NavRoutes
import app.knotwork.design.screens.triggers.TriggerConditionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Triggers screen. Observes the trigger list and the
 * available pipelines (for the binding picker), drives the full-screen editor
 * and the delete dialog, and — critically — re-syncs the background runtime
 * after every mutation so a trigger created / edited / enabled / deleted while
 * the app is running takes effect immediately rather than on the next cold
 * start (see [SyncTriggersUseCase]).
 *
 * Opened with a trigger id in its route ([NavRoutes.TRIGGERS_OPEN_ARG]) — an
 * overdue trigger's notification — it opens that trigger's detail once.
 */
@HiltViewModel
@Suppress("TooManyFunctions") // List + detail + editor combine many discrete callbacks; collapsing hides intent.
class TriggersViewModel @Inject constructor(
    private val triggerRepository: TriggerRepository,
    private val pipelineRepository: PipelineRepository,
    private val saveTrigger: SaveTriggerUseCase,
    private val syncTriggers: SyncTriggersUseCase,
    private val observeTriggerHealthInputs: ObserveTriggerHealthInputsUseCase,
    private val observeTriggerJournal: ObserveTriggerJournalUseCase,
    exportTriggerJournal: ExportTriggerJournalUseCase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TriggersUiState())
    val uiState: StateFlow<TriggersUiState> = _uiState.asStateFlow()

    /**
     * The journal export half of this screen.
     *
     * Exports the **whole** evaluation journal, not the trigger a detail sheet
     * happens to have open, which is why the action lives on the list: the
     * questions a journal is read to answer ("was there a day with no evaluation
     * at all?") span triggers, and a per-trigger file could not answer them.
     */
    val journalExport: JournalExportDelegate = JournalExportDelegate(
        scope = viewModelScope,
        fileNameStem = TRIGGER_JOURNAL_EXPORT_STEM,
        buildDocument = { label -> exportTriggerJournal(label) },
    )

    /** One-shot journal-export outcomes, rendered by the screen. */
    val journalExportEvents: SharedFlow<JournalExportEvent> get() = journalExport.events

    /** The single live collector of the trigger + pipeline + health flows; replaced on retry. */
    private var observeJob: Job? = null

    /** The live collector of the open detail trigger's journal; runs once for the VM's life. */
    private var journalJob: Job? = null

    init {
        observe()
        observeDetailJournal()
        savedStateHandle.get<String>(NavRoutes.TRIGGERS_OPEN_ARG)?.takeIf { it.isNotBlank() }?.let { id ->
            openDetail(id)
            // Once: a restored screen the user has since closed the detail on stays closed.
            savedStateHandle[NavRoutes.TRIGGERS_OPEN_ARG] = ""
        }
    }

    private fun observe() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            combine(
                triggerRepository.observeTriggers(),
                pipelineRepository.getAllPipelines(),
                observeTriggerHealthInputs(),
            ) { triggers, pipelines, healthInputs -> Triple(triggers, pipelines, healthInputs) }
                .catch { e -> _uiState.update { it.copy(isLoading = false, loadError = e.toUiText()) } }
                .collect { (triggers, pipelines, healthInputs) ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            triggers = triggers,
                            pipelines = pipelines,
                            healthInputs = healthInputs,
                            loadError = null,
                        )
                    }
                }
        }
    }

    /**
     * Follows the open detail trigger's journal. When [TriggersUiState.detailTriggerId]
     * changes, switches to that trigger's evaluation stream (or emits `null` when
     * the detail closes). A stale emission arriving after the detail changed is
     * discarded by re-checking the id at merge time.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeDetailJournal() {
        journalJob = viewModelScope.launch {
            _uiState
                .map { it.detailTriggerId }
                .distinctUntilChanged()
                .flatMapLatest { id ->
                    if (id == null) flowOf(null) else observeTriggerJournal(id).map { journal -> id to journal }
                }
                .collect { emission ->
                    _uiState.update { state ->
                        when {
                            emission == null -> state
                            emission.first == state.detailTriggerId -> state.copy(detailJournal = emission.second)
                            else -> state // stale emission for a since-changed detail
                        }
                    }
                }
        }
    }

    /** Re-runs the load and clears the previous load error. */
    fun retry() {
        _uiState.update { it.copy(loadError = null) }
        observe()
    }

    /** Clears the transient error after it has been surfaced (e.g. by a snackbar). */
    fun clearTransientError() = _uiState.update { it.copy(transientError = null) }

    /** Opens the overflow menu for the row with [id]. */
    fun openRowMenu(id: String) = _uiState.update { it.copy(openMenuTriggerId = id) }

    /** Dismisses any open overflow menu. */
    fun dismissRowMenu() = _uiState.update { it.copy(openMenuTriggerId = null) }

    /** Toggles the enabled flag of the trigger with [id] and re-syncs the runtime. */
    fun toggleEnabled(id: String) {
        val trigger = _uiState.value.triggers.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            try {
                triggerRepository.setEnabled(id, !trigger.enabled)
                syncTriggers()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(transientError = e.toUiText()) }
            }
        }
    }

    // ── Editor ──────────────────────────────────────────────────────────────

    /** Opens the editor for a fresh draft. */
    fun openNewTrigger() {
        _uiState.update { it.copy(openMenuTriggerId = null, editor = TriggerEditorDraft.newDraft()) }
    }

    /** Opens the editor for the trigger with [id]. */
    fun openEditTrigger(id: String) {
        val trigger = _uiState.value.triggers.firstOrNull { it.id == id } ?: return
        _uiState.update { it.copy(openMenuTriggerId = null, editor = TriggerEditorDraft.fromTrigger(trigger)) }
    }

    /**
     * Whole-row tap opens the trigger's **detail** surface (identity + journal),
     * from which the editor is reached via Edit. Resets the journal to its loading
     * state until the first emission for this trigger arrives.
     */
    fun onRowClick(id: String) = openDetail(id)

    /** Opens the detail surface for the trigger with [id]. */
    fun openDetail(id: String) {
        _uiState.update { it.copy(openMenuTriggerId = null, detailTriggerId = id, detailJournal = null) }
    }

    /** Closes the detail surface, returning to the list. */
    fun closeDetail() = _uiState.update { it.copy(detailTriggerId = null, detailJournal = null) }

    /** Opens the editor for the currently-open detail trigger (Edit action on detail). */
    fun editFromDetail() {
        _uiState.value.detailTriggerId?.let(::openEditTrigger)
    }

    /** Closes the editor without persisting. */
    fun closeEditor() = _uiState.update { it.copy(editor = null) }

    /** Updates the editor draft's Name. */
    fun onNameChange(value: String) = updateDraft { it.copy(name = value) }

    /** Switches the editor's condition type. */
    fun onTypeChange(type: TriggerConditionType) = updateDraft { it.copy(type = type) }

    /** Selects an interval preset (leaving Custom mode). */
    fun onIntervalPreset(minutes: Long) =
        updateDraft { it.copy(intervalCustom = false, intervalPresetMinutes = minutes) }

    /** Switches the interval control into Custom mode, seeding a default amount. */
    fun onIntervalCustomToggle() = updateDraft { draft ->
        draft.copy(
            intervalCustom = true,
            intervalCustomAmount = draft.intervalCustomAmount.ifBlank { TriggerEditorDraft.CUSTOM_SEED.toString() },
        )
    }

    /** Updates the Custom interval amount, keeping digits only. */
    fun onIntervalCustomAmountChange(value: String) =
        updateDraft { it.copy(intervalCustomAmount = value.filter(Char::isDigit)) }

    /** Switches the Custom interval unit between minutes and hours. */
    fun onIntervalUnitChange(hours: Boolean) = updateDraft { it.copy(intervalCustomUnitHours = hours) }

    /** Sets the Daily condition's time of day. */
    fun onDailyTimeChange(hour: Int, minute: Int) = updateDraft { it.copy(dailyHour = hour, dailyMinute = minute) }

    /** Toggles the Network condition's Wi-Fi-only flag. */
    fun onWifiOnlyToggle() = updateDraft { it.copy(wifiOnly = !it.wifiOnly) }

    /**
     * Adds a Wi-Fi network name (SSID) to the Network condition's scope.
     *
     * Trims the input and ignores blanks and case-insensitive duplicates so the
     * chip list stays clean. Adding the first SSID narrows the trigger to the
     * named networks only.
     *
     * @param ssid The network name to add.
     */
    fun onSsidAdd(ssid: String) {
        val trimmed = ssid.trim()
        if (trimmed.isEmpty()) return
        updateDraft { draft ->
            if (draft.ssids.any { it.equals(trimmed, ignoreCase = true) }) {
                draft
            } else {
                draft.copy(ssids = draft.ssids + trimmed)
            }
        }
    }

    /**
     * Removes a Wi-Fi network name (SSID) from the Network condition's scope.
     *
     * Removing the last SSID widens the trigger back to any-Wi-Fi / any-network.
     *
     * @param ssid The network name to remove.
     */
    fun onSsidRemove(ssid: String) = updateDraft { draft ->
        draft.copy(ssids = draft.ssids.filterNot { it == ssid })
    }

    /** Binds (or unbinds, when [pipelineId] is `null`) the editor's pipeline. */
    fun onPipelineSelect(pipelineId: String?) = updateDraft { it.copy(pipelineId = pipelineId) }

    /** Updates the editor draft's input prompt. */
    fun onPromptChange(value: String) = updateDraft { it.copy(prompt = value) }

    /** Toggles the editor draft's enabled flag. */
    fun onEnabledToggle() = updateDraft { it.copy(enabled = !it.enabled) }

    /**
     * Persists the editor draft as a trigger and re-syncs the runtime. The
     * trigger's lifecycle fields (armed / lastFiredAt / createdAt) are derived by
     * [SaveTriggerUseCase], not by this layer.
     */
    fun saveEditor() {
        val draft = _uiState.value.editor ?: return
        if (!draft.canSave) return
        val intent = TriggerDraftIntent(
            id = draft.id,
            name = draft.name.trim(),
            condition = draft.resolveCondition(),
            pipelineId = draft.pipelineId,
            prompt = draft.prompt.trim(),
            enabled = draft.enabled,
        )
        viewModelScope.launch {
            try {
                saveTrigger(intent)
                syncTriggers()
                _uiState.update { it.copy(editor = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(transientError = e.toUiText()) }
            }
        }
    }

    // ── Delete ──────────────────────────────────────────────────────────────

    /** Opens the delete dialog for the trigger with [id]. */
    fun requestDelete(id: String) {
        val trigger = _uiState.value.triggers.firstOrNull { it.id == id } ?: return
        _uiState.update {
            it.copy(openMenuTriggerId = null, deleteTarget = TriggerDeleteTarget(id = id, name = trigger.name))
        }
    }

    /** Confirms the pending delete, removes the trigger, and re-syncs the runtime. */
    fun confirmDelete() {
        val target = _uiState.value.deleteTarget ?: return
        // Close any surface still pointing at the trigger being deleted — the
        // detail (which would resolve to a now-missing trigger) and, critically,
        // the editor: the shared delete dialog can be launched from the editor, and
        // leaving its draft open would let a later Save re-upsert the same id and
        // resurrect the trigger the user just deleted.
        _uiState.update {
            val closeDetail = it.detailTriggerId == target.id
            val closeEditor = it.editor?.id == target.id
            it.copy(
                deleteTarget = null,
                editor = if (closeEditor) null else it.editor,
                detailTriggerId = if (closeDetail) null else it.detailTriggerId,
                detailJournal = if (closeDetail) null else it.detailJournal,
            )
        }
        viewModelScope.launch {
            try {
                triggerRepository.deleteTrigger(target.id)
                syncTriggers()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(transientError = e.toUiText()) }
            }
        }
    }

    /** Dismisses the delete dialog without deleting. */
    fun cancelDelete() = _uiState.update { it.copy(deleteTarget = null) }

    private inline fun updateDraft(transform: (TriggerEditorDraft) -> TriggerEditorDraft) {
        _uiState.update { state -> state.copy(editor = state.editor?.let(transform)) }
    }

    private fun Throwable.toUiText(): UiText =
        message?.let(UiText::Dynamic) ?: UiText(R.string.errors_generic_unexpected)
}
