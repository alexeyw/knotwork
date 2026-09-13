package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.repositories.PipelineRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Use case for deleting a pipeline from the library.
 *
 * Any pipeline can be deleted, including the one the editor currently holds and
 * the last one in the library. An earlier rule refused the pipeline "loaded into
 * the editor", but that pipeline was not something the user chose: after a
 * restart it was simply the most recently modified one, the library never showed
 * the editor while it applied, and the refusal arrived only after the user had
 * confirmed an irreversible delete. Keeping the editor consistent with a deletion
 * is the caller's job — see `OrchestratorViewModel.deletePipeline`.
 *
 * @property pipelineRepository Persistence sink for the cascading delete.
 */
class DeletePipelineUseCase @Inject constructor(private val pipelineRepository: PipelineRepository) {
    /**
     * Deletes the pipeline identified by [pipelineId].
     *
     * @param pipelineId Unique identifier of the pipeline to delete.
     * @return [Result.success] when the pipeline is gone, [Result.failure] carrying
     * the cause when the underlying delete throws.
     */
    suspend operator fun invoke(pipelineId: String): Result<Unit> = try {
        pipelineRepository.deletePipeline(pipelineId)
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
