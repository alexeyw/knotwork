package app.knotwork.android.presentation.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.knotwork.android.R
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException

/**
 * Wires a [ChatExportDelegate] to the platform: the share sheet for
 * [ChatExportEvent.Share], the Storage Access Framework document picker for
 * [ChatExportEvent.PickDestination], and a snackbar for every other outcome.
 *
 * Written once for the chat screen and the archive. Two copies of the launcher,
 * the collector and the wording are how two surfaces end up handing out the same
 * chat differently — or telling the user different things about it.
 *
 * The chat is shared as a staged **file** ([shareChatExport]), never as
 * `EXTRA_TEXT`: a chat over about 350 000 characters does not fit the Binder
 * buffer an intent's extras cross, and `startActivity` throws (measured on API 34
 * and 36 emulators).
 *
 * The picker is opened with a concrete MIME type ([CHAT_EXPORT_MIME_JSON]) rather
 * than the deprecated no-argument `CreateDocument()`: a wildcard type makes the
 * picker drop the `.json` extension from the name it was handed.
 *
 * @param delegate The ViewModel's export half.
 * @param snackbarHostState Host every outcome is reported on — rendered by the
 *   calling screen.
 */
@Composable
fun ChatExportEffects(delegate: ChatExportDelegate, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val chooserTitle = stringResource(R.string.chat_export_chooser_title)
    val shareFailed = stringResource(R.string.journal_export_share_failed)
    val saved = stringResource(R.string.chat_export_saved)
    val saveFailed = stringResource(R.string.chat_export_save_failed)
    val renderFailed = stringResource(R.string.chat_export_render_failed)

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(CHAT_EXPORT_MIME_JSON),
    ) { uri ->
        // A null uri is the user backing out of the picker. Not a failure, and
        // deliberately not reported as one.
        if (uri == null) {
            delegate.cancelSave()
            return@rememberLauncherForActivityResult
        }
        val stream = try {
            context.contentResolver.openOutputStream(uri)
        } catch (e: SecurityException) {
            // A provider that offered the document and then refused to open it.
            Timber.w(e, "Could not open the picked chat-export document")
            null
        } catch (e: IOException) {
            // `openOutputStream` declares FileNotFoundException; a provider that
            // created the document and then lost it must not crash the screen.
            Timber.w(e, "The picked chat-export document could not be opened")
            null
        }
        if (stream != null) {
            delegate.saveTo(stream)
        } else {
            delegate.cancelSave()
            coroutineScope.launch { snackbarHostState.showSnackbar(saveFailed) }
        }
    }

    LaunchedEffect(delegate) {
        delegate.events.collect { event ->
            val message = when (event) {
                is ChatExportEvent.Share -> {
                    val shared = shareChatExport(
                        context = context,
                        document = event.document,
                        fileName = event.fileName,
                        chooserTitle = chooserTitle,
                    )
                    // A share sheet that opened needs no confirmation — the user is
                    // looking at it. Only its absence has to be said out loud.
                    if (shared) null else shareFailed
                }
                is ChatExportEvent.PickDestination -> {
                    saveLauncher.launch(event.fileName)
                    null
                }
                ChatExportEvent.Saved -> saved
                ChatExportEvent.SaveFailed -> saveFailed
                ChatExportEvent.RenderFailed -> renderFailed
            }
            // Launched beside the collector, not inside it: a snackbar stays up for
            // seconds, and the next export's picker must not wait behind it.
            if (message != null) launch { snackbarHostState.showSnackbar(message) }
        }
    }
}
