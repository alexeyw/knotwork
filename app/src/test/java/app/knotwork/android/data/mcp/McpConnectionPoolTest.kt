package app.knotwork.android.data.mcp

import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.McpAuth
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [McpConnectionPool] — the single owner of live MCP connections.
 *
 * The behaviours pinned here are the ones the two former per-repository pools each
 * implemented separately (and could therefore drift on): reuse an equal config,
 * reconnect a changed one, swallow connect failures during reconcile, prune servers
 * that left the settings list.
 */
class McpConnectionPoolTest {

    private val factory: McpClientFactory = mockk()
    private val config = McpServerConfig(url = "http://10.0.0.5:8080")

    /**
     * Every fixture URL is loopback / private cleartext, so the pool's cleartext
     * gate would refuse it unless the origin is approved. Approving everything
     * here keeps these tests about pooling; the gate itself is covered by
     * `CleartextPolicyTest` and by the dedicated refusal test below.
     */
    private val settingsRepository: SettingsRepository = mockk {
        every { approvedCleartextOrigins } returns flowOf(
            setOf(
                "http://10.0.0.5:8080",
                "http://10.0.0.1:1",
                "http://10.0.0.2:2",
                "http://10.0.0.9:9000",
            ),
        )
    }

    private fun client(): McpClient = mockk<McpClient>().also {
        coEvery { it.connect(any()) } returns Unit
        coEvery { it.disconnect() } returns Unit
        coEvery { it.getTools() } returns emptyList()
    }

    @Test
    fun `given an equal config when asked twice then the same client is reused`() = runTest {
        val only = client()
        every { factory.create() } returns only
        val pool = McpConnectionPool(factory, settingsRepository)

        val first = pool.withServer(config.url) { client(config) }
        val second = pool.withServer(config.url) { client(config) }

        assertSame(first, second)
        coVerify(exactly = 1) { only.connect(any()) }
    }

    @Test
    fun `given a changed config when asked again then the stale client is disconnected and replaced`() = runTest {
        // Auth edited in Settings must actually apply — keying by URL alone would keep
        // the old credentials alive until the process restarted.
        val stale = client()
        val fresh = client()
        every { factory.create() } returnsMany listOf(stale, fresh)
        val pool = McpConnectionPool(factory, settingsRepository)

        pool.withServer(config.url) { client(config) }
        val second = pool.withServer(config.url) {
            client(config.copy(auth = McpAuth.Bearer(token = "t")))
        }

        assertSame(fresh, second)
        coVerify(exactly = 1) { stale.disconnect() }
    }

    @Test
    fun `given invalidate when peeked then nothing is pooled and the client was disconnected`() = runTest {
        val only = client()
        every { factory.create() } returns only
        val pool = McpConnectionPool(factory, settingsRepository)
        pool.withServer(config.url) { client(config) }

        pool.withServer(config.url) { invalidate() }

        assertNull(pool.peek(config.url))
        coVerify(exactly = 1) { only.disconnect() }
    }

    @Test
    fun `given a server that fails to connect when reconcile then the others still connect`() = runTest {
        // A dead server must not abort the reconcile for the healthy ones beside it.
        val dead = mockk<McpClient>().also {
            coEvery { it.connect(any()) } throws IllegalStateException("refused")
            coEvery { it.disconnect() } returns Unit
        }
        val alive = client()
        every { factory.create() } returnsMany listOf(dead, alive)
        val pool = McpConnectionPool(factory, settingsRepository)

        pool.reconcile(
            listOf(
                McpServerConfig(url = "http://10.0.0.1:1"),
                McpServerConfig(url = "http://10.0.0.2:2"),
            ),
        )

        assertNull(pool.peek("http://10.0.0.1:1"))
        assertNotNull(pool.peek("http://10.0.0.2:2"))
    }

    @Test
    fun `given a server removed from settings when reconcile then it is disconnected and dropped`() = runTest {
        val only = client()
        every { factory.create() } returns only
        val pool = McpConnectionPool(factory, settingsRepository)
        pool.reconcile(listOf(config))

        pool.reconcile(emptyList())

        assertNull(pool.peek(config.url))
        coVerify(exactly = 1) { only.disconnect() }
    }

