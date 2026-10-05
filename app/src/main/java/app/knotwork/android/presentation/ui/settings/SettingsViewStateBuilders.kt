package app.knotwork.android.presentation.ui.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import app.knotwork.android.BuildConfig
import app.knotwork.android.R
import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.ActiveModelMeta
import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.ToolApprovalPolicy
import app.knotwork.android.presentation.common.DisplayFormat
import app.knotwork.design.screens.settings.AboutSettingsViewState
import app.knotwork.design.screens.settings.ApproveToolCallsOption
import app.knotwork.design.screens.settings.BackgroundSettingsViewState
import app.knotwork.design.screens.settings.DestructiveActionKind
import app.knotwork.design.screens.settings.DestructiveActionState
import app.knotwork.design.screens.settings.EmbeddingOptionRow
import app.knotwork.design.screens.settings.GenerationSettingsViewState
import app.knotwork.design.screens.settings.IdentityCardState
import app.knotwork.design.screens.settings.LocalModelCardState
import app.knotwork.design.screens.settings.MemorySettingsViewState
import app.knotwork.design.screens.settings.MemoryStatCell
import app.knotwork.design.screens.settings.ModelsSettingsViewState
import app.knotwork.design.screens.settings.PipelinesSettingsViewState
import app.knotwork.design.screens.settings.PrivacySettingsViewState
import app.knotwork.design.screens.settings.ProviderRowState
import app.knotwork.design.screens.settings.SLIDER_AUDIO_MAX_DURATION
import app.knotwork.design.screens.settings.SLIDER_BACKGROUND_APPROVAL_WINDOW
import app.knotwork.design.screens.settings.SLIDER_BACKGROUND_RESUME_MAX_AGE
import app.knotwork.design.screens.settings.SLIDER_HTTP_TOOL_MAX_RESPONSE
import app.knotwork.design.screens.settings.SLIDER_MAX_CONTEXT
import app.knotwork.design.screens.settings.SLIDER_MEMORY_COMPACTION_AGE
import app.knotwork.design.screens.settings.SLIDER_MEMORY_COMPRESSION_THRESHOLD
import app.knotwork.design.screens.settings.SLIDER_MEMORY_LIVE_WINDOW
import app.knotwork.design.screens.settings.SLIDER_MEMORY_MAX_CHUNKS
import app.knotwork.design.screens.settings.SLIDER_MEMORY_RECENCY_HALF_LIFE
import app.knotwork.design.screens.settings.SLIDER_MEMORY_SEARCH_THRESHOLD
import app.knotwork.design.screens.settings.SLIDER_MEMORY_SEARCH_TOP_K
import app.knotwork.design.screens.settings.SLIDER_MEMORY_SUMMARY_LIMIT
import app.knotwork.design.screens.settings.SLIDER_PIPELINE_NESTING_DEPTH
import app.knotwork.design.screens.settings.SLIDER_PIPELINE_STRUCTURED_REPAIRS
import app.knotwork.design.screens.settings.SLIDER_PRIVACY_RETENTION_AGE
import app.knotwork.design.screens.settings.SLIDER_PRIVACY_RETENTION_RUNS
import app.knotwork.design.screens.settings.SLIDER_TEMPERATURE
import app.knotwork.design.screens.settings.SLIDER_TOOL_CALL_TIMEOUT
import app.knotwork.design.screens.settings.SLIDER_TOP_K
import app.knotwork.design.screens.settings.SLIDER_TOP_P
import app.knotwork.design.screens.settings.SLIDER_WORKSPACE_MAX_FILE_SIZE
import app.knotwork.design.screens.settings.SLIDER_WORKSPACE_MAX_TOTAL
import app.knotwork.design.screens.settings.SLIDER_WORKSPACE_READ_TOKEN_BUDGET
import app.knotwork.design.screens.settings.SettingSliderRow
import app.knotwork.design.screens.settings.SettingsHubViewState
import app.knotwork.design.screens.settings.SystemInstructionsCardState
import app.knotwork.design.screens.settings.ToolsSettingsViewState
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/** Builds the settings hub state (subtitle, the five inline Basic controls, restart, search). */
@Composable
internal fun buildHubViewState(uiState: SettingsUiState): SettingsHubViewState = SettingsHubViewState(
    loading = uiState.identity == null,
    subtitleVersion = BuildConfig.VERSION_NAME,
    subtitleChannel = BuildConfig.BUILD_TYPE,
    subtitleBuildDate = formatBuildDate(BuildConfig.GIT_COMMIT_DATE_EPOCH_MS),
    systemInstructionsPreview = uiState.systemInstructions.trim().replace('\n', ' '),
    approveSelection = uiState.toolApprovalPolicy.toApproveOption(),
    approveAllLabel = stringResource(R.string.settings_restrictions_approve_all),
    approveSensitiveLabel = stringResource(R.string.settings_restrictions_approve_sensitive),
    approveNeverLabel = stringResource(R.string.settings_restrictions_approve_never),
    blockDestructive = uiState.blockDestructiveTools,
    backendLabel = stringResource(R.string.settings_row_inference_backend_subtitle, uiState.localModelBackend),
    selectedBackend = uiState.localModelBackend,
    backendOptions = LocalBackend.entries.map { it.key },
    crashReportingEnabled = uiState.crashReportingEnabled,
    restartRequiredMessage = stringResource(R.string.settings_restart_required_message)
        .takeIf { uiState.restartRequired },
    searchQuery = uiState.searchQuery,
    searchResults = uiState.searchResults,
)

