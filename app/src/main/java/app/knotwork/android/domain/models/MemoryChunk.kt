package app.knotwork.android.domain.models

/**
 * Domain model representing a chunk of long-term memory.
 *
 * @property id The unique identifier of the memory chunk.
 * @property text The raw text of the memory.
 * @property embedding The vector embedding of the text.
 * @property timestamp The time the memory was created.
 * @property isPinned When `true`, the user pinned this chunk; pinned rows
 *   sort ahead of unpinned ones on the memory surface and survive future
 *   compaction passes.
 * @property source Provenance of the chunk (auto-extracted from a chat,
 *   saved manually, produced by compaction, or unknown for legacy rows).
 * @property tags Free-form labels attached to the chunk (e.g. the
 *   auto-extraction fact type `fact` / `preference` / `project`, or
 *   user-added tags). Lower-case, kebab-case by convention; may be empty.
 * @property useCount Number of times this chunk has been injected into a
 *   pipeline run's Long-Term Memory context block — runs, not replies. Drives
 *   the detail sheet's "Used in N runs" line; `0` for chunks never retrieved.
 * @property lastUsedAt Epoch-millis of the most recent retrieval, or `null`
 *   if the chunk has never been used.
 */
data class MemoryChunk(
    val id: Long,
    val text: String,
    val embedding: FloatArray,
    val timestamp: Long,
    val isPinned: Boolean = false,
    val source: MemorySource = MemorySource.Unknown,
    val tags: List<String> = emptyList(),
    val useCount: Int = 0,
    val lastUsedAt: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MemoryChunk

        if (id != other.id) return false
        if (text != other.text) return false
        if (!embedding.contentEquals(other.embedding)) return false
        if (timestamp != other.timestamp) return false
        if (isPinned != other.isPinned) return false
        if (source != other.source) return false
        if (tags != other.tags) return false
        if (useCount != other.useCount) return false
        if (lastUsedAt != other.lastUsedAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + isPinned.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + tags.hashCode()
        result = 31 * result + useCount
        result = 31 * result + (lastUsedAt?.hashCode() ?: 0)
        return result
    }
}
