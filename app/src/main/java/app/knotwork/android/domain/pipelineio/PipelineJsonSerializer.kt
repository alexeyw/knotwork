package app.knotwork.android.domain.pipelineio

import app.knotwork.android.domain.constants.PipelineConstants
import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineImportOutcome
import app.knotwork.android.domain.text.toDisplaySafe
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Two-way mapper between [PipelineGraph] and the schema-versioned JSON
 * format consumed by the browser-side editor (`pipeline-editor.html`) and
 * by other instances of this application.
 *
 * ### Schema (version 1)
 *
 * ```
 * {
 *   "schemaVersion": 1,
 *   "id": "<uuid>",
 *   "name": "<display name>",
 *   "updatedAt": 1730000000000,
 *   "nodes": [
 *     {
 *       "id": "node-1",
 *       "type": "CLOUD",
 *       "position": { "x": 120.0, "y": 80.0 },
 *       "label": "Cloud",
 *       "config": {
 *         "systemPrompt": "...",
 *         "cloudProvider": "auto",
 *         "modelPath": "",
 *         "toolName": "",
 *         "targetPipelineId": null,
 *         "skillId": null,
 *         "clarificationTimeoutMs": 60000,
 *         "conditionPrompt": "",
 *         "conditionKeywords": "",
 *         "conditionComplexity": null,
 *         "fallbackClass": null,
 *         "quickReplies": null,
 *         "alwaysConfirm": null,
 *         "maxSubtasks": null,
 *         "stopOnError": null,
 *         "conditionHasImage": null
 *       },
 *       "contextConfig": { "chatHistory": true, ... },
 *       "nodeConfig": { "v": 1, "type": "CLOUD", "title": "Cloud", ... }
 *     }
 *   ],
 *   "connections": [
 *     { "id": "conn-1", "fromNodeId": "node-1", "toNodeId": "node-2", "label": "" }
 *   ],
 *   "samplePrompts": [ … ],
 *   "memoryRetrievalQuery": "journal entries around $DATE"
 * }
 * ```
 *
 * `samplePrompts` and `memoryRetrievalQuery` are optional pipeline-level
 * fields. Both decode to their defaults when absent, so documents written
 * before they existed parse unchanged and `schemaVersion` stays `1`.
 * `samplePrompts` is always emitted (as `[]` when there are none);
 * `memoryRetrievalQuery` is emitted only when actually declared.
 *
 * ### Rich per-node config (`nodeConfig`)
 *
 * The optional `nodeConfig` object carries the full
 * `NodeConfig` payload (the `NodeConfigCodec` envelope: `{ "v": 1,
 * "type", "title", ...type-specific... }`) so the browser editor and the
 * in-app editor can round-trip every form field, not just the flat
 * `config` subset the runtime reads. It is treated as an **opaque blob**
 * here — stored into / read from [NodeModel.configJson] verbatim, never
 * interpreted by this domain-layer serializer (only the presentation-layer
 * `NodeConfigCodec` parses it). The field is additive and optional:
 * documents without it (older exports, hand-written presets) parse fine and
 * the app re-derives the rich config from the flat fields on first edit.
 *
 * ### What a file cannot make the app store or say
 *
 * A document is content the user did not write, so [parse] normalises the
 * parts that are displayed and rejects the parts that would be stored
 * differently from how they validate:
 *  - `name` and every node `label` become one line within
 *    [PipelineConstants.MAX_NAME_LENGTH] / [PipelineConstants.MAX_IMPORTED_LABEL_LENGTH];
 *  - two nodes or two connections sharing an id fail the import;
 *  - every value quoted back in an error message goes through
 *    [toDisplaySafe], so a crafted id or type cannot add lines to the error.
 *
 * `nodeConfig` stays opaque here; the editor reads every field that reaches
 * the run from the flat `config` block, never from it (`NodeConfigCodec.decode`).
 *
 * ### Forward-compatibility
 *
 * Unknown fields in `config` (e.g. `intentRouterPrompt` produced by a
 * future editor version) are silently dropped on parse — they are not
 * representable in the current [NodeModel]. The caller is expected to
 * surface a [PipelineImportOutcome.SchemaMismatch] dialog when the
 * `schemaVersion` differs so the user knows configuration may have been
 * stripped.
 *
 * Uses `org.json` per the project's API conventions.
 */
