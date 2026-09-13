package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.models.ChatSession
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.text.toSingleLineTitle
import app.knotwork.android.domain.usecases.ArchiveChatUseCase
import app.knotwork.android.domain.usecases.UnarchiveChatUseCase
import app.knotwork.design.screens.chat.ChatHomeThreadRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Threads / sessions delegate of [ChatHomeViewModel].
 *
 * Owns the live session cache and the set of sessions with a background run,
 * projecting both into the `thread` slice of [ChatHomeScreenState] (title,
 * favorite, drawer rows). Hosts the drawer overlay and the session CRUD
 * surface (new / rename / favorite / delete), and exposes the session-metadata
 * refresh and the auto-rename helper that the ViewModel core composes into the
 * send / session-init / thread-switch paths.
 *
 * The thread *switch* itself stays in the ViewModel ([selectThread] seam): it
 * orchestrates run-lifecycle concerns (cancelling the generation collector,
 * re-subscribing the message stream, reattaching to a live run) that are core,
 * not thread-state. CRUD that ends in a switch routes through that seam.
 *
 * Pipeline-name resolution spans this delegate's session cache and the pipeline
 * delegate's default-binding, so the metadata refresh composes the injected
 * [pipelineNameRefresher] to keep a single atomic emission (see
 * `docs/architecture.md` §1.2).
 *
 * @property scope The ViewModel's [androidx.lifecycle.viewModelScope].
 * @property state The ViewModel's single source-of-truth state flow.
 * @property chatRepository Source of the session list; session CRUD.
 * @property archiveChatUseCase Moves a chat out of the drawer list without
 *   deleting anything it owns.
 * @property unarchiveChatUseCase Reverses [archiveChatUseCase]. Only the user
 *   ever calls it — background activity in an archived chat never restores it.
 * @property pipelineRunRepository Source of the active-run session-id set (drawer badges).
 * @property selectThread Seam into the ViewModel's thread switch.
 * @property clearDraft Seam into the ViewModel's per-session draft store; called
 *   on session deletion so the removed chat's unsent text does not linger.
 * @property pipelineNameRefresher Recomputes the pipeline subtitle for a snapshot
 *   (supplied by the pipeline-binding delegate) so it rides the metadata refresh.
 * @property onSessionsChanged Invoked after each sessions-flow emission so the
 *   pipeline-binding delegate can rebind a chat whose pipeline was deleted.
 */
class ChatHomeThreadsDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatHomeScreenState>,
    private val chatRepository: ChatRepository,
    private val archiveChatUseCase: ArchiveChatUseCase,
    private val unarchiveChatUseCase: UnarchiveChatUseCase,
    private val pipelineRunRepository: PipelineRunRepository,
    private val selectThread: (String) -> Unit,
    private val clearDraft: (String) -> Unit,
    private val pipelineNameRefresher: (ChatHomeScreenState) -> ChatHomeScreenState,
    private val onSessionsChanged: suspend () -> Unit,
) {

    private var sessions: List<ChatSession> = emptyList()

    /**
     * The in-flight persistence write of the most recently created chat, or `null`
     * once it has settled. [createNewSessionWithPipeline] switches the active thread
     * synchronously but persists the row asynchronously; [deleteCurrentSession] joins
     * this job first so deleting a just-created chat removes its (now-persisted) row
     * instead of racing the write and leaving an orphan that the late insert re-adds.
     */
    private var pendingNewSessionSave: Job? = null

    /**
     * Session ids that currently own a pipeline run in a non-terminal status.
     * Fed by [observeActiveRuns] and projected into the drawer thread rows as
     * the in-progress badge ([buildThreadRows]).
     */
    private var activeRunSessionIds: Set<String> = emptySet()

    /** Latest session cache snapshot — read by the ViewModel core and the other delegates' providers. */
    fun sessionsSnapshot(): List<ChatSession> = sessions

    /** Launches the session-list and active-run observers. Called once from the ViewModel `init`. */
    fun startObservers() {
        observeSessions()
        observeActiveRuns()
    }

    /** Opens the drawer overlay. */
    fun openDrawer() {
        state.update { it.copy(visual = ChatHomeUiState.DrawerOpen) }
    }

    /** Closes the drawer overlay, settling on the right state for the current message list. */
    fun closeDrawer() {
        state.update { current ->
            if (current.visual is ChatHomeUiState.DrawerOpen) {
                current.copy(visual = current.restingVisual())
            } else {
                current
            }
        }
    }

    /**
     * Pure transformer: recomputes thread title + favorite, the drawer rows,
     * and (via [pipelineNameRefresher]) the pipeline subtitle for [s] from the
     * latest session/pipeline caches. Composed into the caller's single
     * `state.update` block so one upstream emission (sessions flow, session
     * init, thread switch) produces exactly one state emission — collectors
     * never observe a frame with a fresh title but stale rows.
     *
     * Title/favorite are left untouched when the active session id is non-blank
     * but missing from the cache (mid-deletion window).
     *
     * @param s The snapshot to refresh.
     * @return [s] with thread metadata and pipeline subtitle recomputed.
     */
    fun sessionMetadataRefreshed(s: ChatHomeScreenState): ChatHomeScreenState {
        val currentId = s.thread.currentSessionId
        val session = sessions.firstOrNull { it.id == currentId }
        val refreshedThread = when {
            session != null -> s.thread.copy(
                title = session.name.ifBlank { ChatHomeThreadState.DEFAULT_TITLE },
                favorite = session.isStarred,
                archived = session.isArchived,
            )
            currentId.isBlank() -> s.thread.copy(
                title = ChatHomeThreadState.DEFAULT_TITLE,
                favorite = false,
                archived = false,
            )
            else -> s.thread
        }
        return pipelineNameRefresher(
            s.copy(
                thread = refreshedThread.copy(
                    rows = buildThreadRows(currentId),
                    archivedCount = sessions.count { it.isArchived },
                ),
            ),
        )
    }

    /**
     * Auto-renames a newly-created chat to the first user message (truncated to
     * [AUTO_RENAME_CHAR_LIMIT] characters). Invoked by the
     * send path so the drawer entry reflects the user's framing.
     *
     * The session is looked up **after** the new-chat save has landed, not from the
     * in-memory list at send time. A first message typed straight into a new chat
     * could otherwise reach here before the list observed the new row, and the
     * rename was silently skipped — the chat kept its "New Chat" title for good.
     * The name is written with a targeted rename rather than a whole-row save, so a
     * concurrent write to the same row (the favourite flag, the timestamp the first
     * message bumps) is not overwritten with the stale copy.
     *
     * @param sessionId The session being renamed.
     * @param prompt The user's first message text.
     */
    fun autoRenameIfDefault(sessionId: String, prompt: String) {
        if (prompt.isBlank()) return
        // Collapse whitespace (including newlines) so a multi-line first message
        // still yields a clean, informative single-line title.
        val truncated = prompt.toSingleLineTitle(AUTO_RENAME_CHAR_LIMIT, AUTO_RENAME_SUFFIX)
        val saveInFlight = pendingNewSessionSave
        scope.launch {
            saveInFlight?.join()
            val currentName = sessions.firstOrNull { it.id == sessionId }?.name
                ?: chatRepository.getSessionById(sessionId)?.name
                ?: return@launch
            if (currentName != DEFAULT_NEW_CHAT_NAME) return@launch
            chatRepository.renameSession(sessionId, truncated)
        }
    }

    /**
     * Creates a new chat session bound to [pipelineId] and switches to it.
     *
     * @param pipelineId Pipeline identifier to bind, or `null` to inherit the
     *   application-wide default pipeline.
     */
    fun createNewSessionWithPipeline(pipelineId: String?) {
        val newId = UUID.randomUUID().toString()
        // Persist the new session off the main thread…
        pendingNewSessionSave = scope.launch {
            chatRepository.saveSession(
                ChatSession(
                    id = newId,
                    name = DEFAULT_NEW_CHAT_NAME,
                    updatedAt = System.currentTimeMillis(),
                    pipelineId = pipelineId,
                ),
            )
        }
        // …but switch the active thread *synchronously*. Deferring the switch
        // behind the suspending `saveSession` left a window where the freshly
        // created chat was on screen while `currentSessionId` still pointed at
        // the previously-active chat — so an immediately-following overflow
        // "delete" removed the wrong chat. Flipping the id before the suspension
        // point closes that race; the persisted row lands a moment later (and
        // [deleteCurrentSession] joins it so a quick create→delete is safe).
        selectThread(newId)
    }

    /**
     * Renames the chat session identified by [threadId]. Trims the input and
     * short-circuits when the trimmed value is blank.
     */
    fun renameSession(threadId: String, newName: String) {
        val trimmed = newName.trim()
        if (threadId.isBlank() || trimmed.isEmpty()) return
        scope.launch {
            chatRepository.renameSession(threadId, trimmed)
        }
    }

    /** Flips the session-level favorite flag on the currently active chat. No-op when no session is loaded. */
    fun toggleFavoriteCurrent() {
        val sessionId = state.value.thread.currentSessionId
        if (sessionId.isBlank()) return
        val current = sessions.firstOrNull { it.id == sessionId }?.isStarred ?: false
        scope.launch {
            chatRepository.setSessionFavorite(sessionId, !current)
        }
    }

    /**
     * Deletes the currently active session and auto-selects the next available
     * thread; when no other session exists, creates a fresh unbound chat so the
     * user is never stranded on a non-existent session id.
     */
    fun deleteCurrentSession() {
        val sessionId = state.value.thread.currentSessionId
        if (sessionId.isBlank()) return
        scope.launch {
            // Let any in-flight create-write settle first: deleting a chat created
            // a moment ago must remove its persisted row, not race the still-pending
            // insert and leave an orphan the insert re-adds.
            pendingNewSessionSave?.join()
            chatRepository.deleteSession(sessionId)
            selectNextAfterLeaving(sessionId)
            // Discard the deleted chat's unsent draft AFTER the switch: the
            // switch stashes the outgoing (now-deleted) session's composer text
            // back into the draft map, so clearing before it would be undone,
            // leaving an orphan entry keyed by a session that no longer exists.
            clearDraft(sessionId)
        }
    }

    /** Opens the overflow menu of the drawer row [threadId]. */
    fun openThreadMenu(threadId: String) {
        state.update { it.copy(thread = it.thread.copy(openMenuId = threadId)) }
    }

    /** Closes any open drawer-row overflow menu. */
    fun dismissThreadMenu() {
        state.update { it.copy(thread = it.thread.copy(openMenuId = null)) }
    }

    /**
     * Archives [threadId] and, when it is the chat currently on screen, switches
     * away from it.
     *
     * The switch mirrors [deleteCurrentSession]: the user asked for this chat to
     * leave the list, so leaving them staring at it would contradict the action.
     * Nothing is deleted, so the chat stays one tap away in the archive.
     *
     * @param threadId session to archive.
     */
    fun archiveThread(threadId: String) {
        if (threadId.isBlank()) return
        val wasActive = state.value.thread.currentSessionId == threadId
        scope.launch {
            archiveChatUseCase(threadId)
                // Only switch away once the write actually landed: moving the
                // user off a chat that is still in the list would tell them an
                // action succeeded when it did not.
                .onSuccess { if (wasActive) selectNextAfterLeaving(threadId) }
                .onFailure { e -> Timber.w(e, "Archiving chat %s failed", threadId) }
        }
    }

    /** Restores [threadId] out of the archive. Does not switch the active thread. */
    fun unarchiveThread(threadId: String) {
        if (threadId.isBlank()) return
        scope.launch {
            unarchiveChatUseCase(threadId)
                .onFailure { e -> Timber.w(e, "Restoring chat %s failed", threadId) }
        }
    }

    /**
     * Deletes [threadId] outright. Unlike [deleteCurrentSession] this targets an
     * arbitrary row (the drawer overflow), so it only re-selects when the
     * deleted chat was the one on screen.
     */
    fun deleteThread(threadId: String) {
        if (threadId.isBlank()) return
        val wasActive = state.value.thread.currentSessionId == threadId
        scope.launch {
            pendingNewSessionSave?.join()
            chatRepository.deleteSession(threadId)
            if (wasActive) {
                selectNextAfterLeaving(threadId)
            }
            clearDraft(threadId)
        }
    }

    /**
     * Moves off [leavingId] onto the next *non-archived* chat, creating a fresh
     * one when none is left, so the user is never stranded on a session that is
     * gone (deleted) or no longer in the list (archived).
     */
    private fun selectNextAfterLeaving(leavingId: String) {
        val remaining = sessions.filter { it.id != leavingId && !it.isArchived }
        if (remaining.isNotEmpty()) {
            selectThread(remaining.first().id)
        } else {
            createNewSessionWithPipeline(pipelineId = null)
        }
    }

    /**
     * Observes the session list. Keeps a cache for lookups and refreshes the
     * title + subtitle + rows.
     *
     * Collects **including** archived sessions: the cache is what resolves the
     * *active* chat's title, star and archive flag, and an archived chat can be
     * open (read-only). Filtering here would blank the title the moment the user
     * archived the chat they were reading. The archived rows are filtered out of
     * the drawer list itself in [buildThreadRows], where the exclusion belongs.
     */
    private fun observeSessions() {
        scope.launch {
            chatRepository.getSessionsFlow(includeArchived = true).collect { current ->
                sessions = current
                state.update { sessionMetadataRefreshed(it) }
                onSessionsChanged()
            }
        }
    }

    /**
     * Mirrors the set of sessions owning a non-terminal run into
     * [activeRunSessionIds] and re-projects the drawer rows, so threads with a
     * run still working in the background render the in-progress badge. Only the
     * rows are rebuilt — title / pipeline-name resolution is untouched because a
     * badge flip cannot change either.
     */
    private fun observeActiveRuns() {
        scope.launch {
            pipelineRunRepository.observeActiveRunSessionIds().collect { sessionIds ->
                activeRunSessionIds = sessionIds
                state.update { current ->
                    current.copy(
                        thread = current.thread.copy(rows = buildThreadRows(current.thread.currentSessionId)),
                    )
                }
            }
        }
    }

    /**
     * Projects the live session cache into drawer thread rows. Favorited
     * sessions sort to the top of the drawer; the rest follow the repository's
     * `updatedAt DESC` ordering.
     *
     * Archived sessions are excluded — the drawer is the "current work" list and
     * archiving is precisely the act of taking a chat out of it. They remain in
     * [sessions] so the active chat's metadata still resolves when the user is
     * reading an archived thread.
     *
     * @param activeId id of the active session used for the `selected`/`active` flags.
     */
    private fun buildThreadRows(activeId: String): List<ChatHomeThreadRow> {
        val sorted = sessions.filterNot { it.isArchived }.sortedWith(
            compareByDescending<ChatSession> { it.isStarred }
                .thenByDescending { it.updatedAt },
        )
        return sorted.map { session ->
            ChatHomeThreadRow(
                id = session.id,
                title = session.name.ifBlank { ChatHomeThreadState.DEFAULT_TITLE },
                subtitle = formatThreadSubtitle(session.updatedAt),
                selected = session.id == activeId,
                active = session.id == activeId,
                starred = session.isStarred,
                running = session.id in activeRunSessionIds,
            )
        }
    }

    /** Formats a session `updatedAt` timestamp as the drawer thread subtitle (e.g. `Mon 14:32`). */
    private fun formatThreadSubtitle(updatedAt: Long): String =
        SimpleDateFormat(THREAD_SUBTITLE_PATTERN, Locale.getDefault()).format(Date(updatedAt))

    companion object {
        /** Default name of a freshly-created chat session — must match legacy `ChatViewModel` for compatibility. */
        const val DEFAULT_NEW_CHAT_NAME: String = "New Chat"

        /** Maximum characters of the first user message used as the auto-generated session name. */
        const val AUTO_RENAME_CHAR_LIMIT: Int = 40

        /** Suffix appended to a truncated auto-rename name. */
        const val AUTO_RENAME_SUFFIX: String = "..."

        /** Pattern used for the drawer thread subtitle (e.g. `Mon 14:32`). */
        private const val THREAD_SUBTITLE_PATTERN: String = "EEE HH:mm"
    }
}
