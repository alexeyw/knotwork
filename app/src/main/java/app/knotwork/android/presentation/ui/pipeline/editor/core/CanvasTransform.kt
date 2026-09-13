package app.knotwork.android.presentation.ui.pipeline.editor.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Affine transform between two coordinate spaces: an infinite "canvas" plane where node
 * positions are stored, and the on-screen viewport.
 *
 * **Canvas units are dp.** A node card is laid out in dp, so its position must be in dp
 * too, or the two disagree by the display density: positions stored as screen pixels
 * spaced cards for a density-1 screen and stacked them on top of each other on a dense
 * one (about 3× on a flagship phone). It is also the unit every other producer of
 * pipeline files already used without saying so — the bundled presets, the browser
 * editor (CSS pixels) and hand-written recipes — so a file authored anywhere lays out
 * the same on every device.
 *
 * The transform is uniform-scale + translation only — there is no rotation or shear in
 * the editor — so it is encoded as `(scale, offsetX, offsetY)` plus the display
 * [density], rather than a full 3×3 matrix. All math is pure Kotlin so it can be
 * unit-tested off the device.
 *
 * `screen = canvas * scale * density + offset` ⇔ `canvas = (screen - offset) / (scale * density)`
 *
 * Scale clamped to `[0.4f, 2.0f]`.
 *
 * @property scale the user's zoom; `1.0f` draws a card at its natural dp size. This is
 *   the value the editor shows as a percentage, and the one [MIN_SCALE] / [MAX_SCALE]
 *   clamp — density is not part of it.
 * @property offsetX horizontal screen-space translation applied after scaling.
 * @property offsetY vertical screen-space translation applied after scaling.
 * @property density screen pixels per dp on the display the canvas is drawn on. The
 *   editor sets it from the composition's `LocalDensity`; the default of `1f` makes
 *   canvas units and pixels coincide, which is what the pure geometry tests assume.
 */
data class CanvasTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val density: Float = 1f,
) {

    /** Screen pixels one canvas unit covers at the current zoom: [scale] × [density]. */
    val pixelsPerUnit: Float get() = scale * density

    /**
     * Maps a canvas-space point onto screen pixels.
     */
    fun canvasToScreenX(x: Float): Float = x * pixelsPerUnit + offsetX

    /** @see canvasToScreenX */
    fun canvasToScreenY(y: Float): Float = y * pixelsPerUnit + offsetY

    /**
     * Maps a screen-pixel point back to canvas space.
     */
    fun screenToCanvasX(x: Float): Float = (x - offsetX) / pixelsPerUnit

    /** @see screenToCanvasX */
    fun screenToCanvasY(y: Float): Float = (y - offsetY) / pixelsPerUnit

    /**
     * Returns a copy with [scale] adjusted by [factor] around the screen-space anchor
     * `(anchorX, anchorY)`. The anchor is the pinch midpoint — the canvas point under
     * the user's fingers stays fixed while the surroundings scale.
     *
     * @param factor multiplicative delta from a gesture handler (`zoom` parameter on
     * `transformable`). Clamped so the resulting scale stays within [MIN_SCALE]..[MAX_SCALE].
     * @param anchorX screen-X of the pinch midpoint.
     * @param anchorY screen-Y of the pinch midpoint.
     */
    fun zoomedBy(factor: Float, anchorX: Float, anchorY: Float): CanvasTransform {
        val nextScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        if (nextScale == scale) return this
        val canvasX = screenToCanvasX(anchorX)
        val canvasY = screenToCanvasY(anchorY)
        return copy(
            scale = nextScale,
            offsetX = anchorX - canvasX * nextScale * density,
            offsetY = anchorY - canvasY * nextScale * density,
        )
    }

    /**
     * Returns a copy translated by a screen-space delta. Used by the canvas pan handler.
     */
    fun panBy(dxScreen: Float, dyScreen: Float): CanvasTransform =
        copy(offsetX = offsetX + dxScreen, offsetY = offsetY + dyScreen)

    /**
     * Returns a copy whose viewport is centred on the canvas-space point `(x, y)`.
     * The resulting transform keeps the current [scale]; the offset is recomputed
     * so the named point lands at `(viewportW / 2, viewportH / 2)` in screen space.
     *
     * @param x canvas-X to centre on.
     * @param y canvas-Y to centre on.
     * @param viewportW viewport width in pixels.
     * @param viewportH viewport height in pixels.
     */
    fun centeredOn(x: Float, y: Float, viewportW: Float, viewportH: Float): CanvasTransform = copy(
        offsetX = viewportW / 2f - x * pixelsPerUnit,
        offsetY = viewportH / 2f - y * pixelsPerUnit,
    )

    /**
     * Returns a copy whose viewport tightly frames [bbox] in canvas space, with
     * [paddingPx] of breathing room on every side. The resulting [scale] is the
     * largest value (clamped to `[MIN_SCALE, MAX_SCALE]`) that lets the padded
     * bbox fit inside `(viewportW, viewportH)`; the offset centres the bbox in
     * the viewport.
     *
     * Powers the toolbar's "Fit to view" action and the mini-map's tap-to-jump
     * behaviour. Degenerate bbox (zero width or height) falls back to a
     * scale-1.0 centered-on-bbox-center transform.
     *
     * @param bbox canvas-space bounds to frame.
     * @param viewportW viewport width in pixels.
     * @param viewportH viewport height in pixels.
     * @param paddingPx screen-space padding to leave around the bbox; pass `0f`
     *   for an edge-to-edge fit.
     * @param maxScale upper bound for the resulting scale, below [MAX_SCALE]. Framing a
     *   pipeline as it opens passes `1f`, so a small graph is shown at its natural size
     *   instead of being blown up to fill the screen.
     */
    fun fitToBounds(
        bbox: Bounds,
        viewportW: Float,
        viewportH: Float,
        paddingPx: Float,
        maxScale: Float = MAX_SCALE,
    ): CanvasTransform {
        if (viewportW <= 0f || viewportH <= 0f) return this
        val cx = (bbox.minX + bbox.maxX) / 2f
        val cy = (bbox.minY + bbox.maxY) / 2f
        val bboxW = bbox.maxX - bbox.minX
        val bboxH = bbox.maxY - bbox.minY
        if (bboxW <= 0f || bboxH <= 0f) {
            // Single-point or zero-dimension bbox: skip the divide-by-zero, just centre.
            return copy(scale = 1f).centeredOn(cx, cy, viewportW, viewportH)
        }
        val availW = max(1f, viewportW - 2f * paddingPx)
        val availH = max(1f, viewportH - 2f * paddingPx)
        // The bbox is in canvas units and the viewport in pixels, so the ratio is pixels per
        // unit; dividing out the density leaves the zoom the user sees.
        val rawScale = min(availW / bboxW, availH / bboxH) / density
        val targetScale = rawScale.coerceIn(MIN_SCALE, maxScale.coerceIn(MIN_SCALE, MAX_SCALE))
        return copy(scale = targetScale).centeredOn(cx, cy, viewportW, viewportH)
    }

    /**
     * Returns a copy zoomed by one discrete step around the viewport centre.
     * Used by the always-visible zoom rail's `+` / `−` buttons.
     *
     * `direction > 0` zooms in (`ZOOM_STEP`); `direction < 0` zooms out (`1 /
     * ZOOM_STEP`). The result is clamped to `[MIN_SCALE, MAX_SCALE]` like every
     * other zoom path.
     */
    fun zoomedOneStep(direction: Int, viewportW: Float, viewportH: Float): CanvasTransform {
        if (direction == 0) return this
        val factor = if (direction > 0) ZOOM_STEP else 1f / ZOOM_STEP
        return zoomedBy(factor, anchorX = viewportW / 2f, anchorY = viewportH / 2f)
    }

    companion object {
        /** Lower bound for [scale]. */
        const val MIN_SCALE: Float = 0.4f

        /** Upper bound for [scale]. */
        const val MAX_SCALE: Float = 2.0f

        /**
         * Discrete zoom multiplier per click of the zoom rail's `+` button. Matches
         * Figma's `√2`-rounded step (1.25× ⇒ four clicks roughly double scale).
         */
        const val ZOOM_STEP: Float = 1.25f

        /** Grid spacing for snap-to-grid, in canvas units (dp). */
        const val GRID_STEP: Float = 24f

        /**
         * Snaps [value] to the nearest [GRID_STEP] step. Pure helper used both at drag-release
         * commit time and by the auto-layout module so every node position is grid-aligned.
         */
        fun snapToGrid(value: Float): Float = round(value / GRID_STEP) * GRID_STEP
    }
}

