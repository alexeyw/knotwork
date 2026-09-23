package app.knotwork.android.domain.settings

import app.knotwork.android.domain.settings.SettingControlType.BACKEND
import app.knotwork.android.domain.settings.SettingControlType.DROPDOWN
import app.knotwork.android.domain.settings.SettingControlType.IDENTITY
import app.knotwork.android.domain.settings.SettingControlType.LINK
import app.knotwork.android.domain.settings.SettingControlType.MEMORY_ACTIONS
import app.knotwork.android.domain.settings.SettingControlType.RESET
import app.knotwork.android.domain.settings.SettingControlType.SEGMENTED
import app.knotwork.android.domain.settings.SettingControlType.SLIDER
import app.knotwork.android.domain.settings.SettingControlType.TEXTAREA
import app.knotwork.android.domain.settings.SettingControlType.TOGGLE
import app.knotwork.android.domain.settings.SettingTier.ADVANCED
import app.knotwork.android.domain.settings.SettingTier.BASIC
import app.knotwork.android.domain.settings.SettingsCategoryId.ABOUT
import app.knotwork.android.domain.settings.SettingsCategoryId.BACKGROUND
import app.knotwork.android.domain.settings.SettingsCategoryId.GENERATION
import app.knotwork.android.domain.settings.SettingsCategoryId.MEMORY
import app.knotwork.android.domain.settings.SettingsCategoryId.MODELS
import app.knotwork.android.domain.settings.SettingsCategoryId.PIPELINES
import app.knotwork.android.domain.settings.SettingsCategoryId.PRIVACY
import app.knotwork.android.domain.settings.SettingsCategoryId.TOOLS

// ── Entry builders (file-private) ───────────────────────────────────────────
// The category is stamped onto every entry by SettingsRegistry.category(...),
// so these builders use a placeholder categoryId and the lists below stay flat
// and readable.

private fun setting(
    key: String,
    tier: SettingTier,
    controlType: SettingControlType,
    hubBasic: Boolean = false,
    syn: List<String> = emptyList(),
): SettingEntry = SettingEntry(
    key = key,
    categoryId = GENERATION,
    tier = tier,
    hubBasic = hubBasic,
    controlType = controlType,
    synonyms = syn,
)

private fun link(
    tier: SettingTier,
    destination: String,
    key: String? = null,
    syn: List<String> = emptyList(),
): SettingEntry = SettingEntry(
    key = key,
    categoryId = GENERATION,
    tier = tier,
    controlType = LINK,
    synonyms = syn,
    linkDestination = destination,
)

private fun row(tier: SettingTier, controlType: SettingControlType, syn: List<String> = emptyList()): SettingEntry =
    SettingEntry(
        key = null,
        categoryId = GENERATION,
        tier = tier,
        controlType = controlType,
        synonyms = syn,
    )

private val GENERATION_ENTRIES = listOf(
    setting("SYSTEM_PROMPT_PREFIX", BASIC, TEXTAREA, hubBasic = true, syn = listOf("prompt", "persona")),
    setting("TEMPERATURE", ADVANCED, SLIDER, syn = listOf("sampling", "creativity")),
    setting("TOP_K", ADVANCED, SLIDER, syn = listOf("sampling")),
    setting("TOP_P", ADVANCED, SLIDER, syn = listOf("nucleus", "sampling")),
    setting("MAX_CONTEXT_LENGTH", ADVANCED, SLIDER, syn = listOf("max", "window", "tokens")),
    setting("AUDIO_MAX_DURATION_SEC", ADVANCED, SLIDER, syn = listOf("audio", "mic", "voice", "max")),
)

private val MODELS_ENTRIES = listOf(
    setting("LOCAL_MODEL_BACKEND", BASIC, BACKEND, hubBasic = true, syn = listOf("npu", "gpu", "litert")),
    link(BASIC, "Provider list", syn = listOf("openai", "anthropic", "cloud", "providers")),
    link(ADVANCED, "Pipelines", key = "DEFAULT_PIPELINE_ID", syn = listOf("pipeline", "default")),
)

