@file:Suppress("MatchingDeclarationName")

package app.knotwork.android.presentation.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.knotwork.android.R
import app.knotwork.android.domain.models.EntrySurface
import app.knotwork.android.domain.models.MemoryImportStrategy
import app.knotwork.android.domain.models.ProviderId
import app.knotwork.android.domain.models.ToolApprovalPolicy
import app.knotwork.android.presentation.tile.requestAddDutyTile
import app.knotwork.android.presentation.ui.common.openDocumentation
import app.knotwork.design.components.dialogs.SingleChoiceDialog
import app.knotwork.design.components.dialogs.SingleChoiceDialogUi
import app.knotwork.design.components.dialogs.SingleChoiceOptionUi
import app.knotwork.design.screens.automation.ExternalAutomationConsentContent
import app.knotwork.design.screens.automation.ExternalAutomationConsentStrings
import app.knotwork.design.screens.settings.AboutSettingsContent
import app.knotwork.design.screens.settings.ApproveToolCallsOption
import app.knotwork.design.screens.settings.BackgroundSettingsContent
import app.knotwork.design.screens.settings.GenerationSettingsContent
import app.knotwork.design.screens.settings.HubSearchResultRow
import app.knotwork.design.screens.settings.LocalSettingsHighlightKey
import app.knotwork.design.screens.settings.LocalSettingsHints
import app.knotwork.design.screens.settings.MemoryImportDialogUi
import app.knotwork.design.screens.settings.MemorySettingsContent
import app.knotwork.design.screens.settings.ModelsSettingsContent
import app.knotwork.design.screens.settings.PipelinesSettingsContent
import app.knotwork.design.screens.settings.PrivacySettingsContent
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
import app.knotwork.design.screens.settings.SettingsCallbacks
import app.knotwork.design.screens.settings.SettingsCategoryId
import app.knotwork.design.screens.settings.SettingsHubContent
import app.knotwork.design.screens.settings.ToolsSettingsContent
import com.jakewharton.processphoenix.ProcessPhoenix
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import app.knotwork.android.domain.settings.SettingsCategoryId as DomainCategoryId
import app.knotwork.design.screens.settings.MemoryImportDialog as CatalogMemoryImportDialog

/**
 * Navigation actions threaded into every settings screen from the nav graph.
 * Kept as one bundle so each screen takes a single `nav` argument.
 *
 * @property onBack Up navigation from the current screen.
 * @property onOpenCategory Open a category sub-screen from the hub.
 * @property onOpenModels Open the model-discovery / management surface.
 * @property onOpenProvider Open a cloud-provider detail screen.
 * @property onOpenAddProvider Open the add-provider picker.
 * @property onOpenManageTools Open the Tools / MCP screen.
 * @property onOpenAllowedDomains Open the allowed-HTTP-domains screen.
 * @property onOpenLicenses Open the About / licenses screen.
 * @property onOpenUsageStatistics Open the on-device Usage statistics screen.
 * @property onOpenExternalAutomationJournal Open the external-automation request
 *   journal.
 * @property onOpenRunLimits Open the run-limits screen.
 */
data class SettingsNavActions(
    val onBack: () -> Unit,
    val onOpenCategory: (SettingsCategoryId) -> Unit,
    val onOpenModels: () -> Unit,
    val onOpenProvider: (ProviderId) -> Unit,
    val onOpenAddProvider: () -> Unit,
    val onOpenManageTools: () -> Unit,
    val onOpenAllowedDomains: () -> Unit,
    val onOpenLicenses: () -> Unit,
    val onOpenUsageStatistics: () -> Unit,
    val onOpenExternalAutomationJournal: () -> Unit,
    val onOpenRunLimits: () -> Unit,
)

