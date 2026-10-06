package app.knotwork.android.domain.services

import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MemorySource
import javax.inject.Inject
import kotlin.math.pow

/**
 * Re-ranks the raw cosine-similarity hits returned by the long-term memory
 * vector search into a list that better matches what a human would expect to
 * surface.
 *
 * Pure cosine similarity is a blunt instrument: a stale chunk can outrank a
 * fresh one purely on lexical overlap, restatements of one fact clutter the
 * limited context budget, and user-pinned facts are treated no differently from
 * noise. This service layers six deterministic rules on top of the raw scores
 * to fix that, each of which is independently unit-testable:
 *
 *  1. **Additive scoring** — `final = similarity + recencyBonus + pinnedBoost +
 *     manualBonus`. Relevance is never scaled down; freshness, pinning and the
 *     user's own wording only ever *add*.
 *  2. **Recency bonus** — a chunk earns up to [RECENCY_BONUS] on top of its
 *     similarity, halving every `halfLifeDays` of age:
 *     `bonus = RECENCY_BONUS * 0.5^(daysSince / halfLifeDays)`. The bonus decays
 *     towards zero but never reaches it, so fresher always outranks staler at
 *     equal relevance — at any age, with no plateau.
 *  3. **Pinned boost** — pinned chunks receive a flat [PINNED_BOOST], sort ahead
 *     of every non-pinned chunk, and are **exempt from the threshold filter**,
 *     so a deliberately curated fact is always surfaced ("always at the top").
 *  4. **The user's own wording** — a chunk the user saved by hand
 *     ([MemorySource.Manual]) earns a small flat [MANUAL_SOURCE_BONUS]: at about
 *     equal relevance and age, what the user wrote outranks what a model
 *     extracted, including inside a group of near-duplicates. The smallest of
 *     the bonuses, so freshness still decides between a stale manual entry and a
 *     fresh extracted one.
 *  5. **Verbatim beats derived** — a consolidation summary
 *     ([MemorySource.Compaction]) is dropped when the memory it was distilled
 *     from is itself in the pool, either by provenance (one of its
 *     `originalChunkIds` is present) or by meaning (a non-derived chunk
 *     restates it). The source outranks the paraphrase regardless of score.
 *  6. **Threshold filter, then near-duplicate collapse** — non-pinned chunks
 *     whose **raw similarity** (not their bonused score — a bonus must never
 *     buy admission) falls below the caller's threshold are dropped before
 *     anything else, and among the survivors any chunk whose embedding is
 *     within
 *     [MemoryVectorSimilarity.NEAR_DUPLICATE_THRESHOLD] of an already-kept,
 *     better-ranked chunk is collapsed into it.
 *
 * **Why age must not scale relevance.** The previous model multiplied
 * similarity by a weight that fell linearly to zero at twice the half-life and
 * then thresholded the *product*. That made age a hard time-to-live: past that
 * point no chunk was retrievable at any relevance, and well before it a
 * perfectly on-topic fact was already scored below a default threshold. Under
 * the additive model the threshold judges relevance alone, so an old but
 * genuinely relevant chunk stays retrievable indefinitely, while a fresh chunk
 * of equal relevance still wins the ordering.
 *
 * **Why a summary must never outrank its source.** Compaction summaries are
 * written by a small on-device model at consolidation time, so they are always
 * *newer* than the facts they replace and would win the recency bonus against
 * them. Left alone, a paraphrase — the one kind of chunk that can be subtly
 * wrong — would displace the verbatim wording whenever both are retrievable.
 * The compaction pass already refuses to delete facts its summary cannot be
 * shown to cover
 * ([app.knotwork.android.domain.services.CompactionCoverageVerifier]); this
 * rule is the read-side half of the same contract, and the one that also covers
 * summaries whose sources came back through an import.
 *
 * **Why duplicates are detected by embedding, not by text prefix.** Collapsing
 * chunks that share their first N characters both over- and under-merges:
 * pipelines that write with a fixed preamble ("Journal entry for …") produced
 * identical prefixes for unrelated facts, while the same fact reworded kept two
 * slots. Comparing embeddings measures the thing that actually matters, and
 * reuses the exact threshold the write path uses to refuse a duplicate.
 *
 * The service is stateless and clock-free: the caller supplies `nowMillis` so
 * recency is reproducible in tests and the domain layer keeps a single
 * wall-clock read site (the retrieval use case).
 */
class MemoryReranker @Inject constructor() {

