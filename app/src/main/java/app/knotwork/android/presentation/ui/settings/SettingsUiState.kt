package app.knotwork.android.presentation.ui.settings

import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.ActiveModelMeta
import app.knotwork.android.domain.models.ExternalAutomationJournalEntry
import app.knotwork.android.domain.models.Identity
import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.MemoryExportDocument
import app.knotwork.android.domain.models.MemoryStats
import app.knotwork.android.domain.models.ProviderSummary
import app.knotwork.android.domain.models.TestProbeResult
import app.knotwork.android.domain.models.ToolApprovalPolicy
import app.knotwork.design.screens.settings.HubSearchResultRow

/**
 * Top-level Settings screen UI state. Aggregates every slice the
 * redesigned screen needs to render — kept as a single immutable data
 * class so `viewModel.uiState.collectAsState()` returns a snapshot
 * suitable for `derivedStateOf` skips.
 *
 * @property identity Current identity snapshot; `null` while the first
 *   read is in flight.
 * @property systemInstructions Live textarea content (user-editable).
 * @property variableCatalog Catalog of `$VARIABLE` placeholders surfaced
 *   in the chip row beneath the textarea.
 * @property toolApprovalPolicy Currently selected HITL policy.
 * @property blockDestructiveTools Mirror of the persisted toggle.
 * @property toolCallTimeoutMs Wall-clock deadline for one tool invocation.
 * @property workspaceMaxFileSizeBytes Per-file ceiling for the workspace.
 * @property workspaceMaxTotalBytes Workspace-wide size ceiling.
 * @property workspaceReadTokenBudget Token budget one `read_file` call returns.
 * @property httpToolMaxResponseBytes Ceiling on an `http_request` response body.
 * @property blockNetworkFromLocalModel Mirror of the persisted toggle.
 * @property runMaxTokens Interactive token ceiling. Held here only to build the
 *   run-limits entry-row summary — the limits themselves are owned by the
 *   run-limits screen and its own ViewModel.
 * @property capAutonomousSteps `pipelineMaxSteps`. Read-only here: the limit is
 *   edited on the run-limits screen, and this copy exists only to build the
 *   value summary on the entry row that leads there.
 * @property resumeMaxAgeHours Window (hours) during which an interrupted
 *   pipeline run can still be resumed from its checkpoint.
 * @property backgroundApprovalWindowHours Window (hours) during which a run
 *   parked on an unanswered background HITL request waits for the user's
 *   response before failing.
 * @property temperature / [topK] / [topP] / [maxContextLength] Sampling
 *   parameters mirrored from DataStore.
 * @property audioMaxDurationSec Maximum voice-input capture length (seconds)
 *   before recording auto-stops (Generation → Advanced).
 * @property pipelineMaxNestingDepth Maximum nesting depth allowed for
 *   PIPELINE-node sub-pipeline expansion (Pipelines → Advanced).
 * @property structuredOutputMaxRepairs Maximum structured-output repair
 *   attempts before a node falls back to its failure policy (Pipelines →
 *   Advanced).
 * @property memorySummaryDefaultLimit Default number of recent long-term
 *   memory chunks rendered into the `$MEMORY_SUMMARY` prompt variable
 *   (Memory → Advanced).
 * @property activeModelMeta Live snapshot of the active model card.
 * @property localModelBackend Wire key of the selected backend
 *   ([LocalBackend.key]).
 * @property lastTestProbeResult Most recent persisted probe outcome.
 * @property providers Collapsed external-provider rows.
 * @property memoryStats Live aggregate counters.
 * @property averageSimilarityScore Rolling average of recent similarity-search
 *   scores (session-scoped, from `MemorySearchStatsTracker`); `null` until a
 *   search has been recorded — the AVG SCORE cell then renders a dash.
 * @property memorySearchTopK How many ranked chunks a single retrieval
 *   returns into a node's context block.
 * @property memorySearchThreshold Minimum cosine-similarity score a chunk
 *   must reach to be surfaced during retrieval.
 * @property memoryRecencyHalfLifeDays Recency half-life (days) used by the
 *   memory re-ranker.
 * @property memoryCompactionEnabled Whether the daily background compaction
 *   pass is enabled.
 * @property memoryCompactionAgeDays Age (days) after which a non-pinned chunk
 *   becomes a compaction candidate.
 * @property maxMemoryChunks Hard ceiling on the number of stored chunks.
 * @property chatHistoryCompressionEnabled Whether long chat sessions are
 *   compressed (older tail summarised) once over the token budget.
 * @property chatHistoryCompressionThresholdTokens Approximate-token budget above
 *   which chat history is compressed.
 * @property chatHistoryLiveWindowSize Number of most-recent messages kept
 *   verbatim when compression is active.
 * @property activeEmbeddingProviderId Wire id of the selected embedding
 *   provider.
 * @property lastReembedProviderId Wire id of the provider the stored memory
 *   vectors were last (re-)embedded with, or `null` when unknown (no provider
 *   switch yet). A non-null value differing from [activeEmbeddingProviderId]
 *   surfaces the persistent "re-embed recommended" banner.
 * @property embeddingProviderOptions Available embedding providers (id +
 *   display name) for the Memory-section dropdown.
 * @property memoryValidationError Transient validation error surfaced when a
 *   memory-tuning mutator rejects an out-of-range / unknown value; `null` when
 *   the last edit was accepted.
 * @property reembedProgress `null` when no re-embed job is in flight;
 *   otherwise `0f..1f`.
 * @property scheduledTaskNotificationsEnabled Mirror of the "Scheduled task
 *   results" notifications toggle.
 * @property crashReportingEnabled Mirror of the toggle.
 * @property verboseMemoryLoggingEnabled Mirror of the verbose memory logging
 *   toggle (Settings → Privacy).
 * @property traceRetentionRunsPerSession How many most-recent pipeline runs
 *   the retention pass preserves per chat session (Settings → Privacy).
 * @property traceRetentionMaxAgeDays Maximum age (days) a terminal pipeline
 *   run is kept before the retention pass deletes it (Settings → Privacy).
 * @property restartRequired `true` after the user changed an
 *   inference-backend / Ollama base URL that requires a process restart.
 * @property pendingDestructive Currently staged destructive action,
 *   `null` when no dialog is active.
 * @property destructiveTypedInput Live text in the typed-confirm field.
 * @property snackbarMessage One-shot message surfaced via the screen-level
 *   SnackbarHost; consumed by [SettingsViewModel.snackbarShown].
 * @property pendingHighlightAnchor Anchor key of a settings row a search
 *   deep-link asked to highlight; the owning category sub-screen flashes the row
 *   and clears it via [SettingsViewModel.highlightConsumed]. `null` when no
 *   highlight is pending.
 * @property searchQuery Live settings-search query owned by the ViewModel; blank
 *   renders the normal hub body, non-blank swaps it for [searchResults].
 * @property searchResults Ranked search hits for [searchQuery] (empty while the
 *   query is blank or nothing matches).
 * @property shareTargetPipelineId Id of the pipeline bound to the share target,
 *   or `null` when the surface is unbound (inert).
 * @property quickSettingsTilePipelineId Id of the pipeline bound to the Quick
 *   Settings tile, or `null` when the surface is unbound (inert).
 * @property bindablePipelines Pipelines offered in the Background binding
 *   pickers (id + display name), in library order.
 * @property externalAutomationEnabled Mirror of the external-automation master
 *   switch — whether another app on the device may ask for a pipeline run. Off
 *   by default.
 * @property externalAutomationPipelineId Id of the one pipeline outside apps may
 *   run, or `null` when nothing is bound. This binding is an **allowlist**, not a
 *   default: a request naming anything else is refused rather than redirected, so
 *   an unbound surface refuses everything even while switched on.
 * @property externalAutomationLatestRequest Newest row of the external-request
 *   journal, or `null` when nothing has ever arrived. Only the newest is held:
 *   the Background row answers "did anything reach us, and what happened to it",
 *   and the full timeline lives on its own screen.
 * @property pendingExternalAutomationConsent `true` while the consent dialog
 *   raised by switching the contract **on** is open. Held in the ViewModel rather
 *   than in the composable so the half-made decision survives a configuration
 *   change — and so the invariant that matters (the switch does not move until
 *   the user confirms) is testable without the UI.
 */
