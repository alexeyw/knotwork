package app.knotwork.android.data.local.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a single chat message in the local database.
 *
 * @property id The unique identifier for the message (auto-generated).
 * @property sessionId The ID of the chat session this message belongs to.
 * @property role The role of the sender (e.g., USER, AGENT, SYSTEM).
 * @property content The text content of the message.
 * @property timestamp The time the message was created, in milliseconds since epoch.
 * @property isFinal Whether the message is part of the user-facing chat history.
 *   Intermediate node outputs are persisted with `false` so they remain available
 *   for the agent console while staying out of the main chat list. Defaults to
 *   `true`; the migration backfills `1` for pre-existing rows so legacy chats
 *   continue to render unchanged.
 * @property isStarred Whether the user has marked this message as a favourite.
 *   Defaults to `false`; surfaced via the chat-screen "starred only" filter.
 * @property attachmentPath Store-relative path (file name) of the image attached
 *   to this message, or `null` when there is no attachment. Added in
 *   `MIGRATION_38_39`; backfilled to `NULL` for pre-existing rows.
 * @property attachmentMimeType MIME type of the attached image (always
 *   `image/jpeg` in this phase), or `null` when there is no attachment.
 * @property attachmentWidth Pixel width of the stored (downscaled) attachment,
 *   or `null` when there is no attachment.
 * @property attachmentHeight Pixel height of the stored (downscaled) attachment,
 *   or `null` when there is no attachment.
 * @property modelName Display name of the model that generated this message,
 *   snapshotted at save time. `null` for user/system messages and for legacy
 *   AGENT rows persisted before `MIGRATION_43_44` added the column.
 * @property imported Whether the row came from a chat file (*Import chat*) instead of
 *   being written on this device. Added in `MIGRATION_62_63`; every row that existed
 *   before it was written here, so it is back-filled with `0`.
 */
@Entity(
    tableName = "chat_messages",
    // `sessionId` is the filter column for every hot chat query (load a chat,
    // delete a session's messages, collect attachment paths). Without an index
    // each is a full-table scan over all sessions' messages; this indexes it.
    indices = [Index("sessionId")],
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    @ColumnInfo(defaultValue = "1")
    val isFinal: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    val isStarred: Boolean = false,
    val attachmentPath: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentWidth: Int? = null,
    val attachmentHeight: Int? = null,
    val modelName: String? = null,
    @ColumnInfo(defaultValue = "0")
    val imported: Boolean = false,
)
