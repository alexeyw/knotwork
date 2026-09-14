package app.knotwork.design.screens.chat

import app.knotwork.design.components.chat.ChatContent
import app.knotwork.design.components.chat.ChatContextAction
import app.knotwork.design.components.chat.ChatMessageStatus
import app.knotwork.design.components.chat.ChatMetadata
import app.knotwork.design.components.chat.ChatRole
import app.knotwork.design.components.chat.ComposerAttachment
import app.knotwork.design.components.chat.ComposerState
import app.knotwork.design.components.chat.ComposerVoiceNotice
import app.knotwork.design.components.console.ConsoleFilter
import app.knotwork.design.components.console.ConsoleLine
import app.knotwork.design.components.console.ConsoleSnap
import app.knotwork.design.components.console.ConsoleSource
import app.knotwork.design.components.console.ConsoleTab
import app.knotwork.design.components.console.ConsoleTraceSpan
import app.knotwork.design.components.console.ConsoleVarRow

/**
 * Visual variant of the chat-home surface. Drives chrome differences
 * (drawer overlay, console overlay, error scrim) and lets snapshot tests
 * iterate the 9 documented states deterministically.
 *
 * The presentation layer in `:app` maps its sealed `ChatHomeUiState` onto
 * this enum at the boundary — the catalog stays free of `:app` types.
 */
enum class ChatHomeVisualState {
    /**
     * Cold-start state — emitted before the chat repository delivers its
     * first message snapshot. Rendered as a centred progress indicator with
     * no placeholder copy so the user never sees the empty-state hero flash
     * for a frame on every launch.
     */
    Loading,

    /** No messages in the active thread; empty-state surface visible. */
    Empty,

    /** History present, no in-flight request. Default. */
    Idle,

    /** Assistant is producing tokens. Loader bubble visible; composer in stop mode. */
    Generating,

    /** A tool call awaits user approval. HITL confirmation card pinned to last bubble. */
    HitlConfirm,

    /** Assistant needs more info. Clarification card pinned to last bubble. */
    Clarification,

    /**
     * The session's most recent run was interrupted by a process death.
     * Interrupted-run status card (Resume / Discard) pinned to last bubble.
     */
    Interrupted,

    /**
     * A run has spent one of the limits the user set for it and is waiting to
     * be told whether it may carry on. Run-ceiling pause card (Continue / Stop)
     * pinned to the last bubble.
     *
     * Its own state rather than a flavour of [Interrupted]: both pin a status
     * card and leave the composer idle, but an interrupted run is over unless
     * the user revives it, while this one is alive and waiting. A host that
     * could not tell them apart would offer Discard for a run that is still
     * holding its checkpoint.
     */
    CeilingPause,

    /** Inline error tile + retry. */
    Error,

    /** Alternate nav drawer is open over the chat surface. */
    DrawerOpen,

    /** Console pane expanded over the chat surface. */
    ConsoleExpanded,
}

/**
 * Lightweight projection of one chat message as consumed by [ChatHomeContent].
 *
 * The full `ChatMessage` Composable in `:catalog` requires a [ChatRole],
 * [ChatContent] and [ChatMetadata] — bundling them as one immutable row
 * keeps the screen's `LazyColumn` body declarative.
 *
 * @property id stable identity for `LazyColumn` keys.
 * @property role conversational role of the sender.
 * @property content sealed body to render inside the bubble.
 * @property metadata footer payload (timestamp, model, tokens, status).
 */
data class ChatHomeMessageRow(val id: String, val role: ChatRole, val content: ChatContent, val metadata: ChatMetadata)

/**
 * Single thread row inside the alternate-nav drawer.
 *
 * @property id stable identity used as the `LazyColumn` key.
 * @property title display title (falls back to a localised default at the
 *   call site).
 * @property subtitle pre-formatted secondary line (timestamp + counts).
 * @property selected `true` when this thread is currently loaded.
 */
data class ChatHomeThreadRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val selected: Boolean = false,
    /**
     * `true` when the thread is currently active (used as the leading
     * status-dot color in the drawer). Distinct from [selected] which
     * only flags multi-select; an active thread is also visually
     * highlighted with a cream row background.
     */
    val active: Boolean = false,
    /**
     * `true` when the user has favorited this thread. Renders a small
     * leading star glyph in the drawer and lets the host sort favorited
     * threads to the top of the list. Mirrors the session-level
     * `isStarred` flag persisted in `chat_sessions.isStarred`.
     */
    val starred: Boolean = false,
    /**
     * `true` when the thread owns a pipeline run in a non-terminal status
     * (queued, executing, or suspended on a human-in-the-loop request).
     * Renders a small trailing progress indicator so the user can spot
     * background conversations that are still working at a glance.
     */
    val running: Boolean = false,
)

