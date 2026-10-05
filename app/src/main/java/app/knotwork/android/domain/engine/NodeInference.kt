package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.LocalSampling
import kotlinx.coroutines.flow.Flow

/**
 * A node's only way to its model during one visit.
 *
 * Inside a run the engine hands each node a recording implementation
 * ([RecordingNodeInference]): every on-device call gets the run's sampler and a
 * seed derived from the run seed, and is recorded with its full prompt and
 * output, so the run can be checked later by repeating the call. Executors never
 * call [LlmInferenceEngine.generateResponseStream] themselves — a call made
 * around this seam would run on a random seed and leave no record (enforced by
 * `LocalInferenceSeamKonsistTest`).
 *
 * Outside a run — a node executed directly by a test or a preview, memory
 * extraction after a run — [Unrecorded] passes through and records nothing.
 */
interface NodeInference {

    /**
     * Streams one on-device generation.
     *
     * @param engine The engine to generate on; the caller has loaded the model.
     * @param prompt The full text to send.
     * @param imagePath An image sent with [prompt], or `null` for text only.
     * @param repairTemperature `null` for an ordinary call, which runs on the
     *   run's sampler and a derived seed; non-`null` for a structured-output
     *   repair, which runs on [LocalSampling.repair] at this temperature.
     * @return The generated text, chunk by chunk.
     */
    fun local(
        engine: LlmInferenceEngine,
        prompt: String,
        imagePath: String? = null,
        repairTemperature: Float? = null,
    ): Flow<String>

    /**
     * Notes one call to a cloud model, made by the node itself. Nothing about
     * the text is kept: a hosted model's answer cannot be repeated with a seed,
     * so the note only marks the visit as answered in the cloud.
     *
     * @param provider The provider id.
     * @param model The model id asked for, or `null` when the node does not know it.
     */
    fun cloudCall(provider: String, model: String?)

    /**
     * Inference outside a run: the engine's own sampling for an ordinary call
     * (the user's sampler, a fresh seed), the fixed repair sampling for a repair,
     * and no record of either.
     */
    object Unrecorded : NodeInference {
        override fun local(
            engine: LlmInferenceEngine,
            prompt: String,
            imagePath: String?,
            repairTemperature: Float?,
        ): Flow<String> =
            engine.generateResponseStream(prompt, imagePath, repairTemperature?.let(LocalSampling::repair))

        override fun cloudCall(provider: String, model: String?) = Unit
    }
}
