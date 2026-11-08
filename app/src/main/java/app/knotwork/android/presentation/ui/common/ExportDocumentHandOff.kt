package app.knotwork.android.presentation.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.knotwork.android.domain.constants.TransientCacheDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * The one way an export document leaves the app, shared by the journal and chat
 * exports: staged as a `FileProvider` file for the share sheet ([share]), or
 * written into a document the user picked ([write]) — both through one encoder
 * ([bytes]), so a shared file and a saved file of the same document are the same
 * bytes.
 */
object ExportDocumentHandOff {

    private const val TAG = "ExportDocumentHandOff"

    /**
     * The bytes an exported document is handed out as, whichever way it leaves.
     *
     * Both hand-offs below — the file staged for the share sheet and the file the user
     * picked — write exactly this, so the two can never disagree about a document:
     * UTF-8, no byte-order mark, nothing appended. A second encoder on either path
     * (a `writeText` with another charset, a pretty-printer run "to be safe") is the
     * divergence this function exists to rule out.
     *
     * @param json The rendered export document.
     * @return Its UTF-8 bytes.
     */
    fun bytes(json: String): ByteArray = json.toByteArray(Charsets.UTF_8)

    /**
     * Stages [json] as a real file and offers it to the system share sheet.
     *
     * **Why a file and not `EXTRA_TEXT`.** An intent's extras cross the Binder, whose
     * transaction buffer is 1 MB per process; a `String` takes two bytes a character
     * in a `Parcel`. Measured on emulators (API 34 and 36): a share chooser carrying
     * 340 000 characters as text starts, one carrying 360 000 fails with a
     * `TransactionTooLargeException` thrown out of `startActivity`. Exports grow with
     * what they hold — a journal up to its retention ceiling, a chat with every long
     * answer in it — and the export somebody most wants to send is the long one. So
     * the document travels as a `FileProvider` URI, whose size the Binder never sees.
     *
     * The staging directory is cleared per share so copies of an old export cannot
     * accumulate in the cache — and, more to the point, so a stale document can never
     * be handed to the next share. Each export path stages into its own
     * [TransientCacheDirectory], so sharing one kind of document never deletes
     * another a receiving app may still be reading. Whatever a share leaves behind is
     * removed by the daily transient-cache sweep.
     *
     * @param context Context able to resolve the `FileProvider` and start the chooser.
     * @param json The rendered export document.
     * @param fileName Name the receiving app sees.
     * @param mimeType Type the share intent declares.
     * @param stagingDirectory Cache directory the `FileProvider` serves this export from.
     * @param chooserTitle Localised chooser title.
     * @param subject Subject offered to receivers that have one (a mail app), or `null`.
     * @return `true` when the share sheet was opened, `false` when the document could
     *   not be staged or no app could receive it — the caller says so, because a
     *   share that silently does nothing is indistinguishable from one the user
     *   dismissed.
     */
    suspend fun share(
        context: Context,
        json: String,
        fileName: String,
        mimeType: String,
        stagingDirectory: TransientCacheDirectory,
        chooserTitle: String,
        subject: String? = null,
    ): Boolean {
        val uri = withContext(Dispatchers.IO) {
            try {
                val shareDir = File(context.cacheDir, stagingDirectory.dirName)
                shareDir.deleteRecursively()
                shareDir.mkdirs()
                val staged = File(shareDir, fileName)
                staged.writeBytes(bytes(json))
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", staged)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to stage an export for sharing")
                null
            }
        } ?: return false

        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, fileName)
            if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            // A device with no app able to receive a JSON file. Rare, but real on a
            // stripped ROM — and the user must be told, not left tapping a dead icon.
            Timber.tag(TAG).w(e, "No activity can receive the export")
            false
        }
    }

    /**
     * Writes [json] into the document the user picked through the Storage Access
     * Framework, then closes the stream.
     *
     * @param outputStream Destination document stream; owned here and always closed.
     * @param json The rendered export document.
     * @param ioDispatcher Dispatcher carrying the write. Injected rather than fixed so
     *   a test can drive it on the test scheduler — with `Dispatchers.IO` hard-wired,
     *   `advanceUntilIdle` returns before the bytes land and the assertion reads an
     *   empty document.
     * @return `true` when the document was written, `false` on an I/O failure — which
     *   the caller surfaces rather than swallowing, because the picker has already
     *   created a file and leaving an empty one behind without a word is the worst of
     *   both outcomes.
     */
    suspend fun write(
        outputStream: OutputStream,
        json: String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Boolean = withContext(ioDispatcher) {
        try {
            outputStream.use { it.write(bytes(json)) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "Failed to write an export document")
            false
        }
    }
}
