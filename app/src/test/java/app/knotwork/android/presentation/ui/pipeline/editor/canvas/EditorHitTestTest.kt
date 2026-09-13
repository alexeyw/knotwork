package app.knotwork.android.presentation.ui.pipeline.editor.canvas

import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.presentation.ui.pipeline.editor.core.CanvasTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the editor's canvas-space hit-test geometry — the maths behind
 * connection creation. These cover the two regressions users hit:
 *
 *  - releasing a connection anywhere on the target node's card must connect (not only on a
 *    pixel-precise top-edge anchor), at any zoom — [hitTestInputNode];
 *  - a press on an outbound port must be recognised as a connection start so the canvas can
 *    decline to pan it — [hitTestOutboundPort].
 *
 * Canvas units are dp, so every coordinate below is a dp on the card (168 × 96 at most) and
 * no display density is involved: the geometry answers the same on every screen, which is
 * the property the canvas-units change exists for.
 *
 * The pure geometry is exercised here (no Compose runtime); gesture arbitration itself is
 * device-only and out of scope for the JVM gate.
 */
class EditorHitTestTest {

    private fun node(id: String, type: NodeType, x: Float, y: Float): NodeModel =
        NodeModel(id = id, type = type, x = x, y = y)

    // A LITE_RT card at canvas origin spans (0,0)..(168,96).
    private val liteRt = node("n1", NodeType.LITE_RT, x = 0f, y = 0f)

    @Test
    fun `release on the node body connects to that node`() {
        // Card centre — the spot the old anchor-radius test missed at high zoom.
        val hit = hitTestInputNode(pointerCanvasX = 84f, pointerCanvasY = 48f, nodes = listOf(liteRt))
        assertEquals("n1", hit?.id)
    }

    @Test
    fun `release near the bottom of the node body still connects`() {
        val hit = hitTestInputNode(pointerCanvasX = 20f, pointerCanvasY = 90f, nodes = listOf(liteRt))
        assertEquals("n1", hit?.id)
    }

    @Test
    fun `release far from every node connects to nothing`() {
        val hit = hitTestInputNode(pointerCanvasX = 1000f, pointerCanvasY = 1000f, nodes = listOf(liteRt))
        assertNull(hit)
    }

    @Test
    fun `release between two nodes picks the nearer card`() {
        val far = node("n2", NodeType.LITE_RT, x = 500f, y = 500f)
        val hit = hitTestInputNode(pointerCanvasX = 84f, pointerCanvasY = 48f, nodes = listOf(liteRt, far))
        assertEquals("n1", hit?.id)
    }

    @Test
    fun `release near a target stacked just below the source connects to the target, not the source`() {
        // Source card at origin (centre y=48); target just below it (centre y=178). A release
        // in the gap near the target's top edge (y=100) is actually closer to the SOURCE centre
        // (52) than the target centre (78) — without excluding the source this resolves to a
        // self-drop and the connection is rejected. This is the tightly-stacked pair that
        // refused to connect while every other pair worked.
        val source = node("src", NodeType.LITE_RT, x = 0f, y = 0f)
        val target = node("tgt", NodeType.TOOL, x = 0f, y = 130f)
        val nodes = listOf(source, target)
        // Without excluding the source the buggy nearest-centre pick returns the source…
        assertEquals("src", hitTestInputNode(84f, 100f, nodes)?.id)
        // …excluding it (the real call passes the draft's source id) lands on the target.
        assertEquals("tgt", hitTestInputNode(84f, 100f, nodes, excludeNodeId = "src")?.id)
    }

    @Test
    fun `press on the single outbound port is recognised`() {
        // Default port anchor: (width/2, baseHeight) = (84, 64).
        val hit = hitTestOutboundPort(
            pointerCanvasX = 84f,
            pointerCanvasY = 64f,
            nodes = listOf(liteRt),
            transform = CanvasTransform(scale = 1f),
        )
        assertNotNull(hit)
        assertEquals("n1", hit?.nodeId)
        assertEquals("", hit?.label)
    }

    @Test
    fun `press in empty canvas is not a port`() {
        val hit = hitTestOutboundPort(
            pointerCanvasX = 400f,
            pointerCanvasY = 400f,
            nodes = listOf(liteRt),
            transform = CanvasTransform(scale = 1f),
        )
        assertNull(hit)
    }

    @Test
    fun `port grab radius shrinks in canvas space as zoom increases`() {
        // 18 dp below the anchor: within the 20 dp tolerance at scale 1, outside it at
        // scale 2 (20 / 2 = 10) — the grab area stays visually constant.
        val belowPort = 64f + 18f
        val atScale1 = hitTestOutboundPort(84f, belowPort, listOf(liteRt), CanvasTransform(scale = 1f))
        val atScale2 = hitTestOutboundPort(84f, belowPort, listOf(liteRt), CanvasTransform(scale = 2f))
        assertNotNull(atScale1)
        assertNull(atScale2)
    }

    @Test
    fun `given a dense display when a port is pressed then the grab radius is unchanged in canvas units`() {
        // The tolerance is a screen dp and a canvas unit is a dp, so density must not enter:
        // the same 18 dp press is a hit at density 1 and at density 3. Before canvas units
        // were dp, the tolerance was converted to pixels and so tripled on a dense screen.
        val belowPort = 64f + 18f
        val dense = CanvasTransform(scale = 1f, density = 3f)
        assertNotNull(hitTestOutboundPort(84f, belowPort, listOf(liteRt), dense))
        assertNull(hitTestOutboundPort(84f, 64f + 21f, listOf(liteRt), dense))
    }

    @Test
    fun `IF node exposes two distinct outbound ports the press can target`() {
        val ifNode = node("if1", NodeType.IF_CONDITION, x = 0f, y = 0f)
        // Two ports sit symmetrically around the centre (84) at ±20 dp.
        val left = hitTestOutboundPort(64f, 64f, listOf(ifNode), CanvasTransform(scale = 1f))
        val right = hitTestOutboundPort(104f, 64f, listOf(ifNode), CanvasTransform(scale = 1f))
        assertNotNull("Left True/False port must be grabbable", left)
        assertNotNull("Right True/False port must be grabbable", right)
        assertNotEquals("The two ports must carry different labels", left?.label, right?.label)
    }
}
