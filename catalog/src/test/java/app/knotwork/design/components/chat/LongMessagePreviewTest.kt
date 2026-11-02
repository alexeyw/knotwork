package app.knotwork.design.components.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
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

/** A long message's bubble: its preview, the fade, and *Read whole message*, which opens the reader. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class LongMessagePreviewTest {

    @get:Rule
    val rule = createComposeRule()

    /** Prose paragraphs well past the threshold; the preview ends on the 13th. */
    private val answer = (1..40).joinToString("\n\n") { "Paragraph $it. " + "Words to read slowly. ".repeat(13) }

    private fun show(content: ChatContent, role: ChatRole = ChatRole.Assistant, onReadWhole: () -> Unit = {}) {
        rule.setContent {
            KnotworkTheme {
                // A preview is taller than the test's screen: the chat scrolls to its action.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ChatMessage(
                        role = role,
                        content = content,
                        metadata = ChatMetadata(timestamp = "09:14"),
                        markdownRenderer = { source -> Renderer(source) },
                        onReadWholeMessage = onReadWhole,
                    )
                }
            }
        }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText("Paragraph 1.", substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @androidx.compose.runtime.Composable
    private fun Renderer(source: String) {
        Markdown(
            content = source,
            typography = knotworkMarkdownTypography(),
            colors = knotworkMarkdownColor(),
            components = knotworkMarkdownComponents(),
        )
    }

    private fun drawnTexts(): List<String> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }

    @Test
    fun `given an agent's long answer when shown then the bubble draws only its preview`() {
        val preview = checkNotNull(chatMessagePreview(answer, markdown = true))
        show(ChatContent.Markdown(answer, preview))

        val texts = drawnTexts()
        assertTrue(texts.any { it.startsWith("Paragraph 13.") })
        assertTrue(texts.none { it.startsWith("Paragraph 14.") })
    }

    @Test
    fun `given a preview when shown then Read whole message and the share are one button`() {
        val preview = checkNotNull(chatMessagePreview(answer, markdown = true))
        show(ChatContent.Markdown(answer, preview))

        val action = rule.onNodeWithTag(CHAT_READ_WHOLE_TAG).fetchSemanticsNode()
        assertEquals(Role.Button, action.config[SemanticsProperties.Role])
        val label = action.config[SemanticsProperties.Text].map { it.text }
        assertEquals(listOf("Read whole message", "${preview.shownPercent}% shown"), label)
    }

    @Test
    fun `given a preview when Read whole message is tapped then the host is asked to open the message`() {
        var opened = 0
        show(ChatContent.Markdown(answer, checkNotNull(chatMessagePreview(answer, markdown = true))), onReadWhole = {
            opened++
        })

        rule.onNodeWithTag(CHAT_READ_WHOLE_TAG).performScrollTo().performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `given the user's own long message when shown then it gets the same preview and action`() {
        val pasted = (1..400).joinToString("\n") { "Paragraph $it. a line of the pasted log" }
        show(ChatContent.Text(pasted, chatMessagePreview(pasted, markdown = false)), role = ChatRole.User)

        rule.onNodeWithTag(CHAT_READ_WHOLE_TAG).assertExists()
        assertTrue(drawnTexts().none { it.contains("Paragraph 400.") })
    }

    @Test
    fun `given a preview under 1 percent when shown then it says less than 1 percent shown`() {
        val json = "Paragraph 1. " + "{\"id\":1}".repeat(100_000)
        show(ChatContent.Markdown(json, checkNotNull(chatMessagePreview(json, markdown = true))))

        val label = rule.onNodeWithTag(CHAT_READ_WHOLE_TAG).fetchSemanticsNode().config[SemanticsProperties.Text]
        assertEquals("Less than 1% shown", label.last().text)
    }

    @Test
    fun `given a message without a preview when shown then there is no action`() {
        show(ChatContent.Markdown("Paragraph 1. A short answer."))

        rule.onAllNodesWithTag(CHAT_READ_WHOLE_TAG).assertCountEquals(0)
    }

    @Test
    fun `given the reader opened from the action when it is closed then focus is back on the action`() {
        val preview = checkNotNull(chatMessagePreview(answer, markdown = true))
        rule.setContent {
            KnotworkTheme {
                CompositionLocalProvider(LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true)) {
                    var open by remember { mutableStateOf(false) }
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ChatMessage(
                            role = ChatRole.Assistant,
                            content = ChatContent.Markdown(answer, preview),
                            metadata = ChatMetadata(timestamp = "09:14"),
                            markdownRenderer = { source -> Renderer(source) },
                            onReadWholeMessage = { open = true },
                        )
                    }
                    if (open) {
                        ChatMessageReader(
                            state = ChatMessageReaderState(ChatRole.Assistant, "09:14", answer, markdown = true),
                            onClose = { open = false },
                            onCopy = { true },
                        )
                    }
                }
            }
        }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithTag(CHAT_READ_WHOLE_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        val action = rule.onNodeWithTag(CHAT_READ_WHOLE_TAG).performScrollTo()
        action.requestFocus()
        action.assertIsFocused()

        action.performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()

        rule.onAllNodesWithTag(CHAT_MESSAGE_READER_LIST_TAG).assertCountEquals(0)
        rule.onNodeWithTag(CHAT_READ_WHOLE_TAG).assertIsFocused()
    }
}
