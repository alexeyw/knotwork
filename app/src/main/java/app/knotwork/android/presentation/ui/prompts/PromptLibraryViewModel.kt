package app.knotwork.android.presentation.ui.prompts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.R
import app.knotwork.android.domain.constants.PromptPresetConstants
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PromptPackCandidate
import app.knotwork.android.domain.models.PromptPackImportNotes
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.prompt.PromptVariableProvider
import app.knotwork.android.domain.repositories.PromptPresetRepository
import app.knotwork.android.domain.services.DefaultNames
import app.knotwork.android.domain.usecases.SavePromptAsPresetUseCase
import app.knotwork.android.domain.usecases.promptpack.ExportPromptPackUseCase
import app.knotwork.android.domain.usecases.promptpack.ImportPromptPackUseCase
import app.knotwork.android.domain.usecases.promptpack.PromptPackCollisionChoice
import app.knotwork.android.domain.usecases.promptpack.PromptPackImportResult
import app.knotwork.android.domain.usecases.promptpack.ResolvePromptPackCollisionUseCase
import app.knotwork.android.presentation.ui.common.UiText
import app.knotwork.android.presentation.ui.orchestrator.OrchestratorErrorText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Prompt Library screen. Sources presets from
 * [PromptPresetRepository], so the same catalogue surfaced in the editor
 * picker is editable here too.
 *
 * Bundled presets are read-only. User presets can be edited (in-place
 * upsert via [SavePromptAsPresetUseCase] with an `existingId`), duplicated
 * (`(copy)` suffix), and deleted (through the repository).
 */