private val MEMORY_ENTRIES = listOf(
    setting("AUTO_EXTRACT_ENABLED", BASIC, TOGGLE, syn = listOf("learn", "extract")),
    setting("MEMORY_COMPACTION_ENABLED", BASIC, TOGGLE, syn = listOf("compact")),
    setting("CHAT_HISTORY_COMPRESSION_ENABLED", BASIC, TOGGLE, syn = listOf("history", "compress")),
    setting("MEMORY_SEARCH_TOP_K", ADVANCED, SLIDER, syn = listOf("retrieve", "top-k")),
    setting("MEMORY_SEARCH_THRESHOLD", ADVANCED, SLIDER, syn = listOf("retrieve", "similarity")),
    setting("MEMORY_RECENCY_HALF_LIFE_DAYS", ADVANCED, SLIDER, syn = listOf("decay", "recency")),
    setting("MEMORY_COMPACTION_AGE_DAYS", ADVANCED, SLIDER, syn = listOf("compact", "age")),
    setting("MAX_MEMORY_CHUNKS", ADVANCED, SLIDER, syn = listOf("max", "cap", "size")),
    setting("CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS", ADVANCED, SLIDER, syn = listOf("history", "tokens")),
    setting("CHAT_HISTORY_LIVE_WINDOW_SIZE", ADVANCED, SLIDER, syn = listOf("history", "window")),
    setting("MEMORY_SUMMARY_DEFAULT_LIMIT", ADVANCED, SLIDER, syn = listOf("summary", "limit")),
    setting("ACTIVE_EMBEDDING_PROVIDER_ID", ADVANCED, DROPDOWN, syn = listOf("vector", "embedding")),
    setting("VERBOSE_MEMORY_LOGGING_ENABLED", ADVANCED, TOGGLE, syn = listOf("log", "verbose")),
    row(ADVANCED, MEMORY_ACTIONS, syn = listOf("export", "import", "re-embed", "clear")),
)

private val PIPELINES_ENTRIES = listOf(
    // Absorbed, not moved beside: the step cap used to be a lone slider here,
    // the only one of four ceilings a user could see. It now lives on the
    // run-limits screen with the other three and the spend statement, reached
    // through this row — which keeps the category, so the search deep-link and
    // its row highlight still land where they always did.
    // "pipelines" is carried as an explicit synonym on both pipeline-scoped
    // rows because the category title no longer contains the word: it was
    // renamed from "Pipelines & structured output" to "Run limits & structured
    // output" (closed-test finding `#12` — the old title promised the pipelines
    // themselves, which live in the Pipelines tab). Search matches the category
    // title, so without these synonyms the rename would have made the category
    // unreachable for the term users actually type.
    link(
        BASIC,
        "Run limits",
        syn = listOf("steps", "tokens", "limit", "cap", "budget", "cost", "spend", "runaway", "pipelines"),
    ),
    setting("PIPELINE_MAX_NESTING_DEPTH", ADVANCED, SLIDER, syn = listOf("nest", "max", "depth", "pipelines")),
    setting("STRUCTURED_OUTPUT_MAX_REPAIRS", ADVANCED, SLIDER, syn = listOf("json", "repair", "max")),
    link(ADVANCED, "Provider detail", syn = listOf("retry", "cloud", "delay")),
)

private val TOOLS_ENTRIES = listOf(
    setting("TOOL_APPROVAL_POLICY", BASIC, SEGMENTED, hubBasic = true, syn = listOf("hitl", "approval")),
    setting("BLOCK_DESTRUCTIVE_TOOLS", BASIC, TOGGLE, hubBasic = true, syn = listOf("safety")),
    setting("BLOCK_NETWORK_FROM_LOCAL_MODEL", BASIC, TOGGLE, syn = listOf("network", "offline")),
    link(BASIC, "Tools screen", syn = listOf("mcp", "tools", "servers")),
    setting("TOOL_CALL_TIMEOUT_MS", ADVANCED, SLIDER, syn = listOf("timeout", "approval", "wait")),
    setting("WORKSPACE_MAX_FILE_SIZE_BYTES", ADVANCED, SLIDER, syn = listOf("file", "max")),
    setting("WORKSPACE_MAX_TOTAL_BYTES", ADVANCED, SLIDER, syn = listOf("workspace", "max", "total")),
    setting("WORKSPACE_READ_TOKEN_BUDGET", ADVANCED, SLIDER, syn = listOf("max", "tokens", "read")),
    setting("HTTP_TOOL_MAX_RESPONSE_BYTES", ADVANCED, SLIDER, syn = listOf("http", "mcp", "response", "result", "max")),
    link(ADVANCED, "Files / domains", syn = listOf("http", "domains", "allowlist")),
)

