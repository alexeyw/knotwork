package app.knotwork.design.components.chat

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.components.UNBROKEN_RUN_MAX
import app.knotwork.design.components.knotworkMarkdownColor
import app.knotwork.design.components.knotworkMarkdownComponents
import app.knotwork.design.components.knotworkMarkdownTypography
import app.knotwork.design.theme.KnotworkTheme
import com.mikepenz.markdown.m3.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A chat bubble never hands one text a run without whitespace longer than
 * [UNBROKEN_RUN_MAX]: laid out whole, 200 000 characters of minified JSON froze the chat
 * for seconds in one frame. Ordinary text stays in one text.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ChatMessageLongTextTest {

    @get:Rule
    val rule = createComposeRule()

    /** Minified JSON as an API returns it: no spaces, no newlines. */
    private fun minifiedJson(length: Int): String {
        val sb = StringBuilder("[")
        var i = 0
        while (sb.length < length) {
            sb.append("{\"id\":$i,\"name\":\"item$i\",\"url\":\"https://example.org/a/b?q=$i\"},")
            i++
        }
        return sb.substring(0, length)
    }

    @Test
    fun `given an agent answer of 200 000 characters of JSON when shown then no text holds more than 2 000 of it`() {
        val json = minifiedJson(200_000)
        show(ChatRole.Assistant, ChatContent.Markdown(json), marker = json.take(40))

        val texts = drawnTexts()
        assertTrue(
            "the longest text in the bubble has ${texts.maxOf { it.length }} characters",
            texts.all { it.length <= UNBROKEN_RUN_MAX },
        )
        assertEquals(json.length, texts.sumOf { it.length })
    }

    @Test
    fun `given an agent answer of 50 000 characters of prose when shown then it stays one text`() {
        val prose = "the model reads the context and answers with a plan ".repeat(1_000)
        show(ChatRole.Assistant, ChatContent.Markdown(prose), marker = "the model reads")

        assertEquals(listOf(prose.length), drawnTexts().map { it.length })
    }

    @Test
    fun `given a code block with a 5 000-character line when shown then the line continues on new lines`() {
        val line = "x".repeat(5_000)
        show(ChatRole.Assistant, ChatContent.Markdown("```\n$line\n```"), marker = "xxxx")

        val code = drawnTexts().single()
        assertEquals(listOf(2_000, 2_000, 1_000), code.split('\n').map { it.length })
    }

    @Test
    fun `given a user message with a 5 000-character run when shown then it is drawn as three texts`() {
        val run = "y".repeat(5_000)
        show(ChatRole.User, ChatContent.Text("look: $run"), marker = "look:")

        assertEquals(listOf(2_006, 2_000, 1_000), drawnTexts().map { it.length })
    }

    private fun show(role: ChatRole, content: ChatContent, marker: String) {
        rule.setContent {
            KnotworkTheme {
                ChatMessage(
                    role = role,
                    content = content,
                    metadata = ChatMetadata(timestamp = "09:14"),
                    markdownRenderer = { source ->
                        Markdown(
                            content = source,
                            typography = knotworkMarkdownTypography(),
                            colors = knotworkMarkdownColor(),
                            components = knotworkMarkdownComponents(),
                        )
                    },
                )
            }
        }
        // The Markdown renderer parses off the main thread; wait for the text to arrive.
        rule.waitUntil(timeoutMillis = 60_000) {
            rule.onAllNodes(hasText(marker, substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    /** Every text the bubble draws, the footer's timestamp left out. */
    private fun drawnTexts(): List<String> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }
            .filter { it != "09:14" }
}
