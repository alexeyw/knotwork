package app.knotwork.android.domain.services

/**
 * Pure policy behind the "Block network from local model" restriction: which
 * model endpoints a request may still reach while the restriction is on.
 *
 * The restriction promises that a prompt — and the memory text embedded for a
 * search — stays on the device or on the user's own network. Cloud providers are
 * refused outright, so the only endpoint this policy ever has to judge is a
 * user-configured one (today: the Ollama base URL, shared by chat and by the
 * Ollama embedding provider).
 *
 * ### Why the scheme does not matter, and the host does
 *
 * [CleartextPolicy] answers a different question — whether a connection may be
 * *unencrypted* — and therefore lets any `https://` address through without
 * looking at the host. That is correct for encryption and wrong for this
 * restriction: an Ollama server on a rented VPS behind TLS is encrypted *and*
 * off the user's network. So this policy reads the host of every URL, whatever
 * its scheme, and the two policies are applied together, not instead of each
 * other.
 *
 * ### Why only address literals count as local
 *
 * A host is local only when it is `localhost` or a loopback / RFC-1918 IPv4
 * literal ([HttpRequestPolicy.isLoopbackOrPrivateHost]). A name such as
 * `ollama.lan` is refused even if it resolves to a LAN address today: the name
 * says nothing about where DNS will send the request tomorrow, and resolving it
 * here would only move the decision to a lookup the connection does not share.
 * The same rule already decides cleartext admissibility, so a user sees one
 * definition of "my network" across the app.
 */
object LocalOnlyPolicy {

    /**
     * Whether [url] points at this device or a private-LAN address, and may
     * therefore be reached while the restriction is on.
     *
     * @param url the endpoint as the user typed or the app stored it; any scheme.
     * @return `true` when the URL has a host and that host is `localhost` or a
     *   loopback / private IPv4 literal; `false` for every other host, and for a
     *   URL with no parsable host (a refusal is the safe reading of garbage).
     */
    fun isLocalEndpoint(url: String): Boolean {
        val host = CleartextPolicy.hostOf(url) ?: return false
        return HttpRequestPolicy.isLoopbackOrPrivateHost(host)
    }
}