@HiltViewModel
@Suppress("TooManyFunctions") // Library + editor combine many discrete callbacks; collapsing hides intent.
class PromptLibraryViewModel @Inject constructor(
    private val promptPresetRepository: PromptPresetRepository,
    private val savePromptAsPresetUseCase: SavePromptAsPresetUseCase,
    private val promptTemplateEngine: PromptTemplateEngine,
    private val promptVariableProviders: Set<@JvmSuppressWildcards PromptVariableProvider>,
    private val importPromptPackUseCase: ImportPromptPackUseCase,
    private val resolvePromptPackCollisionUseCase: ResolvePromptPackCollisionUseCase,
    private val exportPromptPackUseCase: ExportPromptPackUseCase,
    private val defaultNames: DefaultNames,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        PromptLibraryUiState(availableVariables = computeAvailableVariables(promptVariableProviders)),
    )
    val uiState: StateFlow<PromptLibraryUiState> = _uiState.asStateFlow()

    init {
        loadPresets()
    }

    /**
     * Re-runs the preset load flow and clears the previous `errorMessage`.
     * Wired to the catalog `PromptLibraryContent` error-state Retry CTA so a
     * failed load is recoverable in-place.
     */
    fun retry() {
        _uiState.update { it.copy(errorMessage = null) }
        loadPresets()
    }

    private fun loadPresets() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            combine(
                promptPresetRepository.getBundledPresets(),
                promptPresetRepository.getUserPresets(),
            ) { bundled, user -> bundled to user }
                .catch { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = e.message?.let(UiText::Dynamic)
                                ?: UiText(R.string.errors_generic_unexpected),
                        )
                    }
                }
                .collect { (bundled, user) ->
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            bundledPresets = bundled,
                            userPresets = user,
                            errorMessage = null,
                        )
                    }
                }
        }
    }

    /**
     * Deletes the user-saved preset with [id]. No-op for bundled presets
     * — those ship inside the APK and are read-only; the catalog row's
     * delete icon is still wired to this method, so we short-circuit
     * silently rather than surface an error for a UX that's already
     * unambiguous via the "bundled" connotation.
     */
    fun deletePrompt(id: String) {
        val isBundled = _uiState.value.bundledPresets.any { it.id == id }
        if (isBundled) return
        viewModelScope.launch {
            try {
                promptPresetRepository.deleteUserPreset(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = e.message?.let(UiText::Dynamic)
                            ?: UiText(R.string.errors_generic_unexpected),
                    )
                }
            }
        }
    }

    /**
     * Renders [template] through [PromptTemplateEngine] and exposes the resulting
     * segments via [PromptLibraryUiState.previewState] for display in the bottom sheet.
     */
    fun requestPromptPreview(template: String) {
        _uiState.update { it.copy(previewState = PromptPreviewState.Loading) }
        viewModelScope.launch {
            val segments = promptTemplateEngine.renderSegments(
                template,
                promptVariableProviders.toList(),
            )
            _uiState.update { it.copy(previewState = PromptPreviewState.Ready(segments)) }
        }
    }

    /** Closes the prompt-preview bottom sheet, returning the UI to its idle state. */
    fun dismissPromptPreview() {
        _uiState.update { it.copy(previewState = PromptPreviewState.Hidden) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Imports the picked prompt file.
     *
     * @param document Full text read off the picked document.
     * @param fileName The document's display name; its stem becomes the
     *   prompt's id when the file does not name one, so re-importing the
     *   same file updates rather than duplicates.
     */
    fun importPromptFile(document: String, fileName: String) {
        viewModelScope.launch {
            val result = try {
                importPromptPackUseCase(document = document, fileName = fileName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                surfaceError(e)
                return@launch
            }
            applyImportResult(result)
        }
    }

    /**
     * Completes an import that stopped on a collision.
     *
     * @param candidate The prompt as read from the file.
     * @param choice What the user decided.
     * @param notes Anything that still has to be reported afterwards.
     */
    fun resolveImportCollision(
        candidate: PromptPackCandidate,
        choice: PromptPackCollisionChoice,
        notes: PromptPackImportNotes?,
    ) {
        _uiState.update { it.copy(importDialog = null) }
        viewModelScope.launch {
            val result = try {
                resolvePromptPackCollisionUseCase(candidate = candidate, choice = choice, notes = notes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                surfaceError(e)
                return@launch
            }
            applyImportResult(result)
        }
    }

    /**
     * Renders the prompt with [id] and parks it in
     * [PromptLibraryUiState.pendingExport] for the create-document picker.
     */
    fun requestExport(id: String) {
        viewModelScope.launch {
            exportPromptPackUseCase(id)
                .onSuccess { rendered -> _uiState.update { it.copy(pendingExport = rendered) } }
                .onFailure { error -> surfaceError(error) }
        }
    }

    /**
     * Clears the parked export the moment the picker is launched, so a
     * recomposition or configuration change cannot fire it twice for the
     * same payload.
     */
    fun consumePendingExport() {
        _uiState.update { it.copy(pendingExport = null) }
    }

    /** Reports a completed export. */
    fun onExported(displayName: String) {
        _uiState.update {
            it.copy(
                snackbar = PromptLibrarySnackbar(
                    UiText.Resource(R.string.prompts_export_success_format, listOf(displayName)),
                ),
            )
        }
    }

    /** Reports a document the platform would not let us read. */
    fun onFileUnreadable() {
        _uiState.update { it.copy(snackbar = PromptLibrarySnackbar(UiText(R.string.prompts_import_unreadable))) }
    }

    /**
     * Reports a document that could not be written.
     *
     * Its own message rather than [onFileUnreadable]'s: the user just asked to
     * save a file, and being told it "could not be read" describes an
     * operation they did not perform.
     */
    fun onExportFailed() {
        _uiState.update { it.copy(snackbar = PromptLibrarySnackbar(UiText(R.string.prompts_export_unwritable))) }
    }

    /** Dismisses whichever import dialog is open, deciding nothing. */
    fun dismissImportDialog() {
        _uiState.update { it.copy(importDialog = null) }
    }

    /** Consumes the pending snackbar once it has been shown. */
    fun consumeSnackbar() {
        _uiState.update { it.copy(snackbar = null) }
    }

    /**
     * Turns an import result into what the user sees: a snackbar when there
     * is nothing to decide and nothing to disclose, a dialog otherwise.
     *
     * The imported prompt's category rides along on the success snackbar so
     * its action can switch tabs — an import that lands in a category the
     * user is not looking at is otherwise indistinguishable from one that
     * did not happen.
     */
    private fun applyImportResult(result: PromptPackImportResult) {
        when (result) {
            is PromptPackImportResult.Imported -> {
                // Built once so the snackbar decision and the dialog cannot
                // disagree, and so neither needs a non-null assertion.
                val reportedDialog = result.notes
                    ?.takeUnless { it.isEmpty }
                    ?.let { PromptImportDialog.Reported(presetName = result.preset.name, notes = it) }
                _uiState.update { state ->
                    state.copy(
                        // A dialog and a snackbar are not raised together. When
                        // there is something to disclose, the dialog is the
                        // disclosure *and* the confirmation — its own first
                        // sentence says the prompt is in the library — and a
                        // snackbar behind a scrim is an action the user cannot
                        // reach anyway.
                        snackbar = if (reportedDialog != null) {
                            null
                        } else {
                            PromptLibrarySnackbar(
                                text = UiText.Resource(
                                    R.string.prompts_import_success_format,
                                    listOf(result.preset.name, result.preset.nodeType.name),
                                ),
                                showCategory = result.preset.nodeType.name,
                            )
                        },
                        importDialog = reportedDialog,
                    )
                }
            }

            is PromptPackImportResult.Unchanged -> _uiState.update {
                it.copy(
                    snackbar = PromptLibrarySnackbar(
                        UiText.Resource(R.string.prompts_import_unchanged_format, listOf(result.preset.name)),
                    ),
                )
            }

            is PromptPackImportResult.NeedsDecision -> _uiState.update {
                it.copy(
                    importDialog = PromptImportDialog.Collision(
                        candidate = result.candidate,
                        existingName = result.existing.name,
                        notes = result.notes,
                    ),
                )
            }

            is PromptPackImportResult.Failed -> _uiState.update {
                it.copy(importDialog = PromptImportDialog.Failed(result.cause))
            }
        }
    }

    /** Routes an unexpected throwable to the screen's error channel. */
    private fun surfaceError(error: Throwable) {
        _uiState.update {
            it.copy(errorMessage = OrchestratorErrorText.forThrowable(error))
        }
    }

    /** Selects [category] as the active tab on the library screen. */
    fun selectCategory(category: String) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    /**
     * Opens the bottom-sheet editor.
     *
     * @param promptId `null` to start a fresh draft (pre-filled with the
     *   currently-selected category); non-null to edit an existing user
     *   preset. Editing a bundled preset is a no-op — bundled rows are
     *   read-only by contract.
     */
    fun openEditor(promptId: String?) {
        val draft = if (promptId == null) {
            // Fresh draft inherits the category currently in view so the
            // resulting preset surfaces in the tab the user is browsing.
            val initialCategory = _uiState.value.selectedCategory.orEmpty()
            PromptEditorDraft(category = initialCategory)
        } else {
            val source = _uiState.value.userPresets.firstOrNull { it.id == promptId } ?: return
            PromptEditorDraft(
                id = source.id,
                name = source.name,
                category = source.nodeType.name,
                body = source.systemPrompt,
                description = source.description,
                tags = source.tags,
            )
        }
        _uiState.update { it.copy(editorDraft = draft) }
    }

    /** Closes the bottom-sheet editor without persisting changes. */
    fun closeEditor() {
        _uiState.update { it.copy(editorDraft = null) }
    }

    /** Updates the editor draft's Name field. */
    fun onEditorNameChange(value: String) {
        _uiState.update { state -> state.copy(editorDraft = state.editorDraft?.copy(name = value)) }
    }

    /** Updates the editor draft's Category field. */
    fun onEditorCategoryChange(value: String) {
        _uiState.update { state -> state.copy(editorDraft = state.editorDraft?.copy(category = value)) }
    }

    /** Updates the editor draft's Body field. */
    fun onEditorBodyChange(value: String) {
        _uiState.update { state -> state.copy(editorDraft = state.editorDraft?.copy(body = value)) }
    }

    /** Appends [token] at the end of the editor body (poor-man's "insert at cursor"). */
    fun onEditorVariableInsert(token: String) {
        _uiState.update { state ->
            val draft = state.editorDraft ?: return@update state
            val separator = if (draft.body.isEmpty() || draft.body.last().isWhitespace()) "" else " "
            state.copy(editorDraft = draft.copy(body = draft.body + separator + token))
        }
    }

    /**
     * Persists the editor draft via [SavePromptAsPresetUseCase] and closes
     * the sheet on success. Draft validation (name length, blank body,
     * LLM-driven node type) is delegated to the use case; failures surface
     * via [PromptLibraryUiState.errorMessage].
     */
    fun saveEditor() {
        val draft = _uiState.value.editorDraft ?: return
        val nodeType = runCatching { NodeType.valueOf(draft.category) }
            .getOrNull()
            ?.takeIf { it in PromptPresetConstants.LLM_DRIVEN_NODE_TYPES }
            ?: run {
                _uiState.update {
                    it.copy(errorMessage = UiText(R.string.prompts_editor_category_invalid))
                }
                return
            }
        viewModelScope.launch {
            val result = savePromptAsPresetUseCase(
                systemPrompt = draft.body,
                name = draft.name,
                description = draft.description,
                nodeType = nodeType,
                tags = draft.tags,
                existingId = draft.id,
            )
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(editorDraft = null) }
                },
                onFailure = { e ->
                    // A refused name or prompt is a typed problem; its words come from resources.
                    _uiState.update { it.copy(errorMessage = OrchestratorErrorText.forThrowable(e)) }
                },
            )
        }
    }

    /**
     * Duplicates a preset (bundled or user) into a new user preset with a
     * `(copy)` suffix on the name. Bundled presets are duplicated as user
     * presets — that's how a user starts customising a bundled template
     * without losing the original.
     */
    fun duplicatePrompt(id: String) {
        val all = _uiState.value.bundledPresets + _uiState.value.userPresets
        val source = all.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            val result = savePromptAsPresetUseCase(
                systemPrompt = source.systemPrompt,
                name = defaultNames.copyOf(source.name),
                description = source.description,
                nodeType = source.nodeType,
                tags = source.tags,
            )
            if (result.isFailure) {
                _uiState.update {
                    it.copy(
                        errorMessage = result.exceptionOrNull()?.let(OrchestratorErrorText::forThrowable)
                            ?: UiText(R.string.errors_generic_unexpected),
                    )
                }
            }
        }
    }

    private companion object {
        /**
         * See `OrchestratorViewModel.computeAvailableVariables` for the rationale: a
         * provider whose `key()` throws is silently skipped so a misbehaving DI binding
         * cannot empty the chip row.
         */
        private fun computeAvailableVariables(providers: Set<PromptVariableProvider>): List<String> = providers
            .mapNotNull { runCatching { it.key() }.getOrNull() }
            .distinct()
            .sorted()
            .map { "$$it" }
    }
}
