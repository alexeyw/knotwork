package app.knotwork.android.presentation.ui.orchestrator

import app.knotwork.android.R
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PresetCategory
import app.knotwork.android.domain.models.PromptPreset
import app.knotwork.android.domain.repositories.PromptPresetRepository
import app.knotwork.android.domain.usecases.LoadPipelineFromPresetUseCase
import app.knotwork.android.domain.usecases.LoadPipelineUseCase
import app.knotwork.android.domain.usecases.SavePipelineAsPresetUseCase
import app.knotwork.android.domain.usecases.SavePromptAsPresetUseCase
import app.knotwork.android.presentation.ui.common.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The collaborators of [OrchestratorPresetsDelegate], injected as one so the pipeline view
 * model's constructor grows by one parameter for them, not five.
 *
 * @property loadPipelineFromPreset Builds the graph a pipeline preset describes.
 * @property savePipelineAsPreset Saves a pipeline as a user preset.
 * @property savePromptAsPreset Saves a node prompt as a user prompt preset.
 * @property promptPresetRepository The bundled and user prompt presets per node type.
 * @property loadPipeline Reads a saved pipeline by id, for saving it as a preset from the library.
 */
class OrchestratorPresetUseCases @Inject constructor(
    val loadPipelineFromPreset: LoadPipelineFromPresetUseCase,
    val savePipelineAsPreset: SavePipelineAsPresetUseCase,
    val savePromptAsPreset: SavePromptAsPresetUseCase,
    val promptPresetRepository: PromptPresetRepository,
    val loadPipeline: LoadPipelineUseCase,
)

/**
 * Presets delegate of [OrchestratorViewModel]: pipeline and prompt presets — applied to the
 * open pipeline, listed per node type, and saved from the editor or the library.
 *
 * Shares the view model's [scope] and its single [uiState] reducer (see
 * `docs/architecture.md` §1.2); owns no state of its own.
 *
 * @property scope The view model's `viewModelScope`.
 * @property uiState The view model's single source-of-truth state flow.
 * @property useCases What presets are read from and saved to.
 */
class OrchestratorPresetsDelegate(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<OrchestratorUiState>,
    private val useCases: OrchestratorPresetUseCases,
) {

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
        scope.launch {
            uiState.update { it.copy(isLoading = true) }
            val result = useCases.loadPipelineFromPreset.materialize(presetId)
            uiState.update { state ->
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
                        state.copy(isLoading = false, errorMessage = OrchestratorErrorText.forThrowable(e))
                    },
                )
            }
        }
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
        useCases.promptPresetRepository.getPresetsForType(nodeType).map { all -> all.filter { it.isBundled } }

    /**
     * Cold flow of user-saved prompt presets targeting [nodeType], for the
     * `PromptPresetPickerDialog`'s Mine tab.
     */
    fun userPresetsForType(nodeType: NodeType): Flow<List<PromptPreset>> =
        useCases.promptPresetRepository.getPresetsForType(nodeType).map { all -> all.filter { !it.isBundled } }

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
        scope.launch {
            val result = useCases.savePipelineAsPreset(
                graph = uiState.value.currentPipeline,
                name = name,
                description = description,
                category = category,
                tags = tags,
            )
            uiState.update { state ->
                val error = result.exceptionOrNull()?.let(::saveErrorText)
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
        scope.launch {
            val pipeline = useCases.loadPipeline.getPipelineById(pipelineId)
            if (pipeline == null) {
                uiState.update { it.copy(errorMessage = UiText(R.string.errors_orchestrator_pipeline_not_found)) }
                return@launch
            }
            val result = useCases.savePipelineAsPreset(
                graph = pipeline,
                name = name,
                description = description,
                category = category,
                tags = tags,
            )
            uiState.update { state ->
                val error = result.exceptionOrNull()?.let(::saveErrorText)
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
        scope.launch {
            val result = useCases.savePromptAsPreset(
                systemPrompt = systemPrompt,
                name = name,
                description = description,
                nodeType = nodeType,
                tags = tags,
            )
            uiState.update { state ->
                val error = result.exceptionOrNull()?.let(::saveErrorText)
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

    /**
     * A save / import failure in the user's words, naming nodes of the pipeline on screen.
     *
     * @param e What was thrown.
     * @return The text to show, or `null` for a validation failure with no errors.
     */
    private fun saveErrorText(e: Throwable): UiText? = OrchestratorErrorText.forSave(e, uiState.value.currentPipeline)
}
