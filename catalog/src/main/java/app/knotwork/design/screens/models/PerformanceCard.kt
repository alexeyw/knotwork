@file:Suppress("MatchingDeclarationName") // Hosts PerformanceCard + private state helpers.

package app.knotwork.design.screens.models

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Header glyph size. */
private val HeaderGlyphSize = 20.dp

/** Spinner glyph size for the running state. */
private val SpinnerSize = 20.dp

/** Height of one progress-track segment. */
private val SegmentHeight = 5.dp

/** Minimum width of a single metric block before the row wraps it to a new line. */
private val MetricMinWidth = 104.dp

/** Full rotation of the running-state spinner, in milliseconds. */
private const val SPINNER_PERIOD_MS = 900

/** Pulse period of the active measuring segment, in milliseconds. */
private const val SEGMENT_PULSE_MS = 1400

/** Dimmed alpha floor of the pulsing measuring segment. */
private const val SEGMENT_PULSE_MIN_ALPHA = 0.35f

/**
 * Stateless model **Performance card** — rolling-average TTFT / decode speed /
 * peak memory for the active model, plus the controlled benchmark
 * (warm-up → measured → inline one-shot report).
 *
 * Rendered directly below the Active-model card; reuses that card's idiom
 * (rounded `surface3` container with section dividers). All numeric values
 * arrive pre-formatted; see [PerformanceCardState].
 *
 * @param state which variant to render.
 * @param strings localised display strings.
 * @param callbacks benchmark/share/dismiss callbacks.
 * @param modifier optional layout modifier.
 */
@Composable
fun PerformanceCard(
    state: PerformanceCardState,
    modifier: Modifier = Modifier,
    strings: PerformanceStrings = PerformanceStrings(),
    callbacks: PerformanceCallbacks = PerformanceCallbacks(),
) {
    val accent = state is PerformanceCardState.Running || state is PerformanceCardState.Result
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(color = KnotworkTheme.extended.surface3),
    ) {
        PerformanceHeader(
            title = strings.title,
            accent = accent,
            trailing = {
                when (state) {
                    is PerformanceCardState.Populated -> Text(
                        text = state.sampleCaption,
                        style = KnotworkTextStyles.MonoSm,
                        color = KnotworkTheme.extended.onSurfaceMuted,
                    )
                    is PerformanceCardState.Result -> BenchmarkPill(label = strings.benchmarkBadge)
                    else -> Unit
                }
            },
        )
        HorizontalDivider(color = KnotworkTheme.extended.divider)
        when (state) {
            is PerformanceCardState.NoModel -> NoticeBody(
                icon = AppIcons.Chip,
                title = null,
                body = strings.noModelBody,
            )
            is PerformanceCardState.EngineBusy -> NoticeBody(
                icon = AppIcons.Hourglass,
                title = strings.busyTitle,
                body = strings.busyBody,
            )
            is PerformanceCardState.Empty -> EmptyBody(strings = strings, callbacks = callbacks)
            is PerformanceCardState.Populated -> PopulatedBody(state = state, strings = strings, callbacks = callbacks)
            is PerformanceCardState.Running -> RunningBody(state = state, strings = strings, callbacks = callbacks)
            is PerformanceCardState.Result -> ResultBody(state = state, strings = strings, callbacks = callbacks)
        }
    }
}

@Composable
private fun PerformanceHeader(title: String, accent: Boolean, trailing: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
    ) {
        Icon(
            imageVector = AppIcons.Gauge,
            contentDescription = null,
            tint = if (accent) MaterialTheme.colorScheme.primary else KnotworkTheme.extended.onSurface2,
            modifier = Modifier.size(HeaderGlyphSize),
        )
        // A flow, not a row: a row measured the caption first and handed the title what was
        // left, so a larger font broke "Performance" inside the word. The flow moves the
        // caption under the title once the title's longest word would not fit beside it.
        FlowRow(
            itemVerticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                style = KnotworkTextStyles.LabelLg,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            trailing()
        }
    }
}

@Composable
private fun BenchmarkPill(label: String) {
    Text(
        text = label,
        style = KnotworkTextStyles.LabelSm,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .clip(KnotworkTheme.shapes.full)
            .background(color = MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp1),
    )
}

@Composable
private fun NoticeBody(icon: ImageVector, title: String?, body: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp3),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceDim,
            modifier = Modifier.size(HeaderGlyphSize),
        )
        Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
            if (title != null) {
                Text(
                    text = title,
                    style = KnotworkTextStyles.LabelLg,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = body,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurface2,
            )
        }
    }
}

@Composable
private fun EmptyBody(strings: PerformanceStrings, callbacks: PerformanceCallbacks) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier.padding(KnotworkTheme.spacing.sp3),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
            Icon(
                imageVector = AppIcons.History,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurfaceDim,
                modifier = Modifier.size(HeaderGlyphSize),
            )
            Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
                Text(
                    text = strings.emptyTitle,
                    style = KnotworkTextStyles.LabelLg,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = strings.emptyBody,
                    style = KnotworkTextStyles.BodySm,
                    color = KnotworkTheme.extended.onSurface2,
                )
            }
        }
        RunBenchmarkButton(strings = strings, callbacks = callbacks)
    }
}

