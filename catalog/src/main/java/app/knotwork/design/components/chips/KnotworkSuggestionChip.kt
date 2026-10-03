package app.knotwork.design.components.chips

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Action-only Knotwork chip — quick-reply, suggested action, onboarding
 * suggestion.
 *
 * Geometry: same 8 dp `sm` shape as
 * [KnotworkFilterChip], but always rendered with a 1 dp outline and
 * `surface1` container so the chip reads on top of chat bubbles
 * (filter chips use a transparent fill, which would disappear into the
 * bubble background).
 *
 * Unlike [KnotworkFilterChip] this atom has no `selected` parameter — once
 * tapped, the caller typically removes the chip from the list or converts
 * it into a filter chip with `selected = true`. Defaults to
 * [KnotworkChipSize.Md] because quick-reply chips need the 40 dp tap target
 * to read as primary affordances inside the chat stream.
 *
 * @param label User-visible text. Rendered in [KnotworkTextStyles.LabelMd]
 *  (`Sm`/`Md`) or [KnotworkTextStyles.LabelSm] (`Xs`).
 * @param onClick Click handler.
 * @param modifier Layout modifier applied to the outer surface.
 * @param size Visual height / padding. Defaults to [KnotworkChipSize.Md]
 *  (quick-reply).
 * @param leadingIcon Optional vector rendered before [label].
 * @param enabled `false` disables the click handler and greys the chip.
 */
@Composable
fun KnotworkSuggestionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: KnotworkChipSize = KnotworkChipSize.Md,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val ext = KnotworkTheme.extended
    val container = if (enabled) ext.surface1 else ext.surface2
    val content = if (enabled) ext.onSurface2 else ext.onSurfaceDim
    val border = BorderStroke(
        width = KnotworkChipDefaults.BorderDefault,
        color = MaterialTheme.colorScheme.outline,
    )

    val height = when (size) {
        KnotworkChipSize.Xs -> KnotworkChipDefaults.HeightXs
        KnotworkChipSize.Sm -> KnotworkChipDefaults.HeightSm
        KnotworkChipSize.Md -> KnotworkChipDefaults.HeightMd
    }
    val padding = when (size) {
        KnotworkChipSize.Xs -> KnotworkChipDefaults.PaddingXs
        KnotworkChipSize.Sm -> KnotworkChipDefaults.PaddingSm
        KnotworkChipSize.Md -> KnotworkChipDefaults.PaddingMd
    }
    val iconSize = when (size) {
        KnotworkChipSize.Xs -> KnotworkChipDefaults.IconSizeXs
        KnotworkChipSize.Sm -> KnotworkChipDefaults.IconSizeSm
        KnotworkChipSize.Md -> KnotworkChipDefaults.IconSizeMd
    }
    val textStyle = when (size) {
        KnotworkChipSize.Xs -> KnotworkTextStyles.LabelSm
        KnotworkChipSize.Sm -> KnotworkTextStyles.LabelMd
        KnotworkChipSize.Md -> KnotworkTextStyles.LabelLg
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = KnotworkTheme.shapes.sm,
        color = container,
        contentColor = content,
        border = border,
        modifier = modifier
            .defaultMinSize(minHeight = height)
            .height(height)
            .semantics { this.role = Role.Button },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkChipDefaults.IconGap),
            modifier = Modifier.padding(padding),
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(iconSize),
                )
            }
            Text(text = label, style = textStyle, color = content)
        }
    }
}
