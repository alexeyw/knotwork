package app.knotwork.android.data.local

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import app.knotwork.android.domain.constants.TransientCacheDirectory
import app.knotwork.android.domain.services.AudioCaptureStore
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
 * Filesystem-backed [AudioCaptureStore] rooted at `cacheDir/audio/` inside the
 * app's private cache.
 *
 * The audio cache holds only short-lived voice-input clips: each one exists just
 * long enough to be transcribed and is then deleted, so it lives under
 * [Context.cacheDir] (OS-reclaimable) rather than persistent storage. Files are
 * UUID-named, so no two callers collide and no lock is needed; blocking I/O runs
 * on [dispatcher] ([Dispatchers.IO] in production, overridable in tests).
 *
 * @property context Application context, used solely to locate [Context.cacheDir]
 *   and read picked content URIs.
 */
@Singleton
class AudioCaptureStoreImpl @Inject constructor(@ApplicationContext private val context: Context) : AudioCaptureStore {

    /** Dispatcher for blocking I/O; swapped in unit tests. */
    internal var dispatcher: CoroutineDispatcher = Dispatchers.IO

    override fun newRecordingFile(): String = File(rootDir(), "${UUID.randomUUID()}.$WAV_EXTENSION").absolutePath

    override suspend fun importFromUri(uri: String): Result<String> = withContext(dispatcher) {
        // Tracked so a copy that throws (or is cancelled) midway, or a resolver
        // that never opens, never leaves a truncated clip behind in the cache.
        // `File.delete()` does not suspend, so calling it on the cancellation
        // path before re-throwing is safe.
        var target: File? = null
        try {
            val parsed = uri.toUri()
            // A picker result today, but the read runs with the app's identity, so
            // the same rule as the image sink applies: another app's content only.
            if (!ForeignContentUri.isAcceptable(context, parsed)) {
                return@withContext Result.failure(SecurityException("Audio URI is not another app's content URI"))
            }
            val extension = extensionFor(uri)
            val file = File(rootDir(), "${UUID.randomUUID()}.$extension").also { target = it }
            val copied = context.contentResolver.openInputStream(parsed)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
            if (copied) {
                Result.success(file.absolutePath)
            } else {
                target?.delete()
                Result.failure(IOException("Could not read audio URI"))
            }
        } catch (e: CancellationException) {
            target?.delete()
            throw e
        } catch (e: Exception) {
            // Resolver failures vary by source (FileNotFoundException,
            // SecurityException, provider-specific RuntimeExceptions); any of them
            // means "could not import", surfaced as a failed Result. Only the type
            // is logged: a resolver's message usually quotes the URI.
            Timber.e("Failed to import audio URI (%s)", e.javaClass.simpleName)
            target?.delete()
            Result.failure(e)
        }
    }

    override suspend fun delete(path: String): Result<Unit> = withContext(dispatcher) {
        try {
            val target = resolveSafe(path)
            when {
                target == null || !target.exists() -> Result.success(Unit)
                target.delete() -> Result.success(Unit)
                else -> Result.failure(IOException("Failed to delete audio clip $path"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.e(e, "Failed to delete audio clip %s", path)
            Result.failure(e)
        }
    }

    /**
     * Returns the audio cache directory, creating it lazily on first access.
     */
    private fun rootDir(): File {
        val dir = File(context.cacheDir, TransientCacheDirectory.VOICE_CLIP.dirName)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Resolves an absolute path to a file, but only when it lives inside the
     * audio cache directory — a guard so [delete] cannot remove a file outside
     * the store. The test is [PathContainment]'s: canonicalise, then compare, so
     * `…/audio/../files/x` is refused rather than passing a prefix test on the
     * string as written.
     */
    private fun resolveSafe(path: String): File? {
        if (path.isEmpty()) return null
        return PathContainment.childOrNull(File(path), rootDir())
    }

    /**
     * Best-effort file extension for an imported clip: resolver MIME type first,
     * then the URI's own extension, falling back to [WAV_EXTENSION]. The native
     * decoder sniffs content, so this only keeps the temp file name sensible.
     */
    private fun extensionFor(uri: String): String {
        val mime = runCatching { context.contentResolver.getType(uri.toUri()) }.getOrNull()
        val fromMime = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        if (!fromMime.isNullOrBlank()) return fromMime
        val fromUri = MimeTypeMap.getFileExtensionFromUrl(uri)
        return if (fromUri.isNullOrBlank()) WAV_EXTENSION else fromUri
    }

    private companion object {
        /** Extension used for recorder output and the import fallback. */
        const val WAV_EXTENSION = "wav"
    }
}
