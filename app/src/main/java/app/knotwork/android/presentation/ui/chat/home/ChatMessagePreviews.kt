package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.Role
import app.knotwork.design.components.chat.CHAT_PREVIEW_THRESHOLD
import app.knotwork.design.components.chat.ChatMessagePreview
import app.knotwork.design.components.chat.chatMessagePreview
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The bubble previews of a thread's long messages, each cut once.
 *
 * A message longer than [CHAT_PREVIEW_THRESHOLD] characters is shown in its bubble as
 * a [ChatMessagePreview]. Cutting a Markdown message parses its beginning, so the cut
 * runs on [dispatcher], off the main thread, and only for a message it has not cut
 * before; a thread of short messages never leaves the caller's thread. A preview is
 * kept while its message's text is unchanged.
 *
 * Not thread-safe: one collector of one thread's messages calls [of], one call at a
 * time.
 *
 * @param dispatcher where a cut runs, read on every call so a test can swap it.
 */
internal class ChatMessagePreviews(private val dispatcher: () -> CoroutineDispatcher) {

    /** A message's text and the preview cut from it; `null` when it is shown whole. */
    private class Cut(val text: String, val preview: ChatMessagePreview?)

    private var cuts = HashMap<Long, Cut>()

    /**
     * The previews of [messages], by message id: cut now for a message that is new or
     * has new text, kept from the last call for the rest. A message without a preview
     * — short, the user's message with an image, a system notice, or one not stored
     * yet — has no entry. Previews of messages no longer in [messages] are dropped.
     *
     * @param messages the thread's messages, as the repository emitted them.
     * @return each long message's preview, keyed by its id.
     */
    suspend fun of(messages: List<ChatMessage>): Map<Long, ChatMessagePreview> {
        val kept = HashMap<Long, Cut>()
        val fresh = ArrayList<ChatMessage>()
        for (message in messages) {
            val id = message.id ?: continue
            if (!message.mayHavePreview()) continue
            val cut = cuts[id]
            if (cut != null && cut.text == message.content) kept[id] = cut else fresh += message
        }
        if (fresh.isNotEmpty()) {
            withContext(dispatcher()) {
                for (message in fresh) {
                    kept[checkNotNull(message.id)] = Cut(message.content, previewOf(message))
                }
            }
        }
        cuts = kept
        return buildMap { kept.forEach { (id, cut) -> cut.preview?.let { put(id, it) } } }
    }

    companion object {
        /**
         * The preview of one message, cut on the caller's thread: Markdown for the
         * agent's message, plain text for the user's. Used for a message just sent,
         * which is plain text, whose cut reads no further than the preview.
         *
         * @param message the message.
         * @return its preview, or `null` when its bubble shows it whole.
         */
        fun previewOf(message: ChatMessage): ChatMessagePreview? = if (message.mayHavePreview()) {
            chatMessagePreview(
                message.content,
                markdown = message.role == Role.AGENT,
            )
        } else {
            null
        }

        /**
         * Whether [this] is drawn as a text or Markdown bubble long enough to be cut:
         * not a system notice, not the user's image with its caption.
         */
        private fun ChatMessage.mayHavePreview(): Boolean =
            content.length > CHAT_PREVIEW_THRESHOLD && role != Role.SYSTEM && attachment == null
    }
}
