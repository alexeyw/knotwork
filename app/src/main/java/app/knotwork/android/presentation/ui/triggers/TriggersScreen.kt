package app.knotwork.android.presentation.ui.triggers

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.knotwork.android.R
import app.knotwork.android.domain.constants.DocumentationLinks
import app.knotwork.android.domain.models.Trigger
import app.knotwork.android.domain.models.TriggerCondition
import app.knotwork.android.domain.models.TriggerEvaluationSource
import app.knotwork.android.domain.models.TriggerEvaluationVerdict
import app.knotwork.android.domain.models.TriggerHealthStatus
import app.knotwork.android.domain.models.TriggerHitlResolution
import app.knotwork.android.domain.models.TriggerRunOutcome
import app.knotwork.android.domain.models.TriggerSkipReason
import app.knotwork.android.domain.usecases.ClockTime
import app.knotwork.android.domain.usecases.JournalDayHeader
import app.knotwork.android.domain.usecases.JournalTimestamp
import app.knotwork.android.domain.usecases.TriggerHealthEvaluator
import app.knotwork.android.domain.usecases.TriggerJournalGrouper
import app.knotwork.android.domain.usecases.TriggerJournalView
import app.knotwork.android.presentation.ui.common.JournalExportActionHandlers
import app.knotwork.android.presentation.ui.common.asString
import app.knotwork.android.presentation.ui.common.openDocumentation
import app.knotwork.android.presentation.ui.common.rememberJournalExportHandlers
import app.knotwork.design.components.misc.KnotworkSnackbarHost
import app.knotwork.design.screens.triggers.TriggerConditionType
import app.knotwork.design.screens.triggers.TriggerDeleteDialogContent
import app.knotwork.design.screens.triggers.TriggerDeleteStrings
import app.knotwork.design.screens.triggers.TriggerDeleteUi
import app.knotwork.design.screens.triggers.TriggerDetailCallbacks
import app.knotwork.design.screens.triggers.TriggerDetailContent
import app.knotwork.design.screens.triggers.TriggerDetailStrings
import app.knotwork.design.screens.triggers.TriggerDetailViewState
import app.knotwork.design.screens.triggers.TriggerEditorCallbacks
import app.knotwork.design.screens.triggers.TriggerEditorContent
import app.knotwork.design.screens.triggers.TriggerEditorStrings
import app.knotwork.design.screens.triggers.TriggerEditorUi
import app.knotwork.design.screens.triggers.TriggerHealthUi
import app.knotwork.design.screens.triggers.TriggerJournalDayGroupUi
import app.knotwork.design.screens.triggers.TriggerJournalEntryUi
import app.knotwork.design.screens.triggers.TriggerJournalHitlUi
import app.knotwork.design.screens.triggers.TriggerJournalOutcomeUi
import app.knotwork.design.screens.triggers.TriggerJournalSkipReasonUi
import app.knotwork.design.screens.triggers.TriggerJournalSourceUi
import app.knotwork.design.screens.triggers.TriggerJournalVerdictUi
import app.knotwork.design.screens.triggers.TriggerJournalVisualState
import app.knotwork.design.screens.triggers.TriggerPipelineOptionUi
import app.knotwork.design.screens.triggers.TriggerRowUi
import app.knotwork.design.screens.triggers.TriggersCallbacks
import app.knotwork.design.screens.triggers.TriggersContent
import app.knotwork.design.screens.triggers.TriggersStrings
import app.knotwork.design.screens.triggers.TriggersViewState
import app.knotwork.design.screens.triggers.TriggersVisualState
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * App-side Triggers mapper. Subscribes to [TriggersViewModel.uiState], folds it
 * into the catalog [TriggersViewState], and routes between the list, the
 * full-screen editor (`TriggerEditorContent`) and the delete dialog
 * (`TriggerDeleteDialogContent`).
 *
 * The editor replaces the list while open (the catalog editor is a full-screen
 * surface), so it is rendered instead of the list rather than over it.
 *
 * @param modifier optional layout modifier.
 * @param viewModel the screen ViewModel.
 * @param onBack back-navigation callback.
 */
