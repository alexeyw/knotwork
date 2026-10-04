package app.knotwork.android.data.engine.retry

import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.executor.clients.retry.RetryConfig
import ai.koog.prompt.streaming.IncompleteStreamException
import java.time.Clock
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Decides whether a failed cloud call is tried again, and after how long.
 *
 * It replaces the decision Koog's `RetryingLLMClient` made, which misread providers in three
 * ways (measured against a local server, `CloudRetryEndToEndTest`):
 * - **The status was searched for in the text.** Koog matched `\b500\b` and friends against
 *   the whole message, body included, so a 400 whose body said "max_tokens must be <= 500" was
 *   retried. A failure that carries an HTTP status is now judged by that status and nothing
 *   else.
 * - **A wait named by the provider had no ceiling.** "retry after 86400 seconds" would have
 *   put the call to sleep for a day. A wait longer than [maxDelay] is now not waited out: the
 *   call fails and says how long the provider asked for ([RetryDecision.WaitTooLong]).
 * - **The `Retry-After` header was never read**, because Koog's exception carries no headers.
 *   The caller now hands the header in ([decide]'s `retryAfterHeader`), captured on the
 *   transport by [app.knotwork.android.data.engine.KoogTransportFactory].
 *
 * A failure with no HTTP status — a dropped connection, a timeout — keeps Koog's rules exactly
 * as they were: [IncompleteStreamException], or a message matching [RetryConfig.DEFAULT_PATTERNS].
 * That is deliberate: nothing measured says those rules are wrong, and one of them is relied on
 * (a socket timeout is *not* retried, so a silent provider costs one deadline, not three).
 *
 * @property maxAttempts Calls in total, the first included; at least 1.
 * @property initialDelay Backoff before the first retry when the provider names no wait.
 * @property maxDelay Ceiling of the backoff, and the longest wait a provider may ask for.
 * @property backoffMultiplier Growth of the backoff between retries.
 * @property jitterFactor Random fraction added on top of a backoff (never on a provider's wait).
 * @property random Source of the jitter.
 * @property clock Time against which an HTTP-date `Retry-After` is measured.
 */
internal class CloudRetryPolicy(
    val maxAttempts: Int,
    private val initialDelay: Duration,
    val maxDelay: Duration,
    private val backoffMultiplier: Double = DEFAULT_BACKOFF_MULTIPLIER,
    private val jitterFactor: Double = DEFAULT_JITTER_FACTOR,
    private val random: Random = Random.Default,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * Decides what to do after attempt number [attempt] failed with [error].
     *
     * @param error The failure of that attempt.
     * @param attempt Zero-based index of the attempt that failed.
     * @param retryAfterHeader The `Retry-After` header of the error answer, when one arrived.
     * @return Retry after a delay, give up with the original error, or give up because the
     *   provider asked for a longer wait than the app takes.
     */
    fun decide(error: Throwable, attempt: Int, retryAfterHeader: String?): RetryDecision {
        if (attempt >= maxAttempts - 1 || !isRetryable(error)) return RetryDecision.GiveUp
        val asked = RetryAfterHint.fromHeader(retryAfterHeader, clock.instant()) ?: RetryAfterHint.fromMessages(error)
        return when {
            asked == null -> RetryDecision.Retry(backoff(attempt))
            asked > maxDelay -> RetryDecision.WaitTooLong(asked)
            else -> RetryDecision.Retry(asked)
        }
    }

    /** Exponential backoff for retry number `attempt + 1`, capped at [maxDelay], plus jitter. */
    private fun backoff(attempt: Int): Duration {
        val exponentialMs = initialDelay.inWholeMilliseconds * backoffMultiplier.pow(attempt)
        val boundedMs = minOf(exponentialMs, maxDelay.inWholeMilliseconds.toDouble())
        val jitterMs = if (jitterFactor > 0.0 &&
            boundedMs > 0.0
        ) {
            random.nextDouble(0.0, boundedMs * jitterFactor)
        } else {
            0.0
        }
        return (boundedMs + jitterMs).toLong().milliseconds
    }

    /** What a policy concluded about one failed attempt. */
    sealed interface RetryDecision {

        /**
         * Try again after [delay].
         *
         * @property delay The wait before the next attempt.
         */
        data class Retry(val delay: Duration) : RetryDecision

        /** Do not try again; the original error stands. */
        data object GiveUp : RetryDecision

        /**
         * The provider asked for a wait longer than the app takes; the call fails saying so.
         *
         * @property requested The wait the provider asked for.
         */
        data class WaitTooLong(val requested: Duration) : RetryDecision
    }

    /** Defaults of the backoff shape, shared with the settings-driven wrapper. */
    companion object {
        /** HTTP statuses that mean "the same request may succeed later". 529: Anthropic overloaded. */
        val RETRYABLE_STATUSES: Set<Int> = setOf(429, 500, 502, 503, 504, 529)

        /** Backoff growth between retries. */
        const val DEFAULT_BACKOFF_MULTIPLIER: Double = 2.0

        /** Random fraction added on top of a backoff. */
        const val DEFAULT_JITTER_FACTOR: Double = 0.2

        /**
         * The HTTP status [error] carries, from the first [KoogHttpClientException] in its chain —
         * every Koog provider client reports an HTTP failure through one, wrapped or not.
         *
         * @param error A failed call.
         * @return The status, or `null` when the failure never got an HTTP answer.
         */
        fun statusOf(error: Throwable): Int? =
            causeChain(error).firstNotNullOfOrNull { (it as? KoogHttpClientException)?.statusCode }

        /**
         * Whether [error] may succeed if the same request is sent again.
         *
         * @param error A failed call.
         * @return By the HTTP status when there is one; otherwise Koog's rules, unchanged.
         */
        fun isRetryable(error: Throwable): Boolean {
            val status = statusOf(error)
            if (status != null) return status in RETRYABLE_STATUSES
            if (error is IncompleteStreamException) return true
            val message = error.message ?: return false
            return RetryConfig.DEFAULT_PATTERNS.any { it.matches(message) }
        }
    }
}
