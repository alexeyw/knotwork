package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.repositories.ApiKeyRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [AutoProvider] — what a node set to "auto" runs on. */
class AutoProviderTest {

    private fun keys(vararg withKey: CloudProvider) = mockk<ApiKeyRepository> {
        every { getApiKey(any()) } answers { flowOf(if (firstArg<CloudProvider>() in withKey) "key" else null) }
    }

    @Test
    fun `given keys for several providers when resolved then the first of the order wins`() = runTest {
        assertEquals(CloudProvider.ANTHROPIC, AutoProvider.resolve(keys(CloudProvider.OPENAI, CloudProvider.ANTHROPIC)))
        assertEquals(CloudProvider.GOOGLE, AutoProvider.resolve(keys(CloudProvider.DEEPSEEK, CloudProvider.GOOGLE)))
    }

    @Test
    fun `given keys only for providers outside the order when resolved then auto picks none`() = runTest {
        // Adding a provider must not route every "auto" node's prompt to it the moment its key is saved.
        assertNull(
            AutoProvider.resolve(keys(CloudProvider.OPENROUTER, CloudProvider.GROQ, CloudProvider.OPENAI_COMPATIBLE)),
        )
        assertEquals(
            listOf(CloudProvider.GOOGLE, CloudProvider.ANTHROPIC, CloudProvider.OPENAI, CloudProvider.DEEPSEEK),
            AutoProvider.ORDER,
        )
    }
}
