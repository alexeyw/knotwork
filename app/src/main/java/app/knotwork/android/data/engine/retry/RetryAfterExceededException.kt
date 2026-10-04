package app.knotwork.android.data.engine.retry

import kotlin.math.ceil
import kotlin.time.Duration

/**
 * A cloud call ended because the provider asked to wait longer than the app waits between
 * attempts.
 *
 * Waiting it out would hold the step for as long as the provider names — "retry after 86400
 * seconds" would have held it for a day — and retrying sooner only earns another refusal. So
 * the call fails at once, and its message is the sentence the user reads in the console, the
 * chat error and the run trace: which provider, how long it asked for, and what to do.
 *
 * @param provider Wire id of the provider (e.g. `"groq"`), quoted the way the other cloud
 *   errors quote it.
 * @param requested The wait the provider asked for.
 * @param ceiling The longest wait the app takes between attempts.
 * @param rateLimited Whether the answer was a 429, which the message then calls rate limiting.
 * @param cause The provider's own error, kept for logs and for the status it carries.
 */
class RetryAfterExceededException(
    provider: String,
    requested: Duration,
    ceiling: Duration,
    rateLimited: Boolean,
    cause: Throwable,
) : Exception(message(provider, requested, ceiling, rateLimited), cause) {

    private companion object {
        fun message(provider: String, requested: Duration, ceiling: Duration, rateLimited: Boolean): String {
            val asked = "asked to wait ${wholeSeconds(requested)} s, longer than the ${wholeSeconds(ceiling)} s " +
                "the app waits. The step failed: run it again after that."
            return if (rateLimited) {
                "'$provider' is rate-limiting requests and $asked"
            } else {
                "'$provider' $asked"
            }
        }

        /** Seconds rounded up, so a wait of 62.5 s is never reported as shorter than it is. */
        fun wholeSeconds(duration: Duration): Long = ceil(duration.inWholeMilliseconds / MILLIS_PER_SECOND).toLong()

        const val MILLIS_PER_SECOND = 1_000.0
    }
}
