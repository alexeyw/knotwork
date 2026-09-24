package app.knotwork.android.data.services.embedding

import ai.koog.prompt.executor.clients.LLMEmbeddingProviderAPI
import app.knotwork.android.data.engine.ModelNetworkGate
import app.knotwork.android.data.repositories.NetworkActivityTrackerImpl
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.EmbeddingException
import app.knotwork.android.domain.services.EmbeddingProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * Unit tests for [CloudEmbeddingProvider].
 *
 * The Koog client is faked via [KoogEmbedderFactory], so no real HTTP request
 * is ever issued. The provider performs no internal fallback — that lives in
 * `EmbeddingProviderResolver` — so a missing key fails loudly here.
 */
class CloudEmbeddingProviderTest {

    private val embedderFactory = mockk<KoogEmbedderFactory>()
    private val apiKeyRepository = mockk<ApiKeyRepository>()
    private val client = mockk<LLMEmbeddingProviderAPI>()
    private val localOnlyMode = MutableStateFlow(false)
    private val settingsRepository = mockk<SettingsRepository> {
        every { blockNetworkFromLocalModel } returns localOnlyMode
    }

    private val networkActivity = NetworkActivityTrackerImpl()

    private lateinit var provider: CloudEmbeddingProvider

    @Before
    fun setup() {
        provider = CloudEmbeddingProvider(
            embedderFactory = embedderFactory,
            apiKeyRepository = apiKeyRepository,
            modelNetworkGate = ModelNetworkGate(settingsRepository),
            networkActivityTracker = networkActivity,
        )
    }

    @Test
    fun `exposes the OpenAI identity and 1536 dimension`() {
        assertEquals(EmbeddingProvider.ID_OPENAI_3_SMALL, provider.id)
        assertEquals(1536, provider.dimension)
    }

    @Test
    fun `isAvailable is true when a key is configured`() = runTest {
        every { apiKeyRepository.getOpenAIKey() } returns flowOf("sk-test")
        assertTrue(provider.isAvailable())
    }

    @Test
    fun `isAvailable is false when the key is missing or blank`() = runTest {
        every { apiKeyRepository.getOpenAIKey() } returns flowOf(null)
        assertFalse(provider.isAvailable())

        every { apiKeyRepository.getOpenAIKey() } returns flowOf("   ")
        assertFalse(provider.isAvailable())
    }

    @Test
    fun `given local-only mode on and a saved key when isAvailable then false`() = runTest {
        // The resolver reads `false` as "fall back to the on-device model", so memory keeps
        // working and no memory text reaches OpenAI while the restriction is on.
        localOnlyMode.value = true
        every { apiKeyRepository.getOpenAIKey() } returns flowOf("sk-test")

        assertFalse(provider.isAvailable())
    }

    @Test
    fun `given local-only mode on and a saved key when embed then refuses without building a client`() = runTest {
        // `isAvailable` and `embed` are separate calls; the restriction can be switched on
        // between them, so the send itself must refuse too.
        localOnlyMode.value = true
        every { apiKeyRepository.getOpenAIKey() } returns flowOf("sk-test")

        val thrown = runCatching { provider.embed("private memory text") }.exceptionOrNull()

        assertTrue("expected EmbeddingException, got $thrown", thrown is EmbeddingException)
        assertTrue(thrown!!.message!!.contains("Block network"))
        coVerify(exactly = 0) { embedderFactory.openAiClient(any()) }
    }

    @Test
    fun `embed with a key calls OpenAI and maps doubles to floats`() = runTest {
        every { apiKeyRepository.getOpenAIKey() } returns flowOf("sk-test")
        coEvery { embedderFactory.openAiClient("sk-test") } returns client
        coEvery { client.embed(any<List<String>>(), any()) } returns
            listOf(listOf(0.1, 0.2, 0.3))

        val result = provider.embed("hello")

        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), result, 1e-6f)
        coVerify(exactly = 1) { client.embed(listOf("hello"), any()) }
    }

    @Test
    fun `given the gate admits the call when embed then the privacy indicator records it`() = runTest {
        // Every memory write and memory search goes through here while the embedding model
        // is OpenAI — traffic the More tab's "no network calls" pill never heard of.
        every { apiKeyRepository.getOpenAIKey() } returns flowOf("sk-test")
        coEvery { embedderFactory.openAiClient("sk-test") } returns client
        coEvery { client.embed(any<List<String>>(), any()) } returns listOf(listOf(0.1))

        provider.embed("hello")

        assertNotNull("memory text left without being recorded", networkActivity.lastOutboundAt.value)
    }

    @Test
    fun `given the gate refuses the call when embed then nothing is recorded`() = runTest {
        localOnlyMode.value = true
        every { apiKeyRepository.getOpenAIKey() } returns flowOf("sk-test")

        runCatching { provider.embed("private memory text") }

        assertNull(networkActivity.lastOutboundAt.value)
    }

    @Test
    fun `embed batch preserves per-input order`() = runTest {
        every { apiKeyRepository.getOpenAIKey() } returns flowOf("sk-test")
        coEvery { embedderFactory.openAiClient(any()) } returns client
        coEvery { client.embed(any<List<String>>(), any()) } returns
            listOf(listOf(1.0), listOf(2.0))

        val result = provider.embed(listOf("a", "b"))

        assertEquals(2, result.size)
        assertArrayEquals(floatArrayOf(1f), result[0], 0f)
        assertArrayEquals(floatArrayOf(2f), result[1], 0f)
    }

    @Test
    fun `embed without a key throws EmbeddingException`() = runTest {
        every { apiKeyRepository.getOpenAIKey() } returns flowOf(null)

        val thrown = runCatching { provider.embed("hello") }.exceptionOrNull()

        assertTrue(thrown is EmbeddingException)
        coVerify(exactly = 0) { embedderFactory.openAiClient(any()) }
    }

    @Test
    fun `embed wraps a client failure in EmbeddingException`() = runTest {
        every { apiKeyRepository.getOpenAIKey() } returns flowOf("sk-test")
        coEvery { embedderFactory.openAiClient(any()) } returns client
        coEvery { client.embed(any<List<String>>(), any()) } throws RuntimeException("boom")

        val thrown = runCatching { provider.embed("hello") }.exceptionOrNull()

        assertTrue(thrown is EmbeddingException)
        assertEquals("boom", thrown?.cause?.message)
    }

    @Test
    fun `embed rethrows CancellationException without wrapping`() = runTest {
        every { apiKeyRepository.getOpenAIKey() } returns flowOf("sk-test")
        coEvery { embedderFactory.openAiClient(any()) } returns client
        coEvery { client.embed(any<List<String>>(), any()) } throws CancellationException("cancelled")

        val thrown = runCatching { provider.embed("hello") }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }

    @Test
    fun `embed empty batch returns empty without a key lookup`() = runTest {
        val result = provider.embed(emptyList())

        assertEquals(0, result.size)
        coVerify(exactly = 0) { embedderFactory.openAiClient(any()) }
    }
}
