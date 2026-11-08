package app.knotwork.android.presentation.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import app.knotwork.android.BuildConfig
import app.knotwork.android.R
import app.knotwork.android.domain.settings.SettingTier
import app.knotwork.android.domain.settings.SettingsCategoryId
import app.knotwork.android.domain.settings.SettingsRegistry
import app.knotwork.android.domain.settings.SettingsSearchResult
import app.knotwork.android.domain.settings.SettingsSearchableEntry
import app.knotwork.android.domain.settings.anchorKey
import app.knotwork.design.screens.settings.HubSearchResultRow
import app.knotwork.design.R as DesignR
import app.knotwork.design.screens.settings.SettingsCategoryId as CatalogCategoryId

/**
 * Localized search copy for one settings row.
 *
 * @property nameRes Human-readable setting name (the primary match field).
 * @property descRes One-line description shown under the name in search results.
 *   Non-null on purpose: it used to be nullable and unasserted, which is how a
 *   row could carry a description that contradicted its own help text and keep
 *   a green build. `SettingsSearchCatalogTest` now checks the text itself.
 */
data class SettingsSearchStrings(@StringRes val nameRes: Int, @StringRes val descRes: Int)

/**
 * Presentation-side bridge between the pure-domain [SettingsRegistry] and the
 * [app.knotwork.android.domain.settings.SettingsSearchEngine]: it resolves the
 * localized name / description / category title for every registry row into the
 * searchable index the engine consumes, and maps engine results back into the
 * catalog [HubSearchResultRow] model the hub renders.
 *
 * The [SEARCH_STRINGS] table is keyed by [anchorKey] and must cover every
 * registry row — a completeness guard (`SettingsSearchCatalogTest`) fails the
 * build if a setting is added to the registry without search copy, so a new
 * preference can never silently fall out of search.
 */
object SettingsSearchCatalog {

    /**
     * Anchor key of the crash-reporting consent row. Held as a constant because
     * it is referenced both as a [SEARCH_STRINGS] key and in [buildIndex], where
     * the row is filtered out for the `foss` distribution (which hides it).
     */
    private const val CRASH_REPORTING_ANCHOR = "CRASH_REPORTING_ENABLED"

    /** Separates the terms of one row's synonyms resource. */
    private const val SYNONYM_SEPARATOR = ','

