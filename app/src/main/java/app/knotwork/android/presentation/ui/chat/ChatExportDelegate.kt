package app.knotwork.android.presentation.ui.chat

import app.knotwork.android.domain.usecases.ChatExportDocument
import app.knotwork.android.domain.usecases.ExportChatUseCase
import app.knotwork.android.presentation.ui.common.ExportDocumentHandOff
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.io.OutputStream
import java.util.Date

/**
 * One-shot outcome of a chat export, consumed by the screen that offered it.
 *
 * Every branch is reported, the successful save included: a file lands somewhere
 * the user is not looking, and an action that gives no sign it happened reads as
 * a dead control.
 */
sealed interface ChatExportEvent {

    /**
     * The chat is rendered; the screen stages it and opens the share sheet.
     *
     * @property document The rendered chat.
     * @property fileName Name the receiving app sees.
     */
    data class Share(val document: ChatExportDocument, val fileName: String) : ChatExportEvent

    /**
     * The chat is rendered and held; the screen opens the document picker with
     * this suggested name and hands back the stream the user picks
     * ([ChatExportDelegate.saveTo]).
     *
     * @property fileName Name the picker suggests.
     */
    data class PickDestination(val fileName: String) : ChatExportEvent

    /** The chat was written to the file the user picked. */
    data object Saved : ChatExportEvent

    /** The picked file could not be written, or the rendered chat was no longer held. */
    data object SaveFailed : ChatExportEvent

    /** The chat could not be read, so there is nothing to share or save. */
    data object RenderFailed : ChatExportEvent
}

/**
 * The export half of a chat surface's ViewModel: renders a chat on request and
 * hands it to the share sheet or to a file the user picks.
 *
 * Shared by the chat screen (the open chat) and the archive (any archived chat):
 * both hand out the same document, so both hand it out the same way — one
 * renderer ([ExportChatUseCase]), one name ([chatExportFileName]) and one encoder
 * ([ExportDocumentHandOff.bytes], behind both the share staging and the write).
 * A file saved and a file shared from the same render are therefore the same
 * bytes; `ChatExportHandOffTest` holds that.
 *
 * **The chat is rendered before the picker opens**, and the very document is held
 * until the picked stream arrives. The suggested name comes from the document
 * itself, and what is written is what was rendered at the tap. A configuration
 * change while the picker is open keeps the ViewModel, so the document survives
 * it; a process death does not, and the save then reports [ChatExportEvent.SaveFailed]
 * instead of writing nothing in silence.
 *
 * **No network anywhere on this path** — the chat is read from the local database
 * and handed to the system share sheet or a file the user picked, on an explicit
 * action (`ChatExportNoNetworkKonsistTest`).
 *
 * @property scope The ViewModel's [androidx.lifecycle.viewModelScope].
 * @property exportChat Renders a chat by its session id.
 * @property ioDispatcher Dispatcher carrying the write; injected so a test can
 *   drive it on the test scheduler.
 * @property clock Moment a filename is stamped with; injected so a test can pin it.
 */
class ChatExportDelegate(
    private val scope: CoroutineScope,
    private val exportChat: ExportChatUseCase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Date = ::Date,
) {

    private val _events = MutableSharedFlow<ChatExportEvent>(extraBufferCapacity = 1)

    /** One-shot export outcomes; the screen turns each into a share sheet, a picker or a snackbar. */
    val events: SharedFlow<ChatExportEvent> = _events.asSharedFlow()

    /** The chat rendered for the picker that is open, until its stream arrives. */
    private var pendingSave: ChatExportDocument? = null

    /**
     * Renders the chat and asks the screen to hand it to the share sheet.
     *
     * @param sessionId Chat to export. A blank id — a chat not yet stored — does nothing.
     */
    fun share(sessionId: String) {
        if (sessionId.isBlank()) return
        scope.launch {
            val document = renderOrNull(sessionId)
            _events.tryEmit(
                if (document == null) {
                    ChatExportEvent.RenderFailed
                } else {
                    ChatExportEvent.Share(document, chatExportFileName(document.sessionName, clock()))
                },
            )
        }
    }

    /**
     * Renders the chat, holds it, and asks the screen to open the document picker
     * with the name the file should get.
     *
     * @param sessionId Chat to save. A blank id — a chat not yet stored — does nothing.
     */
    fun save(sessionId: String) {
        if (sessionId.isBlank()) return
        scope.launch {
            val document = renderOrNull(sessionId)
            if (document == null) {
                _events.tryEmit(ChatExportEvent.RenderFailed)
                return@launch
            }
            pendingSave = document
            _events.tryEmit(ChatExportEvent.PickDestination(chatExportFileName(document.sessionName, clock())))
        }
    }

    /**
     * Writes the held chat into [outputStream] — the document the user picked — and
     * reports the outcome. The stream is closed on every path: the picker has
     * already created the file, and a stranded stream would leave it empty with
     * nothing said.
     *
     * @param outputStream Destination document stream; owned here.
     */
    fun saveTo(outputStream: OutputStream) {
        val document = pendingSave
        pendingSave = null
        scope.launch {
            val saved = if (document == null) {
                // Nothing left to write: the render lived in memory and did not
                // survive a process death while the picker was open. Saying so
                // lets the user try again rather than find an empty file later.
                closeQuietly(outputStream)
                false
            } else {
                ExportDocumentHandOff.write(outputStream, document.json, ioDispatcher)
            }
            _events.tryEmit(if (saved) ChatExportEvent.Saved else ChatExportEvent.SaveFailed)
        }
    }

    /** Drops the held chat: the user backed out of the picker, or the picked file could not be opened. */
    fun cancelSave() {
        pendingSave = null
    }

    /**
     * Renders a chat, logging a failure and answering `null` for it. The screen
     * reports the `null` — an export that silently does nothing is the failure this
     * path exists to avoid.
     */
    private suspend fun renderOrNull(sessionId: String): ChatExportDocument? = exportChat(sessionId)
        .onFailure { e -> Timber.w(e, "Chat export could not be rendered") }
        .getOrNull()

    /** Closes [outputStream] off the main thread; a failure to close changes nothing the user can act on. */
    private suspend fun closeQuietly(outputStream: OutputStream) {
        withContext(ioDispatcher) {
            try {
                outputStream.close()
            } catch (e: IOException) {
                Timber.w(e, "The picked chat-export document could not be closed")
            }
        }
    }
}