@Composable
fun TriggersScreen(
    modifier: Modifier = Modifier,
    viewModel: TriggersViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Built here, where the snackbar host lives, and passed down: the export
    // reports every outcome on that host, including the ones nobody is looking at
    // (a save into a folder the user then leaves).
    val journalExport = rememberJournalExportHandlers(viewModel.journalExport, snackbarHostState)

    // Reading a connected Wi-Fi SSID needs fine location; request it the first time
    // the user scopes a trigger to specific networks. The SSID is stored regardless
    // of the grant — an ungranted trigger simply never matches until permission is
    // given (fail-safe) — so the result callback is intentionally a no-op.
    val locationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val addSsid: (String) -> Unit = { ssid ->
        viewModel.onSsidAdd(ssid)
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // Surface a one-shot mutation error (save / toggle / delete) as a snackbar
    // over whichever surface is showing, then clear it.
    val transientMessage = uiState.transientError?.asString()
    LaunchedEffect(uiState.transientError) {
        val message = transientMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearTransientError()
    }

    // System back walks the inline stack editor → detail → list before exiting.
    BackHandler(enabled = uiState.editor != null || uiState.detailTriggerId != null) {
        when {
            uiState.editor != null -> viewModel.closeEditor()
            uiState.detailTriggerId != null -> viewModel.closeDetail()
        }
    }

    // A slowly ticking clock so staleness badges and relative journal labels
    // advance while the screen stays open, even when no trigger/journal flow emits
    // (a trigger can silently cross its stale threshold otherwise).
    val nowMillis = rememberTickingNowMillis()

    Box(modifier = modifier.fillMaxSize()) {
        val editor = uiState.editor
        val detailTrigger = uiState.detailTrigger
        // One host, handed to whichever surface is up: each has its own Scaffold,
        // and the list's lifts it above the new-trigger button.
        val snackbarHost: @Composable () -> Unit = { KnotworkSnackbarHost(hostState = snackbarHostState) }
        when {
            editor != null -> TriggerEditorContent(
                state = uiState.toEditorUi(editor),
                strings = triggerEditorStrings(),
                callbacks = TriggerEditorCallbacks(
                    onClose = viewModel::closeEditor,
                    onSave = viewModel::saveEditor,
                    onNameChange = viewModel::onNameChange,
                    onTypeChange = viewModel::onTypeChange,
                    onIntervalPreset = viewModel::onIntervalPreset,
                    onIntervalCustomToggle = viewModel::onIntervalCustomToggle,
                    onIntervalCustomAmountChange = viewModel::onIntervalCustomAmountChange,
                    onIntervalUnitChange = viewModel::onIntervalUnitChange,
                    onDailyTimeChange = viewModel::onDailyTimeChange,
                    onWifiOnlyToggle = viewModel::onWifiOnlyToggle,
                    onSsidAdd = addSsid,
                    onSsidRemove = viewModel::onSsidRemove,
                    onPipelineSelect = viewModel::onPipelineSelect,
                    onPromptChange = viewModel::onPromptChange,
                    onEnabledToggle = viewModel::onEnabledToggle,
                    onDelete = { editor.id?.let(viewModel::requestDelete) },
                ),
                snackbarHost = snackbarHost,
            )
            detailTrigger != null -> TriggerDetailSurface(
                snackbarHost = snackbarHost,
                uiState = uiState,
                trigger = detailTrigger,
                viewModel = viewModel,
                nowMillis = nowMillis,
            )
            else -> TriggersList(
                snackbarHost = snackbarHost,
                uiState = uiState,
                viewModel = viewModel,
                onBack = onBack,
                nowMillis = nowMillis,
                journalExport = journalExport,
            )
        }

        // Shared delete dialog — surfaces over whichever surface (list or detail)
        // requested the delete.
        val deleteTarget = uiState.deleteTarget
        if (deleteTarget != null) {
            Dialog(onDismissRequest = viewModel::cancelDelete) {
                TriggerDeleteDialogContent(
                    state = TriggerDeleteUi(triggerName = deleteTarget.name),
                    strings = triggerDeleteStrings(),
                    onConfirm = viewModel::confirmDelete,
                    onCancel = viewModel::cancelDelete,
                )
            }
        }
    }
}

/** The list surface + its delete dialog, shown when the editor is closed. */
@Composable
private fun TriggersList(
    snackbarHost: @Composable () -> Unit,
    uiState: TriggersUiState,
    viewModel: TriggersViewModel,
    onBack: () -> Unit,
    nowMillis: Long,
    journalExport: JournalExportActionHandlers,
) {
    val context = LocalContext.current
    val resolvedError = uiState.loadError?.asString()
    val fallbackError = stringResource(R.string.triggers_error_body)
    TriggersContent(
        state = uiState.toListViewState(
            subtitle = pluralStringResource(
                R.plurals.triggers_subtitle,
                uiState.triggers.size,
                uiState.triggers.size,
                uiState.activeCount,
            ),
            conditionLabels = uiState.conditionLabels(),
            resolvedError = resolvedError,
            fallbackError = fallbackError,
            nowMillis = nowMillis,
        ),
        strings = triggersStrings(),
        callbacks = TriggersCallbacks(
            onBack = onBack,
            onNewTrigger = viewModel::openNewTrigger,
            onRowClick = viewModel::onRowClick,
            onRowToggle = viewModel::toggleEnabled,
            onRowMenuOpen = viewModel::openRowMenu,
            onRowMenuDismiss = viewModel::dismissRowMenu,
            onEditTrigger = viewModel::openEditTrigger,
            onDeleteTrigger = viewModel::requestDelete,
            onRetry = viewModel::retry,
            onShareJournal = journalExport.onShare,
            onSaveJournal = journalExport.onSave,
            onOpenDocumentation = { openDocumentation(context, DocumentationLinks.ID_TRIGGERS) },
        ),
        snackbarHost = snackbarHost,
    )
}

/**
 * Pre-resolved human-readable condition lines keyed by trigger id. The pure
 * structured-label computation is memoised on the trigger list so only string
 * resolution (cheap resource lookups) runs on an unrelated recomposition.
 */
@Composable
private fun TriggersUiState.conditionLabels(): Map<String, String> {
    val structured = remember(triggers) {
        triggers.map { it.id to TriggerConditionFormatter.toLabel(it.condition) }
    }
    return structured.associate { (id, label) -> id to conditionText(label) }
}

/** Resolves a structured [TriggerConditionLabel] to its localized display string. */
@Composable
private fun conditionText(label: TriggerConditionLabel): String = when (label) {
    is TriggerConditionLabel.IntervalMinutes ->
        pluralStringResource(R.plurals.triggers_interval_minutes, label.minutes, label.minutes)
    is TriggerConditionLabel.IntervalHours ->
        pluralStringResource(R.plurals.triggers_interval_hours, label.hours, label.hours)
    is TriggerConditionLabel.Daily ->
        stringResource(R.string.triggers_condition_daily, "%02d:%02d".format(label.hour, label.minute))
    TriggerConditionLabel.Charging -> stringResource(R.string.triggers_condition_charging)
    TriggerConditionLabel.NetworkAny -> stringResource(R.string.triggers_condition_network_any)
    TriggerConditionLabel.NetworkWifi -> stringResource(R.string.triggers_condition_network_wifi)
    is TriggerConditionLabel.NetworkNamed ->
        stringResource(R.string.triggers_condition_network_named, label.ssids.joinToString(", "))
}

/** Maps the app state onto the catalog list view state. */
private fun TriggersUiState.toListViewState(
    subtitle: String,
    conditionLabels: Map<String, String>,
    resolvedError: String?,
    fallbackError: String,
    nowMillis: Long,
): TriggersViewState {
    val pipelineNames = pipelines.associate { it.id to it.name }
    val rows = triggers.map { trigger ->
        TriggerRowUi(
            id = trigger.id,
            name = trigger.name,
            conditionLabel = conditionLabels[trigger.id].orEmpty(),
            conditionType = trigger.condition.toConditionType(),
            pipelineName = trigger.pipelineId?.let { pipelineNames[it] },
            enabled = trigger.enabled,
            health = triggerHealthEvaluator
                .evaluate(trigger, healthInputs[trigger.id], nowMillis)
                ?.toHealthUi(),
        )
    }
    val visualState = when {
        loadError != null && triggers.isEmpty() -> TriggersVisualState.Error
        isLoading -> TriggersVisualState.Loading
        triggers.isEmpty() -> TriggersVisualState.Empty
        else -> TriggersVisualState.Default
    }
    val errorText = if (visualState == TriggersVisualState.Error) {
        resolvedError?.takeIf { it.isNotBlank() } ?: fallbackError
    } else {
        null
    }
    return TriggersViewState(
        visualState = visualState,
        rows = rows,
        openMenuRowId = openMenuTriggerId,
        subtitle = if (visualState == TriggersVisualState.Default) subtitle else "",
        errorMessage = errorText,
    )
}

/**
 * Maps the editor draft onto the catalog editor view state. The pipeline option
 * list is memoised on [TriggersUiState.pipelines] so editing the draft (each
 * keystroke) does not reallocate it.
 */
@Composable
private fun TriggersUiState.toEditorUi(draft: TriggerEditorDraft): TriggerEditorUi {
    val options = remember(pipelines) { pipelines.map { TriggerPipelineOptionUi(id = it.id, name = it.name) } }
    val pipelineName = draft.pipelineId?.let { id -> pipelines.firstOrNull { it.id == id }?.name }
    return TriggerEditorUi(
        id = draft.id,
        name = draft.name,
        type = draft.type,
        intervalCustom = draft.intervalCustom,
        intervalMinutes = draft.intervalPresetMinutes,
        intervalCustomAmount = draft.intervalCustomAmount,
        intervalCustomUnitHours = draft.intervalCustomUnitHours,
        intervalError = draft.intervalError,
        dailyHour = draft.dailyHour,
        dailyMinute = draft.dailyMinute,
        wifiOnly = draft.wifiOnly,
        ssids = draft.ssids,
        pipelines = options,
        pipelineName = pipelineName,
        prompt = draft.prompt,
        enabled = draft.enabled,
        // Show the field-level error only when editing an existing trigger whose
        // name the user has cleared — a fresh draft starts blank and shouldn't nag.
        nameError = draft.id != null && draft.name.isBlank(),
        canSave = draft.canSave,
    )
}

/** Maps a domain condition onto the catalog condition-type enum (for the row glyph). */
private fun TriggerCondition.toConditionType(): TriggerConditionType = when (this) {
    is TriggerCondition.IntervalSchedule -> TriggerConditionType.Interval
    is TriggerCondition.DailySchedule -> TriggerConditionType.Daily
    TriggerCondition.Charging -> TriggerConditionType.Charging
    is TriggerCondition.NetworkConnected -> TriggerConditionType.Network
}

// ── Detail surface ──────────────────────────────────────────────────────────

/** Stateless helpers reused across recompositions; both are pure and dependency-free. */
private val triggerHealthEvaluator = TriggerHealthEvaluator()
private val triggerJournalGrouper = TriggerJournalGrouper()

/** Coarse tick period for [rememberTickingNowMillis] — the thresholds it feeds are minute-scale. */
private const val NOW_TICK_MILLIS = 30_000L

/**
 * A composition-scoped clock that re-emits every [NOW_TICK_MILLIS] so time-derived
 * UI — health staleness and relative journal timestamps — advances while the
 * screen stays open, instead of freezing until an unrelated state change forces a
 * recomposition. The tick is coarse, so its cost is negligible; it stops
 * automatically when the composable leaves the composition.
 */
@Composable
private fun rememberTickingNowMillis(): Long {
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(NOW_TICK_MILLIS)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * The trigger-detail surface: identity header + evaluation journal, reached by
 * tapping a list row. Edit / Delete / toggle reuse the list ViewModel's mutation
 * paths so there is no duplicated lifecycle logic.
 */
@Composable
private fun TriggerDetailSurface(
    snackbarHost: @Composable () -> Unit,
    uiState: TriggersUiState,
    trigger: Trigger,
    viewModel: TriggersViewModel,
    nowMillis: Long,
) {
    TriggerDetailContent(
        state = uiState.toDetailViewState(trigger = trigger, nowMillis = nowMillis),
        strings = triggerDetailStrings(),
        callbacks = TriggerDetailCallbacks(
            onBack = viewModel::closeDetail,
            onEdit = viewModel::editFromDetail,
            onDelete = { viewModel.requestDelete(trigger.id) },
            onToggleEnabled = { viewModel.toggleEnabled(trigger.id) },
            onBindPipeline = viewModel::editFromDetail,
        ),
        snackbarHost = snackbarHost,
    )
}

/** Maps the app state + the open [trigger] onto the catalog detail view state. */
@Composable
private fun TriggersUiState.toDetailViewState(trigger: Trigger, nowMillis: Long): TriggerDetailViewState {
    val zone = remember { ZoneId.systemDefault() }
    // Build the day formatter from the current config locale so headers follow a
    // runtime language change (a static formatter would freeze the load-time locale).
    val locale = LocalConfiguration.current.locales[0]
    val dayFormatter = remember(locale) { DateTimeFormatter.ofPattern("EEE d MMM", locale) }
    val conditionLabel = conditionText(remember(trigger) { TriggerConditionFormatter.toLabel(trigger.condition) })
    val pipelineName = trigger.pipelineId?.let { id -> pipelines.firstOrNull { it.id == id }?.name }

    val health = triggerHealthEvaluator.evaluate(trigger, healthInputs[trigger.id], nowMillis)
    val stale = health == TriggerHealthStatus.STALE
    // Name the last-checked moment; qualify it with the date when it is not today
    // so a multi-day-overdue trigger doesn't read as if it was checked this morning.
    val staleSince = healthInputs[trigger.id]?.latestEvaluatedAt
        ?.let { staleSinceLabel(it, nowMillis, zone, dayFormatter) }

    val journal = detailJournal
    val journalState = when {
        journal == null -> TriggerJournalVisualState.Loading
        journal.isEmpty() -> TriggerJournalVisualState.Empty
        else -> TriggerJournalVisualState.Populated
    }
    // Resolve the format-dependent labels once in composable context; the per-entry
    // projection below is a plain function (no @Composable calls inside `map`).
    val labels = JournalLabels(
        justNow = stringResource(R.string.triggers_journal_time_just_now),
        minutesAgoFormat = stringResource(R.string.triggers_journal_time_minutes_ago),
        today = stringResource(R.string.triggers_journal_day_today),
        yesterday = stringResource(R.string.triggers_journal_day_yesterday),
    )
    val dayGroups = if (journalState == TriggerJournalVisualState.Populated) {
        triggerJournalGrouper.group(journal.orEmpty(), nowMillis, zone).toDayGroupsUi(labels, dayFormatter)
    } else {
        emptyList()
    }

    return TriggerDetailViewState(
        name = trigger.name,
        conditionType = trigger.condition.toConditionType(),
        conditionLabel = conditionLabel,
        pipelineName = pipelineName,
        enabled = trigger.enabled,
        showStaleBanner = stale,
        staleSinceLabel = if (stale) staleSince else null,
        journalState = journalState,
        dayGroups = dayGroups,
    )
}

/**
 * The format-dependent journal labels, pre-resolved once in composable context so
 * the per-entry projection ([toDayGroupsUi]) can stay a plain function.
 */
private data class JournalLabels(
    val justNow: String,
    val minutesAgoFormat: String,
    val today: String,
    val yesterday: String,
)

/** Resolves the grouped journal view into localized catalog day groups. */
private fun TriggerJournalView.toDayGroupsUi(
    labels: JournalLabels,
    dayFormatter: DateTimeFormatter,
): List<TriggerJournalDayGroupUi> = dayGroups.map { group ->
    TriggerJournalDayGroupUi(
        headerLabel = group.header.label(labels, dayFormatter),
        entries = group.entries.map { entry ->
            val verdict = entry.evaluation.verdict
            TriggerJournalEntryUi(
                id = entry.evaluation.id,
                source = entry.evaluation.source.toSourceUi(),
                verdict = verdict.toVerdictUi(),
                outcome = if (verdict is TriggerEvaluationVerdict.Fired) {
                    entry.evaluation.outcome.toOutcomeUi()
                } else {
                    null
                },
                outcomeError = (entry.evaluation.outcome as? TriggerRunOutcome.Failure)?.error,
                skipReason = (verdict as? TriggerEvaluationVerdict.Skipped)?.reason?.toSkipReasonUi(),
                skipMomentLabel = clockLabel(entry.momentTime),
                timestampLabel = entry.timestamp.label(labels),
                hitl = entry.evaluation.hitl?.lastResolution?.toHitlUi(),
                hitlParked = entry.evaluation.hitl?.parked == true,
            )
        },
    )
}

// ── Domain → catalog vocabulary maps ────────────────────────────────────────

private fun TriggerHealthStatus.toHealthUi(): TriggerHealthUi = when (this) {
    TriggerHealthStatus.HEALTHY -> TriggerHealthUi.Healthy
    TriggerHealthStatus.STALE -> TriggerHealthUi.Overdue
    TriggerHealthStatus.ERRORED -> TriggerHealthUi.LastRunFailed
}

private fun TriggerEvaluationSource.toSourceUi(): TriggerJournalSourceUi = when (this) {
    TriggerEvaluationSource.POLL -> TriggerJournalSourceUi.Poll
    TriggerEvaluationSource.EVENT -> TriggerJournalSourceUi.Event
    TriggerEvaluationSource.CHARGING_SWEEP -> TriggerJournalSourceUi.Charging
}

private fun TriggerEvaluationVerdict.toVerdictUi(): TriggerJournalVerdictUi = when (this) {
    TriggerEvaluationVerdict.Fired -> TriggerJournalVerdictUi.Fired
    TriggerEvaluationVerdict.ReArmed -> TriggerJournalVerdictUi.ReArmed
    is TriggerEvaluationVerdict.Skipped -> TriggerJournalVerdictUi.Skipped
}

private fun TriggerSkipReason.toSkipReasonUi(): TriggerJournalSkipReasonUi = when (this) {
    TriggerSkipReason.DISABLED -> TriggerJournalSkipReasonUi.Disabled
    TriggerSkipReason.UNBOUND -> TriggerJournalSkipReasonUi.Unbound
    TriggerSkipReason.CONDITION_NOT_MET -> TriggerJournalSkipReasonUi.ConditionNotMet
    TriggerSkipReason.ALREADY_FIRED -> TriggerJournalSkipReasonUi.AlreadyFired
}

/** Maps the settled (or absent → pending) run outcome to the catalog enum. */
private fun TriggerRunOutcome?.toOutcomeUi(): TriggerJournalOutcomeUi = when (this) {
    null -> TriggerJournalOutcomeUi.Pending
    TriggerRunOutcome.Success -> TriggerJournalOutcomeUi.Success
    is TriggerRunOutcome.Failure -> TriggerJournalOutcomeUi.Failure
    TriggerRunOutcome.CancelledBySystem -> TriggerJournalOutcomeUi.CancelledBySystem
    TriggerRunOutcome.Cancelled -> TriggerJournalOutcomeUi.Cancelled
    TriggerRunOutcome.HitlTimeout -> TriggerJournalOutcomeUi.HitlTimeout
    TriggerRunOutcome.StoppedByCeiling -> TriggerJournalOutcomeUi.StoppedByCeiling
}

/** Maps the latest HITL gate's resolution to the catalog enum. */
private fun TriggerHitlResolution.toHitlUi(): TriggerJournalHitlUi = when (this) {
    TriggerHitlResolution.PENDING -> TriggerJournalHitlUi.Waiting
    TriggerHitlResolution.APPROVED -> TriggerJournalHitlUi.Approved
    TriggerHitlResolution.DENIED -> TriggerJournalHitlUi.Denied
    TriggerHitlResolution.ANSWERED -> TriggerJournalHitlUi.Answered
    TriggerHitlResolution.TIMED_OUT -> TriggerJournalHitlUi.TimedOut
    TriggerHitlResolution.ABANDONED -> TriggerJournalHitlUi.Abandoned
}

// ── Timestamp / day-header resolution ───────────────────────────────────────

/** Resolves a structural day header to its localized label. */
private fun JournalDayHeader.label(labels: JournalLabels, dayFormatter: DateTimeFormatter): String = when (this) {
    JournalDayHeader.Today -> labels.today
    JournalDayHeader.Yesterday -> labels.yesterday
    is JournalDayHeader.Date -> date.format(dayFormatter)
}

/** Resolves a structural timestamp to its localized label. */
private fun JournalTimestamp.label(labels: JournalLabels): String = when (this) {
    JournalTimestamp.JustNow -> labels.justNow
    is JournalTimestamp.MinutesAgo -> labels.minutesAgoFormat.format(minutes)
    is JournalTimestamp.AbsoluteTime -> clockLabel(time)
}

/** Formats a [ClockTime] as a 24-hour `HH:mm` label. */
private fun clockLabel(time: ClockTime): String = "%02d:%02d".format(time.hour, time.minute)

/**
 * Formats the stale banner's "last checked" moment. A same-day moment reads as a
 * bare `HH:mm` ("07:15"); an earlier day is qualified with its date
 * ("Mon 14 Jul, 07:15") so a multi-day-overdue trigger isn't misread as checked
 * earlier today.
 */
private fun staleSinceLabel(epochMillis: Long, nowMillis: Long, zone: ZoneId, dayFormatter: DateTimeFormatter): String {
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val time = "%02d:%02d".format(moment.hour, moment.minute)
    return if (moment.toLocalDate() == today) time else "${moment.toLocalDate().format(dayFormatter)}, $time"
}

@Composable
private fun triggerDetailStrings(): TriggerDetailStrings = TriggerDetailStrings(
    backCd = stringResource(R.string.triggers_back_cd),
    subtitle = stringResource(R.string.triggers_detail_subtitle),
    whenLabel = stringResource(R.string.triggers_detail_when),
    runsLabel = stringResource(R.string.triggers_detail_runs),
    stateLabel = stringResource(R.string.triggers_detail_state),
    stateEnabled = stringResource(R.string.triggers_detail_state_enabled),
    stateDisabled = stringResource(R.string.triggers_detail_state_disabled),
    unboundHint = stringResource(R.string.triggers_unbound_hint),
    unboundAction = stringResource(R.string.triggers_unbound_action),
    edit = stringResource(R.string.triggers_detail_edit),
    delete = stringResource(R.string.triggers_detail_delete),
    staleBannerTitleFormat = stringResource(R.string.triggers_detail_stale_title_format),
    staleBannerTitleNoTime = stringResource(R.string.triggers_detail_stale_title_no_time),
    staleBannerBody = stringResource(R.string.triggers_detail_stale_body),
    journalSectionLabel = stringResource(R.string.triggers_journal_section),
    journalWindowLabel = stringResource(R.string.triggers_journal_window),
    retentionFooter = stringResource(R.string.triggers_journal_retention),
    emptyTitle = stringResource(R.string.triggers_journal_empty_title),
    emptyBody = stringResource(R.string.triggers_journal_empty_body),
    sourcePoll = stringResource(R.string.triggers_journal_source_poll),
    sourceEvent = stringResource(R.string.triggers_journal_source_event),
    sourceCharging = stringResource(R.string.triggers_journal_source_charging),
    verdictFired = stringResource(R.string.triggers_journal_verdict_fired),
    verdictReArmed = stringResource(R.string.triggers_journal_verdict_rearmed),
    verdictSkipped = stringResource(R.string.triggers_journal_verdict_skipped),
    reArmNote = stringResource(R.string.triggers_journal_rearm_note),
    skipDisabled = stringResource(R.string.triggers_journal_skip_disabled),
    skipUnbound = stringResource(R.string.triggers_journal_skip_unbound),
    skipConditionNotMetFormat = stringResource(R.string.triggers_journal_skip_condition_not_met_format),
    skipAlreadyFired = stringResource(R.string.triggers_journal_skip_already_fired),
    outcomePending = stringResource(R.string.triggers_journal_outcome_pending),
    outcomeSuccess = stringResource(R.string.triggers_journal_outcome_success),
    outcomeFailure = stringResource(R.string.triggers_journal_outcome_failure),
    outcomeCancelledBySystem = stringResource(R.string.triggers_journal_outcome_cancelled_by_system),
    outcomeCancelled = stringResource(R.string.triggers_journal_outcome_cancelled),
    outcomeHitlTimeout = stringResource(R.string.triggers_journal_outcome_hitl_timeout),
    outcomeStoppedByCeiling = stringResource(R.string.triggers_journal_outcome_stopped_by_ceiling),
    hitlWaiting = stringResource(R.string.triggers_journal_hitl_waiting),
    hitlApproved = stringResource(R.string.triggers_journal_hitl_approved),
    hitlDenied = stringResource(R.string.triggers_journal_hitl_denied),
    hitlAnswered = stringResource(R.string.triggers_journal_hitl_answered),
    hitlTimedOut = stringResource(R.string.triggers_journal_hitl_timed_out),
    hitlAbandoned = stringResource(R.string.triggers_journal_hitl_abandoned),
    hitlParkedPending = stringResource(R.string.triggers_journal_hitl_parked_pending),
    hitlParkedSettled = stringResource(R.string.triggers_journal_hitl_parked_settled),
)

@Composable
private fun triggersStrings(): TriggersStrings = TriggersStrings(
    title = stringResource(R.string.triggers_screen_title),
    backCd = stringResource(R.string.triggers_back_cd),
    moreCd = stringResource(R.string.triggers_more_cd),
    fab = stringResource(R.string.triggers_fab),
    unboundHint = stringResource(R.string.triggers_unbound_hint),
    bind = stringResource(R.string.triggers_bind),
    bindCd = stringResource(R.string.triggers_bind_cd),
    menuEdit = stringResource(R.string.triggers_menu_edit),
    menuDelete = stringResource(R.string.triggers_menu_delete),
    emptyTitle = stringResource(R.string.triggers_empty_title),
    emptyBody = stringResource(R.string.triggers_empty_body),
    emptyCta = stringResource(R.string.triggers_empty_cta),
    emptyStepTrigger = stringResource(R.string.triggers_empty_step_trigger),
    emptyStepRun = stringResource(R.string.triggers_empty_step_run),
    emptyStepResult = stringResource(R.string.triggers_empty_step_result),
    errorTitle = stringResource(R.string.triggers_error_title),
    errorRetry = stringResource(R.string.common_retry),
    healthHealthy = stringResource(R.string.triggers_health_healthy),
    healthOverdue = stringResource(R.string.triggers_health_overdue),
    healthLastRunFailed = stringResource(R.string.triggers_health_last_run_failed),
    exportShareCd = stringResource(R.string.triggers_export_share_cd),
    exportSaveCd = stringResource(R.string.triggers_export_save_cd),
    documentationCd = stringResource(R.string.triggers_documentation_cd),
)

@Composable
private fun triggerEditorStrings(): TriggerEditorStrings = TriggerEditorStrings(
    titleNew = stringResource(R.string.triggers_editor_title_new),
    titleEdit = stringResource(R.string.triggers_editor_title_edit),
    subtitleNew = stringResource(R.string.triggers_editor_subtitle_new),
    subtitleEdit = stringResource(R.string.triggers_editor_subtitle_edit),
    closeCd = stringResource(R.string.triggers_editor_close_cd),
    save = stringResource(R.string.common_save),
    required = stringResource(R.string.triggers_editor_required),
    kicker = stringResource(R.string.triggers_editor_kicker),
    kickerBody = stringResource(R.string.triggers_editor_kicker_body),
    nameLabel = stringResource(R.string.triggers_editor_name_label),
    namePlaceholder = stringResource(R.string.triggers_editor_name_placeholder),
    nameErrorText = stringResource(R.string.triggers_editor_name_error),
    conditionLabel = stringResource(R.string.triggers_editor_condition_label),
    typeInterval = stringResource(R.string.triggers_type_interval),
    typeIntervalSub = stringResource(R.string.triggers_type_interval_sub),
    typeDaily = stringResource(R.string.triggers_type_daily),
    typeDailySub = stringResource(R.string.triggers_type_daily_sub),
    typeCharging = stringResource(R.string.triggers_type_charging),
    typeChargingSub = stringResource(R.string.triggers_type_charging_sub),
    typeNetwork = stringResource(R.string.triggers_type_network),
    typeNetworkSub = stringResource(R.string.triggers_type_network_sub),
    intervalCustom = stringResource(R.string.triggers_interval_custom),
    intervalErrorText = stringResource(R.string.triggers_interval_error),
    intervalFloorNote = stringResource(R.string.triggers_interval_floor_note),
    unitMinutes = stringResource(R.string.triggers_unit_minutes),
    unitHours = stringResource(R.string.triggers_unit_hours),
    dailyFieldLabel = stringResource(R.string.triggers_daily_field_label),
    chargingTitle = stringResource(R.string.triggers_charging_title),
    chargingBody = stringResource(R.string.triggers_charging_body),
    wifiOnlyLabel = stringResource(R.string.triggers_wifi_only_label),
    wifiOnlyDesc = stringResource(R.string.triggers_wifi_only_desc),
    ssidSectionLabel = stringResource(R.string.triggers_ssid_section_label),
    ssidSectionDesc = stringResource(R.string.triggers_ssid_section_desc),
    ssidPlaceholder = stringResource(R.string.triggers_ssid_placeholder),
    ssidAdd = stringResource(R.string.triggers_ssid_add),
    pipelineLabel = stringResource(R.string.triggers_pipeline_label),
    pipelineNone = stringResource(R.string.triggers_pipeline_none),
    pipelineNoneSub = stringResource(R.string.triggers_pipeline_none_sub),
    promptLabel = stringResource(R.string.triggers_prompt_label),
    promptPlaceholder = stringResource(R.string.triggers_prompt_placeholder),
    enabledLabel = stringResource(R.string.triggers_enabled_label),
    enabledDesc = stringResource(R.string.triggers_enabled_desc),
    timeOk = stringResource(R.string.common_ok),
    cancel = stringResource(R.string.common_cancel),
    deleteTrigger = stringResource(R.string.triggers_editor_delete),
)

@Composable
private fun triggerDeleteStrings(): TriggerDeleteStrings = TriggerDeleteStrings(
    title = stringResource(R.string.triggers_delete_title),
    bodyFormat = stringResource(R.string.triggers_delete_body_format),
    cancel = stringResource(R.string.common_cancel),
    delete = stringResource(R.string.triggers_delete_confirm),
)