data class SettingsUiState(
    val identity: Identity? = null,
    val systemInstructions: String = "",
    val variableCatalog: List<VariableCatalogChip> = emptyList(),
    val toolApprovalPolicy: ToolApprovalPolicy = ToolApprovalPolicy.DEFAULT,
    val blockDestructiveTools: Boolean = false,
    val toolCallTimeoutMs: Long = SettingsDefaults.TOOL_CALL_TIMEOUT_MS_DEFAULT,
    val workspaceMaxFileSizeBytes: Long = SettingsDefaults.WORKSPACE_MAX_FILE_SIZE_BYTES_DEFAULT,
    val workspaceMaxTotalBytes: Long = SettingsDefaults.WORKSPACE_MAX_TOTAL_BYTES_DEFAULT,
    val workspaceReadTokenBudget: Int = SettingsDefaults.WORKSPACE_READ_TOKEN_BUDGET_DEFAULT,
    val httpToolMaxResponseBytes: Long = SettingsDefaults.HTTP_TOOL_MAX_RESPONSE_BYTES_DEFAULT,
    val blockNetworkFromLocalModel: Boolean = false,
    val capAutonomousSteps: Int = SettingsDefaults.PIPELINE_MAX_STEPS_DEFAULT,
    val runMaxTokens: Int = SettingsDefaults.RUN_MAX_TOKENS_DEFAULT,
    val resumeMaxAgeHours: Int = SettingsDefaults.RESUME_MAX_AGE_HOURS_DEFAULT,
    val backgroundApprovalWindowHours: Int = SettingsDefaults.BACKGROUND_APPROVAL_WINDOW_HOURS_DEFAULT,
    val temperature: Float = SettingsDefaults.TEMPERATURE_DEFAULT,
    val topK: Int = SettingsDefaults.TOP_K_DEFAULT,
    val topP: Float = SettingsDefaults.TOP_P_DEFAULT,
    val maxContextLength: Int = SettingsDefaults.MAX_CONTEXT_LENGTH_DEFAULT,
    val audioMaxDurationSec: Int = SettingsDefaults.AUDIO_MAX_DURATION_SEC_DEFAULT,
    val pipelineMaxNestingDepth: Int = SettingsDefaults.PIPELINE_MAX_NESTING_DEPTH_DEFAULT,
    val structuredOutputMaxRepairs: Int = SettingsDefaults.STRUCTURED_OUTPUT_MAX_REPAIRS_DEFAULT,
    val memorySummaryDefaultLimit: Int = SettingsDefaults.MEMORY_SUMMARY_DEFAULT_LIMIT_DEFAULT,
    val activeModelMeta: ActiveModelMeta? = null,
    val localModelBackend: String = LocalBackend.CPU.key,
    val lastTestProbeResult: TestProbeResult? = null,
    val testProbeInFlight: Boolean = false,
    val providers: List<ProviderSummary> = emptyList(),
    val memoryStats: MemoryStats = MemoryStats.EMPTY,
    val averageSimilarityScore: Float? = null,
    val autoExtractEnabled: Boolean = SettingsDefaults.AUTO_EXTRACT_ENABLED_DEFAULT,
    val memorySearchTopK: Int = SettingsDefaults.MEMORY_SEARCH_TOP_K_DEFAULT,
    val memorySearchThreshold: Float = SettingsDefaults.MEMORY_SEARCH_THRESHOLD_DEFAULT,
    val memoryRecencyHalfLifeDays: Int = SettingsDefaults.MEMORY_RECENCY_HALF_LIFE_DAYS_DEFAULT,
    val memoryCompactionEnabled: Boolean = SettingsDefaults.MEMORY_COMPACTION_ENABLED_DEFAULT,
    val memoryCompactionAgeDays: Int = SettingsDefaults.MEMORY_COMPACTION_AGE_DAYS_DEFAULT,
    val maxMemoryChunks: Int = SettingsDefaults.MAX_MEMORY_CHUNKS_DEFAULT,
    val chatHistoryCompressionEnabled: Boolean = SettingsDefaults.CHAT_HISTORY_COMPRESSION_ENABLED_DEFAULT,
    val chatHistoryCompressionThresholdTokens: Int =
        SettingsDefaults.CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS_DEFAULT,
    val chatHistoryLiveWindowSize: Int = SettingsDefaults.CHAT_HISTORY_LIVE_WINDOW_DEFAULT,
    val activeEmbeddingProviderId: String = SettingsDefaults.ACTIVE_EMBEDDING_PROVIDER_ID_DEFAULT,
    val lastReembedProviderId: String? = null,
    val embeddingProviderOptions: List<EmbeddingProviderOption> = emptyList(),
    val memoryValidationError: MemoryValidationError? = null,
    val reembedProgress: Float? = null,
    val scheduledTaskNotificationsEnabled: Boolean = true,
    val shareTargetPipelineId: String? = null,
    val shareReuseSession: Boolean = SettingsDefaults.SHARE_REUSE_SESSION_DEFAULT,
    val quickSettingsTilePipelineId: String? = null,
    val bindablePipelines: List<PipelineBindingOption> = emptyList(),
    val externalAutomationEnabled: Boolean = SettingsDefaults.EXTERNAL_AUTOMATION_ENABLED_DEFAULT,
    val externalAutomationPipelineId: String? = null,
    val externalAutomationLatestRequest: ExternalAutomationJournalEntry? = null,
    val pendingExternalAutomationConsent: Boolean = false,
    val crashReportingEnabled: Boolean = false,
    val verboseMemoryLoggingEnabled: Boolean = SettingsDefaults.VERBOSE_MEMORY_LOGGING_ENABLED_DEFAULT,
    val traceRetentionRunsPerSession: Int = SettingsDefaults.TRACE_RETENTION_RUNS_PER_SESSION_DEFAULT,
    val traceRetentionMaxAgeDays: Int = SettingsDefaults.TRACE_RETENTION_MAX_AGE_DAYS_DEFAULT,
    val restartRequired: Boolean = false,
    val pendingDestructive: PendingDestructiveAction? = null,
    val destructiveTypedInput: String = "",
    val pendingImport: PendingMemoryImport? = null,
    val snackbarMessage: String? = null,
    val pendingHighlightAnchor: String? = null,
    val searchQuery: String = "",
    val searchResults: List<HubSearchResultRow> = emptyList(),
)

