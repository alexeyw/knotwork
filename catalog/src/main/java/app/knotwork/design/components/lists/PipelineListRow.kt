package app.knotwork.design.components.lists

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.chips.Status
import app.knotwork.design.components.chips.StatusPill
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Row visual height. */
private val PipelineRowHeight = 72.dp

/** Diameter of the leading pipeline mark + tinted halo. */
private val PipelineLeadingSize = 40.dp

/** Width of one action affordance in the swipe-revealed action strip. */
private val SwipeActionWidth = 64.dp

/** Total width of the revealed action strip — 3 actions × [SwipeActionWidth]. */
private val SwipeRevealWidth = 192.dp

/** Alpha applied to the leading-tint halo behind the leading icon. */
private const val LEADING_TINT_ALPHA = 0.18f

/** Fraction of the reveal width past which a release snaps open. */
private const val SWIPE_OPEN_THRESHOLD = 0.5f

/**
 * Discrete swipe-revealed actions exposed by [PipelineListRow]. The caller
 * receives the chosen action via the `onAction` callback so the screen-level
 * ViewModel can dispatch domain logic (duplicate, archive, delete pipeline).
 */
enum class PipelineSwipeAction {
    /** Deep-copy the pipeline through `DuplicatePipelineUseCase`. */
    Duplicate,

    /** Archive the pipeline (soft-delete) so it stays recoverable. */
    Archive,

    /** Hard-delete the pipeline; screen layer should confirm before invoking. */
    Delete,
}

/**
 * Knotwork pipeline-library list row.
 *
 * Visual contract:
 *  - 72 dp tall; leading 40 dp tinted mark, `TitleMd` title (1 line, ellipsis),
 *    `BodySm onSurfaceMuted` subtitle (last-run timestamp + status), trailing
 *    24 dp `MoreVert` overflow.
 *  - Swipe-from-right reveals three action buttons (Duplicate / Archive /
 *    Delete) that emit through [onAction]; the row content slides left over
 *    `extended.surface3` so the actions read as one block. Past the
 *    [SWIPE_OPEN_THRESHOLD] fraction the row snaps open; below it, it springs
 *    closed.
 *  - Tap on the row body invokes [onClick]. Tap on the trailing icon
 *    invokes [onOverflow] (open the more-actions menu).
 *  - When [revealed] is set explicitly, the gesture state follows it (used
 *    by snapshot tests and by screens that pre-seed the swipe state).
 *
 * @param title pipeline display name; rendered in `TitleMd`, 1-line ellipsis.
 * @param subtitle e.g. "Run 12 min ago" — rendered in `BodySm onSurfaceMuted`.
 * @param status status pill rendered next to the subtitle.
 * @param leadingTint hue used for the 40 dp pipeline mark — usually the
 * primary node hue of the pipeline (`extended.node*`).
 * @param leadingIcon vector rendered inside the leading 40 dp mark.
 * @param onClick invoked when the user taps the row body.
 * @param onOverflow invoked when the user taps the trailing overflow icon.
 * @param onAction invoked with the chosen [PipelineSwipeAction] when the
 * user taps one of the swipe-revealed action buttons.
 * @param modifier optional layout modifier applied to the row root.
 * @param revealed when non-null, drives the gesture state programmatically
 * (used by snapshot tests). When `null` (default), the user controls the
 * swipe via horizontal drag.
 */
