package app.knotwork.design.components.text

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.components.unbrokenRunCuts
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app's [Text] and [WholeText] as drawn: ordinary text exactly as Material draws it, and
 * a long text bounded by the rule [shownText] picks. Native graphics, so lines have real
 * widths and an ellipsis is real.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class TextTest {

    @get:Rule
    val rule = createComposeRule()

    private fun blob(n: Int): String = buildString(n) { repeat(n) { append(('a' + it % 26)) } }

    private fun prose(n: Int): String = "word ".repeat(n / 5 + 1).substring(0, n)

    private fun show(content: @Composable () -> Unit) {
        rule.setContent { KnotworkTheme { Box(Modifier.width(300.dp)) { content() } } }
        rule.waitForIdle()
    }

    /** The text of every text node, in drawing order. */
    private fun drawnTexts(): List<String> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }

    @Test
    fun `given ordinary text when drawn then it is one text node, unchanged`() {
        val text = "The model answered in two short sentences. Nothing here is long."

        show { Text(text = text, style = KnotworkTextStyles.BodyBase) }

        assertEquals(listOf(text), drawnTexts())
    }

    @Test
    fun `given a line limit when the text is long then one node holds only what its lines can`() {
        var layout: TextLayoutResult? = null

        show {
            Text(
                text = blob(200_000),
                style = KnotworkTextStyles.MonoBase,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { layout = it },
            )
        }

        assertEquals(listOf(2 * LINE_CHARS_MAX), drawnTexts().map { it.length })
        val drawn = checkNotNull(layout)
        assertEquals(2, drawn.lineCount)
        assertTrue("the cut text still ends in an ellipsis", drawn.isLineEllipsized(1))
    }

    @Test
    fun `given no line limit when a run is long then it is drawn in pieces, every character`() {
        val text = blob(5_000)

        show { Text(text = text, style = KnotworkTextStyles.MonoBase) }

        val drawn = drawnTexts()
        assertTrue("one text holds the whole run", drawn.size > 1)
        drawn.forEach { assertTrue("a piece holds a run over the maximum", unbrokenRunCuts(it).isEmpty()) }
        assertEquals(text, drawn.joinToString(""))
    }

    @Test
    fun `given no line limit when the text passes the cap then the rest is counted under it`() {
        val text = prose(50_000)

        show { Text(text = text, style = KnotworkTextStyles.BodyBase) }

        val drawn = drawnTexts()
        assertEquals(text.substring(0, TEXT_MAX_CHARS), drawn.dropLast(1).joinToString(""))
        assertEquals("… 40,000 more characters", drawn.last())
    }

    @Test
    fun `given whole text when long then every character is drawn and nothing is counted`() {
        val text = prose(50_000) + blob(5_000)

        show { WholeText(text = text, style = KnotworkTextStyles.BodyBase) }

        val drawn = drawnTexts()
        assertEquals(text, drawn.joinToString(""))
        drawn.forEach { assertTrue(unbrokenRunCuts(it).isEmpty()) }
    }

    @Test
    fun `given pieces when laid out then the last piece reports its layout`() {
        val text = blob(5_000)
        var layout: TextLayoutResult? = null

        show { Text(text = text, style = KnotworkTextStyles.MonoBase, onTextLayout = { layout = it }) }

        assertEquals(drawnTexts().last(), checkNotNull(layout).layoutInput.text.text)
    }
}