/** Builds the Generation category state (system instructions + advanced sampling). */
@Composable
internal fun buildGenerationViewState(uiState: SettingsUiState, context: Context): GenerationSettingsViewState {
    val locale = LocalConfiguration.current.locales[0]
    return GenerationSettingsViewState(
        systemInstructions = buildSystemInstructions(uiState, context),
        advancedSliders = listOf(
            SettingSliderRow(
                id = SLIDER_TEMPERATURE,
                title = stringResource(R.string.settings_row_temperature_title),
                valueLabel = String.format(locale, "%.1f", uiState.temperature),
                value = uiState.temperature,
                valueRange = 0f..2f,
            ),
            SettingSliderRow(
                id = SLIDER_TOP_K,
                title = stringResource(R.string.settings_row_top_k_title),
                valueLabel = uiState.topK.toString(),
                value = uiState.topK.toFloat(),
                valueRange = 1f..100f,
                steps = TOP_K_STEPS,
            ),
            SettingSliderRow(
                id = SLIDER_TOP_P,
                title = stringResource(R.string.settings_row_top_p_title),
                valueLabel = String.format(locale, "%.2f", uiState.topP),
                value = uiState.topP,
                valueRange = 0f..1f,
            ),
            SettingSliderRow(
                id = SLIDER_MAX_CONTEXT,
                title = stringResource(R.string.settings_row_max_context_title),
                valueLabel = "${uiState.maxContextLength} tok",
                value = uiState.maxContextLength.toFloat(),
                valueRange = 512f..8192f,
                steps = MAX_CONTEXT_STEPS,
            ),
            SettingSliderRow(
                id = SLIDER_AUDIO_MAX_DURATION,
                title = stringResource(R.string.settings_row_voice_length_title),
                valueLabel = "${uiState.audioMaxDurationSec} s",
                value = uiState.audioMaxDurationSec.toFloat(),
                valueRange = SettingsDefaults.AUDIO_MAX_DURATION_SEC_MIN.toFloat()
                    .rangeTo(SettingsDefaults.AUDIO_MAX_DURATION_SEC_MAX.toFloat()),
            ),
        ).withAnchors(),
    )
}

/** Builds the Models category state (active model + backend + providers + restart). */
@Composable
internal fun buildModelsViewState(uiState: SettingsUiState, context: Context): ModelsSettingsViewState =
    ModelsSettingsViewState(
        localModel = buildLocalModelCard(uiState, context),
        providers = uiState.providers.map { summary ->
            ProviderRowState(
                id = summary.id.cloudProvider.id,
                title = summary.displayName,
                fingerprint = summary.keyFingerprint,
                model = summary.model,
                endpointHint = summary.endpointHint,
                isLan = summary.isLanLocal,
                usesAddress = summary.id.cloudProvider.usesBaseUrl,
                modelMissing = summary.modelMissing,
            )
        },
        restartRequiredMessage = stringResource(R.string.settings_restart_required_message)
            .takeIf { uiState.restartRequired },
    )

