package app.knotwork.android.domain.models

/**
 * One record of the persistent pipeline-run trace.
 *
 * A run's trace is the ordered union of two record kinds: per-node
 * input/output snapshots ([NodeIo]) and console log events ([ConsoleEntry]).
 * The engine appends records through
 * [app.knotwork.android.domain.repositories.RunTraceRepository] as the run
 * executes; the console replays them when a session is reopened after the run
 * finished (or while it is still executing in the background).
 *
 * @property runId The pipeline run the record belongs to.
 * @property sessionId The chat session that owns the run.
 * @property seq Zero-based monotonic position of the record within the run.
 *   Unique per run across both record kinds — the console replay/live seam
 *   deduplicates by this number.
 * @property timestamp Wall-clock time of the record in
 *   `System.currentTimeMillis()` units.
 */
sealed class RunTraceRecord {
    abstract val runId: String
    abstract val sessionId: String
    abstract val seq: Long
    abstract val timestamp: Long

    /**
     * Input/output snapshot of one executed pipeline node. Backs the Vars and
     * Traces console tabs for replayed runs and the checkpoint/resume
     * mechanism (a completed node's recorded output substitutes for
     * re-execution).
     *
     * @property nodeId The graph node id (unique within the pipeline graph).
     * @property nodeType The [app.knotwork.android.domain.models.NodeType]
     *   name of the executed node.
     * @property inputText The text the node received as input.
     * @property outputText The text the node produced.
     * @property durationMs How long the node took to execute, in milliseconds.
     * @property tokenCount The approximate number of LLM tokens produced, or
     *   `null` for non-LLM nodes.
     * @property conditionResult The recorded boolean verdict of an
     *   `IF_CONDITION` node, or `null` for every other node type. Persisted so
     *   checkpoint resume re-routes the True/False branch exactly as the
     *   interrupted run did, without re-evaluating the condition.
     * @property routingKey The recorded routing key of an `INTENT_ROUTER` or
     *   `EVALUATION` node (edge-label selector), or `null` for every other
     *   node type. Persisted for the same branch-restoration reason as
     *   [conditionResult].
     * @property resolvedToolName The tool a `TOOL` node actually executed
     *   (relevant for "auto"-configured nodes), or `null` for non-TOOL nodes.
     *   Persisted so a replayed tool observation is attributed to the real
     *   tool in the `--- Tool Results ---` context block.
     * @property depth Pipeline-nesting level of the run this node belongs to:
     *   `0` for the top-level run, `1` for a direct sub-pipeline, and so on.
     *   Projected back into the Traces/Vars console tabs so a sub-pipeline's
     *   nodes render nested under the `PIPELINE` node that spawned them.
     * @property visit Zero-based index of this visit to [nodeId] within its run
     *   invocation — a node inside a queue loop runs once per item. Ties the
     *   record to the model calls made during the same visit. `null` for a
     *   record written before visits were recorded.
     * @property inputSha256 Lowercase hex SHA-256 of [inputText] (UTF-8), or
     *   `null` for a record written before hashes were recorded.
     * @property outputSha256 Lowercase hex SHA-256 of [outputText] (UTF-8), or
     *   `null` for a record written before hashes were recorded.
     */
    data class NodeIo(
        override val runId: String,
        override val sessionId: String,
        override val seq: Long,
        override val timestamp: Long,
        val nodeId: String,
        val nodeType: String,
        val inputText: String,
        val outputText: String,
        val durationMs: Long,
        val tokenCount: Int?,
        val conditionResult: Boolean? = null,
        val routingKey: String? = null,
        val resolvedToolName: String? = null,
        val depth: Int = 0,
        val visit: Int? = null,
        val inputSha256: String? = null,
        val outputSha256: String? = null,
    ) : RunTraceRecord()

    /**
     * One persisted console log event, mirroring [ConsoleEvent]. Backs the
     * Logs console tab for replayed runs.
     *
     * @property type Category of the event (drives line color and filtering).
     * @property message Pre-formatted human-readable console line.
     * @property depth Pipeline-nesting level of the run that produced the event
     *   (`0` top-level, `1` direct sub-pipeline, …). Drives the indented
     *   replay rendering in the Logs tab.
     */
    data class ConsoleEntry(
        override val runId: String,
        override val sessionId: String,
        override val seq: Long,
        override val timestamp: Long,
        val type: ConsoleEventType,
        val message: String,
        val depth: Int = 0,
    ) : RunTraceRecord()

