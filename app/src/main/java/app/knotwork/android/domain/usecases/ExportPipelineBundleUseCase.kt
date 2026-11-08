package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.pipelineio.PipelineBundleJsonSerializer
import app.knotwork.android.domain.repositories.PipelinePresetRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import javax.inject.Inject

/**
 * Exports a pipeline together with the **transitive closure** of every
 * sub-pipeline it references through `PIPELINE` nodes, producing a
 * self-contained bundle JSON document.
 *
 * The closure walk starts from [rootPipelineId] and follows each `PIPELINE`
 * node's `targetPipelineId`. Every referenced pipeline is resolved first from
 * the saved library and then, as a fallback, from the bundled preset
 * catalogue — the preset body is re-identified under the referenced id so the
 * bundle is self-contained and needs no seed-on-demand at the receiver. A
 * `visited` set makes diamond dependencies collapse to one copy and breaks
 * reference cycles.
 *
 * The walk **fails fast**: a `targetPipelineId` that resolves in neither the
 * library nor the preset catalogue aborts the export with
 * [PipelineBundleExportException], because a bundle carrying a dangling
 * reference would degrade silently at the receiver. The closure size is capped
 * at [PipelineBundleJsonSerializer.MAX_BUNDLE_PIPELINES].
 */
class ExportPipelineBundleUseCase @Inject constructor(
    private val pipelineRepository: PipelineRepository,
    private val pipelinePresetRepository: PipelinePresetRepository,
) {

    /**
     * Walks the dependency closure of [rootPipelineId] and serialises it into a
     * bundle document.
     *
     * @param rootPipelineId Id of the saved pipeline to export as the bundle
     *   root.
     * @param exportedAt Provenance timestamp stamped into the envelope;
     *   defaults to the current wall clock and is injectable for tests.
     * @return [Result.success] with the bundle JSON, or [Result.failure] with a
     *   [PipelineBundleExportException] when the root is missing, a dependency
     *   is unresolvable, or the closure exceeds the pipeline limit.
     */
    suspend operator fun invoke(
        rootPipelineId: String,
        exportedAt: Long = System.currentTimeMillis(),
    ): Result<String> {
        val collected = LinkedHashMap<String, PipelineGraph>()
        val queue = ArrayDeque<String>()
        queue.add(rootPipelineId)

        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (collected.containsKey(id)) continue

            val graph = resolve(id)
                ?: return Result.failure(
                    PipelineBundleExportException(
                        if (id == rootPipelineId) {
                            BundleExportProblem.RootNotFound(id)
                        } else {
                            BundleExportProblem.ReferenceNotFound(id)
                        },
                    ),
                )

            collected[id] = graph
            if (collected.size > PipelineBundleJsonSerializer.MAX_BUNDLE_PIPELINES) {
                return Result.failure(
                    PipelineBundleExportException(
                        BundleExportProblem.TooManyPipelines(PipelineBundleJsonSerializer.MAX_BUNDLE_PIPELINES),
                    ),
                )
            }

            graph.nodes
                .filter { it.type == NodeType.PIPELINE }
                .mapNotNull { it.targetPipelineId?.takeIf { target -> target.isNotBlank() } }
                .forEach { target -> if (!collected.containsKey(target)) queue.add(target) }
        }

        return Result.success(
            PipelineBundleJsonSerializer.serialize(collected.values.toList(), exportedAt),
        )
    }

    /**
     * Resolves a pipeline id to a graph for inclusion in the bundle: the saved
     * library takes precedence; a bundled preset is the fallback, its graph
     * re-identified under [id] so the parent's reference resolves inside the
     * bundle. Returns `null` when neither source has it.
     */
    private suspend fun resolve(id: String): PipelineGraph? = pipelineRepository.getPipelineById(id)
        ?: pipelinePresetRepository.getPresetById(id)?.graph?.copy(id = id)
}

/**
 * Raised when a pipeline-bundle export cannot produce a self-contained file —
 * the root is missing, a `PIPELINE` dependency is unresolvable, or the closure
 * exceeds the pipeline limit.
 *
 * @property problem Why, for the screen to word in the interface language.
 */
class PipelineBundleExportException(val problem: BundleExportProblem) : Exception(problem.toString())

/** Why a pipeline bundle could not be exported. */
sealed interface BundleExportProblem {

    /**
     * The pipeline being exported is gone.
     *
     * @property pipelineId Its id.
     */
    data class RootNotFound(val pipelineId: String) : BundleExportProblem

    /**
     * A pipeline the exported one calls is gone.
     *
     * @property pipelineId The called pipeline's id.
     */
    data class ReferenceNotFound(val pipelineId: String) : BundleExportProblem

    /**
     * The exported pipeline and the pipelines it calls are more than one bundle holds.
     *
     * @property limit How many one bundle holds.
     */
    data class TooManyPipelines(val limit: Int) : BundleExportProblem
}
