package app.knotwork.android.data.tools.local.executors

import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.ToolExecutionContext
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.LocalToolExecutor
import app.knotwork.android.domain.repositories.NetworkActivityTracker
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.HttpRequestPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.min

/**
 * [LocalToolExecutor] for the built-in `http_request` tool — the agent's only
 * outbound HTTP capability, and the most security-sensitive tool in the
 * workspace set. In combination with `read_file` an over-permissive HTTP tool
 * is a data-exfiltration channel (a prompt injection inside an untrusted file
 * asking the agent to POST the contents out), so every call passes through a
 * stack of refusals before a byte leaves the device:
 *
 *  1. **Method gate** — only `GET` / `POST` / `PUT` / `DELETE` are supported;
 *     [HttpRequestPolicy.methodRisk] also drives the per-method HITL risk
 *     (`GET` → SENSITIVE, the rest → DESTRUCTIVE), resolved upstream in
 *     `ToolRepositoryImpl.getRisk` from the same source.
 *  2. **Allowlist gate** — while [SettingsRepository.allowedHttpDomains] is
 *     empty the tool is not even published to the agent; a direct call is
 *     refused here. A target host matching no entry is refused before connect.
 *  3. **Transport gate** — public hosts must use `https://`; cleartext is only
 *     tolerated for loopback / private-LAN addresses (the Ollama exception that
 *     `network_security_config.xml` also carves out).
 *  4. **Header gate** — a header the transport owns (`Host`, `Content-Length`,
 *     `Transfer-Encoding`, `Connection`, …) is refused: `Host` would choose the
 *     virtual host behind an allowlisted address, which the allowlist never saw.
 *  5. **Credential gate** — if any stored provider API key value appears in an
 *     outgoing header name or value, the body or the URL — the latter two also
 *     percent-decoded — the call is refused with "request contains a stored
 *     credential". A key the model splits or encodes some other way is not
 *     recognised; no substring filter can promise more.
 *  6. **Redirect gate** — automatic redirects are disabled; each hop is
 *     re-validated against the same allowlist / transport rules, and a redirect
 *     pointing outside the allowlist aborts the call.
 *
 * The response is read up to [SettingsRepository.httpToolMaxResponseBytes] and a
 * truncation marker is appended past the cap, bounding how much untrusted remote
 * content one call can inject into the local model's context. Every failure is
 * mapped to a readable observation string instead of throwing — the agent sees
 * the cause and can react.
 *
 * **Every hop has a deadline and ends with the run.** A call is bounded by
 * [callDeadlineMs] as a whole — a response that trickles never trips a per-read
 * timeout — and is cancelled when the calling coroutine is: the blocking OkHttp
 * call does not notice a coroutine cancel on its own, so *Stop* used to wait for
 * the body, and every chat queued behind the run waited with it.
 *
 * @property okHttpClient Shared client; a per-call derivative disables automatic
 *   redirect following so each hop can be validated.
 * @property settingsRepository Source of the allowlist and the response-size cap.
 * @property apiKeyRepository Source of the stored provider keys scanned for leaks.
 * @property networkActivityTracker Told about every hop sent, so the More tab's privacy
 *   indicator counts this tool's requests.
 * @property callDeadlineMs Longest one hop may take, from connecting to reading
 *   the last byte of the body; a test shortens it.
 */
