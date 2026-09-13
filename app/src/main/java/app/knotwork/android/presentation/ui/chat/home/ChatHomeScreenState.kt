package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.ClarificationRequest
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.models.MessageAttachment
import app.knotwork.android.domain.models.PipelineSamplePrompt
import app.knotwork.android.domain.models.RunNoticeCause
import app.knotwork.design.components.chat.ComposerVoiceNotice
import app.knotwork.design.screens.chat.ChatHomeConsoleState
import app.knotwork.design.screens.chat.ChatHomeMessageRow
import app.knotwork.design.screens.chat.ChatHomeThreadRow

/**
 * Single immutable snapshot of everything the chat-home surface renders.
 *
 * Replaces the former constellation of ~25 independent `StateFlow`s on
 * [ChatHomeViewModel] with one source of truth: the ViewModel owns a single
 * `MutableStateFlow<ChatHomeScreenState>` and every mutation goes through
 * `update { it.copy(...) }`, while the screen performs a single
 * `collectAsStateWithLifecycle` and hands the immutable sub-structures down
 * the tree as-is (each sub-structure is a stable `data class`, so child
 * composables skip recomposition when their slice did not change).
 *
 * One-shot events (export payloads, snackbars, pipeline-fallback signals)
 * intentionally stay outside this snapshot — they are delivered through the
 * ViewModel's `SharedFlow` channels because replaying them on
 * re-subscription would duplicate side effects.
 *
 * @property visual sealed visual axis of the surface (Loading / Empty /
 *   Idle / Generating / HitlConfirm / Clarification / Error / DrawerOpen).
 *   Kept as the pre-existing [ChatHomeUiState] hierarchy — the debug state
 *   picker and the catalog mapping both key off it.
 * @property composer composer input slice ([ChatHomeComposerState]).
 * @property console console-pane render state. Reuses the catalog
 *   [ChatHomeConsoleState] verbatim so the screen-side mapping can pass it
 *   through to `ChatHomeViewState` without re-projection.
 * @property consoleClearConfirmRequested whether the destructive "Clear
 *   console for this session?" confirmation dialog is up. Lives next to —
 *   not inside — [console] because the dialog is screen chrome, not part of
 *   the catalog console pane's render contract.
 * @property pending orchestrator pause snapshots ([ChatHomePendingState]).
 * @property thread active-thread metadata + drawer rows ([ChatHomeThreadState]).
 * @property model local-model picker slice ([ChatHomeModelState]).
 * @property tokens token-meter slice ([ChatHomeTokenState]).
 * @property messages chat rows projected from the repository display flow.
 * @property pipelineName display name of the pipeline bound to the active
 *   chat (explicit binding, else the user-marked default), or `null` when
 *   neither resolves — the TopAppBar subtitle must not advertise a pipeline
 *   that execution would never pick.
 * @property availablePipelines pipeline summaries surfaced by the
 *   new-thread pipeline picker.
 * @property activeSamplePrompts starter ("quick action") prompts declared by
 *   the pipeline bound to the active chat, rendered on the empty state. Empty
 *   when the pipeline declares none — the mapping then falls back to a generic
 *   pipeline-agnostic set.
 * @property sourceChooserVisible whether the image-source chooser sheet
 *   (Photo library / Camera) is currently shown.
 * @property runNotice the advisory about the run currently in flight — that it
 *   has crossed the soft threshold on one of its limits, or that the
 *   stuck-detector thinks it is going in circles — or `null` when there is
 *   nothing to say. Deliberately **live-only** and deliberately
 *   *not* part of [visual]: the run is still going, so it is not a state of the
 *   screen but a remark about the work in it, and it must not displace the HITL
 *   or generating visuals it coexists with. Cleared the moment the run reaches
 *   a terminal state or the user sends again.
 * @property imageViewer target of the full-screen image viewer, or `null`
 *   when the viewer is closed.
 * @property sendingUserTurn the message the user has just sent, shown as a
 *   pending bubble until its stored row reaches [messages], or `null` when
 *   nothing is in that window. Kept apart from [messages] because the display
 *   flow overwrites that list on every emission.
 */
