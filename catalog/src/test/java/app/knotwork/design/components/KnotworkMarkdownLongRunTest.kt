package app.knotwork.design.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.theme.KnotworkTheme
import com.mikepenz.markdown.m3.Markdown
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Every Markdown element the chat draws, holding a run without whitespace longer than
 * [UNBROKEN_RUN_MAX], is drawn by [knotworkMarkdownComponents] without one text holding
 * more of the run than that — the renderer draws headings and table cells with its own
 * text, which the app's bounded text never sees. Laid out whole, a 200 000-character run
 * took 10 s in a heading and 13 s in a table cell on the host
 * (`project_docs/long-text-2026-11-04-data/`).
 *
 * The elements are drawn one after another in one renderer, each with its own marker so
 * the check waits for that element's parse.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class KnotworkMarkdownLongRunTest {

    @get:Rule
    val rule = createComposeRule()

    /** A run without whitespace: base64 or minified JSON as a tool returns it. */
    private val run = buildString(RUN_CHARS) { repeat(RUN_CHARS) { append(('a' + it % 26)) } }

    /** Each element the chat's Markdown can hold, with [run] in its text and a marker before it. */
    private val elements: List<Pair<String, (String) -> String>> = listOf(
        "paragraph" to { mark -> "$mark $run" },
        "atx heading" to { mark -> "## $mark $run" },
        "level 6 heading" to { mark -> "###### $mark $run" },
        "setext heading" to { mark -> "$mark $run\n===" },
        "setext heading 2" to { mark -> "$mark $run\n---" },
        "list item" to { mark -> "- $mark $run" },
        "ordered list item" to { mark -> "1. $mark $run" },
        "block quote" to { mark -> "> $mark $run" },
        "table body cell" to { mark -> "| key | value |\n|---|---|\n| $mark | $run |" },
        "table header cell" to { mark -> "| $mark $run | value |\n|---|---|\n| a | b |" },
    )

    private fun drawnTexts(): List<String> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }

    @Test
    fun `given a long run in any element when the chat draws it then no text holds more than the maximum of it`() {
        var source by mutableStateOf("warm up")
        rule.setContent {
            KnotworkTheme {
                Markdown(
                    content = source,
                    typography = knotworkMarkdownTypography(),
                    colors = knotworkMarkdownColor(),
                    components = knotworkMarkdownComponents(),
                )
            }
        }
        for ((name, build) in elements) {
            val marker = "MARK${name.replace(' ', '_')}"
            source = build(marker)
            rule.waitUntil(timeoutMillis = 10_000) {
                rule.onAllNodes(hasText(marker, substring = true), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            rule.waitForIdle()

            val drawn = drawnTexts()
            drawn.forEach { text ->
                assertTrue(
                    "$name: a text holds a run of over $UNBROKEN_RUN_MAX characters",
                    unbrokenRunCuts(text).isEmpty(),
                )
            }
            assertTrue("$name: the run is not drawn whole", drawn.joinToString("").contains(run))
        }
    }

    @Test
    fun `given a long run in a code block when drawn then each line holds at most the maximum of it`() {
        rule.setContent {
            KnotworkTheme {
                Markdown(
                    content = "```\n$run\n```",
                    typography = knotworkMarkdownTypography(),
                    colors = knotworkMarkdownColor(),
                    components = knotworkMarkdownComponents(),
                )
            }
        }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText(run.take(40), substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        drawnTexts().forEach { assertTrue(unbrokenRunCuts(it).isEmpty()) }
    }

    private companion object {
        /** Two and a half times the maximum: every element must cut it twice. */
        const val RUN_CHARS = 5_000
    }
}