/**
 * A pipeline the user can bind to an entry surface, shown in the Background
 * binding pickers.
 *
 * @property id The pipeline id persisted as the surface binding.
 * @property name The pipeline's display name.
 */
data class PipelineBindingOption(val id: String, val name: String)

/**
 * A successfully-parsed memory import file awaiting the user's strategy choice
 * in the import dialog.
 *
 * @property document The parsed export document to import once confirmed.
 * @property providerMismatch `true` when the file was exported under a
 *   different embedding provider than the one active on this device — the
 *   dialog then warns that embeddings will be re-computed on first retrieval.
 * @property schemaMismatch `true` when the file's `schemaVersion` differs from
 *   what this build expects (best-effort parse); the dialog warns accordingly.
 */
data class PendingMemoryImport(
    val document: MemoryExportDocument,
    val providerMismatch: Boolean,
    val schemaMismatch: Boolean,
) {
    /**
     * The warnings the import dialog shows for this file, in display order.
     * Decided here rather than in the Composable so the decision is testable on
     * the JVM; the screen only turns each one into its sentence.
     */
    val warnings: List<MemoryImportWarning>
        get() = buildList {
            if (schemaMismatch) add(MemoryImportWarning.SchemaMismatch)
            if (providerMismatch) add(MemoryImportWarning.ProviderMismatch)
            if (document.pinnedInFile > 0) add(MemoryImportWarning.PinsNotImported)
        }
}

