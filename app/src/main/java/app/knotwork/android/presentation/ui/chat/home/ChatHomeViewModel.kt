package app.knotwork.android.presentation.ui.chat.home

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.ChatSession
import app.knotwork.android.domain.models.HardCeilingBreach
import app.knotwork.android.domain.models.MessageAttachment
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.PipelineSamplePrompt
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.ClarificationRepository
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.AttachmentStore
import app.knotwork.android.domain.services.AudioCaptureStore
import app.knotwork.android.domain.services.AudioRecorder
import app.knotwork.android.domain.services.ChatHistoryCompressionCoordinator
import app.knotwork.android.domain.services.MemoryAutoExtractionCoordinator
import app.knotwork.android.domain.usecases.AgentOrchestratorUseCase
import app.knotwork.android.domain.usecases.ArchiveChatUseCase
import app.knotwork.android.domain.usecases.AttachmentMessageContent
import app.knotwork.android.domain.usecases.ExportChatUseCase
import app.knotwork.android.domain.usecases.GetContextWindowUseCase
import app.knotwork.android.domain.usecases.LoadModelUseCase
import app.knotwork.android.domain.usecases.ResolveEntryInferenceUseCase
import app.knotwork.android.domain.usecases.ResumePipelineRunUseCase
import app.knotwork.android.domain.usecases.SaveMessageToMemoryUseCase
import app.knotwork.android.domain.usecases.SubmitApprovalDecisionUseCase
import app.knotwork.android.domain.usecases.SubmitCeilingDecisionUseCase
import app.knotwork.android.domain.usecases.SubmitClarificationAnswerUseCase
import app.knotwork.android.domain.usecases.TranscribeAudioUseCase
import app.knotwork.android.domain.usecases.UnarchiveChatUseCase
import app.knotwork.android.presentation.state.ActiveSessionTracker
import app.knotwork.design.components.chat.ChatContent
import app.knotwork.design.components.chat.ChatMessageStatus
import app.knotwork.design.components.chat.ChatMetadata
import app.knotwork.design.components.chat.ChatRole
import app.knotwork.design.components.chips.Risk
import app.knotwork.design.screens.chat.ChatHomeMessageRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * Hilt [ViewModel] backing the redesigned Knotwork chat home.
 *
 * Wires together:
 *  - [AgentOrchestratorUseCase] for the agent execution stream;
 *  - [ChatRepository] for session + message persistence;
 *  - [PipelineRepository] for pipeline-binding observation and the
 *    deleted-pipeline fallback Snackbar;
 *  - [SettingsRepository] for the persisted active session id and the
 *    context-window cap;
 *  - [LlmInferenceEngine] for the "load a model first" gate;
 *  - [GetContextWindowUseCase] for the rough token-counter TopAppBar
 *    readout (v0.1 — `text.length / 4`).
 *
 * Acts as a thin coordinator over a set of domain **delegates**, each owning a
 * cohesive slice of the chat-home surface and sharing this ViewModel's
 * [viewModelScope] and the single [_state] reducer (the variant-B delegation
 * pattern — see `docs/architecture.md` §1.2). The screen calls
 * `viewModel.<delegate>.*`:
 *  - [console] — `ConsoleLog` / `PipelineTrace` / `NodeIO` streaming + replay;
 *  - [voice] — record / pick → transcribe into the composer;
 *  - [attachments] — image source chooser, ingest, viewer, multimodal pre-flight;
 *  - [transfer] — chat import / export / save-to-memory;
 *  - [pipelineBinding] — library cache, TopAppBar subtitle, deleted-pipeline fallback;
 *  - [threads] — session cache, drawer rows + overlay, session CRUD;
 *  - [hitl] — approval gate + clarification reply;
 *  - [reattach] — reattach / background-run / interrupted-run card.
 *
 * What stays here is the agent-execution core the delegates orchestrate around:
 * the user-prompted send cycle (`Idle → Generating → Idle / Error`), the live
 * run collector ([attachToLiveRun] + [handleOrchestratorState]) the reattach and
 * HITL delegates drive through seams, the thread-switch hub ([selectThread]),
 * session init + message stream + token counter, and the resting-state machine.
 *
 * Everything the screen renders is aggregated into a single immutable
 * [ChatHomeScreenState] exposed through [state]; every mutation funnels
 * through `_state.update { it.copy(...) }` (delegates use the same shared
 * [_state]). One-shot side effects (export payloads, snackbars, the
 * pipeline-fallback signal) stay on dedicated [SharedFlow] channels — owned by
 * the relevant delegate — because replaying them on re-subscription would
 * duplicate the side effect.
 */
