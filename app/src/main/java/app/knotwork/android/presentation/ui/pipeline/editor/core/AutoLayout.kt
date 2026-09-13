package app.knotwork.android.presentation.ui.pipeline.editor.core

import app.knotwork.android.domain.models.PipelineGraph
import kotlin.math.max

/**
 * Pure-Kotlin Sugiyama-style hierarchical auto-layout for a [PipelineGraph].
 *
 * Three-pass algorithm:
 *  1. **Layering** — longest-path from each [app.knotwork.android.domain.models.NodeType.INPUT].
 *     Disconnected nodes (without any predecessor) land on layer 0; nodes reachable from
 *     several inputs collapse onto the deepest layer that respects all predecessors.
 *     Back-edges (cycles such as a QUEUE_PROCESSOR re-iteration loop) are ignored for
 *     layering, so a cyclic graph lays out without overflowing the resolution stack.
 *  2. **Ordering** — within each layer the nodes are sorted by the median index of their
 *     incoming neighbours on the layer above (single-pass median heuristic; sufficient
 *     for the small graphs the editor handles).
 *  3. **Coordinates** — nodes get evenly-spaced X positions (centred at zero) on their
 *     layer and a fixed-stride Y per layer; both X and Y are snapped to [CanvasTransform.GRID_STEP]
 *     so the result lines up with the drag-and-drop snap grid.
 *
 * The function also returns the per-node depth so callers can apply the spec's 80 ms
 * stagger when animating to the new positions.
 *
 * Pure Kotlin — no Compose dependency — so the layout is JVM-testable.
 */
object AutoLayout {

    /**
     * Default vertical centre-to-centre step between layers, in canvas units (dp). A card
     * runs up to ~126 dp tall once its inbound and outbound port labels are counted, so
     * 216 dp leaves ~90 dp of clear air between layers. Canvas units are the unit the cards
     * are laid out in, so this is the on-screen spacing at every display density.
     */
    const val LAYER_GAP_Y: Float = 216f

    /**
     * Default horizontal centre-to-centre step between siblings on the same layer, in canvas
     * units (dp): the 168 dp card width plus a 72 dp gutter.
     */
    const val SIBLING_GAP_X: Float = 240f

    /**
     * Result of one layout pass.
     *
     * @property positions canvas-space `(x, y)` per node id; values are snapped to
     * [CanvasTransform.GRID_STEP].
     * @property depths zero-based layer index per node id. Used by the editor to schedule
     * a per-depth 80 ms stagger when animating the layout transition.
     */
    data class Result(val positions: Map<String, Pair<Float, Float>>, val depths: Map<String, Int>)

    /**
     * Computes positions for every node in [graph].
     *
     * @param siblingGap horizontal centre-to-centre step between siblings on a layer, in
     * canvas units (dp); defaults to [SIBLING_GAP_X].
     * @param layerGap vertical centre-to-centre step between layers, in canvas units (dp);
     * defaults to [LAYER_GAP_Y].
     * @return a [Result] populated for every node id in [graph]. The map is empty when
     * [graph] has no nodes.
     */
    fun compute(graph: PipelineGraph, siblingGap: Float = SIBLING_GAP_X, layerGap: Float = LAYER_GAP_Y): Result {
        if (graph.nodes.isEmpty()) return Result(emptyMap(), emptyMap())
        val depths = computeDepths(graph)
        val byLayer = depths.entries
            .groupBy({ it.value }, { it.key })
            .toSortedMap()
        val byLayerOrdered = orderWithinLayers(byLayer, graph)
        val positions = assignCoordinates(byLayerOrdered, siblingGap, layerGap)
        return Result(positions = positions, depths = depths)
    }

    // ─── Pass 1 — longest-path layering ──────────────────────────────────────

    private fun computeDepths(graph: PipelineGraph): Map<String, Int> {
        val inbound = mutableMapOf<String, MutableList<String>>()
        graph.nodes.forEach { inbound[it.id] = mutableListOf() }
        graph.connections.forEach { c ->
            inbound.getOrPut(c.targetNodeId) { mutableListOf() }.add(c.sourceNodeId)
        }
        val depth = mutableMapOf<String, Int>()
        // Nodes currently on the resolution stack. The editor permits legitimate
        // back-edges (e.g. a QUEUE_PROCESSOR re-iteration loop, an EVALUATION → Retry
        // edge), which make the predecessor graph cyclic. Re-entering a node that is
        // already being resolved means we followed such a back-edge: ignore it for
        // layering instead of recursing forever (which would `StackOverflowError`).
        val onStack = mutableSetOf<String>()
        fun resolve(id: String): Int {
            depth[id]?.let { return it }
            if (!onStack.add(id)) return 0
            val result = inbound[id].orEmpty()
                .filter { it != id } // drop self-loops outright
                .maxOfOrNull { resolve(it) }
                ?.plus(1) ?: 0
            onStack.remove(id)
            depth[id] = result
            return result
        }
        graph.nodes.forEach { resolve(it.id) }
        return depth
    }

    // ─── Pass 2 — median heuristic within each layer ─────────────────────────

    private fun orderWithinLayers(byLayer: Map<Int, List<String>>, graph: PipelineGraph): Map<Int, List<String>> {
        if (byLayer.isEmpty()) return emptyMap()
        val ordered = mutableMapOf<Int, List<String>>()
        // Sort the seed layer deterministically by node id so a fresh graph always lays
        // out the same way; downstream layers inherit the ordering through the median.
        ordered[0] = byLayer[0]?.sorted().orEmpty()
        val maxDepth = byLayer.keys.max()
        for (depth in 1..maxDepth) {
            val above = ordered[depth - 1].orEmpty()
            val aboveIndex = above.withIndex().associate { it.value to it.index }
            val children = byLayer[depth].orEmpty()
            ordered[depth] = children.sortedWith(
                compareBy(
                    { node ->
                        val parents = graph.connections
                            .filter { it.targetNodeId == node }
                            .mapNotNull { aboveIndex[it.sourceNodeId] }
                        if (parents.isEmpty()) Float.MAX_VALUE else medianOf(parents)
                    },
                    // Stable tiebreaker so the test corpus is deterministic.
                    { it },
                ),
            )
        }
        return ordered
    }

    private fun medianOf(values: List<Int>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid].toFloat()
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2f
        }
    }

    // ─── Pass 3 — coordinates with grid snapping ─────────────────────────────

    private fun assignCoordinates(
        byLayer: Map<Int, List<String>>,
        siblingGap: Float,
        layerGap: Float,
    ): Map<String, Pair<Float, Float>> {
        if (byLayer.isEmpty()) return emptyMap()
        val widest = byLayer.values.maxOfOrNull { it.size } ?: 0
        val positions = mutableMapOf<String, Pair<Float, Float>>()
        byLayer.forEach { (depth, layerNodes) ->
            val layerWidth = max(1, layerNodes.size)
            val startX = -siblingGap * (layerWidth - 1) / 2f
            layerNodes.forEachIndexed { index, id ->
                val rawX = startX + index * siblingGap
                val rawY = depth * layerGap
                positions[id] = CanvasTransform.snapToGrid(rawX) to CanvasTransform.snapToGrid(rawY)
            }
        }
        // Suppress unused so Kotlin doesn't warn when "widest" is read only by tests
        // through the result map's structure (kept as a guard in case future logic needs it).
        @Suppress("UNUSED_VARIABLE")
        val _widestCount = widest
        return positions
    }
}
