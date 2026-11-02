package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.design.components.chat.ChatContent
import app.knotwork.design.components.chat.ChatMessageReaderState
import app.knotwork.design.components.chat.ChatMetadata
import app.knotwork.design.components.chat.ChatRole
import app.knotwork.design.components.chat.ToolCallStatus
import app.knotwork.design.screens.chat.ChatHomeMessageRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the message reader shows for a chat row. */
class MessageReaderStateTest {

    @Test
    fun `given the agent's Markdown answer when read then it is Markdown under its time and model`() {
        val row = ChatHomeMessageRow(
            id = "a-2",
            role = ChatRole.Assistant,
            content = ChatContent.Markdown("# Plan"),
            metadata = ChatMetadata(timestamp = "09:14", model = "gemma-3n-E4B"),
        )

        assertEquals(
            ChatMessageReaderState(ChatRole.Assistant, "09:14 · gemma-3n-E4B", "# Plan", markdown = true),
            messageReaderState(row),
        )
    }

    @Test
    fun `given the user's text when read then it is plain text under its time alone`() {
        val row = ChatHomeMessageRow(
            id = "u-1",
            role = ChatRole.User,
            content = ChatContent.Text("# not a heading"),
            metadata = ChatMetadata(timestamp = "09:12"),
        )

        assertEquals(
            ChatMessageReaderState(ChatRole.User, "09:12", "# not a heading", markdown = false),
            messageReaderState(row),
        )
    }

    @Test
    fun `given a row with no text when read then there is nothing to show`() {
        val row = ChatHomeMessageRow(
            id = "t-3",
            role = ChatRole.Tool,
            content = ChatContent.ToolCall("fs.read", "{}", null, ToolCallStatus.Running),
            metadata = ChatMetadata(timestamp = "09:13"),
        )

        assertNull(messageReaderState(row))
    }
}
