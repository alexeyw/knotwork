package app.knotwork.android.domain.engine.executors

import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.constants.PipelineExecutionDefaults
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ExecutionScope
import app.knotwork.android.domain.models.NodeExecutionResult
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeOutput
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.usecases.LoadModelUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import javax.inject.Inject

/**
 * Executor for [NodeType.SUMMARY][app.knotwork.android.domain.models.NodeType.SUMMARY] nodes.
 *
 * Runs a local-model synthesis pass that consolidates upstream subtask results
 * (`inputText`) and the original user request (`originalPrompt`) into a single coherent
 * summary, using the canonical [DefaultPrompts.Summary.SYNTHESIS_TEMPLATE]. Streams the
 * generation as orchestrator state updates and emits the trimmed final text as a
 * [NodeOutput.Result]. Falls back to a literal `"No summary generated."` placeholder
 * when the model produces an empty response, so downstream nodes never see a blank
 * input.
 */
class SummaryNodeExecutor @Inject constructor(
    private val llmEngine: LlmInferenceEngine,
    private val loadModelUseCase: LoadModelUseCase,
) : NodeExecutor {

    override fun execute(
        node: NodeModel,
        inputText: String,
        sessionId: String,
        originalPrompt: String,
        runId: String?,
        scope: ExecutionScope,
    ): Flow<NodeOutput> = flow {
        val nodeSystemPrompt = node.systemPrompt ?: DefaultPrompts.Summary.SYSTEM_FALLBACK

        val fullPrompt = DefaultPrompts.renderTemplate(
            DefaultPrompts.Summary.SYNTHESIS_TEMPLATE,
            mapOf(
                "NODE_SYSTEM_PROMPT" to nodeSystemPrompt,
                "ORIGINAL_TASK" to originalPrompt,
                "RESULTS_OF_SUBTASKS" to inputText,
            ),
        )

        val loadResult = loadModelUseCase(node.modelPath)
        if (loadResult is Result.Error) {
            val errorMsg = "Error loading local model for summary node"
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
            ).e(e, "[NODE_ERR] type=%s id=%s error in SummaryNodeExecutor generation", node.type.name, node.id)
            emit(NodeOutput.State(AgentOrchestratorState.Error(e.message ?: "Unknown error")))
            emit(NodeOutput.Result(NodeExecutionResult(error = e.message)))
            return@flow
        }

        val generatedText = accumulatedResponse.toString().trim()
        val fullResponseText = if (generatedText.isNotEmpty()) generatedText else "No summary generated."

        kotlinx.coroutines.delay(PipelineExecutionDefaults.NODE_RESULT_EMIT_DELAY_MS)

        emit(NodeOutput.Result(NodeExecutionResult(outputText = fullResponseText)))
    }
}
