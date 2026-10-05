package app.knotwork.android.presentation.ui.settings.provider

import app.knotwork.android.data.engine.OpenAiCompatibleClients
import app.knotwork.android.domain.connection.AddressRefusal
import app.knotwork.android.domain.connection.ConnectionCheckResult
import app.knotwork.android.domain.connection.ConnectionFailure
import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.ProviderId
import app.knotwork.android.presentation.ui.common.ConnectionTestState
import app.knotwork.design.components.misc.TestProbeTone
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
 * The screen's composition has Roborazzi baselines, but the per-provider shape is the part they
 * cannot see: which provider gets an address, a fixed address, a key, a built-in model list or a
 * typed id with *Choose*. It is also where a newly added provider would be wired wrong, and the
 * failure would be silent — a field simply missing.
 */
@RunWith(RobolectricTestRunner::class)
class ProviderDetailProjectionTest {

    private val context = RuntimeEnvironment.getApplication()

    private val reachable = ConnectionTestState.Finished(ConnectionCheckResult.Reachable(listOf("a", "b")))

    @Test
    fun `given a hosted provider with a built-in list then it has a key and a dropdown, and no address`() {
        val state = ProviderDetailUiState(apiKey = "sk-1", model = "gpt-4o").toViewState(ProviderId.OpenAi, context)

        assertEquals("sk-1", state.apiKey?.value)
        assertEquals("gpt-4o", state.model.value)
        assertTrue("A provider Koog lists offers a model list.", state.model.options.isNotEmpty())
        assertNull(state.address)
        assertNull(state.fixedAddress)
        assertEquals("OpenAI", state.title)
    }

    @Test
    fun `given every provider that takes a key then each shows the key under its own name`() {
        ProviderId.entries.filter { it.cloudProvider.usesApiKey && it != ProviderId.OpenAiCompatible }.forEach { id ->
            val state = ProviderDetailUiState(apiKey = "key").toViewState(id, context)

            assertEquals("$id hid its key field.", "key", state.apiKey?.value)
            assertTrue("$id's key field is not named.", state.apiKey!!.label.contains(id.displayName()))
        }
    }

    @Test
    fun `given OpenRouter and Groq then the address shown is the one the client sends to`() {
        assertEquals(OpenAiCompatibleClients.OPENROUTER_BASE_URL, fixedAddressOf(ProviderId.OpenRouter))
        assertEquals(OpenAiCompatibleClients.GROQ_BASE_URL, fixedAddressOf(ProviderId.Groq))
    }

    @Test
    fun `given OpenRouter and Groq then they show where they send, a required key and a typed model id`() {
        listOf(ProviderId.OpenRouter to "https://openrouter.ai/api", ProviderId.Groq to "https://api.groq.com/openai")
            .forEach { (id, address) ->
                val state = ProviderDetailUiState().toViewState(id, context)

                assertEquals(address, state.fixedAddress?.value)
                assertEquals("required", state.apiKey?.marker)
                assertEquals("required", state.model.marker)
                assertTrue("$id's model is typed.", state.model.options.isEmpty())
                assertNull(state.address)
            }
    }

    @Test
    fun `given a server the user runs then it has an address with the v1 hint and an optional key`() {
        val state = ProviderDetailUiState(
            baseUrl = "http://10.0.0.2:8000/v1",
        ).toViewState(ProviderId.OpenAiCompatible, context)

        assertEquals("http://10.0.0.2:8000/v1", state.address?.value)
        assertTrue(state.address?.hint.orEmpty().contains("/v1"))
        assertEquals("optional", state.apiKey?.marker)
        assertEquals("API key", state.apiKey?.label)
        assertEquals("required", state.model.marker)
    }

