package app.knotwork.android.domain.models

import app.knotwork.android.domain.engine.stuck.GraphStuckDetector

/**
 * Run-tree-scoped execution context threaded from
 * [app.knotwork.android.domain.engine.GraphExecutionEngine] into every
 * [app.knotwork.android.domain.engine.executors.NodeExecutor.execute] call.
 *
 * Replaces the bare `depth: Int` parameter the executor interface used to
 * carry: bundling the nesting depth, the shared step budget and the
 * per-node visit index in one value object keeps the executor signature stable
 * as composition features grow (the only consumer today is
 * `PipelineNodeExecutor`, which re-enters the engine for a sub-pipeline).
 *
 * The engine builds a fresh [ExecutionScope] for each node it dispatches: the
 * run-tree-wide fields ([depth], [budget]) are constant across a run while
 * [pipelineVisitIndex] is recomputed per node so a `PIPELINE` node visited more
 * than once (e.g. inside a `QUEUE_PROCESSOR` loop) can mint a distinct,
 * resume-stable child run id per visit.
 *
 * @property depth Pipeline-nesting depth of the current run: `0` for a
 *   top-level run, `parentDepth + 1` for a sub-pipeline. Used to enforce the
 *   runtime nesting ceiling and to stamp console/trace records with their
 *   nesting level for the indented console rendering.
 * @property budget The spend ledger shared across the whole run tree — the
 *   step and token counters every ceiling is charged against, seeded from the
 *   root run record so a resumed run continues its own count rather than
 *   restarting it. `null` when the engine was invoked without one
 *   (non-persisted editor test runs). See [RunBudgetLedger].
 * @property stuckDetector The repetition detector shared across the whole run
 *   tree, on the same terms as [budget]: a sub-pipeline observes into the same
 *   window as its parent, so a parent calling one child repeatedly with the
 *   same input reads as the single loop it is. `null` when the engine was
 *   invoked without one. See [GraphStuckDetector].
 * @property contextNotes Advice raised for the run but not yet delivered to a
 *   model, shared across the whole tree so the scope a note can reach matches
 *   the scope the detector's grace period counts over. `null` when the engine
 *   was invoked without one. See [RunContextNotes].
 * @property pipelineVisitIndex Zero-based index of *this* `PIPELINE`-node visit
 *   within the current run. Incremented by the engine each time it enters a
 *   `PIPELINE` node (including replayed visits during resume), so the value is
 *   re-derived deterministically on resume and the in-flight visit lands on the
 *   same index as on the original run — letting `PipelineNodeExecutor` find the
 *   exact child run to resume. `0` for every non-`PIPELINE` node.
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
 * @property imageDelivery The run tree's shared single-image delivery state, or
 *   `null` when the run carries no image. Threaded through unchanged so a
 *   `PIPELINE` node can forward it to its sub-pipeline's engine invocation —
 *   letting a vision sink nested inside a sub-pipeline consume the image. Only
 *   [PipelineNodeExecutor][app.knotwork.android.domain.engine.executors.PipelineNodeExecutor]
 *   reads it; the engine sets [imagePath] from it for the actual delivery node.
 * @property imagePresent `true` when the run's originating message carried an image
 *   attachment — the presence-only signal a routing/condition node reads to branch on
 *   "did the user send a picture?". Unlike [imageDelivery] (the actual deliverable image,
 *   absent on resume), this survives a checkpoint resume: the engine derives it from
 *   `imageDelivery != null` on a fresh run and from the persisted `PipelineRun.hadImage`
 *   on a resumed one. Carries no pixels, only the boolean fact.
 * @property generatingModel The run tree's shared holder for the model that
 *   produced the answer, or `null` when the engine was invoked without one.
 *   Threaded through unchanged so a `PIPELINE` node forwards it to its
 *   sub-pipeline; the answering `LITE_RT`/`CLOUD` node writes it and the root
 *   `OUTPUT` node reads it to attribute the persisted message. See
 *   [RunGeneratingModel].
 * @property runOrigin What started the run tree. Constant across the whole tree:
 *   `PipelineNodeExecutor` forwards it verbatim into every sub-pipeline
 *   invocation, so a nested run classifies its retrieval key exactly as its
 *   parent does. Consumed by the engine's long-term-memory retrieval (see
 *   [MemoryRetrievalQueryResolver][app.knotwork.android.domain.engine.MemoryRetrievalQueryResolver]);
 *   node executors other than `PIPELINE` ignore it.
 * @property inputWrittenByModel Whether a model wrote the text the run carried into
 *   this node (see [ModelAuthorship][app.knotwork.android.domain.engine.ModelAuthorship]).
 *   `false` for the run's prompt and for a tool's result, however many pass-through
 *   nodes it crossed. Read by the OUTPUT node, whose echo mode saves that text as
 *   the assistant's message and marks the row relayed when no model wrote it.
 */
data class ExecutionScope(
    val depth: Int = 0,
    val budget: RunBudgetLedger? = null,
    val stuckDetector: GraphStuckDetector? = null,
    val contextNotes: RunContextNotes? = null,
    val pipelineVisitIndex: Int = 0,
    val routingChoices: List<String> = emptyList(),
    val imagePath: String? = null,
    val imageDelivery: RunImageDelivery? = null,
    val imagePresent: Boolean = false,
    val generatingModel: RunGeneratingModel? = null,
    val runOrigin: RunOrigin = RunOrigin.CHAT,
    val inputWrittenByModel: Boolean = false,
)