/** Builds the Memory category state (lifecycle toggles, tuning sliders, data actions). */
@Composable
internal fun buildMemoryViewState(uiState: SettingsUiState, context: Context): MemorySettingsViewState {
    val locale = LocalConfiguration.current.locales[0]
    return MemorySettingsViewState(
        stats = listOf(
            MemoryStatCell(
                value = uiState.memoryStats.chunkCount.toString(),
                label = stringResource(R.string.settings_memory_stat_chunks),
            ),
            MemoryStatCell(
                value = DisplayFormat.formatBytes(uiState.memoryStats.totalBytes),
                label = stringResource(R.string.settings_memory_stat_size),
            ),
            MemoryStatCell(
                value = if (uiState.memoryStats.threadCount > 0) {
                    uiState.memoryStats.threadCount.toString()
                } else {
                    stringResource(R.string.settings_memory_stat_dash)
                },
                label = stringResource(R.string.settings_memory_stat_threads),
            ),
            MemoryStatCell(
                value = uiState.averageSimilarityScore
                    ?.let { String.format(locale, "%.2f", it) }
                    ?: stringResource(R.string.settings_memory_stat_dash),
                label = stringResource(R.string.settings_memory_stat_avg_score),
            ),
        ),
        autoExtractEnabled = uiState.autoExtractEnabled,
        autoExtractLabel = stringResource(R.string.settings_memory_auto_extract_title),
        compactionEnabled = uiState.memoryCompactionEnabled,
        compactionLabel = stringResource(R.string.settings_memory_compaction_title),
        chatHistoryCompressionEnabled = uiState.chatHistoryCompressionEnabled,
        chatHistoryCompressionLabel = stringResource(R.string.settings_memory_history_compression_title),
        advancedSliders = memoryAdvancedSliders(uiState, locale),
        verboseLoggingEnabled = uiState.verboseMemoryLoggingEnabled,
        embeddingTitle = stringResource(R.string.settings_memory_embedding_title),
        embeddingOptions = uiState.embeddingProviderOptions.map { EmbeddingOptionRow(it.id, it.displayName) },
        selectedEmbeddingId = uiState.activeEmbeddingProviderId,
        selectedEmbeddingLabel = uiState.embeddingProviderOptions
            .firstOrNull { it.id == uiState.activeEmbeddingProviderId }
            ?.displayName
            ?: uiState.activeEmbeddingProviderId,
        validationError = uiState.memoryValidationError?.let { memoryValidationMessage(it, context) },
        reembedBanner = stringResource(R.string.settings_memory_reembed_banner).takeIf {
            uiState.lastReembedProviderId != null &&
                uiState.lastReembedProviderId != uiState.activeEmbeddingProviderId
        },
        reembedProgressPercent = uiState.reembedProgress?.let { (it * MAX_PERCENT).roundToInt() },
        exportLabel = stringResource(R.string.settings_memory_export),
        importLabel = stringResource(R.string.settings_memory_import),
        reembedLabel = stringResource(R.string.settings_memory_reembed),
        clearLabel = stringResource(R.string.settings_memory_clear),
        destructiveAction = destructiveActionFor(uiState, context, PendingDestructiveAction.ClearMemory),
    )
}

/** Builds the Pipelines category state (run-limits entry + advanced nesting/repairs). */
@Composable
internal fun buildPipelinesViewState(uiState: SettingsUiState): PipelinesSettingsViewState = PipelinesSettingsViewState(
    // The value summary is the point of the entry row: the numbers a user opened
    // Settings to read stay legible without opening anything further.
    runLimitsSummary = stringResource(
        R.string.run_limits_entry_value,
        uiState.capAutonomousSteps,
        uiState.runMaxTokens,
    ),
    advancedSliders = listOf(
        SettingSliderRow(
            id = SLIDER_PIPELINE_NESTING_DEPTH,
            title = stringResource(R.string.settings_pipeline_nesting_depth_title),
            valueLabel = uiState.pipelineMaxNestingDepth.toString(),
            value = uiState.pipelineMaxNestingDepth.toFloat(),
            valueRange = SettingsDefaults.PIPELINE_MAX_NESTING_DEPTH_MIN.toFloat()
                .rangeTo(SettingsDefaults.PIPELINE_MAX_NESTING_DEPTH_MAX.toFloat()),
            steps = nestingSteps(),
        ),
        SettingSliderRow(
            id = SLIDER_PIPELINE_STRUCTURED_REPAIRS,
            title = stringResource(R.string.settings_pipeline_structured_repairs_title),
            valueLabel = uiState.structuredOutputMaxRepairs.toString(),
            value = uiState.structuredOutputMaxRepairs.toFloat(),
            valueRange = SettingsDefaults.STRUCTURED_OUTPUT_MAX_REPAIRS_MIN.toFloat()
                .rangeTo(SettingsDefaults.STRUCTURED_OUTPUT_MAX_REPAIRS_MAX.toFloat()),
            steps = repairsSteps(),
        ),
    ).withAnchors(),
)

/**
 * Builds the Tools category state (approval policy, guardrail toggles and the
 * tool / workspace ceilings).
 *
 * The five ceilings are shown in the units a person thinks in — seconds,
 * megabytes, kilobytes, tokens — through [ToolCeilingUnits], which also owns
 * the inverse `routeToolsSlider` applies. Converting at the two edges keeps
 * every consumer of the setting reading plain bytes and milliseconds.
 */
