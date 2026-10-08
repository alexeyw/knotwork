package app.knotwork.android.presentation.ui.orchestrator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.R
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineValidationError
import app.knotwork.android.domain.prompt.PromptVariableProvider
import app.knotwork.android.domain.usecases.SavePipelineUseCase
import app.knotwork.android.presentation.ui.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel of the pipeline library and the pipeline editor, which share it through the
 * `pipelines` navigation graph.
 *
 * It is a coordinator: everything both screens render is one immutable
 * [OrchestratorUiState] exposed through [uiState], and every mutation funnels through
 * `_uiState.update { it.copy(...) }`. Four delegates share that reducer and the
 * [viewModelScope] (see `docs/architecture.md` §1.2):
 *  - [library] — the saved-pipelines list, the default pipeline and entry-surface
 *    bindings, and open / create / rename / duplicate / delete;
 *  - [transfer] — importing a pipeline file or bundle, and exporting a bundle;
 *  - [presets] — pipeline and prompt presets;
 *  - [nodeSheet] — what the node settings sheet lists, and the prompt preview.
 *
 * What stays here is the open pipeline itself: editing its graph, saving it, the
 * validation wording the editor shows, and the one-shot messages both screens consume.
 *
 * @param savePipelineUseCase Persists the open pipeline.
 * @param libraryUseCases The collaborators of [library].
 * @param transferUseCases The collaborators of [transfer].
 * @param presetUseCases The collaborators of [presets].
 * @param nodeSheetSources The collaborators of [nodeSheet].
 */
