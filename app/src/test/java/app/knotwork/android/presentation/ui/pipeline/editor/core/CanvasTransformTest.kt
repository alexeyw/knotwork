package app.knotwork.android.presentation.ui.pipeline.editor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pure-Kotlin tests for [CanvasTransform]. No Android dependency — runs on the JVM.
 */
class CanvasTransformTest {

    @Test
    fun `given identity transform when round-trip then point is preserved`() {
        val t = CanvasTransform()
        val x = 123.4f
        val y = -56.7f
        assertEquals(x, t.screenToCanvasX(t.canvasToScreenX(x)), 1e-3f)
        assertEquals(y, t.screenToCanvasY(t.canvasToScreenY(y)), 1e-3f)
    }

    @Test
    fun `given scaled transform when round-trip then point is preserved`() {
        val t = CanvasTransform(scale = 1.5f, offsetX = 20f, offsetY = -10f)
        assertEquals(50f, t.screenToCanvasX(t.canvasToScreenX(50f)), 1e-3f)
        assertEquals(80f, t.screenToCanvasY(t.canvasToScreenY(80f)), 1e-3f)
    }

    @Test
    fun `given zoom factor above max when zoomedBy then scale clamps to MAX_SCALE`() {
        val t = CanvasTransform(scale = 1.9f)
        val clamped = t.zoomedBy(factor = 5f, anchorX = 0f, anchorY = 0f)
        assertEquals(CanvasTransform.MAX_SCALE, clamped.scale, 0f)
    }

    @Test
    fun `given zoom factor below min when zoomedBy then scale clamps to MIN_SCALE`() {
        val t = CanvasTransform(scale = 0.5f)
        val clamped = t.zoomedBy(factor = 0.1f, anchorX = 0f, anchorY = 0f)
        assertEquals(CanvasTransform.MIN_SCALE, clamped.scale, 0f)
    }

    @Test
    fun `given zoom around anchor when zoomedBy then anchor canvas point lands at same screen point`() {
        val t = CanvasTransform(scale = 1f, offsetX = 0f, offsetY = 0f)
        val anchorScreenX = 200f
        val anchorScreenY = 150f
        val zoomed = t.zoomedBy(factor = 1.5f, anchorX = anchorScreenX, anchorY = anchorScreenY)
        val canvasAnchorX = t.screenToCanvasX(anchorScreenX)
        val canvasAnchorY = t.screenToCanvasY(anchorScreenY)
        assertEquals(anchorScreenX, zoomed.canvasToScreenX(canvasAnchorX), 1e-3f)
        assertEquals(anchorScreenY, zoomed.canvasToScreenY(canvasAnchorY), 1e-3f)
    }

    @Test
    fun `given pan delta when panBy then offsets shift by exactly that delta`() {
        val t = CanvasTransform(scale = 1.5f, offsetX = 10f, offsetY = 20f)
        val panned = t.panBy(dxScreen = 25f, dyScreen = -10f)
        assertEquals(35f, panned.offsetX, 0f)
        assertEquals(10f, panned.offsetY, 0f)
        assertEquals(1.5f, panned.scale, 0f)
    }

    @Test
    fun `given canvas-space anchor when centeredOn then anchor projects to viewport centre`() {
        val t = CanvasTransform(scale = 2f).centeredOn(x = 100f, y = 50f, viewportW = 400f, viewportH = 300f)
        assertEquals(200f, t.canvasToScreenX(100f), 1e-3f)
        assertEquals(150f, t.canvasToScreenY(50f), 1e-3f)
    }

    @Test
    fun `given value mid-grid when snapToGrid then rounds to nearest grid step`() {
        assertEquals(0f, CanvasTransform.snapToGrid(11f), 0f)
        assertEquals(24f, CanvasTransform.snapToGrid(13f), 0f)
        assertEquals(24f, CanvasTransform.snapToGrid(35f), 0f)
        assertEquals(48f, CanvasTransform.snapToGrid(37f), 0f)
        assertEquals(-24f, CanvasTransform.snapToGrid(-13f), 0f)
    }