    /**
     * Applies the ranking rules to [candidates] and returns the surviving chunks
     * paired with their recomputed final score, ordered best-first (pinned
     * chunks first, then by descending final score), capped at [limit].
     *
     * @param candidates Raw vector-search hits as `(chunk, cosineSimilarity)`
     *   pairs. Order is irrelevant — this method re-sorts from scratch.
     * @param nowMillis Current wall-clock time in `System.currentTimeMillis()`
     *   units, used as the reference point for the recency bonus. Chunks with a
     *   `timestamp` in the future (clock skew) are treated as zero days old.
     * @param halfLifeDays Age, in days, at which a chunk's recency bonus halves.
     *   Coerced to at least `1` to avoid division by zero.
     * @param threshold Minimum similarity a non-pinned chunk must reach to be
     *   retained. Pinned chunks bypass this filter entirely.
     * @param limit Maximum number of chunks to return; values below `1` yield an
     *   empty list. Capping here rather than in the caller is what bounds the
     *   near-duplicate scan — see [collapseNearDuplicates].
     * @return The retained chunks with their final scores, ordered best-first.
     */
    fun rerank(
        candidates: List<Pair<MemoryChunk, Float>>,
        nowMillis: Long,
        halfLifeDays: Int,
        threshold: Float,
        limit: Int,
    ): List<Pair<MemoryChunk, Float>> {
        if (limit < 1) return emptyList()
        val safeHalfLifeDays = halfLifeDays.coerceAtLeast(1)

        val ranked = candidates
            .filter { (chunk, similarity) -> chunk.isPinned || similarity >= threshold }
            .map { (chunk, similarity) -> chunk to finalScore(chunk, similarity, nowMillis, safeHalfLifeDays) }
            .sortedWith(
                compareByDescending<Pair<MemoryChunk, Float>> { it.first.isPinned }
                    .thenByDescending { it.second },
            )

        return collapseNearDuplicates(suppressSupersededSummaries(ranked), limit)
    }

    /**
     * Drops every consolidation summary whose own source material is present in
     * [ranked], so the verbatim fact — not the model's rewrite of it — is what
     * reaches the prompt.
     *
     * A summary counts as superseded when either link holds:
     *  - **provenance** — one of its [MemorySource.Compaction.originalChunkIds]
     *    is among the candidates, which is what makes those ids a live link
     *    rather than a historical note; or
     *  - **meaning** — some non-derived candidate restates it (embedding cosine
     *    at or above [MemoryVectorSimilarity.NEAR_DUPLICATE_THRESHOLD]), which
     *    catches the common case where the summary's sources are long deleted
     *    but an equivalent fact was learned again since.
     *
     * Running this *before* the collapse rather than inside it is deliberate:
     * the collapse is a greedy top-K walk, so resolving the conflict there
     * would depend on where the two chunks happened to land relative to the
     * cut-off. Here the decision is made over the full threshold-survivor list
     * and neither the ordering nor the collapse's early exit is disturbed.
     *
     * Pinned summaries are exempt. Pinning is an explicit user statement that
     * this exact entry must always surface, and no automatic rule may quietly
     * overrule it.
     *
     * Accepted cost: the surviving source is judged retrievable, not
     * guaranteed *returned* — if it happens to rank below the top-K while the
     * summary ranked inside it, this query surfaces neither. Preferring the
     * verbatim wording is worth that occasional miss, and the alternative
     * (substituting the source into the summary's slot) buys it by making the
     * kept set depend on where the two chunks fell relative to the cut-off.
     *
     * @param ranked Threshold survivors, already ordered best-first.
     * @return The same list minus the superseded summaries (order preserved).
     */
    private fun suppressSupersededSummaries(ranked: List<Pair<MemoryChunk, Float>>): List<Pair<MemoryChunk, Float>> {
        val verbatim = ranked.filter { it.first.source !is MemorySource.Compaction }
        // Nothing derived to drop, or nothing verbatim to prefer over it.
        if (verbatim.isEmpty() || verbatim.size == ranked.size) return ranked

        val verbatimIds = verbatim.mapTo(HashSet()) { it.first.id }
        return ranked.filterNot { (chunk, _) ->
            val source = chunk.source
            source is MemorySource.Compaction &&
                !chunk.isPinned &&
                (
                    source.originalChunkIds.any { it in verbatimIds } ||
                        verbatim.any { (candidate, _) ->
                            MemoryVectorSimilarity.cosine(chunk.embedding, candidate.embedding) >=
                                MemoryVectorSimilarity.NEAR_DUPLICATE_THRESHOLD
                        }
                    )
        }
    }

