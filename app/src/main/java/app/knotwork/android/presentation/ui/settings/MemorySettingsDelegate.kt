package app.knotwork.android.presentation.ui.settings

import android.content.Context
import android.text.format.Formatter
import app.knotwork.android.R
import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.MemoryExportDocument
import app.knotwork.android.domain.models.MemoryImportOutcome
import app.knotwork.android.domain.models.MemoryImportStrategy
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.EmbeddingProvider
import app.knotwork.android.domain.services.MemorySearchStatsTracker
import app.knotwork.android.domain.text.ImportedText
import app.knotwork.android.domain.text.toDisplaySafe
import app.knotwork.android.domain.usecases.ClearAllMemoryUseCase
import app.knotwork.android.domain.usecases.ExportMemoryBaseUseCase
import app.knotwork.android.domain.usecases.MemoryImportResult
import app.knotwork.android.domain.usecases.MemoryImportUseCase
import app.knotwork.android.domain.usecases.ReembedAllMemoriesUseCase
import app.knotwork.android.presentation.common.BoundedText
import app.knotwork.android.presentation.common.memoryImportLimitBytes
import app.knotwork.android.presentation.common.readTextWithin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.InputStream
import java.io.OutputStream

/**
 * Memory category delegate of [SettingsViewModel].
 *
 * The largest category: owns the live memory stats, every memory-tuning
 * parameter (with range validation), the embedding-provider selection, and the
 * memory-data actions (export / import / re-embed / clear). Shares the
 * ViewModel's [scope] and single [SettingsUiState] reducer.
 *
 * Out-of-range tuning edits are rejected at this layer — the value stays
 * unpersisted and a [MemoryValidationError] is surfaced — so a malformed caller
 * can never write a degenerate bound.
 *
 * @property scope The ViewModel's `viewModelScope`.
 * @property state The ViewModel's single source-of-truth state flow.
 * @property appContext Application context for snackbar copy.
 * @property settingsRepository Persistence for the memory-tuning settings.
 * @property memoryRepository Source of the live aggregate stats.
 * @property memorySearchStatsTracker Rolling average of recent similarity scores.
 * @property embeddingProviders Hilt-registered embedding providers (dropdown source + validation set).
 * @property clearAllMemoryUseCase Wipes the entire memory base.
 * @property exportMemoryBaseUseCase Serialises the memory base to a stream.
 * @property memoryImportUseCase Parses + imports a memory export file.
 * @property reembedAllMemoriesUseCase Re-embeds every stored chunk with the active provider.
 */
