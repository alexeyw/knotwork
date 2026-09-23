package app.knotwork.android.domain.engine.executors

import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ExecutionScope
import app.knotwork.android.domain.models.NodeExecutionResult
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeOutput
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.Skill
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.prompt.PromptVariableProvider
import app.knotwork.android.domain.repositories.SkillRepository
import app.knotwork.android.domain.repositories.ToolRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import javax.inject.Inject

/**
 * Executor for [NodeType.SKILL] nodes.
 *
 * A SKILL node runs a reusable [Skill] as an inference step. Three things come
 * from the skill rather than from the node configuration: the instruction
 * (system prompt), the visible-tool allowlist, and the default context
 * selection. The executor:
 *
 *  1. loads the skill referenced by [NodeModel.skillId];
 *  2. renders the skill's instruction through [PromptTemplateEngine], with all
 *     built-in `$VARIABLE` providers available — but `$TOOLS` scoped so it
 *     expands only to the skill's allowlist (see [ScopedToolsVariableProvider]);
 *  3. runs inference on the node's selected engine by delegating to the
 *     existing [LiteRtNodeExecutor] / [CloudLlmNodeExecutor] (engine chosen by
 *     [NodeModel.cloudProvider]: `null`/blank ⇒ local LiteRT, otherwise cloud),
 *     so streaming, metrics, and cancellation handling are reused, not duplicated;
 *  4. if the inference output initiates a tool call, enforces the skill's
 *     allowlist **at the executor level** before dispatch — a call to a tool
 *     outside the allowlist is rejected with a typed error observation and is
 *     never executed; an allowed call is dispatched through the shared
 *     [ToolInvocationGate], so the tool risk / Human-in-the-Loop contract is
 *     never weakened by the skill. The node's `alwaysConfirm` switch reaches the
 *     gate exactly as it does from a TOOL node: it can add a confirmation to
 *     every call the skill makes, never remove one.
 *
 * The instruction restriction is substantive, not cosmetic: scoping `$TOOLS`
 * keeps a tool out of the model's view, and the executor-level allowlist check
 * is the hard guard that makes the constraint real even if the model emits a
 * call for a tool it was never shown.
 */
