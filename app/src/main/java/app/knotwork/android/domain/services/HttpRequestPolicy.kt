package app.knotwork.android.domain.services

import app.knotwork.android.domain.models.ToolRisk

/**
 * Pure, dependency-free security policy for the built-in `http_request` tool.
 *
 * Everything here is a decision the tool must make *before* a byte leaves the
 * device — method→risk mapping, allowlist membership, transport (https vs
 * cleartext) admissibility, credential-leak detection — kept out of the
 * data-layer executor so the rules are exhaustively unit-testable without an
 * [okhttp3.OkHttpClient] or a live socket. The executor
 * ([app.knotwork.android.data.tools.local.executors.HttpRequestExecutor]) is
 * the only caller; it parses the URL with OkHttp and feeds the resulting host /
 * scheme strings here.
 *
 * The policy is deliberately conservative: an unknown method, an empty
 * allowlist, a non-matching host, or a cleartext destination that is not a
 * loopback / private-LAN address all resolve to "refuse", because in
 * combination with `read_file` an over-permissive HTTP tool is a data
 * exfiltration channel (a prompt injection inside a read file asking the agent
 * to POST the data out).
 */
object HttpRequestPolicy {

    /**
     * HTTP methods the tool supports, each mapped to the [ToolRisk] the
     * Human-in-the-Loop gate should apply. A read (`GET`) is reversible and
     * scoped → [ToolRisk.SENSITIVE]; a state-changing write (`POST` / `PUT` /
     * `DELETE`) can have an irreversible remote effect → [ToolRisk.DESTRUCTIVE]
     * (the typed-confirmation path, also blockable by "block destructive
     * tools"). Any other method is unsupported and rejected before dispatch.
     */
    private val METHOD_RISK: Map<String, ToolRisk> = mapOf(
        "GET" to ToolRisk.SENSITIVE,
        "POST" to ToolRisk.DESTRUCTIVE,
        "PUT" to ToolRisk.DESTRUCTIVE,
        "DELETE" to ToolRisk.DESTRUCTIVE,
    )

    /** Methods that carry a request body (the others must not). */
    private val METHODS_WITH_BODY: Set<String> = setOf("POST", "PUT", "DELETE")

    /**
     * Request headers dropped when a redirect crosses to a different host —
     * mirrors OkHttp's automatic-redirect behaviour, which we lose by following
     * redirects manually. Keeps a bearer token / cookie from leaking to a second
     * host on the redirect chain (low risk here since both hosts are
     * allowlisted, but free defence-in-depth). Compared case-insensitively.
     */
    private val SENSITIVE_REDIRECT_HEADERS = setOf("authorization", "cookie", "proxy-authorization")

    /** Hostname / IPv4 literal grammar used to validate an allowlist entry. */
    private val HOST_PATTERN = Regex(
        "^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*$",
    )

    /** Length of the `"://"` scheme separator stripped during normalisation. */
    private const val SCHEME_SEPARATOR_LENGTH = 3

    /** Number of dotted octets in an IPv4 literal. */
    private const val IPV4_OCTET_COUNT = 4

    /** First octet of the loopback range `127.0.0.0/8`. */
    private const val LOOPBACK_FIRST_OCTET = 127

    /** First octet of the private range `10.0.0.0/8`. */
    private const val PRIVATE_CLASS_A_FIRST_OCTET = 10

    /** Octets of the private range `192.168.0.0/16`. */
    private const val PRIVATE_CLASS_C_FIRST_OCTET = 192
    private const val PRIVATE_CLASS_C_SECOND_OCTET = 168

    /** First octet and second-octet bounds of the private range `172.16.0.0/12`. */
    private const val PRIVATE_CLASS_B_FIRST_OCTET = 172
    private const val PRIVATE_CLASS_B_SECOND_OCTET_MIN = 16
    private const val PRIVATE_CLASS_B_SECOND_OCTET_MAX = 31

    /** Inclusive bounds of a valid IPv4 octet. */
    private val OCTET_RANGE = 0..255

    /**
     * Resolves the [ToolRisk] for an HTTP [method], or `null` when the method is
     * not one the tool supports. Case-insensitive.
     *
     * @param method The HTTP method (e.g. `"get"`, `"POST"`).
     * @return The mapped risk, or `null` for an unsupported method.
     */
    fun methodRisk(method: String): ToolRisk? = METHOD_RISK[method.trim().uppercase()]

    /**
     * Whether [method] is allowed to carry a request body.
     *
     * @param method The HTTP method.
     * @return `true` for `POST` / `PUT` / `DELETE`, `false` otherwise.
     */
    fun methodAllowsBody(method: String): Boolean = method.trim().uppercase() in METHODS_WITH_BODY

