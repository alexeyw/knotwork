package app.knotwork.android.domain.repositories

import app.knotwork.android.domain.models.CloudProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the definitions [ApiKeyRepository] carries beside its accessors:
 * [requiredCredential] and [isConfigured].
 */
class ApiKeyRepositoryTest {

    private fun repository(key: String? = null, address: String? = null, model: String? = null) =
        mockk<ApiKeyRepository> {
            every { getApiKey(any()) } returns flowOf(key)
            every { getBaseUrl(any()) } returns flowOf(address)
            every { getModel(any()) } returns flowOf(model)
        }

    @Test
    fun `given a hosted provider with a default model when only its key is saved then it is configured`() = runTest {
        assertTrue(repository(key = "sk").isConfigured(CloudProvider.OPENAI))
        assertFalse(repository(key = " ").isConfigured(CloudProvider.OPENAI))
    }

    @Test
    fun `given a hosted provider without a default model when no model is chosen then it is not configured`() =
        runTest {
            assertFalse(repository(key = "sk-or").isConfigured(CloudProvider.OPENROUTER))
            assertTrue(repository(key = "sk-or", model = "m").isConfigured(CloudProvider.OPENROUTER))
        }

    @Test
    fun `given a server the user runs when only its address and model are saved then it is configured`() = runTest {
        // The key of such a server is optional; the address decides.
        val own = CloudProvider.OPENAI_COMPATIBLE
        val address = "http://10.0.0.2:8000/v1"

        assertTrue(repository(address = address, model = "m").isConfigured(own))
        assertFalse(repository(key = "sk", model = "m").isConfigured(own))
        assertFalse(repository(address = address).isConfigured(own))
    }

    @Test
    fun `given Ollama when only its address is saved then it is configured, its model having a default`() = runTest {
        assertTrue(repository(address = "http://10.0.0.2:11434").isConfigured(CloudProvider.OLLAMA))
    }
}
