package app.knotwork.design.screens.tools

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
 * The bulk switches in an MCP server row's menu.
 *
 * A server can publish dozens of tools when the user wants one, and switching
 * the rest off one by one was the complaint. The menu offers "Turn all tools
 * on" while at least one listed tool is off and "Turn all tools off" while at
 * least one is on — so what it offers tells which way the tools stand — and
 * neither for a server that lists no tools. A pick reaches the host as one
 * call naming the server and the direction.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ToolsServerMenuTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val calls = mutableListOf<Pair<String, Boolean>>()

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    @Test
    fun `given some tools on and some off when the menu opens then it offers both and each names its direction`() {
        // Given
        showServer(true, false, true)

        // When
        openMenu()
        composeTestRule.onNodeWithText(string(R.string.knotwork_tools_row_action_enable_all)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.knotwork_tools_row_action_disable_all)).performClick()
        openMenu()
        composeTestRule.onNodeWithText(string(R.string.knotwork_tools_row_action_enable_all)).performClick()

        // Then
        assertEquals(listOf(SERVER_ID to false, SERVER_ID to true), calls)
    }

    @Test
    fun `given every tool on when the menu opens then it offers only switching them off`() {
        // Given
        showServer(true, true)

        // When
        openMenu()

        // Then
        composeTestRule.onNodeWithText(string(R.string.knotwork_tools_row_action_disable_all)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.knotwork_tools_row_action_enable_all)).assertDoesNotExist()
    }

    @Test
    fun `given every tool off when the menu opens then it offers only switching them on`() {
        // Given
        showServer(false, false)

        // When
        openMenu()

        // Then
        composeTestRule.onNodeWithText(string(R.string.knotwork_tools_row_action_enable_all)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.knotwork_tools_row_action_disable_all)).assertDoesNotExist()
    }

    @Test
    fun `given a server that lists no tools when the menu opens then it offers neither`() {
        // Given
        showServer()

        // When
        openMenu()

        // Then — the rest of the menu is there
        composeTestRule.onNodeWithText(string(R.string.knotwork_tools_row_action_refresh)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.knotwork_tools_row_action_enable_all)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.knotwork_tools_row_action_disable_all)).assertDoesNotExist()
        assertEquals(emptyList<Pair<String, Boolean>>(), calls)
    }

    /** Renders one connected server whose listed tools are switched as [enabled] says, in order. */
    private fun showServer(vararg enabled: Boolean) {
        val tools = enabled.mapIndexed { index, on ->
            McpToolEntry(
                id = "mcp:cafebabe:tool_$index",
                name = "tool_$index",
                description = "",
                risk = BuiltInToolRisk.Sensitive,
                enabled = on,
            )
        }
        val state = ToolsViewState(
            visualState = ToolsVisualState.Default,
            mcpServers = listOf(
                McpServerRow(
                    id = SERVER_ID,
                    url = "Example MCP",
                    toolCount = tools.size,
                    latencyLabel = "ok",
                    state = McpConnectionState.Connected,
                    tools = tools,
                ),
            ),
        )
        val callbacks = ToolsCallbacks(onServerToolsToggle = { serverId, on -> calls += serverId to on })
        composeTestRule.setContent { KnotworkTheme { ToolsContent(state = state, callbacks = callbacks) } }
    }

    private fun openMenu() {
        composeTestRule.onNodeWithContentDescription(string(R.string.knotwork_tools_row_overflow_cd)).performClick()
    }

    private companion object {
        const val SERVER_ID = "https://mcp.example.com"
    }
}
