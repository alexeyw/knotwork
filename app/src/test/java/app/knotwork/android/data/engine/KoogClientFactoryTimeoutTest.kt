package app.knotwork.android.data.engine

import ai.koog.prompt.executor.clients.retry.RetryConfig
import app.knotwork.android.data.engine.retry.CloudRetryWrapper
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Guards the network deadlines applied to cloud clients.
 *
 * Koog's own default is 900 s for the request and the socket, and this factory used to
 * accept it silently: a stalled provider held a node for a measured 900 033 ms before
 * anything gave up. The assertions below read the timeout config back off each
 * constructed client, so dropping the `settings = …` argument for any provider — the
 * exact regression — restores the 900 s default and reddens the test.
 */
class KoogClientFactoryTimeoutTest {

    private lateinit var apiKeyRepository: ApiKeyRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var factory: KoogClientFactory

    @Before
    fun setup() {
        apiKeyRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)

        every { apiKeyRepository.getOpenAIKey() } returns flowOf("k")
        every { apiKeyRepository.getAnthropicKey() } returns flowOf("k")
        every { apiKeyRepository.getGoogleKey() } returns flowOf("k")
        every { apiKeyRepository.getDeepSeekKey() } returns flowOf("k")
        every { settingsRepository.blockNetworkFromLocalModel } returns flowOf(false)
        // Retries off, so `createClient` hands back the raw provider client to inspect.
        every { settingsRepository.cloudRetryMaxAttempts } returns flowOf(1)

        factory = KoogClientFactory(
            apiKeyRepository = apiKeyRepository,
            modelNetworkGate = ModelNetworkGate(settingsRepository),
            retryWrapper = CloudRetryWrapper(settingsRepository),
        )
    }

    @Test
    fun `given any BYOK provider when a client is built then our deadlines replace Koog defaults`() = runTest {
        val providers = listOf(
            CloudProvider.OPENAI,
            CloudProvider.ANTHROPIC,
            CloudProvider.GOOGLE,
            CloudProvider.DEEPSEEK,
        )

        providers.forEach { provider ->
            val client = factory.createClient(provider)
            assertNotNull("no client built for $provider", client)

            val timeouts = koogTimeoutsOf(client!!)
            assertEquals(
                "$provider must bound provider silence at 60s, not Koog's 900s default",
                60_000L,
                timeouts.socketTimeoutMillis,
            )
            assertEquals("$provider connect budget", 30_000L, timeouts.connectTimeoutMillis)
        }
    }

    @Test
    fun `given a socket timeout when matched against the retry policy then it is not retried`() {
        // What the deadline actually costs the user depends on whether the retry policy
        // treats it as transient: with the configured 3 attempts, a retried socket timeout
        // would mean three full 60 s waits instead of one. Koog's default patterns match
        // several timeout phrasings, so this is asserted against the real message the
        // engine produces rather than assumed.
        val socketTimeout = "Socket timeout has expired " +
            "[url=https://api.deepseek.com/chat/completions, socket_timeout=60000] ms"

        val retryable = RetryConfig.DEFAULT_PATTERNS.any { it.matches(socketTimeout) }

        assertFalse(
            "a socket timeout must fail once, not three times over three minutes",
            retryable,
        )
    }

    @Test
    fun `given a rejected API key when matched against the retry policy then it is not retried`() {
        // Verbatim from the reference device (invalid Google key). Retrying an auth
        // failure spends the whole attempt budget to be told "no" three times, and
        // delays the one message that tells the user what to fix. Koog's patterns match
        // on message text, and a provider's error body is long and quotes numbers, so
        // "400 is not in the retryable list" is asserted rather than assumed.
        val invalidKey = """
            Error from client: GoogleLLMClient
            Message: Expected status code 200 but was 400
            Status code: 400
            Error body:
            {
              "error": {
                "code": 400,
                "message": "API key not valid. Please pass a valid API key.",
                "status": "INVALID_ARGUMENT"
              }
            }
        """.trimIndent()

        val retryable = RetryConfig.DEFAULT_PATTERNS.any { it.matches(invalidKey) }

        assertFalse("an invalid key must be reported at once, not retried", retryable)
    }

    @Test
    fun `given the socket deadline when compared to the request deadline then silence is bounded first`() = runTest {
        // The semantic that matters: a long healthy stream keeps resetting the per-read
        // socket timeout, so it must be the tighter of the two. If the request timeout were
        // the smaller one, a slow-but-alive generation would be cut for being long.
        val timeouts = koogTimeoutsOf(factory.createClient(CloudProvider.DEEPSEEK)!!)

        assertTrue(
            "socket (${timeouts.socketTimeoutMillis}) must be tighter than request " +
                "(${timeouts.requestTimeoutMillis})",
            timeouts.socketTimeoutMillis < timeouts.requestTimeoutMillis,
        )
    }
}
