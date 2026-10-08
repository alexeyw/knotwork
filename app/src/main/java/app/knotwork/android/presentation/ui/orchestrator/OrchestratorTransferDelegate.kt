package app.knotwork.android.presentation.ui.orchestrator

import app.knotwork.android.R
import app.knotwork.android.domain.models.ImportCollisionResolution
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineImportOutcome
import app.knotwork.android.domain.pipelineio.PipelineBundleJsonSerializer
import app.knotwork.android.domain.text.ImportedText
import app.knotwork.android.domain.text.toDisplaySafe
import app.knotwork.android.domain.usecases.ConfirmedImport
import app.knotwork.android.domain.usecases.ExportPipelineBundleUseCase
import app.knotwork.android.domain.usecases.ImportPipelineBundleUseCase
import app.knotwork.android.domain.usecases.ImportPipelineUseCase
import app.knotwork.android.domain.usecases.PipelineBundlePrepareResult
import app.knotwork.android.domain.usecases.SavePipelineUseCase
import app.knotwork.android.presentation.ui.common.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The collaborators of [OrchestratorTransferDelegate], injected as one so the pipeline view
 * model's constructor grows by one parameter for them, not three.
 *
 * @property importPipeline Parses, checks and saves one imported pipeline.
 * @property importPipelineBundle Parses, checks and saves an imported bundle of pipelines.
 * @property exportPipelineBundle Writes a pipeline and everything it calls as one bundle.
 */
class OrchestratorTransferUseCases @Inject constructor(
    val importPipeline: ImportPipelineUseCase,
    val importPipelineBundle: ImportPipelineBundleUseCase,
    val exportPipelineBundle: ExportPipelineBundleUseCase,
)

/**
 * Import / export delegate of [OrchestratorViewModel]: a pipeline file or bundle read into
 * the library — with the schema-mismatch and id-collision questions it can raise — and a
 * bundle written out.
 *
 * Shares the view model's [scope] and its single [uiState] reducer (see
 * `docs/architecture.md` §1.2); the pending questions and the pending export live in
 * [OrchestratorUiState].
 *
 * @property scope The view model's `viewModelScope`.
 * @property uiState The view model's single source-of-truth state flow.
 * @property useCases The import and export use cases.
 */