    @Test
    fun `given a failed tools-list on the Tools screen then the agent does not reuse the dead session`() = runTest {
        // The finding this pool exists for. `McpServerRepositoryImpl` (health
        // indicator) and `ToolRepositoryImpl` (real calls) used to hold one
        // `@Singleton` pool each, so a session the Tools screen had already written
        // off as broken stayed live on the execution side — the screen could read
        // "ok" while the next tool call went to a dead client.
        //
        // Asserted at the pool, which is now the single source of truth: a failure
        // that invalidates an entry must leave nothing behind for the other
        // consumer to pick up.
        val dead = client()
        val fresh = client()
        every { factory.create() } returnsMany listOf(dead, fresh)
        val pool = McpConnectionPool(factory, settingsRepository)

        // The agent side connects first and would happily keep this client.
        pool.reconcile(listOf(config))
        assertSame(dead, pool.peek(config.url))

        // The Tools screen's fetch fails and drops the entry.
        pool.withServer(config.url) { invalidate() }
        assertNull(pool.peek(config.url))

        // The agent's next reconcile therefore builds a new session instead of
        // routing onto the one already known to be broken.
        pool.reconcile(listOf(config))
        assertSame(fresh, pool.peek(config.url))
    }

    @Test
    fun `given a connected server when peeked then the pooled client is returned without connecting`() = runTest {
        val only = client()
        coEvery { only.getTools() } returns listOf(AgentTool("t", "d", "{}"))
        every { factory.create() } returns only
        val pool = McpConnectionPool(factory, settingsRepository)
        pool.reconcile(listOf(config))

        assertSame(only, pool.peek(config.url))
        coVerify(exactly = 1) { only.connect(any()) }
    }

