package app.knotwork.design.components.misc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Stripe spacing in dp — wide enough to read as decorative, narrow enough to fill the box. */
private val StripeSpacing = 12.dp

/** Stripe stroke width. */
private val StripeStroke = 2.dp

/** Alpha applied to the stripe foreground (40 % per spec). */
private const val STRIPE_ALPHA = 0.4f

/**
 * Knotwork striped placeholder — the canonical "missing asset" stand-in for
 * any product surface that ships before its real illustration / hero image
 * is available.
 *
 * Visual contract:
 *  - 40 % opacity diagonal stripes drawn in `extended.onSurfaceDim` over a
 *    `extended.surface2` background, clipped to `KnotworkTheme.shapes.md`.
 *  - Optional caption rendered in `MonoSm` at the centre.
 *  - Decorative — `contentDescription` defaults to a generic "Placeholder
 *    illustration" string so screen readers don't omit the slot, but the
 *    caller can override it via [contentDescription].
 *
 * @param modifier optional layout modifier; callers must constrain the size
 * via `Modifier.size(…)` or by placing the placeholder inside a sized
 * parent.
 * @param caption optional mono caption rendered centred over the stripes.
 * @param shape corner shape of the clipped placeholder; defaults to
 * `KnotworkTheme.shapes.md`.
 * @param contentDescription accessibility label; defaults to
 * `"Placeholder illustration"`.
 */
@Composable
fun StripedPlaceholder(
    modifier: Modifier = Modifier,
    caption: String? = null,
    shape: androidx.compose.ui.graphics.Shape = KnotworkTheme.shapes.md,
    contentDescription: String = stringResource(R.string.knotwork_placeholder_illustration_cd),
) {
    val background = KnotworkTheme.extended.surface2
    val foreground = KnotworkTheme.extended.onSurfaceDim.copy(alpha = STRIPE_ALPHA)
    Box(
        modifier = modifier
            .clip(shape)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = background)
            drawDiagonalStripes(
                color = foreground,
                spacingDp = StripeSpacing,
                strokeDp = StripeStroke,
            )
        }
        if (caption != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(KnotworkTheme.spacing.sp3),
            ) {
                Text(
                    text = caption,
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurface2,
                )
            }
        }
    }
}

/** Draws evenly-spaced diagonal stripes from top-left to bottom-right of the canvas. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDiagonalStripes(
    color: Color,
    spacingDp: Dp,
    strokeDp: Dp,
) {
    val spacingPx = spacingDp.toPx()
    val strokePx = strokeDp.toPx()
    val width = size.width
    val height = size.height
    val total = width + height
    var offset = -height
    while (offset < total) {
        drawLine(
            color = color,
            start = Offset(x = offset, y = 0f),
            end = Offset(x = offset + height, y = height),
            strokeWidth = strokePx,
            cap = StrokeCap.Square,
        )
        offset += spacingPx
    }
}
