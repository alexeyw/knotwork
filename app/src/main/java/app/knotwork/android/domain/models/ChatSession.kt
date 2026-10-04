package app.knotwork.android.domain.models

import java.util.UUID

/**
 * Domain model representing a chat session.
 *
 * @property id The unique identifier for the session.
 * @property name The display name of the chat session.
 * @property updatedAt The timestamp of the last activity in this session.
 * @property pipelineId Identifier of the pipeline bound to this chat. `null` means
 *   the session uses the application-wide default pipeline (the user-marked
 *   `EntryPointSettings.defaultPipelineId`), preserving the default
 *   behaviour for legacy sessions and any chat that does not explicitly
 *   opt into a specific pipeline.
 * @property isStarred Whether the user has favorited this chat. Favorited
 *   chats sort to the top of the drawer thread list and render a small star
 *   indicator next to the title. Persisted in `chat_sessions.isStarred`
 *   (migration v21 → v22).
 * @property isArchived Whether the user has archived this chat. Archived
 *   sessions drop out of the main thread list and are reachable only through
 *   the archive surface; nothing they own is deleted, and unarchiving restores
 *   them to the list unchanged. New activity in an archived session (a
 *   background trigger run, a scheduled task) deliberately does **not** bring
 *   it back — archiving is a user decision and only the user reverses it.
 *   Persisted in `chat_sessions.isArchived` (migration v53 → v54).
 * @property archivedAt Wall-clock instant (epoch-millis) at which the user
 *   archived this chat, or `null` when it is not archived. The archive surface
 *   orders by it ("newest archived first") and renders it as the row's relative
 *   label ("Archived 2 h ago"); comparing it against [updatedAt] is also what
 *   tells the user a background run settled *after* they archived the chat.
 *   Deliberately not derived from [updatedAt], which a background run bumps
 *   without un-archiving. Persisted in `chat_sessions.archivedAt`
 *   (migration v54 → v55).
 */
data class ChatSession(
    val id: String,
    val name: String,
    val updatedAt: Long,
    val pipelineId: String? = null,
    val isStarred: Boolean = false,
    val isArchived: Boolean = false,
    val archivedAt: Long? = null,
) {
    /** Factory helpers for constructing fresh [ChatSession] rows. */
    companion object {
        /**
         * Builds a brand-new session row, centralising the id/timestamp defaults
         * that every background entry point (the scheduler worker, the share
         * target, automation triggers) would otherwise re-derive inline. Keeping
         * the construction in one place means a future change to how a fresh
         * session is shaped (a new required field, a "source" marker) lands once.
         *
         * @param name Display name for the new session.
         * @param pipelineId Pipeline bound to the session, or `null` to use the
         *   application-wide default.
         * @param id Session id; defaults to a fresh UUID. Callers recreating a
         *   specific (e.g. deleted) session pass its id to keep deep-links and
         *   bindings stable.
         * @param now Creation timestamp (epoch-millis); defaults to the wall
         *   clock and is overridable for tests.
         * @return The constructed (not yet persisted) [ChatSession].
         */
        fun create(
            name: String,
            pipelineId: String? = null,
            id: String = UUID.randomUUID().toString(),
            now: Long = System.currentTimeMillis(),
        ): ChatSession = ChatSession(id = id, name = name, updatedAt = now, pipelineId = pipelineId)
    }
}
