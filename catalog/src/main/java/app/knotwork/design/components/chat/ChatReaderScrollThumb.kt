package app.knotwork.design.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/** Test tag of the reader's scroll thumb — its touch target. */
const val CHAT_READER_THUMB_TAG: String = "chat_reader_thumb"

/** Opacity of the bar at rest, relative to `onSurface`. */
private const val BAR_ALPHA = 0.32f

/** Widest the bubble beside the dragged thumb grows before its text ends in an ellipsis. */
private val BubbleMaxWidth = 280.dp

/**
 * Maps between the reader's scroll position and its thumb, by item: the reader's items
 * differ in height and only those on screen are measured, so the thumb's share is the
 * place of the first item on screen among those that can be at the top.
 *
 * The count of items on screen changes as the list scrolls, so it is read on every call:
 * the map itself stays the same for the list, and a drag is never handed to a new one.
 *
 * @param itemCount how many items the list holds.
 * @param visibleCount how many items fill the screen now, at least 1.
 */
internal class ChatReaderScrollMap(private val itemCount: Int, private val visibleCount: () -> Int) : ScrollThumbMap {
    /** The last item that can be at the top. */
    private fun maxTop(): Int = (itemCount - visibleCount()).coerceAtLeast(0)

    override fun fraction(firstIndex: Int, firstOffsetPx: Int): Float {
        val maxTop = maxTop()
        return if (maxTop == 0) 0f else (firstIndex.toFloat() / maxTop).coerceIn(0f, 1f)
    }

    override fun scrollTarget(fraction: Float): ScrollThumbTarget =
        ScrollThumbTarget(index = (fraction.coerceIn(0f, 1f) * maxTop()).toInt(), offsetPx = 0)
}

/**
 * Whether a list of [itemCount] items whose items on screen average [averageItemPx] is
 * taller than [THUMB_MIN_SCREENS] screens of [viewportPx] — an estimate, since only the
 * items on screen are measured. Both leave out the gaps between blocks ([isGap]), which
 * are many and small and would make a few tall items look like a long message.
 */
internal fun chatReaderThumbShown(itemCount: Int, averageItemPx: Float, viewportPx: Int): Boolean =
    itemCount * averageItemPx > THUMB_MIN_SCREENS * viewportPx.toFloat()

/**
 * The reader's scroll thumb ([KnotworkScrollThumb]), in the app's colours, once the whole
 * message is ready and it is more than [THUMB_MIN_SCREENS] screens tall. While dragged, a
 * bubble beside it names the heading the item at the top stands under, or — before the
 * first heading, or in a message with none — the share the thumb is at, *42%*.
 *
 * @param listState the reader's list.
 * @param items the list's items.
 * @param headings the heading each item stands under ([readerHeadings]), one per item.
 * @param viewportPx the list's height.
 */
@Composable
internal fun ChatReaderScrollThumb(
    listState: LazyListState,
    items: List<ChatReaderItem>,
    headings: List<String?>,
    viewportPx: Int,
) {
    val itemCount = items.size
    val contentCount = remember(items) { items.count { !it.isGap } }
    val shown by remember(listState, items, viewportPx) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo.filter { items.getOrNull(it.index)?.isGap == false }
            visible.isNotEmpty() &&
                chatReaderThumbShown(contentCount, visible.sumOf { it.size }.toFloat() / visible.size, viewportPx)
        }
    }
    if (!shown) return
    val map = remember(listState, itemCount) {
        ChatReaderScrollMap(itemCount) { listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1) }
    }
    KnotworkScrollThumb(
        listState = listState,
        map = map,
        viewportPx = viewportPx,
        colors = ScrollThumbColors(
            idle = MaterialTheme.colorScheme.onSurface.copy(alpha = BAR_ALPHA),
            dragged = MaterialTheme.colorScheme.primary,
        ),
        testTag = CHAT_READER_THUMB_TAG,
        bubble = { fraction ->
            val heading = headings.getOrNull(map.scrollTarget(fraction).index)
            ThumbBubble(text = heading ?: NumberFormat.getPercentInstance().format(fraction), heading = heading != null)
        },
    )
}

/** The heading — or the share, in mono — beside the dragged thumb, on one line. */
@Composable
private fun ThumbBubble(text: String, heading: Boolean) {
    val shape = KnotworkTheme.shapes.sm
    Text(
        text = text,
        style = if (heading) KnotworkTextStyles.ButtonLabel else KnotworkTextStyles.MonoBase,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(top = ThumbBubbleDrop)
            .offset(x = ThumbBubbleOverlap)
            .widthIn(max = BubbleMaxWidth)
            .shadow(KnotworkTheme.elevation.el3, shape)
            .background(KnotworkTheme.extended.surface3, shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}
