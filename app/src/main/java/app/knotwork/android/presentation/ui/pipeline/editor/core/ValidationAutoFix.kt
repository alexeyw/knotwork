package app.knotwork.android.presentation.ui.pipeline.editor.core

import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineValidationError
import java.util.UUID

/**
 * Best-effort auto-fix recipes for the [PipelineValidationError]s surfaced by
 * `PipelineGraph.validate()`. Each recipe transforms an input graph into a
 * (hopefully) valid one without user intervention.
 *
 * Drives the `Auto-fix` action on the redesigned `ValidationBar`.
 * Idempotent: applying the recipes to an
 * already-valid graph returns it unchanged.
 *
 * The fixer is **conservative** — it only acts when the fix can't make the
 * graph worse. Cyclic-graph fixes, for example, would require picking an edge
 * to delete and that pick is too policy-sensitive to automate; we leave those
 * for the user. The `Auto-fix` button gracefully reports "no safe fix
 * available" via [AutoFixOutcome] when no recipe applies.
 */
object ValidationAutoFix {

    /**
     * Applies every applicable recipe to [graph] for the supplied [errors].
     * Returns the resulting graph plus an [AutoFixOutcome] describing what
     * happened so the caller can surface a snackbar.
     *
     * Recipes are applied in order:
     *  1. MissingInput → drop an INPUT node at the upper-left grid square.
     *  2. MissingOutput → drop an OUTPUT node below the lowest existing node.
     *  3. MultipleInputs / MultipleOutputs → keep the first, delete the rest.
     *  4. DisconnectedInput → connect the INPUT to the topmost non-INPUT node.
     *  5. DisconnectedOutput → connect the OUTPUT to the bottommost non-OUTPUT node.
     *  6. UnreachableNode / DeadEndNode / HasCycles / NodeEmptyContext →
     *     **no auto-fix** (require human judgement).
     */
    fun apply(graph: PipelineGraph, errors: List<PipelineValidationError>): AutoFixOutcome {
        var working = graph
        val appliedRecipes = mutableListOf<String>()
        errors.distinct().forEach { error ->
            val before = working
            val after = applyRecipe(working, error)
            if (after !== before) {
                working = after
                appliedRecipes += recipeNameFor(error)
            }
        }
        return AutoFixOutcome(
            graph = working,
            appliedRecipes = appliedRecipes.toList(),
            unchanged = working === graph,
        )
    }

    /**
     * Routes a single [error] to its recipe. Returns the unchanged graph when
     * the error class has no recipe.
     */
    @Suppress("ReturnCount")
    private fun applyRecipe(graph: PipelineGraph, error: PipelineValidationError): PipelineGraph = when (error) {
        PipelineValidationError.MissingInput -> addInputNode(graph)
        PipelineValidationError.MissingOutput -> addOutputNode(graph)
        PipelineValidationError.MultipleInputs -> keepFirstNodeOfType(graph, NodeType.INPUT)
        PipelineValidationError.MultipleOutputs -> keepFirstNodeOfType(graph, NodeType.OUTPUT)
        PipelineValidationError.DisconnectedInput -> connectInputToTopmostPeer(graph)
        PipelineValidationError.DisconnectedOutput -> connectBottommostPeerToOutput(graph)
        // Recipes that would require human judgement — skip silently.
        PipelineValidationError.HasCycles -> graph
        PipelineValidationError.UnreachableNode -> graph
        PipelineValidationError.DeadEndNode -> graph
        is PipelineValidationError.NodeEmptyContext -> graph
        // Composition errors need the user to re-pick a target / restructure
        // the call graph — no safe automatic recipe.
        is PipelineValidationError.MissingTargetPipeline -> graph
        is PipelineValidationError.TargetPipelineNotFound -> graph
        is PipelineValidationError.PipelineCycle -> graph
        is PipelineValidationError.PipelineNestingTooDeep -> graph
        // A SKILL node needs the user to pick a valid skill — no safe recipe.
        is PipelineValidationError.MissingSkill -> graph
        is PipelineValidationError.SkillNotFound -> graph
    }

    private fun addInputNode(graph: PipelineGraph): PipelineGraph {
        if (graph.nodes.any { it.type == NodeType.INPUT }) return graph
        val node = NodeModel(
            id = newNodeId(),
            type = NodeType.INPUT,
            label = "Input",
            x = 0f,
            y = 0f,
        )
        return graph.copy(nodes = listOf(node) + graph.nodes)
    }

    private fun addOutputNode(graph: PipelineGraph): PipelineGraph {
        if (graph.nodes.any { it.type == NodeType.OUTPUT }) return graph
        val lowestY = graph.nodes.maxOfOrNull { it.y } ?: 0f
        val node = NodeModel(
            id = newNodeId(),
            type = NodeType.OUTPUT,
            label = "Output",
            x = 0f,
            y = lowestY + AUTO_FIX_VERTICAL_SPACING,
        )
        return graph.copy(nodes = graph.nodes + node)
    }

    private fun keepFirstNodeOfType(graph: PipelineGraph, type: NodeType): PipelineGraph {
        val matching = graph.nodes.filter { it.type == type }
        if (matching.size <= 1) return graph
        val keep = matching.first().id
        val drop = matching.drop(1).map { it.id }.toSet()
        return graph.copy(
            nodes = graph.nodes.filter { it.id !in drop },
            connections = graph.connections.filter { it.sourceNodeId !in drop && it.targetNodeId !in drop },
        )
    }