/**
 * Suggestion card rendered in the empty-state body. Composes a title
 * (`"Summarise this week's emails"`) with a monospace `uses · {tools}`
 * subtitle so the user knows which tools the prompt will reach for.
 *
 * @property id stable identity for `LazyColumn`-style snapshot pinning.
 * @property title prompt headline.
 * @property toolsUsed pre-formatted comma-separated tool list
 * (`"search_tool, delegate_task"`). Empty string hides the subtitle.
 */
data class ChatHomeSamplePromptCard(val id: String, val title: String, val toolsUsed: String)

/**
 * Console projection passed to [ChatHomeContent] when the console pane is
 * overlayed. The catalog has no opinion on pagination; tests and previews
 * pass simple lists.
 *
 * @property snap current snap point (Peek / Partial / Full).
 * @property tab currently-selected tab.
 * @property logs Logs-tab data.
 * @property vars Vars-tab data.
 * @property traces Traces-tab data.
 * @property filter source filter applied to [logs].
 */
data class ChatHomeConsoleState(
    /**
     * When non-null the console pane is rendered as an overlay anchored to
     * the bottom of the chat surface at the requested snap height. `null`
     * means the pane is closed — the orthogonal sealed [ChatHomeVisualState]
     * keeps its meaning and the body underneath renders unchanged. The
     * console is therefore truly independent of the chat state machine
     * (Generating / HitlConfirm / Clarification / Idle / Empty / Error all
     * stay visible behind the pane).
     */
    val snap: ConsoleSnap? = null,
    val tab: ConsoleTab = ConsoleTab.Logs,
    val logs: List<ConsoleLine> = emptyList(),
    val vars: List<ConsoleVarRow> = emptyList(),
    val traces: List<ConsoleTraceSpan> = emptyList(),
    val filter: ConsoleFilter = ConsoleFilter.allOn,
    /**
     * When non-null, the inline search field above the Logs list is rendered
     * and the query is substring-matched against each log line. `null`
     * means the search bar is hidden; `""` means visible but matching every
     * line. The host (chat ViewModel) toggles between these via the
     * `onConsoleSearchToggle` callback.
     */
    val searchQuery: String? = null,
)

/**
 * Top-level immutable input to [ChatHomeContent]. Every screen-side concern
 * that can change the visual is funnelled through this one type so the
 * stateless content stays trivially snapshot-testable.
 *
 * Covers the 9-state matrix for Chat (home).
 *
 * @property visualState which of the 9 documented states to render.
 * @property threadTitle text displayed as the TopAppBar title.
 * @property modelName text displayed beneath the title (model picker label).
 * @property messages chronological message list rendered inside the body
 *   `LazyColumn`. Empty when [visualState] is [ChatHomeVisualState.Empty].
 * @property composerValue current value of the composer text field.
 * @property composerState composer state-machine slot (drives the
 *   send/stop morph and inline error banner).
 * @property pendingTypedConfirm typed-confirm input for a Destructive
 *   confirmation; ignored unless [visualState] is
 *   [ChatHomeVisualState.HitlConfirm] with risk Destructive.
 * @property errorMessage user-visible error text for an **untyped** failure —
 *   a node or the engine broke — rendered in [ChatHomeVisualState.Error] with
 *   the destructive-toned tile and its Retry action. `null` whenever the run
 *   was stopped deliberately; see [termination].
 * @property termination why the app itself ended the run, when it did. Mutually
 *   exclusive with [errorMessage]: a stopped run is either a fault we did not
 *   choose or a decision we made, never both, and the two read very differently
 *   to the person holding the phone.
 * @property runNotice advisory about the run **still in flight** — it crossed a
 *   soft threshold, or (once the stuck detector lands) it stopped making
 *   progress. Independent of [visualState] on purpose: the run is still going,
 *   so the notice coexists with whatever the run is doing rather than replacing
 *   it.
 * @property threads thread rows surfaced inside the drawer overlay.
 * @property console console-pane snapshot, used when [visualState] is
 *   [ChatHomeVisualState.ConsoleExpanded].
 * @property samplePrompts suggestion chips rendered in the empty state.
 */
