package app.knotwork.design.screens.settings

/**
 * Bundle of typed callbacks shared by the settings hub and every category
 * sub-screen. Kept as a single class so call sites stay compact; each screen
 * wires only the subset of callbacks its controls use.
 */
class SettingsCallbacks(
    // ─── Navigation ──────────────────────────────────────────────────────────
    /** Back / up from the current screen. */
    val onBack: () -> Unit = {},
    /** Open a category sub-screen from the hub. */
    val onOpenCategory: (SettingsCategoryId) -> Unit = {},

    // ─── Search (hub) ─────────────────────────────────────────────────────────
    /** Live edit of the hub search query. */
    val onSearchQueryChange: (String) -> Unit = {},
    /** Tap a search result — deep-links to its category sub-screen + highlight. */
    val onSearchResultClick: (HubSearchResultRow) -> Unit = {},
    /** Clear the hub search query (empty-state action + field trailing icon). */
    val onClearSearch: () -> Unit = {},
    /** Open the Generation sub-screen from the hub's system-instructions row. */
    val onOpenSystemInstructions: () -> Unit = {},
    /** Open the Models management surface (Discover / model catalogue). */
    val onManageModelsClick: () -> Unit = {},
    /** Open the Tools / MCP management screen. */
    val onOpenManageTools: () -> Unit = {},
    /** Open the allowed-HTTP-domains screen. */
    val onOpenAllowedDomains: () -> Unit = {},
    /** Open the About screen (version + open-source licenses). */
    val onOpenLicenses: () -> Unit = {},

    // ─── Generation ──────────────────────────────────────────────────────────
    /** System-instructions textarea edit. */
    val onSystemInstructionsChange: (String) -> Unit = {},
    /** Insert a `$VARIABLE` placeholder into the system instructions. */
    val onChipInsert: (String) -> Unit = {},
    /** Sampling / voice slider change, keyed by `SLIDER_*` id. */
    val onGenerationSliderChange: (id: String, value: Float) -> Unit = { _, _ -> },
    /** Restore the sampling parameters to defaults. */
    val onResetSamplingDefaults: () -> Unit = {},

    // ─── Tools ───────────────────────────────────────────────────────────────
    /** Tool-approval policy change. */
    val onApproveSelectionChange: (ApproveToolCallsOption) -> Unit = {},
    /** "Block destructive tools" toggle. */
    val onBlockDestructiveChange: (Boolean) -> Unit = {},
    /** Any Tools-&-workspace Advanced slider changed (id, new value). */
    val onToolsSliderChange: (id: String, value: Float) -> Unit = { _, _ -> },
    /** "Block network from local model" toggle. */
    val onBlockNetworkChange: (Boolean) -> Unit = {},

    // ─── Pipelines ───────────────────────────────────────────────────────────
    /** Pipeline slider change (cap steps / nesting / repairs), keyed by id. */
    val onPipelinesSliderChange: (id: String, value: Float) -> Unit = { _, _ -> },

    // ─── Models ──────────────────────────────────────────────────────────────
    /** Local-model backend selection. */
    val onBackendSelected: (String) -> Unit = {},
    /** Run the on-device backend smoke test. */
    val onTestBackendClick: () -> Unit = {},
    /** Open a cloud-provider detail row. */
    val onProviderRowClick: (String) -> Unit = {},
    /** Open the add-provider picker. */
    val onAddProviderClick: () -> Unit = {},
    /** Apply a pending restart (backend / Ollama base URL change). */
    val onRestartClick: () -> Unit = {},

    // ─── Memory ──────────────────────────────────────────────────────────────
    /** "Auto-extract memories" toggle. */
    val onAutoExtractToggle: (Boolean) -> Unit = {},
    /** "Background compaction" toggle. */
    val onMemoryCompactionToggle: (Boolean) -> Unit = {},
    /** "Compress chat history" toggle. */
    val onChatHistoryCompressionToggle: (Boolean) -> Unit = {},
    /** Memory-tuning slider change, keyed by `SLIDER_MEMORY_*` id. */
    val onMemorySliderChange: (id: String, value: Float) -> Unit = { _, _ -> },
    /** "Verbose memory logging" toggle. */
    val onVerboseMemoryLoggingToggle: (Boolean) -> Unit = {},
    /** Embedding-provider selection. */
    val onEmbeddingProviderSelected: (String) -> Unit = {},
    /** Export the memory base. */
    val onExportMemoryClick: () -> Unit = {},
    /** Import a memory base. */
    val onImportMemoryClick: () -> Unit = {},
    /** Re-embed all stored chunks with the active provider. */
    val onReembedClick: () -> Unit = {},
    /** Stage the clear-all-memory destructive action. */
    val onClearMemoryClick: () -> Unit = {},

    // ─── Background ──────────────────────────────────────────────────────────
    /** "Scheduled task alerts" toggle. */
    val onScheduledResultsToggle: (Boolean) -> Unit = {},
    /** Background slider change (resume / approval window), keyed by id. */
    val onBackgroundSliderChange: (id: String, value: Float) -> Unit = { _, _ -> },
    /** Open the picker binding a pipeline to the share target. */
    val onShareTargetPipelineClick: () -> Unit = {},
    /** "Keep shares in one chat" toggle. */
    val onShareReuseSessionToggle: (Boolean) -> Unit = {},
    /** Open the picker binding a pipeline to the Quick Settings tile. */
    val onQuickTilePipelineClick: () -> Unit = {},
    /**
     * "External automation" master switch, carrying the position the user asked
     * for. Unlike every other toggle here it is a **request**, not a write: the
     * host raises a consent dialog for `true` and only persists once confirmed,
     * while `false` is applied immediately.
     */
    val onExternalAutomationToggle: (Boolean) -> Unit = {},
    /** Open the picker binding the one pipeline outside apps may run. */
    val onExternalAutomationPipelineClick: () -> Unit = {},
    /** Open the external-automation request journal. */
    val onOpenExternalAutomationJournal: () -> Unit = {},
    /** Opens the run-limits screen from the Pipelines category entry row. */
    val onOpenRunLimits: () -> Unit = {},

    // ─── Privacy ─────────────────────────────────────────────────────────────
    /** "Crash reporting" toggle. */
    val onCrashReportingToggle: (Boolean) -> Unit = {},
    /** Trace-retention slider change, keyed by `SLIDER_PRIVACY_*` id. */
    val onPrivacySliderChange: (id: String, value: Float) -> Unit = { _, _ -> },
    /** Open the on-device Usage statistics sub-screen. */
    val onOpenUsageStatistics: () -> Unit = {},

    // ─── About ───────────────────────────────────────────────────────────────
    /** Stage the reset-all-settings destructive action. */
    val onResetSettingsClick: () -> Unit = {},

    // ─── Destructive dialogs (Memory + About) ────────────────────────────────
    /** Typed-confirm field edit. */
    val onDestructiveTypedConfirmChange: (String) -> Unit = {},
    /** Confirm the staged destructive action. */
    val onDestructiveConfirm: () -> Unit = {},
    /** Cancel the staged destructive action. */
    val onDestructiveCancel: () -> Unit = {},
)

/** Convenience factory returning a callbacks bundle that ignores every event. */
fun noopSettingsCallbacks(): SettingsCallbacks = SettingsCallbacks()
