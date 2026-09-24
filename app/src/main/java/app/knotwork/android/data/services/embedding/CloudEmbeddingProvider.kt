package app.knotwork.android.data.services.embedding

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import app.knotwork.android.data.engine.ModelNetworkGate
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.NetworkActivityTracker
import app.knotwork.android.domain.services.EmbeddingException
import app.knotwork.android.domain.services.EmbeddingProvider
import kotlinx.coroutines.flow.firstOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Cloud [EmbeddingProvider] backed by OpenAI `text-embedding-3-small` (1536-d),
 * called through Koog's `OpenAILLMClient`.
 *
 * Reusing the Koog client (the same transport `KoogClientFactory` uses for chat
 * completions) means no bespoke HTTP code; the deadlines are the chat clients' own
 * ([app.knotwork.android.data.engine.CloudClientTimeouts]), applied by the factory.
 *
 * This provider never silently falls back to another backend: doing so would
 * break its [dimension] contract (a caller sizing buffers on `dimension = 1536`
 * must not receive a 512-d on-device vector). When no key is configured
 * [isAvailable] reports `false` so `EmbeddingProviderResolver` substitutes the
 * on-device default *before* this provider is ever returned; if [embed] is
 * nonetheless called without a key it fails loudly with an [EmbeddingException].
 *
 * While "Block network from local model" is on, [ModelNetworkGate] refuses it
 * like every other cloud provider: [isAvailable] reports `false` (so memory keeps
 * working on the on-device model) and [embed] refuses before building a client.
 * The text embedded here is the user's memory and search queries — before this
 * gate, the restriction stopped a Cloud node but not this.
 *
 * @property embedderFactory Builds the underlying Koog embedding client.
 * @property apiKeyRepository Source of the OpenAI API key.
 * @property modelNetworkGate Decides whether a cloud provider may be reached right now.
 * @property networkActivityTracker Told about each embedding request before it is sent, so
 *   the More tab's privacy indicator counts memory traffic too.
 */
@Singleton
class CloudEmbeddingProvider @Inject constructor(
    private val embedderFactory: KoogEmbedderFactory,
    private val apiKeyRepository: ApiKeyRepository,
    private val modelNetworkGate: ModelNetworkGate,
    private val networkActivityTracker: NetworkActivityTracker,
) : EmbeddingProvider {

    override val id: String = EmbeddingProvider.ID_OPENAI_3_SMALL

    override val displayName: String = "OpenAI (text-embedding-3-small)"

    override val dimension: Int = DIMENSION

    /** Available only when the network gate admits cloud providers and a non-blank OpenAI API key is configured. */
    override suspend fun isAvailable(): Boolean =
        modelNetworkGate.cloudRefusal() == null && !apiKeyRepository.getOpenAIKey().firstOrNull().isNullOrBlank()

    override suspend fun embed(text: String): FloatArray = embed(listOf(text)).first()

    /**
     * Embeds [texts] via OpenAI's batch embeddings endpoint in a single
     * request, mapping each returned `List<Double>` to a [FloatArray].
     *
     * @throws EmbeddingException If the network gate refuses cloud providers, no
     *   API key is configured, or the transport / API call fails. [CancellationException] is rethrown unchanged so
     *   coroutine cancellation is not swallowed.
     */
    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()

        // Re-checked here, not only in `isAvailable`: the restriction can be switched on
        // between the resolver's check and this send.
        modelNetworkGate.cloudRefusal()?.let { refusal ->
            throw EmbeddingException(refusal.message(CloudProvider.OPENAI))
        }
        val key = apiKeyRepository.getOpenAIKey().firstOrNull()
        if (key.isNullOrBlank()) {
            throw EmbeddingException("OpenAI API key is not configured")
        }

        val client = embedderFactory.openAiClient(key)
        networkActivityTracker.recordOutbound()
        return try {
            client.embed(texts, OpenAIModels.Embeddings.TextEmbedding3Small)
                .map { it.toFloatVector() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.e(e, "OpenAI embedding request failed")
            throw EmbeddingException("OpenAI embedding request failed", e)
        }
    }

    private companion object {
        /** Default output dimension of `text-embedding-3-small`. */
        const val DIMENSION = 1536
    }
}
