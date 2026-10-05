package app.knotwork.android.presentation.ui.settings.provider

import app.knotwork.android.domain.connection.AddressRefusal
import app.knotwork.android.domain.connection.ConnectionCheckResult
import app.knotwork.android.domain.connection.ConnectionRefusal
import app.knotwork.android.domain.connection.ProviderConnectionChecker
import app.knotwork.android.domain.connection.ProviderConnectionDraft
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.ProviderId
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.presentation.ui.common.ConnectionTestState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.TestTimeSource

/**
 * Unit tests for [ProviderDetailViewModel] — the standalone editor backing the
 * Settings → External providers detail screen. Closes the
 * `presentation.ui.settings.provider` coverage gap recorded in
 * `docs/coverage-baseline.md` (the ViewModel shipped at 0 % initially).
 *
 * The tests exercise both directions of the contract:
 * - `bind(providerId)` wires the relevant [ApiKeyRepository] read flows into
 *   [ProviderDetailUiState] for every provider.
 * - Each `update*` mutator persists through the repository under the bound
 *   provider, applying the "blank → null" normalisation, the address
 *   validation and the context-window fallback — and never writes a slot the
 *   provider does not use.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var apiKeyRepository: ApiKeyRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: ProviderDetailViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // Relaxed so the suspend setters are stubbed as no-ops; bind() reads are
        // overridden per test below.
        apiKeyRepository = mockk(relaxed = true)
        // Relaxed so the global cloud-retry flows the ViewModel binds on
        // construction resolve to empty flows (no emissions) by default.
        settingsRepository = mockk(relaxed = true)
        viewModel = newViewModel()
    }

    /** A ViewModel on the shared mocks; the connection checker is irrelevant outside the Test row tests. */
    private fun newViewModel(): ProviderDetailViewModel =
        ProviderDetailViewModel(apiKeyRepository, settingsRepository, mockk(relaxed = true), TestTimeSource())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Every provider reached with a key at a host the client already knows. */
    private val hostedProviders = ProviderId.entries.filter { it.cloudProvider.usesApiKey }

    @Test
    fun `given each hosted provider when bind then its key and model flow into state`() = runTest {
        hostedProviders.forEach { id ->
            val vm = newViewModel()
            every { apiKeyRepository.getApiKey(id.cloudProvider) } returns flowOf("key-${id.name}")
            every { apiKeyRepository.getModel(id.cloudProvider) } returns flowOf("model-${id.name}")

            vm.bind(id)
            advanceUntilIdle()

            assertEquals("key-${id.name}", vm.uiState.value.apiKey)
            assertEquals("model-${id.name}", vm.uiState.value.model)
        }
    }

    @Test
    fun `given a hosted provider when bind then no server address is read`() = runTest {
        every { apiKeyRepository.getApiKey(CloudProvider.OPENAI) } returns flowOf("sk-openai")
        every { apiKeyRepository.getModel(CloudProvider.OPENAI) } returns flowOf("gpt-4o")

        viewModel.bind(ProviderId.OpenAi)
        advanceUntilIdle()

        verify(exactly = 0) { apiKeyRepository.getBaseUrl(any()) }
        verify(exactly = 0) { apiKeyRepository.getOllamaContextWindowSize() }
    }

    @Test
    fun `given Ollama when bind then base url model and context window flow into state`() = runTest {
        every { apiKeyRepository.getBaseUrl(CloudProvider.OLLAMA) } returns flowOf("http://10.0.0.2:11434")
        every { apiKeyRepository.getModel(CloudProvider.OLLAMA) } returns flowOf("llama3")
        every { apiKeyRepository.getOllamaContextWindowSize() } returns flowOf(8192)

        viewModel.bind(ProviderId.Ollama)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("http://10.0.0.2:11434", state.baseUrl)
        assertEquals("llama3", state.model)
        assertEquals("8192", state.ollamaContextWindow)
        verify(exactly = 0) { apiKeyRepository.getApiKey(any()) }
    }

    @Test
    fun `given null repository value when bind then state field is empty string`() = runTest {
        every { apiKeyRepository.getApiKey(CloudProvider.OPENAI) } returns flowOf(null)
        every { apiKeyRepository.getModel(CloudProvider.OPENAI) } returns flowOf(null)

        viewModel.bind(ProviderId.OpenAi)
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.apiKey)
        assertEquals("", viewModel.uiState.value.model)
    }

    @Test
    fun `given non-blank value when updateKey then persists value for that provider`() = runTest {
        viewModel.updateKey(ProviderId.OpenAi, "sk-new")
        advanceUntilIdle()
        coVerify { apiKeyRepository.setApiKey(CloudProvider.OPENAI, "sk-new") }
    }

    @Test
    fun `given blank value when updateKey then persists null`() = runTest {
        viewModel.updateKey(ProviderId.OpenAi, "   ")
        advanceUntilIdle()
        coVerify { apiKeyRepository.setApiKey(CloudProvider.OPENAI, null) }
    }

    @Test
    fun `given Ollama when updateKey then nothing is written`() = runTest {
        // Ollama has no key; a stray write would create an entry nothing ever reads.
        viewModel.updateKey(ProviderId.Ollama, "sk-stray")
        advanceUntilIdle()
        coVerify(exactly = 0) { apiKeyRepository.setApiKey(any(), any()) }
    }

    @Test
    fun `given each provider when updateModel then persists trimmed-to-null value under that provider`() = runTest {
        ProviderId.entries.forEach { id -> viewModel.updateModel(id, "model-${id.name}") }
        viewModel.updateModel(ProviderId.Google, " ")
        advanceUntilIdle()

        ProviderId.entries.forEach { id ->
            coVerify { apiKeyRepository.setModel(id.cloudProvider, "model-${id.name}") }
        }
        coVerify { apiKeyRepository.setModel(CloudProvider.GOOGLE, null) }
    }

    @Test
    fun `given blank base url when updateBaseUrl then flags invalid and persists null`() = runTest {
        viewModel.updateBaseUrl(ProviderId.Ollama, "")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.baseUrlInvalid)
        assertEquals("", viewModel.uiState.value.baseUrl)
        coVerify { apiKeyRepository.setBaseUrl(CloudProvider.OLLAMA, null) }
    }

    @Test
    fun `given a hosted provider when updateBaseUrl then nothing is written`() = runTest {
        viewModel.updateBaseUrl(ProviderId.OpenAi, "http://localhost:11434")
        advanceUntilIdle()
        coVerify(exactly = 0) { apiKeyRepository.setBaseUrl(any(), any()) }
    }

    @Test
    fun `given a base url with no scheme when updateBaseUrl then it is flagged invalid`() = runTest {
        // Found on the device. The check used to be `isBlank()` alone, so a bare
        // address was accepted in silence — and because `CleartextPolicy` treats
        // anything without an `http://` prefix as *not cleartext*, no consent
        // was asked for either. The value was stored, looked right, and failed
        // at request time.
        viewModel.updateBaseUrl(ProviderId.Ollama, "192.168.1.24")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.baseUrlInvalid)
    }

    @Test
    fun `given a host with a port but no scheme when updateBaseUrl then it is flagged invalid`() = runTest {
        // The shape a person is most likely to type, and the one that reads most
        // like a finished URL.
        viewModel.updateBaseUrl(ProviderId.Ollama, "192.168.1.24:11434")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.baseUrlInvalid)
    }

    @Test
    fun `given an https base url when updateBaseUrl then it is accepted`() = runTest {
        // The validator asks for a parseable scheme and host, not for cleartext:
        // a TLS-terminated Ollama behind a reverse proxy is a legitimate setup.
        viewModel.updateBaseUrl(ProviderId.Ollama, "https://ollama.example.net")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.baseUrlInvalid)
    }

    @Test
    fun `given valid base url when updateBaseUrl then clears invalid and persists value`() = runTest {
        viewModel.updateBaseUrl(ProviderId.Ollama, "http://localhost:11434")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.baseUrlInvalid)
        assertEquals("http://localhost:11434", viewModel.uiState.value.baseUrl)
        coVerify { apiKeyRepository.setBaseUrl(CloudProvider.OLLAMA, "http://localhost:11434") }
    }

    @Test
    fun `given numeric input when updateOllamaContextWindow then persists parsed size`() = runTest {
        viewModel.updateOllamaContextWindow("16384")
        advanceUntilIdle()
        coVerify { apiKeyRepository.setOllamaContextWindowSize(16384) }
    }

    @Test
    fun `given non-numeric input when updateOllamaContextWindow then persists default size`() = runTest {
        viewModel.updateOllamaContextWindow("not-a-number")
        advanceUntilIdle()
        coVerify { apiKeyRepository.setOllamaContextWindowSize(4096) }
    }

    /** A ViewModel whose Test row sees [approved] origins with the restriction off, and checks with [checker]. */
    private fun testRowViewModel(
        checker: ProviderConnectionChecker,
        approved: Set<String> = emptySet(),
    ): ProviderDetailViewModel {
        every { settingsRepository.approvedCleartextOrigins } returns MutableStateFlow(approved)
        every { settingsRepository.blockNetworkFromLocalModel } returns MutableStateFlow(false)
        return ProviderDetailViewModel(apiKeyRepository, settingsRepository, checker, TestTimeSource())
    }

    private val ollamaUrl = "http://192.168.1.20:11434"

    /** Ollama bound at [ollamaUrl], its Test row checking with [checker]. */
    private fun TestScope.boundOllama(checker: ProviderConnectionChecker): ProviderDetailViewModel {
        every { apiKeyRepository.getModel(CloudProvider.OLLAMA) } returns flowOf(null)
        every { apiKeyRepository.getBaseUrl(CloudProvider.OLLAMA) } returns MutableStateFlow(ollamaUrl)
        every { apiKeyRepository.getOllamaContextWindowSize() } returns flowOf(4096)
        return testRowViewModel(checker, approved = setOf(ollamaUrl)).also {
            it.bind(ProviderId.Ollama)
            advanceUntilIdle()
        }
    }

    private fun ollamaChecker(): ProviderConnectionChecker = mockk {
        every { destination(CloudProvider.OLLAMA, ollamaUrl) } returns "192.168.1.20"
        coEvery { check(CloudProvider.OLLAMA, ProviderConnectionDraft("", ollamaUrl)) } returns
            ConnectionCheckResult.Reachable(listOf("llama3.2:3b"))
    }

    @Test
    fun `given a hosted provider without a key when bound then the Test row is disabled for the key`() = runTest {
        every { apiKeyRepository.getApiKey(CloudProvider.OPENAI) } returns flowOf(null)
        every { apiKeyRepository.getModel(CloudProvider.OPENAI) } returns flowOf(null)
        val vm = testRowViewModel(mockk())

        vm.bind(ProviderId.OpenAi)
        advanceUntilIdle()

        assertEquals(ConnectionTestState.Disabled(ConnectionRefusal.MissingKey), vm.connectionTestState.value)
    }

    @Test
    fun `given Ollama at an approved address when Test is pressed then the values in the form are checked`() = runTest {
        val vm = boundOllama(ollamaChecker())
        val before = vm.connectionTestState.value

        vm.startConnectionTest()
        advanceUntilIdle()

        assertEquals(ConnectionTestState.Idle, before)
        assertEquals(
            ConnectionTestState.Finished(ConnectionCheckResult.Reachable(listOf("llama3.2:3b"))),
            vm.connectionTestState.value,
        )
    }

    @Test
    fun `given a result when the address is edited then it is dropped and the new address is judged`() = runTest {
        val vm = boundOllama(ollamaChecker())
        vm.startConnectionTest()
        advanceUntilIdle()

        vm.updateBaseUrl(ProviderId.Ollama, "http://192.168.1.21:11434")
        advanceUntilIdle()

        assertEquals(
            ConnectionTestState.Disabled(AddressRefusal.CleartextNeedsApproval("http://192.168.1.21:11434")),
            vm.connectionTestState.value,
        )
    }

    @Test
    fun `given a running test when cancelled then the row is idle again`() = runTest {
        val checker = mockk<ProviderConnectionChecker> {
            every { destination(any(), any()) } returns "192.168.1.20"
            coEvery { check(any(), any()) } coAnswers { awaitCancellation() }
        }
        val vm = boundOllama(checker)
        vm.startConnectionTest()
        advanceUntilIdle()
        val running = vm.connectionTestState.value

        vm.cancelConnectionTest()
        advanceUntilIdle()

        assertTrue("$running", running is ConnectionTestState.Running)
        assertEquals(ConnectionTestState.Idle, vm.connectionTestState.value)
    }

    @Test
    fun `given an address a rule refuses when typed then the reason is set for the field`() = runTest {
        every { settingsRepository.approvedCleartextOrigins } returns MutableStateFlow(emptySet())
        every { settingsRepository.blockNetworkFromLocalModel } returns MutableStateFlow(true)
        every { apiKeyRepository.getModel(CloudProvider.OPENAI_COMPATIBLE) } returns flowOf(null)
        every { apiKeyRepository.getApiKey(CloudProvider.OPENAI_COMPATIBLE) } returns flowOf(null)
        every { apiKeyRepository.getBaseUrl(CloudProvider.OPENAI_COMPATIBLE) } returns MutableStateFlow(null)
        val vm = ProviderDetailViewModel(apiKeyRepository, settingsRepository, mockk(relaxed = true), TestTimeSource())
        vm.bind(ProviderId.OpenAiCompatible)
        advanceUntilIdle()

        vm.updateBaseUrl(ProviderId.OpenAiCompatible, "https://gpu-box.lan/v1")
        advanceUntilIdle()
        val refused = vm.uiState.value.addressRefusal
        vm.updateBaseUrl(ProviderId.OpenAiCompatible, "http")
        advanceUntilIdle()

        assertEquals(AddressRefusal.HostNotLocal("gpu-box.lan"), refused)
        assertEquals(
            "Not an address yet — the field's error says so, not a rule.",
            null,
            vm.uiState.value.addressRefusal,
        )
    }

    @Test
    fun `given the model list when opened and closed then the state follows`() = runTest {
        val vm = newViewModel()

        vm.openModelSheet()
        val open = vm.uiState.value.modelSheetOpen
        vm.closeModelSheet()

        assertTrue(open)
        assertFalse(vm.uiState.value.modelSheetOpen)
    }
}
