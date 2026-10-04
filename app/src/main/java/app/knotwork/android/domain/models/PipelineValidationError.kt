package app.knotwork.android.domain.models

/**
 * Represents a validation error in a pipeline graph.
 */
sealed class PipelineValidationError {
    /**
     * Error indicating the pipeline is missing an INPUT node.
     */
    data object MissingInput : PipelineValidationError()

    /**
     * Error indicating the pipeline is missing an OUTPUT node.
     */
    data object MissingOutput : PipelineValidationError()

    /**
     * Error indicating the pipeline has multiple INPUT nodes.
     */
    data object MultipleInputs : PipelineValidationError()

    /**
     * Error indicating the pipeline has multiple OUTPUT nodes.
     */
    data object MultipleOutputs : PipelineValidationError()

    /**
     * Error indicating the pipeline graph contains cycles.
     */
    data object HasCycles : PipelineValidationError()

    /**
     * Error indicating an INPUT node has no outgoing connections.
     */
    data object DisconnectedInput : PipelineValidationError()

    /**
     * Error indicating an OUTPUT node has no incoming connections.
     */
    data object DisconnectedOutput : PipelineValidationError()

    /**
     * Error indicating there are nodes not reachable from an INPUT node.
     */
    data object UnreachableNode : PipelineValidationError()

    /**
     * Error indicating there are nodes that do not lead to an OUTPUT node.
     */
    data object DeadEndNode : PipelineValidationError()

    /**
     * Error indicating that a node's [NodeContextConfig] has every flag
     * disabled, which would cause the node executor to receive an empty
     * input string at runtime and produce a meaningless answer or fail.
     *
     * This validation is only applied to nodes whose [NodeType] consumes
     * upstream pipeline context. The starting [NodeType.INPUT] node is
     * exempt because it has no upstream data to consume.
     *
     * @property nodeId The unique identifier of the node whose context
     * configuration is empty. Used by the presentation layer to look up
     * the node label and surface a human-readable error to the user.
     */
    data class NodeEmptyContext(val nodeId: String) : PipelineValidationError()

    /**
     * Error indicating a [NodeType.PIPELINE] node has no target pipeline
     * selected (its [NodeModel.targetPipelineId] is `null` or blank). This is a
     * structural, single-graph check raised by [PipelineGraph.validate]; the
     * cross-pipeline reference checks below come from `PipelineCompositionValidator`.
     *
     * @property nodeId The id of the PIPELINE node with no target, for deep-link
     * from the editor's validation bar.
     */
    data class MissingTargetPipeline(val nodeId: String) : PipelineValidationError()

    /**
     * Error indicating a [NodeType.PIPELINE] node references a pipeline id that
     * does not resolve to any stored pipeline (deleted or never existed).
     *
     * @property nodeId The id of the referencing PIPELINE node, for deep-link.
     * @property targetPipelineId The unresolved pipeline id that was referenced.
     */
    data class TargetPipelineNotFound(val nodeId: String, val targetPipelineId: String) : PipelineValidationError()

    /**
     * Error indicating the pipeline composition contains a cycle: a chain of
     * [NodeType.PIPELINE] references that returns to a pipeline already on the
     * path (including a pipeline referencing itself). A cyclic composition can
     * never terminate and is rejected before any run starts.
     *
     * @property pipelineChain The pipeline ids forming the cycle, in call order,
     * with the repeated id appended last (e.g. `[A, B, A]`) so the UI can render
     * the offending chain.
     */
    data class PipelineCycle(val pipelineChain: List<String>) : PipelineValidationError()

    /**
     * Error indicating the static nesting depth of the pipeline composition
     * exceeds the configured ceiling
     * ([app.knotwork.android.domain.repositories.RunSettings.pipelineMaxNestingDepth]).
     * Computed over the acyclic call graph (a cycle is reported separately as
     * [PipelineCycle]).
     *
     * @property pipelineChain The deepest chain of pipeline ids that breaches the
     * limit, in call order.
     * @property limit The configured maximum nesting depth.
     */
    data class PipelineNestingTooDeep(val pipelineChain: List<String>, val limit: Int) : PipelineValidationError()

    /**
     * Error indicating a [NodeType.SKILL] node has no skill selected (its
     * [NodeModel.skillId] is `null` or blank). Raised by [PipelineGraph.validate].
     *
     * @property nodeId The id of the SKILL node with no skill, for deep-link
     * from the editor's validation bar.
     */
    data class MissingSkill(val nodeId: String) : PipelineValidationError()

    /**
     * Error indicating a [NodeType.SKILL] node references a skill id that does
     * not resolve to any stored skill (deleted or never existed).
     *
     * @property nodeId The id of the referencing SKILL node, for deep-link.
     * @property skillId The unresolved skill id that was referenced.
     */
    data class SkillNotFound(val nodeId: String, val skillId: String) : PipelineValidationError()
}
