package app.knotwork.android.presentation.ui.settings

import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.ProviderId
import app.knotwork.android.domain.models.ProviderSummary
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.GenerationSettings
import app.knotwork.android.domain.repositories.LocalModelRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provider rows of Settings → Models, as [ModelsSettingsDelegate] summarises them: one per
 * provider in the order every provider surface uses, and the one state no row had before — a key
 * saved with no model, for a provider that has no default.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelsSettingsDelegateTest {

    private val keys = mutableMapOf<CloudProvider, String?>()
    private val addresses = mutableMapOf<CloudProvider, String?>()
    private val models = mutableMapOf<CloudProvider, String?>()

    private val apiKeyRepository = mockk<ApiKeyRepository> {
        every { getApiKey(any()) } answers { flowOf(keys[firstArg()]) }
        every { getBaseUrl(any()) } answers { flowOf(addresses[firstArg()]) }
        every { getModel(any()) } answers { flowOf(models[firstArg()]) }
    }

    private fun TestScope.summaries(): Map<ProviderId, ProviderSummary> {
        val state = MutableStateFlow(SettingsUiState())
        ModelsSettingsDelegate(
            // A scope on the test's scheduler: `advanceUntilIdle` skips `backgroundScope`.
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            state = state,
            appContext = mockk(relaxed = true),
            generationSettings = mockk<GenerationSettings>(relaxed = true) {
                every { localModelBackend } returns flowOf("cpu")
            },
            apiKeyRepository = apiKeyRepository,
            localModelRepository = mockk<LocalModelRepository>(relaxed = true),
            testBackendUseCase = mockk(relaxed = true),
        )
        advanceUntilIdle()
        return state.value.providers.associateBy { it.id }
    }

    @Test
    fun `given every provider when summarised then each has a row, in the shared order`() = runTest {
        val rows = summaries()

        assertEquals(ProviderId.entries.toList(), rows.keys.toList())
    }

    @Test
    fun `given a key saved and no model for a provider without a default then its row says so`() = runTest {
        keys[CloudProvider.OPENROUTER] = "sk-or-abcdef7c21"
        keys[CloudProvider.GROQ] = "gsk_abcdef"
        models[CloudProvider.GROQ] = "llama-3.3-70b-versatile"
        keys[CloudProvider.OPENAI] = "sk-abcdef3a9f"

        val rows = summaries()

        assertTrue(rows.getValue(ProviderId.OpenRouter).modelMissing)
        assertFalse(rows.getValue(ProviderId.Groq).modelMissing)
        assertFalse("OpenAI has a default model.", rows.getValue(ProviderId.OpenAi).modelMissing)
        assertFalse("Nothing saved, nothing missing yet.", rows.getValue(ProviderId.Anthropic).modelMissing)
    }

    @Test
    fun `given a server the user runs when its address is saved then the row shows the address`() = runTest {
        addresses[CloudProvider.OPENAI_COMPATIBLE] = "http://192.168.1.20:8000/v1"

        val row = summaries().getValue(ProviderId.OpenAiCompatible)

        assertEquals("http://192.168.1.20:8000/v1", row.endpointHint)
        assertTrue("A model is required and none is chosen.", row.modelMissing)
        assertTrue(row.isLanLocal)
    }
}
