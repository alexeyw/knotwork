package app.knotwork.android.data.local.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.knotwork.android.domain.models.MemorySource

/**
 * Room entity for one earlier version of a long-term memory chunk: the text a chunk
 * held before a newer statement of the same fact replaced it in place.
 *
 * A version belongs to its chunk and goes with it: deleting the chunk — by hand, by
 * *Clear memory*, by compaction or by the hard limit — deletes its versions through
 * the `CASCADE` foreign key. A version carries no embedding: it is never searched.
 *
 * @property id Auto-generated row id.
 * @property chunkId Id of the [MemoryChunkEntity] whose earlier text this is.
 * @property text The text the chunk held before it was replaced.
 * @property source Where that text came from, persisted like [MemoryChunkEntity.source].
 * @property tagsCsv The chunk's tags at the time, comma-separated (`''` for none).
 * @property capturedAt When that text was first stored, epoch millis (the chunk's
 *   `timestamp` at the time).
 * @property replacedAt When a newer statement replaced it, epoch millis.
 */
@Entity(
    tableName = "memory_chunk_history",
    foreignKeys = [
        ForeignKey(
            entity = MemoryChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chunkId")],
)
data class MemoryChunkVersionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val chunkId: Long,
    val text: String,
    @ColumnInfo(name = "source", defaultValue = MemoryChunkEntity.SOURCE_DEFAULT_JSON)
    val source: MemorySource,
    @ColumnInfo(name = "tagsCsv", defaultValue = "")
    val tagsCsv: String = "",
    val capturedAt: Long,
    val replacedAt: Long,
)
