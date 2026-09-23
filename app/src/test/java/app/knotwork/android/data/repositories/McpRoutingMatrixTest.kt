package app.knotwork.android.data.repositories

import app.knotwork.android.data.mcp.McpClient
import app.knotwork.android.data.mcp.McpClientFactory
import app.knotwork.android.data.mcp.McpConnectionPool
import app.knotwork.android.data.tools.local.LocalAppFunctionManager
import app.knotwork.android.data.tools.local.SearchTool
import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.ToolExecutionContext
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Guard for the class of defect behind "the risk comes from one server, the call
 * goes to another": two resolvers answering "which server serves this name?" for
 * the approval gate and for the dispatch, and disagreeing.
 *
 * Two servers publish the same tool name. Each carries a different risk decision
 * — A `READ_ONLY`, B `DESTRUCTIVE`, neither the `SENSITIVE` default — so the risk
 * [ToolRepositoryImpl.getRisk] returns names the server it resolved. Every
 * combination of the three states that decide routing (does the server publish
 * the name, has the user switched it off there, is the server reachable) is run,
 * and in every one the catalogue entry, the risk and the call must name the same
 * server — or, when no server can serve the name, all three must refuse.
 *
 * A second resolver added anywhere on that path, or a failover that sends the call
 * past the resolved server, breaks at least one cell.
 */
class McpRoutingMatrixTest {

    /** One fake MCP server: what it publishes, whether it is up, and what it was asked to run. */
    private class FakeServer(val label: String, var up: Boolean, var publishes: Boolean) {
        val calls = mutableListOf<String>()
        val tools: List<AgentTool> get() = if (publishes) listOf(AgentTool(TOOL, label, "{}")) else emptyList()
    }

    /** A client bound, on connect, to whichever fake server its config names. */
    private class FakeClient(private val servers: Map<String, FakeServer>) : McpClient {
        private var server: FakeServer? = null

        override suspend fun connect(config: McpServerConfig) {
            val target = servers.getValue(config.url)
            if (!target.up) throw IOException("${target.label} is down")
            server = target
        }

        override suspend fun disconnect() {
            server = null
        }

        override suspend fun getTools(): List<AgentTool> = server?.tools.orEmpty()

        override suspend fun executeTool(name: String, arguments: String): String {
            val target = checkNotNull(server) { "not connected" }
            target.calls += name
            return "from-${target.label}"
        }
    }

    /** One cell of the matrix: the three routing states of each server. */
    private data class Cell(
        val aPublishes: Boolean,
        val aDisabled: Boolean,
        val aUp: Boolean,
        val bPublishes: Boolean,
        val bDisabled: Boolean,
        val bUp: Boolean,
    )

    private fun cells(): List<Cell> {
        val flags = listOf(false, true)
        return flags.flatMap { ap ->
            flags.flatMap { ad ->
                flags.flatMap { au ->
                    flags.flatMap { bp ->
                        flags.flatMap { bd -> flags.map { bu -> Cell(ap, ad, au, bp, bd, bu) } }
                    }
                }
            }
        }
    }

    private fun repository(servers: Map<String, FakeServer>, disabled: Set<String>): ToolRepositoryImpl {
        val settings: SettingsRepository = mockk()
        every { settings.mcpServers } returns flowOf(listOf(McpServerConfig(url = URL_A), McpServerConfig(url = URL_B)))
        every { settings.disabledAppFunctions } returns flowOf(emptySet())
        every { settings.disabledMcpTools } returns flowOf(disabled)
        every { settings.toolRiskOverrides } returns flowOf(
            mapOf(
                McpServerRepositoryImpl.mcpToolId(serverUrl = URL_A, toolName = TOOL) to ToolRisk.READ_ONLY,
                McpServerRepositoryImpl.mcpToolId(serverUrl = URL_B, toolName = TOOL) to ToolRisk.DESTRUCTIVE,
            ),
        )
        every { settings.approvedCleartextOrigins } returns flowOf(emptySet())
        every { settings.allowedHttpDomains } returns flowOf(emptyList())
        every { settings.blockNetworkFromLocalModel } returns flowOf(false)
        val factory = object : McpClientFactory {
            override fun create(): McpClient = FakeClient(servers)
        }
        val appFunctions: LocalAppFunctionManager = mockk()
        coEvery { appFunctions.getAvailableFunctions() } returns emptyList()
        coEvery { appFunctions.isDiscovered(any()) } returns false
        val apiKeys: ApiKeyRepository = mockk()
        every { apiKeys.getOpenAIKey() } returns flowOf(null)
        every { apiKeys.getAnthropicKey() } returns flowOf(null)
        every { apiKeys.getGoogleKey() } returns flowOf(null)
        every { apiKeys.getDeepSeekKey() } returns flowOf(null)
        every { apiKeys.getOllamaBaseUrl() } returns flowOf(null)
        val searchTool: SearchTool = mockk(relaxed = true)
        every { searchTool.asAgentTool() } returns AgentTool("search_tool", "desc", "{}")
        return ToolRepositoryImpl(
            settings,
            McpConnectionPool(factory, settings),
            appFunctions,
            apiKeys,
            searchTool,
            emptyMap(),
        )
    }