/** Settings hub: search field, the five inline Basic controls and the category list. */
@Composable
fun SettingsHubScreen(viewModel: SettingsViewModel, nav: SettingsNavActions) {
    val uiState by viewModel.uiState.collectAsState()
    val callbacks = rememberSettingsCallbacks(
        viewModel = viewModel,
        nav = nav,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        onClearSearch = viewModel::clearSearch,
        onSearchResultClick = { row ->
            viewModel.requestHighlight(row.anchorKey)
            nav.onOpenCategory(row.categoryId)
        },
    )
    SettingsSurface(viewModel) {
        SettingsHubContent(state = buildHubViewState(uiState), callbacks = callbacks)
    }
}

/**
 * Reads the ViewModel-resolved highlight for [category] and, when one targets
 * this screen, schedules it to clear after a short dwell so the flash fires once
 * on arrival. The registry lookup itself lives in the ViewModel
 * ([SettingsViewModel.resolveHighlight]); this only owns the consume effect.
 */
@Composable
private fun rememberCategoryHighlight(
    viewModel: SettingsViewModel,
    anchor: String?,
    category: DomainCategoryId,
): SettingsHighlight {
    val highlight = viewModel.resolveHighlight(anchor, category)
    LaunchedEffect(highlight.key) {
        if (highlight.key != null) {
            delay(HIGHLIGHT_CONSUME_MS)
            viewModel.highlightConsumed()
        }
    }
    return highlight
}

/** Generation category sub-screen. */
@Composable
fun GenerationSettingsScreen(viewModel: SettingsViewModel, nav: SettingsNavActions) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val callbacks = rememberSettingsCallbacks(viewModel, nav)
    val highlight = rememberCategoryHighlight(viewModel, uiState.pendingHighlightAnchor, DomainCategoryId.GENERATION)
    SettingsSurface(viewModel) {
        CompositionLocalProvider(LocalSettingsHighlightKey provides highlight.key) {
            GenerationSettingsContent(
                state = buildGenerationViewState(uiState, context),
                callbacks = callbacks,
                advancedExpanded = highlight.advancedExpanded,
            )
        }
    }
}

/** Models category sub-screen. */
@Composable
fun ModelsSettingsScreen(viewModel: SettingsViewModel, nav: SettingsNavActions) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val callbacks = rememberSettingsCallbacks(viewModel, nav)
    val highlight = rememberCategoryHighlight(viewModel, uiState.pendingHighlightAnchor, DomainCategoryId.MODELS)
    SettingsSurface(viewModel) {
        CompositionLocalProvider(LocalSettingsHighlightKey provides highlight.key) {
            ModelsSettingsContent(state = buildModelsViewState(uiState, context), callbacks = callbacks)
        }
    }
}

/** Memory category sub-screen (also hosts the import strategy dialog). */
@Composable
fun MemorySettingsScreen(viewModel: SettingsViewModel, nav: SettingsNavActions) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val callbacks = rememberSettingsCallbacks(viewModel, nav)
    val highlight = rememberCategoryHighlight(viewModel, uiState.pendingHighlightAnchor, DomainCategoryId.MEMORY)
    SettingsSurface(viewModel) {
        CompositionLocalProvider(LocalSettingsHighlightKey provides highlight.key) {
            MemorySettingsContent(
                state = buildMemoryViewState(uiState, context),
                callbacks = callbacks,
                advancedExpanded = highlight.advancedExpanded,
            )
        }
        uiState.pendingImport?.let { pending ->
            MemoryImportDialog(
                pending = pending,
                onMerge = { viewModel.confirmImport(MemoryImportStrategy.Merge) },
                onReplace = { viewModel.confirmImport(MemoryImportStrategy.Replace) },
                onCancel = viewModel::cancelImport,
            )
        }
    }
}

/** Pipelines-&-structured-output category sub-screen. */
@Composable
fun PipelinesSettingsScreen(viewModel: SettingsViewModel, nav: SettingsNavActions) {
    val uiState by viewModel.uiState.collectAsState()
    val callbacks = rememberSettingsCallbacks(viewModel, nav)
    val highlight = rememberCategoryHighlight(viewModel, uiState.pendingHighlightAnchor, DomainCategoryId.PIPELINES)
    SettingsSurface(viewModel) {
        CompositionLocalProvider(LocalSettingsHighlightKey provides highlight.key) {
            PipelinesSettingsContent(
                state = buildPipelinesViewState(uiState),
                callbacks = callbacks,
                advancedExpanded = highlight.advancedExpanded,
            )
        }
    }
}

