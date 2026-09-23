package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.constants.PipelineConstants
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelinePreset
import app.knotwork.android.domain.models.PipelineValidationException
import app.knotwork.android.domain.repositories.PipelinePresetRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import kotlinx.coroutines.CancellationException
import java.util.UUID
import javax.inject.Inject

/**
 * Materialises a [app.knotwork.android.domain.models.PipelinePreset] into a
 * concrete, persistable [PipelineGraph].
 *
 * The preset's embedded graph is a *template*: its `id` and every node /
 * connection id are placeholders that must be regenerated on instantiation
 * so the new pipeline can coexist with the source preset in the library
 * without primary-key collisions. The deep-copy logic mirrors
 * [DuplicatePipelineUseCase]:
 *
 * - Fresh UUIDs for the pipeline, every node, and every connection.
 * - Connection `sourceNodeId` / `targetNodeId` re-mapped to the new node ids.
 * - Orphan connections (referencing node ids not present in `template.nodes`)
 *   are silently dropped rather than crashing the load — a corrupt preset
 *   should not block the user from getting at least the well-formed parts.
 *
 * The instantiated graph is validated through [PipelineGraph.validate] and
 * persisted via [PipelineRepository.savePipeline]; the new pipeline id is
 * returned so the caller can immediately load it as the active pipeline in
 * the editor.
 *
 * @property pipelinePresetRepository Source of the preset catalogue.
 * @property pipelineRepository Persistence sink for the instantiated pipeline.
 */
