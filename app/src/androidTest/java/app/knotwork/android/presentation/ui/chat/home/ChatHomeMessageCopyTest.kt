package app.knotwork.android.presentation.ui.chat.home

import android.os.TransactionTooLargeException
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import app.knotwork.android.R
import app.knotwork.design.components.chat.ChatContent
import app.knotwork.design.components.chat.ChatMetadata
import app.knotwork.design.components.chat.ChatRole
import app.knotwork.design.screens.chat.ChatHomeMessageRow
import io.mockk.every
import org.junit.Rule
import org.junit.Test
import app.knotwork.design.R as KnotworkR

/**
 * The chat's *Copy* puts the whole message on the clipboard through `setClipboardText`:
 * no snackbar of the app's own when it fits — Android 14+ shows the system's preview —
 * and *Too long for the clipboard* when the system refuses it.
 *
 * The clipboard is a fake injected through [LocalClipboardManager], as in
 * [ChatHomeConsolePaneTest]; the refusing one throws what
 * `android.content.ClipboardManager.setPrimaryClip` throws for an oversized clip.
 */
@Suppress("DEPRECATION") // ClipboardManager is deprecated in favour of Clipboard; ChatHomeScreen still uses it.
class ChatHomeMessageCopyTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class RecordingClipboardManager : ClipboardManager {
        var lastText: AnnotatedString? = null
            private set

        override fun setText(annotatedString: AnnotatedString) {
            lastText = annotatedString
        }

        override fun getText(): AnnotatedString? = lastText

        override fun hasText(): Boolean = lastText?.text?.isNotEmpty() == true
    }

    private class RefusingClipboardManager : ClipboardManager {
        override fun setText(annotatedString: AnnotatedString): Unit =
            throw RuntimeException(TransactionTooLargeException("data parcel size 2097264 bytes"))

        override fun getText(): AnnotatedString? = null

        override fun hasText(): Boolean = false
    }

    private val answer = ChatHomeMessageRow(
        id = "a-1",
        role = ChatRole.Assistant,
        content = ChatContent.Text("the answer on screen"),
        metadata = ChatMetadata(timestamp = "09:14"),
    )

    @Test
    fun copy_messageThatFits_putsTheWholeMessageOnTheClipboardAndSaysNothing() {
        val clipboard = RecordingClipboardManager()
        copyTheAnswer(clipboard)

        assert(clipboard.lastText?.text == "the whole answer") {
            "Clipboard payload mismatch — got: ${clipboard.lastText?.text}"
        }
        composeTestRule.onAllNodesWithText(tooLong()).assertCountEquals(0)
    }

    @Test
    fun copy_messageTooLargeForTheClipboard_saysNothingWasCopied() {
        copyTheAnswer(RefusingClipboardManager())

        composeTestRule.onNodeWithText(tooLong()).assertExists()
    }

    private fun copyTheAnswer(clipboard: ClipboardManager) {
        val (viewModel, handles) = mockChatHomeViewModel(initialMessages = listOf(answer))
        every { handles.transfer.textForRow("a-1") } returns "the whole answer"
        composeTestRule.setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                MaterialTheme { ChatHomeScreen(viewModel = viewModel) }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("the answer on screen").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        val copy = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(KnotworkR.string.knotwork_chat_message_action_copy)
        composeTestRule.onAllNodesWithText(copy)[0].performClick()
        composeTestRule.waitForIdle()
    }

    private fun tooLong(): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_snackbar_copy_too_large)
}
