package app.knotwork.design.components.chips

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkPalette
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Mono variable chip — the affordance that inserts a `$NAME` token into a
 * nearby text input or copies it to the clipboard.
 *
 * Geometry: transparent fill, 1 dp outline
 * border (the spec calls for `dashed`; Compose lacks a first-class dashed
 * `BorderStroke`, so the chip relies on the mono typography + accent label
 * to read as "template, not value"), mono typography with accent fill
 * (`Accent700` light / `Accent200` dark), 10 dp · 6 dp padding.
 *
 * @param name Variable name **without** the leading `$` — the chip
 *  prepends `$` automatically. If [name] already starts with `$` the chip
 *  strips it to avoid `$$VAR`.
 * @param onInsert Click handler. The atom is dumb — the caller decides
 *  whether to splice the token into a focused field or copy it.
 * @param modifier Layout modifier applied to the outer surface.
 * @param enabled `false` greys out the chip and disables the click handler.
 */
@Composable
fun KnotworkVariableChip(name: String, onInsert: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val ext = KnotworkTheme.extended
    val displayName = name.trimStart('$')
    val accent: Color = if (MaterialTheme.colorScheme.background.luminance() > LIGHT_THRESHOLD) {
        KnotworkPalette.Accent700
    } else {
        KnotworkPalette.Accent200
    }
    val labelColor = if (enabled) accent else ext.onSurfaceDim
    val borderColor = if (enabled) MaterialTheme.colorScheme.outline else ext.onSurfaceDim

    Surface(
        onClick = onInsert,
        enabled = enabled,
        shape = KnotworkTheme.shapes.sm,
        color = Color.Transparent,
        contentColor = labelColor,
        border = BorderStroke(width = KnotworkChipDefaults.BorderDefault, color = borderColor),
        modifier = modifier,
    ) {
        Text(
            text = "$" + displayName,
            style = KnotworkTextStyles.MonoSm.copy(
                color = labelColor,
                fontWeight = FontWeight.Medium,
            ),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** Background luminance above this is "light theme"; tuned to match the M3 surface-light cutoff. */
private const val LIGHT_THRESHOLD = 0.5f

// Rec. 709 luminance coefficients (CIE 1931 to luma standard). Pulled into named constants
// so detekt does not see them as magic numbers, and so the formula reads like the spec.
private const val LUMA_RED = 0.2126f
private const val LUMA_GREEN = 0.7152f
private const val LUMA_BLUE = 0.0722f

private fun Color.luminance(): Float = LUMA_RED * red + LUMA_GREEN * green + LUMA_BLUE * blue
