package app.knotwork.android.data.local

import app.knotwork.android.di.IoDispatcher
import app.knotwork.android.domain.models.ModelFileHash
import app.knotwork.android.domain.services.ModelFileHasher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject

/**
 * [ModelFileHasher] that streams the file through SHA-256 in fixed-size chunks.
 *
 * A model file is several gigabytes, so it is never read into memory: one
 * buffer is reused for the whole read, and cancellation is checked between
 * chunks so a pass stopped by WorkManager ends within one chunk instead of
 * finishing the file.
 *
 * The file is stamped (size and last-modified time) before and after the read.
 * A file that changed in between — a re-download writing over it, say — yields
 * no hash: the digest would describe a mixture of two files.
 *
 * @param ioDispatcher Dispatcher the blocking read runs on.
 * @param stampOf Reads a file's stamp, or `null` when it is not a readable
 *   file. A parameter only so a test can make a file "change" mid-read.
 */
class StreamingModelFileHasher internal constructor(
    private val ioDispatcher: CoroutineDispatcher,
    private val stampOf: (File) -> FileStamp?,
) : ModelFileHasher {

    /**
     * Production constructor: stamps the file from the file system.
     *
     * @param ioDispatcher Dispatcher the blocking read runs on.
     */
    @Inject
    constructor(@IoDispatcher ioDispatcher: CoroutineDispatcher) : this(ioDispatcher, ::readStamp)

    override suspend fun hash(path: String): ModelFileHash? = withContext(ioDispatcher) {
        val file = File(path)
        val before = stampOf(file) ?: return@withContext null
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        } catch (e: IOException) {
            Timber.w(e, "Model file could not be read for hashing")
            return@withContext null
        }
        if (stampOf(file) != before) {
            Timber.w("Model file changed while it was hashed; leaving it for the next pass")
            return@withContext null
        }
        ModelFileHash(
            sha256 = digest.digest().toHex(),
            fileSizeBytes = before.sizeBytes,
            fileModifiedAtMs = before.modifiedAtMs,
        )
    }

    /**
     * What identifies a file's content without reading it: its length and its
     * last-modified time.
     *
     * @property sizeBytes The file's length in bytes.
     * @property modifiedAtMs The file's last-modified time, in epoch milliseconds.
     */
    data class FileStamp(val sizeBytes: Long, val modifiedAtMs: Long)

    private companion object {
        const val HASH_ALGORITHM = "SHA-256"

        /** One mebibyte: large enough that the per-chunk overhead is noise. */
        const val BUFFER_BYTES = 1 shl 20

        /** Reads [file]'s stamp, or `null` when it is not a regular file. */
        fun readStamp(file: File): FileStamp? =
            if (file.isFile) FileStamp(sizeBytes = file.length(), modifiedAtMs = file.lastModified()) else null

        /** Lowercase hex of the digest bytes. */
        fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
    }
}