/** A notice the memory-import dialog raises about the staged file. */
enum class MemoryImportWarning {
    /** The file's `schemaVersion` differs from this build's; some data may not import cleanly. */
    SchemaMismatch,

    /** The file's vectors come from another embedding provider and will be re-computed. */
    ProviderMismatch,

    /**
     * The file had pinned chunks. Pins are never taken from a file (see
     * [app.knotwork.android.domain.memoryio.MemoryJsonSerializer]); the dialog
     * says how many were dropped.
     */
    PinsNotImported,
}

/**
 * One selectable embedding provider in the Memory-section dropdown.
 *
 * @property id Stable wire id persisted via
 *   [app.knotwork.android.domain.repositories.SettingsRepository.activeEmbeddingProviderId]
 *   (e.g. `"use"`).
 * @property displayName Human-readable provider label rendered in the dropdown.
 */
data class EmbeddingProviderOption(val id: String, val displayName: String)

/**
 * Which memory-tuning mutator rejected its last input. Surfaced through
 * [SettingsUiState.memoryValidationError] so the screen can show an inline
 * error and the value stays unpersisted.
 */
enum class MemoryValidationError {
    SearchTopK,
    SearchThreshold,
    RecencyHalfLife,
    CompactionAge,
    MaxChunks,
    CompressionThreshold,
    LiveWindow,
    UnknownEmbeddingProvider,
}

/**
 * One `$VARIABLE` chip in the System instructions card.
 *
 * @property placeholder Raw placeholder (with leading `$`).
 * @property sample Live resolved value used as a tooltip / chip subtitle.
 */
data class VariableCatalogChip(val placeholder: String, val sample: String)

/**
 * Destructive action currently staged behind the typed-confirm dialog.
 */
enum class PendingDestructiveAction { ClearMemory, ResetSettings }
