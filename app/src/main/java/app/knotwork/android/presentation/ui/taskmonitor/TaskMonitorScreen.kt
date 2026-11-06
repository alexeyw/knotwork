package app.knotwork.android.presentation.ui.taskmonitor

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.knotwork.android.R
import app.knotwork.android.domain.services.ScheduledTaskKind
import app.knotwork.android.presentation.ui.common.UiText
import app.knotwork.android.presentation.ui.common.resolve
import app.knotwork.design.screens.taskmonitor.TaskFilterKind
import app.knotwork.design.screens.taskmonitor.TaskMonitorCallbacks
import app.knotwork.design.screens.taskmonitor.TaskMonitorContent
import app.knotwork.design.screens.taskmonitor.TaskMonitorDetail
import app.knotwork.design.screens.taskmonitor.TaskMonitorDetailSheetBody
import app.knotwork.design.screens.taskmonitor.TaskMonitorRow
import app.knotwork.design.screens.taskmonitor.TaskMonitorStrings
import app.knotwork.design.screens.taskmonitor.TaskMonitorViewState
import app.knotwork.design.screens.taskmonitor.TaskMonitorVisualState
import app.knotwork.design.screens.taskmonitor.TaskRowStatus

/**
 * Slim app-side Task Monitor mapper. Subscribes to
 * [TaskMonitorViewModel.uiState] and renders [TaskMonitorContent] with an
 * optional row-detail `ModalBottomSheet`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskMonitorScreen(
    viewModel: TaskMonitorViewModel,
    modifier: Modifier = Modifier,
    onNavigateToChat: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val callbacks = TaskMonitorCallbacks(
        onBack = onBack,
        onFilterChanged = { viewModel.onFilterChanged(it.toAppFilter()) },
        onRowClick = viewModel::openDetails,
        onRowCancel = viewModel::onCancelTaskClicked,
        onCancelAllScheduled = viewModel::onCancelAllScheduledClicked,
        onCancelAllScheduledConfirm = viewModel::onCancelAllScheduledConfirmed,
        onCancelAllScheduledDismiss = viewModel::onCancelAllScheduledDismissed,
        onDetailDismiss = viewModel::closeDetails,
        onDetailOpenChat = { taskId ->
            // The catalog already gates the CTA visibility on
            // `canOpenChat`; this guard is a defence-in-depth so a
            // mistaken host invocation can't write an invalid
            // currentChatSessionId for a background WorkManager UUID.
            val task = uiState.tasks.firstOrNull { it.id == taskId }
            if (task?.type == TaskType.SESSION) {
                viewModel.onOpenChatClicked(taskId) {
                    viewModel.closeDetails()
                    onNavigateToChat(taskId)
                }
            }
        },
        // `onRetry` only fires in the catalog's Error state, which this mapper
        // never produces: the view state is folded purely from reactive flows
        // (`getSessionsFlow` + `getWorkInfosFlow` + `activeSessionsState`) that
        // self-heal on the next emission, so there is no failed-load surface to
        // recover from here.
        onRetry = { /* unreachable: this screen never enters the Error state. */ },
    )

    TaskMonitorScreenContent(uiState = uiState, callbacks = callbacks, modifier = modifier)
}

/**
 * The Task Monitor for a given state, without its ViewModel: the words, the row
 * projection, [TaskMonitorContent] and the row-detail sheet.
 *
 * @param uiState The state to show.
 * @param callbacks The screen's actions; [TaskMonitorCallbacks.onDetailDismiss]
 *   also closes the detail sheet.
 * @param modifier Layout modifier applied to the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskMonitorScreenContent(
    uiState: TaskMonitorState,
    callbacks: TaskMonitorCallbacks,
    modifier: Modifier = Modifier,
) {
    val strings = taskMonitorStrings(scheduledTaskCount = uiState.scheduledTaskCount)
    // Row copy is resolved once in composable context; the projection below is a
    // plain function, so it cannot call `stringResource` per row.
    val labels = taskMonitorRowLabels()
    val context = LocalContext.current
    val viewState = remember(uiState, labels, context) { uiState.toViewState(labels) { context.resolve(it) } }
    TaskMonitorContent(state = viewState, modifier = modifier, strings = strings, callbacks = callbacks)

    val detail = viewState.expandedDetail
    if (detail != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = callbacks.onDetailDismiss, sheetState = sheetState) {
            TaskMonitorDetailSheetBody(detail = detail, strings = strings, callbacks = callbacks)
        }
    }
}

/**
 * Pre-resolved row copy for the scheduled-task rows.
 *
 * @property scheduledTitle Base title of a task the agent scheduled for itself.
 * @property intervalFormat Compact repeat-interval fragment (`every 6 h`).
 *   Deliberately abbreviated rather than pluralised: it reads the same for every
 *   count, the same reasoning the journal's `12m ago` label follows.
 */
internal data class TaskRowLabels(val scheduledTitle: String, val intervalFormat: String)

@Composable
private fun taskMonitorRowLabels(): TaskRowLabels = TaskRowLabels(
    scheduledTitle = stringResource(R.string.taskmonitor_row_scheduled_title),
    intervalFormat = stringResource(R.string.taskmonitor_row_scheduled_interval_format),
)

