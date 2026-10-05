package app.knotwork.android.domain.verification

import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import javax.inject.Inject

/**
 * Reads a run tree as it was recorded: the root run, its sub-pipeline runs and
 * every run's trace.
 *
 * Reads only. A store that cannot be read answers as its repository does — no
 * run, no descendants, an empty trace — so a reader of the tree sees less, never
 * an error.
 *
 * @property pipelineRunRepository The run records.
 * @property runTraceRepository The runs' traces.
 */
class ReadRecordedRunTreeUseCase @Inject constructor(
    private val pipelineRunRepository: PipelineRunRepository,
    private val runTraceRepository: RunTraceRepository,
) {

    /**
     * Reads the tree of [rootRunId].
     *
     * @param rootRunId The root run.
     * @return The tree, or `null` when no such run exists.
     */
    suspend operator fun invoke(rootRunId: String): RecordedRunTree? {
        val root = pipelineRunRepository.getRun(rootRunId) ?: return null
        val runs = listOf(root) + pipelineRunRepository.getDescendantRuns(root.id)
        return RecordedRunTree(
            root = root,
            runs = runs.associateBy { it.id },
            traces = runs.associate { run -> run.id to runTraceRepository.getTraceForRun(run.id).sortedBy { it.seq } },
        )
    }
}