    private fun connectInputToTopmostPeer(graph: PipelineGraph): PipelineGraph {
        val input = graph.nodes.firstOrNull { it.type == NodeType.INPUT } ?: return graph
        val alreadyConnected = graph.connections.any { it.sourceNodeId == input.id }
        if (alreadyConnected) return graph
        val target = graph.nodes
            .filter { it.id != input.id && it.type != NodeType.INPUT }
            .minByOrNull { it.y }
            ?: return graph
        return graph.copy(
            connections = graph.connections + ConnectionModel(
                id = newConnectionId(),
                sourceNodeId = input.id,
                targetNodeId = target.id,
                label = null,
            ),
        )
    }

    private fun connectBottommostPeerToOutput(graph: PipelineGraph): PipelineGraph {
        val output = graph.nodes.firstOrNull { it.type == NodeType.OUTPUT } ?: return graph
        val alreadyConnected = graph.connections.any { it.targetNodeId == output.id }
        if (alreadyConnected) return graph
        val source = graph.nodes
            .filter { it.id != output.id && it.type != NodeType.OUTPUT }
            .maxByOrNull { it.y }
            ?: return graph
        return graph.copy(
            connections = graph.connections + ConnectionModel(
                id = newConnectionId(),
                sourceNodeId = source.id,
                targetNodeId = output.id,
                label = null,
            ),
        )
    }

    private fun recipeNameFor(error: PipelineValidationError): String = when (error) {
        PipelineValidationError.MissingInput -> "add-input"
        PipelineValidationError.MissingOutput -> "add-output"
        PipelineValidationError.MultipleInputs -> "dedupe-inputs"
        PipelineValidationError.MultipleOutputs -> "dedupe-outputs"
        PipelineValidationError.DisconnectedInput -> "connect-input"
        PipelineValidationError.DisconnectedOutput -> "connect-output"
        PipelineValidationError.HasCycles -> ""
        PipelineValidationError.UnreachableNode -> ""
        PipelineValidationError.DeadEndNode -> ""
        is PipelineValidationError.NodeEmptyContext -> ""
        is PipelineValidationError.MissingTargetPipeline -> ""
        is PipelineValidationError.TargetPipelineNotFound -> ""
        is PipelineValidationError.PipelineCycle -> ""
        is PipelineValidationError.PipelineNestingTooDeep -> ""
        is PipelineValidationError.MissingSkill -> ""
        is PipelineValidationError.SkillNotFound -> ""
    }

    private fun newNodeId(): String = "n-" + UUID.randomUUID().toString().substring(0, SHORT_ID_LEN)
    private fun newConnectionId(): String = "c-" + UUID.randomUUID().toString().substring(0, SHORT_ID_LEN)
}

/** Length of the random suffix appended to fresh auto-fix node / connection ids. */
private const val SHORT_ID_LEN = 8

/**
 * Outcome of [ValidationAutoFix.apply].
 *
 * @property graph the post-fix pipeline graph (same reference as input when no
 *   recipe applied).
 * @property appliedRecipes machine-readable recipe identifiers — useful for
 *   telemetry. Empty when no recipe applied.
 * @property unchanged convenience flag: `true` when no recipe applied (`graph
 *   === input`).
 */
data class AutoFixOutcome(val graph: PipelineGraph, val appliedRecipes: List<String>, val unchanged: Boolean)

/** Vertical spacing (canvas units, dp) between the lowest existing node and a freshly-added OUTPUT. */
private const val AUTO_FIX_VERTICAL_SPACING = 160f

/**
 * Returns the node id the editor should focus when the user taps `Go ↗` on the
 * given error row. Errors that don't naturally reference a node (cycle, missing
 * input/output) resolve to `null` so the row's button greys out.
 */
fun PipelineValidationError.focusableNodeId(graph: PipelineGraph): String? = when (this) {
    is PipelineValidationError.NodeEmptyContext -> nodeId
    // Composition errors that pin to a specific PIPELINE node deep-link to it.
    is PipelineValidationError.MissingTargetPipeline -> nodeId
    is PipelineValidationError.TargetPipelineNotFound -> nodeId
    // SKILL errors pin to the offending SKILL node — deep-link to it.
    is PipelineValidationError.MissingSkill -> nodeId
    is PipelineValidationError.SkillNotFound -> nodeId
    PipelineValidationError.DisconnectedInput -> graph.nodes.firstOrNull { it.type == NodeType.INPUT }?.id
    PipelineValidationError.DisconnectedOutput -> graph.nodes.firstOrNull { it.type == NodeType.OUTPUT }?.id
    PipelineValidationError.MultipleInputs -> graph.nodes.firstOrNull { it.type == NodeType.INPUT }?.id
    PipelineValidationError.MultipleOutputs -> graph.nodes.firstOrNull { it.type == NodeType.OUTPUT }?.id
    PipelineValidationError.MissingInput,
    PipelineValidationError.MissingOutput,
    PipelineValidationError.HasCycles,
    PipelineValidationError.UnreachableNode,
    PipelineValidationError.DeadEndNode,
    // A cycle / depth breach spans multiple pipelines — no single node to focus.
    is PipelineValidationError.PipelineCycle,
    is PipelineValidationError.PipelineNestingTooDeep,
    -> null
}
