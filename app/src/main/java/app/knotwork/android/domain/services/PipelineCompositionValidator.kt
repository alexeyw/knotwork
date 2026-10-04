package app.knotwork.android.domain.services

import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineTargetAvailability
import app.knotwork.android.domain.models.PipelineValidationError
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.RunSettings
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Static, cross-pipeline validator for
 * [NodeType.PIPELINE][app.knotwork.android.domain.models.NodeType.PIPELINE]
 * composition. Where [PipelineGraph.validate] performs the structural,
 * single-graph checks (including a PIPELINE node that names no target), this
 * validator walks the *call graph* — following every PIPELINE node's
 * [app.knotwork.android.domain.models.NodeModel.targetPipelineId] transitively
 * through the [PipelineRepository] — and rejects the three composition hazards
 * that can only be decided with knowledge of the other pipelines:
 *
 * - [PipelineValidationError.TargetPipelineNotFound] — a referenced pipeline id
 *   does not resolve (deleted or never existed).
 * - [PipelineValidationError.PipelineCycle] — a chain of references returns to a
 *   pipeline already on the path (including a self-reference); such a
 *   composition can never terminate.
 * - [PipelineValidationError.PipelineNestingTooDeep] — a reference chain would
 *   run deeper than [RunSettings.pipelineMaxNestingDepth], mirroring the
 *   runtime ceiling enforced by `PipelineNodeExecutor`.
 *
 * Because the call graph is known ahead of time, this is the authoritative
 * defence: a composition rejected here can never start a run. The runtime depth
 * check in the executor is only the safety net for the window between
 * validation and execution.
 *
 * **Root resolution.** The [graph][validate] passed in is the in-memory graph
 * being saved (which may not yet be persisted, or may differ from the persisted
 * copy). Its own id therefore resolves to the supplied instance; every
 * descendant is resolved from the repository. This means closing a cycle by
 * editing the root is detected against the persisted descendants.
 */
class PipelineCompositionValidator @Inject constructor(
    private val pipelineRepository: PipelineRepository,
    private val runSettings: RunSettings,
) {
    /**
     * Validates the composition rooted at [graph].
     *
     * @param graph The in-memory pipeline graph being saved.
     * @param extraResolvable Additional in-memory pipelines that should resolve
     *   ahead of the repository — used when validating a pipeline whose
     *   dependencies are not yet persisted (e.g. every graph of a bundle being
     *   imported, so intra-bundle references, cross-library cycles the bundle
     *   would splice in, and depth are all decided against the incoming set).
     *   The root ([graph]) always wins for its own id.
     * @return The composition errors found; empty when the composition is
     *   sound (and trivially empty when [graph] has no PIPELINE nodes).
     */
    suspend fun validate(
        graph: PipelineGraph,
        extraResolvable: Map<String, PipelineGraph> = emptyMap(),
    ): List<PipelineValidationError> {
        // Cheap short-circuit: nothing to compose if there are no PIPELINE nodes.
        if (graph.nodes.none { it.type == NodeType.PIPELINE }) return emptyList()

        val maxDepth = runSettings.pipelineMaxNestingDepth.first()
        val errors = LinkedHashSet<PipelineValidationError>()
        // Descendants resolve from the supplied in-memory set first, then the
        // repo. The root resolves to its own in-memory instance regardless.
        val resolved = extraResolvable.toMutableMap().apply { put(graph.id, graph) }

        suspend fun resolve(pipelineId: String): PipelineGraph? =
            resolved[pipelineId] ?: pipelineRepository.getPipelineById(pipelineId)?.also { resolved[pipelineId] = it }

        // Depth-first walk over the call graph. `path` holds the pipeline ids on
        // the current branch (root first); `depth` is the number of PIPELINE
        // hops from the root, matching the runtime depth semantics.
        suspend fun visit(current: PipelineGraph, path: List<String>, depth: Int) {
            current.nodes
                .filter { it.type == NodeType.PIPELINE }
                .forEach { node ->
                    val targetId = node.targetPipelineId
                    // Blank targets are a structural single-graph error reported
                    // by PipelineGraph.validate(); not this validator's concern.
                    if (targetId.isNullOrBlank()) return@forEach

                    if (targetId in path) {
                        val cycleStart = path.indexOf(targetId)
                        errors.add(
                            PipelineValidationError.PipelineCycle(path.subList(cycleStart, path.size) + targetId),
                        )
                        return@forEach
                    }

                    val targetGraph = resolve(targetId)
                    if (targetGraph == null) {
                        errors.add(PipelineValidationError.TargetPipelineNotFound(node.id, targetId))
                        return@forEach
                    }

                    val childDepth = depth + 1
                    if (childDepth > maxDepth) {
                        errors.add(PipelineValidationError.PipelineNestingTooDeep(path + targetId, maxDepth))
                        return@forEach
                    }

                    visit(targetGraph, path + targetId, childDepth)
                }
        }

        visit(graph, listOf(graph.id), depth = 0)
        return errors.toList()
    }

    /**
     * Classifies every saved pipeline as a candidate target for a
     * [NodeType.PIPELINE] node in [editingGraph]. Reuses the same call-graph
     * reachability the [validate] pass relies on, so the editor's target picker
     * disables exactly the options a subsequent save would reject — and names
     * the reason so the user knows the remedy.
     *
     * A candidate is **not** selectable when:
     *  - it is [editingPipelineId] itself ([PipelineTargetAvailability.Reason.Self]);
     *  - its call graph transitively runs [editingPipelineId], which would close a
     *    cycle ([PipelineTargetAvailability.Reason.Cycle], naming the back-reference);
     *  - nesting it under the edited pipeline would exceed
     *    [RunSettings.pipelineMaxNestingDepth]
     *    ([PipelineTargetAvailability.Reason.Depth]).
     *
     * The depth check measures the candidate's own subtree as if the edited
     * pipeline were the run root; the full transitive depth across every ancestor
     * that references the edited pipeline remains enforced by [validate] at save.
     *
     * @param editingPipelineId id of the pipeline being edited.
     * @param editingGraph the in-memory (possibly unsaved) edited graph; it
     *   overrides the persisted copy of its own id during reachability so an edit
     *   that would close a cycle is detected before it is saved.
     * @return one [PipelineTargetAvailability] per saved pipeline, sorted by name.
     */
    suspend fun classifyTargets(
        editingPipelineId: String,
        editingGraph: PipelineGraph,
    ): List<PipelineTargetAvailability> {
        val all = pipelineRepository.getAllPipelines().first()
        val maxDepth = runSettings.pipelineMaxNestingDepth.first()
        // Resolve ids through the persisted set, but let the in-memory edited
        // graph win for its own id so unsaved edits drive the classification.
        val byId = all.associateByTo(mutableMapOf()) { it.id }
        byId[editingGraph.id] = editingGraph

        return all
            .sortedBy { it.name.lowercase() }
            .map { candidate ->
                val reason = when {
                    candidate.id == editingPipelineId -> PipelineTargetAvailability.Reason.Self
                    else -> {
                        val culprit = cycleCulpritName(candidate.id, editingPipelineId, byId)
                        when {
                            culprit != null -> PipelineTargetAvailability.Reason.Cycle(culprit)
                            1 + subtreeDepth(candidate.id, byId) > maxDepth ->
                                PipelineTargetAvailability.Reason.Depth(maxDepth)
                            else -> null
                        }
                    }
                }
                PipelineTargetAvailability(
                    pipelineId = candidate.id,
                    name = candidate.name,
                    selectable = reason == null,
                    reason = reason,
                )
            }
    }
}

