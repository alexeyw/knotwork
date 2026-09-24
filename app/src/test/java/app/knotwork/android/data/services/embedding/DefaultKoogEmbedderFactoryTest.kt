package app.knotwork.android.data.services.embedding

import app.knotwork.android.data.engine.koogTimeoutsOf
import app.knotwork.android.data.engine.retry.CloudRetryWrapper
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [DefaultKoogEmbedderFactory].
 *
 * The embedding clients are the same Koog classes the chat factory builds, and they used
 * to be built without a timeout config — so Koog's 900 s default held every memory write
 * and memory search against a provider that accepted the connection and went quiet. The
 * value is read back off the constructed client, so dropping the config again reddens
 * this test. The Ollama client keeps no readable copy; `KoogClientTimeoutKonsistTest`
 * covers its construction site.
 */
class DefaultKoogEmbedderFactoryTest {

    private val settingsRepository = mockk<SettingsRepository> {
        // Retries off, so the factory hands back the raw provider client to inspect.
        every { cloudRetryMaxAttempts } returns flowOf(1)
    }

    private val factory = DefaultKoogEmbedderFactory(CloudRetryWrapper(settingsRepository))

    @Test
    fun `given an OpenAI embedding client when built then provider silence is bounded like chat`() = runTest {
        val timeouts = koogTimeoutsOf(factory.openAiClient("sk-test"))

        assertEquals("must bound provider silence at 60s, not Koog's 900s", 60_000L, timeouts.socketTimeoutMillis)
        assertEquals("connect budget", 30_000L, timeouts.connectTimeoutMillis)
    }
}
