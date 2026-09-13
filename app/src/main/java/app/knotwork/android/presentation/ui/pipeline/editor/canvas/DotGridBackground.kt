package app.knotwork.android.presentation.ui.pipeline.editor.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.knotwork.android.presentation.ui.pipeline.editor.core.CanvasTransform
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Renders the canvas-space dot grid used as the editor background. Tracks
 * [transform] so the dots pan + zoom together with the rest of the canvas
 * (`canvas: 1200 × 1600 · 24 dp grid · 1.00×` as the info pill phrases it).
 *
 * The grid spacing matches [CanvasTransform.GRID_STEP] — the same value used by
 * `snapToGrid` — so a snapped node always rests on a visible intersection.
 *
 * The spacing is in canvas units (dp), so it scales with [transform] — zoom and display
 * density together — and a card always spans the same number of grid steps. The dot
 * radius, by contrast, is a fixed screen dp: zooming spreads the dots, not fattens them.
 *
 * Performance: a 1× scale viewport of 412 × 915 dp at a 24 dp grid renders ~650 dots
 * per frame — small enough for a `Canvas` draw pass without staggering or recycling.
 *
 * @param transform pan / zoom transform from the editor state.
 * @param modifier optional layout modifier (typically `.fillMaxSize()`).
 */
@Composable
internal fun DotGridBackground(transform: CanvasTransform, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val dotColor = Color(red = 0f, green = 0f, blue = 0f, alpha = DOT_ALPHA_LIGHT)
    val dotRadiusPx = with(density) { DOT_RADIUS_DP.dp.toPx() }
    Canvas(modifier = modifier.fillMaxSize()) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val gridScreen = CanvasTransform.GRID_STEP * transform.pixelsPerUnit
        if (gridScreen < MIN_VISIBLE_GRID_PX) return@Canvas
        // Find first grid line on or after the left/top edge of the viewport.
        val grid = CanvasTransform.GRID_STEP
        val firstCanvasX = floor(transform.screenToCanvasX(0f) / grid) * grid
        val firstCanvasY = floor(transform.screenToCanvasY(0f) / grid) * grid
        val lastCanvasX = ceil(transform.screenToCanvasX(size.width) / grid) * grid
        val lastCanvasY = ceil(transform.screenToCanvasY(size.height) / grid) * grid
        var canvasX = firstCanvasX
        while (canvasX <= lastCanvasX) {
            var canvasY = firstCanvasY
            while (canvasY <= lastCanvasY) {
                drawCircle(
                    color = dotColor,
                    radius = dotRadiusPx,
                    center = Offset(
                        x = transform.canvasToScreenX(canvasX),
                        y = transform.canvasToScreenY(canvasY),
                    ),
                )
                canvasY += CanvasTransform.GRID_STEP
            }
            canvasX += CanvasTransform.GRID_STEP
        }
    }
}

/** Visual radius of every grid dot; small enough not to compete with node ports. */
private const val DOT_RADIUS_DP = 1f

/** Dot tint alpha — kept low so the grid reads as a hint rather than a print. */
private const val DOT_ALPHA_LIGHT = 0.10f

/**
 * Below this projected grid step the dots fuse into noise. Hide them so the
 * canvas reads cleanly at extreme zoom-out (`0.4×`).
 */
private const val MIN_VISIBLE_GRID_PX = 6f
