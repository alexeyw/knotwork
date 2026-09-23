package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.repositories.LocalModelRepository
import javax.inject.Inject

/**
 * Why an image attachment may not start a run. The caller turns it into the
 * words the user sees; the decision itself is [CheckImageAttachmentUseCase]'s.
 */
enum class ImageAttachmentBlock {
    /** The run would start on a `CLOUD` node — attachments never leave the device. */
    CLOUD_ENTRY,

    /** The active local model is not marked vision-capable, so it cannot read the image. */
    MODEL_NO_VISION,

    /** No on-device step of the pipeline could receive the image, so it would be ignored. */
    NO_VISION_STEP,
}

/**
 * The multimodal pre-flight: decides, before a run is enqueued, whether an image
 * attachment may start it.
 *
 * **Every entry that attaches an image asks this** — the chat composer and the
 * share target alike. The check used to live in the composer's ViewModel
 * delegate, where the share path could not reach it, so a shared image went into
 * a cloud-first pipeline and was dropped without a word while the model answered
 * about a picture it never saw. `ImageAttachmentEntryCensusTest` pins the set of
 * entries that hand an attachment to the orchestrator, each of which calls this.
 *
 * Three checks, in precedence order:
 * 1. the pipeline starts on a `CLOUD` node → [ImageAttachmentBlock.CLOUD_ENTRY];
 * 2. the active model is not vision-capable → [ImageAttachmentBlock.MODEL_NO_VISION]
 *    (the most common and most actionable cause);
 * 3. no on-device vision sink is reachable → [ImageAttachmentBlock.NO_VISION_STEP].
 *
 * The structural half of the privacy guarantee does not depend on this:
 * `CloudLlmNodeExecutor` never reads the image, whatever this answers.
 *
 * @property resolveEntryInference Classifies where the pipeline's first inference runs.
 * @property localModelRepository Source of the active model's vision flag.
 */
class CheckImageAttachmentUseCase @Inject constructor(
    private val resolveEntryInference: ResolveEntryInferenceUseCase,
    private val localModelRepository: LocalModelRepository,
) {
    /**
     * Runs the pre-flight for an image run of [pipelineId].
     *
     * @param pipelineId The pipeline the run would execute, or `null` for the
     *   application-wide default (the same fallback the orchestrator applies).
     * @return The reason the run must not start, or `null` when it may.
     */
    suspend operator fun invoke(pipelineId: String?): ImageAttachmentBlock? {
        val entryKind = resolveEntryInference(pipelineId)
        return when {
            entryKind == EntryInferenceKind.CLOUD -> ImageAttachmentBlock.CLOUD_ENTRY
            localModelRepository.getActiveModel()?.supportsVision != true -> ImageAttachmentBlock.MODEL_NO_VISION
            entryKind == EntryInferenceKind.NONE -> ImageAttachmentBlock.NO_VISION_STEP
            else -> null
        }
    }
}
