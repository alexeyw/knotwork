package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.design.components.chat.ChatContent
import app.knotwork.design.components.chat.ChatMessageReaderState
import app.knotwork.design.screens.chat.ChatHomeMessageRow

/** Joins the parts of the reader's subtitle, as the bubble's footer joins its time and model. */
private const val SUBTITLE_SEPARATOR = " · "

/**
 * What the message reader shows for [row]: the whole message, Markdown when its bubble
 * draws Markdown, under the message's time and — for the agent's message, which names
 * it — its model. `null` for a row that holds no text, such as a tool call or an image.
 *
 * @param row the chat row the reader was opened on.
 * @return the reader's state, or `null` when the row has no text to read.
 */
internal fun messageReaderState(row: ChatHomeMessageRow): ChatMessageReaderState? {
    val (text, markdown) = when (val content = row.content) {
        is ChatContent.Text -> content.text to false
        is ChatContent.Markdown -> content.source to true
        else -> return null
    }
    val subtitle = listOfNotNull(row.metadata.timestamp, row.metadata.model).joinToString(SUBTITLE_SEPARATOR)
    return ChatMessageReaderState(role = row.role, subtitle = subtitle, text = text, markdown = markdown)
}
