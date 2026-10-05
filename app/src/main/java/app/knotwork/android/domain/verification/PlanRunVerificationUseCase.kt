package app.knotwork.android.domain.verification

import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.ModelFileStatus
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.RunHeader
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.models.RunTreeIds
import app.knotwork.android.domain.repositories.GenerationSettings
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Decides whether a finished run can be checked on this device now, and what the
 * check will do.
 *
 * Reads only the record: the run tree's runs, their traces, the model registry
 * and the generation settings. Nothing is loaded and nothing runs — the answer
 * is what the surface shows before the user starts the check, and what the check
 * then executes ([VerifyRunUseCase]). The order of the refusals is the order a
 * person would fix them in: a run still going, a run recorded before seeds, a
 * run with nothing on-device, then the model file, then the settings.
 *
 * @property pipelineRunRepository The run records.
 * @property runTraceRepository The runs' traces.
 * @property localModelRepository The model registry, for the file checksums.
 * @property pipelineRepository The pipelines, for the node labels.
 * @property generationSettings The backend and window the next load would use.
 */
class PlanRunVerificationUseCase @Inject constructor(
    private val pipelineRunRepository: PipelineRunRepository,
    private val runTraceRepository: RunTraceRepository,
    private val localModelRepository: LocalModelRepository,
    private val pipelineRepository: PipelineRepository,
    private val generationSettings: GenerationSettings,
) {

    /**
     * Plans the check of root run [rootRunId].
     *
     * @param rootRunId The run the console shows.
     * @return Whether the check can start, with the plan when it can.
     */
    suspend operator fun invoke(rootRunId: String): VerifyAvailability {
        val root = pipelineRunRepository.getRun(rootRunId)
        return when {
            root == null -> VerifyAvailability.PreVersion
            !root.status.isTerminal -> VerifyAvailability.Busy
            root.header == null -> VerifyAvailability.PreVersion
            else -> availabilityOf(root, root.header)
        }
    }

    /** Whether the finished, recorded run [root] can be checked here now, with the plan when it can. */
    private suspend fun availabilityOf(root: PipelineRun, header: RunHeader): VerifyAvailability {
        val runs = listOf(root) + pipelineRunRepository.getDescendantRuns(root.id)
        val traces = runs.associate { it.id to runTraceRepository.getTraceForRun(it.id) }
        val records = traces.values.flatten()
        if (records.none { it is RunTraceRecord.LocalModelCall }) return VerifyAvailability.NoLocalCalls

        val visits = visitsOf(root, runs.associateBy { it.id }, traces, depth = 0)
        val repeated = visits.flatMap { it.calls }
        val mismatch = mismatchOf(repeated)
        return if (mismatch != null) {
            VerifyAvailability.Mismatch(mismatch)
        } else {
            VerifyAvailability.Available(
                VerificationPlan(
                    rootRunId = root.id,
                    header = header,
                    visits = visits,
                    cloudCalls = records.count { it is RunTraceRecord.CloudModelCall },
                    recordedModelMs = repeated.map { it.durationMs }.takeIf { all -> all.none { it == null } }
                        ?.sumOf { it ?: 0L },
                ),
            )
        }
    }

    /**
     * The visits of [run] in the order the run made them, each `PIPELINE` visit
     * followed by its sub-pipeline's visits one level deeper.
     *
     * A `PIPELINE` node that failed wrote no record of its own, yet the child run
     * it started holds calls the check must not lose: such a visit is recovered
     * from the child's id and listed after the recorded ones.
     */
    private suspend fun visitsOf(
        run: PipelineRun,
        runs: Map<String, PipelineRun>,
        traces: Map<String, List<RunTraceRecord>>,
        depth: Int,
    ): List<PlannedVisit> {
        val labels = run.pipelineId?.let { pipelineRepository.getPipelineById(it) }?.nodes
            ?.associate { it.id to it.label }.orEmpty()
        val trace = traces[run.id].orEmpty()
        val recorded = visitKeysOf(trace)
        val unrecorded = runs.values.filter { it.parentRunId == run.id }
            .mapNotNull { RunTreeIds.parentVisit(it.id, run.id) }
            .filter { (nodeId, visit) -> recorded.none { it.nodeId == nodeId && it.visit == visit } }
            .map { (nodeId, visit) -> VisitKey(nodeId, NodeType.PIPELINE.name, visit) }
            .sortedWith(compareBy({ it.nodeId }, { it.visit }))
        return (recorded + unrecorded).flatMap { key ->
            val visit = plannedVisit(run.id, depth, key, labels[key.nodeId] ?: key.nodeId, trace)
            val child = runs[RunTreeIds.child(run.id, key.nodeId, key.visit)]
            if (visit.kind == VisitKind.SubPipeline && child != null) {
                listOf(visit) + visitsOf(child, runs, traces, depth + 1)
            } else {
                listOf(visit)
            }
        }
    }

    /** The distinct node visits of [trace], ordered by their first record. */
    private fun visitKeysOf(trace: List<RunTraceRecord>): List<VisitKey> = trace.sortedBy { it.seq }.mapNotNull {
        when (it) {
            is RunTraceRecord.NodeIo -> it.visit?.let { visit -> VisitKey(it.nodeId, it.nodeType, visit) }
            is RunTraceRecord.LocalModelCall -> VisitKey(it.nodeId, it.nodeType, it.visit)
            is RunTraceRecord.CloudModelCall -> VisitKey(it.nodeId, it.nodeType, it.visit)
            is RunTraceRecord.ConsoleEntry, is RunTraceRecord.MemorySnapshot -> null
        }
    }.distinctBy { it.nodeId to it.visit }

    /** What the check does with one visit, from the calls the visit recorded. */
    private fun plannedVisit(
        runId: String,
        depth: Int,
        key: VisitKey,
        label: String,
        trace: List<RunTraceRecord>,
    ): PlannedVisit {
        val local = trace.filterIsInstance<RunTraceRecord.LocalModelCall>()
            .filter { it.nodeId == key.nodeId && it.visit == key.visit }
            .sortedBy { it.call }
        val usedCloud = trace.any {
            it is RunTraceRecord.CloudModelCall &&
                it.nodeId == key.nodeId &&
                it.visit == key.visit
        }
        val reason = when {
            key.nodeType == NodeType.PIPELINE.name -> null
            key.nodeType == NodeType.TOOL.name -> NotVerifiableReason.TOOL_NOT_EXECUTED
            usedCloud -> NotVerifiableReason.CLOUD_MODEL
            local.isEmpty() -> NotVerifiableReason.NO_MODEL_CALL
            else -> local.firstNotNullOfOrNull { RunReproducibilityPolicy.notVerifiable(it) }
        }
        val kind = when {
            key.nodeType == NodeType.PIPELINE.name -> VisitKind.SubPipeline
            reason != null -> VisitKind.NotVerifiable(reason)
            else -> VisitKind.Repeat
        }
        return PlannedVisit(
            runId = runId,
            depth = depth,
            nodeId = key.nodeId,
            nodeType = key.nodeType,
            label = label,
            visit = key.visit,
            kind = kind,
            calls = if (kind == VisitKind.Repeat) local else emptyList(),
        )
    }

    /**
     * What stops the [calls] from being repeated here now: the model file, then
     * the backend and window the next load would use.
     */
    private suspend fun mismatchOf(calls: List<RunTraceRecord.LocalModelCall>): VerifyMismatch? {
        for (call in calls.distinctBy { it.modelPath to it.modelSha256 }) {
            val path = call.modelPath ?: continue
            val name = localModelRepository.findByPath(path)?.name ?: path.substringAfterLast('/')
            when (val status = localModelRepository.fileStatus(path)) {
                ModelFileStatus.Missing -> return VerifyMismatch.ModelMissing(name)
                ModelFileStatus.HashPending -> return VerifyMismatch.HashPending(name)
                is ModelFileStatus.Hashed -> if (status.sha256 != call.modelSha256) {
                    return VerifyMismatch.ModelChanged(name)
                }
            }
        }
        val backend = LocalBackend.fromKey(generationSettings.localModelBackend.first()) ?: LocalBackend.CPU
        val window = generationSettings.maxContextLength.first()
        return calls.firstNotNullOfOrNull { settingsMismatch(it, backend, window) }
    }

    /** How [call] differs from the backend and window the next load would use, if it does. */
    private fun settingsMismatch(
        call: RunTraceRecord.LocalModelCall,
        backend: LocalBackend,
        window: Int,
    ): VerifyMismatch? {
        val recordedBackend = call.backend ?: return null
        val recordedWindow = call.contextWindow ?: return null
        val windowDiffers = RunReproducibilityPolicy.windowMatters(call) && recordedWindow != window
        return when {
            recordedBackend != backend && windowDiffers ->
                VerifyMismatch.BackendAndWindow(recordedBackend, recordedWindow, backend)
            recordedBackend != backend -> VerifyMismatch.Backend(recordedBackend, backend)
            windowDiffers -> VerifyMismatch.Window(recordedWindow, window)
            else -> null
        }
    }

    /**
     * One node visit.
     *
     * @property nodeId The node.
     * @property nodeType The node's type name.
     * @property visit The visit index.
     */
    private data class VisitKey(val nodeId: String, val nodeType: String, val visit: Int)
}
