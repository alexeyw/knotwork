package app.knotwork.design.components.controls

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Knotwork three-or-more segmented control.
 *
 * Renders the trailing segmented control used by the Settings →
 * Restrictions card's "Approve tool calls" row. Generic enough to be
 * reused by any 2–5-option mutually-exclusive picker — see
 * `KnotworkSegmentedControlPreview` for usage.
 *
 * The options share one row in equal parts while every label fits its part on
 * one line; otherwise they stack, each the full width, its label wrapping
 * between words. A larger font therefore lengthens the control instead of
 * cutting a label short — at 200 % even "Sensitive +" outgrows a third of a
 * phone's width. Each option is at least [SegmentedHeight] tall, and taller when
 * its label needs it.
 *
 * @param options Ordered labels for the segments.
 * @param selectedIndex Index of the currently selected segment.
 * @param onSelect Click callback supplying the newly selected index.
 * @param modifier Outer layout modifier.
 */
@Composable
fun KnotworkSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(SegmentedCornerRadius),
        color = KnotworkTheme.extended.surface2,
        border = BorderStroke(width = SegmentedBorderWidth, color = KnotworkTheme.extended.divider),
    ) {
        Layout(
            content = {
                options.forEachIndexed { index, label ->
                    Segment(label = label, selected = index == selectedIndex, onClick = { onSelect(index) })
                }
            },
            measurePolicy = SegmentsMeasurePolicy,
        )
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(SegmentedCornerRadius))
            .background(color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp1),
    ) {
        Text(
            text = label,
            style = KnotworkTextStyles.LabelMd.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                KnotworkTheme.extended.onSurfaceMuted
            },
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Lays the segments out in one row of equal parts when every label fits its part
 * on one line, and in one column of full-width segments otherwise.
 *
 * The intrinsic widths say the same to a parent: the widest a row needs (each
 * part as wide as the widest label) and the narrowest a column can be (the
 * longest word), so a row placing the control beside a label can ask how much
 * room the options need before they stack.
 */
private object SegmentsMeasurePolicy : MeasurePolicy {

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        if (measurables.isEmpty()) return layout(constraints.minWidth, constraints.minHeight) {}
        val rowWidth = rowWidth(measurables)
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            rowWidth.coerceAtLeast(constraints.minWidth)
        }
        val minHeight = SegmentedHeight.roundToPx()
        return if (rowWidth <= width) {
            val widths = partWidths(width, measurables.size)
            val height = measurables.indices.maxOf { measurables[it].minIntrinsicHeight(widths[it]) }
                .coerceAtLeast(minHeight)
            val placeables = measurables.mapIndexed { index, segment ->
                segment.measure(Constraints.fixed(widths[index], height))
            }
            layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
                var x = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(x, 0)
                    x += placeable.width
                }
            }
        } else {
            val placeables = measurables.map { segment ->
                segment.measure(Constraints(minWidth = width, maxWidth = width, minHeight = minHeight))
            }
            val height = placeables.sumOf { it.height }
            layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height
                }
            }
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        rowWidth(measurables)

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.maxOfOrNull { it.minIntrinsicWidth(Constraints.Infinity) } ?: 0

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        intrinsicHeight(measurables, width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        intrinsicHeight(measurables, width)

    /** The width a row needs: every part as wide as the widest label laid out on one line. */
    private fun rowWidth(measurables: List<IntrinsicMeasurable>): Int =
        (measurables.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0) * measurables.size

    /** [width] split into [count] parts, the remainder spread over the first ones. */
    private fun partWidths(width: Int, count: Int): List<Int> =
        List(count) { index -> width / count + if (index < width % count) 1 else 0 }

    private fun IntrinsicMeasureScope.intrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        if (measurables.isEmpty()) return 0
        val minHeight = SegmentedHeight.roundToPx()
        return if (width == Constraints.Infinity || rowWidth(measurables) <= width) {
            val widths = if (width == Constraints.Infinity) null else partWidths(width, measurables.size)
            measurables.indices.maxOf { index ->
                measurables[index].minIntrinsicHeight(widths?.get(index) ?: Constraints.Infinity)
            }.coerceAtLeast(minHeight)
        } else {
            measurables.sumOf { it.minIntrinsicHeight(width).coerceAtLeast(minHeight) }
        }
    }
}

/** Least height of a segment — touch-target floor of 40 dp + 4 dp visual padding. */
private val SegmentedHeight = 40.dp

/** Pill corner radius. */
private val SegmentedCornerRadius = 12.dp

/** Outline border width. */
private val SegmentedBorderWidth = 1.dp
