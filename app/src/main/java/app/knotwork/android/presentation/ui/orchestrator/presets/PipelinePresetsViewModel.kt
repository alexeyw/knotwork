package app.knotwork.android.presentation.ui.orchestrator.presets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.R
import app.knotwork.android.di.IoDispatcher
import app.knotwork.android.domain.models.PipelinePreset
import app.knotwork.android.domain.models.PresetCategory
import app.knotwork.android.domain.pipelineio.PipelinePresetJsonSerializer
import app.knotwork.android.domain.repositories.PipelinePresetRepository
import app.knotwork.android.domain.usecases.LoadPipelineFromPresetUseCase
import app.knotwork.android.domain.usecases.SavePipelineAsPresetUseCase
import app.knotwork.android.presentation.state.TransientMessageRelay
import app.knotwork.android.presentation.ui.common.ExportDocumentHandOff
import app.knotwork.android.presentation.ui.common.PickedDocument
import app.knotwork.android.presentation.ui.common.UiText
import app.knotwork.android.presentation.ui.common.UnwrittenDocument
import app.knotwork.android.presentation.ui.common.discardUnwritten
import app.knotwork.android.presentation.ui.orchestrator.OrchestratorErrorText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel backing both `PresetPickerSheet` (modal bottom sheet on the
 * pipeline library) and `PipelinePresetsManagerScreen` (full-screen
 * manager reached from More → Library).
 *
 * Owns:
 *
 * - The two-tier preset catalogue (bundled / user) observed from
 *   [PipelinePresetRepository].
 * - Tab + category-chip selection state.
 * - The `loadFromPreset` flow that materialises a preset into a pipeline
 *   row through [LoadPipelineFromPresetUseCase] and surfaces the new
 *   pipeline id via [PipelinePresetsUiState.pendingPipelineIdFromPreset]
 *   so the host can navigate the user into the editor.
 * - Delete + JSON export of user presets.
 *
 * `SavePipelineAsPresetUseCase` is **not** invoked from this ViewModel —
 * the Save-as-preset dialog lives on the pipeline library / editor and
 * dispatches through [OrchestratorPresetsDelegate.saveCurrentAsPreset] /
 * [OrchestratorPresetsDelegate.saveAsPresetFromLibrary] so the dialog stays
 * close to the graph it is packaging.
 */
