package app.knotwork.android.data.engine.retry

import ai.koog.prompt.executor.clients.LLMClient
import app.knotwork.android.domain.engine.retry.CloudRetryListener
import app.knotwork.android.domain.repositories.NetworkSettings
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Wraps a raw Koog [LLMClient] with the project's settings-driven retry policy.
 *
 * Centralises the single legitimate "retry" boundary: every cloud client (chat completions,
 * embeddings) is decorated here, so a transient failure — a 429 or 5xx, a dropped connection —
 * is retried with exponential backoff and jitter, or after the wait the provider asked for,
 * while authentication errors, invalid requests and [kotlinx.coroutines.CancellationException]
 * are never retried. The decision is [CloudRetryPolicy]'s; the loop is
 * [RetryingCloudLlmClient]'s, which also reports each retry to a [CloudRetryListener] so the
 * cloud node executor can surface a
 * [app.knotwork.android.domain.models.ConsoleEventType.CloudRetry] console line.
 *
 * Both replaced Koog's `RetryingLLMClient`, which read the status out of the message text,
 * never saw a `Retry-After` header and slept through any wait a provider named.
 *
 * @property networkSettings Source of the configured attempt budget and base
 *   delay (Settings → Providers).
 */
@Singleton
class CloudRetryWrapper @Inject constructor(private val networkSettings: NetworkSettings) {

    /**
     * Decorates [client] with the configured retry policy.
     *
     * When the attempt budget is `1` (retries disabled) the raw client is
     * returned unchanged — there is nothing to retry and nothing to observe.
     *
     * @param client The raw cloud client to wrap.
     * @param provider Display id of the provider (e.g. `"openai"`), used for the
     *   retry console line and the error of a wait that is too long.
     * @param listener Sink notified before each retry; defaults to
     *   [CloudRetryListener.NONE] for off-graph callers (embeddings, the
     *   delegate-task tool) that do not surface console lines.
     * @param retryAfter Where [client]'s transport records the `Retry-After` header of an
     *   error answer ([RetryAfterCapturingHttpClientFactory]); `null` for a client built
     *   without capture, which still honours a wait named in the error text.
     * @return The retry-wrapped client, or [client] itself when retries are off.
     */
    suspend fun wrap(
        client: LLMClient,
        provider: String,
        listener: CloudRetryListener = CloudRetryListener.NONE,
        retryAfter: RetryAfterSlot? = null,
    ): LLMClient {
        val maxAttempts = networkSettings.cloudRetryMaxAttempts.first()
        if (maxAttempts <= 1) return client

        val baseDelay = networkSettings.cloudRetryBaseDelayMs.first().milliseconds
        return RetryingCloudLlmClient(
            delegate = client,
            provider = provider,
            policy = CloudRetryPolicy(
                maxAttempts = maxAttempts,
                initialDelay = baseDelay,
                // The base delay is bounded to 10 s by settings, so a fixed 30 s ceiling always
                // stays above it while leaving headroom for the exponential growth. It is also
                // the longest wait a provider may ask for before the call fails instead.
                maxDelay = maxOf(baseDelay, MAX_BACKOFF),
            ),
            listener = listener,
            retryAfter = retryAfter,
        )
    }

    private companion object {
        /** Upper bound on a single backoff delay, and on a provider's requested wait. */
        val MAX_BACKOFF = 30.seconds
    }
}
