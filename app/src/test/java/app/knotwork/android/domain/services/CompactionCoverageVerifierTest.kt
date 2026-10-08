package app.knotwork.android.domain.services

import app.knotwork.android.domain.models.MemoryChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Unit tests for [CompactionCoverageVerifier].
 *
 * Embeddings are unit vectors at explicit angles, so "the summary sits as
 * centrally as the cluster's centroid" is exact arithmetic rather than a
 * plausible-looking float: the cosine of two such vectors is the cosine of the
 * angle between them.
 */
class CompactionCoverageVerifierTest {

    private val verifier = CompactionCoverageVerifier()

    /** A unit vector at [degrees] in the first two dimensions. */
    private fun unit(degrees: Double): FloatArray {
        val radians = degrees * PI / 180.0
        return floatArrayOf(cos(radians).toFloat(), sin(radians).toFloat())
    }

    private fun chunk(id: Long, embedding: FloatArray) =
        MemoryChunk(id = id, text = "fact-$id", embedding = embedding, timestamp = 0L)

    @Test
    fun `given a summary at the cluster centre when verified then every member is covered`() {
        // 15 degrees apart: close, but distinguishable (cos 15 = 0.966 < 0.98).
        val members = listOf(
            chunk(1, unit(0.0)),
            chunk(2, unit(15.0)),
            chunk(3, unit(30.0)),
        )

        val verdict = verifier.verify(members, summaryEmbedding = unit(15.0))

        assertEquals(listOf(1L, 2L, 3L), verdict.covered.map { it.id })
        assertTrue(verdict.uncovered.isEmpty())
    }

    @Test
    fun `given a summary that drifted off topic when verified then no member is covered`() {
        val members = listOf(
            chunk(1, unit(0.0)),
            chunk(2, unit(15.0)),
            chunk(3, unit(30.0)),
        )

        // 30 degrees off the far edge of the cluster: the model answered about
        // something else, and the originals must survive.
        val verdict = verifier.verify(members, summaryEmbedding = unit(60.0))

        assertTrue(verdict.covered.isEmpty())
        assertEquals(listOf(1L, 2L, 3L), verdict.uncovered.map { it.id })
    }

    @Test
    fun `given a summary that represents part of the cluster when verified then only that part is covered`() {
        // Two neighbouring facts plus an unrelated one dragged into the cluster:
        // centroid at 32.6 degrees, so the 90-degree member scores 0.54 against it
        // and the summary's 0.13 falls far short.
        val members = listOf(
            chunk(1, unit(0.0)),
            chunk(2, unit(15.0)),
            chunk(3, unit(90.0)),
        )

        val verdict = verifier.verify(members, summaryEmbedding = unit(7.5))

        assertEquals(listOf(1L, 2L), verdict.covered.map { it.id })
        assertEquals(listOf(3L), verdict.uncovered.map { it.id })
    }

    @Test
    fun `given a summary slightly less central than the centroid when verified then the margin admits it`() {
        // A summary is a rewrite, not an average, so it lands a little further
        // out than the centroid; within the tolerance that still counts.
        val members = listOf(
            chunk(1, unit(0.0)),
            chunk(2, unit(15.0)),
            chunk(3, unit(30.0)),
        )

        // The furthest member sits 22 degrees away: cos(22) = 0.927 against a
        // centroid similarity of 0.966 — a gap of 0.039, admitted only because
        // of the 0.05 tolerance.
        val verdict = verifier.verify(members, summaryEmbedding = unit(22.0))

        assertEquals(listOf(1L, 2L, 3L), verdict.covered.map { it.id })
    }

    @Test
    fun `given a member with no usable vector when verified then it is not covered`() {
        // Absence of evidence must never authorise a deletion.
        val members = listOf(
            chunk(1, unit(0.0)),
            chunk(2, unit(15.0)),
            chunk(3, FloatArray(0)),
        )

        val verdict = verifier.verify(members, summaryEmbedding = unit(7.5))

        assertEquals(listOf(1L, 2L), verdict.covered.map { it.id })
        assertEquals(listOf(3L), verdict.uncovered.map { it.id })
    }

    @Test
    fun `given a member from another embedding space when verified then it is not covered`() {
        // A chunk awaiting the background re-embed: its vector cannot be
        // compared with the cluster's at all.
        val members = listOf(
            chunk(1, unit(0.0)),
            chunk(2, unit(15.0)),
            chunk(3, floatArrayOf(1f, 0f, 0f)),
        )

        val verdict = verifier.verify(members, summaryEmbedding = unit(7.5))

        assertEquals(listOf(1L, 2L), verdict.covered.map { it.id })
        assertEquals(listOf(3L), verdict.uncovered.map { it.id })
    }

    @Test
    fun `given a summary embedded by another provider when verified then nothing is covered`() {
        // Cross-space vectors score 0 against every member, so the gate fails
        // safe and the pass deletes nothing.
        val members = listOf(
            chunk(1, unit(0.0)),
            chunk(2, unit(15.0)),
            chunk(3, unit(30.0)),
        )

        val verdict = verifier.verify(members, summaryEmbedding = floatArrayOf(1f, 0f, 0f))

        assertTrue(verdict.covered.isEmpty())
        assertEquals(3, verdict.uncovered.size)
    }

    @Test
    fun `given an empty cluster when verified then the verdict is empty`() {
        val verdict = verifier.verify(members = emptyList(), summaryEmbedding = unit(0.0))

        assertTrue(verdict.covered.isEmpty())
        assertTrue(verdict.uncovered.isEmpty())
    }

    // ─── Members the embedder cannot tell apart ────────────────────────────

    /** A chunk carrying the vector the bundled USE file recorded for [text]. */
    private fun recorded(id: Long, text: String) =
        MemoryChunk(id = id, text = text, embedding = MemorySupersedeFixture.vector(text), timestamp = 0L)

    @Test
    fun `given two members the embedder cannot tell apart when verified then neither is covered`() {
        // A summary close to one is just as close to the other, so the vectors cannot
        // say which of the two it carries. The member it can tell apart is judged as before.
        val members = listOf(
            chunk(1, unit(0.0)),
            chunk(2, unit(0.0)),
            chunk(3, unit(15.0)),
        )

        val verdict = verifier.verify(members, summaryEmbedding = unit(7.5))

        assertEquals(listOf(3L), verdict.covered.map { it.id })
        assertEquals(listOf(1L, 2L), verdict.uncovered.map { it.id })
    }

    @Test
    fun `given unrelated Russian facts on the on-device embedder when verified then none is covered`() {
        // The built-in encoder folds Russian text into one direction: these three
        // unrelated facts and a summary that carries none of them all sit at 0.99.
        val members = listOf(
            recorded(1, "Живёт в Берлине"),
            recorded(2, "Аллергия на арахис"),
            recorded(3, "Работает программистом"),
        )

        val verdict = verifier.verify(members, summaryEmbedding = MemorySupersedeFixture.vector("Кошку зовут Мурка"))

        assertTrue(verdict.covered.isEmpty())
    }

    @Test
    fun `given Russian facts clustered with an English one when verified then no Russian fact is covered`() {
        val members = listOf(
            recorded(1, "Живёт в Берлине"),
            recorded(2, "Аллергия на арахис"),
            recorded(3, "Работает программистом"),
            recorded(4, "Lives in Berlin"),
        )

        val verdict = verifier.verify(members, summaryEmbedding = MemorySupersedeFixture.vector("Любит горы и походы"))

        assertTrue(verdict.covered.none { it.id in setOf(1L, 2L, 3L) })
    }
}
