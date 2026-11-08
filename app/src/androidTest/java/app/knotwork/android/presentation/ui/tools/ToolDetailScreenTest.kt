package app.knotwork.android.presentation.ui.tools

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.knotwork.android.data.repositories.McpServerRepositoryImpl
import app.knotwork.android.domain.connection.ConnectionFailure
import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.McpConnectionStatus
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.McpTool
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented Compose tests for [ToolDetailScreen].
 *
 * Verifies the two branches the screen ships with: MCP tools render the
 * remote JSON-Schema from the cached [McpTool]; local tools render the
 * real `AgentTool.parameters` (no longer the cosmetic placeholder that
 * earlier shipped).
 */
class ToolDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val serverUrl = "https://example.invalid/mcp"

    @Test
    fun mcpTool_rendersSchemaFromServer() {
        val toolId = McpServerRepositoryImpl.mcpToolId(serverUrl = serverUrl, toolName = "search")
        val mcpTool = McpTool(
            id = toolId,
            serverUrl = serverUrl,
            name = "search",
            description = "Web search served from MCP",
            inputSchemaJson = "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}",
        )
        val viewModel = mockk<ToolsViewModel>(relaxed = true)
        val state = MutableStateFlow(
            ToolsUiState(
                mcpServers = listOf(
                    McpServerSnapshot(
                        config = McpServerConfig(url = serverUrl),
                        status = McpConnectionStatus.Connected,
                        tools = listOf(mcpTool),
                    ),
                ),
            ),
        )
        every { viewModel.uiState } returns state
        every { viewModel.findMcpTool(toolId = toolId) } returns mcpTool

        composeTestRule.setContent {
            ToolDetailScreen(toolId = toolId, onBack = {}, viewModel = viewModel)
        }

        composeTestRule.onNodeWithText("Web search served from MCP").assertIsDisplayed()
        composeTestRule.onNodeWithText(serverUrl).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(text = laidOutSchema(mcpTool.inputSchemaJson), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun mcpTool_missingWithErrorStatus_rendersErrorInsteadOfPermanentSkeleton() {
        // A stale deep link, a removed server, or a failed fetch can produce a `null`
        // McpTool. Without explicit handling the surface stays on a loading skeleton
        // forever; this test pins the regression for the SchemaError fallback.
        val toolId = McpServerRepositoryImpl.mcpToolId(serverUrl = serverUrl, toolName = "ghost")
        val viewModel = mockk<ToolsViewModel>(relaxed = true)
        val state = MutableStateFlow(
            ToolsUiState(
                mcpServers = listOf(
                    McpServerSnapshot(
                        config = McpServerConfig(url = serverUrl),
                        status = McpConnectionStatus.Error(host = "mcp.example", failure = ConnectionFailure.NotMcp),
                        tools = emptyList(),
                    ),
                ),
            ),
        )
        every { viewModel.uiState } returns state
        every { viewModel.findMcpTool(toolId = toolId) } returns null

        composeTestRule.setContent {
            ToolDetailScreen(toolId = toolId, onBack = {}, viewModel = viewModel)
        }

        composeTestRule.onNodeWithText(text = "Schema unavailable", substring = true).assertIsDisplayed()
    }

    @Test
    fun localTool_rendersRealParametersInsteadOfPlaceholder() {
        val tool = AgentTool(
            name = "get_system_time",
            description = "Reads the device clock",
            parameters = "{\"type\":\"object\",\"properties\":{\"timezone\":{\"type\":\"string\"}}}",
        )
        val viewModel = mockk<ToolsViewModel>(relaxed = true)
        val state = MutableStateFlow(ToolsUiState(localTools = listOf(tool)))
        every { viewModel.uiState } returns state

        composeTestRule.setContent {
            ToolDetailScreen(toolId = tool.name, onBack = {}, viewModel = viewModel)
        }

        composeTestRule.onNodeWithText("Reads the device clock").assertIsDisplayed()
        composeTestRule.onNodeWithText(text = "timezone", substring = true).assertIsDisplayed()
    }
}
