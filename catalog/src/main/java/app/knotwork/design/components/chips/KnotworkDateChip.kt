package app.knotwork.design.components.chips

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Non-interactive section-divider chip for the chat stream.
 *
 * Geometry: 24 dp tall pill (the explicit
 * exception to Knotwork's 8 dp chip rule — pill reads better as a
 * separator), `surface2` fill, `onSurface2` label, [KnotworkTextStyles.LabelSm]
 * typography, 10 dp horizontal padding.
 *
 * Intended for `Today` / `Yesterday` / localised date headers inside the
 * chat column. Centre via the caller (this atom is left-aligned and width
 * intrinsic).
 *
 * @param text Label shown inside the chip — caller localises the value
 *  (`Today`, `Yesterday`, `dd MMM yyyy`).
 * @param modifier Layout modifier; usually `Modifier.align(Alignment.CenterHorizontally)`
 *  inside a Column.
 */
@Composable
fun KnotworkDateChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = KnotworkTheme.shapes.full,
        color = KnotworkTheme.extended.surface2,
        contentColor = KnotworkTheme.extended.onSurface2,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = KnotworkTextStyles.LabelSm,
            color = KnotworkTheme.extended.onSurface2,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
