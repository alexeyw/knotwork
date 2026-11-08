package app.knotwork.android.data.repositories

import androidx.annotation.VisibleForTesting
import app.knotwork.android.data.mcp.McpClient
import app.knotwork.android.data.mcp.McpConnectionPool
import app.knotwork.android.data.mcp.mcpFailureContext
import app.knotwork.android.data.network.ConnectionFailureClassifier
import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.McpConnectionStatus
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.McpTool
import app.knotwork.android.domain.repositories.McpServerRepository
import app.knotwork.android.domain.services.CleartextPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [McpServerRepository] implementation.
 *
 * This class owns the Tools surface's **view** of a server — its TTL-cached tool
 * list and its observable connection status. It does **not** own the connection:
 * live clients belong to the shared [McpConnectionPool], which `ToolRepositoryImpl`
 * uses for the calls the agent actually makes. That sharing is the point. While
 * each repository kept its own pool, the health indicator described one session
 * and the agent used another, so the screen could read "13 tools · ok" against a
 * session that was already dead for execution purposes.
 *
 * The class is `@Singleton` so the same caches and status flows are reused across
 * every ViewModel observer. Concurrent `fetchToolList` calls for one server are
 * collapsed into a single round-trip by holding that server's pool lock across
 * the cache check and the fetch — two collectors subscribing at once on a cold
 * start would otherwise both connect, which is wasteful and races the status
 * emissions.
 */
@Singleton
class McpServerRepositoryImpl @Inject constructor(private val pool: McpConnectionPool) : McpServerRepository {

    private val statusFlows = ConcurrentHashMap<String, MutableStateFlow<McpConnectionStatus>>()
    private val caches = ConcurrentHashMap<String, CachedToolList>()

    /**
     * Time source. Overridable from unit tests via a direct assignment so that
     * the TTL behaviour can be exercised without sleeping. Production code
     * leaves the field at its [System.currentTimeMillis] default.
     */
    @VisibleForTesting
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    override suspend fun fetchToolList(config: McpServerConfig, forceRefresh: Boolean): Result<List<McpTool>> {
        val serverUrl = config.url
        val flow = statusFlows.getOrPut(serverUrl) { MutableStateFlow(McpConnectionStatus.Connecting) }
        return pool.withServer(serverUrl) {
            val now = clockMs()
            if (!forceRefresh) {
                val cached = caches[serverUrl]
                // A list fetched with another config (a saved edit's sign-in, transport
                // or headers) does not answer for this one: the edit's own refetch may
                // have been cancelled, and serving the old list would show "Connected"
                // without ever trying the new config.
                //
                // Nor does a list from a connection the pool no longer holds: the agent's
                // path replaces a connection whose session the server closed, and drops
                // one it could not replace. Serving that list would show "Connected" for
                // a connection that is gone — the screen saying one thing while the
                // agent sees another.
                if (cached != null &&
                    cached.config == config &&
                    cached.connection != null &&
                    cached.connection === pool.peek(serverUrl) &&
                    now - cached.fetchedAtMs <= McpServerRepository.TOOL_LIST_TTL_MS
                ) {
                    // The status flow may still read `Error` from a fetch cancelled
                    // before it settled. This list is an answer from the connection the
                    // pool holds now, so the status that goes with it is `Connected`.
                    flow.value = McpConnectionStatus.Connected
                    return@withServer Result.success(cached.tools)
                }
            }
            val statusBeforeFetch = flow.value
            flow.value = McpConnectionStatus.Connecting

            // try/catch instead of runCatching: the block suspends
            // (connect / getTools), and runCatching would trap the
            // CancellationException that must propagate for cooperative
            // cancellation. `Throwable` keeps runCatching's catch surface.
            //
            // Cancellation is re-thrown immediately with no work in the clause;
            // resolving the status on that path is done in `finally`, guarded by
            // [settled], which is where cleanup that must also run when cancelled
            // belongs. Success and failure both resolve the status; cancellation
            // used to resolve nothing, so a fetch abandoned mid-flight (the
            // caller's scope going away — a settings edit that renavigates, a
            // screen leaving) pinned the row on "Connecting…" until the user hit
            // Refresh by hand.
            var settled = false
            try {
                val pooled = pool.peek(serverUrl)
                val client = client(config)
                val listed = if (client === pooled) {
                    // A connection that was already open is asked again: that request is
                    // what tells a live session from one the server has closed, and how a
                    // tool the server added since reaches the app. A closed session is
                    // replaced once, as for the agent's calls — and the new connection
                    // listed its tools while connecting, so it is not asked twice.
                    recovering(client) { current ->
                        if (current ===
                            client
                        ) {
                            current.refreshTools()
                        } else {
                            current.getTools()
                        }
                    }
                } else {
                    // Connected just now: the handshake listed the tools.
                    client.getTools()
                }
                val tools = listed.map { agentTool -> agentTool.toMcpTool(serverUrl) }
                caches[serverUrl] = CachedToolList(
                    config = config,
                    tools = tools,
                    fetchedAtMs = clockMs(),
                    connection = pool.peek(serverUrl),
                )
                flow.value = McpConnectionStatus.Connected
                settled = true
                Result.success(tools)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.e(e, "MCP tools/list failed for %s", serverUrl)
                // Drop the pooled entry so the next attempt reconnects instead of
                // reusing a connection that has already proven unusable. Because
                // the pool is shared, this also stops the agent's next tool call
                // from being routed onto the same dead session. A tool call still
                // running on that connection ends with a failure; it is not repeated —
                // it may have run on the server.
                //
                // `NonCancellable` because this cleanup suspends (the transport's
                // `disconnect`) inside a catch clause: if the caller's scope is
                // cancelled while we are retiring a client we have just proven
                // broken, abandoning it half-retired would leave exactly the stale
                // entry this whole class exists to avoid.
                withContext(NonCancellable) { invalidate() }
                flow.value = McpConnectionStatus.Error(
                    host = CleartextPolicy.hostOf(serverUrl) ?: serverUrl,
                    failure = ConnectionFailureClassifier.classify(e, mcpFailureContext(config)),
                )
                settled = true
                Result.failure(e)
            } finally {
                // Only reachable when the fetch was cancelled. Restoring the
                // previous value is not enough: a flow starts life at
                // `Connecting`, so the very first fetch has nothing better to fall
                // back to. An abandoned attempt genuinely leaves the server in an
                // unknown state, and the honest rendering of that is "not
                // connected, try again" rather than a spinner that never stops.
                // Assignment does not suspend, so this is safe while cancelling.
                if (!settled) {
                    val cached = caches[serverUrl]
                    flow.value = when {
                        statusBeforeFetch != McpConnectionStatus.Connecting -> statusBeforeFetch
                        cached?.connection != null && cached.connection === pool.peek(serverUrl) ->
                            McpConnectionStatus.Connected
                        else -> McpConnectionStatus.Interrupted
                    }
                }
            }
        }
    }