@Composable
internal fun buildToolsViewState(uiState: SettingsUiState): ToolsSettingsViewState = ToolsSettingsViewState(
    approveSelection = uiState.toolApprovalPolicy.toApproveOption(),
    approveAllLabel = stringResource(R.string.settings_restrictions_approve_all),
    approveSensitiveLabel = stringResource(R.string.settings_restrictions_approve_sensitive),
    approveNeverLabel = stringResource(R.string.settings_restrictions_approve_never),
    blockDestructive = uiState.blockDestructiveTools,
    blockNetwork = uiState.blockNetworkFromLocalModel,
    advancedSliders = listOf(
        intSlider(
            SLIDER_TOOL_CALL_TIMEOUT,
            stringResource(R.string.settings_row_tool_call_timeout_title),
            "${ToolCeilingUnits.toSeconds(uiState.toolCallTimeoutMs)} s",
            ToolCeilingUnits.toSeconds(uiState.toolCallTimeoutMs),
            ToolCeilingUnits.toSeconds(SettingsDefaults.TOOL_CALL_TIMEOUT_MS_MIN),
            ToolCeilingUnits.toSeconds(SettingsDefaults.TOOL_CALL_TIMEOUT_MS_MAX),
        ),
        intSlider(
            SLIDER_WORKSPACE_MAX_FILE_SIZE,
            stringResource(R.string.settings_row_workspace_max_file_size_title),
            "${ToolCeilingUnits.toMegabytes(uiState.workspaceMaxFileSizeBytes)} MB",
            ToolCeilingUnits.toMegabytes(uiState.workspaceMaxFileSizeBytes),
            ToolCeilingUnits.toMegabytes(SettingsDefaults.WORKSPACE_MAX_FILE_SIZE_BYTES_MIN),
            ToolCeilingUnits.toMegabytes(SettingsDefaults.WORKSPACE_MAX_FILE_SIZE_BYTES_MAX),
        ),
        intSlider(
            SLIDER_WORKSPACE_MAX_TOTAL,
            stringResource(R.string.settings_row_workspace_max_total_title),
            "${ToolCeilingUnits.toMegabytes(uiState.workspaceMaxTotalBytes)} MB",
            ToolCeilingUnits.toMegabytes(uiState.workspaceMaxTotalBytes),
            ToolCeilingUnits.toMegabytes(SettingsDefaults.WORKSPACE_MAX_TOTAL_BYTES_MIN),
            ToolCeilingUnits.toMegabytes(SettingsDefaults.WORKSPACE_MAX_TOTAL_BYTES_MAX),
        ),
        intSlider(
            SLIDER_WORKSPACE_READ_TOKEN_BUDGET,
            stringResource(R.string.settings_row_workspace_read_budget_title),
            "${uiState.workspaceReadTokenBudget} tok",
            uiState.workspaceReadTokenBudget,
            SettingsDefaults.WORKSPACE_READ_TOKEN_BUDGET_MIN,
            SettingsDefaults.WORKSPACE_READ_TOKEN_BUDGET_MAX,
        ),
        intSlider(
            SLIDER_HTTP_TOOL_MAX_RESPONSE,
            stringResource(R.string.settings_row_http_tool_max_response_title),
            "${ToolCeilingUnits.toKilobytes(uiState.httpToolMaxResponseBytes)} KB",
            ToolCeilingUnits.toKilobytes(uiState.httpToolMaxResponseBytes),
            ToolCeilingUnits.toKilobytes(SettingsDefaults.HTTP_TOOL_MAX_RESPONSE_BYTES_MIN),
            ToolCeilingUnits.toKilobytes(SettingsDefaults.HTTP_TOOL_MAX_RESPONSE_BYTES_MAX),
        ),
    ).withAnchors(),
)

/**
 * Builds the Background category state (notification toggles, entry-surface
 * bindings, the external-automation consent switch, and the advanced windows).
 */
@Composable
internal fun buildBackgroundViewState(uiState: SettingsUiState): BackgroundSettingsViewState =
    BackgroundSettingsViewState(
        scheduledResultsEnabled = uiState.scheduledTaskNotificationsEnabled,
        shareTargetPipelineLabel = pipelineBindingLabel(uiState, uiState.shareTargetPipelineId),
        shareReuseSessionEnabled = uiState.shareReuseSession,
        quickTilePipelineLabel = pipelineBindingLabel(uiState, uiState.quickSettingsTilePipelineId),
        externalAutomationEnabled = uiState.externalAutomationEnabled,
        externalAutomationPipelineLabel = externalAutomationBindingLabel(uiState),
        externalAutomationUnbound = uiState.isExternalAutomationUnbound(),
        externalAutomationJournalLabel = externalJournalSummary(uiState.externalAutomationLatestRequest),
        advancedSliders = listOf(
            SettingSliderRow(
                id = SLIDER_BACKGROUND_RESUME_MAX_AGE,
                title = stringResource(R.string.settings_row_resume_window_title),
                valueLabel = "${uiState.resumeMaxAgeHours} h",
                value = uiState.resumeMaxAgeHours.toFloat(),
                valueRange = HOURS_MIN..HOURS_MAX,
            ),
            SettingSliderRow(
                id = SLIDER_BACKGROUND_APPROVAL_WINDOW,
                title = stringResource(R.string.settings_row_approval_window_title),
                valueLabel = "${uiState.backgroundApprovalWindowHours} h",
                value = uiState.backgroundApprovalWindowHours.toFloat(),
                valueRange = HOURS_MIN..HOURS_MAX,
            ),
        ).withAnchors(),
    )

