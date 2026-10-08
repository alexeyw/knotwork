package app.knotwork.android.domain.services

/**
 * Abstraction over a text-embedding backend.
 *
 * An embedding provider turns natural-language text into a dense vector
 * ([FloatArray]) suitable for semantic similarity search in the long-term
 * memory subsystem. Decoupling the rest of the app from any single backend
 * lets the user pick between an on-device model (no network, private) and a
 * cloud model (higher quality, requires an API key) without the memory
 * pipeline knowing which one is active — that selection is resolved at call
 * time by [EmbeddingProviderResolver].
 *
 * Providers are registered into a Hilt `Map<String, EmbeddingProvider>` keyed
 * by [id]; the currently active id is persisted in
 * `MemorySettings.activeEmbeddingProviderId`.
 *
 * Implementations must be safe to call from a coroutine and must perform all
 * heavy work off the main thread.
 */
interface EmbeddingProvider {

    /**
     * Stable, machine-readable identifier for this provider (e.g. `"use"`).
     *
     * This is the Hilt multibinding map key and the value persisted in
     * settings. It must never change once shipped, otherwise a user's saved
     * selection would silently fall back to the default.
     */
    val id: String

    /**
     * Human-readable name shown in the Settings → Memory provider picker
     * (e.g. `"On-device (Universal Sentence Encoder)"`).
     */
    val displayName: String

    /**
     * Length of every vector this provider produces.
     *
     * Vectors from providers with different dimensions are not directly
     * comparable; switching the active provider therefore implies that stored
     * memory embeddings must eventually be re-computed (handled by later
     * memory tasks). Exposed here so callers can validate or branch on it.
     */
    val dimension: Int

    /**
     * Whether this provider can currently serve embedding requests.
     *
     * Cloud-backed providers return `false` when their required configuration
     * is missing (e.g. no API key, no server URL). [EmbeddingProviderResolver]
     * consults this and substitutes the always-available on-device default
     * rather than returning a provider that would either throw or — worse —
     * silently produce vectors of a different [dimension]. A provider that is
     * returned by the resolver therefore always honours its declared
     * [dimension].
     *
     * @return `true` if [embed] can be expected to succeed against this
     *   provider's own backend.
     */
    suspend fun isAvailable(): Boolean

    /**
     * Computes the embedding vector for a single piece of [text].
     *
     * @param text The input string to embed. May be empty, in which case the
     *   provider still returns a [dimension]-sized vector (implementation
     *   defined — typically zeros or the model's empty-string embedding).
     * @return A [FloatArray] of length [dimension].
     * @throws EmbeddingException If the embedding could not be produced (e.g. a
     *   cloud request failed). On-device providers may instead surface the
     *   underlying engine error.
     */
    suspend fun embed(text: String): FloatArray

    /**
     * Computes embeddings for a batch of [texts] in a single logical call.
     *
     * Cloud providers override this to use their native batch endpoint (one
     * round-trip instead of N); on-device providers typically map over
     * [embed]. The returned list is index-aligned with [texts] — element `i`
     * is the embedding of `texts[i]`.
     *
     * @param texts The input strings to embed. An empty list yields an empty
     *   result without contacting any backend.
     * @return A list of [FloatArray]s, each of length [dimension], in the same
     *   order as [texts].
     * @throws EmbeddingException If the batch could not be embedded.
     */
    suspend fun embed(texts: List<String>): List<FloatArray>

    /**
     * Canonical provider identifiers.
     *
     * Declared as compile-time constants so they can double as Hilt
     * `@StringKey` annotation values and as the persisted-settings default.
     * This is the single source of truth — the DI module, the resolver
     * fallback, and `SettingsDefaults` all reference these.
     */
    companion object {
        /** On-device MediaPipe Universal Sentence Encoder (100-d). The default. */
        const val ID_USE: String = "use"

        /** OpenAI `text-embedding-3-small` via Koog (1536-d). Requires an OpenAI key. */
        const val ID_OPENAI_3_SMALL: String = "openai_3_small"

        /** Local-network Ollama embeddings via Koog (`nomic-embed-text`, 768-d). */
        const val ID_OLLAMA: String = "ollama"
    }
}

/**
 * Thrown when an [EmbeddingProvider] fails to produce an embedding.
 *
 * Used to translate backend-specific failures (HTTP errors, transport
 * exceptions, malformed responses) into a single domain-level error type so
 * callers in the memory pipeline can handle embedding failures uniformly
 * without depending on data-layer (Koog / OkHttp) exception types.
 *
 * @param message Human-readable description of what failed.
 * @param cause The underlying exception, if any.
 */
class EmbeddingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
