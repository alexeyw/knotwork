package app.knotwork.android.data.mcp

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.agents.mcp.metadata.McpServerInfo
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.kotlinx.toKoogJSONObject
import androidx.annotation.VisibleForTesting
import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.McpAuth
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.McpTransport
import app.knotwork.android.domain.repositories.NetworkActivityTracker
import app.knotwork.android.domain.repositories.SettingsRepository
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
import kotlinx.coroutines.flow.firstOrNull
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
 *
 * ### Everything a server sends is bounded here
 *
 * A server's catalogue and its tool results are untrusted remote text: the
 * catalogue lands in the system prompt of every run through `$TOOLS`, a result
 * in the chat history, the run state and every later prompt. This client is
 * where both enter the app, and the one place that covers the agent's path and
 * the Tools screen's alike, so the limits live here:
 *
 * - a tool whose name breaks the MCP naming rule ([isPublishableName]) is not
 *   published;
 * - a description — the tool's or a parameter's — is clamped to
 *   [MAX_DESCRIPTION_CHARS];
 * - a server publishes at most [MAX_PUBLISHED_TOOLS] tools and
 *   [CATALOGUE_BUDGET_CHARS] of catalogue; the tail past either is left out;
 * - a result is cut at [resultByteBudget] — the user's *Largest tool response*
 *   setting, shared with `http_request` — with a marker saying so.
 *
 * A tool that is not published cannot be executed either.
 *
 * @param networkActivityTracker feeds the privacy indicator; `null` in tests.
 * @param resultByteBudget reads the current result budget, in UTF-8 bytes, on
 *   every call, so a change to the setting applies to the next call.
 */
