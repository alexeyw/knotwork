package app.knotwork.android.domain.verification

import app.knotwork.android.domain.models.LocalSampling
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.RunHeader
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import javax.inject.Inject

/**
 * Decides whether a finished root run can be started again with its own seed and
 * sampler, and with what.
 *
 * Offered only for a run the user started from a chat or a share, on a message
 * without an image (`decisions.md §75.2` R5): a background run keys its memory
 * retrieval differently, and an image is not kept by the record. Reads the record
 * only — the new run is started by whoever shows the action, with the request
 * this returns.
 *
 * @property pipelineRunRepository The run records.
 * @property pipelineRepository The pipelines, for the one the run executed.
 */
class PlanRunAgainWithSeedUseCase @Inject constructor(
    private val pipelineRunRepository: PipelineRunRepository,
    private val pipelineRepository: PipelineRepository,
) {

    /**
     * Plans starting root run [rootRunId] again with its seed.
     *
     * @param rootRunId The run the console shows.
     * @return Whether it can be started again, with the request when it can.
     */
    suspend operator fun invoke(rootRunId: String): RunAgainAvailability {
        val run = pipelineRunRepository.getRun(rootRunId)
        val header = run?.header
        val userPrompt = run?.userPrompt
        return when {
            run == null -> RunAgainAvailability.NotOffered(RunAgainNotOfferedReason.PRE_VERSION)
            !run.status.isTerminal -> RunAgainAvailability.Busy
            header == null || userPrompt == null ->
                RunAgainAvailability.NotOffered(RunAgainNotOfferedReason.PRE_VERSION)
            run.origin !in INTERACTIVE_ORIGINS ->
                RunAgainAvailability.NotOffered(RunAgainNotOfferedReason.BACKGROUND_ORIGIN)
            run.hadImage -> RunAgainAvailability.NotOffered(RunAgainNotOfferedReason.IMAGE)
            else -> requestFor(run, header, userPrompt)
        }
    }

    /** The request for a finished, offered [run], unless its pipeline is gone. */
    private suspend fun requestFor(run: PipelineRun, header: RunHeader, userPrompt: String): RunAgainAvailability {
        val pipeline = run.pipelineId?.let { pipelineRepository.getPipelineById(it) }
            ?: return RunAgainAvailability.NotOffered(RunAgainNotOfferedReason.PIPELINE_DELETED)
        return RunAgainAvailability.Available(
            RunAgainRequest(
                sessionId = run.sessionId,
                pipelineId = pipeline.id,
                pipelineName = pipeline.name,
                userPrompt = userPrompt,
                sampling = LocalSampling(sampler = header.sampler, seed = header.seed),
            ),
        )
    }

    private companion object {
        /** The origins of a run the user started from the app's foreground. */
        val INTERACTIVE_ORIGINS = setOf(RunOrigin.CHAT, RunOrigin.SHARE)
    }
}
