package app.knotwork.android.data.engine.retry

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.retry.RetryConfig
import ai.koog.prompt.executor.clients.retry.RetryingLLMClient
import app.knotwork.android.domain.engine.retry.CloudRetryListener
import app.knotwork.android.domain.repositories.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Wraps a raw Koog [LLMClient] with the project's settings-driven retry policy.
 *
 * Centralises the single legitimate Koog "retry" boundary: every cloud client
 * (chat completions, embeddings) is decorated here so transient failures
 * (HTTP 429 / 5xx / connection or read timeouts) are retried with exponential
 * backoff and jitter, while authentication errors and
 * [kotlinx.coroutines.CancellationException] are never retried — both contracts
 * come straight from Koog's [RetryingLLMClient] (the project's
 * cancellation contract is preserved because the decorator wraps a suspend client).
 *
 * Retries are made observable by interposing a [RetryObservingLLMClient]
 * between the policy and the real client: it counts re-invocations and reports
 * each one to the supplied [CloudRetryListener] so the cloud node executor can
 * surface a [app.knotwork.android.domain.models.ConsoleEventType.CloudRetry]
 * console line.
 *
 * @property settingsRepository Source of the configured attempt budget and base
 *   delay (Settings → Providers).
 */
@Singleton
class CloudRetryWrapper @Inject constructor(private val settingsRepository: SettingsRepository) {

    /**
     * Decorates [client] with the configured retry policy.
     *
     * When the attempt budget is `1` (retries disabled) the raw client is
     * returned unchanged — there is nothing to retry and nothing to observe.
     *
     * @param client The raw cloud client to wrap.
     * @param provider Display id of the provider (e.g. `"openai"`), used only
     *   for the retry console line.
     * @param listener Sink notified before each retry; defaults to
     *   [CloudRetryListener.NONE] for off-graph callers (embeddings, the
     *   delegate-task tool) that do not surface console lines.
     * @return The retry-wrapped client, or [client] itself when retries are off.
     */
    suspend fun wrap(
        client: LLMClient,
        provider: String,
        listener: CloudRetryListener = CloudRetryListener.NONE,
    ): LLMClient {
        val maxAttempts = settingsRepository.cloudRetryMaxAttempts.first()
        if (maxAttempts <= 1) return client

        val baseDelay = settingsRepository.cloudRetryBaseDelayMs.first().milliseconds
        val observed = RetryObservingLLMClient(
            delegate = client,
            provider = provider,
            maxAttempts = maxAttempts,
            listener = listener,
        )
        return RetryingLLMClient(
            delegate = observed,
            config = RetryConfig(
                maxAttempts = maxAttempts,
                initialDelay = baseDelay,
                // The base delay is bounded to 10s by settings, so a fixed 30s
                // ceiling always satisfies RetryConfig's initialDelay <= maxDelay
                // invariant while leaving headroom for the exponential growth.
                maxDelay = maxOf(baseDelay, MAX_BACKOFF),
                backoffMultiplier = BACKOFF_MULTIPLIER,
                jitterFactor = JITTER_FACTOR,
            ),
        )
    }

    private companion object {
        /** Upper bound on a single backoff delay (matches Koog's PRODUCTION shape). */
        val MAX_BACKOFF = 30.seconds

        /** Exponential backoff growth factor between retries. */
        const val BACKOFF_MULTIPLIER = 2.0

        /** Random jitter fraction added on top of each backoff delay. */
        const val JITTER_FACTOR = 0.2
    }
}