    /**
     * Anchor → localized search strings. Authored from the ratified settings
     * information architecture; one entry per row returned by
     * [SettingsRegistry.allEntries].
     */
    val SEARCH_STRINGS: Map<String, SettingsSearchStrings> = mapOf(
        // ─── Generation ──────────────────────────────────────────────────────
        "SYSTEM_PROMPT_PREFIX" to strings(
            R.string.settings_search_name_system_prompt_prefix,
            R.string.settings_search_desc_system_prompt_prefix,
        ),
        "TEMPERATURE" to strings(R.string.settings_search_name_temperature, R.string.settings_search_desc_temperature),
        "TOP_K" to strings(R.string.settings_search_name_top_k, R.string.settings_search_desc_top_k),
        "TOP_P" to strings(R.string.settings_search_name_top_p, R.string.settings_search_desc_top_p),
        "MAX_CONTEXT_LENGTH" to strings(
            R.string.settings_search_name_max_context_length,
            R.string.settings_search_desc_max_context_length,
        ),
        "AUDIO_MAX_DURATION_SEC" to strings(
            R.string.settings_search_name_audio_max_duration_sec,
            R.string.settings_search_desc_audio_max_duration_sec,
        ),
        // ─── Models ──────────────────────────────────────────────────────────
        "LOCAL_MODEL_BACKEND" to strings(
            R.string.settings_search_name_local_model_backend,
            R.string.settings_search_desc_local_model_backend,
        ),
        "LINK_PROVIDER_LIST" to strings(
            R.string.settings_search_name_link_provider_list,
            R.string.settings_search_desc_link_provider_list,
        ),
        "DEFAULT_PIPELINE_ID" to strings(
            R.string.settings_search_name_default_pipeline_id,
            R.string.settings_search_desc_default_pipeline_id,
        ),
        // ─── Memory ──────────────────────────────────────────────────────────
        "AUTO_EXTRACT_ENABLED" to strings(
            R.string.settings_search_name_auto_extract_enabled,
            R.string.settings_search_desc_auto_extract_enabled,
        ),
        "MEMORY_COMPACTION_ENABLED" to strings(
            R.string.settings_search_name_memory_compaction_enabled,
            R.string.settings_search_desc_memory_compaction_enabled,
        ),
        "CHAT_HISTORY_COMPRESSION_ENABLED" to strings(
            R.string.settings_search_name_chat_history_compression_enabled,
            R.string.settings_search_desc_chat_history_compression_enabled,
        ),
        "MEMORY_SEARCH_TOP_K" to strings(
            R.string.settings_search_name_memory_search_top_k,
            R.string.settings_search_desc_memory_search_top_k,
        ),
        "MEMORY_SEARCH_THRESHOLD" to strings(
            R.string.settings_search_name_memory_search_threshold,
            R.string.settings_search_desc_memory_search_threshold,
        ),
        "MEMORY_RECENCY_HALF_LIFE_DAYS" to strings(
            R.string.settings_search_name_memory_recency_half_life_days,
            R.string.settings_search_desc_memory_recency_half_life_days,
        ),
        "MEMORY_COMPACTION_AGE_DAYS" to strings(
            R.string.settings_search_name_memory_compaction_age_days,
            R.string.settings_search_desc_memory_compaction_age_days,
        ),
        "MAX_MEMORY_CHUNKS" to strings(
            R.string.settings_search_name_max_memory_chunks,
            R.string.settings_search_desc_max_memory_chunks,
        ),
        "CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS" to strings(
            R.string.settings_search_name_chat_history_compression_threshold_tokens,
            R.string.settings_search_desc_chat_history_compression_threshold_tokens,
        ),
        "CHAT_HISTORY_LIVE_WINDOW_SIZE" to strings(
            R.string.settings_search_name_chat_history_live_window_size,
            R.string.settings_search_desc_chat_history_live_window_size,
        ),
        "MEMORY_SUMMARY_DEFAULT_LIMIT" to strings(
            R.string.settings_search_name_memory_summary_default_limit,
            R.string.settings_search_desc_memory_summary_default_limit,
        ),
        "ACTIVE_EMBEDDING_PROVIDER_ID" to strings(
            R.string.settings_search_name_active_embedding_provider_id,
            R.string.settings_search_desc_active_embedding_provider_id,
        ),
        "VERBOSE_MEMORY_LOGGING_ENABLED" to strings(
            R.string.settings_search_name_verbose_memory_logging_enabled,
            R.string.settings_search_desc_verbose_memory_logging_enabled,
        ),
        "MEMORY_ACTIONS" to strings(
            R.string.settings_search_name_memory_actions,
            R.string.settings_search_desc_memory_actions,
        ),
        // ─── Run limits & structured output ──────────────────────────────────
        "LINK_RUN_LIMITS" to strings(
            R.string.run_limits_entry_label,
            R.string.run_limits_entry_search,
        ),
        "PIPELINE_MAX_NESTING_DEPTH" to strings(
            R.string.settings_search_name_pipeline_max_nesting_depth,
            R.string.settings_search_desc_pipeline_max_nesting_depth,
        ),
        "STRUCTURED_OUTPUT_MAX_REPAIRS" to strings(
            R.string.settings_search_name_structured_output_max_repairs,
            R.string.settings_search_desc_structured_output_max_repairs,
        ),
        "LINK_PROVIDER_DETAIL" to strings(
            R.string.settings_search_name_link_provider_detail,
            R.string.settings_search_desc_link_provider_detail,
        ),
        // ─── Tools & workspace ───────────────────────────────────────────────
        "TOOL_APPROVAL_POLICY" to strings(
            R.string.settings_search_name_tool_approval_policy,
            R.string.settings_search_desc_tool_approval_policy,
        ),
        "BLOCK_DESTRUCTIVE_TOOLS" to strings(
            R.string.settings_search_name_block_destructive_tools,
            R.string.settings_search_desc_block_destructive_tools,
        ),
        "BLOCK_NETWORK_FROM_LOCAL_MODEL" to strings(
            R.string.settings_search_name_block_network_from_local_model,
            R.string.settings_search_desc_block_network_from_local_model,
        ),
        "LINK_TOOLS_SCREEN" to strings(
            R.string.settings_search_name_link_tools_screen,
            R.string.settings_search_desc_link_tools_screen,
        ),
        "TOOL_CALL_TIMEOUT_MS" to strings(
            R.string.settings_search_name_tool_call_timeout_ms,
            R.string.settings_search_desc_tool_call_timeout_ms,
        ),
        "WORKSPACE_MAX_FILE_SIZE_BYTES" to strings(
            R.string.settings_search_name_workspace_max_file_size_bytes,
            R.string.settings_search_desc_workspace_max_file_size_bytes,
        ),
        "WORKSPACE_MAX_TOTAL_BYTES" to strings(
            R.string.settings_search_name_workspace_max_total_bytes,
            R.string.settings_search_desc_workspace_max_total_bytes,
        ),
        "WORKSPACE_READ_TOKEN_BUDGET" to strings(
            R.string.settings_search_name_workspace_read_token_budget,
            R.string.settings_search_desc_workspace_read_token_budget,
        ),
        "HTTP_TOOL_MAX_RESPONSE_BYTES" to strings(
            R.string.settings_search_name_http_tool_max_response_bytes,
            R.string.settings_search_desc_http_tool_max_response_bytes,
        ),
        "LINK_FILES_DOMAINS" to strings(
            R.string.settings_search_name_link_files_domains,
            R.string.settings_search_desc_link_files_domains,
        ),
        // ─── Background & triggers ───────────────────────────────────────────
        "SCHEDULED_TASK_NOTIFICATIONS" to strings(
            R.string.settings_search_name_scheduled_task_notifications,
            R.string.settings_search_desc_scheduled_task_notifications,
        ),
        "SHARE_TARGET_PIPELINE_ID" to strings(
            R.string.settings_search_name_share_target_pipeline_id,
            R.string.settings_search_desc_share_target_pipeline_id,
        ),
        "SHARE_REUSE_SESSION" to strings(
            R.string.settings_search_name_share_reuse_session,
            R.string.settings_search_desc_share_reuse_session,
        ),
        "QUICK_SETTINGS_TILE_PIPELINE_ID" to strings(
            R.string.settings_search_name_quick_settings_tile_pipeline_id,
            R.string.settings_search_desc_quick_settings_tile_pipeline_id,
        ),
        "RESUME_MAX_AGE_HOURS" to strings(
            R.string.settings_search_name_resume_max_age_hours,
            R.string.settings_search_desc_resume_max_age_hours,
        ),
        "BACKGROUND_APPROVAL_WINDOW_HOURS" to strings(
            R.string.settings_search_name_background_approval_window_hours,
            R.string.settings_search_desc_background_approval_window_hours,
        ),
        "EXTERNAL_AUTOMATION_ENABLED" to strings(
            R.string.settings_search_name_external_automation_enabled,
            R.string.settings_search_desc_external_automation_enabled,
        ),
        "EXTERNAL_AUTOMATION_PIPELINE_ID" to strings(
            R.string.settings_search_name_external_automation_pipeline_id,
            R.string.settings_search_desc_external_automation_pipeline_id,
        ),
        "LINK_EXTERNAL_AUTOMATION_JOURNAL" to strings(
            R.string.settings_search_name_link_external_automation_journal,
            R.string.settings_search_desc_link_external_automation_journal,
        ),
        // ─── Privacy ─────────────────────────────────────────────────────────
        CRASH_REPORTING_ANCHOR to strings(
            R.string.settings_search_name_crash_reporting_enabled,
            R.string.settings_search_desc_crash_reporting_enabled,
        ),
        "TRACE_RETENTION_RUNS_PER_SESSION" to strings(
            R.string.settings_search_name_trace_retention_runs_per_session,
            R.string.settings_search_desc_trace_retention_runs_per_session,
        ),
        "TRACE_RETENTION_MAX_AGE_DAYS" to strings(
            R.string.settings_search_name_trace_retention_max_age_days,
            R.string.settings_search_desc_trace_retention_max_age_days,
        ),
        "LINK_BACKUP_RESTORE" to strings(
            R.string.settings_search_name_link_backup_restore,
            R.string.settings_search_desc_link_backup_restore,
        ),
        // ─── About ───────────────────────────────────────────────────────────
        "IDENTITY" to strings(R.string.settings_search_name_identity, R.string.settings_search_desc_identity),
        "LANGUAGE" to strings(R.string.settings_search_name_language, R.string.settings_search_desc_language),
        "LINK_LICENSES" to strings(
            R.string.settings_search_name_link_licenses,
            R.string.settings_search_desc_link_licenses,
        ),
        "RESET" to strings(R.string.settings_search_name_reset, R.string.settings_search_desc_reset),
    )

