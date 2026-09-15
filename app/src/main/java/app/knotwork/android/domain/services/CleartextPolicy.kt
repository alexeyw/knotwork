package app.knotwork.android.domain.services

/**
 * Pure policy deciding whether an unencrypted (`http://`) connection to a
 * user-configured host may be opened, and whether the user has agreed to it.
 *
 * ### Why this exists in app code rather than in the platform config
 *
 * Android's `network_security_config.xml` is the natural home for a cleartext
 * rule, and it was where this rule used to live — as a hand-written list of
 * fourteen private IP addresses. That list could only ever match a real user's
 * LAN address by coincidence, and the file ships inside the APK, so someone who
 * installed a release build could not add their own. The result was that
 * running a local Ollama or MCP server over HTTP — the exact scenario this
 * product is built around — worked only for whoever happened to use one of the
 * fourteen addresses.
 *
 * Android does not support ranges or wildcards there, so the rule cannot be
 * expressed in the platform config at all. It is expressed here instead:
 * cleartext is permitted **only** to a loopback / private-LAN address the user
 * has explicitly approved, and never to a public host. That is strictly
 * narrower than the fourteen-address list for public traffic (which the list
 * also refused) and strictly wider for the LAN (which is the point).
 *
 * The trade this makes is real and is recorded in `decisions.md`: the manifest
 * now permits cleartext app-wide, so the platform no longer blocks, for
 * example, a redirect from `https://` to `http://` on a stack this policy is
 * not wired into. The gates below cover every stack that talks to a
 * user-configured address; the residual exposure is redirects inside the
 * third-party Koog / Ktor clients.
 *
 * ### Consent is taken at configuration time, not at request time
 *
 * The network layer is three unrelated stacks (a shared OkHttp singleton, Koog's
 * Ktor client for cloud + Ollama, and one Ktor client per MCP connection) and
 * none of them is a place a person can be asked a question. The address, on the
 * other hand, is typed into a settings field — so that is where the app asks,
 * once per origin, and remembers the answer.
 */
object CleartextPolicy {

    /**
     * The verdict for one destination URL.
     */
    sealed interface Verdict {
        /** Encrypted, or a non-HTTP scheme — nothing for this policy to gate. */
        data object NotCleartext : Verdict

        /**
         * Cleartext to a loopback / private-LAN address the user has approved.
         * Allowed.
         *
         * @property origin the canonical `scheme://host[:port]` that was approved.
         */
        data class ApprovedPrivate(val origin: String) : Verdict

        /**
         * Cleartext to a loopback / private-LAN address the user has **not**
         * approved yet. Refused until they do — the caller should surface the
         * consent prompt rather than silently failing.
         *
         * @property origin the canonical origin the user would be approving.
         */
        data class NeedsApproval(val origin: String) : Verdict

        /**
         * Cleartext to a public host. Always refused, and not approvable —
         * sending a prompt (or an API key) unencrypted across the open internet
         * is not a trade the user should be offered.
         */
        data object PublicRefused : Verdict
    }

    /**
     * Classifies [url] against [approvedOrigins].
     *
     * @param url the destination URL, as the user typed or the app stored it.
     * @param approvedOrigins origins the user has already agreed to send
     *   unencrypted, in the [originOf] canonical form.
     * @return the [Verdict] for this destination.
     */
    fun classify(url: String, approvedOrigins: Set<String>): Verdict {
        val trimmed = url.trim()
        if (!trimmed.startsWith(HTTP_SCHEME, ignoreCase = true)) {
            return Verdict.NotCleartext
        }
        val host = hostOf(trimmed) ?: return Verdict.PublicRefused
        if (!HttpRequestPolicy.isLoopbackOrPrivateHost(host)) {
            return Verdict.PublicRefused
        }
        val origin = originOf(trimmed) ?: return Verdict.PublicRefused
        return if (origin in approvedOrigins) Verdict.ApprovedPrivate(origin) else Verdict.NeedsApproval(origin)
    }

