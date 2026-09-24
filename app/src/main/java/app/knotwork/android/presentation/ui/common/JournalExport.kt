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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** MIME type of an exported journal document — the share type and the `CreateDocument` type. */
const val JOURNAL_EXPORT_MIME: String = "application/json"

/** Filename stem of the trigger-evaluation journal export. */
const val TRIGGER_JOURNAL_EXPORT_STEM: String = "trigger-journal"

/** Filename stem of the external-automation request journal export. */
const val EXTERNAL_REQUESTS_EXPORT_STEM: String = "external-requests"

/**
 * Filesystem-safe timestamp for the export filename.
 *
 * `DateTimeFormatter`, not `SimpleDateFormat`: these two formatters are shared by
 * the in-app export (main thread) and the debug dump receiver (its own IO scope),
 * and `SimpleDateFormat` is mutable and not thread-safe — two concurrent formats
 * can interleave into a corrupted string. Immutable formatters remove the hazard
 * rather than relying on the two callers never overlapping.
 */
private val FILE_STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)

/** Human-readable "generated at" label baked into the document header. */
private val GENERATED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

/** Renders [at] in the device's own zone with [formatter]. */
private fun format(formatter: DateTimeFormatter, at: Date): String =
    formatter.format(LocalDateTime.ofInstant(at.toInstant(), ZoneId.systemDefault()))

private const val TAG = "JournalExport"

/**
 * Formats the document's `generatedAt` header label.
 *
 * `Locale.US` and a fixed pattern, not the device locale: this label is read by
 * whoever analyses the file, often on another machine, and a header that renders
 * as `٢٠٢٦-٠٨-٣٠` on one phone and `2026-08-30` on another is a header nobody can
 * sort by. The moment itself is device-local, matching the filename stamp.
 *
 * Shared with `TriggerJournalDumpReceiver` so a dump pulled over adb and a file
 * exported from the app carry the same header, not two dialects of one.
 *
 * @param at Moment to label; defaults to now.
 * @return The label, e.g. `2026-08-30 21:30:00`.
 */
fun journalGeneratedAtLabel(at: Date = Date()): String = format(GENERATED_AT_FORMAT, at)

/**
 * Builds the filename of a journal export: `<stem>-yyyyMMdd-HHmmss.json`.
 *
 * The pattern is **the same one `TriggerJournalDumpReceiver` writes**, stem
 * included, so a soak analysis that globs `trigger-journal-*.json` keeps working
 * whether the file came off a debug device over adb or out of the in-app export
 * on a release build. The stamp is device-local, matching the `generatedAt` label
 * inside the document.
 *
 * @param stem [TRIGGER_JOURNAL_EXPORT_STEM] or [EXTERNAL_REQUESTS_EXPORT_STEM].
 * @param at Moment to stamp the name with; defaults to now.
 * @return The filename, extension included.
 */
fun journalExportFileName(stem: String, at: Date = Date()): String = "$stem-${format(FILE_STAMP_FORMAT, at)}.json"

/**
 * Stages [json] as a real file and offers it to the system share sheet.
 *
 * **Why a file and not `EXTRA_TEXT`.** The whole journal is exported, and its
 * retention ceiling is 2 000 rows — several hundred kilobytes of JSON against a
 * 1 MB Binder transaction budget shared by everything in flight. Passing the
 * document as an intent extra would work on a short journal and throw
 * `TransactionTooLargeException` on a long one, which is exactly the journal
 * anyone would want to send. So it travels as a `FileProvider` URI, the way the
 * Files screen already shares workspace files.
 *
 * The staging directory is cleared per share so copies of an old journal cannot
 * accumulate in the cache — and, more to the point, so a stale journal can never
 * be handed to the next share.
 *
 * @param context Context able to resolve the `FileProvider` and start the chooser.
 * @param json The rendered export document.
 * @param fileName Name the receiving app sees; from [journalExportFileName].
 * @param chooserTitle Localised chooser title.
 * @return `true` when the share sheet was opened, `false` when the document could
 *   not be staged or no app could receive it — the caller says so, because a
 *   share that silently does nothing is indistinguishable from one the user
 *   dismissed.
 */
suspend fun shareJournalDocument(context: Context, json: String, fileName: String, chooserTitle: String): Boolean {
    val uri = withContext(Dispatchers.IO) {
        try {
            val shareDir = File(context.cacheDir, TransientCacheDirectory.JOURNAL_EXPORT.dirName)
            shareDir.deleteRecursively()
            shareDir.mkdirs()
            val staged = File(shareDir, fileName)
            staged.writeText(json)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", staged)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to stage the journal export for sharing")
            null
        }
    } ?: return false

    val send = Intent(Intent.ACTION_SEND).apply {
        type = JOURNAL_EXPORT_MIME
        putExtra(Intent.EXTRA_TITLE, fileName)
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
        Timber.tag(TAG).w(e, "No activity can receive the journal export")
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
suspend fun writeJournalDocument(
    outputStream: OutputStream,
    json: String,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): Boolean = withContext(ioDispatcher) {
    try {
        outputStream.use { it.write(json.toByteArray()) }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Timber.tag(TAG).e(e, "Failed to write the journal export document")
        false
    }
}
