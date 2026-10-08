package app.knotwork.design.screens.settings

// ─── Slider ids ──────────────────────────────────────────────────────────────
// Stable ids shared by the app (which builds the rows and routes each id back to
// the owning ViewModel setter) and the catalog (which uses them as test tags).

/** Generation · sampling temperature slider id. */
const val SLIDER_TEMPERATURE: String = "temperature"

/** Generation · top-K sampling slider id. */
const val SLIDER_TOP_K: String = "top_k"

/** Generation · top-P sampling slider id. */
const val SLIDER_TOP_P: String = "top_p"

/** Generation · max-context-length slider id. */
const val SLIDER_MAX_CONTEXT: String = "max_context"

/** Generation · voice-input length slider id. */
const val SLIDER_AUDIO_MAX_DURATION: String = "audio_max_duration"

/** Tools · per-tool-call timeout slider id. */
const val SLIDER_TOOL_CALL_TIMEOUT: String = "tool_call_timeout"

/** Tools · workspace per-file size ceiling slider id. */
const val SLIDER_WORKSPACE_MAX_FILE_SIZE: String = "workspace_max_file_size"

/** Tools · workspace total size ceiling slider id. */
const val SLIDER_WORKSPACE_MAX_TOTAL: String = "workspace_max_total"

/** Tools · single-read token budget slider id. */
const val SLIDER_WORKSPACE_READ_TOKEN_BUDGET: String = "workspace_read_token_budget"

/** Tools · `http_request` response ceiling slider id. */
const val SLIDER_HTTP_TOOL_MAX_RESPONSE: String = "http_tool_max_response"

/** Memory · search top-K slider id. */
const val SLIDER_MEMORY_SEARCH_TOP_K: String = "memory_search_top_k"

/** Memory · similarity-threshold slider id. */
const val SLIDER_MEMORY_SEARCH_THRESHOLD: String = "memory_search_threshold"

/** Memory · recency half-life slider id. */
const val SLIDER_MEMORY_RECENCY_HALF_LIFE: String = "memory_recency_half_life"

/** Memory · compaction-age slider id. */
const val SLIDER_MEMORY_COMPACTION_AGE: String = "memory_compaction_age"

/** Memory · max-chunks slider id. */
const val SLIDER_MEMORY_MAX_CHUNKS: String = "memory_max_chunks"

/** Memory · chat-history compression threshold slider id. */
const val SLIDER_MEMORY_COMPRESSION_THRESHOLD: String = "memory_compression_threshold"

/** Memory · chat-history live-window slider id. */
const val SLIDER_MEMORY_LIVE_WINDOW: String = "memory_live_window"

/** Memory · `$MEMORY_SUMMARY` default limit slider id. */
const val SLIDER_MEMORY_SUMMARY_LIMIT: String = "memory_summary_limit"

/** Pipelines · max-nesting-depth slider id. */
const val SLIDER_PIPELINE_NESTING_DEPTH: String = "pipeline_nesting_depth"

/** Pipelines · structured-output repair-budget slider id. */
const val SLIDER_PIPELINE_STRUCTURED_REPAIRS: String = "pipeline_structured_repairs"

/** Background · resume-max-age slider id. */
const val SLIDER_BACKGROUND_RESUME_MAX_AGE: String = "background_resume_max_age"

/** Background · approval-window slider id. */
const val SLIDER_BACKGROUND_APPROVAL_WINDOW: String = "background_approval_window"

/** Privacy · trace-retention runs slider id. */
const val SLIDER_PRIVACY_RETENTION_RUNS: String = "privacy_retention_runs"

/** Privacy · trace-retention max-age slider id. */
const val SLIDER_PRIVACY_RETENTION_AGE: String = "privacy_retention_age"

// ─── Hub ─────────────────────────────────────────────────────────────────────

/**
 * Input to `SettingsHubContent`. Carries the top-bar subtitle, the six inline
 * "Basic" cross-category controls and the restart banner. The eight category
 * navigation rows are rendered from static catalog metadata (title/summary/icon
 * per [SettingsCategoryId]); the hub only needs the live values here.
 *
 * @property loading `true` while the first DataStore read is in flight — the
 *   category rows then render a dimmed skeleton.
 * @property subtitleVersion / [subtitleChannel] / [subtitleBuildDate] Top-bar
 *   subtitle pieces.
 * @property systemInstructionsPreview One-line preview of the system-instructions
 *   value shown on the link row (blank → placeholder copy).
 * @property approveSelection Current tool-approval policy (segmented).
 * @property approveAllLabel / [approveSensitiveLabel] / [approveNeverLabel]
 *   Localised segmented labels.
 * @property blockDestructive "Block destructive tools" toggle value.
 * @property backendLabel Backend description line (e.g. "GPU · falls back to CPU…").
 * @property selectedBackend Short trailing backend value (e.g. "GPU").
 * @property backendOptions Available backend dropdown options.
 * @property crashReportingEnabled "Crash reporting" toggle value.
 * @property restartRequiredMessage Banner copy; `null` hides the restart banner.
 * @property searchQuery Live settings-search query; blank renders the normal hub
 *   body, non-blank swaps it for the search-result list (or the empty state).
 * @property searchResults Ranked search hits for [searchQuery], built from the
 *   settings registry; empty while [searchQuery] is blank or nothing matches.
 */
