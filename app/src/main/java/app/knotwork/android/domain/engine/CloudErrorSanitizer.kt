package app.knotwork.android.domain.engine

/**
 * Strips credentials out of cloud-provider error text before it is shown, logged, or
 * persisted.
 *
 * Provider errors quote the request that failed, and not every provider keeps the
 * credential in a header. Google authenticates with a query parameter, so a plain
 * transport failure arrives already carrying the key:
 *
 * ```
 * Socket timeout has expired [url=https://…:streamGenerateContent?alt=sse&key=AIza…]
 * ```
 *
 * That string is the node's error, and from there it reaches the run console, the chat
 * error banner, the persisted run trace and logcat. API keys live in the Keystore-backed
 * store precisely so they never appear in any of those places, so the text is scrubbed at
 * the point where a provider exception becomes a message.
 *
 * The redaction is deliberately conservative: it matches the credential-bearing shapes
 * (secret-named query parameters and `Bearer` tokens) rather than trying to recognise any
 * particular provider's key format, which would silently miss the next provider added.
 */
object CloudErrorSanitizer {

    /** Placeholder left in place of a removed secret. */
    private const val MASK = "***"

    /** Fallback for a provider exception that carried no message at all. */
    private const val UNKNOWN = "Unknown error"

    /**
     * Query-parameter names whose values are credentials. Matched case-insensitively; the
     * value runs to the next separator (`&`, whitespace, `]`, `)`, `"`, `'`) or end of text.
     */
    private val SECRET_QUERY_PARAM = Regex(
        """\b(key|api[-_]?key|access[-_]?token|auth[-_]?token|token|password)=([^&\s\]\)"']+)""",
        RegexOption.IGNORE_CASE,
    )

    /** `Authorization: Bearer <token>` and bare `Bearer <token>` fragments. */
    private val BEARER_TOKEN = Regex("""\bBearer\s+\S+""", RegexOption.IGNORE_CASE)

    /**
     * Returns [message] with any embedded credential replaced by [MASK].
     *
     * @param message Raw provider/transport error text, possibly `null`.
     * @param causeName Simple class name of the failure's deepest cause, substituted for
     *   a missing message and for one that trails off into the word `null`.
     * @return Text safe to surface to the user and to persist, never `null`.
     */
    fun sanitize(message: String?, causeName: String? = null): String {
        val raw = message?.takeIf { it.isNotBlank() } ?: return causeName ?: UNKNOWN
        return redactSecrets(raw)
            .let(::collapseRepeatedLines)
            .let { text -> replaceDanglingNull(text, causeName) }
    }

    /**
     * Returns the user-facing text for a failed provider call: [error]'s message,
     * scrubbed, with its deepest cause's type standing in when the message says
     * nothing (see [sanitize]).
     *
     * The deepest cause is what actually failed; the wrapper above it often carries no
     * message of its own, which is how a user would otherwise end up reading `null`.
     *
     * @param error The exception a provider or transport call raised.
     * @return Text safe to show, log and persist.
     */
    fun sanitize(error: Throwable): String {
        val rootCause = generateSequence(error) { it.cause }.last()
        return sanitize(error.message, rootCause::class.simpleName)
    }

    /**
     * Masks credential-shaped fragments in [text] and changes nothing else.
     *
     * [sanitize] also rewrites the message it is given (repeated lines, a trailing
     * `null`), which is right for a provider error on its way to an error card and
     * wrong for arbitrary text. This is the variant for the places that see text of
     * every kind — the engine's console and run-error choke point, and the crash
     * reporter's log tree — where the only job is that no credential gets past.
     *
     * @param text Any text that may quote a failing request.
     * @return [text] with secret-named query-parameter values and `Bearer` tokens
     *   replaced by the mask; identical to [text] when there was nothing to mask.
     */
    fun redactSecrets(text: String): String = text
        .replace(SECRET_QUERY_PARAM) { match -> "${match.groupValues[1]}=$MASK" }
        .replace(BEARER_TOKEN, "Bearer $MASK")

    /**
     * Replaces a message that trails off into the literal word `null`.
     *
     * A transport failure whose own exception carries no message gets interpolated by
     * the client library into its wrapper text, and the user is shown the result
     * verbatim. Measured on the reference device when a provider dropped the connection
     * mid-answer: the entire error card read
     *
     * ```
     * Error from client: OllamaClient
     * Message: Exception during streaming: null
     * ```
     *
     * The run failed correctly — but "null" tells the reader nothing at all, which is
     * the same failure of the "no silent degradation" contract as a wrong message would
     * be. The exception type is substituted instead: it is short, carries no credential,
     * and at least names the kind of failure.
     *
     * Only a **trailing** `null` is treated this way. A provider's JSON error body may
     * legitimately contain `null` values (`"finish_reason": null`) and must survive.
     */
    private fun replaceDanglingNull(text: String, causeName: String?): String {
        val trimmed = text.trimEnd()
        if (trimmed != "null" && !trimmed.endsWith(": null")) return text
        val replacement = causeName ?: UNKNOWN
        return if (trimmed == "null") replacement else trimmed.removeSuffix("null") + replacement
    }

    /**
     * Drops a line that merely repeats the one before it.
     *
     * Koog wraps a client failure in `LLMClientException`, which prefixes
     * `"Error from client: <name>"` to a message that already carries the same prefix
     * from the inner exception. On the device that reaches the user as a literal
     * duplicate:
     *
     * ```
     * Error from client: GoogleLLMClient
     * Error from client: GoogleLLMClient
     * Message: Unable to resolve host "generativelanguage.googleapis.com"
     * ```
     *
     * The repetition carries no information and costs a line in an error card the user
     * is meant to read, so it is collapsed here rather than shown twice. Only *adjacent*
     * duplicates are removed — two identical lines that are genuinely apart in a longer
     * provider message are left alone.
     */
    private fun collapseRepeatedLines(text: String): String = text.lines()
        .filterIndexed { index, line -> index == 0 || line != text.lines()[index - 1] }
        .joinToString("\n")
}
