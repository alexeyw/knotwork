package app.knotwork.android.data.engine.retry

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * Reads how long a provider asked the caller to wait before trying again.
 *
 * A provider says it in one of two places. The `Retry-After` header is the standard one
 * (RFC 9110 §10.2.3): a number of seconds or an HTTP date. Many providers also — or only — put
 * it in the error text: OpenAI-compatible servers write "try again in 2.5s", Groq writes
 * durations in Go's form ("try again in 1m2.5s"), others "retry after 3 seconds". Koog read
 * only the text, missed Groq's form, and never saw the header at all, because the exception it
 * reads carries no headers.
 *
 * Every pattern here is a literal and matched against provider text, never built from it.
 */
internal object RetryAfterHint {

    /** Phrases that put a whole number of seconds in the text: Koog's forms, kept as they were. */
    private val SECONDS_PHRASES = listOf(
        Regex("""retry\s+after\s+(\d+)\s+second""", RegexOption.IGNORE_CASE),
        Regex("""retry-after:\s*(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""wait\s+(\d+)\s+second""", RegexOption.IGNORE_CASE),
    )

    /** "try again in" followed by a duration in Go's notation: `1m2.5s`, `2.42s`, `750ms`. */
    private val TRY_AGAIN_IN = Regex("""try again in\s+(\d[0-9.hms]*[hms])""", RegexOption.IGNORE_CASE)

    /** One `<number><unit>` part of a Go duration. */
    private val DURATION_PART = Regex("""(\d+(?:\.\d+)?)(ms|h|m|s)""")

    /**
     * Reads a `Retry-After` header value.
     *
     * @param value The header as received, or `null` when the answer had none.
     * @param now The current time, against which an HTTP date is measured.
     * @return The wait — zero for a date already past — or `null` when there is no header or
     *   it is neither a number of seconds nor an HTTP date.
     */
    fun fromHeader(value: String?, now: Instant): Duration? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        text.toDoubleOrNull()?.let { seconds ->
            return if (seconds >= 0) (seconds * MILLIS_PER_SECOND).toLong().milliseconds else null
        }
        return try {
            val until = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            java.time.Duration.between(now, until).toKotlinDuration().coerceAtLeast(Duration.ZERO)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * Reads a wait named in an error's text.
     *
     * @param text An error message, which for an HTTP failure includes the provider's body.
     * @return The first wait the text names, or `null` when it names none.
     */
    fun fromText(text: String): Duration? {
        SECONDS_PHRASES.forEach { phrase ->
            phrase.find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { return it.seconds }
        }
        val token = TRY_AGAIN_IN.find(text)?.groupValues?.get(1) ?: return null
        return goDuration(token)
    }

    /**
     * Reads the first wait named anywhere in [error]'s chain of messages — a provider's body
     * can sit in a wrapped cause rather than in the outermost message.
     *
     * @param error The failure of one attempt.
     * @return The wait, or `null` when no message in the chain names one.
     */
    fun fromMessages(error: Throwable): Duration? =
        causeChain(error).firstNotNullOfOrNull { cause -> cause.message?.let(::fromText) }

    /**
     * Parses a Go duration such as `1m2.5s` or `750ms`, insisting that its parts make up the
     * whole token: a token with anything left over is not a duration this reads.
     */
    private fun goDuration(token: String): Duration? {
        val parts = DURATION_PART.findAll(token).toList()
        if (parts.isEmpty() || parts.joinToString("") { it.value } != token) return null
        val millis = parts.sumOf { part ->
            val amount = part.groupValues[1].toDouble()
            when (part.groupValues[2]) {
                "h" -> amount * MILLIS_PER_HOUR
                "m" -> amount * MILLIS_PER_MINUTE
                "s" -> amount * MILLIS_PER_SECOND
                else -> amount
            }
        }
        return millis.toLong().milliseconds
    }

    private const val MILLIS_PER_SECOND = 1_000.0
    private const val MILLIS_PER_MINUTE = 60_000.0
    private const val MILLIS_PER_HOUR = 3_600_000.0
}

/**
 * [error] and its causes, outermost first. The walk stops at the first repeat: `initCause`
 * rejects only a direct self-cause, so a longer loop is possible, and an error handler must not
 * hang on one.
 *
 * @param error The failure to walk.
 * @return The chain, without repeats.
 */
internal fun causeChain(error: Throwable): List<Throwable> {
    val chain = mutableListOf(error)
    var current = error
    while (true) {
        val next = current.cause ?: return chain
        if (chain.any { it === next }) return chain
        chain += next
        current = next
    }
}
