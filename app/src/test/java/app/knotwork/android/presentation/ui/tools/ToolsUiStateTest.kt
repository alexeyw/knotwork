package app.knotwork.android.presentation.ui.tools

import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.McpConnectionStatus
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.McpTool
import app.knotwork.android.domain.services.McpToolRouting
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Tools screen's "not offered to the agent" line must tell the story the agent
 * lives in: these cases mirror the ones `ToolRepositoryImplTest` pins for the
 * agent's catalogue.
 */
class ToolsUiStateTest {

    private fun tool(serverUrl: String, name: String) = McpTool(
        id = "mcp:$serverUrl:$name",
        serverUrl = serverUrl,
        name = name,
        description = "",
        inputSchemaJson = "{}",
    )

    private fun server(url: String, vararg names: String) = McpServerSnapshot(
        config = McpServerConfig(url = url),
        status = McpConnectionStatus.Connected,
        tools = names.map { tool(url, it) },
    )

    @Test
    fun `given a server tool with a device tool's name when mcpShadowing then it is shadowed by the device tool`() {
        val state = ToolsUiState(
            mcpServers = listOf(server("a", "read_file", "create_issue")),
            localTools = listOf(AgentTool("read_file", "built in", "{}")),
        )

        assertEquals(mapOf("mcp:a:read_file" to McpToolRouting.Shadow.LocalTool), state.mcpShadowing)
    }

    @Test
    fun `given two servers publishing one name when mcpShadowing then the later one is shadowed by the earlier`() {
        val state = ToolsUiState(mcpServers = listOf(server("a", "create_issue"), server("b", "create_issue")))

        assertEquals(
            mapOf("mcp:b:create_issue" to McpToolRouting.Shadow.EarlierServer("a")),
            state.mcpShadowing,
        )
    }

    @Test
    fun `given the earlier server switched the name off when mcpShadowing then the later one is offered`() {
        val state = ToolsUiState(
            mcpServers = listOf(server("a", "create_issue"), server("b", "create_issue")),
            disabledMcpTools = setOf("mcp:a:create_issue"),
        )

        assertEquals(emptyMap<String, McpToolRouting.Shadow>(), state.mcpShadowing)
    }
}
