package app.knotwork.android.domain.memoryio

import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MemoryExportDocument
import app.knotwork.android.domain.models.MemoryImportOutcome
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
 *       "tags": ["preference"]
 *     }
 *   ]
 * }
 * ```
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

    /**
     * Renders [chunks] into the schema-versioned JSON form.
     *
     * @param chunks The memory chunks to serialise.
     * @param embeddingProviderId Id of the active embedding provider — stamped
     *   on the document so an importing device can detect a vector-space
     *   mismatch.
     * @param exportedAt Epoch-millis to record as the export time.
     * @return JSON text suitable for writing to a file.
     */
    fun serialize(chunks: List<MemoryChunk>, embeddingProviderId: String, exportedAt: Long): String {
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
        }

        val document = MemoryExportDocument(
            embeddingProviderId = providerId,
            exportedAt = exportedAt,
            chunks = chunks,
            pinnedInFile = pinnedInFile,
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
            id = json.optLong(KEY_ID, 0L),
            text = text,
            embedding = embedding,
            timestamp = timestamp.coerceAtMost(nowMillis),
            isPinned = false,
            source = MemorySourceJson.decode(json.optJSONObject(KEY_SOURCE)),
            tags = tags,
        )
    }
}