@HiltViewModel
class OrchestratorViewModel @Inject constructor(
    private val savePipelineUseCase: SavePipelineUseCase,
    libraryUseCases: OrchestratorLibraryUseCases,
    transferUseCases: OrchestratorTransferUseCases,
    presetUseCases: OrchestratorPresetUseCases,
    nodeSheetSources: OrchestratorNodeSheetSources,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        OrchestratorUiState(availableVariables = computeAvailableVariables(nodeSheetSources.promptVariableProviders)),
    )

    /**
     * The current UI state of the Orchestrator screen.
     */
    val uiState: StateFlow<OrchestratorUiState> = _uiState.asStateFlow()

    private val _focusNodeRequest = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /**
     * One-shot stream of node ids that the editor should centre the canvas on.
     * Emitted by the [requestFocusNode] hook used by `ValidationBar` taps.
     * Replays are intentionally not retained — every emission represents a fresh tap.
     */
    val focusNodeRequest: SharedFlow<String> = _focusNodeRequest.asSharedFlow()

    /** The library list, the default and entry-surface bindings, and the library actions. */
    val library: OrchestratorLibraryDelegate = OrchestratorLibraryDelegate(
        scope = viewModelScope,
        uiState = _uiState,
        useCases = libraryUseCases,
    )

    /** Importing a pipeline file or bundle, and exporting a bundle. */
    val transfer: OrchestratorTransferDelegate = OrchestratorTransferDelegate(
        scope = viewModelScope,
        uiState = _uiState,
        useCases = transferUseCases,
    )

    /** Pipeline and prompt presets. */
    val presets: OrchestratorPresetsDelegate = OrchestratorPresetsDelegate(
        scope = viewModelScope,
        uiState = _uiState,
        useCases = presetUseCases,
    )

    /** What the node settings sheet lists, and the prompt preview. */
    val nodeSheet: OrchestratorNodeSheetDelegate = OrchestratorNodeSheetDelegate(
        scope = viewModelScope,
        uiState = _uiState,
        sources = nodeSheetSources,
    )

    init {
        // The order the observers started in before the split, kept.
        library.observeSavedPipelines()
        nodeSheet.loadAvailableTools()
        nodeSheet.observeLocalModels()
        library.observeDefaultPipelineId()
        library.observeSurfaceBindingIds()
    }

    /**
     * Adds a new node to the canvas at the specified coordinates.
     *
     * Returns the freshly-generated node id so the caller (typically the editor's quick-add
     * flow) can immediately reference the new node — for example to open its `NodeConfigSheet`
     * before the [uiState] StateFlow has propagated the update. Reading
     * `uiState.currentPipeline.nodes.lastOrNull()` right after this call observes the
     * pre-update value, so the returned id is the only reliable handle.
     *
     * @param type The type of node to add.
     * @param x The x-coordinate for the node's position.
     * @param y The y-coordinate for the node's position.
     * @return The unique identifier assigned to the newly-added node.
     */
    fun addNode(type: NodeType, x: Float, y: Float): String {
        val newNode = NodeModel(
            id = UUID.randomUUID().toString(),
            type = type,
            x = x,
            y = y,
            cloudProvider = if (type == NodeType.CLOUD) CloudProvider.AUTO_KEY else null,
            contextConfig = NodeContextConfig.defaultForType(type),
        )
        _uiState.update { state ->
            val updatedPipeline = state.currentPipeline.copy(
                nodes = state.currentPipeline.nodes + newNode,
            )
            state.copy(currentPipeline = updatedPipeline)
        }
        return newNode.id
    }

    /**
     * Moves an existing node by a delta amount.
     *
     * @param nodeId The unique identifier of the node to move.
     * @param deltaX The change in the x-coordinate.
     * @param deltaY The change in the y-coordinate.
     */
    fun moveNode(nodeId: String, deltaX: Float, deltaY: Float) {
        _uiState.update { state ->
            val updatedNodes = state.currentPipeline.nodes.map {
                if (it.id == nodeId) it.copy(x = it.x + deltaX, y = it.y + deltaY) else it
            }
            state.copy(
                currentPipeline = state.currentPipeline.copy(nodes = updatedNodes),
            )
        }
    }

    /**
     * Creates a connection between two nodes.
     *
     * @param sourceNodeId The unique identifier of the source node.
     * @param targetNodeId The unique identifier of the target node.
     * @param label Optional label for the connection.
     * @return The ID of the newly created connection, or null if it was not created (e.g. cycle).
     */
    fun addConnection(sourceNodeId: String, targetNodeId: String, label: String? = null): String? {
        val newConnection = ConnectionModel(
            id = UUID.randomUUID().toString(),
            sourceNodeId = sourceNodeId,
            targetNodeId = targetNodeId,
            label = label,
        )
        var createdConnectionId: String? = null
        _uiState.update { state ->
            // Remove previous connection if it's between the same source and target,
            // OR if it's from the same source with the same label (e.g. "True" / "False")
            val filteredConnections = state.currentPipeline.connections.filterNot {
                (it.sourceNodeId == sourceNodeId && it.targetNodeId == targetNodeId) ||
                    (it.sourceNodeId == sourceNodeId && it.label == label && label != null)
            }

            val tempPipeline = state.currentPipeline.copy(
                connections = filteredConnections + newConnection,
            )

            // Validate DAG
            if (tempPipeline.isValidDAG()) {
                createdConnectionId = newConnection.id
                state.copy(currentPipeline = tempPipeline, errorMessage = null)
            } else {
                state.copy(errorMessage = UiText(R.string.errors_orchestrator_cycle_detected))
            }
        }
        return createdConnectionId
    }

    /**
     * Removes an existing connection.
     *
     * @param connectionId The unique identifier of the connection to remove.
     */
    fun removeConnection(connectionId: String) {
        _uiState.update { state ->
            val updatedConnections = state.currentPipeline.connections.filter {
                it.id != connectionId
            }
            state.copy(
                currentPipeline = state.currentPipeline.copy(connections = updatedConnections),
            )
        }
    }

    /**
     * Removes a node and any connections attached to it.
     *
     * @param nodeId The unique identifier of the node to remove.
     */
    fun removeNode(nodeId: String) {
        _uiState.update { state ->
            val updatedNodes = state.currentPipeline.nodes.filter { it.id != nodeId }
            val updatedConnections = state.currentPipeline.connections.filter {
                it.sourceNodeId != nodeId && it.targetNodeId != nodeId
            }
            state.copy(
                currentPipeline = state.currentPipeline.copy(
                    nodes = updatedNodes,
                    connections = updatedConnections,
                ),
            )
        }
    }

    /**
     * Updates the per-node context configuration that controls which pipeline
     * context blocks (chat history, original task, previous node output,
     * long-term memory, tool results) are concatenated into the node's input
     * on every execution.
     *
     * Two invariants are enforced here as a safety net for cases where the
     * UI layer is bypassed (JSON import, programmatic updates, future
     * regressions):
     *
     * 1. The `nodeInput` flag is forced to `true` — the previous node's
     *    output is the canonical input source for any node in a chain, so
     *    disabling it would silently break the pipeline.
     * 2. If the caller passes a config with every flag disabled, the
     *    `errorMessage` is set so the UI can surface a Snackbar prompting
     *    the user to keep at least one source enabled.
     *
     * @param nodeId The unique identifier of the node to update.
     * @param config The desired [NodeContextConfig]; sanitized before use.
     */
    fun updateNodeContextConfig(nodeId: String, config: NodeContextConfig) {
        val incomingAllDisabled = config.isEmpty()
        val sanitized = config.copy(nodeInput = true)
        _uiState.update { state ->
            val updatedNodes = state.currentPipeline.nodes.map {
                if (it.id == nodeId) it.copy(contextConfig = sanitized) else it
            }
            state.copy(
                currentPipeline = state.currentPipeline.copy(nodes = updatedNodes),
                errorMessage = if (incomingAllDisabled) {
                    UiText(R.string.errors_orchestrator_at_least_one_source)
                } else {
                    null
                },
            )
        }
    }

    /**
     * Saves the current pipeline.
     */
    fun saveCurrentPipeline() {
        // Captured before the launch, not inside it: Save means "save what is on
        // screen now". Reading it in the coroutine would pick up whatever the
        // user did between the tap and the dispatch, and then record THAT as
        // the persisted baseline — quietly marking an edit as saved that never
        // reached storage.
        val saved = _uiState.value.currentPipeline
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = savePipelineUseCase(saved)
            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    // The baseline moves only on success, and to the exact graph
                    // that was persisted rather than to whatever is on screen
                    // now: an edit made while the save was in flight is still
                    // unsaved, and saying otherwise is how work goes missing.
                    persistedPipeline = if (result.isSuccess) saved else state.persistedPipeline,
                    // Confirmation follows the outcome. The editor's overflow
                    // used to announce "Pipeline saved." the moment the item was
                    // tapped, so a save the validator rejected reported success
                    // and failure at once — invisible until the toolbar started
                    // carrying an Unsaved marker to contradict it.
                    feedbackMessage = if (result.isSuccess) {
                        UiText(R.string.pipeline_editor_save_done)
                    } else {
                        state.feedbackMessage
                    },
                    errorMessage = result.exceptionOrNull()?.let(::saveErrorText),
                )
            }
        }
    }

    /**
     * Clears the transient feedback string after the Snackbar has shown it.
     * Mirrors [clearError] so the library screen can dismiss the feedback
     * channel independently of the error channel.
     */
    fun clearFeedback() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    /**
     * Acknowledges and resets the [OrchestratorUiState.pendingEditorNavigation]
     * flag. Call from the library screen's `LaunchedEffect` after invoking
     * the navigation callback, so the same trigger never fires twice (e.g.
     * after a configuration change).
     */
    fun consumePendingEditorNavigation() {
        _uiState.update { it.copy(pendingEditorNavigation = false) }
    }

    /**
     * Clears error messages from UI state.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Replaces the persisted [NodeModel] for [nodeId] with [updated]. Used by the new
     * `NodeConfigSheet` flow once the user taps Save and the catalog validation passes.
     *
     * The caller is expected to have already projected its catalog `NodeConfig` onto a
     * [NodeModel] via `NodeConfigCodec.apply(source, config)`. This entry point is
     * intentionally generic so future per-type updates do not require dedicated VM
     * methods.
     */
    fun updateNodeFromEditor(nodeId: String, updated: NodeModel) {
        _uiState.update { state ->
            val nextNodes = state.currentPipeline.nodes.map { if (it.id == nodeId) updated else it }
            state.copy(currentPipeline = state.currentPipeline.copy(nodes = nextNodes))
        }
    }

    /**
     * Replaces the entire `currentPipeline` graph in one shot. Used by the
     * editor for undo / redo (which restores a previously captured snapshot) and the
     * auto-layout commit (which writes the recomputed node positions in bulk).
     */
    fun replaceCurrentPipeline(graph: PipelineGraph) {
        _uiState.update { it.copy(currentPipeline = graph) }
    }

    /**
     * Requests that the editor centre its canvas on [nodeId] and select it. Fired by the
     * `ValidationBar` so tapping an error focuses the offending node without forcing
     * the validation logic to know anything about the canvas viewport.
     */
    fun requestFocusNode(nodeId: String) {
        _focusNodeRequest.tryEmit(nodeId)
    }

    /**
     * Resolves a single [PipelineValidationError] to its user-visible label using the
     * same wording the save-time toast emits. Exposed so the editor's `ValidationBar`
     * can render the same copy without re-implementing the mapping.
     */
    fun labelFor(error: PipelineValidationError): UiText =
        OrchestratorErrorText.forValidation(error, _uiState.value.currentPipeline)

    /**
     * A save / import failure in the user's words, naming nodes of the pipeline on screen.
     *
     * @param e What was thrown.
     * @return The text to show, or `null` for a validation failure with no errors.
     */
    private fun saveErrorText(e: Throwable): UiText? = OrchestratorErrorText.forSave(e, _uiState.value.currentPipeline)

    private companion object {
        /**
         * Computes the deterministic, sorted list of `$KEY` tokens advertised by the
         * registered [PromptVariableProvider]s. A provider whose `key()` throws is
         * silently skipped — this mirrors the engine's tolerance for broken providers
         * so a single misbehaving DI binding cannot empty the chip row.
         */
        private fun computeAvailableVariables(providers: Set<PromptVariableProvider>): List<String> = providers
            .mapNotNull { runCatching { it.key() }.getOrNull() }
            .distinct()
            .sorted()
            .map { "$$it" }
    }
}