/** Tools-&-workspace category sub-screen. */
@Composable
fun ToolsSettingsScreen(viewModel: SettingsViewModel, nav: SettingsNavActions) {
    val uiState by viewModel.uiState.collectAsState()
    val callbacks = rememberSettingsCallbacks(viewModel, nav)
    val highlight = rememberCategoryHighlight(viewModel, uiState.pendingHighlightAnchor, DomainCategoryId.TOOLS)
    SettingsSurface(viewModel) {
        CompositionLocalProvider(LocalSettingsHighlightKey provides highlight.key) {
            ToolsSettingsContent(
                state = buildToolsViewState(uiState),
                callbacks = callbacks,
                advancedExpanded = highlight.advancedExpanded,
            )
        }
    }
}

/** Background-&-triggers category sub-screen. */
@Composable
fun BackgroundSettingsScreen(viewModel: SettingsViewModel, nav: SettingsNavActions) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var pickerSurface by remember { mutableStateOf<EntrySurface?>(null) }
    val callbacks = rememberSettingsCallbacks(
        viewModel = viewModel,
        nav = nav,
        onShareTargetPipelineClick = { pickerSurface = EntrySurface.SHARE },
        onQuickTilePipelineClick = { pickerSurface = EntrySurface.QUICK_TILE },
        onExternalAutomationPipelineClick = { pickerSurface = EntrySurface.EXTERNAL_AUTOMATION },
    )
    val highlight = rememberCategoryHighlight(viewModel, uiState.pendingHighlightAnchor, DomainCategoryId.BACKGROUND)
    SettingsSurface(viewModel) {
        CompositionLocalProvider(LocalSettingsHighlightKey provides highlight.key) {
            BackgroundSettingsContent(
                state = buildBackgroundViewState(uiState),
                callbacks = callbacks,
                advancedExpanded = highlight.advancedExpanded,
            )
        }
    }
    pickerSurface?.let { surface ->
        // Exhaustive on purpose: a new EntrySurface must not compile until this
        // screen decides what it shows for it, rather than borrowing a sentence
        // written about a different surface.
        val titleRes = when (surface) {
            EntrySurface.SHARE -> R.string.settings_pipeline_picker_share_title
            EntrySurface.QUICK_TILE -> R.string.settings_pipeline_picker_tile_title
            EntrySurface.EXTERNAL_AUTOMATION -> R.string.settings_pipeline_picker_external_title
        }
        val selectedId = when (surface) {
            EntrySurface.SHARE -> uiState.shareTargetPipelineId
            EntrySurface.QUICK_TILE -> uiState.quickSettingsTilePipelineId
            EntrySurface.EXTERNAL_AUTOMATION -> uiState.externalAutomationPipelineId
        }
        SurfacePipelinePickerDialog(
            title = stringResource(titleRes),
            options = uiState.bindablePipelines,
            selectedId = selectedId,
            onSelect = { pipelineId ->
                viewModel.setSurfacePipeline(surface, pipelineId)
                // Offer to pin the tile in context, once the user has bound it.
                if (surface == EntrySurface.QUICK_TILE && pipelineId != null) requestAddDutyTile(context)
                pickerSurface = null
            },
            onDismiss = { pickerSurface = null },
        )
    }
    if (uiState.pendingExternalAutomationConsent) {
        Dialog(onDismissRequest = viewModel::dismissExternalAutomationConsent) {
            ExternalAutomationConsentContent(
                onConfirm = viewModel::confirmExternalAutomationConsent,
                onCancel = viewModel::dismissExternalAutomationConsent,
                strings = externalAutomationConsentStrings(),
            )
        }
    }
}