    @Test
    fun `given identity-scale factor when zoomedBy then transform stays identical`() {
        val t = CanvasTransform(scale = 1f, offsetX = 5f, offsetY = 5f)
        val next = t.zoomedBy(factor = 1f, anchorX = 100f, anchorY = 100f)
        assertEquals(t, next)
    }

    @Test
    fun `given zero scale factor when zoomedBy then never produces NaN offsets`() {
        val t = CanvasTransform(scale = 1f)
        val next = t.zoomedBy(factor = 0.1f, anchorX = 50f, anchorY = 50f)
        assertNotEquals(Float.NaN, next.offsetX)
        assertNotEquals(Float.NaN, next.offsetY)
    }

    // ===== fitToBounds =====

    @Test
    fun `given bbox fits in viewport when fitToBounds then scale frames padded bbox edge-to-edge`() {
        // 400×300 bbox, 800×600 viewport, 20 px padding: bbox * 2 = 800×600, padded fits exactly at scale 2.
        // But MAX_SCALE caps at 2.0 so the result is exactly 2.0.
        val bbox = Bounds(minX = 0f, minY = 0f, maxX = 400f, maxY = 300f)
        val fit = CanvasTransform().fitToBounds(bbox, viewportW = 840f, viewportH = 640f, paddingPx = 20f)
        assertEquals(2.0f, fit.scale, 1e-3f)
    }

    @Test
    fun `given oversize bbox when fitToBounds then scale clamps to MIN_SCALE`() {
        // bbox 10000×10000 in a 400×400 viewport — raw scale 0.04 would be far below MIN_SCALE.
        val bbox = Bounds(minX = 0f, minY = 0f, maxX = 10000f, maxY = 10000f)
        val fit = CanvasTransform().fitToBounds(bbox, viewportW = 400f, viewportH = 400f, paddingPx = 0f)
        assertEquals(CanvasTransform.MIN_SCALE, fit.scale, 0f)
    }

    @Test
    fun `given non-square bbox when fitToBounds then chooses the limiting axis`() {
        // Tall bbox 100×400 in a 600×400 viewport: width-axis scale = 6.0 capped to 2.0;
        // height-axis raw scale = 1.0. Limiting is height, so scale = 1.0.
        val bbox = Bounds(minX = 0f, minY = 0f, maxX = 100f, maxY = 400f)
        val fit = CanvasTransform().fitToBounds(bbox, viewportW = 600f, viewportH = 400f, paddingPx = 0f)
        assertEquals(1.0f, fit.scale, 1e-3f)
    }

    @Test
    fun `given bbox when fitToBounds then centre of bbox lands at viewport centre`() {
        val bbox = Bounds(minX = 100f, minY = 200f, maxX = 300f, maxY = 400f)
        // Centre of bbox = (200, 300).
        val fit = CanvasTransform().fitToBounds(bbox, viewportW = 800f, viewportH = 600f, paddingPx = 16f)
        assertEquals(400f, fit.canvasToScreenX(200f), 1e-2f)
        assertEquals(300f, fit.canvasToScreenY(300f), 1e-2f)
    }

    @Test
    fun `given degenerate point-bbox when fitToBounds then falls back to centred identity scale`() {
        val bbox = Bounds(minX = 50f, minY = 50f, maxX = 50f, maxY = 50f)
        val fit = CanvasTransform(scale = 1.7f).fitToBounds(bbox, viewportW = 400f, viewportH = 400f, paddingPx = 10f)
        assertEquals(1f, fit.scale, 0f)
        // The single point lands at the viewport centre.
        assertEquals(200f, fit.canvasToScreenX(50f), 1e-2f)
        assertEquals(200f, fit.canvasToScreenY(50f), 1e-2f)
    }

    @Test
    fun `given zero-sized viewport when fitToBounds then returns self unchanged`() {
        val original = CanvasTransform(scale = 1.2f, offsetX = 33f, offsetY = 44f)
        val bbox = Bounds(0f, 0f, 100f, 100f)
        assertEquals(original, original.fitToBounds(bbox, viewportW = 0f, viewportH = 600f, paddingPx = 0f))
        assertEquals(original, original.fitToBounds(bbox, viewportW = 600f, viewportH = 0f, paddingPx = 0f))
    }

