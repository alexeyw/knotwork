package app.knotwork.design.components.buttons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.theme.KnotworkTheme

/**
 * Knotwork brand **primary** button — filled, accent container.
 *
 * Visual contract:
 *  - Container: `colorScheme.primary` (Accent500 light / Accent400 dark).
 *  - Content: `onPrimary`.
 *  - Shape: `KnotworkTheme.shapes.full` (pill).
 *  - Elevation: 0 dp in every state — Knotwork is flat-by-design.
 *  - Touch target floors at 48 × 48 dp via
 *    `Modifier.minimumInteractiveComponentSize()` even when the visual is
 *    32 dp (`KnotworkButtonSize.Sm`).
 *  - **Loading**: label fades to alpha 0.3, a 16 dp
 *    `CircularProgressIndicator` overlays the centre, click is gated.
 *  - **Disabled**: container `extended.surface3`, label
 *    `extended.onSurfaceDim`, no elevation.
 *
 * @param text user-visible label.
 * @param onClick invoked on tap; gated to no-op when [enabled] is `false`
 *   or [loading] is `true`.
 * @param modifier optional layout modifier applied to the underlying
 *   [Button].
 * @param size visual tier — `Sm` (32 dp) / `Md` (40 dp, default) /
 *   `Lg` (48 dp). See [KnotworkButtonSize].
 * @param enabled when `false`, the button is non-interactive and renders
 *   the disabled palette.
 * @param loading when `true`, the button is non-interactive, the label
 *   fades, and a circular progress indicator overlays the centre.
 * @param leadingIcon optional vector rendered before the label
 *   (`IconSizeMd` / `IconSizeSm` depending on [size]).
 */
@Composable
fun KnotworkPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: KnotworkButtonSize = KnotworkButtonSize.Md,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val effectiveEnabled = enabled && !loading
    val visualHeight = KnotworkButtonDefaults.heightFor(size)
    Button(
        onClick = onClick,
        enabled = effectiveEnabled,
        shape = KnotworkTheme.shapes.full,
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = ZERO_ELEVATION,
            pressedElevation = ZERO_ELEVATION,
            focusedElevation = ZERO_ELEVATION,
            hoveredElevation = ZERO_ELEVATION,
            disabledElevation = ZERO_ELEVATION,
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = KnotworkTheme.extended.surface3,
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
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = KnotworkButtonDefaults.LoadingIndicatorStroke,
                )
            }
        }
    }
}

/**
 * Zero-elevation constant used by every state of the primary button.
 * Pulled into a `val` so the compose-runtime stability inference treats
 * the elevation block as static.
 */
private val ZERO_ELEVATION = 0.dp
