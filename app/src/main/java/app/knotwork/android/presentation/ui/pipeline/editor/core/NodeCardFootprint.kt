package app.knotwork.android.presentation.ui.pipeline.editor.core

/**
 * Footprint of a node card on the editor canvas, in canvas units — which are dp.
 *
 * The design system sizes `NodeCard` in dp (168 dp wide, 64–96 dp tall), and the
 * canvas stores node positions in the same unit, so these numbers can be used
 * directly against `NodeModel.x / y` with no density conversion anywhere. Every
 * piece of editor geometry that needs the card's size — port anchors, hit tests,
 * fit-to-view, the mini-map, the auto-layout re-centring box, and the guard that
 * keeps bundled presets from overlapping — reads it from here, so they cannot
 * drift apart.
 */
object NodeCardFootprint {

    /** Width of every card (`NodeCardWidth` in the design system). */
    const val WIDTH: Float = 168f

    /**
     * Height of a card with no body line (`NodeCardMinHeight`). The outbound port
     * row sits on this edge, so edges start from it.
     */
    const val BASE_HEIGHT: Float = 64f

    /**
     * Tallest a card renders (`NodeCardMaxHeight`), reached when it shows a body or
     * error line. Used wherever the whole card must be covered: release targets,
     * fit-to-view bounds and overlap checks.
     */
    const val MAX_HEIGHT: Float = 96f
}
