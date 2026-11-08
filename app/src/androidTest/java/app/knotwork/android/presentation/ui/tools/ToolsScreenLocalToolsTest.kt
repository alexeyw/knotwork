package app.knotwork.android.presentation.ui.tools

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.knotwork.android.R
import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.ToolRisk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import app.knotwork.design.R as KnotworkR

/**
 * Covers the built-in (AppFunctions) section of the
 * Tools screen: row names render with their per-risk pill, and tapping
 * the row click target invokes the ViewModel's toggle hook with the
 * inverted enabled flag.
 */
class ToolsScreenLocalToolsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun builtInTool_rowShowsTheInterfaceStringAndNotWhatTheModelReads() {
        val (vm, _) = mockToolsViewModel(
            initialUiState = ToolsUiState(
                localTools = listOf(AgentTool("search_tool", "What the model reads", "{}")),
            ),
        )

        composeTestRule.setContent {
            MaterialTheme { ToolsScreen(viewModel = vm) }
        }

        // The start of the text: a row's description is limited to its lines.
        val shown = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.tool_description_search_tool)
            .take(DESCRIPTION_START)
        composeTestRule.onNodeWithText(text = shown, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(text = "What the model reads").assertDoesNotExist()
    }

    @Test
    fun localTools_rendersNamesAndRiskPillsForEachRisk() {
        val (vm, _) = mockToolsViewModel(
            initialUiState = ToolsUiState(
                localTools = listOf(
                    sampleAgentTool(name = "search_tool", risk = ToolRisk.READ_ONLY),
                    sampleAgentTool(name = "schedule_task", risk = ToolRisk.SENSITIVE),
                    sampleAgentTool(name = "delegate_task", risk = ToolRisk.DESTRUCTIVE),
                ),
            ),
        )

        composeTestRule.setContent {
            MaterialTheme { ToolsScreen(viewModel = vm) }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val readOnlyLabel = ctx.getString(KnotworkR.string.knotwork_tools_pill_readonly)
        val sensitiveLabel = ctx.getString(KnotworkR.string.knotwork_tools_pill_sensitive)
        val destructiveLabel = ctx.getString(KnotworkR.string.knotwork_tools_pill_destructive)

        // The tool names are friendly-cased in the screen mapper, but the
        // simple ids stay verbatim for these samples (no leading FQN or
        // `#invoke` suffix), so the row title equals the input name.
        composeTestRule.onNodeWithText(text = "search_tool").assertIsDisplayed()
        composeTestRule.onNodeWithText(text = "schedule_task").assertIsDisplayed()
        composeTestRule.onNodeWithText(text = "delegate_task").assertIsDisplayed()

        // Risk pills are rendered with a `Risk level: <label>` content
        // description so semantics announce them; one of each risk lives
        // on the screen.
        composeTestRule.onAllNodesWithContentDescription(label = "Risk level: $readOnlyLabel")
            .assertCountEquals(expectedSize = 1)
        composeTestRule.onAllNodesWithContentDescription(label = "Risk level: $sensitiveLabel")
            .assertCountEquals(expectedSize = 1)
        composeTestRule.onAllNodesWithContentDescription(label = "Risk level: $destructiveLabel")
            .assertCountEquals(expectedSize = 1)
    }

    @Test
    fun localToolSwitch_tap_invokesToggleLocalTool() {
        val (vm, _) = mockToolsViewModel(
            initialUiState = ToolsUiState(
                localTools = listOf(sampleAgentTool(name = "search_tool")),
                // Tool starts enabled (not in the disabled set) — toggling
                // the Switch should request disabling it.
                disabledAppFunctions = emptySet(),
            ),
        )

        composeTestRule.setContent {
            MaterialTheme { ToolsScreen(viewModel = vm) }
        }

        // Only one Switch is on the surface (the per-tool enable toggle).
        composeTestRule.onNode(matcher = isToggleable()).performClick()

        verify(exactly = 1) { vm.toggleLocalTool(toolName = "search_tool", isEnabled = false) }
    }

    @Test
    fun localToolRow_tap_routesToolClickThroughToOpenToolDetail() {
        val (vm, _) = mockToolsViewModel(
            initialUiState = ToolsUiState(
                localTools = listOf(sampleAgentTool(name = "search_tool")),
            ),
        )
        val opened = mutableListOf<String>()

        composeTestRule.setContent {
            MaterialTheme {
                ToolsScreen(viewModel = vm, onOpenToolDetail = { opened += it })
            }
        }

        composeTestRule.onNodeWithText(text = "search_tool").performClick()

        // `onOpenToolDetail` is the lambda the navigation graph wires to
        // the tool-detail destination; the row's clickable forwards the
        // raw tool id (here equal to the simple name) to it.
        //
        // JUnit assertion — Kotlin `assert` is a no-op without `-ea`,
        // which Android instrumentation runs do not enable.
        assertEquals(
            "onOpenToolDetail should receive exactly [search_tool]",
            listOf("search_tool"),
            opened,
        )
    }

    private companion object {
        const val DESCRIPTION_START = 24
    }
}