@Composable
fun PipelineListRow(
    title: String,
    subtitle: String,
    status: Status,
    leadingTint: Color,
    leadingIcon: ImageVector,
    onClick: () -> Unit,
    onOverflow: () -> Unit,
    onAction: (PipelineSwipeAction) -> Unit,
    modifier: Modifier = Modifier,
    revealed: Boolean? = null,
) {
    val density = LocalDensity.current
    val revealPx = with(density) { SwipeRevealWidth.toPx() }
    val offsetAnimatable = remember { Animatable(initialValue = 0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(revealed, revealPx) {
        when (revealed) {
            true -> offsetAnimatable.animateTo(-revealPx)
            false -> offsetAnimatable.animateTo(0f)
            null -> Unit
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PipelineRowHeight)
            .background(KnotworkTheme.extended.surface3),
    ) {
        SwipeActionStrip(
            onAction = onAction,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(SwipeRevealWidth)
                .fillMaxHeight(),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            modifier = Modifier
                .offset { IntOffset(offsetAnimatable.value.roundToInt(), 0) }
                .fillMaxWidth()
                .height(PipelineRowHeight)
                .background(MaterialTheme.colorScheme.surface)
                .draggable(
                    state = rememberDraggableState { delta ->
                        if (revealed == null) {
                            scope.launch {
                                val target = (offsetAnimatable.value + delta).coerceIn(-revealPx, 0f)
                                offsetAnimatable.snapTo(target)
                            }
                        }
                    },
                    orientation = Orientation.Horizontal,
                    enabled = revealed == null,
                    onDragStopped = {
                        scope.launch {
                            val openTarget = -revealPx
                            val snapTarget = if (offsetAnimatable.value < openTarget * SWIPE_OPEN_THRESHOLD) {
                                openTarget
                            } else {
                                0f
                            }
                            offsetAnimatable.animateTo(snapTarget)
                        }
                    },
                )
                .clickable(onClick = onClick, role = Role.Button)
                .padding(horizontal = KnotworkTheme.spacing.sp4),
        ) {
            Box(
                modifier = Modifier
                    .size(PipelineLeadingSize)
                    .background(
                        color = leadingTint.copy(alpha = LEADING_TINT_ALPHA),
                        shape = KnotworkTheme.shapes.md,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = leadingTint,
                    modifier = Modifier.size(20.dp),
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = title,
                    style = KnotworkTextStyles.TitleMd,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                ) {
                    Text(
                        text = subtitle,
                        style = KnotworkTextStyles.BodySm,
                        color = KnotworkTheme.extended.onSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    StatusPill(status = status)
                }
            }

            // Trailing overflow icon — gated off when the row is fully revealed
            // because the action strip absorbs the gesture target.
            if (offsetAnimatable.value > -revealPx / 2f) {
                IconButton(onClick = onOverflow) {
                    Icon(
                        imageVector = AppIcons.More,
                        contentDescription = stringResource(
                            app.knotwork.design.R.string.knotwork_library_row_overflow_cd,
                            title,
                        ),
                        tint = KnotworkTheme.extended.onSurfaceMuted,
                    )
                }
            } else {
                Spacer(Modifier.width(KnotworkTheme.spacing.sp10))
            }
        }
    }
}

/**
 * Three-button strip rendered behind the swipeable row. Each button has a
 * distinct background tint matching the action's risk: Duplicate uses
 * `extended.surface4`, Archive uses `extended.signalWarn`, Delete uses
 * `extended.signalError`.
 */
@Composable
private fun SwipeActionStrip(onAction: (PipelineSwipeAction) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier) {
        SwipeActionButton(
            icon = AppIcons.Copy,
            label = "Duplicate",
            tintBackground = KnotworkTheme.extended.surface4,
            tintForeground = KnotworkTheme.extended.onSurface2,
            onClick = { onAction(PipelineSwipeAction.Duplicate) },
        )
        SwipeActionButton(
            icon = AppIcons.Archive,
            label = "Archive",
            tintBackground = KnotworkTheme.extended.signalWarn,
            tintForeground = MaterialTheme.colorScheme.onPrimary,
            onClick = { onAction(PipelineSwipeAction.Archive) },
        )
        SwipeActionButton(
            icon = AppIcons.Trash,
            label = "Delete",
            tintBackground = KnotworkTheme.extended.signalError,
            tintForeground = MaterialTheme.colorScheme.onPrimary,
            onClick = { onAction(PipelineSwipeAction.Delete) },
        )
    }
}

/** One revealed action button — coloured strip + icon + label. */
@Composable
private fun SwipeActionButton(
    icon: ImageVector,
    label: String,
    tintBackground: Color,
    tintForeground: Color,
    onClick: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(SwipeActionWidth)
            .fillMaxHeight()
            .background(tintBackground)
            .clickable(onClick = onClick, role = Role.Button)
            .semantics { contentDescription = label },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tintForeground,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = KnotworkTextStyles.LabelSm,
            color = tintForeground,
        )
    }
}