    @Test
    fun `given an unapproved private cleartext server when connecting then the pool refuses`() = runTest {
        // The cleartext gate. The manifest now permits unencrypted traffic
        // app-wide (Android cannot express "any private-LAN address" in its
        // network-security config), so this check is what actually stops an
        // unapproved unencrypted MCP connection from being opened.
        every { factory.create() } returns client()
        every { settingsRepository.approvedCleartextOrigins } returns flowOf(emptySet())
        val pool = McpConnectionPool(factory, settingsRepository)

        val failure = runCatching { pool.withServer(config.url) { client(config) } }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("not been approved"))
        assertNull(pool.peek(config.url))
    }

    @Test
    fun `given a public cleartext server when connecting then the pool refuses and cannot be approved`() = runTest {
        // Approval only ever unlocks private addresses; a public host stays
        // refused however the approved set is configured.
        val publicConfig = McpServerConfig(url = "http://mcp.example.com/sse")
        every { factory.create() } returns client()
        every { settingsRepository.approvedCleartextOrigins } returns flowOf(setOf("http://mcp.example.com"))
        val pool = McpConnectionPool(factory, settingsRepository)

        val failure = runCatching { pool.withServer(publicConfig.url) { client(publicConfig) } }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("public address"))
    }

    @Test
    fun `given a closed session when a call runs with session recovery then it is repeated once on a new connection`() =
        runTest {
            val closed = client().also { coEvery { it.executeTool(any(), any()) } throws McpSessionExpiredException() }
            val fresh = client().also { coEvery { it.executeTool(any(), any()) } returns "ok" }
            every { factory.create() } returnsMany listOf(closed, fresh)
            val pool = McpConnectionPool(factory, settingsRepository)
            val pooled = pool.withServer(config.url) { client(config) }

            val result = pool.withSessionRecovery(config.url, pooled) { it.executeTool("echo", "{}") }

            assertEquals("ok", result)
            assertSame(fresh, pool.peek(config.url))
            coVerify(exactly = 1) { closed.disconnect() }
            coVerify(exactly = 1) { fresh.connect(config) }
            coVerify(exactly = 1) { closed.executeTool(any(), any()) }
            coVerify(exactly = 1) { fresh.executeTool(any(), any()) }
        }

    @Test
    fun `given the new session is closed too when recovering then the failure propagates after one new connection`() =
        runTest {
            val closed = client().also { coEvery { it.executeTool(any(), any()) } throws McpSessionExpiredException() }
            val alsoClosed = client().also {
                coEvery { it.executeTool(any(), any()) } throws
                    McpSessionExpiredException()
            }
            every { factory.create() } returnsMany listOf(closed, alsoClosed, client())
            val pool = McpConnectionPool(factory, settingsRepository)
            val pooled = pool.withServer(config.url) { client(config) }

            val failure = runCatching { pool.withSessionRecovery(config.url, pooled) { it.executeTool("echo", "{}") } }
                .exceptionOrNull()

            assertTrue("failure: $failure", failure is McpSessionExpiredException)
            verify(exactly = 2) { factory.create() }
            assertSame(alsoClosed, pool.peek(config.url))
        }

    @Test
    fun `given any other failure when a call runs with session recovery then it is not repeated`() = runTest {
        // A call that timed out or broke mid-way may have run on the server.
        val pooledClient = client().also { coEvery { it.executeTool(any(), any()) } throws IOException("reset") }
        every { factory.create() } returns pooledClient
        val pool = McpConnectionPool(factory, settingsRepository)
        val pooled = pool.withServer(config.url) { client(config) }

        val failure = runCatching { pool.withSessionRecovery(config.url, pooled) { it.executeTool("echo", "{}") } }
            .exceptionOrNull()

        assertTrue("failure: $failure", failure is IOException && failure !is McpSessionExpiredException)
        coVerify(exactly = 1) { pooledClient.executeTool(any(), any()) }
        coVerify(exactly = 0) { pooledClient.disconnect() }
        assertSame(pooledClient, pool.peek(config.url))
    }

    @Test
    fun `given another caller already replaced the connection when recovering then its client is used`() = runTest {
        val stale = client().also { coEvery { it.executeTool(any(), any()) } throws McpSessionExpiredException() }
        val current = client().also { coEvery { it.executeTool(any(), any()) } returns "ok" }
        every { factory.create() } returns current
        val pool = McpConnectionPool(factory, settingsRepository)
        pool.withServer(config.url) { client(config) }

        val result = pool.withSessionRecovery(config.url, stale) { it.executeTool("echo", "{}") }

        assertEquals("ok", result)
        verify(exactly = 1) { factory.create() }
        coVerify(exactly = 0) { current.disconnect() }
    }

    @Test
    fun `given two calls find the session closed at once when recovering then one new connection serves both`() =
        runTest {
            val closed = client().also { coEvery { it.executeTool(any(), any()) } throws McpSessionExpiredException() }
            val fresh = client().also {
                // Connecting takes a moment, so the second caller reaches the lock while the first holds it.
                coEvery { it.connect(any()) } coAnswers { delay(10) }
                coEvery { it.executeTool(any(), any()) } returns "ok"
            }
            every { factory.create() } returnsMany listOf(closed, fresh)
            val pool = McpConnectionPool(factory, settingsRepository)
            val pooled = pool.withServer(config.url) { client(config) }

            val first = async { pool.withSessionRecovery(config.url, pooled) { it.executeTool("echo", "{}") } }
            val second = async { pool.withSessionRecovery(config.url, pooled) { it.executeTool("echo", "{}") } }

            assertEquals(listOf("ok", "ok"), listOf(first.await(), second.await()))
            verify(exactly = 2) { factory.create() }
            coVerify(exactly = 1) { fresh.connect(any()) }
        }

    @Test
    fun `given the pool holds no connection when recovering then none is revived`() = runTest {
        // The server was removed, or another caller's reconnect failed: not this call's to undo.
        val stale = client().also { coEvery { it.executeTool(any(), any()) } throws McpSessionExpiredException() }
        val pool = McpConnectionPool(factory, settingsRepository)

        val failure = runCatching { pool.withSessionRecovery(config.url, stale) { it.executeTool("echo", "{}") } }
            .exceptionOrNull()

        assertTrue("failure: $failure", failure is McpSessionExpiredException)
        verify(exactly = 0) { factory.create() }
        assertNull(pool.peek(config.url))
    }

    @Test
    fun `given the server refuses a new session when recovering then the call fails and nothing is pooled`() = runTest {
        val closed = client().also { coEvery { it.executeTool(any(), any()) } throws McpSessionExpiredException() }
        val refused = client().also { coEvery { it.connect(any()) } throws IOException("503") }
        every { factory.create() } returnsMany listOf(closed, refused)
        val pool = McpConnectionPool(factory, settingsRepository)
        val pooled = pool.withServer(config.url) { client(config) }

        val failure = runCatching { pool.withSessionRecovery(config.url, pooled) { it.executeTool("echo", "{}") } }
            .exceptionOrNull()

        assertEquals("503", failure?.message)
        assertNull(pool.peek(config.url))
    }

    @Test
    fun `given the lock is held when recovering inside it then the same rule applies`() = runTest {
        val closed = client().also { coEvery { it.refreshTools() } throws McpSessionExpiredException() }
        val fresh = client().also { coEvery { it.getTools() } returns listOf(AgentTool("t", "d", "{}")) }
        every { factory.create() } returnsMany listOf(closed, fresh)
        val pool = McpConnectionPool(factory, settingsRepository)

        val tools = pool.withServer(config.url) {
            val pooled = client(config)
            recovering(pooled) { current -> if (current === pooled) current.refreshTools() else current.getTools() }
        }

        assertEquals(listOf("t"), tools.map { it.name })
        assertSame(fresh, pool.peek(config.url))
    }
}
