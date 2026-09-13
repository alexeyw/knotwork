package app.knotwork.android.data.mcp

import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.agents.mcp.metadata.McpServerInfo
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.kotlinx.toKoogJSONObject
import androidx.annotation.VisibleForTesting
import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.McpAuth
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.McpTransport
import app.knotwork.android.domain.repositories.NetworkActivityTracker
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttpTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.Base64
import javax.inject.Inject

/**
 * Concrete implementation of [McpClient] using the Koog framework's MCP tools.
 * It manages the underlying Ktor HttpClient and the Koog ToolRegistry.
 */
@OptIn(ai.koog.agents.core.tools.annotations.InternalAgentToolsApi::class)
class KoogMcpClient(private val networkActivityTracker: NetworkActivityTracker? = null) : McpClient {

    /**
     * One live connection: the Ktor client, the transport it speaks over, and
     * the Koog registry built on top. Kept as a single immutable value so
     * readers take an **atomic snapshot** instead of observing a half-swapped
     * pair of fields.
     *
     * Before this was one value, `connect` nulled `registry` and closed
     * `httpClient` in place, so a concurrent `executeTool` could look up a tool
     * in a registry that was momentarily absent and report
     * `Tool <name> not found` — telling the agent a tool does not exist when it
     * does. Found by a directed on-device MCP test.
     *
     * @property httpClient Ktor client owning the socket pool; closed on teardown.
     * @property transport MCP transport, retained so the session can be
     *   terminated server-side on teardown rather than merely dropped.
     * @property registry Koog tool registry discovered from the server.
     */
    private class Session(val httpClient: HttpClient, val transport: Transport, val registry: ToolRegistry)

    @Volatile
    private var session: Session? = null

    /**
     * Serialises connection-state transitions against readers. Readers hold it
     * only long enough to take a [session] snapshot, and it is **never** held
     * across a tool execution, so a slow or hung MCP call cannot block the
     * Tools screen behind it. [connect] does hold it for the whole handshake —
     * bounded by [connectTimeoutMs], which is the longest a reader can wait.
     */
    private val sessionMutex = Mutex()

    private val serializer = KotlinxSerializer(Json { ignoreUnknownKeys = true })

    /**
     * Deadline for a single [executeTool] round-trip, in milliseconds.
     * Overridable in tests so a regression can measure the deadline without
     * waiting out the production value. [connect] also derives the socket
     * timeout from it, so a change only reaches the socket on the next connect.
     */
    @VisibleForTesting
    internal var toolCallTimeoutMs: Long = TOOL_CALL_TIMEOUT_MS

    /** Deadline for the [connect] handshake, in milliseconds. See [toolCallTimeoutMs]. */
    @VisibleForTesting
    internal var connectTimeoutMs: Long = CONNECT_TIMEOUT_MS

