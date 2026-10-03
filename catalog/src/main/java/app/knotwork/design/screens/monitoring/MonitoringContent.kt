@file:Suppress("MatchingDeclarationName") // Hosts MonitoringContent + helpers.

package app.knotwork.design.screens.monitoring

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import app.knotwork.design.components.misc.EmptyState
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Stateless Knotwork Monitoring surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitoringContent(
    state: MonitoringViewState,
    modifier: Modifier = Modifier,
    strings: MonitoringStrings = MonitoringStrings(),
    callbacks: MonitoringCallbacks = noopMonitoringCallbacks(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            androidx.compose.foundation.layout.Column {
                TopBar(strings = strings, callbacks = callbacks)
                androidx.compose.material3.HorizontalDivider(color = KnotworkTheme.extended.divider)
            }
        },
    ) { padding ->
        when (state.visualState) {
            MonitoringVisualState.Loading -> Loading(padding = padding)
            MonitoringVisualState.Empty -> Empty(padding = padding, strings = strings)
            MonitoringVisualState.Error -> Error(
                padding = padding,
                state = state,
                strings = strings,
                callbacks = callbacks,
            )
            MonitoringVisualState.Default -> Body(padding = padding, state = state, strings = strings)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(strings: MonitoringStrings, callbacks: MonitoringCallbacks) {
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
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun Loading(padding: PaddingValues) {
    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Empty(padding: PaddingValues, strings: MonitoringStrings) {
    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        EmptyState(title = strings.emptyTitle, subtitle = strings.emptySubtitle)
    }
}

@Composable
private fun Error(
    padding: PaddingValues,
    state: MonitoringViewState,
    strings: MonitoringStrings,
    callbacks: MonitoringCallbacks,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding).padding(KnotworkTheme.spacing.sp6),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            title = strings.errorTitle,
            subtitle = state.errorMessage.orEmpty(),
            ctaLabel = strings.errorRetry,
            onCtaClick = callbacks.onRetry,
        )
    }
}

@Composable
private fun Body(padding: PaddingValues, state: MonitoringViewState, strings: MonitoringStrings) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(
            start = KnotworkTheme.spacing.sp4,
            end = KnotworkTheme.spacing.sp4,
            top = KnotworkTheme.spacing.sp3,
            bottom = KnotworkTheme.spacing.sp6,
        ),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        if (state.powerSavingActive) {
            item(key = "power") {
                PowerSavingBanner(strings = strings)
            }
        }
        if (state.stats.isNotEmpty()) {
            item(key = "stats") { StatsGrid(stats = state.stats) }
        }
        if (state.totalExecutionLine != null) {
            item(key = "total") {
                AggregateLine(label = strings.sessionAggregates, value = state.totalExecutionLine)
            }
        }
        if (state.perNodeBreakdown.isNotEmpty()) {
            item(key = "breakdown-header") {
                SectionHeader(label = strings.perNodeHeader)
            }
            items(items = state.perNodeBreakdown, key = { it.nodeType }) { row ->
                NodeBreakdownRow(row = row)
            }
        }
        if (state.logs.isNotEmpty()) {
            item(key = "logs-header") { SectionHeader(label = strings.logsHeader) }
            items(items = state.logs, key = { it.timestamp + it.message.hashCode() }) { line ->
                LogLine(line = line)
            }
        } else {
            item(key = "logs-empty") {
                Text(
                    text = strings.logsEmpty,
                    style = KnotworkTextStyles.BodyBase,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
        }
    }
}

@Composable
private fun PowerSavingBanner(strings: MonitoringStrings) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(color = KnotworkTheme.extended.signalWarn.copy(alpha = 0.15f))
            .padding(KnotworkTheme.spacing.sp4),
    ) {
        Icon(
            imageVector = AppIcons.Battery,
            contentDescription = null,
            tint = KnotworkTheme.extended.signalWarn,
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = strings.powerSavingTitle,
                style = KnotworkTextStyles.TitleMd,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = strings.powerSavingSubtitle,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurface2,
            )
        }
    }
}

@Composable
private fun StatsGrid(stats: List<MonitoringStat>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier.fillMaxWidth(),
    ) {
        stats.forEach { stat ->
            StatCell(stat = stat, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCell(stat: MonitoringStat, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        modifier = modifier
            .clip(KnotworkTheme.shapes.md)
            .background(color = KnotworkTheme.extended.surface1)
            .padding(KnotworkTheme.spacing.sp3),
    ) {
        Text(
            text = stat.label,
            style = KnotworkTextStyles.LabelSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
        Text(
            text = stat.value,
            style = KnotworkTextStyles.TitleLg,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun AggregateLine(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = KnotworkTextStyles.LabelSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = KnotworkTextStyles.MonoBase,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = KnotworkTextStyles.LabelSm,
        color = KnotworkTheme.extended.onSurfaceMuted,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NodeBreakdownRow(row: NodeBreakdownRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.sm)
            .background(color = KnotworkTheme.extended.surface1)
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
    ) {
        Text(
            text = row.nodeType,
            style = KnotworkTextStyles.MonoBase,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = row.totalLabel,
            style = KnotworkTextStyles.MonoBase,
            color = KnotworkTheme.extended.onSurface2,
        )
    }
}

@Composable
private fun LogLine(line: MonitoringLogLine) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.sm)
            .background(color = KnotworkTheme.extended.surface1)
            .padding(KnotworkTheme.spacing.sp3),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
    ) {
        Text(
            text = line.timestamp,
            style = KnotworkTextStyles.LabelSm,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = line.message,
            style = KnotworkTextStyles.BodySm,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Localised string bundle threaded into [MonitoringContent]. */
data class MonitoringStrings(
    val title: String = "Live metrics",
    val backCd: String = "Back",
    val powerSavingTitle: String = "Power saving active",
    val powerSavingSubtitle: String = "Agent model is unloaded and background tasks are paused.",
    val sessionAggregates: String = "TOTAL EXECUTION",
    val perNodeHeader: String = "TIME PER NODE TYPE",
    val logsHeader: String = "RECENT LOGS",
    val logsEmpty: String = "No recent actions yet.",
    val emptyTitle: String = "No metrics yet",
    val emptySubtitle: String = "Run a pipeline to populate the live metrics here.",
    val errorTitle: String = "Couldn't load metrics",
    val errorRetry: String = "Retry",
)