object PipelineJsonSerializer {

    /**
     * Version stamp emitted on every [serialize] call and validated by [parse].
     *
     * Mismatches surface as [PipelineImportOutcome.SchemaMismatch] so the UI can warn
     * the user that a file produced by a different build of the editor may have fields
     * the current schema cannot represent.
     */
    const val CURRENT_SCHEMA_VERSION: Int = 1

    /**
     * Every `schemaVersion` this build knows how to read.
     *
     * Today this is exactly `{1}` — the stamp has never held another value, so
     * there is no older format to migrate from and [migrate] is an identity.
     * The set is spelled out anyway because it is the thing a future version
     * bump has to extend: adding `2` here without also adding its migration
     * step is a compile-visible omission, whereas the previous
     * `version == CURRENT` test would have quietly started rejecting every
     * v1 document the moment the stamp moved.
     *
     * A version **outside** this set is not a failure — the document is still
     * parsed best-effort and reported as
     * [PipelineImportOutcome.SchemaMismatch], because a file from a future
     * build is usually still mostly readable.
     *
     * There is deliberately **no migration function yet**. A no-op `migrate()`
     * would be a placeholder that reads as a working mechanism, and the first
     * real bump has to touch [parse] anyway to decide *where* the step runs.
     * When `2` is added here, its transformation goes with it — and the loop in
     * `PipelineJsonSerializerTest` that asserts every member of this set parses
     * will fail until it does.
     */
    val SUPPORTED_SCHEMA_VERSIONS: Set<Int> = setOf(1)

    /**
     * Keys this build reads at each level of the document. Anything present in
     * a document and absent from these sets is data we cannot represent, and
     * is reported through `droppedFields` rather than discarded quietly.
     *
     * Kept beside the serializer that writes them: a field added to
     * [serialize] without being added here would immediately show up as a
     * false "dropped" report on the app's own exports, which is a much louder
     * failure than the silent loss it replaces.
     */
    private val ROOT_KEYS = setOf(
        "schemaVersion",
        "id",
        "name",
        "updatedAt",
        "nodes",
        "connections",
        "samplePrompts",
        "memoryRetrievalQuery",
    )
    private val NODE_KEYS = setOf("id", "type", "position", "label", "config", "contextConfig", "nodeConfig")
    private val POSITION_KEYS = setOf("x", "y")
    private val CONFIG_KEYS = setOf(
        "systemPrompt", "cloudProvider", "modelPath", "toolName", "targetPipelineId", "skillId",
        "clarificationTimeoutMs", "conditionPrompt", "conditionKeywords", "conditionComplexity",
        "fallbackClass", "quickReplies", "alwaysConfirm", "maxSubtasks", "stopOnError",
        "conditionHasImage",
    )
    private val CONTEXT_CONFIG_KEYS = setOf(
        "chatHistory",
        "originalTask",
        "nodeInput",
        "longTermMemory",
        "toolResults",
    )
    private val CONNECTION_KEYS = setOf("id", "fromNodeId", "toNodeId", "label")

    /* ----------------------------------------------------------------- *
     *  Serialise
     * ----------------------------------------------------------------- */

    /**
     * Renders [graph] into the schema-versioned JSON form. The output is
     * guaranteed to round-trip back through [parse] producing a
     * [PipelineImportOutcome.Success] equivalent to [graph].
     */
    fun serialize(graph: PipelineGraph): String {
        val root = JSONObject()
        root.put("schemaVersion", CURRENT_SCHEMA_VERSION)
        root.put("id", graph.id)
        root.put("name", graph.name)
        root.put("updatedAt", graph.updatedAt)

        val nodesJson = JSONArray()
        graph.nodes.forEach { node -> nodesJson.put(serializeNode(node)) }
        root.put("nodes", nodesJson)

        val connectionsJson = JSONArray()
        graph.connections.forEach { c -> connectionsJson.put(serializeConnection(c)) }
        root.put("connections", connectionsJson)

        root.put("samplePrompts", PipelineSamplePromptJson.encodeToArray(graph.samplePrompts))

        // Optional pipeline-level field: emitted only when actually declared, so
        // a pipeline that does not use it produces byte-identical output to
        // pre-field builds (and hand-written documents stay minimal). Blank is
        // skipped too — it round-trips to null anyway (`optStringOrNull`), and
        // writing an empty key would suggest a declaration that does not exist.
        graph.memoryRetrievalQuery
            ?.takeIf { it.isNotBlank() }
            ?.let { root.put("memoryRetrievalQuery", it) }

        return root.toString()
    }

