package app.knotwork.android.domain.verification

import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.RunHeader
import app.knotwork.android.domain.models.RunTraceRecord

/**
 * What the app promises about repeating a run — written once, read by the check
 * and by every surface that states the promise.
 *
 * Measured on the reference device (`decisions.md §70.6`): with the same model
 * file, backend, seed, sampler and input, an on-device call gives the same text
 * byte for byte on **CPU**, and on **GPU** when the context window is also the
 * same. The **NPU** is not promised — on the reference device it silently ran on
 * the CPU. A call that read an image cannot be repeated from the record (the
 * image is not kept), and a cloud model takes no seed the app controls. Another
 * device was never measured, so nothing is promised there.
 */
object RunReproducibilityPolicy {

    /**
     * Why an on-device call cannot be repeated and compared, or `null` when it can.
     *
     * @param call A recorded on-device call.
     * @return The reason, or `null` when the check may repeat the call.
     */
    fun notVerifiable(call: RunTraceRecord.LocalModelCall): NotVerifiableReason? = when {
        call.backend == LocalBackend.NPU -> NotVerifiableReason.NPU
        call.hadImage -> NotVerifiableReason.IMAGE_INPUT
        call.modelPath == null ||
            call.modelSha256 == null ||
            call.backend == null ||
            call.contextWindow == null -> NotVerifiableReason.MODEL_NOT_RECORDED
        else -> null
    }

    /**
     * Whether a repeat of this call must also use the recorded context window: on
     * GPU the same seed reproduces the same text only at the same window.
     *
     * @param call A recorded on-device call.
     * @return `true` for a call that ran on the GPU.
     */
    fun windowMatters(call: RunTraceRecord.LocalModelCall): Boolean = call.backend == LocalBackend.GPU

    /**
     * What may be promised about repeating a run, from its header and the model
     * calls it recorded.
     *
     * @param header The run's header, or `null` for a run recorded before headers existed.
     * @param localCalls The run tree's recorded on-device calls.
     * @param cloudCalls How many calls the run tree made to cloud models.
     * @return The promise.
     */
    fun promise(
        header: RunHeader?,
        localCalls: List<RunTraceRecord.LocalModelCall>,
        cloudCalls: Int,
    ): Reproducibility = when {
        header == null -> Reproducibility.NotPromised(NotPromisedReason.PRE_VERSION)
        localCalls.isEmpty() && cloudCalls > 0 -> Reproducibility.NotPromised(NotPromisedReason.CLOUD_ONLY)
        localCalls.any { it.backend == LocalBackend.NPU } -> Reproducibility.NotPromised(NotPromisedReason.NPU)
        localCalls.any { it.hadImage } -> Reproducibility.NotPromised(NotPromisedReason.IMAGE)
        else -> Reproducibility.Promised(
            backends = localCalls.mapNotNull { it.backend }.toSet(),
            usedCloud = cloudCalls > 0,
        )
    }
}

/** What may be promised about repeating a run. */
sealed interface Reproducibility {

    /**
     * On-device calls repeat byte for byte on this device under the stated
     * condition — on GPU, the context window included.
     *
     * @property backends The backends the run's on-device calls ran on (CPU, GPU).
     * @property usedCloud Whether some calls went to a cloud model, which is not repeatable.
     */
    data class Promised(val backends: Set<LocalBackend>, val usedCloud: Boolean) : Reproducibility

    /**
     * Nothing is promised, for one reason.
     *
     * @property reason Why.
     */
    data class NotPromised(val reason: NotPromisedReason) : Reproducibility
}

/** Why repeating a run is not promised. */
enum class NotPromisedReason {
    /** The run used the NPU, which was not measured to repeat. */
    NPU,

    /** Every answer came from a cloud model. */
    CLOUD_ONLY,

    /** The run read an image, which the record does not keep. */
    IMAGE,

    /** The run was recorded before the app kept seeds and hashes. */
    PRE_VERSION,
}
