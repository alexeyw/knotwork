package app.knotwork.android.presentation.ui.automation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.knotwork.android.R
import app.knotwork.android.domain.constants.ExternalAutomationContract
import app.knotwork.android.domain.models.ExternalAutomationJournalEntry
import app.knotwork.android.domain.models.ExternalAutomationRejectionReason
import app.knotwork.android.domain.models.ExternalAutomationStatus
import app.knotwork.android.domain.models.ExternalAutomationTarget
import app.knotwork.android.domain.usecases.ClockTime
import app.knotwork.android.domain.usecases.JournalDayGrouper
import app.knotwork.android.domain.usecases.JournalDayHeader
import app.knotwork.android.domain.usecases.JournalDaySection
import app.knotwork.android.domain.usecases.JournalTimestamp
import app.knotwork.android.domain.usecases.automation.CleanupExternalAutomationJournalUseCase
import app.knotwork.android.presentation.ui.common.rememberJournalExportHandlers
import app.knotwork.android.presentation.ui.common.writePlainClipboardText
import app.knotwork.design.screens.automation.ExternalAutomationJournalCallbacks
import app.knotwork.design.screens.automation.ExternalAutomationJournalContent
import app.knotwork.design.screens.automation.ExternalAutomationJournalStrings
import app.knotwork.design.screens.automation.ExternalAutomationJournalViewState
import app.knotwork.design.screens.automation.ExternalCallKeyUi
import app.knotwork.design.screens.automation.ExternalJournalVisualState
import app.knotwork.design.screens.automation.ExternalRequestDayGroupUi
import app.knotwork.design.screens.automation.ExternalRequestEntryUi
import app.knotwork.design.screens.automation.ExternalRequestReasonUi
import app.knotwork.design.screens.automation.ExternalRequestSenderKindUi
import app.knotwork.design.screens.automation.ExternalRequestStatusUi
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Coarse tick period for the relative journal timestamps; their thresholds are minute-scale. */
private const val NOW_TICK_MILLIS = 30_000L

/**
 * Caller-supplied strings are rendered at most this long.
 *
 * Every one of them — the request id, the target name, the package to answer to —
 * arrives from another process and is journalled verbatim, so their length is set
 * by whoever wrote the calling profile rather than by this app. Ellipsis in the
 * layout would already stop them overflowing; clipping them here stops a
 * pathological value being carried through the row's string building at all.
 */
private const val CALLER_TEXT_MAX_CHARS = 40

/** How much of a target pipeline id is shown before it is elided. */
private const val TARGET_ID_PREFIX_CHARS = 8

/** The journal's retention window in days, as the copy needs it (an `Int` for the plural selector). */
private val RETENTION_DAYS = CleanupExternalAutomationJournalUseCase.RETENTION_WINDOW_DAYS.toInt()

/** Groups the journal by device-local day; the arithmetic is shared with the trigger journal. */
private val journalGrouper = JournalDayGrouper<ExternalAutomationJournalEntry> { it.receivedAt }

/**
 * App-side external-automation journal mapper: subscribes to
 * [ExternalAutomationJournalViewModel.uiState], folds it into the catalog view
 * state, and renders the stateless journal surface.
 *
 * @param onBack Up navigation, back to Settings → Background.
 * @param viewModel The screen ViewModel (Hilt-provided).
 */
