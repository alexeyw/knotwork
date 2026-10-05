package app.knotwork.android.domain.verification

import app.knotwork.android.domain.models.LocalSampling

/**
 * Whether a finished run can be started again with its own seed and sampler, and
 * what that new run is.
 *
 * Starting again is a new run on today's inputs — memory, chat history and tool
 * results are read again, and tools run again with the usual approvals — that
 * shares only the run's message, pipeline, seed and sampler. Its answer repeats
 * only if every input is the same.
 */
sealed interface RunAgainAvailability {

    /**
     * The run can be started again.
     *
     * @property request What the new run is started with.
     */
    data class Available(val request: RunAgainRequest) : RunAgainAvailability

    /** The run is still going. */
    data object Busy : RunAgainAvailability

    /**
     * Not offered for this run, for one reason.
     *
     * @property reason Why.
     */
    data class NotOffered(val reason: RunAgainNotOfferedReason) : RunAgainAvailability
}

/**
 * What a run started again with a recorded run's seed is started with.
 *
 * @property sessionId The chat the recorded run belongs to; the new run answers there.
 * @property pipelineId The pipeline the recorded run executed — the new run executes
 *   it again, not whatever the chat is bound to now.
 * @property pipelineName That pipeline's display name, for the confirmation.
 * @property userPrompt The message that started the recorded run, sent again.
 * @property sampling The recorded run's seed and sampler. The new run's header takes
 *   both, so each on-device call derives the same seed as the recorded call in the
 *   same place.
 */
data class RunAgainRequest(
    val sessionId: String,
    val pipelineId: String,
    val pipelineName: String,
    val userPrompt: String,
    val sampling: LocalSampling,
)

/** Why starting a run again with its seed is not offered. */
enum class RunAgainNotOfferedReason {
    /** The run's message carried an image, which the record does not keep. */
    IMAGE,

    /**
     * A trigger, the scheduler, the tile or another app started the run. Such a run
     * keys its memory retrieval off the pipeline, not a message, so a chat-started
     * repeat would not read the same memory; it can still be checked and exported.
     */
    BACKGROUND_ORIGIN,

    /** The run was recorded before runs kept a seed. */
    PRE_VERSION,

    /** The pipeline the run executed has been deleted since. */
    PIPELINE_DELETED,
}
