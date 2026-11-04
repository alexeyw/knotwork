package app.knotwork.design.screens.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Multi-line monospace input rendered with the brand outline.
 *
 * Used by the system-prompt-prefix row inside `SettingsContent`.
 * Visual contract:
 *  - `KnotworkTheme.shapes.md` outline, 1dp `outlineStrong` border.
 *  - Mono base text style + brand-primary cursor.
 *  - Internal padding of `sp3`.
 *  - Placeholder rendered in `onSurfaceMuted` when [value] is empty.
 *
 * Stateless — the caller owns [value] and [onValueChange].
 *
 * @param value current text.
 * @param onValueChange callback invoked on user input.
 * @param placeholder hint text shown when [value] is empty.
 * @param modifier layout modifier applied to the outer container; callers
 *  typically pass `Modifier.fillMaxWidth().height(120.dp)`.
 */
@Composable
fun KnotworkMonoTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(KnotworkTheme.shapes.md)
            .border(
                width = BORDER_WIDTH,
                color = KnotworkTheme.extended.outlineStrong,
                shape = KnotworkTheme.shapes.md,
            )
            .padding(KnotworkTheme.spacing.sp3),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = KnotworkTextStyles.MonoBase.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxSize(),
        )
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = KnotworkTextStyles.MonoBase,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

private val BORDER_WIDTH = 1.dp
