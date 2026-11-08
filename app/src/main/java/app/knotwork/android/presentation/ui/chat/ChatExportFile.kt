package app.knotwork.android.presentation.ui.chat

import android.content.Context
import app.knotwork.android.domain.constants.TransientCacheDirectory
import app.knotwork.android.domain.usecases.ChatExportDocument
import app.knotwork.android.domain.usecases.ExportChatUseCase
import app.knotwork.android.presentation.ui.common.ExportDocumentHandOff
import app.knotwork.android.presentation.ui.common.journalExportFileName
import java.util.Date

/** MIME type of an exported chat document — the share type, the `CreateDocument` type and the import filter. */
const val CHAT_EXPORT_MIME_JSON: String = "application/json"

/** Filename stem of a chat export, before the chat's name and the timestamp. */
const val CHAT_EXPORT_FILE_STEM: String = "chat"

/**
 * Longest part of a chat's name kept in its export's filename, in characters.
 * Enough to tell a folder of exports apart, short enough that the picker shows
 * the timestamp after it.
 */
const val CHAT_EXPORT_FILE_TITLE_LIMIT: Int = 40

/**
 * Names the file an exported chat becomes: `chat-<name>-yyyyMMdd-HHmmss.json`.
 *
 * The same name for both hand-offs — the file staged for the share sheet and the
 * one the document picker suggests — so a chat sent and a chat saved arrive as
 * the same file. The timestamp is the journals' ([journalExportFileName]):
 * device-local, sortable, and free of characters a file system refuses.
 *
 * The chat's name goes in through [chatExportFileTitle]; a chat with no name of
 * its own exports as `chat-yyyyMMdd-HHmmss.json`.
 *
 * @param sessionName The exported document's session name.
 * @param at Moment to stamp the name with; defaults to now.
 * @return The filename, extension included.
 */
fun chatExportFileName(sessionName: String, at: Date = Date()): String {
    val title = chatExportFileTitle(sessionName)
    val stem = if (title.isEmpty()) CHAT_EXPORT_FILE_STEM else "$CHAT_EXPORT_FILE_STEM-$title"
    return journalExportFileName(stem, at)
}

/**
 * Stages an exported chat as a file and offers it to the system share sheet — the
 * chat's own hand-off over the shared [ExportDocumentHandOff.share]: its MIME type, its
 * own staging directory ([TransientCacheDirectory.CHAT_EXPORT], so a chat share
 * never deletes a journal another app is reading) and the chat's name as the
 * subject a mail app fills in.
 *
 * A file, never `EXTRA_TEXT`: a chat over about 350 000 characters does not fit the
 * Binder buffer an intent's extras cross, and `startActivity` throws (measured on
 * API 34 and 36 emulators).
 *
 * @param context Context able to resolve the `FileProvider` and start the chooser.
 * @param document The rendered chat.
 * @param fileName Name the receiving app sees; from [chatExportFileName].
 * @param chooserTitle Localised chooser title.
 * @return `true` when the share sheet opened, `false` when the file could not be
 *   staged or no app can receive it.
 */
suspend fun shareChatExport(
    context: Context,
    document: ChatExportDocument,
    fileName: String,
    chooserTitle: String,
): Boolean = ExportDocumentHandOff.share(
    context = context,
    json = document.json,
    fileName = fileName,
    mimeType = CHAT_EXPORT_MIME_JSON,
    stagingDirectory = TransientCacheDirectory.CHAT_EXPORT,
    chooserTitle = chooserTitle,
    subject = document.sessionName,
)

/**
 * The part of a chat's name that goes into its export's filename.
 *
 * Letters and digits of any script are kept, so a chat named in Russian or Greek
 * keeps its name; every run of anything else — spaces, punctuation, emoji, and
 * the `/` and `:` some file systems refuse — becomes one `-`, never at either
 * end. At most [CHAT_EXPORT_FILE_TITLE_LIMIT] characters, counted by code point
 * so a cut never splits a surrogate pair. Combining marks stay with the letter
 * they follow. The export's fallback name ([ExportChatUseCase.FALLBACK_SESSION_NAME])
 * adds nothing to the stem and is left out.
 *
 * No regular expression: the name is written by the user or a model, and a
 * character walk is linear whatever it holds.
 *
 * @param sessionName The exported document's session name.
 * @return The filename-safe title, possibly empty.
 */
fun chatExportFileTitle(sessionName: String): String {
    if (sessionName == ExportChatUseCase.FALLBACK_SESSION_NAME) return ""
    val title = StringBuilder()
    var kept = 0
    var gap = false
    var index = 0
    while (index < sessionName.length) {
        val codePoint = sessionName.codePointAt(index)
        index += Character.charCount(codePoint)
        val mark = isCombiningMark(codePoint)
        when {
            Character.isLetterOrDigit(codePoint) || (mark && title.isNotEmpty() && !gap) -> {
                val separator = if (gap && title.isNotEmpty()) 1 else 0
                if (kept + separator + 1 > CHAT_EXPORT_FILE_TITLE_LIMIT) break
                if (separator == 1) title.append('-')
                title.appendCodePoint(codePoint)
                kept += separator + 1
                gap = false
            }
            else -> gap = true
        }
    }
    return title.toString()
}

/** Whether [codePoint] is a combining mark — an accent written after its letter. */
private fun isCombiningMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt(),
    -> true
    else -> false
}