class LoadPipelineFromPresetUseCase @Inject constructor(
    private val pipelinePresetRepository: PipelinePresetRepository,
    private val pipelineRepository: PipelineRepository,
) {
    /**
     * Instantiates the preset identified by [presetId] and persists the
     * resulting [PipelineGraph].
     *
     * @param presetId The stable id of the preset to instantiate.
     * @return [Result.success] holding the id of the newly persisted
     *   pipeline, or [Result.failure] when the preset is missing, the
     *   template fails [PipelineGraph.validate], or the persistence layer
     *   throws.
     */
    suspend operator fun invoke(presetId: String): Result<String> = try {
        val pipeline = materialize(presetId).getOrElse { return Result.failure(it) }
        // A composed preset (one whose PIPELINE nodes target bundled sub-pipeline
        // presets) only runs if those sub-pipelines are persisted under the
        // stable id its nodes reference. Seed any that are not already present,
        // create-if-absent, before saving the parent — so first-launch seeding
        // and a later "+ From preset" spawn both leave a runnable composition,
        // while never clobbering a sub-pipeline the user has since edited.
        ensureSubPipelineDependencies(pipeline)
        pipelineRepository.savePipeline(pipeline)
        Result.success(pipeline.id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Instantiates the preset identified by [presetId] into a fresh,
     * validated [PipelineGraph] **without persisting it**.
     *
     * Used by surfaces that fill an *existing* pipeline from a template (the
     * editor's empty-state "From template" CTA) rather than spawning a new
     * library row — the caller decides where the regenerated graph's nodes /
     * connections land. [invoke] builds on this and adds the persistence step.
     *
     * @param presetId The stable id of the preset to instantiate.
     * @return [Result.success] with the regenerated graph (new pipeline / node
     *   / connection ids, validated), or [Result.failure] when the preset is
     *   missing or the template fails [PipelineGraph.validate].
     */
    suspend fun materialize(presetId: String): Result<PipelineGraph> = try {
        val preset = pipelinePresetRepository.getPresetById(presetId)
            ?: return Result.failure(IllegalStateException("Preset not found: $presetId"))
        buildGraph(preset, pipelineId = UUID.randomUUID().toString())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Instantiates [preset] into a validated [PipelineGraph] under [pipelineId].
     *
     * Every node and connection id is regenerated (fresh UUIDs) so an
     * instantiated graph can coexist with the source preset, but the
     * caller chooses the pipeline id: [materialize] passes a fresh UUID
     * (a user-spawned copy), whereas [ensureSubPipelineDependencies] passes
     * the preset's own stable id so a composed parent's `targetPipelineId`
     * reference resolves to it.
     */
    private fun buildGraph(preset: PipelinePreset, pipelineId: String): Result<PipelineGraph> {
        val template = preset.graph
        val nodeIdMapping: Map<String, String> =
            template.nodes.associate { it.id to UUID.randomUUID().toString() }

        val instantiatedNodes = template.nodes.map { node ->
            node.copy(id = nodeIdMapping.getValue(node.id))
        }

        val instantiatedConnections = template.connections.mapNotNull { connection ->
            val newSource = nodeIdMapping[connection.sourceNodeId]
            val newTarget = nodeIdMapping[connection.targetNodeId]
            if (newSource == null || newTarget == null) {
                null
            } else {
                connection.copy(
                    id = UUID.randomUUID().toString(),
                    sourceNodeId = newSource,
                    targetNodeId = newTarget,
                )
            }
        }

        val pipeline = PipelineGraph(
            id = pipelineId,
            name = truncateName(preset.name),
            nodes = instantiatedNodes,
            connections = instantiatedConnections,
            updatedAt = System.currentTimeMillis(),
            // Carry the preset's starter prompts onto the materialized copy so
            // the new-chat empty state shows the pipeline's own quick actions
            // (mirrors DuplicatePipelineUseCase).
            samplePrompts = template.samplePrompts,
            // Same reasoning for the preset's declared background
            // memory-retrieval key: a materialized preset must behave like the
            // template it came from.
            memoryRetrievalQuery = template.memoryRetrievalQuery,
        )

        val errors = pipeline.validate()
        return if (errors.isNotEmpty()) {
            Result.failure(PipelineValidationException(errors))
        } else {
            Result.success(pipeline)
        }
    }

    /**
     * Seeds the bundled sub-pipelines a composed [pipeline] depends on.
     *
     * For every PIPELINE node target that is **not already a saved pipeline**
     * but **is a bundled preset id**, the matching preset is materialised under
     * that same stable id and persisted, then its own dependencies are seeded
     * recursively. The order — persist the dependency, then recurse — together
     * with the already-saved short-circuit makes the walk terminate even on a
     * (malformed) cyclic catalogue, and idempotent: a sub-pipeline the user has
     * already saved (or edited) is left untouched. A sub-preset that is missing
     * or fails validation is skipped rather than failing the parent load — the
     * dangling target then surfaces through the normal composition validation.
     */
    private suspend fun ensureSubPipelineDependencies(pipeline: PipelineGraph) {
        val targets = pipeline.nodes
            .filter { it.type == NodeType.PIPELINE }
            .mapNotNull { it.targetPipelineId?.takeIf { id -> id.isNotBlank() } }
            .toSet()

        targets.forEach { targetId ->
            if (pipelineRepository.getPipelineById(targetId) != null) return@forEach
            val preset = pipelinePresetRepository.getPresetById(targetId) ?: return@forEach
            val subPipeline = buildGraph(preset, pipelineId = preset.id).getOrNull() ?: return@forEach
            pipelineRepository.savePipeline(subPipeline)
            ensureSubPipelineDependencies(subPipeline)
        }
    }

    /**
     * Clamps [name] to [MAX_NAME_LENGTH] characters, trimming trailing
     * whitespace so a truncated name does not leak a stray space. Mirrors
     * the limit enforced by [CreatePipelineUseCase] /
     * [RenamePipelineUseCase] / [DuplicatePipelineUseCase] so the
     * instantiated pipeline can always be subsequently renamed without
     * violating the gate.
     */
    private fun truncateName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.length <= MAX_NAME_LENGTH) return trimmed
        return trimmed.substring(0, MAX_NAME_LENGTH).trimEnd()
    }

    private companion object {
        const val MAX_NAME_LENGTH = PipelineConstants.MAX_NAME_LENGTH
    }
}
