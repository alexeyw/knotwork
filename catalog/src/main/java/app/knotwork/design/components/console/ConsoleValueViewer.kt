package app.knotwork.design.components.console

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.knotwork.design.R
import app.knotwork.design.components.KnotworkFullScreenDialog
import app.knotwork.design.components.misc.KnotworkLoader
import app.knotwork.design.components.misc.KnotworkSnackbarHost
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkPalette
import app.knotwork.design.tokens.KnotworkTextStyles
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * How long formatting may take before the viewer says it is formatting; a shorter one
 * draws nothing, so nothing flashes.
 */
private const val LOADER_DELAY_MS = 300L

private const val CONTENT_CROSSFADE_MS = 150

/** Opacity of the bar's tint, of secondary text, and of punctuation, relative to `consoleFg`. */
private const val BAR_TINT_ALPHA = 0.06f
private const val DIM_ALPHA = 0.66f

/** Indent levels drawn at most; deeper lines keep the last level's indent. */
private const val INDENT_CAP = 6

/** Indent levels drawn at most at a large font scale, where a row holds few characters. */
private const val INDENT_CAP_LARGE_FONT = 3

/** Font scale from which [INDENT_CAP_LARGE_FONT] applies. */
private const val INDENT_CAP_FONT_SCALE = 1.5f

/** Characters of indent per level, as `JSON.stringify(value, null, 2)` writes it. */
private const val INDENT_CHARS_PER_LEVEL = 2

/** The body's padding above its first line and below its last. */
private val ListTopPadding = 12.dp
private val ListBottomPadding = 24.dp

/**
 * The whole of one Vars value, full screen over the console, on the console's tokens.
 *
 * A bar with Close, the value's key over its node, and Copy; a line naming how the
 * value was read and its size; then the value, one lazy item per line or piece of a
 * long line ([ConsoleValuePieces]). The body comes formatted from the host; while it
 * is not ready the header is already there, and after [LOADER_DELAY_MS] the body says
 * it is formatting. The viewer shows the row as it was when opened. A value more than
 * five screens tall gets a scroll thumb ([ConsoleValueScrollThumb]).
 *
 * It opens, closes and follows a predictive back gesture as a [KnotworkFullScreenDialog]
 * does, over the console.
 *
 * Copy hands the row to [onCopy]; when the host reports that the value did not fit
 * the clipboard, the viewer says so in its own snackbar.
 *
 * @param state the row the viewer was opened on and its body.
 * @param onClose called once the closing motion has ended.
 * @param onCopy copies the row's whole value; returns `false` when it was too large
 *   for the clipboard and nothing was copied.
 */
@Composable
fun ConsoleValueViewer(state: ConsoleValueViewerState, onClose: () -> Unit, onCopy: (ConsoleVarRow) -> Boolean) {
    // The console is always dark, so the bars draw light icons over it.
    KnotworkFullScreenDialog(onClose = onClose, darkBackground = true) { close, modifier ->
        ViewerSurface(state = state, onClose = close, onCopy = onCopy, modifier = modifier)
    }
}

/** The viewer's opaque surface: bar, meta line and body, with the snackbar host over its foot. */
@Composable
private fun ViewerSurface(
    state: ConsoleValueViewerState,
    onClose: () -> Unit,
    onCopy: (ConsoleVarRow) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val tooLong = stringResource(R.string.knotwork_copy_too_long)
    val paneTitle = "${state.row.key}, ${state.row.node}"
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(KnotworkTheme.extended.consoleBg)
            .semantics { this.paneTitle = paneTitle },
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            ViewerBar(
                row = state.row,
                onClose = onClose,
                onCopy = {
                    if (!onCopy(state.row)) scope.launch { snackbarHostState.showSnackbar(tooLong) }
                },
            )
            MetaLine(state)
            val reduced = KnotworkTheme.a11y.reducedMotion()
            Crossfade(
                targetState = state.body,
                animationSpec = if (reduced) snap() else tween(CONTENT_CROSSFADE_MS),
                label = "valueBody",
            ) { body ->
                when (body) {
                    ConsoleValueBody.Formatting -> FormattingNotice()
                    is ConsoleValueBody.Ready -> ValueLines(body.lines)
                }
            }
        }
        KnotworkSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }
}

/** Close, the key over its node, and Copy. */
@Composable
private fun ViewerBar(row: ConsoleVarRow, onClose: () -> Unit, onCopy: () -> Unit) {
    val fg = KnotworkTheme.extended.consoleFg
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(fg.copy(alpha = BAR_TINT_ALPHA))
            .padding(4.dp),
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = AppIcons.X,
                contentDescription = stringResource(R.string.knotwork_console_value_close),
                tint = fg,
            )
        }
        Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
            Text(
                text = row.key,
                style = KnotworkTextStyles.MonoBase.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                color = KnotworkPalette.Accent400,
            )
            Text(text = row.node, style = KnotworkTextStyles.MonoSm, color = fg.copy(alpha = DIM_ALPHA))
        }
        IconButton(onClick = onCopy) {
            Icon(
                imageVector = AppIcons.Copy,
                contentDescription = stringResource(R.string.knotwork_console_var_copy),
                tint = fg,
            )
        }
    }
}

