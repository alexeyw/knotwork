package app.knotwork.android.data.repositories

import app.knotwork.android.data.local.dao.ChatDao
import app.knotwork.android.data.local.dao.ChatHistorySummaryDao
import app.knotwork.android.data.local.models.ChatHistorySummaryEntity
import app.knotwork.android.data.local.models.ChatSessionEntity
import app.knotwork.android.data.mappers.toDomain
import app.knotwork.android.data.mappers.toEntity
import app.knotwork.android.domain.models.ChatHistorySummary
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.ChatSession
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.services.AttachmentStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [ChatRepository] that uses a local Room database via [ChatDao].
 *
 * Caches the most recently seen [cachedSessionId] to avoid an N+1 SELECT pattern during
 * token streaming: once a session is confirmed to exist, subsequent [saveMessage] calls for
 * the same session skip the [ChatDao.getSessionById] round-trip and update only the timestamp.
 *
 * @property chatDao The Data Access Object for chat messages.
 * @property chatHistorySummaryDao DAO for the per-session compressed-history
 *   summaries used by the long-session chat-compression feature.
 * @property attachmentStore Store for image attachment files, used to delete
 *   the on-disk image when its owning message or session is removed. File
 *   deletion is best-effort: a failure is tolerated because the orphan-cleanup
 *   sweep reclaims any leftover file later.
 */
