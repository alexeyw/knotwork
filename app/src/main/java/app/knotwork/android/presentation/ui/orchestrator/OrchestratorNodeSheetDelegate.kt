package app.knotwork.android.presentation.ui.orchestrator

import app.knotwork.android.domain.engine.AutoProvider
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.PipelineTargetAvailability
import app.knotwork.android.domain.models.Skill
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.prompt.PromptVariableProvider
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.SkillRepository
import app.knotwork.android.domain.repositories.ToolRepository
import app.knotwork.android.domain.repositories.ToolSettings
import app.knotwork.android.domain.repositories.isConfigured
import app.knotwork.android.domain.services.PipelineCompositionValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * What the node settings sheet offers to choose from, injected as one so the pipeline view
 * model's constructor grows by one parameter for it, not eight.
 *
 * @property toolRepository The tools a TOOL node can call.
 * @property toolSettings The http allowlist, whose changes re-query the tools.
 * @property localModelRepository The installed on-device models for a LITE_RT node.
 * @property compositionValidator Which pipelines a PIPELINE node may call.
 * @property skillRepository The skills a SKILL node can run.
 * @property apiKeyRepository Which cloud providers are set up, and their models.
 * @property promptTemplateEngine Renders a prompt's `$VARIABLE`s for the preview.
 * @property promptVariableProviders Every registered prompt variable.
 */
class OrchestratorNodeSheetSources @Inject constructor(
    val toolRepository: ToolRepository,
    val toolSettings: ToolSettings,
    val localModelRepository: LocalModelRepository,
    val compositionValidator: PipelineCompositionValidator,
    val skillRepository: SkillRepository,
    val apiKeyRepository: ApiKeyRepository,
    val promptTemplateEngine: PromptTemplateEngine,
    val promptVariableProviders: Set<@JvmSuppressWildcards PromptVariableProvider>,
)

/**
 * Node-sheet delegate of [OrchestratorViewModel]: what the node settings sheet lists —
 * tools, installed models, pipeline targets, skills, set-up providers — and the prompt
 * preview it opens.
 *
 * Shares the view model's [scope] and its single [uiState] reducer (see
 * `docs/architecture.md` §1.2); the lists and the preview live in [OrchestratorUiState].
 *
 * @property scope The view model's `viewModelScope`.
 * @property uiState The view model's single source-of-truth state flow.
 * @property sources What the sheet reads.
 */
class OrchestratorNodeSheetDelegate(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<OrchestratorUiState>,
    private val sources: OrchestratorNodeSheetSources,
) {

    /**
     * Mirrors `LocalModelRepository.getAllModels()` into [OrchestratorUiState.availableLocalModels]
     * so the editor's `NodeConfigSheet` can feed the LITE_RT model dropdown straight from the
     * installed-models registry (with the active model badged). Errors collapse into the same
     * `errorMessage` channel the rest of the VM uses, so a model-list load failure doesn't fail
     * the whole editor screen.
     */
    internal fun observeLocalModels() {
        scope.launch {
            sources.localModelRepository.getAllModels()
                .catch { e ->
                    uiState.update { it.copy(errorMessage = OrchestratorErrorText.forThrowable(e)) }
                }
                .collect { models ->
                    uiState.update { it.copy(availableLocalModels = models) }
                }
        }
    }

    internal fun loadAvailableTools() {
        scope.launch {
            // Re-query the available tools whenever the http allowlist changes, not just
            // once at start-up: adding the first allowed domain un-hides `http_request`,
            // and the TOOL-node picker must reflect that in the same session rather than
            // only after an app restart. The flow also emits its current value on
            // collection, so this still performs the initial load.
            sources.toolSettings.allowedHttpDomains.collect {
                try {
                    val tools = sources.toolRepository.getAvailableTools()
                    uiState.update { it.copy(availableTools = tools) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    uiState.update { it.copy(errorMessage = OrchestratorErrorText.forThrowable(e)) }
                }
            }
        }
    }

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
        val graph = uiState.value.currentPipeline
        return try {
            sources.compositionValidator.classifyTargets(editingPipelineId = graph.id, editingGraph = graph)
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
        sources.skillRepository.getAllSkills().first()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Failed to load skills for the SKILL node picker")
        emptyList()
    }

    /**
     * What the node sheet's provider fields say about this device: every provider set up here
     * (its credential, and a model where it has no default) with the model id it uses, and what
     * a node set to *Auto* would run on — the rule the CLOUD executor applies ([AutoProvider]).
     *
     * Read when a sheet opens rather than kept as state: it changes only in Settings, which the
     * editor is not on at the same time.
     *
     * @return The availability; a provider's model is `null` when it uses its default. Empty when
     *   the stored values cannot be read — the fields then show every provider as not set up,
     *   which still lets the user choose one.
     */
    suspend fun loadProviderAvailability(): ProviderAvailability = try {
        val configured = CloudProvider.entries.filter { sources.apiKeyRepository.isConfigured(it) }
        ProviderAvailability(
            models = configured.associateWith { provider ->
                sources.apiKeyRepository.getModel(provider).first()?.takeIf { it.isNotBlank() }
            },
            auto = AutoProvider.resolve(sources.apiKeyRepository),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Failed to read which providers are set up for the node sheet")
        ProviderAvailability()
    }

    /**
     * Renders [template] through [PromptTemplateEngine] and exposes the resulting
     * segments via [OrchestratorUiState.previewState].
     *
     * Resolution may suspend on I/O (the `$MEMORY_SUMMARY` provider hits the database),
     * so the call runs on [scope]. The intermediate `Loading` state lets the UI
     * show a spinner if the user opens the sheet against a slow provider.
     *
     * @param template the raw prompt that may contain `$VARIABLE` placeholders.
     */
    fun requestPromptPreview(template: String) {
        uiState.update { it.copy(previewState = PromptPreviewState.Loading) }
        scope.launch {
            val segments = sources.promptTemplateEngine.renderSegments(
                template,
                sources.promptVariableProviders.toList(),
            )
            uiState.update { it.copy(previewState = PromptPreviewState.Ready(segments)) }
        }
    }

    /**
     * Closes the prompt-preview bottom sheet, returning the UI to its idle state.
     */
    fun dismissPromptPreview() {
        uiState.update { it.copy(previewState = PromptPreviewState.Hidden) }
    }
}