/** Localised copy of the external-automation consent dialog. */
@Composable
private fun externalAutomationConsentStrings(): ExternalAutomationConsentStrings = ExternalAutomationConsentStrings(
    title = stringResource(R.string.external_automation_consent_title),
    intro = stringResource(R.string.external_automation_consent_intro),
    bulletAnyApp = stringResource(R.string.external_automation_consent_any_app),
    bulletOnePipeline = stringResource(R.string.external_automation_consent_one_pipeline),
    bulletApprovals = stringResource(R.string.external_automation_consent_approvals),
    bulletCost = stringResource(R.string.external_automation_consent_cost),
    bulletReversible = stringResource(R.string.external_automation_consent_reversible),
    confirm = stringResource(R.string.external_automation_consent_confirm),
    cancel = stringResource(R.string.external_automation_consent_cancel),
)

/**
 * `:app` binding of the catalog's single-choice dialog for the pipeline that a
 * given entry surface runs.
 *
 * "None" is a real option rather than the absence of one: choosing it clears the
 * binding so the surface returns to its inert, privacy-first default. It is
 * therefore rendered as a row like any other, with `null` as its id.
 *
 * @param title Dialog title, naming the surface being bound.
 * @param options The pipelines available to bind.
 * @param selectedId Currently bound pipeline, or `null` for none.
 * @param onSelect A row was tapped; `null` clears the binding.
 * @param onDismiss Cancel or scrim tap.
 */
@Composable
private fun SurfacePipelinePickerDialog(
    title: String,
    options: List<PipelineBindingOption>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    SingleChoiceDialog(
        ui = SingleChoiceDialogUi(
            title = title,
            options = listOf(
                SingleChoiceOptionUi(id = null, label = stringResource(R.string.settings_pipeline_picker_none)),
            ) + options.map { SingleChoiceOptionUi(id = it.id, label = it.name) },
            selectedId = selectedId,
            cancelLabel = stringResource(R.string.common_cancel),
        ),
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

/** Privacy category sub-screen. */
@Composable
fun PrivacySettingsScreen(viewModel: SettingsViewModel, nav: SettingsNavActions) {
    val uiState by viewModel.uiState.collectAsState()
    val callbacks = rememberSettingsCallbacks(viewModel, nav)
    val highlight = rememberCategoryHighlight(viewModel, uiState.pendingHighlightAnchor, DomainCategoryId.PRIVACY)
    SettingsSurface(viewModel) {
        CompositionLocalProvider(LocalSettingsHighlightKey provides highlight.key) {
            PrivacySettingsContent(
                state = buildPrivacyViewState(uiState),
                callbacks = callbacks,
                advancedExpanded = highlight.advancedExpanded,
            )
        }
    }
}

/** About category sub-screen (also hosts the reset confirm dialog via the content). */
@Composable
fun AboutSettingsScreen(viewModel: SettingsViewModel, nav: SettingsNavActions) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val callbacks = rememberSettingsCallbacks(viewModel, nav)
    val highlight = rememberCategoryHighlight(viewModel, uiState.pendingHighlightAnchor, DomainCategoryId.ABOUT)
    SettingsSurface(viewModel) {
        CompositionLocalProvider(LocalSettingsHighlightKey provides highlight.key) {
            AboutSettingsContent(
                state = buildAboutViewState(uiState, context),
                callbacks = callbacks,
                advancedExpanded = highlight.advancedExpanded,
            )
        }
    }
}

/**
 * Wraps a settings screen body in a Box that hosts the one-shot snackbar surfaced
 * by VM actions (memory export, reset, probe, sampling reset).
 */
@Composable
private fun SettingsSurface(viewModel: SettingsViewModel, content: @Composable () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    // Remembered per screen, not saved: a hint is a sentence you read once, so
    // returning to a category finds every explanation closed again. (Collapsible
    // row *groups* do persist — a group is a workspace, a hint is not.)
    val hints = remember(context) {
        SettingsHelpCatalog.controller(context) { id -> openDocumentation(context, id) }
    }
    LaunchedEffect(uiState.snackbarMessage) {
        val message = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.snackbarShown()
    }
    CompositionLocalProvider(LocalSettingsHints provides hints) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            SnackbarHost(hostState = snackbarHostState)
        }
    }
}

