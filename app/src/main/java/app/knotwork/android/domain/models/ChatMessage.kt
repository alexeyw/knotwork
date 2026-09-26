package app.knotwork.android.domain.models

/**
 * Domain model representing a single chat message.
 *
 * @property id The unique identifier of the message. Null if not yet saved.
 * @property sessionId The ID of the chat session this message belongs to.
 * @property role The role of the sender (e.g., USER or AGENT).
 * @property content The text content of the message.
 * @property timestamp The time the message was created, in milliseconds since epoch.
 * @property isFinal Whether the message is a user-facing final message (USER input or final
 *   AGENT answer) that should appear in the main chat list. Intermediate node outputs
 *   (tool observations, internal SYSTEM logs) are persisted with `isFinal = false` so they
 *   are kept for the agent console while staying out of the main history.
 *   Defaults to `true` to preserve behavior for legacy save sites.
 * @property isStarred Whether the user has saved (starred) this message. Starred messages
 *   are surfaced via the chat-screen "starred only" filter.
 * @property attachment The image attached to this message, or `null` when the
 *   message has no attachment. Only user messages carry attachments in this phase.
 * @property modelName Display name of the model that generated this message,
 *   captured at generation time so the chat keeps attributing the answer to the
 *   right model even after the user switches the active model. Only AGENT
 *   answers carry it; `null` for user/system messages and for legacy AGENT rows
 *   saved before this was recorded.
 * @property imported Whether the message came from a chat file (*Import chat*) rather
 *   than being written on this device. A file decides a row's role and text, so an
 *   imported USER row is not something this device's user said: long-term memory
 *   extraction never reads imported rows, Retry never re-runs one, and an imported
 *   AGENT row is not attributed to the active model. See [writtenOnThisDevice].
 * @property relayed Whether an AGENT row's text was handed on unchanged rather than
 *   written by a model — an OUTPUT node in echo mode saving a tool's result, or the
 *   user's own words, as the reply. Long-term memory extraction reads such a row no
 *   more than an imported one: it is not something the assistant said. `false` for
 *   every other row, and for rows saved before this was recorded.
 */
data class ChatMessage(
    val id: Long? = null,
    val sessionId: String,
    val role: Role,
    val content: String,
    val timestamp: Long,
    val isFinal: Boolean = true,
    val isStarred: Boolean = false,
    val attachment: MessageAttachment? = null,
    val modelName: String? = null,
    val imported: Boolean = false,
    val relayed: Boolean = false,
) {
    /**
     * `true` for a message this app wrote on this device — typed by the user, produced
     * by a run, or recorded by the app — and `false` for one taken from a chat file.
     * The one predicate consumers use to tell the two apart.
     */
    val writtenOnThisDevice: Boolean get() = !imported
}