    /**
     * Connects to the MCP server described by [config]. Branches on
     * [McpServerConfig.transport]:
     *  - [McpTransport.SSE] — classic server-sent events via Koog's
     *    `defaultSseTransport`.
     *  - [McpTransport.STREAMABLE_HTTP] — the post-2025-03-26 spec
     *    transport via the upstream MCP Kotlin SDK
     *    (`HttpClient.mcpStreamableHttpTransport`). Uses POST for
     *    outbound JSON-RPC and an SSE channel for inbound, so the SSE
     *    Ktor plugin is installed on the shared client unconditionally.
     *
     * Calling [connect] more than once on the same instance is supported
     * (e.g. reconnect or repoint scenarios): the previous [Session] is torn
     * down — session terminated server-side, `HttpClient` closed — before a
     * fresh one is installed, so neither socket pools nor server-side MCP
     * sessions accumulate.
     *
     * The new [Session] is published **only after** transport construction
     * succeeds. If transport construction or `fromTransport` throws (network
     * error, malformed URL, server-side rejection), the client is closed
     * locally and the field is left in its previous state, so a leaked Ktor
     * engine cannot accumulate across failed connects.
     *
     * Runs under [sessionMutex] so a concurrent [executeTool] can never observe
     * a partially-swapped connection.
     */
    override suspend fun connect(config: McpServerConfig) {
        // Outbound HTTP traffic is about to start — surface it to the privacy indicator
        // *before* the network handshake so the timestamp reflects intent even if the
        // call fails downstream.
        networkActivityTracker?.recordOutbound()
        withContext(Dispatchers.IO) {
            sessionMutex.withLock {
                // Drop any previous session before reattaching. Without this, a second
                // connect() on the same instance would silently leak the prior
                // HttpClient (and its underlying engine threads/sockets) and strand a
                // live session on the server.
                tearDown(session)
                session = null

                // Compose the final header set: typed [McpAuth] becomes its
                // canonical request header, then user-supplied `config.headers`
                // are appended on top (the user wins on conflict — e.g. an
                // explicit `Authorization` row overrides the typed auth).
                val composedHeaders = composeHeaders(config = config)
                // The SSE plugin is required by both transports: classic SSE for the
                // event stream, Streamable HTTP for the inbound notification channel.
                // Installing it unconditionally lets either branch reuse the same
                // HttpClient without juggling `client.config { install(SSE) }` calls.
                val client = HttpClient {
                    install(SSE)
                    // Socket floor, deliberately set ABOVE our own call deadline
                    // ([toolCallTimeoutMs]) so the two never race: the engine Koog
                    // resolves is OkHttp, whose default read timeout is 10 s, and
                    // that default — not any decision of ours — was what actually
                    // ended every MCP call, measured at exactly 10.0 s on the
                    // reference device. `requestTimeoutMillis` stays unset on
                    // purpose: setting it alone is what made the call unbounded in
                    // the reverted first attempt at this fix (F12), because it
                    // replaces the engine's defaults without applying to the
                    // SSE-framed response path. The call deadline lives in
                    // [executeTool]; this only stops the socket undercutting it.
                    install(HttpTimeout) {
                        socketTimeoutMillis = toolCallTimeoutMs + SOCKET_TIMEOUT_SLACK_MS
                        connectTimeoutMillis = connectTimeoutMs
                    }
                    if (composedHeaders.isNotEmpty()) {
                        defaultRequest {
                            composedHeaders.forEach { (key, value) -> headers.append(key, value) }
                        }
                    }
                }
                // try/finally (no catch) so every failure — including
                // cancellation — propagates unchanged while the locally created
                // client is still closed; a catch-and-rethrow here would have to
                // special-case CancellationException to keep cancellation
                // cooperative.
                var attached = false
                try {
                    // The handshake carries its own deadline for the same reason
                    // the tool call does (see [executeTool]): a server that accepts
                    // the socket and then goes quiet would otherwise spin the Tools
                    // row on "Connecting…" for as long as the process lives.
                    val established = withTimeoutOrNull(connectTimeoutMs) {
                        val transport: Transport = when (config.transport) {
                            McpTransport.SSE -> McpToolRegistryProvider.defaultSseTransport(
                                url = config.url,
                                baseClient = client,
                            )
                            McpTransport.STREAMABLE_HTTP -> client.mcpStreamableHttpTransport(url = config.url)
                        }
                        val serverInfo = McpServerInfo(url = config.url, command = "")
                        val toolRegistry = McpToolRegistryProvider.fromTransport(transport, serverInfo)
                        Session(httpClient = client, transport = transport, registry = toolRegistry)
                    } ?: throw IOException(
                        "MCP server ${config.url} did not complete the handshake within " +
                            "${connectTimeoutMs / MILLIS_PER_SECOND}s",
                    )
                    // Publish the session only after the transport has been attached
                    // successfully — failure paths must close the client locally.
                    session = established
                    attached = true
                } finally {
                    if (!attached) {
                        runCatching { client.close() }
                    }
                }
            }
        }
    }