/**
 * Resolves the display label for a surface pipeline binding: the bound
 * pipeline's name, or a localised "Not set" placeholder when [pipelineId] is
 * `null` or no longer matches a known pipeline (e.g. it was deleted).
 */
@Composable
private fun pipelineBindingLabel(uiState: SettingsUiState, pipelineId: String?): String =
    uiState.bindablePipelines.firstOrNull { it.id == pipelineId }?.name
        ?: stringResource(R.string.settings_pipeline_not_set)

/** Builds the Privacy category state (crash reporting + retention sliders). */
@Composable
internal fun buildPrivacyViewState(uiState: SettingsUiState): PrivacySettingsViewState = PrivacySettingsViewState(
    crashReportingEnabled = uiState.crashReportingEnabled,
    // Hidden in the FOSS / F-Droid build, which ships no crash collector. The
    // flavour-specific `BuildConfig.CRASH_REPORTING_AVAILABLE` is `true` for the
    // full distribution and `false` for `foss`.
    crashReportingAvailable = BuildConfig.CRASH_REPORTING_AVAILABLE,
    advancedSliders = listOf(
        SettingSliderRow(
            id = SLIDER_PRIVACY_RETENTION_RUNS,
            title = stringResource(R.string.settings_privacy_retention_runs_title),
            valueLabel = uiState.traceRetentionRunsPerSession.toString(),
            value = uiState.traceRetentionRunsPerSession.toFloat(),
            valueRange = SettingsDefaults.TRACE_RETENTION_RUNS_PER_SESSION_MIN.toFloat()
                .rangeTo(SettingsDefaults.TRACE_RETENTION_RUNS_PER_SESSION_MAX.toFloat()),
        ),
        SettingSliderRow(
            id = SLIDER_PRIVACY_RETENTION_AGE,
            title = stringResource(R.string.settings_privacy_retention_age_title),
            valueLabel = stringResource(R.string.settings_memory_param_days_value, uiState.traceRetentionMaxAgeDays),
            value = uiState.traceRetentionMaxAgeDays.toFloat(),
            valueRange = SettingsDefaults.TRACE_RETENTION_MAX_AGE_DAYS_MIN.toFloat()
                .rangeTo(SettingsDefaults.TRACE_RETENTION_MAX_AGE_DAYS_MAX.toFloat()),
        ),
    ).withAnchors(),
)

/** Builds the About category state (identity + version line + reset confirm). */
@Composable
internal fun buildAboutViewState(uiState: SettingsUiState, context: Context): AboutSettingsViewState =
    AboutSettingsViewState(
        identity = uiState.identity?.let { id ->
            IdentityCardState(
                displayName = id.displayName,
                avatarInitials = context.getString(R.string.settings_identity_avatar_initials),
                metaLine = context.getString(
                    if (id.keystoreAvailable) {
                        R.string.settings_identity_meta_keystore_ok
                    } else {
                        R.string.settings_identity_meta_keystore_missing
                    },
                    id.deviceId,
                ),
            )
        },
        versionLine = listOf(
            "v${BuildConfig.VERSION_NAME}",
            BuildConfig.BUILD_TYPE,
            formatBuildDate(BuildConfig.GIT_COMMIT_DATE_EPOCH_MS),
        ).joinToString(" · "),
        destructiveAction = destructiveActionFor(uiState, context, PendingDestructiveAction.ResetSettings),
    )

// ─── Shared helpers ──────────────────────────────────────────────────────────

@Composable
private fun buildSystemInstructions(uiState: SettingsUiState, context: Context): SystemInstructionsCardState {
    val characterCount = uiState.systemInstructions.length
    val charLimit = SettingsDefaults.SYSTEM_INSTRUCTIONS_CHAR_LIMIT
    return SystemInstructionsCardState(
        value = uiState.systemInstructions,
        placeholder = stringResource(R.string.settings_field_system_prompt_prefix),
        variableChips = uiState.variableCatalog.map { it.placeholder },
        characterCount = characterCount,
        characterLimit = charLimit,
        approximateTokens = DisplayFormat.approxTokenCount(uiState.systemInstructions),
        helperText = stringResource(R.string.settings_system_instructions_helper),
        validationError = if (characterCount > charLimit) {
            context.getString(R.string.settings_system_instructions_too_long, charLimit)
        } else {
            null
        },
    )
}

