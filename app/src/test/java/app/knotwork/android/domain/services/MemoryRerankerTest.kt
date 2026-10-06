package app.knotwork.android.domain.services

import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MemorySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Unit tests for [MemoryReranker]. Each ranking rule — the additive recency
 * bonus, the pinned boost, the bonus of a hand-saved chunk, the threshold filter
 * and the near-duplicate collapse — is exercised in isolation. A test chunk is
 * an extracted fact unless a test says otherwise, plus the edge cases (clock skew, zero half-life,
 * unrelated chunks that share a text prefix, chunks awaiting a re-embed).
 */
class MemoryRerankerTest {

    private val reranker = MemoryReranker()

    /**
     * Anchor "now" at a round multiple of [DAY] so chunk ages are exact whole
     * days and recency math is easy to assert.
     */
    private val now = 1_000L * DAY

    /**
     * A unit vector at [degrees] in the first two dimensions. The cosine
     * similarity of two such vectors is the cosine of the angle between them,
     * which makes "these two chunks restate each other" (small angle) and "these
     * two are unrelated" (wide angle) exact rather than hand-waved.
     */
    private fun unit(degrees: Double): FloatArray {
        val radians = degrees * PI / 180.0
        return floatArrayOf(cos(radians).toFloat(), sin(radians).toFloat())
    }

    private fun chunk(
        id: Long,
        text: String = "fact-$id",
        timestamp: Long = now,
        isPinned: Boolean = false,
        embedding: FloatArray = unit(id * DISTINCT_ANGLE_DEGREES),
        source: MemorySource = MemorySource.ChatSession(sessionId = "session"),
    ): MemoryChunk = MemoryChunk(
        id = id,
        text = text,
        embedding = embedding,
        timestamp = timestamp,
        isPinned = isPinned,
        source = source,
    )

    // region recency bonus