class HttpRequestExecutor internal constructor(
    private val okHttpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository,
    private val apiKeyRepository: ApiKeyRepository,
    private val networkActivityTracker: NetworkActivityTracker,
    private val callDeadlineMs: Long,
) : LocalToolExecutor {

    /**
     * Creates the executor with the production call deadline.
     *
     * @param okHttpClient Shared client.
     * @param settingsRepository Source of the allowlist and the response-size cap.
     * @param apiKeyRepository Source of the stored provider keys scanned for leaks.
     * @param networkActivityTracker Told about every hop sent.
     */
    @Inject
    constructor(
        okHttpClient: OkHttpClient,
        settingsRepository: SettingsRepository,
        apiKeyRepository: ApiKeyRepository,
        networkActivityTracker: NetworkActivityTracker,
    ) : this(okHttpClient, settingsRepository, apiKeyRepository, networkActivityTracker, CALL_DEADLINE_MS)

    override val toolName: String = TOOL_NAME

    @Suppress("ReturnCount")
    override suspend fun execute(arguments: String, context: ToolExecutionContext): String {
        val json = try {
            JSONObject(arguments)
        } catch (e: JSONException) {
            Timber.w(e, "http_request received malformed argument JSON")
            return "Error: arguments must be a JSON object with 'method' and 'url'."
        }

        val method = json.optString("method", "GET").trim().uppercase()
        if (HttpRequestPolicy.methodRisk(method) == null) {
            return "Error: unsupported method '$method'. Use GET, POST, PUT or DELETE."
        }

        val rawUrl = json.optString("url", "").trim()
        if (rawUrl.isEmpty()) return "Error: missing 'url' argument."

        val allowed = settingsRepository.allowedHttpDomains.firstOrNull().orEmpty()
        if (allowed.isEmpty()) {
            return "Error: http_request is disabled — no allowed domains are configured. " +
                "Add a domain in Settings → Tools → Allowed domains to enable it."
        }

        val parsedUrl = rawUrl.toHttpUrlOrNull()
            ?: return "Error: '$rawUrl' is not a valid absolute http(s) URL."

        targetError(parsedUrl, allowed)?.let { return "Error: $it" }

        val headers = parseHeaders(json)
        headers.firstOrNull { (name, _) -> name.trim().lowercase() in TRANSPORT_HEADERS }?.let { (name, _) ->
            return "Error: header '$name' is set by the transport from the URL and the body; remove it."
        }
        val body = if (HttpRequestPolicy.methodAllowsBody(method)) json.optString("body", "") else null

        // Scan the URL too, not just headers/body: for a GET the query string and
        // path are the easiest channel to smuggle a stored key out
        // (`https://allowed.example/log?k=sk-…`), so leaving the URL unchecked
        // would defeat the credential filter for the most common method. Header
        // names and the percent-decoded URL and body are scanned too; a key the
        // model splits or encodes some other way is beyond a substring filter.
        val scanTexts = buildList {
            headers.forEach { (name, value) ->
                add(name)
                add(value)
            }
            body?.takeIf { it.isNotEmpty() }?.let {
                add(it)
                add(percentDecoded(it))
            }
            add(rawUrl)
            add(percentDecoded(rawUrl))
        }
        if (HttpRequestPolicy.leaksCredential(scanTexts, collectStoredSecrets())) {
            return "Error: request contains a stored credential — refusing to send a saved API key off-device."
        }

        val maxBytes = settingsRepository.httpToolMaxResponseBytes.firstOrNull()
            ?: SettingsDefaults.HTTP_TOOL_MAX_RESPONSE_BYTES_DEFAULT

        return withContext(Dispatchers.IO) {
            try {
                runRequest(parsedUrl, method, body, headers, allowed, maxBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Timber.w(e, "http_request to %s failed", parsedUrl.host)
                "Error: request to '${parsedUrl.host}' failed: ${e.message}"
            }
        }
    }

    /**
     * Executes the request, following redirects manually so every hop can be
     * re-validated against the allowlist before it is taken. Returns the
     * formatted response, or a refusal string when a redirect leaves the
     * allowlist.
     */
    @Suppress("ReturnCount")
    private suspend fun runRequest(
        startUrl: HttpUrl,
        startMethod: String,
        startBody: String?,
        headers: List<Pair<String, String>>,
        allowed: List<String>,
        maxBytes: Long,
    ): String {
        val client = okHttpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(SettingsDefaults.HTTP_TOOL_TIMEOUT_MS_DEFAULT, TimeUnit.MILLISECONDS)
            .readTimeout(SettingsDefaults.HTTP_TOOL_TIMEOUT_MS_DEFAULT, TimeUnit.MILLISECONDS)
            .callTimeout(callDeadlineMs, TimeUnit.MILLISECONDS)
            .build()

        var url = startUrl
        var method = startMethod
        var body = startBody
        var currentHeaders = headers
        var hopCount = 0
        while (true) {
            // Every hop is a request of its own, and a redirect can lead to another host.
            networkActivityTracker.recordOutbound()
            val call = client.newCall(buildRequest(url, method, body, currentHeaders))
            val hop = call.cancellingWithCaller { response ->
                val location = response.header("Location")
                if (response.isRedirect && hopCount < SettingsDefaults.HTTP_TOOL_MAX_REDIRECTS && location != null) {
                    // Only the status and the location are needed from a redirect.
                    response.close()
                    Hop.Redirect(response.code, location)
                } else {
                    Hop.Final(formatResponse(response, maxBytes))
                }
            }
            val redirect = when (hop) {
                is Hop.Final -> return hop.text
                is Hop.Redirect -> hop
            }
            val next = url.resolve(redirect.location)
                ?: return "Error: redirect to an unresolvable location '${redirect.location}'."
            targetError(next, allowed)?.let { return "Error: redirect blocked — $it" }
            // 301/302/303 demote the follow-up to a bodyless GET (browser semantics);
            // 307/308 preserve the method and body.
            if (redirect.code == HTTP_MOVED_PERMANENTLY ||
                redirect.code == HTTP_FOUND ||
                redirect.code == HTTP_SEE_OTHER
            ) {
                method = "GET"
                body = null
            }
            // Drop credential headers when the redirect crosses to a different
            // host, mirroring OkHttp's automatic-redirect behaviour we forgo here.
            currentHeaders = HttpRequestPolicy.headersForRedirect(
                headers = currentHeaders,
                fromHost = url.host,
                toHost = next.host,
            )
            url = next
            hopCount++
        }
    }

    /** What one hop produced: the formatted final response, or a redirect to follow. */
    private sealed interface Hop {
        /** The response to hand back, formatted. */
        data class Final(val text: String) : Hop

        /** A redirect with its status [code] and the [location] it points at. */
        data class Redirect(val code: Int, val location: String) : Hop
    }

    /**
     * Executes the call and hands its response to [block] on the IO dispatcher,
     * cancelling the call when the calling coroutine is cancelled.
     *
     * A blocking OkHttp call does not notice a coroutine cancel: *Stop* would wait
     * for the body to end, or for a read to stall past its timeout. `Call.cancel`
     * unblocks it, including a body read inside [block].
     *
     * @param block Reads the response; runs on the IO dispatcher and must close it.
     * @return What [block] returned.
     */
    private suspend fun <T> Call.cancellingWithCaller(block: (Response) -> T): T = coroutineScope {
        val call = this@cancellingWithCaller
        val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) { block(call.execute()) }
        } finally {
            canceller.cancel()
        }
    }

    /** [text] percent-decoded, or unchanged when it holds no valid escape. */
    private fun percentDecoded(text: String): String = try {
        URLDecoder.decode(text, Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        text
    }

    /** Builds the OkHttp [Request] for one hop. */
    private fun buildRequest(
        url: HttpUrl,
        method: String,
        body: String?,
        headers: List<Pair<String, String>>,
    ): Request {
        // A null media type leaves the body's Content-Type unset so an explicit
        // user-provided Content-Type header (added below) is the single source.
        val requestBody = if (HttpRequestPolicy.methodAllowsBody(method)) {
            (body ?: "").toRequestBody(null)
        } else {
            null
        }
        val builder = Request.Builder().url(url).method(method, requestBody)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    /**
     * Renders [response] as `HTTP <code>` + truncated headers + a body capped at
     * [SettingsRepository.httpToolMaxResponseBytes]. Always closes the response.
     */
    private fun formatResponse(response: Response, maxBytes: Long): String = response.use {
        val headerText = it.headers
            .joinToString(separator = "\n") { header -> "${header.first}: ${header.second}" }
            .let { text ->
                if (text.length > HEADER_CHAR_LIMIT) {
                    text.take(HEADER_CHAR_LIMIT) + "\n[... headers truncated]"
                } else {
                    text
                }
            }

        val source = it.body.source()
        source.request(maxBytes + 1)
        val buffer = source.buffer
        val truncated = buffer.size > maxBytes
        val bytesToRead = min(buffer.size, maxBytes)
        val bodyText = String(buffer.readByteArray(bytesToRead), Charsets.UTF_8)

        buildString {
            append("HTTP ").append(it.code)
            if (headerText.isNotEmpty()) {
                append('\n').append(headerText)
            }
            append("\n\n")
            append(bodyText)
            if (truncated) {
                append("\n[... response truncated at ").append(maxBytes).append(" bytes]")
            }
        }
    }

    /** Validates [url] against the allowlist and transport policy; `null` ⇒ admissible. */
    private fun targetError(url: HttpUrl, allowed: List<String>): String? {
        val host = url.host
        if (!HttpRequestPolicy.isHostAllowed(host, allowed)) {
            return "target domain '$host' is not in the allowlist."
        }
        if (url.scheme != "https" && !HttpRequestPolicy.isLoopbackOrPrivateHost(host)) {
            return "only https is allowed for '$host' (cleartext is permitted for local addresses only)."
        }
        return null
    }

    /** Parses the optional `headers` JSON object into ordered name/value pairs. */
    private fun parseHeaders(json: JSONObject): List<Pair<String, String>> {
        val headersObj = json.optJSONObject("headers") ?: return emptyList()
        val result = mutableListOf<Pair<String, String>>()
        val keys = headersObj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            if (name.isNotBlank()) result.add(name to headersObj.optString(name, ""))
        }
        return result
    }

    /** Reads all stored, non-blank provider API key values for the credential scan. */
    private suspend fun collectStoredSecrets(): List<String> = listOfNotNull(
        apiKeyRepository.getOpenAIKey().firstOrNull(),
        apiKeyRepository.getAnthropicKey().firstOrNull(),
        apiKeyRepository.getGoogleKey().firstOrNull(),
        apiKeyRepository.getDeepSeekKey().firstOrNull(),
    ).filter { it.isNotBlank() }

    companion object {
        /** Tool name as exposed to the LLM and used as the DI map key. */
        const val TOOL_NAME = "http_request"

        /** Human-facing label for the browser editor's tool dropdown. */
        const val TOOL_LABEL: String = "HTTP Request"

        /** Maximum characters of response headers echoed back to the agent. */
        private const val HEADER_CHAR_LIMIT = 2_000

        /**
         * Longest one hop may take as a whole, connecting to the last byte of the
         * body (60 s): the same deadline an MCP tool call gets.
         */
        const val CALL_DEADLINE_MS: Long = 60_000L

        /**
         * Headers the transport owns, lower-cased: set from the URL and the body,
         * never by the model. `Host` in particular would pick the virtual host
         * behind an allowlisted address.
         */
        private val TRANSPORT_HEADERS = setOf(
            "host", "content-length", "transfer-encoding", "connection", "upgrade", "te", "trailer",
            "keep-alive", "proxy-connection", "proxy-authorization", "expect",
        )

        private const val HTTP_MOVED_PERMANENTLY = 301
        private const val HTTP_FOUND = 302
        private const val HTTP_SEE_OTHER = 303

        /** Human-readable description steering the model on when/how to call. */
        const val DESCRIPTION: String =
            "Performs an HTTP request to a remote API and returns the status, headers and (truncated) body. " +
                "Supports GET, POST, PUT and DELETE. ONLY domains the user has explicitly added to the " +
                "allowlist (Settings → Tools → Allowed domains) can be reached; any other host is refused. " +
                "Public hosts must use https. Never put a stored API key in headers or body — such requests " +
                "are refused. GET requires confirmation; POST/PUT/DELETE require an explicit destructive-action " +
                "confirmation."

        /** JSON-schema of the accepted arguments. */
        val PARAMETERS: String = """
            {
              "type": "object",
              "properties": {
                "method": { "type": "string", "description": "HTTP method: GET, POST, PUT or DELETE. Default GET." },
                "url": { "type": "string", "description": "Absolute https URL whose host is in the allowlist (e.g. https://api.example.com/v1/items)." },
                "headers": { "type": "object", "description": "Optional request headers as a flat name→value object." },
                "body": { "type": "string", "description": "Optional request body for POST/PUT/DELETE." }
              },
              "required": ["method", "url"]
            }
        """.trimIndent()
    }
}
