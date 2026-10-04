package app.knotwork.android.presentation.ui.tools

import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.McpConnectionStatus
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.McpTool
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.models.ToolSource
import app.knotwork.android.domain.repositories.McpServerRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.ToolRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolsViewModelTest {

    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val toolRepository: ToolRepository = mockk(relaxed = true)
    private val mcpServerRepository: McpServerRepository = mockk(relaxed = true)

    private lateinit var viewModel: ToolsViewModel
    private val testDispatcher = StandardTestDispatcher()

    private val mcpServersFlow = MutableStateFlow<List<McpServerConfig>>(emptyList())
    private val disabledAppFunctionsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val disabledMcpToolsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val allowedHttpDomainsFlow = MutableStateFlow<List<String>>(emptyList())
    private val toolRiskOverridesFlow = MutableStateFlow<Map<String, ToolRisk>>(emptyMap())
    private val statusFlows = mutableMapOf<String, MutableStateFlow<McpConnectionStatus>>()

    private fun statusFlowFor(url: String): MutableStateFlow<McpConnectionStatus> =
        statusFlows.getOrPut(url) { MutableStateFlow(McpConnectionStatus.Connecting) }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { settingsRepository.mcpServers } returns mcpServersFlow
        every { settingsRepository.disabledAppFunctions } returns disabledAppFunctionsFlow
        every { settingsRepository.disabledMcpTools } returns disabledMcpToolsFlow
        every { settingsRepository.allowedHttpDomains } returns allowedHttpDomainsFlow
        every { settingsRepository.toolRiskOverrides } returns toolRiskOverridesFlow
        coEvery { toolRepository.getAllLocalTools() } returns listOf(
            AgentTool(name = "get_system_time", description = "desc", parameters = "{}"),
        )
        every { mcpServerRepository.observeConnectionStatus(any()) } answers { statusFlowFor(firstArg()) }
        coEvery { mcpServerRepository.fetchToolList(any(), any()) } returns Result.success(emptyList())
        viewModel = ToolsViewModel(
            toolSettings = settingsRepository,
            toolRepository = toolRepository,
            mcpServerRepository = mcpServerRepository,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state reflects repository values`() = runTest {
        mcpServersFlow.value = listOf(McpServerConfig(url = "http://test.com"))
        disabledAppFunctionsFlow.value = setOf("get_system_time")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("http://test.com"), state.mcpServers.map { it.url })
        assertEquals(setOf("get_system_time"), state.disabledAppFunctions)
        assertEquals("get_system_time", state.localTools.first().name)
    }

    @Test
    fun `allowedHttpDomainCount reflects the settings flow`() = runTest {
        allowedHttpDomainsFlow.value = listOf("api.openai.com", "api.github.com")
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.allowedHttpDomainCount)
    }

    @Test
    fun `local tools list shows discovered AppFunctions alongside built-ins so they can be disabled`() = runTest {
        coEvery { toolRepository.getAllLocalTools() } returns listOf(
            AgentTool(name = "get_system_time", description = "", parameters = "{}", source = ToolSource.BUILT_IN),
            AgentTool(name = "com.x/echo", description = "", parameters = "{}", source = ToolSource.APP_FUNCTION),
        )
        viewModel = ToolsViewModel(
            toolSettings = settingsRepository,
            toolRepository = toolRepository,
            mcpServerRepository = mcpServerRepository,
        )
        advanceUntilIdle()

        // Both are surfaced: hiding AppFunctions would leave them callable yet
        // uncontrollable. They now appear with the same disable toggle.
        val names = viewModel.uiState.value.localTools.map { it.name }
        assertEquals(listOf("get_system_time", "com.x/echo"), names)
    }

    @Test
    fun `appearing server snapshot exists before observers and fetches launch`() = runTest {
        val url = "https://racy.example/mcp"
        val config = McpServerConfig(url = url)
        val sample = McpTool(
            id = "mcp:cafebabe:tool",
            serverUrl = url,
            name = "tool",
            description = "",
            inputSchemaJson = "{}",
        )
        coEvery {
            mcpServerRepository.fetchToolList(config = config, forceRefresh = false)
        } returns Result.success(listOf(sample))

        mcpServersFlow.value = listOf(config)
        advanceUntilIdle()

        val snapshot = viewModel.uiState.value.mcpServers.firstOrNull { it.url == url }
        assertTrue("snapshot for $url must exist", snapshot != null)
        assertEquals(listOf(sample), snapshot!!.tools)
    }

    @Test
    fun `appearing server triggers fetchToolList with the persisted config`() = runTest {
        val url = "https://example.com/mcp"
        val config = McpServerConfig(url = url, headers = mapOf("Authorization" to "Bearer xyz"))
        val sample = McpTool(
            id = "mcp:abcd1234:search",
            serverUrl = url,
            name = "search",
            description = "Web search",
            inputSchemaJson = "{}",
        )
        coEvery {
            mcpServerRepository.fetchToolList(config = config, forceRefresh = false)
        } returns Result.success(listOf(sample))

        mcpServersFlow.value = listOf(config)
        advanceUntilIdle()

        coVerify { mcpServerRepository.fetchToolList(config = config, forceRefresh = false) }
        val snapshot = viewModel.uiState.value.mcpServers.first()
        assertEquals(url, snapshot.url)
        assertEquals(listOf(sample), snapshot.tools)
    }

    @Test
    fun `connection error surfaces in the snapshot status`() = runTest {
        val url = "https://broken.example/mcp"
        mcpServersFlow.value = listOf(McpServerConfig(url = url))
        advanceUntilIdle()

        statusFlowFor(url).value = McpConnectionStatus.Error(reason = "boom")
        advanceUntilIdle()

        val snapshot = viewModel.uiState.value.mcpServers.first()
        assertTrue(snapshot.status is McpConnectionStatus.Error)
        assertEquals("boom", (snapshot.status as McpConnectionStatus.Error).reason)
    }

    @Test
    fun `removeMcpServer drops the snapshot`() = runTest {
        val url = "http://old.com"
        mcpServersFlow.value = listOf(McpServerConfig(url = url))
        advanceUntilIdle()

        viewModel.removeMcpServer(url = url)
        mcpServersFlow.value = emptyList()
        advanceUntilIdle()

        coVerify { settingsRepository.removeMcpServer(url = url) }
        coVerify { mcpServerRepository.disconnect(serverUrl = url) }
        assertTrue(viewModel.uiState.value.mcpServers.isEmpty())
    }

    @Test
    fun `edited config for an existing URL triggers a forced refetch`() = runTest {
        // Regression guard for the on-save-edit flow: after the user changes
        // auth headers or transport on a known URL, reconcileServerSet must
        // re-fetch with forceRefresh=true so the UI reflects the new
        // connection state without a manual Refresh tap.
        val url = "https://hf.example/mcp"
        val original = McpServerConfig(url = url)
        val edited = McpServerConfig(url = url, headers = mapOf("Authorization" to "Bearer t"))
        mcpServersFlow.value = listOf(original)
        advanceUntilIdle()

        mcpServersFlow.value = listOf(edited)
        advanceUntilIdle()

        coVerify { mcpServerRepository.fetchToolList(config = edited, forceRefresh = true) }
    }

    @Test
    fun `refreshServer forces a fresh fetchToolList with the persisted config`() = runTest {
        val url = "https://refresh.example/mcp"
        val config = McpServerConfig(url = url, headers = mapOf("X-Test" to "1"))
        mcpServersFlow.value = listOf(config)
        advanceUntilIdle()

        viewModel.refreshServer(serverUrl = url)
        advanceUntilIdle()

        coVerify { mcpServerRepository.fetchToolList(config = config, forceRefresh = true) }
    }

    @Test
    fun `toggleServerExpanded flips the membership in expandedServerUrls`() = runTest {
        val url = "https://expand.example/mcp"
        mcpServersFlow.value = listOf(McpServerConfig(url = url))
        advanceUntilIdle()

        viewModel.toggleServerExpanded(serverUrl = url)
        assertEquals(setOf(url), viewModel.uiState.value.expandedServerUrls)
        viewModel.toggleServerExpanded(serverUrl = url)
        assertTrue(viewModel.uiState.value.expandedServerUrls.isEmpty())
    }

    @Test
    fun `toggleLocalTool enables previously-disabled function`() = runTest {
        disabledAppFunctionsFlow.value = setOf("get_system_time")
        advanceUntilIdle()

        viewModel.toggleLocalTool(toolName = "get_system_time", isEnabled = true)
        advanceUntilIdle()

        coVerify { settingsRepository.setDisabledAppFunctions(functions = emptySet()) }
    }

    @Test
    fun `toggleMcpTool writes to disabledMcpTools`() = runTest {
        val id = "mcp:abcd1234:search"
        viewModel.toggleMcpTool(toolId = id, isEnabled = false)
        advanceUntilIdle()

        coVerify { settingsRepository.setDisabledMcpTools(toolIds = setOf(id)) }
    }

    @Test
    fun `findMcpTool returns the matching tool or null`() = runTest {
        val url = "https://lookup.example/mcp"
        val config = McpServerConfig(url = url)
        val tool = McpTool(
            id = "mcp:deadbeef:search",
            serverUrl = url,
            name = "search",
            description = "Web search",
            inputSchemaJson = "{}",
        )
        coEvery {
            mcpServerRepository.fetchToolList(config = config, forceRefresh = false)
        } returns Result.success(listOf(tool))

        mcpServersFlow.value = listOf(config)
        advanceUntilIdle()

        assertEquals(tool, viewModel.findMcpTool(toolId = tool.id))
        assertNull(viewModel.findMcpTool(toolId = "mcp:deadbeef:missing"))
    }

    @Test
    fun `risk overrides are observed into state`() = runTest {
        toolRiskOverridesFlow.value = mapOf("mcp:deadbeef:search" to ToolRisk.READ_ONLY)
        advanceUntilIdle()

        assertEquals(
            mapOf("mcp:deadbeef:search" to ToolRisk.READ_ONLY),
            viewModel.uiState.value.toolRiskOverrides,
        )
    }

    @Test
    fun `setToolRisk persists the user's decision under the gate's own lookup key`() = runTest {
        advanceUntilIdle()

        // The key matters more than the value: `ToolRepositoryImpl.getRisk`
        // looks MCP tools up by the server-scoped id, so writing the bare name
        // here would persist an entry the gate never reads.
        viewModel.setToolRisk(toolKey = "mcp:deadbeef:search", risk = ToolRisk.DESTRUCTIVE)
        advanceUntilIdle()

        coVerify {
            settingsRepository.setToolRiskOverride(toolKey = "mcp:deadbeef:search", risk = ToolRisk.DESTRUCTIVE)
        }
    }
}
