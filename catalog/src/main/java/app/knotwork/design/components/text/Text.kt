package app.knotwork.design.components.text

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import app.knotwork.design.R
import java.text.NumberFormat
import androidx.compose.material3.Text as MaterialText

/**
 * The app's text: Material 3's `Text`, with the same parameters, that never lays out more
 * than the main thread can afford however long the string it is given.
 *
 * Laying out one text costs time in its length and in the square of its longest run
 * without whitespace, and neither a line limit nor an ellipsis bounds that: a tool output
 * or model answer of 200 000 characters without spaces held the main thread for seconds in
 * a two-line row. Every text in the app and the catalog goes through here
 * (`BoundedTextGuardTest` refuses Material's own), so a new screen showing such a string
 * is bounded without deciding anything ([shownText]):
 *
 * - **[maxLines] set** — the text is handed only as many characters as its lines could ever
 *   hold; what is drawn, ellipsis included, does not change.
 * - **No line limit** — a run without whitespace longer than 2 000 characters continues in
 *   the next text of a column, and a text longer than 10 000 characters shows its first
 *   10 000 and a line saying how many more there are.
 *
 * Text that is neither is drawn exactly as Material draws it, in one text. A screen that
 * must show every character of a long text uses [WholeText] instead.
 *
 * When the text is drawn in pieces, [modifier] goes to the column holding them, each piece
 * takes the column's width, [minLines] applies to the first piece and [onTextLayout] gets
 * the last piece's layout.
 *
 * @param text the text to show.
 * @param modifier layout modifier.
 * @param color text colour; `Unspecified` takes the style's, then the content colour.
 * @param fontSize font size; `Unspecified` takes the style's.
 * @param fontStyle font style (italic); `null` takes the style's.
 * @param fontWeight font weight; `null` takes the style's.
 * @param fontFamily font family; `null` takes the style's.
 * @param letterSpacing letter spacing; `Unspecified` takes the style's.
 * @param textDecoration underline or strike-through; `null` takes the style's.
 * @param textAlign alignment within the lines; `null` takes the style's.
 * @param lineHeight line height; `Unspecified` takes the style's.
 * @param overflow how the end of a text cut by its line limit is drawn.
 * @param softWrap whether lines break at the width; `false` lays each line out unbounded.
 * @param maxLines the most lines drawn; `Int.MAX_VALUE` for no limit.
 * @param minLines the fewest lines of height the text takes.
 * @param onTextLayout receives the layout after each layout pass.
 * @param style the text style; defaults to the local one.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    val shown = remember(text, maxLines) { shownText(text, maxLines, cap = TEXT_MAX_CHARS) }
    val looks = TextLooks(
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        style = style,
    )
    ShownTexts(shown, modifier, looks, minLines) { piece, spec ->
        MaterialText(
            text = piece,
            modifier = spec.modifier,
            color = looks.color,
            fontSize = looks.fontSize,
            fontStyle = looks.fontStyle,
            fontWeight = looks.fontWeight,
            fontFamily = looks.fontFamily,
            letterSpacing = looks.letterSpacing,
            textDecoration = looks.textDecoration,
            textAlign = looks.textAlign,
            lineHeight = looks.lineHeight,
            overflow = looks.overflow,
            softWrap = looks.softWrap,
            maxLines = looks.maxLines,
            minLines = spec.minLines,
            onTextLayout = if (spec.reportsLayout) onTextLayout else null,
            style = spec.style,
        )
    }
}

/**
 * [Text] for a styled text. Each piece keeps its part of every style, link and
 * inline-content placeholder, and [inlineContent] goes to every piece.
 *
 * @param text the styled text to show.
 * @param modifier layout modifier.
 * @param color text colour; `Unspecified` takes the style's, then the content colour.
 * @param fontSize font size; `Unspecified` takes the style's.
 * @param fontStyle font style (italic); `null` takes the style's.
 * @param fontWeight font weight; `null` takes the style's.
 * @param fontFamily font family; `null` takes the style's.
 * @param letterSpacing letter spacing; `Unspecified` takes the style's.
 * @param textDecoration underline or strike-through; `null` takes the style's.
 * @param textAlign alignment within the lines; `null` takes the style's.
 * @param lineHeight line height; `Unspecified` takes the style's.
 * @param overflow how the end of a text cut by its line limit is drawn.
 * @param softWrap whether lines break at the width; `false` lays each line out unbounded.
 * @param maxLines the most lines drawn; `Int.MAX_VALUE` for no limit.
 * @param minLines the fewest lines of height the text takes.
 * @param inlineContent composables standing in for the text's inline-content placeholders.
 * @param onTextLayout receives the layout after each layout pass.
 * @param style the text style; defaults to the local one.
 */
