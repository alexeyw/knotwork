@file:Suppress("MatchingDeclarationName") // Hosts TaskMonitorContent + helpers.

package app.knotwork.design.screens.taskmonitor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.components.chips.KnotworkFilterChip
import app.knotwork.design.components.chips.Status
import app.knotwork.design.components.chips.StatusPill
import app.knotwork.design.components.misc.EmptyState
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Side length of the leading icon halo on a row. */
private val LeadingTileSize = 40.dp

/** Per-row progress bar height. */
private val ProgressBarHeight = 2.dp

/**
 * Stateless Knotwork Task Monitor surface.
 *
 * Renders the filter row, list of task cards, and the details body. The
 * host wraps [TaskMonitorDetailSheetBody] in a `ModalBottomSheet` when a
 * row is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskMonitorContent(
    state: TaskMonitorViewState,
    modifier: Modifier = Modifier,
    strings: TaskMonitorStrings = TaskMonitorStrings(),
    callbacks: TaskMonitorCallbacks = noopTaskMonitorCallbacks(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            androidx.compose.foundation.layout.Column {
                TopBar(state = state, strings = strings, callbacks = callbacks)
                androidx.compose.material3.HorizontalDivider(color = KnotworkTheme.extended.divider)
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.visualState != TaskMonitorVisualState.Loading &&
                state.visualState != TaskMonitorVisualState.Error
            ) {
                FilterRow(filter = state.filter, callbacks = callbacks)
            }
            when (state.visualState) {
                TaskMonitorVisualState.Loading -> Loading()
                TaskMonitorVisualState.Empty -> Empty(strings = strings)
                TaskMonitorVisualState.Error -> Error(state = state, strings = strings, callbacks = callbacks)
                TaskMonitorVisualState.Default -> if (state.rows.isEmpty()) {
                    Empty(strings = strings)
                } else {
                    List(state = state, strings = strings, callbacks = callbacks)
                }
            }
        }
        if (state.confirmingCancelAll) {
            CancelAllScheduledDialog(strings = strings, callbacks = callbacks)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(state: TaskMonitorViewState, strings: TaskMonitorStrings, callbacks: TaskMonitorCallbacks) {
    TopAppBar(
        title = {
            Text(text = strings.title, style = KnotworkTextStyles.TitleMd, color = MaterialTheme.colorScheme.onSurface)
        },
        navigationIcon = {
            IconButton(onClick = callbacks.onBack) {
                Icon(
                    imageVector = AppIcons.Back,
                    contentDescription = strings.backCd,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        actions = {
            // Only offered when there is something to stop: this is the way out
            // of a task that keeps re-scheduling itself, not a routine control.
            if (state.scheduledTaskCount > 0) {
                IconButton(onClick = callbacks.onCancelAllScheduled) {
                    Icon(
                        imageVector = AppIcons.Stop,
                        contentDescription = strings.cancelAllCd,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/**
 * Confirmation for stopping every scheduled task at once.
 *
 * A plain confirm rather than the typed-keyword dialog the settings wipes use:
 * this is recoverable (a task can simply be scheduled again) and it is itself
 * the recovery action, so making someone type a word while they are trying to
 * stop a runaway loop would be the wrong kind of friction. It still confirms,
 * because it settles several tasks the user cannot individually see at that
 * moment.
 */
@Composable
private fun CancelAllScheduledDialog(strings: TaskMonitorStrings, callbacks: TaskMonitorCallbacks) {
    AlertDialog(
        onDismissRequest = callbacks.onCancelAllScheduledDismiss,
        title = { Text(text = strings.cancelAllTitle) },
        text = { Text(text = strings.cancelAllBody) },
        confirmButton = {
            TextButton(onClick = callbacks.onCancelAllScheduledConfirm) {
                Text(text = strings.cancelAllConfirm, color = KnotworkTheme.extended.signalError)
            }
        },
        dismissButton = {
            TextButton(onClick = callbacks.onCancelAllScheduledDismiss) { Text(text = strings.cancelAllDismiss) }
        },
    )
}

@Composable
private fun FilterRow(filter: TaskFilterKind, callbacks: TaskMonitorCallbacks) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        contentPadding = PaddingValues(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(TaskFilterKind.entries.toTypedArray()) { kind ->
            KnotworkFilterChip(
                label = kind.displayName,
                selected = filter == kind,
                onClick = { callbacks.onFilterChanged(kind) },
            )
        }
    }
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Empty(strings: TaskMonitorStrings) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(title = strings.emptyTitle, subtitle = strings.emptySubtitle)
    }
}

@Composable
private fun Error(state: TaskMonitorViewState, strings: TaskMonitorStrings, callbacks: TaskMonitorCallbacks) {
    Box(modifier = Modifier.fillMaxSize().padding(KnotworkTheme.spacing.sp6), contentAlignment = Alignment.Center) {
        EmptyState(
            title = strings.errorTitle,
            subtitle = state.errorMessage.orEmpty(),
            ctaLabel = strings.errorRetry,
            onCtaClick = callbacks.onRetry,
        )
    }
}

