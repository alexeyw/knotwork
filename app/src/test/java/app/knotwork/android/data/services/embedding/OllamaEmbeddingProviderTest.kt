package app.knotwork.android.data.services.embedding

import ai.koog.prompt.executor.clients.LLMEmbeddingProviderAPI
import app.knotwork.android.data.engine.ModelNetworkGate
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * Unit tests for [OllamaEmbeddingProvider].
 *
 * The Koog Ollama client is faked via [KoogEmbedderFactory]; the provider does
 * no internal fallback, so a missing base URL fails loudly here. The network gate
 * is real, so fixtures use an approved private address — a cleartext hostname
 * such as `http://host:11434` is refused by the cleartext rule.
 */
class OllamaEmbeddingProviderTest {

    private val embedderFactory = mockk<KoogEmbedderFactory>()
    private val apiKeyRepository = mockk<ApiKeyRepository>()
    private val client = mockk<LLMEmbeddingProviderAPI>()
    private val localOnlyMode = MutableStateFlow(false)
    private val settingsRepository = mockk<SettingsRepository> {
        every { blockNetworkFromLocalModel } returns localOnlyMode
        every { approvedCleartextOrigins } returns flowOf(setOf(LAN_URL))
    }

    private lateinit var provider: OllamaEmbeddingProvider

    @Before
    fun setup() {
        provider = OllamaEmbeddingProvider(embedderFactory, apiKeyRepository, ModelNetworkGate(settingsRepository))
    }

    @Test
    fun `exposes the Ollama identity and 768 dimension`() {
        assertEquals(EmbeddingProvider.ID_OLLAMA, provider.id)
        assertEquals(768, provider.dimension)
    }

    @Test
    fun `isAvailable is true when a base url is configured`() = runTest {
        every { apiKeyRepository.getOllamaBaseUrl() } returns flowOf(LAN_URL)
        assertTrue(provider.isAvailable())
    }

    @Test
    fun `isAvailable is false when the base url is missing or blank`() = runTest {
        every { apiKeyRepository.getOllamaBaseUrl() } returns flowOf(null)
        assertFalse(provider.isAvailable())

        every { apiKeyRepository.getOllamaBaseUrl() } returns flowOf("  ")
        assertFalse(provider.isAvailable())
    }

    @Test
    fun `embed with a base url calls Ollama and maps doubles to floats`() = runTest {
        every { apiKeyRepository.getOllamaBaseUrl() } returns flowOf(LAN_URL)
        coEvery { embedderFactory.ollamaClient(LAN_URL) } returns client
        coEvery { client.embed(any<List<String>>(), any()) } returns
            listOf(listOf(0.5, 0.25))

        val result = provider.embed("hi")

        assertArrayEquals(floatArrayOf(0.5f, 0.25f), result, 1e-6f)
        coVerify(exactly = 1) { client.embed(listOf("hi"), any()) }
    }

    @Test
    fun `given local-only mode on and a public https url when isAvailable then false`() = runTest {
        localOnlyMode.value = true
        every { apiKeyRepository.getOllamaBaseUrl() } returns flowOf(PUBLIC_HTTPS_URL)

        assertFalse(provider.isAvailable())
    }

    @Test
    fun `given local-only mode on and a public https url when embed then refuses without building a client`() =
        runTest {
            localOnlyMode.value = true
            every { apiKeyRepository.getOllamaBaseUrl() } returns flowOf(PUBLIC_HTTPS_URL)

            val thrown = runCatching { provider.embed("private memory text") }.exceptionOrNull()

            assertTrue("expected EmbeddingException, got $thrown", thrown is EmbeddingException)
            assertTrue(thrown!!.message!!.contains("ollama.example.com"))
            coVerify(exactly = 0) { embedderFactory.ollamaClient(any()) }
        }

    @Test
    fun `given local-only mode on and a private address over https when isAvailable then true`() = runTest {
        localOnlyMode.value = true
        every { apiKeyRepository.getOllamaBaseUrl() } returns flowOf("https://192.168.1.2:11434")

        assertTrue(provider.isAvailable())
    }

    @Test
    fun `given local-only mode off and a public https url when isAvailable then true`() = runTest {
        every { apiKeyRepository.getOllamaBaseUrl() } returns flowOf(PUBLIC_HTTPS_URL)

        assertTrue(provider.isAvailable())
    }

    @Test
    fun `given cleartext to a public address when embed then refuses without building a client`() = runTest {
        // The embedding client used to skip the cleartext rule the chat client applies,
        // so memory text could travel unencrypted to a public host.
        every { apiKeyRepository.getOllamaBaseUrl() } returns flowOf("http://203.0.113.7:11434")

        assertFalse(provider.isAvailable())
        val thrown = runCatching { provider.embed("private memory text") }.exceptionOrNull()

        assertTrue("expected EmbeddingException, got $thrown", thrown is EmbeddingException)
        coVerify(exactly = 0) { embedderFactory.ollamaClient(any()) }
    }

    @Test
    fun `given unapproved cleartext to a private address when isAvailable then false`() = runTest {
        every { apiKeyRepository.getOllamaBaseUrl() } returns flowOf("http://10.0.0.9:11434")

        assertFalse(provider.isAvailable())
    }

    @Test
    fun `embed without a base url throws EmbeddingException`() = runTest {
        every { apiKeyRepository.getOllamaBaseUrl() } returns flowOf(null)

        val thrown = runCatching { provider.embed("hi") }.exceptionOrNull()

        assertTrue(thrown is EmbeddingException)
        coVerify(exactly = 0) { embedderFactory.ollamaClient(any()) }
    }

    @Test
    fun `embed wraps a client failure in EmbeddingException`() = runTest {
        every { apiKeyRepository.getOllamaBaseUrl() } returns flowOf(LAN_URL)
        coEvery { embedderFactory.ollamaClient(any()) } returns client
        coEvery { client.embed(any<List<String>>(), any()) } throws RuntimeException("down")

        val thrown = runCatching { provider.embed("hi") }.exceptionOrNull()

        assertTrue(thrown is EmbeddingException)
    }

    @Test
    fun `embed rethrows CancellationException without wrapping`() = runTest {
        every { apiKeyRepository.getOllamaBaseUrl() } returns flowOf(LAN_URL)
        coEvery { embedderFactory.ollamaClient(any()) } returns client
        coEvery { client.embed(any<List<String>>(), any()) } throws CancellationException("cancelled")

        val thrown = runCatching { provider.embed("hi") }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }

    @Test
    fun `embed empty batch returns empty without a url lookup`() = runTest {
        val result = provider.embed(emptyList())

        assertEquals(0, result.size)
        coVerify(exactly = 0) { embedderFactory.ollamaClient(any()) }
    }

    private companion object {
        /** An approved private cleartext address — admitted by both network rules. */
        const val LAN_URL = "http://192.168.1.2:11434"

        /** An encrypted address off the user's network — admitted only with the restriction off. */
        const val PUBLIC_HTTPS_URL = "https://ollama.example.com"
    }
}
