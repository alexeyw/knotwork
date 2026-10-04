package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.MemorySettings
import app.knotwork.android.domain.services.EmbeddingProviderResolver
import app.knotwork.android.domain.services.MemoryReranker
import app.knotwork.android.domain.services.MemorySearchStatsTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Use case for retrieving the most relevant long-term memories for a given user
 * query. It embeds the query into a vector, runs a cosine-similarity search
 * against the stored memory chunks, and re-ranks the full scored pool through
 * [MemoryReranker] (threshold filtering, recency bonus, pinned boost,
 * near-duplicate collapse), which returns the top-K survivors.
 *
 * This is the query-string façade over the lower-level vector search
 * ([MemoryRepository.findSimilarMemories], which takes a raw embedding). It is
 * the single entry point used by the pipeline ([app.knotwork.android.domain.engine.GraphExecutionEngine]
 * resolves it once per run, keyed off the immutable user prompt).
 *
 * **Why re-rank here, not in the repository.** [MemoryRepository.findSimilarMemories]
 * is shared with [MemoryExtractionUseCase]'s near-duplicate detection, which
 * needs raw cosine similarity untouched. Re-ranking therefore lives in this
 * retrieval-only path. The search is asked for the *full* scored pool (top-K is
 * applied **after** re-ranking) so a pinned or fresh chunk that ranks below the
 * raw-cosine top-K can still be promoted into the final result.
 *
 * **Why the resolver, not a fixed engine.** The query *must* be embedded with
 * the same provider that produced the stored chunks' embeddings — otherwise the
 * vectors live in different spaces (and, for cross-dimension providers, cosine
 * similarity collapses to `0` outright). Auto-extraction
 * ([MemoryExtractionUseCase]) embeds via [EmbeddingProviderResolver]; retrieval
 * resolves the *same* active provider here so the flag actually surfaces
 * memories regardless of which backend the user selected.
 *
 * @property embeddingProviderResolver Resolves the user's active embedding
 *   provider (with graceful on-device fallback).
 * @property memoryRepository Backing store exposing the raw vector search.
 * @property memoryReranker Applies recency / pinned / dedup / threshold rules to
 *   the raw search hits.
 * @property memorySettings Source of the default top-K / threshold /
 *   recency half-life when the caller does not override them.
 * @property memorySearchStatsTracker Records the raw similarity scores of each
 *   search so the Settings AVG SCORE stat cell reflects retrieval quality.
 */
class RetrieveRelevantMemoryUseCase @Inject constructor(
    private val embeddingProviderResolver: EmbeddingProviderResolver,
    private val memoryRepository: MemoryRepository,
    private val memoryReranker: MemoryReranker,
    private val memorySettings: MemorySettings,
    private val memorySearchStatsTracker: MemorySearchStatsTracker,
) {
    /**
     * Executes the retrieval process.
     *
     * @param query The text query (e.g. the user's message) to find context for.
     * @param limit Maximum number of memories to return. When `null` (the
     *   default), `MemorySettings.memorySearchTopK` is used. Provided as an
     *   explicit override mainly for tests.
     * @param threshold Minimum raw similarity a memory must reach to be kept —
     *   the recency and pinned bonuses reorder the survivors but never buy a
     *   chunk past this gate. Pinned chunks bypass the filter. When `null` (the
     *   default), `MemorySettings.memorySearchThreshold` is used.
     * @return Relevant [MemoryChunk]s ordered best-first (pinned chunks first,
     *   then by descending final score), capped at the effective top-K.
     */
    suspend operator fun invoke(query: String, limit: Int? = null, threshold: Float? = null): List<MemoryChunk> =
        retrieveScored(query, limit, threshold).map { it.first }

    /**
     * Score-preserving variant of [invoke]: runs the exact same embed → search →
     * re-rank → top-K pipeline but returns each surviving chunk paired with its
     * final (post-rerank) score, best-first.
     *
     * Used by [app.knotwork.android.domain.engine.GraphExecutionEngine] to surface
     * the similarity scores in the `MemoryAccess` console event without a second
     * retrieval. [invoke] is the score-free façade callers use when they only
     * need the chunks.
     *
     * @param query The text query (e.g. the user's message) to find context for.
     * @param limit Maximum number of memories to return. When `null` (the
     *   default), `MemorySettings.memorySearchTopK` is used.
     * @param threshold Minimum raw similarity a memory must reach to be kept —
     *   the recency and pinned bonuses reorder the survivors but never buy a
     *   chunk past this gate. Pinned chunks bypass the filter. When `null` (the
     *   default), `MemorySettings.memorySearchThreshold` is used.
     * @return Relevant `(chunk, finalScore)` pairs ordered best-first (pinned
     *   chunks first, then by descending final score), capped at the effective
     *   top-K.
     */
    suspend fun retrieveScored(
        query: String,
        limit: Int? = null,
        threshold: Float? = null,
    ): List<Pair<MemoryChunk, Float>> = withContext(Dispatchers.Default) {
        // Chunks imported under a different provider are repaired off the hot
        // path by MemoryReembedWorker (scheduled at import time); retrieval just
        // tolerates not-yet-repaired chunks (their cross-space vectors score ~0)
        // rather than blocking here on a potentially large re-embed.

        // Embed the query with the user's active provider so it shares the
        // stored chunks' embedding space.
        val provider = embeddingProviderResolver.resolve()
        val queryEmbedding = provider.embed(query)

        val effectiveLimit = limit ?: memorySettings.memorySearchTopK.first()
        val effectiveThreshold = threshold ?: memorySettings.memorySearchThreshold.first()
        val halfLifeDays = memorySettings.memoryRecencyHalfLifeDays.first()

        // Pull the full scored pool (not just the raw-cosine top-K) so the
        // re-ranker can promote a pinned or fresh chunk that the raw search
        // would have left just outside the top-K.
        memoryRepository.findSimilarMemories(queryEmbedding)
            .also { candidates ->
                // Pre-trim to the tracker's sample size: the pool can hold
                // thousands of entries and record() only keeps the head, so
                // mapping every score would allocate a large list for nothing.
                memorySearchStatsTracker.record(
                    candidates.take(MemorySearchStatsTracker.STATS_SAMPLE_SIZE).map { it.second },
                )
            }
            .let { candidates ->
                // Top-K is applied by the re-ranker itself: the near-duplicate
                // collapse has to see the full pool but only needs to run until
                // K chunks survive, so the cap and the collapse are one step.
                memoryReranker.rerank(
                    candidates = candidates,
                    nowMillis = System.currentTimeMillis(),
                    halfLifeDays = halfLifeDays,
                    threshold = effectiveThreshold,
                    limit = effectiveLimit,
                )
            }
    }
}
