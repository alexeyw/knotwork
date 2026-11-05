package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.constants.PipelineConstants
import app.knotwork.android.domain.models.InputProblem
import app.knotwork.android.domain.models.InvalidInputException
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.services.DefaultNames
import kotlinx.coroutines.CancellationException
import java.util.UUID
import javax.inject.Inject

/**
 * Use case for duplicating an existing pipeline.
 *
 * Produces a deep copy of the source graph with fresh identifiers (pipeline,
 * every node, every connection) so the duplicate can coexist with its source
 * in the library without primary-key collisions on the Room side. Connection
 * source / target references are remapped to the new node ids.
 *
 * The duplicate's name is the source name marked as a copy in the interface
 * language ([DefaultNames.copyOf] — `"X (copy)"` in English). We keep the
 * mark even when the source name already carries one — repeated duplication
 * then yields `"X (copy) (copy)"`, which is intentional: it surfaces
 * accidental double-clicks instead of silently collapsing them into a single
 * name shared by multiple pipelines.
 *
 * @property pipelineRepository Source of the original graph and the persistence sink.
 * @property defaultNames How a copy is named.
 */
class DuplicatePipelineUseCase @Inject constructor(
    private val pipelineRepository: PipelineRepository,
    private val defaultNames: DefaultNames,
) {
    /**
     * Duplicates the pipeline identified by [pipelineId].
     *
     * @param pipelineId Unique identifier of the source pipeline.
     * @return [Result.success] holding the persisted duplicate (so the caller
     * can immediately load it as the active pipeline), or [Result.failure] if
     * the source is missing or the save fails.
     */
    suspend operator fun invoke(pipelineId: String): Result<PipelineGraph> {
        // Wrap the entire pipeline (read + transform + save) so any unexpected
        // throwable — including a malformed source graph that references node
        // ids no longer present in `source.nodes` — surfaces as `Result.failure`
        // instead of escaping the use-case boundary as an uncaught exception.
        return try {
            val source = pipelineRepository.getPipelineById(pipelineId)
                ?: return Result.failure(InvalidInputException(InputProblem.NotFound(InputProblem.Subject.PIPELINE)))

            val nodeIdMapping: Map<String, String> =
                source.nodes.associate { it.id to UUID.randomUUID().toString() }

            val duplicatedNodes = source.nodes.map { node ->
                node.copy(id = nodeIdMapping.getValue(node.id))
            }

            // Drop connections that reference node ids missing from
            // `source.nodes` rather than crashing the duplicate. A dangling
            // connection in the source graph is already broken; producing a
            // duplicate that silently inherits the corruption (or worse,
            // crashes the action) would be a regression. We keep the rest of
            // the graph intact.
            //
            // Use `connection.copy(...)` instead of the constructor so any
            // future fields added to `ConnectionModel` (visual styling,
            // routing waypoints, etc.) are preserved on the duplicate
            // automatically.
            val duplicatedConnections = source.connections.mapNotNull { connection ->
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

            val duplicate = PipelineGraph(
                id = UUID.randomUUID().toString(),
                name = buildDuplicateName(source.name),
                nodes = duplicatedNodes,
                connections = duplicatedConnections,
                updatedAt = System.currentTimeMillis(),
                samplePrompts = source.samplePrompts,
                // A copy must behave like its source on background runs too, so
                // the declared memory-retrieval key travels with it.
                memoryRetrievalQuery = source.memoryRetrievalQuery,
            )

            pipelineRepository.savePipeline(duplicate)
            Result.success(duplicate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Composes the duplicate's display name as `"<source> (copy)"` in the
     * interface language, truncating the base name when the whole would exceed
     * [MAX_NAME_LENGTH].
     * The limit is shared with [CreatePipelineUseCase] / [RenamePipelineUseCase]
     * so a duplicate is never longer than what those flows accept — without
     * the truncation, repeated duplication or already-long names would
     * produce graphs whose names cannot be saved by a subsequent rename.
     *
     * Trailing whitespace is stripped after truncation so we never persist
     * names like `"Long source... (copy)"` with a stray space before the
     * suffix.
     */
    private fun buildDuplicateName(sourceName: String): String {
        // What the copy mark adds, wherever the language puts it.
        val maxBase = MAX_NAME_LENGTH - defaultNames.copyOf("").length
        val base = if (sourceName.length > maxBase) {
            sourceName.substring(0, maxBase).trimEnd()
        } else {
            sourceName
        }
        return defaultNames.copyOf(base)
    }

    private companion object {
        /** The shared pipeline-name ceiling ([PipelineConstants.MAX_NAME_LENGTH]). */
        const val MAX_NAME_LENGTH = PipelineConstants.MAX_NAME_LENGTH
    }
}