/** PIPELINE-node target ids declared by [pipeline] (non-blank only). */
private fun pipelineTargets(pipeline: PipelineGraph): List<String> = pipeline.nodes
    .filter { it.type == NodeType.PIPELINE }
    .mapNotNull { it.targetPipelineId?.takeIf { id -> id.isNotBlank() } }

/**
 * Returns the display name of a pipeline in [candidateId]'s call graph
 * (including the candidate itself) that directly runs [targetId], or `null`
 * when [targetId] is unreachable from [candidateId]. A non-null result means
 * picking the candidate would close a cycle back to the edited pipeline.
 */
private fun cycleCulpritName(candidateId: String, targetId: String, byId: Map<String, PipelineGraph>): String? {
    val visited = mutableSetOf<String>()
    val stack = ArrayDeque<String>().apply { addLast(candidateId) }
    while (stack.isNotEmpty()) {
        val id = stack.removeLast()
        if (!visited.add(id)) continue
        val pipeline = byId[id] ?: continue
        val targets = pipelineTargets(pipeline)
        if (targetId in targets) return pipeline.name
        targets.forEach { stack.addLast(it) }
    }
    return null
}

/**
 * Maximum number of PIPELINE hops below [rootId] in the call graph. Back-edges
 * (cycles within the subtree) are treated as depth 0 so the walk always
 * terminates; such cycles are reported separately by the full [validate] pass.
 */
private fun subtreeDepth(rootId: String, byId: Map<String, PipelineGraph>): Int {
    val memo = mutableMapOf<String, Int>()
    val onPath = mutableSetOf<String>()

    fun depthOf(id: String): Int {
        memo[id]?.let { return it }
        if (!onPath.add(id)) return 0
        val pipeline = byId[id]
        val depth = if (pipeline == null) {
            0
        } else {
            pipelineTargets(pipeline).maxOfOrNull { 1 + depthOf(it) } ?: 0
        }
        onPath.remove(id)
        memo[id] = depth
        return depth
    }

    return depthOf(rootId)
}

/**
 * Pure helper: the pipelines in [all] that run [targetPipelineId] through a
 * [NodeType.PIPELINE] node — the dependents that would be left with a dangling
 * target if [targetPipelineId] were deleted. The target itself is excluded.
 *
 * @param targetPipelineId the pipeline about to be deleted.
 * @param all every saved pipeline.
 * @return the dependent pipelines, in the order they appear in [all].
 */
fun findDependentPipelines(targetPipelineId: String, all: List<PipelineGraph>): List<PipelineGraph> =
    all.filter { pipeline ->
        pipeline.id != targetPipelineId &&
            pipeline.nodes.any { it.type == NodeType.PIPELINE && it.targetPipelineId == targetPipelineId }
    }