@HiltViewModel
class ChatHomeViewModel
@Inject
// Reason: the chat surface is one screen whose behaviour is already split into
// eight delegates, and this constructor is where their collaborators arrive
// before being handed on. Hilt assembles the list — it is never written out by
// hand — and the count drops only if the delegates are injected directly rather
// than constructed here, which is a wiring change with its own blast radius
// (each delegate would need its own Hilt binding and lifecycle owner).
// Scoped to the constructor so it cannot silence a future finding elsewhere in
// the class.
@Suppress("LongParameterList")
constructor(
    private val agentOrchestratorUseCase: AgentOrchestratorUseCase,
    private val chatRepository: ChatRepository,
    private val pipelineRepository: PipelineRepository,
    private val settingsRepository: SettingsRepository,
    private val getContextWindowUseCase: GetContextWindowUseCase,
    private val llmInferenceEngine: LlmInferenceEngine,
    private val clarificationRepository: ClarificationRepository,
    private val localModelRepository: LocalModelRepository,
    private val loadModelUseCase: LoadModelUseCase,
    private val memoryAutoExtractionCoordinator: MemoryAutoExtractionCoordinator,
    private val chatHistoryCompressionCoordinator: ChatHistoryCompressionCoordinator,
    private val saveMessageToMemoryUseCase: SaveMessageToMemoryUseCase,
    private val pipelineRunRepository: PipelineRunRepository,
    private val runTraceRepository: RunTraceRepository,
    private val resumePipelineRunUseCase: ResumePipelineRunUseCase,
    private val pendingInteractionRepository: PendingInteractionRepository,
    private val submitApprovalDecisionUseCase: SubmitApprovalDecisionUseCase,
    private val submitClarificationAnswerUseCase: SubmitClarificationAnswerUseCase,
    private val submitCeilingDecisionUseCase: SubmitCeilingDecisionUseCase,
    private val attachmentStore: AttachmentStore,
    private val resolveEntryInferenceUseCase: ResolveEntryInferenceUseCase,
    private val audioRecorder: AudioRecorder,
    private val audioCaptureStore: AudioCaptureStore,
    private val transcribeAudioUseCase: TranscribeAudioUseCase,
    private val activeSessionTracker: ActiveSessionTracker,
    private val archiveChatUseCase: ArchiveChatUseCase,
    private val exportChatUseCase: ExportChatUseCase,
    private val unarchiveChatUseCase: UnarchiveChatUseCase,
) : ViewModel() {

    private val _state: MutableStateFlow<ChatHomeScreenState> = MutableStateFlow(ChatHomeScreenState())

    /**
     * Single source of truth for the chat-home surface. The screen performs
     * one `collectAsStateWithLifecycle` on this flow and hands the immutable
     * sub-structures (composer, console, pending, thread, model, tokens)
     * down the tree as-is.
     */
    val state: StateFlow<ChatHomeScreenState> = _state.asStateFlow()

    /**
     * Marks the chat surface as visible and in the foreground, publishing the
     * currently open session id to [ActiveSessionTracker].
     *
     * While a session is the active one, the live-phase HITL approval gate
     * suppresses its system notification ([ApprovalNotificationManager]) — the
     * user already sees the inline approval card, so a duplicate notification in
     * the shade would be noise. Called by the screen on `ON_RESUME` and whenever
     * the open session changes while resumed.
     */
    fun onChatScreenVisible() {
        activeSessionTracker.setActiveSessionId(state.value.thread.currentSessionId.takeIf { it.isNotBlank() })
    }

    /**
     * Marks the chat surface as no longer visible (navigated away or app sent to
     * background), clearing [ActiveSessionTracker].
     *
     * Clearing it re-enables the approval notification so a run that raises a
     * HITL gate while the app is backgrounded still reaches the user through the
     * notification shade. Called by the screen on `ON_STOP` and on dispose.
     */
    fun onChatScreenHidden() {
        activeSessionTracker.setActiveSessionId(null)
    }

    private var messagesJob: Job? = null

    /**
     * Node type currently executing in the live run, taken from
     * [AgentOrchestratorState.PipelineStage]. Used only to attribute streamed tokens
     * to the right producer in the status pill; `null` between runs.
     */
    private var currentNodeType: String? = null

    private var generationJob: Job? = null

    /**
     * The in-flight "load the active model, then send" coroutine, or `null`
     * once it settles. Tracked separately from [generationJob] so the
     * transient model-loading phase (`Generating(preparingModel = true)`) can be cancelled by
     * [stopGeneration] without disturbing a real generation, and so re-entering
     * the send path after a successful load does not cancel its own launcher.
     */
    private var modelLoadJob: Job? = null

    /**
     * Per-session unsent composer text ("drafts"), keyed by chat session id.
     *
     * The composer holds exactly one live [ChatHomeComposerState.value] for the
     * whole surface, so a bare thread switch would carry one chat's half-typed
     * text into another. This map lets [selectThread] stash the outgoing chat's
     * draft and restore the incoming chat's, giving every conversation its own
     * independent input buffer. In-memory only: drafts are ephemeral and are
     * intentionally not persisted across process death. Entries are dropped when
     * a draft is sent ([proceedSend]) or its session is deleted ([clearDraft]).
     */
    private val sessionDrafts: MutableMap<String, String> = mutableMapOf()

    /**
     * Re-entrancy guard for the image send path: the attachment pre-flight is
     * asynchronous (it resolves the pipeline graph), so a second `sendMessage`
     * could slip through the synchronous `Generating` guard before the first
     * pre-flight flips the surface. Set synchronously the moment a pre-flight
     * launches, cleared when it settles to a block or a started run.
     */
    private var attachmentSendInFlight: Boolean = false
    private var tokenCounterJob: Job? = null

    /**
     * Dispatcher carrying the CPU-bound projection of a replayed trace
     * (filtering and mapping the full record list to console rows). Off-main
     * by default so a long run's one-shot projection cannot jank the session
     * open; swapped for the test dispatcher in unit tests. Read lazily by
     * [console] on every replay, so a test that sets it after construction is
     * honoured.
     */
    @VisibleForTesting
    internal var traceProjectionDispatcher: CoroutineDispatcher = Dispatchers.Default

    /**
     * Console-pane delegate (live `ConsoleLog` / `PipelineTrace` / `NodeIO`
     * aggregation, persisted-trace replay, per-run caches, console intents).
     * Exposed directly (the screen and tests call `viewModel.console.*`) — the
     * delegate shares [viewModelScope] and the single [_state] reducer and owns
     * the `console` slice. See `docs/architecture.md` §1.2.
     */
    val console: ChatHomeConsoleDelegate = ChatHomeConsoleDelegate(
        scope = viewModelScope,
        state = _state,
        settingsRepository = settingsRepository,
        runTraceRepository = runTraceRepository,
        pipelineRunRepository = pipelineRunRepository,
        traceProjectionDispatcher = { traceProjectionDispatcher },
    )

    /**
     * Voice-input delegate (record / pick → transcribe into the composer). Owns
     * the `composer.voice` / `voiceNotice` / `audioChooser` state and the
     * `voiceErrorEvents` one-shot. The screen calls `viewModel.voice.*`.
     */
    val voice: ChatHomeVoiceDelegate = ChatHomeVoiceDelegate(
        scope = viewModelScope,
        state = _state,
        settingsRepository = settingsRepository,
        audioRecorder = audioRecorder,
        audioCaptureStore = audioCaptureStore,
        transcribeAudioUseCase = transcribeAudioUseCase,
    )

    /**
     * Image-attachment delegate (source chooser, ingest/preview, full-screen
     * viewer, multimodal pre-flight). Owns the `composer.attachment`,
     * `sourceChooserVisible` and `imageViewer` state and `attachmentErrorEvents`.
     * The send path consults [ChatHomeAttachmentDelegate.preflightBlockReason].
     */
    val attachments: ChatHomeAttachmentDelegate = ChatHomeAttachmentDelegate(
        scope = viewModelScope,
        state = _state,
        attachmentStore = attachmentStore,
        resolveEntryInferenceUseCase = resolveEntryInferenceUseCase,
        sessions = { threads.sessionsSnapshot() },
    )

    /**
     * Chat import / export / save-to-memory delegate. Owns the `exportEvents`,
     * `importErrorEvents` and `memorySaveEvents` one-shots and exposes
     * [ChatHomeTransferDelegate.textForRow] for the long-press context menu.
     */
    val transfer: ChatHomeTransferDelegate = ChatHomeTransferDelegate(
        scope = viewModelScope,
        state = _state,
        chatRepository = chatRepository,
        exportChatUseCase = exportChatUseCase,
        saveMessageToMemoryUseCase = saveMessageToMemoryUseCase,
        selectThread = ::selectThread,
    )

    /**
     * Pipeline-binding delegate (library cache, TopAppBar subtitle, deleted-
     * pipeline fallback). Owns `availablePipelines` / `pipelineName` and the
     * `pipelineFallbackEvents` one-shot; reads the session cache via a provider.
     */
    val pipelineBinding: ChatHomePipelineBindingDelegate = ChatHomePipelineBindingDelegate(
        scope = viewModelScope,
        state = _state,
        settingsRepository = settingsRepository,
        pipelineRepository = pipelineRepository,
        chatRepository = chatRepository,
        pipelineRunRepository = pipelineRunRepository,
        sessions = { threads.sessionsSnapshot() },
    )

    /**
     * Threads / sessions delegate (session cache, drawer rows + overlay,
     * session CRUD, metadata refresh). Owns the `thread` slice. The thread
     * switch itself stays in the ViewModel ([selectThread]) — it orchestrates
     * run-lifecycle concerns — and CRUD routes back through it.
     */
    val threads: ChatHomeThreadsDelegate = ChatHomeThreadsDelegate(
        scope = viewModelScope,
        state = _state,
        chatRepository = chatRepository,
        archiveChatUseCase = archiveChatUseCase,
        unarchiveChatUseCase = unarchiveChatUseCase,
        pipelineRunRepository = pipelineRunRepository,
        selectThread = ::selectThread,
        clearDraft = ::clearDraft,
        pipelineNameRefresher = pipelineBinding::pipelineNameRefreshed,
        onSessionsChanged = pipelineBinding::handleDeletedBoundPipeline,
    )

    /**
     * Reattach / background-run / interrupted-run delegate. Owns the
     * `pending.interrupted` snapshot and the shared `resumeFeedbackEvents`
     * channel; drives the ViewModel's live collector ([attachToLiveRun]) and
     * restores suspension cards through the HITL delegate. Declared before [hitl]
     * so [hitl] can share its resume-feedback channel.
     */
    val reattach: ChatHomeReattachDelegate = ChatHomeReattachDelegate(
        scope = viewModelScope,
        state = _state,
        pipelineRunRepository = pipelineRunRepository,
        pipelineRepository = pipelineRepository,
        settingsRepository = settingsRepository,
        agentOrchestratorUseCase = agentOrchestratorUseCase,
        clarificationRepository = clarificationRepository,
        pendingInteractionRepository = pendingInteractionRepository,
        resumePipelineRunUseCase = resumePipelineRunUseCase,
        attachToLiveRun = ::attachToLiveRun,
        replayTrace = console::replayTrace,
        restoreApproval = { hitl.handleWaitingForApproval(it) },
        restoreClarification = { hitl.handleAwaitingClarification(it) },
        restoreCeilingPause = { hitl.restoreCeilingPause(it) },
    )

    /**
     * Human-in-the-loop / clarification delegate. Owns the `pending.tool` /
     * `pending.clarification` snapshots and the typed-confirm input; a parked
     * resume re-attaches via [attachToLiveRun] and resume failures surface
     * through the reattach delegate's shared channel.
     */
    val hitl: ChatHomeHitlDelegate = ChatHomeHitlDelegate(
        scope = viewModelScope,
        state = _state,
        chatRepository = chatRepository,
        submitApprovalDecisionUseCase = submitApprovalDecisionUseCase,
        submitClarificationAnswerUseCase = submitClarificationAnswerUseCase,
        submitCeilingDecisionUseCase = submitCeilingDecisionUseCase,
        attachToLiveRun = ::attachToLiveRun,
        emitResumeFeedback = reattach::emitResumeFeedback,
    )

    init {
        pipelineBinding.startObservers()
        threads.startObservers()
        observeMaxContextSize()
        console.startObservers()
        observeInstalledModels()
        initializeSession()
    }

    /** Updates the composer input value. Hoisted to the VM so screen recompositions never own the text. */
    fun onComposerValueChange(value: String) {
        // Typing dismisses any blocked voice notice so it never wedges Send
        // (which is disabled while a notice is shown).
        _state.update { it.copy(composer = it.composer.copy(value = value, voiceNotice = null)) }
    }

    /**
     * The three cheap refusals the send path applies before touching the
     * engine: an attachment still being downscaled, a wholly empty send, and an
     * archived chat.
     *
     * The archived case is unreachable from the UI — the composer is replaced
     * by the restore bar — but the guard keeps the invariant true for every
     * other caller: accepting a message here would silently un-archive a chat
     * the user deliberately put away.
     *
     * Grouped into one predicate so the send path's early-return count stays
     * readable as the pre-flight grows.
     *
     * @param draftText the trimmed composer text.
     * @param readyAttachment the fully-ingested image attachment, or `null`.
     * @return `true` when the send may proceed.
     */
    private fun canAcceptSend(draftText: String, readyAttachment: MessageAttachment?): Boolean {
        val snapshot = _state.value
        if (snapshot.composer.attachment is ComposerAttachmentDraft.Processing) return false
        if (draftText.isEmpty() && readyAttachment == null) return false
        if (snapshot.thread.archived) return false
        return true
    }

    /**
     * Sends the current composer draft through [AgentOrchestratorUseCase].
     * No-op when the composer is blank, when generation is already in
     * flight, or when no LLM model is loaded (in the last case the surface
     * flips to [ChatHomeUiState.Error] so the user sees actionable
     * guidance).
     *
     * The user prompt itself is persisted by the orchestrator's pipeline
     * (see `TaskQueueManagerImpl.processTask` step 1), not by this VM —
     * persisting it twice would surface duplicate rows in the display
     * flow. Same for the final agent reply: `OutputNodeExecutor` writes
     * the `isFinal = true` row when the pipeline reaches OUTPUT.
     */
    fun sendMessage() {
        val draftText = _state.value.composer.value.trim()
        val readyAttachment = (_state.value.composer.attachment as? ComposerAttachmentDraft.Ready)?.attachment
        if (!canAcceptSend(draftText = draftText, readyAttachment = readyAttachment)) return
        // Busy: a generation is streaming, or a model load kicked off by this
        // very path is still in flight (`Generating(preparingModel = true)`).
        // A single `is Generating` covers both, so a second tap cannot cancel
        // and restart the in-progress load.
        if (_state.value.visual is ChatHomeUiState.Generating) return
        if (!llmInferenceEngine.isInitialized) {
            // The model isn't loaded yet — instead of dead-ending on an error
            // tile that forces the user to tap Retry (load) and then Send
            // again, auto-load the active model and re-enter the send path so
            // the message goes out in one tap. Only a genuine load failure
            // (no active model registered) surfaces the error, from where
            // Retry re-attempts the load-and-send. The draft/attachment stay
            // in the composer across the load (nothing is cleared here).
            loadModelThenSend()
            return
        }
        // Multimodal pre-flight: an image can only travel into a run that starts
        // on-device against a vision-capable model. The check resolves the
        // pipeline graph, so it suspends — run it before clearing the composer so
        // a blocked send keeps the user's draft and attachment intact (mirrors
        // the "model not loaded" guard above, which also returns before clearing).
        //
        // The suspend gap means the synchronous `Generating` guard above cannot
        // cover the window between two rapid sends; `attachmentSendInFlight` is
        // set synchronously here (before the coroutine suspends) so a second tap
        // is rejected until this pre-flight resolves to either a block or a run.
        if (readyAttachment != null) {
            // Guard without its own `return` keeps the function's return count
            // within the detekt ceiling; a second tap simply finds the flag set
            // and falls through to the trailing `return` doing nothing.
            if (!attachmentSendInFlight) {
                attachmentSendInFlight = true
                viewModelScope.launch {
                    try {
                        val block = attachments.preflightBlockReason()
                        if (block != null) {
                            _state.update { it.copy(visual = ChatHomeUiState.Error(block)) }
                        } else {
                            proceedSend(draftText, readyAttachment)
                        }
                    } finally {
                        attachmentSendInFlight = false
                    }
                }
            }
            return
        }
        proceedSend(draftText, readyAttachment = null)
    }

    /**
     * Performs the actual send once any pre-flight guard has passed: builds the
     * effective prompt, clears the composer, flips the surface to Generating and
     * launches the orchestrator collection.
     *
     * @param draftText The user's trimmed caption text (may be empty for an
     *   image-only message).
     * @param readyAttachment The resolved image attachment to send, or `null`.
     */
    private fun proceedSend(draftText: String, readyAttachment: MessageAttachment?) {
        // Image-only message: an empty caption is replaced by the internal
        // default instruction so the all-text pipeline graph has a prompt to
        // travel; the saved message keeps the empty caption so the bubble shows
        // just the thumbnail.
        val content = AttachmentMessageContent.resolve(draftText)
        _state.update { it.copy(composer = it.composer.copy(value = "", attachment = null)) }

        val sessionId = _state.value.thread.currentSessionId
        if (sessionId.isBlank()) return
        // The draft has now been sent; drop its per-session buffer so returning
        // to this chat later shows an empty composer, not the just-sent text.
        sessionDrafts.remove(sessionId)

        // Rename from the user's own text, not the effective prompt: an
        // image-only message (empty draft) must not title the chat with the
        // internal default instruction.
        threads.autoRenameIfDefault(sessionId, draftText)

        launchRun(
            sessionId = sessionId,
            prompt = content.prompt,
            displayContent = content.displayContent,
            readyAttachment = readyAttachment,
        )
    }

    /**
     * Starts a pipeline run for an already-decided prompt and drives the UI from
     * its state stream.
     *
     * Split out of [proceedSend] because [retryAfterError] needs exactly this and
     * none of the composer bookkeeping around it: a retry re-runs a turn the user
     * already sent, so clearing the composer (or renaming the chat from the
     * retried text) would destroy work they did while reading the error.
     *
     * @param sessionId chat session the run belongs to.
     * @param prompt the effective prompt travelling through the pipeline.
     * @param displayContent what the user's bubble shows, when it differs from
     *   [prompt] (an image-only message has an empty caption but a real prompt).
     * @param readyAttachment attachment to carry with the turn, if any.
     */
    private fun launchRun(
        sessionId: String,
        prompt: String,
        displayContent: String?,
        readyAttachment: MessageAttachment?,
        persistUserMessage: Boolean = true,
    ) {
        val pipelineId = threads.sessionsSnapshot().firstOrNull { it.id == sessionId }?.pipelineId

        // Fresh run = fresh cumulative log upstream; the baseline carried
        // over from a previous run's mid-stream Clear no longer applies
        // (the engine restarts events from scratch), and an in-flight
        // reattach lookup is cancelled with it. Mirrors legacy
        // `ChatViewModel.sendMessage`.
        resetConsoleCachesForNewRun()
        // Single atomic transition: flip to Generating, reset the streaming
        // token counter (the pill displays "generating · 0 tok" →
        // "generating · N tok" rather than carrying over the previous
        // response's final count), drop pending HITL / clarification
        // snapshots, and clear the console projections of the previous run.
        _state.update { current ->
            current.withPendingCleared().withConsoleProjectionsCleared().copy(
                visual = ChatHomeUiState.Generating(),
                tokens = current.tokens.copy(streaming = 0),
                // A notice belongs to the run that raised it. The new run has
                // its own budget and will raise its own if it needs to.
                runNotice = null,
            )
        }

        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            // The per-session orchestrator flow (a replay-1 SharedFlow) replays
            // the PREVIOUS run's terminal state (Completed/Error) — or the seed
            // Idle — to this freshly attached collector. A run enqueued micro-
            // seconds ago cannot have reached a terminal state in the synchronous
            // window before this subscription, so a leading terminal/resting
            // emission is that stale replay. Drop it once: otherwise the replayed
            // Completed would immediately settle the new run out of `Generating`,
            // hiding all live progress (no indicator, stale console) until the
            // answer silently lands. The first run worked only because its seed
            // was Idle, which `handleOrchestratorState` already ignores.
            var awaitingFirstRunState = true
            agentOrchestratorUseCase(
                sessionId = sessionId,
                userPrompt = prompt,
                pipelineId = pipelineId,
                attachment = readyAttachment,
                displayContent = displayContent,
                persistUserMessage = persistUserMessage,
            )
                .catch { error ->
                    _state.update {
                        it.copy(visual = ChatHomeUiState.Error(error.message ?: UNKNOWN_ERROR_FALLBACK))
                    }
                }
                .collect { orchestratorState ->
                    if (awaitingFirstRunState) {
                        awaitingFirstRunState = false
                        if (orchestratorState.isStaleReplayOnSend()) return@collect
                    }
                    handleOrchestratorState(orchestratorState)
                }
        }
    }

    /**
     * Whether [this] is a terminal/resting state that, when seen as the very
     * first emission after [proceedSend] subscribes, must be the previous run's
     * replayed value (a just-enqueued run cannot have settled yet). Used to drop
     * that one stale replay so it cannot end the fresh run's `Generating` state.
     */
    private fun AgentOrchestratorState.isStaleReplayOnSend(): Boolean = this is AgentOrchestratorState.Completed ||
        this is AgentOrchestratorState.Error ||
        this == AgentOrchestratorState.Idle

    /**
     * Loads the active local model and, on success, re-enters [sendMessage] so
     * the user's retained composer draft (and any attachment) is delivered
     * automatically without a second tap. Drives the transient
     * `Generating(preparingModel = true)` state so the surface shows an honest
     * "loading model" affordance rather than the misleading "generating" pill
     * while nothing is being generated yet.
     *
     * A load failure (typically "no active model registered") flips to
     * [ChatHomeUiState.Error] without clearing the composer, so Retry — or the
     * user, after registering a model in Settings — can resend. Tracked via
     * [modelLoadJob] so [stopGeneration] can cancel the pending send.
     */
    private fun loadModelThenSend() {
        _state.update { it.copy(visual = ChatHomeUiState.Generating(preparingModel = true)) }
        modelLoadJob?.cancel()
        modelLoadJob = viewModelScope.launch {
            when (val outcome = loadModelUseCase()) {
                is Result.Success -> {
                    // If the user tapped Stop (or switched chats) while the load
                    // was suspended, the job is cancelled — bail before the send
                    // fires, since the rest of this branch runs synchronously
                    // with no further suspension point for cancellation to land.
                    if (!isActive) return@launch
                    if (llmInferenceEngine.isInitialized) {
                        // Clear the transient preparing-model state first so sendMessage's
                        // "already busy" guard does not reject the re-entry; the
                        // resting visual is derived from the live receiver so a
                        // message emission that landed during the load is honoured.
                        _state.update { it.copy(visual = it.restingVisual()) }
                        sendMessage()
                    } else {
                        // Defensive: a reported success that somehow left the
                        // engine uninitialised must not loop back into another
                        // load attempt — surface the error instead.
                        _state.update { it.copy(visual = ChatHomeUiState.Error(NO_ACTIVE_MODEL_MESSAGE)) }
                    }
                }
                is Result.Error ->
                    _state.update {
                        it.copy(visual = ChatHomeUiState.Error(outcome.message ?: NO_ACTIVE_MODEL_MESSAGE))
                    }
            }
        }
    }

    /**
     * Handles the Retry CTA on the chat error tile.
     *
     *  - Engine healthy: the failure was not a model-load problem (e.g. a
     *    generation / tool / provider error), so Retry re-runs **the turn that
     *    failed** — the last user message in the thread, with whatever
     *    attachment it carried. It deliberately does NOT send the composer
     *    text: text typed while reading the error is a different message, and
     *    dispatching it here would both lose the failed turn and send something
     *    the user never pressed Send on. The composer is left untouched.
     *  - Engine cold with a pending draft: this is the model-not-loaded path;
     *    [loadModelThenSend] loads then resends the retained draft. A further
     *    load failure re-shows the Error (typically "no active model
     *    registered" — only Settings can fix that).
     *  - Engine cold with no pending draft: loads the model only.
     */
    fun retryAfterError() {
        if (llmInferenceEngine.isInitialized) {
            retryLastUserTurn()
            return
        }
        val hasDraft = _state.value.composer.value.trim().isNotEmpty() ||
            _state.value.composer.attachment is ComposerAttachmentDraft.Ready
        if (hasDraft) {
            loadModelThenSend()
            return
        }
        _state.update { it.copy(visual = ChatHomeUiState.Generating(preparingModel = true)) }
        modelLoadJob?.cancel()
        modelLoadJob = viewModelScope.launch {
            val outcome = loadModelUseCase()
            // The resting visual is derived from the update receiver, not a
            // pre-computed snapshot — a message emission landing while
            // `loadModelUseCase` was suspended must be reflected here.
            _state.update { current ->
                current.copy(
                    visual = when (outcome) {
                        is Result.Success -> current.restingVisual()
                        is Result.Error -> ChatHomeUiState.Error(outcome.message ?: NO_ACTIVE_MODEL_MESSAGE)
                    },
                )
            }
        }
    }

    /**
     * Re-runs the most recent user turn of the current session.
     *
     * The turn is read back from storage rather than from the composer: the user
     * message is persisted *before* the pipeline runs, so it survives any
     * failure after that point — which is exactly the situation the Retry CTA
     * appears in. Reading the persisted row also recovers the attachment, so
     * retrying an image message retries the image and not just its caption.
     *
     * When there is no user turn to repeat (an empty or agent-only thread), the
     * error is simply cleared — there is nothing to run, and leaving the tile up
     * would strand the screen.
     */
    private fun retryLastUserTurn() {
        val sessionId = _state.value.thread.currentSessionId
        if (sessionId.isBlank()) {
            _state.update { it.copy(visual = it.restingVisual()) }
            return
        }
        viewModelScope.launch {
            val lastUserMessage = chatRepository.getMessagesForSession(sessionId).first()
                .lastOrNull { it.role == Role.USER }
            if (lastUserMessage == null) {
                _state.update { it.copy(visual = it.restingVisual()) }
                return@launch
            }
            // Re-resolve through the same helper `proceedSend` uses so an
            // image-only turn (empty caption) travels with the internal default
            // instruction instead of an empty prompt.
            val content = AttachmentMessageContent.resolve(lastUserMessage.content)
            launchRun(
                sessionId = sessionId,
                prompt = content.prompt,
                displayContent = content.displayContent,
                readyAttachment = lastUserMessage.attachment,
                // The failed attempt already persisted this row; re-running must
                // not add a second copy of the same message to the thread.
                persistUserMessage = false,
            )
        }
    }

    /**
     * Stops the active run: the execution itself, not just the screen's view of
     * it.
     *
     * This used to only detach the collector. The run carried on in the queue's
     * own scope and its answer still landed in the conversation, so a control
     * labelled `stop` did something the word does not mean — the user was told
     * the work had ended while it went on spending steps, tokens and battery.
     *
     * The order matters. The collector and the pending "load then send" are cut
     * first so the surface settles immediately, then the run is cancelled
     * through the queue, which settles it as
     * [app.knotwork.android.domain.models.PipelineRunStatus.CANCELLED] and
     * leaves a line in the conversation saying so.
     *
     * Reached only from the composer's Stop button. Switching threads cancels
     * [generationJob] directly and deliberately does **not** come through here:
     * leaving a chat is not a decision to end its run.
     */
    fun stopGeneration() {
        generationJob?.cancel()
        // Also abort a pending "load model then send" so tapping Stop during
        // the preparing-model phase cancels the queued send instead of letting
        // it fire once the load completes.
        modelLoadJob?.cancel()
        reattach.cancel()
        agentOrchestratorUseCase.cancelRun(_state.value.thread.currentSessionId)
        _state.update { current ->
            // The advisory belongs to the run. Stopping the run ends it — a
            // strip still saying "nearing the step limit" above a composer
            // with nothing running is worse than no strip at all.
            val cleared = current.withPendingCleared().copy(runNotice = null)
            if (current.visual is ChatHomeUiState.Generating) {
                cleared.copy(visual = cleared.restingVisual())
            } else {
                cleared
            }
        }
    }

    /**
     * Switches the active chat session. Persists the selection, cancels
     * any in-flight generation, and re-subscribes the message stream to
     * the newly-selected session.
     *
     * The previous thread's rows are cleared in the same atomic state
     * update that flips the surface to `Empty` and installs the new
     * session id — otherwise [restingVisual] would read stale data from
     * the message list and resolve to `Idle` while the UI is conceptually
     * empty, producing a brief flicker of the outgoing thread's content.
     */
    fun selectThread(threadId: String) {
        if (threadId.isBlank() || threadId == _state.value.thread.currentSessionId) return
        generationJob?.cancel()
        messagesJob?.cancel()
        // Abort any in-flight "load model then send": it captures the session
        // at send time, so leaving it running would deliver the pending message
        // into whatever chat is active when the load finishes (or lose it).
        modelLoadJob?.cancel()
        resetConsoleCachesForNewRun()
        // Per-chat drafts: stash the outgoing chat's unsent text before the
        // switch, then restore the incoming chat's saved draft (empty when it
        // has none). Read/write the map outside `_state.update` — the update
        // lambda may re-run under CAS contention and must stay side-effect free.
        val outgoingSessionId = _state.value.thread.currentSessionId
        if (outgoingSessionId.isNotBlank()) {
            sessionDrafts[outgoingSessionId] = _state.value.composer.value
        }
        val restoredDraft = sessionDrafts[threadId].orEmpty()
        _state.update { current ->
            val cleared = current.withPendingCleared().withConsoleProjectionsCleared()
            threads.sessionMetadataRefreshed(
                cleared.copy(
                    console = cleared.console.copy(snap = null),
                    messages = emptyList(),
                    tokens = cleared.tokens.copy(used = 0),
                    visual = ChatHomeUiState.Empty,
                    thread = cleared.thread.copy(currentSessionId = threadId),
                    composer = cleared.composer.copy(value = restoredDraft),
                    // Belongs to the run in the chat being left. Carried across,
                    // it would advertise a limit on a thread with nothing
                    // running in it.
                    runNotice = null,
                ),
            )
        }
        observeMessages(threadId)
        reattach.reattachToRun(threadId)
        viewModelScope.launch {
            settingsRepository.setCurrentChatSessionId(threadId)
            // The pipelines / sessions flows do not re-emit on a thread
            // switch, so a binding that went stale since their last
            // emission would otherwise reach the task queue silently.
            // Re-running the deleted-binding check here rebinds the chat
            // to the default and surfaces the one-shot Snackbar instead.
            pipelineBinding.handleDeletedBoundPipeline()
        }
    }

    /**
     * Drops the stored per-session draft for [sessionId]. Called by the threads
     * delegate when a session is deleted so its unsent text does not linger in
     * [sessionDrafts] for an id that no longer exists.
     *
     * @param sessionId The chat session whose draft buffer should be discarded.
     */
    fun clearDraft(sessionId: String) {
        sessionDrafts.remove(sessionId)
    }

    /**
     * Debug-only escape hatch used by the triple-tap state picker to force
     * the surface into an arbitrary visual state. The picker UI is gated on
     * `BuildConfig.DEBUG`, but the VM accepts the call in any build so
     * unit tests can drive the state machine deterministically.
     */
    fun forceState(state: ChatHomeUiState) {
        _state.update { it.copy(visual = state) }
    }

    /**
     * Loads or restores the active chat session, mirroring legacy
     * `ChatViewModel.initializeSession`. Either reuses the id persisted
     * in [SettingsRepository.currentChatSessionId] or generates a fresh
     * one and creates an empty [ChatSession] for it.
     */
    private fun initializeSession() {
        viewModelScope.launch {
            // A deep-link entry request (open-thread / new-chat, drained by the
            // CHAT_TAB composable into selectThread / createNewSessionWithPipeline)
            // may set the session before or during this default restore. Never let
            // the persisted default override an explicit selection — otherwise a
            // launcher shortcut to a specific chat would flash the last-active one
            // instead. The selection can land synchronously before this body runs
            // OR during the `currentChatSessionId.first()` suspend below, so the
            // guard is re-checked after every suspension point.
            if (_state.value.thread.currentSessionId.isNotBlank()) return@launch
            val savedSessionId = settingsRepository.currentChatSessionId.first()
            if (_state.value.thread.currentSessionId.isNotBlank()) return@launch
            val sessionId = if (savedSessionId.isNullOrBlank()) {
                val newId = UUID.randomUUID().toString()
                settingsRepository.setCurrentChatSessionId(newId)
                chatRepository.saveSession(
                    ChatSession(
                        id = newId,
                        name = ChatHomeThreadsDelegate.DEFAULT_NEW_CHAT_NAME,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                newId
            } else {
                savedSessionId
            }
            if (_state.value.thread.currentSessionId.isNotBlank()) return@launch
            _state.update {
                threads.sessionMetadataRefreshed(it.copy(thread = it.thread.copy(currentSessionId = sessionId)))
            }
            observeMessages(sessionId)
            reattach.reattachToRun(sessionId)
        }
    }

    /**
     * Observes the installed-model list and mirrors it into the
     * [ChatHomeModelState] slice. Also keeps the model display name in sync
     * with the currently active model so the TopAppBar subtitle always
     * reflects the real inference engine bound to the chat.
     */
    private fun observeInstalledModels() {
        viewModelScope.launch {
            localModelRepository.getAllModels().collect { models ->
                val active = models.firstOrNull { it.isActive }
                _state.update {
                    it.copy(
                        model = ChatHomeModelState(
                            name = active?.name ?: ChatHomeModelState.DEFAULT_NAME,
                            installed = models,
                            activeId = active?.id,
                        ),
                    )
                }
            }
        }
    }

    /** Observes the configured context-window cap and mirrors it into [ChatHomeTokenState.max]. */
    private fun observeMaxContextSize() {
        viewModelScope.launch {
            settingsRepository.maxContextLength.collect { value ->
                _state.update { it.copy(tokens = it.tokens.copy(max = value)) }
            }
        }
    }

    /**
     * Subscribes the message stream to [sessionId], cancelling any prior
     * subscription. Each emission is projected through
     * [chatMessageToRow] and folded into [ChatHomeScreenState.messages];
     * the rough token counter is recomputed *concurrently* via
     * [GetContextWindowUseCase] so that the suspending tokenisation never
     * stalls the collector (otherwise [rebalanceRestingState] would only
     * fire after the use case resumes, gating the UI behind a background
     * computation that could take dozens of milliseconds on a cold session).
     */
    private fun observeMessages(sessionId: String) {
        messagesJob?.cancel()
        tokenCounterJob?.cancel()
        if (sessionId.isBlank()) {
            _state.update { it.copy(messages = emptyList(), tokens = it.tokens.copy(used = 0)) }
            // No session to load — settle Loading to Empty so the surface
            // renders the empty-state hero instead of spinning forever.
            rebalanceRestingState()
            return
        }
        messagesJob = viewModelScope.launch {
            chatRepository.getDisplayMessagesForSession(sessionId).collect { incoming ->
                _state.update { current ->
                    current.copy(
                        messages = incoming.map { message ->
                            val attachment = message.attachment
                            chatMessageToRow(
                                message = message,
                                activeModelName = current.model.name,
                                // Pure path arithmetic (no disk I/O on the main
                                // thread); the thumbnail's own error slot renders
                                // the missing state if the file is gone.
                                attachmentModel = attachment?.let { attachmentStore.absolutePathFor(it.path) },
                                onImageTap = attachment?.let { { attachments.openImageViewer(it) } },
                            )
                        },
                    )
                }
                rebalanceRestingState()
                tokenCounterJob?.cancel()
                tokenCounterJob = launch {
                    val approx = try {
                        getContextWindowUseCase(sessionId).length / TOKEN_CHARS_PER_TOKEN
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        0
                    }
                    _state.update { it.copy(tokens = it.tokens.copy(used = approx)) }
                }
            }
        }
    }

    /**
     * Reacts to the message stream emitting an empty / non-empty list by
     * flipping between [ChatHomeUiState.Empty] and [ChatHomeUiState.Idle].
     * Active overlays (Generating, Error, Drawer, Console, HITL,
     * Clarification) are preserved — the resting state only matters when
     * no overlay is up.
     */
    private fun rebalanceRestingState() {
        _state.update { current ->
            when {
                // Cold-start: first message emission settles Loading into the
                // matching resting state (Empty if no messages, Idle if any).
                current.visual is ChatHomeUiState.Loading ->
                    current.copy(visual = current.restingVisual())
                current.visual is ChatHomeUiState.Empty && current.messages.isNotEmpty() ->
                    current.copy(visual = ChatHomeUiState.Idle)
                current.visual is ChatHomeUiState.Idle && current.messages.isEmpty() ->
                    current.copy(visual = ChatHomeUiState.Empty)
                else -> current
            }
        }
    }

    /** Branches on the orchestrator emission. Terminal states settle UI; intermediate states keep `Generating`. */
    private fun handleOrchestratorState(state: AgentOrchestratorState) {
        when (state) {
            is AgentOrchestratorState.WaitingForApproval -> hitl.handleWaitingForApproval(state)
            is AgentOrchestratorState.AwaitingClarification -> hitl.handleAwaitingClarification(state.request)
            is AgentOrchestratorState.WaitingForCeilingRaise -> hitl.handleCeilingPause(state)
            is AgentOrchestratorState.ConsoleLog -> console.onConsoleLog(state.events, state.runId)
            is AgentOrchestratorState.PipelineTrace -> console.onPipelineTrace(state.steps)
            is AgentOrchestratorState.NodeIO -> console.onNodeIo(state)
            is AgentOrchestratorState.PipelineStage -> {
                // Remembered only to attribute the streamed tokens correctly: while a
                // CLOUD node runs, nothing is decoding on this device, so naming the
                // local backend would tell the user their prompt is being processed
                // on-device when it is not.
                currentNodeType = state.stepInfo.nodeName
            }
            is AgentOrchestratorState.Thinking ->
                // Approximate-token estimate from the cumulative partial text
                // length divided by `TOKEN_CHARS_PER_TOKEN`. Same heuristic
                // we use for the chat-level token meter.
                updateStreamingTokens(state.partialText.length / TOKEN_CHARS_PER_TOKEN)
            is AgentOrchestratorState.Answering ->
                updateStreamingTokens(state.partialText.length / TOKEN_CHARS_PER_TOKEN)
            is AgentOrchestratorState.Completed -> {
                _state.update { current ->
                    current.withPendingCleared().copy(
                        tokens = current.tokens.copy(streaming = 0),
                        visual = current.restingVisual(),
                        // The run finished inside its limits after all. A
                        // warning about a limit it did not reach is noise, so
                        // it leaves no residue.
                        runNotice = null,
                    )
                }
                // Fire-and-forget: distil durable facts from the just-finished
                // conversation into long-term memory. The coordinator debounces
                // and owns its own scope, so this survives ViewModel teardown.
                memoryAutoExtractionCoordinator.onPipelineCompleted(_state.value.thread.currentSessionId)
                // Fire-and-forget likewise: keep a long session's history within
                // the context window by summarising its older tail in the
                // background (same debounce + agent-busy gating as extraction).
                chatHistoryCompressionCoordinator.onPipelineCompleted(_state.value.thread.currentSessionId)
            }
            is AgentOrchestratorState.Error -> {
                _state.update {
                    it.withPendingCleared().copy(
                        tokens = it.tokens.copy(streaming = 0),
                        // The typed cause travels into the visual state so the
                        // surface can render a sentence for it. `state.message`
                        // is the diagnostic when a reason is present, and is
                        // shown verbatim only when it is not.
                        // The run settled, so `PipelineRunRepository.finishRun`
                        // has already written this outcome into the thread. The
                        // tile must not repeat it — see `ChatHomeUiState.Error`.
                        visual = ChatHomeUiState.Error(
                            state.message,
                            reason = state.reason,
                            announcedInThread = true,
                        ),
                        // The run is over; an advisory about how it was going
                        // has no reader once the outcome is on screen.
                        runNotice = null,
                    )
                }
            }
            // The run is still going and has something to say about itself. Not
            // a visual state: it must coexist with whatever the run is doing —
            // generating, or holding a HITL gate open — rather than replace it.
            is AgentOrchestratorState.RunNotice -> {
                _state.update { it.copy(runNotice = state.cause) }
            }
            // Intermediate states keep `Generating` while the request is in flight.
            else -> Unit
        }
    }

    /**
     * Mirrors the running streamed-token estimate into
     * [ChatHomeTokenState.streaming], together with the backend actually
     * decoding those tokens.
     *
     * The backend is read from the engine, not from settings: a load that fell
     * back runs on CPU while the saved preference still reads GPU, and that
     * divergence is precisely what the status line has to expose.
     * Re-reading it per token is a volatile field read, and an
     * unchanged value produces an equal state that `StateFlow` drops.
     */
    private fun updateStreamingTokens(tokens: Int) {
        // A cloud node's tokens are decoded by the provider, not here. Reporting the
        // device's GPU/CPU backend for them would misstate *where the prompt is being
        // processed* — the one thing a local-first app must not get wrong.
        val backend = if (currentNodeType == CLOUD_NODE_TYPE) {
            CLOUD_BACKEND_LABEL
        } else {
            llmInferenceEngine.activeBackend?.key
        }
        _state.update { it.copy(tokens = it.tokens.copy(streaming = tokens, backend = backend)) }
    }

    /**
     * Live branch of the reattach protocol: subscribes the orchestrator-state
     * collector to [sessionId] without enqueueing a task, flips the surface
     * to `Generating` (the run is in flight — the composer must offer Stop,
     * not Send), and restores the HITL / clarification card when the
     * persistent [status] says the run is suspended on one.
     */
    private suspend fun attachToLiveRun(sessionId: String, status: PipelineRunStatus) {
        _state.update { current ->
            if (current.visual.isRestingOrCold()) {
                current.copy(visual = ChatHomeUiState.Generating())
            } else {
                current
            }
        }
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            agentOrchestratorUseCase.observe(sessionId)
                .catch { error ->
                    _state.update {
                        it.copy(visual = ChatHomeUiState.Error(error.message ?: UNKNOWN_ERROR_FALLBACK))
                    }
                }
                .collect { orchestratorState -> handleOrchestratorState(orchestratorState) }
        }
        reattach.restoreSuspensionCard(sessionId, status)
    }

    /**
     * Drops the run-scoped console caches and cancels any in-flight reattach
     * lookup at the start of each new run / thread switch. The console caches
     * (clear baseline, per-node I/O, trace timestamps, replay baseline) live in
     * [console]; the reattach job is a ViewModel concern. Always paired
     * with [withConsoleProjectionsCleared] inside the caller's single
     * `_state.update` block so the flow emission stays atomic.
     *
     * A new run (or a thread switch) invalidates the replayed baseline: the
     * next live snapshot belongs to a different run, and a thread switch reloads
     * its own baseline via [reattach]. The reattach job is cancelled here
     * so a stale in-flight lookup neither re-installs the old baseline nor
     * re-subscribes the collector.
     */
    private fun resetConsoleCachesForNewRun() {
        console.resetForNewRun()
        reattach.cancel()
    }

    companion object {
        /** Fallback message for `Throwable` instances surfaced via [ChatHomeUiState.Error] without a cause. */
        const val UNKNOWN_ERROR_FALLBACK: String = "Unknown error"

        /**
         * User-facing message surfaced when the auto load-and-send path
         * ([loadModelThenSend]) or [retryAfterError] tries to load the model
         * but `LoadModelUseCase` can't find an active one (no models installed,
         * or the registered active model file is missing). At that point only
         * Settings → Models can fix the problem, so the copy redirects there.
         */
        const val NO_ACTIVE_MODEL_MESSAGE: String =
            "No active model. Open Settings → Models to install or activate one."

        /** Rough chars-per-token divisor used for the v0.1 token counter (`text.length / 4`). */
        const val TOKEN_CHARS_PER_TOKEN: Int = 4

        /** `NodeType.CLOUD.name` — matched as a string to keep the UI off the domain enum. */
        private const val CLOUD_NODE_TYPE: String = "CLOUD"

        /**
         * Shown in the status pill instead of a device backend while a cloud node
         * streams. Deliberately generic: the provider id is not available to the UI
         * mid-run, and "cloud" is the fact the user needs — the work left the device.
         */
        private const val CLOUD_BACKEND_LABEL: String = "cloud"

        /** Pre-formatted timestamp format used in [ChatMetadata.timestamp] (24h, locale-aware). */
        private const val TIMESTAMP_PATTERN: String = "HH:mm"

        /**
         * Projects a domain [ChatMessage] onto the design-system row
         * model. Kept on the companion so the mapping can be unit-tested
         * without spinning up the VM.
         */
        fun chatMessageToRow(
            message: ChatMessage,
            activeModelName: String,
            attachmentModel: Any? = null,
            onImageTap: (() -> Unit)? = null,
        ): ChatHomeMessageRow {
            val role = when (message.role) {
                Role.USER -> ChatRole.User
                Role.AGENT -> ChatRole.Assistant
                Role.SYSTEM -> ChatRole.System
            }
            val formattedTimestamp = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.getDefault())
                .format(Date(message.timestamp))
            val metadata = ChatMetadata(
                timestamp = formattedTimestamp,
                // Attribute the answer to the model that actually generated it
                // (snapshotted on the message), not the currently-active one;
                // legacy rows without a recorded model fall back to the active name.
                model = if (role == ChatRole.Assistant) message.modelName ?: activeModelName else null,
                status = ChatMessageStatus.Sent,
            )
            val idPrefix = when (role) {
                ChatRole.User -> "u"
                ChatRole.Assistant -> "a"
                ChatRole.System -> "s"
                ChatRole.Tool -> "t"
            }
            val rowId = message.id?.let { "$idPrefix-$it" } ?: "$idPrefix-${UUID.randomUUID()}"
            // A user message carrying an image renders as an image bubble:
            // thumbnail (aspect-fit) + the caption below (null for image-only,
            // so the bubble shrinks to the thumbnail). Otherwise: agent + tool
            // bubbles can carry markdown (headings / lists / code fences from
            // the LLM); user text stays plain to avoid a stray `#` becoming a
            // heading.
            val attachment = message.attachment
            val content = when {
                role == ChatRole.User && attachment != null -> ChatContent.Image(
                    model = attachmentModel,
                    caption = message.content.ifEmpty { null },
                    aspectRatio = if (attachment.height > 0) {
                        attachment.width.toFloat() / attachment.height
                    } else {
                        1f
                    },
                    onTap = onImageTap,
                )
                role == ChatRole.Assistant || role == ChatRole.Tool -> ChatContent.Markdown(message.content)
                else -> ChatContent.Text(message.content)
            }
            return ChatHomeMessageRow(
                id = rowId,
                role = role,
                content = content,
                metadata = metadata,
            )
        }
    }
}

