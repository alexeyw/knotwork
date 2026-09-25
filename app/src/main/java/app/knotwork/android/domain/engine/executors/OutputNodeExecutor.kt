package app.knotwork.android.domain.engine.executors

import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.ExecutionScope
import app.knotwork.android.domain.models.NodeExecutionResult
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeOutput
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.usecases.LoadModelUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import javax.inject.Inject

/**
 * Executor for [NodeType.OUTPUT][app.knotwork.android.domain.models.NodeType.OUTPUT] nodes —
 * the terminal node of every pipeline run.
 *
 * Two modes:
 * - **With `systemPrompt`**: runs a local-model formatting pass over the upstream
 *   `inputText`, stripping common LLM conversational prefixes ("Here is the output:",
 *   …) before persisting the final response as an
 *   [AGENT][app.knotwork.android.domain.models.Role.AGENT] [ChatMessage].
 * - **Without `systemPrompt`**: persists the upstream `inputText` as-is. This is the
 *   path documented in `NodeContextConfig` § 6.3 where the OUTPUT node is treated as a
 *   pass-through and does not consume its [NodeContextConfig].
 *
 * Streaming tokens are surfaced as
 * [Thinking][app.knotwork.android.domain.models.AgentOrchestratorState.Thinking] /
 * [Answering][app.knotwork.android.domain.models.AgentOrchestratorState.Answering] states;
 * the final text is also emitted as
 * [Completed][app.knotwork.android.domain.models.AgentOrchestratorState.Completed] to signal
 * the end of the pipeline run.
 *
 * The chat message is persisted **only for the root run** ([ExecutionScope.depth] == 0).
 * A sub-pipeline's OUTPUT (depth > 0) returns its text to the parent `PIPELINE`
 * node as the node result (via `Completed`/`Result`) and does not write to chat,
 * so nested results never flood the conversation.
 */
class OutputNodeExecutor @Inject constructor(
    private val llmEngine: LlmInferenceEngine,
    private val loadModelUseCase: LoadModelUseCase,
    private val chatRepository: ChatRepository,
    private val localModelRepository: LocalModelRepository,
) : NodeExecutor {
    override fun execute(
        node: NodeModel,
        inputText: String,
        sessionId: String,
        originalPrompt: String,
        runId: String?,
        scope: ExecutionScope,
    ): Flow<NodeOutput> = flow {
        // Resolve the model that actually produced this answer so it stays
        // attributed correctly even after the user switches the active model.
        // Preference: the cloud provider label or on-device model recorded by the
        // answering node (scope.generatingModel), then the active model as a
        // fallback for legacy graphs. Only computed for the root run, which is
        // the one that persists to chat.
        val generatingModelName = if (scope.depth == 0) resolveGeneratingModelName(scope) else null
        val nodeSystemPrompt = node.systemPrompt
        if (!nodeSystemPrompt.isNullOrBlank()) {
            val fullPrompt = DefaultPrompts.renderTemplate(
                DefaultPrompts.Output.FORMATTING_TEMPLATE,
                mapOf(
                    "NODE_SYSTEM_PROMPT" to nodeSystemPrompt,
                    "INPUT_TEXT" to inputText,
                ),
            )

            val loadResult = loadModelUseCase(node.modelPath)
            if (loadResult is Result.Error) {
                val errorMsg = "Error loading local model for output node"
                emit(NodeOutput.State(AgentOrchestratorState.Error(errorMsg)))
                emit(NodeOutput.Result(NodeExecutionResult(error = errorMsg)))
                return@flow
            }

            val responseStream = llmEngine.generateResponseStream(fullPrompt)
            val accumulatedResponse = StringBuilder()
            var emittedThinking = false

            try {
                responseStream.collect { token ->
                    accumulatedResponse.append(token)
                    if (!emittedThinking) {
                        emit(NodeOutput.State(AgentOrchestratorState.Thinking(accumulatedResponse.toString())))
                        emittedThinking = true
                    } else {
                        emit(NodeOutput.State(AgentOrchestratorState.Answering(accumulatedResponse.toString())))
                    }
                }
            } catch (e: CancellationException) {
                // Preserve structured-concurrency cancellation: a broad `catch (Exception)`
                // would silently swallow cancellation and leave the parent coroutine running.
                throw e
            } catch (e: Exception) {
                Timber.tag(
                    "PipelineDebug",
                ).e(e, "[NODE_ERR] type=%s id=%s error in OutputNodeExecutor generation", node.type.name, node.id)
                emit(NodeOutput.State(AgentOrchestratorState.Error(e.message ?: "Unknown error")))
                emit(NodeOutput.Result(NodeExecutionResult(error = e.message)))
                return@flow
            }

            val generatedText = accumulatedResponse.toString().trim()
            var finalOutput = if (generatedText.isNotEmpty()) generatedText else inputText

            // Heuristic cleanup for common LLM conversational fillers
            val lowerCaseOutput = finalOutput.lowercase()
            val prefixesToRemove = listOf(
                "here is the formatted output:",
                "here is the output:",
                "formatted output:",
                "here is the result:",
            )
            for (prefix in prefixesToRemove) {
                if (lowerCaseOutput.startsWith(prefix)) {
                    finalOutput = finalOutput.substring(prefix.length).trim()
                    break
                }
            }

            // Only the root run's OUTPUT writes to the user-facing chat. A nested
            // sub-pipeline OUTPUT (depth > 0) returns its text up to the parent
            // PIPELINE node via the Completed/Result below instead of flooding
            // the chat with every intermediate sub-pipeline result.
            if (scope.depth == 0) {
                chatRepository.saveMessage(
                    ChatMessage(
                        sessionId = sessionId,
                        role = Role.AGENT,
                        content = finalOutput,
                        timestamp = System.currentTimeMillis(),
                        modelName = generatingModelName,
                    ),
                )
            }
            emit(NodeOutput.State(AgentOrchestratorState.Completed(finalOutput)))
            emit(NodeOutput.Result(NodeExecutionResult(outputText = finalOutput)))
        } else {
            // See above: nested OUTPUTs return their text as the PIPELINE node
            // result rather than persisting a chat message.
            if (scope.depth == 0) {
                chatRepository.saveMessage(
                    ChatMessage(
                        sessionId = sessionId,
                        role = Role.AGENT,
                        content = inputText,
                        timestamp = System.currentTimeMillis(),
                        modelName = generatingModelName,
                    ),
                )
            }
            emit(NodeOutput.State(AgentOrchestratorState.Completed(inputText)))
            emit(NodeOutput.Result(NodeExecutionResult(outputText = inputText)))
        }
    }

    /**
     * Resolves the display name of the model that generated this answer:
     *  1. the cloud provider label recorded by a `CLOUD` answering node, else
     *  2. the on-device model name resolved from the path recorded by a `LITE_RT`
     *     answering node, else
     *  3. the active model name (legacy graphs / no recorded answering node).
     *
     * @param scope The execution scope carrying the run tree's
     *   [RunGeneratingModel][app.knotwork.android.domain.models.RunGeneratingModel].
     * @return The model display name, or `null` when none can be resolved.
     */
    private suspend fun resolveGeneratingModelName(scope: ExecutionScope): String? {
        val generating = scope.generatingModel
        generating?.cloudLabel?.let { return it }
        generating?.localModelPath?.let { path ->
            localModelRepository.getAllModels().first().firstOrNull { it.path == path }?.name?.let { return it }
        }
        return localModelRepository.getActiveModel()?.name
    }
}