data class SettingsHubViewState(
    val loading: Boolean,
    val subtitleVersion: String,
    val subtitleChannel: String,
    val subtitleBuildDate: String,
    val systemInstructionsPreview: String,
    val approveSelection: ApproveToolCallsOption,
    val approveAllLabel: String,
    val approveSensitiveLabel: String,
    val approveNeverLabel: String,
    val blockDestructive: Boolean,
    val backendLabel: String,
    val selectedBackend: String,
    val backendOptions: List<String>,
    val crashReportingEnabled: Boolean,
    val restartRequiredMessage: String? = null,
    val searchQuery: String = "",
    val searchResults: List<HubSearchResultRow> = emptyList(),
)

// ─── Generation ──────────────────────────────────────────────────────────────

/**
 * Generation category sub-screen state.
 *
 * @property systemInstructions Basic system-instructions textarea slice.
 * @property advancedSliders Sampling + voice sliders (temperature, top-K, top-P,
 *   max context, voice length), all Advanced.
 */
data class GenerationSettingsViewState(
    val systemInstructions: SystemInstructionsCardState,
    val advancedSliders: List<SettingSliderRow>,
)

// ─── Models ──────────────────────────────────────────────────────────────────

/**
 * Models category sub-screen state.
 *
 * @property localModel Active-model card + backend dropdown + test probe.
 * @property providers Collapsed external-provider rows.
 * @property restartRequiredMessage Restart banner copy; `null` hides the banner.
 */
data class ModelsSettingsViewState(
    val localModel: LocalModelCardState,
    val providers: List<ProviderRowState>,
    val restartRequiredMessage: String? = null,
)

// ─── Memory ──────────────────────────────────────────────────────────────────

/**
 * Memory category sub-screen state.
 *
 * @property stats Stat cells (chunks / size / threads / avg-score).
 * @property autoExtractEnabled "Auto-extract" toggle (Basic).
 * @property autoExtractLabel Localised toggle label.
 * @property compactionEnabled "Background compaction" toggle (Basic).
 * @property compactionLabel Localised toggle label.
 * @property chatHistoryCompressionEnabled "Compress chat history" toggle (Basic).
 * @property chatHistoryCompressionLabel Localised toggle label.
 * @property advancedSliders Memory-tuning sliders (Advanced).
 * @property verboseLoggingEnabled Verbose memory-logging toggle (Advanced).
 * @property embeddingTitle Embedding-dropdown row title.
 * @property embeddingOptions Available embedding providers.
 * @property selectedEmbeddingId Wire id of the selected provider.
 * @property selectedEmbeddingLabel Display name of the selected provider.
 * @property validationError Inline error beneath the sliders when the last
 *   tuning edit was rejected; `null` otherwise.
 * @property reembedBanner Persistent provider-mismatch warning; `null` hides it.
 * @property reembedProgressPercent Re-embed progress `0..100`, or `null`.
 * @property exportLabel / [importLabel] / [reembedLabel] / [clearLabel]
 *   Localised action-button labels.
 * @property destructiveAction Clear-memory typed-confirm payload; `null` hides it.
 */
data class MemorySettingsViewState(
    val stats: List<MemoryStatCell>,
    val autoExtractEnabled: Boolean,
    val autoExtractLabel: String,
    val compactionEnabled: Boolean,
    val compactionLabel: String,
    val chatHistoryCompressionEnabled: Boolean,
    val chatHistoryCompressionLabel: String,
    val advancedSliders: List<SettingSliderRow>,
    val verboseLoggingEnabled: Boolean,
    val embeddingTitle: String,
    val embeddingOptions: List<EmbeddingOptionRow>,
    val selectedEmbeddingId: String,
    val selectedEmbeddingLabel: String,
    val validationError: String? = null,
    val reembedBanner: String? = null,
    val reembedProgressPercent: Int? = null,
    val exportLabel: String,
    val importLabel: String,
    val reembedLabel: String,
    val clearLabel: String,
    val destructiveAction: DestructiveActionState? = null,
)

// ─── Pipelines ───────────────────────────────────────────────────────────────

