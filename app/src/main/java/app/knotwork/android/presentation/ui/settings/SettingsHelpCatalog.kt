package app.knotwork.android.presentation.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import app.knotwork.android.R
import app.knotwork.android.domain.constants.DocumentationLinks
import app.knotwork.design.screens.settings.KnotworkHintLink
import app.knotwork.design.screens.settings.SettingsHint
import app.knotwork.design.screens.settings.SettingsHintController

/**
 * Why a settings row carries no explanation.
 *
 * Recorded rather than left implicit, so "this row has no hint" is a decision
 * someone made and can be argued with — not an omission that looks identical to
 * a forgotten string.
 */
enum class NoHint {
    /** The row is a door: the screen it opens explains itself. */
    LINK_ROW,

    /** The row displays, it does not decide — identity, version, licences. */
    DISPLAY_ROW,

    /** Verb plus plain object, with no effect off the row: "Export memories". */
    SELF_EVIDENT,

    /**
     * The row's behaviour does not currently happen, so there is nothing
     * truthful to explain. Writing a hint for one of these would document
     * behaviour the build does not have, which is the failure this category
     * exists to stop.
     *
     * **No row qualifies today, and the enum stays** — as the place the next
     * one is recorded rather than quietly explained. The seven that once did
     * were all found by trying to write an explanation and failing to find the
     * code that would make it true; each was then closed the honest way, by
     * fixing the row or removing it:
     *  - `TEMPERATURE`, `TOP_K`, `TOP_P` now reach the on-device engine and
     *    carry ordinary explanations. LiteRT-LM's sampler is all-or-nothing, so
     *    they had to arrive together or not at all — which is why all three sat
     *    here while the conversation was opened with no sampler config.
     *  - `REPETITION_PENALTY` was removed: the library's `SamplerConfig` is
     *    exactly `(topK, topP, temperature, seed)`, so there is nothing to wire
     *    it to. Measured against the packaged `.aar`, not assumed.
     *  - `TOOL_USAGE_INSTRUCTION`, `AUTO_SUMMARIZE_THRESHOLD` and
     *    `LONG_RUNNING_TASKS_NOTIFICATIONS` were removed: no consumer existed
     *    for the first two, and the third gated a notification nothing posted.
     */
    BEHAVIOUR_NOT_SHIPPED,
}

/**
 * A settings row's explanation, or the recorded reason it has none.
 *
 * Deliberately **not** a nullable string: the search catalogue's `descRes` was
 * nullable and unasserted, so a row could lose its description and keep the
 * build green. Making the decision explicit is what lets
 * `SettingsHelpCatalogTest` demand that every registry row is decided.
 */
sealed interface SettingHelp {

    /** The row explains itself through [res]. */
    data class Text(@StringRes val res: Int) : SettingHelp

    /**
     * The row's behaviour does not happen yet, and [res] says so.
     *
     * Distinct from [Text] so the docs generator and the defect list can still
     * tell these rows apart mechanically — but it **does** carry text and
     * **does** render a glyph. Leaving them silent was the wrong call: a row
     * with no explanation beside rows that have one reads as unfinished work,
     * and "moving this changes nothing yet" is precisely the explanation a user
     * who just dragged the slider needs.
     */
    data class NotShipped(@StringRes val res: Int) : SettingHelp

    /** The row carries no explanation, for [why]. */
    data class None(val why: NoHint) : SettingHelp
}

/**
 * Anchor to help text for every row in the [SettingsRegistry] — the canonical
 * source for what a setting means.
 *
 * The same strings feed three consumers and can no longer disagree: the in-app
 * hint panel, the settings-search index, and the generated `Settings` section of
 * `docs/user-guide.md`. Before this, one setting's meaning was written three
 * times over — the row subtitle, the search description and the guide — and
 * closed testing found them already saying three different things, one of them
 * quoting a threshold no constant in the code held.
 *
 * Which rows carry a hint is decided by criterion, not by a list, so nobody has
 * to maintain one. A row is explained when any of these holds:
 *  1. **It has a number.** What a number does is never in its name.
 *  2. **It borrows a word** from outside the product's vocabulary — Top-K,
 *     threshold, half-life, embedding, compaction, backend.
 *  3. **It has a consequence off the row** — data leaves the device, another
 *     feature is blocked, notifications start arriving.
 */
object SettingsHelpCatalog {

