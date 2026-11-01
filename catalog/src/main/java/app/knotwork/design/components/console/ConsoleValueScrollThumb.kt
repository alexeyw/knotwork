package app.knotwork.design.components.console

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles
import kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import java.text.NumberFormat
import kotlin.math.roundToInt

/** Test tag of the value viewer's scroll thumb — its touch target. */
internal const val CONSOLE_VALUE_THUMB_TAG = "console_value_thumb"

/** The thumb is shown only when the value is taller than this many screens. */
private const val THUMB_MIN_SCREENS = 5

/** How long the thumb stays after scrolling and dragging stop. */
internal const val THUMB_HIDE_DELAY_MS = 1_500L

/** The thumb's bar at rest and while it is dragged, and the touch target around it. */
private val ThumbIdleWidth = 4.dp
private val ThumbIdleHeight = 44.dp
private val ThumbDragWidth = 6.dp
private val ThumbDragHeight = 56.dp
private val ThumbTouchSize = 48.dp

/** Gap between the bar and the screen's edge. */
private val ThumbEdgeGap = 3.dp

/** How far the line bubble reaches into the touch target, so it sits 22 dp from the edge. */
private val BubbleOverlap = ThumbTouchSize - 22.dp

/** Drop from the top of the bar to the top of the bubble. */
private val BubbleDrop = 6.dp

private const val BAR_ALPHA = 0.42f
private const val BUBBLE_FILL_ALPHA = 0.12f
private const val BUBBLE_HAIRLINE_ALPHA = 0.15f

/**
 * Whether a value of [rowCount] rows of [rowPx] each is tall enough for a scroll thumb in
 * a viewport of [viewportPx]: more than [THUMB_MIN_SCREENS] screens.
 */
internal fun consoleValueThumbShown(rowCount: Int, rowPx: Float, viewportPx: Int): Boolean =
    rowCount * rowPx > THUMB_MIN_SCREENS * viewportPx.toFloat()

/**
 * Maps between the value viewer's scroll position and its thumb, in rows of
 * [ConsoleValuePieces]: the thumb's place is the share of the scroll range above the top
 * row, and dragging it to a share scrolls that row to the top.
 *
 * @param pieces the viewer's lazy items and their rows.
 * @param rowPx the height of one row.
 * @param contentViewportPx the height rows are shown in — the viewport less the list's
 *   vertical content padding.
 */
internal class ConsoleValueScrollMap(
    private val pieces: ConsoleValuePieces,
    private val rowPx: Float,
    contentViewportPx: Int,
) {
    /** The last row that can be at the top, as a fractional row. */
    private val maxTopRow = (pieces.rowCount - contentViewportPx / rowPx).coerceAtLeast(0f)

    /**
     * The thumb's share, from 0 at the top to 1 at the bottom, for a list whose first
     * visible item is [firstIndex], scrolled [firstOffsetPx] into it.
     */
    fun fraction(firstIndex: Int, firstOffsetPx: Int): Float {
        if (maxTopRow == 0f) return 0f
        val topRow = pieces.firstRowOf(firstIndex) + firstOffsetPx / rowPx
        return (topRow / maxTopRow).coerceIn(0f, 1f)
    }

    /**
     * Where to scroll for the thumb at [fraction].
     *
     * @return the lazy item to bring to the top, how far into it, and the value line it
     *   belongs to.
     */
    fun target(fraction: Float): ConsoleValueScrollTarget {
        val position = pieces.pieceAt((fraction.coerceIn(0f, 1f) * maxTopRow).toInt())
        return ConsoleValueScrollTarget(
            index = position.index,
            offsetPx = (position.rowInPiece * rowPx).roundToInt(),
            lineIndex = pieces.piece(position.index).lineIndex,
        )
    }
}

/**
 * A place to scroll the value viewer to.
 *
 * @property index the lazy item to bring to the top.
 * @property offsetPx how far into that item.
 * @property lineIndex the value line the item belongs to.
 */
internal data class ConsoleValueScrollTarget(val index: Int, val offsetPx: Int, val lineIndex: Int)

/**
 * The value viewer's scroll thumb, over the right edge of its list. Shown only for a
 * value more than [THUMB_MIN_SCREENS] screens tall: it fades in while the list scrolls
 * and out [THUMB_HIDE_DELAY_MS] after it stops — appearing and going without the fade
 * under reduced motion. Dragging it scrolls the list; while dragged, a bubble beside it
 * names the line at the top, *Line 4,101 of 9,812* — not for a value of one line, which
 * would say line 1 throughout.
 *
 * While shown, the thumb's touch target is excluded from the system back gesture: the
 * thumb sits in the edge's gesture zone, where a drag would otherwise go back. It is
 * hidden from TalkBack, whose scroll actions page through the list.
 *
 * @param listState the list the thumb scrolls.
 * @param pieces the list's items and their rows.
 * @param lineCount how many lines the value has.
 * @param rowPx the height of one row.
 * @param viewportPx the list's height.
 * @param paddingPx the list's top and bottom content padding together.
 */