    private fun serializeNode(node: NodeModel): JSONObject {
        val position = JSONObject()
            .put("x", node.x.toDouble())
            .put("y", node.y.toDouble())

        val config = JSONObject()
            .put("systemPrompt", node.systemPrompt ?: JSONObject.NULL)
            .put("cloudProvider", node.cloudProvider ?: JSONObject.NULL)
            .put("modelPath", node.modelPath ?: JSONObject.NULL)
            .put("toolName", node.toolName ?: JSONObject.NULL)
            // PIPELINE / SKILL reference ids live in the flat block (mirroring
            // `toolName`) because the runtime executors read them directly off
            // [NodeModel] — `PipelineNodeExecutor` reads `targetPipelineId`,
            // `SkillNodeExecutor` reads `skillId`. They are ALSO embedded in the
            // rich `nodeConfig` envelope below; keeping both in agreement matches
            // how `systemPrompt`/`toolName` are already duplicated.
            .put("targetPipelineId", node.targetPipelineId ?: JSONObject.NULL)
            .put("skillId", node.skillId ?: JSONObject.NULL)
            .put("clarificationTimeoutMs", node.clarificationTimeoutMs ?: JSONObject.NULL)
            .put("conditionPrompt", node.conditionPrompt ?: JSONObject.NULL)
            .put("conditionKeywords", node.conditionKeywords ?: JSONObject.NULL)
            .put("fallbackClass", node.fallbackClass ?: JSONObject.NULL)
            .put("quickReplies", node.quickReplies ?: JSONObject.NULL)
            .put("alwaysConfirm", node.alwaysConfirm ?: JSONObject.NULL)
            .put("maxSubtasks", node.maxSubtasks ?: JSONObject.NULL)
            .put("stopOnError", node.stopOnError ?: JSONObject.NULL)
            .put("conditionComplexity", node.conditionComplexity ?: JSONObject.NULL)
            .put("conditionHasImage", node.conditionHasImage ?: JSONObject.NULL)

        val contextConfig = JSONObject()
            .put("chatHistory", node.contextConfig.chatHistory)
            .put("originalTask", node.contextConfig.originalTask)
            .put("nodeInput", node.contextConfig.nodeInput)
            .put("longTermMemory", node.contextConfig.longTermMemory)
            .put("toolResults", node.contextConfig.toolResults)

        val nodeJson = JSONObject()
            .put("id", node.id)
            .put("type", node.type.name)
            .put("position", position)
            .put("label", node.label)
            .put("config", config)
            .put("contextConfig", contextConfig)

        // Embed the rich per-node config (the `NodeConfigCodec` payload stored
        // in [NodeModel.configJson]) as an opaque nested `nodeConfig` object so
        // the browser editor and other app instances can round-trip the full
        // `NodeConfig`. The domain layer intentionally does NOT
        // interpret this blob — it is passed through verbatim, keeping the
        // serializer free of any presentation-layer dependency. The flat
        // `config` block above stays authoritative for the runtime engine.
        // Absent / blank / malformed `configJson` simply omits the key.
        node.configJson
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            ?.let { nodeJson.put("nodeConfig", it) }

        return nodeJson
    }

    private fun serializeConnection(c: ConnectionModel): JSONObject = JSONObject()
        .put("id", c.id)
        .put("fromNodeId", c.sourceNodeId)
        .put("toNodeId", c.targetNodeId)
        .put("label", c.label ?: JSONObject.NULL)

    /* ----------------------------------------------------------------- *
     *  Parse
     * ----------------------------------------------------------------- */

