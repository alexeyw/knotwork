package app.knotwork.android.domain.services

import app.knotwork.android.domain.services.MemorySupersedeFixture.PairClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Holds the supersede fixture to what it claims: recorded from the embedder the app
 * ships, reproducible with the app's own cosine, labelled consistently — and showing
 * the reason the supersede rule asks a model rather than a threshold.
 */
class MemorySupersedeFixtureTest {

    @Test
    fun `given the bundled embedder when the fixture is read then its vectors were recorded from that model`() {
        // Given
        val model = File("src/main/assets/universal_sentence_encoder.tflite")

        // When
        val sha256 = MessageDigest.getInstance("SHA-256").digest(model.readBytes())
            .joinToString(separator = "") { "%02x".format(it) }

        // Then — a changed model means the vectors describe another geometry: re-record them.
        assertEquals(MemorySupersedeFixture.modelSha256, sha256)
    }

    @Test
    fun `given every pair when its vectors are compared with the app cosine then the recorded cosine is reproduced`() {
        MemorySupersedeFixture.pairs.forEach { pair ->
            // Given
            val stored = MemorySupersedeFixture.vector(pair.stored)
            val incoming = MemorySupersedeFixture.vector(pair.incoming)

            // When
            val cosine = MemoryVectorSimilarity.cosine(stored, incoming)

            // Then
            assertEquals(pair.id, MemorySupersedeFixture.dimension, stored.size)
            assertEquals(pair.id, MemorySupersedeFixture.dimension, incoming.size)
            assertEquals(pair.id, pair.cosine, cosine, COSINE_TOLERANCE)
        }
    }

    @Test
    fun `given the labelled pairs when read then each class carries its verdict and every class is present`() {
        // Given
        val expected = mapOf(
            PairClass.PARAPHRASE to SupersedeVerdict.SAME,
            PairClass.CORRECTION to SupersedeVerdict.UPDATE,
            PairClass.DISTINCT to SupersedeVerdict.DIFFERENT,
            PairClass.UNRELATED to SupersedeVerdict.DIFFERENT,
        )

        // When
        val pairs = MemorySupersedeFixture.pairs

        // Then
        pairs.forEach { assertEquals(it.id, expected.getValue(it.pairClass), it.verdict) }
        assertEquals(PairClass.entries.toSet(), pairs.map { it.pairClass }.toSet())
        assertEquals(pairs.size, pairs.map { it.id }.toSet().size)
    }

    @Test
    fun `given same-shape different facts when compared then they score like paraphrases`() {
        // Given
        val byClass = MemorySupersedeFixture.pairs.groupBy { it.pairClass }
        val threshold = MemoryVectorSimilarity.NEAR_DUPLICATE_THRESHOLD

        // When
        val distinctAbove = byClass.getValue(PairClass.DISTINCT).count { it.cosine >= threshold }
        val lowestParaphrase = byClass.getValue(PairClass.PARAPHRASE).minOf { it.cosine }
        val highestDistinct = byClass.getValue(PairClass.DISTINCT).maxOf { it.cosine }

        // Then — no threshold separates a reworded fact from a different one, so a
        // vector can only find the candidate; the judge has to read the texts.
        assertTrue("distinct pairs above $threshold: $distinctAbove", distinctAbove > 0)
        assertTrue(
            "paraphrase min $lowestParaphrase, distinct max $highestDistinct",
            lowestParaphrase < highestDistinct,
        )
    }

    private companion object {
        /** Vectors are recorded to six decimals; the cosine of the rounded vectors drifts below this. */
        const val COSINE_TOLERANCE = 1e-4f
    }
}
