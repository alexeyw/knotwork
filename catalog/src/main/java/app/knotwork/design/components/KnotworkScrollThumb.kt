package app.knotwork.design.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.knotwork.design.theme.KnotworkTheme
import kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/** How long a scroll thumb stays after scrolling and dragging stop. */
internal const val THUMB_HIDE_DELAY_MS = 1_500L

/** A list gets a scroll thumb only when it is taller than this many screens. */
internal const val THUMB_MIN_SCREENS = 5

/** The thumb's bar at rest and while it is dragged, and the touch target around it. */
private val ThumbIdleWidth = 4.dp
private val ThumbIdleHeight = 44.dp
private val ThumbDragWidth = 6.dp
private val ThumbDragHeight = 56.dp
private val ThumbTouchSize = 48.dp

/** Gap between the bar and the screen's edge. */
private val ThumbEdgeGap = 3.dp

/**
 * Maps between a list's scroll position and its thumb: the thumb's place is a share of
 * the list's scroll range, from 0 at the top to 1 at the bottom.
 */
internal interface ScrollThumbMap {
    /**
     * The thumb's share for a list whose first visible item is [firstIndex], scrolled
     * [firstOffsetPx] into it.
     */
    fun fraction(firstIndex: Int, firstOffsetPx: Int): Float

    /** Where to scroll the list for the thumb at [fraction]. */
    fun scrollTarget(fraction: Float): ScrollThumbTarget
}

/**
 * A place to scroll a list to.
 *
 * @property index the lazy item to bring to the top.
 * @property offsetPx how far into that item.
 */
internal data class ScrollThumbTarget(val index: Int, val offsetPx: Int)

/**
 * The bar's colours.
 *
 * @property idle the bar at rest: thin and faint.
 * @property dragged the bar while it is dragged: wider, in an accent.
 */
internal class ScrollThumbColors(val idle: Color, val dragged: Color)

/**
 * A scroll thumb over the right edge of a lazy list, for a list the caller has found
 * tall enough. It fades in while the list scrolls and out [THUMB_HIDE_DELAY_MS] after it
 * stops — appearing and going without the fade under reduced motion. Dragging it scrolls
 * the list through [map]; while dragged, [bubble] is drawn beside it with the share the
 * drag has reached.
 *
 * While shown, the thumb's touch target is excluded from the system back gesture: the
 * thumb sits in the edge's gesture zone, where a drag would otherwise go back. It is
 * hidden from TalkBack, whose scroll actions page through the list.
 *
 * The console's value viewer and the chat's message reader draw their thumbs with it.
 *
 * @param listState the list the thumb scrolls.
 * @param map how the list's position and the thumb's share map onto each other.
 * @param viewportPx the list's height.
 * @param colors the bar's colours.
 * @param testTag the test tag of the thumb's touch target.
 * @param bubble what is drawn beside the dragged thumb, given the share it is at; `null`
 *   for nothing.
 */
@Composable
internal fun KnotworkScrollThumb(
    listState: LazyListState,
    map: ScrollThumbMap,
    viewportPx: Int,
    colors: ScrollThumbColors,
    testTag: String,
    bubble: (@Composable (fraction: Float) -> Unit)?,
) {
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
            val target = map.scrollTarget(fraction)
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
                .testTag(testTag)
                .clearAndSetSemantics {},
        ) {
            if (dragging && bubble != null) bubble(dragFraction)
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
                ThumbBar(dragging = dragging, colors = colors)
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

/** The bar: thin and faint at rest, wider in its accent while dragged. */
@Composable
private fun ThumbBar(dragging: Boolean, colors: ScrollThumbColors) {
    Box(
        modifier = Modifier
            .padding(end = ThumbEdgeGap)
            .size(
                width = if (dragging) ThumbDragWidth else ThumbIdleWidth,
                height = if (dragging) ThumbDragHeight else ThumbIdleHeight,
            )
            .background(if (dragging) colors.dragged else colors.idle, KnotworkTheme.shapes.xs),
    )
}

/** How far a thumb's bubble reaches into the touch target, so it sits 22 dp from the edge. */
internal val ThumbBubbleOverlap = ThumbTouchSize - 22.dp

/** Drop from the top of the bar to the top of the bubble. */
internal val ThumbBubbleDrop = 6.dp
