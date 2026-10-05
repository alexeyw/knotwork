package app.knotwork.android.presentation.ui.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.knotwork.android.R
import timber.log.Timber
import java.io.IOException

/**
 * One outcome message waiting to be shown, tagged with the emission that produced
 * it.
 *
 * The tag is the point: it makes two identical consecutive outcomes two distinct
 * values, so the snackbar effect restarts for the second one instead of treating
 * it as the same message it is already showing.
 *
 * @property sequence Monotonic emission counter.
 * @property text The message to show.
 */
private data class PendingExportMessage(val sequence: Int, val text: UiText)

/**
 * What the share chooser and the outcome messages call an exported document.
 *
 * @property chooserTitle The share chooser's title.
 * @property shareFailed No app could receive the file.
 * @property saveFailed The picked file could not be written.
 * @property renderFailed Nothing could be produced to share or save.
 * @property saved The plural saying how many entries were saved.
 */
data class JournalExportCopy(
    @StringRes val chooserTitle: Int,
    @StringRes val shareFailed: Int,
    @StringRes val saveFailed: Int,
    @StringRes val renderFailed: Int,
    @PluralsRes val saved: Int,
) {
    /** The two journals' copy, and the defaults. */
    companion object {
        /** The trigger and external-request journals. */
        val Journal: JournalExportCopy = JournalExportCopy(
            chooserTitle = R.string.journal_export_chooser_title,
            shareFailed = R.string.journal_export_share_failed,
            saveFailed = R.string.journal_export_save_failed,
            renderFailed = R.string.journal_export_render_failed,
            saved = R.plurals.journal_export_saved,
        )

        /** A run's trace. */
        val RunTrace: JournalExportCopy = JournalExportCopy(
            chooserTitle = R.string.run_trace_export_chooser_title,
            shareFailed = R.string.journal_export_share_failed,
            saveFailed = R.string.run_trace_export_save_failed,
            renderFailed = R.string.run_trace_export_render_failed,
            saved = R.plurals.run_trace_export_saved,
        )
    }
}

/**
 * The two journal-export actions a screen hands to its top bar.
 *
 * @property onShare Renders the journal and opens the system share sheet.
 * @property onSave Opens the document picker; the journal is written into
 *   whatever the user picks.
 */
class JournalExportActionHandlers(val onShare: () -> Unit, val onSave: () -> Unit)

/**
 * Wires a [JournalExportDelegate] to the platform: a Storage Access Framework
 * document picker for "save", the system share sheet for "share", and a snackbar
 * for every outcome of either.
 *
 * Written once and used by both journal screens. The alternative — each screen
 * repeating the launcher, the collector and the snackbar wording — is how two
 * screens end up telling the user different things about the same operation, and
 * how one of them ends up telling the user nothing at all.
 *
 * The picker is opened with a concrete MIME type ([JOURNAL_EXPORT_MIME]) rather
 * than the deprecated no-argument `CreateDocument()`: a wildcard type makes the
 * picker drop the `.json` extension from the name it was handed.
 *
 * @param delegate The ViewModel's export half.
 * @param snackbarHostState Host every outcome is reported on.
 * @param copy What the chooser and the outcome messages call the document.
 * @return The handlers to pass into the screen's top-bar actions.
 */
@Composable
fun rememberJournalExportHandlers(
    delegate: JournalExportDelegate,
    snackbarHostState: SnackbarHostState,
    copy: JournalExportCopy = JournalExportCopy.Journal,
): JournalExportActionHandlers {
    val context = LocalContext.current
    val chooserTitle = stringResource(copy.chooserTitle)

    // Outcomes are held as UiText and resolved in composition below, rather than
    // read off `Context` inside the collector: the `LocalContextGetResourceValueCall`
    // lint rule forbids that, and the count-bearing message needs a plural anyway.
    //
    // Each is tagged with a sequence number, and that number — not the text — is
    // what drives the snackbar. Keying on the text loses the second of two
    // identical outcomes in a row (two failed saves, say): the key would not
    // change, the effect would not restart, and the user would be told once about
    // something that happened twice. Which is the exact silence this surface
    // exists to avoid.
    var pending by remember { mutableStateOf<PendingExportMessage?>(null) }
    var emitted by remember { mutableIntStateOf(0) }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(JOURNAL_EXPORT_MIME),
    ) { uri ->
        // A null uri is the user backing out of the picker. Not a failure, and
        // deliberately not reported as one.
        uri ?: return@rememberLauncherForActivityResult
        val stream = try {
            context.contentResolver.openOutputStream(uri)
        } catch (e: SecurityException) {
            // A provider that offered the document and then refused to open it.
            Timber.w(e, "Could not open the picked journal-export document")
            null
        } catch (e: IOException) {
            // `openOutputStream` declares FileNotFoundException; a provider that
            // created the document and then lost it must not crash the screen.
            Timber.w(e, "The picked journal-export document could not be opened")
            null
        }
        if (stream != null) {
            delegate.saveTo(stream)
        } else {
            pending = PendingExportMessage(++emitted, UiText(copy.saveFailed))
        }
    }

    LaunchedEffect(delegate) {
        delegate.events.collect { event ->
            val text = when (event) {
                is JournalExportEvent.Share -> {
                    val shared = shareJournalDocument(
                        context = context,
                        json = event.document.json,
                        fileName = event.fileName,
                        chooserTitle = chooserTitle,
                    )
                    // A share sheet that opened needs no confirmation — the user is
                    // looking at it. Only its absence has to be said out loud.
                    if (shared) null else UiText(copy.shareFailed)
                }

                is JournalExportEvent.Saved -> UiText.Plural(
                    id = copy.saved,
                    quantity = event.entryCount,
                    args = listOf(event.entryCount),
                )

                JournalExportEvent.SaveFailed -> UiText(copy.saveFailed)

                JournalExportEvent.RenderFailed -> UiText(copy.renderFailed)
            }
            if (text != null) pending = PendingExportMessage(++emitted, text)
        }
    }

    val message = pending?.text?.asString()
    LaunchedEffect(pending?.sequence) {
        snackbarHostState.showSnackbar(message ?: return@LaunchedEffect)
    }

    return remember(delegate, saveLauncher) {
        JournalExportActionHandlers(
            onShare = delegate::share,
            onSave = { saveLauncher.launch(delegate.newFileName()) },
        )
    }
}
