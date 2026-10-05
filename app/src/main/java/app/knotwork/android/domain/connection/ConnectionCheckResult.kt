package app.knotwork.android.domain.connection

import kotlin.time.Duration

/**
 * What one connection check found: the server answered with its list, a rule kept the request
 * from being sent, or the request was sent and failed.
 *
 * A check is one attempt with no retries, and it never sends a prompt: a provider is asked for
 * its model list, an MCP server for its tools.
 */
sealed interface ConnectionCheckResult {

    /**
     * The server answered with its list.
     *
     * @property items The model ids a provider serves, or the names of the tools an MCP server
     *   publishes, in the server's order. Empty when the server is up and serves none yet.
     */
    data class Reachable(val items: List<String>) : ConnectionCheckResult

    /**
     * Nothing was sent.
     *
     * @property refusal Why not.
     */
    data class Refused(val refusal: ConnectionRefusal) : ConnectionCheckResult

    /**
     * A request was sent and the check failed.
     *
     * @property host The host the check was sent to, for the message that names it.
     * @property failure What happened.
     */
    data class Failed(val host: String, val failure: ConnectionFailure) : ConnectionCheckResult
}

/**
 * How a connection check that sent a request failed. One value per thing a user can do about it,
 * so the screen can name the cause and one action.
 */
sealed interface ConnectionFailure {

    /**
     * The server rejected the credentials: 401 or 403 — or 400 from Google, which answers a key
     * it does not know that way.
     *
     * @property status The HTTP status.
     * @property keySent Whether the check sent a key at all; a server that wants one when none
     *   was entered needs a different action from one that refuses the key it got.
     */
    data class Unauthorized(val status: Int, val keySent: Boolean) : ConnectionFailure

    /**
     * Nothing at the address the check asked (404) — most often a server address without `/v1`.
     *
     * @property url The full address that was requested.
     */
    data class NotFound(val url: String) : ConnectionFailure

    /**
     * The server is rate-limiting (429).
     *
     * @property wait How long it asked to wait, from `Retry-After` or its answer; `null` when it
     *   did not say.
     */
    data class RateLimited(val wait: Duration?) : ConnectionFailure

    /**
     * The server answered with an error of its own (5xx).
     *
     * @property status The HTTP status.
     */
    data class ServerError(val status: Int) : ConnectionFailure

    /**
     * The server answered with a status no other value covers.
     *
     * @property status The HTTP status.
     * @property excerpt The start of what it said, credentials removed; `null` when it said nothing.
     */
    data class UnexpectedAnswer(val status: Int, val excerpt: String?) : ConnectionFailure

    /** The server answered successfully, but not with a model list. */
    data object NoModelList : ConnectionFailure

    /** The address answered, but not as an MCP server: a wrong path, or not MCP at all. */
    data object NotMcp : ConnectionFailure

    /** The host name did not resolve — a typo in the address, or a phone that is offline. */
    data object UnknownHost : ConnectionFailure

    /** The host is there, and nothing accepted the connection on that port. */
    data object ConnectionRefused : ConnectionFailure

    /**
     * No connection within the connect deadline.
     *
     * @property after The deadline that ran out.
     */
    data class ConnectTimeout(val after: Duration) : ConnectionFailure

    /**
     * Connected, then nothing arrived for the read deadline.
     *
     * @property after The deadline that ran out.
     */
    data class Silence(val after: Duration) : ConnectionFailure

    /**
     * An MCP server accepted the connection and did not finish the handshake in time.
     *
     * @property after The handshake deadline.
     */
    data class HandshakeTimeout(val after: Duration) : ConnectionFailure

    /**
     * The server redirected the request to an address the rules refuse; the redirect was not
     * followed.
     *
     * @property reason Which address, and why, as the rule worded it.
     */
    data class RedirectRefused(val reason: String) : ConnectionFailure

    /**
     * Anything else — a certificate the phone does not trust, for one.
     *
     * @property detail What went wrong, credentials removed.
     */
    data class Other(val detail: String) : ConnectionFailure
}
