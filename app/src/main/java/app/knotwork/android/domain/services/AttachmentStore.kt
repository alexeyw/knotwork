package app.knotwork.android.domain.services

import app.knotwork.android.domain.models.MessageAttachment

/**
 * On-device store for image attachments of chat messages.
 *
 * The store owns a private directory (`files/attachments/`) inside app storage.
 * It never keeps the original picked/captured image: [ingest] decodes the
 * supplied bytes, corrects EXIF orientation, downscales **preserving the aspect
 * ratio** so the longest side fits the model-coordinated cap, re-encodes to
 * JPEG, and writes that single derived file. The returned [MessageAttachment]
 * carries the store-relative path (file name) persisted on the message; the
 * absolute path for loading is resolved on demand via [absolutePathFor].
 *
 * Retention: [delete] removes a single file (called when its owning message or
 * session is deleted), and [listStoredPaths] enumerates every stored file so a
 * background sweep can delete the ones no message references anymore.
 */
interface AttachmentStore {
    /**
     * Ingests raw image bytes: decode → EXIF-correct → aspect-preserving
     * downscale → JPEG re-encode → write into the attachment directory.
     *
     * @param bytes The original image bytes (e.g. read from a picked/captured
     *   content URI). Held in memory only for the duration of the call.
     * @return [Result.success] with the stored [MessageAttachment] (relative
     *   path, `image/jpeg`, downscaled pixel dimensions), or [Result.failure]
     *   when the bytes cannot be decoded as an image or writing fails.
     */
    suspend fun ingest(bytes: ByteArray): Result<MessageAttachment>

    /**
     * Reads the bytes behind a content URI another app handed over (a photo
     * picker result, a shared image) and ingests them via [ingest].
     *
     * The read runs with this app's identity, so only a `content://` URI served
     * by **another** app's provider is opened: a `file://` URI or one of this
     * app's own providers is refused without being read. The app's own camera
     * captures therefore do not come through here — see [ImageCaptureStore].
     *
     * @param uri content URI string of the picked/shared image.
     * @return [Result.success] with the stored [MessageAttachment], or
     *   [Result.failure] when the URI is refused, cannot be read, or cannot be
     *   decoded.
     */
    suspend fun ingestUri(uri: String): Result<MessageAttachment>

    /**
     * Deletes a single stored attachment file. Succeeds (no-op) when the file
     * is already absent so retention is idempotent.
     *
     * @param path The store-relative path (file name) returned by [ingest].
     * @return [Result.success] when the file is gone, [Result.failure] on an
     *   unexpected I/O error.
     */
    suspend fun delete(path: String): Result<Unit>

    /**
     * Enumerates the store-relative paths of stored attachment files at least
     * [minAgeMillis] old. Used by the orphan-cleanup sweep to diff against the
     * set of paths still referenced by messages.
     *
     * The age filter is a safety window: an attachment is written to disk the
     * moment it is picked but only referenced by a message row on send, so a
     * just-created file still in the composer must be excluded from the sweep
     * to avoid deleting an in-flight attachment.
     *
     * @param minAgeMillis minimum age (ms since last modification) a file must
     *   have to be listed; `0` lists every file.
     * @return [Result.success] with the qualifying stored file names, or
     *   [Result.failure] when the directory cannot be read.
     */
    suspend fun listStoredPaths(minAgeMillis: Long = 0L): Result<List<String>>

    /**
     * Resolves a store-relative attachment path to an absolute filesystem path
     * suitable for an image loader. Pure path arithmetic — does not touch disk
     * and does not verify the file exists.
     *
     * @param path The store-relative path (file name) from a [MessageAttachment].
     * @return The absolute path of the file inside the attachment directory.
     */
    fun absolutePathFor(path: String): String

    /**
     * Whether the stored file for [path] currently exists. Runs the filesystem
     * stat off the caller's thread so the presentation layer never blocks the
     * main thread checking attachment availability.
     *
     * @param path The store-relative path (file name) from a [MessageAttachment].
     * @return `true` when the file is present, `false` otherwise (incl. an
     *   invalid path).
     */
    suspend fun exists(path: String): Boolean

    /**
     * Size in bytes of the stored file for [path], or `0` when it is absent.
     * Runs the filesystem stat off the caller's thread.
     *
     * @param path The store-relative path (file name) from a [MessageAttachment].
     * @return The file size in bytes, or `0` if missing.
     */
    suspend fun sizeBytes(path: String): Long
}
