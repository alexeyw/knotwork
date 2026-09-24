package app.knotwork.android.domain.services

/**
 * Owns the files a camera app writes when the composer takes a photo.
 *
 * The camera app writes the **full-resolution original** into a file this store
 * names — the only copy of the photo that still carries its EXIF metadata, GPS
 * included, since the stored attachment is re-encoded without it. So the store
 * gives each capture exactly one way out: [consume] reads it and deletes it,
 * [discard] deletes it unread. A capture whose result never arrives (the process
 * died while the camera was open) is removed by the daily transient-cache sweep.
 *
 * Captures are read from the file, not through `ContentResolver`: the camera
 * writes through this app's own `FileProvider`, and a URI of that provider is
 * exactly what [AttachmentStore.ingestUri] refuses.
 */
interface ImageCaptureStore {
    /**
     * Allocates a fresh capture target and returns the content URI the camera
     * app writes the photo to. Creates the capture directory; not the file.
     *
     * @return The `FileProvider` content URI string to hand to the camera.
     */
    fun newCaptureUri(): String

    /**
     * Reads the photo the camera wrote behind [uri] and deletes the file —
     * whether or not the read succeeds.
     *
     * @param uri A URI returned by [newCaptureUri].
     * @return [Result.success] with the original bytes, or [Result.failure] when
     *   [uri] is not one of this store's captures or the file cannot be read (the
     *   camera wrote nothing).
     */
    suspend fun consume(uri: String): Result<ByteArray>

    /**
     * Deletes the capture behind [uri] without reading it — for a cancelled or
     * failed capture, which may still have left a partial file. A no-op for a
     * URI that is not one of this store's captures or whose file is gone.
     *
     * @param uri A URI returned by [newCaptureUri].
     */
    suspend fun discard(uri: String)
}