/**
 * Builds the unified [SettingsCallbacks] bag shared by every settings screen:
 * VM forwarders, the nav actions, the memory SAF launchers and the restart
 * trigger. Every screen receives the full bag and uses only the subset its
 * controls invoke.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun rememberSettingsCallbacks(
    viewModel: SettingsViewModel,
    nav: SettingsNavActions,
    onSearchQueryChange: (String) -> Unit = {},
    onSearchResultClick: (HubSearchResultRow) -> Unit = {},
    onClearSearch: () -> Unit = {},
    onShareTargetPipelineClick: () -> Unit = {},
    onQuickTilePipelineClick: () -> Unit = {},
    onExternalAutomationPipelineClick: () -> Unit = {},
): SettingsCallbacks {
    val context = LocalContext.current
    val exportFilename = stringResource(R.string.settings_memory_export_filename)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MIME_JSON),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val stream = runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()
        if (stream != null) viewModel.exportMemoryBase(stream)
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
        if (stream != null) viewModel.importMemory(stream)
    }
    return SettingsCallbacks(
        onBack = nav.onBack,
        onOpenCategory = nav.onOpenCategory,
        onSearchQueryChange = onSearchQueryChange,
        onSearchResultClick = onSearchResultClick,
        onClearSearch = onClearSearch,
        onOpenSystemInstructions = { nav.onOpenCategory(SettingsCategoryId.Generation) },
        onManageModelsClick = nav.onOpenModels,
        onOpenManageTools = nav.onOpenManageTools,
        onOpenAllowedDomains = nav.onOpenAllowedDomains,
        onOpenLicenses = nav.onOpenLicenses,
        onSystemInstructionsChange = viewModel::updateSystemInstructions,
        onChipInsert = viewModel::insertVariable,
        onGenerationSliderChange = { id, value -> routeGenerationSlider(viewModel, id, value) },
        onResetSamplingDefaults = viewModel::resetSamplingDefaults,
        onApproveSelectionChange = { option -> viewModel.setToolApprovalPolicy(option.toPolicy()) },
        onBlockDestructiveChange = viewModel::setBlockDestructiveTools,
        onBlockNetworkChange = viewModel::setBlockNetworkFromLocalModel,
        onToolsSliderChange = { id, value -> routeToolsSlider(viewModel, id, value) },
        onPipelinesSliderChange = { id, value -> routePipelinesSlider(viewModel, id, value) },
        onBackendSelected = viewModel::setLocalModelBackend,
        onTestBackendClick = viewModel::runBackendProbe,
        onProviderRowClick = { id ->
            ProviderId.entries.firstOrNull { it.cloudProvider.id == id }?.let(nav.onOpenProvider)
        },
        onAddProviderClick = nav.onOpenAddProvider,
        onRestartClick = {
            viewModel.acknowledgeRestart()
            ProcessPhoenix.triggerRebirth(context.applicationContext)
        },
        onAutoExtractToggle = viewModel::setAutoExtractEnabled,
        onMemoryCompactionToggle = viewModel::setMemoryCompactionEnabled,
        onChatHistoryCompressionToggle = viewModel::setChatHistoryCompressionEnabled,
        onMemorySliderChange = { id, value -> routeMemorySlider(viewModel, id, value) },
        onVerboseMemoryLoggingToggle = viewModel::setVerboseMemoryLoggingEnabled,
        onEmbeddingProviderSelected = viewModel::setActiveEmbeddingProviderId,
        onExportMemoryClick = { exportLauncher.launch(exportFilename) },
        onImportMemoryClick = { importLauncher.launch(arrayOf(MIME_JSON)) },
        onReembedClick = viewModel::runReembed,
        onClearMemoryClick = viewModel::stageClearMemory,
        onScheduledResultsToggle = viewModel::setScheduledTaskNotificationsEnabled,
        onBackgroundSliderChange = { id, value -> routeBackgroundSlider(viewModel, id, value) },
        onShareTargetPipelineClick = onShareTargetPipelineClick,
        onShareReuseSessionToggle = viewModel::setShareReuseSession,
        onQuickTilePipelineClick = onQuickTilePipelineClick,
        onExternalAutomationToggle = viewModel::setExternalAutomationEnabled,
        onExternalAutomationPipelineClick = onExternalAutomationPipelineClick,
        onOpenExternalAutomationJournal = nav.onOpenExternalAutomationJournal,
        onOpenRunLimits = nav.onOpenRunLimits,
        onCrashReportingToggle = viewModel::setCrashReportingEnabled,
        onPrivacySliderChange = { id, value -> routePrivacySlider(viewModel, id, value) },
        onOpenUsageStatistics = nav.onOpenUsageStatistics,
        onResetSettingsClick = viewModel::stageResetSettings,
        onDestructiveTypedConfirmChange = viewModel::updateDestructiveTypedInput,
        onDestructiveConfirm = viewModel::confirmDestructive,
        onDestructiveCancel = viewModel::cancelDestructive,
    )
}

private fun routeGenerationSlider(viewModel: SettingsViewModel, id: String, value: Float) {
    when (id) {
        SLIDER_TEMPERATURE -> viewModel.setTemperature(value)
        SLIDER_TOP_K -> viewModel.setTopK(value.roundToInt())
        SLIDER_TOP_P -> viewModel.setTopP(value)
        SLIDER_MAX_CONTEXT -> viewModel.setMaxContextLength(value.roundToInt())
        SLIDER_AUDIO_MAX_DURATION -> viewModel.setAudioMaxDurationSec(value.roundToInt())
    }
}

/**
 * Dispatches a Tools-&-workspace slider back to the ViewModel, converting the
 * human-facing unit shown on the row (seconds, MB, KB, tokens) into the unit the
 * setting is stored in — through [ToolCeilingUnits], the same object
 * `buildToolsViewState` reads the value out with.
 */
