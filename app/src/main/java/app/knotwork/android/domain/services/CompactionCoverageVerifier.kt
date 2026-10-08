package app.knotwork.android.domain.services

import app.knotwork.android.domain.models.MemoryChunk
import javax.inject.Inject

/**
 * Decides which chunks of a compaction cluster a freshly-generated summary is
 * actually allowed to replace.
 *
 * Consolidation asks a small on-device model to fold several stored facts into
 * one, then deletes the originals. Without a check, *any* non-blank reply is
 * accepted as a faithful replacement — so a model that drops one of the facts,
 * or answers about something else entirely, silently destroys the only copy of
 * it. This verifier is the pre-commit gate that prevents that: only the members
 * the summary demonstrably covers may be deleted, and the rest stay stored
 * verbatim.
 *
 * **The rule.** A member is *covered* when the summary is at least as close to
 * it as the cluster's own centroid is, within a small tolerance:
 *
 * ```
 * covered(m)  ⇔  cosine(summary, m) ≥ cosine(centroid, m) − COVERAGE_MARGIN
 * ```
 *
 * The centroid is the single vector closest to the cluster as a whole, i.e. the
 * best "average" of exactly the facts being merged. Requiring the summary to
 * sit about that centrally is therefore a direct statement of what
 * consolidation claims to have done, and the summary of a cluster whose model
 * reply wandered off-topic lands far below it.
 *
 * **A member the embedder cannot tell apart from another is never covered.**
 * When two members of the cluster sit at [INDISTINGUISHABLE_SIMILARITY] or
 * closer, a summary near one is just as near the other, so the vectors cannot
 * say which of the two it carries — and both stay. This is what an embedder
 * that folds a language into one direction looks like: the bundled on-device
 * encoder scores unrelated Russian facts at 0.99 and above, so every Russian
 * member of a cluster would otherwise count as covered by any Russian summary.
 * The cost: two stored copies of one fact no longer merge. After the write-time
 * judge, stored near-twins are mostly facts of the same shape that differ
 * (allergic to cats / allergic to peanuts), which must not be merged blindly anyway.
 *
 * **Why the yardstick is relative, not an absolute similarity floor.**
 * Embedding backends differ in how they distribute similarity (the on-device
 * 100-dimension encoder and a cloud provider do not agree on what `0.75` means), and
 * clusters differ in tightness. A hard-coded floor would therefore reject
 * honest summaries under one provider and wave through nonsense under another.
 * Judging the summary against the cluster's own centroid makes the gate
 * scale-free: it asks the same question in every space.
 *
 * **What this catches and what it does not.** It catches the failure mode that
 * costs data — a fact quietly missing from the summary, or a reply that is not
 * a summary at all. It does **not** catch a distorted detail inside an
 * otherwise-faithful sentence (moving a meeting from Tuesday to Wednesday
 * barely moves the vector). That residual risk is handled on the read side
 * instead: [MemoryReranker] never lets a derived summary displace a verbatim
 * chunk that restates it.
 *
 * The verifier is pure and stateless — no clock, no I/O — so every branch is
 * unit-testable.
 */
class CompactionCoverageVerifier @Inject constructor() {

    /**
     * Splits [members] into the chunks the summary may replace and those it may
     * not.
     *
     * @param members The cluster being consolidated (order is preserved in the
     *   verdict, so the caller's ids stay in their original sequence).
     * @param summaryEmbedding The embedding of the generated summary, produced
     *   by the **active** provider — the same one that embedded the members, or
     *   the comparison is meaningless (cross-space vectors score `0f` and every
     *   member ends up uncovered, which fails safe: nothing is deleted).
     * @return The covered / uncovered split. A member whose embedding cannot be
     *   compared at all (empty vector, or a dimension from another provider), or
     *   one the embedder cannot tell apart from another member, is always
     *   reported uncovered: absence of evidence must never authorise a deletion.
     */
    fun verify(members: List<MemoryChunk>, summaryEmbedding: FloatArray): CoverageVerdict {
        val centroid = MemoryVectorSimilarity.centroid(members.map { it.embedding })
        val (covered, uncovered) = members.withIndex().partition { (index, member) ->
            val centroidSimilarity = MemoryVectorSimilarity.cosine(centroid, member.embedding)
            // A member that is not even comparable to its own cluster centroid
            // carries no usable vector, so there is nothing to verify against.
            if (centroidSimilarity <= 0f || hasTwin(index, members)) {
                false
            } else {
                MemoryVectorSimilarity.cosine(summaryEmbedding, member.embedding) >=
                    centroidSimilarity - COVERAGE_MARGIN
            }
        }
        return CoverageVerdict(covered = covered.map { it.value }, uncovered = uncovered.map { it.value })
    }

    /** Whether another member sits at [INDISTINGUISHABLE_SIMILARITY] or closer to the one at [index]. */
    private fun hasTwin(index: Int, members: List<MemoryChunk>): Boolean = members.indices.any { other ->
        other != index &&
            MemoryVectorSimilarity.cosine(members[index].embedding, members[other].embedding) >=
            INDISTINGUISHABLE_SIMILARITY
    }

    /** Shared constants for [CompactionCoverageVerifier]. */
    companion object {

        /**
         * How far below the centroid's similarity a summary may sit and still
         * count as covering a member.
         *
         * A summary is a rewrite, not an average, so it is expected to land a
         * little further from each individual fact than the centroid does; the
         * tolerance absorbs that without admitting a summary that simply left a
         * fact out (which lands far lower, not marginally). Fixed rather than
         * user-tunable for the same reason as
         * [MemoryVectorSimilarity.NEAR_DUPLICATE_THRESHOLD]: another slider
         * nobody has the data to set would make the feature worse, not better.
         */
        const val COVERAGE_MARGIN: Float = 0.05f

        /**
         * The similarity at which two members count as indistinguishable to the
         * embedder. Measured on the bundled encoder's recorded vectors
         * (`supersede_pairs.json`): every pair of the fixture's Russian facts sits
         * at 0.990 or above, unrelated English facts at 0.961 or below, and the
         * English pairs at 0.98 or above (10 of 1 128) are all rewordings,
         * corrections or facts of the same shape.
         */
        const val INDISTINGUISHABLE_SIMILARITY: Float = 0.98f
    }

    /**
     * Outcome of verifying one cluster against its summary.
     *
     * @property covered Members the summary faithfully represents — these, and
     *   only these, may be deleted and recorded in
     *   [app.knotwork.android.domain.models.MemorySource.Compaction].
     * @property uncovered Members the summary failed to account for. They stay
     *   stored verbatim, so the fact survives even though it was clustered.
     */
    data class CoverageVerdict(val covered: List<MemoryChunk>, val uncovered: List<MemoryChunk>)
}
