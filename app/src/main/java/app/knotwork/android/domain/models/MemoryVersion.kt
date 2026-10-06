package app.knotwork.android.domain.models

/**
 * One earlier version of a long-term memory chunk: a text the chunk held before a
 * newer statement of the same fact replaced it in place.
 *
 * Kept so a correction never loses what it corrected — the user can see what the
 * agent used to remember, where it came from, and when it changed. A version is
 * never searched or shown to the agent.
 *
 * @property id Identifier of the version.
 * @property chunkId Identifier of the chunk this text belonged to.
 * @property text The earlier text.
 * @property source Where the earlier text came from.
 * @property tags The chunk's tags while it held that text.
 * @property capturedAt When the earlier text was first stored, epoch millis.
 * @property replacedAt When a newer statement replaced it, epoch millis.
 */
data class MemoryVersion(
    val id: Long,
    val chunkId: Long,
    val text: String,
    val source: MemorySource,
    val tags: List<String>,
    val capturedAt: Long,
    val replacedAt: Long,
) {
    /** Shared constants of [MemoryVersion]. */
    companion object {
        /**
         * Earlier versions a chunk keeps at most; the oldest beyond it are dropped.
         * A bound, not a target: the history shows what a fact used to say, not every
         * pass that restated it.
         */
        const val MAX_PER_CHUNK: Int = 10
    }
}

/**
 * A memory chunk waiting to update a pinned chunk, which an automatic write never
 * replaces: the two form a pair until the user resolves it.
 *
 * @property updateChunkId Identifier of the chunk holding the update.
 * @property pinnedChunkId Identifier of the pinned chunk it updates.
 */
data class MemoryPendingUpdate(val updateChunkId: Long, val pinnedChunkId: Long)