@Composable
private fun List(state: TaskMonitorViewState, strings: TaskMonitorStrings, callbacks: TaskMonitorCallbacks) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = KnotworkTheme.spacing.sp4,
            end = KnotworkTheme.spacing.sp4,
            top = KnotworkTheme.spacing.sp2,
            bottom = KnotworkTheme.spacing.sp6,
        ),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
    ) {
        items(items = state.rows, key = { it.id }) { row ->
            TaskRow(row = row, strings = strings, callbacks = callbacks)
        }
    }
}

@Composable
private fun TaskRow(row: TaskMonitorRow, strings: TaskMonitorStrings, callbacks: TaskMonitorCallbacks) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(color = KnotworkTheme.extended.surface1)
            .clickable(onClick = { callbacks.onRowClick(row.id) }, role = Role.Button)
            .padding(KnotworkTheme.spacing.sp4),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(LeadingTileSize)
                    .background(color = MaterialTheme.colorScheme.surface, shape = KnotworkTheme.shapes.sm),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.Play,
                    contentDescription = null,
                    tint = KnotworkTheme.extended.onSurface2,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = KnotworkTheme.spacing.sp3),
                verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
            ) {
                Text(
                    text = row.title,
                    style = KnotworkTextStyles.TitleMd,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.subtitle != null) {
                    Text(
                        text = row.subtitle,
                        style = KnotworkTextStyles.MonoSm,
                        color = KnotworkTheme.extended.onSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            StatusPill(status = row.status.toCatalogStatus())
            if (row.isCancellable) {
                IconButton(onClick = { callbacks.onRowCancel(row.id) }) {
                    Icon(
                        imageVector = AppIcons.X,
                        contentDescription = strings.cancelCd,
                        tint = KnotworkTheme.extended.signalError,
                    )
                }
            }
        }
        if (row.status == TaskRowStatus.Running && row.progress != null) {
            LinearProgressIndicator(
                progress = { row.progress.coerceIn(minimumValue = 0f, maximumValue = 1f) },
                modifier = Modifier.fillMaxWidth().height(ProgressBarHeight),
                color = MaterialTheme.colorScheme.primary,
                trackColor = KnotworkTheme.extended.surface2,
                strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
            )
        }
    }
}

/**
 * Stateless body for the row-detail `ModalBottomSheet`. The host owns the
 * sheet container itself.
 */
@Composable
fun TaskMonitorDetailSheetBody(
    detail: TaskMonitorDetail,
    strings: TaskMonitorStrings,
    callbacks: TaskMonitorCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(KnotworkTheme.spacing.sp4),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = detail.title,
                    style = KnotworkTextStyles.TitleLg,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (detail.subtitle != null) {
                    Text(
                        text = detail.subtitle,
                        style = KnotworkTextStyles.MonoSm,
                        color = KnotworkTheme.extended.onSurfaceMuted,
                    )
                }
            }
            StatusPill(status = detail.status.toCatalogStatus())
        }
        if (detail.logs.isEmpty()) {
            Text(
                text = strings.detailNoLogs,
                style = KnotworkTextStyles.BodyBase,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        } else {
            detail.logs.forEach { line ->
                Text(
                    text = line,
                    style = KnotworkTextStyles.MonoSm,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3)) {
            KnotworkTextButton(
                text = strings.detailDismiss,
                onClick = callbacks.onDetailDismiss,
                modifier = Modifier.weight(1f),
            )
            if (detail.canOpenChat) {
                // Only sessions have a chat to open; background tasks
                // (WorkManager UUIDs) hide the CTA to avoid routing the
                // app to a non-existent chat id.
                KnotworkPrimaryButton(
                    text = strings.detailOpenChat,
                    onClick = { callbacks.onDetailOpenChat(detail.id) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun TaskRowStatus.toCatalogStatus(): Status = when (this) {
    TaskRowStatus.Queued -> Status.Queued
    TaskRowStatus.Running -> Status.Running
    TaskRowStatus.Success -> Status.Success
    TaskRowStatus.Cancelled -> Status.Cancelled
    TaskRowStatus.Failed -> Status.Error
}

/** Localised string bundle threaded into [TaskMonitorContent]. */
@Suppress("LongParameterList")
data class TaskMonitorStrings(
    val title: String = "Active tasks",
    val backCd: String = "Back",
    val cancelCd: String = "Cancel task",
    val emptyTitle: String = "No tasks running",
    val emptySubtitle: String = "Background tasks and active chat sessions appear here.",
    val errorTitle: String = "Couldn't load tasks",
    val errorRetry: String = "Retry",
    val detailDismiss: String = "Close",
    val detailOpenChat: String = "Open chat",
    val detailNoLogs: String = "No log lines captured yet.",
    val cancelAllCd: String = "Stop all scheduled tasks",
    val cancelAllTitle: String = "Stop all scheduled tasks?",
    val cancelAllConfirm: String = "Stop all",
    val cancelAllDismiss: String = "Keep them",
    /**
     * Body of the bulk-cancel confirmation. Already carries the task count:
     * the host resolves the plural for its own language before building this
     * bundle, the same way every other counted string in the catalog arrives
     * pre-resolved.
     */
    val cancelAllBody: String =
        "This stops 2 scheduled tasks, including any that are running. Automations and their triggers are not " +
            "affected. Nothing else is deleted — a task can be scheduled again.",
)
