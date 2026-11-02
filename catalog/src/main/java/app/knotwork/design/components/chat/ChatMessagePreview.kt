package app.knotwork.design.components.chat

import androidx.compose.runtime.Immutable
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import java.text.BreakIterator

/** A message longer than this many characters is shown in its bubble as a [ChatMessagePreview]. */
const val CHAT_PREVIEW_THRESHOLD: Int = 6_000

/** Most characters of the source a preview shows. */
internal const val PREVIEW_MAX_CHARS = 4_000

/** Fewest characters of the source a preview made of whole blocks shows; below it the next block is cut inside. */
internal const val PREVIEW_MIN_CHARS = 2_000

/** Characters of the source parsed to find a preview's blocks: twice what a preview shows at most. */
private const val PREVIEW_PARSE_CHARS = 2 * PREVIEW_MAX_CHARS

/** Percent in one whole, for [ChatMessagePreview.shownPercent]. */
private const val PERCENT = 100L

/**
 * The beginning of a long message, which its bubble shows instead of the whole, with a
 * way into the full-screen reader ([ChatMessageReader]).
 *
 * Built by [chatMessagePreview] off the main thread, once per message; the bubble only
 * draws it.
 *
 * @property text the source up to where the preview ends, trailing whitespace removed —
 *   Markdown for a Markdown message, plain text otherwise.
 * @property shownPercent where the preview ends as a share of the source's length, in
 *   whole percent rounded down: 0 means less than 1 %. Never above 66, since a preview
 *   shows at most 4 000 of more than 6 000 characters.
 */
@Immutable
data class ChatMessagePreview(val text: String, val shownPercent: Int)

/**
 * The preview of a message longer than [CHAT_PREVIEW_THRESHOLD] characters; `null` for a
 * shorter one, which its bubble shows whole.
 *
 * The preview is the message's first blocks, whole and in order, while they end within
 * [PREVIEW_MAX_CHARS] characters. A block is a paragraph, a heading, a list item or a
 * quote of a Markdown message; inside a code block, each line; inside a table, each row;
 * of a plain-text message, each line. A heading is never the preview's last block: the
 * preview stops before it.
 *
 * When whole blocks end before [PREVIEW_MIN_CHARS] — the first block alone may be longer
 * than the preview — the next block is cut inside, as near [PREVIEW_MAX_CHARS] as it
 * goes: before its last whitespace between [PREVIEW_MIN_CHARS] and [PREVIEW_MAX_CHARS],
 * or, with none, at [PREVIEW_MAX_CHARS] itself, on a grapheme boundary, so no emoji or
 * surrogate pair is split. So a preview shows between [PREVIEW_MIN_CHARS] and
 * [PREVIEW_MAX_CHARS] characters of the source, and at least 2 000 are always hidden.
 *
 * The blocks are found on the parse of the first [PREVIEW_PARSE_CHARS] characters, not
 * the whole message. Where the preview ends between the lines of a code block, its fence
 * is left open: the bubble draws the code to its end. A cut inside a paragraph can leave
 * an emphasis or a link opened before it without its close; the renderer then shows
 * that markup as written.
 *
 * Costs the parse of [PREVIEW_PARSE_CHARS] characters at most, whatever the message's
 * length; meant to run off the main thread.
 *
 * @param source the whole message.
 * @param markdown whether [source] is Markdown; plain text otherwise.
 * @return the preview, or `null` when the message is shown whole.
 */
fun chatMessagePreview(source: String, markdown: Boolean): ChatMessagePreview? {
    if (source.length <= CHAT_PREVIEW_THRESHOLD) return null
    val ends = if (markdown) markdownBlockEnds(source.take(PREVIEW_PARSE_CHARS)) else plainLineEnds(source)
    val end = previewEnd(source, ends ?: plainLineEnds(source))
    return ChatMessagePreview(
        text = source.substring(0, end).trimEnd(),
        shownPercent = (end * PERCENT / source.length).toInt(),
    )
}

/**
 * Where a block ends in the source, and whether it is a heading.
 *
 * @property end the offset just past the block.
 * @property heading whether the block is a heading, which never ends a preview.
 */
private class BlockEnd(val end: Int, val heading: Boolean = false)