    /**
     * Snapshot of the long-term memory chunks resolved for this run, written
     * at the moment the run's single lazy memory retrieval actually happens.
     * Checkpoint resume seeds the engine's memoized memory list from this
     * record instead of re-running retrieval, so a resumed run sees exactly
     * the `--- Long-Term Memory ---` block the interrupted run saw (and the
     * per-chunk usage counters are not double-counted).
     *
     * Only the chunk identity and text survive persistence — embeddings are
     * neither needed to rebuild the context block nor worth the storage; a
     * chunk restored from a snapshot carries an empty embedding vector.
     *
     * @property entries The resolved memory chunks in retrieval-rank order.
     */
    data class MemorySnapshot(
        override val runId: String,
        override val sessionId: String,
        override val seq: Long,
        override val timestamp: Long,
        val entries: List<MemoryChunk>,
    ) : RunTraceRecord()

    /**
     * One call a node made to the on-device model: exactly what went in and came
     * out, and everything that decided the output.
     *
     * The record is what makes a run checkable. Re-running [prompt] with
     * [sampling] on the model file [modelSha256], on the same [backend] (and, on
     * GPU, the same [contextWindow]), on the same device, produces [output] again
     * byte for byte — measured for CPU and GPU, not promised for NPU
     * (`decisions.md §70.6`). The prompt is the whole text the model read —
     * system prompt, context blocks, history and memory included — so no live
     * input has to be reconstructed to repeat the call.
     *
     * A node may call the model several times in one visit (a structured-output
     * repair, for one); each call is its own record, numbered by [call].
     *
     * @property nodeId The graph node that made the call.
     * @property nodeType The [NodeType] name of that node.
     * @property visit Zero-based visit index of the node within its run
     *   invocation; matches [NodeIo.visit].
     * @property call Zero-based index of this call among the visit's model calls.
     * @property depth Pipeline-nesting level of the run, as on [NodeIo.depth].
     * @property sampling The sampler and seed the call ran with.
     * @property modelPath Absolute path of the model file the engine had loaded,
     *   or `null` when the engine reported none.
     * @property modelSha256 SHA-256 of that file from the model registry, or
     *   `null` when the file had not been hashed yet — such a call cannot be
     *   checked later, since nothing proves which bytes ran it.
     * @property backend The backend the engine actually ran on (after any
     *   fallback), or `null` when the engine reported none.
     * @property contextWindow The engine's context window (tokens) at the call,
     *   or `null` when the engine reported none. Part of what a GPU repeat must
     *   match.
     * @property hadImage Whether an image was sent with the prompt. The image
     *   itself is not recorded, so such a call cannot be repeated from the record.
     * @property prompt The full text sent to the model.
     * @property output The full text the model streamed back, unprocessed.
     * @property promptSha256 Lowercase hex SHA-256 of [prompt] (UTF-8).
     * @property outputSha256 Lowercase hex SHA-256 of [output] (UTF-8).
     * @property durationMs How long the call's stream took, or `null` for a call
     *   recorded before durations were kept. A check estimates its own length
     *   from it: repeating a call costs about what the call cost.
     */
    data class LocalModelCall(
        override val runId: String,
        override val sessionId: String,
        override val seq: Long,
        override val timestamp: Long,
        val nodeId: String,
        val nodeType: String,
        val visit: Int,
        val call: Int,
        val depth: Int,
        val sampling: LocalSampling,
        val modelPath: String?,
        val modelSha256: String?,
        val backend: LocalBackend?,
        val contextWindow: Int?,
        val hadImage: Boolean,
        val prompt: String,
        val output: String,
        val promptSha256: String,
        val outputSha256: String,
        val durationMs: Long? = null,
    ) : RunTraceRecord()

    /**
     * One call a node made to a cloud model. Only which provider and model were
     * asked is recorded, not the text: a hosted model takes no seed the app
     * controls, so its answer cannot be repeated and checked — the record exists
     * so a node answered in the cloud is shown as such instead of as a node that
     * made no model call.
     *
     * @property nodeId The graph node that made the call.
     * @property nodeType The [NodeType] name of that node.
     * @property visit Zero-based visit index of the node within its run invocation.
     * @property call Zero-based index of this call among the visit's model calls.
     * @property depth Pipeline-nesting level of the run.
     * @property provider The provider id (e.g. `anthropic`).
     * @property model The model id the provider was asked for, or `null` when the
     *   calling node does not know it.
     */
    data class CloudModelCall(
        override val runId: String,
        override val sessionId: String,
        override val seq: Long,
        override val timestamp: Long,
        val nodeId: String,
        val nodeType: String,
        val visit: Int,
        val call: Int,
        val depth: Int,
        val provider: String,
        val model: String?,
    ) : RunTraceRecord()
}
