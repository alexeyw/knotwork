package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.models.ChatSession
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.EntryPointSettings
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Pipeline-binding delegate of [ChatHomeViewModel].
 *
 * Owns the pipeline-library cache projected into [ChatHomeScreenState.availablePipelines],
 * the resolved TopAppBar subtitle [ChatHomeScreenState.pipelineName], and the
 * deleted-pipeline fallback: when the active chat's binding points at a pipeline
 * that no longer exists, it rebinds to the default and raises the one-shot
 * [pipelineFallbackEvents] Snackbar.
 *
 * Pipeline-name resolution genuinely spans two domains — the session's binding
 * (the session cache, owned by the threads delegate / ViewModel) and the
 * default pipeline (owned here). The thread-metadata refresh therefore composes
 * [pipelineNameRefreshed] into its own atomic state update, and this delegate
 * reads the session cache through the [sessions] provider — keeping a single
 * emission per upstream change without either side owning the other.
 *
 * Shares the ViewModel's [scope] and single [state] reducer (see
 * `docs/architecture.md` §1.2).
 *
 * @property scope The ViewModel's [androidx.lifecycle.viewModelScope].
 * @property state The ViewModel's single source-of-truth state flow.
 * @property entryPointSettings Source of the user-set default pipeline id.
 * @property pipelineRepository Source of the pipeline library.
 * @property chatRepository Rebinds a session to the default when its pipeline is gone.
 * @property pipelineRunRepository Source of the session's runs, used to name the
 *   pipeline that actually produced what is on screen when the session carries
 *   no binding of its own.
 * @property sessions Provider of the live session cache (owned by the ViewModel).
 */
class ChatHomePipelineBindingDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatHomeScreenState>,
    private val entryPointSettings: EntryPointSettings,
    private val pipelineRepository: PipelineRepository,
    private val chatRepository: ChatRepository,
    private val pipelineRunRepository: PipelineRunRepository,
    private val sessions: () -> List<ChatSession>,
) {

    private val _pipelineFallbackEvents: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)

    /**
     * One-shot signal raised when the binding of the active chat points at a
     * pipeline that no longer exists in the library (deleted, or stale at
     * thread-switch time) and the chat is rebound to the default pipeline. The
     * screen surfaces a `KnotworkSnackbar` so the user is told their selection
     * moved — the rebind is never silent.
     */
    val pipelineFallbackEvents: SharedFlow<Unit> = _pipelineFallbackEvents.asSharedFlow()

    private var availablePipelinesObserved: Boolean = false
    private var defaultPipelineId: String? = null

    /**
     * Pipeline of the most recent run of the active session, or `null` when it
     * has never run. Read only for sessions that carry no binding of their own —
     * see [resolveActivePipeline].
     */
    private var lastRunPipelineId: String? = null

    /** Launches the pipeline-library, default-pipeline and session-run observers. Called once from the ViewModel `init`. */
    fun startObservers() {
        observeAvailablePipelines()
        observeDefaultPipelineId()
        observeActiveSessionRuns()
    }

    /**
     * Continuously observes the pipeline library. Used for three things:
     *  1. Caching the available pipelines so the send path can resolve the
     *     bound pipeline name.
     *  2. Recomputing the TopAppBar subtitle ([ChatHomeScreenState.pipelineName]).
     *  3. Detecting deletion of the pipeline bound to the active chat and
     *     rebinding it to the default pipeline, surfacing a one-shot Snackbar
     *     via [pipelineFallbackEvents].
     */
    private fun observeAvailablePipelines() {
        scope.launch {
            pipelineRepository.getAllPipelines().collect { graphs ->
                val summaries = graphs.map {
                    PipelineSummary(id = it.id, name = it.name, samplePrompts = it.samplePrompts)
                }
                state.update { pipelineNameRefreshed(it.copy(availablePipelines = summaries)) }
                availablePipelinesObserved = true
                handleDeletedBoundPipeline(summaries)
            }
        }
    }

    /**
     * Tracks the pipeline of the active session's most recent run.
     *
     * A session created by a trigger or by the scheduler deliberately carries no
     * pipeline binding — `FireTriggerUseCase` documents why: the session is a
     * result log, so a follow-up the user types into it should use the
     * application default rather than a stale copy of whatever the trigger ran.
     * External automation goes further and funnels every request into one fixed
     * session, which therefore cannot have a binding right for all of them.
     *
     * The consequence was a title naming the default pipeline above a
     * conversation produced by a different one. Reading the run rather than
     * rebinding the session fixes what is displayed without touching what runs
     * next — the two really are different questions here.
     *
     * Restarted per session; the runs flow is ordered most-recent-first.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeActiveSessionRuns() {
        scope.launch {
            state
                .map { it.thread.currentSessionId }
                .distinctUntilChanged()
                // Blank is the window before a session is chosen, on every cold
                // start. Querying for it would be a guaranteed-empty read.
                .filter { it.isNotBlank() }
                .flatMapLatest { sessionId ->
                    pipelineRunRepository.observeRunsForSession(sessionId)
                }
                .collect { runs ->
                    lastRunPipelineId = runs.firstOrNull()?.pipelineId
                    state.update { pipelineNameRefreshed(it) }
                }
        }
    }

    /** Observes the user-set default pipeline id and refreshes the subtitle when it changes. */
    private fun observeDefaultPipelineId() {
        scope.launch {
            entryPointSettings.defaultPipelineId.collect { id ->
                defaultPipelineId = id
                state.update { pipelineNameRefreshed(it) }
            }
        }
    }

    /**
     * Rebinds the active chat to the default pipeline when its binding points at
     * a pipeline that no longer exists in the library, and raises the one-shot
     * [pipelineFallbackEvents] Snackbar. Invoked from the pipelines observer
     * (deletion while the chat is active), the sessions observer, and the thread
     * switch (binding already stale when the thread is opened). No-op while the
     * pipeline flow has not produced its initial snapshot — without this guard a
     * startup race would misread the empty initial pipeline list as "the bound
     * pipeline no longer exists" and silently rebind every chat to the default.
     *
     * @param summaries The current pipeline-library snapshot.
     */
    suspend fun handleDeletedBoundPipeline(summaries: List<PipelineSummary>) {
        if (!availablePipelinesObserved) return
        val session = sessions().firstOrNull { it.id == state.value.thread.currentSessionId } ?: return
        val boundId = session.pipelineId ?: return
        if (summaries.any { it.id == boundId }) return
        chatRepository.saveSession(session.copy(pipelineId = null))
        _pipelineFallbackEvents.tryEmit(Unit)
    }

    /** Convenience overload that resolves the deletion check against the current state snapshot. */
    suspend fun handleDeletedBoundPipeline() = handleDeletedBoundPipeline(state.value.availablePipelines)

    /**
     * Pure transformer: recomputes the pipeline-derived projections for [s] —
     * the TopAppBar subtitle ([ChatHomeScreenState.pipelineName]) and the
     * active pipeline's empty-state starter prompts
     * ([ChatHomeScreenState.activeSamplePrompts]) — from the session cache and
     * the default-pipeline binding. Composed into a caller's `state.update`
     * block (never its own emission) so the refresh rides the same atomic
     * emission as the change that triggered it — including the threads
     * delegate's session-metadata refresh.
     *
     * @param s The snapshot to recompute the pipeline projections for.
     * @return [s] with the pipeline subtitle and sample prompts refreshed.
     */
    fun pipelineNameRefreshed(s: ChatHomeScreenState): ChatHomeScreenState {
        val active = resolveActivePipeline(
            sessions = sessions(),
            currentSessionId = s.thread.currentSessionId,
            summaries = s.availablePipelines,
            defaultPipelineId = defaultPipelineId,
        )
        return s.copy(
            pipelineName = active?.name,
            activeSamplePrompts = active?.samplePrompts.orEmpty(),
        )
    }

    /**
     * Returns the active session's pipeline binding (or `null` when the session
     * inherits the default). Surfaced to the screen so the new-thread picker can
     * pre-select the same pipeline as the current chat.
     */
    fun currentPipelineId(): String? =
        sessions().firstOrNull { it.id == state.value.thread.currentSessionId }?.pipelineId

    /**
     * Resolves the pipeline the active chat will actually run — explicit
     * binding when set, otherwise the user-marked default. Returns `null` when
     * neither resolves (empty library, or no default marked): the subtitle and
     * suggestions must not advertise a pipeline that execution would never
     * pick, so there is no order-dependent "first in the library" fallback.
     */
    private fun resolveActivePipeline(
        sessions: List<ChatSession>,
        currentSessionId: String,
        summaries: List<PipelineSummary>,
        defaultPipelineId: String?,
    ): PipelineSummary? {
        if (summaries.isEmpty()) return null
        val session = sessions.firstOrNull { it.id == currentSessionId }
        val boundId = session?.pipelineId
        val boundMatch = boundId?.let { id -> summaries.firstOrNull { it.id == id } }
        // The binding comes first because it is the user's own choice: picking a
        // pipeline opens a NEW chat carrying it, so a bound session's title must
        // never be overruled by a run.
        if (boundMatch != null) return boundMatch
        // Unbound: name the pipeline that produced what is on screen. This is the
        // trigger / scheduler / external-automation case, where the session has
        // no binding by design and the default is not what ran.
        val lastRunMatch = lastRunPipelineId?.let { id -> summaries.firstOrNull { it.id == id } }
        if (lastRunMatch != null) return lastRunMatch
        return defaultPipelineId?.let { id -> summaries.firstOrNull { it.id == id } }
    }
}
