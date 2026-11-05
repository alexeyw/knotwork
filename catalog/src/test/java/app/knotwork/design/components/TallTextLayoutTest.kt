package app.knotwork.design.components

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.R
import app.knotwork.design.components.chat.HitlConfirmationCard
import app.knotwork.design.components.chat.HitlConfirmationModel
import app.knotwork.design.components.chips.Risk
import app.knotwork.design.theme.KnotworkTheme
import com.mikepenz.markdown.m3.Markdown
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A text taller than a layout constraint can hold — about 262 000 px — is drawn, not thrown.
 * A container that sizes itself by its content's intrinsic height first turns that height into
 * a fixed constraint, and one too tall to represent threw `IllegalArgumentException` ("Can't
 * represent a width of 0 and height of …"): a table header with a 200 000-character cell, and an
 * approval card with a 500 000-character argument expanded. Native graphics, so the heights are
 * real.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class TallTextLayoutTest {

    @get:Rule
    val rule = createComposeRule()

    private fun drawnLength(): Int =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .sumOf { node -> node.config[SemanticsProperties.Text].sumOf { it.length } }

    private fun showMarkdown(source: String, marker: String) {
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
        rule.waitUntil(timeoutMillis = 30_000) {
            rule.onAllNodes(hasText(marker, substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        rule.waitForIdle()
    }

    @Test
    fun `given a table header cell of 300 000 characters of prose when drawn then it is drawn whole`() {
        val prose = "word ".repeat(60_000)

        showMarkdown("| HEAD $prose | value |\n|---|---|\n| a | b |", marker = "HEAD")

        assertTrue(drawnLength() >= prose.trim().length)
    }

    @Test
    fun `given a table header cell with a 200 000-character run when drawn then it is drawn whole`() {
        val run = buildString(200_000) { repeat(200_000) { append(('a' + it % 26)) } }

        showMarkdown("| HEAD $run | value |\n|---|---|\n| a | b |", marker = "HEAD")

        assertTrue(drawnLength() >= run.length)
    }

    @Test
    fun `given an argument of 500 000 characters when the approval card is expanded then it is drawn whole`() {
        val value = "word ".repeat(100_000)
        rule.setContent {
            KnotworkTheme {
                HitlConfirmationCard(
                    model = HitlConfirmationModel(
                        risk = Risk.Sensitive,
                        toolName = "write_file",
                        summary = "",
                        arguments = "{\n  \"content\": $value\n}",
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
        }
        val expand = ApplicationProvider.getApplicationContext<Context>().getString(R.string.knotwork_hitl_args_expand)

        rule.onNodeWithContentDescription(expand, useUnmergedTree = true).performClick()
        rule.waitForIdle()

        assertTrue(drawnLength() >= value.length)
    }
}
