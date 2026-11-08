package app.knotwork.design.components.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles
import coil3.compose.SubcomposeAsyncImage

/**
 * Renders an attached image (thumbnail or full-bleed) via Coil, with built-in
 * **loading** and **missing-file** fallbacks.
 *
 * The image is loaded from [model] (typically the absolute path of the stored
 * JPEG). While Coil resolves it, [ImageThumbnailLoadingState] shows; if the
 * file is gone (retention swept it) Coil errors and [ImageThumbnailMissingState]
 * shows. Aspect handling is the caller's [contentScale] — bubbles pass
 * [ContentScale.Fit] (never crop the frame), the composer preview passes
 * [ContentScale.Crop] for its tight square.
 *
 * **Stateless** — owns no state; the model and click handler are hoisted.
 *
 * @param model Coil image model (absolute file path, URI, etc.). `null` renders
 *   the missing state.
 * @param contentDescription accessibility label for the image.
 * @param modifier layout modifier; the caller sizes the thumbnail.
 * @param contentScale how the image fills its box (fit for bubbles, crop for
 *   the composer preview).
 * @param shape clip shape applied to the image and its fallbacks.
 * @param onClick optional tap handler (opens the full-screen viewer).
 */
@Composable
fun ImageThumbnail(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    shape: Shape = KnotworkTheme.shapes.md,
    onClick: (() -> Unit)? = null,
) {
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    SubcomposeAsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier
            .clip(shape)
            .then(clickable),
        loading = { ImageThumbnailLoadingState(modifier = Modifier.fillMaxSize()) },
        error = { ImageThumbnailMissingState(modifier = Modifier.fillMaxSize()) },
    )
}

/**
 * Loading placeholder for an attachment thumbnail — a muted fill with a centred
 * spinning `refresh` glyph. The spin is decorative; reduced-motion freezes it to
 * a static frame.
 */
@Composable
fun ImageThumbnailLoadingState(modifier: Modifier = Modifier) {
    val reducedMotion = KnotworkTheme.a11y.reducedMotion()
    val angle by if (reducedMotion) {
        remember { mutableFloatStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "thumb_spin").animateFloat(
            initialValue = 0f,
            targetValue = FULL_TURN_DEGREES,
            animationSpec = infiniteRepeatable(
                tween(SPINNER_PERIOD_MS, easing = LinearEasing),
            ),
            label = "thumb_spin_angle",
        )
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.background(KnotworkTheme.extended.surface3),
    ) {
        Icon(
            imageVector = AppIcons.Refresh,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceDim,
            modifier = Modifier
                .size(SPINNER_SIZE)
                .rotate(angle),
        )
    }
}

/**
 * Missing-file fallback for an attachment thumbnail — a dashed-bordered muted
 * surface with the `imageOff` glyph and a calm, non-alarmist label. Shown when
 * the underlying file has been cleared by the retention sweep.
 */
@Composable
fun ImageThumbnailMissingState(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1, Alignment.CenterVertically),
        modifier = modifier
            .clip(KnotworkTheme.shapes.md)
            .background(KnotworkTheme.extended.surface2)
            .border(
                width = 1.dp,
                color = KnotworkTheme.extended.divider,
                shape = KnotworkTheme.shapes.md,
            )
            .padding(KnotworkTheme.spacing.sp2),
    ) {
        Icon(
            imageVector = AppIcons.ImageOff,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceDim,
            modifier = Modifier.size(MISSING_ICON_SIZE),
        )
        Text(
            text = stringResource(R.string.knotwork_attachment_unavailable),
            style = KnotworkTextStyles.LabelSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.knotwork_attachment_removed),
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.onSurfaceDim,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Deterministic stand-in used by previews / snapshots: renders a flat colour
 * block in place of a real decoded image so catalog baselines do not depend on
 * Coil resolving a file. Mirrors [ImageThumbnail]'s clip + click contract.
 */
@Composable
internal fun ImageThumbnailPreviewFill(modifier: Modifier = Modifier, shape: Shape = KnotworkTheme.shapes.md) {
    Image(
        painter = ColorPainter(KnotworkTheme.extended.surface4),
        contentDescription = null,
        modifier = modifier.clip(shape),
    )
}

private val SPINNER_SIZE = 22.dp
private val MISSING_ICON_SIZE = 28.dp
private const val SPINNER_PERIOD_MS = 900
private const val FULL_TURN_DEGREES = 360f
