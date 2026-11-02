package app.knotwork.design.components.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import app.knotwork.design.components.UNBROKEN_RUN_MAX
import app.knotwork.design.components.markdownParagraphText
import app.knotwork.design.components.splitAtUnbrokenRuns
import app.knotwork.design.components.unbrokenRunCuts
import com.mikepenz.markdown.annotator.AnnotatorSettings
import com.mikepenz.markdown.model.State
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode

/** Most lines of code one item of the reader holds; a longer code block is drawn as several items. */
internal const val READER_CODE_CHUNK_LINES = 40

/**
 * Characters of a message the reader parses first, so that its beginning is drawn
 * before the whole message is parsed: twice the 4 000 characters a bubble's preview
 * shows at most.
 */
internal const val READER_HEAD_CHARS = 8_000

/**
 * One item of the message reader's list. No item hands one text more than
 * [UNBROKEN_RUN_MAX] characters without whitespace, and no code item more than
 * [READER_CODE_CHUNK_LINES] lines, so drawing one item costs a bounded time whatever
 * the message's length.
 *
 * Every key is a position in the message's source. The reader first lays out the
 * items of the message's head, then those of the whole; an item at the same place
 * keeps its key, so the list keeps its scroll position when the rest arrives.
 */
internal sealed interface ChatReaderItem {
    /** The item's key in the list. */
    val key: String

    /** What the list may reuse this item's composition for. */
    val contentType: Any

    /**
     * A top-level block of a Markdown message, drawn by the renderer as it is.
     *
     * @property node the block's node.
     */
    data class Block(val node: ASTNode) : ChatReaderItem {
        override val key: String get() = "b${node.startOffset}"
        override val contentType: Any get() = node.type
    }

    /**
     * One piece of a Markdown paragraph whose text holds a run longer than
     * [UNBROKEN_RUN_MAX], cut as the chat bubble cuts it.
     *
     * @property node the paragraph's node.
     * @property index the piece's place in the paragraph, from 0.
     * @property text the piece, styled and linked.
     */
    data class ParagraphPiece(val node: ASTNode, val index: Int, val text: AnnotatedString) : ChatReaderItem {
        override val key: String get() = "p${node.startOffset}:$index"
        override val contentType: Any get() = ParagraphPiece::class
    }

    /**
     * Up to [READER_CODE_CHUNK_LINES] lines of a code block longer than that. The
     * chunks of one block share one continuous surface: only the first has top
     * corners and padding, only the last bottom ones.
     *
     * @property blockStart where the code block starts in the source.
     * @property index the chunk's place in the block, from 0.
     * @property code the chunk's lines, joined by line breaks.
     * @property language the code's language as the fence names it, if it does.
     * @property last whether this is the block's last chunk.
     */
    data class CodeChunk(
        val blockStart: Int,
        val index: Int,
        val code: String,
        val language: String?,
        val last: Boolean,
    ) : ChatReaderItem {
        /** Whether this is the block's first chunk. */
        val first: Boolean get() = index == 0

        override val key: String get() = "c$blockStart:$index"
        override val contentType: Any get() = CodeChunk::class
    }

    /**
     * One line of a plain-text message, or a piece of a line with a run longer than
     * [UNBROKEN_RUN_MAX].
     *
     * @property start where the text starts in the source.
     * @property text the line or piece, without its line break.
     */
    data class PlainText(val start: Int, val text: String) : ChatReaderItem {
        override val key: String get() = "t$start"
        override val contentType: Any get() = PlainText::class
    }
}

/**
 * The head of [source] the reader parses first: its first [READER_HEAD_CHARS]
 * characters, one fewer where that would split a surrogate pair.
 *
 * @param source the whole message.
 * @return the head, or [source] itself when it is no longer.
 */
internal fun readerHead(source: String): String {
    if (source.length <= READER_HEAD_CHARS) return source
    val end = if (Character.isHighSurrogate(source[READER_HEAD_CHARS - 1])) READER_HEAD_CHARS - 1 else READER_HEAD_CHARS
    return source.substring(0, end)
}

/**
 * The items of a plain-text message: one per line, a line with a long run cut into
 * pieces ([splitAtUnbrokenRuns]). An empty line is an empty item, drawn one line high.
 * Linear in the text's length; meant to run off the main thread.
 *
 * @param text the message.
 * @return the items, in order.
 */
internal fun plainReaderItems(text: String): List<ChatReaderItem> {
    val items = ArrayList<ChatReaderItem>()
    var lineStart = 0
    while (lineStart <= text.length) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        var pieceStart = lineStart
        for (piece in text.substring(lineStart, lineEnd).splitAtUnbrokenRuns()) {
            items += ChatReaderItem.PlainText(pieceStart, piece)
            pieceStart += piece.length
        }
        lineStart = lineEnd + 1
    }
    return items
}