    /**
     * Parses [jsonText] into a [PipelineGraph] and reports the outcome.
     *
     * The function never throws — every parse error is converted into a
     * [PipelineImportOutcome.Failure] with a human-readable message so the
     * caller (UI / use case) can surface it without try/catch boilerplate.
     */
    fun parse(jsonText: String): PipelineImportOutcome {
        val root = try {
            JSONObject(jsonText)
        } catch (e: JSONException) {
            // Clamped, not echoed: on Android the parser's message ends with
            // " at character N of <the entire input>", so an unbounded echo put
            // the whole picked file into the error the user reads.
            return PipelineImportOutcome.Failure("Invalid JSON: ${e.message.orEmpty().toDisplaySafe()}")
        }

        if (!root.has("schemaVersion")) {
            return PipelineImportOutcome.Failure("Missing required field: schemaVersion")
        }
        val schemaVersion = root.optInt("schemaVersion", -1)
        val supported = schemaVersion in SUPPORTED_SCHEMA_VERSIONS

        // Collected before building, because building is what drops them:
        // `buildNode` reads the keys it knows and never looks at the rest.
        val droppedFields = collectDroppedFields(root)

        val graph = try {
            buildGraph(root)
        } catch (e: PipelineParseException) {
            return PipelineImportOutcome.Failure(e.message ?: "Parse failed")
        } catch (e: JSONException) {
            return PipelineImportOutcome.Failure("Malformed pipeline document: ${e.message.orEmpty().toDisplaySafe()}")
        }

        return if (supported) {
            PipelineImportOutcome.Success(graph = graph, droppedFields = droppedFields)
        } else {
            PipelineImportOutcome.SchemaMismatch(
                graph = graph,
                foundVersion = schemaVersion,
                expectedVersion = CURRENT_SCHEMA_VERSION,
                droppedFields = droppedFields,
            )
        }
    }

    /**
     * Lists, as dotted paths, every key the document carries that this build
     * does not read.
     *
     * This is the answer to the real complaint about the importer: a document
     * whose `schemaVersion` matches can still contain fields we silently throw
     * away, because the format's convention is that additive fields do **not**
     * bump the version. A version check could never have caught that; walking
     * the document against the known key sets can.
     *
     * `nodeConfig` is deliberately not descended into — it is an opaque blob
     * that round-trips verbatim, so nothing inside it is ever lost.
     *
     * @param root the parsed document.
     * @return dotted paths, in document order, e.g. `nodes[1].config.topK`.
     *   Empty for a document this build wrote.
     */
    private fun collectDroppedFields(root: JSONObject): List<String> {
        val dropped = mutableListOf<String>()
        unknownKeys(root, ROOT_KEYS).forEach { dropped += it }

        root.optJSONArray("nodes")?.let { nodes ->
            for (i in 0 until nodes.length()) {
                val node = nodes.optJSONObject(i) ?: continue
                val prefix = "nodes[$i]"
                unknownKeys(node, NODE_KEYS).forEach { dropped += "$prefix.$it" }
                node.optJSONObject("position")
                    ?.let { unknownKeys(it, POSITION_KEYS) }
                    ?.forEach { dropped += "$prefix.position.$it" }
                node.optJSONObject("config")
                    ?.let { unknownKeys(it, CONFIG_KEYS) }
                    ?.forEach { dropped += "$prefix.config.$it" }
                node.optJSONObject("contextConfig")
                    ?.let { unknownKeys(it, CONTEXT_CONFIG_KEYS) }
                    ?.forEach { dropped += "$prefix.contextConfig.$it" }
            }
        }

        root.optJSONArray("connections")?.let { connections ->
            for (i in 0 until connections.length()) {
                val connection = connections.optJSONObject(i) ?: continue
                unknownKeys(connection, CONNECTION_KEYS).forEach { dropped += "connections[$i].$it" }
            }
        }
        return dropped
    }

    /** Keys of [json] that are not in [known], in document order. */
    private fun unknownKeys(json: JSONObject, known: Set<String>): List<String> =
        json.keys().asSequence().filterNot { it in known }.toList()