data class ChatHomeViewState(
    val visualState: ChatHomeVisualState,
    val threadTitle: String,
    val modelName: String,
    val messages: List<ChatHomeMessageRow> = emptyList(),
    val composerValue: String = "",
    val composerState: ComposerState = ComposerState.Idle,
    val pendingTypedConfirm: String = "",
    val errorMessage: String? = null,
    val termination: ChatTerminationUi? = null,
    val runNotice: ChatRunNoticeUi? = null,
    val threads: List<ChatHomeThreadRow> = emptyList(),
    val console: ChatHomeConsoleState = ChatHomeConsoleState(),
    val samplePrompts: List<String> = emptyList(),
    /** Pipeline currently bound to the thread; rendered in the TopAppBar subtitle and the empty-state caption. */
    val pipelineName: String = "default",
    /** Used / max token counts rendered in the TopAppBar subtitle (`"1.4k / 8k tok"`). */
    val tokensUsed: Int = 0,
    val tokensMax: Int = 0,
    /** Star/favorite flag rendered in the trailing TopAppBar action. */
    val favorite: Boolean = false,
    /**
     * Rich suggestion cards rendered inside the empty-state body. When
     * non-empty this list replaces the legacy [samplePrompts] chip row;
     * if both are empty the body renders the legacy "no prompts" copy.
     */
    val samplePromptCards: List<ChatHomeSamplePromptCard> = emptyList(),
    /**
     * Single-line agent status rendered in the console entry strip
     * (`"[NODE]  idle · ready"`). `null` hides the pill.
     */
    val agentStatusLine: String? = null,
    /**
     * Label of the loader bubble shown at the end of the thread while
     * [visualState] is `Generating`, or `null` for the default "Generating…".
     * Set when nothing is being generated yet — the model is still loading, or
     * the run waits behind another one — so the bubble does not claim otherwise.
     */
    val loaderLabel: String? = null,
    /**
     * Image attached to the composer, rendered as a removable strip above the
     * input row. `null` when no image is attached.
     */
    val composerAttachment: ComposerAttachment? = null,
    /**
     * Calm blocked/permission notice shown above the composer input row when a
     * voice action cannot proceed, or `null` when none.
     */
    val composerVoiceNotice: ComposerVoiceNotice? = null,
    /**
     * Id of the drawer thread whose overflow menu is open, or `null`. Hoisted
     * so only one menu can be open at a time.
     */
    val openThreadMenuId: String? = null,
    /**
     * **Preview / snapshot control only**: when non-null the named drawer row is
     * forced open, every other row forced shut, and the drag disabled. `null`
     * (production) leaves each row's swipe under the user's finger.
     */
    val revealedThreadId: String? = null,
    /**
     * Number of archived chats. Drives the drawer's "Archived chats" footer
     * row, which is shown **only** when this is greater than zero: the drawer
     * is a 320 dp working list, and a permanently empty row is clutter in the
     * one place the user goes to switch threads. Discoverability is carried by
     * the always-present More-tab entry instead.
     */
    val archivedCount: Int = 0,
    /**
     * `true` when the open thread is archived. The composer is **replaced** by
     * the restore bar (not disabled in place) and the top-bar subtitle reads
     * "Archived · read-only": a thread that is not in the drawer must not
     * accept a message, because sending one would silently change archive
     * state — and archiving is a user decision that only the user reverses.
     */
    val archivedReadOnly: Boolean = false,
    /**
     * `true` when the failure is already written into the conversation as a
     * message, so this surface owes the user no tile of its own.
     *
     * The invariant below is about the user being told, not about a particular
     * widget: a run settles its outcome into the thread whether or not anyone
     * was watching, and a tile repeating a line two rows above it is the
     * duplication this flag exists to end. A failure with no run behind it — a
     * blocked attachment, a model that would not load — has no such line, and
     * still has to carry [errorMessage].
     */
    val explainedInThread: Boolean = false,
) {
    init {
        require(
            (visualState == ChatHomeVisualState.Error) ==
                (errorMessage != null || termination != null || explainedInThread),
        ) {
            "the Error visual must be explained somewhere: errorMessage, termination, or the thread itself"
        }
        require(errorMessage == null || termination == null) {
            "a stopped run is either an untyped failure or a typed termination, never both"
        }
    }
}

/**
 * Stable callback bundle accepted by [ChatHomeContent]. Hoisted out of the
 * composable signature so screen code can pass one parameter object and so
 * tests / previews can construct a single no-op default.
 */
