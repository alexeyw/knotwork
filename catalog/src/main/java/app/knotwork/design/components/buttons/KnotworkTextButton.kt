package app.knotwork.design.components.buttons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import app.knotwork.design.theme.KnotworkTheme

/**
 * Knotwork **text** button — chrome-less, label-only.
 *
 * Visual contract:
 *  - Container: `Color.Transparent`.
 *  - Label: `colorScheme.primary` (or `extended.riskDestructive` when
 *    [destructive] is `true`).
 *  - Shape: `KnotworkTheme.shapes.full` (pill) for the press-state
 *    ripple, so the highlight stays consistent with filled siblings.
 *  - Touch target floors at 48 × 48 dp via
 *    `Modifier.minimumInteractiveComponentSize()`.
 *
 * @param text user-visible label.
 * @param onClick invoked on tap; gated to no-op when [enabled] is `false`.
 * @param modifier optional layout modifier applied to the button root.
 * @param size visual tier — see [KnotworkButtonSize].
 * @param enabled disables interactivity + applies the disabled palette
 *   when `false`.
 * @param destructive recolours the label with `extended.riskDestructive`.
 * @param leadingIcon optional leading glyph.
 */
@Suppress("LongParameterList") // Brand-stable public API.
@Composable
fun KnotworkTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: KnotworkButtonSize = KnotworkButtonSize.Md,
    enabled: Boolean = true,
    destructive: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val accent = when {
        destructive -> KnotworkTheme.extended.riskDestructive
        else -> MaterialTheme.colorScheme.primary
    }
    val visualHeight = KnotworkButtonDefaults.heightFor(size)
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = KnotworkTheme.shapes.full,
        colors = ButtonDefaults.textButtonColors(
            containerColor = Color.Transparent,
            contentColor = accent,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = KnotworkTheme.extended.onSurfaceDim,
        ),
        contentPadding = KnotworkButtonDefaults.paddingFor(size),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .defaultMinSize(minHeight = visualHeight)
            .height(visualHeight),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(KnotworkButtonDefaults.IconGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(KnotworkButtonDefaults.iconSizeFor(size)),
                )
            }
            Text(
                text = text,
                style = KnotworkButtonDefaults.textStyleFor(size),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
