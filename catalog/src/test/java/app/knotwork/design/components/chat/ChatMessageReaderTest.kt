package app.knotwork.design.components.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.components.UNBROKEN_RUN_MAX
import app.knotwork.design.theme.KnotworkTheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

/** What the chat's message reader shows and does while a message is prepared and once it is. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ChatMessageReaderTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * Runs the reader's steps at once on the caller's thread, except those whose number
     * (from 1, in the order they are handed over) is in [held]; [release] runs those.
     */
    private class HeldSteps(private vararg val held: Int) : CoroutineDispatcher() {
        private var step = 0
        private val waiting = mutableListOf<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            step++
            if (step in held) waiting += block else block.run()
        }

        fun release() {
            val blocks = waiting.toList()
            waiting.clear()
            blocks.forEach { it.run() }
        }
    }

    private fun show(
        text: String,
        role: ChatRole = ChatRole.Assistant,
        markdown: Boolean = true,
        subtitle: String = "09:14 · gemma-3n",
        onClose: () -> Unit = {},
        onCopy: () -> Boolean = { true },
        work: CoroutineDispatcher = Dispatchers.Default,
    ) {
        rule.setContent {
            KnotworkTheme {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true),
                    LocalChatReaderWork provides work,
                ) {
                    ChatMessageReader(
                        state = ChatMessageReaderState(role, subtitle, text, markdown),
                        onClose = onClose,
                        onCopy = onCopy,
                    )
                }
            }
        }
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText(text, substring = substring), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun drawnTexts(): List<String> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }

    /**
     * Four hundred short paragraphs: about twice the head the reader prepares first, and
     * both the head and the whole far taller than the screen, whether or not the test's
     * graphics wrap a line.
     */
    private val longMessage = (1..400).joinToString("\n\n") { "Paragraph $it. Words to read slowly." }

    @Test
    fun `given an agent's Markdown message when shown then the bar names it and its blocks are drawn`() {
        show("# Plan\n\nFirst **step**.\n\n- one\n- two")

        waitForText("First step.")
        rule.onNodeWithText("Agent’s message").assertExists()
        rule.onNodeWithText("09:14 · gemma-3n").assertExists()
        rule.onAllNodesWithText("Plan", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `given the user's own message when shown then it is titled so and drawn as plain text`() {
        show("line one\n**not bold**", role = ChatRole.User, markdown = false, subtitle = "09:12")

        waitForText("**not bold**")
        rule.onNodeWithText("Your message").assertExists()
        rule.onNodeWithText("line one", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `given a message that fits the clipboard when Copy is tapped then it is copied and nothing is said`() {
        var copies = 0
        show("Short answer.", onCopy = {
            copies++
            true
        })

        rule.onNodeWithContentDescription("Copy").performClick()
        rule.waitForIdle()

        assertEquals(1, copies)
        rule.onAllNodesWithText("Too long for the clipboard. Nothing was copied.").assertCountEquals(0)
    }

    @Test
    fun `given a message too large for the clipboard when Copy is tapped then the reader says nothing was copied`() {
        show("Short answer.", onCopy = { false })

        rule.onNodeWithContentDescription("Copy").performClick()

        rule.onNodeWithText("Too long for the clipboard. Nothing was copied.").assertExists()
    }

    @Test
    fun `given the reader when Back is tapped then the host hears it once the closing motion ends`() {
        var closed = 0
        show("Short answer.", onClose = { closed++ })

        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()

        assertEquals(1, closed)
    }

    @Test
    fun `given 200 000 characters of JSON when shown then only the pieces on screen are drawn, none over 2 000`() {
        val json = "{\"id\":1,\"name\":\"item\",\"url\":\"https://example.org/a/b?q=1\"},".repeat(3_400).take(200_000)
        show(json)

        waitForText(json.take(40), substring = true)

        val pieces = drawnTexts().filter { it.length > 100 && json.contains(it) }
        val longest = drawnTexts().maxOf { it.length }
        assertTrue("the longest text in the reader has $longest characters", longest <= UNBROKEN_RUN_MAX)
        // The paragraph is 100 pieces. Drawn as one item, all of them would be laid out at
        // once; as items, only those on or near the screen are.
        val all = json.length / UNBROKEN_RUN_MAX
        assertTrue("${pieces.size} of the paragraph's $all pieces are drawn", pieces.size < all)
    }

    @Test
    fun `given a code block of 100 lines when shown then its first item holds 40 of them`() {
        show("```\n" + (1..100).joinToString("\n") { "line $it" } + "\n```")

        waitForText("line 1\n", substring = true)

        val first = drawnTexts().single { it.startsWith("line 1\n") }
        assertEquals((1..40).map { "line $it" }, first.lines())
    }

    @Test
    fun `given a plain line with a 5 000-character run when shown then it is drawn in pieces`() {
        show("look: " + "y".repeat(5_000), role = ChatRole.User, markdown = false)

        waitForText("look:", substring = true)

        assertEquals(
            listOf(2_006, 2_000, 1_000),
            drawnTexts().filter {
                it.startsWith("look") || it.startsWith("y")
            }.map { it.length },
        )
    }

    @Test
    fun `given nothing ready yet when 300 ms pass then the reader says it is preparing the rest, not before`() {
        rule.mainClock.autoAdvance = false
        show(longMessage, work = HeldSteps(1))

        rule.mainClock.advanceTimeBy(250)
        rule.onAllNodesWithText("Preparing the rest…", useUnmergedTree = true).assertCountEquals(0)

        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("Preparing the rest…", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `given a long message when only its head is ready then the head is drawn and its end says so`() {
        // The steps in order: the head's parse, the whole's parse (held), the head's items.
        show(longMessage, work = HeldSteps(2))

        waitForText("Paragraph 1.", substring = true)
        rule.onNodeWithTag(CHAT_MESSAGE_READER_LIST_TAG).performScrollToKey("preparing")
        rule.mainClock.advanceTimeBy(400)

        rule.onNodeWithText("Preparing the rest…", useUnmergedTree = true).assertExists()
        rule.onAllNodes(hasText("Paragraph 400.", substring = true), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `given a long message when the rest arrives then the list stays where the reader is`() {
        val work = HeldSteps(2)
        show(longMessage, work = work)
        waitForText("Paragraph 1.", substring = true)
        val hundredth = longMessage.indexOf("Paragraph 100.")
        rule.onNodeWithTag(CHAT_MESSAGE_READER_LIST_TAG).performScrollToKey("b$hundredth")
        rule.waitForIdle()
        val before = rule.onNode(hasText("Paragraph 100.", substring = true), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("the list has scrolled", before.top < 200f)
        rule.onAllNodes(hasText("Paragraph 1.", substring = true), useUnmergedTree = true).assertCountEquals(0)

        val last = "b${longMessage.indexOf("Paragraph 400.")}"
        assertEquals(-1, indexOfKey(last))

        rule.runOnUiThread { work.release() }
        rule.waitUntil(timeoutMillis = 5_000) { indexOfKey(last) >= 0 }

        val after = rule.onNode(hasText("Paragraph 100.", substring = true), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        rule.onNodeWithTag(CHAT_MESSAGE_READER_LIST_TAG).performScrollToKey(last)
        rule.onNode(hasText("Paragraph 400.", substring = true), useUnmergedTree = true).assertExists()
    }

    /** Where the reader's list holds the item with [key]; -1 while it holds none. */
    private fun indexOfKey(key: String): Int = rule.onNodeWithTag(
        CHAT_MESSAGE_READER_LIST_TAG,
    ).fetchSemanticsNode().config[SemanticsProperties.IndexForKey](key)
}