    // ===== zoomedOneStep =====

    @Test
    fun `given identity transform when zoomedOneStep positive then scale multiplies by ZOOM_STEP`() {
        val zoomed = CanvasTransform(scale = 1f).zoomedOneStep(direction = 1, viewportW = 400f, viewportH = 300f)
        assertEquals(CanvasTransform.ZOOM_STEP, zoomed.scale, 1e-4f)
    }

    @Test
    fun `given identity transform when zoomedOneStep negative then scale divides by ZOOM_STEP`() {
        val zoomed = CanvasTransform(scale = 1f).zoomedOneStep(direction = -1, viewportW = 400f, viewportH = 300f)
        assertEquals(1f / CanvasTransform.ZOOM_STEP, zoomed.scale, 1e-4f)
    }

    @Test
    fun `given zero direction when zoomedOneStep then returns self unchanged`() {
        val original = CanvasTransform(scale = 1.3f, offsetX = 12f, offsetY = -7f)
        assertEquals(original, original.zoomedOneStep(direction = 0, viewportW = 100f, viewportH = 100f))
    }

    @Test
    fun `given zoomedOneStep positive when at MAX_SCALE then result still clamped`() {
        val zoomed = CanvasTransform(scale = CanvasTransform.MAX_SCALE)
            .zoomedOneStep(direction = 1, viewportW = 400f, viewportH = 300f)
        assertEquals(CanvasTransform.MAX_SCALE, zoomed.scale, 0f)
    }

    @Test
    fun `given zoomedOneStep negative when at MIN_SCALE then result still clamped`() {
        val zoomed = CanvasTransform(scale = CanvasTransform.MIN_SCALE)
            .zoomedOneStep(direction = -1, viewportW = 400f, viewportH = 300f)
        assertEquals(CanvasTransform.MIN_SCALE, zoomed.scale, 0f)
    }

    // ===== Display density (canvas units are dp) =====

    @Test
    fun `given a dense display when canvasToScreen then a canvas dp covers density pixels`() {
        val t = CanvasTransform(scale = 1f, offsetX = 10f, offsetY = 20f, density = 3f)
        assertEquals(310f, t.canvasToScreenX(100f), 1e-4f)
        assertEquals(170f, t.canvasToScreenY(50f), 1e-4f)
    }

    @Test
    fun `given zoom and density when pixelsPerUnit then multiplies both`() {
        assertEquals(4.5f, CanvasTransform(scale = 1.5f, density = 3f).pixelsPerUnit, 1e-4f)
    }

    @Test
    fun `given a dense display when round-trip then point is preserved`() {
        val t = CanvasTransform(scale = 0.8f, offsetX = -40f, offsetY = 15f, density = 2.625f)
        assertEquals(123.5f, t.screenToCanvasX(t.canvasToScreenX(123.5f)), 1e-3f)
        assertEquals(-77f, t.screenToCanvasY(t.canvasToScreenY(-77f)), 1e-3f)
    }

    @Test
    fun `given two cards 240 dp apart when drawn at densities 1 and 3 then the screen gap scales with density`() {
        // The regression: presets space cards 240 canvas units apart while a card is 168 dp.
        // With canvas units treated as pixels, a 3x screen drew them 240 px apart — less than
        // one 504 px card. As dp they are 720 px apart, clear of the card.
        val cardWidthPx = NodeCardFootprint.WIDTH * 3f
        val dense = CanvasTransform(density = 3f)
        val gapPx = dense.canvasToScreenX(240f) - dense.canvasToScreenX(0f)
        assertEquals(720f, gapPx, 1e-3f)
        assertEquals(true, gapPx > cardWidthPx)
    }