private val BACKGROUND_ENTRIES = listOf(
    setting("SCHEDULED_TASK_NOTIFICATIONS", BASIC, TOGGLE, syn = listOf("notify", "schedule")),
    setting("SHARE_TARGET_PIPELINE_ID", BASIC, DROPDOWN, syn = listOf("share", "send", "pipeline", "surface")),
    setting("SHARE_REUSE_SESSION", BASIC, TOGGLE, syn = listOf("share", "one", "chat", "same", "reuse")),
    setting(
        "QUICK_SETTINGS_TILE_PIPELINE_ID",
        BASIC,
        DROPDOWN,
        syn = listOf("tile", "quick settings", "shade", "pipeline", "surface"),
    ),
    setting(
        "EXTERNAL_AUTOMATION_ENABLED",
        BASIC,
        TOGGLE,
        syn = listOf("external", "automation", "tasker", "macrodroid", "adb", "broadcast", "intent"),
    ),
    setting(
        "EXTERNAL_AUTOMATION_PIPELINE_ID",
        BASIC,
        DROPDOWN,
        syn = listOf("external", "automation", "tasker", "pipeline", "surface", "allowlist"),
    ),
    link(
        BASIC,
        "External automation journal",
        syn = listOf("external", "automation", "tasker", "requests", "journal", "log", "history"),
    ),
    setting("RESUME_MAX_AGE_HOURS", ADVANCED, SLIDER, syn = listOf("resume", "max")),
    setting("BACKGROUND_APPROVAL_WINDOW_HOURS", ADVANCED, SLIDER, syn = listOf("approval", "window")),
)

private val PRIVACY_ENTRIES = listOf(
    setting("CRASH_REPORTING_ENABLED", BASIC, TOGGLE, hubBasic = true, syn = listOf("crash", "telemetry")),
    setting("TRACE_RETENTION_RUNS_PER_SESSION", ADVANCED, SLIDER, syn = listOf("trace", "retention")),
    setting("TRACE_RETENTION_MAX_AGE_DAYS", ADVANCED, SLIDER, syn = listOf("trace", "retention", "max")),
)

private val ABOUT_ENTRIES = listOf(
    row(BASIC, IDENTITY, syn = listOf("identity", "device")),
    link(BASIC, "Licenses", syn = listOf("version", "licenses", "oss")),
    row(ADVANCED, RESET, syn = listOf("reset", "defaults")),
)

/**
 * Single source of truth for the settings information architecture.
 *
 * Maps every user-facing setting (and the non-editing link / identity / reset
 * rows) to its category, visibility tier, control type and search synonyms.
 * The settings hub, every category sub-screen, the key→category guidance board
 * and the settings search index (built later) all read from this registry — add
 * a setting in one place and it auto-indexes everywhere.
 *
 * Pure-domain and Android-free: human-readable titles, descriptions and
 * category summaries are resolved in the presentation layer from string
 * resources; only the structural metadata and English search synonyms live
 * here.
 *
 * **Not included** (deliberately): internal/runtime state that is not a
 * user-tunable setting (`IS_FIRST_LAUNCH`, `HAS_COMPLETED_ONBOARDING`,
 * `CURRENT_CHAT_SESSION_ID`, `MEMORY_LAST_COMPACTED_AT`,
 * `LAST_INIT_BACKEND_ATTEMPT`, `LAST_TEST_PROBE_RESULT`, `CONSOLE_PREFERRED_TAB`,
 * `LAST_REEMBED_PROVIDER_ID` and the legacy migrated keys). Secrets
 * (`HUGGING_FACE_TOKEN`, per-server MCP auth) live in the Keystore store and
 * surface only on their owning screens.
 *
 * **Managed elsewhere** rows ([SettingControlType.LINK]) point at the screen
 * that owns the value (Provider detail for cloud keys / `CLOUD_RETRY_*`, the
 * Tools screen for tool toggles / MCP servers, the Files/domains surface for
 * the HTTP allowlist) so the hub never duplicates them.
 */
object SettingsRegistry {

    /**
     * Every category in ratified display order. The `order` field is derived
     * from list position so the ordering lives in exactly one place.
     */
    val categories: List<SettingsCategory> = listOf(
        Triple(GENERATION, "spark", GENERATION_ENTRIES),
        Triple(MODELS, "chip", MODELS_ENTRIES),
        Triple(MEMORY, "brain", MEMORY_ENTRIES),
        Triple(PIPELINES, "flow", PIPELINES_ENTRIES),
        Triple(TOOLS, "tool", TOOLS_ENTRIES),
        Triple(BACKGROUND, "history", BACKGROUND_ENTRIES),
        Triple(PRIVACY, "shield", PRIVACY_ENTRIES),
        Triple(ABOUT, "info", ABOUT_ENTRIES),
    ).mapIndexed { order, (id, iconKey, entries) ->
        SettingsCategory(
            id = id,
            iconKey = iconKey,
            order = order,
            entries = entries.map { it.copy(categoryId = id) },
        )
    }

    /** Flattened view of every row across all categories, in display order. */
    fun allEntries(): List<SettingEntry> = categories.flatMap { it.entries }

    /**
     * The five cross-category rows surfaced inline on the hub. Returned in
     * category display order.
     */
    fun hubBasicEntries(): List<SettingEntry> = allEntries().filter { it.hubBasic }

    /** Looks up the category that owns [key], or `null` when no setting matches. */
    fun categoryOf(key: String): SettingsCategoryId? = allEntries().firstOrNull { it.key == key }?.categoryId
}
