package app.knotwork.design.components.controls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Down-scale factor applied to the trailing [Switch]: Material3's default
 * (52×32 dp) dwarfs the row title at the app's 14sp body scale, so it is shrunk
 * to ~78% to sit comfortably beside the label.
 */
private const val LABELED_SWITCH_SCALE = 0.78f

/**
 * A single settings-style toggle row: a `label` + muted `description` on the
 * left and a trailing [Switch] reflecting [checked]. The **whole row** is the
 * tap target (the documented 48 dp floor) and the switch itself is
 * non-interactive, so a click anywhere on the row fires [onToggle].
 *
 * Shared design-system control so the Models, Skills, and other surfaces render
 * an identical labelled toggle (track/thumb colours, scale, padding, tap area)
 * from one place instead of hand-copying the styling.
 *
 * The caller owns the row's surface and shape via [modifier] (e.g. a background
 * colour or a clip), keeping this component agnostic to where it is embedded.
 *
 * @param label Primary line of the row.
 * @param description Secondary, muted explanatory line.
 * @param checked Current on/off state shown by the switch.
 * @param onToggle Invoked when the row is tapped (only while [enabled]).
 * @param modifier Caller-supplied modifier applied before the row's own
 *   width/clickable/padding (use it for a background or clip).
 * @param enabled When `false` the row is non-interactive and the switch is
 *   rendered disabled.
 */
@Composable
fun LabeledSwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = KnotworkTextStyles.BodyBase,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.scale(LABELED_SWITCH_SCALE),
        )
    }
}
