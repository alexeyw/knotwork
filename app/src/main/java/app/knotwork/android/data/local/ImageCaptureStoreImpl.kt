package app.knotwork.android.data.local

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.knotwork.android.domain.constants.TransientCacheDirectory
import app.knotwork.android.domain.services.ImageCaptureStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Filesystem-backed [ImageCaptureStore] rooted at `cacheDir/images/`
 * ([TransientCacheDirectory.CAMERA_CAPTURE]) — the directory
 * `res/xml/file_paths.xml` exposes to the camera app through the app's
 * `FileProvider`.
 *
 * A URI is mapped back to its file by name, and only when it is this store's:
 * this app's `FileProvider` authority, the capture directory's path segment, and
 * a name that stays inside the directory ([PathContainment]). Anything else is
 * refused, so neither [consume] nor [discard] can be pointed at another file.
 *
 * @property context Application context: locates the cache directory and mints
 *   `FileProvider` URIs.
 */
@Singleton
class ImageCaptureStoreImpl @Inject constructor(@ApplicationContext private val context: Context) : ImageCaptureStore {

    /** Dispatcher for blocking file I/O; swapped in unit tests. */
    internal var dispatcher: CoroutineDispatcher = Dispatchers.IO

    override fun newCaptureUri(): String {
        val file = File(captureDir(), "$CAPTURE_PREFIX${UUID.randomUUID()}$CAPTURE_SUFFIX")
        return FileProvider.getUriForFile(context, authority(), file).toString()
    }

    override suspend fun consume(uri: String): Result<ByteArray> = withContext(dispatcher) {
        val file = captureFileOf(uri)
            ?: return@withContext Result.failure(IllegalArgumentException("Not a camera capture of this app"))
        try {
            Result.success(file.readBytes())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // The camera reported success but wrote nothing (or a truncated file
            // it cannot finish). Only the type is logged — the name is ours, but
            // the rule for WARN+ in this codebase is no paths at all.
            Timber.w("Camera capture could not be read (%s)", e.javaClass.simpleName)
            Result.failure(e)
        } finally {
            // The original leaves the device's storage here, read or not: it is
            // the only copy that keeps the photo's EXIF, GPS included.
            file.delete()
        }
    }

    override suspend fun discard(uri: String) {
        withContext(dispatcher) { captureFileOf(uri)?.delete() }
    }

    /**
     * Maps [uri] back to the capture file it names, or `null` when it is not one
     * of this store's captures: another authority, another provider path, or a
     * name that would leave the capture directory.
     */
    private fun captureFileOf(uri: String): File? {
        val parsed: Uri = uri.toUri()
        if (parsed.authority != authority()) return null
        val segments = parsed.pathSegments
        if (segments.size != CAPTURE_PATH_SEGMENTS || segments[0] != DIR.dirName) return null
        val dir = captureDir()
        return PathContainment.childOrNull(File(dir, segments[1]), dir)
    }

    /** Returns the capture directory, creating it on first use. */
    private fun captureDir(): File = File(context.cacheDir, DIR.dirName).apply { mkdirs() }

    private fun authority(): String = "${context.packageName}.$FILE_PROVIDER_SUFFIX"

    private companion object {
        val DIR = TransientCacheDirectory.CAMERA_CAPTURE

        /** Suffix of this app's `FileProvider` authority (`${applicationId}.fileprovider`). */
        const val FILE_PROVIDER_SUFFIX = "fileprovider"

        /** `<cache-path name>/<file name>` — the shape of every URI the provider mints here. */
        const val CAPTURE_PATH_SEGMENTS = 2

        const val CAPTURE_PREFIX = "capture_"
        const val CAPTURE_SUFFIX = ".jpg"
    }
}
