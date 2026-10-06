package app.knotwork.android.data.mappers

import app.knotwork.android.data.local.Converters
import app.knotwork.android.data.local.TagsCsv
import app.knotwork.android.data.local.models.MemoryChunkEntity
import app.knotwork.android.domain.models.MemoryChunk

/**
 * Maps a stored memory chunk to the domain model, decoding its embedding BLOB.
 *
 * @param converters Decodes the embedding BLOB.
 * @return The chunk, or `null` when its embedding cannot be decoded — such a row has
 *   no vector to compare and is left out of every similarity read.
 */
internal fun MemoryChunkEntity.toDomainOrNull(converters: Converters): MemoryChunk? {
    val embeddingArray = converters.toFloatArray(embedding) ?: return null
    return MemoryChunk(
        id = id,
        text = text,
        embedding = embeddingArray,
        timestamp = timestamp,
        isPinned = isPinned,
        source = source,
        tags = TagsCsv.decode(tagsCsv),
        useCount = useCount,
        lastUsedAt = lastUsedAt,
    )
}
