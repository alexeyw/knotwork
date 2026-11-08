package app.knotwork.design.components.text

import androidx.compose.ui.text.AnnotatedString
import app.knotwork.design.components.splitAtUnbrokenRuns

/**
 * Characters a text with a line limit is handed per line it may draw. No drawn line holds
 * this many: the most measured is 1 280 — a line of `i` in the app's smallest style
 * (`Caption`) at font scale 0.85 across a 2 560 dp window
 * (`project_docs/long-text-2026-11-04-data/chars_per_line.tsv`) — so cutting here never
 * changes what is drawn. The longest line limit a text uses is 6, so a text with one is
 * handed at most 12 000 characters.
 */
internal const val LINE_CHARS_MAX = 2_000

/**
 * Most characters a text without a line limit shows; the rest is counted in a line under it.
 * Laid out cut into runs of at most 2 000 characters, this many cost about 10 ms on the host
 * in the worst shape measured (`project_docs/long-text-2026-11-04-data/`).
 */
internal const val TEXT_MAX_CHARS = 10_000

/**
 * What one text draws: [pieces], laid out one under the other, then — when [omitted] is
 * above zero — a line saying how many characters were left out.
 *
 * @param T the kind of text, `String` or [AnnotatedString].
 * @property pieces the text to draw, cut where one unbroken run grows too long; one piece
 *   when no cut is needed.
 * @property omitted characters of the original text not drawn at all; zero when every
 *   character is in [pieces] or the text ends in its line limit.
 */
internal class ShownText<T : CharSequence>(val pieces: List<T>, val omitted: Int)

/**
 * The end of the longest prefix of [text] no longer than [max] that does not end on the
 * first half of a surrogate pair.
 *
 * @param text the text to cut.
 * @param max the most characters to keep; at least 1.
 * @return an offset in `0..text.length`.
 */
internal fun prefixEnd(text: CharSequence, max: Int): Int {
    if (text.length <= max) return text.length
    return if (text[max - 1].isHighSurrogate()) max - 1 else max
}

/**
 * The characters a text limited to [maxLines] lines may need: [LINE_CHARS_MAX] per line,
 * or no bound when the lines are not limited.
 */
private fun charsForLines(maxLines: Int): Int =
    if (maxLines >= Int.MAX_VALUE / LINE_CHARS_MAX) Int.MAX_VALUE else maxLines * LINE_CHARS_MAX

/**
 * What a text is drawn as, so that no single laid-out text costs time the main thread cannot
 * spare. Laying out one text costs time in its length and in the square of its longest run
 * without whitespace, whatever its line limit: a 200 000-character string without spaces held
 * the main thread for seconds in a two-line row.
 *
 * - **Line limit set** ([maxLines] below `Int.MAX_VALUE`): one piece, the first
 *   `maxLines × LINE_CHARS_MAX` characters. No line holds that many, so what is drawn —
 *   ellipsis included — stays as it was.
 * - **No line limit:** the first [cap] characters (all when [cap] is `null`), cut where one
 *   unbroken run passes 2 000 characters ([splitAtUnbrokenRuns]); [ShownText.omitted]
 *   counts the rest.
 *
 * Text that needs neither comes back whole in one piece.
 *
 * @param text the text to draw.
 * @param maxLines the text's line limit; `Int.MAX_VALUE` for none.
 * @param cap the most characters to show without a line limit; `null` to show every one.
 * @return the pieces to draw and the count left out.
 */
internal fun shownText(text: String, maxLines: Int, cap: Int?): ShownText<String> {
    if (maxLines != Int.MAX_VALUE) {
        return ShownText(listOf(text.substring(0, prefixEnd(text, charsForLines(maxLines)))), omitted = 0)
    }
    val end = if (cap == null) text.length else prefixEnd(text, cap)
    return ShownText(text.substring(0, end).splitAtUnbrokenRuns(), omitted = text.length - end)
}

/**
 * [shownText] for a styled text: each piece keeps its part of every style, link and
 * inline-content placeholder.
 *
 * @param text the text to draw.
 * @param maxLines the text's line limit; `Int.MAX_VALUE` for none.
 * @param cap the most characters to show without a line limit; `null` to show every one.
 * @return the pieces to draw and the count left out.
 */
internal fun shownText(text: AnnotatedString, maxLines: Int, cap: Int?): ShownText<AnnotatedString> {
    if (maxLines != Int.MAX_VALUE) {
        return ShownText(listOf(text.subSequence(0, prefixEnd(text, charsForLines(maxLines)))), omitted = 0)
    }
    val end = if (cap == null) text.length else prefixEnd(text, cap)
    val head = if (end == text.length) text else text.subSequence(0, end)
    return ShownText(head.splitAtUnbrokenRuns(), omitted = text.length - end)
}