/**
 * Minimal pipeline summary used by [ChatHomeViewModel] to resolve the
 * TopAppBar subtitle, the deleted-pipeline fallback, and the active pipeline's
 * empty-state starter prompts.
 *
 * @property id stable identifier of the pipeline.
 * @property name display name of the pipeline.
 * @property samplePrompts starter ("quick action") prompts the pipeline
 *   declares for the new-chat empty state (empty when it declares none).
 */
data class PipelineSummary(
    val id: String,
    val name: String,
    val samplePrompts: List<PipelineSamplePrompt> = emptyList(),
)

/**
 * Snapshot of the tool the orchestrator is currently paused on, exposed
 * through [ChatHomePendingState.tool] so the mapper can render the
 * trailing HITL confirmation card from real data instead of fixtures.
 *
 * @property toolName fully-qualified tool id (e.g. `fs.write_file`).
 * @property arguments raw JSON-encoded argument blob emitted by the agent.
 * @property risk per-tool risk tier resolved by `ToolRepository.getRisk`.
 */
data class HitlPending(val toolName: String, val arguments: String, val risk: ToolRisk)

/**
 * Snapshot of the session's interrupted run, exposed through
 * [ChatHomePendingState.interrupted] so the mapping can render the trailing
 * interrupted-run status card (Resume / Discard) from real data.
 *
 * @property runId id of the interrupted persistent run record — the Discard
 *   intent settles exactly this record, never "whatever is interrupted now".
 * @property nodeLabel resolved display label of the node the run stopped at
 *   (falls back to [ChatHomeReattachDelegate.INTERRUPTED_UNKNOWN_NODE_LABEL]).
 * @property timestamp pre-formatted time the run was actually interrupted
 *   (`finishedAt` of the record). Captured once here so the card shows a
 *   stable, truthful time instead of re-deriving "now" on every
 *   recomposition.
 * @property resumable whether the card offers the Resume CTA: `false` when
 *   the interruption is older than the resume window or the record predates
 *   prompt persistence — only Discard remains. The use case re-validates on
 *   tap regardless; this flag just keeps the offered action honest.
 */
