package app.knotwork.android.data.engine

import app.knotwork.android.data.engine.retry.CloudRetryWrapper
import app.knotwork.android.domain.engine.CloudClientUnavailability
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [KoogClientFactory].
 */
class KoogClientFactoryTest {

    private lateinit var apiKeyRepository: ApiKeyRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var factory: KoogClientFactory

    @Before
    fun setup() {
        apiKeyRepository = mockk()
        settingsRepository = mockk(relaxed = true) {
            every { blockNetworkFromLocalModel } returns MutableStateFlow(false)
            // Disable retry wrapping so each helper returns the raw client these
            // identity/null assertions expect (an attempt budget of 1 = no retries).
            every { cloudRetryMaxAttempts } returns flowOf(1)
        }
        factory = KoogClientFactory(
            apiKeyRepository,
            ModelNetworkGate(settingsRepository),
            CloudRetryWrapper(settingsRepository),
        )
    }

    @Test
    fun `createOpenAIExecutor returns null when key is null`() = runTest {
        coEvery { apiKeyRepository.getOpenAIKey() } returns flowOf(null)
        val executor = factory.createOpenAIExecutor()
        assertNull(executor)
    }

    @Test
    fun `createOpenAIExecutor returns executor when key is present`() = runTest {
        coEvery { apiKeyRepository.getOpenAIKey() } returns flowOf("test-key")
        val executor = factory.createOpenAIExecutor()
        assertNotNull(executor)
    }

    @Test
    fun `createAnthropicExecutor returns executor when key is present`() = runTest {
        coEvery { apiKeyRepository.getAnthropicKey() } returns flowOf("test-key")
        val executor = factory.createAnthropicExecutor()
        assertNotNull(executor)
    }

    @Test
    fun `createGoogleExecutor returns executor when key is present`() = runTest {
        coEvery { apiKeyRepository.getGoogleKey() } returns flowOf("test-key")
        val executor = factory.createGoogleExecutor()
        assertNotNull(executor)
    }

    @Test
    fun `createDeepSeekExecutor returns executor when key is present`() = runTest {
        coEvery { apiKeyRepository.getDeepSeekKey() } returns flowOf("test-key")
        val executor = factory.createDeepSeekExecutor()
        assertNotNull(executor)
    }

    @Test
    fun `createOllamaExecutor returns executor when url is present`() = runTest {
        coEvery { apiKeyRepository.getOllamaBaseUrl() } returns flowOf("http://localhost:11434")
        every { settingsRepository.approvedCleartextOrigins } returns flowOf(setOf("http://localhost:11434"))
        val executor = factory.createOllamaExecutor()
        assertNotNull(executor)
    }

    @Test
    fun `createOllamaExecutor returns null when url is empty`() = runTest {
        coEvery { apiKeyRepository.getOllamaBaseUrl() } returns flowOf("")
        val executor = factory.createOllamaExecutor()
        assertNull(executor)
    }

    @Test
    fun `createOpenAIExecutor returns null when local-only mode is on`() = runTest {
        every { settingsRepository.blockNetworkFromLocalModel } returns MutableStateFlow(true)
        coEvery { apiKeyRepository.getOpenAIKey() } returns flowOf("test-key")
        val executor = factory.createOpenAIExecutor()
        assertNull(executor)
    }

    @Test
    fun `createAnthropicExecutor returns null when local-only mode is on`() = runTest {
        every { settingsRepository.blockNetworkFromLocalModel } returns MutableStateFlow(true)
        coEvery { apiKeyRepository.getAnthropicKey() } returns flowOf("test-key")
        assertNull(factory.createAnthropicExecutor())
    }

    @Test
    fun `Ollama is still reachable in local-only mode`() = runTest {
        every { settingsRepository.blockNetworkFromLocalModel } returns MutableStateFlow(true)
        coEvery { apiKeyRepository.getOllamaBaseUrl() } returns flowOf("http://192.168.1.42:11434")
        every { settingsRepository.approvedCleartextOrigins } returns flowOf(setOf("http://192.168.1.42:11434"))
        assertNotNull(factory.createOllamaExecutor())
    }

    @Test
    fun `given local-only mode on and a public https Ollama url when createOllamaExecutor then returns null`() =
        runTest {
            givenLocalOnlyOllama(url = "https://ollama.example.com")
            assertNull(factory.createOllamaExecutor())
        }

    @Test
    fun `given local-only mode on and a public https IP literal when createOllamaExecutor then returns null`() =
        runTest {
            givenLocalOnlyOllama(url = "https://203.0.113.7:11434")
            assertNull(factory.createOllamaExecutor())
        }

