package app.knotwork.design.components.console

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A Vars row with a long value shows a preview, and its menu still copies the whole value.
 *
 * Laying out one string costs time in its whole length, quadratic when the string has no
 * spaces, however few lines are drawn: a value like this one held the main thread for
 * seconds when the row handed all of it to one `Text`. The length of the text the row
 * hands over is what is asserted, because no line cap bounds that cost.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ConsoleVarPreviewTest {

    @get:Rule
    val rule = createComposeRule()

    // A tool answer the size of the HTTP tool's budget would be larger still; what matters
    // is a value with no spaces, quoted the way the host quotes it.
    private val longValue = "\"" + "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIz".repeat(5_000) + "\""

    private val row = ConsoleVarRow(node = "TOOL#9c41d2", key = "output", valueJson = longValue)

    @Test
    fun `given a value without spaces of 200 000 characters when the Vars tab renders then its row holds a preview`() {
        render(onCopyVar = {})

        val longest = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text] }
            .maxOf { it.length }
        assertTrue("the longest text on the tab has $longest characters", longest <= 400)
        rule.onNodeWithText(longValue.take(400)).assertExists()
    }

    @Test
    fun `given a previewed value when Copy value is picked then the whole row is reported`() {
        var copied: ConsoleVarRow? = null
        render(onCopyVar = { copied = it })

        rule.onNodeWithText(longValue.take(400)).performTouchInput { longClick() }
        rule.onNodeWithText("Copy value").performClick()

        assertEquals(row, copied)
    }

    private fun render(onCopyVar: (ConsoleVarRow) -> Unit) {
        rule.setContent {
            KnotworkTheme {
                ConsolePane(
                    tab = ConsoleTab.Vars,
                    onTabChange = {},
                    logs = emptyList(),
                    vars = listOf(row),
                    traces = emptyList(),
                    filter = ConsoleFilter.allOn,
                    onFilterChange = {},
                    onSearch = {},
                    onCopyAll = {},
                    onClear = {},
                    onCopyVar = onCopyVar,
                )
            }
        }
    }
}
