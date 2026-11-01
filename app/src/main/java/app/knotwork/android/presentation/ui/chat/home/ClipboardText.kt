package app.knotwork.android.presentation.ui.chat.home

import android.os.TransactionTooLargeException
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString

/**
 * Puts text on the clipboard and says whether it got there — a console payload or a
 * whole chat message.
 *
 * A clip reaches the system's clipboard service in one Binder transaction, and
 * `android.content.ClipboardManager.setPrimaryClip` rethrows a failed one as a
 * `RuntimeException` wrapping the `TransactionTooLargeException` (read in the
 * platform sources; not reproduced on a device). A String takes two bytes a
 * character in a Parcel and the transaction buffer is about 1 MB, while a node's
 * output can be a megabyte (the HTTP tool's default budget), and a chat message can
 * carry it. A copy that does not fit is reported to the caller instead of ending
 * the process. Any other failure is rethrown.
 *
 * No confirmation is shown on success: the app runs on Android 14 and later, where
 * the system shows its own preview of what was copied.
 *
 * @receiver the Compose clipboard of the screen that copies.
 * @param text the text to copy.
 * @return `true` when [text] is on the clipboard; `false` when it was too large
 *   for the transaction and nothing was copied.
 */
internal fun ClipboardManager.setClipboardText(text: String): Boolean = try {
    setText(AnnotatedString(text))
    true
} catch (e: RuntimeException) {
    if (e.cause !is TransactionTooLargeException) throw e
    false
}