    override fun observeConnectionStatus(serverUrl: String): Flow<McpConnectionStatus> =
        statusFlows.getOrPut(serverUrl) {
            MutableStateFlow(McpConnectionStatus.Connecting)
        }.asStateFlow()

    override suspend fun disconnect(serverUrl: String) {
        // Runs under the same per-URL lock an in-flight fetchToolList holds, so this
        // serialises with it instead of racing past it. The pool never removes its
        // Mutex entry: dropping it while another coroutine is suspended waiting on
        // it would silently break the lock contract, and a follow-up fetch would
        // create a fresh Mutex that runs in parallel with the in-flight disconnect.
        pool.withServer(serverUrl) {
            invalidate()
            caches.remove(serverUrl)
            // Status flow entry stays in the map so any active observer keeps a
            // live handle. Removing it here would orphan the observer's
            // collection — a follow-up `fetchToolList` would `getOrPut` a brand-
            // new MutableStateFlow that the in-flight collector knows nothing
            // about, leaving the UI pinned to whatever value the orphaned flow
            // last held (typically the previous `Error`). Reset to `Connecting`
            // so the UI immediately reflects the "no live session" state until
            // the next fetch resolves it.
            statusFlows[serverUrl]?.value = McpConnectionStatus.Connecting
        }
    }

    /**
     * Maps a protocol-neutral [AgentTool] (as returned by `McpClient.getTools`)
     * to the MCP-affiliated [McpTool] consumed by the Tools surface.
     */
    private fun AgentTool.toMcpTool(serverUrl: String): McpTool = McpTool(
        id = mcpToolId(serverUrl = serverUrl, toolName = name),
        serverUrl = serverUrl,
        name = name,
        description = description,
        inputSchemaJson = parameters,
        risk = risk,
    )

    /**
     * One server's tool list as last fetched.
     *
     * @property config The config the list was fetched with; the list answers only a fetch with it.
     * @property tools The tools the server published.
     * @property fetchedAtMs When the fetch finished, by [clockMs].
     * @property connection The pooled client the list came from; the list answers only while
     *   the pool still holds that same client. Compared by identity.
     */
    private class CachedToolList(
        val config: McpServerConfig,
        val tools: List<McpTool>,
        val fetchedAtMs: Long,
        val connection: McpClient?,
    )

    /** Stable id builder + format constants shared with `ToolDetailScreen`. */
    companion object {
        /** Recognises [mcpToolId] outputs in route parameters and other callers. */
        const val MCP_ID_PREFIX: String = "mcp:"

        /** Number of hex chars taken from the SHA-256 digest for the id prefix. */
        private const val ID_HASH_HEX_LEN: Int = 8

        /** One hex byte is two characters wide; padding helper. */
        private const val HEX_BYTE_WIDTH: Int = 2

        private const val UNSIGNED_BYTE_MASK: Int = 0xFF
        private const val HEX_RADIX: Int = 16

        /**
         * Builds the stable route-safe id used as the tool's argument when
         * navigating to `ToolDetailScreen`. The SHA-256 prefix isolates the
         * server URL from path-segment encoding concerns and keeps the id
         * deterministic across process restarts.
         */
        fun mcpToolId(serverUrl: String, toolName: String): String = "$MCP_ID_PREFIX${sha8(serverUrl)}:$toolName"

        private fun sha8(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return buildString(capacity = ID_HASH_HEX_LEN) {
                val bytesNeeded = ID_HASH_HEX_LEN / HEX_BYTE_WIDTH
                for (i in 0 until bytesNeeded) {
                    val byte = bytes[i].toInt() and UNSIGNED_BYTE_MASK
                    append(byte.toString(radix = HEX_RADIX).padStart(length = HEX_BYTE_WIDTH, padChar = '0'))
                }
            }
        }
    }
}
