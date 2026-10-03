package app.knotwork.android.domain.models

/**
 * Discriminated output of a [app.knotwork.android.domain.engine.executors.NodeExecutor.execute] flow.
 *
 * Replaces the previous untyped `Flow<Any>` channel. A node executor emits intermediate
 * pipeline-state updates (rendered as UI states by the engine) followed by exactly one
 * terminal [Result] that carries the node's textual output and metrics. Modelling these
 * two payloads as a sealed class lets [app.knotwork.android.domain.engine.GraphExecutionEngine]
 * dispatch via an exhaustive `when` instead of brittle `is`-checks against `Any`.
 */
sealed class NodeOutput {
    /**
     * Carries an [AgentOrchestratorState] update. Multiple [State] values may be emitted by a
     * single execution as the node streams progress (e.g. `Thinking`, `Answering`,
     * `ExecutingTool`, `AwaitingClarification`).
     */
    data class State(
        /** Orchestrator state update produced by the running node. */
        val state: AgentOrchestratorState,
    ) : NodeOutput()

    /**
     * Terminal value of a node execution. Exactly one [Result] is expected per `execute()` flow;
     * it provides the [NodeExecutionResult] consumed by the engine (output text, error, routing
     * key, condition result, token count, resolved tool name).
     */
    data class Result(
        /** Final [NodeExecutionResult] consumed by `GraphExecutionEngine`. */
        val result: NodeExecutionResult,
    ) : NodeOutput()

    /**
     * A console line the node wants surfaced through the agent console.
     *
     * An executor has no direct access to the engine's console sink, so it emits
     * this variant and [app.knotwork.android.domain.engine.GraphExecutionEngine]
     * translates it into a `RunConsole.push(type, message)` call (assigning the run's
     * monotonic `seq` and nesting `depth`). The structured-output gate uses it to
     * announce each repair attempt
     * ([ConsoleEventType.StructuredOutputRepair][ConsoleEventType.StructuredOutputRepair])
     * and to surface a per-node failure ([ConsoleEventType.Error][ConsoleEventType.Error])
     * when a structured consumer falls back to its default branch.
     */
    data class Console(
        /** Category of the console event, driving its colour and filter chip. */
        val type: ConsoleEventType,
        /** Pre-formatted human-readable console line. */
        val message: String,
    ) : NodeOutput()
}
