package app.knotwork.android.domain.models

/**
 * Persistent record of a single pipeline run.
 *
 * A run is created when an [AgentTask] is enqueued and survives process death:
 * unlike the in-memory orchestrator state flows, this record lets the app
 * reconstruct what happened to a run after Doze, an OOM kill, or a swipe from
 * recents. The record id equals the id of the [AgentTask] that started the
 * run — task and run relate strictly one-to-one, so no second identifier is
 * minted.
 *
 * @property id Unique identifier of the run (UUID). Equal to [AgentTask.id].
 * @property sessionId Id of the chat session the run belongs to.
 * @property pipelineId Id of the pipeline executing the run. `null` while the
 *   run is still [PipelineRunStatus.QUEUED]: the pipeline is resolved (session
 *   binding → application default) only when processing starts, so a queued
 *   run may not have a concrete pipeline yet.
 * @property origin What triggered the run — an interactive chat message or the
 *   background scheduler. See [RunOrigin].
 * @property status Current lifecycle status. See [PipelineRunStatus].
 * @property currentNodeId Id of the graph node currently executing (or the
 *   last node that started). `null` before the first node starts.
 * @property startedAt Epoch millis when the run was enqueued.
 * @property finishedAt Epoch millis when the run reached a terminal status;
 *   `null` while the run is still active.
 * @property errorMessage Why the run did not finish, in text. `null` unless it
 *   ended [PipelineRunStatus.FAILED] or [PipelineRunStatus.INTERRUPTED]. Its
 *   audience depends on [terminationReason]: without one this is an ordinary
 *   failure and the string is the description a person reads, but when the app
 *   itself ended the run this holds the terse **diagnostic** form
 *   (`RunTerminationReason.diagnostic()`), not user copy. Reading it as a
 *   sentence in that case would show somebody `step-ceiling: 15/15 steps`; the
 *   sentence is resolved from [terminationReason] in the presentation layer.
 * @property graphContentHash Content hash of the executing pipeline graph
 *   captured at the moment the run transitioned to
 *   [PipelineRunStatus.RUNNING] (see `PipelineGraph.contentHash`). Used to
 *   invalidate checkpoint resume when the graph was edited between
 *   interruption and resume. `null` while the run is still queued.
 * @property userPrompt The user message that started the run, captured at
 *   enqueue time. Checkpoint resume feeds it back into the engine as the
 *   immutable `originalUserMessage` (context blocks, lazy memory retrieval,
 *   INPUT-node passthrough) — recovering it from chat history would be
 *   ambiguous. `null` only for records written before this field existed;
 *   such runs cannot be resumed.
 * @property parentRunId Id of the parent run when this run is a sub-pipeline
 *   spawned by a `PIPELINE` node; `null` for a top-level run. The parent/child
 *   links form the run tree the nested console, the shared step budget and the
 *   resume-across-boundary mechanism rely on. The root of a tree is reached by
 *   walking [parentRunId] up until it is `null`.
 * @property hadImage `true` when the run's originating message carried an image
 *   attachment. Persisted so a checkpoint resume — which never re-delivers the
 *   image — can still tell presence-only consumers (an IF_CONDITION that branches
 *   on image presence, the INTENT_ROUTER image note) that the run had a picture,
 *   even for nodes that execute live past the resume point. Defaults to `false`.
 * @property stepsSpent Node executions charged to this run **tree** so far,
 *   accumulated across every attempt of the logical run. Meaningful only on a
 *   tree root ([parentRunId] `null`): a child run charges its parent's root, so
 *   a child's own counter stays `0`. Persisted because the ceiling has to
 *   survive a resume — every answered background approval comes back through
 *   `ResumePipelineRunUseCase`, and a per-attempt counter would hand a parking
 *   run a fresh ceiling after every answer.
 * @property tokensSpent Tokens charged to this run tree so far, on the same
 *   root-keyed, resume-surviving basis as [stepsSpent]. A floor rather than an
 *   exact total — see `RunBudgetLedger` for what is and is not charged.
 * @property terminationReason Why the run stopped, when it stopped for a
 *   reason the app itself decided. `null` for runs that completed, for ordinary
 *   node failures, and for rows written before the column existed. The
 *   human-readable rendering lives in [errorMessage]; this is the machine-
 *   readable cause, so consumers no longer have to recover it by comparing
 *   prose. See [RunTerminationKind].
 * @property header The seed, sampler, versions and device the run executes
 *   with — chosen when a root run starts and kept for every later attempt of it.
 *   `null` for a sub-pipeline's record (it runs with its root's header), for a
 *   run not started yet, and for a run recorded before headers existed.
 */
