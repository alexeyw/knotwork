package app.knotwork.design.components.console

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.KnotworkScrollThumb
import app.knotwork.design.components.ScrollThumbColors
import app.knotwork.design.components.ScrollThumbMap
import app.knotwork.design.components.ScrollThumbTarget
import app.knotwork.design.components.THUMB_MIN_SCREENS
import app.knotwork.design.components.ThumbBubbleDrop
import app.knotwork.design.components.ThumbBubbleOverlap
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles
import java.text.NumberFormat
import kotlin.math.roundToInt

/** Test tag of the value viewer's scroll thumb — its touch target. */
internal const val CONSOLE_VALUE_THUMB_TAG = "console_value_thumb"

private const val BUBBLE_FILL_ALPHA = 0.12f
private const val BUBBLE_HAIRLINE_ALPHA = 0.15f

/** Opacity of the bar at rest, relative to `consoleFg`. */
private const val BAR_ALPHA = 0.42f

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
) : ScrollThumbMap {
    /** The last row that can be at the top, as a fractional row. */
    private val maxTopRow = (pieces.rowCount - contentViewportPx / rowPx).coerceAtLeast(0f)

    /**
     * The thumb's share, from 0 at the top to 1 at the bottom, for a list whose first
     * visible item is [firstIndex], scrolled [firstOffsetPx] into it.
     */
    override fun fraction(firstIndex: Int, firstOffsetPx: Int): Float {
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

    override fun scrollTarget(fraction: Float): ScrollThumbTarget = target(fraction).let {
        ScrollThumbTarget(index = it.index, offsetPx = it.offsetPx)
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
 * The value viewer's scroll thumb ([KnotworkScrollThumb]), in the console's colours, for a
 * value more than [THUMB_MIN_SCREENS] screens tall. While dragged, a bubble beside it
 * names the line at the top, *Line 4,101 of 9,812* — not for a value of one line, which
 * would say line 1 throughout.
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
    KnotworkScrollThumb(
        listState = listState,
        map = map,
        viewportPx = viewportPx,
        colors = ScrollThumbColors(
            idle = KnotworkTheme.extended.consoleFg.copy(alpha = BAR_ALPHA),
            dragged = KnotworkTheme.extended.consoleTag,
        ),
        testTag = CONSOLE_VALUE_THUMB_TAG,
        bubble = if (lineCount > 1) {
            { fraction -> LineBubble(line = map.target(fraction).lineIndex + 1, of = lineCount) }
        } else {
            null
        },
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
            .padding(top = ThumbBubbleDrop)
            .offset(x = ThumbBubbleOverlap)
            .shadow(KnotworkTheme.elevation.el3, shape)
            .background(fg.copy(alpha = BUBBLE_FILL_ALPHA).compositeOver(KnotworkTheme.extended.consoleBg), shape)
            .border(1.dp, fg.copy(alpha = BUBBLE_HAIRLINE_ALPHA), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
