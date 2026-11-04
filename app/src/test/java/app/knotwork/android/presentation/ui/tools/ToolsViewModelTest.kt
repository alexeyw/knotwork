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
import app.knotwork.android.domain.services.McpToolRouting
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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

        coVerify { settingsRepository.setAppFunctionsEnabled(functions = setOf("get_system_time"), enabled = true) }
    }

    @Test
    fun `toggleMcpTool writes to disabledMcpTools`() = runTest {
        val id = "mcp:abcd1234:search"
        viewModel.toggleMcpTool(toolId = id, isEnabled = false)
        advanceUntilIdle()

        coVerify { settingsRepository.setMcpToolsEnabled(toolIds = setOf(id), enabled = false) }
    }

    @Test
    fun `given two MCP switches flipped before the store answers when both land then both are kept`() = runTest {
        // Given — the screen's copy of the set lags a write behind, as it does on a device
        storeAppliesSwitchesAtomically()
        advanceUntilIdle()

        // When
        viewModel.toggleMcpTool(toolId = "mcp:abcd1234:a", isEnabled = false)
        viewModel.toggleMcpTool(toolId = "mcp:abcd1234:b", isEnabled = false)
        advanceUntilIdle()

        // Then — a set computed from the screen's copy kept only the second
        assertEquals(setOf("mcp:abcd1234:a", "mcp:abcd1234:b"), disabledMcpToolsFlow.value)
    }

    @Test
    fun `given two local switches flipped before the store answers when both land then both are kept`() = runTest {
        // Given
        storeAppliesSwitchesAtomically()
        advanceUntilIdle()

        // When
        viewModel.toggleLocalTool(toolName = "com.x/a", isEnabled = false)
        viewModel.toggleLocalTool(toolName = "com.x/b", isEnabled = false)
        advanceUntilIdle()

        // Then
        assertEquals(setOf("com.x/a", "com.x/b"), disabledAppFunctionsFlow.value)
    }

    @Test
    fun `given two servers when one server's tools are switched off then one write carries exactly its tools`() =
        runTest {
            // Given
            val first = givenServer(url = FIRST_URL, hash = "aaaa1111", "search", "fetch", "list")
            givenServer(url = SECOND_URL, hash = "bbbb2222", "search")
            storeAppliesSwitchesAtomically()
            advanceUntilIdle()

            // When
            viewModel.setServerToolsEnabled(serverUrl = FIRST_URL, enabled = false)
            advanceUntilIdle()

            // Then
            val firstIds = first.mapTo(mutableSetOf()) { it.id }
            coVerify(exactly = 1) { settingsRepository.setMcpToolsEnabled(any(), any()) }
            coVerify(exactly = 1) { settingsRepository.setMcpToolsEnabled(toolIds = firstIds, enabled = false) }
            assertEquals(firstIds, disabledMcpToolsFlow.value)
        }

    @Test
    fun `given a server when all its tools are switched off or on then the set equals switching each alone`() =
        runTest {
            // Given — another server's tool and a tool this server no longer lists are already off
            val tools = givenServer(url = FIRST_URL, hash = "aaaa1111", "search", "fetch", "list")
            val before = setOf("mcp:bbbb2222:search", "mcp:aaaa1111:gone")
            storeAppliesSwitchesAtomically()
            disabledMcpToolsFlow.value = before
            advanceUntilIdle()

            // When — one by one
            tools.forEach { viewModel.toggleMcpTool(toolId = it.id, isEnabled = false) }
            advanceUntilIdle()
            val oneByOne = disabledMcpToolsFlow.value
            disabledMcpToolsFlow.value = before
            advanceUntilIdle()
            // When — all at once
            viewModel.setServerToolsEnabled(serverUrl = FIRST_URL, enabled = false)
            advanceUntilIdle()
            val allOff = disabledMcpToolsFlow.value
            viewModel.setServerToolsEnabled(serverUrl = FIRST_URL, enabled = true)
            advanceUntilIdle()

            // Then — the tool the server no longer lists keeps its state either way
            assertEquals(oneByOne, allOff)
            assertEquals(before + tools.map { it.id }, allOff)
            assertEquals(before, disabledMcpToolsFlow.value)
        }

    @Test
    fun `given a collapsed server when all its tools are switched then its row expands`() = runTest {
        // Given
        givenServer(url = FIRST_URL, hash = "aaaa1111", "search")
        advanceUntilIdle()

        // When
        viewModel.setServerToolsEnabled(serverUrl = FIRST_URL, enabled = false)

        // Then
        assertEquals(setOf(FIRST_URL), viewModel.uiState.value.expandedServerUrls)
    }

    @Test
    fun `given a server that lists no tools when all its tools are switched then nothing changes`() = runTest {
        // Given — fetchToolList answers an empty list by default
        mcpServersFlow.value = listOf(McpServerConfig(url = FIRST_URL))
        advanceUntilIdle()

        // When — the listed server, and one the screen does not know
        viewModel.setServerToolsEnabled(serverUrl = FIRST_URL, enabled = false)
        viewModel.setServerToolsEnabled(serverUrl = "https://unknown.example/mcp", enabled = false)
        advanceUntilIdle()

        // Then
        coVerify(exactly = 0) { settingsRepository.setMcpToolsEnabled(any(), any()) }
        assertTrue(viewModel.uiState.value.expandedServerUrls.isEmpty())
    }

    @Test
    fun `given two servers sharing a name when the first switches all off then the second serves the name`() = runTest {
        // Given — the second server's `search` is shadowed by the first's
        givenServer(url = FIRST_URL, hash = "aaaa1111", "search")
        val second = givenServer(url = SECOND_URL, hash = "bbbb2222", "search").single()
        storeAppliesSwitchesAtomically()
        advanceUntilIdle()
        assertEquals(
            McpToolRouting.Shadow.EarlierServer(serverUrl = FIRST_URL),
            viewModel.uiState.value.mcpShadowing[second.id],
        )

        // When
        viewModel.setServerToolsEnabled(serverUrl = FIRST_URL, enabled = false)
        advanceUntilIdle()

        // Then — as when the first server's `search` is switched off alone
        assertNull(viewModel.uiState.value.mcpShadowing[second.id])
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

    /**
     * Makes the settings mock apply each switch to the set it holds, as the
     * DataStore transaction does, and publish the result through the flow the
     * view model observes.
     */
    private fun storeAppliesSwitchesAtomically() {
        coEvery { settingsRepository.setMcpToolsEnabled(any(), any()) } answers {
            val ids = firstArg<Set<String>>()
            disabledMcpToolsFlow.update { if (secondArg<Boolean>()) it - ids else it + ids }
        }
        coEvery { settingsRepository.setAppFunctionsEnabled(any(), any()) } answers {
            val names = firstArg<Set<String>>()
            disabledAppFunctionsFlow.update { if (secondArg<Boolean>()) it - names else it + names }
        }
    }

    /**
     * Adds a server whose `tools/list` answers [names], after any servers
     * already added, and returns its tools.
     */
    private fun givenServer(url: String, hash: String, vararg names: String): List<McpTool> {
        val config = McpServerConfig(url = url)
        val tools = names.map { name ->
            McpTool(id = "mcp:$hash:$name", serverUrl = url, name = name, description = "", inputSchemaJson = "{}")
        }
        coEvery { mcpServerRepository.fetchToolList(config = config, forceRefresh = false) } returns
            Result.success(tools)
        mcpServersFlow.update { it + config }
        return tools
    }

    private companion object {
        const val FIRST_URL = "https://first.example/mcp"
        const val SECOND_URL = "https://second.example/mcp"
    }
}