data class PipelineRun(
    val id: String,
    val sessionId: String,
    val pipelineId: String?,
    val origin: RunOrigin,
    val status: PipelineRunStatus,
    val currentNodeId: String?,
    val startedAt: Long,
    val finishedAt: Long?,
    val errorMessage: String?,
    val graphContentHash: String?,
    val userPrompt: String? = null,
    val parentRunId: String? = null,
    val hadImage: Boolean = false,
    val stepsSpent: Int = 0,
    val tokensSpent: Int = 0,
    val terminationReason: RunTerminationKind? = null,
    val header: RunHeader? = null,
)

/**
 * Source that triggered a pipeline run.
 */
enum class RunOrigin {
    /** The run was started by an interactive chat message. */
    CHAT,

    /** The run was started by the background task scheduler. */
    SCHEDULER,

    /**
     * The run was started by an OS share target — the user shared text or an
     * image into the app via `ACTION_SEND`. Behaves like an interactive run
     * (the share activity brings the app to the foreground) but is attributed
     * to the share surface for observability.
     */
    SHARE,

    /**
     * The run was started by the Quick Settings tile — a one-tap launch of the
     * user's duty pipeline from the system shade. Executes in the background
     * (the app may not be in the foreground), so it rides the same WorkManager
     * path as [SCHEDULER] but is attributed to the tile surface.
     */
    QUICK_TILE,

    /**
     * The run was started by a user-defined automation trigger (a schedule, a
     * charging event, or a network/Wi-Fi connection). Executes in the
     * background through the same WorkManager path as [SCHEDULER] / [QUICK_TILE]
     * but is attributed to the trigger surface for observability.
     */
    TRIGGER,

    /**
     * The run was started by a third-party automation app (Tasker, MacroDroid,
     * `adb`) through the external-automation contract. Executes in the
     * background on the same WorkManager path as [SCHEDULER] / [QUICK_TILE] /
     * [TRIGGER], but is attributed to the external surface: it is the only
     * origin whose rate is set by software outside the user's control, so
     * telling it apart from the app's own background work is what makes the
     * external entry point auditable at all.
     */
    EXTERNAL,
}

/**
 * Lifecycle status of a persistent pipeline run.
 *
 * Transitions: [QUEUED] → [RUNNING] →
 * ([WAITING_APPROVAL] | [WAITING_CLARIFICATION] | [WAITING_CEILING] → [RUNNING])*
 * → terminal ([COMPLETED] | [FAILED] | [CANCELLED]). [INTERRUPTED] is the terminal status applied
 * to QUEUED/RUNNING records whose owning process died before the run could finish (orphan sweep
 * at application start). User-initiated cancellation maps to [CANCELLED], never to [FAILED] —
 * stopping a run is not a failure.
 */
enum class PipelineRunStatus {
    /** Enqueued, waiting for the worker to pick the task up. */
    QUEUED,

    /** The execution engine is actively walking the graph. */
    RUNNING,

    /** Suspended on a human-in-the-loop tool approval. */
    WAITING_APPROVAL,

    /** Suspended on a clarification question to the user. */
    WAITING_CLARIFICATION,

    /**
     * The run spent a ceiling and is waiting for the user to say whether it may
     * continue. Resumable like the other waiting states: answering raises the
     * spent axis by one more portion and the run picks up from its checkpoint.
     */
    WAITING_CEILING,

    /** Terminal: the run reached the OUTPUT node and produced a final answer. */
    COMPLETED,

    /** Terminal: the run failed with an error. */
    FAILED,

    /** Terminal: the user cancelled the run. Not a failure. */
    CANCELLED,

    /** Terminal: the owning process died while the run was active. */
    INTERRUPTED,

    ;

    /**
     * Whether this status is terminal — once written, the run record must
     * never transition again (enforced by the repository's guarded updates).
     */
    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == INTERRUPTED
}
