package app.knotwork.android.domain.engine.executors

import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.engine.CloudErrorSanitizer
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.engine.executors.ToolCallParser.ToolCall
import app.knotwork.android.domain.engine.structured.CloudStructuredInferenceClientFactory
import app.knotwork.android.domain.engine.structured.CollectingRepairListener
import app.knotwork.android.domain.engine.structured.EngineStructuredInferenceClient
import app.knotwork.android.domain.engine.structured.GateResult
import app.knotwork.android.domain.engine.structured.StructuredInferenceClient
import app.knotwork.android.domain.engine.structured.StructuredOutputGate
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.ExecutionScope
import app.knotwork.android.domain.models.NodeExecutionResult
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeOutput
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.prompt.ChatTranscript
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.ToolRepository
import app.knotwork.android.domain.usecases.LoadModelUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executor for [NodeType.TOOL][app.knotwork.android.domain.models.NodeType.TOOL] nodes.
 *
 * Resolves which tool to run (either the explicit `node.toolName` or LLM-driven auto
 * selection via [DefaultPrompts.Tool.AUTO_SELECT_TEMPLATE]), builds its arguments, and
 * hands the resolved call to the shared [ToolInvocationGate], which owns the
 * Human-in-the-Loop (HITL) risk / approval / parking contract and the actual dispatch
 * through [ToolRepository]. The gate is shared with [SkillNodeExecutor] so a skill that
 * initiates a tool call can never weaken that contract.
 *
 * Tool observations and "Execution denied by user" markers are persisted by the gate as
 * non-final [SYSTEM][app.knotwork.android.domain.models.Role.SYSTEM] chat messages so the
 * agent console can replay them without polluting the user-facing chat list.
 *
 * Marked `@Singleton` for parity with its previous lifetime; all per-session suspension
 * state now lives in the [ToolInvocationGate] singleton.
 */
