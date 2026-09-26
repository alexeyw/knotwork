package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.ImportCollisionResolution
import app.knotwork.android.domain.models.PipelineCollision
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineImportOutcome
import app.knotwork.android.domain.pipelineio.ImportedPipelineClaims
import app.knotwork.android.domain.pipelineio.PipelineBundleIdRemapper
import app.knotwork.android.domain.pipelineio.PipelineJsonSerializer
import app.knotwork.android.domain.repositories.PipelineRepository
import java.util.UUID
import javax.inject.Inject

/**
 * Imports a pipeline from a JSON document produced by the browser-side
 * editor (`pipeline-editor.html`) or by another instance of this app.
 *
 * Two-step orchestration:
 *
 * 1. The JSON is parsed by [PipelineJsonSerializer.parse], which surfaces
 *    one of [PipelineImportOutcome.Success] / [PipelineImportOutcome.SchemaMismatch]
 *    / [PipelineImportOutcome.Failure]; what the file claims about itself is
 *    then checked against its graph ([ImportedPipelineClaims]).
 * 2. On a clean [PipelineImportOutcome.Success] this use case checks whether
 *    the imported graph's id is already taken — it names a saved pipeline, or a
 *    deleted pipeline's bindings still name it. If it is **not**,
 *    the graph is persisted immediately through [SavePipelineUseCase]. If it
 *    **does**, nothing is written — [ImportInvocation.pendingCollision] carries
 *    the graph so the UI can prompt the user (Replace / Import as copy /
 *    Cancel) and then call [persistWithResolution]. This closes the previous
 *    silent-overwrite behaviour where a colliding id clobbered the existing
 *    pipeline without warning. On [PipelineImportOutcome.SchemaMismatch] we
 *    still defer to [persistConfirmed] after the compatibility warning.
 *
 * **A collision is described by the pipeline already there.** The prompt is
 * built from a [PipelineCollision]: the library pipeline's own name (the
 * file's `name` is its author's to choose, and naming it made the prompt claim
 * the library held a pipeline it did not) and everything bound to the shared
 * id ([FindPipelineBindingsUseCase]). Replace keeps the id on purpose, so the
 * default-pipeline setting, entry surfaces, triggers, chats and calling
 * pipelines all run the imported graph afterwards — the prompt lists them so
 * the user agrees to that knowingly.
 *
 * **Every save reports the graph it wrote.** Node and connection ids are
 * freshened before saving (below), so the graph in storage differs from the
 * parsed one; the editor must continue from the stored one, or its next Save
 * writes the file's ids again and reopens the hole the freshening closes.
 *
 * Splitting parse from persist keeps the use case testable without a
 * fake `Activity` and lets the UI display a confirm-dialog before any
 * mutation hits the database.
 *
 * **Node/connection ids are always freshened before persistence.** The
 * browser editor and every shipped preset number nodes `node-1`, `node-2`, …
 * independently per pipeline, but [app.knotwork.android.data.local.models.NodeEntity]
 * / [app.knotwork.android.data.local.models.ConnectionEntity] use a **global**
 * primary key. Persisting an imported graph verbatim therefore lets its
 * `node-N` ids collide with an unrelated pipeline that already owns those ids,
 * and `OnConflictStrategy.REPLACE` silently reassigns the existing rows to the
 * import — emptying the other pipeline's graph. Running every keep-id save path
 * through [PipelineBundleIdRemapper.freshenElementIds] (pipeline id preserved,
 * node/connection ids regenerated) closes this data-loss hole, mirroring the
 * identical guard the bundle-import path already applies.
 */
