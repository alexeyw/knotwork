package app.knotwork.android.presentation.ui.settings.provider

import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Coverage for the projection that feeds the catalog's provider detail surface.
 *
 * The screen's composition now has Roborazzi baselines, but the per-provider shape
 * is the part they cannot see: which provider gets an API key, which gets a base
 * URL, which offers a model list. It is also where a newly added provider would
 * be wired wrong, and the failure would be silent — a field simply missing.
 */
@RunWith(RobolectricTestRunner::class)
class ProviderDetailProjectionTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `given a key-based provider then it carries a key, a model list and no Ollama inputs`() {
        val state = ProviderDetailUiState(apiKey = "sk-1", model = "gpt-4o")
            .toViewState(ProviderId.OpenAi, context)

        assertEquals("sk-1", state.apiKey)
        assertEquals("gpt-4o", state.model)
        assertTrue("A key-based provider offers a model list.", state.availableModels.isNotEmpty())
        assertNull(state.ollama)
    }

    @Test
    fun `given every key-based provider then each shows the key under its own name`() {
        // The state holds the bound provider's values only, so the remaining per-provider
        // part is the name — the label of the key field and the screen title.
        ProviderId.entries.filter { it.cloudProvider.usesApiKey }.forEach { provider ->
            val state = ProviderDetailUiState(apiKey = "key").toViewState(provider, context)

            assertEquals("$provider hid its key field.", "key", state.apiKey)
            assertEquals(provider.displayName(), state.providerLabel)
            assertTrue("$provider's key field is not named.", state.apiKeyLabel.contains(provider.displayName()))
        }
    }

    @Test
    fun `given Ollama then the key is null rather than empty`() {
        // `null` hides the field; `""` would show an empty one, which reads as a
        // key the user forgot. Ollama runs LAN-local without authentication —
        // even a stray key value in the state must not surface.
        val state = ProviderDetailUiState(apiKey = "stray", model = "llama3.1", baseUrl = "http://host:11434")
            .toViewState(ProviderId.Ollama, context)

        assertNull(state.apiKey)
        assertEquals("llama3.1", state.model)
        assertTrue("Ollama's model is typed, not picked.", state.availableModels.isEmpty())
        assertEquals("http://host:11434", assertNotNull(state.ollama).let { state.ollama!!.baseUrl })
    }

    @Test
    fun `given an invalid base URL then the error rides on the Ollama inputs`() {
        val state = ProviderDetailUiState(baseUrlInvalid = true).toViewState(ProviderId.Ollama, context)

        assertNotNull("The error belongs under the field, not in a dialog.", state.ollama?.baseUrlValidationError)
    }

    @Test
    fun `given a valid base URL then no error is carried`() {
        val state = ProviderDetailUiState(baseUrlInvalid = false).toViewState(ProviderId.Ollama, context)

        assertNull(state.ollama?.baseUrlValidationError)
    }

    @Test
    fun `given a pending cleartext origin then the banner names it`() {
        val state = ProviderDetailUiState(cleartextConsentOrigin = "192.168.1.24:11434")
            .toViewState(ProviderId.Ollama, context)

        assertTrue(
            "The sentence has to name the address being allowed.",
            state.cleartextConsent?.body?.contains("192.168.1.24:11434") == true,
        )
    }

    @Test
    fun `given no pending origin then there is no banner`() {
        val state = ProviderDetailUiState().toViewState(ProviderId.Ollama, context)

        assertNull(state.cleartextConsent)
    }

    @Test
    fun `given the retry policy then its bounds match the settings defaults`() {
        // The bounds travel with the state precisely so they cannot drift from
        // what the store coerces against. This is the assertion that keeps that
        // true.
        val retry = ProviderDetailUiState().cloudRetryViewState(context)

        assertEquals(SettingsDefaults.CLOUD_RETRY_MAX_ATTEMPTS_MIN.toFloat(), retry.attemptsRange.start, 0f)
        assertEquals(
            SettingsDefaults.CLOUD_RETRY_MAX_ATTEMPTS_MAX.toFloat(),
            retry.attemptsRange.endInclusive,
            0f,
        )
        assertEquals(SettingsDefaults.CLOUD_RETRY_BASE_DELAY_MS_MIN.toFloat(), retry.delayRange.start, 0f)
        assertEquals(SettingsDefaults.CLOUD_RETRY_BASE_DELAY_MS_MAX.toFloat(), retry.delayRange.endInclusive, 0f)
    }
}
