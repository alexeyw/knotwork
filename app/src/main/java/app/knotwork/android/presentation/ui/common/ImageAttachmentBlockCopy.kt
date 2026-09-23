package app.knotwork.android.presentation.ui.common

import app.knotwork.android.domain.usecases.ImageAttachmentBlock

/**
 * The chat composer's words for each [ImageAttachmentBlock]. Calm, non-alarmist
 * copy in line with the attachment UX. The share target says the same in shorter
 * string resources (`share_image_blocked_*`), because its notice is a toast and a
 * toast holds two lines on Android 12+.
 */
object ImageAttachmentBlockCopy {
    /**
     * The active local model is not marked vision-capable. The run is blocked so
     * the native inference layer never receives an image it cannot decode.
     */
    const val MODEL_NO_VISION_MESSAGE: String =
        "This model can't read images. Turn on image support for it on the Models screen, " +
            "or switch to a vision-capable model."

    /**
     * The pipeline starts on a CLOUD node. Attachments stay on-device by design,
     * so the run is blocked rather than dropping the image silently.
     */
    const val CLOUD_ATTACHMENT_BLOCKED_MESSAGE: String =
        "Images stay on your device and aren't sent to cloud models. Use a pipeline that starts " +
            "with an on-device step to send a picture."

    /**
     * The pipeline has no on-device step that could read the image (no reachable
     * `LITE_RT` node carrying the original task); the engine would otherwise drop
     * it while the run looks normal.
     */
    const val PIPELINE_NO_VISION_MESSAGE: String =
        "This pipeline has no on-device step that can read an image. Pick a pipeline with an " +
            "on-device model step to send a picture."

    /**
     * Returns the user-facing message for [block].
     *
     * @param block Why the image may not start the run.
     * @return The message to show.
     */
    fun messageFor(block: ImageAttachmentBlock): String = when (block) {
        ImageAttachmentBlock.CLOUD_ENTRY -> CLOUD_ATTACHMENT_BLOCKED_MESSAGE
        ImageAttachmentBlock.MODEL_NO_VISION -> MODEL_NO_VISION_MESSAGE
        ImageAttachmentBlock.NO_VISION_STEP -> PIPELINE_NO_VISION_MESSAGE
    }
}