/**
 * The items of a parsed Markdown message: one per top-level block, except that
 *
 * - a paragraph with a run longer than [UNBROKEN_RUN_MAX] becomes its pieces, built
 *   as `MarkdownParagraph` builds its text and cut as the chat bubble cuts it;
 * - a code block of more than [READER_CODE_CHUNK_LINES] lines — counting each piece
 *   of a line with a long run as a line, as the bubble draws them — becomes chunks of
 *   that many lines.
 *
 * Every other block — a list, a quote, a table, and a short paragraph or code block —
 * is one item, drawn by the renderer as the bubble draws it. Linear in the message's
 * length; meant to run off the main thread.
 *
 * The parse of a message's [readerHead] leaves out its last block when the cut runs
 * into it, so the head ends on a whole block — unless that block is the head's only
 * one, as with one long paragraph, which is then drawn as far as the head goes.
 *
 * @param parsed the parsed message, or its head.
 * @param settings link and inline-code styles for paragraph pieces, read in
 *   composition beforehand.
 * @param paragraphStyle the paragraph text style.
 * @param head whether [parsed] is the parse of the message's head.
 * @return the items, in order.
 */
internal fun markdownReaderItems(
    parsed: State.Success,
    settings: AnnotatorSettings,
    paragraphStyle: TextStyle,
    head: Boolean = false,
): List<ChatReaderItem> {
    val content = parsed.content
    val items = ArrayList<ChatReaderItem>()
    for (node in if (head) wholeBlocksOf(parsed) else parsed.node.children) {
        when (node.type) {
            MarkdownElementTypes.PARAGRAPH -> items += paragraphItems(node, content, settings, paragraphStyle)
            MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK -> items += codeItems(node, content)
            else -> items += ChatReaderItem.Block(node)
        }
    }
    return items
}

/**
 * The top-level nodes of a head's parse, without a last block that reaches the end of the
 * head — the cut may have run through it — unless it is the only block.
 */
private fun wholeBlocksOf(head: State.Success): List<ASTNode> {
    val nodes = head.node.children
    val last = nodes.indexOfLast { it.type != MarkdownTokenTypes.EOL }
    return if (last > 0 && nodes[last].endOffset >= head.content.length) nodes.subList(0, last) else nodes
}

/** A paragraph as one block, or as its pieces when its source holds a long run. */
private fun paragraphItems(
    node: ASTNode,
    content: String,
    settings: AnnotatorSettings,
    style: TextStyle,
): List<ChatReaderItem> {
    // Markup is only ever removed on the way to the drawn text, so a drawn run is
    // never longer than the source run it comes from.
    if (unbrokenRunCuts(node.getTextInNode(content)).isEmpty()) return listOf(ChatReaderItem.Block(node))
    return markdownParagraphText(content, node, style, settings)
        .splitAtUnbrokenRuns()
        .mapIndexed { index, piece -> ChatReaderItem.ParagraphPiece(node, index, piece) }
}

/** A code block as one block, or as chunks of [READER_CODE_CHUNK_LINES] lines when it is longer. */
private fun codeItems(node: ASTNode, content: String): List<ChatReaderItem> {
    val code = codeOf(node, content) ?: return listOf(ChatReaderItem.Block(node))
    val lines = code.split('\n').flatMap { it.splitAtUnbrokenRuns() }
    if (lines.size <= READER_CODE_CHUNK_LINES) return listOf(ChatReaderItem.Block(node))
    val language = node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)?.getTextInNode(content)?.toString()
    val chunks = lines.chunked(READER_CODE_CHUNK_LINES)
    return chunks.mapIndexed { index, chunk ->
        ChatReaderItem.CodeChunk(
            blockStart = node.startOffset,
            index = index,
            code = chunk.joinToString("\n"),
            language = language,
            last = index == chunks.lastIndex,
        )
    }
}

/**
 * The code of a fenced or indented block, taken as the renderer's own code block takes
 * it (`MarkdownCodeFence` / `MarkdownCodeBlock`); `null` for a fence too short to hold
 * code, which the renderer skips.
 */
private fun codeOf(node: ASTNode, content: String): String? {
    val children = node.children
    if (node.type == MarkdownElementTypes.CODE_BLOCK) {
        if (children.isEmpty()) return null
        return content.substring(children.first().startOffset, children.last().endOffset).replaceIndent()
    }
    // CODE_FENCE_START, [FENCE_LANG,] EOL, content and EOLs, [CODE_FENCE_END].
    if (children.size < MIN_FENCE_CHILDREN) return null
    val language = node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)
    val minEnd = if (language != null && children.size > MIN_FENCE_CHILDREN) MIN_FENCE_CHILDREN else 2
    val end = children[(children.size - 2).coerceAtLeast(minEnd)].endOffset
    return content.substring(children[2].startOffset, end).replaceIndent()
}

/** Children of the shortest fence that holds code: its start, a line break and one line. */
private const val MIN_FENCE_CHILDREN = 3
