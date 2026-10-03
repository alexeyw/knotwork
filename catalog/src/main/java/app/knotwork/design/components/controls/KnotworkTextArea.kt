package app.knotwork.design.components.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkPalette
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Multi-line Knotwork text input.
 *
 * Built on [BasicTextField] for the same reason as [KnotworkTextField] — we
 * need pixel control over the box, plus the variable-highlight pass and the
 * insert-chip strip that Material3's text field can't host without
 * acrobatics.
 *
 * Features:
 *  - Same 7-state visual table as [KnotworkTextField] — default / hovered /
 *    focused / filled / disabled / readOnly / error.
 *  - Optional mono typography ([monospace] = true, default for prompts).
 *  - Variable highlight: every `\$[A-Z_]+` token is repainted in the accent
 *    palette (`Accent700` light / `Accent200` dark) with a dotted underline
 *    so prompt authors instantly see which variables resolve at runtime.
 *  - Optional [insertChips] strip rendered below the box. Tapping a chip
 *    inserts the corresponding `$NAME` token at the current cursor
 *    position. Cursor state is held internally via [TextFieldValue] so the
 *    public surface stays `String`-typed.
 *  - Optional [counterMax] renders a "current / max" tail in the helper
 *    row when wrapped by [KnotworkField]; callers compose it into the
 *    `helper` parameter themselves.
 *
 * @param value Current text. The caller owns the source-of-truth string.
 * @param onValueChange Edit callback.
 * @param modifier Layout modifier applied to the outer column.
 * @param placeholder Hint text shown when [value] is empty.
 * @param enabled `false` greys out the field and disables input + chips.
 * @param readOnly `true` blocks edits; insert-chips are still hidden in
 *  read-only mode (no point offering tokens you cannot drop).
 * @param isError `true` swaps the border to `riskDestructive`.
 * @param keyboardOptions IME options forwarded to [BasicTextField].
 * @param monospace `true` switches to [KnotworkTextStyles.MonoBase] (the
 *  spec's `Mono13`). Default `true` — almost every Knotwork text area is a
 *  prompt or expression.
 * @param highlightVariables `true` repaints `\$[A-Z_]+` tokens with the
 *  accent palette + dotted underline.
 * @param minLines Minimum visible lines (`3` by default).
 * @param maxLines Maximum visible lines before internal scroll engages
 *  (`8` by default).
 * @param insertChips Optional list of variable names (without `$`) rendered
 *  as a chip strip under the box. Tapping a chip inserts the corresponding
 *  `$NAME` at the current cursor position.
 */
@Composable
fun KnotworkTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    monospace: Boolean = true,
    highlightVariables: Boolean = true,
    minLines: Int = 3,
    maxLines: Int = 8,
    insertChips: List<String> = emptyList(),
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val visuals = resolveTextAreaVisuals(
        enabled = enabled,
        readOnly = readOnly,
        isError = isError,
        isFocused = isFocused,
        isHovered = isHovered,
    )

    // Holds the cursor position alongside the text so insert-chip taps can
    // splice tokens at the active selection. Re-anchored whenever the
    // external [value] is replaced from outside the composable (e.g. ViewModel
    // restore) to avoid pointing the cursor past the new length.
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    if (fieldValue.text != value) {
        fieldValue = TextFieldValue(value, selection = androidx.compose.ui.text.TextRange(value.length))
    }

    val baseStyle = if (monospace) KnotworkTextStyles.MonoBase else KnotworkTextStyles.BodyBase
    // Compose static accent — read from the palette so the highlight stays
    // hue-locked and is not pulled out from under us by Material You / dynamic
    // colour. Light theme = Accent700; dark theme = Accent200.
    val accentColor = if (MaterialTheme.colorScheme.background.luminance() > LIGHT_THRESHOLD) {
        KnotworkPalette.Accent700
    } else {
        KnotworkPalette.Accent200
    }
    val transformation: VisualTransformation = if (highlightVariables) {
        VariableHighlightTransformation(accentColor)
    } else {
        VisualTransformation.None
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = visuals.minHeight)
                .clip(visuals.shape)
                .border(visuals.borderWidth, visuals.borderColor, visuals.shape)
                .background(visuals.containerColor, visuals.shape)
                .padding(KnotworkFieldDefaults.PaddingTextArea),
        ) {
            BasicTextField(
                value = fieldValue,
                onValueChange = { next ->
                    fieldValue = next
                    if (next.text != value) onValueChange(next.text)
                },
                enabled = enabled,
                readOnly = readOnly,
                singleLine = false,
                minLines = minLines,
                maxLines = maxLines,
                textStyle = baseStyle.copy(color = visuals.textColor),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = keyboardOptions,
                visualTransformation = transformation,
                interactionSource = interactionSource,
                modifier = Modifier.fillMaxWidth(),
            )
            if (fieldValue.text.isEmpty() && placeholder != null) {
                Text(
                    text = placeholder,
                    style = baseStyle.copy(color = visuals.placeholderColor),
                )
            }
        }

        if (insertChips.isNotEmpty() && enabled && !readOnly) {
            Spacer(Modifier.height(KnotworkTheme.spacing.sp2))
            InsertChipStrip(
                names = insertChips,
                onInsert = { name ->
                    val token = "$$name"
                    val sel = fieldValue.selection
                    val text = fieldValue.text
                    val before = text.substring(0, sel.start)
                    val after = text.substring(sel.end)
                    val next = before + token + after
                    val cursor = (before.length + token.length).coerceAtMost(next.length)
                    fieldValue = TextFieldValue(
                        text = next,
                        selection = androidx.compose.ui.text.TextRange(cursor),
                    )
                    onValueChange(next)
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InsertChipStrip(names: List<String>, onInsert: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ChipStripGap),
        verticalArrangement = Arrangement.spacedBy(ChipStripGap),
        modifier = Modifier.fillMaxWidth(),
    ) {
        for (name in names) {
            VariableInsertChip(name = name, onClick = { onInsert(name) })
        }
    }
}

@Composable
private fun VariableInsertChip(name: String, onClick: () -> Unit) {
    // Dashed-border variable chip — the spec singles this out as the only
    // dashed border in the system, signalling "template, not value". Real
    // dashed strokes are awkward in Compose; render as a 1 dp solid border
    // with a transparent fill so the chip is unmistakably hollow, and let
    // the dotted-underline highlight inside the text area carry the
    // "this is a variable" message redundantly.
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = KnotworkTheme.shapes.sm,
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            width = KnotworkFieldDefaults.BorderDefault,
            color = MaterialTheme.colorScheme.outline,
        ),
    ) {
        Text(
            text = "$$name",
            style = KnotworkTextStyles.MonoSm.copy(
                color = if (MaterialTheme.colorScheme.background.luminance() > LIGHT_THRESHOLD) {
                    KnotworkPalette.Accent700
                } else {
                    KnotworkPalette.Accent200
                },
                fontWeight = FontWeight.Medium,
            ),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * Resolved visual contract for the text-area state cross-product. Same shape
 * as [KnotworkTextField]'s but with `surface1` always-on container and a
 * minHeight instead of fixed height.
 */
private data class TextAreaVisuals(
    val minHeight: Dp,
    val containerColor: Color,
    val borderColor: Color,
    val borderWidth: Dp,
    val shape: CornerBasedShape,
    val textColor: Color,
    val placeholderColor: Color,
)

@Composable
private fun resolveTextAreaVisuals(
    enabled: Boolean,
    readOnly: Boolean,
    isError: Boolean,
    isFocused: Boolean,
    isHovered: Boolean,
): TextAreaVisuals {
    val ext = KnotworkTheme.extended
    val shape = KnotworkTheme.shapes.sm
    val container: Color
    val borderColor: Color
    val borderWidth: Dp
    when {
        !enabled -> {
            container = ext.surface3
            borderColor = MaterialTheme.colorScheme.outline
            borderWidth = KnotworkFieldDefaults.BorderDefault
        }
        readOnly -> {
            container = ext.surface2
            borderColor = MaterialTheme.colorScheme.outline
            borderWidth = KnotworkFieldDefaults.BorderDefault
        }
        isError -> {
            container = ext.surface1
            borderColor = ext.riskDestructive
            borderWidth = KnotworkFieldDefaults.BorderError
        }
        isFocused -> {
            container = ext.surface1
            borderColor = MaterialTheme.colorScheme.primary
            borderWidth = KnotworkFieldDefaults.BorderFocused
        }
        isHovered -> {
            container = ext.surface1
            borderColor = ext.outlineStrong
            borderWidth = KnotworkFieldDefaults.BorderDefault
        }
        else -> {
            container = ext.surface1
            borderColor = MaterialTheme.colorScheme.outline
            borderWidth = KnotworkFieldDefaults.BorderDefault
        }
    }
    val textColor = if (!enabled) ext.onSurfaceDim else MaterialTheme.colorScheme.onSurface
    return TextAreaVisuals(
        minHeight = TextAreaMinHeight,
        containerColor = container,
        borderColor = borderColor,
        borderWidth = borderWidth,
        shape = shape,
        textColor = textColor,
        placeholderColor = ext.onSurfaceDim,
    )
}

/**
 * Repaints every `\$[A-Z_]+` token in [accentColor] with a dotted underline
 * so prompt authors can spot variables inline without leaving the field.
 *
 * Backed by a length-preserving [TransformedText] so cursor position and
 * IME / accessibility offsets stay 1:1 with the underlying string.
 */
private class VariableHighlightTransformation(private val accentColor: Color) : VisualTransformation {

    private val regex = Regex("""\$[A-Z_][A-Z0-9_]*""")

    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder(text.text)
        // Re-apply any existing spans (so callers that already passed an
        // AnnotatedString — unlikely with BasicTextField but cheap to honour
        // — don't lose their styling).
        text.spanStyles.forEach { range ->
            builder.addStyle(range.item, range.start, range.end)
        }
        for (match in regex.findAll(text.text)) {
            builder.addStyle(
                SpanStyle(
                    color = accentColor,
                    fontWeight = FontWeight.Medium,
                    textDecoration = TextDecoration.Underline,
                ),
                match.range.first,
                match.range.last + 1,
            )
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

private val TextAreaMinHeight = 96.dp
private val ChipStripGap = 6.dp

/** Threshold used to decide whether the current theme is "light" for accent picking. */
private const val LIGHT_THRESHOLD = 0.5f

// Rec. 709 luminance coefficients (see `KnotworkVariableChip.kt` for the same formula).
private const val LUMA_RED = 0.2126f
private const val LUMA_GREEN = 0.7152f
private const val LUMA_BLUE = 0.0722f

private fun Color.luminance(): Float = LUMA_RED * red + LUMA_GREEN * green + LUMA_BLUE * blue
