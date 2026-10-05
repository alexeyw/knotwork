package app.knotwork.android.domain.models

import app.knotwork.android.domain.engine.NodeInference

/**
 * What the engine tells one node executor beyond the node and its input: the run
 * tree the node belongs to, and the facts that are particular to this one visit.
 *
 * Threaded from [app.knotwork.android.domain.engine.GraphExecutionEngine] into
 * every [app.knotwork.android.domain.engine.executors.NodeExecutor.execute] call.
 * The engine builds a fresh scope for each node it dispatches: [run] is the same
 * value for every node of the tree, the other fields are recomputed per visit.
 *
 * @property run The state the whole run tree shares — nesting depth, the spend
 *   ledger, the repetition detector, pending advice, the image, the generating
 *   model, the origin. `PipelineNodeExecutor` hands its [RunTreeContext.nested]
 *   form to the sub-pipeline it starts. The default is
 *   [RunTreeContext.standalone], for a node executed outside any engine run; the
 *   engine always passes the run's own tree.
 * @property visitIndex Zero-based index of *this* visit to the node within the
 *   current run invocation. Incremented by the engine each time it enters the
 *   node (including replayed visits during resume), so the value is re-derived
 *   deterministically on resume and the in-flight visit lands on the same index
 *   as on the original run. `PipelineNodeExecutor` uses it to find the exact
 *   child run to resume; every on-device model call derives its seed from it.
 * @property routingChoices Labels of the node's outgoing connections, supplied
 *   by the engine only for routing nodes ([NodeType.INTENT_ROUTER]). The node's
 *   executor passes them to the structured-output gate as the constrained set of
 *   accepted routing keys, so the gate can validate (and repair towards) a key
 *   that actually matches an outgoing edge. Empty for every other node — and for
 *   a routing node with no labelled edges, in which case the executor skips the
 *   gate and the engine falls back to the first outgoing edge.
 * @property imagePath Absolute filesystem path of the run's image attachment,
 *   set by the engine **only** on the first vision-eligible `LITE_RT` node (a
 *   `LITE_RT` node whose context includes the original task) and `null` on every
 *   other node. This is how the per-phase contract "the attachment belongs to
 *   `userPrompt`; only text travels the graph" is realised: a single node sees
 *   the image, the rest of the graph (and every `CLOUD` node) never does.
 *   [LiteRtNodeExecutor][app.knotwork.android.domain.engine.executors.LiteRtNodeExecutor]
 *   forwards it to the inference engine; all other executors ignore it.
 * @property inputWrittenByModel Whether a model wrote the text the run carried into
 *   this node (see [ModelAuthorship][app.knotwork.android.domain.engine.ModelAuthorship]).
 *   `false` for the run's prompt and for a tool's result, however many pass-through
 *   nodes it crossed. Read by the OUTPUT node, whose echo mode saves that text as
 *   the assistant's message and marks the row relayed when no model wrote it.
 * @property inference The node's only way to a model during this visit: inside a
 *   run the engine passes a recording one, which seeds every on-device call from
 *   the run seed and keeps it for the trace. The default records nothing, for a
 *   node executed outside any engine run.
 */
data class ExecutionScope(
    val run: RunTreeContext = RunTreeContext.standalone(),
    val visitIndex: Int = 0,
    val routingChoices: List<String> = emptyList(),
    val imagePath: String? = null,
    val inputWrittenByModel: Boolean = false,
    val inference: NodeInference = NodeInference.Unrecorded,
)
