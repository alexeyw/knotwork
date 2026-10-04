package app.knotwork.android.data.mcp

import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.repositories.NetworkSettings
import app.knotwork.android.domain.services.CleartextPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single owner of live MCP connections.
 *
 * Before this class existed there were **two** independent pools: one inside
 * `McpServerRepositoryImpl`, driving the Tools screen's health indicator, and one
 * inside `ToolRepositoryImpl`, used for the calls the agent actually makes. Both
 * were `@Singleton`, both keyed by server URL, and neither knew about the other.
 * The consequence was a UI that could report "13 tools · ok" from a healthy
 * session while the session the next tool call would use was already dead — one
 * piece of state with two sources of truth, which is a bug generator regardless
 * of how carefully either half is written.
 *
 * Everything that owns a socket now lives here, so "is this server reachable?"
 * and "which client will run the call?" are answered by the same entry.
 *
 * **Concurrency contract.** Connection identity is per server URL, so the lock is
 * too: one [Mutex] per URL rather than one global lock. Connecting is a
 * multi-step read-modify-write with a suspending `connect()` in the middle, and
 * without the lock two callers (the Tools screen refreshing while a pipeline
 * dispatches a tool) could both see "no entry", both connect, and both store —
 * leaking a live socket that nothing ever disconnects.
 *
 * The lock is deliberately **not** held while a caller uses the client: an MCP
 * tool call can run for a minute, and serialising concurrent calls to the same
 * server behind the connect lock would be a silent throughput regression. Use
 * [withServer] for the connect / invalidate step, then use the returned client
 * outside the block.
 */
@Singleton
class McpConnectionPool @Inject constructor(
    private val clientFactory: McpClientFactory,
    private val networkSettings: NetworkSettings,
) {

    /**
     * A pooled connection plus the [McpServerConfig] it was established with.
     * The config is the equality unit that decides whether a cached connection
     * is still valid or has to be torn down and rebuilt — keying by URL alone
     * would keep a stale connection alive after the user edited credentials,
     * and every later call would fail with the old auth until the process died.
     *
     * @property client live client owning the socket / SSE stream.
     * @property config configuration this connection was opened with.
     */
    private data class PooledClient(val client: McpClient, val config: McpServerConfig)

    private val clients = ConcurrentHashMap<String, PooledClient>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Connect-time capability handed to [withServer]'s block. Its methods may only
     * be called while the per-URL lock is held, which is why they are reachable
     * only from inside that block rather than from the pool's public surface —
     * [Mutex] is not reentrant, so an accidental "connect without the lock" path
     * would either race or deadlock depending on where it was called from.
     */
    interface Session {
        /**
         * Returns a connected client for this server, connecting only when there
         * is no usable pooled entry — i.e. on first use, after a failure dropped
         * the entry, or when [config] differs from the one the pooled connection
         * was established with (changed auth / transport / headers must actually
         * apply).
         *
         * Reconnecting on *every* call opens a fresh MCP session per refresh: the
         * server keeps the abandoned ones, and an in-place transport swap can
         * break a tool call running concurrently on the same client. Both were
         * observed in the directed MCP test.
         *
         * @param config configuration to connect with.
         * @return a connected [McpClient].
         */
        suspend fun client(config: McpServerConfig): McpClient

        /**
         * Disconnects and forgets this server's pooled entry, so the next
         * [client] call reconnects instead of reusing a connection that has
         * already proven unusable. Disconnect failures are logged, not thrown —
         * the entry is dropped either way.
         */
        suspend fun invalidate()
    }

    /**
     * Runs [block] holding this server's connect lock, giving it a [Session] for
     * connecting or invalidating the pooled entry.
     *
     * Callers that need to serialise their own multi-step work with connection
     * changes (a TTL cache read followed by a fetch, say) should do that work
     * inside the block; callers that only need a client should get it here and
     * use it after the block returns.
     *
     * @param serverUrl pool key and connection identity.
     * @param block work to run under the lock.
     * @return whatever [block] returns.
     */
    suspend fun <T> withServer(serverUrl: String, block: suspend Session.() -> T): T {
        val mutex = mutexes.getOrPut(serverUrl) { Mutex() }
        return mutex.withLock { UrlSession(serverUrl).block() }
    }

    /**
     * Returns the pooled client for [serverUrl] without connecting, or `null`
     * when there is no live entry.
     *
     * Deliberately lock-free: this is the read used on hot paths that have
     * already reconciled the pool ([reconcile]) and only need to route a call to
     * the client that reconcile established.
     *
     * @param serverUrl pool key.
     * @return the live client, or `null`.
     */
    fun peek(serverUrl: String): McpClient? = clients[serverUrl]?.client

    /**
     * Brings the pool in line with [configs]: every listed server ends up with a
     * live entry built from its current config, and every URL not listed is
     * disconnected and dropped.
     *
     * Unlike [Session.client], a connect failure here is swallowed and logged —
     * the URL is simply left out of the pool so the next reconcile retries. A
     * dead server must not abort the reconcile for the healthy ones beside it.
     *
     * Locks are taken and released one URL at a time, never nested, so this can
     * safely interleave with [withServer] calls for other servers.
     *
     * @param configs the persisted server list, already deduplicated by URL.
     */
    suspend fun reconcile(configs: List<McpServerConfig>) {
        val persistedUrls = configs.mapTo(mutableSetOf()) { it.url }
        (clients.keys.toSet() - persistedUrls).forEach { url ->
            withServer(url) { invalidate() }
        }
        configs.forEach { config ->
            withServer(config.url) {
                try {
                    client(config)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "MCP connect to %s failed; will retry on next sync", config.url)
                }
            }
        }
    }

    /** [Session] bound to one server URL; valid only while its lock is held. */
    private inner class UrlSession(private val serverUrl: String) : Session {

        override suspend fun client(config: McpServerConfig): McpClient {
            val pooled = clients[serverUrl]
            if (pooled != null && pooled.config == config) {
                return pooled.client
            }
            if (pooled != null) {
                // Config changed (auth / transport / headers / display name) — tear
                // down the stale connection so the reconnect actually applies the
                // new settings instead of holding the old auth.
                disconnectQuietly(pooled.client, "stale config")
                clients.remove(serverUrl)
            }
            // Cleartext gate, applied here because this is the only place an MCP
            // connection is opened. The manifest permits unencrypted traffic
            // app-wide (Android cannot express "any private-LAN address" there),
            // so `CleartextPolicy` is what actually enforces it: private and
            // approved, or refused.
            val verdict = CleartextPolicy.classify(config.url, networkSettings.approvedCleartextOrigins.first())
            CleartextPolicy.refusalMessage(verdict)?.let { reason -> error(reason) }
            val created = clientFactory.create()
            created.connect(config)
            clients[serverUrl] = PooledClient(client = created, config = config)
            return created
        }

        override suspend fun invalidate() {
            val pooled = clients.remove(serverUrl) ?: return
            disconnectQuietly(pooled.client, "invalidate")
        }

        private suspend fun disconnectQuietly(client: McpClient, reason: String) {
            try {
                client.disconnect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.w(e, "MCP disconnect (%s) for %s failed; dropping the entry anyway", reason, serverUrl)
            }
        }
    }
}
