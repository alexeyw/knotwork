package app.knotwork.android.presentation.ui.settings

import android.content.Context
import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.Identity
import app.knotwork.android.domain.models.MemoryStats
import app.knotwork.android.domain.models.TestProbeResult
import app.knotwork.android.domain.models.ToolApprovalPolicy
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.ExternalAutomationJournalRepository
import app.knotwork.android.domain.repositories.IdentityRepository
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.EmbeddingProvider
import app.knotwork.android.domain.services.MemorySearchStatsTracker
import app.knotwork.android.domain.usecases.ClearAllMemoryUseCase
import app.knotwork.android.domain.usecases.ExportMemoryBaseUseCase
import app.knotwork.android.domain.usecases.GetSystemPromptVariableCatalogUseCase
import app.knotwork.android.domain.usecases.MemoryImportUseCase
import app.knotwork.android.domain.usecases.PromptVariableCatalogEntry
import app.knotwork.android.domain.usecases.ReembedAllMemoriesUseCase
import app.knotwork.android.domain.usecases.ResetSamplingDefaultsUseCase
import app.knotwork.android.domain.usecases.ResetToRecommendedDefaultsUseCase
import app.knotwork.android.domain.usecases.TestBackendUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import app.knotwork.android.domain.settings.SettingsCategoryId as DomainSettingsCategoryId