@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) {
    val shown = remember(text, maxLines) { shownText(text, maxLines, cap = TEXT_MAX_CHARS) }
    val looks = TextLooks(
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        style = style,
    )
    ShownTexts(shown, modifier, looks, minLines) { piece, spec ->
        StyledPiece(piece, spec, looks, inlineContent, if (spec.reportsLayout) onTextLayout else { _ -> })
    }
}

/**
 * Every character of a text, however long — for the few screens whose point is to show a
 * text whole, where [Text] would stop at 10 000 characters: the approval card's arguments
 * (the user approves exactly what will run), a model's question, a memory entry, a file
 * preview, a rendered prompt, the message reader. A run without whitespace longer than
 * 2 000 characters still continues in the next text of a column, so the layout grows only
 * with the length.
 *
 * Every use is named in `BoundedTextGuardTest` with the reason it needs the whole text.
 *
 * @param text the text to show.
 * @param modifier layout modifier of the text, or of the column holding its pieces.
 * @param color text colour; `Unspecified` takes the style's, then the content colour.
 * @param fontWeight font weight; `null` takes the style's.
 * @param textAlign alignment within the lines; `null` takes the style's.
 * @param style the text style; defaults to the local one.
 */
@Composable
fun WholeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    val shown = remember(text) { shownText(text, Int.MAX_VALUE, cap = null) }
    val looks = TextLooks(color = color, fontWeight = fontWeight, textAlign = textAlign, style = style)
    ShownTexts(shown, modifier, looks, minLines = 1) { piece, spec ->
        MaterialText(
            text = piece,
            modifier = spec.modifier,
            color = looks.color,
            fontWeight = looks.fontWeight,
            textAlign = looks.textAlign,
            style = spec.style,
        )
    }
}

/**
 * [WholeText] for a styled text; each piece keeps its part of every style, link and
 * inline-content placeholder, and [inlineContent] goes to every piece.
 *
 * @param text the styled text to show.
 * @param modifier layout modifier of the text, or of the column holding its pieces.
 * @param color text colour; `Unspecified` takes the style's, then the content colour.
 * @param inlineContent composables standing in for the text's inline-content placeholders.
 * @param style the text style; defaults to the local one.
 */
@Composable
fun WholeText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    style: TextStyle = LocalTextStyle.current,
) {
    val shown = remember(text) { shownText(text, Int.MAX_VALUE, cap = null) }
    val looks = TextLooks(color = color, style = style)
    ShownTexts(shown, modifier, looks, minLines = 1) { piece, spec ->
        StyledPiece(piece, spec, looks, inlineContent) { }
    }
}

/** Everything about a text's look but its characters, passed on to each piece unchanged. */
private class TextLooks(
    val color: Color = Color.Unspecified,
    val fontSize: TextUnit = TextUnit.Unspecified,
    val fontStyle: FontStyle? = null,
    val fontWeight: FontWeight? = null,
    val fontFamily: FontFamily? = null,
    val letterSpacing: TextUnit = TextUnit.Unspecified,
    val textDecoration: TextDecoration? = null,
    val textAlign: TextAlign? = null,
    val lineHeight: TextUnit = TextUnit.Unspecified,
    val overflow: TextOverflow = TextOverflow.Clip,
    val softWrap: Boolean = true,
    val maxLines: Int = Int.MAX_VALUE,
    val style: TextStyle,
)