/**
 * Pipelines-&-structured-output category sub-screen state.
 *
 * @property runLimitsSummary Value summary on the run-limits entry row — the
 *   step and token limits, so the numbers a user came to read are legible
 *   without opening anything. The limits themselves moved to their own screen:
 *   four numbers plus a spend statement do not fit here, and the one that used
 *   to sit on this screen was the only one of the four that was visible at all.
 * @property advancedSliders Max-nesting-depth + structured-output-repairs sliders.
 */
data class PipelinesSettingsViewState(val runLimitsSummary: String, val advancedSliders: List<SettingSliderRow>)

// ─── Tools ───────────────────────────────────────────────────────────────────

/**
 * Tools-&-workspace category sub-screen state.
 *
 * @property approveSelection Tool-approval policy (segmented, Basic).
 * @property approveAllLabel / [approveSensitiveLabel] / [approveNeverLabel]
 *   Localised segmented labels.
 * @property blockDestructive "Block destructive tools" toggle (Basic).
 * @property blockNetwork "Block network from local model" toggle (Basic).
 * @property advancedSliders The tool / workspace ceilings (Advanced): the
 *   per-call timeout, the two workspace size limits, the single-read token
 *   budget and the `http_request` response cap.
 */
data class ToolsSettingsViewState(
    val approveSelection: ApproveToolCallsOption,
    val approveAllLabel: String,
    val approveSensitiveLabel: String,
    val approveNeverLabel: String,
    val blockDestructive: Boolean,
    val blockNetwork: Boolean,
    val advancedSliders: List<SettingSliderRow>,
)

// ─── Background ──────────────────────────────────────────────────────────────

/**
 * Background-&-triggers category sub-screen state.
 *
 * @property scheduledResultsEnabled "Scheduled task alerts" toggle (Basic).
 * @property shareTargetPipelineLabel Bound-pipeline label for the share target
 *   (the pipeline name, or a localised "Not set" placeholder) (Basic).
 * @property shareReuseSessionEnabled "Keep shares in one chat" toggle (Basic).
 * @property quickTilePipelineLabel Bound-pipeline label for the Quick Settings
 *   tile (the pipeline name, or a localised "Not set" placeholder) (Basic).
 * @property externalAutomationEnabled "External automation" master switch — the
 *   consent toggle that lets another app on the device ask for a pipeline run
 *   (Basic). Off by default.
 * @property externalAutomationPipelineLabel Bound-pipeline label for the
 *   external-automation surface (the pipeline name, or a localised "Not set"
 *   placeholder) (Basic).
 * @property externalAutomationUnbound Whether the switch is on with nothing
 *   bound — a reachable, inert state that must read as visibly incomplete rather
 *   than quietly do nothing. Drives the warning treatment on the binding row.
 * @property externalAutomationJournalLabel Pre-resolved summary of the newest
 *   inbound request ("Refused · 12m ago"), or a localised "No requests yet".
 *   Names the last event rather than a count, because the reason to open this
 *   screen is almost always to diagnose one.
 * @property advancedSliders Resume-window + approval-window sliders (Advanced).
 */
data class BackgroundSettingsViewState(
    val scheduledResultsEnabled: Boolean,
    val shareTargetPipelineLabel: String,
    val shareReuseSessionEnabled: Boolean,
    val quickTilePipelineLabel: String,
    val externalAutomationEnabled: Boolean,
    val externalAutomationPipelineLabel: String,
    val externalAutomationUnbound: Boolean,
    val externalAutomationJournalLabel: String,
    val advancedSliders: List<SettingSliderRow>,
)

// ─── Privacy ─────────────────────────────────────────────────────────────────

/**
 * Privacy category sub-screen state.
 *
 * @property crashReportingEnabled Crash-reporting toggle (Basic).
 * @property advancedSliders Trace-retention sliders (runs, age) (Advanced).
 * @property crashReportingAvailable Whether the build has a live crash collector
 *   behind the consent toggle. `false` for the FOSS / F-Droid distribution,
 *   which ships no Firebase and hides the toggle entirely. Defaults to `true`
 *   so existing previews and the full distribution render the row unchanged.
 */
data class PrivacySettingsViewState(
    val crashReportingEnabled: Boolean,
    val advancedSliders: List<SettingSliderRow>,
    val crashReportingAvailable: Boolean = true,
)

// ─── About ───────────────────────────────────────────────────────────────────

/**
 * About category sub-screen state.
 *
 * @property identity Identity card; `null` while loading.
 * @property versionLine Pre-formatted "v0.9.2 · alpha · 2026.05.18" line shown
 *   on the version/licenses link row.
 * @property destructiveAction Reset-settings confirm payload; `null` hides it.
 */
data class AboutSettingsViewState(
    val identity: IdentityCardState?,
    val versionLine: String,
    val destructiveAction: DestructiveActionState? = null,
)