class OrchestratorTransferDelegate(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<OrchestratorUiState>,
    private val useCases: OrchestratorTransferUseCases,
) {

    /**
     * Entry point for the shared "Import JSON" affordance. Detects whether
     * [jsonString] is a bundle envelope (a self-contained closure of several
     * pipelines) or a single-pipeline document and routes to the matching
     * flow, so the library screen exposes one import affordance regardless of
     * the file shape.
     *
     * @param jsonString Raw JSON read from the picked document.
     */
    fun importJson(jsonString: String) {
        if (PipelineBundleJsonSerializer.looksLikeBundle(jsonString)) {
            importBundleFromJson(jsonString)
        } else {
            importPipelineFromJson(jsonString)
        }
    }

    /**
     * Parses [jsonString] as a single pipeline and, on a clean non-colliding
     * success, persists it through [SavePipelineUseCase] so it appears in the
     * saved-pipelines list immediately.
     *
     * Two deferral paths write nothing until the user decides:
     * - a `schemaVersion` mismatch stashes the graph in
     *   [OrchestratorUiState.pendingImport] for [confirmPendingImport];
     * - an id collision (the imported id already names a saved pipeline)
     *   stashes the graph in [OrchestratorUiState.pendingCollision] for
     *   [resolveCollision], closing the previous silent-overwrite behaviour.
     */
    private fun importPipelineFromJson(jsonString: String) {
        scope.launch {
            uiState.update { it.copy(isLoading = true) }
            val invocation = useCases.importPipeline(jsonString)
            uiState.update { state ->
                when (val outcome = invocation.outcome) {
                    is PipelineImportOutcome.Success -> {
                        val collision = invocation.pendingCollision
                        val saveErr = invocation.saveResult?.let { res ->
                            res.exceptionOrNull()?.let(::saveErrorText)
                        }
                        // The graph as written, not as parsed: the importer freshens
                        // node and connection ids, and an editor holding the file's
                        // ids would write them back on its next Save.
                        val savedGraph = invocation.saveResult?.getOrNull()
                        val saved = collision == null && savedGraph != null
                        state.copy(
                            currentPipeline = savedGraph ?: state.currentPipeline,
                            // An import that reached storage IS the saved state.
                            persistedPipeline = savedGraph ?: state.persistedPipeline,
                            isLoading = false,
                            pendingImport = null,
                            pendingCollision = collision,
                            errorMessage = saveErr,
                            // A matching schemaVersion does not mean nothing was lost:
                            // the format adds fields without bumping the version, so a
                            // file from a newer build can carry settings this one cannot
                            // read. Say so instead of importing a quietly diminished
                            // pipeline. Only when the import actually landed — a
                            // collision or save error has its own, louder surface.
                            feedbackMessage = if (saved && outcome.droppedFields.isNotEmpty()) {
                                UiText.Plural(
                                    id = R.plurals.orchestrator_library_import_dropped_feedback,
                                    quantity = outcome.droppedFields.size,
                                    args = listOf(outcome.droppedFields.size),
                                )
                            } else {
                                state.feedbackMessage
                            },
                        )
                    }
                    is PipelineImportOutcome.SchemaMismatch ->
                        state.copy(
                            isLoading = false,
                            pendingImport = outcome,
                            errorMessage = null,
                        )
                    is PipelineImportOutcome.Failure ->
                        state.copy(
                            isLoading = false,
                            pendingImport = null,
                            // Display-safe as a whole, behind the per-value rule in
                            // the serializer: the message quotes a file the user did
                            // not write.
                            errorMessage = UiText.Dynamic(
                                outcome.message.toDisplaySafe(ImportedText.MAX_MESSAGE_LENGTH),
                            ),
                        )
                }
            }
        }
    }

    /**
     * Resolves a pending single-import id collision with the user's choice
     * ([ImportCollisionResolution.REPLACE] overwrites in place;
     * [ImportCollisionResolution.IMPORT_AS_COPY] saves a fresh copy). No-op
     * when no collision is pending.
     *
     * @param resolution The user's collision choice.
     */
    fun resolveCollision(resolution: ImportCollisionResolution) {
        val collision = uiState.value.pendingCollision ?: return
        uiState.update { it.copy(isLoading = true, pendingCollision = null) }
        scope.launch {
            val result = useCases.importPipeline.persistWithResolution(collision.incoming, resolution)
            uiState.update { state ->
                val saveErr = result.exceptionOrNull()?.let(::saveErrorText)
                // Only Replace opens the result: a copy is a new pipeline beside
                // the one the user was looking at. Either way the editor takes
                // the graph as written (freshened ids), never the parsed one.
                val replaced = result.getOrNull()?.takeIf { resolution == ImportCollisionResolution.REPLACE }
                state.copy(
                    currentPipeline = replaced ?: state.currentPipeline,
                    persistedPipeline = replaced ?: state.persistedPipeline,
                    isLoading = false,
                    errorMessage = saveErr,
                )
            }
        }
    }

    /**
     * Discards a pending single-import id collision without persisting.
     */
    fun cancelCollision() {
        uiState.update { it.copy(pendingCollision = null) }
    }

    /**
     * Parses [jsonString] as a pipeline bundle, validates every contained
     * graph, and either persists straight away (no collisions, no schema
     * mismatch) or stashes the prepared closure in
     * [OrchestratorUiState.pendingBundleImport] for the user to resolve. A
     * parse or validation failure surfaces through [OrchestratorUiState.errorMessage].
     */
    private fun importBundleFromJson(jsonString: String) {
        scope.launch {
            uiState.update { it.copy(isLoading = true) }
            when (val prepared = useCases.importPipelineBundle.prepare(jsonString)) {
                is PipelineBundlePrepareResult.Failure ->
                    uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = UiText.Dynamic(
                                prepared.message.toDisplaySafe(ImportedText.MAX_MESSAGE_LENGTH),
                            ),
                        )
                    }

                is PipelineBundlePrepareResult.Ready -> {
                    val needsPrompt = prepared.collisions.isNotEmpty() || prepared.schemaMismatches.isNotEmpty()
                    if (needsPrompt) {
                        uiState.update {
                            it.copy(
                                isLoading = false,
                                pendingBundleImport = PendingBundleImport(
                                    pipelines = prepared.pipelines,
                                    collisions = prepared.collisions,
                                    schemaMismatches = prepared.schemaMismatches,
                                ),
                            )
                        }
                    } else {
                        persistBundle(prepared.pipelines, ImportCollisionResolution.REPLACE)
                    }
                }
            }
        }
    }

    /**
     * Resolves a pending bundle import with the user's collision choice and
     * writes the closure atomically. No-op when no bundle import is pending.
     *
     * @param resolution How to treat ids that collide with the library.
     */
    fun resolveBundleImport(resolution: ImportCollisionResolution) {
        val pending = uiState.value.pendingBundleImport ?: return
        uiState.update { it.copy(isLoading = true, pendingBundleImport = null) }
        scope.launch { persistBundle(pending.pipelines, resolution) }
    }

    /**
     * Discards a pending bundle import without persisting.
     */
    fun cancelBundleImport() {
        uiState.update { it.copy(pendingBundleImport = null) }
    }

    /**
     * Atomically persists [pipelines] under [resolution], surfacing either a
     * success count feedback or an error. Shared by the direct (no-collision)
     * and dialog-resolved bundle-import paths.
     */
    private suspend fun persistBundle(pipelines: List<PipelineGraph>, resolution: ImportCollisionResolution) {
        val result = useCases.importPipelineBundle.persist(pipelines, resolution)
        uiState.update { state ->
            result.fold(
                onSuccess = { saved ->
                    // If the bundle replaced the pipeline currently open in the
                    // editor (REPLACE keeps ids), refresh the in-memory copy to
                    // the just-persisted graph so a later Save writes the
                    // imported content instead of silently reverting to stale
                    // state. Under copy every id changes, so nothing matches and
                    // the open pipeline is left untouched.
                    val refreshed = saved.firstOrNull { it.id == state.currentPipeline.id }
                    state.copy(
                        isLoading = false,
                        currentPipeline = refreshed ?: state.currentPipeline,
                        feedbackMessage = UiText.Plural(
                            R.plurals.orchestrator_library_import_bundle_success,
                            saved.size,
                            listOf(saved.size),
                        ),
                    )
                },
                onFailure = { e ->
                    state.copy(isLoading = false, errorMessage = OrchestratorErrorText.forThrowable(e))
                },
            )
        }
    }

    /**
     * Exports the pipeline identified by [pipelineId] together with the
     * transitive closure of its `PIPELINE` dependencies as a bundle document,
     * stashing the result in [OrchestratorUiState.pendingBundleExport] for the
     * library screen to write to a user-picked file. A fail-fast export error
     * (missing root, unresolvable dependency, or over-limit closure) surfaces
     * through [OrchestratorUiState.errorMessage].
     *
     * @param pipelineId Id of the saved pipeline to export as the bundle root.
     * @param fileName Suggested destination file name.
     */
    fun exportBundle(pipelineId: String, fileName: String) {
        scope.launch {
            uiState.update { it.copy(isLoading = true) }
            val result = useCases.exportPipelineBundle(pipelineId)
            uiState.update { state ->
                result.fold(
                    onSuccess = { json ->
                        state.copy(
                            isLoading = false,
                            pendingBundleExport = PendingBundleExport(fileName = fileName, content = json),
                        )
                    },
                    onFailure = { e ->
                        state.copy(
                            isLoading = false,
                            errorMessage = UiText.of(
                                R.string.orchestrator_library_export_bundle_failed,
                                e.message ?: "",
                            ),
                        )
                    },
                )
            }
        }
    }

    /**
     * Clears the pending bundle-export payload once the library screen has
     * written it (or the user cancelled the file picker).
     */
    fun consumeBundleExport() {
        uiState.update { it.copy(pendingBundleExport = null) }
    }

    /**
     * Persists the graph captured in [OrchestratorUiState.pendingImport]
     * after the user has accepted the schema-mismatch warning. No-op when
     * no import is pending.
     */
    fun confirmPendingImport() {
        val pending = uiState.value.pendingImport ?: return
        // Clear pendingImport immediately so the AlertDialog dismisses
        // before the suspending save runs. Holding it while persistConfirmed
        // is in-flight would let the user re-click "Import anyway" or
        // dismiss the dialog mid-save, racing two persists for the same
        // graph.
        uiState.update { it.copy(isLoading = true, pendingImport = null) }
        scope.launch {
            when (val confirmed = useCases.importPipeline.persistConfirmed(pending)) {
                // The confirmed graph collides with an existing pipeline: defer
                // to the collision dialog instead of silently overwriting.
                is ConfirmedImport.Collision ->
                    uiState.update { it.copy(isLoading = false, pendingCollision = confirmed.collision) }

                is ConfirmedImport.Saved ->
                    uiState.update { state ->
                        val saveErr = confirmed.result.exceptionOrNull()?.let(::saveErrorText)
                        val savedGraph = confirmed.result.getOrNull()
                        state.copy(
                            currentPipeline = savedGraph ?: state.currentPipeline,
                            persistedPipeline = savedGraph ?: state.persistedPipeline,
                            isLoading = false,
                            errorMessage = saveErr,
                        )
                    }
            }
        }
    }

    /**
     * Discards a pending schema-mismatch import without persisting.
     */
    fun cancelPendingImport() {
        uiState.update { it.copy(pendingImport = null) }
    }

    /**
     * A save / import failure in the user's words, naming nodes of the pipeline on screen.
     *
     * @param e What was thrown.
     * @return The text to show, or `null` for a validation failure with no errors.
     */
    private fun saveErrorText(e: Throwable): UiText? = OrchestratorErrorText.forSave(e, uiState.value.currentPipeline)
}
