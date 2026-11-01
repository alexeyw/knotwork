package app.knotwork.design.components.console

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Behavioural tests for the console's long-press copy affordance on the Vars
 * and Traces tabs.
 *
 * Those two tabs used to receive no copy callback at all: `onCopyLine` reached
 * only the Logs body, so the sole way to get a node's input or output off the
 * screen was a screenshot — and the Vars tab is exactly where a run's assembled
 * prompt is read. Asserted behaviourally rather than by snapshot because the
 * menu does not exist until a long press, and because the snapshot suite is
 * inert under `./gradlew check`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ConsoleCopyAffordanceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val vars = listOf(
        ConsoleVarRow(node = "LITE_RT#501fab", key = "input", value = "the assembled prompt"),
        ConsoleVarRow(node = "LITE_RT#501fab", key = "output", value = "the model answer"),
    )

    private val logs = listOf(
        ConsoleLine("12:00:00.000", ConsoleSource.NODE, ConsoleLevel.Trace, "▶ INPUT"),
    )

    private val traces = listOf(
        ConsoleTraceSpan(
            name = "LITE_RT#501fab",
            durationMs = 1_240L,
            startedAt = "12:00:00.000",
            status = SpanStatus.Ok,
        ),
    )

    @Test
    fun `long-pressing a vars row offers Copy value and reports that row`() {
        var copied: ConsoleVarRow? = null
        composeTestRule.setContent {
            KnotworkTheme { Pane(tab = ConsoleTab.Vars, onCopyVar = { copied = it }) }
        }

        // A short tap must not open the menu — the same rule log rows follow, so
        // scrolling past a row cannot fire an action.
        composeTestRule.onNodeWithText("the assembled prompt").performClick()
        assertNull("a short tap must not copy anything", copied)

        composeTestRule.onNodeWithText("the assembled prompt").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Copy value").performClick()

        assertEquals(vars.first(), copied)
    }

    @Test
    fun `long-pressing the second vars row reports that row and not the first`() {
        // The menu is anchored per row; a shared expansion flag would copy
        // whichever row happened to be first.
        var copied: ConsoleVarRow? = null
        composeTestRule.setContent {
            KnotworkTheme { Pane(tab = ConsoleTab.Vars, onCopyVar = { copied = it }) }
        }

        composeTestRule.onNodeWithText("the model answer").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Copy value").performClick()

        assertEquals(vars[1], copied)
    }

    @Test
    fun `long-pressing a log row still offers both of its items`() {
        // The Logs tab had this gesture first; Vars and Traces were given the
        // same one by moving it into a shared wrapper rather than pasting it.
        // This pins the row that was converted — its menu has two items, which
        // is why the wrapper takes a menu body rather than a single label.
        var copied: ConsoleLine? = null
        var filtered: ConsoleSource? = null
        composeTestRule.setContent {
            KnotworkTheme {
                Pane(tab = ConsoleTab.Logs, onCopyLine = { copied = it }, onFilterByLineSource = { filtered = it })
            }
        }

        composeTestRule.onNodeWithText("▶ INPUT").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Only show this source").assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy line").performClick()

        assertEquals(logs.single(), copied)
        assertNull("copying must not also apply the filter", filtered)
    }

    @Test
    fun `long-pressing a traces row offers Copy span and reports that span`() {
        var copied: ConsoleTraceSpan? = null
        composeTestRule.setContent {
            KnotworkTheme { Pane(tab = ConsoleTab.Traces, onCopySpan = { copied = it }) }
        }

        composeTestRule.onNodeWithText("LITE_RT#501fab").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Copy span").performClick()

        assertEquals(traces.single(), copied)
    }

    /**
     * Renders the pane on [tab] with the fixtures above and the two copy
     * callbacks under test; everything else is inert.
     */
    @Composable
    private fun Pane(
        tab: ConsoleTab,
        onCopyVar: (ConsoleVarRow) -> Unit = {},
        onCopySpan: (ConsoleTraceSpan) -> Unit = {},
        onCopyLine: (ConsoleLine) -> Unit = {},
        onFilterByLineSource: (ConsoleSource) -> Unit = {},
    ) {
        ConsolePane(
            tab = tab,
            onTabChange = {},
            logs = logs,
            vars = vars,
            traces = traces,
            filter = ConsoleFilter.allOn,
            onFilterChange = {},
            onSearch = {},
            onCopyAll = {},
            onClear = {},
            onCopyVar = onCopyVar,
            onCopySpan = onCopySpan,
            onCopyLine = onCopyLine,
            onFilterByLineSource = onFilterByLineSource,
        )
    }
}
