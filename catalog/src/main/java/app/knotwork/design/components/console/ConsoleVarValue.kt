package app.knotwork.design.components.console

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import app.knotwork.design.R
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkPalette
import app.knotwork.design.tokens.KnotworkTextStyles
import java.text.NumberFormat

/** Lines of a Vars value its row shows; the row's menu copies the whole value. */
private const val VAR_PREVIEW_MAX_LINES = 3

/**
 * Characters of a Vars value handed to its row's `Text`. Laying out a string costs time in
 * its whole length however few lines are drawn — quadratic when it has no spaces, so a
 * 200 000-character value held the main thread for seconds — and [VAR_PREVIEW_MAX_LINES]
 * alone bounds nothing. 400 still fills more than three lines at the console's widest:
 * the sheet stops at Material's 640 dp.
 */
internal const val VAR_PREVIEW_MAX_CHARS = 400

/** What a line break in a Vars preview is drawn as, so three lines hold text, not blank lines. */
private const val LINE_BREAK_MARK = " ↵ "

/** Opacity of the size line and of an empty value's `""`, relative to `consoleFg`. */
private const val VAR_DIM_ALPHA = 0.66f

/** Opacity of a line-break mark in a preview, relative to `consoleFg`. */
private const val VAR_MARK_ALPHA = 0.42f

/**
 * The preview of a Vars value: its first [VAR_PREVIEW_MAX_CHARS] characters, with every
 * line break — `\r\n`, `\r` or `\n` — drawn as one [LINE_BREAK_MARK] in [markStyle] and
 * every tab as one space.
 *
 * The value is cut before it is mapped, so both the work and the result stay bounded
 * however long the value is: at most three characters per character kept. The cut never
 * ends on the first half of a surrogate pair.
 *
 * @param value the value as the node had it.
 * @param markStyle style of the line-break marks; the rest takes the `Text`'s own style.
 * @return the string handed to the row's `Text`.
 */
internal fun consoleVarPreview(value: String, markStyle: SpanStyle): AnnotatedString {
    var end = minOf(value.length, VAR_PREVIEW_MAX_CHARS)
    if (end < value.length && value[end - 1].isHighSurrogate()) end--
    return buildAnnotatedString {
        var i = 0
        while (i < end) {
            val c = value[i]
            when (c) {
                '\r', '\n' -> {
                    withStyle(markStyle) { append(LINE_BREAK_MARK) }
                    if (c == '\r' && i + 1 < value.length && value[i + 1] == '\n') i++
                }
                '\t' -> append(' ')
                else -> append(c)
            }
            i++
        }
    }
}

/**
 * The key and value of one Vars row: the key, then a preview of the value in at most
 * [VAR_PREVIEW_MAX_LINES] lines.
 *
 * A value longer than [VAR_PREVIEW_MAX_CHARS], or one whose preview overflows its lines,
 * is *cut*: a line under it gives the value's size, and TalkBack reads the key and that
 * size instead of the preview. An empty value shows `""`, and TalkBack says so. The row's
 * long-press menu copies the whole value either way.
 *
 * @param row the row to draw.
 * @param modifier layout modifier of the column.
 */
@Composable
internal fun ConsoleVarValue(row: ConsoleVarRow, modifier: Modifier = Modifier) {
    val fg = KnotworkTheme.extended.consoleFg
    val dim = fg.copy(alpha = VAR_DIM_ALPHA)
    val markColor = fg.copy(alpha = VAR_MARK_ALPHA)
    val preview = remember(row.value, markColor) { consoleVarPreview(row.value, SpanStyle(color = markColor)) }
    var overflows by remember(row.value) { mutableStateOf(false) }
    val empty = row.value.isEmpty()
    val cut = row.value.length > VAR_PREVIEW_MAX_CHARS || overflows
    val size = if (cut) {
        val count = row.value.length
        pluralStringResource(
            R.plurals.knotwork_console_value_n_chars,
            count,
            NumberFormat.getIntegerInstance().format(count),
        )
    } else {
        null
    }
    val emptyLabel = stringResource(R.string.knotwork_console_value_a11y_empty)
    val description = when {
        size != null -> "${row.key}, $size"
        empty -> "${row.key}, $emptyLabel"
        else -> null
    }
    Column(
        modifier = if (description != null) {
            modifier.clearAndSetSemantics { contentDescription = description }
        } else {
            modifier
        },
    ) {
        Text(
            text = row.key,
            style = KnotworkTextStyles.MonoBase,
            color = KnotworkPalette.Accent400,
        )
        if (empty) {
            Text(text = "\"\"", style = KnotworkTextStyles.MonoBase, color = dim)
        } else {
            Text(
                text = preview,
                style = KnotworkTextStyles.MonoBase,
                color = fg,
                maxLines = VAR_PREVIEW_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { overflows = it.hasVisualOverflow },
            )
        }
        if (size != null) {
            Text(text = size, style = KnotworkTextStyles.MonoSm, color = dim)
        }
    }
}
