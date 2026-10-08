package app.knotwork.android.presentation.ui.orchestrator

import app.knotwork.android.R
import app.knotwork.android.domain.models.EntrySurface
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.repositories.EntryPointSettings
import app.knotwork.android.domain.services.findDependentPipelines
import app.knotwork.android.domain.usecases.CreatePipelineUseCase
import app.knotwork.android.domain.usecases.DeletePipelineUseCase
import app.knotwork.android.domain.usecases.DuplicatePipelineUseCase
import app.knotwork.android.domain.usecases.LoadPipelineUseCase
import app.knotwork.android.domain.usecases.RenamePipelineUseCase
import app.knotwork.android.domain.usecases.ResolveSurfacePipelineUseCase
import app.knotwork.android.domain.usecases.SetSurfacePipelineUseCase
import app.knotwork.android.presentation.ui.common.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * The collaborators of [OrchestratorLibraryDelegate], injected as one so the pipeline view
 * model's constructor grows by one parameter for them, not eight.
 *
 * @property loadPipeline Reads the saved pipelines: the library list and one pipeline by id.
 * @property renamePipeline Renames a saved pipeline.
 * @property duplicatePipeline Saves a copy of a pipeline under a fresh id.
 * @property deletePipeline Deletes a pipeline the user chose to delete.
 * @property createPipeline Creates and saves a new, empty pipeline.
 * @property resolveSurfacePipeline Reads which pipeline an entry surface (share, tile) runs.
 * @property setSurfacePipeline Binds an entry surface to a pipeline, or clears the binding.
 * @property entryPointSettings The default pipeline and the per-surface bindings.
 */
class OrchestratorLibraryUseCases @Inject constructor(
    val loadPipeline: LoadPipelineUseCase,
    val renamePipeline: RenamePipelineUseCase,
    val duplicatePipeline: DuplicatePipelineUseCase,
    val deletePipeline: DeletePipelineUseCase,
    val createPipeline: CreatePipelineUseCase,
    val resolveSurfacePipeline: ResolveSurfacePipelineUseCase,
    val setSurfacePipeline: SetSurfacePipelineUseCase,
    val entryPointSettings: EntryPointSettings,
)

/**
 * Pipeline-library delegate of [OrchestratorViewModel]: the saved-pipelines list, the
 * default pipeline and entry-surface bindings, and the library actions — open, create,
 * rename, duplicate, delete.
 *
 * Shares the view model's [scope] and its single [uiState] reducer (see
 * `docs/architecture.md` §1.2); owns no state of its own.
 *
 * @property scope The view model's `viewModelScope`.
 * @property uiState The view model's single source-of-truth state flow.
 * @property useCases What the library reads and writes.
 */
