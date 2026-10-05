package app.knotwork.android.domain.verification

import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.RunHeader
import app.knotwork.android.domain.models.RunTraceRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

/**
 * Renders a recorded run tree to the trace export: a JSON document that holds the
 * run header, every trace record of every run in the tree with its hashes, and the
 * run digest.
 *
 * The document is what a person needs to repeat or audit the run elsewhere: each
 * on-device call carries the full prompt the model read, its output, the seed and
 * sampler, the model file's name and SHA-256, the backend and the context window.
 * So the file holds everything the model read — memory excerpts and chat history
 * included, inside the prompts — and leaves the device only through the user's own
 * share or save.
 *
 * Pure formatting: the caller reads the tree, names the pipelines and computes the
 * digest ([RunDigest]); nothing here touches storage or the network. The shape
 * follows the journal exports — `schemaVersion`, `generatedAt`, `localOnly` — and,
 * being an exported format, its field names do not change within a schema version.
 * Times are wall-clock milliseconds as recorded; the model file is named by its
 * file name, not its path on this device.
 */
class BuildRunTraceExportUseCase @Inject constructor() {

    /**
     * Renders [tree] to the pretty-printed JSON export document.
     *
     * @param tree The recorded run tree.
     * @param pipelineNames Display names of the tree's pipelines by id; a pipeline
     *   deleted since the run is absent and exported without a name.
     * @param digest The run digest, or `null` for a run without one.
     * @param generatedAtLabel Device-local "generated at" label, pre-formatted by
     *   the caller (the domain owns no date formatting).
     * @return The JSON document as a string.
     */
    operator fun invoke(
        tree: RecordedRunTree,
        pipelineNames: Map<String, String>,
        digest: String?,
        generatedAtLabel: String,
    ): String {
        val document = buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("generatedAt", generatedAtLabel)
            put("localOnly", true)
            put("rootRunId", tree.root.id)
            val header = tree.root.header
            if (header != null) putJsonObject("header") { putHeader(header) } else put("header", JsonNull)
            put("digest", digest)
            put("digestScheme", RunDigest.SCHEME)
            put("totalRecords", tree.records.size)
            putJsonArray("runs") {
                for (run in tree.inTreeOrder()) {
                    addJsonObject { putRun(run, pipelineNames, tree.traceOf(run.id)) }
                }
            }
        }
        return PRETTY_JSON.encodeToString(JsonObject.serializer(), document)
    }

    /** Writes the run header's fields. */
    private fun JsonObjectBuilder.putHeader(header: RunHeader) {
        put("seed", header.seed)
        put("temperature", header.sampler.temperature)
        put("topK", header.sampler.topK)
        put("topP", header.sampler.topP)
        put("appVersion", header.appVersion)
        put("runtimeVersion", header.runtimeVersion)
        put("device", header.device)
    }

    /** Writes one run of the tree and its records in `seq` order. */
    private fun JsonObjectBuilder.putRun(
        run: PipelineRun,
        pipelineNames: Map<String, String>,
        trace: List<RunTraceRecord>,
    ) {
        put("runId", run.id)
        put("parentRunId", run.parentRunId)
        put("pipelineId", run.pipelineId)
        put("pipelineName", run.pipelineId?.let { pipelineNames[it] })
        put("origin", run.origin.name)
        put("status", run.status.name)
        put("startedAt", run.startedAt)
        put("finishedAt", run.finishedAt)
        put("userPrompt", run.userPrompt)
        put("hadImage", run.hadImage)
        put("errorMessage", run.errorMessage)
        put("terminationReason", run.terminationReason?.name)
        put(
            "records",
            buildJsonArray {
                for (record in trace) addJsonObject { putRecord(record) }
            },
        )
    }

    /** Writes one trace record, tagged with its kind. */
    private fun JsonObjectBuilder.putRecord(record: RunTraceRecord) {
        put("seq", record.seq)
        put("timestamp", record.timestamp)
        when (record) {
            is RunTraceRecord.NodeIo -> putNodeIo(record)
            is RunTraceRecord.LocalModelCall -> putLocalCall(record)
            is RunTraceRecord.CloudModelCall -> putCloudCall(record)
            is RunTraceRecord.ConsoleEntry -> {
                put("kind", KIND_CONSOLE)
                put("depth", record.depth)
                put("type", exportName(record.type))
                put("message", record.message)
            }
            is RunTraceRecord.MemorySnapshot -> {
                put("kind", KIND_MEMORY)
                putJsonArray("entries") {
                    for (chunk in record.entries) {
                        addJsonObject {
                            put("id", chunk.id)
                            put("text", chunk.text)
                        }
                    }
                }
            }
        }
    }

    /** Writes a node's input and output with their hashes. */
    private fun JsonObjectBuilder.putNodeIo(record: RunTraceRecord.NodeIo) {
        put("kind", KIND_NODE)
        put("depth", record.depth)
        put("nodeId", record.nodeId)
        put("nodeType", record.nodeType)
        put("visit", record.visit)
        put("durationMs", record.durationMs)
        put("tokenCount", record.tokenCount)
        put("conditionResult", record.conditionResult)
        put("routingKey", record.routingKey)
        put("resolvedToolName", record.resolvedToolName)
        put("input", record.inputText)
        put("inputSha256", record.inputSha256)
        put("output", record.outputText)
        put("outputSha256", record.outputSha256)
    }

    /** Writes an on-device call: everything needed to repeat it, and what it gave. */
    private fun JsonObjectBuilder.putLocalCall(record: RunTraceRecord.LocalModelCall) {
        put("kind", KIND_LOCAL_CALL)
        put("depth", record.depth)
        put("nodeId", record.nodeId)
        put("nodeType", record.nodeType)
        put("visit", record.visit)
        put("call", record.call)
        put("seed", record.sampling.seed)
        put("temperature", record.sampling.sampler.temperature)
        put("topK", record.sampling.sampler.topK)
        put("topP", record.sampling.sampler.topP)
        put("modelFile", record.modelPath?.substringAfterLast('/'))
        put("modelSha256", record.modelSha256)
        put("backend", record.backend?.name)
        put("contextWindow", record.contextWindow)
        put("hadImage", record.hadImage)
        put("durationMs", record.durationMs)
        put("prompt", record.prompt)
        put("promptSha256", record.promptSha256)
        put("output", record.output)
        put("outputSha256", record.outputSha256)
    }

    /** Writes a cloud call's note: which provider and model, no text. */
    private fun JsonObjectBuilder.putCloudCall(record: RunTraceRecord.CloudModelCall) {
        put("kind", KIND_CLOUD_CALL)
        put("depth", record.depth)
        put("nodeId", record.nodeId)
        put("nodeType", record.nodeType)
        put("visit", record.visit)
        put("call", record.call)
        put("provider", record.provider)
        put("model", record.model)
    }

    /**
     * The stable export name of a console line's type. Explicit, as in storage: the
     * sealed hierarchy has no name of its own, and a renamed variant must not change
     * an exported format silently.
     */
    private fun exportName(type: ConsoleEventType): String = when (type) {
        ConsoleEventType.NodeExecution -> "NODE_EXECUTION"
        ConsoleEventType.ToolCall -> "TOOL_CALL"
        ConsoleEventType.MemoryAccess -> "MEMORY_ACCESS"
        ConsoleEventType.SystemMessage -> "SYSTEM_MESSAGE"
        ConsoleEventType.Error -> "ERROR"
        ConsoleEventType.StructuredOutputRepair -> "STRUCTURED_OUTPUT_REPAIR"
        ConsoleEventType.CloudRetry -> "CLOUD_RETRY"
        ConsoleEventType.HistoryCompression -> "HISTORY_COMPRESSION"
        ConsoleEventType.RunCeiling -> "RUN_CEILING"
        ConsoleEventType.StuckDetector -> "STUCK_DETECTOR"
    }

    private companion object {
        /**
         * Export-format version, bumped on any shape change a reader must be able
         * to detect — an additive one included.
         *
         * - `1` — the original shape.
         */
        const val SCHEMA_VERSION = 1

        const val KIND_NODE = "node"
        const val KIND_LOCAL_CALL = "localModelCall"
        const val KIND_CLOUD_CALL = "cloudModelCall"
        const val KIND_CONSOLE = "console"
        const val KIND_MEMORY = "memory"

        /** Pretty-printing config, rendering `null`s explicitly. */
        val PRETTY_JSON = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = true
        }
    }
}
