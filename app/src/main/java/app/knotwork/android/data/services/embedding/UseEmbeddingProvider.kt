package app.knotwork.android.data.services.embedding

import app.knotwork.android.domain.engine.TextEmbeddingEngine
import app.knotwork.android.domain.services.EmbeddingProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device [EmbeddingProvider] backed by the MediaPipe Universal Sentence
 * Encoder (`universal_sentence_encoder.tflite`, 100-d).
 *
 * This is the default provider: it runs fully on-device, needs no network and
 * no API key, and keeps user text private. It delegates the actual inference
 * to the existing [TextEmbeddingEngine] so there is a single MediaPipe
 * integration point; this class only adds the richer [EmbeddingProvider]
 * contract (id / displayName / dimension / batch) on top.
 *
 * Calls are serialized with a [Mutex] because the underlying MediaPipe
 * `TextEmbedder` holds a single native handle that is not safe for concurrent
 * `embed` calls.
 *
 * @property engine The MediaPipe-backed embedding engine.
 */
@Singleton
class UseEmbeddingProvider @Inject constructor(private val engine: TextEmbeddingEngine) : EmbeddingProvider {

    private val mutex = Mutex()

    override val id: String = EmbeddingProvider.ID_USE

    override val displayName: String = "On-device (Universal Sentence Encoder)"

    override val dimension: Int = DIMENSION

    /** The on-device model is always available — no credentials, no network. */
    override suspend fun isAvailable(): Boolean = true

    override suspend fun embed(text: String): FloatArray = mutex.withLock { engine.generateEmbedding(text) }

    /**
     * Embeds [texts] sequentially under a single lock acquisition.
     *
     * MediaPipe has no native batch API, so this maps over the single-text
     * path. Holding the lock across the whole batch avoids interleaving with
     * concurrent single embeds and amortizes lock overhead.
     */
    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        return mutex.withLock { texts.map { engine.generateEmbedding(it) } }
    }

    private companion object {
        /**
         * Output dimension of the bundled Universal Sentence Encoder file: 100,
         * recorded from that file. The 512 this once claimed was assumed, not read.
         */
        const val DIMENSION = 100
    }
}