    /**
     * Disconnects from the current MCP server: terminates the session
     * server-side, closes the HTTP client and drops the registry, so a
     * subsequent [connect] starts from a clean slate.
     */
    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            sessionMutex.withLock {
                tearDown(session)
                session = null
            }
        }
    }

    /**
     * Releases [current]'s server-side and local resources, in that order.
     *
     * The session is terminated on the server first (HTTP `DELETE` carrying the
     * session id) because that request needs the still-open [HttpClient]. MCP
     * servers keep a Streamable-HTTP session alive until it is explicitly
     * terminated or times out, so merely closing the socket strands it: a
     * directed on-device test left four orphaned sessions on one server in a
     * single run.
     *
     * Termination is best-effort — an unreachable or already-forgetful server
     * must not prevent the local teardown that follows in [finally].
     *
     * @param current session to release; `null` is a no-op so callers need no guard.
     */
    private suspend fun tearDown(current: Session?) {
        if (current == null) return
        try {
            (current.transport as? StreamableHttpClientTransport)?.terminateSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "MCP session termination failed; closing the transport anyway")
        } finally {
            current.httpClient.close()
        }
    }

    /**
     * Retrieves the list of available tools from the connected Koog ToolRegistry.
     * Maps the Koog tool descriptors to the domain-specific [AgentTool] models.
     *
     * @return A list of [AgentTool] objects, or an empty list if not connected.
     */
    override suspend fun getTools(): List<AgentTool> = withContext(Dispatchers.IO) {
        val current = sessionMutex.withLock { session }
        current?.registry?.tools?.map { tool ->
            AgentTool(
                name = tool.name,
                description = tool.descriptor.description,
                parameters = run {
                    val root = JSONObject()
                    root.put("type", "object")
                    val props = JSONObject()
                    val required = JSONArray()
                    tool.descriptor.requiredParameters.forEach { param ->
                        props.put(param.name, param.toJsonSchema())
                        required.put(param.name)
                    }
                    tool.descriptor.optionalParameters.forEach { param ->
                        props.put(param.name, param.toJsonSchema())
                    }
                    root.put("properties", props)
                    if (required.length() > 0) root.put("required", required)
                    root.toString()
                },
            )
        } ?: emptyList()
    }

    /**
     * Executes a specific tool by name from the Koog ToolRegistry.
     * Parses the JSON arguments and uses the Koog serializer to execute and format the result.
     *
     * The connection snapshot is taken under [sessionMutex] and the call itself
     * runs **outside** it: a slow tool must not block the Tools screen, and a
     * concurrent reconnect must not swap the registry out mid-lookup.
     *
     * A missing connection and a missing tool are reported as **different**
     * failures on purpose. Reporting a torn-down client as "tool not found"
     * tells the agent the tool does not exist, and the agent then plans around
     * a capability it actually has.
     *
     * The round-trip carries an explicit [toolCallTimeoutMs] deadline. Without
     * one the limit was whatever the transitively resolved Ktor engine happened
     * to default to — measured at exactly 10 s on the reference device, a value
     * nobody chose, documented nowhere and free to change on any Ktor/Koog bump
     * (found by a directed on-device test). The deadline is applied here with
     * [withTimeoutOrNull] rather than through Ktor's `HttpTimeout` plugin
     * **on purpose**: that plugin does not apply to MCP's SSE-framed response
     * path, so installing it removed the engine's own socket timeout without
     * supplying a replacement and made the call unbounded — a hung call then
     * froze the whole task queue, since it is a single serial worker (F13).
     * `withTimeoutOrNull` also keeps the timeout from surfacing as a
     * [CancellationException]: a cancellation would propagate through
     * `ToolRepositoryImpl` and take the entire run down instead of being
     * reported as one failed tool call.
     *
     * @param name The name of the tool to execute.
     * @param arguments A JSON string representing the arguments.
     * @return A string containing the serialized result of the execution.
     * @throws IllegalStateException if the client is not connected.
     * @throws IllegalArgumentException if the server does not advertise [name].
     * @throws IOException if the server does not answer within [toolCallTimeoutMs].
     */
    override suspend fun executeTool(name: String, arguments: String): String = withContext(Dispatchers.IO) {
        networkActivityTracker?.recordOutbound()
        val current = sessionMutex.withLock { session }
            ?: throw IllegalStateException("MCP client is not connected; cannot execute $name")
        val tool = current.registry.getToolOrNull(name)
            ?: throw IllegalArgumentException("Tool $name not found")

        withTimeoutOrNull(toolCallTimeoutMs) {
            val kotlinxJsonArgs = Json.parseToJsonElement(arguments).jsonObject
            val koogJsonArgs = kotlinxJsonArgs.toKoogJSONObject()
            // Fail with a descriptive error rather than an opaque NPE when a
            // misbehaving MCP server / Koog tool yields null for the decoded args or
            // the result; the caller (ToolInvocationGate) maps the throw to a tool
            // error observation.
            val args = tool.decodeArgs(koogJsonArgs, serializer)
                ?: throw IllegalStateException("MCP tool $name produced null decoded arguments")
            val result = tool.executeUnsafe(args)
                ?: throw IllegalStateException("MCP tool $name produced a null result")
            tool.encodeResultToStringUnsafe(result, serializer)
        } ?: throw IOException(
            "MCP tool $name did not respond within ${toolCallTimeoutMs / MILLIS_PER_SECOND}s",
        )
    }

    /**
     * Renders this parameter as a JSON-Schema fragment, preserving both its
     * declared type and its description.
     *
     * Every parameter used to be advertised as `{"type":"string"}` regardless of
     * what the server declared. The model then dutifully produced `"300"` for a
     * numeric field and the server rejected the call on schema validation, which
     * made **any MCP tool with a non-string parameter unusable** — found by a
     * directed on-device test against `trigger-long-running-operation`.
     * The description was dropped too, leaving the model to guess an argument's
     * meaning from its name alone.
     */
    private fun ToolParameterDescriptor.toJsonSchema(): JSONObject = type.toJsonSchema().apply {
        if (description.isNotBlank()) put("description", description)
    }

    /**
     * Maps a Koog [ToolParameterType] onto its JSON-Schema equivalent.
     *
     * `Integer` and `Float` are kept distinct (`integer` / `number`) because a
     * server validating a whole-number field rejects `1.5`, and collapsing both
     * to `number` would let the model offer one.
     *
     * The `when` is deliberately exhaustive over the sealed hierarchy rather than
     * carrying an `else`: a type Koog adds later should break the build here, not
     * silently fall back to a guess. Guessing `string` for everything is exactly
     * how F11 happened.
     */
    private fun ToolParameterType.toJsonSchema(): JSONObject = when (this) {
        is ToolParameterType.String -> JSONObject().put("type", "string")
        is ToolParameterType.Integer -> JSONObject().put("type", "integer")
        is ToolParameterType.Float -> JSONObject().put("type", "number")
        is ToolParameterType.Boolean -> JSONObject().put("type", "boolean")
        is ToolParameterType.Null -> JSONObject().put("type", "null")
        is ToolParameterType.Enum -> JSONObject()
            .put("type", "string")
            .put("enum", JSONArray().apply { entries.forEach { put(it) } })
        is ToolParameterType.List -> JSONObject()
            .put("type", "array")
            .put("items", itemsType.toJsonSchema())
        is ToolParameterType.Object -> JSONObject().apply {
            put("type", "object")
            put(
                "properties",
                JSONObject().apply {
                    properties.forEach { put(it.name, it.toJsonSchema()) }
                },
            )
            if (requiredProperties.isNotEmpty()) {
                put("required", JSONArray().apply { requiredProperties.forEach { put(it) } })
            }
            additionalProperties?.let { put("additionalProperties", it) }
        }
        is ToolParameterType.AnyOf -> JSONObject()
            .put("anyOf", JSONArray().apply { types.forEach { put(it.toJsonSchema()) } })
    }

    /** Header-composition + auth helpers shared across instances. */
    companion object {
        /**
         * Default deadline for one tool round-trip. Matches the 60 s the
         * project already requires of cloud LLM calls (`api-conventions.md`):
         * real MCP tools search, index or run a model of their own, and the
         * accidental 10 s engine default was well under what they need.
         */
        internal const val TOOL_CALL_TIMEOUT_MS = 60_000L

        /**
         * Default deadline for the connect handshake. Shorter than
         * [TOOL_CALL_TIMEOUT_MS] because this one blocks a person looking at
         * the Tools screen rather than a background tool call.
         */
        internal const val CONNECT_TIMEOUT_MS = 30_000L

        /**
         * How far the socket read timeout sits above [TOOL_CALL_TIMEOUT_MS], so
         * a slow-but-alive server is ended by our deadline with its own error
         * text rather than by a lower-level socket error five seconds earlier.
         */
        private const val SOCKET_TIMEOUT_SLACK_MS = 5_000L

        /** Divisor for rendering a millisecond deadline as seconds in error text. */
        private const val MILLIS_PER_SECOND = 1_000L

        /**
         * Builds the final request-header map for [config]: typed
         * [McpAuth] is rendered first, then user-supplied
         * `config.headers` are overlaid on top (custom rows win on
         * conflict — that's the documented power-user override).
         */
        internal fun composeHeaders(config: McpServerConfig): Map<String, String> {
            val builder = LinkedHashMap<String, String>()
            when (val auth = config.auth) {
                is McpAuth.None -> Unit
                is McpAuth.Bearer -> if (auth.token.isNotBlank()) {
                    builder[HttpHeaders.Authorization] = "Bearer ${auth.token}"
                }
                is McpAuth.Basic -> {
                    val credentials = "${auth.username}:${auth.password}"
                    val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
                    builder[HttpHeaders.Authorization] = "Basic $encoded"
                }
                is McpAuth.ApiKey -> if (auth.headerName.isNotBlank() && auth.value.isNotBlank()) {
                    builder[auth.headerName] = auth.value
                }
            }
            config.headers.forEach { (key, value) -> builder[key] = value }
            return builder
        }
    }
}

/**
 * Factory class for creating [KoogMcpClient] instances.
 * Injected via Hilt for dependency management.
 */
class KoogMcpClientFactory @Inject constructor(private val networkActivityTracker: NetworkActivityTracker) :
    McpClientFactory {
    /**
     * Creates a new instance of [KoogMcpClient]. Each instance carries the
     * shared [NetworkActivityTracker] so MCP traffic surfaces in the More
     * tab's privacy indicator.
     *
     * @return A new [McpClient] implementation.
     */
    override fun create(): McpClient = KoogMcpClient(networkActivityTracker = networkActivityTracker)
}
