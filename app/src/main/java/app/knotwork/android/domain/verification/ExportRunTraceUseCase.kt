package app.knotwork.android.domain.verification

import app.knotwork.android.domain.models.RunTraceExportDocument
import app.knotwork.android.domain.repositories.PipelineRepository
import javax.inject.Inject

/**
 * Produces the trace export of a finished run: reads the run tree, names its
 * pipelines, computes the run digest and renders the document through
 * [BuildRunTraceExportUseCase].
 *
 * Only a finished run is exported — while it runs, records are still being
 * written and there is no digest yet. A run recorded before runs kept a header is
 * exported too, as records only: no header, no hashes, no digest.
 *
 * **No network on this path.** The document is rendered from the local database
 * and handed to the share sheet or to a file the user picked, both on an explicit
 * tap. Structurally enforced by `RunTraceExportNoNetworkKonsistTest`.
 *
 * @property readRecordedRunTree The run tree and its traces.
 * @property pipelineRepository The pipelines, for their display names.
 * @property buildExport The pure formatter.
 */
class ExportRunTraceUseCase @Inject constructor(
    private val readRecordedRunTree: ReadRecordedRunTreeUseCase,
    private val pipelineRepository: PipelineRepository,
    private val buildExport: BuildRunTraceExportUseCase,
) {

    /**
     * Renders the trace export of root run [rootRunId].
     *
     * @param rootRunId The run the console shows.
     * @param generatedAtLabel Device-local "generated at" label, pre-formatted by
     *   the caller.
     * @return The document, or `null` when there is no such run or it is still going.
     */
    suspend operator fun invoke(rootRunId: String, generatedAtLabel: String): RunTraceExportDocument? {
        val tree = readRecordedRunTree(rootRunId)?.takeIf { it.root.status.isTerminal } ?: return null
        val pipelineNames = tree.runs.values.mapNotNull { it.pipelineId }.distinct()
            .mapNotNull { id -> pipelineRepository.getPipelineById(id)?.let { id to it.name } }
            .toMap()
        val digest = RunDigest.of(tree)
        return RunTraceExportDocument(
            json = buildExport(tree, pipelineNames, digest, generatedAtLabel),
            recordCount = tree.records.size,
            digest = digest,
        )
    }
}
