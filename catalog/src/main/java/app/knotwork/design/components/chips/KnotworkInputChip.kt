package app.knotwork.design.components.chips

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Removable Knotwork chip — a value the user has added (stop tokens, quick
 * replies, list-of-strings).
 *
 * Geometry:
 *  - 32 dp tall `KnotworkTheme.shapes.sm` container, `surface2` fill, no
 *    border (the container colour already separates it from the field
 *    background).
 *  - Padding: 10 dp left · 4 dp right — the trailing `×` sits in a 20 dp
 *    circular hit area, which itself enforces a 44 dp effective tap target
 *    through [androidx.compose.ui.semantics.semantics] on the surface.
 *
 * Stateless atom — the parent [KnotworkChipsInput] owns the list.
 *
 * @param label The chip text.
 * @param onRemove Click handler for the trailing `×`. The chip itself is
 *  not clickable; only the remove icon is.
 * @param modifier Layout modifier applied to the outer surface.
 * @param leadingIcon Optional 16 dp icon rendered before the label.
 */
@Composable
fun KnotworkInputChip(
    label: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    val ext = KnotworkTheme.extended
    Surface(
        shape = KnotworkTheme.shapes.sm,
        color = ext.surface2,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .defaultMinSize(minHeight = KnotworkChipDefaults.HeightSm)
            .height(KnotworkChipDefaults.HeightSm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkChipDefaults.IconGap),
            modifier = Modifier.padding(start = 10.dp, end = 4.dp),
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(KnotworkChipDefaults.IconSizeSm),
                )
            }
            Text(text = label, style = KnotworkTextStyles.LabelMd)
            Box(
                modifier = Modifier
                    .size(RemoveHitArea)
                    .clip(CircleShape)
                    .background(ext.surface3, CircleShape)
                    .clickable(onClick = onRemove)
                    .semantics { contentDescription = "Remove $label" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.X,
                    contentDescription = null,
                    tint = ext.onSurfaceMuted,
                    modifier = Modifier.size(RemoveIconSize),
                )
            }
        }
    }
}

private val RemoveHitArea = 20.dp
private val RemoveIconSize = 14.dp