    /**
     * The search synonyms of every row, keyed by [anchorKey]: one string resource per row, the
     * terms separated by commas, in the interface language. The matched synonym is shown under
     * the result, so these are interface text — the Russian list carries the technical terms
     * (`gpu`, `mcp`, `json`) too, as a person types them in either language.
     */
    internal val SEARCH_SYNONYMS: Map<String, Int> = mapOf(
        "SYSTEM_PROMPT_PREFIX" to R.string.settings_search_syn_system_prompt_prefix,
        "TEMPERATURE" to R.string.settings_search_syn_temperature,
        "TOP_K" to R.string.settings_search_syn_top_k,
        "TOP_P" to R.string.settings_search_syn_top_p,
        "MAX_CONTEXT_LENGTH" to R.string.settings_search_syn_max_context_length,
        "AUDIO_MAX_DURATION_SEC" to R.string.settings_search_syn_audio_max_duration_sec,
        "LOCAL_MODEL_BACKEND" to R.string.settings_search_syn_local_model_backend,
        "LINK_PROVIDER_LIST" to R.string.settings_search_syn_link_provider_list,
        "DEFAULT_PIPELINE_ID" to R.string.settings_search_syn_default_pipeline_id,
        "AUTO_EXTRACT_ENABLED" to R.string.settings_search_syn_auto_extract_enabled,
        "MEMORY_COMPACTION_ENABLED" to R.string.settings_search_syn_memory_compaction_enabled,
        "CHAT_HISTORY_COMPRESSION_ENABLED" to R.string.settings_search_syn_chat_history_compression_enabled,
        "MEMORY_SEARCH_TOP_K" to R.string.settings_search_syn_memory_search_top_k,
        "MEMORY_SEARCH_THRESHOLD" to R.string.settings_search_syn_memory_search_threshold,
        "MEMORY_RECENCY_HALF_LIFE_DAYS" to R.string.settings_search_syn_memory_recency_half_life_days,
        "MEMORY_COMPACTION_AGE_DAYS" to R.string.settings_search_syn_memory_compaction_age_days,
        "MAX_MEMORY_CHUNKS" to R.string.settings_search_syn_max_memory_chunks,
        "CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS" to
            R.string.settings_search_syn_chat_history_compression_threshold_tokens,
        "CHAT_HISTORY_LIVE_WINDOW_SIZE" to R.string.settings_search_syn_chat_history_live_window_size,
        "MEMORY_SUMMARY_DEFAULT_LIMIT" to R.string.settings_search_syn_memory_summary_default_limit,
        "ACTIVE_EMBEDDING_PROVIDER_ID" to R.string.settings_search_syn_active_embedding_provider_id,
        "VERBOSE_MEMORY_LOGGING_ENABLED" to R.string.settings_search_syn_verbose_memory_logging_enabled,
        "MEMORY_ACTIONS" to R.string.settings_search_syn_memory_actions,
        "LINK_RUN_LIMITS" to R.string.settings_search_syn_link_run_limits,
        "PIPELINE_MAX_NESTING_DEPTH" to R.string.settings_search_syn_pipeline_max_nesting_depth,
        "STRUCTURED_OUTPUT_MAX_REPAIRS" to R.string.settings_search_syn_structured_output_max_repairs,
        "LINK_PROVIDER_DETAIL" to R.string.settings_search_syn_link_provider_detail,
        "TOOL_APPROVAL_POLICY" to R.string.settings_search_syn_tool_approval_policy,
        "BLOCK_DESTRUCTIVE_TOOLS" to R.string.settings_search_syn_block_destructive_tools,
        "BLOCK_NETWORK_FROM_LOCAL_MODEL" to R.string.settings_search_syn_block_network_from_local_model,
        "LINK_TOOLS_SCREEN" to R.string.settings_search_syn_link_tools_screen,
        "TOOL_CALL_TIMEOUT_MS" to R.string.settings_search_syn_tool_call_timeout_ms,
        "WORKSPACE_MAX_FILE_SIZE_BYTES" to R.string.settings_search_syn_workspace_max_file_size_bytes,
        "WORKSPACE_MAX_TOTAL_BYTES" to R.string.settings_search_syn_workspace_max_total_bytes,
        "WORKSPACE_READ_TOKEN_BUDGET" to R.string.settings_search_syn_workspace_read_token_budget,
        "HTTP_TOOL_MAX_RESPONSE_BYTES" to R.string.settings_search_syn_http_tool_max_response_bytes,
        "LINK_FILES_DOMAINS" to R.string.settings_search_syn_link_files_domains,
        "SCHEDULED_TASK_NOTIFICATIONS" to R.string.settings_search_syn_scheduled_task_notifications,
        "SHARE_TARGET_PIPELINE_ID" to R.string.settings_search_syn_share_target_pipeline_id,
        "SHARE_REUSE_SESSION" to R.string.settings_search_syn_share_reuse_session,
        "QUICK_SETTINGS_TILE_PIPELINE_ID" to R.string.settings_search_syn_quick_settings_tile_pipeline_id,
        "EXTERNAL_AUTOMATION_ENABLED" to R.string.settings_search_syn_external_automation_enabled,
        "EXTERNAL_AUTOMATION_PIPELINE_ID" to R.string.settings_search_syn_external_automation_pipeline_id,
        "LINK_EXTERNAL_AUTOMATION_JOURNAL" to R.string.settings_search_syn_link_external_automation_journal,
        "RESUME_MAX_AGE_HOURS" to R.string.settings_search_syn_resume_max_age_hours,
        "BACKGROUND_APPROVAL_WINDOW_HOURS" to R.string.settings_search_syn_background_approval_window_hours,
        CRASH_REPORTING_ANCHOR to R.string.settings_search_syn_crash_reporting_enabled,
        "TRACE_RETENTION_RUNS_PER_SESSION" to R.string.settings_search_syn_trace_retention_runs_per_session,
        "TRACE_RETENTION_MAX_AGE_DAYS" to R.string.settings_search_syn_trace_retention_max_age_days,
        "LINK_BACKUP_RESTORE" to R.string.settings_search_syn_link_backup_restore,
        "IDENTITY" to R.string.settings_search_syn_identity,
        "LANGUAGE" to R.string.settings_search_syn_language,
        "LINK_LICENSES" to R.string.settings_search_syn_link_licenses,
        "RESET" to R.string.settings_search_syn_reset,
    )

