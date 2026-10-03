package app.knotwork.android.domain.engine.golden

import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunCeilingAxis
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunSpend
import app.knotwork.android.domain.models.RunTerminationReason
import app.knotwork.android.domain.repositories.PipelineRunRepository
import kotlinx.coroutines.flow.Flow

/**
 * In-memory run store of the golden harness that records every write made to run records —
 * by the engine, by the `PIPELINE` executor, by the resume use cases, and by the harness
 * playing the task queue.
 *
 * `updateCurrentNode` is the visit boundary of the trace: the engine calls it before every
 * node, `INPUT` and `OUTPUT` included (which write no `NodeIo`), so it is forwarded to the
 * [GoldenNodeTracker] and recorded as the visit.
 *
 * The store keeps the guards of the production store (`PipelineRunRepositoryImpl` and its
 * DAO), because a lenient fake would pin a write as effective that the app ignores: a terminal
 * status is written once, and status, current-node, `markRunning` and spend writes on a
 * terminal run change nothing. Such a write is still recorded — the call is the engine's
 * behaviour — with `(ignored: terminal)` appended.
 *
 * Methods no run path reaches fail the run as a violation, so a refactoring that starts using
 * one shows up instead of being answered silently.
 *
 * @param log The run's event log.
 * @param tracker Receives the visit boundaries.
 */
internal class GoldenPipelineRunRepository(private val log: GoldenEventLog, private val tracker: GoldenNodeTracker) :
    PipelineRunRepository {

    private val runs = mutableMapOf<String, PipelineRun>()
    private val spend = mutableMapOf<String, RunSpend>()

    override suspend fun createRun(run: PipelineRun) {
        runs[run.id] = run
        log.record(
            "run.create ${run.id} pipeline=${run.pipelineId} parent=${run.parentRunId} status=${run.status} " +
                "origin=${run.origin} hadImage=${run.hadImage}",
            "userPrompt" to run.userPrompt.orEmpty(),
        )
    }

    override suspend fun markRunning(runId: String, pipelineId: String, graphContentHash: String) {
        val applied = updateUnlessTerminal(runId) {
            it.copy(status = PipelineRunStatus.RUNNING, pipelineId = pipelineId, graphContentHash = graphContentHash)
        }
        log.record("run.running $runId pipeline=$pipelineId graphHash=$graphContentHash${suffix(applied)}")
    }

    override suspend fun updateStatus(runId: String, status: PipelineRunStatus) {
        val applied = updateUnlessTerminal(runId) { it.copy(status = status) }
        log.record("run.status $runId $status${suffix(applied)}")
    }

    override suspend fun updateCurrentNode(runId: String, nodeId: String) {
        val position = tracker.enter(runId, nodeId)
        val applied = updateUnlessTerminal(runId) { it.copy(currentNodeId = nodeId) }
        log.record(
            "visit $runId ${position.pipeline.id}/$nodeId ${position.node.type} #${position.visit}${suffix(applied)}",
        )
    }

    override suspend fun finishRun(
        runId: String,
        status: PipelineRunStatus,
        errorMessage: String?,
        reason: RunTerminationReason?,
    ) {
        if (!status.isTerminal) log.violation("finishRun requires a terminal status, got $status")
        val applied = updateUnlessTerminal(runId) { it.copy(status = status, errorMessage = errorMessage) }
        log.record("run.finish $runId $status reason=$reason${suffix(applied)}", "error" to errorMessage.orEmpty())
    }

    override suspend fun recordSpend(rootRunId: String, stepsSpent: Int, tokensSpent: Int) {
        val applied = runs[rootRunId]?.status?.isTerminal != true
        if (applied) spend[rootRunId] = (spend[rootRunId] ?: RunSpend()).copy(steps = stepsSpent, tokens = tokensSpent)
        log.record("run.spend $rootRunId steps=$stepsSpent tokens=$tokensSpent${suffix(applied)}")
    }

    override suspend fun getSpend(rootRunId: String): RunSpend = spend[rootRunId] ?: RunSpend()

    override suspend fun extendCeiling(rootRunId: String, axis: RunCeilingAxis) {
        val current = spend[rootRunId] ?: RunSpend()
        spend[rootRunId] = when (axis) {
            RunCeilingAxis.STEPS -> current.copy(stepCeilingExtensions = current.stepCeilingExtensions + 1)
            RunCeilingAxis.TOKENS -> current.copy(tokenCeilingExtensions = current.tokenCeilingExtensions + 1)
            RunCeilingAxis.MONEY -> log.violation("No golden scenario grants a money ceiling")
        }
        log.record("run.extendCeiling $rootRunId $axis")
    }

    override suspend fun getRun(runId: String): PipelineRun? = runs[runId]

    override suspend fun getRootRunId(runId: String): String? {
        var run = runs[runId] ?: return null
        while (true) {
            val parentId = run.parentRunId ?: return run.id
            run = runs[parentId] ?: return run.id
        }
    }

    override suspend fun markResumed(runId: String, fromStatus: PipelineRunStatus): Boolean {
        val run = runs[runId]
        val applied = run != null && run.status == fromStatus
        if (run != null && applied) runs[runId] = run.copy(status = PipelineRunStatus.QUEUED, errorMessage = null)
        log.record("run.resumed $runId from=$fromStatus applied=$applied")
        return applied
    }

    override suspend fun getDescendantRuns(rootRunId: String): List<PipelineRun> = unused("getDescendantRuns")

    override suspend fun countRootRunsByOriginSince(origin: RunOrigin, sinceEpochMs: Long): Int =
        unused("countRootRunsByOriginSince")

    override suspend fun getActiveRunForSession(sessionId: String): PipelineRun? = unused("getActiveRunForSession")

    override suspend fun getLatestRunForSession(sessionId: String): PipelineRun? = unused("getLatestRunForSession")

    override fun observeRunsForSession(sessionId: String): Flow<List<PipelineRun>> = unused("observeRunsForSession")

    override fun observeActiveRunSessionIds(): Flow<Set<String>> = unused("observeActiveRunSessionIds")

    override suspend fun discardInterruptedRun(runId: String) = unused("discardInterruptedRun")

    override suspend fun getOrphanedRuns(): List<PipelineRun> = unused("getOrphanedRuns")

    override suspend fun applyRetention(keepPerSession: Int, maxAgeCutoffEpochMs: Long): Int = unused("applyRetention")

    /** Applies [change] unless the run is missing or already terminal; returns whether it did. */
    private fun updateUnlessTerminal(runId: String, change: (PipelineRun) -> PipelineRun): Boolean {
        val run = runs[runId] ?: return false
        if (run.status.isTerminal) return false
        runs[runId] = change(run)
        return true
    }

    private fun suffix(applied: Boolean): String = if (applied) "" else " (ignored: terminal or unknown run)"

    private fun unused(method: String): Nothing =
        log.violation("PipelineRunRepository.$method is not on any golden run path; extend the harness deliberately")
}
