package app.knotwork.android.domain.repositories

import app.knotwork.android.domain.models.ChatHistorySummary
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.ChatSession
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing chat history and sessions.
 */
interface ChatRepository {
    /**
     * Saves a new chat message.
     *
     * @param message The [ChatMessage] to save.
     */
    suspend fun saveMessage(message: ChatMessage)

    /**
     * Retrieves the full history of messages for a given session, including
     * intermediate (`isFinal = false`) entries. Use this for context-window
     * computations, exports, and console logging.
     *
     * @param sessionId The unique ID of the chat session.
     * @return A [Flow] emitting the list of [ChatMessage] ordered by time.
     */
    fun getMessagesForSession(sessionId: String): Flow<List<ChatMessage>>

    /**
     * Retrieves only the user-facing messages for a session — that is, messages
     * with `isFinal = true`. This is the source of truth for the main chat list
     * UI; intermediate node outputs are filtered out and surfaced separately
     * in the agent console.
     *
     * @param sessionId The unique ID of the chat session.
     * @return A [Flow] emitting the list of final [ChatMessage]s ordered by time.
     */
    fun getDisplayMessagesForSession(sessionId: String): Flow<List<ChatMessage>>

    /**
     * Toggles the starred state of a single message. Starred messages are
     * preserved across sessions and accessible via the chat-screen filter.
     *
     * @param messageId The id of the message to update.
     * @param starred The new starred state to persist.
     */
    suspend fun setMessageStarred(messageId: Long, starred: Boolean)

    /**
     * Retrieves all starred messages across every session ordered chronologically
     * (oldest first), matching the main chat list. Backs the chat-screen
     * "starred only" filter; preserving `ASC` order keeps the screen's
     * "scroll-to-last" auto-scroll consistent across filter toggles.
     *
     * @return A [Flow] emitting the current list of starred [ChatMessage]s.
     */
    fun getStarredMessages(): Flow<List<ChatMessage>>

    /**
     * Deletes all messages associated with the given session, and the session itself.
     *
     * @param sessionId The unique ID of the chat session to delete.
     */
    suspend fun deleteSession(sessionId: String)

    /**
     * Retrieves a list of all distinct chat session IDs.
     * Deprecated: Use getSessionsFlow() instead.
     *
     * @return A list of unique session IDs.
     */
    suspend fun getAllSessions(): List<String>

    /**
     * Deletes a specific chat message by its ID.
     *
     * @param messageId The ID of the message to delete.
     */
    suspend fun deleteMessage(messageId: Long)

    /**
     * Retrieves recent system messages (logs/observations).
     *
     * @param limit The maximum number of messages to retrieve.
     * @return A [Flow] emitting a list of recent system [ChatMessage].
     */
    fun getRecentSystemMessages(limit: Int = 100): Flow<List<ChatMessage>>

    /**
     * Creates or updates a chat session.
     *
     * @param session The [ChatSession] to create or update.
     */
    suspend fun saveSession(session: ChatSession)

    /**
     * Renames an existing chat session without rewriting other fields. Faster
     * than reading the row and calling [saveSession] and avoids the race
     * inherent in a read-modify-write performed off the main thread while
     * the orchestrator is concurrently updating `updatedAt`.
     *
     * No-op when no session with [sessionId] exists.
     *
     * @param sessionId The id of the session to rename.
     * @param newName The new display name to persist. Callers are expected to
     *   trim / validate the value upstream.
     */
    suspend fun renameSession(sessionId: String, newName: String)

    /**
     * Toggles the session-level favorite flag persisted on
     * `chat_sessions.isStarred`. Favorited chats sort to the top of the
     * drawer thread list and render a small star indicator.
     *
     * Distinct from [setMessageStarred] which operates on individual messages.
     *
     * @param sessionId The id of the session to update.
     * @param favorite The new favorite flag to persist.
     */
    suspend fun setSessionFavorite(sessionId: String, favorite: Boolean)