    /**
     * Resolves the registry into the localized search index the engine consumes.
     *
     * @param context Used to resolve the name / description / category-title /
     *   synonyms string resources.
     * @return One [SettingsSearchableEntry] per registry row, in display order.
     */
    fun buildIndex(context: Context): List<SettingsSearchableEntry> = SettingsRegistry.allEntries()
        // The crash-reporting consent row is hidden in the `foss` distribution
        // (no live collector). Drop it from the search index there too, so a
        // search hit can never deep-link to a Privacy row that is never rendered.
        .filterNot { !BuildConfig.CRASH_REPORTING_AVAILABLE && it.anchorKey() == CRASH_REPORTING_ANCHOR }
        .map { entry ->
            val anchor = entry.anchorKey()
            val copy = requireNotNull(SEARCH_STRINGS[anchor]) { "Missing search copy for anchor '$anchor'" }
            SettingsSearchableEntry(
                anchorKey = anchor,
                categoryId = entry.categoryId,
                tier = entry.tier,
                name = context.getString(copy.nameRes),
                description = context.getString(copy.descRes),
                categoryTitle = context.getString(categoryTitleRes(entry.categoryId)),
                synonyms = synonymsOf(context, anchor),
            )
        }

    private fun strings(@StringRes nameRes: Int, @StringRes descRes: Int) = SettingsSearchStrings(nameRes, descRes)

