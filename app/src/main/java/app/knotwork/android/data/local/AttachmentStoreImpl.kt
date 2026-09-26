package app.knotwork.android.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import app.knotwork.android.domain.models.MessageAttachment
import app.knotwork.android.domain.services.AttachmentStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Filesystem-backed [AttachmentStore] rooted at `files/attachments/` inside the
 * app's private storage ([Context.filesDir]).
 *
 * Ingest never persists the original image. The pipeline is:
 * 1. **bounds decode** to read the source dimensions without allocating pixels;
 * 2. **sampled decode** with a power-of-two `inSampleSize` that lands the
 *    longest side in `[cap, 2·cap)`, so the expensive full-resolution bitmap is
 *    never allocated;
 * 3. **EXIF orientation** applied from the original bytes (the sampled bitmap
 *    has no metadata), so camera photos are stored upright;
 * 4. **exact aspect-preserving scale** down to [MAX_LONGEST_SIDE_PX] — never a
 *    square crop, because the multimodal model keeps the natural aspect ratio;
 * 5. **JPEG re-encode** at [JPEG_QUALITY] into a UUID-named file.
 *
 * The longest-side cap is the client-side storage bound; the model performs its
 * own token-budget resize at inference time, so this only protects on-disk size
 * and decode memory.
 *
 * **Concurrency.** No lock is needed: every ingest writes a freshly minted
 * UUID file (no two callers can target the same path), [delete] is path-scoped
 * and idempotent, and the store keeps no shared in-memory state. Blocking work
 * runs on [dispatcher] ([Dispatchers.IO] in production, overridable in tests).
 *
 * @property context Application context, used solely to locate [Context.filesDir].
 */
@Singleton
class AttachmentStoreImpl @Inject constructor(@ApplicationContext private val context: Context) : AttachmentStore {

    /** Dispatcher for blocking I/O and bitmap work; swapped in unit tests. */
    internal var dispatcher: CoroutineDispatcher = Dispatchers.IO

