package app.knotwork.android.domain.models

/**
 * Mutable single-image delivery state shared by every run in one execution tree.
 *
 * A run carries at most one image attachment ([image]). The contract is that it
 * reaches the **first vision sink in execution order across the whole tree** — a
 * `LITE_RT` node whose context includes the original task — and exactly that one
 * node. Because sub-pipelines run on fresh engine invocations, the *same*
 * [RunImageDelivery] instance is threaded into a `PIPELINE` node's child run (via
 * [RunTreeContext.imageDelivery]), just like [RunBudgetLedger]: a vision sink
 * nested inside a sub-pipeline can therefore consume the image, and once it does
 * [consumed] flips so no later node — at any depth — receives it again.
 *
 * The holder is a tiny mutable object passed by reference: the engine's `invoke`
 * flow is the only writer and the recursion is strictly synchronous within a
 * single coroutine (the parent suspends on the child's flow), so no
 * synchronisation is needed. It is per-execution and not persisted — a resumed
 * run never re-delivers (it replays a trace), so the holder is simply absent.
 *
 * @property image The resolved attachment to deliver (absolute path + dimensions
 *   + byte size).
 * @property consumed `true` once a vision sink has received [image]; the engine
 *   only delivers while this is `false`.
 */
class RunImageDelivery(val image: EngineImageInput) {
    var consumed: Boolean = false
}