@Composable
private fun buildLocalModelCard(uiState: SettingsUiState, context: Context): LocalModelCardState {
    val activeMeta = uiState.activeModelMeta
    return LocalModelCardState(
        modelName = activeMeta?.name,
        metaLine = activeMeta?.let { formatActiveModelMeta(it, context) },
        backendLabel = stringResource(R.string.settings_row_inference_backend_subtitle, uiState.localModelBackend),
        backendOptions = LocalBackend.entries.map { it.key },
        selectedBackend = uiState.localModelBackend,
        testProbeText = formatTestProbe(uiState, context),
        testProbeIsError = uiState.lastTestProbeResult?.success == false,
    )
}

@Composable
private fun memoryAdvancedSliders(uiState: SettingsUiState, locale: Locale): List<SettingSliderRow> = listOf(
    intSlider(
        SLIDER_MEMORY_SEARCH_TOP_K,
        stringResource(R.string.settings_memory_param_top_k_title),
        uiState.memorySearchTopK.toString(),
        uiState.memorySearchTopK,
        SettingsDefaults.MEMORY_SEARCH_TOP_K_MIN,
        SettingsDefaults.MEMORY_SEARCH_TOP_K_MAX,
    ),
    SettingSliderRow(
        id = SLIDER_MEMORY_SEARCH_THRESHOLD,
        title = stringResource(R.string.settings_memory_param_threshold_title),
        valueLabel = String.format(locale, "%.2f", uiState.memorySearchThreshold),
        value = uiState.memorySearchThreshold,
        valueRange = SettingsDefaults.MEMORY_SEARCH_THRESHOLD_MIN..SettingsDefaults.MEMORY_SEARCH_THRESHOLD_MAX,
    ),
    intSlider(
        SLIDER_MEMORY_RECENCY_HALF_LIFE,
        stringResource(R.string.settings_memory_param_half_life_title),
        stringResource(R.string.settings_memory_param_days_value, uiState.memoryRecencyHalfLifeDays),
        uiState.memoryRecencyHalfLifeDays,
        SettingsDefaults.MEMORY_RECENCY_HALF_LIFE_DAYS_MIN,
        SettingsDefaults.MEMORY_RECENCY_HALF_LIFE_DAYS_MAX,
    ),
    intSlider(
        SLIDER_MEMORY_COMPACTION_AGE,
        stringResource(R.string.settings_memory_param_compaction_age_title),
        stringResource(R.string.settings_memory_param_days_value, uiState.memoryCompactionAgeDays),
        uiState.memoryCompactionAgeDays,
        SettingsDefaults.MEMORY_COMPACTION_AGE_DAYS_MIN,
        SettingsDefaults.MEMORY_COMPACTION_AGE_DAYS_MAX,
    ),
    intSlider(
        SLIDER_MEMORY_MAX_CHUNKS,
        stringResource(R.string.settings_memory_param_max_chunks_title),
        String.format(locale, "%,d", uiState.maxMemoryChunks),
        uiState.maxMemoryChunks,
        SettingsDefaults.MAX_MEMORY_CHUNKS_MIN,
        SettingsDefaults.MAX_MEMORY_CHUNKS_MAX,
    ),
    intSlider(
        SLIDER_MEMORY_COMPRESSION_THRESHOLD,
        stringResource(R.string.settings_memory_param_compression_threshold_title),
        String.format(locale, "%,d", uiState.chatHistoryCompressionThresholdTokens),
        uiState.chatHistoryCompressionThresholdTokens,
        SettingsDefaults.CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS_MIN,
        SettingsDefaults.CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS_MAX,
    ),
    intSlider(
        SLIDER_MEMORY_LIVE_WINDOW,
        stringResource(R.string.settings_memory_param_live_window_title),
        uiState.chatHistoryLiveWindowSize.toString(),
        uiState.chatHistoryLiveWindowSize,
        SettingsDefaults.CHAT_HISTORY_LIVE_WINDOW_MIN,
        SettingsDefaults.CHAT_HISTORY_LIVE_WINDOW_MAX,
    ),
    intSlider(
        SLIDER_MEMORY_SUMMARY_LIMIT,
        stringResource(R.string.settings_memory_summary_limit_title),
        uiState.memorySummaryDefaultLimit.toString(),
        uiState.memorySummaryDefaultLimit,
        SettingsDefaults.MEMORY_SUMMARY_LIMIT_MIN,
        SettingsDefaults.MEMORY_SUMMARY_LIMIT_MAX,
    ),
).withAnchors()

/**
 * Builds an integer-valued slider kept continuous (`steps = 0`); the callback
 * rounds the dragged float back to an `Int`, avoiding one tick composable per
 * unit over wide ranges (which would freeze the main thread).
 */
private fun intSlider(id: String, title: String, valueLabel: String, value: Int, min: Int, max: Int): SettingSliderRow =
    SettingSliderRow(
        id = id,
        title = title,
        valueLabel = valueLabel,
        value = value.toFloat(),
        valueRange = min.toFloat()..max.toFloat(),
        steps = 0,
    )