internal fun TaskMonitorState.toViewState(labels: TaskRowLabels, resolve: (UiText) -> String): TaskMonitorViewState {
    val rows = tasks.map { it.toRow(labels, resolve) }
    val visualState = when {
        isLoading -> TaskMonitorVisualState.Loading
        rows.isEmpty() -> TaskMonitorVisualState.Empty
        else -> TaskMonitorVisualState.Default
    }
    val detail = detailTaskId?.let { id ->
        tasks.firstOrNull { it.id == id }?.let { task ->
            TaskMonitorDetail(
                id = task.id,
                title = resolve(task.title),
                subtitle = task.pipelineStage?.let(resolve),
                status = task.status.toCatalog(),
                logs = emptyList(),
                // Open-chat navigation is only valid for chat-session
                // tasks; background WorkManager rows carry a UUID id
                // that does not resolve to a chat.
                canOpenChat = task.type == TaskType.SESSION,
            )
        }
    }
    return TaskMonitorViewState(
        visualState = visualState,
        filter = filter.toCatalog(),
        rows = rows,
        expandedDetail = detail,
        scheduledTaskCount = scheduledTaskCount,
        confirmingCancelAll = confirmingCancelAll,
    )
}

/**
 * Says what a scheduled task *is* — its kind, cadence and the chat it reports
 * into — as the row's second line.
 *
 * The prompt takes the title and this takes the subtitle, not the other way
 * round: a row title has room for roughly a dozen characters next to the status
 * pill and the cancel control, and every scheduled task would open with the same
 * "Scheduled task · …" prefix. Truncating the one thing that differs would
 * reproduce the anonymous rows this label exists to replace.
 */
private fun TaskItem.scheduledSubtitle(labels: TaskRowLabels): String? {
    val label = scheduled ?: return null
    return buildList {
        add(labels.scheduledTitle)
        if (label.kind == ScheduledTaskKind.PERIODIC) add(labels.intervalFormat.format(label.intervalHours))
        boundSessionName?.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString(SUBTITLE_SEPARATOR)
}

private fun TaskItem.toRow(labels: TaskRowLabels, resolve: (UiText) -> String): TaskMonitorRow = TaskMonitorRow(
    id = id,
    // The prompt is the only part that tells two scheduled tasks apart, so it
    // gets the widest line.
    title = scheduled?.promptPreview?.takeIf { it.isNotBlank() } ?: resolve(title),
    subtitle = scheduledSubtitle(labels) ?: pipelineStage?.let(resolve),
    status = status.toCatalog(),
    progress = progress?.takeIf { it >= 0f },
    isCancellable = type == TaskType.BACKGROUND_WORK && (status == TaskStatus.RUNNING || status == TaskStatus.QUEUED),
)

private fun TaskStatus.toCatalog(): TaskRowStatus = when (this) {
    TaskStatus.RUNNING -> TaskRowStatus.Running
    TaskStatus.QUEUED -> TaskRowStatus.Queued
    TaskStatus.COMPLETED -> TaskRowStatus.Success
    TaskStatus.FAILED -> TaskRowStatus.Failed
}

private fun TaskFilterType.toCatalog(): TaskFilterKind = when (this) {
    TaskFilterType.ALL -> TaskFilterKind.All
    TaskFilterType.ACTIVE -> TaskFilterKind.Active
    TaskFilterType.BACKGROUND -> TaskFilterKind.Background
    TaskFilterType.COMPLETED -> TaskFilterKind.Completed
}

private fun TaskFilterKind.toAppFilter(): TaskFilterType = when (this) {
    TaskFilterKind.All -> TaskFilterType.ALL
    TaskFilterKind.Active -> TaskFilterType.ACTIVE
    TaskFilterKind.Background -> TaskFilterType.BACKGROUND
    TaskFilterKind.Completed -> TaskFilterType.COMPLETED
}

/** Separator between the parts of a scheduled row's subtitle. */
private const val SUBTITLE_SEPARATOR = " · "

@Composable
private fun taskMonitorStrings(scheduledTaskCount: Int): TaskMonitorStrings = TaskMonitorStrings(
    title = stringResource(R.string.taskmonitor_screen_title),
    backCd = stringResource(R.string.common_back),
    cancelCd = stringResource(R.string.common_cancel),
    emptyTitle = stringResource(R.string.taskmonitor_empty_title),
    emptySubtitle = stringResource(R.string.taskmonitor_empty_subtitle),
    errorTitle = stringResource(R.string.taskmonitor_error_title),
    errorRetry = stringResource(R.string.common_retry),
    detailDismiss = stringResource(R.string.common_close),
    detailOpenChat = stringResource(R.string.taskmonitor_open_chat),
    detailNoLogs = stringResource(R.string.taskmonitor_detail_no_logs),
    cancelAllCd = stringResource(R.string.taskmonitor_cancel_all_cd),
    cancelAllTitle = stringResource(R.string.taskmonitor_cancel_all_title),
    cancelAllConfirm = stringResource(R.string.taskmonitor_cancel_all_confirm),
    cancelAllDismiss = stringResource(R.string.taskmonitor_cancel_all_dismiss),
    cancelAllBody = pluralStringResource(
        R.plurals.taskmonitor_cancel_all_body,
        scheduledTaskCount,
        scheduledTaskCount,
    ),
)