/** How the value was read and its size, between two hairlines; only the size while it is formatting. */
@Composable
private fun MetaLine(state: ConsoleValueViewerState) {
    val numbers = NumberFormat.getIntegerInstance()
    val length = state.row.value.length
    val chars = pluralStringResource(R.plurals.knotwork_console_value_n_chars, length, numbers.format(length))
    val text = when (val body = state.body) {
        ConsoleValueBody.Formatting -> chars
        is ConsoleValueBody.Ready -> {
            val count = body.lines.size
            val lines = pluralStringResource(R.plurals.knotwork_console_value_n_lines, count, numbers.format(count))
            when (body.format) {
                ConsoleValueFormat.Json -> stringResource(R.string.knotwork_console_value_meta_json, chars)
                ConsoleValueFormat.Text -> stringResource(R.string.knotwork_console_value_meta_text, chars, lines)
                ConsoleValueFormat.NotValidJson -> stringResource(
                    R.string.knotwork_console_value_meta_bad_json,
                    chars,
                    lines,
                )
            }
        }
    }
    val hairline = KnotworkTheme.extended.consoleFg.copy(alpha = BAR_TINT_ALPHA * 2)
    HorizontalDivider(color = hairline)
    Text(
        text = text,
        style = KnotworkTextStyles.MonoSm,
        color = KnotworkTheme.extended.consoleFg.copy(alpha = DIM_ALPHA),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
    HorizontalDivider(color = hairline)
}

/** Nothing for [LOADER_DELAY_MS], then *Formatting…* with the loader, said once to TalkBack. */
@Composable
private fun FormattingNotice() {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOADER_DELAY_MS)
        show = true
    }
    if (!show) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier.padding(16.dp),
    ) {
        KnotworkLoader()
        Text(
            text = stringResource(R.string.knotwork_console_value_loading),
            style = KnotworkTextStyles.MonoBase,
            color = KnotworkTheme.extended.consoleFg.copy(alpha = DIM_ALPHA),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** The value's lines, one lazy item per line or per piece of a long one, each padded by its indent. */
@Composable
private fun ValueLines(lines: List<ConsoleValueLine>) {
    val style = KnotworkTextStyles.MonoBase
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charPx = remember(measurer, style, density) { measurer.measure("0", style).size.width.coerceAtLeast(1) }
    val indentCap = if (density.fontScale >= INDENT_CAP_FONT_SCALE) INDENT_CAP_LARGE_FONT else INDENT_CAP
    val colors = TokenColors.current()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sidePx = with(density) { (16.dp * 2).roundToPx() }
        val widthPx = (constraints.maxWidth - sidePx).coerceAtLeast(charPx)
        val pieces = remember(lines, widthPx, charPx, indentCap) {
            ConsoleValuePieces(lines) { line -> (widthPx - indentChars(line, indentCap) * charPx) / charPx }
        }
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = ListTopPadding,
                bottom = ListBottomPadding,
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(count = pieces.count) { index ->
                val piece = pieces.piece(index)
                val indent = with(density) { (indentChars(piece.line, indentCap) * charPx).toDp() }
                Text(
                    text = remember(piece, colors) { pieceText(piece, colors) },
                    style = style,
                    color = KnotworkTheme.extended.consoleFg,
                    modifier = Modifier.fillMaxWidth().padding(start = indent),
                )
            }
        }
        ConsoleValueScrollThumb(
            listState = listState,
            pieces = pieces,
            lineCount = lines.size,
            rowPx = with(density) { style.lineHeight.toPx() },
            viewportPx = constraints.maxHeight,
            paddingPx = with(density) { (ListTopPadding + ListBottomPadding).roundToPx() },
        )
    }
}

/** Characters of indent drawn before [line], its level capped at [cap]. */
private fun indentChars(line: ConsoleValueLine, cap: Int): Int = minOf(line.indent, cap) * INDENT_CHARS_PER_LEVEL

/**
 * The colours of the value's tokens: keys `Accent400`, strings `Accent200`, numbers and
 * literals `consoleFg`, punctuation `consoleFg` dimmed.
 */
private data class TokenColors(val key: Color, val string: Color, val plain: Color, val punctuation: Color) {
    fun of(token: ConsoleValueToken): Color = when (token) {
        ConsoleValueToken.Key -> key
        ConsoleValueToken.Str -> string
        ConsoleValueToken.Number, ConsoleValueToken.Literal -> plain
        ConsoleValueToken.Punctuation -> punctuation
    }

    companion object {
        @Composable
        fun current(): TokenColors {
            val fg = KnotworkTheme.extended.consoleFg
            return TokenColors(
                key = KnotworkPalette.Accent400,
                string = KnotworkPalette.Accent200,
                plain = fg,
                punctuation = fg.copy(alpha = DIM_ALPHA),
            )
        }
    }
}

/** The text of [piece] with its part of every token span painted. */
private fun pieceText(piece: ConsoleValuePiece, colors: TokenColors): AnnotatedString = buildAnnotatedString {
    append(piece.line.text, piece.start, piece.end)
    for (span in piece.line.spans) {
        val start = maxOf(span.start, piece.start)
        val end = minOf(span.end, piece.end)
        if (start < end) addStyle(SpanStyle(color = colors.of(span.token)), start - piece.start, end - piece.start)
    }
}
