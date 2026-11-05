package app.knotwork.android.domain.models

/**
 * Why a pipeline, a pipeline bundle or a memory export could not be imported.
 *
 * A value, not a sentence: the screen words it in the interface language. Every
 * string a problem carries is quoted from the file the user picked, so the
 * parser stores it display-safe — one line, no control or bidi characters, at
 * most `ImportedText.MAX_QUOTED_VALUE_LENGTH` characters — and the screen
 * bounds it again when it builds the sentence.
 */
sealed interface ImportProblem {

    /**
     * The document is nested deeper than the parser follows.
     *
     * @property maxDepth The deepest nesting it accepts.
     */
    data class TooDeep(val maxDepth: Int) : ImportProblem

    /**
     * The file is not JSON.
     *
     * @property detail The JSON parser's own text, as it came.
     */
    data class NotJson(val detail: String) : ImportProblem

    /**
     * A value has the wrong shape somewhere in an otherwise readable document.
     *
     * @property detail The JSON parser's own text, as it came.
     */
    data class Malformed(val detail: String) : ImportProblem

    /**
     * A required top-level field is absent or blank.
     *
     * @property field The field's name, e.g. `schemaVersion`.
     */
    data class MissingField(val field: String) : ImportProblem

    /** What is wrong with the graph a pipeline document describes: its id, its nodes, its connections. */
    sealed interface GraphProblem : ImportProblem

    /**
     * The pipeline's id is not one line within the length limit.
     *
     * @property maxLength The longest id accepted.
     */
    data class InvalidPipelineId(val maxLength: Int) : GraphProblem

    /**
     * Two nodes share an id.
     *
     * @property nodeId The repeated id.
     */
    data class DuplicateNodeId(val nodeId: String) : GraphProblem

    /**
     * Two connections share an id.
     *
     * @property connectionId The repeated id.
     */
    data class DuplicateConnectionId(val connectionId: String) : GraphProblem

    /**
     * A node has no id.
     *
     * @property index Its position in the `nodes` list, from 0.
     */
    data class NodeWithoutId(val index: Int) : GraphProblem

    /**
     * A node has no type.
     *
     * @property nodeId The node.
     */
    data class NodeWithoutType(val nodeId: String) : GraphProblem

    /**
     * A node names a type this app does not know.
     *
     * @property nodeId The node.
     * @property type The type it names.
     */
    data class UnknownNodeType(val nodeId: String, val type: String) : GraphProblem

    /**
     * A connection has no id.
     *
     * @property index Its position in the `connections` list, from 0.
     */
    data class ConnectionWithoutId(val index: Int) : GraphProblem

    /**
     * A connection lacks one of its ends.
     *
     * @property connectionId The connection.
     * @property field The missing end, `fromNodeId` or `toNodeId`.
     */
    data class ConnectionWithoutEnd(val connectionId: String, val field: String) : GraphProblem

    /**
     * A connection names a node the pipeline does not contain.
     *
     * @property connectionId The connection.
     * @property nodeId The node it names.
     */
    data class ConnectionToUnknownNode(val connectionId: String, val nodeId: String) : GraphProblem

    /**
     * A connection leaving a branching node carries a label that is none of the node's branches.
     *
     * @property connectionId The connection.
     * @property nodeType The branching node's type code, e.g. `IF_CONDITION`.
     * @property nodeId The branching node.
     * @property label The label it carries.
     * @property branches The labels the node's branches have.
     */
    data class LabelNotABranch(
        val connectionId: String,
        val nodeType: String,
        val nodeId: String,
        val label: String,
        val branches: List<String>,
    ) : GraphProblem

    /**
     * A node's context switch is not `true` or `false`.
     *
     * @property nodeId The node.
     * @property flag The switch, e.g. `includeHistory`.
     */
    data class ContextFlagNotBoolean(val nodeId: String, val flag: String) : GraphProblem

    /**
     * A node names a cloud provider this app does not know.
     *
     * @property nodeId The node.
     * @property provider The provider it names.
     */
    data class UnknownCloudProvider(val nodeId: String, val provider: String) : GraphProblem

    /** A bundle holds no pipelines. */
    data object EmptyBundle : ImportProblem

    /**
     * A bundle holds more pipelines than one import takes.
     *
     * @property count How many it holds.
     * @property limit How many one import takes.
     */
    data class BundleTooLarge(val count: Int, val limit: Int) : ImportProblem

    /**
     * A bundle entry is not a JSON object.
     *
     * @property index Its position in the bundle, from 0.
     */
    data class BundleEntryNotObject(val index: Int) : ImportProblem

    /**
     * A bundle entry is not a pipeline this app can read.
     *
     * @property index Its position in the bundle, from 0.
     * @property problem Why.
     */
    data class BundleEntryInvalid(val index: Int, val problem: ImportProblem) : ImportProblem

    /**
     * Pipelines in a bundle share ids.
     *
     * @property ids The repeated ids, quoted and listed, at most a few of them.
     */
    data class DuplicatePipelineIds(val ids: String) : ImportProblem

    /**
     * Pipelines in a bundle call pipelines the bundle does not contain.
     *
     * @property ids The missing ids, quoted and listed, at most a few of them.
     */
    data class MissingPipelines(val ids: String) : ImportProblem

    /**
     * A pipeline in a bundle reads, but would not run: the checks a save applies refuse it.
     *
     * @property graph The pipeline, for the name and the nodes the findings name.
     * @property errors What the checks found.
     */
    data class PipelineFailsChecks(val graph: PipelineGraph, val errors: List<PipelineValidationError>) :
        ImportProblem

    /**
     * A memory export holds more memories than the memory keeps.
     *
     * @property count How many it holds.
     * @property limit How many the memory keeps.
     */
    data class TooManyMemories(val count: Int, val limit: Int) : ImportProblem

    /**
     * A memory entry is not a JSON object.
     *
     * @property index Its position in the file, from 0.
     */
    data class MemoryEntryNotObject(val index: Int) : ImportProblem

    /**
     * A memory entry's text, embedding or time is missing or malformed.
     *
     * @property index Its position in the file, from 0.
     */
    data class MemoryEntryInvalid(val index: Int) : ImportProblem

    /**
     * An earlier version of a memory entry is malformed.
     *
     * @property index The entry's position in the file, from 0.
     */
    data class MemoryHistoryInvalid(val index: Int) : ImportProblem
}
