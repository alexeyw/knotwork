package app.knotwork.android.data.engine

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.AbstractOpenAILLMClient
import app.knotwork.android.domain.models.CloudProvider
import kotlinx.serialization.json.Json

/**
 * Builds the Koog client of the providers reached through the OpenAI API:
 * OpenRouter, Groq and a server the user runs ([CloudProvider.OPENAI_COMPATIBLE]).
 *
 * All three are Koog's own `OpenAILLMClient` with other settings — no further Koog module, no
 * new boundary (measured against Koog 1.3.0 before this was written):
 * - **OpenRouter and Groq** answer at a fixed address under Koog's default paths
 *   (`…/api/v1/chat/completions`, `…/openai/v1/chat/completions`).
 * - **A server the user runs** is entered the way vLLM and LM Studio document it, with `/v1`
 *   at the end. Koog appends its default `v1/chat/completions` to the base, which would make
 *   `/v1/v1/…`, so the paths are given relative to the address instead.
 * - **Without a key** the client is built from its HTTP client directly: Koog's convenience
 *   constructor always sends `Authorization: Bearer <key>`, and with an empty key a server
 *   would see a bare `Bearer`.
 *
 * The model these clients are asked for is built by [KoogCloudLlmModelResolver].
 */
internal object OpenAiCompatibleClients {

    /** OpenRouter's API, under which Koog's default `v1/…` paths apply. */
    const val OPENROUTER_BASE_URL: String = "https://openrouter.ai/api"

    /** Groq's OpenAI-compatible API, under which Koog's default `v1/…` paths apply. */
    const val GROQ_BASE_URL: String = "https://api.groq.com/openai"

    /** Chat path relative to a user-entered address that already ends in `/v1`. */
    const val OWN_SERVER_CHAT_PATH: String = "chat/completions"

    /** Model-list path relative to a user-entered address that already ends in `/v1`. */
    const val OWN_SERVER_MODELS_PATH: String = "models"

    /** Client name Koog's own convenience constructor gives an OpenAI client, kept for its logs. */
    private const val CLIENT_NAME = "OpenAILLMClient"

    /**
     * Builds the client of an OpenAI-compatible [provider].
     *
     * @param provider [CloudProvider.OPENROUTER], [CloudProvider.GROQ] or
     *   [CloudProvider.OPENAI_COMPATIBLE].
     * @param apiKey The key to send as a Bearer token, or `null` for a server that runs without
     *   one — then no `Authorization` header is sent at all.
     * @param baseUrl The user-entered address of [CloudProvider.OPENAI_COMPATIBLE]; ignored for
     *   a provider at a fixed address.
     * @param http The transport the client runs on.
     * @return The raw, un-retried client.
     */
    fun client(provider: CloudProvider, apiKey: String?, baseUrl: String?, http: KoogHttpClient.Factory): LLMClient =
        if (apiKey != null) {
            OpenAILLMClient(
                apiKey = apiKey,
                settings = settings(provider, baseUrl, CloudClientTimeouts.CONFIG),
                httpClientFactory = http,
            )
        } else {
            OpenAILLMClient(
                settings = settings(provider, baseUrl, CloudClientTimeouts.CONFIG),
                httpClient = unauthenticated(settings(provider, baseUrl, CloudClientTimeouts.CONFIG), http),
            )
        }

    /**
     * Koog settings for [provider].
     *
     * @param timeouts The deadlines; always [CloudClientTimeouts.CONFIG], passed at the call
     *   site so `KoogClientTimeoutKonsistTest` sees it at every construction.
     */
    internal fun settings(
        provider: CloudProvider,
        baseUrl: String?,
        timeouts: ConnectionTimeoutConfig,
    ): OpenAIClientSettings = when (provider) {
        CloudProvider.OPENROUTER -> OpenAIClientSettings(baseUrl = OPENROUTER_BASE_URL, timeoutConfig = timeouts)
        CloudProvider.GROQ -> OpenAIClientSettings(baseUrl = GROQ_BASE_URL, timeoutConfig = timeouts)
        CloudProvider.OPENAI_COMPATIBLE -> OpenAIClientSettings(
            baseUrl = requireNotNull(baseUrl) { "An OpenAI-compatible server needs its address" },
            timeoutConfig = timeouts,
            chatCompletionsPath = OWN_SERVER_CHAT_PATH,
            modelsPath = OWN_SERVER_MODELS_PATH,
        )
        else -> throw IllegalArgumentException("${provider.id} is not reached through the OpenAI API")
    }

    /**
     * The HTTP client Koog's own convenience constructor would build — its JSON configuration
     * and the deadlines of [settings] included — minus the `Authorization` header.
     *
     * Built through Koog's `createConfiguredHttpClient` rather than restated, because the client
     * decodes the model list with that private JSON configuration (unknown keys ignored,
     * snake_case names); a default one would fail on the first field it does not know.
     */
    private fun unauthenticated(settings: OpenAIClientSettings, http: KoogHttpClient.Factory): KoogHttpClient =
        AbstractOpenAILLMClient.createConfiguredHttpClient(
            apiKey = "",
            settings = settings,
            httpClientFactory = WithoutAuthorization(http),
            clientName = CLIENT_NAME,
        )

    /**
     * Passes every request setting to [delegate] except the `Authorization` header, which
     * Koog adds for any key — an empty one included.
     *
     * @property delegate The transport the client runs on.
     */
    private class WithoutAuthorization(private val delegate: KoogHttpClient.Factory) : KoogHttpClient.Factory {
        override fun create(
            clientName: String,
            baseUrl: String,
            headers: Map<String, String>,
            queryParameters: Map<String, String>,
            requestTimeoutMillis: Long,
            connectTimeoutMillis: Long,
            socketTimeoutMillis: Long,
            json: Json,
        ): KoogHttpClient = delegate.create(
            clientName = clientName,
            baseUrl = baseUrl,
            headers = headers.filterKeys { !it.equals("Authorization", ignoreCase = true) },
            queryParameters = queryParameters,
            requestTimeoutMillis = requestTimeoutMillis,
            connectTimeoutMillis = connectTimeoutMillis,
            socketTimeoutMillis = socketTimeoutMillis,
            json = json,
        )
    }
}