/**
 * Unit tests for the redesigned [SettingsViewModel].
 *
 * Coverage:
 *  - identity load + variable catalog load on init,
 *  - mutator methods route through their respective repositories,
 *  - restart-required detection flips when the backend / Ollama URL changes,
 *  - destructive typed-confirm gate (Clear memory / Reset settings),
 *  - reset-settings clears every preference back to defaults.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val context = mockk<Context>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val externalAutomationJournal = mockk<ExternalAutomationJournalRepository>(relaxed = true) {
        every { observeAll() } returns flowOf(emptyList())
    }
    private val apiKeys = mockk<ApiKeyRepository>(relaxed = true)
    private val localModels = mockk<LocalModelRepository>(relaxed = true)
    private val memory = mockk<MemoryRepository>(relaxed = true)
    private val identity = mockk<IdentityRepository>(relaxed = true)
    private val testBackend = mockk<TestBackendUseCase>(relaxed = true)
    private val resetSampling = mockk<ResetSamplingDefaultsUseCase>(relaxed = true)
    private val resetToRecommended = mockk<ResetToRecommendedDefaultsUseCase>(relaxed = true)
    private val clearMemory = mockk<ClearAllMemoryUseCase>(relaxed = true)
    private val exportMemory = mockk<ExportMemoryBaseUseCase>(relaxed = true)
    private val memoryImport = mockk<MemoryImportUseCase>(relaxed = true)
    private val reembed = mockk<ReembedAllMemoriesUseCase>(relaxed = true)
    private val variableCatalog = mockk<GetSystemPromptVariableCatalogUseCase>(relaxed = true)

    // Real instance: the tracker is a pure in-memory rolling window, so using
    // it directly doubles as integration coverage of the AVG SCORE plumbing.
    private val searchStatsTracker = MemorySearchStatsTracker()

    private val useProvider = fakeProvider(EmbeddingProvider.ID_USE, "On-device (USE)")
    private val openAiProvider = fakeProvider(EmbeddingProvider.ID_OPENAI_3_SMALL, "OpenAI (3-small)")
    private val embeddingProviders: Map<String, EmbeddingProvider> = mapOf(
        EmbeddingProvider.ID_OPENAI_3_SMALL to openAiProvider,
        EmbeddingProvider.ID_USE to useProvider,
    )

    private lateinit var viewModel: SettingsViewModel
    private val dispatcher = StandardTestDispatcher()

    private fun fakeProvider(providerId: String, name: String): EmbeddingProvider = mockk(relaxed = true) {
        every { id } returns providerId
        every { displayName } returns name
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)

        every { context.getString(any()) } returns "anonymous"
        every { context.getString(any<Int>(), *anyVararg()) } returns "message"

        every { settings.systemPromptPrefix } returns MutableStateFlow("")
        every { settings.toolApprovalPolicy } returns MutableStateFlow(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settings.blockDestructiveTools } returns MutableStateFlow(false)
        every { settings.toolCallTimeoutMs } returns MutableStateFlow(SettingsDefaults.TOOL_CALL_TIMEOUT_MS_DEFAULT)
        every { settings.workspaceMaxFileSizeBytes } returns
            MutableStateFlow(SettingsDefaults.WORKSPACE_MAX_FILE_SIZE_BYTES_DEFAULT)
        every { settings.workspaceMaxTotalBytes } returns
            MutableStateFlow(SettingsDefaults.WORKSPACE_MAX_TOTAL_BYTES_DEFAULT)
        every { settings.workspaceReadTokenBudget } returns
            MutableStateFlow(SettingsDefaults.WORKSPACE_READ_TOKEN_BUDGET_DEFAULT)
        every { settings.httpToolMaxResponseBytes } returns
            MutableStateFlow(SettingsDefaults.HTTP_TOOL_MAX_RESPONSE_BYTES_DEFAULT)
        every { settings.blockNetworkFromLocalModel } returns MutableStateFlow(false)
        every { settings.pipelineMaxSteps } returns MutableStateFlow(20)
        every { settings.temperature } returns MutableStateFlow(0.7f)
        every { settings.topK } returns MutableStateFlow(40)
        every { settings.topP } returns MutableStateFlow(0.9f)
        every { settings.maxContextLength } returns MutableStateFlow(4096)
        every { settings.localModelBackend } returns MutableStateFlow("CPU")
        every { settings.lastTestProbeResult } returns MutableStateFlow<TestProbeResult?>(null)
        every { settings.autoExtractEnabled } returns MutableStateFlow(true)
        every { settings.memorySearchTopK } returns MutableStateFlow(SettingsDefaults.MEMORY_SEARCH_TOP_K_DEFAULT)
        every { settings.memorySearchThreshold } returns
            MutableStateFlow(SettingsDefaults.MEMORY_SEARCH_THRESHOLD_DEFAULT)
        every { settings.memoryRecencyHalfLifeDays } returns
            MutableStateFlow(SettingsDefaults.MEMORY_RECENCY_HALF_LIFE_DAYS_DEFAULT)
        every { settings.memoryCompactionEnabled } returns MutableStateFlow(true)
        every { settings.memoryCompactionAgeDays } returns
            MutableStateFlow(SettingsDefaults.MEMORY_COMPACTION_AGE_DAYS_DEFAULT)
        every { settings.maxMemoryChunks } returns MutableStateFlow(SettingsDefaults.MAX_MEMORY_CHUNKS_DEFAULT)
        every { settings.activeEmbeddingProviderId } returns MutableStateFlow(EmbeddingProvider.ID_USE)
        every { settings.lastReembedProviderId } returns MutableStateFlow<String?>(null)
        every { settings.scheduledTaskNotificationsEnabled } returns MutableStateFlow(true)
        every { settings.crashReportingEnabled } returns MutableStateFlow(false)
        every { settings.verboseMemoryLoggingEnabled } returns MutableStateFlow(false)

        every { localModels.observeActiveModelMeta() } returns MutableStateFlow(null)
        every { memory.observeStats() } returns MutableStateFlow(MemoryStats.EMPTY)

        every { apiKeys.getOpenAIKey() } returns MutableStateFlow<String?>(null)
        every { apiKeys.getOpenAIModel() } returns MutableStateFlow<String?>(null)
        every { apiKeys.getAnthropicKey() } returns MutableStateFlow<String?>(null)
        every { apiKeys.getAnthropicModel() } returns MutableStateFlow<String?>(null)
        every { apiKeys.getGoogleKey() } returns MutableStateFlow<String?>(null)
        every { apiKeys.getGoogleModel() } returns MutableStateFlow<String?>(null)
        every { apiKeys.getDeepSeekKey() } returns MutableStateFlow<String?>(null)
        every { apiKeys.getDeepSeekModel() } returns MutableStateFlow<String?>(null)
        every { apiKeys.getOllamaBaseUrl() } returns MutableStateFlow<String?>(null)
        every { apiKeys.getOllamaModelName() } returns MutableStateFlow<String?>(null)
        every { apiKeys.getOllamaContextWindowSize() } returns MutableStateFlow(4096)

        coEvery { identity.getIdentity(any()) } returns Identity(
            displayName = "Anonymous · this device",
            deviceId = "4f3a-92d1",
            keystoreAvailable = true,
        )
        coEvery { variableCatalog() } returns listOf(
            PromptVariableCatalogEntry("\$DATE", "20 May 2026"),
            PromptVariableCatalogEntry("\$TIME", "09:30"),
        )

        viewModel = newViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads identity and variable catalog`() = runTest {
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals("Anonymous · this device", state.identity?.displayName)
        assertEquals(2, state.variableCatalog.size)
        assertEquals("\$DATE", state.variableCatalog.first().placeholder)
    }

    @Test
    fun `setToolApprovalPolicy routes through repository`() = runTest {
        advanceUntilIdle()
        viewModel.setToolApprovalPolicy(ToolApprovalPolicy.AllCalls)
        advanceUntilIdle()
        coVerify { settings.setToolApprovalPolicy(ToolApprovalPolicy.AllCalls) }
    }

    @Test
    fun `setBlockDestructiveTools routes through repository`() = runTest {
        advanceUntilIdle()
        viewModel.setBlockDestructiveTools(true)
        advanceUntilIdle()
        coVerify { settings.setBlockDestructiveTools(true) }
    }

    @Test
    fun `every tool ceiling is observed into state`() = runTest {
        advanceUntilIdle()

        // The five ceilings were registered, searchable and consumed by real
        // code while no screen rendered them, so nothing observed them into
        // state either. This is the observation half of that repair.
        val state = viewModel.uiState.value
        assertEquals(SettingsDefaults.TOOL_CALL_TIMEOUT_MS_DEFAULT, state.toolCallTimeoutMs)
        assertEquals(SettingsDefaults.WORKSPACE_MAX_FILE_SIZE_BYTES_DEFAULT, state.workspaceMaxFileSizeBytes)
        assertEquals(SettingsDefaults.WORKSPACE_MAX_TOTAL_BYTES_DEFAULT, state.workspaceMaxTotalBytes)
        assertEquals(SettingsDefaults.WORKSPACE_READ_TOKEN_BUDGET_DEFAULT, state.workspaceReadTokenBudget)
        assertEquals(SettingsDefaults.HTTP_TOOL_MAX_RESPONSE_BYTES_DEFAULT, state.httpToolMaxResponseBytes)
    }

    @Test
    fun `every tool ceiling edit routes through repository`() = runTest {
        advanceUntilIdle()

        viewModel.setToolCallTimeoutMs(30_000L)
        viewModel.setWorkspaceMaxFileSizeBytes(2L * 1024 * 1024)
        viewModel.setWorkspaceMaxTotalBytes(200L * 1024 * 1024)
        viewModel.setWorkspaceReadTokenBudget(1_500)
        viewModel.setHttpToolMaxResponseBytes(512L * 1024)
        advanceUntilIdle()

        coVerify { settings.setToolCallTimeoutMs(30_000L) }
        coVerify { settings.setWorkspaceMaxFileSizeBytes(2L * 1024 * 1024) }
        coVerify { settings.setWorkspaceMaxTotalBytes(200L * 1024 * 1024) }
        coVerify { settings.setWorkspaceReadTokenBudget(1_500) }
        coVerify { settings.setHttpToolMaxResponseBytes(512L * 1024) }
    }

    @Test
    fun `setBlockNetworkFromLocalModel routes through repository`() = runTest {
        advanceUntilIdle()
        viewModel.setBlockNetworkFromLocalModel(true)
        advanceUntilIdle()
        coVerify { settings.setBlockNetworkFromLocalModel(true) }
    }

    @Test
    fun `setAudioMaxDurationSec routes through repository`() = runTest {
        advanceUntilIdle()
        viewModel.setAudioMaxDurationSec(45)
        advanceUntilIdle()
        coVerify { settings.setAudioMaxDurationSec(45) }
    }

    @Test
    fun `setPipelineMaxNestingDepth routes through repository`() = runTest {
        advanceUntilIdle()
        viewModel.setPipelineMaxNestingDepth(4)
        advanceUntilIdle()
        coVerify { settings.setPipelineMaxNestingDepth(4) }
    }

    @Test
    fun `setStructuredOutputMaxRepairs routes through repository`() = runTest {
        advanceUntilIdle()
        viewModel.setStructuredOutputMaxRepairs(3)
        advanceUntilIdle()
        coVerify { settings.setStructuredOutputMaxRepairs(3) }
    }

    @Test
    fun `setMemorySummaryDefaultLimit routes through repository`() = runTest {
        advanceUntilIdle()
        viewModel.setMemorySummaryDefaultLimit(12)
        advanceUntilIdle()
        coVerify { settings.setMemorySummaryDefaultLimit(12) }
    }

    // ─── Memory tuning: observation ────────────────────────────────────────

    @Test
    fun `init observes memory tuning preferences and builds embedding options`() = runTest {
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(SettingsDefaults.MEMORY_SEARCH_TOP_K_DEFAULT, state.memorySearchTopK)
        assertEquals(SettingsDefaults.MEMORY_SEARCH_THRESHOLD_DEFAULT, state.memorySearchThreshold)
        assertEquals(SettingsDefaults.MEMORY_RECENCY_HALF_LIFE_DAYS_DEFAULT, state.memoryRecencyHalfLifeDays)
        assertTrue(state.memoryCompactionEnabled)
        assertEquals(SettingsDefaults.MEMORY_COMPACTION_AGE_DAYS_DEFAULT, state.memoryCompactionAgeDays)
        assertEquals(SettingsDefaults.MAX_MEMORY_CHUNKS_DEFAULT, state.maxMemoryChunks)
        assertEquals(EmbeddingProvider.ID_USE, state.activeEmbeddingProviderId)
        // On-device USE is hoisted to the top regardless of map iteration order.
        assertEquals(EmbeddingProvider.ID_USE, state.embeddingProviderOptions.first().id)
        assertEquals(embeddingProviders.size, state.embeddingProviderOptions.size)
    }

    // ─── Memory tuning: valid edits persist ────────────────────────────────

    @Test
    fun `setMemorySearchTopK within range persists and clears error`() = runTest {
        advanceUntilIdle()
        viewModel.setMemorySearchTopK(10)
        advanceUntilIdle()
        coVerify { settings.setMemorySearchTopK(10) }
        assertNull(viewModel.uiState.value.memoryValidationError)
    }

    @Test
    fun `setMemorySearchThreshold within range persists`() = runTest {
        advanceUntilIdle()
        viewModel.setMemorySearchThreshold(0.5f)
        advanceUntilIdle()
        coVerify { settings.setMemorySearchThreshold(0.5f) }
        assertNull(viewModel.uiState.value.memoryValidationError)
    }

    @Test
    fun `setMemoryRecencyHalfLifeDays within range persists`() = runTest {
        advanceUntilIdle()
        viewModel.setMemoryRecencyHalfLifeDays(60)
        advanceUntilIdle()
        coVerify { settings.setMemoryRecencyHalfLifeDays(60) }
    }

    @Test
    fun `setMemoryCompactionEnabled persists`() = runTest {
        advanceUntilIdle()
        viewModel.setMemoryCompactionEnabled(false)
        advanceUntilIdle()
        coVerify { settings.setMemoryCompactionEnabled(false) }
        assertNull(viewModel.uiState.value.memoryValidationError)
    }

    @Test
    fun `setMemoryCompactionAgeDays within range persists`() = runTest {
        advanceUntilIdle()
        viewModel.setMemoryCompactionAgeDays(45)
        advanceUntilIdle()
        coVerify { settings.setMemoryCompactionAgeDays(45) }
    }

    @Test
    fun `setTraceRetentionRunsPerSession persists`() = runTest {
        advanceUntilIdle()
        viewModel.setTraceRetentionRunsPerSession(40)
        advanceUntilIdle()
        coVerify { settings.setTraceRetentionRunsPerSession(40) }
    }

    @Test
    fun `setTraceRetentionMaxAgeDays persists`() = runTest {
        advanceUntilIdle()
        viewModel.setTraceRetentionMaxAgeDays(60)
        advanceUntilIdle()
        coVerify { settings.setTraceRetentionMaxAgeDays(60) }
    }

    @Test
    fun `setMaxMemoryChunks within range persists`() = runTest {
        advanceUntilIdle()
        viewModel.setMaxMemoryChunks(8_000)
        advanceUntilIdle()
        coVerify { settings.setMaxMemoryChunks(8_000) }
    }

    @Test
    fun `setActiveEmbeddingProviderId for known provider persists`() = runTest {
        advanceUntilIdle()
        viewModel.setActiveEmbeddingProviderId(EmbeddingProvider.ID_OPENAI_3_SMALL)
        advanceUntilIdle()
        coVerify { settings.setActiveEmbeddingProviderId(EmbeddingProvider.ID_OPENAI_3_SMALL) }
        assertNull(viewModel.uiState.value.memoryValidationError)
    }

    // ─── Memory tuning: out-of-range edits are rejected ────────────────────

    @Test
    fun `setMemorySearchTopK above max is rejected with validation error and not persisted`() = runTest {
        advanceUntilIdle()
        viewModel.setMemorySearchTopK(SettingsDefaults.MEMORY_SEARCH_TOP_K_MAX + 1)
        advanceUntilIdle()
        assertEquals(MemoryValidationError.SearchTopK, viewModel.uiState.value.memoryValidationError)
        coVerify(exactly = 0) { settings.setMemorySearchTopK(any()) }
    }

    @Test
    fun `setMemorySearchTopK below min is rejected`() = runTest {
        advanceUntilIdle()
        viewModel.setMemorySearchTopK(SettingsDefaults.MEMORY_SEARCH_TOP_K_MIN - 1)
        advanceUntilIdle()
        assertEquals(MemoryValidationError.SearchTopK, viewModel.uiState.value.memoryValidationError)
        coVerify(exactly = 0) { settings.setMemorySearchTopK(any()) }
    }

    @Test
    fun `setMemorySearchThreshold out of range is rejected`() = runTest {
        advanceUntilIdle()
        viewModel.setMemorySearchThreshold(SettingsDefaults.MEMORY_SEARCH_THRESHOLD_MAX + 0.1f)
        advanceUntilIdle()
        assertEquals(MemoryValidationError.SearchThreshold, viewModel.uiState.value.memoryValidationError)
        coVerify(exactly = 0) { settings.setMemorySearchThreshold(any()) }
    }

    @Test
    fun `setMemoryRecencyHalfLifeDays out of range is rejected`() = runTest {
        advanceUntilIdle()
        viewModel.setMemoryRecencyHalfLifeDays(SettingsDefaults.MEMORY_RECENCY_HALF_LIFE_DAYS_MAX + 1)
        advanceUntilIdle()
        assertEquals(MemoryValidationError.RecencyHalfLife, viewModel.uiState.value.memoryValidationError)
        coVerify(exactly = 0) { settings.setMemoryRecencyHalfLifeDays(any()) }
    }

    @Test
    fun `setMemoryCompactionAgeDays out of range is rejected`() = runTest {
        advanceUntilIdle()
        viewModel.setMemoryCompactionAgeDays(SettingsDefaults.MEMORY_COMPACTION_AGE_DAYS_MIN - 1)
        advanceUntilIdle()
        assertEquals(MemoryValidationError.CompactionAge, viewModel.uiState.value.memoryValidationError)
        coVerify(exactly = 0) { settings.setMemoryCompactionAgeDays(any()) }
    }

    @Test
    fun `setMaxMemoryChunks out of range is rejected`() = runTest {
        advanceUntilIdle()
        viewModel.setMaxMemoryChunks(SettingsDefaults.MAX_MEMORY_CHUNKS_MAX + 1)
        advanceUntilIdle()
        assertEquals(MemoryValidationError.MaxChunks, viewModel.uiState.value.memoryValidationError)
        coVerify(exactly = 0) { settings.setMaxMemoryChunks(any()) }
    }

    @Test
    fun `setActiveEmbeddingProviderId for unknown id is rejected`() = runTest {
        advanceUntilIdle()
        viewModel.setActiveEmbeddingProviderId("nonexistent_provider")
        advanceUntilIdle()
        assertEquals(
            MemoryValidationError.UnknownEmbeddingProvider,
            viewModel.uiState.value.memoryValidationError,
        )
        coVerify(exactly = 0) { settings.setActiveEmbeddingProviderId(any()) }
    }

    @Test
    fun `clearMemoryValidationError resets the error and a valid edit clears it`() = runTest {
        advanceUntilIdle()
        viewModel.setMemorySearchTopK(SettingsDefaults.MEMORY_SEARCH_TOP_K_MAX + 5)
        advanceUntilIdle()
        assertEquals(MemoryValidationError.SearchTopK, viewModel.uiState.value.memoryValidationError)

        viewModel.clearMemoryValidationError()
        assertNull(viewModel.uiState.value.memoryValidationError)

        // A subsequent in-range edit also keeps the error cleared.
        viewModel.setMemorySearchTopK(3)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.memoryValidationError)
    }

    @Test
    fun `resetSamplingDefaults invokes use case and surfaces snackbar`() = runTest {
        advanceUntilIdle()
        viewModel.resetSamplingDefaults()
        advanceUntilIdle()
        coVerify { resetSampling() }
        assertNotNull(viewModel.uiState.value.snackbarMessage)
    }

    @Test
    fun `changing backend flips restartRequired`() = runTest {
        val backendFlow = MutableStateFlow("CPU")
        every { settings.localModelBackend } returns backendFlow
        viewModel = newViewModel()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.restartRequired)
        backendFlow.value = "GPU"
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.restartRequired)
    }

    @Test
    fun `setting Ollama base URL from blank to value flips restartRequired`() = runTest {
        val ollamaUrlFlow = MutableStateFlow<String?>(null)
        every { apiKeys.getOllamaBaseUrl() } returns ollamaUrlFlow
        viewModel = newViewModel()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.restartRequired)
        ollamaUrlFlow.value = "http://192.168.1.42:11434"
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.restartRequired)
    }

    @Test
    fun `clearing Ollama base URL from value to blank flips restartRequired`() = runTest {
        val ollamaUrlFlow = MutableStateFlow<String?>("http://192.168.1.42:11434")
        every { apiKeys.getOllamaBaseUrl() } returns ollamaUrlFlow
        viewModel = newViewModel()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.restartRequired)
        ollamaUrlFlow.value = null
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.restartRequired)
    }

    @Test
    fun `acknowledgeRestart resets the baseline`() = runTest {
        val backendFlow = MutableStateFlow("CPU")
        every { settings.localModelBackend } returns backendFlow
        viewModel = newViewModel()
        advanceUntilIdle()
        backendFlow.value = "GPU"
        advanceUntilIdle()
        viewModel.acknowledgeRestart()
        assertFalse(viewModel.uiState.value.restartRequired)
    }

    @Test
    fun `stageClearMemory sets pending destructive action`() = runTest {
        advanceUntilIdle()
        viewModel.stageClearMemory()
        assertEquals(PendingDestructiveAction.ClearMemory, viewModel.uiState.value.pendingDestructive)
    }

    @Test
    fun `confirmDestructive without matching keyword is a no-op`() = runTest {
        every { context.getString(app.knotwork.android.R.string.destructive_typed_keyword) } returns "yes"
        advanceUntilIdle()
        viewModel.stageClearMemory()
        viewModel.updateDestructiveTypedInput("nope")
        viewModel.confirmDestructive()
        advanceUntilIdle()
        coVerify(exactly = 0) { clearMemory() }
        assertNotNull(viewModel.uiState.value.pendingDestructive)
    }

    @Test
    fun `confirmDestructive with matching keyword clears memory`() = runTest {
        every { context.getString(app.knotwork.android.R.string.destructive_typed_keyword) } returns "yes"
        advanceUntilIdle()
        viewModel.stageClearMemory()
        viewModel.updateDestructiveTypedInput("yes")
        viewModel.confirmDestructive()
        advanceUntilIdle()
        coVerify { clearMemory() }
        assertNull(viewModel.uiState.value.pendingDestructive)
    }

    @Test
    fun `confirmDestructive ResetSettings resets via use case without a typed keyword`() = runTest {
        advanceUntilIdle()
        viewModel.stageResetSettings()
        // No typed input — the plain confirm dialog has no keyword gate.
        viewModel.confirmDestructive()
        advanceUntilIdle()
        // The reset writes the consent default with every other preference; the
        // collector follows the persisted flag (see CrashReportingConsentOwnerTest).
        coVerify { resetToRecommended() }
        assertNotNull(viewModel.uiState.value.snackbarMessage)
        assertNull(viewModel.uiState.value.pendingDestructive)
    }

    @Test
    fun `confirmDestructive ResetSettings leaves user-authored prompts and embedding provider untouched`() = runTest {
        advanceUntilIdle()
        viewModel.stageResetSettings()
        viewModel.confirmDestructive()
        advanceUntilIdle()
        // Scope guarantee: reset never rewrites user content / memory configuration.
        coVerify(exactly = 0) { settings.setSystemPromptPrefix(any()) }
        coVerify(exactly = 0) { settings.setActiveEmbeddingProviderId(any()) }
    }

    @Test
    fun `cancelDestructive clears pending state`() = runTest {
        advanceUntilIdle()
        viewModel.stageClearMemory()
        viewModel.cancelDestructive()
        assertNull(viewModel.uiState.value.pendingDestructive)
        assertEquals("", viewModel.uiState.value.destructiveTypedInput)
    }

    @Test
    fun `setScheduledTaskNotificationsEnabled routes through repository`() = runTest {
        advanceUntilIdle()
        viewModel.setScheduledTaskNotificationsEnabled(false)
        advanceUntilIdle()
        coVerify { settings.setScheduledTaskNotificationsEnabled(false) }
    }

    @Test
    fun `setShareReuseSession routes through repository`() = runTest {
        advanceUntilIdle()
        viewModel.setShareReuseSession(false)
        advanceUntilIdle()
        coVerify { settings.setShareReuseSession(false) }
    }

    @Test
    fun `scheduledTaskNotificationsEnabled mirrors the repository flow into ui state`() = runTest {
        every { settings.scheduledTaskNotificationsEnabled } returns MutableStateFlow(false)
        viewModel = newViewModel()
        advanceUntilIdle()
        assertEquals(false, viewModel.uiState.value.scheduledTaskNotificationsEnabled)
    }

    @Test
    fun `setCrashReportingEnabled persists consent`() = runTest {
        advanceUntilIdle()
        viewModel.setCrashReportingEnabled(true)
        advanceUntilIdle()
        // Only persisted: the collector follows the flag through the app's
        // release-only observer (see CrashReportingConsentOwnerTest).
        coVerify { settings.setCrashReportingEnabled(true) }
    }

    @Test
    fun `setVerboseMemoryLoggingEnabled routes through repository`() = runTest {
        advanceUntilIdle()
        viewModel.setVerboseMemoryLoggingEnabled(true)
        advanceUntilIdle()
        coVerify { settings.setVerboseMemoryLoggingEnabled(true) }
    }

    @Test
    fun `verboseMemoryLoggingEnabled flow is mirrored into uiState`() = runTest {
        every { settings.verboseMemoryLoggingEnabled } returns MutableStateFlow(true)
        val vm = newViewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.verboseMemoryLoggingEnabled)
    }

    @Test
    fun `runReembed forwards progress to uiState`() = runTest {
        coEvery { reembed() } returns flowOf(0.5f, 1f)
        advanceUntilIdle()
        viewModel.runReembed()
        advanceUntilIdle()
        // Final emission of 1f clears the in-flight indicator (null), per VM contract.
        assertNull(viewModel.uiState.value.reembedProgress)
    }

    @Test
    fun `given recorded search scores when init then averageSimilarityScore mirrors the tracker`() = runTest {
        searchStatsTracker.record(listOf(0.8f, 0.6f))
        val vm = newViewModel()
        advanceUntilIdle()
        assertEquals(0.7f, vm.uiState.value.averageSimilarityScore!!, 0.0001f)
    }

    @Test
    fun `given provider mismatch when init then lastReembedProviderId is mirrored into uiState`() = runTest {
        // Given — vectors were created with OpenAI but USE is now active.
        every { settings.lastReembedProviderId } returns
            MutableStateFlow<String?>(EmbeddingProvider.ID_OPENAI_3_SMALL)
        val vm = newViewModel()

        advanceUntilIdle()

        // Then — the state carries the mismatch the screen turns into a banner.
        val state = vm.uiState.value
        assertEquals(EmbeddingProvider.ID_OPENAI_3_SMALL, state.lastReembedProviderId)
        assertEquals(EmbeddingProvider.ID_USE, state.activeEmbeddingProviderId)
    }

    @Test
    fun `given successful reembed when runReembed then the active provider is recorded as baseline`() = runTest {
        coEvery { reembed() } returns flowOf(0.5f, 1f)
        advanceUntilIdle()

        viewModel.runReembed()
        advanceUntilIdle()

        // The store is now in the active provider's space — the banner condition clears.
        coVerify { settings.setLastReembedProviderId(EmbeddingProvider.ID_USE) }
    }

    @Test
    fun `given confirmed memory wipe when performed then the active provider is recorded as baseline`() = runTest {
        every { context.getString(app.knotwork.android.R.string.destructive_typed_keyword) } returns "yes"
        advanceUntilIdle()

        viewModel.stageClearMemory()
        viewModel.updateDestructiveTypedInput("yes")
        viewModel.confirmDestructive()
        advanceUntilIdle()

        coVerify { clearMemory() }
        coVerify { settings.setLastReembedProviderId(EmbeddingProvider.ID_USE) }
    }

    @Test
    fun `runBackendProbe persists outcome and surfaces snackbar`() = runTest {
        coEvery { testBackend() } returns TestProbeResult(
            tokensGenerated = 100,
            durationMs = 500L,
            timestampMs = 0L,
            success = true,
        )
        advanceUntilIdle()
        viewModel.runBackendProbe()
        advanceUntilIdle()
        coVerify { testBackend() }
        assertNotNull(viewModel.uiState.value.snackbarMessage)
    }

    @Test
    fun `requestHighlight stores the anchor and highlightConsumed clears it`() = runTest {
        advanceUntilIdle()
        viewModel.requestHighlight("TEMPERATURE")
        assertEquals("TEMPERATURE", viewModel.uiState.value.pendingHighlightAnchor)
        viewModel.highlightConsumed()
        assertNull(viewModel.uiState.value.pendingHighlightAnchor)
    }

    @Test
    fun `resolveHighlight targets the owning category and seeds Advanced for advanced rows`() = runTest {
        advanceUntilIdle()
        // TEMPERATURE is an Advanced Generation row.
        val onGeneration = viewModel.resolveHighlight("TEMPERATURE", DomainSettingsCategoryId.GENERATION)
        assertEquals("TEMPERATURE", onGeneration.key)
        assertEquals(true, onGeneration.advancedExpanded)
        // The same anchor does not target a different category.
        val onMemory = viewModel.resolveHighlight("TEMPERATURE", DomainSettingsCategoryId.MEMORY)
        assertNull(onMemory.key)
        // An unknown anchor resolves to nothing.
        assertNull(viewModel.resolveHighlight("NOT_A_KEY", DomainSettingsCategoryId.GENERATION).key)
    }

    @Test
    fun `onSearchQueryChange publishes synonym hits and clearSearch empties them`() = runTest {
        advanceUntilIdle()
        // "npu" matches LOCAL_MODEL_BACKEND via its registry synonym.
        viewModel.onSearchQueryChange("npu")
        assertEquals("npu", viewModel.uiState.value.searchQuery)
        assertTrue(viewModel.uiState.value.searchResults.any { it.anchorKey == "LOCAL_MODEL_BACKEND" })
        viewModel.clearSearch()
        assertEquals("", viewModel.uiState.value.searchQuery)
        assertTrue(viewModel.uiState.value.searchResults.isEmpty())
    }

    // ─── External automation: the consent gate ───────────────────────────────

    @Test
    fun `given the external switch moved on when staged then nothing is persisted yet`() = runTest {
        advanceUntilIdle()

        viewModel.setExternalAutomationEnabled(true)
        advanceUntilIdle()

        // The invariant the whole dialog exists for: the contract does not open
        // because the switch was touched, only because the user agreed to it.
        assertTrue(viewModel.uiState.value.pendingExternalAutomationConsent)
        coVerify(exactly = 0) { settings.setExternalAutomationEnabled(any()) }
    }

    @Test
    fun `given staged consent when confirmed then the contract is switched on and the dialog closes`() = runTest {
        advanceUntilIdle()
        viewModel.setExternalAutomationEnabled(true)
        advanceUntilIdle()

        viewModel.confirmExternalAutomationConsent()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.pendingExternalAutomationConsent)
        coVerify(exactly = 1) { settings.setExternalAutomationEnabled(true) }
    }

    @Test
    fun `given staged consent when dismissed then the contract stays as it was`() = runTest {
        advanceUntilIdle()
        viewModel.setExternalAutomationEnabled(true)
        advanceUntilIdle()

        viewModel.dismissExternalAutomationConsent()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.pendingExternalAutomationConsent)
        coVerify(exactly = 0) { settings.setExternalAutomationEnabled(any()) }
    }

    @Test
    fun `given the external switch moved off when applied then it is persisted without a dialog`() = runTest {
        advanceUntilIdle()

        viewModel.setExternalAutomationEnabled(false)
        advanceUntilIdle()

        // Closing an entry point never asks: a confirmation on the way out would
        // make the consent copy's "one tap turns it off" promise false.
        assertFalse(viewModel.uiState.value.pendingExternalAutomationConsent)
        coVerify(exactly = 1) { settings.setExternalAutomationEnabled(false) }
    }

    @Test
    fun `given a staged consent when the switch is moved off then the dialog is dropped`() = runTest {
        advanceUntilIdle()
        viewModel.setExternalAutomationEnabled(true)
        advanceUntilIdle()

        viewModel.setExternalAutomationEnabled(false)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.pendingExternalAutomationConsent)
        coVerify(exactly = 1) { settings.setExternalAutomationEnabled(false) }
    }

    private fun newViewModel(): SettingsViewModel = SettingsViewModel(
        appContext = context,
        settingsRepository = settings,
        apiKeyRepository = apiKeys,
        localModelRepository = localModels,
        memoryRepository = memory,
        identityRepository = identity,
        testBackendUseCase = testBackend,
        resetSamplingDefaultsUseCase = resetSampling,
        resetToRecommendedDefaultsUseCase = resetToRecommended,
        clearAllMemoryUseCase = clearMemory,
        exportMemoryBaseUseCase = exportMemory,
        memoryImportUseCase = memoryImport,
        reembedAllMemoriesUseCase = reembed,
        getSystemPromptVariableCatalogUseCase = variableCatalog,
        embeddingProviders = embeddingProviders,
        memorySearchStatsTracker = searchStatsTracker,
        pipelineRepository = io.mockk.mockk(relaxed = true) {
            io.mockk.every { getAllPipelines() } returns kotlinx.coroutines.flow.flowOf(emptyList())
        },
        setSurfacePipelineUseCase = io.mockk.mockk(relaxed = true),
        externalAutomationJournal = externalAutomationJournal,
    )
}