    @Test
    fun `in every routing state the catalogue entry, the risk and the call name the same server`() = runTest {
        val failures = mutableListOf<String>()
        for (cell in cells()) {
            val a = FakeServer(label = "A", up = cell.aUp, publishes = cell.aPublishes)
            val b = FakeServer(label = "B", up = cell.bUp, publishes = cell.bPublishes)
            val disabled = buildSet {
                if (cell.aDisabled) add(McpServerRepositoryImpl.mcpToolId(serverUrl = URL_A, toolName = TOOL))
                if (cell.bDisabled) add(McpServerRepositoryImpl.mcpToolId(serverUrl = URL_B, toolName = TOOL))
            }
            val repository = repository(mapOf(URL_A to a, URL_B to b), disabled)
            val expected = listOf(a to cell.aDisabled, b to cell.bDisabled)
                .firstOrNull { (server, off) -> server.up && server.publishes && !off }
                ?.first

            val listed = repository.getAvailableTools().filter { it.name == TOOL }.map { it.description }
            if (listed != listOfNotNull(expected?.label)) failures += "$cell: catalogue lists $listed"

            val risk = runCatching { repository.getRisk(TOOL, "{}") }
            val expectedRisk = when (expected) {
                a -> ToolRisk.READ_ONLY
                b -> ToolRisk.DESTRUCTIVE
                else -> null
            }
            val riskSeen = risk.getOrNull()
            if (riskSeen != expectedRisk) failures += "$cell: risk ${riskSeen ?: risk.exceptionOrNull()}"

            val call = runCatching {
                repository.executeTool(TOOL, "{}", ToolExecutionContext(sessionId = "s", gatedRisk = expectedRisk))
            }
            val ran = listOf(a, b).filter { it.calls.isNotEmpty() }.map { it.label }
            if (ran != listOfNotNull(expected?.label)) failures += "$cell: call ran on $ran"
            if (expected != null && call.getOrNull() != "from-${expected.label}") {
                failures += "$cell: call returned ${call.getOrNull() ?: call.exceptionOrNull()}"
            }
            if (expected == null && call.exceptionOrNull() !is IllegalArgumentException) {
                failures +=
                    "$cell: an unservable call must be refused, got ${call.getOrNull() ?: call.exceptionOrNull()}"
            }
        }
        assertTrue(failures.joinToString(separator = "\n"), failures.isEmpty())
        assertEquals(CELL_COUNT, cells().size)
    }

    @Test
    fun `given a server that comes up between the risk check and the call when executeTool then the call is refused`() =
        runTest {
            // A is down when the gate asks, so B serves the name and the gate decides
            // on B's risk. A reconnects before the dispatch and takes the name back:
            // running the call on A would run it under B's decision.
            val a = FakeServer(label = "A", up = false, publishes = true)
            val b = FakeServer(label = "B", up = true, publishes = true)
            val repository = repository(mapOf(URL_A to a, URL_B to b), disabled = emptySet())

            val gatedRisk = repository.getRisk(TOOL, "{}")
            a.up = true
            val call = runCatching {
                repository.executeTool(TOOL, "{}", ToolExecutionContext(sessionId = "s", gatedRisk = gatedRisk))
            }

            assertEquals(ToolRisk.DESTRUCTIVE, gatedRisk)
            assertTrue("expected a refusal, got ${call.getOrNull()}", call.exceptionOrNull() is IllegalStateException)
            assertTrue(a.calls.isEmpty())
            assertTrue(b.calls.isEmpty())
        }

    private companion object {
        const val TOOL = "create_issue"
        const val URL_A = "https://a.example/mcp"
        const val URL_B = "https://b.example/mcp"

        /** 2 servers × 3 binary states. */
        const val CELL_COUNT = 64
    }
}
