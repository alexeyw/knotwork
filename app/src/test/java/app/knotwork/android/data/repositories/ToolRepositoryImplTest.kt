package app.knotwork.android.data.repositories

import app.knotwork.android.data.mcp.McpClient
import app.knotwork.android.data.mcp.McpClientFactory
import app.knotwork.android.data.mcp.McpConnectionPool
import app.knotwork.android.data.tools.local.LocalAppFunctionManager
import app.knotwork.android.data.tools.local.SearchTool
import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.McpAuth
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.ToolExecutionContext
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.LocalToolExecutor
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolRepositoryImplTest {

    private val settingsRepository: SettingsRepository = mockk()
    private val mcpClientFactory: McpClientFactory = mockk()
    private val mcpClient: McpClient = mockk()
    private val localAppFunctionManager: LocalAppFunctionManager = mockk()
    private val apiKeyRepository: ApiKeyRepository = mockk()
    private val searchTool: SearchTool = mockk(relaxed = true)
    private val scheduleTaskExecutor: LocalToolExecutor = mockk(relaxed = true)
    private val searchToolExecutor: LocalToolExecutor = mockk(relaxed = true)

    private lateinit var repository: ToolRepositoryImpl

    @Before
    fun setup() {
        every { mcpClientFactory.create() } returns mcpClient
        every { settingsRepository.mcpServers } returns flowOf(listOf(McpServerConfig(url = "http://localhost:8080")))
        every { settingsRepository.disabledAppFunctions } returns flowOf(emptySet())
        every { settingsRepository.disabledMcpTools } returns flowOf(emptySet())
        every { settingsRepository.toolRiskOverrides } returns flowOf(emptyMap())
        // The fixture server is unencrypted loopback, so the pool's cleartext gate
        // would refuse it unless the user had approved that origin — which is
        // exactly what someone running a local MCP server would have done.
        every { settingsRepository.approvedCleartextOrigins } returns flowOf(
            setOf(
                "http://localhost:8080",
                "http://10.0.0.1:8080",
                "http://10.0.0.2:8080",
                "http://10.0.0.3:8080",
                "http://10.0.0.4:8080",
                "http://10.0.0.5:8080",
            ),
        )
        // http_request stays hidden from getAvailableTools unless a test opts a domain in.
        every { settingsRepository.allowedHttpDomains } returns flowOf(emptyList())
        every { settingsRepository.blockNetworkFromLocalModel } returns flowOf(false)
        coEvery { localAppFunctionManager.getAvailableFunctions() } returns
            listOf(AgentTool("get_system_time", "desc", "{}"))
        // mockk picks the most recent matching stub: default everything to "not discovered"
        // and then override the specific names the tests rely on.
        coEvery { localAppFunctionManager.isDiscovered(any()) } returns false
        coEvery { localAppFunctionManager.isDiscovered("get_system_time") } returns true
        coEvery { mcpClient.connect(any()) } returns Unit
        coEvery { mcpClient.disconnect() } returns Unit

        every { apiKeyRepository.getOpenAIKey() } returns flowOf(null)
        every { apiKeyRepository.getAnthropicKey() } returns flowOf(null)
        every { apiKeyRepository.getGoogleKey() } returns flowOf(null)
        every { apiKeyRepository.getDeepSeekKey() } returns flowOf(null)
        every { apiKeyRepository.getOllamaBaseUrl() } returns flowOf(null)

        every { searchTool.asAgentTool() } returns AgentTool("search_tool", "desc", "{}")
        every { scheduleTaskExecutor.toolName } returns "schedule_task"
        every { searchToolExecutor.toolName } returns "search_tool"

        // Hilt would normally inject this map; in unit tests we build it explicitly so we
        // can verify which executor (if any) gets dispatched to for a given tool name.
        val executorMap = mapOf(
            "schedule_task" to scheduleTaskExecutor,
            "search_tool" to searchToolExecutor,
        )

        repository = ToolRepositoryImpl(
            settingsRepository,
            McpConnectionPool(mcpClientFactory, settingsRepository),
            localAppFunctionManager,
            apiKeyRepository,
            searchTool,
            executorMap,
        )
    }

    @Test
    fun `getAvailableTools includes discovered AppFunctions alongside built-in and mcp`() = runTest {
        // Discovered AppFunctions (e.g. `get_system_time`) are now part
        // of the advertised catalogue, alongside built-in tools (`schedule_task`,
        // `search_tool`) and MCP-side tools.
        val mcpTools = listOf(AgentTool("test_mcp", "desc", "params"))
        coEvery { mcpClient.getTools() } returns mcpTools

        val result = repository.getAvailableTools()

        assertTrue(result.any { it.name == "schedule_task" })
        assertTrue(result.any { it.name == "search_tool" })
        assertTrue(result.any { it.name == "test_mcp" })
        assertTrue(result.any { it.name == "get_system_time" })
        // Built-ins precede AppFunctions for prompt-engineering stability.
        val builtinIndex = result.indexOfFirst { it.name == "search_tool" }
        val appFunctionIndex = result.indexOfFirst { it.name == "get_system_time" }
        assertTrue(builtinIndex < appFunctionIndex)
        coVerify(exactly = 1) { mcpClient.getTools() }
    }

    @Test
    fun `getAvailableTools drops AppFunction that collides with built-in name`() = runTest {
        // A discovered AppFunction whose id collides with a built-in must not leak in:
        // the built-in version (with its registered executor and risk) keeps winning.
        coEvery { localAppFunctionManager.getAvailableFunctions() } returns listOf(
            AgentTool("schedule_task", "imposter description", "{}"),
            AgentTool("get_system_time", "desc", "{}"),
        )
        coEvery { mcpClient.getTools() } returns emptyList()

        val result = repository.getAvailableTools()

        val scheduleEntries = result.filter { it.name == "schedule_task" }
        assertEquals(1, scheduleEntries.size)
        // The surviving entry is the built-in, not the discovered impostor.
        assertFalse(scheduleEntries.single().description == "imposter description")
        // Non-colliding AppFunctions still come through.
        assertTrue(result.any { it.name == "get_system_time" })
    }

    @Test
    fun `executeTool executes mcp tool if not local`() = runTest {
        val name = "test_mcp"
        val args = "{\"key\":\"value\"}"
        val expectedResult = "success"

        coEvery { mcpClient.getTools() } returns listOf(AgentTool("test_mcp", "desc", "params"))
        coEvery { mcpClient.executeTool(name, args) } returns expectedResult

        val result = repository.executeTool(name, args)

        assertEquals(expectedResult, result)
        coVerify(exactly = 1) { mcpClient.executeTool(name, args) }
    }

    @Test
    fun `executeTool throws with tool name interpolated when tool is disabled`() = runTest {
        // Use a built-in tool name here — AppFunctions are not advertised by
        // ToolRepositoryImpl any more, so the disabled-check path is reachable only via
        // built-ins (or, in the future, AppFunctions once execution is wired up).
        val toolName = "schedule_task"
        every { settingsRepository.disabledAppFunctions } returns flowOf(setOf(toolName))
        coEvery { mcpClient.getTools() } returns emptyList()

        val exception = runCatching { repository.executeTool(toolName, "{}") }
            .exceptionOrNull()

        assertTrue(exception is IllegalArgumentException)
        assertTrue(exception!!.message!!.contains(toolName))
        assertTrue(exception.message!!.contains("disabled"))
    }

    @Test
    fun `executeTool throws with tool name interpolated when tool not found`() = runTest {
        val toolName = "nonexistent_tool"
        coEvery { mcpClient.getTools() } returns emptyList()

        val exception = runCatching { repository.executeTool(toolName, "{}") }
            .exceptionOrNull()

        assertTrue(exception is IllegalArgumentException)
        assertTrue(exception!!.message!!.contains(toolName))
    }

    @Test
    fun `executeTool dispatches to LocalToolExecutor registered under the tool name`() = runTest {
        // Defect 4 regression guard: a registered tool name must hit its executor and
        // forward the JSON arguments verbatim.
        coEvery { scheduleTaskExecutor.execute("{\"prompt\":\"do X\"}") } returns "scheduled"
        coEvery { mcpClient.getTools() } returns emptyList()

        val result = repository.executeTool("schedule_task", "{\"prompt\":\"do X\"}")

        assertEquals("scheduled", result)
        coVerify(exactly = 1) { scheduleTaskExecutor.execute("{\"prompt\":\"do X\"}") }
    }

    @Test
    fun `executeTool forwards the engine execution context to the local executor`() = runTest {
        // The context carries the trusted session identity (used by schedule_task
        // to bind the scheduled run back to its conversation) — it must reach the
        // executor unmodified.
        val context = ToolExecutionContext(sessionId = "session-3")
        coEvery { scheduleTaskExecutor.execute("{}", context) } returns "scheduled"
        coEvery { mcpClient.getTools() } returns emptyList()

        val result = repository.executeTool("schedule_task", "{}", context)

        assertEquals("scheduled", result)
        coVerify(exactly = 1) { scheduleTaskExecutor.execute("{}", context) }
    }

    @Test
    fun `executeTool dispatches AppFunction via LocalAppFunctionManager`() = runTest {
        // The discovered-AppFunction branch delegates the entire
        // codec + ExecuteAppFunctionRequest + system-call pipeline to the manager. The
        // repository only forwards the verbatim arguments string and returns the rendered
        // result. All Android AppFunctions types stay encapsulated behind invokeByName.
        val toolName = "get_system_time"
        val arguments = "{\"locale\":\"en\"}"
        coEvery { localAppFunctionManager.invokeByName(toolName, arguments) } returns
            "{\"result\":\"2026-05-14T10:00:00\"}"
        coEvery { mcpClient.getTools() } returns emptyList()

        val result = repository.executeTool(toolName, arguments)

        assertEquals("{\"result\":\"2026-05-14T10:00:00\"}", result)
        coVerify(exactly = 1) { localAppFunctionManager.invokeByName(toolName, arguments) }
    }

    @Test
    fun `executeTool propagates IllegalStateException raised by the AppFunction pipeline`() = runTest {
        // The manager wraps any AppFunctionException into IllegalStateException internally;
        // the repository must surface that without further wrapping or swallowing.
        val toolName = "get_system_time"
        coEvery { localAppFunctionManager.invokeByName(toolName, any()) } throws
            IllegalStateException("AppFunction $toolName failed: denied")
        coEvery { mcpClient.getTools() } returns emptyList()

        val exception = runCatching { repository.executeTool(toolName, "{}") }.exceptionOrNull()

        assertTrue("Expected IllegalStateException, got $exception", exception is IllegalStateException)
        assertTrue(exception!!.message!!.contains(toolName))
        assertTrue(exception.message!!.contains("denied"))
    }

    @Test
    fun `getAvailableTools filters MCP tools listed in disabledMcpTools`() = runTest {
        // Two MCP tools advertised; only one is in the disabled set. The disabled id matches
        // McpServerRepositoryImpl.mcpToolId(serverUrl, toolName) — keep the implementations
        // in sync if the id format ever changes.
        val serverUrl = "http://localhost:8080"
        val disabledId = McpServerRepositoryImpl.mcpToolId(serverUrl = serverUrl, toolName = "shell")
        coEvery { mcpClient.getTools() } returns listOf(
            AgentTool(name = "search", description = "Web search", parameters = "{}"),
            AgentTool(name = "shell", description = "Run shell", parameters = "{}"),
        )
        every { settingsRepository.disabledMcpTools } returns flowOf(setOf(disabledId))

        val available = repository.getAvailableTools()

        assertTrue("search must remain available", available.any { it.name == "search" })
        assertTrue("shell must be filtered out", available.none { it.name == "shell" })
    }

    @Test
    fun `executeTool throws when MCP tool is disabled`() = runTest {
        val serverUrl = "http://localhost:8080"
        val toolName = "shell"
        val disabledId = McpServerRepositoryImpl.mcpToolId(serverUrl = serverUrl, toolName = toolName)
        coEvery { mcpClient.getTools() } returns listOf(
            AgentTool(name = toolName, description = "Run shell", parameters = "{}"),
        )
        every { settingsRepository.disabledMcpTools } returns flowOf(setOf(disabledId))

        val exception = runCatching { repository.executeTool(toolName, "{}") }.exceptionOrNull()

        assertTrue("Expected IllegalArgumentException, got $exception", exception is IllegalArgumentException)
        assertTrue(exception!!.message!!.contains(toolName))
        assertTrue(exception.message!!.contains("disabled"))
        coVerify(exactly = 0) { mcpClient.executeTool(any(), any()) }
    }

    @Test
    fun `executeTool throws when AppFunction is disabled`() = runTest {
        val toolName = "get_system_time"
        every { settingsRepository.disabledAppFunctions } returns flowOf(setOf(toolName))
        coEvery { mcpClient.getTools() } returns emptyList()

        val exception = runCatching { repository.executeTool(toolName, "{}") }.exceptionOrNull()

        assertTrue(exception is IllegalArgumentException)
        assertTrue(exception!!.message!!.contains(toolName))
        assertTrue(exception.message!!.contains("disabled"))
        // Disabled AppFunctions must short-circuit before we hit the manager.
        coVerify(exactly = 0) { localAppFunctionManager.invokeByName(any(), any()) }
    }

    @Test
    fun `executeTool throws when local tool name is registered but has no executor`() = runTest {
        // Defect 4 regression guard for the silent-stub bug: if a tool is advertised as
        // a built-in but no LocalToolExecutor is wired up for it, the repository must
        // fail fast instead of returning a fake "Local tool executed: …" success string.
        // We use `delegate_task` because it is advertised only when at least one cloud
        // API key is configured, and the test setup deliberately omits it from the
        // executor map.
        every { apiKeyRepository.getAnthropicKey() } returns flowOf("anthropic-test-key")
        coEvery { mcpClient.getTools() } returns emptyList()
        val toolName = "delegate_task"

        val exception = runCatching { repository.executeTool(toolName, "{}") }
            .exceptionOrNull()

        assertTrue("Expected IllegalArgumentException, got $exception", exception is IllegalArgumentException)
        assertTrue(exception!!.message!!.contains(toolName))
        assertTrue(exception.message!!.contains("no executor registered"))
    }

    @Test
    fun `given builtin search_tool when getRisk then returns READ_ONLY`() = runTest {
        coEvery { mcpClient.getTools() } returns emptyList()

        val risk = repository.getRisk("search_tool")

        assertEquals(ToolRisk.READ_ONLY, risk)
    }

    @Test
    fun `given builtin schedule_task when getRisk then returns SENSITIVE`() = runTest {
        coEvery { mcpClient.getTools() } returns emptyList()

        val risk = repository.getRisk("schedule_task")

        assertEquals(ToolRisk.SENSITIVE, risk)
    }

    @Test
    fun `given builtin delegate_task when getRisk then returns SENSITIVE`() = runTest {
        // delegate_task is only advertised when a cloud API key is present.
        every { apiKeyRepository.getAnthropicKey() } returns flowOf("anthropic-test-key")
        coEvery { mcpClient.getTools() } returns emptyList()

        val risk = repository.getRisk("delegate_task")

        assertEquals(ToolRisk.SENSITIVE, risk)
    }

    @Test
    fun `given builtin read_file when getRisk then returns READ_ONLY`() = runTest {
        coEvery { mcpClient.getTools() } returns emptyList()

        assertEquals(ToolRisk.READ_ONLY, repository.getRisk("read_file"))
    }

    @Test
    fun `given builtin write_file when getRisk then returns SENSITIVE`() = runTest {
        coEvery { mcpClient.getTools() } returns emptyList()

        assertEquals(ToolRisk.SENSITIVE, repository.getRisk("write_file"))
    }

    @Test
    fun `given builtin edit_file when getRisk then returns SENSITIVE`() = runTest {
        coEvery { mcpClient.getTools() } returns emptyList()

        assertEquals(ToolRisk.SENSITIVE, repository.getRisk("edit_file"))
    }

    @Test
    fun `given builtin delete_file when getRisk then returns DESTRUCTIVE`() = runTest {
        coEvery { mcpClient.getTools() } returns emptyList()

        assertEquals(ToolRisk.DESTRUCTIVE, repository.getRisk("delete_file"))
    }

    @Test
    fun `given http_request GET when getRisk then returns SENSITIVE`() = runTest {
        assertEquals(
            ToolRisk.SENSITIVE,
            repository.getRisk("http_request", """{"method":"GET","url":"https://example.com"}"""),
        )
    }

    @Test
    fun `given http_request POST when getRisk then returns DESTRUCTIVE`() = runTest {
        assertEquals(
            ToolRisk.DESTRUCTIVE,
            repository.getRisk("http_request", """{"method":"POST","url":"https://example.com"}"""),
        )
    }

    @Test
    fun `given http_request with malformed args when getRisk then defaults to DESTRUCTIVE`() = runTest {
        assertEquals(ToolRisk.DESTRUCTIVE, repository.getRisk("http_request", "not json"))
    }

    @Test
    fun `given empty allowlist when getAvailableTools then http_request is hidden`() = runTest {
        coEvery { mcpClient.getTools() } returns emptyList()

        val result = repository.getAvailableTools()

        assertFalse(result.any { it.name == "http_request" })
    }

    @Test
    fun `given a configured allowlist when getAvailableTools then http_request is published`() = runTest {
        every { settingsRepository.allowedHttpDomains } returns flowOf(listOf("example.com"))
        coEvery { mcpClient.getTools() } returns emptyList()

        val result = repository.getAvailableTools()

        assertTrue(result.any { it.name == "http_request" })
    }

    @Test
    fun `given the network restriction is on when getAvailableTools then search_tool is withheld`() = runTest {
        // "Block network from local model" covers the built-in search tool: it is the one
        // network tool with neither an allowlist nor a confirmation gate, so the model is
        // not offered it at all while the restriction is on.
        every { settingsRepository.blockNetworkFromLocalModel } returns flowOf(true)
        coEvery { mcpClient.getTools() } returns emptyList()

        val result = repository.getAvailableTools()

        assertFalse(result.any { it.name == "search_tool" })
    }

    @Test
    fun `given the network restriction is off when getAvailableTools then search_tool is published`() = runTest {
        coEvery { mcpClient.getTools() } returns emptyList()

        val result = repository.getAvailableTools()

        assertTrue(result.any { it.name == "search_tool" })
    }

    @Test
    fun `given the network restriction is on when getAvailableTools then other tools are untouched`() = runTest {
        // The restriction withholds the tools that reach the network on their own, not the
        // catalogue: a workspace tool has nothing to do with it, and an MCP tool has its
        // own control (the restriction deliberately does not cover MCP servers).
        every { settingsRepository.blockNetworkFromLocalModel } returns flowOf(true)
        coEvery { mcpClient.getTools() } returns listOf(AgentTool("mcp_tool", "desc", "{}"))

        val result = repository.getAvailableTools()

        assertTrue(result.any { it.name == "read_file" })
        assertTrue(result.any { it.name == "mcp_tool" })
    }

    @Test
    fun `given the network restriction is on when getAllLocalTools then search_tool is still present`() = runTest {
        // Same split as http_request: the full catalogue keeps the tool so executeTool and
        // getRisk can still route to it; only the agent-facing view hides it.
        every { settingsRepository.blockNetworkFromLocalModel } returns flowOf(true)
        coEvery { mcpClient.getTools() } returns emptyList()

        assertTrue(repository.getAllLocalTools().any { it.name == "search_tool" })
    }

    @Test
    fun `given empty allowlist when getAllLocalTools then http_request is still present`() = runTest {
        // The full catalogue always contains http_request (so executeTool/getRisk can
        // route to it); only the agent-facing getAvailableTools hides it.
        coEvery { mcpClient.getTools() } returns emptyList()

        assertTrue(repository.getAllLocalTools().any { it.name == "http_request" })
    }

    @Test
    fun `given no cloud keys when getAllLocalTools then write tools are published with their risk`() = runTest {
        coEvery { mcpClient.getTools() } returns emptyList()

        val tools = repository.getAllLocalTools().associateBy { it.name }

        assertEquals(ToolRisk.SENSITIVE, tools.getValue("write_file").risk)
        assertEquals(ToolRisk.SENSITIVE, tools.getValue("edit_file").risk)
        assertEquals(ToolRisk.DESTRUCTIVE, tools.getValue("delete_file").risk)
    }

    @Test
    fun `given discovered AppFunction without override when getRisk then returns SENSITIVE`() = runTest {
        // Setup already mocks `localAppFunctionManager.getAvailableFunctions()` to return `get_system_time`
        // and `settingsRepository.toolRiskOverrides` to emit an empty map.
        coEvery { mcpClient.getTools() } returns emptyList()

        val risk = repository.getRisk("get_system_time")

        assertEquals(ToolRisk.SENSITIVE, risk)
    }

    @Test
    fun `given discovered AppFunction with READ_ONLY override when getRisk then returns READ_ONLY`() = runTest {
        every { settingsRepository.toolRiskOverrides } returns
            flowOf(mapOf("get_system_time" to ToolRisk.READ_ONLY))
        coEvery { mcpClient.getTools() } returns emptyList()

        val risk = repository.getRisk("get_system_time")

        assertEquals(ToolRisk.READ_ONLY, risk)
    }

    @Test
    fun `given discovered AppFunction with DESTRUCTIVE override when getRisk then returns DESTRUCTIVE`() = runTest {
        every { settingsRepository.toolRiskOverrides } returns
            flowOf(mapOf("get_system_time" to ToolRisk.DESTRUCTIVE))
        coEvery { mcpClient.getTools() } returns emptyList()

        val risk = repository.getRisk("get_system_time")

        assertEquals(ToolRisk.DESTRUCTIVE, risk)
    }

    @Test
    fun `given MCP tool when getRisk then returns SENSITIVE`() = runTest {
        coEvery { mcpClient.getTools() } returns listOf(AgentTool("remote_tool", "desc", "{}"))

        val risk = repository.getRisk("remote_tool")

        assertEquals(ToolRisk.SENSITIVE, risk)
    }

    @Test
    fun `given MCP tool with DESTRUCTIVE override when getRisk then returns DESTRUCTIVE`() = runTest {
        // Raising the gate. Before the override path reached MCP, `DESTRUCTIVE` was
        // unreachable by construction for every MCP tool — the blanket `SENSITIVE`
        // meant `blockDestructiveTools` could never apply to a remote tool.
        coEvery { mcpClient.getTools() } returns listOf(AgentTool("delete_repo", "desc", "{}"))
        every { settingsRepository.toolRiskOverrides } returns
            flowOf(mapOf(mcpKey("delete_repo") to ToolRisk.DESTRUCTIVE))

        val risk = repository.getRisk("delete_repo")

        assertEquals(ToolRisk.DESTRUCTIVE, risk)
    }

    @Test
    fun `given MCP tool with READ_ONLY override when getRisk then returns READ_ONLY`() = runTest {
        // Lowering the gate: a genuinely read-only remote tool should not prompt on
        // every call once the user has said so.
        coEvery { mcpClient.getTools() } returns listOf(AgentTool("list_issues", "desc", "{}"))
        every { settingsRepository.toolRiskOverrides } returns
            flowOf(mapOf(mcpKey("list_issues") to ToolRisk.READ_ONLY))

        val risk = repository.getRisk("list_issues")

        assertEquals(ToolRisk.READ_ONLY, risk)
    }

    @Test
    fun `given MCP override keyed by bare tool name when getRisk then it is ignored`() = runTest {
        // The key namespace is per server (`mcp:<sha8(url)>:<name>`), not the bare
        // name — otherwise an override meant for one server would silently follow the
        // same tool name onto every other connected server.
        coEvery { mcpClient.getTools() } returns listOf(AgentTool("create_issue", "desc", "{}"))
        every { settingsRepository.toolRiskOverrides } returns
            flowOf(mapOf("create_issue" to ToolRisk.READ_ONLY))

        val risk = repository.getRisk("create_issue")

        assertEquals(ToolRisk.SENSITIVE, risk)
    }

    @Test
    fun `given override for a different MCP server when getRisk then the local server keeps SENSITIVE`() = runTest {
        // Same tool name on two servers is normal in MCP catalogues. An override set
        // on the other server must not leak onto this one.
        coEvery { mcpClient.getTools() } returns listOf(AgentTool("create_issue", "desc", "{}"))
        val otherServerKey = McpServerRepositoryImpl.mcpToolId(
            serverUrl = "http://other-host:9000",
            toolName = "create_issue",
        )
        every { settingsRepository.toolRiskOverrides } returns
            flowOf(mapOf(otherServerKey to ToolRisk.READ_ONLY))

        val risk = repository.getRisk("create_issue")

        assertEquals(ToolRisk.SENSITIVE, risk)
    }

    /** Builds the per-server override key for the single MCP server the setup configures. */
    private fun mcpKey(toolName: String): String =
        McpServerRepositoryImpl.mcpToolId(serverUrl = "http://localhost:8080", toolName = toolName)

    @Test
    fun `given unknown tool when getRisk then throws IllegalArgumentException`() = runTest {
        coEvery { mcpClient.getTools() } returns emptyList()

        val exception = assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repository.getRisk("phantom_tool") }
        }
        assertTrue(exception.message!!.contains("phantom_tool"))
    }

    @Test
    fun `given override is set after first lookup when getRisk runs again then override wins`() = runTest {
        // Regression guard for accidental caching: the resolver must observe the latest
        // setting on every call, not the value captured at construction time.
        every { settingsRepository.toolRiskOverrides } returns flowOf(emptyMap())
        // The fixture server is unencrypted loopback, so the pool's cleartext gate
        // would refuse it unless the user had approved that origin — which is
        // exactly what someone running a local MCP server would have done.
        every { settingsRepository.approvedCleartextOrigins } returns flowOf(
            setOf(
                "http://localhost:8080",
                "http://10.0.0.1:8080",
                "http://10.0.0.2:8080",
                "http://10.0.0.3:8080",
                "http://10.0.0.4:8080",
                "http://10.0.0.5:8080",
            ),
        )
        coEvery { mcpClient.getTools() } returns emptyList()

        val firstRisk = repository.getRisk("get_system_time")
        assertEquals(ToolRisk.SENSITIVE, firstRisk)

        every { settingsRepository.toolRiskOverrides } returns
            flowOf(mapOf("get_system_time" to ToolRisk.READ_ONLY))

        val secondRisk = repository.getRisk("get_system_time")
        assertEquals(ToolRisk.READ_ONLY, secondRisk)
    }

    @Test
    fun `concurrent getAvailableTools and executeTool does not throw`() = runTest {
        val mcpTools = listOf(AgentTool("test_mcp", "desc", "params"))
        coEvery { mcpClient.getTools() } returns mcpTools
        coEvery { mcpClient.executeTool(any(), any()) } returns "success"

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val jobs = (1..100).map {
                launch {
                    if (it % 2 == 0) {
                        repository.getAvailableTools()
                    } else {
                        repository.executeTool("test_mcp", "{}")
                    }
                }
            }
            jobs.forEach { it.join() }
        }

        assertTrue(true) // Should reach here without exceptions
    }

    // ───────────────────────────────────────────────────────────────────
    // MCP routing fixes (3 regressions).
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `sync reconnects mcp client when auth changes for the same url`() = runTest {
        // Regression: syncMcpClients used to key the pool only by URL, so an
        // auth edit (None → Bearer) for an already-connected server was a
        // no-op and every subsequent tool call kept using stale credentials
        // until the process restarted.
        val url = "http://localhost:8080"
        val initial = McpServerConfig(url = url, auth = McpAuth.None)
        val updated = McpServerConfig(url = url, auth = McpAuth.Bearer(token = "fresh-token"))
        every { settingsRepository.mcpServers } returns flowOf(listOf(initial))
        coEvery { mcpClient.getTools() } returns emptyList()

        // First sync via getAvailableTools — should connect once with the
        // None config.
        repository.getAvailableTools()
        coVerify(exactly = 1) { mcpClient.connect(initial) }
        coVerify(exactly = 0) { mcpClient.disconnect() }

        // Persisted config now carries Bearer auth. Next sync MUST disconnect
        // the stale client and reconnect with the fresh credentials.
        every { settingsRepository.mcpServers } returns flowOf(listOf(updated))

        repository.getAvailableTools()

        coVerify(exactly = 1) { mcpClient.disconnect() }
        coVerify(exactly = 1) { mcpClient.connect(updated) }
    }

    @Test
    fun `executeTool falls through to next provider when first server has the tool disabled`() = runTest {
        // Regression: disabledMcpTools is scoped per server
        // (id = mcp:<sha8(url)>:<toolName>); two servers can advertise the
        // same tool name and only one's mcpId can be in the disabled set.
        // The old loop threw immediately on the first disabled hit, robbing
        // the enabled sibling of a chance to execute.
        val urlA = "http://10.0.0.1:8080"
        val urlB = "http://10.0.0.2:8080"
        val toolName = "shared_tool"
        val configA = McpServerConfig(url = urlA)
        val configB = McpServerConfig(url = urlB)
        val clientB: McpClient = mockk(relaxed = true)
        every { settingsRepository.mcpServers } returns flowOf(listOf(configA, configB))
        every { mcpClientFactory.create() } returnsMany listOf(mcpClient, clientB)
        coEvery { mcpClient.getTools() } returns
            listOf(AgentTool(name = toolName, description = "A", parameters = "{}"))
        coEvery { clientB.getTools() } returns
            listOf(AgentTool(name = toolName, description = "B", parameters = "{}"))
        coEvery { clientB.executeTool(toolName, any()) } returns "from-B"
        val disabledIdA = McpServerRepositoryImpl.mcpToolId(serverUrl = urlA, toolName = toolName)
        every { settingsRepository.disabledMcpTools } returns flowOf(setOf(disabledIdA))

        val result = repository.executeTool(toolName, "{}")

        assertEquals("from-B", result)
        coVerify(exactly = 0) { mcpClient.executeTool(toolName, any()) }
        coVerify(exactly = 1) { clientB.executeTool(toolName, "{}") }
    }

    @Test
    fun `executeTool throws disabled when every advertising provider has the tool disabled`() = runTest {
        // After the routing rewrite, sawDisabled must still produce the
        // "is disabled" failure shape when nobody else can run the tool.
        val urlA = "http://10.0.0.1:8080"
        val urlB = "http://10.0.0.2:8080"
        val toolName = "shared_tool"
        val clientB: McpClient = mockk(relaxed = true)
        every { settingsRepository.mcpServers } returns
            flowOf(listOf(McpServerConfig(url = urlA), McpServerConfig(url = urlB)))
        every { mcpClientFactory.create() } returnsMany listOf(mcpClient, clientB)
        coEvery { mcpClient.getTools() } returns
            listOf(AgentTool(name = toolName, description = "A", parameters = "{}"))
        coEvery { clientB.getTools() } returns
            listOf(AgentTool(name = toolName, description = "B", parameters = "{}"))
        every { settingsRepository.disabledMcpTools } returns flowOf(
            setOf(
                McpServerRepositoryImpl.mcpToolId(serverUrl = urlA, toolName = toolName),
                McpServerRepositoryImpl.mcpToolId(serverUrl = urlB, toolName = toolName),
            ),
        )

        val exception = runCatching { repository.executeTool(toolName, "{}") }.exceptionOrNull()

        assertTrue(exception is IllegalArgumentException)
        assertTrue(exception!!.message!!.contains(toolName))
        assertTrue(exception.message!!.contains("disabled"))
        coVerify(exactly = 0) { mcpClient.executeTool(any(), any()) }
        coVerify(exactly = 0) { clientB.executeTool(any(), any()) }
    }

    @Test
    fun `executeTool falls through to next provider when first server execute throws`() = runTest {
        // Regression: a single break inside the for-loop blew up
        // multi-provider resilience — one flaky server made every other
        // healthy provider unreachable. The fix is to keep walking.
        val urlA = "http://10.0.0.4:8080"
        val urlB = "http://10.0.0.5:8080"
        val toolName = "shared_tool"
        val clientB: McpClient = mockk(relaxed = true)
        every { settingsRepository.mcpServers } returns
            flowOf(listOf(McpServerConfig(url = urlA), McpServerConfig(url = urlB)))
        every { mcpClientFactory.create() } returnsMany listOf(mcpClient, clientB)
        coEvery { mcpClient.getTools() } returns
            listOf(AgentTool(name = toolName, description = "A", parameters = "{}"))
        coEvery { clientB.getTools() } returns
            listOf(AgentTool(name = toolName, description = "B", parameters = "{}"))
        coEvery { mcpClient.executeTool(toolName, any()) } throws RuntimeException("flaky upstream")
        coEvery { clientB.executeTool(toolName, any()) } returns "from-B"

        val result = repository.executeTool(toolName, "{}")

        assertEquals("from-B", result)
        coVerify(exactly = 1) { mcpClient.executeTool(toolName, "{}") }
        coVerify(exactly = 1) { clientB.executeTool(toolName, "{}") }
    }

    @Test
    fun `executeTool dispatches MCP tool exactly once when settings persists duplicate server URLs`() = runTest {
        // Regression: switching the MCP dispatch loop from
        // ConcurrentHashMap.entries (implicit URL-key dedup) to the raw
        // persisted list let a duplicate-URL row in settings (which
        // SettingsManager.updateMcpServer can produce by replacing-by-index
        // without checking for collisions) execute the same tool twice on
        // the same connected client per call — catastrophic for non-
        // idempotent side effects. distinctMcpConfigs() now keeps only the
        // first occurrence of each URL before iteration.
        val toolName = "shared_tool"
        val url = "http://10.0.0.3:8080"
        every { settingsRepository.mcpServers } returns flowOf(
            listOf(McpServerConfig(url = url), McpServerConfig(url = url)),
        )
        coEvery { mcpClient.getTools() } returns
            listOf(AgentTool(name = toolName, description = "T", parameters = "{}"))
        coEvery { mcpClient.executeTool(toolName, any()) } returns "once"

        val result = repository.executeTool(toolName, "{}")

        assertEquals("once", result)
        coVerify(exactly = 1) { mcpClient.executeTool(toolName, "{}") }
        coVerify(exactly = 1) { mcpClient.connect(any()) }
    }

    @Test
    fun `getAvailableTools deduplicates tools when settings persists duplicate server URLs`() = runTest {
        // Sibling regression: a duplicated URL row also produced duplicate
        // entries in the advertised tool catalogue, which would inflate the
        // agent's prompt and confuse the LLM's tool-selection pass.
        val url = "http://10.0.0.3:8080"
        val mcpTool = AgentTool(name = "shared_tool", description = "T", parameters = "{}")
        every { settingsRepository.mcpServers } returns flowOf(
            listOf(McpServerConfig(url = url), McpServerConfig(url = url)),
        )
        coEvery { mcpClient.getTools() } returns listOf(mcpTool)

        val result = repository.getAvailableTools()

        assertEquals(1, result.count { it.name == "shared_tool" })
    }

    @Test
    fun `executeTool rethrows last execute error when every advertising provider fails`() = runTest {
        // When nobody can serve the call, the agent gets a concrete cause
        // (network error / 5xx / parse failure) instead of a generic
        // "not found across active providers" — which would mislead the
        // operator into thinking the tool was never registered.
        val urlA = "http://10.0.0.1:8080"
        val urlB = "http://10.0.0.2:8080"
        val toolName = "shared_tool"
        val clientB: McpClient = mockk(relaxed = true)
        every { settingsRepository.mcpServers } returns
            flowOf(listOf(McpServerConfig(url = urlA), McpServerConfig(url = urlB)))
        every { mcpClientFactory.create() } returnsMany listOf(mcpClient, clientB)
        coEvery { mcpClient.getTools() } returns
            listOf(AgentTool(name = toolName, description = "A", parameters = "{}"))
        coEvery { clientB.getTools() } returns
            listOf(AgentTool(name = toolName, description = "B", parameters = "{}"))
        coEvery { mcpClient.executeTool(toolName, any()) } throws RuntimeException("first-failure")
        coEvery { clientB.executeTool(toolName, any()) } throws IllegalStateException("last-failure")

        val exception = runCatching { repository.executeTool(toolName, "{}") }.exceptionOrNull()

        assertTrue(
            "Expected the last execute failure to be rethrown, got $exception",
            exception is IllegalStateException,
        )
        assertEquals("last-failure", exception!!.message)
    }
}
