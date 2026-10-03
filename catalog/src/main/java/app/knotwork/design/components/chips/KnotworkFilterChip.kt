package app.knotwork.design.components.chips

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Two-state Knotwork chip used for segmented controls, library filters, and
 * single-choice rows (yes/no, Format, Style, Risk gate).
 *
 * Geometry:
 *  - Shape: `KnotworkTheme.shapes.sm` (8 dp). Spec deliberately diverges
 *    from Material's pill-shaped filter chip — Knotwork uses 8 dp rectangles
 *    everywhere except the dedicated `RiskPill` / `StatusPill` family.
 *  - Sizes: 28 / 32 / 40 dp tall via [KnotworkChipSize].
 *  - Border: 1 dp [androidx.compose.material3.ColorScheme.outline] when
 *    deselected; transparent when selected.
 *  - Container: transparent (deselected) → `primaryContainer` (selected).
 *    Both transitions cross-fade over [KnotworkChipDefaults.TOGGLE_DURATION_MS].
 *  - Optional [trailingCount] renders the M3 "All · 24" pattern as
 *    `label · count` so the count inherits the chip's content colour.
 *
 * Use [Role.Tab] semantics in segmented rows (single-choice) and
 * [Role.Checkbox] in multi-select filter bars — toggle via the [role] param.
 *
 * @param label User-visible text. Rendered in [KnotworkTextStyles.LabelMd]
 *  (`Sm`/`Md`) or [KnotworkTextStyles.LabelSm] (`Xs`).
 * @param selected Whether the chip is currently in the "on" state.
 * @param onClick Click handler. Receives the new logical state — the chip
 *  does **not** mutate state itself, the caller flips [selected].
 * @param modifier Layout modifier applied to the outer surface.
 * @param size Visual height / padding. Defaults to [KnotworkChipSize.Sm].
 * @param leadingIcon Optional vector rendered before [label]. Icon size
 *  derives from [size] via [KnotworkChipDefaults].
 * @param trailingCount Optional count rendered as " · N" after [label].
 *  Useful for filter chips that announce how many items are in their bucket.
 * @param enabled `false` disables the click handler and renders the muted
 *  palette regardless of [selected].
 * @param role A11y role exposed to TalkBack; default [Role.Tab] suits the
 *  segmented-single-choice usage. Pass [Role.Checkbox] for multi-select
 *  filter bars.
 */
@Composable
fun KnotworkFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: KnotworkChipSize = KnotworkChipSize.Sm,
    leadingIcon: ImageVector? = null,
    trailingCount: Int? = null,
    enabled: Boolean = true,
    role: Role = Role.Tab,
) {
    val ext = KnotworkTheme.extended
    val containerTarget: Color = when {
        !enabled -> Color.Transparent
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val contentTarget: Color = when {
        !enabled -> ext.onSurfaceDim
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> ext.onSurface2
    }
    val border: BorderStroke? = when {
        selected -> null
        else -> BorderStroke(
            width = KnotworkChipDefaults.BorderDefault,
            color = if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outline,
        )
    }
    val container by animateColorAsState(
        targetValue = containerTarget,
        animationSpec = tween(KnotworkChipDefaults.TOGGLE_DURATION_MS),
        label = "filter-chip-container",
    )
    val content by animateColorAsState(
        targetValue = contentTarget,
        animationSpec = tween(KnotworkChipDefaults.TOGGLE_DURATION_MS),
        label = "filter-chip-content",
    )

    val height: Dp = when (size) {
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
            .semantics { this.role = role },
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
            val display = if (trailingCount != null) "$label · $trailingCount" else label
            Text(text = display, style = textStyle, color = content)
        }
    }
}