data class ChatHomeScreenState(
    val visual: ChatHomeUiState = ChatHomeUiState.Loading,
    val composer: ChatHomeComposerState = ChatHomeComposerState(),
    val console: ChatHomeConsoleState = ChatHomeConsoleState(),
    val consoleClearConfirmRequested: Boolean = false,
    val pending: ChatHomePendingState = ChatHomePendingState(),
    val thread: ChatHomeThreadState = ChatHomeThreadState(),
    val model: ChatHomeModelState = ChatHomeModelState(),
    val tokens: ChatHomeTokenState = ChatHomeTokenState(),
    val messages: List<ChatHomeMessageRow> = emptyList(),
    val pipelineName: String? = null,
    val availablePipelines: List<PipelineSummary> = emptyList(),
    val activeSamplePrompts: List<PipelineSamplePrompt> = emptyList(),
    val sourceChooserVisible: Boolean = false,
    val runNotice: RunNoticeCause? = null,
    val imageViewer: ImageViewerTarget? = null,
    val sendingUserTurn: SendingUserTurn? = null,
)

/**
 * A user message that has been sent but whose stored row has not reached the
 * display flow yet.
 *
 * The stored row is written by the task queue, not by the screen: after a hop to
 * the IO dispatcher, behind any run already executing in the serial queue, and
 * then back through a Room re-query. Until then the thread would show only the
 * generating indicator — in a new chat, with no sign of what was asked. The
 * bubble stands in for the row over exactly that window.
 *
 * @property row the bubble, marked [app.knotwork.design.components.chat.ChatMessageStatus.Pending].
 * @property sentAtMillis wall-clock time of the send. The queue stamps the stored
 *   row when it writes it, which is never earlier, so the first user row at or
 *   after this time is the one this bubble stands for. Matching by time rather
 *   than by text keeps two identical messages sent in a row apart.
 */
data class SendingUserTurn(val row: ChatHomeMessageRow, val sentAtMillis: Long)

/**
 * Pending composer attachment while the user composes a message.
 *
 * The picked/captured image is ingested asynchronously (downscale + JPEG
 * re-encode), so the draft passes through [Processing] before becoming
 * [Ready]; only [Ready] can be sent.
 */
sealed interface ComposerAttachmentDraft {

    /** The image is being downscaled and re-encoded; send is blocked. */
    data object Processing : ComposerAttachmentDraft

    /**
     * The image is stored and ready to send.
     *
     * @property attachment the stored [MessageAttachment] (carried onto the
     *   user message when sent).
     * @property absolutePath absolute path to the stored JPEG for the preview
     *   thumbnail (Coil model).
     * @property detail mono dimensions/size label shown beside the preview.
     */
    data class Ready(val attachment: MessageAttachment, val absolutePath: String, val detail: String) :
        ComposerAttachmentDraft
}

/**
 * Target of the full-screen image viewer.
 *
 * @property model image loader model (absolute file path), or `null` when the
 *   file is gone.
 * @property fileName file name shown in the viewer top bar.
 * @property dimensionsLabel mono dimensions/size label shown in the top bar.
 * @property isMissing whether the underlying file has been cleared (renders the
 *   "no longer available" message instead of the image).
 */
data class ImageViewerTarget(
    val model: Any?,
    val fileName: String,
    val dimensionsLabel: String,
    val isMissing: Boolean,
)

/**
 * Composer input slice of [ChatHomeScreenState].
 *
 * Named `ChatHomeComposerState` (not `ComposerState`) to avoid colliding
 * with the catalog's `app.knotwork.design.components.chat.ComposerState`,
 * which models the composer's visual mode rather than its text content.
 *
 * @property value current composer draft. Hoisted to the ViewModel so
 *   screen recompositions never own the text.
 * @property typedConfirm typed-confirm input shown next to the Destructive
 *   HITL confirmation row; must equal the magic word
 *   ([ChatHomeHitlDelegate.DESTRUCTIVE_TYPED_CONFIRM_WORD], case-insensitive)
 *   before a destructive tool can be approved.
 * @property attachment pending image attachment shown above the input row, or
 *   `null` when none is attached.
 * @property voice the voice-input capture/transcription phase driving the
 *   composer's recording bar / transcribing indicator.
 * @property voiceNotice a calm blocked/permission notice shown above the input
 *   row when a voice action cannot proceed, or `null` when none.
 * @property audioChooserVisible whether the voice source chooser sheet is open.
 */
data class ChatHomeComposerState(
    val value: String = "",
    val typedConfirm: String = "",
    val attachment: ComposerAttachmentDraft? = null,
    val voice: VoiceInputState = VoiceInputState.Idle,
    val voiceNotice: ComposerVoiceNotice? = null,
    val audioChooserVisible: Boolean = false,
    val audioMaxDurationSec: Int = SettingsDefaults.AUDIO_MAX_DURATION_SEC_DEFAULT,
)

/**
 * Voice-input phase of the composer (distinct from the text [value]). Drives the
 * catalog composer's recording bar and transcribing indicator.
 */