    /** The row's synonyms in the interface language, from its comma-separated resource. */
    private fun synonymsOf(context: Context, anchor: String): List<String> {
        val res = requireNotNull(SEARCH_SYNONYMS[anchor]) { "Missing search synonyms for anchor '$anchor'" }
        return context.getString(res).split(SYNONYM_SEPARATOR).map(String::trim).filter(String::isNotEmpty)
    }

    /** Localized category-title resource (reuses the hub's category titles). */
    @StringRes
    private fun categoryTitleRes(id: SettingsCategoryId): Int = when (id) {
        SettingsCategoryId.GENERATION -> DesignR.string.knotwork_settings_cat_generation_title
        SettingsCategoryId.MODELS -> DesignR.string.knotwork_settings_cat_models_title
        SettingsCategoryId.MEMORY -> DesignR.string.knotwork_settings_cat_memory_title
        SettingsCategoryId.PIPELINES -> DesignR.string.knotwork_settings_cat_pipelines_title
        SettingsCategoryId.TOOLS -> DesignR.string.knotwork_settings_cat_tools_title
        SettingsCategoryId.BACKGROUND -> DesignR.string.knotwork_settings_cat_background_title
        SettingsCategoryId.PRIVACY -> DesignR.string.knotwork_settings_cat_privacy_title
        SettingsCategoryId.ABOUT -> DesignR.string.knotwork_settings_cat_about_title
    }
}

