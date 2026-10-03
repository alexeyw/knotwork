package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.HardCeilingBreach
import app.knotwork.android.domain.models.PendingInteraction
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunBudgetLedger
import app.knotwork.android.domain.models.RunSpend
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.services.CeilingNotifier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps one engine invocation's persistent run record in step with the walk:
 * the node it is on, what the tree has spent, and whether it is waiting for an
 * answer — including a pause on a ceiling, which this class makes durable.
 *
 * The record is what outlives the process. An interrupted run reports the node it
 * stopped on; a parked run resumes by reading back its spend and its WAITING_*
 * status; an answer finds the question it answers in the pending-interaction
 * store. The engine owns only these mid-run writes — the RUNNING transition at
 * the start and every terminal status belong to the task queue (and, for a
 * sub-pipeline, to `PipelineNodeExecutor`).
 *
 * For a run that is not persisted — `runId == null`, an editor test run — every
 * write is skipped and a ceiling cannot park, so the run stops on it instead.
 * The repositories are best-effort by contract: a storage failure never aborts
 * the run.
 *
 * One instance per engine invocation, from [Factory.open]: it carries whether
 * the record currently sits in a WAITING_* status.
 */
class RunRecordWriter private constructor(
    private val stores: Factory,
    private val runId: String?,
    private val sessionId: String,
) {

    /** Whether the record currently sits in a WAITING_* status. */
    private var suspended = false

    /**
     * What the tree had already spent when this attempt started: zero for a fresh
     * run, the previous attempts' total for a resumed one — so a ceiling binds
     * across a park and resume instead of restarting.
     *
     * @return The recorded spend, or none for a run that is not persisted.
     */
    suspend fun spendSoFar(): RunSpend = runId?.let { stores.pipelineRunRepository.getSpend(it) } ?: RunSpend()

    /**
     * Consumes the record of a ceiling question this run parked on, if it is
     * resuming from one.
     *
     * Nothing else will: the other two kinds are consumed by the node executor
     * that raised them, and a ceiling belongs to no node — so it is consumed at
     * the start of the invocation, the one point every attempt of every run in the
     * tree passes through. Leaving it would hand the maintenance sweep a park whose
     * run is happily running again, and the sweep's job is to fail exactly those.
     */
    suspend fun consumeParkedCeiling() {
        val id = runId ?: return
        val parked = stores.pendingInteractionRepository.getForRun(id)
        if (parked?.kind == PendingInteractionKind.CEILING) {
            stores.pendingInteractionRepository.delete(id)
            stores.ceilingNotifier.cancelCeilingNotification(sessionId)
        }
    }

    /**
     * Records the node about to execute, so an interrupted run can report where
     * it stopped.
     *
     * @param nodeId The node's id.
     */
    suspend fun enterNode(nodeId: String) {
        val id = runId ?: return
        stores.pipelineRunRepository.updateCurrentNode(id, nodeId)
    }

    /**
     * Writes the tree's accumulated spend onto the root run record.
     *
     * Called once per executed node, from whatever depth is running. Two things
     * make that cadence the right one rather than an extravagance: the walk
     * already writes to `pipeline_runs` on every node entry ([enterNode]), so this
     * adds no new class of traffic; and the counter has to be exact at every park,
     * because a parked run resumes by reading it back. Nodes are seconds apart —
     * they are LLM calls — so the write is never hot.
     *
     * Losing the write loses accuracy, never the run, and an under-count makes the
     * ceiling bind late rather than early.
     *
     * @param ledger The run tree's spend ledger; its root run id names the record.
     */
    suspend fun recordSpend(ledger: RunBudgetLedger) {
        if (runId == null) return
        val rootId = ledger.rootRunId ?: return
        stores.pipelineRunRepository.recordSpend(
            rootRunId = rootId,
            stepsSpent = ledger.stepsSpent,
            tokensSpent = ledger.tokensSpent,
        )
    }

    /**
     * Makes a ceiling pause durable and, unless the user is already looking at the
     * session, tells them about it.
     *
     * The record is what the pause *is*: the engine coroutine ends immediately
     * after this returns, so from here on the question exists only in the
     * pending-interaction store. It carries the axis and both numbers because the
     * card that asks it has to state the limit **this run** was stopped at — the
     * setting behind that number may well have been edited by the time the answer
     * comes, hours later and in another process. For a sub-pipeline the park sits
     * on the child's record, where the pause happened, while the grant it buys
     * lands on the tree's root, which the submission path resolves.
     *
     * @param ceiling Which ceiling bound, and by how much.
     * @return `true` when the park is durable and the walk may end; `false` when the
     *   run is not persisted or the store refused it — then the run must stop
     *   instead, because a question recorded nowhere would leave it waiting for an
     *   answer no surface could ever offer.
     */
    suspend fun parkOnCeiling(ceiling: HardCeilingBreach): Boolean {
        val id = runId ?: return false
        val saved = stores.pendingInteractionRepository.save(
            PendingInteraction(
                runId = id,
                sessionId = sessionId,
                kind = PendingInteractionKind.CEILING,
                ceilingAxis = ceiling.axis,
                ceilingLimit = ceiling.limit,
                ceilingSpent = ceiling.spent,
                requestedAt = System.currentTimeMillis(),
            ),
        )
        if (saved) {
            stores.ceilingNotifier.sendCeilingPauseRequest(id, sessionId, ceiling)
        }
        return saved
    }

    /**
     * Mirrors a human-in-the-loop suspension, and its end, into the record as the
     * state carrying it is forwarded.
     *
     * [AgentOrchestratorState.WaitingForApproval],
     * [AgentOrchestratorState.AwaitingClarification] and
     * [AgentOrchestratorState.WaitingForCeilingRaise] move the record to the
     * matching WAITING_* status and flush the trace: the run may now wait
     * indefinitely, and the process may die waiting. The first state forwarded
     * *after* a suspension flips it back to [PipelineRunStatus.RUNNING]
     * ([endSuspension] covers executors that emit no state after the answer).
     * Every other state leaves the record untouched.
     *
     * @param state The orchestrator state about to be forwarded downstream.
     */
    suspend fun mirror(state: AgentOrchestratorState) {
        val id = runId ?: return
        suspended = when {
            state is AgentOrchestratorState.WaitingForApproval -> {
                stores.pipelineRunRepository.updateStatus(id, PipelineRunStatus.WAITING_APPROVAL)
                stores.runTraceRepository.flush()
                true
            }
            state is AgentOrchestratorState.AwaitingClarification -> {
                stores.pipelineRunRepository.updateStatus(id, PipelineRunStatus.WAITING_CLARIFICATION)
                stores.runTraceRepository.flush()
                true
            }
            state is AgentOrchestratorState.WaitingForCeilingRaise -> {
                // Reached twice for one pause, and both are wanted. The engine
                // that raised it mirrors it before parking. A *parent* engine
                // reaches it when a sub-pipeline forwards the pause upwards, which
                // is what puts the whole stack into WAITING_CEILING rather than
                // leaving the root RUNNING behind a child that is waiting — the
                // shape of a defect found on device, which made every answer to a
                // nested park bounce off the resume guard.
                stores.pipelineRunRepository.updateStatus(id, PipelineRunStatus.WAITING_CEILING)
                stores.runTraceRepository.flush()
                true
            }
            // Console lines, node-I/O snapshots and run notices are observations
            // *about* the run, not progress of it: they keep arriving while a HITL
            // gate is waiting (a sub-pipeline forwards its child's console traffic
            // upwards). Letting them fall through to the branch below made the
            // first such line read as "the wait ended" and flip the record back to
            // RUNNING while the gate was still open. For a nested pipeline that
            // left the root RUNNING and the child WAITING_APPROVAL, so
            // `ResumePipelineRunUseCase` — which requires a resumable *root* —
            // rejected every attempt to answer the parked notification.
            //
            // A notice is only ever raised just after a node is charged, so today
            // it cannot coincide with an open gate. It is classified here anyway:
            // the guarantee should rest on what the state *means*, not on an
            // ordering argument that a later change could quietly invalidate.
            state is AgentOrchestratorState.ConsoleLog ||
                state is AgentOrchestratorState.NodeIO ||
                state is AgentOrchestratorState.RunNotice -> suspended
            suspended -> {
                stores.pipelineRunRepository.updateStatus(id, PipelineRunStatus.RUNNING)
                false
            }
            else -> false
        }
    }

    /**
     * Ends any suspension the node that has just finished raised. Its executor
     * completing means the wait is over — flipped here rather than on the next
     * forwarded state, because a clarification node, for one, emits no state after
     * its answer arrives, which would leave the record WAITING_* through the next
     * node's model load.
     */
    suspend fun endSuspension() {
        val id = runId ?: return
        if (suspended) {
            stores.pipelineRunRepository.updateStatus(id, PipelineRunStatus.RUNNING)
            suspended = false
        }
    }

    /**
     * Opens a [RunRecordWriter] for each engine invocation; holds the stores they
     * write to.
     *
     * @property pipelineRunRepository The `pipeline_runs` records.
     * @property pendingInteractionRepository Parked questions, the ceiling's included.
     * @property ceilingNotifier Tells the user a run paused on a ceiling.
     * @property runTraceRepository The run trace, flushed when a run starts waiting.
     */
    @Singleton
    class Factory @Inject constructor(
        internal val pipelineRunRepository: PipelineRunRepository,
        internal val pendingInteractionRepository: PendingInteractionRepository,
        internal val ceilingNotifier: CeilingNotifier,
        internal val runTraceRepository: RunTraceRepository,
    ) {
        /**
         * Opens the writer of one invocation.
         *
         * @param runId The run record to keep, or `null` for a run that is not
         *   persisted — then every write is skipped.
         * @param sessionId The chat session the run belongs to.
         * @return A writer with no suspension open.
         */
        fun open(runId: String?, sessionId: String): RunRecordWriter = RunRecordWriter(this, runId, sessionId)
    }
}