@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val chatDao: ChatDao,
    private val chatHistorySummaryDao: ChatHistorySummaryDao,
    private val attachmentStore: AttachmentStore,
) : ChatRepository {

    @Volatile
    private var cachedSessionId: String? = null

    override suspend fun saveMessage(message: ChatMessage) {
        chatDao.insertMessage(message.toEntity())

        if (cachedSessionId == message.sessionId) {
            chatDao.updateSessionTimestamp(message.sessionId, message.timestamp)
        } else {
            // Only the timestamp, never the whole row: a read-modify-write of the full
            // session would put back whatever the read saw, undoing a rename or a
            // favourite written between the two — which is exactly what the first
            // message of a new chat races against (it is auto-renamed from that text).
            if (chatDao.getSessionById(message.sessionId) != null) {
                chatDao.updateSessionTimestamp(message.sessionId, message.timestamp)
            } else {
                chatDao.insertSession(
                    ChatSessionEntity(
                        id = message.sessionId,
                        name = "Chat " + message.sessionId.take(NEW_SESSION_NAME_SUFFIX_LENGTH),
                        updatedAt = message.timestamp,
                    ),
                )
            }
            cachedSessionId = message.sessionId
        }
    }

    override fun getMessagesForSession(sessionId: String): Flow<List<ChatMessage>> =
        chatDao.getMessagesBySessionId(sessionId).map { entities ->
            entities.map { it.toDomain() }
        }

    override fun getDisplayMessagesForSession(sessionId: String): Flow<List<ChatMessage>> =
        chatDao.getDisplayMessagesBySessionId(sessionId).map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun setMessageStarred(messageId: Long, starred: Boolean) {
        chatDao.setMessageStarred(messageId, starred)
    }

    override fun getStarredMessages(): Flow<List<ChatMessage>> = chatDao.getStarredMessages().map { entities ->
        entities.map { it.toDomain() }
    }

    override suspend fun deleteSession(sessionId: String) {
        // Collect attachment paths before the rows are gone, then delete the
        // session atomically (messages + pipeline-run records + session row;
        // trace steps cascade via FK — a crash mid-delete can never leave a
        // half-deleted session behind), then clean up the image files.
        val attachmentPaths = chatDao.getAttachmentPathsForSession(sessionId)
        chatDao.deleteSessionCompletely(sessionId)
        attachmentPaths.forEach { attachmentStore.delete(it) }
    }

    override suspend fun getAllSessions(): List<String> = chatDao.getAllSessions()

    override suspend fun deleteMessage(messageId: Long) {
        // Read the attachment path before deleting the row so the on-disk image
        // can be removed alongside it.
        val attachmentPath = chatDao.getAttachmentPathById(messageId)
        chatDao.deleteMessageById(messageId)
        attachmentPath?.let { attachmentStore.delete(it) }
    }

    override fun getRecentSystemMessages(limit: Int): Flow<List<ChatMessage>> =
        chatDao.getRecentMessagesByRole("SYSTEM", limit).map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun saveSession(session: ChatSession) {
        // Single round-trip via Room's @Upsert. The DAO conflicts on primary key (`id`).
        chatDao.upsertSession(session.toEntity())
    }

    override suspend fun renameSession(sessionId: String, newName: String) {
        chatDao.renameSession(sessionId, newName)
    }

    override suspend fun setSessionFavorite(sessionId: String, favorite: Boolean) {
        chatDao.setSessionStarred(sessionId, favorite)
    }

    override suspend fun setSessionArchived(sessionId: String, archived: Boolean) {
        // The archive instant is stamped here rather than taken from the caller:
        // it is a wall-clock fact about persistence, and the domain use cases
        // ask for a *state* ("this chat is archived"), not for a transition at a
        // particular time. Restoring clears it, so `isArchived` and `archivedAt`
        // are written together and can never disagree.
        chatDao.setSessionArchived(
            sessionId = sessionId,
            archived = archived,
            archivedAt = if (archived) System.currentTimeMillis() else null,
        )
    }

    override suspend fun importChat(json: String): String {
        val trimmed = json.trim()
        var sessionName = DEFAULT_IMPORTED_CHAT_NAME
        val messagesArray: JSONArray = when {
            trimmed.startsWith("{") -> {
                val root = JSONObject(trimmed)
                sessionName = root.optString("sessionName").takeIf { it.isNotBlank() } ?: sessionName
                root.optJSONArray("messages") ?: throw JSONException("Missing 'messages' array")
            }
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> throw JSONException("Unsupported JSON root")
        }

        val newId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        chatDao.upsertSession(
            ChatSessionEntity(id = newId, name = sessionName, updatedAt = now),
        )
        for (i in 0 until messagesArray.length()) {
            val item = messagesArray.getJSONObject(i)
            val roleStr = item.optString("role").ifBlank { Role.USER.name }
            val role = runCatching { Role.valueOf(roleStr) }.getOrDefault(Role.USER)
            val text = item.optString("text")
            val timestamp = item.optLong("timestamp", now)
            chatDao.insertMessage(
                ChatMessage(
                    sessionId = newId,
                    role = role,
                    content = text,
                    timestamp = timestamp,
                ).toEntity(),
            )
        }
        return newId
    }

    override fun getSessionsFlow(includeArchived: Boolean): Flow<List<ChatSession>> =
        chatDao.getSessionsFlow(includeArchived).map { entities ->
            entities.map { it.toDomain() }
        }

    override fun getArchivedSessionsFlow(): Flow<List<ChatSession>> =
        chatDao.getArchivedSessionsFlow().map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun getSessionById(id: String): ChatSession? = chatDao.getSessionById(id)?.toDomain()

    override suspend fun sessionExists(id: String): Boolean = chatDao.sessionExists(id)

    override suspend fun getHistorySummary(sessionId: String): ChatHistorySummary? =
        chatHistorySummaryDao.getForSession(sessionId)?.let { entity ->
            ChatHistorySummary(
                sessionId = entity.sessionId,
                summary = entity.summary,
                coveredMessageCount = entity.coveredMessageCount,
                updatedAt = entity.updatedAt,
            )
        }

    override suspend fun saveHistorySummary(summary: ChatHistorySummary) {
        chatHistorySummaryDao.upsert(
            ChatHistorySummaryEntity(
                sessionId = summary.sessionId,
                summary = summary.summary,
                coveredMessageCount = summary.coveredMessageCount,
                updatedAt = summary.updatedAt,
            ),
        )
    }

    override suspend fun getReferencedAttachmentPaths(): List<String> = chatDao.getAllAttachmentPaths()

    private companion object {
        /**
         * Number of leading characters of a session UUID embedded into the auto-generated
         * display name (e.g. `Chat 5b7a2c`) shown when the user has not renamed the chat.
         */
        const val NEW_SESSION_NAME_SUFFIX_LENGTH: Int = 6

        /**
         * Default name assigned to a chat session created via [importChat] when the
         * incoming document carries no `sessionName` field. Mirrors the legacy
         * `ChatViewModel.DEFAULT_IMPORTED_CHAT_NAME` so behaviour is preserved.
         */
        const val DEFAULT_IMPORTED_CHAT_NAME: String = "Imported Chat"
    }
}
