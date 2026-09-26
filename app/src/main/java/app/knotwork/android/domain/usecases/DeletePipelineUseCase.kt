package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.TriggerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Use case for deleting a pipeline from the library.
 *
 * Any pipeline can be deleted, including the one the editor currently holds and
 * the last one in the library. An earlier rule refused the pipeline "loaded into
 * the editor", but that pipeline was not something the user chose: after a
 * restart it was simply the most recently modified one, the library is never on
 * screen while that pipeline is being edited, and the refusal arrived only after
 * the user had confirmed an irreversible delete. Keeping the editor consistent with a deletion
 * is the caller's job — see `OrchestratorViewModel.deletePipeline`.
 *
 * Triggers bound to the deleted pipeline are switched off in the same step —
 * see [disableBoundTriggers].
 *
 * @property pipelineRepository Persistence sink for the cascading delete.
 * @property triggerRepository Triggers, switched off when their pipeline goes.
 */
class DeletePipelineUseCase @Inject constructor(
    private val pipelineRepository: PipelineRepository,
    private val triggerRepository: TriggerRepository,
) {
    /**
     * Deletes the pipeline identified by [pipelineId].
     *
     * @param pipelineId Unique identifier of the pipeline to delete.
     * @return [Result.success] when the pipeline is gone, [Result.failure] carrying
     * the cause when the underlying delete throws.
     */
    suspend operator fun invoke(pipelineId: String): Result<Unit> = try {
        pipelineRepository.deletePipeline(pipelineId)
        disableBoundTriggers(pipelineId)
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Switches off every enabled trigger bound to the deleted pipeline.
     *
     * The triggers table holds the id without a foreign key, so a trigger would
     * otherwise stay enabled until its next fire noticed the pipeline was gone.
     * In that window the id is free, and a file imported under it — by the user,
     * or from a pipeline file somebody sent — would be run by the trigger,
     * unattended. The trigger keeps its binding; the user re-points and re-enables
     * it on the Triggers screen.
     *
     * @param pipelineId Id of the pipeline just deleted.
     */
    private suspend fun disableBoundTriggers(pipelineId: String) {
        triggerRepository.observeTriggers().first()
            .filter { it.pipelineId == pipelineId && it.enabled }
            .forEach { triggerRepository.setEnabled(it.id, false) }
    }
}
