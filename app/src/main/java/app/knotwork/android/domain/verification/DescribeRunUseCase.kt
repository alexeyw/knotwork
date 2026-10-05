package app.knotwork.android.domain.verification

import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.repositories.LocalModelRepository
import javax.inject.Inject

/**
 * Everything the run header shows about one root run: what it ran with, what it
 * promises, its digest, and which of the three actions — check, start again with
 * the seed, export — it allows now.
 *
 * Reads the record only, once: the run tree is read a single time and handed to
 * the check's planner, so the header and the check cannot see two versions of
 * the run.
 *
 * @property readRecordedRunTree The run tree and its traces.
 * @property planRunVerification Whether the run can be checked here now.
 * @property planRunAgainWithSeed Whether the run can be started again with its seed.
 * @property localModelRepository The model registry, for the names of the files the run used.
 */
class DescribeRunUseCase @Inject constructor(
    private val readRecordedRunTree: ReadRecordedRunTreeUseCase,
    private val planRunVerification: PlanRunVerificationUseCase,
    private val planRunAgainWithSeed: PlanRunAgainWithSeedUseCase,
    private val localModelRepository: LocalModelRepository,
) {

    /**
     * Describes root run [rootRunId].
     *
     * @param rootRunId The run the console shows.
     * @return The description, or `null` when no such run exists.
     */
    suspend operator fun invoke(rootRunId: String): RunDescription? {
        val tree = readRecordedRunTree(rootRunId) ?: return null
        val localCalls = tree.records.filterIsInstance<RunTraceRecord.LocalModelCall>()
        val cloudCalls = tree.records.count { it is RunTraceRecord.CloudModelCall }
        return RunDescription(
            run = tree.root,
            models = modelsOf(localCalls),
            localCalls = localCalls.size,
            cloudCalls = cloudCalls,
            digest = RunDigest.of(tree),
            reproducibility = RunReproducibilityPolicy.promise(tree.root.header, localCalls, cloudCalls),
            verify = planRunVerification(tree),
            runAgain = planRunAgainWithSeed(rootRunId),
        )
    }

    /** The distinct model files, backends and windows the run's on-device calls used, in the order first used. */
    private suspend fun modelsOf(calls: List<RunTraceRecord.LocalModelCall>): List<RunModelUse> =
        calls.distinctBy { listOf(it.modelPath, it.modelSha256, it.backend, it.contextWindow) }.map { call ->
            val path = call.modelPath
            RunModelUse(
                name = path?.let { localModelRepository.findByPath(it)?.name ?: it.substringAfterLast('/') },
                sha256 = call.modelSha256,
                backend = call.backend,
                contextWindow = call.contextWindow,
            )
        }
}

/**
 * What the run header shows about one root run.
 *
 * @property run The root run's record; its header is `null` for a run recorded
 *   before runs kept one.
 * @property models Each distinct model file, backend and window the run's
 *   on-device calls used — usually one.
 * @property localCalls How many on-device calls the run tree recorded.
 * @property cloudCalls How many calls the run tree made to cloud models.
 * @property digest The run digest, or `null` while the run is going and for a run
 *   recorded before headers.
 * @property reproducibility What the app promises about repeating the run.
 * @property verify Whether the run can be checked here now.
 * @property runAgain Whether the run can be started again with its seed.
 */
data class RunDescription(
    val run: PipelineRun,
    val models: List<RunModelUse>,
    val localCalls: Int,
    val cloudCalls: Int,
    val digest: String?,
    val reproducibility: Reproducibility,
    val verify: VerifyAvailability,
    val runAgain: RunAgainAvailability,
)

/**
 * One model file a run used on the device, with the backend and window it ran at.
 *
 * @property name The model's display name, its file name when it is no longer
 *   registered, or `null` when the engine reported no file.
 * @property sha256 The file's SHA-256 as recorded with the calls, or `null` when
 *   the file had not been hashed yet when the run used it.
 * @property backend The backend the calls ran on, or `null` when not reported.
 * @property contextWindow The context window in tokens, or `null` when not reported.
 */
data class RunModelUse(val name: String?, val sha256: String?, val backend: LocalBackend?, val contextWindow: Int?)
