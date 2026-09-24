package app.knotwork.android.data.engine

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
 * and configurations (like Custom Base URL for Ollama) at runtime.
 *
 * Implements the domain-level [CloudLlmClientFactory] interface so that
 * `CloudLlmNodeExecutor` can construct cloud clients without importing data-layer types,
 * while internal callers (e.g. `DelegateTaskTool`) retain the typed per-provider helpers.
 *
 * Every client is built only after [ModelNetworkGate] admits it, and [unavailabilityOf]
 * reports the refusal the gate (or a missing credential) produced — the two share one
 * decision per provider, so a `null` client and its stated cause cannot disagree.
 *
 * Every client carries [CloudClientTimeouts.CONFIG] — Koog's defaults would let a silent
 * provider hold a node for fifteen minutes (see [CloudClientTimeouts]).
 *
 * @property apiKeyRepository Source of the provider keys and the Ollama base URL.
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
     * When `SettingsRepository.blockNetworkFromLocalModel` is `true`, every
     * hosted cloud provider (OpenAI / Anthropic / Google / DeepSeek) returns `null`
     * regardless of credential state, and the Ollama client is constructible only
     * when its base URL names `localhost` or a loopback / private IPv4 address —
     * whatever the scheme, so an `https://` server on the public internet is refused
     * too. The semantics mirror the Settings → Tools & workspace → "Block network
     * from local model" toggle; [unavailabilityOf] names the cause of a `null`.
     *
     * @param provider The typed [CloudProvider] to construct a client for.
     * @param retryListener Sink notified before each retry.
     * @return The LLMClient on success, or `null` when [unavailabilityOf] reports a cause.
     */
    override suspend fun createClient(provider: CloudProvider, retryListener: CloudRetryListener): Any? =
        rawClient(provider)?.let { raw ->
            retryWrapper.wrap(client = raw, provider = provider.id, listener = retryListener)
        }

    /**
     * Names the cause of a `null` from [createClient], checking in the order the `raw*`
     * builders do: for a hosted provider the gate first, then the key; for Ollama the
     * base URL first, then the gate (a missing address is the more useful thing to say).
     *
     * @param provider The provider a client was requested for.
     * @return The cause, or `null` when a client can currently be constructed.
     */
    override suspend fun unavailabilityOf(provider: CloudProvider): CloudClientUnavailability? = when (provider) {
        CloudProvider.OLLAMA -> {
            val url = ollamaBaseUrl()
            if (url == null) CloudClientUnavailability.MissingCredentials else modelNetworkGate.ollamaRefusal(url)
        }
        else -> modelNetworkGate.cloudRefusal()
            ?: CloudClientUnavailability.MissingCredentials.takeIf { apiKey(provider) == null }
    }

    /**
     * Builds the raw, un-decorated Koog client for [provider], applying the
     * network gate and the per-provider credential checks. Retry wrapping is
     * applied separately by [createClient] / the public helpers.
     *
     * @return The raw client, or `null` when [unavailabilityOf] reports a cause.
     */
    private suspend fun rawClient(provider: CloudProvider): LLMClient? = when (provider) {
        CloudProvider.OPENAI -> rawOpenAI()
        CloudProvider.ANTHROPIC -> rawAnthropic()
        CloudProvider.GOOGLE -> rawGoogle()
        CloudProvider.DEEPSEEK -> rawDeepSeek()
        CloudProvider.OLLAMA -> rawOllama()
    }

    /**
     * Creates a retry-wrapped OpenAI LLMClient.
     * @return The client, or null if the API key is not configured or
     *   local-only mode is on.
     */
    suspend fun createOpenAIExecutor(): LLMClient? = rawOpenAI()?.let { wrapNoObserve(it, CloudProvider.OPENAI) }

    /**
     * Creates a retry-wrapped Anthropic LLMClient.
     * @return The client, or null if the API key is not configured or
     *   local-only mode is on.
     */
    suspend fun createAnthropicExecutor(): LLMClient? =
        rawAnthropic()?.let { wrapNoObserve(it, CloudProvider.ANTHROPIC) }

    /**
     * Creates a retry-wrapped Google (Gemini) LLMClient.
     * @return The client, or null if the API key is not configured or
     *   local-only mode is on.
     */
    suspend fun createGoogleExecutor(): LLMClient? = rawGoogle()?.let { wrapNoObserve(it, CloudProvider.GOOGLE) }

    /**
     * Creates a retry-wrapped DeepSeek LLMClient.
     * @return The client, or null if the API key is not configured or
     *   local-only mode is on.
     */
    suspend fun createDeepSeekExecutor(): LLMClient? = rawDeepSeek()?.let { wrapNoObserve(it, CloudProvider.DEEPSEEK) }

    /**
     * Creates a retry-wrapped Ollama LLMClient connected to the configured server.
     * @return The client, or null if the base URL is not configured or
     *   [ModelNetworkGate.ollamaRefusal] refuses it.
     */
    suspend fun createOllamaExecutor(): LLMClient? = rawOllama()?.let { wrapNoObserve(it, CloudProvider.OLLAMA) }

    /** Wraps a raw client with the retry policy but no retry observation (off-graph callers). */
    private suspend fun wrapNoObserve(client: LLMClient, provider: CloudProvider): LLMClient =
        retryWrapper.wrap(client = client, provider = provider.id)

    private suspend fun rawOpenAI(): LLMClient? {
        val key = admittedApiKey(CloudProvider.OPENAI) ?: return null
        return OpenAILLMClient(
            apiKey = key,
            settings = OpenAIClientSettings(timeoutConfig = CloudClientTimeouts.CONFIG),
            httpClientFactory = httpClientFactory,
        )
    }

    private suspend fun rawAnthropic(): LLMClient? {
        val key = admittedApiKey(CloudProvider.ANTHROPIC) ?: return null
        return AnthropicLLMClient(
            apiKey = key,
            settings = AnthropicClientSettings(timeoutConfig = CloudClientTimeouts.CONFIG),
            httpClientFactory = httpClientFactory,
        )
    }

    private suspend fun rawGoogle(): LLMClient? {
        val key = admittedApiKey(CloudProvider.GOOGLE) ?: return null
        return GoogleLLMClient(
            apiKey = key,
            settings = GoogleClientSettings(timeoutConfig = CloudClientTimeouts.CONFIG),
            httpClientFactory = httpClientFactory,
        )
    }

    private suspend fun rawDeepSeek(): LLMClient? {
        val key = admittedApiKey(CloudProvider.DEEPSEEK) ?: return null
        return DeepSeekLLMClient(
            apiKey = key,
            settings = DeepSeekClientSettings(timeoutConfig = CloudClientTimeouts.CONFIG),
            httpClientFactory = httpClientFactory,
        )
    }

    private suspend fun rawOllama(): LLMClient? {
        val url = ollamaBaseUrl() ?: return null
        // Both network rules that concern a user-configured address — the local-only
        // restriction and the cleartext rule — are applied by the gate, the same one the
        // Ollama embedding provider asks, so chat and memory cannot drift apart again.
        if (modelNetworkGate.ollamaRefusal(url) != null) return null
        return OllamaClient(
            httpClientFactory = httpClientFactory,
            baseUrl = url,
            timeoutConfig = CloudClientTimeouts.CONFIG,
        )
    }

    /** The saved key for a hosted [provider], or `null` when the gate refuses it or none is saved. */
    private suspend fun admittedApiKey(provider: CloudProvider): String? =
        if (modelNetworkGate.cloudRefusal() != null) null else apiKey(provider)

    /** The trimmed, non-blank key saved for a hosted [provider]; `null` for Ollama, which has none. */
    private suspend fun apiKey(provider: CloudProvider): String? = when (provider) {
        CloudProvider.OPENAI -> apiKeyRepository.getOpenAIKey()
        CloudProvider.ANTHROPIC -> apiKeyRepository.getAnthropicKey()
        CloudProvider.GOOGLE -> apiKeyRepository.getGoogleKey()
        CloudProvider.DEEPSEEK -> apiKeyRepository.getDeepSeekKey()
        CloudProvider.OLLAMA -> null
    }?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }

    /** The trimmed, non-blank Ollama base URL, or `null` when none is configured. */
    private suspend fun ollamaBaseUrl(): String? =
        apiKeyRepository.getOllamaBaseUrl().firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
}