data class InterruptedRunPending(
    val runId: String,
    val nodeLabel: String,
    val timestamp: String,
    val resumable: Boolean = true,
)

/**
 * Snapshot of a run paused at one of its own ceilings, exposed through
 * [ChatHomePendingState.ceiling] so the mapping can render the trailing pause
 * card (Continue / Stop) from real data.
 *
 * Carries the breach rather than a rendered sentence: the copy has one owner
 * ([app.knotwork.android.presentation.ui.common.RunTerminationCopyMapper]), and
 * a ViewModel holding resolved strings could not be the same words the
 * notification and the run console use for the same event.
 *
 * @property runId Id of the paused run record — the decision settles exactly
 *   this run, never "whatever is paused now". For a pause raised inside a
 *   sub-pipeline this is the child run, which is where the record sits; the
 *   submission path resolves the tree root itself. `null` on the live path,
 *   where the orchestrator emission carries no id and the decision falls back
 *   to the session's parked record — which is exactly the record the pause just
 *   wrote. Naming the session's *active* run instead would be worse than
 *   naming nothing: for a nested pause the record sits on the child while the
 *   active run is the parent.
 * @property breach Which ceiling bound and by how much, exactly as it stood
 *   when the run stopped. Read off the durable record on reattach, so a pause
 *   answered after a restart states the same numbers it stated live.
 * @property timestamp Pre-formatted time the run actually paused, captured once
 *   here for the reason [InterruptedRunPending.timestamp] is: a pause answered
 *   the next morning must still say when the run stopped, not what time it is
 *   now.
 */
