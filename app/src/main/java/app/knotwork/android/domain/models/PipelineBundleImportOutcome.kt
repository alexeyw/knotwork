package app.knotwork.android.domain.models

/**
 * Outcome of parsing a pipeline **bundle** JSON document — a self-contained
 * envelope that carries a root pipeline together with the transitive closure
 * of every sub-pipeline it references through `PIPELINE` nodes.
 *
 * The bundle format is a thin envelope over the single-pipeline schema owned
 * by [app.knotwork.android.domain.pipelineio.PipelineJsonSerializer]; each
 * element of the `pipelines` array is a full single-pipeline document. The
 * envelope adds referential-integrity guarantees the single format cannot: on
 * a clean parse, every `PIPELINE` node's `targetPipelineId` resolves to a
 * pipeline contained in the same file.
 *
 * Mirrors the three-variant shape of [PipelineImportOutcome], adapted for the
 * multi-pipeline case: a schema mismatch is reported per offending pipeline
 * and aggregated, so one out-of-version element does not mask the rest.
 *
 * @see app.knotwork.android.domain.pipelineio.PipelineBundleJsonSerializer
 * @see app.knotwork.android.domain.usecases.ImportPipelineBundleUseCase
 */
sealed class PipelineBundleImportOutcome {

    /**
     * The bundle parsed cleanly: every element matched the current schema
     * version and referential integrity held. The pipelines are ready to be
     * persisted atomically (subject to id-collision resolution).
     *
     * @property pipelines The pipelines contained in the bundle, in file order.
     */
    data class Success(val pipelines: List<PipelineGraph>) : PipelineBundleImportOutcome()

    /**
     * The bundle parsed and referential integrity held, but one or more
     * elements carried a `schemaVersion` different from the version this build
     * understands. The graphs are still produced on a best-effort basis
     * (unknown fields dropped); the UI should warn the user before persisting
     * because some configuration may have been lost.
     *
     * @property pipelines Best-effort parsed pipelines, in file order.
     * @property mismatches One [SchemaMismatch] per element whose version
     *   differed, aggregated so the UI can report the full set at once.
     */
    data class PartialSchemaMismatch(val pipelines: List<PipelineGraph>, val mismatches: List<SchemaMismatch>) :
        PipelineBundleImportOutcome()

    /**
     * Parsing failed irrecoverably: malformed JSON, a missing envelope field,
     * an empty `pipelines` array, a structurally invalid element, duplicate
     * ids, or a dangling `targetPipelineId` reference that no element in the
     * bundle satisfies.
     *
     * @property problem Why, for the screen to word in the interface language.
     */
    data class Failure(val problem: ImportProblem) : PipelineBundleImportOutcome()

    /**
     * A single element's schema-version divergence, carried inside
     * [PartialSchemaMismatch].
     *
     * @property pipelineId The id of the pipeline whose version diverged.
     * @property foundVersion The `schemaVersion` value read from that element.
     * @property expectedVersion The version this build expects.
     */
    data class SchemaMismatch(val pipelineId: String, val foundVersion: Int, val expectedVersion: Int)
}