@OptIn(ai.koog.agents.core.tools.annotations.InternalAgentToolsApi::class)
class KoogMcpClient(
    private val networkActivityTracker: NetworkActivityTracker? = null,
    private val resultByteBudget: suspend () -> Long = { SettingsDefaults.HTTP_TOOL_MAX_RESPONSE_BYTES_DEFAULT },
) : McpClient {

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
    private class Session(val httpClient: HttpClient, val transport: Transport, val registry: ToolRegistry) {
        /**
         * The registry's tools with the catalogue limits applied, computed on first
         * use. The registry is fixed for the life of a session (Koog lists the tools
         * once, at connect), so the published set is too — and a hostile catalogue's
         * warnings are logged once per session instead of on every prompt render.
         * Two readers racing on the first use both compute the same list.
         */
        @Volatile
        var published: List<AgentTool>? = null
    }

    /** [Session.published], computing it on first use. */
    private fun Session.publishedTools(): List<AgentTool> =
        published ?: publishedTools(registry.tools).also { published = it }

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
     * Retrieves the tools this server publishes to the app: the Koog registry's
     * tools mapped to [AgentTool], with the catalogue limits applied (see the
     * class KDoc) — a malformed name is skipped, descriptions are clamped, and
     * the list stops at the count or size limit.
     *
     * @return The published tools, in the server's order, or an empty list if not connected.
     */
    override suspend fun getTools(): List<AgentTool> = withContext(Dispatchers.IO) {
        val current = sessionMutex.withLock { session } ?: return@withContext emptyList()
        current.publishedTools()
    }

    /**
     * Applies the catalogue limits to [tools], in the server's order. The size
     * budget counts what a prompt renders — name, description and parameter
     * schema — and publishing stops at the first tool that would exceed it, so
     * the published set is a stable prefix rather than whichever tools happen to
     * be small.
     */
    private fun publishedTools(tools: List<ToolBase<*, *>>): List<AgentTool> {
        val published = mutableListOf<AgentTool>()
        var budget = CATALOGUE_BUDGET_CHARS
        for (tool in tools) {
            if (!isPublishableName(tool.name)) {
                // The name itself is not logged: it is the part that broke the rule.
                Timber.w("MCP tool with a malformed name (%d chars) was not published", tool.name.length)
                continue
            }
            if (published.size == MAX_PUBLISHED_TOOLS) {
                Timber.w("MCP server publishes more than %d tools; the rest were not published", MAX_PUBLISHED_TOOLS)
                break
            }
            val agentTool = tool.toAgentTool()
            val size = agentTool.name.length + agentTool.description.length + agentTool.parameters.length
            if (size > budget) {
                Timber.w(
                    "MCP catalogue exceeds %d chars at tool %s; it and the rest were not published",
                    CATALOGUE_BUDGET_CHARS,
                    agentTool.name,
                )
                break
            }
            budget -= size
            published += agentTool
        }
        return published
    }

    /** Maps one registry tool to an [AgentTool], clamping every description on the way. */
    private fun ToolBase<*, *>.toAgentTool(): AgentTool = AgentTool(
        name = name,
        description = clampDescription(descriptor.description),
        parameters = run {
            val root = JSONObject()
            root.put("type", "object")
            val props = JSONObject()
            val required = JSONArray()
            descriptor.requiredParameters.forEach { param ->
                props.put(param.name, param.toJsonSchema())
                required.put(param.name)
            }
            descriptor.optionalParameters.forEach { param ->
                props.put(param.name, param.toJsonSchema())
            }
            root.put("properties", props)
            if (required.length() > 0) root.put("required", required)
            root.toString()
        },
    )

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
     * The result is cut at [resultByteBudget] with a marker: it is untrusted text
     * that the chat history, the run state and every later prompt would otherwise
     * carry whole. The cut bounds what leaves this client, not what the transport
     * buffered to decode the response — on the wire the only bound is the deadline.
     *
     * @return A string containing the serialized result of the execution.
     * @throws IllegalStateException if the client is not connected.
     * @throws IllegalArgumentException if the server does not publish [name] (see [getTools]).
     * @throws IOException if the server does not answer within [toolCallTimeoutMs].
     */
    override suspend fun executeTool(name: String, arguments: String): String = withContext(Dispatchers.IO) {
        networkActivityTracker?.recordOutbound()
        val current = sessionMutex.withLock { session }
            ?: throw IllegalStateException("MCP client is not connected; cannot execute $name")
        // A tool the catalogue limits left out is not callable either: otherwise a
        // name the model learnt elsewhere would reach a tool nobody reviewed.
        val tool = current.registry.getToolOrNull(name)
            ?.takeIf { current.publishedTools().any { published -> published.name == name } }
            ?: throw IllegalArgumentException("Tool $name not found")

        val text = withTimeoutOrNull(toolCallTimeoutMs) {
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
        capResult(text = text, maxBytes = resultByteBudget())
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
        if (description.isNotBlank()) put("description", clampDescription(description))
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
         * Longest description kept, for a tool or a parameter. The MCP
         * specification sets no limit, so the number is ours: about 3.7× the
         * longest description in GitHub's MCP server (1 115 chars across its 125
         * tools), so a real catalogue is untouched while one description can no
         * longer fill the prompt.
         */
        internal const val MAX_DESCRIPTION_CHARS = 4_096

        /** Appended to a clamped description, so the model can tell it was cut. */
        internal const val DESCRIPTION_TRUNCATED_MARKER = " [... description truncated]"

        /**
         * Most tools one server publishes. About twice GitHub's MCP server (125
         * tools), one of the largest real catalogues.
         */
        internal const val MAX_PUBLISHED_TOOLS = 256

        /**
         * Largest catalogue one server publishes, counted as the characters a
         * prompt renders (name + description + parameter schema). About twice the
         * whole of GitHub's MCP server (≈124 000 chars). It bounds an unbounded or
         * hostile catalogue; it does not make a big honest one fit a small model's
         * context — switching unneeded tools off does that.
         */
        internal const val CATALOGUE_BUDGET_CHARS = 256 * 1024

        /**
         * The MCP specification's tool-name rule: 1–128 characters from
         * `A–Z a–z 0–9 _ - .`. A name outside it is not published — it cannot be
         * a real server's ordinary tool, and it is exactly the field a hostile
         * catalogue would use to forge a line of the prompt's tool list.
         */
        private val PUBLISHABLE_NAME = Regex("[A-Za-z0-9_.-]{1,128}")

        /** Bytes a UTF-16 char can take in UTF-8 at most (a surrogate pair: 4 bytes for 2 chars). */
        private const val MAX_UTF8_BYTES_PER_CHAR = 3

        /** Mask selecting the top two bits of a UTF-8 byte. */
        private const val UTF8_TOP_BITS_MASK = 0xC0

        /** Top two bits of a UTF-8 continuation byte (`10xxxxxx`). */
        private const val UTF8_CONTINUATION_BITS = 0x80

        /** Whether [name] follows the MCP tool-name rule and may be published. */
        internal fun isPublishableName(name: String): Boolean = PUBLISHABLE_NAME.matches(name)

        /**
         * Clamps [description] to [MAX_DESCRIPTION_CHARS], never splitting a
         * surrogate pair, and marks the cut.
         */
        internal fun clampDescription(description: String): String {
            if (description.length <= MAX_DESCRIPTION_CHARS) return description
            var end = MAX_DESCRIPTION_CHARS
            if (Character.isHighSurrogate(description[end - 1])) end--
            return description.substring(0, end) + DESCRIPTION_TRUNCATED_MARKER
        }

        /**
         * Cuts [text] to at most [maxBytes] UTF-8 bytes on a character boundary
         * and appends the same kind of marker `http_request` uses for a cut body.
         *
         * @param text the tool result.
         * @param maxBytes the budget in bytes.
         * @return [text] unchanged when it fits, otherwise its longest prefix that
         *   fits, followed by `[... result truncated at <maxBytes> bytes]`.
         */
        internal fun capResult(text: String, maxBytes: Long): String {
            // Cheap exit for the common case, without encoding the whole result.
            if (text.length.toLong() * MAX_UTF8_BYTES_PER_CHAR <= maxBytes) return text
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size <= maxBytes) return text
            var end = maxBytes.toInt()
            // `bytes[end]` is the first byte left out; while it continues a character,
            // that character is split, so step back to where it starts.
            while (end > 0 && (bytes[end].toInt() and UTF8_TOP_BITS_MASK) == UTF8_CONTINUATION_BITS) end--
            return String(bytes, 0, end, Charsets.UTF_8) + "\n[... result truncated at $maxBytes bytes]"
        }

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
class KoogMcpClientFactory @Inject constructor(
    private val networkActivityTracker: NetworkActivityTracker,
    private val settingsRepository: SettingsRepository,
) : McpClientFactory {
    /**
     * Creates a new instance of [KoogMcpClient]. Each instance carries the
     * shared [NetworkActivityTracker] so MCP traffic surfaces in the More
     * tab's privacy indicator, and reads the result budget from the same
     * *Largest tool response* setting `http_request` uses.
     *
     * @return A new [McpClient] implementation.
     */
    override fun create(): McpClient = KoogMcpClient(
        networkActivityTracker = networkActivityTracker,
        resultByteBudget = {
            settingsRepository.httpToolMaxResponseBytes.firstOrNull()
                ?: SettingsDefaults.HTTP_TOOL_MAX_RESPONSE_BYTES_DEFAULT
        },
    )
}
