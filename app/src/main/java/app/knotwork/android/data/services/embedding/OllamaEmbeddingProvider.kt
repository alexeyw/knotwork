package app.knotwork.android.data.services.embedding

import ai.koog.prompt.executor.ollama.client.OllamaModels
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
 * Local-network [EmbeddingProvider] backed by an Ollama server running the
 * `nomic-embed-text` model (768-d), called through Koog's `OllamaClient`.
 *
 * This targets users who already run Ollama on their LAN (the same server the
 * app can use for chat completions) and want higher-quality embeddings than
 * the on-device USE model without sending data to a third-party cloud. It
 * reuses the Ollama base URL configured for chat in [ApiKeyRepository].
 *
 * The address passes through [ModelNetworkGate] — the same decision the Ollama
 * chat client takes — before a byte is sent: the cleartext rule always, and,
 * while "Block network from local model" is on, the requirement that the host
 * be local. The text embedded here is the user's memory and search queries, so
 * it is held to the same rules as a prompt.
 *
 * Like the cloud provider, it never silently falls back to another backend
 * (that would break its [dimension] contract): when no base URL is configured,
 * or the gate refuses it, [isAvailable] reports `false` so
 * `EmbeddingProviderResolver` substitutes the on-device default; an [embed] call
 * in either state fails with an [EmbeddingException].
 *
 * @property embedderFactory Builds the underlying Koog Ollama client.
 * @property apiKeyRepository Source of the Ollama base URL.
 * @property modelNetworkGate Decides whether the configured address may be reached.
 * @property networkActivityTracker Told about each embedding request before it is sent, so
 *   the More tab's privacy indicator counts memory traffic too.
 */
@Singleton
class OllamaEmbeddingProvider @Inject constructor(
    private val embedderFactory: KoogEmbedderFactory,
    private val apiKeyRepository: ApiKeyRepository,
    private val modelNetworkGate: ModelNetworkGate,
    private val networkActivityTracker: NetworkActivityTracker,
) : EmbeddingProvider {

    override val id: String = EmbeddingProvider.ID_OLLAMA

    override val displayName: String = "Ollama (nomic-embed-text)"

    override val dimension: Int = DIMENSION

    /** Available only when a non-blank Ollama base URL is configured and the network gate admits it. */
    override suspend fun isAvailable(): Boolean {
        val baseUrl = baseUrl() ?: return false
        return modelNetworkGate.ollamaRefusal(baseUrl) == null
    }

    override suspend fun embed(text: String): FloatArray = embed(listOf(text)).first()

    /**
     * Embeds [texts] via the Ollama server's batch embedding call, mapping each
     * returned `List<Double>` to a [FloatArray].
     *
     * @throws EmbeddingException If no base URL is configured, the network gate
     *   refuses it, or the transport / server call fails. [CancellationException]
     *   is rethrown unchanged so coroutine cancellation is not swallowed.
     */
    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()

        val baseUrl = baseUrl() ?: throw EmbeddingException("Ollama base URL is not configured")
        // Re-checked here, not only in `isAvailable`: the restriction can be switched on
        // between the resolver's check and this send.
        modelNetworkGate.ollamaRefusal(baseUrl)?.let { refusal ->
            throw EmbeddingException(refusal.message(CloudProvider.OLLAMA))
        }

        val client = embedderFactory.ollamaClient(baseUrl)
        networkActivityTracker.recordOutbound()
        return try {
            client.embed(texts, OllamaModels.Embeddings.NOMIC_EMBED_TEXT)
                .map { it.toFloatVector() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.e(e, "Ollama embedding request failed")
            throw EmbeddingException("Ollama embedding request failed", e)
        }
    }

    /** The trimmed, non-blank Ollama base URL, or `null` when none is configured. */
    private suspend fun baseUrl(): String? =
        apiKeyRepository.getOllamaBaseUrl().firstOrNull()?.trim()?.takeIf { it.isNotBlank() }

    private companion object {
        /** Output dimension of the `nomic-embed-text` Ollama model. */
        const val DIMENSION = 768
    }
}