class OrchestratorLibraryDelegate(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<OrchestratorUiState>,
    private val useCases: OrchestratorLibraryUseCases,
) {

    internal fun observeSavedPipelines() {
        scope.launch {
            useCases.loadPipeline.observeAllPipelines()
                .catch { e ->
                    uiState.update { it.copy(errorMessage = OrchestratorErrorText.forThrowable(e)) }
                }
                .collect { pipelines ->
                    uiState.update { state ->
                        val newCurrent = if (state.currentPipeline.nodes.isEmpty() && pipelines.isNotEmpty()) {
                            pipelines.first()
                        } else {
                            state.currentPipeline
                        }
                        state.copy(
                            savedPipelines = pipelines,
                            currentPipeline = newCurrent,
                            // Adopting the first saved pipeline is a load, not an
                            // edit: it starts clean. Any other emission leaves the
                            // baseline alone, or an edit made while the list
                            // refreshed would look saved.
                            persistedPipeline = if (newCurrent !== state.currentPipeline) {
                                newCurrent
                            } else {
                                state.persistedPipeline
                            },
                        )
                    }
                }
        }
    }

    /**
     * Mirrors `EntryPointSettings.defaultPipelineId` into [OrchestratorUiState]
     * so the library screen can render the "Default" badge / menu state in
     * real time after [setDefaultPipeline] is invoked.
     */
    internal fun observeDefaultPipelineId() {
        scope.launch {
            useCases.entryPointSettings.defaultPipelineId.collect { id ->
                uiState.update { it.copy(defaultPipelineId = id) }
            }
        }
    }

    /**
     * Mirrors the two OS-entry-surface bindings
     * (`EntryPointSettings.shareTargetPipelineId` /
     * `quickSettingsTilePipelineId`) into [OrchestratorUiState] so the library
     * renders the outlined "SHARE" / "TILE" pills on the bound rows and they
     * update live the moment the user (re)binds a surface — from this screen's
     * row menu ([bindPipelineToSurface]) or from Settings → Background.
     */
    internal fun observeSurfaceBindingIds() {
        scope.launch {
            useCases.entryPointSettings.shareTargetPipelineId.collect { id ->
                uiState.update { it.copy(shareTargetPipelineId = id) }
            }
        }
        scope.launch {
            useCases.entryPointSettings.quickSettingsTilePipelineId.collect { id ->
                uiState.update { it.copy(quickSettingsTilePipelineId = id) }
            }
        }
    }

    /**
     * Marks [pipelineId] as the application-wide default pipeline. Used by
     * the library's "Set as default" menu item. The setting is observed by
     * the chat ViewModel which uses it in the TopAppBar subtitle and the
     * "Use default pipeline (…)" label, so chat surfaces stay in sync.
     */
    fun setDefaultPipeline(pipelineId: String) {
        scope.launch {
            useCases.entryPointSettings.setDefaultPipelineId(pipelineId)
            uiState.update {
                it.copy(feedbackMessage = UiText(R.string.orchestrator_feedback_default_pipeline_updated))
            }
        }
    }

    /**
     * Binds [pipelineId] to an entry [surface] (library row menu "Use for
     * sharing" / "Use for Quick Settings tile"). Mirrors [setDefaultPipeline];
     * the binding is observed by the Background settings screen. The write goes
     * through [SetSurfacePipelineUseCase] so every surface shares one dispatch
     * point with [ResolveSurfacePipelineUseCase].
     */
    fun bindPipelineToSurface(surface: EntrySurface, pipelineId: String) {
        scope.launch {
            useCases.setSurfacePipeline(surface, pipelineId)
            val feedback = surfaceBoundFeedback(surface) ?: return@launch
            uiState.update { it.copy(feedbackMessage = UiText(feedback)) }
        }
    }

    /**
     * Snackbar feedback shown after binding a pipeline to [surface], or `null`
     * for a surface the library screen cannot bind yet.
     *
     * The `when` stays exhaustive on purpose — a new [EntrySurface] must not
     * compile until someone decides what this screen says about it — while a
     * `null` branch lets a surface exist before its wording does, instead of
     * borrowing a sentence written about a different surface.
     */
    private fun surfaceBoundFeedback(surface: EntrySurface): Int? = when (surface) {
        EntrySurface.SHARE -> R.string.orchestrator_feedback_share_pipeline_bound
        EntrySurface.QUICK_TILE -> R.string.orchestrator_feedback_tile_pipeline_bound
        // Bound from its own settings surface, which owns its wording.
        EntrySurface.EXTERNAL_AUTOMATION -> null
    }

    /**
     * Loads a specific pipeline by ID.
     *
     * @param pipelineId The unique identifier of the pipeline to load.
     */
    fun loadPipeline(pipelineId: String) {
        scope.launch {
            uiState.update { it.copy(isLoading = true) }
            val pipeline = useCases.loadPipeline.getPipelineById(pipelineId)
            uiState.update { state ->
                if (pipeline != null) {
                    state.copy(
                        currentPipeline = pipeline,
                        persistedPipeline = pipeline,
                        isLoading = false,
                        errorMessage = null,
                    )
                } else {
                    state.copy(
                        isLoading = false,
                        errorMessage = UiText(R.string.errors_orchestrator_pipeline_not_found),
                    )
                }
            }
        }
    }

    /**
     * Renames the pipeline identified by [pipelineId] to [newName].
     *
     * Delegates validation (blank / over-length name) to [RenamePipelineUseCase] and
     * surfaces failures via [OrchestratorUiState.errorMessage] for the Snackbar to
     * pick up. The list of pipelines refreshes itself through the existing
     * `observeSavedPipelines` flow once the save completes; if the renamed pipeline
     * is the one currently loaded into the editor, [OrchestratorUiState.currentPipeline]
     * is patched in place so the editor's TopAppBar and the library highlight match
     * without waiting for the database round-trip.
     *
     * @param pipelineId Unique identifier of the pipeline to rename.
     * @param newName The new display name; trimmed and validated by the use case.
     */
    fun renamePipeline(pipelineId: String, newName: String) {
        scope.launch {
            uiState.update { it.copy(isLoading = true) }
            val result = useCases.renamePipeline(pipelineId, newName)
            uiState.update { state ->
                val error = result.exceptionOrNull()?.message?.let { UiText.Dynamic(it) }
                val patchedCurrent = if (
                    result.isSuccess && state.currentPipeline.id == pipelineId
                ) {
                    state.currentPipeline.copy(name = newName.trim())
                } else {
                    state.currentPipeline
                }
                state.copy(
                    isLoading = false,
                    currentPipeline = patchedCurrent,
                    errorMessage = error,
                    feedbackMessage = if (result.isSuccess) {
                        UiText(R.string.orchestrator_feedback_pipeline_renamed)
                    } else {
                        state.feedbackMessage
                    },
                )
            }
        }
    }

    /**
     * Duplicates an existing pipeline and exposes the new graph as the active one.
     *
     * The duplicate is created with fresh ids (pipeline + every node + every
     * connection) by [DuplicatePipelineUseCase]. On success, the duplicate is
     * loaded into [OrchestratorUiState.currentPipeline] so the user can continue
     * editing the copy immediately — this is the expected flow for the library's
     * "Duplicate" context-menu action.
     *
     * @param pipelineId Unique identifier of the source pipeline.
     */
    fun duplicatePipeline(pipelineId: String) {
        scope.launch {
            uiState.update { it.copy(isLoading = true) }
            val result = useCases.duplicatePipeline(pipelineId)
            uiState.update { state ->
                val duplicate = result.getOrNull()
                val error = result.exceptionOrNull()?.message?.let { UiText.Dynamic(it) }
                state.copy(
                    isLoading = false,
                    currentPipeline = duplicate ?: state.currentPipeline,
                    errorMessage = error,
                    feedbackMessage = if (duplicate != null) {
                        UiText(R.string.orchestrator_feedback_pipeline_duplicated)
                    } else {
                        state.feedbackMessage
                    },
                )
            }
        }
    }

    /**
     * The saved pipelines that run [pipelineId] through a `NodeType.PIPELINE`
     * node — i.e. the dependents that would be left with a dangling target if
     * [pipelineId] were deleted. Read straight off the current library snapshot;
     * the library delete dialog uses it to warn the user (and to confirm there is
     * no silent cascade — dependents become a normal, deep-linkable validation
     * error, not an automatic delete).
     *
     * @param pipelineId the pipeline the user is about to delete.
     * @return the dependent pipelines (empty when nothing references it).
     */
    fun dependentsOf(pipelineId: String): List<PipelineGraph> =
        findDependentPipelines(pipelineId, uiState.value.savedPipelines)

    /**
     * Deletes the pipeline identified by [pipelineId] from the library.
     *
     * Any pipeline may be deleted, including the one the editor holds. When it is
     * that one, the editor moves in the same state update to the next pipeline in
     * the library — or to an empty scratch pipeline when none is left — with the
     * saved baseline moved alongside, so the switch does not read as unsaved work.
     * Leaving the deleted graph in memory would not be harmless: the editor would
     * go on showing it, and Save would write it straight back under its old id
     * after its default and surface bindings had already been cleared.
     *
     * The switch is made explicitly rather than left to the library observer,
     * which keeps any non-empty current graph and may emit while the binding
     * clean-up below is still suspended.
     *
     * @param pipelineId Unique identifier of the pipeline to delete.
     */
    fun deletePipeline(pipelineId: String) {
        scope.launch {
            uiState.update { it.copy(isLoading = true) }
            val result = useCases.deletePipeline(pipelineId)
            // If the deleted pipeline was the user-marked default, clear the
            // setting so chat surfaces don't dangle on a non-existent id. A chat
            // with no binding then has no default to run — it says so rather than
            // silently picking another pipeline.
            if (result.isSuccess && uiState.value.defaultPipelineId == pipelineId) {
                useCases.entryPointSettings.setDefaultPipelineId(null)
            }
            // Clear any per-surface entry-point binding dangling on the deleted
            // pipeline so the surface falls back to its inert privacy-first
            // default rather than a non-existent id. Looping EntrySurface.entries
            // through the shared resolve/set use cases means a future surface is
            // covered automatically (no per-surface copy-paste).
            if (result.isSuccess) {
                EntrySurface.entries.forEach { surface ->
                    if (useCases.resolveSurfacePipeline(surface) == pipelineId) {
                        useCases.setSurfacePipeline(surface, null)
                    }
                }
            }
            uiState.update { state ->
                val editorHeldIt = result.isSuccess && state.currentPipeline.id == pipelineId
                val next = if (editorHeldIt) state.savedPipelines.firstOrNull { it.id != pipelineId } else null
                state.copy(
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.let(OrchestratorErrorText::forThrowable),
                    feedbackMessage = if (result.isSuccess) {
                        UiText(R.string.orchestrator_feedback_pipeline_deleted)
                    } else {
                        state.feedbackMessage
                    },
                    currentPipeline = when {
                        !editorHeldIt -> state.currentPipeline
                        next != null -> next
                        else -> PipelineGraph(
                            id = UUID.randomUUID().toString(),
                            name = OrchestratorUiState.DEFAULT_PIPELINE_NAME,
                        )
                    },
                    persistedPipeline = if (editorHeldIt) next else state.persistedPipeline,
                )
            }
        }
    }

    /**
     * Creates a brand-new pipeline with a minimal `INPUT → OUTPUT` seed and
     * loads it as the current pipeline.
     *
     * Used by the library's "New pipeline" FAB. Persistence happens through
     * [CreatePipelineUseCase], which validates the name and seeds the graph so
     * the freshly created pipeline already passes [PipelineGraph.validate].
     *
     * The created graph becomes the saved baseline as well as the current one:
     * it went to storage on the way here, so opening the editor on it must not
     * greet the user with an Unsaved marker for work they have not done yet.
     *
     * @param name Display name for the new pipeline.
     */
    fun createNewPipeline(name: String) {
        scope.launch {
            uiState.update { it.copy(isLoading = true) }
            val result = useCases.createPipeline(name)
            uiState.update { state ->
                val created = result.getOrNull()
                state.copy(
                    isLoading = false,
                    currentPipeline = created ?: state.currentPipeline,
                    persistedPipeline = created ?: state.persistedPipeline,
                    errorMessage = result.exceptionOrNull()?.message?.let { UiText.Dynamic(it) },
                    feedbackMessage = if (created != null) {
                        UiText(R.string.orchestrator_feedback_pipeline_created)
                    } else {
                        state.feedbackMessage
                    },
                    // Only request navigation on a successful create; a failed
                    // create (validation, persistence error) must keep the user
                    // in the library so they can retry, instead of pushing
                    // them into the editor with the previously active graph.
                    pendingEditorNavigation = state.pendingEditorNavigation || created != null,
                )
            }
        }
    }
}