    /**
     * Convenience predicate for the network gates: `true` when a connection to
     * [url] may be opened.
     *
     * @param url the destination URL.
     * @param approvedOrigins the user's approved origins.
     * @return `true` for encrypted destinations and approved private cleartext.
     */
    fun isAllowed(url: String, approvedOrigins: Set<String>): Boolean = when (classify(url, approvedOrigins)) {
        is Verdict.NotCleartext, is Verdict.ApprovedPrivate -> true
        is Verdict.NeedsApproval, is Verdict.PublicRefused -> false
    }

    /**
     * Human-readable reason a connection was refused, for logs and for the
     * error a node surfaces. Deliberately says what to do, not just what failed.
     *
     * @param verdict the refusing verdict.
     * @return the message, or `null` when [verdict] is not a refusal.
     */
    fun refusalMessage(verdict: Verdict): String? = when (verdict) {
        is Verdict.NeedsApproval ->
            "Unencrypted connections to ${verdict.origin} have not been approved. " +
                "Re-save the address in Settings and confirm the prompt."
        is Verdict.PublicRefused ->
            "Refusing an unencrypted connection to a public address. Use https:// instead."
        is Verdict.NotCleartext, is Verdict.ApprovedPrivate -> null
    }

    /**
     * Whether [host] is a loopback or private-LAN address — the only kind of
     * host unencrypted traffic may ever reach.
     *
     * Delegates to [HttpRequestPolicy.isLoopbackOrPrivateHost] so the rule has a
     * single definition shared with the `http_request` tool's own gate.
     *
     * @param host bare host name or IPv4 literal.
     * @return `true` for `localhost`, `127.0.0.0/8`, `10/8`, `172.16/12`, `192.168/16`.
     */
    fun isPrivateHost(host: String): Boolean = HttpRequestPolicy.isLoopbackOrPrivateHost(host)

    /**
     * Canonical `scheme://host[:port]` form used as the approval key, so
     * approving `http://192.168.1.42:11434/api` also covers
     * `http://192.168.1.42:11434/v1/chat` but not a different port on the same
     * machine — a different port is a different server.
     *
     * An authority containing a backslash or whitespace is treated as having no
     * host at all. Ktor — the parser that opens the Ollama and MCP connections —
     * reads a backslash as a path separator, so for
     * `https://evil.example\@192.168.1.42` it connects to `evil.example` while a
     * split on `@` here would name the private address. Refusing the ambiguous
     * form keeps the host this policy judges and the host the connection reaches
     * the same one.
     *
     * @param url the URL to reduce.
     * @return the canonical origin, or `null` when [url] has no parsable host.
     */
    fun originOf(url: String): String? {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf(SCHEME_MARKER)
        if (schemeEnd <= 0) return null
        val scheme = trimmed.take(schemeEnd).lowercase()
        val afterScheme = trimmed.substring(schemeEnd + SCHEME_MARKER.length)
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.any { it == '\\' || it.isWhitespace() }) return null
        // Strip userinfo (`user:pass@host`) before reading the host:port pair.
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.isBlank()) return null
        return "$scheme$SCHEME_MARKER${hostPort.lowercase()}"
    }

    /**
     * Extracts the bare host from [url], dropping scheme, userinfo, port and path.
     *
     * @param url the URL to read.
     * @return the lowercased host, or `null` when there is none.
     */
    fun hostOf(url: String): String? {
        val origin = originOf(url) ?: return null
        val hostPort = origin.substringAfter(SCHEME_MARKER)
        // IPv6 literals are bracketed; everything after the bracket is the port.
        val host = if (hostPort.startsWith('[')) {
            hostPort.substringAfter('[').substringBefore(']')
        } else {
            hostPort.substringBefore(':')
        }
        return host.takeIf { it.isNotBlank() }
    }

    private const val HTTP_SCHEME = "http://"
    private const val SCHEME_MARKER = "://"
}