@Suppress("LongParameterList") // Mirrors the user-facing affordances; collapsing further hides intent.
class ChatHomeCallbacks(
    val onComposerValueChange: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onStop: () -> Unit = {},
    /**
     * Fired when the user taps the composer "add image" button. `null` hides
     * the attachment affordance entirely.
     */
    val onAttach: (() -> Unit)? = null,
    /** Fired when the user removes the pending composer attachment. */
    val onRemoveAttachment: () -> Unit = {},
    /**
     * Fired when the user taps the composer mic (voice input). `null` hides the
     * mic affordance entirely (it never renders without a handler).
     */
    val onMic: (() -> Unit)? = null,
    /** Fired when the user taps Stop in the recording bar. */
    val onStopRecording: () -> Unit = {},
    /** Fired when the user taps the discard ✕ in the recording bar. */
    val onDiscardRecording: () -> Unit = {},
    /** Fired from the no-audio-model voice notice's "Change model" action. */
    val onChangeModel: () -> Unit = {},
    /** Fired from the permission voice notice's "Open settings" action. */
    val onOpenAppSettings: () -> Unit = {},
    val onOpenDrawer: () -> Unit = {},
    val onCloseDrawer: () -> Unit = {},
    val onSelectThread: (String) -> Unit = {},
    val onNewThread: () -> Unit = {},
    val onOverflow: () -> Unit = {},
    val onSamplePrompt: (String) -> Unit = {},
    val onConsoleSnapChange: (ConsoleSnap) -> Unit = {},
    val onConsoleTabChange: (ConsoleTab) -> Unit = {},
    val onConsoleFilterChange: (ConsoleFilter) -> Unit = {},
    val onConsoleSearch: () -> Unit = {},
    val onConsoleSearchQueryChange: (String) -> Unit = {},
    val onConsoleCopyLine: (ConsoleLine) -> Unit = {},
    val onConsoleCopyVar: (ConsoleVarRow) -> Unit = {},
    val onConsoleCopySpan: (ConsoleTraceSpan) -> Unit = {},
    val onConsoleFilterByLineSource: (ConsoleSource) -> Unit = {},
    val onConsoleCopyAll: () -> Unit = {},
    val onConsoleClear: () -> Unit = {},
    val onCloseConsole: () -> Unit = {},
    val onHitlAllowOnce: () -> Unit = {},
    val onHitlAllowAlways: (() -> Unit)? = null,
    val onHitlReject: () -> Unit = {},
    val onHitlTypedConfirmChange: (String) -> Unit = {},
    val onClarificationReply: (String) -> Unit = {},
    /**
     * Fired when the user taps the Resume CTA on the interrupted-run card.
     * Hosts wire this to the checkpoint-resume mechanism.
     */
    val onResumeRun: () -> Unit = {},
    /**
     * Fired when the user taps the Discard CTA on the interrupted-run card.
     * Hosts settle the interrupted run as failed and drop the card.
     */
    val onDiscardRun: () -> Unit = {},
    /**
     * Fired when the user taps the continue CTA on the run-ceiling pause card.
     * Hosts grant the run one more portion of the limit it reached and resume
     * it from its checkpoint. Deliberately not [onResumeRun]: that one continues
     * a run that was interrupted, this one authorises a run to spend more than
     * it was allowed, and a host wiring the two together would let a Resume tap
     * quietly raise a limit.
     */
    val onContinuePastCeiling: () -> Unit = {},
    /**
     * Fired when the user taps the stop CTA on the run-ceiling pause card.
     * Hosts settle the run at the ceiling it reached.
     */
    val onStopAtCeiling: () -> Unit = {},
    val onErrorRetry: () -> Unit = {},
    /**
     * Invoked by the single action on a typed termination tile. What it does is
     * decided by the host from the reason — adjust the limits, open the console,
     * run it again — which is why the tile carries a label rather than a verb.
     */
    val onTerminationAction: () -> Unit = {},
    val onTitleTripleTap: () -> Unit = {},
    val onToggleFavorite: () -> Unit = {},
    /** Fired from the drawer row's overflow "Rename" item. */
    val onEditThread: (String) -> Unit = {},
    /** Opens the drawer row's overflow menu (⋮, which replaced the pencil). */
    val onThreadMenuOpen: (String) -> Unit = {},
    /** Dismisses the drawer row's overflow menu. */
    val onThreadMenuDismiss: () -> Unit = {},
    /** Archives the drawer thread — from the swipe action or the overflow item. */
    val onArchiveThread: (String) -> Unit = {},
    /** Deletes the drawer thread — overflow only, and the host confirms first. */
    val onDeleteThread: (String) -> Unit = {},
    /** Opens the archive surface from the drawer footer row. */
    val onOpenArchive: () -> Unit = {},
    /** Restores the open archived thread from the read-only bar replacing the composer. */
    val onRestoreArchivedThread: () -> Unit = {},
    val onImportChat: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onSamplePromptCard: (ChatHomeSamplePromptCard) -> Unit = {},
    /**
     * Fired when the user taps the console entry strip above the composer.
     * Hosts wire this to opening the console pane at the Partial snap so
     * the user can drill into pipeline activity in one tap (the pill
     * itself surfaces only a one-line summary).
     */
    val onAgentStatusClick: () -> Unit = {},
    /**
     * Fired when the user picks an action from a message-bubble's
     * long-press context menu (Copy / Rerun / Rate). The first argument
     * is the `ChatHomeMessageRow.id` of the row that was long-pressed —
     * hosts use it to look up the underlying domain message and act
     * (write to clipboard, re-send the prompt, open the rating sheet).
     *
     * Default no-op disables the long-press menu — the catalog only
     * enables `combinedClickable` when the underlying `onContextAction`
     * is non-null.
     */
    val onMessageContextAction: (rowId: String, action: ChatContextAction) -> Unit = { _, _ -> },
)

