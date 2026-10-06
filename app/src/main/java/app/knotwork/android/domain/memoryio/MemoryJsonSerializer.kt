package app.knotwork.android.domain.memoryio

import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MemoryExportDocument
import app.knotwork.android.domain.models.MemoryImportOutcome
import app.knotwork.android.domain.models.MemoryVersion
import app.knotwork.android.domain.text.toDisplaySafe
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Two-way mapper between the long-term memory table and the portable JSON
 * document used to move an agent's memory between devices.
 *
 * ### Schema (version 1)
 *
 * ```
 * {
 *   "schemaVersion": 1,
 *   "embeddingProviderId": "use",
 *   "exportedAt": 1717000000000,
 *   "chunks": [
 *     {
 *       "id": 42,
 *       "text": "I prefer dark mode.",
 *       "embedding": [0.12, -0.04, ...],
 *       "source": { "type": "manual" },
 *       "timestamp": 1716990000000,
 *       "isPinned": false,
 *       "tags": ["preference"],
 *       "history": [
 *         {
 *           "text": "I prefer light mode.",
 *           "source": { "type": "chat_session", "sessionId": "…" },
 *           "tags": ["preference"],
 *           "capturedAt": 1716000000000,
 *           "replacedAt": 1716990000000
 *         }
 *       ]
 *     }
 *   ]
 * }
 * ```
 *
 * `history` is optional and additive — a file without it is the same version-1
 * document, and an older reader ignores the key. It holds the chunk's earlier
 * texts, the most recently replaced first, without embeddings: a version is never
 * searched. On import a version is only ever history — it never becomes a current
 * chunk — at most [MemoryVersion.MAX_PER_CHUNK] of the newest are kept, and its
 * dates are capped at the moment of parsing like a chunk's `timestamp`. A chunk
 * waiting to update a pinned one is written as an ordinary chunk: pins are not
 * taken from a file, so neither is the pair.
 *
 * `embeddingProviderId` records which [app.knotwork.android.domain.services.EmbeddingProvider]
 * produced the stored vectors so the importer can detect a vector-space
 * mismatch. The per-chunk `source` object reuses [MemorySourceJson] so its
 * encoding stays identical to the Room `source` column. Transient usage
 * telemetry (`useCount` / `lastUsedAt`) is intentionally omitted — it is
 * per-device and not meaningful after a transfer.
 *
 * ### What a file may not claim
 *
 * Two fields decide whether a chunk is retrieved at all rather than how, and no
 * screen of the app lets the user set them in bulk, so [parse] does not take
 * them from the file:
 *  - `isPinned` is read as `false` for every chunk. A pinned chunk bypasses the
 *    similarity threshold, sorts first in every retrieval and is exempt from
 *    compaction; pinning stays a per-entry act on the Memory screen. How many
 *    chunks the file had pinned is reported as
 *    [MemoryExportDocument.pinnedInFile] so the import dialog can say so.
 *  - `timestamp` is capped at the moment of parsing. A date in the future would
 *    hold the head of `$MEMORY_SUMMARY` (newest first) and stay outside the
 *    compaction window indefinitely; re-dated to "now" it ages like any new
 *    chunk. A file from a device whose clock ran ahead imports as well.
 *
 * A document may hold at most `SettingsDefaults.MAX_MEMORY_CHUNKS_MAX` chunks —
 * the most the memory store can be set to keep.
 *
 * Uses `org.json` per the project's API conventions. [parse] never throws —
 * every error becomes a [MemoryImportOutcome.Failure] with a human-readable
 * message.
 */
object MemoryJsonSerializer {

    /**
     * Current schema version emitted by [serialize]. Older readers surface a
     * [MemoryImportOutcome.SchemaMismatch] rather than silently dropping fields.
     */
    const val CURRENT_SCHEMA_VERSION: Int = 1

