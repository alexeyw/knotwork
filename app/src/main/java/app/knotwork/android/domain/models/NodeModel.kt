package app.knotwork.android.domain.models

import app.knotwork.android.domain.constants.DefaultPrompts

/**
 * Represents a single node on the visual orchestrator canvas.
 *
 * @property id The unique identifier of the node.
 * @property type The [NodeType] describing the capability of this node.
 * @property x X of the node card's top-left corner on the canvas, in **dp** — the unit the
 * editor lays the card out in, so a position means the same spacing on every screen and in
 * every file format that carries it (exports, bundles, bundled presets, the browser editor).
 * @property y Y of the node card's top-left corner on the canvas, in **dp** (see [x]).
 * @property label An optional label or name for the node.
 * @property toolName An optional name of the assigned tool if the node type is [NodeType.TOOL].
 * @property targetPipelineId Id of the pipeline this node executes when its type is
 * [NodeType.PIPELINE]; `null` for every other node type. The referenced graph is loaded and
 * run as a sub-pipeline by `PipelineNodeExecutor`. Persisted as a flat column (mirroring the
 * `toolName` pattern); cross-pipeline cycle / depth / dangling-reference checks live in
 * `PipelineCompositionValidator`.
 * @property skillId Id of the [app.knotwork.android.domain.models.Skill] this node runs when its
 * type is [NodeType.SKILL]; `null` for every other node type. The referenced skill supplies the
 * instruction, the visible-tool allowlist, and the default context selection consumed by
 * `SkillNodeExecutor`. Persisted as a flat column (mirroring `targetPipelineId`); a dangling
 * reference is surfaced as [PipelineValidationError.SkillNotFound] by `PipelineGraph.validate`.
 * @property modelPath An optional path to a specific model file (.tflite) for this node.
 * @property conditionComplexity Threshold for task complexity if type is [NodeType.IF_CONDITION].
 * @property conditionKeywords Comma-separated keywords for condition if type is [NodeType.IF_CONDITION].
 * @property conditionPrompt Free-form prompt for condition classification if type is [NodeType.IF_CONDITION].
 * @property conditionHasImage When `true` on an [NodeType.IF_CONDITION] node, the node takes the
 * "True" branch whenever the run's user input carries an image attachment — a deterministic,
 * no-LLM check evaluated before keywords / complexity / prompt. `null`/`false` leaves image
 * presence out of the decision. Lets a pipeline fork on "did the user send a picture?".
 * Nullable to mirror the `pipeline_nodes.conditionHasImage` column (added as a nullable
 * `INTEGER` in schema v48); `null` and `false` are treated identically.
 * @property systemPrompt An optional system prompt to configure the behavior of the node.
 * @property cloudProvider An optional provider id for a CLOUD node. Either [CloudProvider.AUTO_KEY]
 * (executor picks at runtime) or one of the [CloudProvider.id]s. Persisted as a raw string for
 * backward compatibility with pipelines created before the typed enum existed; parse with
 * [CloudProvider.fromId] on the way in.
 * @property clarificationTimeoutMs Timeout (in ms) the [NodeType.CLARIFICATION] node waits for the
 * user's reply before falling back to a default answer. `null` means use the engine's default
 * (60 000 ms). Ignored for non-CLARIFICATION nodes.
 * @property contextConfig Per-node selection of pipeline context blocks
 * (chat history, original task, previous node output, long-term memory, tool
 * results) that the orchestrator concatenates into the node's input on every
 * execution. Defaults to [NodeContextConfig.ALL_ENABLED] so older pipelines
 * keep their default behaviour.
 * @property fallbackClass INTENT_ROUTER only: the class an answer matching no
 * declared class routes to. `null` keeps the historical behaviour — the first
 * outgoing edge in storage order — because changing that for every already-saved
 * graph would be a silent re-route.
 * @property quickReplies CLARIFICATION only: comma-separated answers offered as
 * chips under the question, replacing the ones the model would have produced.
 * `null` or blank leaves the model's own options in place.
 * @property alwaysConfirm TOOL and SKILL — the node types that dispatch tools:
 * `true` asks for approval on every call through this node, whatever the tool's
 * risk. `null` / `false` inherits the tool's risk and the user's approval policy.
 * Other node types make no tool call, so the field has nothing to act on there.
 *
 * Deliberately one-directional. The per-node control can only make the gate
 * **stricter**, never weaker: a pipeline file is a document that can be shared,
 * and a node able to declare "do not ask about this destructive call" would let
 * a document written by someone else walk past the gate.
 * @property maxSubtasks DECOMPOSITION only: how many sub-tasks are kept from the
 * generated list. `null` (or a value below 1) means [DEFAULT_MAX_SUBTASKS] — the
 * number the node sheet shows for a node without a value, so what the author sees
 * is what runs.
 * @property stopOnError QUEUE_PROCESSOR only: `true` (and `null`, the historical
 * behaviour) fails the whole run on the first failing item; `false` records the
 * failure as that item's result and carries on with the next one.
 * @property configJson Optional JSON payload encoding the per-type
 * [app.knotwork.design.components.pipelineeditor.NodeConfig] populated from
 * the `NodeConfigSheet`. `null` for older pipelines without per-node config;
 * the editor falls back to deriving a
 * default config from the flat fields above on first edit. Serialised /
 * deserialised by `presentation/ui/pipeline/editor/config/NodeConfigCodec`.
 */
data class NodeModel(
    val id: String,
    val type: NodeType,
    val x: Float,
    val y: Float,
    val label: String = type.name,
    val toolName: String? = null,
    val targetPipelineId: String? = null,
    val skillId: String? = null,
    val modelPath: String? = null,
    val conditionComplexity: Int? = null,
    val conditionKeywords: String? = null,
    val conditionPrompt: String? = null,
    val conditionHasImage: Boolean? = null,
    val systemPrompt: String? = DefaultPrompts.getDefaultPromptForNodeType(type),
    val cloudProvider: String? = null,
    val clarificationTimeoutMs: Long? = null,
    val fallbackClass: String? = null,
    val quickReplies: String? = null,
    val alwaysConfirm: Boolean? = null,
    val maxSubtasks: Int? = null,
    val stopOnError: Boolean? = null,
    val contextConfig: NodeContextConfig = NodeContextConfig.ALL_ENABLED,
    val configJson: String? = null,
) {
    /** Defaults shared by the engine and the node sheet. */
    companion object {
        /**
         * How many sub-tasks a DECOMPOSITION node keeps when [maxSubtasks] is not
         * set: the value the node sheet shows and saves for such a node.
         */
        const val DEFAULT_MAX_SUBTASKS: Int = 5
    }
}
