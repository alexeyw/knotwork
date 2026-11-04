package app.knotwork.design.components.controls

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
        modifier = modifier.height(SegmentedHeight),
        shape = RoundedCornerShape(SegmentedCornerRadius),
        color = KnotworkTheme.extended.surface2,
        border = BorderStroke(width = SegmentedBorderWidth, color = KnotworkTheme.extended.divider),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(SegmentedCornerRadius))
                        .background(
                            color = if (selected) {
                                MaterialTheme.colorScheme.surface
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            },
                        )
                        .clickable(onClick = { onSelect(index) })
                        .padding(horizontal = KnotworkTheme.spacing.sp2),
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
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Outer segmented-control height — touch-target floor of 40 dp + 4 dp visual padding. */
private val SegmentedHeight = 40.dp

/** Pill corner radius. */
private val SegmentedCornerRadius = 12.dp

/** Outline border width. */
private val SegmentedBorderWidth = 1.dp
