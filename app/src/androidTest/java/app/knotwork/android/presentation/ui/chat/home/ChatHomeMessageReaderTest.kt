package app.knotwork.android.presentation.ui.chat.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.knotwork.design.components.chat.CHAT_MESSAGE_READER_LIST_TAG
import app.knotwork.design.components.chat.CHAT_READ_WHOLE_TAG
import app.knotwork.design.components.chat.ChatContent
import app.knotwork.design.components.chat.ChatMetadata
import app.knotwork.design.components.chat.ChatRole
import app.knotwork.design.components.chat.chatMessagePreview
import app.knotwork.design.screens.chat.ChatHomeMessageRow
import org.junit.Rule
import org.junit.Test

/**
 * A long message's *Read whole message* opens the whole message in the reader over the
 * chat, and Back closes it. The answer is plain text, which keeps the test off the
 * Markdown renderer's background parse; the reader opens the same way for both.
 */
class ChatHomeMessageReaderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val answer = (1..40).joinToString("\n\n") { "Paragraph $it. " + "Words to read slowly. ".repeat(13) }

    private val row = ChatHomeMessageRow(
        id = "a-1",
        role = ChatRole.Assistant,
        content = ChatContent.Text(answer, chatMessagePreview(answer, markdown = false)),
        metadata = ChatMetadata(timestamp = "09:14", model = "gemma-3n-E4B"),
    )

    @Test
    fun readWhole_opensTheReader_andBackClosesIt() {
        val (viewModel, _) = mockChatHomeViewModel(initialMessages = listOf(row))
        composeTestRule.setContent { MaterialTheme { ChatHomeScreen(viewModel = viewModel) } }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(CHAT_READ_WHOLE_TAG).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Agent’s message").assertExists()
        composeTestRule.onNodeWithText("09:14 · gemma-3n-E4B", substring = true).assertExists()

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag(CHAT_MESSAGE_READER_LIST_TAG).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Agent’s message").assertCountEquals(0)
    }
}
