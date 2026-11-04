package app.knotwork.design.components.text

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.R
import app.knotwork.design.components.chat.ChatContent
import app.knotwork.design.components.chat.ChatMessage
import app.knotwork.design.components.chat.ChatMetadata
import app.knotwork.design.components.chat.ChatRole
import app.knotwork.design.components.chat.ClarificationCard
import app.knotwork.design.components.chat.ClarificationCardModel
import app.knotwork.design.components.chat.HitlConfirmationCard
import app.knotwork.design.components.chat.HitlConfirmationModel
import app.knotwork.design.components.chips.Risk
import app.knotwork.design.components.console.ConsoleFilter
import app.knotwork.design.components.console.ConsoleLevel
import app.knotwork.design.components.console.ConsoleLine
import app.knotwork.design.components.console.ConsolePane
import app.knotwork.design.components.console.ConsoleSource
import app.knotwork.design.components.console.ConsoleTab
import app.knotwork.design.components.prompt.PromptPreviewPreview
import app.knotwork.design.components.prompt.PromptPreviewSegmentUi
import app.knotwork.design.components.prompt.PromptPreviewSheetBody
import app.knotwork.design.components.unbrokenRunCuts
import app.knotwork.design.screens.files.FilesContent
import app.knotwork.design.screens.files.FilesPreview
import app.knotwork.design.screens.memory.MemoryContent
import app.knotwork.design.screens.memory.MemoryPreview
import app.knotwork.design.screens.monitoring.MonitoringContent
import app.knotwork.design.screens.monitoring.MonitoringLogLine
import app.knotwork.design.screens.monitoring.MonitoringViewState
import app.knotwork.design.screens.monitoring.MonitoringVisualState
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The screens that showed a model's, a tool's or a user's text of any length in one text,
 * drawn with such a text: the ones that may cut keep each text node to what its lines can
 * hold or to the cap, and the ones that must show the text whole show every character of it,
 * with no text node holding a run without whitespace longer than 2 000 characters.
 *
 * Each of these laid the whole string out in one text before: a 200 000-character run took
 * 5 to 10 s on the host (`project_docs/long-text-2026-11-04-data/`). The census of who may show
 * a text whole is `BoundedTextGuardTest`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class LongTextSurfacesTest {

    @get:Rule
    val rule = createComposeRule()

    /** A run without whitespace: what a tool's base64 or minified JSON looks like. */
    private val run = buildString(RUN_CHARS) { repeat(RUN_CHARS) { append(('a' + it % 26)) } }

    /** Prose longer than a text shows without a line limit. */
    private val prose = "word ".repeat(PROSE_CHARS / 5)

    private fun show(content: @Composable () -> Unit) {
        rule.setContent { KnotworkTheme { content() } }
        rule.waitForIdle()
    }

    /** Shows [content] with each of [payloads] in turn and runs [check] on each. */
    private fun forEachPayload(
        payloads: List<String>,
        content: @Composable (String) -> Unit,
        check: (String) -> Unit,
    ) {
        var current by mutableStateOf(payloads.first())
        rule.setContent { KnotworkTheme { content(current) } }
        payloads.forEach { payload ->
            current = payload
            rule.waitForIdle()
            check(payload)
        }
    }

    private fun drawnTexts(): List<String> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }

    /**
     * No text node holds more than a cut text may: a text with a line limit is handed at most
     * `maxLines × LINE_CHARS_MAX` characters — a run that long included, which is the point:
     * its cost is bounded by the length — and one without holds at most the cap, in pieces.
     */
    private fun assertBounded() {
        drawnTexts().forEach { text ->
            assertTrue("a text node holds ${text.length} characters", text.length <= MOST_CHARS_OF_A_CUT_TEXT)
        }
    }

    /** No text node holds a run over the maximum, and [text] is drawn whole across the nodes. */
    private fun assertWhole(text: String) {
        val drawn = drawnTexts()
        drawn.forEach { assertTrue("a text node holds a run of over 2 000 characters", unbrokenRunCuts(it).isEmpty()) }
        assertTrue("the text is not drawn whole", drawn.joinToString("").contains(text))
    }

    @Composable
    private fun Monitoring(message: String) = MonitoringContent(
        state = MonitoringViewState(
            visualState = MonitoringVisualState.Default,
            logs = listOf(MonitoringLogLine(id = "row:1", timestamp = "12:00:00", message = message)),
        ),
    )

    @Test
    fun `given a tool observation of any shape when monitoring shows it then it is bounded`() {
        forEachPayload(listOf(run, prose), { Monitoring(it) }) { assertBounded() }
    }

    @Test
    fun `given a long error when the console logs show it then it is bounded`() {
        show {
            ConsolePane(
                tab = ConsoleTab.Logs,
                onTabChange = {},
                logs = listOf(ConsoleLine("12:00:00", ConsoleSource.NODE, ConsoleLevel.Error, run)),
                vars = emptyList(),
                traces = emptyList(),
                filter = ConsoleFilter.allOn,
                onFilterChange = {},
                onSearch = {},
                onCopyAll = {},
                onClear = {},
            )
        }

        assertBounded()
    }

    @Test
    fun `given a long memory entry when its row shows it then it is bounded`() {
        show {
            val base = MemoryPreview.populated()
            val first = base.sections.first()
            MemoryContent(
                state = base.copy(sections = listOf(first.copy(rows = listOf(first.rows.first().copy(body = run))))),
            )
        }

        assertBounded()
    }

    @Test
    fun `given a long memory entry when its detail opens then it is shown whole`() {
        forEachPayload(
            listOf(run, prose),
            { body ->
                val base = MemoryPreview.entryExpanded()
                MemoryContent(state = base.copy(expandedEntry = checkNotNull(base.expandedEntry).copy(body = body)))
            },
        ) { assertWhole(it) }
    }

    private fun showApproval(value: String) = show {
        HitlConfirmationCard(
            model = HitlConfirmationModel(
                risk = Risk.Sensitive,
                toolName = "write_file",
                summary = "",
                arguments = mapOf("content" to value),
                timestamp = "15:12",
            ),
            pendingTypedConfirm = "",
            onTypedConfirmChange = {},
            allowOnceEnabled = true,
            onAllowOnce = {},
            onAllowAlways = null,
            onReject = {},
        )
    }

    private fun expandArguments() {
        val expand = ApplicationProvider.getApplicationContext<Context>().getString(R.string.knotwork_hitl_args_expand)
        rule.onNodeWithContentDescription(expand, useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }

    @Test
    fun `given a long run as an argument when the approval card shows it then it is bounded, whole once expanded`() {
        showApproval(run)
        assertBounded()

        expandArguments()

        // What the user approves is what runs: every character of the argument shows.
        assertWhole(run)
    }

    @Test
    fun `given long prose as an argument when the approval card is expanded then it is whole`() {
        showApproval(prose)
        assertBounded()

        expandArguments()

        assertWhole(prose)
    }

    @Test
    fun `given a model's reply as the question when the clarification card shows it then it is whole`() {
        forEachPayload(listOf(run, prose), {
            ClarificationCard(model = ClarificationCardModel(question = it), onReply = {})
        }) {
            assertWhole(it)
        }
    }

    @Test
    fun `given an exception text when the chat's system row shows it then it is bounded`() {
        forEachPayload(
            listOf(run, prose),
            { ChatMessage(role = ChatRole.System, content = ChatContent.Text(it), metadata = ChatMetadata("12:00")) },
        ) { assertBounded() }
    }

    @Test
    fun `given a file preview when shown then all of it is drawn`() {
        forEachPayload(
            listOf(run, prose),
            { body ->
                val base = FilesPreview.preview()
                FilesContent(state = base.copy(preview = checkNotNull(base.preview).copy(body = body)))
            },
        ) { assertWhole(it) }
    }

    @Test
    fun `given a substituted value when the prompt preview shows it then it is whole`() {
        forEachPayload(
            listOf(run, prose),
            { value ->
                PromptPreviewSheetBody(
                    segments = listOf(
                        PromptPreviewSegmentUi.Literal("Memory: "),
                        PromptPreviewSegmentUi.Resolved(value),
                    ),
                    ui = PromptPreviewPreview.ui(),
                )
            },
        ) { assertWhole(it) }
    }

    @Test
    fun `the payloads are long enough to be cut`() {
        // A run under the maximum or prose under the cap would pass every test above unchanged.
        assertTrue(unbrokenRunCuts(run).isNotEmpty())
        assertTrue(prose.length > TEXT_MAX_CHARS)
        assertEquals(RUN_CHARS, run.length)
    }

    private companion object {
        const val RUN_CHARS = 20_000
        const val PROSE_CHARS = 30_000

        /** The most a cut text node holds: the six-line limit of a memory version, or the cap. */
        const val MOST_CHARS_OF_A_CUT_TEXT = 6 * LINE_CHARS_MAX
    }
}