    // Reason: one throw per required field or identity rule, each with its own
    // message — the same trade-off as `buildConnection`.
    @Suppress("ThrowsCount")
    private fun buildGraph(root: JSONObject): PipelineGraph {
        val id = root.optString("id").takeIf { it.isNotBlank() }
            ?: throw PipelineParseException("Missing required field: id")
        // Normalised, not just checked: the name comes from a file the user did
        // not write and is rendered in the library list, the editor toolbar and
        // the import dialogs, so it gets the ceiling every in-app path enforces —
        // by truncation, so a long name does not fail an otherwise good file.
        val name = root.optString("name")
            .toDisplaySafe(maxLength = PipelineConstants.MAX_NAME_LENGTH, ellipsis = "")
            .takeIf { it.isNotEmpty() }
            ?: throw PipelineParseException("Missing required field: name")
        val updatedAt = root.optLong("updatedAt", System.currentTimeMillis())

        val nodesJson = root.optJSONArray("nodes")
            ?: throw PipelineParseException("Missing required field: nodes")
        val nodes = (0 until nodesJson.length()).map { i ->
            buildNode(nodesJson.getJSONObject(i), index = i)
        }
        // Rejected here, where they are first visible. Accepted, two nodes with
        // one id validated as a single vertex, were both assigned the same fresh
        // id when stored, and one silently replaced the other — the graph that
        // was checked was not the graph that was saved.
        firstDuplicate(nodes.map { it.id })?.let { duplicate ->
            throw PipelineParseException("Duplicate node id \"${duplicate.toDisplaySafe()}\"")
        }

        val connectionsJson = root.optJSONArray("connections") ?: JSONArray()
        val nodeIds = nodes.mapTo(mutableSetOf()) { it.id }
        val connections = (0 until connectionsJson.length()).map { i ->
            buildConnection(connectionsJson.getJSONObject(i), index = i, nodeIds = nodeIds)
        }
        firstDuplicate(connections.map { it.id })?.let { duplicate ->
            throw PipelineParseException("Duplicate connection id \"${duplicate.toDisplaySafe()}\"")
        }

        // Optional + additive: documents from older builds simply lack the key,
        // which decodes to an empty list (no suggestions) — preserving the
        // schema-version-1 forward-compatibility contract.
        val samplePrompts = PipelineSamplePromptJson.decodeFromArray(root.optJSONArray("samplePrompts"))

        // Same additive contract: an absent key means "declares no background
        // retrieval query", which is exactly how every pre-field document
        // behaves at runtime.
        val memoryRetrievalQuery = root.optStringOrNull("memoryRetrievalQuery")

        return PipelineGraph(
            id = id,
            name = name,
            nodes = nodes,
            connections = connections,
            updatedAt = updatedAt,
            samplePrompts = samplePrompts,
            memoryRetrievalQuery = memoryRetrievalQuery,
        )
    }

    private fun buildNode(json: JSONObject, index: Int): NodeModel {
        val id = json.optString("id").takeIf { it.isNotBlank() }
            ?: throw PipelineParseException("Node #$index missing id")
        val typeRaw = json.optString("type").takeIf { it.isNotBlank() }
            ?: throw PipelineParseException("Node \"${id.toDisplaySafe()}\" missing type")
        val type = try {
            NodeType.valueOf(typeRaw)
        } catch (e: IllegalArgumentException) {
            throw PipelineParseException(
                "Node \"${id.toDisplaySafe()}\" has unknown type \"${typeRaw.toDisplaySafe()}\"",
            )
        }

        val position = json.optJSONObject("position")
        val x = position?.optDouble("x", 0.0)?.toFloat() ?: 0f
        val y = position?.optDouble("y", 0.0)?.toFloat() ?: 0f
        val label = json.optString("label")
            .toDisplaySafe(maxLength = PipelineConstants.MAX_IMPORTED_LABEL_LENGTH, ellipsis = "")
            .ifEmpty { type.name }

        val config = json.optJSONObject("config") ?: JSONObject()
        val contextConfigJson = json.optJSONObject("contextConfig")
        val contextConfig = if (contextConfigJson != null) {
            NodeContextConfig(
                chatHistory = contextConfigJson.optBoolean("chatHistory", true),
                originalTask = contextConfigJson.optBoolean("originalTask", true),
                nodeInput = contextConfigJson.optBoolean("nodeInput", true),
                longTermMemory = contextConfigJson.optBoolean("longTermMemory", true),
                toolResults = contextConfigJson.optBoolean("toolResults", true),
            )
        } else {
            // Legacy / minimal documents fall back to the per-type recommended
            // defaults so the imported graph behaves sensibly out of the box.
            NodeContextConfig.defaultForType(type)
        }

        // Round-trip the opaque rich-config blob when present. Stored verbatim
        // into [NodeModel.configJson]; `NodeConfigCodec` (presentation layer)
        // is the only code that interprets it. Absent ⇒ null, so legacy
        // flat-only documents continue to derive their config on first edit.
        val nodeConfigJson = json.optJSONObject("nodeConfig")?.toString()

        return NodeModel(
            id = id,
            type = type,
            x = x,
            y = y,
            label = label,
            toolName = config.optStringOrNull("toolName"),
            targetPipelineId = config.optStringOrNull("targetPipelineId"),
            skillId = config.optStringOrNull("skillId"),
            modelPath = config.optStringOrNull("modelPath"),
            conditionComplexity = config.optIntOrNull("conditionComplexity"),
            conditionKeywords = config.optStringOrNull("conditionKeywords"),
            fallbackClass = config.optStringOrNull("fallbackClass"),
            quickReplies = config.optStringOrNull("quickReplies"),
            alwaysConfirm = config.optBooleanOrNull("alwaysConfirm"),
            maxSubtasks = config.optIntOrNull("maxSubtasks"),
            stopOnError = config.optBooleanOrNull("stopOnError"),
            conditionPrompt = config.optStringOrNull("conditionPrompt"),
            conditionHasImage = config.optBooleanOrNull("conditionHasImage"),
            systemPrompt = config.optStringOrNull("systemPrompt"),
            cloudProvider = config.optStringOrNull("cloudProvider"),
            clarificationTimeoutMs = config.optLongOrNull("clarificationTimeoutMs"),
            contextConfig = contextConfig,
            configJson = nodeConfigJson,
        )
    }

