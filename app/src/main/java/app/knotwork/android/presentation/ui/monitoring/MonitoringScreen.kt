package app.knotwork.android.presentation.ui.monitoring

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
    MonitoringScreenContent(
        uiState = uiState,
        callbacks = MonitoringCallbacks(onBack = onBack, onRetry = viewModel::loadLogs),
        modifier = modifier,
    )
}

/**
 * The Monitoring screen for a given state, without its ViewModel: the words, the
 * number style and the projection onto [MonitoringContent].
 *
 * @param uiState The state to show.
 * @param callbacks The screen's actions.
 * @param modifier Layout modifier applied to the screen.
 */
@Composable
internal fun MonitoringScreenContent(
    uiState: MonitoringUiState,
    callbacks: MonitoringCallbacks,
    modifier: Modifier = Modifier,
) {
    val strings = monitoringStrings()
    val labels = monitoringStatLabels()
    val viewState = remember(uiState, labels) { uiState.toViewState(strings, labels) }
    MonitoringContent(state = viewState, modifier = modifier, strings = strings, callbacks = callbacks)
}

private val LOG_TIME_FORMATTER = SimpleDateFormat("HH:mm:ss", Locale.US)

/**
 * The words and number style of the monitoring stats, in the interface language.
 *
 * @property inferenceTime Label of the last inference's duration.
 * @property tokensPerSecond Label of the decode throughput.
 * @property totalTokens Label of the session's token count.
 * @property milliseconds Format of a duration in ms; `%1$d` is the count.
 * @property locale Writes the throughput's decimal separator.
 */
internal data class MonitoringStatLabels(
    val inferenceTime: String,
    val tokensPerSecond: String,
    val totalTokens: String,
    val milliseconds: String,
    val locale: Locale,
)

@Composable
private fun monitoringStatLabels(): MonitoringStatLabels = MonitoringStatLabels(
    inferenceTime = stringResource(R.string.monitoring_stat_inference_time),
    tokensPerSecond = stringResource(R.string.monitoring_stat_tokens_per_second),
    totalTokens = stringResource(R.string.monitoring_stat_total_tokens),
    milliseconds = stringResource(R.string.monitoring_unit_ms),
    locale = LocalConfiguration.current.locales[0],
)

internal fun MonitoringUiState.toViewState(
    strings: MonitoringStrings,
    labels: MonitoringStatLabels,
): MonitoringViewState {
    val stats = listOf(
        MonitoringStat(labels.inferenceTime, labels.milliseconds.format(metrics.lastInferenceTimeMs)),
        MonitoringStat(labels.tokensPerSecond, String.format(labels.locale, "%.1f", metrics.tokensPerSecond)),
        MonitoringStat(labels.totalTokens, metrics.totalTokensProcessed.toString()),
    )
    val totalLine = if (metrics.totalExecutionTimeMs >
        0L
    ) {
        labels.milliseconds.format(metrics.totalExecutionTimeMs)
    } else {
        null
    }
    val breakdown = metrics.timePerNodeType
        .toList()
        .sortedByDescending { it.second }
        .map { (nodeType, durationMs) ->
            NodeBreakdownRow(nodeType = nodeType.name, totalLabel = labels.milliseconds.format(durationMs))
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