sealed interface VoiceInputState {
    /** Not recording or transcribing. */
    data object Idle : VoiceInputState

    /**
     * Capturing a clip.
     *
     * @property elapsedSec whole seconds captured so far.
     * @property maxSec the recording limit.
     */
    data class Recording(val elapsedSec: Int, val maxSec: Int) : VoiceInputState

    /** Transcribing the captured/picked clip into text. */
    data object Transcribing : VoiceInputState
}

/**
 * Snapshots of whatever the orchestrator is currently paused on.
 *
 * @property tool tool invocation awaiting HITL approval (`null` when no
 *   approval gate is active). Renders the trailing confirmation card.
 * @property clarification clarification request awaiting the user's reply
 *   (`null` when the agent is not waiting). Renders the trailing
 *   clarification card.
 * @property interrupted snapshot of the session's interrupted run (`null`
 *   when the latest run finished normally or is still active). Renders the
 *   trailing interrupted-run status card with Resume / Discard actions.
 * @property ceiling snapshot of a run paused at one of its own limits (`null`
 *   when no run is waiting on that decision). Renders the trailing pause card
 *   with Continue / Stop actions. Held apart from [interrupted] even though the
 *   two cards share their chrome: they ask for different decisions, and one
 *   slot would let a Discard tap settle a run the user meant to continue.
 */
data class ChatHomePendingState(
    val tool: HitlPending? = null,
    val clarification: ClarificationRequest? = null,
    val interrupted: InterruptedRunPending? = null,
    val ceiling: CeilingPausePending? = null,
)

/**
 * Active-thread metadata and the drawer thread list.
 *
 * @property title thread title rendered in the TopAppBar.
 * @property rows drawer thread list projected from the live session cache;
 *   favorited sessions sort to the top, the rest follow `updatedAt DESC`.
 * @property favorite whether the active session is favorited — drives the
 *   TopAppBar star icon.
 * @property currentSessionId id of the active chat session (blank before
 *   session initialisation completes).
 * @property archived whether the *active* chat is archived. Drives the
 *   read-only chrome: the composer is replaced by the restore bar and the
 *   top-bar subtitle reads "Archived · read-only". An archived chat is
 *   deliberately openable — reading the history is the whole reason it was
 *   archived instead of deleted — but it cannot be written to, because a sent
 *   message would silently un-archive it.
 * @property archivedCount number of archived chats. The drawer's archive
 *   footer row appears only when this is positive.
 * @property openMenuId id of the drawer row whose overflow menu is open, or
 *   `null`.
 */
data class ChatHomeThreadState(
    val title: String = DEFAULT_TITLE,
    val rows: List<ChatHomeThreadRow> = emptyList(),
    val favorite: Boolean = false,
    val currentSessionId: String = "",
    val archived: Boolean = false,
    val archivedCount: Int = 0,
    val openMenuId: String? = null,
) {
    companion object {
        /** Pre-formatted fallback thread title surfaced before any thread is selected. */
        const val DEFAULT_TITLE: String = "New conversation"
    }
}

/**
 * Local-model slice feeding the TopAppBar subtitle and the model-picker
 * sheet.
 *
 * @property name display name of the currently active local model, or the
 *   [DEFAULT_NAME] placeholder when none is active.
 * @property installed locally installed LiteRT models listed by the picker.
 * @property activeId row id of the currently active model (`null` when none
 *   is active) — the picker renders the checkmark from this.
 */
data class ChatHomeModelState(
    val name: String = DEFAULT_NAME,
    val installed: List<LocalModel> = emptyList(),
    val activeId: Long? = null,
) {
    companion object {
        /** Pre-formatted fallback model name surfaced when no local model is loaded. */
        const val DEFAULT_NAME: String = "Local model"
    }
}

/**
 * Token-meter slice of [ChatHomeScreenState].
 *
 * @property used rough token usage of the active session (`text.length / 4`).
 * @property max configured context-window cap propagated from
 *   `SettingsRepository.maxContextLength`.
 * @property streaming running approximate count of tokens produced by the
 *   in-flight LLM stream; surfaced through the agent status pill as
 *   `generating (GPU) · N tok`. Zero outside of [ChatHomeUiState.Generating].
 * @property backend wire key of the backend the engine is really executing on
 *   (`CPU` / `GPU` / `NPU`), or `null` when no model is loaded. Read from the
 *   engine rather than from settings: a load that fell back runs on CPU while
 *   the saved preference still says GPU, and the status pill has to show what
 *   is actually decoding the tokens.
 */
data class ChatHomeTokenState(val used: Int = 0, val max: Int = 0, val streaming: Int = 0, val backend: String? = null)
