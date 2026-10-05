package app.knotwork.android.data.engine

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import app.knotwork.android.data.engine.retry.CloudRetryWrapper
import app.knotwork.android.data.engine.retry.RetryAfterSlot
import app.knotwork.android.domain.engine.CloudClientUnavailability
import app.knotwork.android.domain.engine.CloudLlmClientFactory
import app.knotwork.android.domain.engine.retry.CloudRetryListener
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.repositories.ApiKeyRepository
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory for creating Koog LLM client instances (LLMClients).
 * It uses the [ApiKeyRepository] to retrieve the necessary credentials
 * and configurations (like the address of a server the user runs) at runtime.
 *
 * Implements the domain-level [CloudLlmClientFactory] interface so that every caller —
 * `CloudLlmNodeExecutor`, structured output and `delegate_task` alike — constructs cloud
 * clients through [createClient] without importing data-layer types.
 *
 * Whether a client may be built, and why not, is one decision ([admission]) that both
 * [createClient] and [unavailabilityOf] read, so a `null` client and its stated cause cannot
 * disagree. Its checks: the network gate ([ModelNetworkGate]), the credential the provider
 * cannot do without, and a model for a provider that has no default.
 *
 * Every client carries [CloudClientTimeouts.CONFIG] — Koog's defaults would let a silent
 * provider hold a node for fifteen minutes (see [CloudClientTimeouts]) — and runs on a
 * [KoogTransportFactory], which checks every hop, redirects included, and records
 * `Retry-After` for the retry policy.
 *
 * @property apiKeyRepository Source of the provider keys, models and server addresses.
 * @property modelNetworkGate Decides whether model traffic may leave the device right now.
 * @property retryWrapper Decorates each client with the transient-failure retry policy.
 */
