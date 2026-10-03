package app.knotwork.design.components.chips

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Visual height of a Knotwork chip; touch target floors at 48 dp via Material's interactive minimum. */
private val ChipHeight = 32.dp

/** Diameter of the leading / trailing icon inside a chip. */
private val ChipIconSize = 16.dp

/**
 * Knotwork chip style — `Default` (filled neutral), `Tonal` (filled accent
 * 100), or `Outline` (transparent + outline). Use [KnotworkChip] to render.
 */
enum class ChipStyle {
    /** Surface-2 container with `onSurface2` label. Default for filter chips. */
    Default,

    /** Accent 100 container with `onPrimaryContainer` label. Used for emphasised chips. */
    Tonal,

    /** Transparent container with 1 dp `outlineStrong` border. Used for picker quick-replies. */
    Outline,
}

/**
 * Knotwork chip — horizontal pill with optional leading and trailing icons.
 *
 * Visual contract:
 *  - 32 dp tall, shape `KnotworkTheme.shapes.full`, horizontal padding 12 dp.
 *  - `selected = true`: container `Accent100` (light) / `primaryContainer`
 *    (dark), label `onPrimaryContainer` — independent of [style].
 *  - Click target stays 48 dp via Material's interactive minimum size.
 *
 * @param label user-visible text. Rendered in [KnotworkTextStyles.LabelMd].
 * @param onClick optional click handler. When `null`, the chip is purely
 * decorative (used in summary rows where chips display tags, not filters).
 * @param modifier optional layout modifier applied to the chip root.
 * @param leadingIcon optional vector rendered before the label (16 dp).
 * @param trailingIcon optional vector rendered after the label (16 dp); pair
 * with `AppIcons.X` for filter chips that toggle off.
 * @param selected when `true`, renders the selected palette regardless of
 * [style].
 * @param style controls the resting (unselected) palette.
 * @param enabled when `false`, the chip is non-interactive and renders the
 * disabled tone.
 */
@Composable
fun KnotworkChip(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    selected: Boolean = false,
    style: ChipStyle = ChipStyle.Default,
    enabled: Boolean = true,
) {
    val containerColor = chipContainerColor(style = style, selected = selected, enabled = enabled)
    val contentColor = chipContentColor(style = style, selected = selected, enabled = enabled)
    val border = if (style == ChipStyle.Outline && !selected) {
        BorderStroke(width = 1.dp, color = KnotworkTheme.extended.outlineStrong)
    } else {
        null
    }
    if (onClick != null) {
        AssistChip(
            onClick = onClick,
            enabled = enabled,
            shape = KnotworkTheme.shapes.full,
            border = border,
            colors = AssistChipDefaults.assistChipColors(
                containerColor = containerColor,
                labelColor = contentColor,
                leadingIconContentColor = contentColor,
                trailingIconContentColor = contentColor,
                disabledContainerColor = KnotworkTheme.extended.surface2,
                disabledLabelColor = KnotworkTheme.extended.onSurfaceDim,
            ),
            label = {
                Text(text = label, style = KnotworkTextStyles.LabelMd, color = contentColor)
            },
            leadingIcon = leadingIcon?.let {
                {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        modifier = Modifier.size(ChipIconSize),
                        tint = contentColor,
                    )
                }
            },
            trailingIcon = trailingIcon?.let {
                {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        modifier = Modifier.size(ChipIconSize),
                        tint = contentColor,
                    )
                }
            },
            modifier = modifier
                .defaultMinSize(minHeight = ChipHeight)
                .height(ChipHeight),
        )
    } else {
        // Non-interactive variant — tags rendered inside summary rows. Uses a
        // bare Surface so we keep the same visuals without paying for the chip
        // ripple machinery.
        Surface(
            shape = KnotworkTheme.shapes.full,
            color = containerColor,
            border = border,
            modifier = modifier
                .defaultMinSize(minHeight = ChipHeight)
                .height(ChipHeight),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = KnotworkTheme.spacing.sp3),
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        modifier = Modifier.size(ChipIconSize),
                        tint = contentColor,
                    )
                }
                Text(text = label, style = KnotworkTextStyles.LabelMd, color = contentColor)
                if (trailingIcon != null) {
                    Icon(
                        imageVector = trailingIcon,
                        contentDescription = null,
                        modifier = Modifier.size(ChipIconSize),
                        tint = contentColor,
                    )
                }
            }
        }
    }
}

/**
 * Resolves the chip container colour from the selected / style / enabled
 * cross-product. Pulled out of [KnotworkChip] so the lookup table reads as
 * a single block instead of being hidden inside the call to [AssistChip].
 */
@Composable
private fun chipContainerColor(style: ChipStyle, selected: Boolean, enabled: Boolean): Color {
    if (!enabled) return KnotworkTheme.extended.surface2
    // `primaryContainer` is theme-aware: Accent100 in light, a dark brown in
    // dark — exactly the container shade `onPrimaryContainer` (used as the
    // chip label colour) is meant to land on. Hardcoding the palette
    // `Accent100` here makes the dark-theme Tonal chip render light-on-light
    // text.
    if (selected) return MaterialTheme.colorScheme.primaryContainer
    return when (style) {
        ChipStyle.Default -> KnotworkTheme.extended.surface2
        ChipStyle.Tonal -> MaterialTheme.colorScheme.primaryContainer
        ChipStyle.Outline -> Color.Transparent
    }
}

/** Mirror of [chipContainerColor] for label / icon colour. */
@Composable
private fun chipContentColor(style: ChipStyle, selected: Boolean, enabled: Boolean): Color {
    if (!enabled) return KnotworkTheme.extended.onSurfaceDim
    if (selected) return MaterialTheme.colorScheme.onPrimaryContainer
    return when (style) {
        ChipStyle.Default -> KnotworkTheme.extended.onSurface2
        ChipStyle.Tonal -> MaterialTheme.colorScheme.onPrimaryContainer
        ChipStyle.Outline -> KnotworkTheme.extended.onSurface2
    }
}
