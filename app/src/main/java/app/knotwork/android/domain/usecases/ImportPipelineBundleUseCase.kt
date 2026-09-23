package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.ImportCollisionResolution
import app.knotwork.android.domain.models.PipelineBundleImportOutcome
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineValidationException
import app.knotwork.android.domain.pipelineio.ImportedPipelineClaims
import app.knotwork.android.domain.pipelineio.PipelineBundleIdRemapper
import app.knotwork.android.domain.pipelineio.PipelineBundleJsonSerializer
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.services.PipelineCompositionValidator
import app.knotwork.android.domain.text.toDisplaySafe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject

/**
 * Imports a pipeline **bundle** — a self-contained closure of a root pipeline
 * and every sub-pipeline it references — produced by
 * [ExportPipelineBundleUseCase] or the browser editor.
 *
 * Orchestrated in two steps so the UI can prompt on id collisions before any
 * write touches the database, mirroring the parse/confirm split of
 * [ImportPipelineUseCase]:
 *
 * 1. [prepare] parses the file (delegating to
 *    [PipelineBundleJsonSerializer.parse]), validates every graph
 *    structurally, and detects which pipeline ids already exist in the
 *    library. It performs no write.
 * 2. [persist] applies the chosen [ImportCollisionResolution] and writes the
 *    whole closure **atomically** — a single structurally-broken graph rolls
 *    back the entire import.
 */
class ImportPipelineBundleUseCase @Inject constructor(
    private val pipelineRepository: PipelineRepository,
    private val compositionValidator: PipelineCompositionValidator,
) {

    /**
     * Parses [jsonText], validates each contained graph, and reports which ids
     * collide with the existing library — without persisting anything.
     *
     * @param jsonText Raw bundle JSON.
     * @return [PipelineBundlePrepareResult.Ready] with the pipelines, the
     *   colliding ids, and any schema mismatches; or
     *   [PipelineBundlePrepareResult.Failure] when the file cannot be parsed or
     *   a contained graph is structurally invalid.
     */
    suspend fun prepare(jsonText: String): PipelineBundlePrepareResult {
        val (parsed, mismatches) = when (val outcome = PipelineBundleJsonSerializer.parse(jsonText)) {
            is PipelineBundleImportOutcome.Failure ->
                return PipelineBundlePrepareResult.Failure(outcome.message)

            is PipelineBundleImportOutcome.Success -> outcome.pipelines to emptyList()

            is PipelineBundleImportOutcome.PartialSchemaMismatch -> outcome.pipelines to outcome.mismatches
        }
        val pipelines = parsed.map(ImportedPipelineClaims::checked)

        // Per-graph structural validation, then cross-pipeline composition
        // validation resolved against the incoming set (so intra-bundle cycles,
        // self-references, over-depth nesting, and cross-library cycles a
        // REPLACE would splice in are all rejected here — the same authoritative
        // defence a single-pipeline save gets through SavePipelineUseCase). One
        // broken graph aborts the whole import (atomic, observable) rather than
        // writing a partial closure.
        val bundleById = pipelines.associateBy { it.id }
        pipelines.firstNotNullOfOrNull { graph ->
            val errors = graph.validate() + compositionValidator.validate(graph, extraResolvable = bundleById)
            errors.takeIf { it.isNotEmpty() }?.let { graph to it }
        }?.let { (graph, errors) ->
            return PipelineBundlePrepareResult.Failure(
                "Pipeline \"${graph.name.toDisplaySafe()}\" is invalid: " +
                    PipelineValidationException(errors).message,
            )
        }

        // Lightweight existence check: id → name projection, one query, no graph
        // materialisation (vs. getPipelineById per id, which loads full graphs).
        val existingIds = pipelineRepository.observePipelineNames().first().keys
        val collidingIds = pipelines.map { it.id }.filter { it in existingIds }

        return PipelineBundlePrepareResult.Ready(
            pipelines = pipelines,
            collidingIds = collidingIds,
            schemaMismatches = mismatches,
        )
    }

    /**
     * Atomically persists [pipelines] under the chosen [resolution].
     *
     * [ImportCollisionResolution.REPLACE] keeps the ids and overwrites in
     * place; [ImportCollisionResolution.IMPORT_AS_COPY] regenerates every id
     * and remaps intra-bundle references through [PipelineBundleIdRemapper]
     * before saving, leaving existing pipelines untouched.
     *
     * @param pipelines The closure to persist (as produced by [prepare]).
     * @param resolution How to treat ids that collide with the library.
     * @return [Result.success] with the pipelines actually written (ids as
     *   persisted — regenerated under copy), or [Result.failure] if the atomic
     *   save fails.
     */
    suspend fun persist(
        pipelines: List<PipelineGraph>,
        resolution: ImportCollisionResolution,
    ): Result<List<PipelineGraph>> {
        val newId = { _: String -> UUID.randomUUID().toString() }
        val toSave = when (resolution) {
            // Keep pipeline ids (sync semantics) but always freshen node /
            // connection ids: they are globally-unique primary keys, so two
            // bundle pipelines reusing a node id would otherwise collapse under
            // the multi-save's REPLACE conflict strategy.
            ImportCollisionResolution.REPLACE -> PipelineBundleIdRemapper.freshenElementIds(pipelines, newId)
            // Regenerate every id (pipeline + node + connection) and remap
            // intra-bundle references, so the copy is fully self-consistent.
            ImportCollisionResolution.IMPORT_AS_COPY -> PipelineBundleIdRemapper.regenerate(pipelines, newId)
        }

        return try {
            pipelineRepository.savePipelines(toSave)
            Result.success(toSave)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Result of the non-persisting [ImportPipelineBundleUseCase.prepare] step.
 */
sealed class PipelineBundlePrepareResult {

    /**
     * The bundle parsed and every graph validated; the import can proceed once
     * the caller picks a collision resolution.
     *
     * @property pipelines The pipelines to persist, in file order.
     * @property collidingIds The subset of pipeline ids that already exist in
     *   the library. Empty means no dialog is needed and the import may persist
     *   straight away with [ImportCollisionResolution.REPLACE].
     * @property schemaMismatches Per-pipeline schema-version divergences to
     *   surface as a compatibility warning, if any.
     */
    data class Ready(
        val pipelines: List<PipelineGraph>,
        val collidingIds: List<String>,
        val schemaMismatches: List<PipelineBundleImportOutcome.SchemaMismatch>,
    ) : PipelineBundlePrepareResult()

    /**
     * The bundle could not be parsed, or a contained graph was structurally
     * invalid. Nothing was written.
     *
     * @property message Human-readable failure description for the UI.
     */
    data class Failure(val message: String) : PipelineBundlePrepareResult()
}