    @Test
    fun `given a dense display when zoomedBy then anchor canvas point stays under the fingers`() {
        val t = CanvasTransform(scale = 1f, offsetX = 30f, offsetY = -12f, density = 3f)
        val anchorCanvasX = t.screenToCanvasX(500f)
        val anchorCanvasY = t.screenToCanvasY(700f)
        val zoomed = t.zoomedBy(factor = 1.5f, anchorX = 500f, anchorY = 700f)
        assertEquals(500f, zoomed.canvasToScreenX(anchorCanvasX), 1e-2f)
        assertEquals(700f, zoomed.canvasToScreenY(anchorCanvasY), 1e-2f)
        assertEquals(3f, zoomed.density, 0f)
    }

    @Test
    fun `given a dense display when centeredOn then anchor projects to viewport centre`() {
        val t = CanvasTransform(
            scale = 1f,
            density = 3f,
        ).centeredOn(x = 100f, y = 50f, viewportW = 1200f, viewportH = 900f)
        assertEquals(600f, t.canvasToScreenX(100f), 1e-3f)
        assertEquals(450f, t.canvasToScreenY(50f), 1e-3f)
    }

    @Test
    fun `given a dense display when fitToBounds then scale excludes the density`() {
        // 400×300 dp bbox in a 1200×900 px viewport at density 3: pixels per unit = 3,
        // so the zoom the user sees is exactly 1.0 — not 3.0 clamped to MAX_SCALE.
        val bbox = Bounds(minX = 0f, minY = 0f, maxX = 400f, maxY = 300f)
        val fit = CanvasTransform(density = 3f).fitToBounds(bbox, viewportW = 1200f, viewportH = 900f, paddingPx = 0f)
        assertEquals(1f, fit.scale, 1e-3f)
        assertEquals(600f, fit.canvasToScreenX(200f), 1e-2f)
    }

    @Test
    fun `given a small graph when fitToBounds with maxScale 1 then it is not blown up`() {
        val bbox = Bounds(minX = 0f, minY = 0f, maxX = 168f, maxY = 96f)
        val uncapped = CanvasTransform().fitToBounds(bbox, viewportW = 1000f, viewportH = 1000f, paddingPx = 0f)
        val capped = CanvasTransform().fitToBounds(
            bbox,
            viewportW = 1000f,
            viewportH = 1000f,
            paddingPx = 0f,
            maxScale = 1f,
        )
        assertEquals(CanvasTransform.MAX_SCALE, uncapped.scale, 0f)
        assertEquals(1f, capped.scale, 0f)
    }

    @Test
    fun `given maxScale below MIN_SCALE when fitToBounds then MIN_SCALE still wins`() {
        val bbox = Bounds(minX = 0f, minY = 0f, maxX = 100f, maxY = 100f)
        val fit = CanvasTransform().fitToBounds(
            bbox,
            viewportW = 1000f,
            viewportH = 1000f,
            paddingPx = 0f,
            maxScale = 0.1f,
        )
        assertEquals(CanvasTransform.MIN_SCALE, fit.scale, 0f)
    }

    // ===== Bounds =====

    @Test
    fun `given empty points when Bounds-of then returns null`() {
        assertEquals(null, Bounds.of(emptyList()))
    }

    @Test
    fun `given mixed points when Bounds-of then min and max are extremes`() {
        val b = Bounds.of(listOf(1f to 2f, -3f to 5f, 10f to -1f, 4f to 4f))
        assertEquals(Bounds(minX = -3f, minY = -1f, maxX = 10f, maxY = 5f), b)
    }

    @Test
    fun `given node positions when Bounds-ofNodes then card size is included on each axis`() {
        val b = Bounds.ofNodes(
            positions = listOf(0f to 0f, 100f to 50f),
            nodeWidth = 168f,
            nodeHeight = 64f,
        )
        assertEquals(Bounds(minX = 0f, minY = 0f, maxX = 268f, maxY = 114f), b)
    }

    @Test
    fun `given empty nodes when Bounds-ofNodes then returns null`() {
        assertEquals(null, Bounds.ofNodes(emptyList(), nodeWidth = 168f, nodeHeight = 64f))
    }

    @Test
    fun `given degenerate bbox when isEmpty then returns true`() {
        assertEquals(true, Bounds(50f, 50f, 50f, 50f).isEmpty)
        assertEquals(false, Bounds(0f, 0f, 1f, 1f).isEmpty)
    }
}
