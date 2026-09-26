package app.knotwork.android.presentation.ui.orchestrator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.R
import app.knotwork.android.domain.engine.DefaultPipelineFactory
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.EntrySurface
import app.knotwork.android.domain.models.ImportCollisionResolution
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineImportOutcome
import app.knotwork.android.domain.models.PipelineTargetAvailability
import app.knotwork.android.domain.models.PipelineValidationError
import app.knotwork.android.domain.models.PipelineValidationException
import app.knotwork.android.domain.models.PresetCategory
import app.knotwork.android.domain.models.PromptPreset
import app.knotwork.android.domain.models.PromptTemplate
import app.knotwork.android.domain.models.Skill
import app.knotwork.android.domain.pipelineio.PipelineBundleJsonSerializer
import app.knotwork.android.domain.pipelineio.PipelineJsonSerializer
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.prompt.PromptVariableProvider
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.PromptPresetRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.SkillRepository
import app.knotwork.android.domain.repositories.ToolRepository
import app.knotwork.android.domain.services.PipelineCompositionValidator
import app.knotwork.android.domain.services.findDependentPipelines
import app.knotwork.android.domain.text.ImportedText
import app.knotwork.android.domain.text.toDisplaySafe
import app.knotwork.android.domain.usecases.ConfirmedImport
import app.knotwork.android.domain.usecases.CreatePipelineUseCase
import app.knotwork.android.domain.usecases.DeletePipelineUseCase
import app.knotwork.android.domain.usecases.DuplicatePipelineUseCase
import app.knotwork.android.domain.usecases.ExportPipelineBundleUseCase
import app.knotwork.android.domain.usecases.GetPromptTemplatesUseCase
import app.knotwork.android.domain.usecases.ImportPipelineBundleUseCase
import app.knotwork.android.domain.usecases.ImportPipelineUseCase
import app.knotwork.android.domain.usecases.LoadPipelineFromPresetUseCase
import app.knotwork.android.domain.usecases.LoadPipelineUseCase
import app.knotwork.android.domain.usecases.PipelineBundlePrepareResult
import app.knotwork.android.domain.usecases.RenamePipelineUseCase
import app.knotwork.android.domain.usecases.ResolveSurfacePipelineUseCase
import app.knotwork.android.domain.usecases.SavePipelineAsPresetUseCase
import app.knotwork.android.domain.usecases.SavePipelineUseCase
import app.knotwork.android.domain.usecases.SavePromptAsPresetUseCase
import app.knotwork.android.domain.usecases.SavePromptTemplateUseCase
import app.knotwork.android.domain.usecases.SetSurfacePipelineUseCase
import app.knotwork.android.presentation.ui.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for the Visual Orchestrator feature.
 * Manages the state of the infinite canvas and coordinates saving/loading pipelines.
 */
