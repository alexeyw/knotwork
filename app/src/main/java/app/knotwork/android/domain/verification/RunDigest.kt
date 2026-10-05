package app.knotwork.android.domain.verification

import app.knotwork.android.domain.engine.TraceHashing
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.RunTraceRecord

/**
 * The run digest: one SHA-256 that stands for what a finished run produced.
 *
 * A chain over the run's content records in `seq` order — each node's input and
 * output, each on-device call's prompt and output, each cloud call's provider and
 * model. A sub-pipeline's own digest is folded into the record of the `PIPELINE`
 * visit that started it (a visit that failed and left no record is folded in after
 * the recorded ones). Times, durations, run ids, seeds, the sampler, the model
 * file and the console lines are left out: a repeat that produces the same content
 * has the same digest, whenever and with whatever it ran.
 *
 * Every link hashes its fields length-prefixed, so no choice of ids can make two
 * different chains encode to the same bytes. The scheme is versioned ([SCHEME]),
 * so a reader of an export recomputes the digest from the export alone.
 *
 * The digest shows that two records hold the same content; it does not prove who
 * made either — nothing is signed.
 */
object RunDigest {

    /** Version tag the chain starts from: changing the scheme changes every digest. */
    const val SCHEME: String = "knotwork-digest-v1"

    /**
     * The digest of a finished run.
     *
     * @param tree The recorded run tree.
     * @return Lowercase hex SHA-256, or `null` while the root run is still going
     *   and for a run recorded before runs kept a header (its records carry no
     *   visits or hashes to chain).
     */
    fun of(tree: RecordedRunTree): String? =
        if (tree.root.status.isTerminal && tree.root.header != null) digestOf(tree, tree.root.id) else null

    /** The chain over the content records of [runId], its sub-pipelines folded in. */
    private fun digestOf(tree: RecordedRunTree, runId: String): String {
        var state = link(listOf(SCHEME))
        for (record in tree.traceOf(runId)) {
            val fields = fieldsOf(tree, runId, record) ?: continue
            state = link(listOf(state) + fields)
        }
        for ((nodeId, visit) in tree.unrecordedPipelineVisits(runId)) {
            val child = tree.childAt(runId, nodeId, visit) ?: continue
            state = link(listOf(state, PIPELINE, nodeId, visit.toString(), digestOf(tree, child.id)))
        }
        return state
    }

    /** What one record adds to the chain, or `null` for a record that is not content. */
    private fun fieldsOf(tree: RecordedRunTree, runId: String, record: RunTraceRecord): List<String>? = when (record) {
        is RunTraceRecord.NodeIo -> listOf(
            NODE,
            record.nodeId,
            record.nodeType,
            record.visit?.toString().orEmpty(),
            record.inputSha256 ?: TraceHashing.sha256Hex(record.inputText),
            record.outputSha256 ?: TraceHashing.sha256Hex(record.outputText),
            childDigest(tree, runId, record).orEmpty(),
        )
        is RunTraceRecord.LocalModelCall -> listOf(
            LOCAL_CALL,
            record.nodeId,
            record.nodeType,
            record.visit.toString(),
            record.call.toString(),
            record.promptSha256,
            record.outputSha256,
        )
        is RunTraceRecord.CloudModelCall -> listOf(
            CLOUD_CALL,
            record.nodeId,
            record.nodeType,
            record.visit.toString(),
            record.call.toString(),
            record.provider,
            record.model.orEmpty(),
        )
        is RunTraceRecord.ConsoleEntry, is RunTraceRecord.MemorySnapshot -> null
    }

    /** The digest of the sub-pipeline a `PIPELINE` record started, if it has one. */
    private fun childDigest(tree: RecordedRunTree, runId: String, record: RunTraceRecord.NodeIo): String? {
        if (record.nodeType != NodeType.PIPELINE.name) return null
        val child = record.visit?.let { tree.childAt(runId, record.nodeId, it) }
        return child?.let { digestOf(tree, it.id) }
    }

    /** One link of the chain: SHA-256 of [fields], each prefixed with its length. */
    private fun link(fields: List<String>): String = TraceHashing.sha256Hex(
        buildString {
            for (field in fields) append(field.length).append(':').append(field)
        },
    )

    private const val NODE = "node"
    private const val LOCAL_CALL = "local"
    private const val CLOUD_CALL = "cloud"
    private const val PIPELINE = "pipeline"
}