    private const val KEY_SCHEMA_VERSION = "schemaVersion"
    private const val KEY_PROVIDER_ID = "embeddingProviderId"
    private const val KEY_EXPORTED_AT = "exportedAt"
    private const val KEY_CHUNKS = "chunks"
    private const val KEY_ID = "id"
    private const val KEY_TEXT = "text"
    private const val KEY_EMBEDDING = "embedding"
    private const val KEY_SOURCE = "source"
    private const val KEY_TIMESTAMP = "timestamp"
    private const val KEY_IS_PINNED = "isPinned"
    private const val KEY_TAGS = "tags"
    private const val KEY_HISTORY = "history"
    private const val KEY_CAPTURED_AT = "capturedAt"
    private const val KEY_REPLACED_AT = "replacedAt"

    /**
     * Renders [chunks] into the schema-versioned JSON form.
     *
     * @param chunks The memory chunks to serialise.
     * @param embeddingProviderId Id of the active embedding provider — stamped
     *   on the document so an importing device can detect a vector-space
     *   mismatch.
     * @param exportedAt Epoch-millis to record as the export time.
     * @param history Earlier versions by chunk id; a chunk without an entry is
     *   written without a `history` key.
     * @return JSON text suitable for writing to a file.
     */
    fun serialize(
        chunks: List<MemoryChunk>,
        embeddingProviderId: String,
        exportedAt: Long,
        history: Map<Long, List<MemoryVersion>> = emptyMap(),
    ): String {
        val chunksJson = JSONArray()
        for (chunk in chunks) {
            val embeddingJson = JSONArray()
            for (value in chunk.embedding) embeddingJson.put(value.toDouble())
            val tagsJson = JSONArray()
            for (tag in chunk.tags) tagsJson.put(tag)
            chunksJson.put(
                JSONObject().apply {
                    put(KEY_ID, chunk.id)
                    put(KEY_TEXT, chunk.text)
                    put(KEY_EMBEDDING, embeddingJson)
                    put(KEY_SOURCE, MemorySourceJson.encode(chunk.source))
                    put(KEY_TIMESTAMP, chunk.timestamp)
                    put(KEY_IS_PINNED, chunk.isPinned)
                    put(KEY_TAGS, tagsJson)
                    history[chunk.id]?.takeIf { it.isNotEmpty() }?.let { put(KEY_HISTORY, versionsJson(it)) }
                },
            )
        }
        return JSONObject().apply {
            put(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            put(KEY_PROVIDER_ID, embeddingProviderId)
            put(KEY_EXPORTED_AT, exportedAt)
            put(KEY_CHUNKS, chunksJson)
        }.toString()
    }

    /**
     * Parses [jsonText] into a [MemoryExportDocument] and reports the outcome.
     *
     * Never throws: malformed JSON, a missing `schemaVersion` / `embeddingProviderId`,
     * or a chunk missing its required `text` / `embedding` / `timestamp` all
     * resolve to [MemoryImportOutcome.Failure]. A clean parse whose version
     * differs from [CURRENT_SCHEMA_VERSION] yields [MemoryImportOutcome.SchemaMismatch].
     *
     * @param jsonText Raw JSON text.
     * @param nowMillis Wall-clock "now"; no parsed `timestamp` is later than it.
     *   Defaults to the system clock; tests pass a fixed value.
     * @return The parse outcome the UI should branch on.
     */
    @Suppress("ReturnCount")
    fun parse(jsonText: String, nowMillis: Long = System.currentTimeMillis()): MemoryImportOutcome {
        val root: JSONObject = try {
            JSONObject(jsonText)
        } catch (e: JSONException) {
            // Clamped: on Android the message quotes the entire input.
            return MemoryImportOutcome.Failure("Invalid JSON: ${e.message.orEmpty().toDisplaySafe()}")
        }

        val foundVersion = root.optInt(KEY_SCHEMA_VERSION, -1)
        if (foundVersion == -1) {
            return MemoryImportOutcome.Failure("Missing schemaVersion field")
        }
        val providerId = root.optString(KEY_PROVIDER_ID).takeIf { it.isNotBlank() }
            ?: return MemoryImportOutcome.Failure("Missing or blank embeddingProviderId field")
        val exportedAt = root.optLong(KEY_EXPORTED_AT, 0L)

        val chunksJson = root.optJSONArray(KEY_CHUNKS)
            ?: return MemoryImportOutcome.Failure("Missing chunks array")
        // Counted before any chunk is decoded: a file holding more memories than
        // the store can ever keep is refused whole, the way a bundle over its
        // pipeline ceiling is, rather than imported and compacted away.
        if (chunksJson.length() > SettingsDefaults.MAX_MEMORY_CHUNKS_MAX) {
            return MemoryImportOutcome.Failure(
                "File contains ${chunksJson.length()} memory chunks, more than the " +
                    "${SettingsDefaults.MAX_MEMORY_CHUNKS_MAX} the memory can hold",
            )
        }

        val chunks = ArrayList<MemoryChunk>(chunksJson.length())
        val histories = ArrayList<List<MemoryVersion>>(chunksJson.length())
        var pinnedInFile = 0
        for (i in 0 until chunksJson.length()) {
            val chunkJson = chunksJson.optJSONObject(i)
                ?: return MemoryImportOutcome.Failure("Malformed chunk at index $i")
            if (chunkJson.optBoolean(KEY_IS_PINNED, false)) pinnedInFile++
            val chunk = parseChunk(chunkJson, nowMillis) ?: return MemoryImportOutcome.Failure(
                "Chunk at index $i has a missing or malformed required field: text must be non-blank, " +
                    "embedding a non-empty array of finite numbers, and timestamp a positive epoch-millis value",
            )
            chunks.add(chunk)
            histories.add(
                parseHistory(chunkJson, nowMillis) ?: return MemoryImportOutcome.Failure(
                    "Chunk at index $i has a malformed earlier version: its text must be non-blank and " +
                        "capturedAt and replacedAt positive epoch-millis values",
                ),
            )
        }

        val document = MemoryExportDocument(
            embeddingProviderId = providerId,
            exportedAt = exportedAt,
            chunks = chunks,
            pinnedInFile = pinnedInFile,
            histories = if (histories.all { it.isEmpty() }) emptyList() else histories,
        )
        return if (foundVersion != CURRENT_SCHEMA_VERSION) {
            MemoryImportOutcome.SchemaMismatch(
                document = document,
                foundVersion = foundVersion,
                expectedVersion = CURRENT_SCHEMA_VERSION,
            )
        } else {
            MemoryImportOutcome.Success(document)
        }
    }

    /**
     * Parses a single chunk object, returning `null` (so the caller fails the
     * whole import with an index-tagged message) when:
     *  - a required field (`text`, `embedding`, `timestamp`) is absent; or
     *  - `text` is blank; or
     *  - the embedding is not a non-empty array of finite numbers (a blank
     *    embedding would serialise to a zero-length vector that reads back as a
     *    dropped row, and a non-numeric entry would become `NaN` and poison
     *    cosine similarity); or
     *  - `timestamp` is not a positive epoch-millis value. `optLong` coerces a
     *    non-numeric / null `timestamp` to `0`, which would store the memory at
     *    epoch 1970 — maximally stale to the recency re-ranker and an immediate
     *    compaction-deletion candidate — so it is rejected like the other fields.
     *
     * A valid chunk is returned unpinned and with its `timestamp` capped at
     * [nowMillis] (see *What a file may not claim* on the object).
     */
    @Suppress("ReturnCount")
    private fun parseChunk(json: JSONObject, nowMillis: Long): MemoryChunk? {
        if (!json.has(KEY_TEXT) || !json.has(KEY_EMBEDDING) || !json.has(KEY_TIMESTAMP)) return null
        // optString returns "" for an explicit JSON null, so a blank check also
        // rejects `"text": null` — a memory with no text is meaningless.
        val text = json.optString(KEY_TEXT)
        if (text.isBlank()) return null
        val embeddingJson = json.optJSONArray(KEY_EMBEDDING) ?: return null
        if (embeddingJson.length() == 0) return null
        val embedding = FloatArray(embeddingJson.length())
        for (idx in 0 until embeddingJson.length()) {
            val value = embeddingJson.optDouble(idx, Double.NaN)
            if (value.isNaN() || value.isInfinite()) return null
            embedding[idx] = value.toFloat()
        }
        val timestamp = json.optLong(KEY_TIMESTAMP)
        if (timestamp <= 0L) return null
        val tagsJson = json.optJSONArray(KEY_TAGS)
        val tags = if (tagsJson == null) {
            emptyList()
        } else {
            (0 until tagsJson.length()).mapNotNull { idx -> tagsJson.optString(idx).takeIf { it.isNotBlank() } }
        }
        return MemoryChunk(
            // A missing id defaults to 0 so Room auto-assigns a fresh primary key
            // on insert; an explicit positive id would collide with existing
            // auto-increment rows (silently dropped by the Merge dedupe filter or
            // overwritten under Replace's REPLACE-on-conflict).
            // Kept only within the range an export of this app holds: an id near
            // Long.MAX_VALUE, inserted as is, moves the table's AUTOINCREMENT
            // counter to the end of its range, and every later memory write fails
            // for good. 0 lets Room assign one; in range, it still de-duplicates a Merge.
            id = json.optLong(KEY_ID, 0L).takeIf { it in 1L..Int.MAX_VALUE.toLong() } ?: 0L,
            text = text,
            embedding = embedding,
            timestamp = timestamp.coerceAtMost(nowMillis),
            isPinned = false,
            source = MemorySourceJson.decode(json.optJSONObject(KEY_SOURCE)),
            tags = tags,
        )
    }

    private fun versionsJson(versions: List<MemoryVersion>): JSONArray {
        val array = JSONArray()
        for (version in versions) {
            val tagsJson = JSONArray()
            for (tag in version.tags) tagsJson.put(tag)
            array.put(
                JSONObject().apply {
                    put(KEY_TEXT, version.text)
                    put(KEY_SOURCE, MemorySourceJson.encode(version.source))
                    put(KEY_TAGS, tagsJson)
                    put(KEY_CAPTURED_AT, version.capturedAt)
                    put(KEY_REPLACED_AT, version.replacedAt)
                },
            )
        }
        return array
    }

    /**
     * Reads a chunk's earlier versions. A missing `history` is no history; a
     * malformed version fails the chunk, as a malformed required field does.
     *
     * @param json The chunk object.
     * @param nowMillis Upper bound on both dates of a version.
     * @return The newest [MemoryVersion.MAX_PER_CHUNK] versions, most recently
     *   replaced first; `null` when a version is malformed.
     */
    private fun parseHistory(json: JSONObject, nowMillis: Long): List<MemoryVersion>? {
        val array = json.optJSONArray(KEY_HISTORY) ?: return emptyList()
        val versions = (0 until array.length()).map { index ->
            array.optJSONObject(index)?.let { parseVersion(it, nowMillis) }
        }
        return if (versions.any { it == null }) {
            null
        } else {
            versions.filterNotNull().sortedByDescending { it.replacedAt }.take(MemoryVersion.MAX_PER_CHUNK)
        }
    }

    /**
     * Reads one earlier version: its text, source, tags and both dates, the dates
     * capped at [nowMillis]. The id and chunk id are assigned when it is stored.
     *
     * @param json The version object.
     * @param nowMillis Upper bound on both dates.
     * @return The version, or `null` when its text is blank or a date is missing.
     */
    private fun parseVersion(json: JSONObject, nowMillis: Long): MemoryVersion? {
        val text = json.optString(KEY_TEXT)
        val capturedAt = json.optLong(KEY_CAPTURED_AT)
        val replacedAt = json.optLong(KEY_REPLACED_AT)
        if (text.isBlank() || capturedAt <= 0L || replacedAt <= 0L) return null
        val tagsJson = json.optJSONArray(KEY_TAGS)
        return MemoryVersion(
            id = 0L,
            chunkId = 0L,
            text = text,
            source = MemorySourceJson.decode(json.optJSONObject(KEY_SOURCE)),
            tags = if (tagsJson == null) {
                emptyList()
            } else {
                (0 until tagsJson.length()).mapNotNull { tagsJson.optString(it).takeIf(String::isNotBlank) }
            },
            capturedAt = capturedAt.coerceAtMost(nowMillis),
            replacedAt = replacedAt.coerceAtMost(nowMillis),
        )
    }
}