@Singleton
class ToolNodeExecutor @Inject constructor(
    private val llmEngine: LlmInferenceEngine,
    private val loadModelUseCase: LoadModelUseCase,
    private val toolRepository: ToolRepository,
    private val toolInvocationGate: ToolInvocationGate,
    private val structuredOutputGate: StructuredOutputGate,
    private val settingsRepository: SettingsRepository,
    private val cloudStructuredFactory: CloudStructuredInferenceClientFactory,
) : NodeExecutor {

    /**
     * Completes the live approval request [requestId] of [sessionId] with the
     * user's decision. Delegates to the shared [ToolInvocationGate]; kept here so
     * existing callers (`GraphExecutionEngine`, the AppFunctions E2E test) keep
     * working unchanged.
     *
     * @param sessionId chat session id whose pending approval is being resolved.
     * @param requestId identity of the request the decision was given for.
     * @param isApproved `true` if the user approved tool execution, `false` to deny it.
     * @return `true` when the decision settled the live request it names.
     */
    fun resumeWithApproval(sessionId: String, requestId: String, isApproved: Boolean): Boolean =
        toolInvocationGate.resumeWithApproval(sessionId, requestId, isApproved)

    /**
     * Returns the approval request the run of [sessionId] is currently suspended on, or
     * `null` when no approval gate is active. Delegates to the shared [ToolInvocationGate].
     *
     * @param sessionId chat session id used as the lookup key.
     * @return the pending [AgentOrchestratorState.WaitingForApproval], or `null`.
     */
    fun pendingApprovalFor(sessionId: String): AgentOrchestratorState.WaitingForApproval? =
        toolInvocationGate.pendingApprovalFor(sessionId)

    /**
     * Test-only readiness probe; delegates to the shared [ToolInvocationGate].
     */
    @androidx.annotation.VisibleForTesting
    internal fun hasPendingApproval(sessionId: String): Boolean = toolInvocationGate.hasPendingApproval(sessionId)

    @Suppress("LongMethod")
    override fun execute(
        node: NodeModel,
        inputText: String,
        sessionId: String,
        originalPrompt: String,
        runId: String?,
        scope: ExecutionScope,
    ): Flow<NodeOutput> = flow {
        val toolNameConfig = node.toolName
        // A blank / null tool name is the "Auto" selection — the editor's empty
        // tool option is persisted as `null` (see `NodeConfigCodec.apply`), and
        // it means the same thing as the explicit "auto" sentinel: let the LLM
        // pick a tool from the registry at runtime. Only a *configured but
        // unknown* tool name is a real error, handled in the else branch below.
        val isAutoSelect = toolNameConfig.isNullOrBlank() || toolNameConfig.equals("auto", ignoreCase = true)

        emit(NodeOutput.State(AgentOrchestratorState.Thinking("Analyzing task for tool execution...")))

        val configuredMaxRepairs = settingsRepository.structuredOutputMaxRepairs.first()
        // Engine selection mirrors the SKILL node: a node carrying a cloud
        // provider resolves its tool call against that provider; otherwise the
        // local LiteRT engine backs it. Tool selection / arguments are JSON
        // objects, so a provider with native JSON support drops the repair
        // budget to zero (trust-but-verify).
        val resolution = resolveInference(node) ?: return@flow
        val inference = resolution.first
        val maxRepairs = if (resolution.second) 0 else configuredMaxRepairs

        val resolved: Pair<String, String> = try {
            resolveToolCall(isAutoSelect, toolNameConfig, node, inputText, maxRepairs, inference)
        } catch (e: ResolutionFailed) {
            // A handled resolution failure (no tools, unknown tool, gate exhausted on
            // auto-select): the diagnostics were already emitted; end the node here.
            return@flow
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An engine-level failure during argument generation (not a validation
            // miss) — surface it as a graceful node error rather than tearing down the run.
            // The engine may be a cloud provider, whose error can quote its key (see
            // CloudErrorSanitizer); the throwable is kept out of the log for the same reason.
            val errorMsg = "Error generating tool arguments: ${CloudErrorSanitizer.sanitize(e)}"
            Timber.tag("PipelineDebug").e("Tool argument generation failed (%s): %s", e::class.simpleName, errorMsg)
            emit(NodeOutput.State(AgentOrchestratorState.Error(errorMsg)))
            emit(NodeOutput.Result(NodeExecutionResult(error = errorMsg)))
            return@flow
        }

        toolInvocationGate.dispatch(
            collector = this,
            nodeType = node.type.name,
            nodeId = node.id,
            sessionId = sessionId,
            runId = runId,
            resolvedToolName = resolved.first,
            resolvedToolArgs = resolved.second,
            alwaysConfirm = node.alwaysConfirm == true,
        )
    }

    /**
     * Marker thrown by [resolveToolCall] when it has already emitted the terminal
     * diagnostics for a handled failure and the node should simply end. Kept private so
     * it never escapes the executor.
     */
    private class ResolutionFailed : Exception()

    /**
     * Resolves the structured-inference client for [node]: a cloud-backed client
     * when the node selects a configured cloud provider, otherwise the local
     * LiteRT engine. An unavailable cloud provider (missing credentials /
     * local-only mode / unknown id) emits a console note and degrades to the
     * local engine.
     *
     * @return The `(client, supportsNativeJson)` pair, or `null` when even the
     *   local fallback could not load its model (a terminal error was emitted).
     */
    private suspend fun FlowCollector<NodeOutput>.resolveInference(
        node: NodeModel,
    ): Pair<StructuredInferenceClient, Boolean>? {
        val providerId = node.cloudProvider?.takeIf { it.isNotBlank() }
        if (providerId != null) {
            val provider = CloudProvider.fromId(providerId)
            val cloud = provider?.let { cloudStructuredFactory.create(it) { } }
            if (cloud != null) {
                return cloud.inference to cloud.supportsNativeJson
            }
            emit(
                NodeOutput.Console(
                    ConsoleEventType.Error,
                    "Cloud provider '$providerId' unavailable for '${node.label}'; falling back to the local model",
                ),
            )
        }
        val loadResult = loadModelUseCase(node.modelPath)
        if (loadResult is Result.Error) {
            val errorMsg = "Error loading local model for tool node"
            emit(NodeOutput.State(AgentOrchestratorState.Error(errorMsg)))
            emit(NodeOutput.Result(NodeExecutionResult(error = errorMsg)))
            return null
        }
        return EngineStructuredInferenceClient(llmEngine) to false
    }

    /**
     * Resolves the `(toolName, argumentsJson)` to dispatch, running the model output
     * through the structured-output gate.
     *
     * For auto-select the `{tool, arguments}` envelope is validated; an exhausted gate has
     * no fallback tool, so the terminal parse-error result is emitted here and
     * [ResolutionFailed] is thrown. For a fixed tool the arguments object is validated; an
     * exhausted gate falls back to the last raw output so the tool execution surfaces its
     * own error observation. Engine-level inference exceptions propagate to the caller's
     * graceful handler.
     */
    private suspend fun FlowCollector<NodeOutput>.resolveToolCall(
        isAutoSelect: Boolean,
        toolNameConfig: String?,
        node: NodeModel,
        inputText: String,
        maxRepairs: Int,
        inference: StructuredInferenceClient,
    ): Pair<String, String> {
        if (isAutoSelect) {
            val availableTools = toolRepository.getAvailableTools()
            if (availableTools.isEmpty()) {
                val errorMsg = "No tools available for auto selection"
                emit(NodeOutput.State(AgentOrchestratorState.Error(errorMsg)))
                emit(NodeOutput.Result(NodeExecutionResult(error = errorMsg)))
                throw ResolutionFailed()
            }

            // The description and the parameter schema are the tool's own text (an
            // MCP server's, for a server tool): their further lines are indented, so
            // only a real tool can open a `Tool:` line.
            val toolsDescriptions = availableTools.joinToString("\n\n") {
                listOf(
                    "Tool: ${it.name}",
                    ChatTranscript.entry("Description: ", it.description),
                    ChatTranscript.entry("Parameters: ", it.parameters),
                ).joinToString("\n")
            }

            val prompt = DefaultPrompts.renderTemplate(
                DefaultPrompts.Tool.AUTO_SELECT_TEMPLATE,
                mapOf(
                    "AVAILABLE_TOOLS" to toolsDescriptions,
                    "INPUT_TEXT" to inputText,
                ),
            )

            // Gate the `{tool, arguments}` envelope. On failure the auto-select path
            // has no fallback tool to run, so it takes the existing error path — only
            // after the gate has spent its repair attempts.
            return when (val result = runArgGate(ToolCall.serializer(), prompt, node, maxRepairs, inference)) {
                is GateResult.Success ->
                    requireSelectable(result.value.tool, availableTools) to result.value.arguments.asArgumentString()
                is GateResult.Failed -> {
                    val errorMsg = "Failed to parse tool selection JSON from LLM output"
                    emit(
                        NodeOutput.Console(
                            ConsoleEventType.Error,
                            "$errorMsg (after ${result.repairs} repair attempt(s))",
                        ),
                    )
                    emit(NodeOutput.State(AgentOrchestratorState.Error(errorMsg)))
                    emit(NodeOutput.Result(NodeExecutionResult(error = errorMsg)))
                    throw ResolutionFailed()
                }
            }
        }

        val tools = toolRepository.getAvailableTools()
        val selectedTool = tools.find { it.name == toolNameConfig }
        if (selectedTool == null) {
            val errorMsg = "Tool $toolNameConfig not found in available tools"
            emit(NodeOutput.State(AgentOrchestratorState.Error(errorMsg)))
            emit(NodeOutput.Result(NodeExecutionResult(error = errorMsg)))
            throw ResolutionFailed()
        }

        val prompt = DefaultPrompts.renderTemplate(
            DefaultPrompts.Tool.ARGUMENT_GENERATION_TEMPLATE,
            mapOf(
                "TOOL_NAME" to selectedTool.name,
                "TOOL_DESCRIPTION" to selectedTool.description,
                "TOOL_PARAMETERS" to selectedTool.parameters,
                "INPUT_TEXT" to inputText,
            ),
        )

        // Gate the arguments as a JSON object. On failure the tool name is fixed,
        // so we still dispatch — with the last raw output as arguments — and let the
        // tool execution surface its own error observation (the existing path,
        // reached after repairs rather than on the first malformed reply).
        return when (val result = runArgGate(JsonObject.serializer(), prompt, node, maxRepairs, inference)) {
            is GateResult.Success -> selectedTool.name to extractFixedToolArgs(result.value, selectedTool.name)
            is GateResult.Failed -> {
                emit(
                    NodeOutput.Console(
                        ConsoleEventType.Error,
                        "Tool '${selectedTool.name}' arguments did not validate after " +
                            "${result.repairs} repair attempt(s); executing with the raw output",
                    ),
                )
                selectedTool.name to result.lastRaw.trim()
            }
        }
    }

    /**
     * Verifies that an auto-selected tool name actually exists in the catalogue the
     * model was offered, and returns the catalogue's own spelling of it.
     *
     * The fixed-tool branch has always validated its configured name against
     * [ToolRepository.getAvailableTools]; auto-select did not, so a name the model
     * mangled travelled all the way to [ToolInvocationGate] and first failed at the
     * risk lookup — later than necessary and phrased as an internal fault
     * ("Risk lookup failed … Unknown tool") rather than as what it is. This restores
     * the symmetry: the check happens where the choice is made.
     *
     * Only surrounding whitespace is normalised. A near-miss is deliberately NOT
     * repaired to the closest candidate — silently running a *different* tool than
     * the model named would move a call across the HITL risk boundary, which is
     * exactly the decision the gate exists to protect.
     *
     * @param selected The tool name emitted by the model.
     * @param availableTools The catalogue offered to the model in the same pass.
     * @return The matching catalogue name.
     * @throws ResolutionFailed after emitting the terminal diagnostics, when the name
     *   matches no offered tool.
     */
    private suspend fun FlowCollector<NodeOutput>.requireSelectable(
        selected: String,
        availableTools: List<AgentTool>,
    ): String {
        val normalised = selected.trim()
        val match = availableTools.find { it.name == normalised }
        if (match != null) {
            return match.name
        }
        val allNames = availableTools.joinToString(", ") { it.name }
        val offered = if (allNames.length > AVAILABLE_TOOLS_ERROR_CHARS) {
            allNames.take(AVAILABLE_TOOLS_ERROR_CHARS) + "…"
        } else {
            allNames
        }
        val errorMsg = "Tool $normalised not found in available tools"
        emit(
            NodeOutput.Console(
                ConsoleEventType.Error,
                "$errorMsg — the model selected a name outside the offered catalogue ($offered)",
            ),
        )
        emit(NodeOutput.State(AgentOrchestratorState.Error(errorMsg)))
        emit(NodeOutput.Result(NodeExecutionResult(error = errorMsg)))
        throw ResolutionFailed()
    }

    /**
     * Runs one structured-output gate pass for a tool inference, surfacing each repair
     * attempt as a console line (which the engine also counts in the repair metric).
     *
     * @param T The validated payload type ([ToolCall] for auto-select, [JsonObject] for
     *   a fixed tool's arguments).
     * @param serializer Deserializer the gate validates the reply against.
     * @param prompt The fully-rendered tool prompt.
     * @param node The TOOL node (its label keys the repair metric / console line).
     * @param maxRepairs The configured repair ceiling.
     * @param inference The resolved gate client (cloud or local).
     * @return The gate outcome.
     */
    private suspend fun <T> FlowCollector<NodeOutput>.runArgGate(
        serializer: KSerializer<T>,
        prompt: String,
        node: NodeModel,
        maxRepairs: Int,
        inference: StructuredInferenceClient,
    ): GateResult<T> {
        val collected = CollectingRepairListener()
        val result = structuredOutputGate.runJson(
            inference = inference,
            prompt = prompt,
            serializer = serializer,
            nodeName = node.label,
            maxRepairs = maxRepairs,
            listener = collected,
        )
        collected.attempts.forEach { attempt ->
            emit(NodeOutput.Console(ConsoleEventType.StructuredOutputRepair, attempt.consoleMessage()))
        }
        return result
    }

    /**
     * Derives the arguments string for a fixed tool from the validated JSON object.
     *
     * The fixed-tool template may return either a bare arguments object or the
     * `{"tool": NAME, "arguments": <value>}` envelope. When the object carries a matching
     * `tool` field and an `arguments` field, the latter is used; otherwise the whole
     * object is treated as the arguments.
     *
     * @param obj The validated JSON object.
     * @param toolName The fixed tool's name to match the optional `tool` field against.
     * @return The arguments as a JSON string.
     */
    private fun extractFixedToolArgs(obj: JsonObject, toolName: String): String {
        val toolField = (obj["tool"] as? JsonPrimitive)?.contentOrNull
        val argsField = obj["arguments"]
        return if (toolField == toolName && argsField != null) argsField.asArgumentString() else obj.toString()
    }

    private companion object {
        /**
         * Ceiling on how much of the offered catalogue is echoed back in the
         * "tool not found" console line. A connected MCP server can advertise
         * dozens of tools; the line is a diagnostic hint, not the catalogue.
         */
        const val AVAILABLE_TOOLS_ERROR_CHARS = 300
    }
}
