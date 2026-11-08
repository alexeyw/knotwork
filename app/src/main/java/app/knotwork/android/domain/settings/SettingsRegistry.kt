package app.knotwork.android.domain.settings

import app.knotwork.android.domain.settings.SettingControlType.BACKEND
import app.knotwork.android.domain.settings.SettingControlType.DROPDOWN
import app.knotwork.android.domain.settings.SettingControlType.IDENTITY
import app.knotwork.android.domain.settings.SettingControlType.LANGUAGE
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
): SettingEntry = SettingEntry(
    key = key,
    categoryId = GENERATION,
    tier = tier,
    hubBasic = hubBasic,
    controlType = controlType,
)

private fun link(tier: SettingTier, destination: String, key: String? = null): SettingEntry = SettingEntry(
    key = key,
    categoryId = GENERATION,
    tier = tier,
    controlType = LINK,
    linkDestination = destination,
)

private fun row(tier: SettingTier, controlType: SettingControlType): SettingEntry = SettingEntry(
    key = null,
    categoryId = GENERATION,
    tier = tier,
    controlType = controlType,
)

private val GENERATION_ENTRIES = listOf(
    setting("SYSTEM_PROMPT_PREFIX", BASIC, TEXTAREA, hubBasic = true),
    setting("TEMPERATURE", ADVANCED, SLIDER),
    setting("TOP_K", ADVANCED, SLIDER),
    setting("TOP_P", ADVANCED, SLIDER),
    setting("MAX_CONTEXT_LENGTH", ADVANCED, SLIDER),
    setting("AUDIO_MAX_DURATION_SEC", ADVANCED, SLIDER),
)

private val MODELS_ENTRIES = listOf(
    setting("LOCAL_MODEL_BACKEND", BASIC, BACKEND, hubBasic = true),
    link(BASIC, "Provider list"),
    link(ADVANCED, "Pipelines", key = "DEFAULT_PIPELINE_ID"),
)

private val MEMORY_ENTRIES = listOf(
    setting("AUTO_EXTRACT_ENABLED", BASIC, TOGGLE),
    setting("MEMORY_COMPACTION_ENABLED", BASIC, TOGGLE),
    setting("CHAT_HISTORY_COMPRESSION_ENABLED", BASIC, TOGGLE),
    setting("MEMORY_SEARCH_TOP_K", ADVANCED, SLIDER),
    setting("MEMORY_SEARCH_THRESHOLD", ADVANCED, SLIDER),
    setting("MEMORY_RECENCY_HALF_LIFE_DAYS", ADVANCED, SLIDER),
    setting("MEMORY_COMPACTION_AGE_DAYS", ADVANCED, SLIDER),
    setting("MAX_MEMORY_CHUNKS", ADVANCED, SLIDER),
    setting("CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS", ADVANCED, SLIDER),
    setting("CHAT_HISTORY_LIVE_WINDOW_SIZE", ADVANCED, SLIDER),
    setting("MEMORY_SUMMARY_DEFAULT_LIMIT", ADVANCED, SLIDER),
    setting("ACTIVE_EMBEDDING_PROVIDER_ID", ADVANCED, DROPDOWN),
    setting("VERBOSE_MEMORY_LOGGING_ENABLED", ADVANCED, TOGGLE),
    row(ADVANCED, MEMORY_ACTIONS),
)

private val PIPELINES_ENTRIES = listOf(
    // Absorbed, not moved beside: the step cap used to be a lone slider here,
    // the only one of four ceilings a user could see. It now lives on the
    // run-limits screen with the other three and the spend statement, reached
    // through this row — which keeps the category, so the search deep-link and
    // its row highlight still land where they always did.
    // "pipelines" is carried as an explicit synonym on both pipeline-scoped
    // rows (their `settings_search_syn_*` resources, which hold every row's
    // search synonyms in the interface language) because the category title
    // no longer contains the word: it was
    // renamed from "Pipelines & structured output" to "Run limits & structured
    // output" (closed-test finding `#12` — the old title promised the pipelines
    // themselves, which live in the Pipelines tab). Search matches the category
    // title, so without these synonyms the rename would have made the category
    // unreachable for the term users actually type.
    link(BASIC, "Run limits"),
    setting("PIPELINE_MAX_NESTING_DEPTH", ADVANCED, SLIDER),
    setting("STRUCTURED_OUTPUT_MAX_REPAIRS", ADVANCED, SLIDER),
    link(ADVANCED, "Provider detail"),
)

private val TOOLS_ENTRIES = listOf(
    setting("TOOL_APPROVAL_POLICY", BASIC, SEGMENTED, hubBasic = true),
    setting("BLOCK_DESTRUCTIVE_TOOLS", BASIC, TOGGLE, hubBasic = true),
    setting("BLOCK_NETWORK_FROM_LOCAL_MODEL", BASIC, TOGGLE),
    link(BASIC, "Tools screen"),
    setting("TOOL_CALL_TIMEOUT_MS", ADVANCED, SLIDER),
    setting("WORKSPACE_MAX_FILE_SIZE_BYTES", ADVANCED, SLIDER),
    setting("WORKSPACE_MAX_TOTAL_BYTES", ADVANCED, SLIDER),
    setting("WORKSPACE_READ_TOKEN_BUDGET", ADVANCED, SLIDER),
    setting("HTTP_TOOL_MAX_RESPONSE_BYTES", ADVANCED, SLIDER),
    link(ADVANCED, "Files / domains"),
)

private val BACKGROUND_ENTRIES = listOf(
    setting("SCHEDULED_TASK_NOTIFICATIONS", BASIC, TOGGLE),
    setting("SHARE_TARGET_PIPELINE_ID", BASIC, DROPDOWN),
    setting("SHARE_REUSE_SESSION", BASIC, TOGGLE),
    setting("QUICK_SETTINGS_TILE_PIPELINE_ID", BASIC, DROPDOWN),
    setting("EXTERNAL_AUTOMATION_ENABLED", BASIC, TOGGLE),
    setting("EXTERNAL_AUTOMATION_PIPELINE_ID", BASIC, DROPDOWN),
    link(BASIC, "External automation journal"),
    setting("RESUME_MAX_AGE_HOURS", ADVANCED, SLIDER),
    setting("BACKGROUND_APPROVAL_WINDOW_HOURS", ADVANCED, SLIDER),
)

private val PRIVACY_ENTRIES = listOf(
    setting("CRASH_REPORTING_ENABLED", BASIC, TOGGLE, hubBasic = true),
    setting("TRACE_RETENTION_RUNS_PER_SESSION", ADVANCED, SLIDER),
    setting("TRACE_RETENTION_MAX_AGE_DAYS", ADVANCED, SLIDER),
    link(BASIC, "Backup & restore"),
)

private val ABOUT_ENTRIES = listOf(
    row(BASIC, IDENTITY),
    row(BASIC, LANGUAGE),
    link(BASIC, "Licenses"),
    row(ADVANCED, RESET),
)

/**
 * Single source of truth for the settings information architecture.
 *
 * Maps every user-facing setting (and the non-editing link / identity / reset
 * rows) to its category, visibility tier and control type.
 * The settings hub, every category sub-screen, the key→category guidance board
 * and the settings search index (built later) all read from this registry — add
 * a setting in one place and it auto-indexes everywhere.
 *
 * Pure-domain and Android-free: human-readable titles, descriptions, search
 * synonyms and category summaries are resolved in the presentation layer from
 * string resources; only the structural metadata lives here.
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
