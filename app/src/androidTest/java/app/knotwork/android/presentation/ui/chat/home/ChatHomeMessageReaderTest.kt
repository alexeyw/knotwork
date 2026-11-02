package app.knotwork.android.presentation.ui.chat.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
 *
 * The action is invoked through its click semantics, not a tap. A tap after scrolling it
 * into the list's view brought the keyboard up on CI instead of the reader — most likely
 * the composer, which the list runs under, took it. What is tested here is the wiring,
 * not the gesture: `LongMessagePreviewTest` taps the action itself.
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

        composeTestRule.onNodeWithTag(CHAT_READ_WHOLE_TAG).performSemanticsAction(SemanticsActions.OnClick)
        // The reader is a dialog: its window comes up a frame or more later.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Agent’s message").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithText("Agent’s message").assertExists()
        composeTestRule.onNodeWithText("09:14 · gemma-3n-E4B", substring = true).assertExists()

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag(CHAT_MESSAGE_READER_LIST_TAG).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Agent’s message").assertCountEquals(0)
    }
}
