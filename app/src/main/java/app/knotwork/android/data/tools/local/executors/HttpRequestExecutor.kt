package app.knotwork.android.data.tools.local.executors

import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.ToolExecutionContext
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.LocalToolExecutor
import app.knotwork.android.domain.repositories.NetworkActivityTracker
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.HttpRequestPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
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
 *  4. **Credential gate** — if any stored provider API key value appears in an
 *     outgoing header or the body, the call is refused with
 *     "request contains a stored credential".
 *  5. **Redirect gate** — automatic redirects are disabled; each hop is
 *     re-validated against the same allowlist / transport rules, and a redirect
 *     pointing outside the allowlist aborts the call.
 *
 * The response is read up to [SettingsRepository.httpToolMaxResponseBytes] and a
 * truncation marker is appended past the cap, bounding how much untrusted remote
 * content one call can inject into the local model's context. Every failure is
 * mapped to a readable observation string instead of throwing — the agent sees
 * the cause and can react.
 *
 * @property okHttpClient Shared client; a per-call derivative disables automatic
 *   redirect following so each hop can be validated.
 * @property settingsRepository Source of the allowlist and the response-size cap.
 * @property apiKeyRepository Source of the stored provider keys scanned for leaks.
 * @property networkActivityTracker Told about every hop sent, so the More tab's privacy
 *   indicator counts this tool's requests.
 */
class HttpRequestExecutor @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository,
    private val apiKeyRepository: ApiKeyRepository,
    private val networkActivityTracker: NetworkActivityTracker,
) : LocalToolExecutor {

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
        val body = if (HttpRequestPolicy.methodAllowsBody(method)) json.optString("body", "") else null

        // Scan the URL too, not just headers/body: for a GET the query string and
        // path are the easiest channel to smuggle a stored key out
        // (`https://allowed.example/log?k=sk-…`), so leaving the URL unchecked
        // would defeat the credential filter for the most common method.
        val scanTexts = headers.map { it.second } + listOfNotNull(body?.takeIf { it.isNotEmpty() }, rawUrl)
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
    private fun runRequest(
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
            .build()

        var url = startUrl
        var method = startMethod
        var body = startBody
        var currentHeaders = headers
        var hop = 0
        while (true) {
            // Every hop is a request of its own, and a redirect can lead to another host.
            networkActivityTracker.recordOutbound()
            val response = client.newCall(buildRequest(url, method, body, currentHeaders)).execute()
            val code = response.code
            if (response.isRedirect && hop < SettingsDefaults.HTTP_TOOL_MAX_REDIRECTS) {
                val location = response.header("Location")
                if (location == null) {
                    return formatResponse(response, maxBytes)
                }
                val next = url.resolve(location)
                response.close()
                next ?: return "Error: redirect to an unresolvable location '$location'."
                targetError(next, allowed)?.let { return "Error: redirect blocked — $it" }
                // 301/302/303 demote the follow-up to a bodyless GET (browser semantics);
                // 307/308 preserve the method and body.
                if (code == HTTP_MOVED_PERMANENTLY || code == HTTP_FOUND || code == HTTP_SEE_OTHER) {
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
                hop++
                continue
            }
            return formatResponse(response, maxBytes)
        }
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