/**
 * Maps each slider's stable catalog id to the settings-registry anchor key it
 * tunes, so a search deep-link can flash the right row. Inverse of the
 * `route*Slider` dispatch in `SettingsScreens`; both must list the same sliders.
 */
internal val SLIDER_TO_ANCHOR: Map<String, String> = mapOf(
    SLIDER_TOOL_CALL_TIMEOUT to "TOOL_CALL_TIMEOUT_MS",
    SLIDER_WORKSPACE_MAX_FILE_SIZE to "WORKSPACE_MAX_FILE_SIZE_BYTES",
    SLIDER_WORKSPACE_MAX_TOTAL to "WORKSPACE_MAX_TOTAL_BYTES",
    SLIDER_WORKSPACE_READ_TOKEN_BUDGET to "WORKSPACE_READ_TOKEN_BUDGET",
    SLIDER_HTTP_TOOL_MAX_RESPONSE to "HTTP_TOOL_MAX_RESPONSE_BYTES",
    SLIDER_TEMPERATURE to "TEMPERATURE",
    SLIDER_TOP_K to "TOP_K",
    SLIDER_TOP_P to "TOP_P",
    SLIDER_MAX_CONTEXT to "MAX_CONTEXT_LENGTH",
    SLIDER_AUDIO_MAX_DURATION to "AUDIO_MAX_DURATION_SEC",
    SLIDER_PIPELINE_NESTING_DEPTH to "PIPELINE_MAX_NESTING_DEPTH",
    SLIDER_PIPELINE_STRUCTURED_REPAIRS to "STRUCTURED_OUTPUT_MAX_REPAIRS",
    SLIDER_MEMORY_SEARCH_TOP_K to "MEMORY_SEARCH_TOP_K",
    SLIDER_MEMORY_SEARCH_THRESHOLD to "MEMORY_SEARCH_THRESHOLD",
    SLIDER_MEMORY_RECENCY_HALF_LIFE to "MEMORY_RECENCY_HALF_LIFE_DAYS",
    SLIDER_MEMORY_COMPACTION_AGE to "MEMORY_COMPACTION_AGE_DAYS",
    SLIDER_MEMORY_MAX_CHUNKS to "MAX_MEMORY_CHUNKS",
    SLIDER_MEMORY_COMPRESSION_THRESHOLD to "CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS",
    SLIDER_MEMORY_LIVE_WINDOW to "CHAT_HISTORY_LIVE_WINDOW_SIZE",
    SLIDER_MEMORY_SUMMARY_LIMIT to "MEMORY_SUMMARY_DEFAULT_LIMIT",
    SLIDER_BACKGROUND_RESUME_MAX_AGE to "RESUME_MAX_AGE_HOURS",
    SLIDER_BACKGROUND_APPROVAL_WINDOW to "BACKGROUND_APPROVAL_WINDOW_HOURS",
    SLIDER_PRIVACY_RETENTION_RUNS to "TRACE_RETENTION_RUNS_PER_SESSION",
    SLIDER_PRIVACY_RETENTION_AGE to "TRACE_RETENTION_MAX_AGE_DAYS",
)

/** Stamps the registry anchor onto a slider row so search can deep-link to it. */
private fun SettingSliderRow.withAnchor(): SettingSliderRow = copy(anchorKey = SLIDER_TO_ANCHOR[id])

/** Stamps registry anchors onto every slider in the list. */
private fun List<SettingSliderRow>.withAnchors(): List<SettingSliderRow> = map { it.withAnchor() }

private fun ToolApprovalPolicy.toApproveOption(): ApproveToolCallsOption = when (this) {
    ToolApprovalPolicy.AllCalls -> ApproveToolCallsOption.AllCalls
    ToolApprovalPolicy.SensitiveOrDestructive -> ApproveToolCallsOption.Sensitive
    ToolApprovalPolicy.NeverPrompt -> ApproveToolCallsOption.Never
}

/**
 * Resolves the destructive-dialog payload for [forKind] when that action is the
 * one currently staged; `null` otherwise. Keeps each category screen rendering
 * only its own destructive dialog (Clear-memory on Memory, Reset on About).
 */
private fun destructiveActionFor(
    uiState: SettingsUiState,
    context: Context,
    forKind: PendingDestructiveAction,
): DestructiveActionState? {
    if (uiState.pendingDestructive != forKind) return null
    val isClearMemory = forKind == PendingDestructiveAction.ClearMemory
    return DestructiveActionState(
        title = context.getString(
            if (isClearMemory) {
                R.string.settings_destructive_clear_memory_title
            } else {
                R.string.settings_destructive_reset_title
            },
        ),
        body = context.getString(
            if (isClearMemory) {
                R.string.settings_destructive_clear_memory_body
            } else {
                R.string.settings_destructive_reset_body
            },
        ),
        keyword = if (isClearMemory) context.getString(R.string.destructive_typed_keyword) else "",
        hint = if (isClearMemory) context.getString(R.string.settings_destructive_typed_hint) else "",
        pendingInput = if (isClearMemory) uiState.destructiveTypedInput else "",
        kind = if (isClearMemory) DestructiveActionKind.ClearMemory else DestructiveActionKind.ResetSettings,
    )
}