/** Maps a domain [SettingsCategoryId] to its catalog mirror used by the UI. */
fun SettingsCategoryId.toCatalog(): CatalogCategoryId = when (this) {
    SettingsCategoryId.GENERATION -> CatalogCategoryId.Generation
    SettingsCategoryId.MODELS -> CatalogCategoryId.Models
    SettingsCategoryId.MEMORY -> CatalogCategoryId.Memory
    SettingsCategoryId.PIPELINES -> CatalogCategoryId.Pipelines
    SettingsCategoryId.TOOLS -> CatalogCategoryId.Tools
    SettingsCategoryId.BACKGROUND -> CatalogCategoryId.Background
    SettingsCategoryId.PRIVACY -> CatalogCategoryId.Privacy
    SettingsCategoryId.ABOUT -> CatalogCategoryId.About
}

/** Maps one engine result to the catalog row model the hub renders. */
fun SettingsSearchResult.toHubRow(): HubSearchResultRow = HubSearchResultRow(
    anchorKey = entry.anchorKey,
    categoryId = entry.categoryId.toCatalog(),
    name = entry.name,
    nameMatchStart = nameMatchRange?.first ?: -1,
    nameMatchLength = nameMatchRange?.let { it.last - it.first + 1 } ?: 0,
    isBasic = entry.tier == SettingTier.BASIC,
    synonymHit = synonymHit,
)
