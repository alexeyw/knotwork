package app.knotwork.android.domain.models

/**
 * Outcome of parsing a pipeline JSON document produced by the browser-side
 * editor or by another instance of this application.
 *
 * The parsing layer produces this value before any persistence happens, so
 * the UI can decide whether to:
 *
 * - persist immediately ([Success]);
 * - prompt the user with a compatibility warning before persisting
 *   ([SchemaMismatch]) — the file was emitted by a different editor
 *   version, so individual fields may not round-trip cleanly;
 * - surface an error and abort ([Failure]) for malformed JSON or
 *   structurally invalid pipelines.
 *
 * @see app.knotwork.android.domain.pipelineio.PipelineJsonSerializer
 * @see app.knotwork.android.domain.usecases.ImportPipelineUseCase
 */
sealed class PipelineImportOutcome {

    /**
     * The JSON parsed cleanly and its schema version is one this build
     * supports. The graph is ready to be persisted.
     *
     * [droppedFields] is normally empty, but it is **not** guaranteed to be.
     * The format's own rule is that purely additive fields do not bump
     * `schemaVersion` (`samplePrompts` and `memoryRetrievalQuery` were both
     * added that way), so a document written by a *newer build of the same
     * version* can legitimately carry keys this build cannot represent. Those
     * are reported here rather than discarded in silence — which is the whole
     * point: version-based warnings never caught this case.
     *
     * @property graph Fully parsed pipeline ready for `SavePipelineUseCase`.
     * @property droppedFields Dotted paths of keys that were present in the
     *   document and are not representable here, e.g.
     *   `nodes[1].config.samplingTopK`. Empty for a document this build wrote.
     */
    data class Success(val graph: PipelineGraph, val droppedFields: List<String> = emptyList()) :
        PipelineImportOutcome()

    /**
     * The JSON parsed but the `schemaVersion` field does not match the
     * version this build understands. The graph is still produced on a
     * best-effort basis (unknown fields are dropped), but the UI should
     * surface a warning before persisting because some configuration may
     * have been lost.
     *
     * @property graph Best-effort parsed graph.
     * @property foundVersion The `schemaVersion` value read from the file.
     * @property expectedVersion The version this build expects.
     * @property droppedFields Dotted paths of the keys that were actually
     *   lost, so the warning can name them instead of saying "some
     *   configuration may have been stripped" and leaving the user to guess.
     */
    data class SchemaMismatch(
        val graph: PipelineGraph,
        val foundVersion: Int,
        val expectedVersion: Int,
        val droppedFields: List<String> = emptyList(),
    ) : PipelineImportOutcome()

    /**
     * Parsing failed irrecoverably (malformed JSON, missing required
     * fields, unknown `NodeType`, etc.).
     *
     * @property problem Why, for the screen to word in the interface language.
     */
    data class Failure(val problem: ImportProblem) : PipelineImportOutcome()
}