    @Test
    fun `given Ollama then it has an address and a context window, and no key even when one is stored`() {
        // Ollama runs LAN-local without authentication — even a stray key in the state must not surface.
        val state = ProviderDetailUiState(apiKey = "stray", model = "llama3.1", baseUrl = "http://host:11434")
            .toViewState(ProviderId.Ollama, context)

        assertNull(state.apiKey)
        assertEquals("llama3.1", state.model.value)
        assertTrue("Ollama's model is typed, not picked.", state.model.options.isEmpty())
        assertEquals("http://host:11434", state.address?.value)
        assertNotNull(state.contextWindow)
        assertNull("Ollama's address needs no /v1.", state.address?.hint)
    }

    @Test
    fun `given an address being typed then an empty one and an unparsable one have their own error`() {
        val empty = ProviderDetailUiState(baseUrlInvalid = true).toViewState(ProviderId.OpenAiCompatible, context)
        val garbage = ProviderDetailUiState(baseUrl = "10.0.0.2:8000", baseUrlInvalid = true)
            .toViewState(ProviderId.OpenAiCompatible, context)
        val fine = ProviderDetailUiState(baseUrl = "http://10.0.0.2", baseUrlInvalid = false)
            .toViewState(ProviderId.OpenAiCompatible, context)

        assertEquals("Enter the server address.", empty.address?.error)
        assertTrue(garbage.address?.error.orEmpty().startsWith("Not an address."))
        assertNull(fine.address?.error)
    }

    @Test
    fun `given an address the rules refuse then the reason is under the field, worded for the case`() {
        fun refusal(refusal: AddressRefusal) = ProviderDetailUiState(addressRefusal = refusal)
            .toViewState(ProviderId.OpenAiCompatible, context).address?.refusal

        assertTrue(refusal(AddressRefusal.PublicCleartext("203.0.113.7")).orEmpty().contains("public internet"))
        assertTrue(refusal(AddressRefusal.HostNotLocal("gpu-box.lan")).orEmpty().contains("is a host name"))
        assertTrue(refusal(AddressRefusal.HostNotLocal("203.0.113.7")).orEmpty().contains("is a public address"))
        // The banner asks for approval; the field does not say it twice.
        assertNull(refusal(AddressRefusal.CleartextNeedsApproval("http://10.0.0.2:8000")))
    }

    @Test
    fun `given a typed model field then the note says what to do before a test, and Choose comes with a list`() {
        val before = ProviderDetailUiState().toViewState(ProviderId.Groq, context)
        val after = ProviderDetailUiState().toViewState(ProviderId.Groq, context, test = reachable)
        val noList = ProviderDetailUiState().toViewState(
            ProviderId.Groq,
            context,
            test = ConnectionTestState.Finished(ConnectionCheckResult.Failed("h", ConnectionFailure.NoModelList)),
        )

        assertTrue(before.model.note.orEmpty().startsWith("Type the id, or run Test"))
        assertNull(before.model.chooseLabel)
        assertEquals("Choose", after.model.chooseLabel)
        assertNull(after.model.note)
        assertTrue(noList.model.note.orEmpty().startsWith("This server sent no model list"))
    }

    @Test
    fun `given the sheet opened after a test then it lists what the test brought, the current id ticked`() {
        val state = ProviderDetailUiState(model = "b", modelSheetOpen = true)
            .toViewState(ProviderId.OpenRouter, context, test = reachable)
        val withoutList = ProviderDetailUiState(modelSheetOpen = true).toViewState(ProviderId.OpenRouter, context)

        assertEquals(listOf("a", "b"), state.modelSheet?.ids)
        assertEquals("b", state.modelSheet?.selected)
        assertEquals("OpenRouter", state.modelSheet?.source)
        assertNull("No list, nothing to open.", withoutList.modelSheet)
    }

    @Test
    fun `given the test row's state then it is resolved for the provider`() {
        val running = ProviderDetailUiState().toViewState(
            ProviderId.Groq,
            context,
            test = ConnectionTestState.Running("api.groq.com", kotlin.time.TestTimeSource().markNow()),
            elapsedSeconds = 4,
        )

        assertEquals(TestProbeTone.Running, running.test.tone)
        assertEquals("Asking api.groq.com for its models · 4 s", running.test.status)
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