/** Convenience factory returning a callbacks bundle that ignores every event. */
fun noopChatHomeCallbacks(): ChatHomeCallbacks = ChatHomeCallbacks()

/**
 * Default sentinel used as a placeholder when [ChatMetadata.status] is not
 * specifically relevant. Kept module-private so screen code uses the
 * canonical constructor explicitly.
 */
internal val DefaultRowMetadata: ChatMetadata = ChatMetadata(
    timestamp = "—",
    status = ChatMessageStatus.Sent,
)

/**
 * How a stopped or struggling run is toned in the chat.
 *
 * Mirrors the host's termination vocabulary locally, the way this module
 * mirrors every other domain enum: `:catalog` keeps its zero-dependency on
 * `:app`, so the host maps its own type onto this one and hands over resolved
 * strings.
 *
 * Status is never carried by colour alone here — each tone is rendered with a
 * glyph *and* the word the host supplies alongside it.
 */
enum class RunTerminationToneUi {
    /** A limit the user configured did its job. Shield, warning tone. */
    Limit,

    /** The run stopped getting anywhere and was ended. Warning triangle. */
    Stuck,

    /** Housekeeping: the app restarted, the user discarded it, a window closed. */
    Info,
}

/**
 * The tile explaining a run the app deliberately ended.
 *
 * Distinct from the untyped error tile in tone, glyph and action. In
 * particular it has **no Retry**: every typed termination is a decision about
 * this run, and repeating the identical turn reaches the identical outcome.
 *
 * @property tone Glyph and colour ranking.
 * @property toneLabel The word beside the glyph, e.g. "Safety limit".
 * @property title One line naming what happened.
 * @property body What happened and what to do about it.
 * @property meter The numbers behind the decision, in tabular figures on their
 *   own line, or `null` when the reason has none. Kept out of [body] so one
 *   sentence serves surfaces that have the numbers and surfaces that do not.
 * @property banner One clause for the strip above the composer. A separate
 *   string from [body], and a separately *toned* surface: routing it through
 *   the composer's error banner would have put a red alert glyph two inches
 *   below a tile explaining that nothing had gone wrong.
 * @property actionLabel Label of the single offered action, or `null` when
 *   there is nothing useful to offer.
 */
data class ChatTerminationUi(
    val tone: RunTerminationToneUi,
    val toneLabel: String,
    val title: String,
    val banner: String,
    val meter: String? = null,
    val actionLabel: String? = null,
)

/**
 * The quiet, non-modal advisory shown directly above the composer while a run
 * is still going.
 *
 * One component for two causes on purpose: "you are nearing a limit you set"
 * and "this run looks stuck" ask the same thing of the reader — wind this up —
 * so they share a slot and a shape, and differ only in glyph and sentence.
 *
 * Not dismissible, and gone the moment the run ends: it is a property of the
 * run in flight, not a message in the thread.
 *
 * @property tone Glyph and colour.
 * @property text The whole sentence — a notice is one line, not a card.
 */
data class ChatRunNoticeUi(val tone: RunTerminationToneUi, val text: String)