    /** Anchor -> explanation, or the recorded reason there is none. */
    val HELP: Map<String, SettingHelp> = mapOf(
        "SYSTEM_PROMPT_PREFIX" to text(R.string.settings_help_system_prompt_prefix),
        "TEMPERATURE" to text(R.string.settings_help_temperature),
        "TOP_K" to text(R.string.settings_help_top_k),
        "TOP_P" to text(R.string.settings_help_top_p),
        "MAX_CONTEXT_LENGTH" to text(R.string.settings_help_max_context_length),
        "AUDIO_MAX_DURATION_SEC" to text(R.string.settings_help_audio_max_duration_sec),
        "LOCAL_MODEL_BACKEND" to text(R.string.settings_help_local_model_backend),
        "LINK_PROVIDER_LIST" to none(NoHint.LINK_ROW),
        "DEFAULT_PIPELINE_ID" to none(NoHint.LINK_ROW),
        "AUTO_EXTRACT_ENABLED" to text(R.string.settings_help_auto_extract_enabled),
        "MEMORY_COMPACTION_ENABLED" to text(R.string.settings_help_memory_compaction_enabled),
        "CHAT_HISTORY_COMPRESSION_ENABLED" to text(R.string.settings_help_chat_history_compression_enabled),
        "MEMORY_SEARCH_TOP_K" to text(R.string.settings_help_memory_search_top_k),
        "MEMORY_SEARCH_THRESHOLD" to text(R.string.settings_help_memory_search_threshold),
        "MEMORY_RECENCY_HALF_LIFE_DAYS" to text(R.string.settings_help_memory_recency_half_life_days),
        "MEMORY_COMPACTION_AGE_DAYS" to text(R.string.settings_help_memory_compaction_age_days),
        "MAX_MEMORY_CHUNKS" to text(R.string.settings_help_max_memory_chunks),
        "CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS" to
            text(R.string.settings_help_chat_history_compression_threshold_tokens),
        "CHAT_HISTORY_LIVE_WINDOW_SIZE" to text(R.string.settings_help_chat_history_live_window_size),
        "MEMORY_SUMMARY_DEFAULT_LIMIT" to text(R.string.settings_help_memory_summary_default_limit),
        "ACTIVE_EMBEDDING_PROVIDER_ID" to text(R.string.settings_help_active_embedding_provider_id),
        "VERBOSE_MEMORY_LOGGING_ENABLED" to text(R.string.settings_help_verbose_memory_logging_enabled),
        "MEMORY_ACTIONS" to text(R.string.settings_help_memory_actions),
        "LINK_RUN_LIMITS" to none(NoHint.LINK_ROW),
        "PIPELINE_MAX_NESTING_DEPTH" to text(R.string.settings_help_pipeline_max_nesting_depth),
        "STRUCTURED_OUTPUT_MAX_REPAIRS" to text(R.string.settings_help_structured_output_max_repairs),
        "LINK_PROVIDER_DETAIL" to none(NoHint.LINK_ROW),
        "TOOL_APPROVAL_POLICY" to text(R.string.settings_help_tool_approval_policy),
        "BLOCK_DESTRUCTIVE_TOOLS" to text(R.string.settings_help_block_destructive_tools),
        "BLOCK_NETWORK_FROM_LOCAL_MODEL" to text(R.string.settings_help_block_network_from_local_model),
        "LINK_TOOLS_SCREEN" to none(NoHint.LINK_ROW),
        "TOOL_CALL_TIMEOUT_MS" to text(R.string.settings_help_tool_call_timeout_ms),
        "WORKSPACE_MAX_FILE_SIZE_BYTES" to text(R.string.settings_help_workspace_max_file_size_bytes),
        "WORKSPACE_MAX_TOTAL_BYTES" to text(R.string.settings_help_workspace_max_total_bytes),
        "WORKSPACE_READ_TOKEN_BUDGET" to text(R.string.settings_help_workspace_read_token_budget),
        "HTTP_TOOL_MAX_RESPONSE_BYTES" to text(R.string.settings_help_http_tool_max_response_bytes),
        "LINK_FILES_DOMAINS" to none(NoHint.LINK_ROW),
        "SCHEDULED_TASK_NOTIFICATIONS" to text(R.string.settings_help_scheduled_task_notifications),
        "SHARE_TARGET_PIPELINE_ID" to text(R.string.settings_help_share_target_pipeline_id),
        "SHARE_REUSE_SESSION" to text(R.string.settings_help_share_reuse_session),
        "QUICK_SETTINGS_TILE_PIPELINE_ID" to text(R.string.settings_help_quick_settings_tile_pipeline_id),
        "EXTERNAL_AUTOMATION_ENABLED" to text(R.string.settings_help_external_automation_enabled),
        "EXTERNAL_AUTOMATION_PIPELINE_ID" to text(R.string.settings_help_external_automation_pipeline_id),
        "LINK_EXTERNAL_AUTOMATION_JOURNAL" to none(NoHint.LINK_ROW),
        "RESUME_MAX_AGE_HOURS" to text(R.string.settings_help_resume_max_age_hours),
        "BACKGROUND_APPROVAL_WINDOW_HOURS" to text(R.string.settings_help_background_approval_window_hours),
        "CRASH_REPORTING_ENABLED" to text(R.string.settings_help_crash_reporting_enabled),
        "TRACE_RETENTION_RUNS_PER_SESSION" to text(R.string.settings_help_trace_retention_runs_per_session),
        "TRACE_RETENTION_MAX_AGE_DAYS" to text(R.string.settings_help_trace_retention_max_age_days),
        "IDENTITY" to none(NoHint.DISPLAY_ROW),
        "LINK_LICENSES" to none(NoHint.LINK_ROW),
        "RESET" to none(NoHint.SELF_EVIDENT),
    )

