package app.knotwork.design.screens.settings

/**
 * Stable deep-link anchors for the non-slider settings rows the category
 * sub-screens render. Each value mirrors the corresponding `SettingsRegistry`
 * anchor key (a persisted setting's key, or the derived `LINK_*` / action id for
 * a keyless row).
 *
 * Catalog content composables reference these constants instead of inlining
 * string literals, so a typo is a compile error rather than a silently dead
 * highlight. The app-side `SettingsSearchAnchorSyncTest` asserts [ALL] is a
 * subset of the registry anchors, catching a registry rename that would orphan
 * one of these. Slider rows carry their anchor through `SettingSliderRow.anchorKey`
 * (stamped app-side from `SLIDER_TO_ANCHOR`) and are not listed here.
 */
object SettingsRowAnchors {
    /** Generation · system instructions textarea. */
    const val SYSTEM_PROMPT_PREFIX = "SYSTEM_PROMPT_PREFIX"

    /** Models · local-model backend chooser. */
    const val LOCAL_MODEL_BACKEND = "LOCAL_MODEL_BACKEND"

    /** Models · external-providers section. */
    const val LINK_PROVIDER_LIST = "LINK_PROVIDER_LIST"

    /** Memory · auto-extract toggle. */
    const val AUTO_EXTRACT_ENABLED = "AUTO_EXTRACT_ENABLED"

    /** Memory · background-compaction toggle. */
    const val MEMORY_COMPACTION_ENABLED = "MEMORY_COMPACTION_ENABLED"

    /** Memory · compress-chat-history toggle. */
    const val CHAT_HISTORY_COMPRESSION_ENABLED = "CHAT_HISTORY_COMPRESSION_ENABLED"

    /** Memory · embedding-provider dropdown. */
    const val ACTIVE_EMBEDDING_PROVIDER_ID = "ACTIVE_EMBEDDING_PROVIDER_ID"

    /** Memory · verbose-logging toggle. */
    const val VERBOSE_MEMORY_LOGGING_ENABLED = "VERBOSE_MEMORY_LOGGING_ENABLED"

    /** Memory · export/import/re-embed/clear action cluster. */
    const val MEMORY_ACTIONS = "MEMORY_ACTIONS"

    /** Tools · approve-tool-calls segmented control. */
    const val TOOL_APPROVAL_POLICY = "TOOL_APPROVAL_POLICY"

    /** Tools · block-destructive toggle. */
    const val BLOCK_DESTRUCTIVE_TOOLS = "BLOCK_DESTRUCTIVE_TOOLS"

    /** Tools · block-network toggle. */
    const val BLOCK_NETWORK_FROM_LOCAL_MODEL = "BLOCK_NETWORK_FROM_LOCAL_MODEL"

    /** Tools · manage-tools/MCP link. */
    const val LINK_TOOLS_SCREEN = "LINK_TOOLS_SCREEN"

    /** Tools · allowed-HTTP-domains link. */
    const val LINK_FILES_DOMAINS = "LINK_FILES_DOMAINS"

    /** Background · scheduled-task notifications toggle. */
    const val SCHEDULED_TASK_NOTIFICATIONS = "SCHEDULED_TASK_NOTIFICATIONS"

    /** Background · share-target pipeline binding row. */
    const val SHARE_TARGET_PIPELINE_ID = "SHARE_TARGET_PIPELINE_ID"

    /** Background · "keep shares in one chat" toggle. */
    const val SHARE_REUSE_SESSION = "SHARE_REUSE_SESSION"

    /** Background · Quick Settings tile pipeline binding row. */
    const val QUICK_SETTINGS_TILE_PIPELINE_ID = "QUICK_SETTINGS_TILE_PIPELINE_ID"

    /** Background · external-automation master switch (the consent toggle). */
    const val EXTERNAL_AUTOMATION_ENABLED = "EXTERNAL_AUTOMATION_ENABLED"

    /** Background · pipeline outside apps may run (the allowlist binding). */
    const val EXTERNAL_AUTOMATION_PIPELINE_ID = "EXTERNAL_AUTOMATION_PIPELINE_ID"

    /** Background · external-automation request journal link. */
    const val LINK_EXTERNAL_AUTOMATION_JOURNAL = "LINK_EXTERNAL_AUTOMATION_JOURNAL"

    /** Pipelines · run-limits screen link. */
    const val LINK_RUN_LIMITS = "LINK_RUN_LIMITS"

    /** Privacy · crash-reporting toggle. */
    const val CRASH_REPORTING_ENABLED = "CRASH_REPORTING_ENABLED"

    /** About · identity card. */
    const val IDENTITY = "IDENTITY"

    /** About · interface-language picker. */
    const val LANGUAGE = "LANGUAGE"

    /** About · version/licenses link. */
    const val LINK_LICENSES = "LINK_LICENSES"

    /** Privacy · the Backup & restore link row. */
    const val LINK_BACKUP_RESTORE = "LINK_BACKUP_RESTORE"

    /** About · reset-all-settings action. */
    const val RESET = "RESET"

    /** Every non-slider row anchor, for the registry-sync guard test. */
    val ALL: Set<String> = setOf(
        SYSTEM_PROMPT_PREFIX,
        LOCAL_MODEL_BACKEND,
        LINK_PROVIDER_LIST,
        AUTO_EXTRACT_ENABLED,
        MEMORY_COMPACTION_ENABLED,
        CHAT_HISTORY_COMPRESSION_ENABLED,
        ACTIVE_EMBEDDING_PROVIDER_ID,
        VERBOSE_MEMORY_LOGGING_ENABLED,
        MEMORY_ACTIONS,
        TOOL_APPROVAL_POLICY,
        BLOCK_DESTRUCTIVE_TOOLS,
        BLOCK_NETWORK_FROM_LOCAL_MODEL,
        LINK_TOOLS_SCREEN,
        LINK_FILES_DOMAINS,
        SCHEDULED_TASK_NOTIFICATIONS,
        SHARE_TARGET_PIPELINE_ID,
        SHARE_REUSE_SESSION,
        QUICK_SETTINGS_TILE_PIPELINE_ID,
        EXTERNAL_AUTOMATION_ENABLED,
        EXTERNAL_AUTOMATION_PIPELINE_ID,
        LINK_EXTERNAL_AUTOMATION_JOURNAL,
        LINK_RUN_LIMITS,
        CRASH_REPORTING_ENABLED,
        LINK_BACKUP_RESTORE,
        IDENTITY,
        LANGUAGE,
        LINK_LICENSES,
        RESET,
    )
}
