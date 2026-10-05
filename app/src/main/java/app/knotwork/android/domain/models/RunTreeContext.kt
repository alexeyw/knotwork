package app.knotwork.android.domain.models

import app.knotwork.android.domain.engine.stuck.GraphStuckDetector

/**
 * What every engine invocation of one run tree shares: the root run and each
 * sub-pipeline a `PIPELINE` node starts under it.
 *
 * A sub-pipeline runs on a fresh engine invocation, but it is not a fresh run. It
 * charges the parent's ceilings, observes into the parent's repetition window,
 * can receive the parent's undelivered advice and image, attributes its answer to
 * the parent's output, and classifies its memory retrieval by the parent's
 * origin. All of that is this one value: the engine builds it once for the root
 * run, every node receives it in [ExecutionScope.run], and `PipelineNodeExecutor`
 * hands [nested] to the child invocation whole. Nothing in it is copied but
 * [depth]; the holders are shared by reference, which is the point.
 *
 * @property depth Pipeline-nesting depth of the invocation holding this value:
 *   `0` for the root run, one more for each `PIPELINE` level. Enforces the
 *   runtime nesting ceiling and stamps console and trace records so the console
 *   can render nested output as a hierarchy.
 * @property budget The spend ledger every ceiling is charged against, seeded
 *   from the root run record so a resumed run continues its own count. A breach
 *   at any depth stops the whole tree. See [RunBudgetLedger].
 * @property stuckDetector The repetition detector: a parent calling one child
 *   repeatedly with the same input is one loop, and reads as one. See
 *   [GraphStuckDetector].
 * @property contextNotes Advice raised for the run but not yet delivered to a
 *   model. Shared so the scope a note can reach matches the scope the detector's
 *   grace period counts over. See [RunContextNotes].
 * @property imageDelivery The run's single image attachment and whether a node
 *   has taken it, or `null` when the run carries no image or is a resumed run (a
 *   replay never re-delivers). The first vision sink anywhere in the tree takes
 *   it. See [RunImageDelivery].
 * @property imagePresent `true` when the run's originating message carried an
 *   image — the presence-only signal a routing or condition node branches on.
 *   Unlike [imageDelivery] it survives a resume: a fresh run derives it from the
 *   attachment, a resumed one from the persisted `PipelineRun.hadImage`.
 * @property generatingModel Which model produced the answer: written by the
 *   answering `LITE_RT` / `CLOUD` node at any depth, read by the root `OUTPUT`
 *   node to attribute the persisted message. See [RunGeneratingModel].
 * @property origin What started the run tree. Keys long-term-memory retrieval
 *   (`MemoryRetrievalQueryResolver`), so a sub-pipeline of a trigger run
 *   classifies its retrieval exactly as its parent does.
 * @property header The root run's header: the seed every on-device call's seed
 *   is derived from and the sampler every such call uses. A sub-pipeline runs
 *   with its root's header, so the whole tree is reproducible from one seed.
 * @property seedPath Where in the tree this invocation sits, for deriving seeds:
 *   empty at the root, and one `PIPELINE` node id and visit index more per level
 *   (see [nested]). Two sub-pipelines started from different nodes — or from the
 *   same node on different visits — derive different seeds for nodes that share
 *   an id.
 */
data class RunTreeContext(
    val depth: Int,
    val budget: RunBudgetLedger,
    val stuckDetector: GraphStuckDetector,
    val contextNotes: RunContextNotes,
    val imageDelivery: RunImageDelivery?,
    val imagePresent: Boolean,
    val generatingModel: RunGeneratingModel,
    val origin: RunOrigin,
    val header: RunHeader,
    val seedPath: String = "",
) {

    /**
     * The value a `PIPELINE` node hands to the sub-pipeline it starts: the same
     * shared holders, one level deeper.
     *
     * @param pipelineNodeId The `PIPELINE` node starting the sub-pipeline.
     * @param visitIndex That node's zero-based visit index in its own invocation.
     * @return A copy with [depth] incremented, [seedPath] extended by the node and
     *   visit, and every holder shared by reference.
     */
    fun nested(pipelineNodeId: String, visitIndex: Int): RunTreeContext =
        copy(depth = depth + 1, seedPath = "$seedPath/$pipelineNodeId#$visitIndex")

    /** Builds the value for a node executed outside any engine run. */
    companion object {

        /**
         * A tree for executing a single node outside an engine run — a unit test
         * or a preview calling a node executor directly. The engine never builds
         * one: it passes the run's own tree to every node.
         *
         * Its ceilings are zero on purpose. A missing scope must not read as an
         * unlimited one, so an engine handed this tree stops before its first
         * step instead of running without a ceiling.
         *
         * Its header is a fixed placeholder: a node executed outside a run records
         * nothing, so no seed it would derive is ever kept.
         *
         * @return A fresh, unshared tree at depth `0` with no image, an empty
         *   generating-model holder and the interactive origin.
         */
        fun standalone(): RunTreeContext = RunTreeContext(
            depth = 0,
            budget = RunBudgetLedger(
                ceilings = RunCeilings(
                    origin = RunOrigin.CHAT,
                    steps = RunCeilingLimit.Enforced(soft = 0, hard = 0),
                    tokens = RunCeilingLimit.Enforced(soft = 0, hard = 0),
                ),
            ),
            stuckDetector = GraphStuckDetector(),
            contextNotes = RunContextNotes(),
            imageDelivery = null,
            imagePresent = false,
            generatingModel = RunGeneratingModel(),
            origin = RunOrigin.CHAT,
            header = STANDALONE_HEADER,
        )

        /** The header of a [standalone] tree. */
        private val STANDALONE_HEADER = RunHeader(
            seed = 0,
            sampler = RunSampler(temperature = 0.0, topK = 1, topP = 1.0),
            appVersion = "",
            runtimeVersion = "",
            device = "",
        )
    }
}