    @Test
    fun `given local-only mode on and a LAN hostname over https when createOllamaExecutor then returns null`() =
        runTest {
            // A name proves nothing about where it resolves, so only address literals count as local.
            givenLocalOnlyOllama(url = "https://ollama.lan:11434")
            assertNull(factory.createOllamaExecutor())
        }

    @Test
    fun `given local-only mode on and a private IP over https when createOllamaExecutor then returns executor`() =
        runTest {
            givenLocalOnlyOllama(url = "https://192.168.1.42:11434")
            assertNotNull(factory.createOllamaExecutor())
        }

    @Test
    fun `given local-only mode on and approved localhost cleartext when createOllamaExecutor then returns executor`() =
        runTest {
            givenLocalOnlyOllama(url = "http://localhost:11434", approved = setOf("http://localhost:11434"))
            assertNotNull(factory.createOllamaExecutor())
        }

    @Test
    fun `given local-only mode off and a public https Ollama url when createOllamaExecutor then returns executor`() =
        runTest {
            coEvery { apiKeyRepository.getOllamaBaseUrl() } returns flowOf("https://ollama.example.com")
            every { settingsRepository.approvedCleartextOrigins } returns flowOf(emptySet())
            assertNotNull(factory.createOllamaExecutor())
        }

    @Test
    fun `given local-only mode on and a saved key when unavailabilityOf a hosted provider then the restriction`() =
        runTest {
            every { settingsRepository.blockNetworkFromLocalModel } returns MutableStateFlow(true)
            coEvery { apiKeyRepository.getGoogleKey() } returns flowOf("test-key")

            assertNull(factory.createGoogleExecutor())
            assertEquals(
                CloudClientUnavailability.BlockedByLocalOnlyMode,
                factory.unavailabilityOf(CloudProvider.GOOGLE),
            )
        }

    @Test
    fun `given no saved key when unavailabilityOf a hosted provider then missing credentials`() = runTest {
        coEvery { apiKeyRepository.getDeepSeekKey() } returns flowOf("   ")

        assertNull(factory.createDeepSeekExecutor())
        assertEquals(CloudClientUnavailability.MissingCredentials, factory.unavailabilityOf(CloudProvider.DEEPSEEK))
    }

    @Test
    fun `given a saved key when unavailabilityOf a hosted provider then none`() = runTest {
        coEvery { apiKeyRepository.getOpenAIKey() } returns flowOf("test-key")

        assertNull(factory.unavailabilityOf(CloudProvider.OPENAI))
    }

    @Test
    fun `given local-only mode on and a public https Ollama when unavailabilityOf then the host is named`() = runTest {
        givenLocalOnlyOllama(url = " https://ollama.example.com ")

        assertEquals(
            CloudClientUnavailability.EndpointNotLocal("ollama.example.com"),
            factory.unavailabilityOf(CloudProvider.OLLAMA),
        )
    }

    @Test
    fun `given local-only mode on and no Ollama url when unavailabilityOf then missing credentials`() = runTest {
        givenLocalOnlyOllama(url = "")

        assertEquals(CloudClientUnavailability.MissingCredentials, factory.unavailabilityOf(CloudProvider.OLLAMA))
    }

    @Test
    fun `given an unapproved LAN cleartext Ollama when unavailabilityOf then the cleartext refusal`() = runTest {
        coEvery { apiKeyRepository.getOllamaBaseUrl() } returns flowOf("http://10.0.0.9:11434")
        every { settingsRepository.approvedCleartextOrigins } returns flowOf(emptySet())

        assertNull(factory.createOllamaExecutor())
        assertTrue(factory.unavailabilityOf(CloudProvider.OLLAMA) is CloudClientUnavailability.CleartextRefused)
    }

    @Test
    fun `given a public https Ollama via the domain entry point in local-only mode when createClient then null`() =
        runTest {
            // The Cloud node and structured output reach Ollama through `createClient`, not the
            // typed helper — the gate has to hold on that path too.
            givenLocalOnlyOllama(url = "https://ollama.example.com")

            assertNull(factory.createClient(CloudProvider.OLLAMA))
        }

    private fun givenLocalOnlyOllama(url: String, approved: Set<String> = emptySet()) {
        every { settingsRepository.blockNetworkFromLocalModel } returns MutableStateFlow(true)
        coEvery { apiKeyRepository.getOllamaBaseUrl() } returns flowOf(url)
        every { settingsRepository.approvedCleartextOrigins } returns flowOf(approved)
    }
}
