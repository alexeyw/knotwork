package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ConsoleEvent
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.repositories.AppStateSettings
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.design.components.console.ConsoleFilter
import app.knotwork.design.components.console.ConsoleSnap
import app.knotwork.design.components.console.ConsoleSource
import app.knotwork.design.components.console.ConsoleTab
import app.knotwork.design.components.console.ConsoleTraceSpan
import app.knotwork.design.components.console.ConsoleVarRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Console-pane delegate of [ChatHomeViewModel].
 *
 * Owns the entire console responsibility extracted from the chat-home
 * God-object: the live stream aggregation ([onConsoleLog] / [onPipelineTrace] /
 * [onNodeIo]), the persisted-trace replay ([replayTrace]), the per-run caches
 * and the console intent surface (open / snap / search / filter / tab / clear /
 * copy). Everything it renders lands in exactly one sub-structure of
 * [ChatHomeScreenState] — the catalog [app.knotwork.design.screens.chat.ChatHomeConsoleState]
 * `console` slice (plus the adjacent [ChatHomeScreenState.consoleClearConfirmRequested]
 * dialog flag) — so the delegate maps cleanly onto one render slice.
 *
 * **Delegation contract.** The delegate shares the ViewModel's [scope]
 * ([androidx.lifecycle.viewModelScope]) and the ViewModel's single
 * [MutableStateFlow] of [ChatHomeScreenState] ([state]) — the latter is the
 * common reducer: every mutation funnels through `state.update { it.copy(...) }`,
 * exactly as it did when the logic lived on the ViewModel, so the screen keeps
 * collecting one `StateFlow<ChatHomeScreenState>` and observable behaviour is
 * unchanged. The ViewModel keeps its public console method surface intact and
 * forwards each call here, so neither the screen nor the existing tests change.
 *
 * @property scope The ViewModel's [androidx.lifecycle.viewModelScope]; cancelled
 *   with the ViewModel so the preferred-tab observer never outlives it.
 * @property state The ViewModel's single source-of-truth state flow, shared as
 *   the common reducer for the `console` slice.
 * @property appStateSettings Persists / hydrates the preferred console tab.
 * @property runTraceRepository Loads the persisted run trace replayed on session
 *   open ([replayTrace]).
 * @property pipelineRunRepository Resolves descendant (sub-pipeline) runs so the
 *   replay rebuilds the nested console hierarchy.
 * @property traceProjectionDispatcher Lazy provider of the dispatcher carrying
 *   the CPU-bound replay projection. A provider (not a value) so the
 *   [ChatHomeViewModel.traceProjectionDispatcher] test seam set after
 *   construction is honoured on every replay.
 */
class ChatHomeConsoleDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatHomeScreenState>,
    private val appStateSettings: AppStateSettings,
    private val runTraceRepository: RunTraceRepository,
    private val pipelineRunRepository: PipelineRunRepository,
    private val traceProjectionDispatcher: () -> CoroutineDispatcher,
) {

    private val _consoleSnackbarEvents: MutableSharedFlow<ConsoleSnackbarEvent> =
        MutableSharedFlow(extraBufferCapacity = 1)

    /**
     * One-shot stream of snackbar events raised by the console pane (line
     * copied, full log copied). The screen mirrors each emission into the
     * shared `SnackbarHostState`; the clipboard write itself is performed
     * by the Composable (which owns `LocalClipboardManager`).
     */
    val consoleSnackbarEvents: SharedFlow<ConsoleSnackbarEvent> = _consoleSnackbarEvents.asSharedFlow()

    /**
     * Number of [ConsoleEvent]s the user has already dismissed via
     * `Clear`. The engine emits cumulative [AgentOrchestratorState.ConsoleLog]
     * snapshots on every step, so the next snapshot post-Clear still
     * carries every previously-visible event; the baseline trims the
     * leading slice so cleared rows do not pop back in. Reset on every new
     * send / session switch (legacy `ChatViewModel.consoleClearBaseline`).
     */
    private var consoleClearBaseline: Int = 0

    /**
     * Per-node ordered map of the latest [AgentOrchestratorState.NodeIO]
     * snapshot for the active run. Kept on the delegate (not in the flow) so
     * repeated emissions for the same node id (e.g. a queue-processor loop
     * revisiting the same body node) overwrite the previous I/O instead of
     * appending duplicate Vars rows.
     */
    private val nodeIoSnapshots: LinkedHashMap<String, AgentOrchestratorState.NodeIO> = LinkedHashMap()

    /** Counts of trace-step landings; used to assign a deterministic startedAt to each span. */
    private val traceStepStartMs: MutableList<Long> = mutableListOf()

    /**
     * Console replay baseline loaded from the persistent run trace when the
     * session opens, or `null` when none is loaded. The run id and its
     * replayed events travel as one value ([ReplayedBaseline]) so they can
     * never desynchronize: a live [AgentOrchestratorState.ConsoleLog]
     * snapshot merges with the events only when it carries the same run id —
     * events of a *different* run replace the baseline outright (a fresh
     * send already cleared it via [resetForNewRun]).
     */
    private var replayedBaseline: ReplayedBaseline? = null

    /**
     * Latest merged (replay baseline + live) console events of the **top-level**
     * run (depth 0). Held separately from [nestedConsoleByRun] so the merged
     * Logs list can be recomputed whenever either the root or a sub-pipeline
     * emits, without re-deriving the root's replay/live seam each time.
     */
    private var rootConsoleEvents: List<ConsoleEvent> = emptyList()

    /**
     * Latest cumulative console events of each **sub-pipeline** run (depth > 0),
     * keyed by child run id. A nested run's [AgentOrchestratorState.ConsoleLog]
     * is forwarded up by `PipelineNodeExecutor` carrying the child run id and a
     * depth-stamped event list; the merged Logs list interleaves these with the
     * root events by timestamp, and each event renders indented by its depth.
     */
    private val nestedConsoleByRun: LinkedHashMap<String, List<ConsoleEvent>> = LinkedHashMap()

    /**
     * Hydrates the console tab from the persisted DataStore preference. An
     * unrecognised value (e.g. an enum entry removed in a future version)
     * falls back to [ConsoleTab.Logs] so the surface never renders an
     * undefined tab. Called once from the ViewModel's `init`, riding the
     * shared [scope].
     */
    fun startObservers() {
        scope.launch {
            appStateSettings.consolePreferredConsoleTabName.collect { name ->
                val tab = ConsoleTab.entries.firstOrNull { it.name == name } ?: ConsoleTab.Logs
                state.update { it.copy(console = it.console.copy(tab = tab)) }
            }
        }
    }

    /**
     * Opens the console pane at the given [snap] (default: Partial). The
     * pane is an independent overlay — the underlying [ChatHomeScreenState.visual]
     * is left untouched, so the user can drill into pipeline activity during
     * Generating / HitlConfirm / Clarification without losing their place.
     */
    fun openConsole(snap: ConsoleSnap = ConsoleSnap.Partial) {
        state.update { it.copy(console = it.console.copy(snap = snap)) }
    }

    /**
     * Updates the snap point of the currently-open console pane. No-op
     * when the pane is closed.
     */
    fun setConsoleSnap(snap: ConsoleSnap) {
        state.update { current ->
            if (current.console.snap != null) {
                current.copy(console = current.console.copy(snap = snap))
            } else {
                current
            }
        }
    }

    /** Dismisses the console pane without touching the underlying chat state. */
    fun closeConsole() {
        state.update { it.copy(console = it.console.copy(snap = null)) }
    }

    /** Toggles the console inline-search field. Cycles `null → "" → null`. */
    fun toggleConsoleSearch() {
        state.update { current ->
            val next = if (current.console.searchQuery == null) "" else null
            current.copy(console = current.console.copy(searchQuery = next))
        }
    }

    /** Updates the console inline-search query while the field is visible. */
    fun onConsoleSearchQueryChange(query: String) {
        state.update { it.copy(console = it.console.copy(searchQuery = query)) }
    }

    /** Replaces the active console source-set filter. */
    fun onConsoleFilterChange(filter: ConsoleFilter) {
        state.update { it.copy(console = it.console.copy(filter = filter)) }
    }

    /** Reacts to the long-press "Only show this source" menu item. */
    fun filterConsoleByLineSource(source: ConsoleSource) {
        state.update { it.copy(console = it.console.copy(filter = ConsoleFilter(sources = setOf(source)))) }
    }

    /**
     * Persists the user's currently-selected console tab. Mirrors the
     * change into the local console state synchronously so the catalog
     * tab strip re-renders without waiting for the DataStore round-trip.
     */
    fun onConsoleTabChange(tab: ConsoleTab) {
        if (state.value.console.tab == tab) return
        state.update { it.copy(console = it.console.copy(tab = tab)) }
        scope.launch {
            appStateSettings.setConsolePreferredConsoleTabName(tab.name)
        }
    }

    /**
     * Requests the destructive "Clear console for this session?" dialog —
     * the screen renders an [androidx.compose.material3.AlertDialog] driven by
     * [ChatHomeScreenState.consoleClearConfirmRequested]. The actual clear
     * runs on [confirmConsoleClear] so the user has a chance to back out.
     */
    fun requestConsoleClear() {
        if (state.value.console.logs.isEmpty()) return
        state.update { it.copy(consoleClearConfirmRequested = true) }
    }

    /** Dismisses the confirmation dialog without altering the log. */
    fun dismissConsoleClear() {
        state.update { it.copy(consoleClearConfirmRequested = false) }
    }

    /**
     * Advances [consoleClearBaseline] by the count of currently-visible
     * lines and clears the console Logs tab. The next cumulative engine
     * snapshot trims its leading slice, so cleared rows do not pop back in.
     */
    fun confirmConsoleClear() {
        val visible = state.value.console.logs
        state.update { it.copy(consoleClearConfirmRequested = false) }
        if (visible.isEmpty()) return
        consoleClearBaseline += visible.size
        state.update { it.copy(console = it.console.copy(logs = emptyList())) }
    }

    /**
     * Emits a one-shot snackbar event after the screen has placed the
     * single-line clipboard payload on the system clipboard. The
     * `ClipboardManager` interaction itself happens inside the Composable
     * layer (which has access to `LocalClipboardManager`).
     */
    fun signalConsoleLineCopied() {
        _consoleSnackbarEvents.tryEmit(ConsoleSnackbarEvent.LineCopied)
    }

    /** One-shot snackbar event raised after the full filtered log is copied. */
    fun signalConsoleAllCopied() {
        _consoleSnackbarEvents.tryEmit(ConsoleSnackbarEvent.AllCopied)
    }

    /**
     * Mirrors a cumulative [AgentOrchestratorState.ConsoleLog.events]
     * snapshot into the console Logs tab. The snapshot is first merged with
     * the replayed baseline of the same run (deduplicated by
     * [ConsoleEvent.seq]), then the clear baseline is applied so events the
     * user just dismissed via [confirmConsoleClear] stay hidden until the
     * next session switch / send.
     *
     * @param events Cumulative console snapshot from the engine.
     * @param runId The persistent run the snapshot belongs to (`null` when none).
     */
    fun onConsoleLog(events: List<ConsoleEvent>, runId: String?) {
        // Each ConsoleLog snapshot belongs to exactly one run, so all of its
        // events share one nesting depth. Depth 0 is the top-level run; a
        // forwarded sub-pipeline snapshot (depth > 0) is bucketed by its child
        // run id and interleaved with the root by timestamp on recompute.
        val depth = events.firstOrNull()?.depth ?: 0
        if (depth == 0) {
            rootConsoleEvents = mergeWithReplayedBaseline(events, runId)
        } else if (runId != null) {
            nestedConsoleByRun[runId] = events
        }
        recomputeConsoleLogs()
    }

    /**
     * Mirrors the latest [AgentOrchestratorState.PipelineTrace.steps]
     * snapshot into the console Traces tab. The catalog span requires a
     * pre-formatted `startedAt` — we observe wall-clock time the first
     * time we see each step index and reuse it for subsequent emissions of
     * the same step so the displayed start does not jitter on every
     * snapshot.
     *
     * @param steps Cumulative top-level trace steps from the engine.
     */
    fun onPipelineTrace(steps: List<AgentOrchestratorState.TraceStep>) {
        val nowMs = System.currentTimeMillis()
        // Grow the start-timestamp cache to match the step count; existing entries are preserved.
        while (traceStepStartMs.size < steps.size) {
            traceStepStartMs.add(nowMs)
        }
        val rootSpans = steps.mapIndexed { index, step ->
            traceStepToConsoleSpan(step, traceStepStartMs[index])
        }
        // A live PipelineTrace carries only the top-level run's steps (a
        // sub-pipeline's trace is not forwarded live — see
        // `PipelineNodeExecutor.forwardIfObservable`). Preserve any nested
        // (depth > 0) spans restored from a replay projection so reattaching to
        // an active nested run does not drop the sub-pipeline span hierarchy;
        // they are re-merged by start time.
        state.update { current ->
            val nested = current.console.traces.filter { it.depth > 0 }
            val merged = (rootSpans + nested).sortedBy { it.startedAt }
            current.copy(console = current.console.copy(traces = merged))
        }
    }

    /**
     * Captures a per-node I/O snapshot and re-projects the console Vars tab
     * from the accumulated map so repeated emissions for the same node id
     * overwrite (not duplicate) the previous Vars rows.
     *
     * @param io The latest per-node I/O snapshot from the engine.
     */
    fun onNodeIo(io: AgentOrchestratorState.NodeIO) {
        nodeIoSnapshots[io.nodeId] = io
        val vars = nodeIoSnapshots.values.flatMap(::nodeIoToVarRows)
        state.update { it.copy(console = it.console.copy(vars = vars)) }
    }

    /**
     * Loads the persisted trace of [run] — resolved once by the ViewModel's
     * reattach protocol — and installs it as the console baseline: Logs from
     * the replayed console events, Vars and Traces re-projected from the
     * replayed per-node I/O records through the exact same mappers as the live
     * path. The CPU-bound projection of the full trace runs on
     * [traceProjectionDispatcher]; only the cache and state installation happen
     * back on the main dispatcher. A run without a persisted trace leaves the
     * console empty, which matches the pre-replay behaviour. Must complete
     * before the live collector subscribes (see the ordering note on
     * [ChatHomeViewModel] reattach).
     *
     * @param run The persisted run whose trace seeds the console baseline.
     */
    suspend fun replayTrace(run: PipelineRun) {
        // Project the whole run tree, not just the top-level run: a finished
        // (or reattached) run with sub-pipelines stored its nested execution in
        // descendant run records, so the console rebuilds the hierarchy by
        // merging each run's persisted trace. Descendant traces ride the same
        // depth-stamped records, so they render indented under their parent.
        val descendants = pipelineRunRepository.getDescendantRuns(run.id)
        val rootTrace = runTraceRepository.getTraceForRun(run.id)
        val childTraces = descendants.map { it.id to runTraceRepository.getTraceForRun(it.id) }
        if (rootTrace.isEmpty() && childTraces.all { it.second.isEmpty() }) return
        val projection = withContext(traceProjectionDispatcher()) {
            val rootEvents = rootTrace
                .filterIsInstance<RunTraceRecord.ConsoleEntry>()
                .map(::consoleEntryToConsoleEvent)
            val nestedEvents = childTraces
                .map { (id, t) ->
                    id to
                        t.filterIsInstance<RunTraceRecord.ConsoleEntry>().map(::consoleEntryToConsoleEvent)
                }
                .filter { it.second.isNotEmpty() }
            // All runs' node I/O, ordered by wall-clock time so the Vars and
            // Traces tabs interleave a sub-pipeline's nodes between the start
            // and end of the PIPELINE node that spawned them.
            val allNodeIo = (rootTrace + childTraces.flatMap { it.second })
                .filterIsInstance<RunTraceRecord.NodeIo>()
                .sortedBy { it.timestamp }
            val snapshots = allNodeIo.map { it.nodeId to nodeIoRecordToNodeIo(it) }
            ReplayProjection(
                baseline = ReplayedBaseline(runId = run.id, events = rootEvents),
                rootEvents = rootEvents,
                nestedConsoleByRun = nestedEvents,
                nodeIoSnapshots = snapshots,
                vars = snapshots.flatMap { (_, io) -> nodeIoToVarRows(io) },
                traces = allNodeIo.map(::nodeIoRecordToConsoleSpan),
            )
        }
        replayedBaseline = projection.baseline
        rootConsoleEvents = projection.rootEvents
        nestedConsoleByRun.clear()
        projection.nestedConsoleByRun.forEach { (id, events) -> nestedConsoleByRun[id] = events }
        nodeIoSnapshots.clear()
        projection.nodeIoSnapshots.forEach { (nodeId, io) -> nodeIoSnapshots[nodeId] = io }
        state.update {
            it.copy(console = it.console.copy(vars = projection.vars, traces = projection.traces))
        }
        // Logs are the merge of root + nested buckets; recompute once both are
        // installed (also applies the clear baseline).
        recomputeConsoleLogs()
    }

    /**
     * Drops the delegate-side console caches (clear baseline, per-node I/O map,
     * trace start timestamps, replay baseline). Called at the start of each new
     * run, always paired with [ChatHomeScreenState.withConsoleProjectionsCleared]
     * inside the caller's single `state.update` block so the flow emission stays
     * atomic.
     *
     * A new run (or a thread switch) invalidates the replayed baseline: the
     * next live snapshot belongs to a different run, and a thread switch reloads
     * its own baseline via the ViewModel's reattach. The reattach job itself is
     * cancelled by the ViewModel (it owns that lifecycle), not here.
     */
    fun resetForNewRun() {
        consoleClearBaseline = 0
        nodeIoSnapshots.clear()
        traceStepStartMs.clear()
        rootConsoleEvents = emptyList()
        nestedConsoleByRun.clear()
        replayedBaseline = null
    }

    /**
     * Recomputes the Logs tab from the top-level run's events plus every
     * sub-pipeline run's events, ordered by wall-clock time (then in-run seq
     * for ties) so a nested run's lines fall between the `▶`/`✓` of the
     * `PIPELINE` node that spawned them, and trimmed by the clear baseline.
     */
    private fun recomputeConsoleLogs() {
        val merged = (rootConsoleEvents + nestedConsoleByRun.values.flatten())
            .sortedWith(compareBy({ it.timestamp }, { it.seq }))
        val trimmed = applyConsoleClearBaseline(merged)
        state.update { it.copy(console = it.console.copy(logs = trimmed.map(ConsoleEvent::toConsoleLine))) }
    }

    /**
     * Merges a live cumulative console snapshot with the replayed baseline
     * via [mergeConsoleEventsBySeq]. When the live snapshot belongs to a
     * different run — or to no persisted run at all — the baseline is
     * irrelevant and the live snapshot passes through untouched.
     *
     * @param live The cumulative live snapshot from the engine.
     * @param liveRunId The persistent run the live snapshot belongs to.
     * @return The merged event list ordered by seq.
     */
    private fun mergeWithReplayedBaseline(live: List<ConsoleEvent>, liveRunId: String?): List<ConsoleEvent> {
        val baseline = replayedBaseline ?: return live
        if (baseline.events.isEmpty() || liveRunId == null || liveRunId != baseline.runId) return live
        return mergeConsoleEventsBySeq(baseline.events, live)
    }

    /**
     * Trims the leading [consoleClearBaseline] entries off a cumulative
     * [AgentOrchestratorState.ConsoleLog.events] snapshot. When the
     * baseline already covers the snapshot (no new events since the last
     * Clear) the result is an empty list.
     */
    private fun applyConsoleClearBaseline(events: List<ConsoleEvent>): List<ConsoleEvent> {
        if (consoleClearBaseline <= 0) return events
        if (consoleClearBaseline >= events.size) return emptyList()
        return events.subList(consoleClearBaseline, events.size)
    }

    /**
     * Console replay baseline of one persisted run: the run id and its
     * replayed console events as a single value, so the merge guard can
     * never see one without the other.
     *
     * @property runId Id of the run whose trace was replayed.
     * @property events The run's replayed console events, ordered by seq.
     */
    private data class ReplayedBaseline(val runId: String, val events: List<ConsoleEvent>)

    /**
     * Result of projecting a persisted run trace into console rows, built
     * off the main dispatcher by [replayTrace] and installed on the
     * main dispatcher in one step.
     *
     * @property baseline The top-level run's replay/live merge baseline.
     * @property rootEvents The top-level run's replayed console events, used to
     *   seed [rootConsoleEvents].
     * @property nestedConsoleByRun Each sub-pipeline run's replayed console
     *   events, keyed by child run id, used to seed [nestedConsoleByRun].
     * @property nodeIoSnapshots Per-node I/O snapshots across the whole run
     *   tree in trace order, ready to seed [nodeIoSnapshots].
     * @property vars Pre-rendered Vars-tab rows (whole tree).
     * @property traces Pre-rendered Traces-tab spans (whole tree, depth-stamped).
     */
    private data class ReplayProjection(
        val baseline: ReplayedBaseline,
        val rootEvents: List<ConsoleEvent>,
        val nestedConsoleByRun: List<Pair<String, List<ConsoleEvent>>>,
        val nodeIoSnapshots: List<Pair<String, AgentOrchestratorState.NodeIO>>,
        val vars: List<ConsoleVarRow>,
        val traces: List<ConsoleTraceSpan>,
    )
}

/**
 * Pure transformer: clears the console Logs / Vars / Traces projections of the
 * previous run. The pane's snap, tab, filter, and search query survive — only
 * run-scoped data is dropped. Composed into the ViewModel's `state.update` block
 * (never its own emission) so multi-field transitions remain a single atomic
 * flow emission, alongside [ChatHomeConsoleDelegate.resetForNewRun] which drops
 * the matching delegate-side caches.
 */
internal fun ChatHomeScreenState.withConsoleProjectionsCleared(): ChatHomeScreenState = copy(
    console = console.copy(
        logs = emptyList(),
        vars = emptyList(),
        traces = emptyList(),
    ),
)

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
