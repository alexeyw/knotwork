package app.knotwork.android.presentation.ui.orchestrator.presets

import app.knotwork.android.domain.models.PipelineGraph

/**
 * Builds a compact one-line preview of a [PipelineGraph] for the preset
 * picker card subtitle: e.g. `INPUT → LITE_RT → OUTPUT` or
 * `INPUT → INTENT_ROUTER → LITE_RT → OUTPUT`.
 *
 * The traversal is deliberately tolerant of malformed graphs — bundled
 * presets ship with topologically-valid graphs, but the helper is reused on
 * user-saved presets too, where the graph could in principle have been
 * mutated by an older version of the app:
 *
 * 1. The walk starts at the first `INPUT`-typed node (or the first node when
 *    no `INPUT` exists). If no nodes are present, the helper returns `null`
 *    and the caller writes its placeholder in the interface language.
 * 2. At each step the walker picks the **first outgoing connection** in
 *    declaration order — branches are dropped from the preview because the
 *    card has no room for a fan-out diagram, and the preview only needs to
 *    convey the rough shape.
 * 3. The walk stops when no outgoing connection exists, when the target
 *    node id is missing from `nodes`, or after at most [MAX_NODES] hops
 *    (cycle safety + UI-readable length cap).
 */
internal object GraphFlowPreview {

    /** Maximum number of node-type tokens included in the preview string. */
    private const val MAX_NODES = 6

    /** Arrow used between tokens — non-breaking shape, mirrors browser editor. */
    private const val ARROW = " → "

    /**
     * Builds the preview string for [graph].
     *
     * @param graph The pipeline graph whose node-type chain should be rendered.
     * @return A single-line string like `INPUT → LITE_RT → OUTPUT` — node-type
     *   codes, the same in every language — or `null` when the graph carries no
     *   nodes, for the caller to word in the interface language.
     */
    fun render(graph: PipelineGraph): String? {
        val nodes = graph.nodes
        if (nodes.isEmpty()) return null

        val nodesById = nodes.associateBy { it.id }
        // Outgoing-edges projection: first connection in declaration order
        // wins so the walk is deterministic.
        val outgoing: Map<String, String> = graph.connections
            .groupBy { it.sourceNodeId }
            .mapValues { (_, edges) -> edges.first().targetNodeId }

        val startNode = nodes.firstOrNull { it.type.name == "INPUT" } ?: nodes.first()

        val tokens = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var cursor: String? = startNode.id
        while (cursor != null && tokens.size < MAX_NODES) {
            if (!visited.add(cursor)) break
            val node = nodesById[cursor] ?: break
            tokens.add(node.type.name)
            cursor = outgoing[cursor]
        }

        return tokens.joinToString(separator = ARROW)
    }
}
