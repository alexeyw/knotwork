package app.knotwork.android.presentation.ui.common

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
