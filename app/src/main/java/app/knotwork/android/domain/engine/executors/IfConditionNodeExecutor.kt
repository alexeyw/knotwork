package app.knotwork.android.domain.engine.executors

import app.knotwork.android.domain.engine.structured.CollectingRepairListener
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.ExecutionScope
import app.knotwork.android.domain.models.NodeExecutionResult
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeOutput
import app.knotwork.android.domain.usecases.EvaluateIfConditionUseCase
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Executor for [NodeType.IF_CONDITION][app.knotwork.android.domain.models.NodeType.IF_CONDITION]
 * branching nodes.
 *
 * Delegates the boolean evaluation to [EvaluateIfConditionUseCase] (keyword match,
 * complexity heuristics, or LLM-based judgement depending on the node's configuration)
 * and forwards the upstream `inputText` unchanged so that downstream branches still see
 * the original payload. The branch decision is published via
 * [NodeExecutionResult.conditionResult][app.knotwork.android.domain.models.NodeExecutionResult.conditionResult],
 * which `GraphExecutionEngine` reads to pick the next connection.
 *
 * **Structured-output policy.** The LLM path runs through the validate-and-repair
 * gate inside the use case. Each repair attempt is surfaced as a
 * [ConsoleEventType.StructuredOutputRepair] console line (which the engine also
 * counts in the repair metric). When the gate ultimately fails, the node keeps
 * its `False` default branch — a graceful fork rather than a halted run — but
 * emits a [ConsoleEventType.Error] line so the fallback is observable instead of
 * silent. Repairs are an internal node mechanic: they consume repair budget, not
 * pipeline steps.
 */
class IfConditionNodeExecutor @Inject constructor(private val evaluateIfConditionUseCase: EvaluateIfConditionUseCase) :
    NodeExecutor {
    override fun execute(
        node: NodeModel,
        inputText: String,
        sessionId: String,
        originalPrompt: String,
        runId: String?,
        scope: ExecutionScope,
    ): Flow<NodeOutput> = kotlinx.coroutines.flow.flow {
        val repairListener = CollectingRepairListener()
        // Presence-only signal: true when the run's message carried an image. The engine
        // derives it from the live delivery on a fresh run and from the persisted
        // PipelineRun.hadImage on a resumed one, so an IF node forks correctly on image
        // presence even when it executes live past a checkpoint resume — and whether or not
        // a downstream vision node has consumed the image yet.
        val hasImage = scope.run.imagePresent
        val outcome = evaluateIfConditionUseCase(node, inputText, hasImage, repairListener)

        // Surface each buffered repair attempt; the engine renders it and bumps
        // the per-node repair counter.
        repairListener.attempts.forEach { attempt ->
            emit(NodeOutput.Console(ConsoleEventType.StructuredOutputRepair, attempt.consoleMessage()))
        }
        if (outcome.gateFailed) {
            emit(
                NodeOutput.Console(
                    ConsoleEventType.Error,
                    "IF_CONDITION '${node.label}' could not classify input; defaulting to the False branch",
                ),
            )
        }

        emit(NodeOutput.Result(NodeExecutionResult(conditionResult = outcome.value, outputText = inputText)))
    }
}