private fun routeToolsSlider(viewModel: SettingsViewModel, id: String, value: Float) {
    when (id) {
        SLIDER_TOOL_CALL_TIMEOUT -> viewModel.setToolCallTimeoutMs(ToolCeilingUnits.secondsToMillis(value))
        SLIDER_WORKSPACE_MAX_FILE_SIZE ->
            viewModel.setWorkspaceMaxFileSizeBytes(ToolCeilingUnits.megabytesToBytes(value))
        SLIDER_WORKSPACE_MAX_TOTAL -> viewModel.setWorkspaceMaxTotalBytes(ToolCeilingUnits.megabytesToBytes(value))
        SLIDER_WORKSPACE_READ_TOKEN_BUDGET -> viewModel.setWorkspaceReadTokenBudget(value.roundToInt())
        SLIDER_HTTP_TOOL_MAX_RESPONSE ->
            viewModel.setHttpToolMaxResponseBytes(ToolCeilingUnits.kilobytesToBytes(value))
    }
}

private fun routePipelinesSlider(viewModel: SettingsViewModel, id: String, value: Float) {
    when (id) {
        SLIDER_PIPELINE_NESTING_DEPTH -> viewModel.setPipelineMaxNestingDepth(value.roundToInt())
        SLIDER_PIPELINE_STRUCTURED_REPAIRS -> viewModel.setStructuredOutputMaxRepairs(value.roundToInt())
    }
}