/**
 * Inclusive axis-aligned canvas-space bounds (`minX..maxX × minY..maxY`).
 *
 * Used by [CanvasTransform.fitToBounds] to frame an arbitrary set of points
 * (typically the bbox of every node in the graph plus their card geometry)
 * inside the viewport.
 */
data class Bounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {

    /** Width of the bbox in canvas units. Non-negative. */
    val width: Float get() = max(0f, maxX - minX)

    /** Height of the bbox in canvas units. Non-negative. */
    val height: Float get() = max(0f, maxY - minY)

    /** `true` when this bbox has zero area (a single point or an empty selection). */
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    companion object {
        /**
         * Builds the smallest [Bounds] containing every point in [points]. Returns
         * `null` when [points] is empty so the caller can short-circuit without
         * having to pick a sentinel value.
         */
        fun of(points: Iterable<Pair<Float, Float>>): Bounds? {
            var first = true
            var minX = 0f
            var minY = 0f
            var maxX = 0f
            var maxY = 0f
            for ((x, y) in points) {
                if (first) {
                    minX = x
                    minY = y
                    maxX = x
                    maxY = y
                    first = false
                } else {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
            return if (first) null else Bounds(minX, minY, maxX, maxY)
        }

        /**
         * Builds the [Bounds] of every node card, treating each node as a rectangle
         * of `nodeWidth × nodeHeight` rooted at its top-left position. This matches
         * how [app.knotwork.design.components.pipelineeditor.NodeCard] is laid out
         * on canvas — `NodeModel.x / y` is the card's top-left.
         */
        fun ofNodes(positions: Iterable<Pair<Float, Float>>, nodeWidth: Float, nodeHeight: Float): Bounds? {
            var first = true
            var minX = 0f
            var minY = 0f
            var maxX = 0f
            var maxY = 0f
            for ((x, y) in positions) {
                val right = x + nodeWidth
                val bottom = y + nodeHeight
                if (first) {
                    minX = x
                    minY = y
                    maxX = right
                    maxY = bottom
                    first = false
                } else {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (right > maxX) maxX = right
                    if (bottom > maxY) maxY = bottom
                }
            }
            return if (first) null else Bounds(minX, minY, maxX, maxY)
        }
    }
}