class SkillNodeExecutor @Inject constructor(
    private val skillRepository: SkillRepository,
    private val promptTemplateEngine: PromptTemplateEngine,
    private val promptVariableProviders: Set<@JvmSuppressWildcards PromptVariableProvider>,
    private val toolRepository: ToolRepository,
    private val liteRtNodeExecutor: LiteRtNodeExecutor,
    private val cloudLlmNodeExecutor: CloudLlmNodeExecutor,
    private val toolInvocationGate: ToolInvocationGate,
) : NodeExecutor {

    override fun execute(
        node: NodeModel,
        inputText: String,
        sessionId: String,
        originalPrompt: String,
        runId: String?,
        scope: ExecutionScope,
    ): Flow<NodeOutput> = flow {
        val skillId = node.skillId
        if (skillId.isNullOrBlank()) {
            emit(failure("SKILL node '${node.label}' has no skill selected"))
            return@flow
        }

        val skill = try {
            skillRepository.getSkillById(skillId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(failure("Failed to load skill '$skillId': ${e.message ?: "unknown error"}"))
            return@flow
        }
        if (skill == null) {
            emit(failure("Skill '$skillId' not found — it may have been deleted"))
            return@flow
        }

        // Render the skill's instruction with `$TOOLS` scoped to the skill's
        // allowlist; every other built-in variable resolves as usual.
        val renderedInstruction = promptTemplateEngine.render(skill.instruction, scopedProviders(skill))

        // Delegate the actual inference to the existing engine executors via a
        // derived node whose already-rendered instruction is its system prompt.
        // The engine never renders SKILL system prompts (SKILL is not an
        // LLM_NODE_TYPE), so the rendering above is the single source of truth.
        val useCloud = !node.cloudProvider.isNullOrBlank()
        val inferenceNode = node.copy(
            type = if (useCloud) NodeType.CLOUD else NodeType.LITE_RT,
            systemPrompt = renderedInstruction,
        )
        val inferenceExecutor = if (useCloud) cloudLlmNodeExecutor else liteRtNodeExecutor

        var inferenceText: String? = null
        var inferenceError: String? = null
        var tokenCount: Int? = null
        inferenceExecutor.execute(inferenceNode, inputText, sessionId, originalPrompt, runId, scope)
            .collect { output ->
                when (output) {
                    is NodeOutput.State -> emit(output)
                    is NodeOutput.Console -> emit(output)
                    is NodeOutput.Result -> {
                        inferenceText = output.result.outputText
                        inferenceError = output.result.error
                        tokenCount = output.result.tokenCount
                    }
                }
            }

        if (inferenceError != null) {
            emit(NodeOutput.Result(NodeExecutionResult(error = inferenceError, tokenCount = tokenCount)))
            return@flow
        }

        val outputText = inferenceText.orEmpty()
        val toolCall = ToolCallParser.parseToolSelection(outputText)
        if (toolCall == null) {
            // Pure-inference skill (e.g. a translator / summarizer): the
            // rendered response is the node's output.
            emit(NodeOutput.Result(NodeExecutionResult(outputText = outputText, tokenCount = tokenCount)))
            return@flow
        }

        val (toolName, toolArgs) = toolCall
        // Executor-level allowlist enforcement. A `null` allowlist means "all
        // tools allowed"; any explicit list (including the empty list) means a
        // call outside it is refused here, before the gate, so a model that
        // emits a call for a tool it was never shown cannot bypass the skill's
        // restriction.
        val allowlist = skill.toolAllowlist
        if (allowlist != null && toolName !in allowlist) {
            val message = "Tool '$toolName' is not in skill '${skill.name}' allowlist and was not executed."
            Timber.tag("PipelineDebug").w(message)
            emit(NodeOutput.State(AgentOrchestratorState.ObservationResult(toolName, message)))
            emit(
                NodeOutput.Result(
                    NodeExecutionResult(
                        outputText = message,
                        resolvedToolName = toolName,
                        tokenCount = tokenCount,
                    ),
                ),
            )
            return@flow
        }

        // Allowed call: dispatch through the shared HITL gate, which owns risk
        // classification, the destructive-block / approval contract, parking,
        // execution, and the terminal observation result.
        toolInvocationGate.dispatch(
            collector = this,
            nodeType = node.type.name,
            nodeId = node.id,
            sessionId = sessionId,
            runId = runId,
            resolvedToolName = toolName,
            resolvedToolArgs = toolArgs,
            alwaysConfirm = node.alwaysConfirm == true,
        )
    }

    /**
     * Builds the provider list used to render the skill instruction: the full
     * injected provider set with the global `$TOOLS` provider swapped for a
     * [ScopedToolsVariableProvider] bound to this skill's allowlist.
     */
    private fun scopedProviders(skill: Skill): List<PromptVariableProvider> {
        val withoutTools = promptVariableProviders.filterNot { it.key() == TOOLS_KEY }
        return withoutTools + ScopedToolsVariableProvider(toolRepository, skill.toolAllowlist)
    }

    /**
     * Builds a terminal [NodeOutput.Result] carrying an error message. Mirrors
     * `PipelineNodeExecutor.failure`: the engine renders the failure from the
     * result, so no separate state emission is needed here.
     */
    private fun failure(message: String): NodeOutput.Result = NodeOutput.Result(NodeExecutionResult(error = message))

    private companion object {
        const val TOOLS_KEY = "TOOLS"
    }
}

/**
 * A `$TOOLS` provider scoped to a single skill's allowlist.
 *
 * The skill allowlist is tri-state:
 *  - `null` — unrestricted: behaves like the global provider, listing every
 *    currently available tool;
 *  - empty list — instruction-only: resolves to an empty string (no tools);
 *  - non-empty list — an explicit subset: lists only the available tools whose
 *    name is in the allowlist.
 *
 * Each line is `name — description` (em-dash), matching the format of the
 * global `ToolsVariableProvider` so prompts read identically whether or not a
 * skill is in play.
 *
 * @property toolRepository source of the currently available tools.
 * @property allowlist the skill's tri-state tool allowlist.
 */
private class ScopedToolsVariableProvider(
    private val toolRepository: ToolRepository,
    private val allowlist: List<String>?,
) : PromptVariableProvider {

    override fun key(): String = "TOOLS"

    override suspend fun resolve(): String {
        val available = toolRepository.getAvailableTools()
        val visible = if (allowlist == null) available else available.filter { it.name in allowlist }
        if (visible.isEmpty()) return ""
        return visible.joinToString(separator = "\n") { tool -> "${tool.name} — ${tool.description}" }
    }
}