    /**
     * Walks [ranked] best-first and keeps a chunk only when it restates none of
     * the chunks kept before it, stopping as soon as [limit] chunks are kept.
     *
     * Keeping the *best-ranked* representative of a group is what preserves the
     * pinned guarantee: pinned chunks sort ahead of every non-pinned one, so a
     * later unpinned restatement can never evict the pinned original and strip
     * its threshold exemption and top-of-list slot. Among equally-pinned chunks
     * the higher final score wins, which — for equal relevance — is the fresher
     * phrasing.
     *
     * Stopping early is safe rather than approximate: the rule is greedy, so the
     * kept set after `k` survivors depends only on the candidates already
     * walked. Truncating here therefore yields exactly what collapsing the whole
     * list and taking the first [limit] entries would, while bounding the scan
     * to at most [limit] vector comparisons per candidate.
     *
     * @param ranked Threshold survivors, already ordered best-first.
     * @param limit Maximum number of chunks to keep.
     * @return The kept chunks in input order (i.e. still best-first).
     */
    private fun collapseNearDuplicates(
        ranked: List<Pair<MemoryChunk, Float>>,
        limit: Int,
    ): List<Pair<MemoryChunk, Float>> {
        val kept = ArrayList<Pair<MemoryChunk, Float>>(minOf(limit, ranked.size))
        for (candidate in ranked) {
            val isRestatement = kept.any { (keptChunk, _) ->
                MemoryVectorSimilarity.cosine(candidate.first.embedding, keptChunk.embedding) >=
                    MemoryVectorSimilarity.NEAR_DUPLICATE_THRESHOLD
            }
            if (isRestatement) continue
            kept += candidate
            if (kept.size == limit) break
        }
        return kept
    }

    /**
     * Computes the post-ranking score for a single chunk:
     * `similarity + recencyBonus + (pinned ? PINNED_BOOST : 0) +
     * (manual ? MANUAL_SOURCE_BONUS : 0)`.
     *
     * Every adjustment is additive, so none can push a relevant chunk below the
     * similarity it earned — they reorder, they do not disqualify.
     *
     * @param chunk The chunk being scored (its `isPinned` / `timestamp` / `source`
     *   drive the adjustments).
     * @param similarity The raw cosine similarity of [chunk] to the query.
     * @param nowMillis Reference time for the age calculation.
     * @param halfLifeDays Pre-validated (`>= 1`) recency half-life.
     * @return The final score used for ordering.
     */
    private fun finalScore(chunk: MemoryChunk, similarity: Float, nowMillis: Long, halfLifeDays: Int): Float {
        val pinned = if (chunk.isPinned) PINNED_BOOST else 0f
        val manual = if (chunk.source == MemorySource.Manual) MANUAL_SOURCE_BONUS else 0f
        return similarity + recencyBonus(chunk.timestamp, nowMillis, halfLifeDays) + pinned + manual
    }

    /**
     * Derives the freshness bonus added to a chunk's similarity.
     *
     * @param timestamp The chunk's creation time in `System.currentTimeMillis()`
     *   units.
     * @param nowMillis Reference "now".
     * @param halfLifeDays Pre-validated (`>= 1`) recency half-life.
     * @return A bonus in `(0f, RECENCY_BONUS]` — the full bonus for a chunk
     *   created right now, half of it at exactly the half-life, and an
     *   ever-smaller but strictly positive amount beyond, so freshness keeps
     *   breaking ties at any age.
     */
    private fun recencyBonus(timestamp: Long, nowMillis: Long, halfLifeDays: Int): Float {
        val daysSince = ((nowMillis - timestamp).coerceAtLeast(0L)).toDouble() / MILLIS_PER_DAY
        return (RECENCY_BONUS * HALVING_BASE.pow(daysSince / halfLifeDays)).toFloat()
    }

    private companion object {
        /** Flat bonus added to a pinned chunk's final score. */
        const val PINNED_BOOST: Float = 0.2f

        /**
         * Bonus a brand-new chunk earns for freshness, halving every
         * `halfLifeDays`. Kept below [PINNED_BOOST]: pinning is a deliberate
         * user act and must outweigh mere recency.
         */
        const val RECENCY_BONUS: Double = 0.15

        /**
         * Flat bonus of a chunk the user saved by hand. The smallest bonus: on the
         * bundled on-device embedder a related and an unrelated fact are only about
         * 0.1–0.15 apart in similarity, so the source must break ties, not override
         * relevance or freshness. A judgement, not a measurement.
         */
        const val MANUAL_SOURCE_BONUS: Float = 0.03f

        /** Base of the recency decay — `0.5` makes `halfLifeDays` a half-life. */
        const val HALVING_BASE: Double = 0.5

        /** Milliseconds in one day, used to convert an age delta to days. */
        const val MILLIS_PER_DAY: Double = 86_400_000.0
    }
}
