package app.knotwork.design.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.AnnotatorSettings
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.LocalTableRowIndex
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode

/** Vertical space around a code block, as the library's own code block draws it. */
private val CodeBlockVerticalPadding = 8.dp

/**
 * The library's Material 3 components, with paragraphs and code blocks that never hand a
 * single text more than [UNBROKEN_RUN_MAX] characters of one unbroken run.
 *
 * Laying out a text costs time in the square of its longest run without whitespace
 * ([unbrokenRunCuts]): an agent answer that is one long minified JSON or base64 string
 * froze the chat for seconds in a single frame. A paragraph or code block whose source
 * holds no such run renders exactly as the library renders it. One that does:
 *
 * - **Paragraph** — its text is built as `MarkdownParagraph` builds it, then cut at
 *   [unbrokenRunCuts] into consecutive texts in a column. Each piece keeps its part of
 *   every style and link. Where a piece ends inside a run its last line can end short.
 * - **Code block** — the code scrolls sideways and does not wrap, so a run longer than the
 *   maximum continues on a new line at each cut.
 *
 * What a message copies is its source, not what is drawn, so the cuts never reach the
 * clipboard.
 *
 * A **table**'s cells wrap ([KnotworkTableHeader], [KnotworkTableRow]) instead of ending
 * in an ellipsis on one line.
 *
 * Usage: `Markdown(content, typography = …, colors = …, components = knotworkMarkdownComponents())`.
 *
 * @return the components to pass to `Markdown`.
 */
fun knotworkMarkdownComponents(): MarkdownComponents = markdownComponents(
    // The Material 3 `Markdown` passes its own checkbox; keep it.
    checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
    paragraph = { model ->
        if (model.hasLongRun()) {
            PiecedParagraph(model)
        } else {
            MarkdownParagraph(model.content, model.node, style = model.typography.paragraph)
        }
    },
    codeFence = { model ->
        if (model.hasLongRun()) {
            MarkdownCodeFence(model.content, model.node, style = model.typography.code, block = PiecedCodeBlock)
        } else {
            MarkdownCodeFence(model.content, model.node, style = model.typography.code)
        }
    },
    codeBlock = { model ->
        if (model.hasLongRun()) {
            MarkdownCodeBlock(model.content, model.node, style = model.typography.code, block = PiecedCodeBlock)
        } else {
            MarkdownCodeBlock(model.content, model.node, style = model.typography.code)
        }
    },
    table = { model ->
        MarkdownTable(
            content = model.content,
            node = model.node,
            style = model.typography.table,
            headerBlock = { content, header, tableWidth, style ->
                KnotworkTableHeader(content, header, tableWidth, style)
            },
            rowBlock = { content, row, tableWidth, style -> KnotworkTableRow(content, row, tableWidth, style) },
        )
    },
)

/**
 * A table's header row as the library draws it, except that its cells wrap. The library
 * draws each cell on one line ending in an ellipsis, which cut every cell longer than its
 * column, so the rest of it could be read only in a copy. The cells sit at the top of the
 * row, so a row's texts start level whatever their lengths.
 *
 * The chat's bubbles draw their tables with it, and so does the message reader.
 *
 * @param content the Markdown source the header's node indexes into.
 * @param header the table's header node.
 * @param tableWidth the table's width: its column count times the cell width.
 * @param style the table's text style; the header sets it bold.
 */
@Composable
internal fun KnotworkTableHeader(content: String, header: ASTNode, tableWidth: Dp, style: TextStyle) {
    MarkdownTableHeader(
        content = content,
        header = header,
        tableWidth = tableWidth,
        style = style,
        verticalAlignment = Alignment.Top,
        maxLines = Int.MAX_VALUE,
        overflow = TextOverflow.Clip,
    )
}

/**
 * A table's body row as the library draws it, except that its cells wrap and sit at the
 * top of the row, as in [KnotworkTableHeader].
 *
 * @param content the Markdown source the row's node indexes into.
 * @param row the row's node.
 * @param tableWidth the table's width: its column count times the cell width.
 * @param style the table's text style.
 * @param rowIndex the row's index for TalkBack, the header being row 0.
 */
@Composable
internal fun KnotworkTableRow(
    content: String,
    row: ASTNode,
    tableWidth: Dp,
    style: TextStyle,
    rowIndex: Int = LocalTableRowIndex.current,
) {
    MarkdownTableRow(
        content = content,
        header = row,
        tableWidth = tableWidth,
        style = style,
        rowIndex = rowIndex,
        verticalAlignment = Alignment.Top,
        maxLines = Int.MAX_VALUE,
        overflow = TextOverflow.Clip,
    )
}

/**
 * Whether the element's source holds a run longer than [UNBROKEN_RUN_MAX]. Markup is
 * only ever removed on the way to the drawn text, so a drawn run is never longer than
 * the source run it comes from.
 */
private fun MarkdownComponentModel.hasLongRun(): Boolean = unbrokenRunCuts(node.getTextInNode(content)).isNotEmpty()

/** A paragraph drawn as consecutive texts cut at [unbrokenRunCuts]. */
@Composable
private fun PiecedParagraph(model: MarkdownComponentModel) {
    val style = model.typography.paragraph
    val styled = markdownParagraphText(model.content, model.node, style, annotatorSettings())
    val pieces = remember(styled) { styled.splitAtUnbrokenRuns() }
    Column {
        for (piece in pieces) {
            MarkdownText(content = piece, node = model.node, style = style, sourceContent = model.content)
        }
    }
}

/**
 * A paragraph's text, styled and linked as the library's `MarkdownParagraph` builds it.
 * Builds no composition state, so it can run off the main thread.
 *
 * @param content the Markdown source the paragraph's node indexes into.
 * @param node the paragraph's node.
 * @param style the paragraph's text style.
 * @param settings link and inline-code styles, read in composition beforehand.
 * @return the paragraph's whole text.
 */
internal fun markdownParagraphText(
    content: String,
    node: ASTNode,
    style: TextStyle,
    settings: AnnotatorSettings,
): AnnotatedString = buildAnnotatedString {
    pushStyle(style.toSpanStyle())
    buildMarkdownAnnotatedString(content = content, node = node, annotatorSettings = settings)
    pop()
}

/**
 * The library's code block — background, padding, sideways scroll — with a line break at
 * every [unbrokenRunCuts] of the code.
 */
private val PiecedCodeBlock: @Composable (String, String?, TextStyle) -> Unit = { code, language, style ->
    MarkdownCodeBackground(
        color = LocalMarkdownColors.current.codeBackground,
        shape = RoundedCornerShape(LocalMarkdownDimens.current.codeBackgroundCornerSize),
        modifier = Modifier.fillMaxWidth().padding(vertical = CodeBlockVerticalPadding),
        language = language,
        code = code,
    ) {
        val lines = remember(code) { AnnotatedString(code.splitAtUnbrokenRuns().joinToString("\n")) }
        MarkdownBasicText(
            text = lines,
            style = style,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(LocalMarkdownPadding.current.codeBlock),
        )
    }
}