/** One styled piece drawn by Material with [looks], placed as [spec] says. */
@Composable
private fun StyledPiece(
    piece: AnnotatedString,
    spec: PieceSpec,
    looks: TextLooks,
    inlineContent: Map<String, InlineTextContent>,
    onTextLayout: (TextLayoutResult) -> Unit,
) {
    MaterialText(
        text = piece,
        modifier = spec.modifier,
        color = looks.color,
        fontSize = looks.fontSize,
        fontStyle = looks.fontStyle,
        fontWeight = looks.fontWeight,
        fontFamily = looks.fontFamily,
        letterSpacing = looks.letterSpacing,
        textDecoration = looks.textDecoration,
        textAlign = looks.textAlign,
        lineHeight = looks.lineHeight,
        overflow = looks.overflow,
        softWrap = looks.softWrap,
        maxLines = looks.maxLines,
        minLines = spec.minLines,
        inlineContent = inlineContent,
        onTextLayout = onTextLayout,
        style = spec.style,
    )
}

/**
 * How one piece of a text is drawn: [modifier], [minLines], whether it reports its layout to
 * the caller ([reportsLayout]), and its [style] — the text's own, with the line-height trim
 * that piece's place in the column calls for ([stackedStyle]).
 */
private class PieceSpec(val modifier: Modifier, val minLines: Int, val reportsLayout: Boolean, val style: TextStyle)

/**
 * [this] style for item [index] of [count] texts stacked in a column as one text: the
 * line-height space the whole text would trim at its top stays trimmed above the first item
 * only, the space it would trim at its bottom below the last only, and none is trimmed where
 * two items meet — so the seam is as tall as any other line gap.
 */
private fun TextStyle.stackedStyle(index: Int, count: Int): TextStyle {
    val own = lineHeightStyle ?: LineHeightStyle.Default
    val trimsTop = own.trim == LineHeightStyle.Trim.Both || own.trim == LineHeightStyle.Trim.FirstLineTop
    val trimsBottom = own.trim == LineHeightStyle.Trim.Both || own.trim == LineHeightStyle.Trim.LastLineBottom
    val top = trimsTop && index == 0
    val bottom = trimsBottom && index == count - 1
    val trim = when {
        top && bottom -> LineHeightStyle.Trim.Both
        top -> LineHeightStyle.Trim.FirstLineTop
        bottom -> LineHeightStyle.Trim.LastLineBottom
        else -> LineHeightStyle.Trim.None
    }
    return copy(lineHeightStyle = own.copy(trim = trim))
}

/**
 * Draws [shown]: its one piece as one text with [modifier], or its pieces in a column with
 * [modifier], each the column's width, then the count of characters left out. The texts of
 * a column meet with a full line gap ([stackedStyle]).
 *
 * @param piece draws one piece as its [PieceSpec] says.
 */
@Composable
private fun <T : CharSequence> ShownTexts(
    shown: ShownText<T>,
    modifier: Modifier,
    looks: TextLooks,
    minLines: Int,
    piece: @Composable (T, PieceSpec) -> Unit,
) {
    if (shown.pieces.size == 1 && shown.omitted == 0) {
        piece(shown.pieces.single(), PieceSpec(modifier, minLines, reportsLayout = true, style = looks.style))
        return
    }
    val count = shown.pieces.size + if (shown.omitted > 0) 1 else 0
    Column(modifier) {
        shown.pieces.forEachIndexed { index, text ->
            piece(
                text,
                PieceSpec(
                    modifier = Modifier.fillMaxWidth(),
                    minLines = if (index == 0) minLines else 1,
                    reportsLayout = index == shown.pieces.lastIndex,
                    style = looks.style.stackedStyle(index, count),
                ),
            )
        }
        if (shown.omitted > 0) {
            MaterialText(
                text = pluralStringResource(
                    R.plurals.knotwork_text_n_more_chars,
                    shown.omitted,
                    NumberFormat.getIntegerInstance().format(shown.omitted),
                ),
                modifier = Modifier.fillMaxWidth(),
                color = looks.color,
                fontSize = looks.fontSize,
                fontWeight = looks.fontWeight,
                fontFamily = looks.fontFamily,
                textAlign = looks.textAlign,
                lineHeight = looks.lineHeight,
                style = looks.style.stackedStyle(count - 1, count),
            )
        }
    }
}
