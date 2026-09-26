package app.knotwork.android.presentation.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.R
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.EntrySurface
import app.knotwork.android.domain.models.MemoryImportStrategy
import app.knotwork.android.domain.models.ProviderId
import app.knotwork.android.domain.models.ToolApprovalPolicy
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.ExternalAutomationJournalRepository
import app.knotwork.android.domain.repositories.IdentityRepository
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.EmbeddingProvider
import app.knotwork.android.domain.services.MemorySearchStatsTracker
import app.knotwork.android.domain.settings.SettingEntry
import app.knotwork.android.domain.settings.SettingTier
import app.knotwork.android.domain.settings.SettingsCategoryId
import app.knotwork.android.domain.settings.SettingsRegistry
import app.knotwork.android.domain.settings.SettingsSearchEngine
import app.knotwork.android.domain.settings.anchorKey
import app.knotwork.android.domain.usecases.ClearAllMemoryUseCase
import app.knotwork.android.domain.usecases.ExportMemoryBaseUseCase
import app.knotwork.android.domain.usecases.GetSystemPromptVariableCatalogUseCase
import app.knotwork.android.domain.usecases.MemoryImportUseCase
import app.knotwork.android.domain.usecases.ReembedAllMemoriesUseCase
import app.knotwork.android.domain.usecases.ResetSamplingDefaultsUseCase
import app.knotwork.android.domain.usecases.ResetToRecommendedDefaultsUseCase
import app.knotwork.android.domain.usecases.SetSurfacePipelineUseCase
import app.knotwork.android.domain.usecases.TestBackendUseCase
import app.knotwork.design.components.dialogs.typedConfirmMatches
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/**
 * ViewModel backing the Settings screen.
 *
 * A thin coordinator over eight per-category delegates — Generation, Models,
 * Memory, Pipelines, Tools, Background, Privacy, About — each sharing this
 * ViewModel's [viewModelScope] and the single [SettingsUiState] reducer
 * ([_uiState]). The delegates own their category's observers, mutators and
 * validation (the `ChatHome*Delegate` pattern); this class keeps the
 * cross-cutting concerns that span categories: the typed-confirm destructive
 * gate (Clear memory → Memory, Reset settings → About) and the one-shot
 * snackbar surface. Public mutator methods are kept as thin forwarders so the
 * existing screen and tests bind to the same observable surface.
 *
 * Restart-required is owned by [ModelsSettingsDelegate] (backend / Ollama
 * base-URL baselines); see its KDoc for the baseline-capture ordering.
 */