private fun routeMemorySlider(viewModel: SettingsViewModel, id: String, value: Float) {
    when (id) {
        SLIDER_MEMORY_SEARCH_TOP_K -> viewModel.setMemorySearchTopK(value.roundToInt())
        SLIDER_MEMORY_SEARCH_THRESHOLD -> viewModel.setMemorySearchThreshold(value)
        SLIDER_MEMORY_RECENCY_HALF_LIFE -> viewModel.setMemoryRecencyHalfLifeDays(value.roundToInt())
        SLIDER_MEMORY_COMPACTION_AGE -> viewModel.setMemoryCompactionAgeDays(value.roundToInt())
        SLIDER_MEMORY_MAX_CHUNKS -> viewModel.setMaxMemoryChunks(value.roundToInt())
        SLIDER_MEMORY_COMPRESSION_THRESHOLD -> viewModel.setChatHistoryCompressionThresholdTokens(value.roundToInt())
        SLIDER_MEMORY_LIVE_WINDOW -> viewModel.setChatHistoryLiveWindowSize(value.roundToInt())
        SLIDER_MEMORY_SUMMARY_LIMIT -> viewModel.setMemorySummaryDefaultLimit(value.roundToInt())
    }
}

private fun routeBackgroundSlider(viewModel: SettingsViewModel, id: String, value: Float) {
    when (id) {
        SLIDER_BACKGROUND_RESUME_MAX_AGE -> viewModel.setResumeMaxAgeHours(value.roundToInt())
        SLIDER_BACKGROUND_APPROVAL_WINDOW -> viewModel.setBackgroundApprovalWindowHours(value.roundToInt())
    }
}

private fun routePrivacySlider(viewModel: SettingsViewModel, id: String, value: Float) {
    when (id) {
        SLIDER_PRIVACY_RETENTION_RUNS -> viewModel.setTraceRetentionRunsPerSession(value.roundToInt())
        SLIDER_PRIVACY_RETENTION_AGE -> viewModel.setTraceRetentionMaxAgeDays(value.roundToInt())
    }
}

private fun ApproveToolCallsOption.toPolicy(): ToolApprovalPolicy = when (this) {
    ApproveToolCallsOption.AllCalls -> ToolApprovalPolicy.AllCalls
    ApproveToolCallsOption.Sensitive -> ToolApprovalPolicy.SensitiveOrDestructive
    ApproveToolCallsOption.Never -> ToolApprovalPolicy.NeverPrompt
}

/**
 * `:app` binding of the catalog's memory-import dialog: resolves the copy and
 * decides which mismatch warnings apply to the parsed document.
 *
 * Which warnings apply is decided by [PendingMemoryImport.warnings]; this binding
 * turns each into its sentence, and the catalog receives finished sentences.
 *
 * @param pending The parsed document awaiting a strategy.
 * @param onMerge Keep existing entries, skip duplicate ids.
 * @param onReplace Wipe, then load.
 * @param onCancel Import nothing.
 */
@Composable
private fun MemoryImportDialog(
    pending: PendingMemoryImport,
    onMerge: () -> Unit,
    onReplace: () -> Unit,
    onCancel: () -> Unit,
) {
    CatalogMemoryImportDialog(
        ui = MemoryImportDialogUi(
            title = stringResource(R.string.settings_memory_import_dialog_title),
            body = stringResource(R.string.settings_memory_import_dialog_body, pending.document.chunks.size),
            warnings = pending.warnings.map { warning ->
                when (warning) {
                    MemoryImportWarning.SchemaMismatch -> stringResource(R.string.settings_memory_import_schema_warning)
                    MemoryImportWarning.ProviderMismatch -> stringResource(
                        R.string.settings_memory_import_provider_warning,
                        pending.document.embeddingProviderId,
                    )
                    MemoryImportWarning.PinsNotImported -> stringResource(
                        R.string.settings_memory_import_pins_warning,
                        pending.document.pinnedInFile,
                    )
                }
            },
            mergeLabel = stringResource(R.string.settings_memory_import_merge),
            replaceLabel = stringResource(R.string.settings_memory_import_replace),
            cancelLabel = stringResource(R.string.settings_memory_import_cancel),
        ),
        onMerge = onMerge,
        onReplace = onReplace,
        onCancel = onCancel,
    )
}

private const val MIME_JSON = "application/json"
private val IMPORT_BUTTON_GAP = 8.dp

/** Dwell before a settings-search deep-link highlight clears (covers the flash). */
private const val HIGHLIGHT_CONSUME_MS = 1500L
