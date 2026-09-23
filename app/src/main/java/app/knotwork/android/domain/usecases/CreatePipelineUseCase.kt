package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.constants.PipelineConstants
import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.repositories.PipelineRepository
import kotlinx.coroutines.CancellationException
import java.util.UUID
import javax.inject.Inject

/**
 * Use case for creating a brand-new pipeline from the library FAB.
 *
 * Seeds the graph with a minimal `INPUT → OUTPUT` skeleton so the pipeline
 * passes [PipelineGraph.validate] out of the box (an empty graph would fail
 * "MissingInput" / "MissingOutput") and is immediately editable by the user
 * without forcing them to drop nodes manually before the first save.
 *
 * The freshly-saved graph is returned so the caller can swap it into the
 * orchestrator state and navigate to the editor in one step.
 *
 * @property pipelineRepository Persistence sink for the seeded graph.
 */
class CreatePipelineUseCase @Inject constructor(private val pipelineRepository: PipelineRepository) {
    /**
     * Creates and persists a new pipeline named [name].
     *
     * @param name Display name for the new pipeline. Trimmed before
     * validation; blank values and names exceeding [MAX_NAME_LENGTH] are
     * rejected with [IllegalArgumentException] so the dialog can surface a
     * field-level error.
     * @return [Result.success] with the newly persisted [PipelineGraph], or
     * [Result.failure] for validation / storage errors.
     */
    suspend operator fun invoke(name: String): Result<PipelineGraph> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Pipeline name cannot be empty"))
        }
        if (trimmed.length > MAX_NAME_LENGTH) {
            return Result.failure(
                IllegalArgumentException("Pipeline name must be $MAX_NAME_LENGTH characters or fewer"),
            )
        }

        val inputNode = NodeModel(
            id = UUID.randomUUID().toString(),
            type = NodeType.INPUT,
            label = "Input",
            x = INPUT_NODE_X,
            y = SEED_NODE_Y,
            contextConfig = NodeContextConfig.defaultForType(NodeType.INPUT),
        )
        val outputNode = NodeModel(
            id = UUID.randomUUID().toString(),
            type = NodeType.OUTPUT,
            label = "Output",
            x = OUTPUT_NODE_X,
            y = SEED_NODE_Y,
            contextConfig = NodeContextConfig.defaultForType(NodeType.OUTPUT),
        )
        val seedConnection = ConnectionModel(
            id = UUID.randomUUID().toString(),
            sourceNodeId = inputNode.id,
            targetNodeId = outputNode.id,
        )

        val newPipeline = PipelineGraph(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            nodes = listOf(inputNode, outputNode),
            connections = listOf(seedConnection),
            updatedAt = System.currentTimeMillis(),
        )

        return try {
            pipelineRepository.savePipeline(newPipeline)
            Result.success(newPipeline)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private companion object {
        /** The shared pipeline-name ceiling, so every path agrees on what is acceptable. */
        const val MAX_NAME_LENGTH = PipelineConstants.MAX_NAME_LENGTH

        /** Y coordinate shared by both seed nodes — keeps them on a single horizontal track. */
        const val SEED_NODE_Y = 300f

        /** Default canvas X for the seeded INPUT node. */
        const val INPUT_NODE_X = 100f

        /** Default canvas X for the seeded OUTPUT node, far enough right to leave room for nodes between them. */
        const val OUTPUT_NODE_X = 500f
    }
}
