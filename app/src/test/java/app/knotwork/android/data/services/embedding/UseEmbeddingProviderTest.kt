package app.knotwork.android.data.services.embedding

import app.knotwork.android.domain.engine.TextEmbeddingEngine
import app.knotwork.android.domain.services.EmbeddingProvider
import app.knotwork.android.domain.services.MemorySupersedeFixture
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [UseEmbeddingProvider].
 */
class UseEmbeddingProviderTest {

    private val engine = mockk<TextEmbeddingEngine>()
    private lateinit var provider: UseEmbeddingProvider

    @Before
    fun setup() {
        provider = UseEmbeddingProvider(engine)
    }

    @Test
    fun `exposes the on-device identity and the dimension the bundled model returns`() {
        assertEquals(EmbeddingProvider.ID_USE, provider.id)
        // Recorded from the shipped model file (the fixture pins its SHA-256); the
        // 512 this test once asserted was assumed, not read from the file.
        assertEquals(MemorySupersedeFixture.dimension, provider.dimension)
        assertTrue(provider.displayName.isNotBlank())
    }

    @Test
    fun `embed single delegates to the engine`() = runTest {
        val expected = floatArrayOf(0.1f, 0.2f, 0.3f)
        coEvery { engine.generateEmbedding("hello") } returns expected

        val result = provider.embed("hello")

        assertArrayEquals(expected, result, 0f)
        coVerify(exactly = 1) { engine.generateEmbedding("hello") }
    }

    @Test
    fun `embed batch maps each text through the engine in order`() = runTest {
        coEvery { engine.generateEmbedding("a") } returns floatArrayOf(1f)
        coEvery { engine.generateEmbedding("b") } returns floatArrayOf(2f)

        val result = provider.embed(listOf("a", "b"))

        assertEquals(2, result.size)
        assertArrayEquals(floatArrayOf(1f), result[0], 0f)
        assertArrayEquals(floatArrayOf(2f), result[1], 0f)
    }

    @Test
    fun `embed empty batch returns empty without touching the engine`() = runTest {
        val result = provider.embed(emptyList())

        assertTrue(result.isEmpty())
        coVerify(exactly = 0) { engine.generateEmbedding(any()) }
    }
}
