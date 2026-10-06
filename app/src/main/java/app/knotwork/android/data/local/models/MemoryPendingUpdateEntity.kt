package app.knotwork.android.data.local.models

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity linking a memory chunk that updates a pinned chunk to that pinned chunk.
 *
 * A pinned chunk is never replaced by an automatic write, so an update of it is stored
 * as a chunk of its own and linked here until the user resolves the pair. The unique
 * index on [pinnedChunkId] keeps at most one update waiting per pinned chunk. Both
 * foreign keys cascade: deleting the pinned chunk turns its update into an ordinary
 * chunk, and deleting the update ends the pair.
 *
 * @property updateChunkId Id of the chunk holding the waiting update.
 * @property pinnedChunkId Id of the pinned chunk it updates.
 */
@Entity(
    tableName = "memory_pending_updates",
    foreignKeys = [
        ForeignKey(
            entity = MemoryChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["updateChunkId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MemoryChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["pinnedChunkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["pinnedChunkId"], unique = true)],
)
data class MemoryPendingUpdateEntity(
    @PrimaryKey
    val updateChunkId: Long,
    val pinnedChunkId: Long,
)
