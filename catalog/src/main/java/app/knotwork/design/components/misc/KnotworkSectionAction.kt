package app.knotwork.design.components.misc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Right-aligned action link sitting next to a section header (e.g.
 * "Reset to defaults", "Manage", "+ Add provider").
 *
 * Renders a leading icon + a label in the primary tint, both wrapped in
 * a click area sized to the 48 dp touch floor.
 *
 * @param label Localized action text.
 * @param onClick Click callback.
 * @param modifier Layout modifier — usually defaults work.
 * @param icon Optional leading icon. `null` for text-only actions.
 */
@Composable
fun KnotworkSectionAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(
                horizontal = KnotworkTheme.spacing.sp2,
                vertical = KnotworkTheme.spacing.sp1,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(KnotworkTheme.spacing.sp4),
            )
        }
        Text(
            text = label,
            style = KnotworkTextStyles.LabelMd.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