    /**
     * Normalises a user-typed allowlist entry to a bare host, or returns `null`
     * when the input is not a valid host. Strips an accidental scheme, path,
     * port, surrounding whitespace, and lower-cases the result.
     *
     * Used both by the Settings editor (validation + de-duplication) and as the
     * canonical form persisted in [app.knotwork.android.domain.repositories.SettingsRepository.allowedHttpDomains].
     *
     * @param input The raw text the user typed (e.g. `https://API.Example.com/v1`).
     * @return The normalised host (`api.example.com`), or `null` if invalid.
     */
    fun normalizeDomain(input: String): String? {
        var host = input.trim().lowercase()
        if (host.isEmpty()) return null
        val schemeIdx = host.indexOf("://")
        if (schemeIdx >= 0) host = host.substring(schemeIdx + SCHEME_SEPARATOR_LENGTH)
        // Isolate the authority (drop path/query) BEFORE stripping userinfo, so an
        // '@' inside a path can't be mistaken for a userinfo delimiter.
        host = host.substringBefore('/')
        host = host.substringBefore('?')
        // Drop any `user:pass@` userinfo. Without this, `user:pass@example.com`
        // would survive the next `substringBefore(':')` as the bogus host `user`.
        // substringAfterLast returns the whole string when '@' is absent, so this
        // is a no-op for a plain host.
        host = host.substringAfterLast('@')
        host = host.substringBefore(':') // drop any :port
        if (host.isEmpty()) return null
        return if (HOST_PATTERN.matches(host)) host else null
    }

    /**
     * Whether a request [host] is covered by the [allowed] list. Matching is
     * **exact** (case-insensitive): an entry `example.com` admits only
     * `example.com`, **not** `api.example.com` — sub-domains are not implied.
     * This is the least-privilege posture for an exfiltration-relevant control;
     * the user must add each host they intend to reach explicitly. An empty
     * allowlist matches nothing — the tool's "off" state.
     *
     * @param host The request target host (already scheme/port-free, as from
     *   `okhttp3.HttpUrl.host`).
     * @param allowed The configured allowlist of normalised hosts.
     * @return `true` when [host] exactly equals an allowlist entry.
     */
    fun isHostAllowed(host: String, allowed: List<String>): Boolean {
        val h = host.trim().lowercase()
        if (h.isEmpty()) return false
        return allowed.any { raw -> h == raw.trim().lowercase() }
    }

    /**
     * Returns the headers to carry on a redirect hop from [fromHost] to
     * [toHost]. When the host is unchanged the list is returned as-is; when the
     * redirect crosses to a different host, [SENSITIVE_REDIRECT_HEADERS] are
     * dropped so a credential header is not forwarded to the second host.
     *
     * @param headers the headers sent on the current hop.
     * @param fromHost host of the current request.
     * @param toHost host of the redirect target.
     * @return the headers to send on the next hop.
     */
    fun headersForRedirect(
        headers: List<Pair<String, String>>,
        fromHost: String,
        toHost: String,
    ): List<Pair<String, String>> {
        if (fromHost.equals(toHost, ignoreCase = true)) return headers
        return headers.filterNot { it.first.trim().lowercase() in SENSITIVE_REDIRECT_HEADERS }
    }

    /**
     * Whether [host] is a loopback or private-LAN address for which cleartext
     * (`http://`) is tolerated. The single definition of "the user's own
     * network": [CleartextPolicy] uses it for Ollama and MCP addresses and
     * [LocalOnlyPolicy] for the "Block network from local model" restriction.
     * Public hosts must use `https://`.
     *
     * An octet with a leading zero (`010`) is refused: `inet_aton`-style
     * resolvers read it as octal, so `010.0.0.1` can reach the public `8.0.0.1`
     * while reading as `10.0.0.1` here.
     *
     * @param host The request target host.
     * @return `true` for `localhost` and RFC-1918 / loopback IPv4 literals
     *   written in plain decimal.
     */
    fun isLoopbackOrPrivateHost(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h == "localhost") return true
        val octets = h.split('.')
        if (octets.size != IPV4_OCTET_COUNT || octets.any { it.toIntOrNull() == null }) return false
        if (octets.any { it.length > 1 && it.startsWith('0') }) return false
        val (a, b) = octets[0].toInt() to octets[1].toInt()
        if (octets.any { it.toInt() !in OCTET_RANGE }) return false
        return when {
            a == LOOPBACK_FIRST_OCTET -> true // 127.0.0.0/8 loopback
            a == PRIVATE_CLASS_A_FIRST_OCTET -> true // 10.0.0.0/8
            a == PRIVATE_CLASS_C_FIRST_OCTET && b == PRIVATE_CLASS_C_SECOND_OCTET -> true // 192.168.0.0/16
            a == PRIVATE_CLASS_B_FIRST_OCTET &&
                b in PRIVATE_CLASS_B_SECOND_OCTET_MIN..PRIVATE_CLASS_B_SECOND_OCTET_MAX -> true // 172.16.0.0/12
            else -> false
        }
    }

    /**
     * Whether any non-blank stored [secrets] value appears verbatim inside any
     * of the supplied request [texts] (header values and/or the body). A match
     * means the agent is about to ship one of the user's saved provider API keys
     * to a remote host — the call must be refused.
     *
     * @param texts Outbound request strings to scan (header values, body).
     * @param secrets Stored credential values to look for.
     * @return `true` when at least one secret is present in at least one text.
     */
    fun leaksCredential(texts: Iterable<String>, secrets: Iterable<String>): Boolean {
        val needles = secrets.map { it.trim() }.filter { it.isNotEmpty() }
        if (needles.isEmpty()) return false
        return texts.any { text -> needles.any { text.contains(it) } }
    }
}
