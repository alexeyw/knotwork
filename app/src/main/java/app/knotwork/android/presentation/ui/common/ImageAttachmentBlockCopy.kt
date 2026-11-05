package app.knotwork.android.presentation.ui.common

import androidx.annotation.StringRes
import app.knotwork.android.R
import app.knotwork.android.domain.usecases.ImageAttachmentBlock

/**
 * The chat composer's words for each [ImageAttachmentBlock]. Calm, non-alarmist
 * copy in line with the attachment UX. The share target says the same in shorter
 * string resources (`share_image_blocked_*`), because its notice is a toast and a
 * toast holds two lines on Android 12+.
 */
object ImageAttachmentBlockCopy {

    /**
     * Returns the user-facing message for [block]:
     *
     * - [ImageAttachmentBlock.MODEL_NO_VISION] — the active local model is not marked
     *   vision-capable; the run is blocked so the native inference layer never receives an
     *   image it cannot decode.
     * - [ImageAttachmentBlock.CLOUD_ENTRY] — the pipeline starts on a CLOUD node; attachments
     *   stay on-device by design, so the run is blocked rather than dropping the image silently.
     * - [ImageAttachmentBlock.NO_VISION_STEP] — no reachable on-device step carries the original
     *   task, so the engine would drop the image while the run looks normal.
     *
     * @param block Why the image may not start the run.
     * @return The string resource of the message, worded by the screen in the interface language.
     */
    @StringRes
    fun messageFor(block: ImageAttachmentBlock): Int = when (block) {
        ImageAttachmentBlock.CLOUD_ENTRY -> R.string.chat_image_blocked_cloud
        ImageAttachmentBlock.MODEL_NO_VISION -> R.string.chat_image_blocked_model
        ImageAttachmentBlock.NO_VISION_STEP -> R.string.chat_image_blocked_pipeline
    }
}