@Singleton
class KoogClientFactory @Inject constructor(
    private val apiKeyRepository: ApiKeyRepository,
    private val modelNetworkGate: ModelNetworkGate,
    private val retryWrapper: CloudRetryWrapper,
) : CloudLlmClientFactory {

    /**
     * Shared Ktor-backed HTTP factory used by every cloud client.
     *
     * Why explicit instead of SPI auto-discovery: Koog 1.0.0 declares
     * `KoogHttpClient.Factory` as a JVM ServiceLoader SPI, but the Maven
     * Central publish of `http-client-ktor-android-1.0.0` omits the
     * `META-INF/services/ai.koog.http.client.KoogHttpClient$Factory` registration
     * file (the Factory class is present, the registration is not). On the
     * first cloud call the default code path therefore throws
     * `IllegalStateException: No KoogHttpClient.Factory provider found on the
     * runtime classpath`. Constructing the Ktor factory directly bypasses the
     * SPI lookup entirely — the same workaround the Koog error message
     * suggests ("…or pass a KoogHttpClient.Factory explicitly"). Re-collapse
     * to the no-arg secondary constructor once Koog ships an AAR with the
     * services file restored.
     */
    private val httpClientFactory = KtorKoogHttpClient.Factory()

    /**
     * Provider-keyed dispatch used by domain-side consumers. Exhaustive on
     * [CloudProvider]; adding a new provider value forces an update here.
     *
     * When `NetworkSettings.blockNetworkFromLocalModel` is `true`, every hosted provider
     * returns `null` regardless of credential state, and a server the user runs (Ollama, an
     * OpenAI-compatible server) is reachable only when its address names `localhost` or a
     * loopback / private IPv4 address — whatever the scheme, so an `https://` server on the
     * public internet is refused too. The semantics mirror the Settings → Tools & workspace →
     * "Block network from local model" toggle; [unavailabilityOf] names the cause of a `null`.
     *
     * @param provider The typed [CloudProvider] to construct a client for.
     * @param retryListener Sink notified before each retry.
     * @return The LLMClient on success, or `null` when [unavailabilityOf] reports a cause.
     */
    override suspend fun createClient(provider: CloudProvider, retryListener: CloudRetryListener): Any? {
        val granted = admission(provider) as? Admission.Granted ?: return null
        // One slot per client: its transport records the `Retry-After` of an error answer,
        // which the retry policy honours (Koog's exceptions do not carry headers).
        val retryAfter = RetryAfterSlot()
        val http = KoogTransportFactory(httpClientFactory, retryAfter, modelNetworkGate.hopRule())
        return retryWrapper.wrap(
            client = rawClient(provider, granted, http),
            provider = provider.id,
            listener = retryListener,
            retryAfter = retryAfter,
        )
    }

    /**
     * Names the cause of a `null` from [createClient] — the same [admission] it reads.
     *
     * @param provider The provider a client was requested for.
     * @return The cause, or `null` when a client can currently be constructed.
     */
    override suspend fun unavailabilityOf(provider: CloudProvider): CloudClientUnavailability? =
        (admission(provider) as? Admission.Refused)?.cause

    /**
     * Decides whether a client for [provider] may be built now, in the order a user would fix
     * it. For a server the user runs: its address, then the gate on that address, then a
     * model (a missing address is the more useful thing to say). For a hosted provider: the
     * gate, then the key, then a model.
     */
    private suspend fun admission(provider: CloudProvider): Admission =
        if (provider.usesBaseUrl) ownServerAdmission(provider) else hostedAdmission(provider)

    /** [admission] for a server the user runs: address, gate on the address, model; the key is optional. */
    private suspend fun ownServerAdmission(provider: CloudProvider): Admission {
        val url = baseUrl(provider) ?: return Admission.Refused(CloudClientUnavailability.MissingCredentials)
        val refusal = modelNetworkGate.endpointRefusal(url) ?: missingModelCause(provider)
        return refusal?.let(Admission::Refused) ?: Admission.Granted(apiKey = apiKey(provider), baseUrl = url)
    }

    /** [admission] for a hosted provider: gate, key, model. */
    private suspend fun hostedAdmission(provider: CloudProvider): Admission {
        modelNetworkGate.cloudRefusal()?.let { return Admission.Refused(it) }
        val key = apiKey(provider) ?: return Admission.Refused(CloudClientUnavailability.MissingCredentials)
        return missingModelCause(provider)?.let(Admission::Refused) ?: Admission.Granted(apiKey = key, baseUrl = null)
    }

    /** The outcome of [admission]. */
    private sealed interface Admission {
        /** A client may be built from [apiKey] (absent for a server without one) and [baseUrl]. */
        data class Granted(val apiKey: String?, val baseUrl: String?) : Admission

        /** No client; [cause] says why. */
        data class Refused(val cause: CloudClientUnavailability) : Admission
    }

    /**
     * The client a connection check asks for a model list: built exactly as a run's is, on the
     * transport the caller passes, but with no retry policy — a check is one attempt — and with
     * no admission. The caller has applied its own
     * ([app.knotwork.android.domain.connection.ConnectionPreconditions]); in particular it does
     * not require a model, because the check is how a model gets chosen.
     *
     * @param provider The provider to build for.
     * @param apiKey The key entered; required for a hosted provider, optional for a server the user runs.
     * @param baseUrl The address entered for a server the user runs.
     * @param http The transport, which applies the per-hop rule.
     * @return An undecorated client; the caller closes it.
     */
    internal fun checkClient(
        provider: CloudProvider,
        apiKey: String?,
        baseUrl: String?,
        http: KoogHttpClient.Factory,
    ): LLMClient = rawClient(provider, Admission.Granted(apiKey = apiKey, baseUrl = baseUrl), http)

    /**
     * Builds the raw, un-decorated Koog client for [provider] from what [admission] granted.
     * Retry wrapping is applied separately by [createClient].
     */
    private fun rawClient(
        provider: CloudProvider,
        granted: Admission.Granted,
        http: KoogHttpClient.Factory,
    ): LLMClient = when (provider) {
        CloudProvider.OPENAI -> OpenAILLMClient(
            apiKey = granted.requireKey(),
            settings = OpenAIClientSettings(timeoutConfig = CloudClientTimeouts.CONFIG),
            httpClientFactory = http,
        )
        CloudProvider.ANTHROPIC -> AnthropicLLMClient(
            apiKey = granted.requireKey(),
            settings = AnthropicClientSettings(timeoutConfig = CloudClientTimeouts.CONFIG),
            httpClientFactory = http,
        )
        CloudProvider.GOOGLE -> GoogleLLMClient(
            apiKey = granted.requireKey(),
            settings = GoogleClientSettings(timeoutConfig = CloudClientTimeouts.CONFIG),
            httpClientFactory = http,
        )
        CloudProvider.DEEPSEEK -> DeepSeekLLMClient(
            apiKey = granted.requireKey(),
            settings = DeepSeekClientSettings(timeoutConfig = CloudClientTimeouts.CONFIG),
            httpClientFactory = http,
        )
        // Both network rules that concern a user-configured address — the local-only
        // restriction and the cleartext rule — were applied by the gate in `admission`,
        // the same one the Ollama embedding provider asks, so chat and memory cannot drift.
        CloudProvider.OLLAMA -> OllamaClient(
            httpClientFactory = http,
            baseUrl = granted.requireBaseUrl(),
            timeoutConfig = CloudClientTimeouts.CONFIG,
        )
        CloudProvider.OPENROUTER, CloudProvider.GROQ, CloudProvider.OPENAI_COMPATIBLE ->
            OpenAiCompatibleClients.client(provider, granted.apiKey, granted.baseUrl, http)
    }

    private fun Admission.Granted.requireKey(): String = checkNotNull(apiKey) { "admission granted no key" }

    private fun Admission.Granted.requireBaseUrl(): String = checkNotNull(baseUrl) { "admission granted no address" }

    /** [CloudClientUnavailability.MissingModel] when [provider] needs a model id and none is chosen. */
    private suspend fun missingModelCause(provider: CloudProvider): CloudClientUnavailability? =
        CloudClientUnavailability.MissingModel.takeIf {
            provider.requiresModel && apiKeyRepository.getModel(provider).firstOrNull()?.trimmedOrNull() == null
        }

    /** The trimmed, non-blank key saved for [provider]; `null` for a provider that uses none. */
    private suspend fun apiKey(provider: CloudProvider): String? =
        if (provider.usesApiKey) apiKeyRepository.getApiKey(provider).firstOrNull()?.trimmedOrNull() else null

    /** The trimmed, non-blank address of a server the user runs, or `null` when none is configured. */
    private suspend fun baseUrl(provider: CloudProvider): String? =
        apiKeyRepository.getBaseUrl(provider).firstOrNull()?.trimmedOrNull()

    /** The value with surrounding whitespace removed, or `null` when nothing else is left. */
    private fun String.trimmedOrNull(): String? = trim().takeIf { it.isNotBlank() }
}