@Suppress("TooManyFunctions")
class MemorySettingsDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<SettingsUiState>,
    private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val memoryRepository: MemoryRepository,
    private val memorySearchStatsTracker: MemorySearchStatsTracker,
    private val embeddingProviders: Map<String, EmbeddingProvider>,
    private val clearAllMemoryUseCase: ClearAllMemoryUseCase,
    private val exportMemoryBaseUseCase: ExportMemoryBaseUseCase,
    private val memoryImportUseCase: MemoryImportUseCase,
    private val reembedAllMemoriesUseCase: ReembedAllMemoriesUseCase,
) {

    init {
        loadEmbeddingProviders()
        observeMemoryPreferences()
    }

    /**
     * Materialises the Hilt-registered embedding-provider map into the dropdown
     * options. The on-device default ([EmbeddingProvider.ID_USE]) is hoisted to
     * the top; the rest follow in a stable display-name order so the list never
     * re-shuffles between reads.
     */
    private fun loadEmbeddingProviders() {
        val options = embeddingProviders.values
            .map { EmbeddingProviderOption(id = it.id, displayName = it.displayName) }
            .sortedWith(
                compareByDescending<EmbeddingProviderOption> { it.id == EmbeddingProvider.ID_USE }
                    .thenBy { it.displayName },
            )
        state.update { it.copy(embeddingProviderOptions = options) }
    }

    @Suppress("LongMethod")
    private fun observeMemoryPreferences() {
        memoryRepository.observeStats().onEach { stats ->
            state.update { it.copy(memoryStats = stats) }
        }.launchIn(scope)

        memorySearchStatsTracker.averageScore.onEach { value ->
            state.update { it.copy(averageSimilarityScore = value) }
        }.launchIn(scope)

        settingsRepository.autoExtractEnabled.onEach { value ->
            state.update { it.copy(autoExtractEnabled = value) }
        }.launchIn(scope)

        settingsRepository.memorySearchTopK.onEach { value ->
            state.update { it.copy(memorySearchTopK = value) }
        }.launchIn(scope)

        settingsRepository.memorySearchThreshold.onEach { value ->
            state.update { it.copy(memorySearchThreshold = value) }
        }.launchIn(scope)

        settingsRepository.memoryRecencyHalfLifeDays.onEach { value ->
            state.update { it.copy(memoryRecencyHalfLifeDays = value) }
        }.launchIn(scope)

        settingsRepository.memoryCompactionEnabled.onEach { value ->
            state.update { it.copy(memoryCompactionEnabled = value) }
        }.launchIn(scope)

        settingsRepository.memoryCompactionAgeDays.onEach { value ->
            state.update { it.copy(memoryCompactionAgeDays = value) }
        }.launchIn(scope)

        settingsRepository.maxMemoryChunks.onEach { value ->
            state.update { it.copy(maxMemoryChunks = value) }
        }.launchIn(scope)

        settingsRepository.chatHistoryCompressionEnabled.onEach { value ->
            state.update { it.copy(chatHistoryCompressionEnabled = value) }
        }.launchIn(scope)

        settingsRepository.chatHistoryCompressionThresholdTokens.onEach { value ->
            state.update { it.copy(chatHistoryCompressionThresholdTokens = value) }
        }.launchIn(scope)

        settingsRepository.chatHistoryLiveWindowSize.onEach { value ->
            state.update { it.copy(chatHistoryLiveWindowSize = value) }
        }.launchIn(scope)

        settingsRepository.activeEmbeddingProviderId.onEach { value ->
            state.update { it.copy(activeEmbeddingProviderId = value) }
        }.launchIn(scope)

        settingsRepository.lastReembedProviderId.onEach { value ->
            state.update { it.copy(lastReembedProviderId = value) }
        }.launchIn(scope)

        settingsRepository.verboseMemoryLoggingEnabled.onEach { value ->
            state.update { it.copy(verboseMemoryLoggingEnabled = value) }
        }.launchIn(scope)

        settingsRepository.memorySummaryDefaultLimit.onEach { value ->
            state.update { it.copy(memorySummaryDefaultLimit = value) }
        }.launchIn(scope)
    }

    /** Persists the "Auto-extract from conversations" toggle. */
    fun setAutoExtractEnabled(enabled: Boolean) {
        scope.launch { settingsRepository.setAutoExtractEnabled(enabled) }
    }

    /**
     * Persists the default `$MEMORY_SUMMARY` chunk limit. The slider constrains
     * the value to `MEMORY_SUMMARY_LIMIT_MIN..MEMORY_SUMMARY_LIMIT_MAX`, so the
     * write needs no separate range rejection.
     */
    fun setMemorySummaryDefaultLimit(limit: Int) {
        scope.launch { settingsRepository.setMemorySummaryDefaultLimit(limit) }
    }

    /** Persists the verbose memory-logging toggle (logs every memory retrieval to the console). */
    fun setVerboseMemoryLoggingEnabled(enabled: Boolean) {
        scope.launch { settingsRepository.setVerboseMemoryLoggingEnabled(enabled) }
    }

    /**
     * Persists the memory-retrieval top-K. Out-of-range values are rejected with
     * [MemoryValidationError.SearchTopK] and stay unpersisted.
     */
    fun setMemorySearchTopK(value: Int) {
        if (value < SettingsDefaults.MEMORY_SEARCH_TOP_K_MIN || value > SettingsDefaults.MEMORY_SEARCH_TOP_K_MAX) {
            rejectMemoryEdit(MemoryValidationError.SearchTopK)
            return
        }
        clearMemoryValidationError()
        scope.launch { settingsRepository.setMemorySearchTopK(value) }
    }

    /**
     * Persists the memory-retrieval similarity threshold. Out-of-range values
     * are rejected with [MemoryValidationError.SearchThreshold].
     */
    fun setMemorySearchThreshold(value: Float) {
        if (value < SettingsDefaults.MEMORY_SEARCH_THRESHOLD_MIN ||
            value > SettingsDefaults.MEMORY_SEARCH_THRESHOLD_MAX
        ) {
            rejectMemoryEdit(MemoryValidationError.SearchThreshold)
            return
        }
        clearMemoryValidationError()
        scope.launch { settingsRepository.setMemorySearchThreshold(value) }
    }

    /**
     * Persists the recency half-life used by the memory re-ranker. Out-of-range
     * values are rejected with [MemoryValidationError.RecencyHalfLife].
     */
    fun setMemoryRecencyHalfLifeDays(days: Int) {
        if (days < SettingsDefaults.MEMORY_RECENCY_HALF_LIFE_DAYS_MIN ||
            days > SettingsDefaults.MEMORY_RECENCY_HALF_LIFE_DAYS_MAX
        ) {
            rejectMemoryEdit(MemoryValidationError.RecencyHalfLife)
            return
        }
        clearMemoryValidationError()
        scope.launch { settingsRepository.setMemoryRecencyHalfLifeDays(days) }
    }

    /** Persists the background-compaction toggle. Booleans cannot be out-of-range. */
    fun setMemoryCompactionEnabled(enabled: Boolean) {
        clearMemoryValidationError()
        scope.launch { settingsRepository.setMemoryCompactionEnabled(enabled) }
    }

    /**
     * Persists the compaction age window. Out-of-range values are rejected with
     * [MemoryValidationError.CompactionAge].
     */
    fun setMemoryCompactionAgeDays(days: Int) {
        if (days < SettingsDefaults.MEMORY_COMPACTION_AGE_DAYS_MIN ||
            days > SettingsDefaults.MEMORY_COMPACTION_AGE_DAYS_MAX
        ) {
            rejectMemoryEdit(MemoryValidationError.CompactionAge)
            return
        }
        clearMemoryValidationError()
        scope.launch { settingsRepository.setMemoryCompactionAgeDays(days) }
    }

    /**
     * Persists the hard ceiling on stored chunks. Out-of-range values are
     * rejected with [MemoryValidationError.MaxChunks].
     */
    fun setMaxMemoryChunks(limit: Int) {
        if (limit < SettingsDefaults.MAX_MEMORY_CHUNKS_MIN || limit > SettingsDefaults.MAX_MEMORY_CHUNKS_MAX) {
            rejectMemoryEdit(MemoryValidationError.MaxChunks)
            return
        }
        clearMemoryValidationError()
        scope.launch { settingsRepository.setMaxMemoryChunks(limit) }
    }

    /** Toggles long-session chat-history compression. */
    fun setChatHistoryCompressionEnabled(enabled: Boolean) {
        clearMemoryValidationError()
        scope.launch { settingsRepository.setChatHistoryCompressionEnabled(enabled) }
    }

    /**
     * Persists the chat-history compression token threshold. Out-of-range values
     * are rejected with [MemoryValidationError.CompressionThreshold].
     */
    fun setChatHistoryCompressionThresholdTokens(tokens: Int) {
        if (tokens < SettingsDefaults.CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS_MIN ||
            tokens > SettingsDefaults.CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS_MAX
        ) {
            rejectMemoryEdit(MemoryValidationError.CompressionThreshold)
            return
        }
        clearMemoryValidationError()
        scope.launch { settingsRepository.setChatHistoryCompressionThresholdTokens(tokens) }
    }

    /**
     * Persists the chat-history live-window size. Out-of-range values are
     * rejected with [MemoryValidationError.LiveWindow].
     */
    fun setChatHistoryLiveWindowSize(size: Int) {
        if (size < SettingsDefaults.CHAT_HISTORY_LIVE_WINDOW_MIN ||
            size > SettingsDefaults.CHAT_HISTORY_LIVE_WINDOW_MAX
        ) {
            rejectMemoryEdit(MemoryValidationError.LiveWindow)
            return
        }
        clearMemoryValidationError()
        scope.launch { settingsRepository.setChatHistoryLiveWindowSize(size) }
    }

    /**
     * Persists the active embedding provider. The id is validated against the set
     * of registered providers; an unknown id is rejected with
     * [MemoryValidationError.UnknownEmbeddingProvider] and nothing is written.
     */
    fun setActiveEmbeddingProviderId(id: String) {
        if (!embeddingProviders.containsKey(id)) {
            rejectMemoryEdit(MemoryValidationError.UnknownEmbeddingProvider)
            return
        }
        clearMemoryValidationError()
        scope.launch { settingsRepository.setActiveEmbeddingProviderId(id) }
    }

    /** Clears the transient memory-tuning validation error (e.g. after the screen showed it). */
    fun clearMemoryValidationError() {
        if (state.value.memoryValidationError != null) {
            state.update { it.copy(memoryValidationError = null) }
        }
    }

    private fun rejectMemoryEdit(error: MemoryValidationError) {
        state.update { it.copy(memoryValidationError = error) }
    }

    /**
     * Exports the entire memory base into the supplied [OutputStream]. Called by
     * the screen after the SAF launcher returns a writable URI.
     */
    fun exportMemoryBase(target: OutputStream) {
        scope.launch {
            try {
                val count = exportMemoryBaseUseCase(target)
                emitSnackbar(appContext.getString(R.string.settings_memory_export_success, count))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.w(e, "Memory export failed")
                emitSnackbar(appContext.getString(R.string.settings_memory_export_failed, e.message.orEmpty()))
            }
        }
    }

    /**
     * Parses a memory export file from the supplied [InputStream] and, on a
     * usable parse, stages the import dialog (strategy choice + any
     * provider/schema warnings). A failed parse surfaces an error snackbar and
     * stages nothing. The stream is fully read and closed here; persistence is
     * deferred to [confirmImport] once the user picks a strategy.
     */
    fun importMemory(source: InputStream) {
        scope.launch {
            // Bounded by the heap, not by a fixed size: the app's own export of a
            // full memory can be hundreds of MB, and what a device can parse is
            // what its heap holds (see `memoryImportLimitBytes`).
            val limitBytes = memoryImportLimitBytes(Runtime.getRuntime().maxMemory())
            val read = try {
                withContext(Dispatchers.IO) { source.use { it.readTextWithin(limitBytes) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.w(e, "Memory import: failed to read file")
                emitSnackbar(appContext.getString(R.string.settings_memory_import_failed, e.message.orEmpty()))
                return@launch
            }
            val jsonText = when (read) {
                is BoundedText.Read -> read.text
                is BoundedText.TooLarge -> {
                    emitSnackbar(
                        appContext.getString(
                            R.string.settings_memory_import_too_large,
                            Formatter.formatShortFileSize(appContext, read.limitBytes),
                        ),
                    )
                    return@launch
                }
            }

            when (val outcome = memoryImportUseCase.parse(jsonText)) {
                is MemoryImportOutcome.Failure ->
                    emitSnackbar(
                        appContext.getString(
                            R.string.settings_memory_import_failed,
                            // The message quotes the file; one bounded line whatever it holds.
                            outcome.message.toDisplaySafe(ImportedText.MAX_MESSAGE_LENGTH),
                        ),
                    )
                is MemoryImportOutcome.Success -> stagePendingImport(outcome.document, schemaMismatch = false)
                is MemoryImportOutcome.SchemaMismatch -> stagePendingImport(outcome.document, schemaMismatch = true)
            }
        }
    }

    /**
     * Stages [document] for the import dialog, computing whether the file's
     * embedding provider differs from the one active on this device.
     */
    private suspend fun stagePendingImport(document: MemoryExportDocument, schemaMismatch: Boolean) {
        state.update {
            it.copy(
                pendingImport = PendingMemoryImport(
                    document = document,
                    // Compare against the provider retrieval actually resolves to
                    // (on-device fallback included), not the raw persisted setting.
                    providerMismatch = memoryImportUseCase.isProviderMismatched(document),
                    schemaMismatch = schemaMismatch,
                ),
            )
        }
    }

    /**
     * Imports the staged document under [strategy], clears the dialog, and
     * surfaces a result snackbar. No-op when nothing is staged.
     */
    fun confirmImport(strategy: MemoryImportStrategy) {
        val pending = state.value.pendingImport ?: return
        state.update { it.copy(pendingImport = null) }
        scope.launch {
            try {
                val result = memoryImportUseCase.import(pending.document, strategy)
                emitSnackbar(importResultMessage(result))
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                Timber.w(error, "Memory import failed")
                emitSnackbar(appContext.getString(R.string.settings_memory_import_failed, error.message.orEmpty()))
            }
        }
    }

    /** Dismisses the import dialog without importing anything. */
    fun cancelImport() {
        state.update { it.copy(pendingImport = null) }
    }

    /**
     * Composes the post-import snackbar, appending the re-embedding note when the
     * imported chunks were flagged for lazy re-computation.
     */
    private fun importResultMessage(result: MemoryImportResult): String {
        val base = appContext.getString(
            R.string.settings_memory_import_success,
            result.imported,
            result.skipped,
        )
        return if (result.needsReembedding) {
            base + " " + appContext.getString(R.string.settings_memory_import_reembed_note)
        } else {
            base
        }
    }

    /** Re-embeds every stored chunk with the active provider, forwarding progress to the UI. */
    fun runReembed() {
        scope.launch {
            reembedAllMemoriesUseCase().collect { progress ->
                state.update { it.copy(reembedProgress = progress.takeIf { fraction -> fraction < 1f }) }
            }
            // The store is now consistent with the active provider — record it so
            // the "re-embed recommended" banner disappears.
            settingsRepository.setLastReembedProviderId(state.value.activeEmbeddingProviderId)
            emitSnackbar(appContext.getString(R.string.settings_memory_reembed_done))
        }
    }

    /**
     * Wipes the entire memory base (invoked from the typed-confirm destructive
     * gate). An empty store has no stale vectors, so the active provider is
     * recorded as the new baseline so the re-embed banner does not survive a wipe.
     */
    fun performClearMemory() {
        scope.launch {
            clearAllMemoryUseCase()
            settingsRepository.setLastReembedProviderId(state.value.activeEmbeddingProviderId)
            emitSnackbar(appContext.getString(R.string.settings_memory_cleared_snackbar))
        }
    }

    private fun emitSnackbar(message: String) {
        state.update { it.copy(snackbarMessage = message) }
    }
}