@Composable
internal fun ConsoleValueScrollThumb(
    listState: LazyListState,
    pieces: ConsoleValuePieces,
    lineCount: Int,
    rowPx: Float,
    viewportPx: Int,
    paddingPx: Int,
) {
    if (!consoleValueThumbShown(pieces.rowCount, rowPx, viewportPx)) return
    val map = remember(pieces, rowPx, viewportPx, paddingPx) {
        ConsoleValueScrollMap(pieces, rowPx, viewportPx - paddingPx)
    }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val shown = rememberThumbShown(scrolling = listState.isScrollInProgress, dragging = dragging)
    val reduced = KnotworkTheme.a11y.reducedMotion()
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = if (reduced) snap() else tween(KnotworkTheme.motion.dur2),
        label = "thumbAlpha",
    )
    fun listFraction(): Float = when {
        !listState.canScrollBackward -> 0f
        !listState.canScrollForward -> 1f
        else -> map.fraction(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
    }
    // Every share the drag reaches is a request; the newest one wins, and the last is
    // served even when the finger lifts in the same frame that moved it.
    val requests = remember { MutableSharedFlow<Float>(extraBufferCapacity = 1, onBufferOverflow = DROP_OLDEST) }
    LaunchedEffect(map) {
        requests.collectLatest { fraction ->
            val target = map.target(fraction)
            listState.scrollToItem(target.index, target.offsetPx)
        }
    }
    val touchHeightPx = with(LocalDensity.current) { (if (dragging) ThumbDragHeight else ThumbTouchSize).toPx() }
    val travelPx = (viewportPx - touchHeightPx).coerceAtLeast(1f)
    val draggableState = rememberDraggableState { delta ->
        dragFraction = (dragFraction + delta / travelPx).coerceIn(0f, 1f)
        requests.tryEmit(dragFraction)
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset {
                    val fraction = if (dragging) dragFraction else listFraction()
                    IntOffset(0, (fraction * travelPx).roundToInt())
                }
                .graphicsLayer { this.alpha = alpha }
                .testTag(CONSOLE_VALUE_THUMB_TAG)
                .clearAndSetSemantics {},
        ) {
            if (dragging && lineCount > 1) {
                LineBubble(line = map.target(dragFraction).lineIndex + 1, of = lineCount)
            }
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier = Modifier
                    .size(width = ThumbTouchSize, height = if (dragging) ThumbDragHeight else ThumbTouchSize)
                    // Only while shown: a disabled `draggable` still takes the touch it ignores
                    // (foundation 1.12.1 Draggable.kt lines 389-397, 586), so a hidden thumb would
                    // keep the list beneath it from scrolling.
                    .then(
                        if (shown) {
                            Modifier
                                .systemGestureExclusion()
                                .draggable(
                                    state = draggableState,
                                    orientation = Orientation.Vertical,
                                    startDragImmediately = true,
                                    onDragStarted = {
                                        dragFraction = listFraction()
                                        dragging = true
                                    },
                                    onDragStopped = { dragging = false },
                                )
                        } else {
                            Modifier
                        },
                    ),
            ) {
                ThumbBar(dragging = dragging)
            }
        }
    }
}

/**
 * Whether the thumb is shown: from the moment the list scrolls or the thumb is dragged
 * until [THUMB_HIDE_DELAY_MS] after both stop.
 */
@Composable
private fun rememberThumbShown(scrolling: Boolean, dragging: Boolean): Boolean {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) {
            shown = true
        } else if (shown) {
            delay(THUMB_HIDE_DELAY_MS)
            shown = false
        }
    }
    return shown
}

/** The bar: thin and faint at rest, wider in the console's tag colour while dragged. */
@Composable
private fun ThumbBar(dragging: Boolean) {
    val colour = if (dragging) {
        KnotworkTheme.extended.consoleTag
    } else {
        KnotworkTheme.extended.consoleFg.copy(alpha = BAR_ALPHA)
    }
    Box(
        modifier = Modifier
            .padding(end = ThumbEdgeGap)
            .size(
                width = if (dragging) ThumbDragWidth else ThumbIdleWidth,
                height = if (dragging) ThumbDragHeight else ThumbIdleHeight,
            )
            .background(colour, KnotworkTheme.shapes.xs),
    )
}

/** *Line N of M*, beside the dragged thumb. */
@Composable
private fun LineBubble(line: Int, of: Int) {
    val numbers = NumberFormat.getIntegerInstance()
    val fg = KnotworkTheme.extended.consoleFg
    val shape = KnotworkTheme.shapes.sm
    Text(
        text = stringResource(R.string.knotwork_console_value_scroll_pos, numbers.format(line), numbers.format(of)),
        style = KnotworkTextStyles.MonoBase,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .padding(top = BubbleDrop)
            .offset(x = BubbleOverlap)
            .shadow(KnotworkTheme.elevation.el3, shape)
            .background(fg.copy(alpha = BUBBLE_FILL_ALPHA).compositeOver(KnotworkTheme.extended.consoleBg), shape)
            .border(1.dp, fg.copy(alpha = BUBBLE_HAIRLINE_ALPHA), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
