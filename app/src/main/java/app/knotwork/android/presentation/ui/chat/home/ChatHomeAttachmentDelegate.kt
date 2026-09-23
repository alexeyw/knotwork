package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.models.ChatSession
import app.knotwork.android.domain.models.MessageAttachment
import app.knotwork.android.domain.services.AttachmentStore
import app.knotwork.android.domain.services.ImageCaptureStore
import app.knotwork.android.domain.usecases.CheckImageAttachmentUseCase
import app.knotwork.android.presentation.ui.common.ImageAttachmentBlockCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Image-attachment delegate of [ChatHomeViewModel].
 *
 * Owns the composer image attachment (`composer.attachment`), the image-source
 * chooser sheet (`sourceChooserVisible`), and the full-screen image viewer
 * (`imageViewer`) — the leaf slices of [ChatHomeScreenState] it writes — plus
 * the `attachmentErrorEvents` / `attachmentReplacedEvents` one-shots. The multimodal **pre-flight**
 * ([preflightBlockReason]) is exposed publicly because the send path
 * ([ChatHomeViewModel.sendMessage]) consults it before enqueueing an image
 * message; everything else is self-contained.
 *
 * Shares the ViewModel's [scope] and single [state] reducer (see
 * `docs/architecture.md` §1.2).
 *
 * @property scope The ViewModel's [androidx.lifecycle.viewModelScope].
 * @property state The ViewModel's single source-of-truth state flow (shared reducer).
 * @property attachmentStore Ingests / downscales / deletes attachment files and
 *   resolves absolute paths for the preview / viewer.
 * @property imageCaptureStore Mints the camera's capture target and hands its
 *   bytes over exactly once, deleting the full-resolution original.
 * @property checkImageAttachment The multimodal pre-flight shared with the share
 *   target.
 * @property sessions Provider of the live session cache (the pre-flight reads the
 *   active session's pipeline binding); supplied by the ViewModel which owns the cache.
 */
class ChatHomeAttachmentDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatHomeScreenState>,
    private val attachmentStore: AttachmentStore,
    private val imageCaptureStore: ImageCaptureStore,
    private val checkImageAttachment: CheckImageAttachmentUseCase,
    private val sessions: () -> List<ChatSession>,
) {

    private val _attachmentErrorEvents: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)

    /**
     * One-shot signal raised when ingesting a picked/captured image fails. The
     * screen surfaces a transient `KnotworkSnackbar`; deliberately not modelled
     * as a [ChatHomeUiState.Error] so a failed attachment never clobbers the
     * surface's main visual state (e.g. an in-flight `Generating` run).
     */
    val attachmentErrorEvents: SharedFlow<Unit> = _attachmentErrorEvents.asSharedFlow()

    /**
     * Monotonic id of the most recent pick.
     *
     * A type check on the draft cannot tell "my pick still owns the slot" from
     * "a newer pick replaced it and is also in flight" — both read as
     * `Processing`. Under two quick picks that let whichever ingest finished
     * *first* win and the later one delete its own file, which is backwards.
     * The token makes ownership identity rather than shape.
     */
    private var pickGeneration: Long = 0

    private val _attachmentReplacedEvents: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)

    /**
     * Emitted when picking an image discards one already attached.
     *
     * The composer holds exactly one attachment, and the replacement used to be
     * silent: the first external tester attached a second image, watched the
     * first vanish with no explanation, and called it "an unpleasant surprise".
     * The limit stays — saying so is what was missing.
     */
    val attachmentReplacedEvents: SharedFlow<Unit> = _attachmentReplacedEvents.asSharedFlow()

    /**
     * Resolves whether an image message must be blocked before it is enqueued,
     * returning the user-facing block reason or `null` when the send may proceed.
     *
     * The decision is [CheckImageAttachmentUseCase]'s — the same one the share
     * target asks — for the pipeline bound to the active session; the words are
     * [ImageAttachmentBlockCopy]'s.
     *
     * @return The block reason, or `null` to allow the send.
     */
    suspend fun preflightBlockReason(): String? {
        val sessionId = state.value.thread.currentSessionId
        val pipelineId = sessions().firstOrNull { it.id == sessionId }?.pipelineId
        return checkImageAttachment(pipelineId)?.let(ImageAttachmentBlockCopy::messageFor)
    }

    /** Opens the image-source chooser sheet (Photo library / Camera). */
    fun onAttachClicked() {
        state.update { it.copy(sourceChooserVisible = true) }
    }

    /** Dismisses the image-source chooser sheet without a choice. */
    fun dismissSourceChooser() {
        state.update { it.copy(sourceChooserVisible = false) }
    }

    /**
     * Ingests an image picked from the photo library: marks the composer
     * attachment [ComposerAttachmentDraft.Processing], downscales + re-encodes it
     * through [AttachmentStore], then settles to [ComposerAttachmentDraft.Ready]
     * (or restores the previous draft and surfaces an error on failure).
     *
     * @param uri content URI string of the picked image (another app's provider).
     */
    fun onImagePicked(uri: String) {
        ingestIntoSlot { attachmentStore.ingestUri(uri) }
    }

    /**
     * Allocates the file the camera app will write a new photo to.
     *
     * @return The content URI to launch the camera with; hand it back to
     *   [onCaptureResult] when the camera returns.
     */
    fun newCaptureUri(): String = imageCaptureStore.newCaptureUri()

    /**
     * Settles a camera capture. On success the photo is ingested like a picked
     * image; either way the full-resolution original the camera wrote is deleted
     * — it is the only copy that keeps the photo's EXIF, GPS included.
     *
     * @param uri The URI [newCaptureUri] returned for this capture.
     * @param success Whether the camera reports a photo was taken. A cancelled
     *   capture may still have left a partial file, which is discarded.
     */
    fun onCaptureResult(uri: String, success: Boolean) {
        if (!success) {
            scope.launch { imageCaptureStore.discard(uri) }
            return
        }
        ingestIntoSlot {
            imageCaptureStore.consume(uri).fold(
                onSuccess = { bytes -> attachmentStore.ingest(bytes) },
                onFailure = { error -> Result.failure(error) },
            )
        }
    }

    /**
     * The composer-slot protocol shared by a picked and a captured image: mark the
     * slot [ComposerAttachmentDraft.Processing], run [ingest], then settle the slot
     * only if this ingest still owns it.
     *
     * @param ingest Produces the stored attachment (or a failure) for this pick.
     */
    private fun ingestIntoSlot(ingest: suspend () -> Result<MessageAttachment>) {
        // Discard any prior pending attachment's file (it was never sent) before
        // replacing the draft, so a re-pick doesn't leave an orphan behind.
        val replaced = state.value.composer.attachment as? ComposerAttachmentDraft.Ready
        val replacedPath = replaced?.attachment?.path
        val generation = ++pickGeneration
        state.update {
            it.copy(
                sourceChooserVisible = false,
                composer = it.composer.copy(attachment = ComposerAttachmentDraft.Processing),
            )
        }
        scope.launch {
            // The previous file is deleted only once its replacement exists.
            // Deleting first meant a failed ingest destroyed the image the user
            // already had and left the composer empty — the loss was real
            // whether or not anything said so.
            val stored = ingest().getOrNull()
            // Two ways this pick can stop owning the slot while its ingest is in
            // flight: the user taps ✕ (live during `Processing`), or picks again.
            // Anything that puts an image back — the new one on success, the
            // previous one on failure — must check both, or a removal un-does
            // itself and an older pick overwrites a newer one.
            val stillOurs = generation == pickGeneration &&
                state.value.composer.attachment is ComposerAttachmentDraft.Processing
            if (stored != null && stillOurs) {
                if (replacedPath != null) {
                    attachmentStore.delete(replacedPath)
                }
                val ready = ComposerAttachmentDraft.Ready(
                    attachment = stored,
                    absolutePath = attachmentStore.absolutePathFor(stored.path),
                    detail = attachmentDetailLabel(stored.width, stored.height, attachmentStore.sizeBytes(stored.path)),
                )
                state.update { it.copy(composer = it.composer.copy(attachment = ready)) }
                if (replacedPath != null) {
                    _attachmentReplacedEvents.tryEmit(Unit)
                }
            } else if (stillOurs) {
                // Transient failure — surface a snackbar rather than flipping the
                // whole surface to Error (which would clobber an in-flight run).
                //
                // The draft goes back to whatever was attached before, not to
                // null: a failed re-pick has replaced nothing, and clearing the
                // slot would take away an image the user still has (its file is
                // no longer deleted up front either) on the strength of an
                // attempt that did not succeed.
                state.update { it.copy(composer = it.composer.copy(attachment = replaced)) }
                _attachmentErrorEvents.tryEmit(Unit)
            } else {
                // The slot moved on — removed, or claimed by a later pick — so
                // nothing goes back into it, and the file THIS pick ingested has
                // no owner and is deleted.
                //
                // `replacedPath` is deliberately left alone: it is not this
                // pick's file, and whoever owns the slot now may still be
                // showing it or may hand it back on its own failure. Deleting it
                // from here could take away an image the user still has. If it
                // does end up unreferenced, `CleanupOrphanAttachmentsUseCase`
                // sweeps it — an orphaned file is recoverable, a deleted one the
                // user wanted is not.
                if (stored != null) attachmentStore.delete(stored.path)
            }
        }
    }

    /** Removes the pending composer attachment without sending it, deleting its file. */
    fun removeAttachment() {
        val path = (state.value.composer.attachment as? ComposerAttachmentDraft.Ready)?.attachment?.path
        state.update { it.copy(composer = it.composer.copy(attachment = null)) }
        if (path != null) {
            scope.launch { attachmentStore.delete(path) }
        }
    }

    /**
     * Opens the full-screen viewer for [attachment]. The existence check and
     * size read run off the main thread (via [AttachmentStore]) so tapping a
     * bubble never blocks the UI; the viewer renders the calm "no longer
     * available" state when retention has cleared the file.
     *
     * @param attachment the attachment of the tapped message bubble.
     */
    fun openImageViewer(attachment: MessageAttachment) {
        val absolutePath = attachmentStore.absolutePathFor(attachment.path)
        scope.launch {
            val exists = attachmentStore.exists(attachment.path)
            val detail =
                attachmentDetailLabel(attachment.width, attachment.height, attachmentStore.sizeBytes(attachment.path))
            state.update {
                it.copy(
                    imageViewer = ImageViewerTarget(
                        model = if (exists) absolutePath else null,
                        fileName = attachment.path,
                        dimensionsLabel = detail,
                        isMissing = !exists,
                    ),
                )
            }
        }
    }

    /** Closes the full-screen image viewer. */
    fun dismissImageViewer() {
        state.update { it.copy(imageViewer = null) }
    }

    /**
     * Builds the mono dimensions/size label shown on the composer preview and
     * the viewer top bar, e.g. `712×1536 · 84 KB`. Pure: [sizeBytes] is read by
     * the caller off the main thread. Falls back to dimensions only when the
     * size is unknown.
     */
    private fun attachmentDetailLabel(width: Int, height: Int, sizeBytes: Long): String {
        val sizeKb = (sizeBytes / BYTES_PER_KB).toInt()
        val dimensions = "$width×$height"
        return if (sizeKb > 0) "$dimensions · $sizeKb KB" else dimensions
    }

    private companion object {
        /** Divisor used to render an attachment's file size in kilobytes. */
        const val BYTES_PER_KB: Long = 1024L
    }
}