/** Where a preview of [source] ends, given where its blocks end. */
private fun previewEnd(source: String, ends: List<BlockEnd>): Int {
    val fitting = ends.filter { it.end <= PREVIEW_MAX_CHARS }
    // The last whole block that fits; a heading after it would end the preview, so it
    // stops before the heading.
    val whole = fitting.filterNot { it.heading }.maxOfOrNull { it.end } ?: 0
    if (whole >= PREVIEW_MIN_CHARS) return whole
    // Too short: cut inside the first block that does not fit, after every block that
    // does — a heading included, which is then not the preview's last block.
    var next = fitting.maxOfOrNull { it.end } ?: 0
    while (next < PREVIEW_MAX_CHARS && Character.isWhitespace(source[next])) next++
    // Not so early that the preview would show less than its minimum.
    for (cut in PREVIEW_MAX_CHARS downTo maxOf(next + 1, PREVIEW_MIN_CHARS)) {
        if (Character.isWhitespace(source[cut])) return cut
    }
    return graphemeBoundaryAtOrBefore(source, PREVIEW_MAX_CHARS).takeIf { it > next } ?: PREVIEW_MAX_CHARS
}

/** The last grapheme boundary of [text] at or before [offset]. */
private fun graphemeBoundaryAtOrBefore(text: String, offset: Int): Int {
    val graphemes = BreakIterator.getCharacterInstance()
    // Only the neighbourhood of the offset matters, and the text may be megabytes long.
    val from = (offset - GRAPHEME_WINDOW).coerceAtLeast(0)
    graphemes.setText(text.substring(from, (offset + GRAPHEME_WINDOW).coerceAtMost(text.length)))
    val local = offset - from
    return from + if (graphemes.isBoundary(local)) local else graphemes.preceding(local)
}

/** Characters around a cut that are enough to find the grapheme it falls in. */
private const val GRAPHEME_WINDOW = 64

/**
 * The end of each line of a plain-text message that could end a preview. Reads no
 * further than the preview's longest, so it costs nothing on the main thread.
 */
private fun plainLineEnds(source: String): List<BlockEnd> {
    val ends = ArrayList<BlockEnd>()
    for (i in 0..minOf(PREVIEW_MAX_CHARS, source.length - 1)) {
        if (source[i] == '\n') ends += BlockEnd(i)
    }
    return ends
}

/**
 * The end of each block of a Markdown message's [head], in order; `null` if it does not
 * parse, and the message is then cut as plain text.
 */
private fun markdownBlockEnds(head: String): List<BlockEnd>? {
    val root = (parseMarkdown(head, lookupLinks = false) as? State.Success)?.node ?: return null
    val ends = ArrayList<BlockEnd>()
    for (node in root.children) {
        when (node.type) {
            MarkdownTokenTypes.EOL, MarkdownTokenTypes.WHITE_SPACE -> Unit
            MarkdownElementTypes.ORDERED_LIST, MarkdownElementTypes.UNORDERED_LIST ->
                node.endsOf(MarkdownElementTypes.LIST_ITEM).forEach { ends += BlockEnd(it) }
            MarkdownElementTypes.CODE_FENCE -> {
                node.endsOf(MarkdownTokenTypes.CODE_FENCE_CONTENT).forEach { ends += BlockEnd(it) }
                ends += BlockEnd(node.endOffset)
            }
            MarkdownElementTypes.CODE_BLOCK -> node.endsOf(MarkdownTokenTypes.CODE_LINE).forEach {
                ends += BlockEnd(it)
            }
            GFMElementTypes.TABLE -> {
                node.endsOf(GFMElementTypes.HEADER).forEach { ends += BlockEnd(it) }
                node.endsOf(GFMElementTypes.ROW).forEach { ends += BlockEnd(it) }
            }
            in HEADINGS -> ends += BlockEnd(node.endOffset, heading = true)
            else -> ends += BlockEnd(node.endOffset)
        }
    }
    return ends.sortedBy { it.end }
}

/** Where each child of [type] ends. */
private fun ASTNode.endsOf(type: IElementType): List<Int> = children.filter { it.type == type }.map { it.endOffset }

/** Every heading type, ATX and setext. */
private val HEADINGS = setOf(
    MarkdownElementTypes.ATX_1,
    MarkdownElementTypes.ATX_2,
    MarkdownElementTypes.ATX_3,
    MarkdownElementTypes.ATX_4,
    MarkdownElementTypes.ATX_5,
    MarkdownElementTypes.ATX_6,
    MarkdownElementTypes.SETEXT_1,
    MarkdownElementTypes.SETEXT_2,
)