    @Test
    fun `given a non-pinned chunk at the half-life when reranked then its recency bonus is halved`() {
        val aged = chunk(id = 1, timestamp = now - 30 * DAY)

        val result = reranker.rerank(
            candidates = listOf(aged to 0.8f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0f,
            limit = 5,
        )

        assertEquals(1, result.size)
        // bonus = 0.15 * 0.5^(30/30) = 0.075  ->  0.8 + 0.075 = 0.875
        assertEquals(0.875f, result.single().second, EPSILON)
    }

    @Test
    fun `given equal similarity when one chunk is fresher then it ranks first`() {
        val fresh = chunk(id = 1, timestamp = now)
        val stale = chunk(id = 2, timestamp = now - 20 * DAY)

        val result = reranker.rerank(
            candidates = listOf(stale to 0.9f, fresh to 0.9f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0f,
            limit = 5,
        )

        assertEquals(listOf(1L, 2L), result.map { it.first.id })
    }

    @Test
    fun `given a highly relevant chunk aged three half-lives when reranked then it is still retrieved`() {
        // The regression this whole change exists for: under the old
        // multiplicative decay this chunk scored 0 and was unreachable at any
        // relevance. Age must not be able to disqualify a relevant fact.
        val ancient = chunk(id = 1, timestamp = now - 90 * DAY)

        val result = reranker.rerank(
            candidates = listOf(ancient to 0.85f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0.55f,
            limit = 5,
        )

        assertEquals(listOf(1L), result.map { it.first.id })
        // bonus = 0.15 * 0.5^3 = 0.01875  ->  0.85 + 0.01875
        assertEquals(0.86875f, result.single().second, EPSILON)
    }

    @Test
    fun `given two chunks far past twice the half-life when reranked then freshness still breaks the tie`() {
        // The old formula floored the weight at zero from 2 * half-life onwards,
        // so every chunk beyond it tied at 0. The bonus decays without ever
        // reaching zero, so ordering keeps working at any age.
        val older = chunk(id = 1, timestamp = now - 400 * DAY)
        val lessOld = chunk(id = 2, timestamp = now - 200 * DAY)

        val result = reranker.rerank(
            candidates = listOf(older to 0.7f, lessOld to 0.7f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0.55f,
            limit = 5,
        )

        assertEquals(listOf(2L, 1L), result.map { it.first.id })
        assertTrue(result[0].second > result[1].second)
    }

    @Test
    fun `given a future timestamp when reranked then age is clamped to zero`() {
        val skewed = chunk(id = 1, timestamp = now + 10 * DAY)

        val result = reranker.rerank(
            candidates = listOf(skewed to 0.7f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0f,
            limit = 5,
        )

        // Negative age clamps to 0 -> full bonus -> 0.7 + 0.15.
        assertEquals(0.85f, result.single().second, EPSILON)
    }

    // endregion

    // region pinned boost

    @Test
    fun `given a pinned chunk when reranked then it sorts above a higher-similarity non-pinned chunk`() {
        val pinned = chunk(id = 1, isPinned = true)
        val strong = chunk(id = 2, isPinned = false)

        val result = reranker.rerank(
            candidates = listOf(strong to 0.9f, pinned to 0.3f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0f,
            limit = 5,
        )

        assertEquals(listOf(1L, 2L), result.map { it.first.id })
        // pinned final = 0.3 + 0.2 boost + 0.15 full recency bonus = 0.65
        assertEquals(0.65f, result.first().second, EPSILON)
    }

    // endregion

    // region the user's own wording

    @Test
    fun `given a hand-saved chunk when reranked then its score carries the manual bonus`() {
        val manual = chunk(id = 1, source = MemorySource.Manual)

        val result = reranker.rerank(
            candidates = listOf(manual to 0.8f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0f,
            limit = 5,
        )

        // 0.8 + 0.15 full recency bonus + 0.03 manual bonus = 0.98
        assertEquals(0.98f, result.single().second, EPSILON)
    }

    @Test
    fun `given equal relevance and age when one chunk was saved by hand then it ranks above the extracted one`() {
        val extracted = chunk(id = 1)
        val manual = chunk(id = 2, source = MemorySource.Manual)

        val result = reranker.rerank(
            candidates = listOf(extracted to 0.8f, manual to 0.8f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0f,
            limit = 5,
        )

        assertEquals(listOf(2L, 1L), result.map { it.first.id })
    }

    @Test
    fun `given an extracted restatement of a hand-saved chunk when reranked then the hand-saved wording survives`() {
        // The two say the same thing (5° apart, cosine 0.996): the collapse keeps one,
        // and at equal relevance and age it is the one the user wrote.
        val extracted = chunk(id = 1, embedding = unit(0.0))
        val manual = chunk(id = 2, embedding = unit(5.0), source = MemorySource.Manual)

        val result = reranker.rerank(
            candidates = listOf(extracted to 0.8f, manual to 0.8f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0f,
            limit = 5,
        )

        assertEquals(listOf(2L), result.map { it.first.id })
    }

    @Test
    fun `given a stale hand-saved chunk and a fresh extracted one when reranked then freshness still wins`() {
        // The manual bonus breaks ties; it does not outweigh a half-life of age:
        // 0.8 + 0.075 + 0.03 = 0.905 against 0.8 + 0.15 = 0.95.
        val manual = chunk(id = 1, timestamp = now - 30 * DAY, source = MemorySource.Manual)
        val extracted = chunk(id = 2, timestamp = now)

        val result = reranker.rerank(
            candidates = listOf(manual to 0.8f, extracted to 0.8f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0f,
            limit = 5,
        )

        assertEquals(listOf(2L, 1L), result.map { it.first.id })
    }

    @Test
    fun `given a hand-saved chunk just below threshold when reranked then the manual bonus does not buy it in`() {
        val manual = chunk(id = 1, source = MemorySource.Manual)

        val result = reranker.rerank(
            candidates = listOf(manual to 0.54f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0.55f,
            limit = 5,
        )

        assertTrue(result.isEmpty())
    }

    // endregion

    // region threshold filtering

    @Test
    fun `given a non-pinned chunk below threshold when reranked then it is dropped`() {
        val weak = chunk(id = 1)

        val result = reranker.rerank(
            candidates = listOf(weak to 0.40f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0.55f,
            limit = 5,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `given a fresh chunk just below threshold when reranked then the recency bonus does not buy it in`() {
        // The gate judges relevance alone: 0.45 + a full 0.15 bonus would clear
        // 0.55, but freshness must not make an off-topic chunk "relevant".
        val fresh = chunk(id = 1, timestamp = now)

        val result = reranker.rerank(
            candidates = listOf(fresh to 0.45f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0.55f,
            limit = 5,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `given a pinned chunk below threshold when reranked then it is retained`() {
        val pinned = chunk(id = 1, isPinned = true)
        val weakNonPinned = chunk(id = 2)

        val result = reranker.rerank(
            candidates = listOf(pinned to 0.1f, weakNonPinned to 0.4f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0.55f,
            limit = 5,
        )

        assertEquals(listOf(1L), result.map { it.first.id })
    }

    // endregion

    // region near-duplicate collapse

    @Test
    fun `given unrelated facts sharing a long text prefix when reranked then both survive`() {
        // Journal- and translation-style pipelines write with a fixed preamble,
        // so the pre-embedding prefix rule collapsed unrelated facts into one.
        val preamble = "Journal entry for the evening review, recorded automatically: "
        val a = chunk(id = 1, text = preamble + "the tyre pressure warning came back on", embedding = unit(0.0))
        val b = chunk(id = 2, text = preamble + "the dentist moved the appointment to Friday", embedding = unit(60.0))

        val result = reranker.rerank(
            candidates = listOf(a to 0.9f, b to 0.88f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0f,
            limit = 5,
        )

        assertEquals(listOf(1L, 2L), result.map { it.first.id })
    }

    @Test
    fun `given restatements of one fact with different prefixes when reranked then only the best-ranked survives`() {
        // Same fact, no shared prefix at all — invisible to the old rule.
        val older = chunk(
            id = 1,
            text = "The user's daughter is allergic to penicillin.",
            timestamp = now - 5 * DAY,
            embedding = unit(0.0),
        )
        val newer = chunk(
            id = 2,
            text = "Penicillin causes an allergic reaction in the user's daughter.",
            timestamp = now - 1 * DAY,
            embedding = unit(10.0),
        )

        val result = reranker.rerank(
            candidates = listOf(older to 0.9f, newer to 0.9f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0f,
            limit = 5,
        )

        // cos(10°) = 0.985 >= 0.92 -> collapsed; at equal similarity the fresher
        // phrasing carries the larger recency bonus and therefore survives.
        assertEquals(listOf(2L), result.map { it.first.id })
    }

    @Test
    fun `given a pinned chunk and a newer unpinned restatement when reranked then the pinned chunk survives`() {
        val pinnedOlder = chunk(
            id = 1,
            timestamp = now - 10 * DAY,
            isPinned = true,
            embedding = unit(0.0),
        )
        val unpinnedNewer = chunk(id = 2, timestamp = now, embedding = unit(5.0))

        val result = reranker.rerank(
            // The unpinned copy is newer but must not evict the pinned one, else
            // the fact would lose its threshold exemption / top-of-list slot.
            candidates = listOf(pinnedOlder to 0.9f, unpinnedNewer to 0.9f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0f,
            limit = 5,
        )

        assertEquals(listOf(1L), result.map { it.first.id })
        assertTrue(result.single().first.isPinned)
    }

    @Test
    fun `given chunks awaiting a re-embed when reranked then they are never collapsed into each other`() {
        // Empty / cross-provider embeddings score 0 against everything, so an
        // unusable vector must not read as "identical to the previous one".
        val a = chunk(id = 1, text = "first", embedding = floatArrayOf())
        val b = chunk(id = 2, text = "second", embedding = floatArrayOf())

        val result = reranker.rerank(
            candidates = listOf(a to 0.9f, b to 0.88f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0f,
            limit = 5,
        )

        assertEquals(listOf(1L, 2L), result.map { it.first.id })
    }

    // endregion

    // region verbatim beats derived summaries

    @Test
    fun `given a summary whose source chunk is in the pool when reranked then the summary is dropped`() {
        // The summary is fresher and scores higher, but chunk 1 is the verbatim
        // fact it was distilled from — the source must win regardless.
        val original = chunk(id = 1, timestamp = now - 90 * DAY)
        val summary = chunk(
            id = 7,
            timestamp = now,
            embedding = unit(90.0),
            source = MemorySource.Compaction(originalChunkIds = listOf(1L, 2L)),
        )

        val result = reranker.rerank(
            candidates = listOf(summary to 0.9f, original to 0.8f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0f,
            limit = 5,
        )

        assertEquals(listOf(1L), result.map { it.first.id })
    }

    @Test
    fun `given a summary restating a verbatim chunk when reranked then the verbatim chunk survives`() {
        // No provenance link (the sources were deleted long ago), but an
        // equivalent fact was learned again since: 2 degrees apart is well above
        // the near-duplicate threshold.
        val verbatim = chunk(id = 1, timestamp = now - 90 * DAY, embedding = unit(0.0))
        val summary = chunk(
            id = 7,
            timestamp = now,
            embedding = unit(2.0),
            source = MemorySource.Compaction(originalChunkIds = listOf(41L, 42L)),
        )

        val result = reranker.rerank(
            candidates = listOf(summary to 0.9f, verbatim to 0.9f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0f,
            limit = 5,
        )

        assertEquals(listOf(1L), result.map { it.first.id })
    }

    @Test
    fun `given a pinned summary whose source is in the pool when reranked then the summary is kept`() {
        // Pinning is an explicit user statement; no automatic rule overrules it.
        val original = chunk(id = 1, timestamp = now - 90 * DAY)
        val summary = chunk(
            id = 7,
            timestamp = now,
            isPinned = true,
            embedding = unit(90.0),
            source = MemorySource.Compaction(originalChunkIds = listOf(1L)),
        )

        val result = reranker.rerank(
            candidates = listOf(summary to 0.9f, original to 0.8f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0f,
            limit = 5,
        )

        assertEquals(listOf(7L, 1L), result.map { it.first.id })
    }

    @Test
    fun `given a summary whose sources are gone when reranked then it is retained`() {
        // The normal case after a compaction pass: the summary is the only copy
        // of those facts, so suppressing it would lose them.
        val unrelated = chunk(id = 1, timestamp = now - 90 * DAY, embedding = unit(0.0))
        val summary = chunk(
            id = 7,
            timestamp = now,
            embedding = unit(90.0),
            source = MemorySource.Compaction(originalChunkIds = listOf(41L, 42L)),
        )

        val result = reranker.rerank(
            candidates = listOf(summary to 0.9f, unrelated to 0.8f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0f,
            limit = 5,
        )

        assertEquals(listOf(7L, 1L), result.map { it.first.id })
    }

    @Test
    fun `given only summaries in the pool when reranked then none is suppressed`() {
        // Two summaries that happen to share provenance ids must not cannibalise
        // each other — the rule prefers verbatim sources, not older summaries.
        val first = chunk(
            id = 7,
            embedding = unit(0.0),
            source = MemorySource.Compaction(originalChunkIds = listOf(1L)),
        )
        val second = chunk(
            id = 8,
            embedding = unit(90.0),
            source = MemorySource.Compaction(originalChunkIds = listOf(7L)),
        )

        val result = reranker.rerank(
            candidates = listOf(first to 0.9f, second to 0.8f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0f,
            limit = 5,
        )

        assertEquals(listOf(7L, 8L), result.map { it.first.id })
    }

    // endregion

    // region ordering and top-K

    @Test
    fun `given the same candidates in a different input order when reranked then the output order is identical`() {
        val candidates = listOf(
            chunk(id = 1, timestamp = now - 2 * DAY) to 0.91f,
            chunk(id = 2, timestamp = now - 40 * DAY) to 0.72f,
            chunk(id = 3, timestamp = now, isPinned = true) to 0.60f,
            chunk(id = 4, timestamp = now - 9 * DAY) to 0.83f,
        )

        val ranked = reranker.rerank(candidates, now, halfLifeDays = 30, threshold = 0.55f, limit = 10)
        val rankedFromReversed =
            reranker.rerank(candidates.reversed(), now, halfLifeDays = 30, threshold = 0.55f, limit = 10)

        assertEquals(listOf(3L, 1L, 4L, 2L), ranked.map { it.first.id })
        assertEquals(ranked.map { it.first.id }, rankedFromReversed.map { it.first.id })
    }

    @Test
    fun `given more survivors than the limit when reranked then the top-K prefix is returned`() {
        val candidates = (1L..5L).map { id ->
            chunk(id = id) to (0.9f - id * 0.05f)
        }

        val capped = reranker.rerank(candidates, now, halfLifeDays = 30, threshold = 0f, limit = 2)
        val uncapped = reranker.rerank(candidates, now, halfLifeDays = 30, threshold = 0f, limit = 100)

        // Early-exit collapse must return exactly the prefix of the full result.
        assertEquals(uncapped.take(2).map { it.first.id }, capped.map { it.first.id })
        assertEquals(listOf(1L, 2L), capped.map { it.first.id })
    }

    @Test
    fun `given a restatement inside the limit window when reranked then the freed slot goes to the next chunk`() {
        // Collapsing has to happen before the cut, otherwise a duplicate would
        // consume one of the K context slots.
        val first = chunk(id = 1, embedding = unit(0.0)) to 0.90f
        val restatement = chunk(id = 2, embedding = unit(8.0)) to 0.89f
        val distinct = chunk(id = 3, embedding = unit(90.0)) to 0.80f

        val result = reranker.rerank(
            candidates = listOf(first, restatement, distinct),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0f,
            limit = 2,
        )

        assertEquals(listOf(1L, 3L), result.map { it.first.id })
    }

    // endregion

    // region edge cases

    @Test
    fun `given a non-positive half-life when reranked then it is coerced and does not divide by zero`() {
        val fresh = chunk(id = 1, timestamp = now)

        val result = reranker.rerank(
            candidates = listOf(fresh to 0.8f),
            nowMillis = now,
            halfLifeDays = 0,
            threshold = 0f,
            limit = 5,
        )

        // halfLife coerced to 1; age 0 -> full bonus -> 0.8 + 0.15.
        assertEquals(0.95f, result.single().second, EPSILON)
    }

    @Test
    fun `given a non-positive limit when reranked then the result is empty`() {
        val result = reranker.rerank(
            candidates = listOf(chunk(id = 1) to 0.9f),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0f,
            limit = 0,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `given no candidates when reranked then the result is empty`() {
        val result = reranker.rerank(
            candidates = emptyList(),
            nowMillis = now,
            halfLifeDays = 30,
            threshold = 0.55f,
            limit = 5,
        )

        assertTrue(result.isEmpty())
    }

    // endregion

    private companion object {
        const val DAY: Long = 86_400_000L
        const val EPSILON: Float = 1e-4f

        /**
         * Angle between the default embeddings of two test chunks. Wide enough
         * that `cos(30°) = 0.866` stays below the near-duplicate threshold, so
         * chunks are distinct unless a test says otherwise.
         */
        const val DISTINCT_ANGLE_DEGREES: Double = 30.0
    }
}
