package app.knotwork.android.presentation.ui.pipeline.editor.core

import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [EditorState.frameIfNeeded] and [EditorState.requestFit] — how a pipeline is
 * framed when it opens.
 *
 * The case that motivated framing: once canvas units became dp, a three-card preset spans
 * 728 dp, wider than a phone, and opening it at 100 % from the canvas origin cut the last
 * card off. The rules pinned here keep that fix from turning into a view that jumps while
 * the user is editing.
 */
class EditorStateFramingTest {

    private fun graph(id: String, vararg xs: Float) = PipelineGraph(
        id = id,
        name = id,
        nodes = xs.mapIndexed { index, x -> NodeModel(id = "n$index", type = NodeType.LITE_RT, x = x, y = 200f) },
    )

    // Styled Translation's row: 80 / 320 / 560, so the bbox is 80..728 × 200..296 dp.
    private val translation = graph("styled", 80f, 320f, 560f)

    @Test
    fun `given an unmeasured viewport when framing then nothing happens and the frame is still owed`() {
        val editor = EditorState(density = 3f)

        assertFalse(editor.frameIfNeeded(translation, viewportW = 0f, viewportH = 0f, paddingPx = 0f))
        assertTrue(editor.frameIfNeeded(translation, viewportW = 1236f, viewportH = 2400f, paddingPx = 0f))
    }

    @Test
    fun `given a graph wider than the screen when first shown then every card is inside the viewport`() {
        // 412 dp phone at density 3 = 1236 px wide.
        val editor = EditorState(density = 3f)

        assertTrue(editor.frameIfNeeded(translation, viewportW = 1236f, viewportH = 2400f, paddingPx = 96f))

        val t = editor.transform
        assertTrue(t.scale < 1f)
        assertTrue("left card cut off", t.canvasToScreenX(80f) >= 0f)
        assertTrue("right card cut off", t.canvasToScreenX(560f + NodeCardFootprint.WIDTH) <= 1236f)
    }

    @Test
    fun `given a graph smaller than the screen when first shown then it is not zoomed past 100 percent`() {
        val editor = EditorState(density = 3f)

        editor.frameIfNeeded(graph("tiny", 0f), viewportW = 1236f, viewportH = 2400f, paddingPx = 0f)

        assertEquals(1f, editor.transform.scale, 0f)
    }

    @Test
    fun `given a pipeline already framed when shown again then the user's pan and zoom are kept`() {
        val editor = EditorState(density = 3f)
        editor.frameIfNeeded(translation, viewportW = 1236f, viewportH = 2400f, paddingPx = 0f)
        val userView = editor.transform.copy(scale = 1.7f, offsetX = -300f)
        editor.transform = userView

        // A later edit, IME resize or recomposition re-runs the effect.
        assertFalse(
            editor.frameIfNeeded(graph("styled", 0f, 999f), viewportW = 1236f, viewportH = 1800f, paddingPx = 0f),
        )
        assertEquals(userView, editor.transform)
    }

    @Test
    fun `given an empty pipeline when the first node is added then the view does not jump`() {
        val editor = EditorState(density = 3f)
        assertFalse(editor.frameIfNeeded(graph("new"), viewportW = 1236f, viewportH = 2400f, paddingPx = 0f))
        val before = editor.transform

        assertFalse(editor.frameIfNeeded(graph("new", 5000f), viewportW = 1236f, viewportH = 2400f, paddingPx = 0f))
        assertEquals(before, editor.transform)
    }

    @Test
    fun `given a template requested into an empty pipeline when its nodes arrive then they are framed once`() {
        val editor = EditorState(density = 3f)
        editor.frameIfNeeded(graph("same-id"), viewportW = 1236f, viewportH = 2400f, paddingPx = 0f)

        editor.requestFit()
        // The request is made before the template has materialised: still empty.
        assertFalse(editor.frameIfNeeded(graph("same-id"), viewportW = 1236f, viewportH = 2400f, paddingPx = 0f))
        assertTrue(editor.fitRequested)

        assertTrue(
            editor.frameIfNeeded(
                graph("same-id", 80f, 320f, 560f),
                viewportW = 1236f,
                viewportH = 2400f,
                paddingPx = 0f,
            ),
        )
        assertFalse(editor.fitRequested)
        assertFalse(
            editor.frameIfNeeded(
                graph("same-id", 80f, 320f, 900f),
                viewportW = 1236f,
                viewportH = 2400f,
                paddingPx = 0f,
            ),
        )
    }

    @Test
    fun `given a different pipeline id when shown then it gets its own opening frame`() {
        val editor = EditorState(density = 3f)
        editor.frameIfNeeded(graph("placeholder"), viewportW = 1236f, viewportH = 2400f, paddingPx = 0f)

        assertTrue(editor.frameIfNeeded(translation, viewportW = 1236f, viewportH = 2400f, paddingPx = 0f))
    }
}
