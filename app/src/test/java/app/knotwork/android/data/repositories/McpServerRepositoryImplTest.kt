package app.knotwork.android.data.repositories

import app.knotwork.android.data.mcp.McpClient
import app.knotwork.android.data.mcp.McpClientFactory
import app.knotwork.android.data.mcp.McpConnectionPool
import app.knotwork.android.data.mcp.McpSessionExpiredException
import app.knotwork.android.domain.connection.ConnectionFailure
import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.McpAuth
import app.knotwork.android.domain.models.McpConnectionStatus
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.repositories.McpServerRepository
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
import org.junit.Assert.assertSame
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
 *  4. caching the tool list for 5 minutes, only while the connection it came
 *     from is still pooled — and asking the live connection again
 *     (`McpClient.refreshTools`) when the cache does not answer or
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
        // Classified as the form's check classifies it, so the screen words it the same way.
        assertEquals(ConnectionFailure.Other("handshake failed"), (status as McpConnectionStatus.Error).failure)
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
        coEvery { client.refreshTools() } returns listOf(AgentTool("a", "d", "{}"), AgentTool("b", "d", "{}"))
        val factory = mockk<McpClientFactory>()
        coEvery { factory.create() } returns client
        val repo = McpServerRepositoryImpl(pool = pool(factory))
        repo.clockMs = { 1_000L }

        repo.fetchToolList(config = config)
        val refreshed = repo.fetchToolList(config = config, forceRefresh = true)

        // The refresh asks the server again over the live connection — `getTools` only
        // repeats the list the handshake got, which is how a refresh used to send nothing.
        assertEquals(listOf("a", "b"), refreshed.getOrThrow().map { it.name })
        coVerify(exactly = 1) { client.getTools() }
        coVerify(exactly = 1) { client.refreshTools() }
    }

    @Test
    fun `given the TTL has passed when fetched then the server is asked again over the live connection`() = runTest {
        val client = mockk<McpClient>(relaxed = true)
        coEvery { client.getTools() } returns listOf(AgentTool("a", "d", "{}"))
        coEvery { client.refreshTools() } returns listOf(AgentTool("a", "d", "{}"))
        val factory = mockk<McpClientFactory>()
        coEvery { factory.create() } returns client
        val repo = McpServerRepositoryImpl(pool = pool(factory))
        var time = 1_000L
        repo.clockMs = { time }

        repo.fetchToolList(config = config)
        time += McpServerRepository.TOOL_LIST_TTL_MS + 1
        val result = repo.fetchToolList(config = config)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { client.connect(config) }
        coVerify(exactly = 1) { client.refreshTools() }
    }

    @Test
    fun `given a list cached with one config when another config fetches within the TTL then the server is asked`() =
        runTest {
            // Given — two clients, as the factory hands out a new one per connection
            val first = mockk<McpClient>(relaxed = true)
            coEvery { first.getTools() } returns listOf(AgentTool("a", "d", "{}"))
            val second = mockk<McpClient>(relaxed = true)
            coEvery { second.getTools() } returns listOf(AgentTool("a", "d", "{}"))
            val factory = mockk<McpClientFactory>()
            coEvery { factory.create() } returnsMany listOf(first, second)
            val repo = McpServerRepositoryImpl(pool = pool(factory))
            repo.clockMs = { 1_000L }
            repo.fetchToolList(config = config)
            val reconfigured = config.copy(auth = McpAuth.Bearer(token = "t"))

            // When — no forced refresh: the saved edit's own refetch never finished
            val result = repo.fetchToolList(config = reconfigured)

            // Then — the list fetched with the old sign-in does not stand in for the new one
            assertTrue(result.isSuccess)
            coVerify(exactly = 1) { second.connect(reconfigured) }
            coVerify(exactly = 1) { second.getTools() }
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
        // does not re-establish the connection. The first fetch connects, and the
        // handshake lists the tools; the two after it ask the live session again.
        coVerify(exactly = 1) { client.connect(any()) }
        coVerify(exactly = 1) { client.getTools() }
        coVerify(exactly = 2) { client.refreshTools() }
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
    fun `given a failed refresh when fetched again within the TTL then the server is asked and the status clears`() =
        runTest {
            // A failed refresh must not leave a stale red pill on a server that works again —
            // and must not let the list cached before it answer as Connected either: the
            // failure dropped the connection that list came from.
            val first = mockk<McpClient>(relaxed = true)
            coEvery { first.getTools() } returns listOf(AgentTool("a", "d", "{}"))
            coEvery { first.refreshTools() } throws IllegalStateException("transient")
            val second = mockk<McpClient>(relaxed = true)
            coEvery { second.getTools() } returns listOf(AgentTool("a", "d", "{}"))
            val factory = mockk<McpClientFactory>()
            coEvery { factory.create() } returnsMany listOf(first, second)
            val repo = McpServerRepositoryImpl(pool = pool(factory))
            repo.clockMs = { 1_000L }

            // 1) First fetch succeeds → cache populated, status = Connected.
            repo.fetchToolList(config = config)
            // 2) Force-refresh fails → status flips to Error and the connection is dropped.
            repo.fetchToolList(config = config, forceRefresh = true)
            assertTrue(repo.observeConnectionStatus(url).first() is McpConnectionStatus.Error)
            coVerify(exactly = 1) { first.disconnect() }

            // 3) Non-forced fetch within TTL → no cache answer: a new connection is asked.
            val fetched = repo.fetchToolList(config = config, forceRefresh = false)
            assertTrue(fetched.isSuccess)
            coVerify(exactly = 1) { second.connect(config) }
            assertEquals(McpConnectionStatus.Connected, repo.observeConnectionStatus(url).first())
        }

    @Test
    fun `given the pool replaced the connection when fetched within the TTL then the cached list does not answer`() =
        runTest {
            // The agent's path replaces a connection whose session the server closed; the
            // list cached from the old connection describes a session that no longer exists.
            val first = mockk<McpClient>(relaxed = true)
            coEvery { first.getTools() } returns listOf(AgentTool("a", "d", "{}"))
            val second = mockk<McpClient>(relaxed = true)
            coEvery { second.getTools() } returns listOf(AgentTool("b", "d", "{}"))
            coEvery { second.refreshTools() } returns listOf(AgentTool("b", "d", "{}"))
            val factory = mockk<McpClientFactory>()
            coEvery { factory.create() } returnsMany listOf(first, second)
            val pool = pool(factory)
            val repo = McpServerRepositoryImpl(pool = pool)
            repo.clockMs = { 1_000L }
            repo.fetchToolList(config = config)
            pool.withServer(url) {
                invalidate()
                client(config)
            }

            val fetched = repo.fetchToolList(config = config)

            assertEquals(listOf("b"), fetched.getOrThrow().map { it.name })
            coVerify(exactly = 1) { second.refreshTools() }
        }

    @Test
    fun `given the server closed the session when refreshed then a new connection answers`() = runTest {
        val first = mockk<McpClient>(relaxed = true)
        coEvery { first.getTools() } returns listOf(AgentTool("a", "d", "{}"))
        coEvery { first.refreshTools() } throws McpSessionExpiredException()
        val second = mockk<McpClient>(relaxed = true)
        coEvery { second.getTools() } returns listOf(AgentTool("a", "d", "{}"), AgentTool("b", "d", "{}"))
        val factory = mockk<McpClientFactory>()
        coEvery { factory.create() } returnsMany listOf(first, second)
        val pool = pool(factory)
        val repo = McpServerRepositoryImpl(pool = pool)
        repo.fetchToolList(config = config)

        val refreshed = repo.fetchToolList(config = config, forceRefresh = true)

        assertEquals(listOf("a", "b"), refreshed.getOrThrow().map { it.name })
        assertEquals(McpConnectionStatus.Connected, repo.observeConnectionStatus(url).first())
        assertSame(second, pool.peek(url))
        // The new connection's handshake listed the tools; it is not asked a second time.
        coVerify(exactly = 0) { second.refreshTools() }
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
    return McpConnectionPool(clientFactory = factory, networkSettings = settings)
}
