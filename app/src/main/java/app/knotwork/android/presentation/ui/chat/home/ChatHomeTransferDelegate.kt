package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.models.ChatImportException
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.usecases.ChatExportDocument
import app.knotwork.android.domain.usecases.ExportChatUseCase
import app.knotwork.android.domain.usecases.SaveMessageToMemoryUseCase
import app.knotwork.android.domain.usecases.SaveToMemoryOutcome
import app.knotwork.design.components.chat.ChatContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Chat import / export / save-to-memory delegate of [ChatHomeViewModel].
 *
 * Groups the three isolated transfer actions that move chat content in or out of
 * the app: importing a chat from JSON, exporting the active session as a
 * share-sheet payload, and saving a single message's text into long-term
 * memory. None of them owns a render slice of [ChatHomeScreenState] — they read
 * the current messages / active session and communicate through one-shot
 * [SharedFlow] channels ([exportEvents] / [importErrorEvents] /
 * [memorySaveEvents]) consumed by the screen.
 *
 * Shares the ViewModel's [scope] and single [state] reducer (see
 * `docs/architecture.md` §1.2).
 *
 * @property scope The ViewModel's [androidx.lifecycle.viewModelScope].
 * @property state The ViewModel's single source-of-truth state flow.
 * @property chatRepository Imports a chat from JSON.
 * @property exportChatUseCase Serialises a session (active *or* archived) into
 *   the transferable JSON document.
 * @property saveMessageToMemoryUseCase Persists a message's text as a manual memory entry.
 * @property selectThread Seam into the ViewModel's thread switch — an imported
 *   chat becomes the active thread.
 */
class ChatHomeTransferDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatHomeScreenState>,
    private val chatRepository: ChatRepository,
    private val exportChatUseCase: ExportChatUseCase,
    private val saveMessageToMemoryUseCase: SaveMessageToMemoryUseCase,
    private val selectThread: (String) -> Unit,
) {

    private val _exportEvents: MutableSharedFlow<ChatExportDocument> = MutableSharedFlow(extraBufferCapacity = 1)
    private val _importErrorEvents: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 1)
    private val _memorySaveEvents: MutableSharedFlow<MemorySaveEvent> = MutableSharedFlow(extraBufferCapacity = 1)

    /**
     * One-shot stream raised when the user picks `Export chat` from either
     * overflow menu (the chat top bar, or an archive row). The screen consumes
     * each document via a `LaunchedEffect` and dispatches a system share-sheet
     * (`Intent.ACTION_SEND`).
     */
    val exportEvents: SharedFlow<ChatExportDocument> = _exportEvents.asSharedFlow()

    /**
     * One-shot stream of import-failure messages. Surfaced via the shared
     * `SnackbarHostState`; the carried string is a localised user-visible
     * description ("Could not read the selected file", JSON parse error, …).
     */
    val importErrorEvents: SharedFlow<String> = _importErrorEvents.asSharedFlow()

    /**
     * One-shot stream of "Save to memory" outcomes raised by the message
     * long-press action. The screen maps each event to a snackbar
     * ("Saved to memory" / failure copy).
     */
    val memorySaveEvents: SharedFlow<MemorySaveEvent> = _memorySaveEvents.asSharedFlow()

    /**
     * Extracts the plain-text body of a chat-home row by its catalog id
     * (`ChatHomeMessageRow.id`). Returns `null` for rows whose content
     * is not a plain-text bubble (Clarification cards, HITL confirmations,
     * tool-call tiles, inline errors) — those have their own affordances and
     * don't expose a copyable payload.
     *
     * Used by the long-press context menu (`onMessageContextAction`) to
     * resolve Copy / Rerun targets.
     *
     * Resolved against the rows the thread shows, including a just-sent message
     * that has not been stored yet: that bubble can stay pending for minutes behind
     * another run, and a long-press on it must not silently do nothing.
     */
    fun textForRow(rowId: String): String? {
        val row = state.value.visibleMessages.firstOrNull { it.id == rowId } ?: return null
        return when (val content = row.content) {
            is ChatContent.Text -> content.text
            is ChatContent.Markdown -> content.source
            else -> null
        }
    }

    /**
     * Persists the text of the message identified by [rowId] into long-term
     * memory as a manual entry, then raises a [MemorySaveEvent] so the screen
     * can confirm via snackbar. Rows without a copyable text payload, and
     * blank texts, are silently ignored (no event).
     *
     * Backs the long-press "Save to memory" context action.
     */
    fun saveMessageToMemory(rowId: String) {
        val text = textForRow(rowId) ?: return
        scope.launch {
            when (saveMessageToMemoryUseCase(text)) {
                is SaveToMemoryOutcome.Saved -> _memorySaveEvents.tryEmit(MemorySaveEvent.Saved)
                is SaveToMemoryOutcome.Failed -> _memorySaveEvents.tryEmit(MemorySaveEvent.Failed)
                SaveToMemoryOutcome.Skipped -> Unit
            }
        }
    }

    /**
     * Imports a chat from a JSON document. Surfaces a snackbar event on
     * failure via [importErrorEvents]; on success the newly created session
     * becomes the active thread (via the [selectThread] seam).
     *
     * @param json Raw JSON payload (export shape or bare message array).
     */
    fun importChatFromJson(json: String) {
        scope.launch {
            try {
                val newId = chatRepository.importChat(json)
                selectThread(newId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChatImportException) {
                // App-written reason; it never quotes the file.
                _importErrorEvents.tryEmit(e.message ?: IMPORT_GENERIC_FAILURE_MESSAGE)
            } catch (e: Throwable) {
                // Anything else carries text this app did not write (a parser or
                // storage message can quote the file), so it is not shown.
                _importErrorEvents.tryEmit(IMPORT_GENERIC_FAILURE_MESSAGE)
            }
        }
    }

    /**
     * Serialises the currently active session and emits the resulting
     * [ChatExportDocument] via [exportEvents]. The screen consumes the document
     * and dispatches a system share-sheet (`Intent.ACTION_SEND`) — kept in the
     * screen because Hilt-ViewModels stay free of `Context`.
     */
    fun exportCurrentSession() {
        exportSession(state.value.thread.currentSessionId)
    }

    /**
     * Serialises an arbitrary session by id and emits its [ChatExportDocument].
     *
     * Kept independent of the active thread so the archive surface can export a
     * chat it is not currently showing: archiving must not put a conversation
     * out of reach of the history-transfer path, and an archived session is
     * exactly the one a user is most likely to want to hand off elsewhere.
     *
     * @param sessionId Session to serialise. Blank ids are ignored.
     */
    fun exportSession(sessionId: String) {
        if (sessionId.isBlank()) return
        scope.launch {
            exportChatUseCase(sessionId)
                .onSuccess { document -> _exportEvents.tryEmit(document) }
                // Mirrors the previous silent-failure contract: an export that
                // cannot be serialised simply emits nothing.
                .onFailure { e -> Timber.w(e, "Chat export failed") }
        }
    }

    companion object {
        /**
         * The reason shown when an import fails for anything but a
         * [app.knotwork.android.domain.models.ChatImportException] — whose text this app
         * did not write. It completes "Could not import chat: …".
         */
        const val IMPORT_GENERIC_FAILURE_MESSAGE: String = "the file could not be read"
    }
}