class ImportPipelineUseCase @Inject constructor(
    private val savePipelineUseCase: SavePipelineUseCase,
    private val pipelineRepository: PipelineRepository,
    private val findPipelineBindings: FindPipelineBindingsUseCase,
) {

    /**
     * Parses [jsonText] and, if it cleanly matches the current schema and does
     * not collide with an existing pipeline id, persists the resulting graph
     * through [SavePipelineUseCase].
     *
     * For a colliding clean [PipelineImportOutcome.Success] no write happens —
     * the graph is returned in [ImportInvocation.pendingCollision] for the UI
     * to resolve. For every other outcome (`SchemaMismatch` / `Failure`) no
     * write happens either — see [persistConfirmed] for the mismatch path.
     *
     * @return the [ImportInvocation] the UI should render.
     */
    suspend operator fun invoke(jsonText: String): ImportInvocation {
        // Checked once, here, so every later step — the collision prompt, the
        // mismatch confirmation, both resolutions — works on the checked graph.
        val outcome = when (val parsed = PipelineJsonSerializer.parse(jsonText)) {
            is PipelineImportOutcome.Success -> parsed.copy(graph = ImportedPipelineClaims.checked(parsed.graph))
            is PipelineImportOutcome.SchemaMismatch -> parsed.copy(graph = ImportedPipelineClaims.checked(parsed.graph))
            is PipelineImportOutcome.Failure -> parsed
        }
        if (outcome !is PipelineImportOutcome.Success) {
            return ImportInvocation(outcome = outcome, saveResult = null)
        }

        val collision = collisionOf(outcome.graph)
        return if (collision != null) {
            ImportInvocation(outcome = outcome, saveResult = null, pendingCollision = collision)
        } else {
            ImportInvocation(outcome = outcome, saveResult = save(freshenElementIds(outcome.graph)))
        }
    }

    /**
     * Persists [outcome.graph] after the user has explicitly accepted the
     * compatibility warning — unless its id collides with an existing pipeline,
     * in which case it returns [ConfirmedImport.Collision] so the UI can prompt
     * for Replace / Import-as-copy first (closing the silent-overwrite gap on
     * the schema-mismatch branch, not just the clean-success branch).
     *
     * @param outcome The schema-mismatch outcome the user confirmed.
     * @return [ConfirmedImport.Saved] with the save result when the id is free,
     *   or [ConfirmedImport.Collision] describing the collision when it is not.
     */
    suspend fun persistConfirmed(outcome: PipelineImportOutcome.SchemaMismatch): ConfirmedImport {
        val collision = collisionOf(outcome.graph)
        return if (collision != null) {
            ConfirmedImport.Collision(collision)
        } else {
            ConfirmedImport.Saved(save(freshenElementIds(outcome.graph)))
        }
    }

    /**
     * Persists [graph] after the user has resolved an id collision.
     *
     * [ImportCollisionResolution.REPLACE] keeps the pipeline id and overwrites
     * the existing pipeline, but still freshens its node/connection ids through
     * [PipelineBundleIdRemapper.freshenElementIds] — otherwise the imported
     * `node-N` ids could collide with an *unrelated* pipeline's globally-unique
     * node rows and steal them under `OnConflictStrategy.REPLACE`.
     * [ImportCollisionResolution.IMPORT_AS_COPY] regenerates every id (pipeline
     * included) through [PipelineBundleIdRemapper.regenerate] so it is saved as
     * a fresh pipeline (references to *other* library pipelines are preserved,
     * since a single import carries no intra-bundle targets to remap).
     *
     * @param graph The imported graph of [ImportInvocation.pendingCollision].
     * @param resolution The user's choice.
     * @return The graph as written, or the save's failure.
     */
    suspend fun persistWithResolution(
        graph: PipelineGraph,
        resolution: ImportCollisionResolution,
    ): Result<PipelineGraph> {
        val toSave = when (resolution) {
            ImportCollisionResolution.REPLACE -> freshenElementIds(graph)
            ImportCollisionResolution.IMPORT_AS_COPY ->
                PipelineBundleIdRemapper.regenerate(listOf(graph)) { UUID.randomUUID().toString() }.first()
        }
        return save(toSave)
    }

    /** Saves [graph] and, on success, returns it — the graph now in storage. */
    private suspend fun save(graph: PipelineGraph): Result<PipelineGraph> = savePipelineUseCase(graph).map { graph }

    /**
     * Describes what [incoming]'s id is already taken by, if anything: a library
     * pipeline, or bindings left behind by a deleted one. A deleted pipeline's id
     * is free in the library, but its chats, triggers and callers still name it;
     * saving under it without asking would re-bind all of them to the file.
     *
     * @param incoming The checked imported graph.
     * @return The collision to confirm, or `null` when the id is free of both.
     */
    private suspend fun collisionOf(incoming: PipelineGraph): PipelineCollision? {
        val existing = pipelineRepository.getPipelineById(incoming.id)
        val bindings = findPipelineBindings.of(incoming.id)
        return when {
            existing != null -> PipelineCollision(incoming, existing.name, bindings)
            !bindings.isEmpty -> PipelineCollision(incoming, existingName = null, bindings = bindings)
            else -> null
        }
    }

    /**
     * Regenerates [graph]'s node and connection ids (pipeline id preserved) so
     * they are globally unique before persistence, shielding other pipelines
     * that happen to reuse the same `node-N` ids from
     * `OnConflictStrategy.REPLACE`. Thin single-graph adapter over
     * [PipelineBundleIdRemapper.freshenElementIds].
     */
    private fun freshenElementIds(graph: PipelineGraph): PipelineGraph =
        PipelineBundleIdRemapper.freshenElementIds(listOf(graph)) { UUID.randomUUID().toString() }.first()
}

/**
 * Result of confirming a schema-mismatch import ([ImportPipelineUseCase.persistConfirmed]).
 */
sealed class ConfirmedImport {

    /**
     * The confirmed graph's id was free, so it was persisted.
     *
     * @property result The graph as written, or the save's failure.
     */
    data class Saved(val result: Result<PipelineGraph>) : ConfirmedImport()

    /**
     * The confirmed graph's id collides with an existing pipeline; nothing was
     * written. The UI must resolve the collision (Replace / Import as copy).
     *
     * @property collision The existing pipeline and its bindings, plus the
     *   graph awaiting resolution.
     */
    data class Collision(val collision: PipelineCollision) : ConfirmedImport()
}

/**
 * Aggregate carrying both the parse outcome and (when applicable) the
 * persistence result. Modelled as a `data class` so consumers can pattern
 * match on `outcome` and look at `saveResult` / `pendingCollision` only when
 * relevant.
 */
data class ImportInvocation(
    /** Parse outcome — drives the UI branching (Success / SchemaMismatch / Failure). */
    val outcome: PipelineImportOutcome,
    /**
     * Persistence result — the graph as written, freshened ids included —
     * non-null only for outcomes that the use case persisted automatically (a
     * clean [PipelineImportOutcome.Success] whose id did not collide). For [PipelineImportOutcome.SchemaMismatch] persistence is
     * deferred to `persistConfirmed`, for a colliding success it is deferred to
     * `persistWithResolution`, and for [PipelineImportOutcome.Failure] it never
     * runs.
     */
    val saveResult: Result<PipelineGraph>?,
    /**
     * Set only when the parse succeeded cleanly but the graph's id already
     * names a saved pipeline. The UI must prompt the user — naming the
     * existing pipeline and its bindings — and then call
     * [ImportPipelineUseCase.persistWithResolution]; nothing has been written
     * yet.
     */
    val pendingCollision: PipelineCollision? = null,
)