@HiltViewModel
@Suppress(
    // Reason: the visual orchestrator is a single screen but coordinates a
    // dozen distinct user flows (graph editing, library management, JSON
    // import/export, validation, pipeline-config dialogs). Splitting into
    // per-feature ViewModels would require lifting all of them into a shared
    // store anyway because the UI state is shared between the editor and the
    // library screen via the `pipelines` nested nav graph. Tracked for a
    // future refactor; not in scope for the static-analysis enforcement task.
    "TooManyFunctions",
    "LargeClass",
)
class OrchestratorViewModel
@Inject
// The 25-parameter constructor is the same fact seen from the dependency side:
// one ViewModel coordinating a dozen flows needs a use case for each. Hilt
// assembles the list, so no reader ever writes it out; it shrinks when the class
// is split, which is the refactor tracked above. Scoped to the constructor so it
// cannot silence a future finding elsewhere in the class.
@Suppress("LongParameterList")
constructor(
    private val savePipelineUseCase: SavePipelineUseCase,
    private val loadPipelineUseCase: LoadPipelineUseCase,
    private val importPipelineUseCase: ImportPipelineUseCase,
    private val importPipelineBundleUseCase: ImportPipelineBundleUseCase,
    private val exportPipelineBundleUseCase: ExportPipelineBundleUseCase,
    private val loadPipelineFromPresetUseCase: LoadPipelineFromPresetUseCase,
    private val renamePipelineUseCase: RenamePipelineUseCase,
    private val duplicatePipelineUseCase: DuplicatePipelineUseCase,
    private val deletePipelineUseCase: DeletePipelineUseCase,
    private val createPipelineUseCase: CreatePipelineUseCase,
    private val resolveSurfacePipelineUseCase: ResolveSurfacePipelineUseCase,
    private val setSurfacePipelineUseCase: SetSurfacePipelineUseCase,
    private val getPromptTemplatesUseCase: GetPromptTemplatesUseCase,
    private val savePromptTemplateUseCase: SavePromptTemplateUseCase,
    private val savePipelineAsPresetUseCase: SavePipelineAsPresetUseCase,
    private val savePromptAsPresetUseCase: SavePromptAsPresetUseCase,
    private val apiKeyRepository: ApiKeyRepository,
    private val toolRepository: ToolRepository,
    private val localModelRepository: LocalModelRepository,
    private val settingsRepository: SettingsRepository,
    private val promptTemplateEngine: PromptTemplateEngine,
    private val promptPresetRepository: PromptPresetRepository,
    private val compositionValidator: PipelineCompositionValidator,
    private val promptVariableProviders: Set<@JvmSuppressWildcards PromptVariableProvider>,
    private val skillRepository: SkillRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        OrchestratorUiState(availableVariables = computeAvailableVariables(promptVariableProviders)),
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

    init {
        observeSavedPipelines()
        observeProviderKeys()
        loadAvailableTools()
        observeLocalModels()
        observePromptTemplates()
        observeDefaultPipelineId()
        observeSurfaceBindingIds()
    }

    /**
     * Mirrors `LocalModelRepository.getAllModels()` into [OrchestratorUiState.availableLocalModels]
     * so the editor's `NodeConfigSheet` can feed the LITE_RT model dropdown straight from the
     * installed-models registry (with the active model badged). Errors collapse into the same
     * `errorMessage` channel the rest of the VM uses, so a model-list load failure doesn't fail
     * the whole editor screen.
     */
    private fun observeLocalModels() {
        viewModelScope.launch {
            localModelRepository.getAllModels()
                .catch { e ->
                    _uiState.update { it.copy(errorMessage = throwableAsUiText(e)) }
                }
                .collect { models ->
                    _uiState.update { it.copy(availableLocalModels = models) }
                }
        }
    }

    /**
     * Mirrors `SettingsRepository.defaultPipelineId` into [OrchestratorUiState]
     * so the library screen can render the "Default" badge / menu state in
     * real time after [setDefaultPipeline] is invoked.
     */
    private fun observeDefaultPipelineId() {
        viewModelScope.launch {
            settingsRepository.defaultPipelineId.collect { id ->
                _uiState.update { it.copy(defaultPipelineId = id) }
            }
        }
    }

    /**
     * Mirrors the two OS-entry-surface bindings
     * (`SettingsRepository.shareTargetPipelineId` /
     * `quickSettingsTilePipelineId`) into [OrchestratorUiState] so the library
     * renders the outlined "SHARE" / "TILE" pills on the bound rows and they
     * update live the moment the user (re)binds a surface — from this screen's
     * row menu ([bindPipelineToSurface]) or from Settings → Background.
     */
    private fun observeSurfaceBindingIds() {
        viewModelScope.launch {
            settingsRepository.shareTargetPipelineId.collect { id ->
                _uiState.update { it.copy(shareTargetPipelineId = id) }
            }
        }
        viewModelScope.launch {
            settingsRepository.quickSettingsTilePipelineId.collect { id ->
                _uiState.update { it.copy(quickSettingsTilePipelineId = id) }
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
        viewModelScope.launch {
            settingsRepository.setDefaultPipelineId(pipelineId)
            _uiState.update {
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
        viewModelScope.launch {
            setSurfacePipelineUseCase(surface, pipelineId)
            val feedback = surfaceBoundFeedback(surface) ?: return@launch
            _uiState.update { it.copy(feedbackMessage = UiText(feedback)) }
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

    private fun observePromptTemplates() {
        viewModelScope.launch {
            getPromptTemplatesUseCase()
                .catch { e ->
                    _uiState.update { it.copy(errorMessage = throwableAsUiText(e)) }
                }
                .collect { templates ->
                    _uiState.update { state ->
                        state.copy(promptTemplates = templates)
                    }
                }
        }
    }

    /**
     * Saves a new prompt template.
     *
     * @param name The name of the prompt.
     * @param text The prompt content.
     * @param category The category corresponding to NodeType.
     */
    fun savePromptTemplate(name: String, text: String, category: String) {
        viewModelScope.launch {
            try {
                savePromptTemplateUseCase(
                    PromptTemplate(name = name, text = text, category = category),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = throwableAsUiText(e)) }
            }
        }
    }

    private fun observeSavedPipelines() {
        viewModelScope.launch {
            loadPipelineUseCase.observeAllPipelines()
                .catch { e ->
                    _uiState.update { it.copy(errorMessage = throwableAsUiText(e)) }
                }
                .collect { pipelines ->
                    _uiState.update { state ->
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

    private fun observeProviderKeys() {
        viewModelScope.launch {
            apiKeyRepository.getOpenAIKey().collect { key ->
                updateProviderKey(CloudProvider.OPENAI, !key.isNullOrBlank())
            }
        }
        viewModelScope.launch {
            apiKeyRepository.getAnthropicKey().collect { key ->
                updateProviderKey(CloudProvider.ANTHROPIC, !key.isNullOrBlank())
            }
        }
        viewModelScope.launch {
            apiKeyRepository.getGoogleKey().collect { key ->
                updateProviderKey(CloudProvider.GOOGLE, !key.isNullOrBlank())
            }
        }
        viewModelScope.launch {
            apiKeyRepository.getDeepSeekKey().collect { key ->
                updateProviderKey(CloudProvider.DEEPSEEK, !key.isNullOrBlank())
            }
        }
    }

    private fun updateProviderKey(provider: CloudProvider, hasKey: Boolean) {
        _uiState.update { state ->
            val updatedKeys = state.providerKeys.toMutableMap()
            updatedKeys[provider] = hasKey
            state.copy(providerKeys = updatedKeys)
        }
    }

    private fun loadAvailableTools() {
        viewModelScope.launch {
            // Re-query the available tools whenever the http allowlist changes, not just
            // once at start-up: adding the first allowed domain un-hides `http_request`,
            // and the TOOL-node picker must reflect that in the same session rather than
            // only after an app restart. The flow also emits its current value on
            // collection, so this still performs the initial load.
            settingsRepository.allowedHttpDomains.collect {
                try {
                    val tools = toolRepository.getAvailableTools()
                    _uiState.update { it.copy(availableTools = tools) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _uiState.update { it.copy(errorMessage = throwableAsUiText(e)) }
                }
            }
        }
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
     * Updates the label of an existing connection.
     *
     * @param connectionId The unique identifier of the connection.
     * @param label The new label for the connection, or null to remove it.
     */
    fun updateConnectionLabel(connectionId: String, label: String?) {
        _uiState.update { state ->
            val updatedConnections = state.currentPipeline.connections.map {
                if (it.id == connectionId) it.copy(label = label) else it
            }
            state.copy(
                currentPipeline = state.currentPipeline.copy(connections = updatedConnections),
            )
        }
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
     * Updates the condition configuration of an IF_CONDITION node and the system prompt for any node.
     *
     * @param nodeId The unique identifier of the node.
     * @param complexity Threshold for task complexity.
     * @param keywords Comma-separated keywords.
     * @param prompt Free-form prompt.
     * @param systemPrompt The system prompt configuring the behavior of the node.
     */
    fun updateNodeConfiguration(
        nodeId: String,
        complexity: Int?,
        keywords: String?,
        prompt: String?,
        systemPrompt: String?,
    ) {
        _uiState.update { state ->
            val updatedNodes = state.currentPipeline.nodes.map {
                if (it.id == nodeId) {
                    it.copy(
                        conditionComplexity = complexity,
                        conditionKeywords = keywords,
                        conditionPrompt = prompt,
                        systemPrompt = systemPrompt,
                    )
                } else {
                    it
                }
            }
            state.copy(
                currentPipeline = state.currentPipeline.copy(nodes = updatedNodes),
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
     * Updates the tool assigned to a specific node.
     *
     * @param nodeId The unique identifier of the node.
     * @param toolName The name of the tool to assign.
     */
    fun updateNodeTool(nodeId: String, toolName: String) {
        _uiState.update { state ->
            val updatedNodes = state.currentPipeline.nodes.map {
                if (it.id == nodeId) it.copy(toolName = toolName, label = toolName) else it
            }
            state.copy(
                currentPipeline = state.currentPipeline.copy(nodes = updatedNodes),
            )
        }
    }

    /**
     * Updates the reply timeout for a CLARIFICATION node.
     *
     * @param nodeId The unique identifier of the node.
     * @param timeoutMs The timeout in milliseconds, or `null` to fall back to the engine default.
     */
    fun updateNodeClarificationTimeout(nodeId: String, timeoutMs: Long?) {
        _uiState.update { state ->
            val updatedNodes = state.currentPipeline.nodes.map {
                if (it.id == nodeId) it.copy(clarificationTimeoutMs = timeoutMs) else it
            }
            state.copy(
                currentPipeline = state.currentPipeline.copy(nodes = updatedNodes),
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
     * Updates the cloud provider for a CLOUD node.
     *
     * @param nodeId The unique identifier of the node.
     * @param provider The name of the provider.
     */
    fun updateNodeCloudProvider(nodeId: String, provider: String) {
        _uiState.update { state ->
            val updatedNodes = state.currentPipeline.nodes.map {
                if (it.id == nodeId) it.copy(cloudProvider = provider) else it
            }
            state.copy(
                currentPipeline = state.currentPipeline.copy(nodes = updatedNodes),
            )
        }
    }

    /**
     * Clears the current pipeline.
     */
    fun clearPipeline() {
        _uiState.update { state ->
            state.copy(
                currentPipeline = state.currentPipeline.copy(
                    nodes = emptyList(),
                    connections = emptyList(),
                ),
            )
        }
    }

    /**
     * Replaces the nodes and connections of the pipeline currently being
     * edited with the default complex task-routing preset. Preserves the
     * pipeline's `id`, `name`, and `updatedAt` (refreshed to "now") so the
     * preset is *applied to* the current pipeline rather than spawning a
     * new "Base Preset" pipeline alongside it.
     */
    fun applyBasePreset() {
        _uiState.update { state ->
            val preset = DefaultPipelineFactory.create(state.currentPipeline.name)
            state.copy(
                currentPipeline = state.currentPipeline.copy(
                    nodes = preset.nodes,
                    connections = preset.connections,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * Fills the pipeline currently being edited with the graph of the preset
     * identified by [presetId], regenerating node / connection ids so the
     * template is never mutated. Unlike the library's `+ From preset` flow
     * (which spawns a brand-new pipeline), this *replaces the current
     * pipeline's* nodes and connections in place — driving the editor's
     * empty-state "From template" CTA — while preserving the current
     * pipeline's `id` and `name`. Failures surface through `errorMessage`.
     *
     * @param presetId The stable id of the preset to materialise into the
     *   current pipeline.
     */
    fun applyPresetToCurrentPipeline(presetId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = loadPipelineFromPresetUseCase.materialize(presetId)
            _uiState.update { state ->
                result.fold(
                    onSuccess = { graph ->
                        state.copy(
                            isLoading = false,
                            currentPipeline = state.currentPipeline.copy(
                                nodes = graph.nodes,
                                connections = graph.connections,
                                updatedAt = System.currentTimeMillis(),
                            ),
                            feedbackMessage = UiText(R.string.orchestrator_preset_picker_loaded),
                        )
                    },
                    onFailure = { e ->
                        state.copy(isLoading = false, errorMessage = throwableAsUiText(e))
                    },
                )
            }
        }
    }

    /**
     * Exports the current pipeline to a JSON string in the schema-versioned
     * format consumed by the browser-side editor (`pipeline-editor.html`).
     */
    fun exportPipelineToJson(): String = PipelineJsonSerializer.serialize(_uiState.value.currentPipeline)

    /**
     * Entry point for the shared "Import JSON" affordance. Detects whether
     * [jsonString] is a bundle envelope (a self-contained closure of several
     * pipelines) or a single-pipeline document and routes to the matching
     * flow, so the library screen exposes one import affordance regardless of
     * the file shape.
     *
     * @param jsonString Raw JSON read from the picked document.
     */
    fun importJson(jsonString: String) {
        if (PipelineBundleJsonSerializer.looksLikeBundle(jsonString)) {
            importBundleFromJson(jsonString)
        } else {
            importPipelineFromJson(jsonString)
        }
    }

    /**
     * Parses [jsonString] as a single pipeline and, on a clean non-colliding
     * success, persists it through [SavePipelineUseCase] so it appears in the
     * saved-pipelines list immediately.
     *
     * Two deferral paths write nothing until the user decides:
     * - a `schemaVersion` mismatch stashes the graph in
     *   [OrchestratorUiState.pendingImport] for [confirmPendingImport];
     * - an id collision (the imported id already names a saved pipeline)
     *   stashes the graph in [OrchestratorUiState.pendingCollision] for
     *   [resolveCollision], closing the previous silent-overwrite behaviour.
     */
    fun importPipelineFromJson(jsonString: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val invocation = importPipelineUseCase(jsonString)
            _uiState.update { state ->
                when (val outcome = invocation.outcome) {
                    is PipelineImportOutcome.Success -> {
                        val collision = invocation.pendingCollision
                        val saveErr = invocation.saveResult?.let { res ->
                            res.exceptionOrNull()?.let(::messageForSaveError)
                        }
                        // The graph as written, not as parsed: the importer freshens
                        // node and connection ids, and an editor holding the file's
                        // ids would write them back on its next Save.
                        val savedGraph = invocation.saveResult?.getOrNull()
                        val saved = collision == null && savedGraph != null
                        state.copy(
                            currentPipeline = savedGraph ?: state.currentPipeline,
                            // An import that reached storage IS the saved state.
                            persistedPipeline = savedGraph ?: state.persistedPipeline,
                            isLoading = false,
                            pendingImport = null,
                            pendingCollision = collision,
                            errorMessage = saveErr,
                            // A matching schemaVersion does not mean nothing was lost:
                            // the format adds fields without bumping the version, so a
                            // file from a newer build can carry settings this one cannot
                            // read. Say so instead of importing a quietly diminished
                            // pipeline. Only when the import actually landed — a
                            // collision or save error has its own, louder surface.
                            feedbackMessage = if (saved && outcome.droppedFields.isNotEmpty()) {
                                UiText.Plural(
                                    id = R.plurals.orchestrator_library_import_dropped_feedback,
                                    quantity = outcome.droppedFields.size,
                                    args = listOf(outcome.droppedFields.size),
                                )
                            } else {
                                state.feedbackMessage
                            },
                        )
                    }
                    is PipelineImportOutcome.SchemaMismatch ->
                        state.copy(
                            isLoading = false,
                            pendingImport = outcome,
                            errorMessage = null,
                        )
                    is PipelineImportOutcome.Failure ->
                        state.copy(
                            isLoading = false,
                            pendingImport = null,
                            // Display-safe as a whole, behind the per-value rule in
                            // the serializer: the message quotes a file the user did
                            // not write.
                            errorMessage = UiText.Dynamic(
                                outcome.message.toDisplaySafe(ImportedText.MAX_MESSAGE_LENGTH),
                            ),
                        )
                }
            }
        }
    }

    /**
     * Resolves a pending single-import id collision with the user's choice
     * ([ImportCollisionResolution.REPLACE] overwrites in place;
     * [ImportCollisionResolution.IMPORT_AS_COPY] saves a fresh copy). No-op
     * when no collision is pending.
     *
     * @param resolution The user's collision choice.
     */
    fun resolveCollision(resolution: ImportCollisionResolution) {
        val collision = _uiState.value.pendingCollision ?: return
        _uiState.update { it.copy(isLoading = true, pendingCollision = null) }
        viewModelScope.launch {
            val result = importPipelineUseCase.persistWithResolution(collision.incoming, resolution)
            _uiState.update { state ->
                val saveErr = result.exceptionOrNull()?.let(::messageForSaveError)
                // Only Replace opens the result: a copy is a new pipeline beside
                // the one the user was looking at. Either way the editor takes
                // the graph as written (freshened ids), never the parsed one.
                val replaced = result.getOrNull()?.takeIf { resolution == ImportCollisionResolution.REPLACE }
                state.copy(
                    currentPipeline = replaced ?: state.currentPipeline,
                    persistedPipeline = replaced ?: state.persistedPipeline,
                    isLoading = false,
                    errorMessage = saveErr,
                )
            }
        }
    }

    /**
     * Discards a pending single-import id collision without persisting.
     */
    fun cancelCollision() {
        _uiState.update { it.copy(pendingCollision = null) }
    }

    /**
     * Parses [jsonString] as a pipeline bundle, validates every contained
     * graph, and either persists straight away (no collisions, no schema
     * mismatch) or stashes the prepared closure in
     * [OrchestratorUiState.pendingBundleImport] for the user to resolve. A
     * parse or validation failure surfaces through [OrchestratorUiState.errorMessage].
     */
    fun importBundleFromJson(jsonString: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val prepared = importPipelineBundleUseCase.prepare(jsonString)) {
                is PipelineBundlePrepareResult.Failure ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = UiText.Dynamic(
                                prepared.message.toDisplaySafe(ImportedText.MAX_MESSAGE_LENGTH),
                            ),
                        )
                    }

                is PipelineBundlePrepareResult.Ready -> {
                    val needsPrompt = prepared.collisions.isNotEmpty() || prepared.schemaMismatches.isNotEmpty()
                    if (needsPrompt) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                pendingBundleImport = PendingBundleImport(
                                    pipelines = prepared.pipelines,
                                    collisions = prepared.collisions,
                                    schemaMismatches = prepared.schemaMismatches,
                                ),
                            )
                        }
                    } else {
                        persistBundle(prepared.pipelines, ImportCollisionResolution.REPLACE)
                    }
                }
            }
        }
    }

    /**
     * Resolves a pending bundle import with the user's collision choice and
     * writes the closure atomically. No-op when no bundle import is pending.
     *
     * @param resolution How to treat ids that collide with the library.
     */
    fun resolveBundleImport(resolution: ImportCollisionResolution) {
        val pending = _uiState.value.pendingBundleImport ?: return
        _uiState.update { it.copy(isLoading = true, pendingBundleImport = null) }
        viewModelScope.launch { persistBundle(pending.pipelines, resolution) }
    }

    /**
     * Discards a pending bundle import without persisting.
     */
    fun cancelBundleImport() {
        _uiState.update { it.copy(pendingBundleImport = null) }
    }

    /**
     * Atomically persists [pipelines] under [resolution], surfacing either a
     * success count feedback or an error. Shared by the direct (no-collision)
     * and dialog-resolved bundle-import paths.
     */
    private suspend fun persistBundle(pipelines: List<PipelineGraph>, resolution: ImportCollisionResolution) {
        val result = importPipelineBundleUseCase.persist(pipelines, resolution)
        _uiState.update { state ->
            result.fold(
                onSuccess = { saved ->
                    // If the bundle replaced the pipeline currently open in the
                    // editor (REPLACE keeps ids), refresh the in-memory copy to
                    // the just-persisted graph so a later Save writes the
                    // imported content instead of silently reverting to stale
                    // state. Under copy every id changes, so nothing matches and
                    // the open pipeline is left untouched.
                    val refreshed = saved.firstOrNull { it.id == state.currentPipeline.id }
                    state.copy(
                        isLoading = false,
                        currentPipeline = refreshed ?: state.currentPipeline,
                        feedbackMessage = UiText.Plural(
                            R.plurals.orchestrator_library_import_bundle_success,
                            saved.size,
                            listOf(saved.size),
                        ),
                    )
                },
                onFailure = { e ->
                    state.copy(isLoading = false, errorMessage = throwableAsUiText(e))
                },
            )
        }
    }

    /**
     * Exports the pipeline identified by [pipelineId] together with the
     * transitive closure of its `PIPELINE` dependencies as a bundle document,
     * stashing the result in [OrchestratorUiState.pendingBundleExport] for the
     * library screen to write to a user-picked file. A fail-fast export error
     * (missing root, unresolvable dependency, or over-limit closure) surfaces
     * through [OrchestratorUiState.errorMessage].
     *
     * @param pipelineId Id of the saved pipeline to export as the bundle root.
     * @param fileName Suggested destination file name.
     */
    fun exportBundle(pipelineId: String, fileName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = exportPipelineBundleUseCase(pipelineId)
            _uiState.update { state ->
                result.fold(
                    onSuccess = { json ->
                        state.copy(
                            isLoading = false,
                            pendingBundleExport = PendingBundleExport(fileName = fileName, content = json),
                        )
                    },
                    onFailure = { e ->
                        state.copy(
                            isLoading = false,
                            errorMessage = UiText.of(
                                R.string.orchestrator_library_export_bundle_failed,
                                e.message ?: "",
                            ),
                        )
                    },
                )
            }
        }
    }

    /**
     * Clears the pending bundle-export payload once the library screen has
     * written it (or the user cancelled the file picker).
     */
    fun consumeBundleExport() {
        _uiState.update { it.copy(pendingBundleExport = null) }
    }

    /**
     * Persists the graph captured in [OrchestratorUiState.pendingImport]
     * after the user has accepted the schema-mismatch warning. No-op when
     * no import is pending.
     */
    fun confirmPendingImport() {
        val pending = _uiState.value.pendingImport ?: return
        // Clear pendingImport immediately so the AlertDialog dismisses
        // before the suspending save runs. Holding it while persistConfirmed
        // is in-flight would let the user re-click "Import anyway" or
        // dismiss the dialog mid-save, racing two persists for the same
        // graph.
        _uiState.update { it.copy(isLoading = true, pendingImport = null) }
        viewModelScope.launch {
            when (val confirmed = importPipelineUseCase.persistConfirmed(pending)) {
                // The confirmed graph collides with an existing pipeline: defer
                // to the collision dialog instead of silently overwriting.
                is ConfirmedImport.Collision ->
                    _uiState.update { it.copy(isLoading = false, pendingCollision = confirmed.collision) }

                is ConfirmedImport.Saved ->
                    _uiState.update { state ->
                        val saveErr = confirmed.result.exceptionOrNull()?.let(::messageForSaveError)
                        val savedGraph = confirmed.result.getOrNull()
                        state.copy(
                            currentPipeline = savedGraph ?: state.currentPipeline,
                            persistedPipeline = savedGraph ?: state.persistedPipeline,
                            isLoading = false,
                            errorMessage = saveErr,
                        )
                    }
            }
        }
    }

    /**
     * Discards a pending schema-mismatch import without persisting.
     */
    fun cancelPendingImport() {
        _uiState.update { it.copy(pendingImport = null) }
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
                    errorMessage = result.exceptionOrNull()?.let(::messageForSaveError),
                )
            }
        }
    }

    /**
     * Translates a save failure (validation or generic) into a UI-ready
     * [UiText]. Shared by [saveCurrentPipeline], [importPipelineFromJson]
     * and [confirmPendingImport] so the user sees the same wording
     * regardless of which entry point triggered the validator.
     *
     * Returns a single `UiText.Resource` when the failure is a single
     * `PipelineValidationException` with exactly one error; multi-error
     * validation collapses into a `UiText.Dynamic` with a comma-joined
     * resolved-at-display-time list (this keeps the API typed without
     * forcing the resource layer to model arbitrarily many error
     * combinations). Generic exceptions become `UiText.Dynamic`.
     */
    private fun messageForSaveError(e: Throwable): UiText? {
        if (e !is PipelineValidationException) {
            return e.message?.let { UiText.Dynamic(it) }
        }
        val parts = e.errors.map { err -> validationErrorAsUiText(err) }
        return when (parts.size) {
            0 -> null
            1 -> parts.first()
            else -> UiText.Joined(parts)
        }
    }

    /**
     * Resolves a single [PipelineValidationError] to its `UiText`
     * representation. Pulled out so [messageForSaveError] can decide whether
     * to keep the typed `Resource` (single error) or collapse into a
     * `Dynamic` join (multiple errors).
     */
    private fun validationErrorAsUiText(err: PipelineValidationError): UiText = when (err) {
        is PipelineValidationError.MissingInput ->
            UiText(R.string.errors_orchestrator_validation_missing_input)
        is PipelineValidationError.MissingOutput ->
            UiText(R.string.errors_orchestrator_validation_missing_output)
        is PipelineValidationError.MultipleInputs ->
            UiText(R.string.errors_orchestrator_validation_multiple_inputs)
        is PipelineValidationError.MultipleOutputs ->
            UiText(R.string.errors_orchestrator_validation_multiple_outputs)
        is PipelineValidationError.HasCycles ->
            UiText(R.string.errors_orchestrator_validation_has_cycles)
        is PipelineValidationError.DisconnectedInput ->
            UiText(R.string.errors_orchestrator_validation_disconnected_input)
        is PipelineValidationError.DisconnectedOutput ->
            UiText(R.string.errors_orchestrator_validation_disconnected_output)
        is PipelineValidationError.UnreachableNode ->
            UiText(R.string.errors_orchestrator_validation_unreachable_node)
        is PipelineValidationError.DeadEndNode ->
            UiText(R.string.errors_orchestrator_validation_dead_end)
        is PipelineValidationError.NodeEmptyContext ->
            UiText.of(R.string.errors_orchestrator_validation_node_no_sources, nodeNameForError(err.nodeId))
        is PipelineValidationError.MissingTargetPipeline ->
            UiText.of(R.string.errors_orchestrator_validation_missing_target_pipeline, nodeNameForError(err.nodeId))
        is PipelineValidationError.TargetPipelineNotFound ->
            UiText.of(R.string.errors_orchestrator_validation_target_pipeline_not_found, nodeNameForError(err.nodeId))
        is PipelineValidationError.PipelineCycle ->
            UiText.of(
                R.string.errors_orchestrator_validation_pipeline_cycle,
                err.pipelineChain.joinToString(" → ") { it.toDisplaySafe() },
            )
        is PipelineValidationError.PipelineNestingTooDeep ->
            UiText.of(R.string.errors_orchestrator_validation_pipeline_nesting_too_deep, err.limit)
        is PipelineValidationError.MissingSkill ->
            UiText.of(R.string.errors_orchestrator_validation_missing_skill, nodeNameForError(err.nodeId))
        is PipelineValidationError.SkillNotFound ->
            UiText.of(R.string.errors_orchestrator_validation_skill_not_found, nodeNameForError(err.nodeId))
    }

    /**
     * The name a validation error calls a node by: its label, or its id when
     * the node is not on the canvas.
     *
     * Display-safe, because both may come from a file the user did not write —
     * an imported label is bounded at parse, a node id is not — and the error
     * is shown in a snackbar with no line limit.
     *
     * @param nodeId the id the validator reported.
     * @return one line of at most [ImportedText.MAX_QUOTED_VALUE_LENGTH] characters.
     */
    private fun nodeNameForError(nodeId: String): String =
        (_uiState.value.currentPipeline.nodes.find { it.id == nodeId }?.label ?: nodeId).toDisplaySafe()

    /**
     * Lifts a thrown exception into a `UiText`, falling back to the generic
     * "unexpected error" resource when the throwable carries no message.
     */
    private fun throwableAsUiText(e: Throwable): UiText =
        e.message?.let { UiText.Dynamic(it) } ?: UiText(R.string.errors_generic_unexpected)

    /**
     * Loads a specific pipeline by ID.
     *
     * @param pipelineId The unique identifier of the pipeline to load.
     */
    fun loadPipeline(pipelineId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val pipeline = loadPipelineUseCase.getPipelineById(pipelineId)
            _uiState.update { state ->
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = renamePipelineUseCase(pipelineId, newName)
            _uiState.update { state ->
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = duplicatePipelineUseCase(pipelineId)
            _uiState.update { state ->
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
        findDependentPipelines(pipelineId, _uiState.value.savedPipelines)

    /**
     * Classifies every saved pipeline as a candidate target for a PIPELINE node
     * in the pipeline currently open in the editor. Delegates to
     * [PipelineCompositionValidator.classifyTargets] so the picker disables the
     * same cycle / self / depth options a save would reject. Failures degrade to
     * an empty list (the picker then shows its empty-state) rather than crashing
     * the sheet.
     *
     * @return one availability row per saved pipeline, sorted by name.
     */
    suspend fun classifyPipelineTargets(): List<PipelineTargetAvailability> {
        val graph = _uiState.value.currentPipeline
        return try {
            compositionValidator.classifyTargets(editingPipelineId = graph.id, editingGraph = graph)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to classify pipeline targets for %s", graph.id)
            emptyList()
        }
    }

    /**
     * Loads the full skill library (bundled + user) for the SKILL-node picker.
     * The screen maps the returned domain [Skill]s to the catalog `SkillOption`
     * (resolving the localized allowlist summary), so the catalog stays free of
     * skill-storage knowledge. Failures degrade to an empty list (the picker
     * then shows its empty-state) rather than crashing the sheet.
     *
     * @return every skill, newest first; empty on failure.
     */
    suspend fun loadSkills(): List<Skill> = try {
        skillRepository.getAllSkills().first()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Failed to load skills for the SKILL node picker")
        emptyList()
    }

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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = deletePipelineUseCase(pipelineId)
            // If the deleted pipeline was the user-marked default, clear the
            // setting so chat surfaces don't dangle on a non-existent id. A chat
            // with no binding then has no default to run — it says so rather than
            // silently picking another pipeline.
            if (result.isSuccess && _uiState.value.defaultPipelineId == pipelineId) {
                settingsRepository.setDefaultPipelineId(null)
            }
            // Clear any per-surface entry-point binding dangling on the deleted
            // pipeline so the surface falls back to its inert privacy-first
            // default rather than a non-existent id. Looping EntrySurface.entries
            // through the shared resolve/set use cases means a future surface is
            // covered automatically (no per-surface copy-paste).
            if (result.isSuccess) {
                EntrySurface.entries.forEach { surface ->
                    if (resolveSurfacePipelineUseCase(surface) == pipelineId) {
                        setSurfacePipelineUseCase(surface, null)
                    }
                }
            }
            _uiState.update { state ->
                val editorHeldIt = result.isSuccess && state.currentPipeline.id == pipelineId
                val next = if (editorHeldIt) state.savedPipelines.firstOrNull { it.id != pipelineId } else null
                state.copy(
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.let(::throwableAsUiText),
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = createPipelineUseCase(name)
            _uiState.update { state ->
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
     * Cold flow of bundled prompt presets targeting [nodeType], for the
     * `PromptPresetPickerDialog`'s Bundled tab. The dialog calls
     * `collectAsState(initial = emptyList())` so a brief empty render is
     * acceptable while the first asset-decode pass resolves.
     *
     * Pure delegation to [PromptPresetRepository] — kept here so the picker
     * does not need to depend on the data layer directly.
     */
    fun bundledPresetsForType(nodeType: NodeType): Flow<List<PromptPreset>> =
        promptPresetRepository.getPresetsForType(nodeType).map { all -> all.filter { it.isBundled } }

    /**
     * Cold flow of user-saved prompt presets targeting [nodeType], for the
     * `PromptPresetPickerDialog`'s Mine tab.
     */
    fun userPresetsForType(nodeType: NodeType): Flow<List<PromptPreset>> =
        promptPresetRepository.getPresetsForType(nodeType).map { all -> all.filter { !it.isBundled } }

    /**
     * Renders [template] through [PromptTemplateEngine] and exposes the resulting
     * segments via [OrchestratorUiState.previewState].
     *
     * Resolution may suspend on I/O (the `$MEMORY_SUMMARY` provider hits the database),
     * so the call runs on [viewModelScope]. The intermediate `Loading` state lets the UI
     * show a spinner if the user opens the sheet against a slow provider.
     *
     * @param template the raw prompt that may contain `$VARIABLE` placeholders.
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

    /**
     * Closes the prompt-preview bottom sheet, returning the UI to its idle state.
     */
    fun dismissPromptPreview() {
        _uiState.update { it.copy(previewState = PromptPreviewState.Hidden) }
    }

    // ─── Pipeline editor hooks ───────────────────────────────────────────────

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
    fun labelFor(error: PipelineValidationError): UiText = validationErrorAsUiText(error)

    /**
     * Packages the currently-edited pipeline (whatever is loaded into the
     * editor) as a user preset via [SavePipelineAsPresetUseCase]. Surfaces
     * a success / failure message through [OrchestratorUiState.feedbackMessage]
     * / [OrchestratorUiState.errorMessage] so the calling screen (editor)
     * doesn't need its own Snackbar plumbing.
     */
    fun saveCurrentAsPreset(
        name: String,
        description: String,
        category: PresetCategory,
        tags: List<String> = emptyList(),
    ) {
        viewModelScope.launch {
            val result = savePipelineAsPresetUseCase(
                graph = _uiState.value.currentPipeline,
                name = name,
                description = description,
                category = category,
                tags = tags,
            )
            _uiState.update { state ->
                val error = result.exceptionOrNull()?.let(::messageForSaveError)
                state.copy(
                    errorMessage = error,
                    feedbackMessage = if (error == null) {
                        UiText(R.string.orchestrator_preset_save_success)
                    } else {
                        state.feedbackMessage
                    },
                )
            }
        }
    }

    /**
     * Packages an existing library pipeline ([pipelineId]) as a user
     * preset. Resolves the graph through [LoadPipelineUseCase.getPipelineById]
     * before delegating to [SavePipelineAsPresetUseCase], so the editor
     * does not need to be loaded with the source pipeline first.
     */
    fun saveAsPresetFromLibrary(
        pipelineId: String,
        name: String,
        description: String,
        category: PresetCategory,
        tags: List<String> = emptyList(),
    ) {
        viewModelScope.launch {
            val pipeline = loadPipelineUseCase.getPipelineById(pipelineId)
            if (pipeline == null) {
                _uiState.update { it.copy(errorMessage = UiText(R.string.errors_orchestrator_pipeline_not_found)) }
                return@launch
            }
            val result = savePipelineAsPresetUseCase(
                graph = pipeline,
                name = name,
                description = description,
                category = category,
                tags = tags,
            )
            _uiState.update { state ->
                val error = result.exceptionOrNull()?.let(::messageForSaveError)
                state.copy(
                    errorMessage = error,
                    feedbackMessage = if (error == null) {
                        UiText(R.string.orchestrator_preset_save_success)
                    } else {
                        state.feedbackMessage
                    },
                )
            }
        }
    }

    /**
     * Packages a freshly-edited system prompt as a user prompt preset via
     * [SavePromptAsPresetUseCase]. Invoked by
     * `PipelineEditorScreen` from the 💾 button on prompt-bearing fields
     * inside `NodeConfigSheet`.
     *
     * Errors are reported through [OrchestratorUiState.errorMessage] /
     * [OrchestratorUiState.feedbackMessage] using the same channel as
     * pipeline-preset saves.
     *
     * @param systemPrompt The raw prompt template to save.
     * @param name Display name for the preset.
     * @param description Free-form description.
     * @param nodeType The node type this preset targets. Must be LLM-driven
     *   (validated by [SavePromptAsPresetUseCase]).
     * @param tags Lower-case kebab-case tags.
     */
    fun saveCurrentPromptAsPreset(
        systemPrompt: String,
        name: String,
        description: String,
        nodeType: NodeType,
        tags: List<String> = emptyList(),
    ) {
        viewModelScope.launch {
            val result = savePromptAsPresetUseCase(
                systemPrompt = systemPrompt,
                name = name,
                description = description,
                nodeType = nodeType,
                tags = tags,
            )
            _uiState.update { state ->
                val error = result.exceptionOrNull()?.let(::messageForSaveError)
                state.copy(
                    errorMessage = error,
                    feedbackMessage = if (error == null) {
                        UiText(R.string.orchestrator_prompt_preset_save_success)
                    } else {
                        state.feedbackMessage
                    },
                )
            }
        }
    }

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