@Composable
fun ExternalAutomationJournalScreen(
    onBack: () -> Unit,
    viewModel: ExternalAutomationJournalViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val nowMillis = rememberTickingNowMillis()
    val callKeys = callKeys()
    val clipLabel = stringResource(R.string.external_automation_call_clip_label)

    // This screen had no snackbar host until the export arrived, because until
    // then nothing on it could succeed or fail — it only rendered. An export can
    // do both, silently, so the host is added rather than the outcomes dropped.
    val snackbarHostState = remember { SnackbarHostState() }
    val journalExport = rememberJournalExportHandlers(viewModel.journalExport, snackbarHostState)

    Box(modifier = Modifier.fillMaxSize()) {
        ExternalAutomationJournalContent(
            state = uiState.toViewState(nowMillis = nowMillis, callKeys = callKeys),
            strings = journalStrings(),
            callbacks = ExternalAutomationJournalCallbacks(
                onBack = onBack,
                onCopyCallDetails = {
                    writePlainClipboardText(context, clipLabel, callDetailsText(callKeys))
                },
                onShareJournal = journalExport.onShare,
                onSaveJournal = journalExport.onSave,
            ),
        )
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * A composition-scoped clock that re-emits every [NOW_TICK_MILLIS] so the
 * relative timestamps advance while the screen stays open, instead of freezing
 * until an unrelated state change forces a recomposition. It stops automatically
 * when the composable leaves the composition.
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

/** Maps the app state onto the catalog view state. */
@Composable
private fun ExternalAutomationJournalUiState.toViewState(
    nowMillis: Long,
    callKeys: List<ExternalCallKeyUi>,
): ExternalAutomationJournalViewState {
    val zone = remember { ZoneId.systemDefault() }
    // Build the day formatter from the current config locale so headers follow a
    // runtime language change (a static formatter would freeze the load-time locale).
    val locale = LocalConfiguration.current.locales[0]
    val dayFormatter = remember(locale) { DateTimeFormatter.ofPattern("EEE d MMM", locale) }

    val journalState = when {
        entries == null -> ExternalJournalVisualState.Loading
        entries.isEmpty() -> ExternalJournalVisualState.Empty
        else -> ExternalJournalVisualState.Populated
    }
    // Resolve the format-dependent labels once in composable context so the
    // per-entry projection below can stay a plain function.
    val labels = JournalLabels(
        justNow = stringResource(R.string.journal_time_just_now),
        minutesAgoFormat = stringResource(R.string.journal_time_minutes_ago),
        today = stringResource(R.string.journal_day_today),
        yesterday = stringResource(R.string.journal_day_yesterday),
        targetByIdFormat = stringResource(R.string.external_automation_target_by_id),
    )
    val dayGroups = if (journalState == ExternalJournalVisualState.Populated) {
        journalGrouper.group(entries.orEmpty(), nowMillis, zone).toDayGroupsUi(labels, dayFormatter)
    } else {
        emptyList()
    }

    return ExternalAutomationJournalViewState(
        contractEnabled = contractEnabled,
        boundPipelineName = boundPipelineName,
        journalState = journalState,
        dayGroups = dayGroups,
        callAction = ExternalAutomationContract.ACTION_RUN_PIPELINE,
        callKeys = callKeys,
    )
}

/**
 * The format-dependent labels, pre-resolved once in composable context so
 * [toDayGroupsUi] can stay a plain function.
 */
private data class JournalLabels(
    val justNow: String,
    val minutesAgoFormat: String,
    val today: String,
    val yesterday: String,
    val targetByIdFormat: String,
)

/** Resolves the grouped journal into localized catalog day groups. */
private fun List<JournalDaySection<ExternalAutomationJournalEntry>>.toDayGroupsUi(
    labels: JournalLabels,
    dayFormatter: DateTimeFormatter,
): List<ExternalRequestDayGroupUi> = map { section ->
    ExternalRequestDayGroupUi(
        headerLabel = section.header.label(labels, dayFormatter),
        entries = section.entries.map { entry ->
            val row = entry.item
            ExternalRequestEntryUi(
                id = row.id,
                status = row.status.toStatusUi(),
                reason = row.status.reason()?.toReasonUi(),
                targetLabel = row.target?.label(labels),
                actionLabel = row.action.abbreviate(),
                // The contract's own action is on every ordinary row and says
                // nothing; an unrecognised one is the whole explanation of the
                // refusal, so only that case is worth the line.
                showAction = row.action != ExternalAutomationContract.ACTION_RUN_PIPELINE,
                senderLabel = (row.attestedSenderPackage ?: row.declaredReturnPackage)?.abbreviate(),
                senderKind = if (row.attestedSenderPackage != null) {
                    ExternalRequestSenderKindUi.Attested
                } else {
                    ExternalRequestSenderKindUi.Claimed
                },
                requestIdLabel = row.requestId.takeIf { it.isNotBlank() }?.abbreviate(),
                timestampLabel = entry.timestamp.label(labels),
                repeatCount = row.repeatCount,
            )
        },
    )
}

// ── Domain → catalog vocabulary maps ────────────────────────────────────────

private fun ExternalAutomationStatus.toStatusUi(): ExternalRequestStatusUi = when (this) {
    ExternalAutomationStatus.Accepted -> ExternalRequestStatusUi.Accepted
    ExternalAutomationStatus.Completed -> ExternalRequestStatusUi.Completed
    ExternalAutomationStatus.Failed -> ExternalRequestStatusUi.Failed
    is ExternalAutomationStatus.Rejected -> ExternalRequestStatusUi.Rejected
    is ExternalAutomationStatus.Blocked -> ExternalRequestStatusUi.Blocked
}

/** The refusal cause carried by the two refusal statuses; `null` for the rest. */
private fun ExternalAutomationStatus.reason(): ExternalAutomationRejectionReason? = when (this) {
    is ExternalAutomationStatus.Rejected -> reason
    is ExternalAutomationStatus.Blocked -> reason
    ExternalAutomationStatus.Accepted,
    ExternalAutomationStatus.Completed,
    ExternalAutomationStatus.Failed,
    -> null
}

private fun ExternalAutomationRejectionReason.toReasonUi(): ExternalRequestReasonUi = when (this) {
    ExternalAutomationRejectionReason.CONTRACT_DISABLED -> ExternalRequestReasonUi.ContractDisabled
    ExternalAutomationRejectionReason.SURFACE_NOT_BOUND -> ExternalRequestReasonUi.SurfaceNotBound
    ExternalAutomationRejectionReason.TARGET_NOT_ALLOWED -> ExternalRequestReasonUi.TargetNotAllowed
    ExternalAutomationRejectionReason.TARGET_MISSING -> ExternalRequestReasonUi.TargetMissing
    ExternalAutomationRejectionReason.TARGET_AMBIGUOUS -> ExternalRequestReasonUi.TargetAmbiguous
    ExternalAutomationRejectionReason.UNKNOWN_ACTION -> ExternalRequestReasonUi.UnknownAction
    ExternalAutomationRejectionReason.PROMPT_MISSING -> ExternalRequestReasonUi.PromptMissing
    ExternalAutomationRejectionReason.PROMPT_AMBIGUOUS -> ExternalRequestReasonUi.PromptAmbiguous
    ExternalAutomationRejectionReason.PROMPT_UNDECODABLE -> ExternalRequestReasonUi.PromptUndecodable
    ExternalAutomationRejectionReason.REQUEST_ID_MISSING -> ExternalRequestReasonUi.RequestIdMissing
    ExternalAutomationRejectionReason.VALUE_TOO_LONG -> ExternalRequestReasonUi.ValueTooLong
    ExternalAutomationRejectionReason.RATE_LIMITED -> ExternalRequestReasonUi.RateLimited
    ExternalAutomationRejectionReason.RETURN_PACKAGE_MISMATCH -> ExternalRequestReasonUi.ReturnPackageMismatch
}

/**
 * How the row names what the caller asked for.
 *
 * A name is shown as the caller wrote it, because that is what the user
 * recognises; an id is elided to its head, because a full UUID pushes everything
 * else off the line and no one reads past the first few characters anyway.
 */
private fun ExternalAutomationTarget.label(labels: JournalLabels): String = when (this) {
    is ExternalAutomationTarget.ByName -> pipelineName.abbreviate()
    is ExternalAutomationTarget.ById -> labels.targetByIdFormat.format(
        pipelineId.take(TARGET_ID_PREFIX_CHARS) + if (pipelineId.length > TARGET_ID_PREFIX_CHARS) "…" else "",
    )
}

/** Clips a caller-supplied string to a length this app chose rather than the caller. */
private fun String.abbreviate(): String =
    if (length <= CALLER_TEXT_MAX_CHARS) this else take(CALLER_TEXT_MAX_CHARS) + "…"

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

// ── Wire contract → the "how another app calls this" block ──────────────────

/**
 * The request keys, read from the contract object rather than re-typed here.
 *
 * A block that teaches the wrong key is worse than no block: a profile written
 * against it fails silently, looking to the app like an ordinary malformed
 * request. Sourcing the keys from the same constants the parser reads makes that
 * particular mistake impossible.
 */
@Composable
private fun callKeys(): List<ExternalCallKeyUi> = listOf(
    ExternalAutomationContract.EXTRA_PIPELINE_ID to R.string.external_automation_key_pipeline_id,
    ExternalAutomationContract.EXTRA_PIPELINE_NAME to R.string.external_automation_key_pipeline_name,
    ExternalAutomationContract.EXTRA_PROMPT to R.string.external_automation_key_prompt,
    ExternalAutomationContract.EXTRA_PROMPT_B64 to R.string.external_automation_key_prompt_b64,
    ExternalAutomationContract.EXTRA_REQUEST_ID to R.string.external_automation_key_request_id,
    ExternalAutomationContract.EXTRA_RETURN_ACTION to R.string.external_automation_key_return_action,
    ExternalAutomationContract.EXTRA_RETURN_PACKAGE to R.string.external_automation_key_return_package,
).map { (key, meaningRes) ->
    // No required/optional tag: no key of this contract is unconditionally
    // required — a target and a prompt are each required in one of two mutually
    // exclusive forms, and the correlation id only when the caller asked to be
    // answered. Every condition is stated in the key's own meaning line, which
    // is the only place able to express one.
    ExternalCallKeyUi(key = key, meaning = stringResource(meaningRes))
}

/**
 * The clipboard payload of the call block: the action and the key names, nothing
 * invented. Worked Tasker / MacroDroid / `adb` examples are documentation, and
 * putting a half-remembered one here would be the same silent-failure trap the
 * generated reference tables exist to prevent.
 */
private fun callDetailsText(callKeys: List<ExternalCallKeyUi>): String = buildString {
    appendLine(ExternalAutomationContract.ACTION_RUN_PIPELINE)
    callKeys.forEach { key -> appendLine(key.key) }
}.trimEnd()

// ── Localised copy ──────────────────────────────────────────────────────────

@Composable
private fun journalStrings(): ExternalAutomationJournalStrings = ExternalAutomationJournalStrings(
    title = stringResource(R.string.external_automation_title),
    subtitle = stringResource(R.string.external_automation_journal_subtitle),
    backCd = stringResource(R.string.external_automation_back_cd),
    bannerOffTitle = stringResource(R.string.external_automation_banner_off_title),
    bannerOffBody = stringResource(R.string.external_automation_banner_off_body),
    bannerUnboundTitle = stringResource(R.string.external_automation_banner_unbound_title),
    bannerUnboundBody = stringResource(R.string.external_automation_banner_unbound_body),
    bannerBoundTitle = stringResource(R.string.external_automation_banner_bound_title),
    bannerBoundBodyFormat = stringResource(R.string.external_automation_banner_bound_body),
    sectionLabel = stringResource(R.string.external_automation_section_label),
    retentionFooter = pluralStringResource(
        R.plurals.external_automation_retention_footer,
        RETENTION_DAYS,
        RETENTION_DAYS,
        NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])
            .format(CleanupExternalAutomationJournalUseCase.MAX_RECORDS),
    ),
    emptyTitle = stringResource(R.string.external_automation_empty_title),
    emptyBody = stringResource(R.string.external_automation_empty_body),
    statusAccepted = stringResource(R.string.external_automation_status_accepted),
    statusCompleted = stringResource(R.string.external_automation_status_completed),
    statusFailed = stringResource(R.string.external_automation_status_failed),
    statusRejected = stringResource(R.string.external_automation_status_rejected),
    statusBlocked = stringResource(R.string.external_automation_status_blocked),
    outcomeRunning = stringResource(R.string.external_automation_outcome_running),
    outcomeCompleted = stringResource(R.string.external_automation_outcome_completed),
    outcomeFailed = stringResource(R.string.external_automation_outcome_failed),
    rejectedHint = stringResource(R.string.external_automation_rejected_hint),
    blockedHint = stringResource(R.string.external_automation_blocked_hint),
    reasonContractDisabled = stringResource(R.string.external_automation_reason_contract_disabled),
    reasonSurfaceNotBound = stringResource(R.string.external_automation_reason_surface_not_bound),
    reasonTargetNotAllowed = stringResource(R.string.external_automation_reason_target_not_allowed),
    reasonTargetMissing = stringResource(R.string.external_automation_reason_target_missing),
    reasonTargetAmbiguous = stringResource(R.string.external_automation_reason_target_ambiguous),
    reasonUnknownAction = stringResource(R.string.external_automation_reason_unknown_action),
    reasonPromptMissing = stringResource(R.string.external_automation_reason_prompt_missing),
    reasonPromptAmbiguous = stringResource(R.string.external_automation_reason_prompt_ambiguous),
    reasonPromptUndecodable = stringResource(R.string.external_automation_reason_prompt_undecodable),
    reasonRequestIdMissing = stringResource(R.string.external_automation_reason_request_id_missing),
    reasonValueTooLong = stringResource(R.string.external_automation_reason_value_too_long),
    reasonRateLimited = stringResource(R.string.external_automation_reason_rate_limited),
    reasonReturnPackageMismatch = stringResource(R.string.external_automation_reason_return_package_mismatch),
    reasonReturnPackageMismatchNote =
    stringResource(R.string.external_automation_reason_return_package_mismatch_note),
    targetPrefix = stringResource(R.string.external_automation_target_prefix),
    actionPrefixFormat = stringResource(R.string.external_automation_action_prefix),
    senderAttestedFormat = stringResource(R.string.external_automation_sender_attested),
    senderClaimedFormat = stringResource(R.string.external_automation_sender_claimed),
    requestIdFormat = stringResource(R.string.external_automation_request_id),
    repeatFormat = stringResource(R.string.external_automation_repeat),
    repeatCdFormat = stringResource(R.string.external_automation_repeat_cd),
    callBlockTitle = stringResource(R.string.external_automation_call_title),
    callBlockBody = stringResource(R.string.external_automation_call_body),
    callBlockActionLabel = stringResource(R.string.external_automation_call_action_label),
    callBlockKeysLabel = stringResource(R.string.external_automation_call_keys_label),
    callBlockCopy = stringResource(R.string.external_automation_call_copy),
    callBlockExpandCd = stringResource(R.string.external_automation_call_expand_cd),
    exportShareCd = stringResource(R.string.external_automation_export_share_cd),
    exportSaveCd = stringResource(R.string.external_automation_export_save_cd),
)
