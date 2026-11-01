package app.knotwork.design.components.console

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.R
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pins the console's Search action to the Logs tab.
 *
 * The inline search field is rendered by `ConsoleLogsBody` and by nothing else,
 * so on Vars and Traces the header magnifier used to be a control whose tap had
 * no observable effect — reported verbatim by an external tester as "А оно
 * ничего не делает". These tests fail if the action returns to a tab that
 * cannot act on it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ConsoleSearchAffordanceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val searchCd: String
        get() = RuntimeEnvironment.getApplication().getString(R.string.knotwork_console_action_search)

    private val logs = listOf(
        ConsoleLine("12:00:00.000", ConsoleSource.NODE, ConsoleLevel.Trace, "▶ INPUT"),
    )

    private val vars = listOf(
        ConsoleVarRow(node = "LITE_RT#501fab", key = "input", value = "the assembled prompt"),
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
    fun `logs tab offers the search action and reports the tap`() {
        var searchTaps = 0
        composeTestRule.setContent {
            KnotworkTheme { Pane(tab = ConsoleTab.Logs, onSearch = { searchTaps++ }) }
        }

        composeTestRule.onNodeWithContentDescription(searchCd).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(searchCd).performClick()

        assertEquals(1, searchTaps)
    }

    @Test
    fun `vars tab does not render the search action`() {
        composeTestRule.setContent {
            KnotworkTheme { Pane(tab = ConsoleTab.Vars) }
        }

        composeTestRule.onNodeWithContentDescription(searchCd).assertDoesNotExist()
    }

    @Test
    fun `traces tab does not render the search action`() {
        composeTestRule.setContent {
            KnotworkTheme { Pane(tab = ConsoleTab.Traces) }
        }

        composeTestRule.onNodeWithContentDescription(searchCd).assertDoesNotExist()
    }

    /**
     * The other header actions stay on every tab — Copy all and Clear act on
     * the whole console, so they can act wherever the user is.
     */
    @Test
    fun `copy-all and clear stay available on the traces tab`() {
        var copyAll = 0
        var clear = 0
        composeTestRule.setContent {
            KnotworkTheme {
                Pane(tab = ConsoleTab.Traces, onCopyAll = { copyAll++ }, onClear = { clear++ })
            }
        }
        val app = RuntimeEnvironment.getApplication()

        composeTestRule
            .onNodeWithContentDescription(app.getString(R.string.knotwork_console_action_copy_all))
            .performClick()
        composeTestRule
            .onNodeWithContentDescription(app.getString(R.string.knotwork_console_action_clear))
            .performClick()

        assertEquals(1, copyAll)
        assertEquals(1, clear)
    }

    /** Renders the pane on [tab] with the fixtures above; everything else is inert. */
    @Composable
    private fun Pane(
        tab: ConsoleTab,
        onSearch: () -> Unit = {},
        onCopyAll: () -> Unit = {},
        onClear: () -> Unit = {},
    ) {
        ConsolePane(
            tab = tab,
            onTabChange = {},
            logs = logs,
            vars = vars,
            traces = traces,
            filter = ConsoleFilter.allOn,
            onFilterChange = {},
            onSearch = onSearch,
            onCopyAll = onCopyAll,
            onClear = onClear,
        )
    }
}
