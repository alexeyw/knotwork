package app.knotwork.android.presentation.ui.chat.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import app.knotwork.design.components.chat.CHAT_MESSAGE_READER_LIST_TAG
import app.knotwork.design.components.chat.CHAT_READ_WHOLE_TAG
import app.knotwork.design.components.chat.ChatContent
import app.knotwork.design.components.chat.ChatMetadata
import app.knotwork.design.components.chat.ChatRole
import app.knotwork.design.components.chat.chatMessagePreview
import app.knotwork.design.screens.chat.ChatHomeMessageRow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A tap on a long answer's *Read whole message* opens the whole message in the reader
 * over the chat, and Back closes it.
 *
 * The answer is the thread's last message and Markdown, as the agent's answers are, and
 * the action is tapped, not invoked: the chat is scrolled to its end first, as a reader
 * would, since a node scrolled just inside the list's viewport can sit under the composer,
 * which the list runs under. `ChatBubbleTallTapTest` covers a bubble taller than this
 * device may draw this one.
 */
class ChatHomeMessageReaderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val answer = buildString {
        for (section in 1..14) {
            append("## $section. Section $section\n\n")
            append("An introduction with **bold** text and `inline code`.\n\n")
            for (item in 1..10) append("- Item $item: check `path/$item` before the next step\n")
            append("\n")
        }
        append("```bash\n")
        for (line in 1..40) append("echo \"Step $line\"\n")
        append("```\n")
    }

    private val row = ChatHomeMessageRow(
        id = "a-1",
        role = ChatRole.Assistant,
        content = ChatContent.Markdown(answer, chatMessagePreview(answer, markdown = true)),
        metadata = ChatMetadata(timestamp = "09:14", model = "gemma-3n-E4B"),
    )

    /**
     * Swipes the chat up until it cannot scroll further. Each swipe starts well above the
     * list's bottom edge: the list runs under the composer, and a swipe begun there is the
     * composer's.
     */
    private fun scrollChatToEnd() {
        val list = composeTestRule.onAllNodes(hasScrollToIndexAction())[0]
        fun atEnd(): Boolean {
            val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            return range.value() >= range.maxValue()
        }
        repeat(MAX_SWIPES) {
            if (atEnd()) return
            list.performTouchInput { swipeUp(startY = height * SWIPE_START, endY = top) }
            composeTestRule.waitForIdle()
        }
        assertTrue("the chat did not reach its end in $MAX_SWIPES swipes", atEnd())
    }

    @Test
    fun readWhole_opensTheReader_andBackClosesIt() {
        val (viewModel, _) = mockChatHomeViewModel(initialMessages = listOf(row))
        composeTestRule.setContent { MaterialTheme { ChatHomeScreen(viewModel = viewModel) } }
        // The bubble's Markdown is parsed off the main thread; until it is drawn the bubble
        // is short, and scrolling to the end of it would stop short of the action.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Section 1", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitForIdle()
        scrollChatToEnd()

        composeTestRule.onNodeWithTag(CHAT_READ_WHOLE_TAG).performClick()
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

    private companion object {
        /** More swipes than a long answer needs to reach the end of the chat. */
        const val MAX_SWIPES = 10

        /** Where a swipe starts, as a share of the list's height from its top: above the composer. */
        const val SWIPE_START = 0.6f
    }
}