@HiltViewModel
class PipelinePresetsViewModel @Inject constructor(
    private val pipelinePresetRepository: PipelinePresetRepository,
    private val loadPipelineFromPresetUseCase: LoadPipelineFromPresetUseCase,
    @Suppress("unused") private val savePipelineAsPresetUseCase: SavePipelineAsPresetUseCase,
    private val transientMessageRelay: TransientMessageRelay,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    /**
     * The preset the manager's document picker is open for, from the picker's launch
     * ([holdForExport]) until the picked document arrives ([exportTo]) or the pick is
     * cancelled. Held here so a rotation while the picker is open keeps it: the screen
     * used to keep it in composition, lost it, and left the file the picker had
     * created empty.
     */
    private var awaitingExport: PipelinePreset? = null

    private val _uiState = MutableStateFlow(PipelinePresetsUiState())

    /**
     * Aggregated picker / manager state. Subscribed by both
     * `PresetPickerSheet` and `PipelinePresetsManagerScreen`.
     */
    val uiState: StateFlow<PipelinePresetsUiState> = _uiState.asStateFlow()

    init {
        observeBundledPresets()
        observeUserPresets()
    }

    private fun observeBundledPresets() {
        viewModelScope.launch {
            pipelinePresetRepository.getBundledPresets()
                .catch { e -> _uiState.update { it.copy(errorMessage = throwableAsUiText(e)) } }
                .collect { presets ->
                    _uiState.update { it.copy(bundledPresets = presets) }
                }
        }
    }

    private fun observeUserPresets() {
        viewModelScope.launch {
            pipelinePresetRepository.getUserPresets()
                .catch { e -> _uiState.update { it.copy(errorMessage = throwableAsUiText(e)) } }
                .collect { presets ->
                    _uiState.update { it.copy(userPresets = presets) }
                }
        }
    }

    /**
     * Switches the active tab. Resets the category-chip selection because
     * the visible chip set differs between tabs (a chip valid under
     * `Bundled` may not match any `Mine` preset).
     */
    fun selectTab(tab: PresetPickerTab) {
        _uiState.update { it.copy(activeTab = tab, selectedCategory = null) }
    }

    /**
     * Sets the category-chip filter. Pass `null` to clear the filter and
     * show every preset under the active tab.
     */
    fun selectCategory(category: PresetCategory?) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    /**
     * Materialises [presetId] into a new pipeline through
     * [LoadPipelineFromPresetUseCase]. On success the new pipeline id is
     * stashed on [PipelinePresetsUiState.pendingPipelineIdFromPreset] so
     * the host can route the user into the editor; on failure an error
     * message is surfaced for the host's Snackbar.
     */
    fun loadFromPreset(presetId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = loadPipelineFromPresetUseCase(presetId)
            _uiState.update { state ->
                val newId = result.getOrNull()
                state.copy(
                    isLoading = false,
                    pendingPipelineIdFromPreset = newId,
                    errorMessage = result.exceptionOrNull()?.let { throwableAsUiText(it) },
                    feedbackMessage = if (newId != null) {
                        UiText(R.string.orchestrator_preset_picker_loaded)
                    } else {
                        state.feedbackMessage
                    },
                )
            }
        }
    }

    /**
     * Deletes the user preset with [presetId]. No-op for bundled presets
     * — the repository drops the call because they are read-only.
     */
    fun deleteUserPreset(presetId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                pipelinePresetRepository.deleteUserPreset(presetId)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        feedbackMessage = UiText(R.string.orchestrator_preset_manager_deleted),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = throwableAsUiText(e))
                }
            }
        }
    }

    /**
     * Renames the user preset identified by [presetId] to [newName]. The
     * name is trimmed; blank names are rejected with an inline error.
     * Bundled presets cannot be renamed and the call is silently ignored.
     */
    fun renameUserPreset(presetId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            _uiState.update {
                it.copy(errorMessage = UiText(R.string.orchestrator_preset_manager_rename_blank))
            }
            return
        }
        val existing = _uiState.value.userPresets.firstOrNull { it.id == presetId } ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                pipelinePresetRepository.saveUserPreset(existing.copy(name = trimmed))
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        feedbackMessage = UiText(R.string.orchestrator_preset_manager_renamed),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = throwableAsUiText(e))
                }
            }
        }
    }

    /**
     * Renders [preset] as a JSON document suitable for sharing or for
     * dropping into `assets/presets/pipelines/`. Pure synchronous call;
     * [exportTo] writes it into the document the user picked.
     */
    fun exportPresetToJson(preset: PipelinePreset): String = PipelinePresetJsonSerializer.serialize(preset)

    /**
     * Holds [preset] while the manager's document picker is open for it.
     *
     * @param preset The preset the user chose to export.
     */
    fun holdForExport(preset: PipelinePreset) {
        awaitingExport = preset
    }

    /** Drops the held preset: the user backed out of the document picker. */
    fun cancelExport() {
        awaitingExport = null
    }

    /**
     * Writes the held preset into [picked], the document the user picked, and confirms
     * it; a failure surfaces through [PipelinePresetsUiState.errorMessage].
     *
     * The picker has already created the document, so an export that cannot write a
     * byte removes it again: the preset did not survive a process death while the
     * picker was open, or the document would not open. When its provider will not
     * delete it, the message names the empty file.
     *
     * @param picked The document the picker created.
     */
    fun exportTo(picked: PickedDocument) {
        val preset = awaitingExport
        awaitingExport = null
        viewModelScope.launch {
            val outputStream = if (preset == null) null else withContext(ioDispatcher) { picked.openOutput() }
            val outcome = when {
                preset == null || outputStream == null -> when (val left = picked.discardUnwritten(ioDispatcher)) {
                    UnwrittenDocument.Removed -> ExportOutcome.Failed
                    is UnwrittenDocument.Left -> ExportOutcome.FileLeft(left.fileName)
                }
                ExportDocumentHandOff.write(outputStream, exportPresetToJson(preset), ioDispatcher) ->
                    ExportOutcome.Exported
                else -> ExportOutcome.Failed
            }
            _uiState.update { state ->
                when (outcome) {
                    ExportOutcome.Exported -> state.copy(
                        feedbackMessage = UiText(R.string.orchestrator_preset_exported),
                    )
                    ExportOutcome.Failed -> state.copy(
                        errorMessage = UiText(R.string.orchestrator_preset_export_failed),
                    )
                    is ExportOutcome.FileLeft ->
                        state.copy(errorMessage = UiText.of(R.string.export_empty_file_left, outcome.fileName))
                }
            }
        }
    }

    /** How a preset export into a picked document ended. */
    private sealed interface ExportOutcome {
        /** The preset was written. */
        data object Exported : ExportOutcome

        /** Nothing usable was written; the picked file is gone or partly written. */
        data object Failed : ExportOutcome

        /** Nothing was written and the empty file is left. */
        data class FileLeft(val fileName: String) : ExportOutcome
    }

    /**
     * Looks up a preset (bundled or user-owned) by id without touching
     * the database — the in-memory list emitted by the repository is the
     * source of truth for the surface. Used by the manager screen's
     * SAF-driven export flow.
     */
    fun findPreset(presetId: String): PipelinePreset? {
        val state = _uiState.value
        return state.userPresets.firstOrNull { it.id == presetId }
            ?: state.bundledPresets.firstOrNull { it.id == presetId }
    }

    /**
     * Acknowledges the editor-navigation hand-off. Call from the picker
     * host's `LaunchedEffect` after invoking the navigation callback so
     * a configuration change does not re-trigger the navigation.
     */
    fun consumePendingPipelineNavigation() {
        _uiState.update { it.copy(pendingPipelineIdFromPreset = null) }
    }

    /**
     * Surfaces a one-shot message through the activity-level snackbar host.
     *
     * The host lives above the NavGraph, so a message survives the screen being
     * navigated away from — the same route the Files screen takes. Exposed on
     * the ViewModel rather than threaded through navigation as a parameter,
     * following `OnboardingViewModel`.
     *
     * @param message The sentence to show.
     */
    fun announce(message: String) {
        transientMessageRelay.post(UiText.Dynamic(message))
    }

    /** Clears the error channel after the host's Snackbar has shown it. */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /** Clears the feedback channel after the host's Snackbar has shown it. */
    fun clearFeedback() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    private fun throwableAsUiText(e: Throwable): UiText = OrchestratorErrorText.forThrowable(e)
}