data class CeilingPausePending(val runId: String?, val breach: HardCeilingBreach, val timestamp: String)

/**
 * One-shot failure outcomes of a Resume tap on the interrupted-run card,
 * mapped to snackbar copy by the screen. Modelled as an enum so resource ids
 * stay out of the ViewModel. A successful resume emits no event — the
 * surface flips to `Generating` instead.
 */
enum class ResumeFeedbackEvent {
    /** The pipeline graph was edited or deleted; only a full restart can help. */
    GraphChanged,

    /** The interruption is older than the configured resume window. */
    Expired,

    /** The run is no longer resumable (raced discard/resume, legacy record). */
    NotResumable,
}

/**
 * Maps the domain [ToolRisk] enum onto the catalog [Risk] enum. The two
 * exist in different layers (domain stays free of catalog imports) so a
 * thin adapter at the presentation boundary is the cleanest cut.
 */
internal fun ToolRisk.toCatalogRisk(): Risk = when (this) {
    ToolRisk.READ_ONLY -> Risk.Readonly
    ToolRisk.SENSITIVE -> Risk.Sensitive
    ToolRisk.DESTRUCTIVE -> Risk.Destructive
}

/**
 * Discrete one-shot events raised by the console pane and consumed by the
 * screen-level snackbar host. Modelled as an enum so the screen can map
 * each value to the right localised string in one place (no resource id
 * leaks into the VM).
 */
enum class ConsoleSnackbarEvent {
    /** A single console line was copied to the system clipboard. */
    LineCopied,

    /** The full filtered log was copied to the system clipboard. */
    AllCopied,
}

/**
 * One-shot outcome of the long-press "Save to memory" action, mapped to a
 * snackbar by the screen. Modelled as an enum so resource ids stay out of
 * the ViewModel.
 */
enum class MemorySaveEvent {
    /** The message text was embedded and stored as a manual memory entry. */
    Saved,

    /** Embedding or persistence failed; surface a retry-able failure copy. */
    Failed,
}