@Composable
private fun PopulatedBody(
    state: PerformanceCardState.Populated,
    strings: PerformanceStrings,
    callbacks: PerformanceCallbacks,
) {
    Column {
        MetricsRow(
            modifier = Modifier.padding(
                start = KnotworkTheme.spacing.sp3,
                end = KnotworkTheme.spacing.sp3,
                top = KnotworkTheme.spacing.sp3,
            ),
        ) {
            Metric(label = strings.ttftLabel, value = state.ttft, emphasis = false)
            Metric(label = strings.decodeLabel, value = state.decode, emphasis = false)
            Metric(label = strings.peakMemoryLabel, value = state.peakMemory ?: strings.unknownValue, emphasis = false)
        }
        MemoryNote(text = strings.memoryNote)
        HorizontalDivider(color = KnotworkTheme.extended.divider)
        Box(modifier = Modifier.padding(KnotworkTheme.spacing.sp3)) {
            RunBenchmarkButton(strings = strings, callbacks = callbacks)
        }
    }
}

@Composable
private fun RunningBody(
    state: PerformanceCardState.Running,
    strings: PerformanceStrings,
    callbacks: PerformanceCallbacks,
) {
    val measuring = state.phase == BenchmarkPhase.Measuring
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier.padding(KnotworkTheme.spacing.sp3),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        ) {
            BenchmarkSpinner()
            Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
                Text(
                    text = if (measuring) strings.measuringTitle else strings.warmingUpTitle,
                    style = KnotworkTextStyles.LabelLg,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (measuring) strings.measuringSub else strings.warmingUpSub,
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
        }
        ProgressTrack(measuring = measuring)
        KnotworkTextButton(
            text = strings.cancel,
            onClick = callbacks.onCancelBenchmark,
            size = KnotworkButtonSize.Sm,
        )
    }
}

@Composable
private fun ResultBody(
    state: PerformanceCardState.Result,
    strings: PerformanceStrings,
    callbacks: PerformanceCallbacks,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier.padding(KnotworkTheme.spacing.sp3),
    ) {
        MetricsRow {
            Metric(label = strings.ttftLabel, value = state.ttft, emphasis = true)
            Metric(label = strings.decodeLabel, value = state.decode, emphasis = true)
            Metric(label = strings.totalLabel, value = state.total, emphasis = false)
            Metric(
                label = strings.peakMemoryLabel,
                value = state.peakMemory ?: strings.unknownValue,
                emphasis = false,
                sub = state.peakMemory?.let { strings.memoryReportNote },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
            KnotworkPrimaryButton(
                text = strings.share,
                onClick = callbacks.onShareReport,
                leadingIcon = AppIcons.Share,
                modifier = Modifier.weight(1f),
            )
            KnotworkSecondaryButton(
                text = strings.done,
                onClick = callbacks.onDismissReport,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RunBenchmarkButton(strings: PerformanceStrings, callbacks: PerformanceCallbacks) {
    KnotworkPrimaryButton(
        text = strings.runBenchmark,
        onClick = callbacks.onRunBenchmark,
        leadingIcon = AppIcons.Gauge,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetricsRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // FlowRow so the metric blocks wrap to a second line at large font scales
    // instead of clipping; units stay glued to their numbers inside each block.
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        content()
    }
}

@Composable
private fun Metric(label: String, value: String, emphasis: Boolean, sub: String? = null) {
    val valueStyle = KnotworkTextStyles.MonoBase.copy(
        fontWeight = FontWeight.Bold,
        fontSize = if (emphasis) 24.sp else 21.sp,
    )
    Column(modifier = Modifier.widthIn(min = MetricMinWidth)) {
        Text(
            text = value,
            style = valueStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = KnotworkTextStyles.BodySm,
            color = KnotworkTheme.extended.onSurface2,
        )
        if (sub != null) {
            Text(
                text = sub,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

@Composable
private fun MemoryNote(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        modifier = Modifier.padding(
            start = KnotworkTheme.spacing.sp3,
            end = KnotworkTheme.spacing.sp3,
            top = KnotworkTheme.spacing.sp2,
            bottom = KnotworkTheme.spacing.sp3,
        ),
    ) {
        Icon(
            imageVector = AppIcons.Info,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceDim,
            modifier = Modifier.size(KnotworkTheme.spacing.sp4),
        )
        Text(
            text = text,
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}

@Composable
private fun BenchmarkSpinner() {
    val rotation = if (KnotworkTheme.a11y.reducedMotion()) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "benchmark_spinner")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(animation = tween(SPINNER_PERIOD_MS)),
            label = "benchmark_spinner_rotation",
        )
        value
    }
    Icon(
        imageVector = AppIcons.Refresh,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(SpinnerSize)
            .rotate(rotation),
    )
}

@Composable
private fun ProgressTrack(measuring: Boolean) {
    val measuringAlpha = if (!measuring || KnotworkTheme.a11y.reducedMotion()) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "benchmark_segment")
        val value by transition.animateFloat(
            initialValue = SEGMENT_PULSE_MIN_ALPHA,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(SEGMENT_PULSE_MS),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "benchmark_segment_alpha",
        )
        value
    }
    Row(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        Segment(filled = true, alpha = 1f, modifier = Modifier.weight(1f))
        Segment(filled = measuring, alpha = measuringAlpha, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Segment(filled: Boolean, alpha: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(SegmentHeight)
            .clip(KnotworkTheme.shapes.full)
            .background(color = KnotworkTheme.extended.surface4),
    ) {
        if (filled) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SegmentHeight)
                    .clip(KnotworkTheme.shapes.full)
                    .background(color = MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
            )
        }
    }
}
