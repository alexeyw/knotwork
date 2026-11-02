package app.knotwork.design.components.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.R
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A touch reaches the lower part of a chat bubble taller than 8 192 px.
 *
 * The bubble used to clip itself with a clipping layer, which tests a touch against the
 * clip's outline. An outline with unequal corners — the agent's bubble — is tested as a
 * path, by intersecting it with a tiny square around the touch, and that intersection
 * comes back empty 8 192 px and more below the top: *Read whole message* under a long
 * answer, its links and its long-press menu did nothing on the phone.
 *
 * Native graphics, because the intersection is Skia's; and each bubble's body is
 * [TALL_PX] pixels tall, set in pixels, because text drawn on the JVM never makes a
 * bubble that tall.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xxhdpi")
class ChatBubbleTallTapTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Long enough for a preview: the bubble draws *Read whole message* under its body. */
    private val answer = (1..40).joinToString("\n\n") { "Paragraph $it. " + "Words to read slowly. ".repeat(13) }

    private fun show(role: ChatRole, onReadWhole: () -> Unit = {}) {
        composeTestRule.setContent {
            KnotworkTheme {
                val tall = with(LocalDensity.current) { TALL_PX.toDp() }
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    ChatMessage(
                        role = role,
                        content = ChatContent.Markdown(answer, chatMessagePreview(answer, markdown = true)),
                        metadata = ChatMetadata(timestamp = "09:14"),
                        onContextAction = {},
                        markdownRenderer = { Box(modifier = Modifier.fillMaxWidth().height(tall)) },
                        onReadWholeMessage = onReadWhole,
                    )
                }
            }
        }
        composeTestRule.onNodeWithTag(CHAT_READ_WHOLE_TAG).performScrollTo()
    }

    @Test
    fun `given an agent bubble taller than 8 192 px when its action is tapped then the reader is asked for`() {
        var opened = 0
        show(ChatRole.Assistant) { opened++ }

        composeTestRule.onNodeWithTag(CHAT_READ_WHOLE_TAG).performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, opened)
    }

    @Test
    fun `given a user bubble taller than 8 192 px when its action is tapped then the reader is asked for`() {
        var opened = 0
        show(ChatRole.User) { opened++ }

        composeTestRule.onNodeWithTag(CHAT_READ_WHOLE_TAG).performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, opened)
    }

    @Test
    fun `given an agent bubble taller than 8 192 px when pressed low in its body then its menu opens`() {
        show(ChatRole.Assistant)

        // Just above the action: the bubble's body, more than 8 192 px below its top.
        composeTestRule.onNodeWithTag(CHAT_READ_WHOLE_TAG).performTouchInput {
            longClick(Offset(centerX, -LONG_PRESS_ABOVE_ACTION_PX))
        }
        composeTestRule.waitForIdle()

        val copy = RuntimeEnvironment.getApplication().getString(R.string.knotwork_chat_message_action_copy)
        assertEquals(1, composeTestRule.onAllNodesWithText(copy).fetchSemanticsNodes().size)
    }

    private companion object {
        /** The body's height: past the 8 192 px a clipping layer could take a touch down to. */
        const val TALL_PX = 9_000

        /** How far above the action the long press lands, inside the body. */
        const val LONG_PRESS_ABOVE_ACTION_PX = 60f
    }
}
