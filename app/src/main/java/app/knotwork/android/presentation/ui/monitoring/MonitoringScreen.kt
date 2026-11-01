package app.knotwork.android.presentation.ui.monitoring

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.knotwork.android.R
import app.knotwork.design.screens.monitoring.MonitoringCallbacks
import app.knotwork.design.screens.monitoring.MonitoringContent
import app.knotwork.design.screens.monitoring.MonitoringLogLine
import app.knotwork.design.screens.monitoring.MonitoringStat
import app.knotwork.design.screens.monitoring.MonitoringStrings
import app.knotwork.design.screens.monitoring.MonitoringViewState
import app.knotwork.design.screens.monitoring.MonitoringVisualState
import app.knotwork.design.screens.monitoring.NodeBreakdownRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Slim app-side Monitoring mapper. Subscribes to [MonitoringViewModel.uiState]
 * and renders [MonitoringContent].
 */
@Composable
fun MonitoringScreen(viewModel: MonitoringViewModel, modifier: Modifier = Modifier, onBack: () -> Unit = {}) {
    val uiState by viewModel.uiState.collectAsState()
    val strings = monitoringStrings()
    val viewState = remember(uiState) { uiState.toViewState(strings) }
    MonitoringContent(
        state = viewState,
        modifier = modifier,
        strings = strings,
        callbacks = MonitoringCallbacks(onBack = onBack, onRetry = viewModel::loadLogs),
    )
}

private val LOG_TIME_FORMATTER = SimpleDateFormat("HH:mm:ss", Locale.US)

internal fun MonitoringUiState.toViewState(strings: MonitoringStrings): MonitoringViewState {
    val stats = listOf(
        MonitoringStat(label = "INFERENCE TIME", value = "${metrics.lastInferenceTimeMs} ms"),
        MonitoringStat(label = "TOKENS/S", value = String.format(Locale.US, "%.1f", metrics.tokensPerSecond)),
        MonitoringStat(label = "TOTAL TOKENS", value = metrics.totalTokensProcessed.toString()),
    )
    val totalLine = if (metrics.totalExecutionTimeMs > 0L) "${metrics.totalExecutionTimeMs} ms" else null
    val breakdown = metrics.timePerNodeType
        .toList()
        .sortedByDescending { it.second }
        .map { (nodeType, durationMs) ->
            NodeBreakdownRow(nodeType = nodeType.name, totalLabel = "$durationMs ms")
        }
    val logs = recentLogs.mapIndexed { index, message ->
        MonitoringLogLine(
            // A stored row always has its database id; the position stands in only for a
            // message that was never saved, and the prefixes keep the two apart.
            id = message.id?.let { "row:$it" } ?: "pos:$index",
            timestamp = LOG_TIME_FORMATTER.format(Date(message.timestamp)),
            message = message.content,
        )
    }
    val visualState = when {
        isLoading && recentLogs.isEmpty() && metrics.totalTokensProcessed == 0 -> MonitoringVisualState.Loading
        !powerSavingHasContent(isPowerSavingActive, stats, totalLine, breakdown, logs) -> MonitoringVisualState.Empty
        else -> MonitoringVisualState.Default
    }
    @Suppress("UNUSED_PARAMETER")
    strings
    return MonitoringViewState(
        visualState = visualState,
        powerSavingActive = isPowerSavingActive,
        stats = if (visualState == MonitoringVisualState.Default) stats else emptyList(),
        totalExecutionLine = if (visualState == MonitoringVisualState.Default) totalLine else null,
        perNodeBreakdown = if (visualState == MonitoringVisualState.Default) breakdown else emptyList(),
        logs = if (visualState == MonitoringVisualState.Default) logs else emptyList(),
    )
}

private fun powerSavingHasContent(
    powerSaving: Boolean,
    stats: List<MonitoringStat>,
    totalLine: String?,
    breakdown: List<NodeBreakdownRow>,
    logs: List<MonitoringLogLine>,
): Boolean = powerSaving ||
    stats.any { it.value != "0" && it.value != "0 ms" && it.value != "0.0" } ||
    totalLine != null ||
    breakdown.isNotEmpty() ||
    logs.isNotEmpty()

@Composable
private fun monitoringStrings(): MonitoringStrings = MonitoringStrings(
    title = stringResource(R.string.monitoring_screen_title),
    backCd = stringResource(R.string.common_back),
    powerSavingTitle = stringResource(R.string.monitoring_power_saving_title),
    powerSavingSubtitle = stringResource(R.string.monitoring_power_saving_text),
    sessionAggregates = stringResource(R.string.monitoring_section_session_aggregates),
    perNodeHeader = stringResource(R.string.monitoring_section_time_per_node),
    logsHeader = stringResource(R.string.monitoring_section_logs),
    logsEmpty = stringResource(R.string.monitoring_no_recent_actions),
    emptyTitle = stringResource(R.string.monitoring_empty_title),
    emptySubtitle = stringResource(R.string.monitoring_empty_subtitle),
    errorTitle = stringResource(R.string.monitoring_error_title),
    errorRetry = stringResource(R.string.common_retry),
)
