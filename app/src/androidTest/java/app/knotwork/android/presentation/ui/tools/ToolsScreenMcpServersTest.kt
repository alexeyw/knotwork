package app.knotwork.android.presentation.ui.tools

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.knotwork.android.domain.models.McpConnectionStatus
import io.mockk.verify
import org.junit.Rule
import org.junit.Test
import app.knotwork.design.R as KnotworkR

/**
 * Covers the MCP server section of the Tools screen:
 * connection-status subtitle flips from `connecting…` to `ok`, the expand
 * chevron toggles the nested tool list, and the overflow menu's Refresh
 * and "Turn all tools off" actions invoke the ViewModel.
 */
class ToolsScreenMcpServersTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun serverStatus_connectingFlipsToConnected_subtitleUpdates() {
        val (vm, handles) = mockToolsViewModel(
            initialUiState = ToolsUiState(
                mcpServers = listOf(
                    sampleServerSnapshot(
                        displayName = "Example MCP",
                        status = McpConnectionStatus.Connecting,
                    ),
                ),
            ),
        )

        composeTestRule.setContent {
            MaterialTheme { ToolsScreen(viewModel = vm) }
        }

        // Subtitle pattern is `"<count> tools · <label>"`; Connecting
        // resolves to "connecting…" and Connected to "ok".
        composeTestRule.onNodeWithText(text = "0 tools · connecting…").assertIsDisplayed()

        handles.uiStateFlow.value = handles.uiStateFlow.value.copy(
            mcpServers = listOf(
                sampleServerSnapshot(
                    displayName = "Example MCP",
                    status = McpConnectionStatus.Connected,
                ),
            ),
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(text = "0 tools · ok").assertIsDisplayed()
    }

    @Test
    fun expandChevron_tap_invokesToggleServerExpanded() {
        val (vm, _) = mockToolsViewModel(
            initialUiState = ToolsUiState(
                mcpServers = listOf(
                    sampleServerSnapshot(
                        url = "https://mcp.example.com",
                        displayName = "Example MCP",
                        status = McpConnectionStatus.Connected,
                        // Expand chevron only renders when the server has
                        // advertised at least one tool.
                        tools = listOf(
                            sampleMcpTool(
                                id = "mcp:abcd1234:remote_tool",
                                name = "remote_tool",
                            ),
                        ),
                    ),
                ),
            ),
        )

        composeTestRule.setContent {
            MaterialTheme { ToolsScreen(viewModel = vm) }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val expandCd = ctx.getString(KnotworkR.string.knotwork_tools_expand_server_cd)

        composeTestRule.onNodeWithContentDescription(label = expandCd).performClick()

        verify(exactly = 1) { vm.toggleServerExpanded(serverUrl = "https://mcp.example.com") }
    }

    @Test
    fun overflowRefresh_invokesRefreshServer() {
        val (vm, _) = mockToolsViewModel(
            initialUiState = ToolsUiState(
                mcpServers = listOf(
                    sampleServerSnapshot(
                        url = "https://mcp.example.com",
                        displayName = "Example MCP",
                        status = McpConnectionStatus.Connected,
                    ),
                ),
            ),
        )

        composeTestRule.setContent {
            MaterialTheme { ToolsScreen(viewModel = vm) }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val overflowCd = ctx.getString(KnotworkR.string.knotwork_tools_row_overflow_cd)
        val refreshLabel = ctx.getString(KnotworkR.string.knotwork_tools_row_action_refresh)

        composeTestRule.onNodeWithContentDescription(label = overflowCd).performClick()
        composeTestRule.onNodeWithText(text = refreshLabel).performClick()

        verify(exactly = 1) { vm.refreshServer(serverUrl = "https://mcp.example.com") }
    }

    @Test
    fun overflowTurnAllToolsOff_invokesSetServerToolsEnabled() {
        val (vm, _) = mockToolsViewModel(
            initialUiState = ToolsUiState(
                mcpServers = listOf(
                    sampleServerSnapshot(
                        url = "https://mcp.example.com",
                        displayName = "Example MCP",
                        status = McpConnectionStatus.Connected,
                        // The bulk switch is offered only while a listed tool is on.
                        tools = listOf(sampleMcpTool(id = "mcp:abcd1234:remote_tool", name = "remote_tool")),
                    ),
                ),
            ),
        )

        composeTestRule.setContent {
            MaterialTheme { ToolsScreen(viewModel = vm) }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val overflowCd = ctx.getString(KnotworkR.string.knotwork_tools_row_overflow_cd)
        val turnAllOff = ctx.getString(KnotworkR.string.knotwork_tools_row_action_disable_all)

        composeTestRule.onNodeWithContentDescription(label = overflowCd).performClick()
        composeTestRule.onNodeWithText(text = turnAllOff).performClick()

        verify(exactly = 1) { vm.setServerToolsEnabled(serverUrl = "https://mcp.example.com", enabled = false) }
    }
}