private fun memoryValidationMessage(error: MemoryValidationError, context: Context): String = when (error) {
    MemoryValidationError.SearchTopK -> context.getString(
        R.string.settings_memory_validation_top_k,
        SettingsDefaults.MEMORY_SEARCH_TOP_K_MIN,
        SettingsDefaults.MEMORY_SEARCH_TOP_K_MAX,
    )
    MemoryValidationError.SearchThreshold -> context.getString(
        R.string.settings_memory_validation_threshold,
        String.format(Locale.US, "%.2f", SettingsDefaults.MEMORY_SEARCH_THRESHOLD_MIN),
        String.format(Locale.US, "%.2f", SettingsDefaults.MEMORY_SEARCH_THRESHOLD_MAX),
    )
    MemoryValidationError.RecencyHalfLife -> context.getString(
        R.string.settings_memory_validation_half_life,
        SettingsDefaults.MEMORY_RECENCY_HALF_LIFE_DAYS_MIN,
        SettingsDefaults.MEMORY_RECENCY_HALF_LIFE_DAYS_MAX,
    )
    MemoryValidationError.CompactionAge -> context.getString(
        R.string.settings_memory_validation_compaction_age,
        SettingsDefaults.MEMORY_COMPACTION_AGE_DAYS_MIN,
        SettingsDefaults.MEMORY_COMPACTION_AGE_DAYS_MAX,
    )
    MemoryValidationError.MaxChunks -> context.getString(
        R.string.settings_memory_validation_max_chunks,
        SettingsDefaults.MAX_MEMORY_CHUNKS_MIN,
        SettingsDefaults.MAX_MEMORY_CHUNKS_MAX,
    )
    MemoryValidationError.CompressionThreshold -> context.getString(
        R.string.settings_memory_validation_compression_threshold,
        SettingsDefaults.CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS_MIN,
        SettingsDefaults.CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS_MAX,
    )
    MemoryValidationError.LiveWindow -> context.getString(
        R.string.settings_memory_validation_live_window,
        SettingsDefaults.CHAT_HISTORY_LIVE_WINDOW_MIN,
        SettingsDefaults.CHAT_HISTORY_LIVE_WINDOW_MAX,
    )
    MemoryValidationError.UnknownEmbeddingProvider -> context.getString(
        R.string.settings_memory_validation_unknown_provider,
    )
}

private fun formatBuildDate(epochMs: Long): String =
    SimpleDateFormat("yyyy.MM.dd", Locale.US).format(java.util.Date(epochMs))

private fun formatActiveModelMeta(meta: ActiveModelMeta, context: Context): String {
    val size = DisplayFormat.formatBytes(meta.sizeBytes)
    val ctx = "${meta.contextWindowTokens}"
    val quant = meta.quantization ?: "-"
    val downloaded = meta.downloadedAtMs?.let {
        SimpleDateFormat("d MMM", Locale.getDefault()).format(java.util.Date(it))
    } ?: "-"
    return context.getString(R.string.settings_local_model_meta, size, ctx, quant, downloaded)
}

private fun formatTestProbe(uiState: SettingsUiState, context: Context): String {
    val probe = uiState.lastTestProbeResult ?: return context.getString(R.string.settings_row_test_backend_idle)
    return if (probe.success) {
        val durationLabel = String.format(Locale.getDefault(), "%.2fs", probe.durationMs / MS_PER_SECOND_F)
        val tpsLabel = String.format(Locale.getDefault(), "%.1f", probe.tokensPerSecond)
        context.getString(R.string.settings_row_test_backend_success, probe.tokensGenerated, durationLabel, tpsLabel)
    } else {
        context.getString(R.string.settings_row_test_backend_failed, probe.errorMessage.orEmpty())
    }
}

private fun nestingSteps(): Int =
    SettingsDefaults.PIPELINE_MAX_NESTING_DEPTH_MAX - SettingsDefaults.PIPELINE_MAX_NESTING_DEPTH_MIN - 1

private fun repairsSteps(): Int =
    SettingsDefaults.STRUCTURED_OUTPUT_MAX_REPAIRS_MAX - SettingsDefaults.STRUCTURED_OUTPUT_MAX_REPAIRS_MIN - 1

private const val MS_PER_SECOND_F = 1_000f
private const val MAX_PERCENT = 100

private const val TOP_K_STEPS = 99
private const val MAX_CONTEXT_STEPS = 14
private const val HOURS_MIN = 1f
private const val HOURS_MAX = 168f