    /**
     * Sets the session-level archive flag persisted on
     * `chat_sessions.isArchived`. Archiving hides the conversation from the
     * main thread list without deleting the session, its messages, or its
     * runs; unarchiving restores it unchanged.
     *
     * No-op when no session with [sessionId] exists.
     *
     * Implementations also stamp `chat_sessions.archivedAt` with the current
     * instant when [archived] is `true` and clear it when it is `false`, so the
     * archive surface can order by, and label, *when the user put a chat away*
     * rather than when it was last written to.
     *
     * Prefer the [app.knotwork.android.domain.usecases.ArchiveChatUseCase] /
     * [app.knotwork.android.domain.usecases.UnarchiveChatUseCase] entry points
     * over calling this directly — they carry the id validation and the
     * `Result` boundary the UI needs.
     *
     * @param sessionId The id of the session to update.
     * @param archived The new archive flag to persist.
     */
    suspend fun setSessionArchived(sessionId: String, archived: Boolean)

    /**
     * Imports a chat from a JSON document into a freshly-created session and
     * returns the new session id. The caller is expected to switch the
     * active session to the returned id.
     *
     * Accepted shapes:
     *  - the document produced by an export (`{"sessionName": ..., "messages": [...]}`);
     *  - a bare top-level array of message objects (`[{...}, ...]`).
     *
     * Each message carries `role`, `text` and `timestamp`. The file is someone
     * else's text, so the import does not take it at its word:
     *  - only `USER` and `AGENT` rows are imported (an unknown or blank role reads as
     *    `USER`); `SYSTEM` rows — the source device's notices and tool observations —
     *    are left out;
     *  - every row is marked [app.knotwork.android.domain.models.ChatMessage.imported],
     *    so long-term memory extraction, Retry and model attribution skip it;
     *  - no row is dated after the import: a later file is moved back as a whole,
     *    keeping its order; a missing timestamp reads as the import time;
     *  - the file is read in full before anything is written, and written in one
     *    transaction, so a file that fails stores nothing.
     *
     * @param json The JSON content to import.
     * @return The id of the newly created session.
     * @throws app.knotwork.android.domain.models.ChatImportException If the file is not
     *   a chat this app can read; its message is app-written and quotes nothing from
     *   the file.
     */
    suspend fun importChat(json: String): String

    /**
     * Retrieves chat sessions as a flow, ordered by the last update time.
     *
     * @param includeArchived Whether archived sessions are part of the result.
     *   Defaults to `false`: every list-facing caller (thread list, startup
     *   session restore, dynamic shortcuts) wants the active conversations
     *   only. Pass `true` where an archived session must still be accounted
     *   for — the task monitor does, so a run in flight does not vanish from
     *   it when its chat is archived.
     * @return A [Flow] emitting the list of [ChatSession].
     */
    fun getSessionsFlow(includeArchived: Boolean = false): Flow<List<ChatSession>>

    /**
     * Retrieves **only** the archived chat sessions as a flow, most-recently-
     * archived first — the observable source behind the archive surface.
     *
     * @return A [Flow] emitting the list of archived [ChatSession]s.
     */
    fun getArchivedSessionsFlow(): Flow<List<ChatSession>>

    /**
     * Retrieves a specific chat session by its ID.
     *
     * @param id The ID of the session.
     * @return The [ChatSession] if found, null otherwise.
     */
    suspend fun getSessionById(id: String): ChatSession?

    /**
     * Cheap existence check for a session id, avoiding materialising the row
     * when the caller only needs to know whether it still exists (the
     * reuse-or-recreate decision on the background-run paths).
     *
     * @param id The id of the session.
     * @return `true` when a session with [id] exists.
     */
    suspend fun sessionExists(id: String): Boolean

    /**
     * Returns the cached compressed-history summary for a session, or `null`
     * when none has been computed yet. Read by the engine when a node renders
     * chat history and compression is active.
     *
     * @param sessionId The id of the session.
     * @return The [ChatHistorySummary], or `null` if absent.
     */
    suspend fun getHistorySummary(sessionId: String): ChatHistorySummary?

    /**
     * Inserts or replaces the compressed-history summary for a session (1:1 with
     * the session row). Written by the background
     * [app.knotwork.android.domain.usecases.CompressChatHistoryUseCase].
     *
     * @param summary The summary to persist.
     */
    suspend fun saveHistorySummary(summary: ChatHistorySummary)

    /**
     * Returns the store-relative paths of every image attachment still
     * referenced by a chat message. The orphan-cleanup sweep diffs this against
     * the files present in the attachment store to find files no message points
     * at anymore.
     *
     * @return The referenced attachment paths (no duplicates guaranteed by the
     *   caller's set semantics).
     */
    suspend fun getReferencedAttachmentPaths(): List<String>
}
