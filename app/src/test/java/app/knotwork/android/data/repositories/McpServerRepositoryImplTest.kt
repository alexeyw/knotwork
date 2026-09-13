package app.knotwork.android.data.repositories

import app.knotwork.android.data.mcp.McpClient
import app.knotwork.android.data.mcp.McpClientFactory
import app.knotwork.android.data.mcp.McpConnectionPool
import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.McpAuth
import app.knotwork.android.domain.models.McpConnectionStatus
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [McpServerRepositoryImpl]. The implementation owns four
 * behaviours the rest of the surface depends on:
 *
 *  1. mapping `AgentTool` results from `McpClient.getTools()` to
 *     `McpTool` with a stable, route-safe id;
 *  2. emitting `Connecting → Connected` on the status flow when a fetch
 *     succeeds;
 *  3. emitting `Connecting → Error` (and returning `Result.failure`) when
 *     the client throws;
 *  4. caching the tool list for 5 minutes — and bypassing the cache when
 *     `forceRefresh = true`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McpServerRepositoryImplTest {

    private val url = "https://example.invalid/mcp"
    private val config = McpServerConfig(url = url)

    @Test
    fun `fetchToolList parsesValidResponse and emits Connected status`() = runTest {
        val client = mockk<McpClient>(relaxed = true)
        coEvery { client.getTools() } returns listOf(
            AgentTool(
                name = "search",
                description = "Web search",
                parameters = "{\"type\":\"object\"}",
                risk = ToolRisk.READ_ONLY,
            ),
            AgentTool(name = "shell", description = "Run shell", parameters = "{}"),
        )
        val factory = mockk<McpClientFactory>()
        coEvery { factory.create() } returns client
        val repo = McpServerRepositoryImpl(pool = pool(factory))

        val result = repo.fetchToolList(config = config)

        assertTrue(result.isSuccess)
        val tools = result.getOrThrow()
        assertEquals(2, tools.size)
        val first = tools[0]
        assertEquals("search", first.name)
        assertEquals("Web search", first.description)
        assertEquals("{\"type\":\"object\"}", first.inputSchemaJson)
        assertEquals(ToolRisk.READ_ONLY, first.risk)
        assertEquals(url, first.serverUrl)
        assertTrue("id should start with mcp: prefix", first.id.startsWith("mcp:"))
        assertEquals(McpConnectionStatus.Connected, repo.observeConnectionStatus(url).first())
    }

    @Test
    fun `fetchToolList emitsErrorOnFailure and returns Result failure`() = runTest {
        val client = mockk<McpClient>(relaxed = true)
        coEvery { client.connect(any()) } throws IllegalStateException("handshake failed")
        val factory = mockk<McpClientFactory>()
        coEvery { factory.create() } returns client
        val repo = McpServerRepositoryImpl(pool = pool(factory))

        val result = repo.fetchToolList(config = config)

        assertTrue(result.isFailure)
        val status = repo.observeConnectionStatus(url).first()
        assertTrue("expected Error status, was $status", status is McpConnectionStatus.Error)
        assertEquals("handshake failed", (status as McpConnectionStatus.Error).reason)
    }

    @Test
    fun `fetchToolList returnsCachedWithinTtl`() = runTest {
        val client = mockk<McpClient>(relaxed = true)
        coEvery { client.getTools() } returns listOf(AgentTool("a", "d", "{}"))
        val factory = mockk<McpClientFactory>()
        coEvery { factory.create() } returns client
        val repo = McpServerRepositoryImpl(pool = pool(factory))
        var time = 1_000L
        repo.clockMs = { time }

        repo.fetchToolList(config = config)
        time += 60_000L // 1 minute later — well inside the 5-minute TTL.
        val cached = repo.fetchToolList(config = config)

        assertTrue(cached.isSuccess)
        coVerify(exactly = 1) { client.connect(config) }
        coVerify(exactly = 1) { client.getTools() }
    }

    @Test
    fun `fetchToolList forceRefreshBypassesCache`() = runTest {
        val client = mockk<McpClient>(relaxed = true)
        coEvery { client.getTools() } returns listOf(AgentTool("a", "d", "{}"))
        val factory = mockk<McpClientFactory>()
        coEvery { factory.create() } returns client
        val repo = McpServerRepositoryImpl(pool = pool(factory))
        repo.clockMs = { 1_000L }

        repo.fetchToolList(config = config)
        val refreshed = repo.fetchToolList(config = config, forceRefresh = true)

        assertTrue(refreshed.isSuccess)
        coVerify(exactly = 2) { client.getTools() }
    }

    @Test
    fun `given repeated refreshes when config is unchanged then the client connects only once`() = runTest {
        val client = mockk<McpClient>(relaxed = true)
        coEvery { client.getTools() } returns listOf(AgentTool("a", "d", "{}"))
        val factory = mockk<McpClientFactory>()
        coEvery { factory.create() } returns client
        val repo = McpServerRepositoryImpl(pool = pool(factory))

        repeat(times = 3) { repo.fetchToolList(config = config, forceRefresh = true) }

        // Reconnecting per refresh opened a fresh MCP session each time and left
        // the abandoned ones live on the server, while the in-place transport
        // swap could break a tool call running concurrently on the same client
        // (found by a directed on-device MCP test). A refresh re-lists tools; it
        // does not re-establish the connection.
        coVerify(exactly = 1) { client.connect(any()) }
        coVerify(exactly = 3) { client.getTools() }
    }

    @Test
    fun `given a changed config when fetching then the stale connection is replaced`() = runTest {
        val client = mockk<McpClient>(relaxed = true)
        coEvery { client.getTools() } returns listOf(AgentTool("a", "d", "{}"))
        val factory = mockk<McpClientFactory>()
        coEvery { factory.create() } returns client
        val repo = McpServerRepositoryImpl(pool = pool(factory))

        repo.fetchToolList(config = config)
        // Same URL (the pool key), different auth — the new credentials must
        // actually reach the server rather than being masked by a pooled
        // connection opened with the old ones.
        val reconfigured = config.copy(auth = McpAuth.Bearer(token = "t"))
        repo.fetchToolList(config = reconfigured, forceRefresh = true)

        coVerify(exactly = 1) { client.disconnect() }
        coVerify(exactly = 2) { client.connect(any()) }
    }

    @Test
    fun `given a failed fetch when retried then a fresh connection is established`() = runTest {
        val client = mockk<McpClient>(relaxed = true)
        coEvery { client.getTools() } throws IllegalStateException("boom") andThen listOf(AgentTool("a", "d", "{}"))
        val factory = mockk<McpClientFactory>()
        coEvery { factory.create() } returns client
        val repo = McpServerRepositoryImpl(pool = pool(factory))

        val failed = repo.fetchToolList(config = config)
        val retried = repo.fetchToolList(config = config, forceRefresh = true)

        assertTrue(failed.isFailure)
        assertTrue(retried.isSuccess)
        // Not caching the broken entry is what keeps "connect once" from turning
        // into "never reconnect after a blip".
        coVerify(exactly = 2) { client.connect(any()) }
    }

    @Test
    fun `given a fetch cancelled mid-flight then the status does not stay Connecting`() = runTest {
        val client = mockk<McpClient>(relaxed = true)
        val started = CompletableDeferred<Unit>()
        // Suspend forever inside the fetch so the caller can be cancelled while
        // the status still reads Connecting.
        coEvery { client.getTools() } coAnswers {
            started.complete(Unit)
            awaitCancellation()
        }
        val factory = mockk<McpClientFactory>()
        coEvery { factory.create() } returns client
        val repo = McpServerRepositoryImpl(pool = pool(factory))

        val job = launch { repo.fetchToolList(config = config) }
        started.await()
        assertEquals(McpConnectionStatus.Connecting, repo.observeConnectionStatus(url).first())
        job.cancelAndJoin()

        // A row pinned on "Connecting…" reads as "still trying" forever, and
        // only a manual Refresh cleared it.
        assertTrue(
            "cancelled fetch must not leave the row pinned on Connecting",
            repo.observeConnectionStatus(url).first() != McpConnectionStatus.Connecting,
        )
    }

    @Test
    fun `disconnect drops the cache and closes the client`() = runTest {
        val client = mockk<McpClient>(relaxed = true)
        coEvery { client.getTools() } returns listOf(AgentTool("a", "d", "{}"))
        val factory = mockk<McpClientFactory>()
        coEvery { factory.create() } returns client
        val repo = McpServerRepositoryImpl(pool = pool(factory))

        repo.fetchToolList(config = config)
        repo.disconnect(serverUrl = url)
        repo.fetchToolList(config = config)

        coVerify { client.disconnect() }
        // A fresh fetch after disconnect must hit `connect` again because the
        // cache and client entry were dropped.
        coVerify(exactly = 2) { client.connect(config) }
    }

    @Test
    fun `cache fast-path clears a previous Error status`() = runTest {
        // After a failed force-refresh has put the status flow in Error, a follow-up
        // cached-tool fetch must reconcile the flow back to Connected so the UI doesn't
        // leave a stale red pill on a server whose tools are still available from cache.
        val client = mockk<McpClient>(relaxed = true)
        coEvery { client.getTools() } returns listOf(AgentTool("a", "d", "{}"))
        val factory = mockk<McpClientFactory>()
        coEvery { factory.create() } returns client
        val repo = McpServerRepositoryImpl(pool = pool(factory))
        repo.clockMs = { 1_000L }

        // 1) First fetch succeeds → cache populated, status = Connected.
        repo.fetchToolList(config = config)
        // 2) Force-refresh fails → status flips to Error, but cache remains.
        coEvery { client.getTools() } throws IllegalStateException("transient")
        repo.fetchToolList(config = config, forceRefresh = true)
        assertTrue(repo.observeConnectionStatus(url).first() is McpConnectionStatus.Error)

        // 3) Non-forced fetch within TTL → cache hit → status must reconcile to Connected.
        val cached = repo.fetchToolList(config = config, forceRefresh = false)
        assertTrue(cached.isSuccess)
        assertEquals(McpConnectionStatus.Connected, repo.observeConnectionStatus(url).first())
    }

    @Test
    fun `disconnect keeps the status flow so live observers see future emissions`() = runTest {
        // Regression guard: removing the MutableStateFlow on disconnect orphans
        // any in-flight collector — `getOrPut` would then return a brand-new
        // flow instance to the next fetchToolList caller, while the original
        // collector stayed pinned to the previous (now never-updating) flow.
        val client = mockk<McpClient>(relaxed = true)
        coEvery { client.getTools() } returns listOf(AgentTool("a", "d", "{}"))
        val factory = mockk<McpClientFactory>()
        coEvery { factory.create() } returns client
        val repo = McpServerRepositoryImpl(pool = pool(factory))

        // Acquire the status flow first — this is what a live observer holds.
        val statusFlow = repo.observeConnectionStatus(url)
        repo.fetchToolList(config = config)
        assertEquals(McpConnectionStatus.Connected, statusFlow.first())

        // Simulate the edit flow: disconnect drops the cached client + resets
        // the status flow to Connecting (same instance), then the next fetch
        // transitions Connecting → Connected on the very same flow.
        repo.disconnect(serverUrl = url)
        assertEquals(McpConnectionStatus.Connecting, statusFlow.first())

        repo.fetchToolList(config = config)
        assertEquals(McpConnectionStatus.Connected, statusFlow.first())
    }

    @Test
    fun `mcpToolId is deterministic and route-safe`() {
        val id1 = McpServerRepositoryImpl.mcpToolId(serverUrl = url, toolName = "search")
        val id2 = McpServerRepositoryImpl.mcpToolId(serverUrl = url, toolName = "search")
        assertEquals(id1, id2)
        assertNotNull(id1)
        assertTrue(id1.startsWith("mcp:"))
        // No raw URL characters that Navigation would mishandle in a path arg.
        assertTrue(!id1.contains("/"))
        assertTrue(!id1.contains("?"))
    }
}

/**
 * Builds the pool these tests drive. Every fixture here uses `https://`, so the
 * pool's cleartext gate classifies them as "nothing to gate" and no approved
 * origin is needed — the gate is covered by `CleartextPolicyTest` and by
 * `McpConnectionPoolTest`.
 */
private fun pool(factory: McpClientFactory): McpConnectionPool {
    val settings = mockk<SettingsRepository>()
    every { settings.approvedCleartextOrigins } returns flowOf(emptySet())
    return McpConnectionPool(clientFactory = factory, settingsRepository = settings)
}
