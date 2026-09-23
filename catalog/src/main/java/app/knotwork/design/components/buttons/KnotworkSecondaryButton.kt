package app.knotwork.design.components.buttons

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.theme.KnotworkTheme

/**
 * Knotwork brand **secondary** button — transparent container with a
 * 1 dp outline.
 *
 * Visual contract:
 *  - Container: `Color.Transparent`.
 *  - Border: 1 dp, `extended.outlineStrong` by default, swaps to
 *    `extended.riskDestructive` when [destructive] is `true`.
 *  - Content: `onSurface` (or `extended.riskDestructive` when
 *    [destructive]).
 *  - Shape: `KnotworkTheme.shapes.full` (pill).
 *  - Touch target floors at 48 × 48 dp via
 *    `Modifier.minimumInteractiveComponentSize()`.
 *  - Loading + disabled palettes mirror [KnotworkPrimaryButton].
 *
 * @param text user-visible label.
 * @param onClick invoked on tap; gated to no-op when [enabled] is `false`
 *   or [loading] is `true`.
 * @param modifier optional layout modifier applied to the button root.
 * @param size visual tier — see [KnotworkButtonSize].
 * @param enabled disables interactivity + applies the disabled palette
 *   when `false`.
 * @param loading fades the label, overlays a spinner, gates click.
 * @param destructive switches the label + border to
 *   `extended.riskDestructive` (HITL `Reject`, "Delete pipeline", …).
 *   For irreversible destructive actions, gate behind a typed-confirm
 *   dialog (see `HitlConfirmationCard`).
 * @param leadingIcon optional leading glyph.
 */
@Suppress("LongParameterList") // Brand-stable public API.
@Composable
fun KnotworkSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: KnotworkButtonSize = KnotworkButtonSize.Md,
    enabled: Boolean = true,
    loading: Boolean = false,
    destructive: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val effectiveEnabled = enabled && !loading
    val accent = if (destructive) {
        KnotworkTheme.extended.riskDestructive
    } else {
        KnotworkTheme.extended.onSurface2
    }
    val borderColor = if (effectiveEnabled) accent else KnotworkTheme.extended.outlineStrong
    val visualHeight = KnotworkButtonDefaults.heightFor(size)
    OutlinedButton(
        onClick = onClick,
        enabled = effectiveEnabled,
        shape = KnotworkTheme.shapes.full,
        border = BorderStroke(width = 1.dp, color = borderColor),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = accent,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = KnotworkTheme.extended.onSurfaceDim,
        ),
        contentPadding = KnotworkButtonDefaults.paddingFor(size),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .defaultMinSize(minHeight = visualHeight)
            .height(visualHeight)
            .semantics { if (loading) stateDescription = "Loading" },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(KnotworkButtonDefaults.IconGap),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.alpha(if (loading) KnotworkButtonDefaults.LOADING_LABEL_ALPHA else 1f),
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
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(KnotworkButtonDefaults.LoadingIndicatorSize),
                    color = accent,
                    strokeWidth = KnotworkButtonDefaults.LoadingIndicatorStroke,
                )
            }
        }
    }
}
