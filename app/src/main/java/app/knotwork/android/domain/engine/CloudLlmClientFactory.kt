package app.knotwork.android.domain.engine

import app.knotwork.android.domain.engine.retry.CloudRetryListener
import app.knotwork.android.domain.models.CloudProvider

/**
 * Domain-level abstraction over the cloud LLM client construction.
 *
 * The implementation lives in the data layer (`KoogClientFactory`) and bridges to whichever
 * third-party SDK is actually used (currently Koog). Domain-side consumers — most notably
 * `CloudLlmNodeExecutor` — depend only on this interface so they remain free of
 * `app.knotwork.android.data.*` imports and obey the Clean Architecture dependency rule
 * (`data → domain ← presentation`).
 *
 * The returned client is typed as [Any] because the concrete shape (`ai.koog…LLMClient`)
 * is supplied by an external library; consumers cast at the call site to invoke the
 * library API. This keeps the boundary one-directional without smuggling project-internal
 * data-layer types into domain.
 */
interface CloudLlmClientFactory {
    /**
     * Creates a streaming LLM client for the given [provider], decorated with
     * the configured transient-failure retry policy.
     *
     * @param provider The typed [CloudProvider] to construct a client for.
     * @param retryListener Sink notified before each retry, so the caller can
     *   surface retry attempts in the console. Defaults to
     *   [CloudRetryListener.NONE] for callers that do not observe retries.
     * @return The constructed client, or `null` exactly when [unavailabilityOf] reports a
     *         cause — missing credentials / base URL, the "Block network from local model"
     *         restriction, or an address the network rules refuse.
     */
    suspend fun createClient(
        provider: CloudProvider,
        retryListener: CloudRetryListener = CloudRetryListener.NONE,
    ): Any?

    /**
     * Explains why [createClient] would return `null` for [provider], using the same checks
     * in the same order, so a caller can name the real cause instead of guessing it.
     *
     * @param provider The provider a client was (or would be) requested for.
     * @return The cause, or `null` when a client can currently be constructed.
     */
    suspend fun unavailabilityOf(provider: CloudProvider): CloudClientUnavailability?
}