    /**
     * Builds the hint controller a settings sub-screen provides to its rows.
     *
     * @param context Resource resolution for the localized help text.
     * @param onOpenDocument Invoked with a registry document id when a hint's
     *   link is tapped. Defaults to a no-op so a test can build a controller
     *   without a browser.
     * @return A controller that resolves any registry anchor and owns which
     *   single hint is open on the screen.
     */
    fun controller(context: Context, onOpenDocument: (String) -> Unit = {}): SettingsHintController =
        SettingsHintController { anchorKey ->
            val text = when (val help = HELP[anchorKey]) {
                is SettingHelp.Text -> context.getString(help.res)
                is SettingHelp.NotShipped -> context.getString(help.res)
                else -> null
            }
            text?.let {
                SettingsHint(text = it, link = anchorKey?.let { key -> linkFor(context, key, onOpenDocument) })
            }
        }

    /**
     * Resolves a row's documentation link, when it has one.
     *
     * @param context Resource resolution for the link label.
     * @param anchorKey The row's registry anchor.
     * @param onOpenDocument Invoked with the registry id when the link is tapped.
     * @return The link, or `null` for a row whose hint says everything.
     */
    private fun linkFor(context: Context, anchorKey: String, onOpenDocument: (String) -> Unit): KnotworkHintLink? {
        val link = HELP_DOC_LINKS[anchorKey] ?: return null
        return KnotworkHintLink(
            label = context.getString(link.labelRes),
            onClick = { onOpenDocument(link.documentId) },
        )
    }
}

/**
 * A settings row's pointer into the documentation.
 *
 * @property documentId Id from the generated `DocumentationLinks` registry, so
 *   a document that is renamed or loses its heading fails the build rather than
 *   this map.
 * @property labelRes Label shown on the link inside the hint panel.
 */
internal data class HelpDocLink(@StringRes val labelRes: Int, val documentId: String)

/**
 * Rows whose explanation cannot fit in a hint, and the document that holds the
 * rest.
 *
 * Kept as a map beside [SettingsHelpCatalog.HELP] rather than as another field
 * on `SettingHelp`, and deliberately: the shape of `HELP` is parsed by
 * `SettingsHelpDocsGenerator` with regular expressions to build the guide's
 * settings table, and a record it does not recognise is skipped **silently**.
 * Extending the parsed type to carry one link on one row would risk dropping
 * rows from a published document to save a map.
 */
internal val HELP_DOC_LINKS: Map<String, HelpDocLink> = mapOf(
    "EXTERNAL_AUTOMATION_ENABLED" to HelpDocLink(
        labelRes = R.string.settings_help_link_external_automation,
        documentId = DocumentationLinks.ID_EXTERNAL_AUTOMATION,
    ),
)

private fun text(@StringRes res: Int): SettingHelp = SettingHelp.Text(res)

private fun none(why: NoHint): SettingHelp = SettingHelp.None(why)

// No `notShipped(...)` shorthand: nothing constructs one today, and a private
// factory with no caller is dead code. `SettingHelp.NotShipped(res)` is written
// out directly if a row ever needs it again — which the gate in
// `SettingsHelpCatalogTest` is still watching for.