    override suspend fun ingest(bytes: ByteArray): Result<MessageAttachment> = withContext(dispatcher) {
        try {
            val image = decodeDownscaled(bytes)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("Attachment bytes could not be decoded as an image"),
                )
            try {
                val fileName = "${UUID.randomUUID()}.jpg"
                FileOutputStream(File(rootDir(), fileName)).use { out ->
                    image.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                Result.success(
                    MessageAttachment(
                        path = fileName,
                        mimeType = MIME_JPEG,
                        width = image.width,
                        height = image.height,
                    ),
                )
            } finally {
                image.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.e(e, "Failed to ingest image attachment")
            Result.failure(e)
        }
    }

    override suspend fun ingestUri(uri: String): Result<MessageAttachment> {
        val parsed = uri.toUri()
        if (!ForeignContentUri.isAcceptable(context, parsed)) {
            // The URI is caller-supplied (a share can come from any app), so it is
            // neither logged nor quoted: a warning reaches crash reports after opt-in.
            return Result.failure(SecurityException("Attachment URI is not another app's content URI"))
        }
        val bytes = withContext(dispatcher) {
            try {
                context.contentResolver.openInputStream(parsed)?.use { it.readAtMost(MAX_INGEST_BYTES) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Resolver failures vary by source (FileNotFoundException,
                // SecurityException, provider-specific RuntimeExceptions); any of
                // them means "could not read", surfaced as a failed Result. Only
                // the type is logged: a resolver's message usually quotes the URI.
                Timber.e("Failed to read attachment URI (%s)", e.javaClass.simpleName)
                null
            }
        } ?: return Result.failure(IOException("Could not read attachment URI"))
        if (bytes.size > MAX_INGEST_BYTES) {
            Timber.w("Refused an attachment larger than the ingest cap")
            return Result.failure(IOException("Attachment is larger than the ingest cap"))
        }
        return ingest(bytes)
    }

    /**
     * Reads the stream up to one byte past [limit] and stops, so a stream that
     * never ends — or is simply too large — costs at most `limit + 1` bytes of
     * memory. The caller refuses anything longer than [limit].
     *
     * @param limit Most bytes an acceptable stream may hold.
     * @return What was read: the whole stream, or `limit + 1` bytes of it.
     */
    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK_BYTES)
        var remaining = limit.toLong() + 1
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    override suspend fun delete(path: String): Result<Unit> = withContext(dispatcher) {
        try {
            val target = resolveSafe(path)
            when {
                target == null || !target.exists() -> Result.success(Unit)
                target.delete() -> Result.success(Unit)
                else -> Result.failure(IOException("Failed to delete attachment $path"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.e(e, "Failed to delete attachment %s", path)
            Result.failure(e)
        }
    }

    override suspend fun listStoredPaths(minAgeMillis: Long): Result<List<String>> = withContext(dispatcher) {
        try {
            // Files younger than the grace window are skipped: an attachment is
            // written to disk the moment it is picked, but only referenced by a
            // message row on send — without the window the orphan sweep could
            // delete an in-flight attachment still sitting in the composer.
            val cutoff = System.currentTimeMillis() - minAgeMillis
            val names = rootDir().listFiles()
                ?.filter { it.isFile && it.lastModified() <= cutoff }
                ?.map { it.name }
                .orEmpty()
            Result.success(names)
        } catch (e: SecurityException) {
            Timber.e(e, "Failed to list attachment directory")
            Result.failure(e)
        }
    }

    override fun absolutePathFor(path: String): String = File(rootDir(), path).absolutePath

    override suspend fun exists(path: String): Boolean = withContext(dispatcher) {
        val target = resolveSafe(path)
        target != null && target.exists()
    }

    override suspend fun sizeBytes(path: String): Long = withContext(dispatcher) {
        val target = resolveSafe(path)
        if (target != null && target.isFile) target.length() else 0L
    }

    override suspend fun deleteAll(): Boolean = withContext(dispatcher) {
        // Not rootDir(): that would recreate the directory it is asked to remove.
        val dir = File(context.filesDir, ATTACHMENTS_DIR)
        dir.deleteRecursively()
    }

    /**
     * Returns the attachment directory, creating it lazily on first access.
     */
    private fun rootDir(): File {
        val dir = File(context.filesDir, ATTACHMENTS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Resolves a store-relative path to a file inside the attachment directory,
     * rejecting anything that is not a bare file name (path traversal guard).
     * Attachments are stored flat, so a separator or `..` segment is illegal.
     */
    private fun resolveSafe(path: String): File? {
        if (path.isEmpty() || path.contains('/') || path.contains('\\') || path.contains("..")) {
            return null
        }
        return File(rootDir(), path)
    }

    /**
     * Decodes [bytes] into an upright, aspect-preserving, downscaled bitmap, or
     * `null` when the bytes are not a decodable image. The returned bitmap is
     * the caller's to recycle.
     */
    private fun decodeDownscaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight)
        }
        val sampled = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val upright = applyExifOrientation(bytes, sampled)
        return scaleToCap(upright)
    }

    /**
     * Largest power-of-two sub-sampling factor whose result keeps the longest
     * side at or above [MAX_LONGEST_SIDE_PX] (so the exact scale that follows
     * only ever shrinks), minimising the allocated bitmap.
     */
    private fun computeInSampleSize(width: Int, height: Int): Int {
        var longest = max(width, height)
        var sample = 1
        while (longest / 2 >= MAX_LONGEST_SIDE_PX) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    /**
     * Rotates/flips [bitmap] per the EXIF orientation stored in the original
     * [bytes]. Returns [bitmap] unchanged for a normal orientation; otherwise
     * returns a new bitmap and recycles the input.
     */
    private fun applyExifOrientation(bytes: ByteArray, bitmap: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: IOException) {
            Timber.w(e, "Could not read EXIF orientation; assuming normal")
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(ROTATE_90)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(ROTATE_180)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(ROTATE_270)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    /**
     * Scales [bitmap] down so its longest side equals [MAX_LONGEST_SIDE_PX],
     * preserving the aspect ratio. Returns [bitmap] unchanged when it already
     * fits; otherwise returns a new bitmap and recycles the input.
     */
    private fun scaleToCap(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_LONGEST_SIDE_PX) {
            return bitmap
        }
        val ratio = MAX_LONGEST_SIDE_PX.toFloat() / longest
        val targetWidth = max(1, (bitmap.width * ratio).roundToInt())
        val targetHeight = max(1, (bitmap.height * ratio).roundToInt())
        val scaled = bitmap.scale(targetWidth, targetHeight)
        if (scaled !== bitmap) {
            bitmap.recycle()
        }
        return scaled
    }

    companion object {
        /** Directory name under [Context.filesDir] holding attachment files. */
        const val ATTACHMENTS_DIR = "attachments"

        /**
         * Most bytes [ingestUri] reads from a content URI before it refuses the
         * attachment: 64 MiB.
         *
         * The stream comes from another app — a share from any installed app, a
         * picked file — and used to be read into memory whole, so an endless or
         * huge one ended the process. The value is a judgement, not a measurement:
         * well above the largest camera JPEG in circulation (a 200 MP capture is
         * tens of megabytes), and a heap a phone can hold for one decode.
         */
        const val MAX_INGEST_BYTES = 64 * 1024 * 1024

        /** Buffer size of the bounded read in [ingestUri]. */
        private const val READ_CHUNK_BYTES = 64 * 1024

        /**
         * Longest-side pixel cap for a stored attachment. Aspect ratio is always
         * preserved (never a square crop). Generous enough to keep screenshot /
         * document text legible; the model resizes further per its visual-token
         * budget at inference time.
         */
        const val MAX_LONGEST_SIDE_PX = 1536

        /** JPEG quality used when re-encoding a downscaled attachment. */
        const val JPEG_QUALITY = 85

        /** MIME type of every stored attachment (all re-encoded to JPEG). */
        const val MIME_JPEG = "image/jpeg"

        private const val ROTATE_90 = 90f
        private const val ROTATE_180 = 180f
        private const val ROTATE_270 = 270f
    }
}
