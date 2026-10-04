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
            every { approvedCleartextOrigins } returns flowOf(emptySet())
            // Disable retry wrapping so `createClient` returns the raw client these
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
    fun `createClient(OPENAI) returns null when key is null`() = runTest {
        coEvery { apiKeyRepository.getApiKey(CloudProvider.OPENAI) } returns flowOf(null)
        val executor = factory.createClient(CloudProvider.OPENAI)
        assertNull(executor)
    }

    @Test
    fun `createClient(OPENAI) returns executor when key is present`() = runTest {
        coEvery { apiKeyRepository.getApiKey(CloudProvider.OPENAI) } returns flowOf("test-key")
        val executor = factory.createClient(CloudProvider.OPENAI)
        assertNotNull(executor)
    }

    @Test
    fun `createClient(ANTHROPIC) returns executor when key is present`() = runTest {
        coEvery { apiKeyRepository.getApiKey(CloudProvider.ANTHROPIC) } returns flowOf("test-key")
        val executor = factory.createClient(CloudProvider.ANTHROPIC)
        assertNotNull(executor)
    }

    @Test
    fun `createClient(GOOGLE) returns executor when key is present`() = runTest {
        coEvery { apiKeyRepository.getApiKey(CloudProvider.GOOGLE) } returns flowOf("test-key")
        val executor = factory.createClient(CloudProvider.GOOGLE)
        assertNotNull(executor)
    }

    @Test
    fun `createClient(DEEPSEEK) returns executor when key is present`() = runTest {
        coEvery { apiKeyRepository.getApiKey(CloudProvider.DEEPSEEK) } returns flowOf("test-key")
        val executor = factory.createClient(CloudProvider.DEEPSEEK)
        assertNotNull(executor)
    }

    @Test
    fun `createClient(OLLAMA) returns executor when url is present`() = runTest {
        coEvery { apiKeyRepository.getBaseUrl(CloudProvider.OLLAMA) } returns flowOf("http://localhost:11434")
        every { settingsRepository.approvedCleartextOrigins } returns flowOf(setOf("http://localhost:11434"))
        val executor = factory.createClient(CloudProvider.OLLAMA)
        assertNotNull(executor)
    }

    @Test
    fun `createClient(OLLAMA) returns null when url is empty`() = runTest {
        coEvery { apiKeyRepository.getBaseUrl(CloudProvider.OLLAMA) } returns flowOf("")
        val executor = factory.createClient(CloudProvider.OLLAMA)
        assertNull(executor)
    }

    @Test
    fun `createClient(OPENAI) returns null when local-only mode is on`() = runTest {
        every { settingsRepository.blockNetworkFromLocalModel } returns MutableStateFlow(true)
        coEvery { apiKeyRepository.getApiKey(CloudProvider.OPENAI) } returns flowOf("test-key")
        val executor = factory.createClient(CloudProvider.OPENAI)
        assertNull(executor)
    }

    @Test
    fun `createClient(ANTHROPIC) returns null when local-only mode is on`() = runTest {
        every { settingsRepository.blockNetworkFromLocalModel } returns MutableStateFlow(true)
        coEvery { apiKeyRepository.getApiKey(CloudProvider.ANTHROPIC) } returns flowOf("test-key")
        assertNull(factory.createClient(CloudProvider.ANTHROPIC))
    }

    @Test
    fun `Ollama is still reachable in local-only mode`() = runTest {
        every { settingsRepository.blockNetworkFromLocalModel } returns MutableStateFlow(true)
        coEvery { apiKeyRepository.getBaseUrl(CloudProvider.OLLAMA) } returns flowOf("http://192.168.1.42:11434")
        every { settingsRepository.approvedCleartextOrigins } returns flowOf(setOf("http://192.168.1.42:11434"))
        assertNotNull(factory.createClient(CloudProvider.OLLAMA))
    }

    @Test
    fun `given local-only mode on and a public https Ollama url when createClient(OLLAMA) then returns null`() =
        runTest {
            givenLocalOnlyOllama(url = "https://ollama.example.com")
            assertNull(factory.createClient(CloudProvider.OLLAMA))
        }

    @Test
    fun `given local-only mode on and a public https IP literal when createClient(OLLAMA) then returns null`() =
        runTest {
            givenLocalOnlyOllama(url = "https://203.0.113.7:11434")
            assertNull(factory.createClient(CloudProvider.OLLAMA))
        }

    @Test
    fun `given local-only mode on and a LAN hostname over https when createClient(OLLAMA) then returns null`() =
        runTest {
            // A name proves nothing about where it resolves, so only address literals count as local.
            givenLocalOnlyOllama(url = "https://ollama.lan:11434")
            assertNull(factory.createClient(CloudProvider.OLLAMA))
        }

    @Test
    fun `given local-only mode on and a private IP over https when createClient(OLLAMA) then returns executor`() =
        runTest {
            givenLocalOnlyOllama(url = "https://192.168.1.42:11434")
            assertNotNull(factory.createClient(CloudProvider.OLLAMA))
        }

    @Test
    fun `given local-only mode on and approved localhost cleartext when createClient(OLLAMA) then returns executor`() =
        runTest {
            givenLocalOnlyOllama(url = "http://localhost:11434", approved = setOf("http://localhost:11434"))
            assertNotNull(factory.createClient(CloudProvider.OLLAMA))
        }

    @Test
    fun `given local-only mode off and a public https Ollama url when createClient(OLLAMA) then returns executor`() =
        runTest {
            coEvery { apiKeyRepository.getBaseUrl(CloudProvider.OLLAMA) } returns flowOf("https://ollama.example.com")
            every { settingsRepository.approvedCleartextOrigins } returns flowOf(emptySet())
            assertNotNull(factory.createClient(CloudProvider.OLLAMA))
        }

    @Test
    fun `given local-only mode on and a saved key when unavailabilityOf a hosted provider then the restriction`() =
        runTest {
            every { settingsRepository.blockNetworkFromLocalModel } returns MutableStateFlow(true)
            coEvery { apiKeyRepository.getApiKey(CloudProvider.GOOGLE) } returns flowOf("test-key")

            assertNull(factory.createClient(CloudProvider.GOOGLE))
            assertEquals(
                CloudClientUnavailability.BlockedByLocalOnlyMode,
                factory.unavailabilityOf(CloudProvider.GOOGLE),
            )
        }

    @Test
    fun `given no saved key when unavailabilityOf a hosted provider then missing credentials`() = runTest {
        coEvery { apiKeyRepository.getApiKey(CloudProvider.DEEPSEEK) } returns flowOf("   ")

        assertNull(factory.createClient(CloudProvider.DEEPSEEK))
        assertEquals(CloudClientUnavailability.MissingCredentials, factory.unavailabilityOf(CloudProvider.DEEPSEEK))
    }

    @Test
    fun `given a saved key when unavailabilityOf a hosted provider then none`() = runTest {
        coEvery { apiKeyRepository.getApiKey(CloudProvider.OPENAI) } returns flowOf("test-key")

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
        coEvery { apiKeyRepository.getBaseUrl(CloudProvider.OLLAMA) } returns flowOf("http://10.0.0.9:11434")
        every { settingsRepository.approvedCleartextOrigins } returns flowOf(emptySet())

        assertNull(factory.createClient(CloudProvider.OLLAMA))
        assertTrue(factory.unavailabilityOf(CloudProvider.OLLAMA) is CloudClientUnavailability.CleartextRefused)
    }

    @Test
    fun `given OpenRouter with a key but no model when asked then no client, and the cause is the model`() = runTest {
        // No default model ships for it: a client without one would only fail at the server.
        coEvery { apiKeyRepository.getApiKey(CloudProvider.OPENROUTER) } returns flowOf("sk-or")
        coEvery { apiKeyRepository.getModel(CloudProvider.OPENROUTER) } returns flowOf(null)

        assertNull(factory.createClient(CloudProvider.OPENROUTER))
        assertEquals(CloudClientUnavailability.MissingModel, factory.unavailabilityOf(CloudProvider.OPENROUTER))
    }

    @Test
    fun `given Groq with a key and a model when asked then a client is built`() = runTest {
        coEvery { apiKeyRepository.getApiKey(CloudProvider.GROQ) } returns flowOf("gsk")
        coEvery { apiKeyRepository.getModel(CloudProvider.GROQ) } returns flowOf("llama-3.3-70b-versatile")

        assertNotNull(factory.createClient(CloudProvider.GROQ))
        assertNull(factory.unavailabilityOf(CloudProvider.GROQ))
    }

    @Test
    fun `given Groq with a model but no key when asked then the cause is the key, not the model`() = runTest {
        coEvery { apiKeyRepository.getApiKey(CloudProvider.GROQ) } returns flowOf(" ")
        coEvery { apiKeyRepository.getModel(CloudProvider.GROQ) } returns flowOf(null)

        assertEquals(CloudClientUnavailability.MissingCredentials, factory.unavailabilityOf(CloudProvider.GROQ))
    }

    @Test
    fun `given the restriction on when a hosted OpenAI-compatible provider is asked for then it is blocked`() =
        runTest {
            every { settingsRepository.blockNetworkFromLocalModel } returns MutableStateFlow(true)
            coEvery { apiKeyRepository.getApiKey(CloudProvider.OPENROUTER) } returns flowOf("sk-or")
            coEvery { apiKeyRepository.getModel(CloudProvider.OPENROUTER) } returns flowOf("m")

            assertNull(factory.createClient(CloudProvider.OPENROUTER))
            assertEquals(
                CloudClientUnavailability.BlockedByLocalOnlyMode,
                factory.unavailabilityOf(CloudProvider.OPENROUTER),
            )
        }

    @Test
    fun `given a server the user runs with no address when asked then the cause is the address`() = runTest {
        coEvery { apiKeyRepository.getBaseUrl(CloudProvider.OPENAI_COMPATIBLE) } returns flowOf(null)

        val cause = factory.unavailabilityOf(CloudProvider.OPENAI_COMPATIBLE)

        assertEquals(CloudClientUnavailability.MissingCredentials, cause)
        assertTrue(cause!!.message(CloudProvider.OPENAI_COMPATIBLE).contains("no server address"))
    }

    @Test
    fun `given a server the user runs without a key when asked then the missing key is not a cause`() = runTest {
        coEvery { apiKeyRepository.getBaseUrl(CloudProvider.OPENAI_COMPATIBLE) } returns
            flowOf("http://192.168.1.20:8000/v1")
        coEvery { apiKeyRepository.getApiKey(CloudProvider.OPENAI_COMPATIBLE) } returns flowOf(null)
        coEvery { apiKeyRepository.getModel(CloudProvider.OPENAI_COMPATIBLE) } returns flowOf("qwen")
        every { settingsRepository.approvedCleartextOrigins } returns flowOf(setOf("http://192.168.1.20:8000"))

        assertNotNull(factory.createClient(CloudProvider.OPENAI_COMPATIBLE))
        assertNull(factory.unavailabilityOf(CloudProvider.OPENAI_COMPATIBLE))
    }

    @Test
    fun `given a server the user runs at a host name while restricted when asked then it is not local`() = runTest {
        // D6: the Ollama rule — a name says nothing about where DNS will send the request.
        every { settingsRepository.blockNetworkFromLocalModel } returns MutableStateFlow(true)
        coEvery { apiKeyRepository.getBaseUrl(CloudProvider.OPENAI_COMPATIBLE) } returns
            flowOf("https://llm.home.lan/v1")
        coEvery { apiKeyRepository.getModel(CloudProvider.OPENAI_COMPATIBLE) } returns flowOf("qwen")

        assertEquals(
            CloudClientUnavailability.EndpointNotLocal("llm.home.lan"),
            factory.unavailabilityOf(CloudProvider.OPENAI_COMPATIBLE),
        )
    }

    @Test
    fun `given a server the user runs over http on the internet when asked then cleartext is refused`() = runTest {
        coEvery { apiKeyRepository.getBaseUrl(CloudProvider.OPENAI_COMPATIBLE) } returns flowOf("http://203.0.113.7/v1")
        coEvery { apiKeyRepository.getModel(CloudProvider.OPENAI_COMPATIBLE) } returns flowOf("qwen")

        assertNull(factory.createClient(CloudProvider.OPENAI_COMPATIBLE))
        assertTrue(
            factory.unavailabilityOf(CloudProvider.OPENAI_COMPATIBLE) is CloudClientUnavailability.CleartextRefused,
        )
    }

    @Test
    fun `given a server the user runs with an address but no model when asked then the cause is the model`() = runTest {
        coEvery { apiKeyRepository.getBaseUrl(CloudProvider.OPENAI_COMPATIBLE) } returns
            flowOf("https://192.168.1.20/v1")
        coEvery { apiKeyRepository.getModel(CloudProvider.OPENAI_COMPATIBLE) } returns flowOf(null)

        assertEquals(
            CloudClientUnavailability.MissingModel,
            factory.unavailabilityOf(CloudProvider.OPENAI_COMPATIBLE),
        )
    }

    private fun givenLocalOnlyOllama(url: String, approved: Set<String> = emptySet()) {
        every { settingsRepository.blockNetworkFromLocalModel } returns MutableStateFlow(true)
        coEvery { apiKeyRepository.getBaseUrl(CloudProvider.OLLAMA) } returns flowOf(url)
        every { settingsRepository.approvedCleartextOrigins } returns flowOf(approved)
    }
}