    // Reason: each `throw` here pinpoints a distinct schema-violation kind
    // (`missing id`, `missing fromNodeId`, `missing toNodeId`, `unknown source`,
    // `unknown target`). Folding them into a single Result<Throwable> would
    // erase the message-specificity that makes import errors actionable.
    @Suppress("ThrowsCount")
    private fun buildConnection(json: JSONObject, index: Int, nodeIds: Set<String>): ConnectionModel {
        val id = json.optString("id").takeIf { it.isNotBlank() }
            ?: throw PipelineParseException("Connection #$index missing id")
        val quotedId = id.toDisplaySafe()
        val from = json.optString("fromNodeId").takeIf { it.isNotBlank() }
            ?: throw PipelineParseException("Connection \"$quotedId\" missing fromNodeId")
        val to = json.optString("toNodeId").takeIf { it.isNotBlank() }
            ?: throw PipelineParseException("Connection \"$quotedId\" missing toNodeId")
        if (from !in nodeIds) {
            throw PipelineParseException("Connection \"$quotedId\" references unknown node \"${from.toDisplaySafe()}\"")
        }
        if (to !in nodeIds) {
            throw PipelineParseException("Connection \"$quotedId\" references unknown node \"${to.toDisplaySafe()}\"")
        }
        val label = json.optStringOrNull("label")
        return ConnectionModel(id = id, sourceNodeId = from, targetNodeId = to, label = label)
    }

    /** The first value of [ids] that occurs twice, or `null` when every id is distinct. */
    private fun firstDuplicate(ids: List<String>): String? {
        val seen = HashSet<String>(ids.size)
        return ids.firstOrNull { !seen.add(it) }
    }

    private class PipelineParseException(message: String) : RuntimeException(message)
}

/* ----------------------------------------------------------------- *
 *  JSONObject helpers — return null instead of empty string / 0 for
 *  fields that are absent or explicitly JSON-null. Default org.json
 *  optString returns "" for both, which loses signal.
 * ----------------------------------------------------------------- */

private fun JSONObject.optStringOrNull(name: String): String? =
    if (!has(name) || isNull(name)) null else getString(name).takeIf { it.isNotEmpty() }

private fun JSONObject.optIntOrNull(name: String): Int? = if (!has(name) || isNull(name)) null else getInt(name)

private fun JSONObject.optLongOrNull(name: String): Long? = if (!has(name) || isNull(name)) null else getLong(name)

private fun JSONObject.optBooleanOrNull(name: String): Boolean? =
    if (!has(name) || isNull(name)) null else getBoolean(name)