@HiltViewModel
@Suppress("LongParameterList", "TooManyFunctions")
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    settingsRepository: SettingsRepository,
    apiKeyRepository: ApiKeyRepository,
    localModelRepository: LocalModelRepository,
    memoryRepository: MemoryRepository,
    identityRepository: IdentityRepository,
    testBackendUseCase: TestBackendUseCase,
    resetSamplingDefaultsUseCase: ResetSamplingDefaultsUseCase,
    resetToRecommendedDefaultsUseCase: ResetToRecommendedDefaultsUseCase,
    clearAllMemoryUseCase: ClearAllMemoryUseCase,
    exportMemoryBaseUseCase: ExportMemoryBaseUseCase,
    memoryImportUseCase: MemoryImportUseCase,
    reembedAllMemoriesUseCase: ReembedAllMemoriesUseCase,
    getSystemPromptVariableCatalogUseCase: GetSystemPromptVariableCatalogUseCase,
    embeddingProviders: Map<String, @JvmSuppressWildcards EmbeddingProvider>,
    memorySearchStatsTracker: MemorySearchStatsTracker,
    pipelineRepository: PipelineRepository,
    setSurfacePipelineUseCase: SetSurfacePipelineUseCase,
    externalAutomationJournal: ExternalAutomationJournalRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val generation = GenerationSettingsDelegate(
        scope = viewModelScope,
        state = _uiState,
        appContext = appContext,
        settingsRepository = settingsRepository,
        getSystemPromptVariableCatalogUseCase = getSystemPromptVariableCatalogUseCase,
        resetSamplingDefaultsUseCase = resetSamplingDefaultsUseCase,
    )

    private val models = ModelsSettingsDelegate(
        scope = viewModelScope,
        state = _uiState,
        appContext = appContext,
        settingsRepository = settingsRepository,
        apiKeyRepository = apiKeyRepository,
        localModelRepository = localModelRepository,
        testBackendUseCase = testBackendUseCase,
    )

    private val memory = MemorySettingsDelegate(
        scope = viewModelScope,
        state = _uiState,
        appContext = appContext,
        settingsRepository = settingsRepository,
        memoryRepository = memoryRepository,
        memorySearchStatsTracker = memorySearchStatsTracker,
        embeddingProviders = embeddingProviders,
        clearAllMemoryUseCase = clearAllMemoryUseCase,
        exportMemoryBaseUseCase = exportMemoryBaseUseCase,
        memoryImportUseCase = memoryImportUseCase,
        reembedAllMemoriesUseCase = reembedAllMemoriesUseCase,
    )

    private val pipelines = PipelinesSettingsDelegate(viewModelScope, _uiState, settingsRepository)

    private val tools = ToolsSettingsDelegate(viewModelScope, _uiState, settingsRepository)

    private val background =
        BackgroundSettingsDelegate(
            viewModelScope,
            _uiState,
            settingsRepository,
            pipelineRepository,
            setSurfacePipelineUseCase,
            externalAutomationJournal,
        )

    private val privacy = PrivacySettingsDelegate(
        scope = viewModelScope,
        state = _uiState,
        settingsRepository = settingsRepository,
    )

    private val about = AboutSettingsDelegate(
        scope = viewModelScope,
        state = _uiState,
        appContext = appContext,
        identityRepository = identityRepository,
        resetToRecommendedDefaultsUseCase = resetToRecommendedDefaultsUseCase,
    )

    // ─── Generation ────────────────────────────────────────────────────────

    fun updateSystemInstructions(value: String) = generation.updateSystemInstructions(value)
    fun insertVariable(placeholder: String) = generation.insertVariable(placeholder)
    fun setTemperature(value: Float) = generation.setTemperature(value)
    fun setTopK(value: Int) = generation.setTopK(value)
    fun setTopP(value: Float) = generation.setTopP(value)
    fun setMaxContextLength(value: Int) = generation.setMaxContextLength(value)
    fun setAudioMaxDurationSec(seconds: Int) = generation.setAudioMaxDurationSec(seconds)
    fun resetSamplingDefaults() = generation.resetSamplingDefaults()

    // ─── Tools & workspace ───────────────────────────────────────────────────

    fun setToolApprovalPolicy(policy: ToolApprovalPolicy) = tools.setToolApprovalPolicy(policy)
    fun setBlockDestructiveTools(blocked: Boolean) = tools.setBlockDestructiveTools(blocked)
    fun setBlockNetworkFromLocalModel(blocked: Boolean) = tools.setBlockNetworkFromLocalModel(blocked)
    fun setToolCallTimeoutMs(timeoutMs: Long) = tools.setToolCallTimeoutMs(timeoutMs)
    fun setWorkspaceMaxFileSizeBytes(bytes: Long) = tools.setWorkspaceMaxFileSizeBytes(bytes)
    fun setWorkspaceMaxTotalBytes(bytes: Long) = tools.setWorkspaceMaxTotalBytes(bytes)
    fun setWorkspaceReadTokenBudget(tokens: Int) = tools.setWorkspaceReadTokenBudget(tokens)
    fun setHttpToolMaxResponseBytes(bytes: Long) = tools.setHttpToolMaxResponseBytes(bytes)

    // ─── Run limits & structured output ──────────────────────────────────────

    fun setPipelineMaxNestingDepth(depth: Int) = pipelines.setPipelineMaxNestingDepth(depth)
    fun setStructuredOutputMaxRepairs(count: Int) = pipelines.setStructuredOutputMaxRepairs(count)

    // ─── Background & triggers ───────────────────────────────────────────────

    fun setResumeMaxAgeHours(hours: Int) = background.setResumeMaxAgeHours(hours)
    fun setBackgroundApprovalWindowHours(hours: Int) = background.setBackgroundApprovalWindowHours(hours)
    fun setScheduledTaskNotificationsEnabled(enabled: Boolean) =
        background.setScheduledTaskNotificationsEnabled(enabled)

    /** Toggles whether shares accumulate in one Shared chat (`true`) or open a new chat each time. */
    fun setShareReuseSession(reuse: Boolean) = background.setShareReuseSession(reuse)

    /** Binds (or clears, with `null`) the pipeline run by an entry [surface] (share / tile / external). */
    fun setSurfacePipeline(surface: EntrySurface, pipelineId: String?) =
        background.setSurfacePipeline(surface, pipelineId)

    /**
     * Moves the external-automation master switch. Switching it on stages the
     * consent dialog rather than persisting; switching it off persists at once.
     */
    fun setExternalAutomationEnabled(enabled: Boolean) = background.setExternalAutomationEnabled(enabled)

    /** Persists the switched-on external-automation contract the user just consented to. */
    fun confirmExternalAutomationConsent() = background.confirmExternalAutomationConsent()

    /** Drops the staged external-automation consent; the contract stays as it was. */
    fun dismissExternalAutomationConsent() = background.dismissExternalAutomationConsent()

    // ─── Privacy ─────────────────────────────────────────────────────────────

    fun setTraceRetentionRunsPerSession(runs: Int) = privacy.setTraceRetentionRunsPerSession(runs)
    fun setTraceRetentionMaxAgeDays(days: Int) = privacy.setTraceRetentionMaxAgeDays(days)
    fun setCrashReportingEnabled(enabled: Boolean) = privacy.setCrashReportingEnabled(enabled)

    // ─── Local model + providers ─────────────────────────────────────────────

    fun setLocalModelBackend(backend: String) = models.setLocalModelBackend(backend)
    fun runBackendProbe() = models.runBackendProbe()
    fun providerForId(id: String): ProviderId? = models.providerForId(id)

    /** Called by the screen after a restart action; resets the baseline so the banner stays gone. */
    fun acknowledgeRestart() = models.acknowledgeRestart()

    // ─── Memory ──────────────────────────────────────────────────────────────

    fun setAutoExtractEnabled(enabled: Boolean) = memory.setAutoExtractEnabled(enabled)
    fun setVerboseMemoryLoggingEnabled(enabled: Boolean) = memory.setVerboseMemoryLoggingEnabled(enabled)
    fun setMemorySearchTopK(value: Int) = memory.setMemorySearchTopK(value)
    fun setMemorySearchThreshold(value: Float) = memory.setMemorySearchThreshold(value)
    fun setMemoryRecencyHalfLifeDays(days: Int) = memory.setMemoryRecencyHalfLifeDays(days)
    fun setMemoryCompactionEnabled(enabled: Boolean) = memory.setMemoryCompactionEnabled(enabled)
    fun setMemoryCompactionAgeDays(days: Int) = memory.setMemoryCompactionAgeDays(days)
    fun setMaxMemoryChunks(limit: Int) = memory.setMaxMemoryChunks(limit)
    fun setChatHistoryCompressionEnabled(enabled: Boolean) = memory.setChatHistoryCompressionEnabled(enabled)
    fun setChatHistoryCompressionThresholdTokens(tokens: Int) = memory.setChatHistoryCompressionThresholdTokens(tokens)
    fun setChatHistoryLiveWindowSize(size: Int) = memory.setChatHistoryLiveWindowSize(size)
    fun setActiveEmbeddingProviderId(id: String) = memory.setActiveEmbeddingProviderId(id)
    fun setMemorySummaryDefaultLimit(limit: Int) = memory.setMemorySummaryDefaultLimit(limit)
    fun clearMemoryValidationError() = memory.clearMemoryValidationError()
    fun exportMemoryBase(target: OutputStream) = memory.exportMemoryBase(target)
    fun importMemory(source: InputStream) = memory.importMemory(source)
    fun confirmImport(strategy: MemoryImportStrategy) = memory.confirmImport(strategy)
    fun cancelImport() = memory.cancelImport()
    fun runReembed() = memory.runReembed()

    // ─── Destructive actions (cross-cutting coordination) ─────────────────────

    fun stageClearMemory() {
        _uiState.update {
            it.copy(pendingDestructive = PendingDestructiveAction.ClearMemory, destructiveTypedInput = "")
        }
    }

    fun stageResetSettings() {
        _uiState.update {
            it.copy(pendingDestructive = PendingDestructiveAction.ResetSettings, destructiveTypedInput = "")
        }
    }

    fun updateDestructiveTypedInput(value: String) {
        _uiState.update { it.copy(destructiveTypedInput = value) }
    }

    fun cancelDestructive() {
        _uiState.update { it.copy(pendingDestructive = null, destructiveTypedInput = "") }
    }

    fun confirmDestructive() {
        val pending = _uiState.value.pendingDestructive ?: return
        when (pending) {
            // Wiping memory is irreversible — keep the typed-keyword gate.
            PendingDestructiveAction.ClearMemory -> {
                val keyword = appContext.getString(R.string.destructive_typed_keyword)
                if (!typedConfirmMatches(input = _uiState.value.destructiveTypedInput, keyword = keyword)) return
                memory.performClearMemory()
            }
            // Resetting settings touches no user data — a plain confirm is enough.
            PendingDestructiveAction.ResetSettings -> about.performResetSettings()
        }
        _uiState.update { it.copy(pendingDestructive = null, destructiveTypedInput = "") }
    }

    // ─── Surface ───────────────────────────────────────────────────────────

    fun snackbarShown() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    // ─── Settings search ─────────────────────────────────────────────────────

    /**
     * Localized settings-search index, built once from the registry. Lazy so the
     * (~50 string-resource) resolution happens on first query, not at VM init.
     */
    private val searchIndex by lazy { SettingsSearchCatalog.buildIndex(appContext) }

    /** Registry rows keyed by their deep-link anchor for O(1) highlight resolution. */
    private val entriesByAnchor: Map<String, SettingEntry> =
        SettingsRegistry.allEntries().associateBy { it.anchorKey() }

    /**
     * Filters the settings index by [query] and publishes the ranked hits. Runs
     * the pure-domain [SettingsSearchEngine] over the in-memory index — cheap
     * enough to run synchronously per keystroke for the ~50-row catalogue.
     *
     * @param query Raw search query; blank clears the result list.
     */
    fun onSearchQueryChange(query: String) {
        val results = SettingsSearchEngine.search(query, searchIndex).map { it.toHubRow() }
        _uiState.update { it.copy(searchQuery = query, searchResults = results) }
    }

    /** Clears the active search query and its results. */
    fun clearSearch() {
        _uiState.update { it.copy(searchQuery = "", searchResults = emptyList()) }
    }

    // ─── Settings-search deep-link highlight ─────────────────────────────────

    /**
     * Records the anchor of a settings row a search result asked to deep-link to.
     * The owning category sub-screen reads it on arrival, scrolls the row into
     * view and flashes it.
     *
     * @param anchor Stable anchor key of the target row.
     */
    fun requestHighlight(anchor: String) {
        _uiState.update { it.copy(pendingHighlightAnchor = anchor) }
    }

    /** Clears the pending highlight once the destination screen has flashed it. */
    fun highlightConsumed() {
        _uiState.update { it.copy(pendingHighlightAnchor = null) }
    }

    /**
     * Resolves the pending highlight for [category]: whether [anchor] belongs to
     * it and, if so, whether the target row sits behind the Advanced disclosure.
     * Keeps the registry lookup out of the composition (the screen only reads
     * this result and schedules the flash).
     *
     * @param anchor Current [SettingsUiState.pendingHighlightAnchor].
     * @param category The category sub-screen asking.
     * @return The resolved highlight; [SettingsHighlight.key] is `null` when the
     *   pending anchor does not target this category.
     */
    fun resolveHighlight(anchor: String?, category: SettingsCategoryId): SettingsHighlight {
        val entry = anchor?.let { entriesByAnchor[it] }
        val belongs = entry != null && entry.categoryId == category
        return SettingsHighlight(
            key = anchor.takeIf { belongs },
            advancedExpanded = belongs && entry?.tier == SettingTier.ADVANCED,
        )
    }

    companion object {
        /**
         * Re-exposed for tests: maps a [CloudProvider] back to its [ProviderId].
         */
        fun providerIdOf(provider: CloudProvider): ProviderId =
            ProviderId.entries.first { it.cloudProvider == provider }
    }
}

/**
 * Resolved settings-search deep-link highlight for one category sub-screen.
 *
 * @property key Anchor of the row to highlight, or `null` when the pending
 *   highlight does not target this category.
 * @property advancedExpanded `true` when the target row lives behind the
 *   in-category Advanced disclosure, so the screen seeds it open.
 */
data class SettingsHighlight(val key: String?, val advancedExpanded: Boolean)
