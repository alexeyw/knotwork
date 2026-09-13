package app.knotwork.android.data.engine

import androidx.annotation.VisibleForTesting
import app.knotwork.android.domain.engine.GraphExecutionEngine
import app.knotwork.android.domain.engine.TaskQueueManager
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.AgentTask
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.EngineImageInput
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.ResumeContext
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.models.RunTerminationReason
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.AttachmentStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [TaskQueueManager] that manages agent tasks in a priority queue.
 * Handles task execution via [GraphExecutionEngine] and state tracking.
 * Operations are thread-safe and atomized to prevent race conditions.
 */
@Singleton
class TaskQueueManagerImpl @Inject constructor(
    private val chatRepository: ChatRepository,
    private val pipelineRepository: PipelineRepository,
    private val settingsRepository: SettingsRepository,
    private val graphExecutionEngine: GraphExecutionEngine,
    private val pipelineRunRepository: PipelineRunRepository,
    private val runTraceRepository: RunTraceRepository,
    private val attachmentStore: AttachmentStore,
) : TaskQueueManager {

    @VisibleForTesting
    internal var dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
        set(value) {
            field = value
            scope.cancel()
            scope = CoroutineScope(value + SupervisorJob())
            startWorker()
        }

    internal var scope = CoroutineScope(dispatcher + SupervisorJob())

    /**
     * How long a run may go without emitting anything before the worker gives
     * up on it. See [SILENCE_TIMEOUT_MS].
     *
     * A non-positive value disables the valve. That exists for the integration
     * harnesses that drive real `Dispatchers.IO` work under a virtual clock:
     * `advanceUntilIdle()` skips the whole window forward while the run is in
     * fact progressing on threads the test scheduler cannot see, so there the
     * measurement would be meaningless rather than merely inconvenient.
     */
    @VisibleForTesting
    internal var silenceTimeoutMs: Long = SILENCE_TIMEOUT_MS

    private val _globalState = MutableStateFlow<AgentOrchestratorState>(AgentOrchestratorState.Idle)
    override val globalState: StateFlow<AgentOrchestratorState> = _globalState.asStateFlow()

    // Priority queue for tasks
    /**
     * The task currently executing, or `null` between tasks.
     *
     * The worker is serial, so there is at most one — which is why this is a
     * single reference rather than a map. Held in an [AtomicReference] because
     * it is written by the worker and read by [cancelRun] on whatever thread
     * pressed Stop; the queue's own [queueMutex] is not involved, so a cancel
     * never waits behind a task being polled.
     */
    private val activeRun = AtomicReference<ActiveRun?>(null)

    private val queueMutex = Mutex()
    private val taskQueue = PriorityQueue<AgentTask> { t1, t2 ->
        val p1 = t1.priority.ordinal
        val p2 = t2.priority.ordinal
        if (p1 != p2) p1.compareTo(p2) else t1.timestamp.compareTo(t2.timestamp)
    }

    // Channel to signal new tasks
    private val taskSignal = Channel<Unit>(Channel.CONFLATED)

    private val _activeSessionsState = MutableStateFlow<Map<String, AgentOrchestratorState>>(emptyMap())
    override val activeSessionsState: StateFlow<Map<String, AgentOrchestratorState>> =
        _activeSessionsState.asStateFlow()

    /**
     * Per-session event streams — one [MutableSharedFlow] per active chat
     * session, capped by [MAX_SESSION_STATES] and pruned via
     * [evictOldestTerminalSession]. Modelled as a `SharedFlow` (not the
     * earlier `StateFlow`) so the engine's tight-sequence emits never get
     * conflated: emits like `PipelineTrace` immediately followed by
     * `NodeIO` would previously overwrite each other inside the state
     * flow and only one would reach the chat-home collector — depriving
     * the console pane of every event that wasn't the latest. The
     * `replay = 1` keeps the legacy "latest state on subscription"
     * semantics; `extraBufferCapacity` ([CONSOLE_EVENT_BUFFER_CAPACITY])
     * absorbs the longest engine burst observed in practice (memory
     * retrieval + per-node start/end console events for a 12-node graph
     * fit comfortably).
     */
    @VisibleForTesting
    internal val sessionStates = LinkedHashMap<String, MutableSharedFlow<AgentOrchestratorState>>()

    companion object {
        @VisibleForTesting
        internal const val MAX_SESSION_STATES = 20

        /**
         * Buffer capacity for [sessionStates] entries — large enough to
         * hold every intermediate emit of the longest pipeline run
         * observed in practice without back-pressuring the engine.
         */
        @VisibleForTesting
        internal const val CONSOLE_EVENT_BUFFER_CAPACITY = 256

        /**
         * Longest silence tolerated from a running task before the worker
         * declares it stalled and moves on — the safety valve against
         * a run that goes silent without ever finishing.
         *
         * The worker is a **single serial loop**, so one task that never
         * finishes stops every chat in the app: new messages are accepted,
         * their title is written, and they sit on "Generating…" forever with
         * an empty console. That was observed for 1.5 hours against an MCP
         * server that simply never answered.
         *
         * The window measures *silence*, not total duration, because a long
         * run is not a stalled one: generation streams a state per token, so a
         * legitimately slow run keeps the window open indefinitely while a
         * hung network call trips it immediately. Five minutes clears every
         * legitimate quiet stretch by a wide margin — a live approval gate
         * waits 60 s, MCP and cloud calls are capped at 60 s each — while
         * still reacting long before a person concludes the app is broken.
         *
         * Scope, stated plainly: the valve guards the engine phase of a task.
         * The short prologue before it — resolving the pipeline, writing the
         * user message — is not covered, so a hang in those repository calls
         * would still stop the queue. Nothing in the on-device testing pointed at
         * that path, and widening the guard there would mean failing a task on
         * a database stall it could not explain.
         */
        @VisibleForTesting
        internal const val SILENCE_TIMEOUT_MS = 5 * 60 * 1000L

        /**
         * User-facing explanation written to the run record when
         * [SILENCE_TIMEOUT_MS] elapses. Names the consequence, not the
         * internals: the alternative to this message is the silent
         * "Generating…" that F13 documented.
         */
        @VisibleForTesting
        internal const val STALLED_MESSAGE =
            "The task stopped responding and was ended so other messages can run. " +
                "A step it was waiting on — most often an external tool — never answered."
    }

    /** Raised by [failIfStalled] when a run goes quiet for too long. */
    private class RunStalledException : Exception(STALLED_MESSAGE)

    /**
     * Fails the flow with [RunStalledException] when more than [timeoutMs]
     * passes between two upstream emissions, cancelling the upstream in the
     * process so the worker is free again.
     *
     * Written as an explicit relay rather than `Flow.timeout` because that
     * operator reports the stall as a [kotlinx.coroutines.TimeoutCancellationException];
     * cancellation travels a different path through [executeRun] (re-thrown,
     * killing the worker coroutine) than a failure does, and the queue must
     * settle the run as FAILED and keep going.
     *
     * A slow *collector* can never be mistaken for a stalled *producer*: when
     * the consumer lags, this loop is suspended in `send`, not in the timed
     * receive. The relay itself is a rendezvous channel so the engine still
     * feels back-pressure — though `channelFlow` adds its own bounded buffer
     * downstream, so the engine now runs up to that buffer ahead of the
     * collector rather than in lock-step with it.
     *
     * Silence that follows a **human-wait** state is exempt: a run showing an
     * approval or clarification prompt is not stalled, it is waiting for a
     * person, and that wait is visible in the UI and bounded by the gate's own
     * timeout (after which the run parks durably). The exemption ends at the
     * next emission — the gate emits [AgentOrchestratorState.ExecutingTool]
     * before it runs the approved tool, so the call that follows an approval is
     * guarded again. Without this, a clarification node configured to wait
     * longer than the window would be killed while behaving exactly as
     * configured.
     *
     * @param timeoutMs Maximum silence tolerated between emissions; a
     *   non-positive value returns the receiver unguarded (see [silenceTimeoutMs]).
     * @return The same values as the receiver, or a failure once silence exceeds the window.
     */
    private fun Flow<AgentOrchestratorState>.failIfStalled(timeoutMs: Long): Flow<AgentOrchestratorState> {
        if (timeoutMs <= 0) return this
        return channelFlow {
            val relay = Channel<AgentOrchestratorState>()
            val pump = launch { collect { relay.send(it) } }
            // Close on *every* exit path, carrying the cause: a
            // `CancellationException` from upstream cancels this child only — it
            // does not fail the enclosing channelFlow — so without the cause
            // travelling across the relay a cancelled run would sit in the
            // receive below until the window elapsed and then be misreported as
            // stalled instead of cancelled. `invokeOnCompletion` covers normal
            // completion (null cause), failure and cancellation in one place.
            pump.invokeOnCompletion { cause -> relay.close(cause) }
            var awaitingUser = false
            while (true) {
                val received = if (awaitingUser) {
                    relay.receiveCatching()
                } else {
                    withTimeoutOrNull(timeoutMs) { relay.receiveCatching() } ?: throw RunStalledException()
                }
                if (received.isClosed) {
                    received.exceptionOrNull()?.let { throw it }
                    break
                }
                val state = received.getOrThrow()
                awaitingUser = state is AgentOrchestratorState.WaitingForApproval ||
                    state is AgentOrchestratorState.AwaitingClarification
                send(state)
            }
        }
    }

    private fun updateActiveSessionsState() {
        // Per-session flow is a SharedFlow now; the latest value lives in
        // the replay cache (size 1) rather than under `.value`.
        // `sessionStates` is a plain LinkedHashMap mutated under its own monitor
        // by getOrCreateStateFlow / evictOldestTerminalSession on the worker
        // thread; iterate it under the same monitor so this read (driven from the
        // enqueue path) cannot hit a ConcurrentModificationException.
        val currentState = synchronized(sessionStates) {
            sessionStates.mapValues { entry ->
                entry.value.replayCache.lastOrNull() ?: AgentOrchestratorState.Idle
            }
        }
        _activeSessionsState.value = currentState
    }

    init {
        startWorker()
    }

    internal fun startWorker() {
        scope.launch {
            for (signal in taskSignal) {
                while (true) {
                    val task = queueMutex.withLock {
                        taskQueue.poll()
                    } ?: break // Exit inner loop when queue is empty

                    // Each task runs in its own child job so [cancelRun] can end
                    // one run without taking the worker down with it — and the
                    // worker is the only thing driving every other chat, so
                    // cancelling it would freeze the app rather than stop a run.
                    // `join` (not `await`) because a cancelled child is not a
                    // failure to propagate: the loop must go straight on to the
                    // next task.
                    val job = launch { processTask(task) }
                    activeRun.set(ActiveRun(sessionId = task.sessionId, job = job))
                    job.join()
                    // Compare-and-set, never a bare clear: by the time this runs
                    // the reference may already describe the *next* task if a
                    // cancel raced the handover, and clearing that would leave a
                    // live run nothing could stop.
                    activeRun.compareAndSet(ActiveRun(sessionId = task.sessionId, job = job), null)
                }
            }
        }
    }

    override fun cancelRun(sessionId: String) {
        scope.launch {
            // The queue is drained BEFORE the running job is cancelled, and the
            // order is load-bearing rather than tidy. The worker sits in
            // `job.join()` for as long as that job lives, so draining first
            // happens while it provably cannot poll. Cancelling first and
            // draining after leaves a window: the join returns, the worker takes
            // this session's next task, and the drain then finds a queue that no
            // longer holds it — a Stop that stopped one run and started another.
            val dropped = queueMutex.withLock {
                val matching = taskQueue.filter { it.sessionId == sessionId }
                taskQueue.removeAll(matching.toSet())
                matching
            }
            // A task cancelled before it ever ran still owns a QUEUED record.
            // Left alone it would sit there until the next launch swept it as an
            // orphan and blamed a dead process for something the user did.
            dropped.forEach { task ->
                // The message first, then the outcome: they land in the thread in
                // that order, and a line saying the run was stopped needs the
                // question it answers to be above it.
                persistUserMessage(task)
                pipelineRunRepository.finishRun(task.id, PipelineRunStatus.CANCELLED)
            }

            // Re-read rather than captured before the launch: by now the worker
            // may have moved on to another session, and that run is not ours to
            // end.
            val active = activeRun.get()?.takeIf { it.sessionId == sessionId }
            if (active != null) {
                Timber.d("Cancelling the active run of session %s", sessionId)
                active.job.cancel()
            } else if (dropped.isNotEmpty()) {
                // Only when nothing was running: a cancelled run settles the
                // session itself from `executeRun`'s `finally`, and a second
                // Idle racing that would settle the surface before the run has
                // finished unwinding.
                getOrCreateStateFlow(sessionId).emit(AgentOrchestratorState.Idle)
            }
        }
    }

    /**
     * Writes the task's user message into its chat, unless the task is a re-run
     * whose row already exists.
     *
     * Shared with [cancelRun] rather than living inside the execution path,
     * because a task cancelled while still queued never reaches that path — and
     * the composer has already cleared. Without this the user's text is simply
     * gone, and the line explaining that the run was stopped stands over no
     * question at all.
     *
     * @param task The task whose message should be recorded.
     */
    private suspend fun persistUserMessage(task: AgentTask) {
        // Skipped when the turn is being re-run after a failure: the user row is
        // written before the pipeline starts, so it survived the failed attempt,
        // and writing it again would show the same message twice.
        if (!task.persistUserMessage) return
        chatRepository.saveMessage(
            ChatMessage(
                sessionId = task.sessionId,
                role = Role.USER,
                // For an image-only message `displayContent` is the empty caption so
                // the bubble shows just the thumbnail; `prompt` (the internal default
                // instruction) still travels the graph.
                content = task.displayContent ?: task.prompt,
                timestamp = System.currentTimeMillis(),
                attachment = task.attachment,
            ),
        )
    }

    /**
     * A task being executed, paired with the job driving it.
     *
     * @property sessionId Session the task belongs to.
     * @property job The coroutine running it, cancelled by [cancelRun].
     */
    private data class ActiveRun(val sessionId: String, val job: Job)

    private suspend fun processTask(task: AgentTask) {
        if (task.isResume) {
            processResumeTask(task)
            return
        }
        val stateFlow = getOrCreateStateFlow(task.sessionId)
        val loadingState = AgentOrchestratorState.Loading
        stateFlow.emit(loadingState)
        _globalState.value = loadingState

        // Ensure the persistent QUEUED record exists before any lifecycle
        // UPDATE targets it. `enqueueTask` writes the same record off the
        // enqueue critical path; whichever side lands first wins and the
        // other insert is a conflict-IGNORE no-op, so every interleaving
        // (including the worker overtaking the enqueue coroutine) converges
        // on one consistent row.
        pipelineRunRepository.createRun(task.toQueuedRun())

        // 1. Save user message, carrying any image attachment from the task so
        // it is persisted on the message and rendered in the chat bubble. By
        // contract only the prompt text flows along the pipeline graph.
        //
        // Skipped when the turn is being re-run after a failure: the user row is
        // written before the pipeline starts, so it survived the failed attempt,
        // and writing it again would show the same message twice.
        persistUserMessage(task)

        // 2. Load pipeline. Resolution is a deterministic chain that never
        // depends on the order pipelines come back from the repository:
        //   1. `task.pipelineId` — the session binding captured at enqueue
        //      time, when it still resolves to an existing pipeline;
        //   2. `SettingsRepository.defaultPipelineId` — the user-marked
        //      default, when set and still existing;
        //   3. explicit `Error` — no silent "whatever the DAO returned
        //      first" substitution.
        // A bound pipeline deleted while the task waited in the queue falls
        // through to the default; the chat-level UI handles the rebind +
        // Snackbar notification separately, so the fall-through is never
        // silent from the user's perspective.
        val pipelines = pipelineRepository.getAllPipelines().firstOrNull() ?: emptyList()
        if (pipelines.isEmpty()) {
            val message = "No active pipeline found. Please create one in the Visual Orchestrator."
            pipelineRunRepository.finishRun(task.id, PipelineRunStatus.FAILED, message)
            val errState = AgentOrchestratorState.Error(message)
            stateFlow.emit(errState)
            _globalState.value = errState
            return
        }
        val boundPipeline = task.pipelineId?.let { id -> pipelines.firstOrNull { it.id == id } }
        // The default is resolved lazily — reading the settings flow is a
        // suspend call that a successfully resolved binding never needs.
        val activePipeline = boundPipeline
            ?: settingsRepository.defaultPipelineId.firstOrNull()
                ?.let { id -> pipelines.firstOrNull { it.id == id } }

        if (activePipeline == null) {
            val message = "No default pipeline configured. Set one in Settings or bind a pipeline to this chat."
            pipelineRunRepository.finishRun(task.id, PipelineRunStatus.FAILED, message)
            val errState = AgentOrchestratorState.Error(message)
            stateFlow.emit(errState)
            _globalState.value = errState
            return
        }

        // The run record turns RUNNING the moment the pipeline is resolved —
        // this is also where the (previously unknown) pipeline id and the
        // graph content hash become available and are captured for the
        // checkpoint-invalidation contract.
        pipelineRunRepository.markRunning(task.id, activePipeline.id, activePipeline.contentHash())

        executeRun(task, activePipeline, resume = null)
    }

    /**
     * Resume branch of the worker: drives a checkpoint resume of the
     * interrupted run identified by [AgentTask.id] (flagged via
     * [AgentTask.isResume]). Deliberately skips everything a fresh task does
     * before engine start — the user message is already in the chat history,
     * the QUEUED record already exists (`markResumed` flipped it back), and
     * the pipeline resolves strictly by the run's recorded pipeline id, never
     * through the session-binding → default chain (the run must continue on
     * the exact graph it started with).
     *
     * The graph hash is re-validated here even though
     * `ResumePipelineRunUseCase` already checked it: the user can save a
     * pipeline edit in the window between that validation and this worker
     * picking the task up. On a mismatch the run settles back to INTERRUPTED
     * (a fresh resume attempt will then fail fast with `GraphChanged`), and
     * the session surfaces an explicit error instead of executing a
     * half-valid checkpoint.
     */
    private suspend fun processResumeTask(task: AgentTask) {
        val stateFlow = getOrCreateStateFlow(task.sessionId)
        val loadingState = AgentOrchestratorState.Loading
        stateFlow.emit(loadingState)
        _globalState.value = loadingState

        val run = pipelineRunRepository.getRun(task.id)
        val recordedHash = run?.graphContentHash
        val graph = run?.pipelineId?.let { pipelineRepository.getPipelineById(it) }
        if (run == null || graph == null || recordedHash == null || graph.contentHash() != recordedHash) {
            val message = "Pipeline graph changed before resume could start. Restart the task instead."
            pipelineRunRepository.finishRun(
                task.id,
                PipelineRunStatus.INTERRUPTED,
                message,
                RunTerminationReason.GraphChanged,
            )
            // Same rule as the stall path below: the cause travels to the
            // surface, not only to the record. Dropping it here left a resume
            // the app refused wearing the destructive tile and a Retry, when
            // what the user needs is "run it again" against the current graph.
            val errState = AgentOrchestratorState.Error(message, RunTerminationReason.GraphChanged)
            stateFlow.emit(errState)
            _globalState.value = errState
            return
        }

        // Rebuild the checkpoint from the persisted trace: the seq-ordered
        // NodeIo prefix to replay, the latest memory snapshot (if the
        // interrupted run ever resolved memory), and the first free seq for
        // the records the resumed engine will append.
        val trace = runTraceRepository.getTraceForRun(task.id)
        val resume = ResumeContext(
            records = trace.filterIsInstance<RunTraceRecord.NodeIo>().sortedBy { it.seq },
            memorySnapshot = trace.filterIsInstance<RunTraceRecord.MemorySnapshot>()
                .maxByOrNull { it.seq }
                ?.entries,
            nextSeq = (trace.maxOfOrNull { it.seq } ?: -1L) + 1,
        )

        pipelineRunRepository.markRunning(task.id, graph.id, recordedHash)
        // Carry the persisted image-presence forward: a resumed run never re-delivers the
        // image, but an IF/router node executing live past the resume point must still see it.
        executeRun(task, graph, resume, runHadImage = run.hadImage)
    }

    /**
     * Shared engine-delegation tail of both worker branches: drives the
     * engine over [pipeline], mirrors terminal states into the persistent run
     * record, and resets the session flow in its `finally`. Identical for
     * fresh and resumed runs — the only difference is the [resume] payload
     * forwarded to the engine.
     *
     * @param task The task being processed ([AgentTask.id] is the run id).
     * @param pipeline The resolved graph to execute.
     * @param resume Checkpoint payload for a resumed run, `null` for a fresh one.
     * @param runHadImage Presence-only signal for a resumed run, sourced from the
     *   persisted [PipelineRun.hadImage]. A fresh run leaves it `false` and the engine
     *   derives presence from the live image input instead.
     */
    private suspend fun executeRun(
        task: AgentTask,
        pipeline: PipelineGraph,
        resume: ResumeContext?,
        runHadImage: Boolean = false,
    ) {
        val stateFlow = getOrCreateStateFlow(task.sessionId)
        // Set when the engine parks the run in its persistent waiting phase:
        // the flow then completes without a terminal state on purpose, and the
        // `finally` below must not stamp the record CANCELLED — it stays in
        // its WAITING_* status until the user responds or the approval window
        // expires.
        var runParked = false
        // Resolve the run's image attachment into the engine's shape: absolute
        // path (for `Content.ImageFile`) + on-disk byte size (for the console
        // line). Only for a fresh run with an attachment — a resumed run replays
        // a trace and must never re-deliver the image.
        val imageInput = task.attachment
            ?.takeIf { resume == null }
            ?.let { attachment ->
                EngineImageInput(
                    absolutePath = attachmentStore.absolutePathFor(attachment.path),
                    width = attachment.width,
                    height = attachment.height,
                    sizeBytes = attachmentStore.sizeBytes(attachment.path),
                )
            }
        try {
            graphExecutionEngine(
                task.sessionId,
                task.prompt,
                pipeline,
                task.id,
                resume,
                imageInput = imageInput,
                runHadImage = runHadImage,
                // Carries the surface that started the run into the engine so a
                // background run can pick a meaningful long-term-memory
                // retrieval key instead of its generic authored prompt
                // (DESCRIPTION.md §6.10.1).
                origin = task.origin,
            )
                // Safety valve: the worker is serial, so a run that never
                // emits again would hold every other chat hostage (F13).
                .failIfStalled(silenceTimeoutMs)
                .collect { state ->
                    // Terminal engine states are mirrored into the persistent run
                    // record as they pass through, so the record is already
                    // settled when the in-memory flow reaches its observers.
                    when (state) {
                        is AgentOrchestratorState.Completed ->
                            pipelineRunRepository.finishRun(task.id, PipelineRunStatus.COMPLETED)
                        is AgentOrchestratorState.Error ->
                            // The engine's typed reason travels with the state, so a
                            // protective stop settles as one instead of arriving here
                            // as prose the journal would have to parse back.
                            pipelineRunRepository.finishRun(
                                task.id,
                                PipelineRunStatus.FAILED,
                                state.message,
                                state.reason,
                            )
                        is AgentOrchestratorState.SuspendedInBackground -> runParked = true
                        else -> Unit
                    }
                    // `emit` (vs. `tryEmit`) back-pressures the engine if the
                    // buffer ever fills, so we never silently drop an event.
                    stateFlow.emit(state)
                    _globalState.value = state
                }
        } catch (e: CancellationException) {
            // Cancellation (user Stop, scope teardown) is not a failure:
            // mapping it to `Error` would both surface a false error banner
            // and break cooperative cancellation. Re-throw so the worker
            // coroutine dies with its scope; the `finally` below still
            // resets the session state.
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "Execution failed"
            // The stall watchdog is the one exception the queue itself raises, and
            // it is a forced termination rather than a pipeline defect — typed so
            // it stops being recoverable only by matching its own message text.
            val reason = if (e is RunStalledException) RunTerminationReason.RunStalled else null
            pipelineRunRepository.finishRun(task.id, PipelineRunStatus.FAILED, message, reason)
            // The same reason travels to the surface, not only to the record.
            // Dropping it here left a watchdog kill wearing the destructive
            // error tile and a Retry button that would stall all over again.
            val errState = AgentOrchestratorState.Error(message, reason)
            stateFlow.emit(errState)
            _globalState.value = errState
        } finally {
            // Runs on the cancellation path too, where the coroutine's job
            // is already cancelled — `NonCancellable` lets the suspending
            // `emit` reset the session to `Idle` instead of immediately
            // re-throwing and leaving the UI stuck on a stale state.
            withContext(NonCancellable) {
                val last = stateFlow.replayCache.lastOrNull()
                if (runParked) {
                    // A parked run is neither finished nor cancelled — its
                    // record stays WAITING_* for the background response
                    // path. Only the in-memory session state is reset so the
                    // UI stops showing a live run.
                    stateFlow.emit(AgentOrchestratorState.Idle)
                } else if (last !is AgentOrchestratorState.Completed &&
                    last !is AgentOrchestratorState.Error
                ) {
                    // Terminal write with `finally` semantics: reaching here
                    // without a terminal state means the engine neither
                    // completed nor failed — the task was cancelled (user
                    // Stop / scope teardown). Cancellation is NOT a failure,
                    // so it maps to CANCELLED, and the repository's terminal
                    // guard keeps any already-settled record untouched.
                    pipelineRunRepository.finishRun(task.id, PipelineRunStatus.CANCELLED)
                    stateFlow.emit(AgentOrchestratorState.Idle)
                }
                _globalState.value = AgentOrchestratorState.Idle
            }
        }
    }

    /**
     * Builds the initial QUEUED run record for this task. Shared by
     * [enqueueTask] (off the critical path) and [processTask] (the ensure
     * step) — both inserts are conflict-IGNOREs of the same row.
     *
     * @return The QUEUED [PipelineRun] keyed by the task id.
     */
    private fun AgentTask.toQueuedRun(): PipelineRun = PipelineRun(
        id = id,
        sessionId = sessionId,
        pipelineId = pipelineId,
        origin = origin,
        status = PipelineRunStatus.QUEUED,
        currentNodeId = null,
        startedAt = timestamp,
        finishedAt = null,
        errorMessage = null,
        graphContentHash = null,
        userPrompt = prompt,
        hadImage = attachment != null,
    )

    /**
     * Enqueues a new [AgentTask] for execution.
     * Atomically checks the current session state and adds the task to the queue
     * to prevent race conditions during state updates.
     *
     * A persistent [PipelineRun] record in [PipelineRunStatus.QUEUED] status
     * is written *after* the task is offered and the worker signalled, so the
     * encrypted-DB insert never delays queue entry or the Loading emit. If
     * the worker overtakes this coroutine, [processTask]'s ensure step writes
     * the identical record first and this insert becomes a conflict-IGNORE
     * no-op. The pipeline id stored at this point is the session binding
     * captured in the task (possibly `null`) — the resolved pipeline and
     * graph hash are filled in when processing starts.
     *
     * @param task The task to be executed.
     */
    override fun enqueueTask(task: AgentTask) {
        scope.launch {
            queueMutex.withLock {
                val stateFlow = getOrCreateStateFlow(task.sessionId)
                val last = stateFlow.replayCache.lastOrNull()
                if (last == AgentOrchestratorState.Idle ||
                    last is AgentOrchestratorState.Completed ||
                    last is AgentOrchestratorState.Error
                ) {
                    stateFlow.emit(AgentOrchestratorState.Loading)
                    updateActiveSessionsState()
                }
                taskQueue.offer(task)
            }
            taskSignal.trySend(Unit)
            pipelineRunRepository.createRun(task.toQueuedRun())
        }
    }

    override fun observeTaskState(sessionId: String): Flow<AgentOrchestratorState> =
        getOrCreateStateFlow(sessionId).asSharedFlow()

    override fun resumeWithApproval(sessionId: String, isApproved: Boolean) {
        graphExecutionEngine.resumeWithApproval(sessionId, isApproved)
    }

    override fun pendingApproval(sessionId: String): AgentOrchestratorState.WaitingForApproval? =
        graphExecutionEngine.pendingApprovalFor(sessionId)

    private fun getOrCreateStateFlow(sessionId: String): MutableSharedFlow<AgentOrchestratorState> {
        synchronized(sessionStates) {
            return sessionStates.getOrPut(sessionId) {
                if (sessionStates.size >= MAX_SESSION_STATES) {
                    evictOldestTerminalSession()
                }
                MutableSharedFlow<AgentOrchestratorState>(
                    replay = 1,
                    extraBufferCapacity = CONSOLE_EVENT_BUFFER_CAPACITY,
                ).apply {
                    // Seed the replay cache with `Idle` so subscribers that
                    // attach before the engine emits its first state see
                    // the same initial value the legacy StateFlow used.
                    tryEmit(AgentOrchestratorState.Idle)
                }
            }
        }
    }

    // Evicts the oldest session whose state is terminal (Idle/Completed/Error).
    // If all sessions are still running, no eviction occurs — active flows are never dropped.
    private fun evictOldestTerminalSession() {
        val entry = sessionStates.entries.firstOrNull { (_, flow) ->
            val state = flow.replayCache.lastOrNull()
            state is AgentOrchestratorState.Idle ||
                state is AgentOrchestratorState.Completed ||
                state is AgentOrchestratorState.Error
        }
        entry?.let { sessionStates.remove(it.key) }
    }
}
