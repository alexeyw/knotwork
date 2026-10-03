package app.knotwork.android.presentation.ui.pipeline.editor.core

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pure-Kotlin helpers for the cubic-Bezier edges drawn between pipeline node ports.
 *
 * The edge geometry is symmetric: control points sit on a vertical line drawn between
 * the two endpoints, with the offset scaled by the absolute vertical gap so loops and
 * short edges still draw a recognisable curve.
 *
 * Pure math — no Compose dependency — so the helpers are JVM-testable. The canvas
 * caller transforms each result via [CanvasTransform] before drawing.
 */
object BezierEdge {

    /**
     * Computes the two cubic-Bezier control points for an edge from
     * `(x0, y0)` → `(x1, y1)` in canvas space.
     *
     * @return ordered pair of `(c0, c1)` control points (each a [Pair] of canvas-space x/y).
     */
    fun controlPoints(x0: Float, y0: Float, x1: Float, y1: Float): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val verticalGap = abs(y1 - y0)
        val handle = (verticalGap * HANDLE_RATIO).coerceAtLeast(MIN_HANDLE)
        val c0 = x0 to (y0 + handle)
        val c1 = x1 to (y1 - handle)
        return c0 to c1
    }

    /**
     * Evaluates the cubic Bezier `(x0,y0) → c0 → c1 → (x1,y1)` at parameter [t] ∈ `[0, 1]`.
     *
     * @return canvas-space coordinate at the parameter value.
     */
    fun pointAt(
        t: Float,
        x0: Float,
        y0: Float,
        c0x: Float,
        c0y: Float,
        c1x: Float,
        c1y: Float,
        x1: Float,
        y1: Float,
    ): Pair<Float, Float> {
        val mt = 1f - t
        val mt2 = mt * mt
        val mt3 = mt2 * mt
        val t2 = t * t
        val t3 = t2 * t
        val px = mt3 * x0 + CUBIC_COEFF_3 * mt2 * t * c0x + CUBIC_COEFF_3 * mt * t2 * c1x + t3 * x1
        val py = mt3 * y0 + CUBIC_COEFF_3 * mt2 * t * c0y + CUBIC_COEFF_3 * mt * t2 * c1y + t3 * y1
        return px to py
    }

    /**
     * Approximate arc length of the cubic, sampled at [samples] points.
     *
     * Used by the run-trace traveling-dot animation: dividing the arc length by the
     * dot speed (`40 dp/s` per spec) yields the cycle duration so the dot moves at a
     * visually constant velocity regardless of edge length.
     */
    fun approximateArcLength(
        x0: Float,
        y0: Float,
        c0x: Float,
        c0y: Float,
        c1x: Float,
        c1y: Float,
        x1: Float,
        y1: Float,
        samples: Int = ARC_LENGTH_SAMPLES,
    ): Float {
        require(samples >= 2) { "samples must be >= 2 to evaluate at least one segment" }
        var prevX = x0
        var prevY = y0
        var total = 0f
        for (i in 1..samples) {
            val t = i / samples.toFloat()
            val (px, py) = pointAt(t, x0, y0, c0x, c0y, c1x, c1y, x1, y1)
            total += hypot(px - prevX, py - prevY)
            prevX = px
            prevY = py
        }
        return total
    }

    /**
     * Closest-point hit test for tap-to-edit edge interactions. Returns the minimum
     * distance between [px, py] and the polyline approximation of the cubic. Caller
     * compares against a screen-space threshold (e.g. 12 dp) to decide whether the
     * pointer landed on this edge.
     *
     * Sampled at [samples] points; identical sampling density as [approximateArcLength].
     */
    @Suppress("LongParameterList")
    fun distanceToPoint(
        px: Float,
        py: Float,
        x0: Float,
        y0: Float,
        c0x: Float,
        c0y: Float,
        c1x: Float,
        c1y: Float,
        x1: Float,
        y1: Float,
        samples: Int = HIT_TEST_SAMPLES,
    ): Float {
        var prevX = x0
        var prevY = y0
        var best = Float.MAX_VALUE
        for (i in 1..samples) {
            val t = i / samples.toFloat()
            val (sx, sy) = pointAt(t, x0, y0, c0x, c0y, c1x, c1y, x1, y1)
            val d = distancePointToSegment(px, py, prevX, prevY, sx, sy)
            if (d < best) best = d
            prevX = sx
            prevY = sy
        }
        return best
    }

    /**
     * Distance from point `(px, py)` to the line segment `[(x0, y0), (x1, y1)]`.
     *
     * Standard projection-onto-segment formula; pulled out so the hit-test loop
     * stays readable.
     */
    private fun distancePointToSegment(px: Float, py: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0f) return hypot(px - x0, py - y0)
        val t = ((px - x0) * dx + (py - y0) * dy) / lenSq
        val clamped = t.coerceIn(0f, 1f)
        val projX = x0 + clamped * dx
        val projY = y0 + clamped * dy
        return hypot(px - projX, py - projY)
    }

    /** Bezier handle length is `HANDLE_RATIO * verticalGap` per spec. */
    private const val HANDLE_RATIO = 0.5f

    /** Minimum handle length so near-flat edges still curve visibly. */
    private const val MIN_HANDLE = 24f

    /** Sample count used to approximate the arc length of a single edge. */
    private const val ARC_LENGTH_SAMPLES = 24

    /** Sample count used by the hit-test polyline. */
    private const val HIT_TEST_SAMPLES = 16

    /** Cubic-Bezier polynomial coefficient: appears twice per term in the cubic expansion. */
    private const val CUBIC_COEFF_3 = 3f
}
