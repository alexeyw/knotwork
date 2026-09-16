package app.knotwork.android.data.services.embedding

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.LLMEmbeddingProviderAPI
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import app.knotwork.android.data.engine.retry.CloudRetryWrapper
import app.knotwork.android.domain.models.CloudProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory that constructs Koog embedding clients for the cloud / local-server
 * embedding providers.
 *
 * Pulling client construction behind an interface keeps the
 * [EmbeddingProvider][app.knotwork.android.domain.services.EmbeddingProvider]
 * implementations unit-testable: tests substitute a fake factory that returns
 * a mocked [LLMEmbeddingProviderAPI], so no real HTTP client is created and no
 * network is touched. It mirrors the existing
 * [KoogClientFactory][app.knotwork.android.data.engine.KoogClientFactory] seam
 * used for chat clients.
 *
 * Every Koog `LLMClient` implements `LLMEmbeddingProviderAPI`, so the returned
 * clients expose `embed(text, model)` / `embed(texts, model)` directly.
 */
interface KoogEmbedderFactory {

    /**
     * Builds an OpenAI embedding client authenticated with [apiKey], decorated
     * with the configured transient-failure retry policy.
     *
     * Applies no network rule of its own: callers must have asked
     * `ModelNetworkGate.cloudRefusal` first, as `CloudEmbeddingProvider` does.
     *
     * `suspend` because the retry policy is read from settings at build time.
     *
     * @param apiKey A non-blank OpenAI API key (callers must check for blank
     *   first and take the fallback path).
     * @return A Koog [LLMEmbeddingProviderAPI] backed by the OpenAI client.
     */
    suspend fun openAiClient(apiKey: String): LLMEmbeddingProviderAPI

    /**
     * Builds an Ollama embedding client targeting the local-network server at
     * [baseUrl], decorated with the configured transient-failure retry policy.
     *
     * Applies no network rule of its own: callers must have passed [baseUrl]
     * through `ModelNetworkGate.ollamaRefusal` first, as `OllamaEmbeddingProvider`
     * does — a client built from an unchecked address bypasses both the cleartext
     * rule and the "Block network from local model" restriction.
     *
     * `suspend` because the retry policy is read from settings at build time.
     *
     * @param baseUrl A non-blank Ollama base URL (e.g. `http://192.168.1.2:11434`).
     * @return A Koog [LLMEmbeddingProviderAPI] backed by the Ollama client.
     */
    suspend fun ollamaClient(baseUrl: String): LLMEmbeddingProviderAPI
}

/**
 * Default [KoogEmbedderFactory] backed by the real Koog Ktor HTTP transport.
 *
 * Reuses a single explicitly-constructed [KtorKoogHttpClient.Factory] for the
 * same reason as `KoogClientFactory`: the Maven Central publish of Koog 1.0.0
 * omits the `KoogHttpClient.Factory` service-loader registration, so the SPI
 * auto-discovery path throws at runtime — passing the factory explicitly
 * bypasses the lookup.
 */
@Singleton
class DefaultKoogEmbedderFactory @Inject constructor(private val retryWrapper: CloudRetryWrapper) :
    KoogEmbedderFactory {

    private val httpClientFactory = KtorKoogHttpClient.Factory()

    override suspend fun openAiClient(apiKey: String): LLMEmbeddingProviderAPI = retryWrapper.wrap(
        client = OpenAILLMClient(apiKey = apiKey, httpClientFactory = httpClientFactory),
        provider = CloudProvider.OPENAI.id,
    )

    override suspend fun ollamaClient(baseUrl: String): LLMEmbeddingProviderAPI = retryWrapper.wrap(
        client = OllamaClient(httpClientFactory = httpClientFactory, baseUrl = baseUrl),
        provider = CloudProvider.OLLAMA.id,
    )
}

/**
 * Converts a Koog embedding vector ([List]<[Double]>) into the [FloatArray]
 * representation used throughout the memory subsystem (and by MediaPipe).
 *
 * The narrowing from `Double` to `Float` is intentional: on-device storage and
 * cosine-similarity math operate on `Float`, and embedding magnitudes are well
 * within `Float` range, so the precision loss is irrelevant for ranking.
 */
internal fun List<Double>.toFloatVector(): FloatArray = FloatArray(size) { this[it].toFloat() }
