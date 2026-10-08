package app.knotwork.android.presentation.ui.orchestrator

import app.knotwork.android.R
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineValidationError
import app.knotwork.android.domain.models.PipelineValidationException
import app.knotwork.android.domain.text.ImportedText
import app.knotwork.android.domain.text.toDisplaySafe
import app.knotwork.android.presentation.ui.common.UiText

/**
 * The words the pipeline library and editor use for a failure — one place, so a save, an
 * import and a preset save that hit the same validator say the same thing.
 *
 * Pure: a validation error names nodes from the graph it is given, so the caller passes the
 * pipeline the user is looking at.
 */
internal object OrchestratorErrorText {

    /**
     * Turns a save / import / preset failure into user-facing text. A validation failure
     * names every error it carries; any other exception shows its message.
     *
     * Returns a single `UiText.Resource` when the failure is a single
     * `PipelineValidationException` with exactly one error; multi-error validation
     * collapses into a `UiText.Joined` list (this keeps the API typed without forcing the
     * resource layer to model arbitrarily many error combinations). Generic exceptions
     * become `UiText.Dynamic`.
     *
     * @param e What the save threw.
     * @param graph The pipeline whose nodes a validation error names.
     * @return The text to show, or `null` for a validation failure with no errors.
     */
    fun forSave(e: Throwable, graph: PipelineGraph): UiText? {
        if (e !is PipelineValidationException) {
            return e.message?.let { UiText.Dynamic(it) }
        }
        val parts = e.errors.map { err -> forValidation(err, graph) }
        return when (parts.size) {
            0 -> null
            1 -> parts.first()
            else -> UiText.Joined(parts)
        }
    }

    /**
     * Resolves a single [PipelineValidationError] to its `UiText` representation. Used on its
     * own by the editor's validation bar, and by [forSave] for each error of a failure.
     *
     * @param err The validator's finding.
     * @param graph The pipeline whose nodes the finding names.
     * @return The finding in the user's words.
     */
    fun forValidation(err: PipelineValidationError, graph: PipelineGraph): UiText = when (err) {
        is PipelineValidationError.MissingInput ->
            UiText(R.string.errors_orchestrator_validation_missing_input)
        is PipelineValidationError.MissingOutput ->
            UiText(R.string.errors_orchestrator_validation_missing_output)
        is PipelineValidationError.MultipleInputs ->
            UiText(R.string.errors_orchestrator_validation_multiple_inputs)
        is PipelineValidationError.MultipleOutputs ->
            UiText(R.string.errors_orchestrator_validation_multiple_outputs)
        is PipelineValidationError.HasCycles ->
            UiText(R.string.errors_orchestrator_validation_has_cycles)
        is PipelineValidationError.DisconnectedInput ->
            UiText(R.string.errors_orchestrator_validation_disconnected_input)
        is PipelineValidationError.DisconnectedOutput ->
            UiText(R.string.errors_orchestrator_validation_disconnected_output)
        is PipelineValidationError.UnreachableNode ->
            UiText(R.string.errors_orchestrator_validation_unreachable_node)
        is PipelineValidationError.DeadEndNode ->
            UiText(R.string.errors_orchestrator_validation_dead_end)
        is PipelineValidationError.NodeEmptyContext ->
            UiText.of(R.string.errors_orchestrator_validation_node_no_sources, nodeName(graph, err.nodeId))
        is PipelineValidationError.MissingTargetPipeline ->
            UiText.of(R.string.errors_orchestrator_validation_missing_target_pipeline, nodeName(graph, err.nodeId))
        is PipelineValidationError.TargetPipelineNotFound ->
            UiText.of(R.string.errors_orchestrator_validation_target_pipeline_not_found, nodeName(graph, err.nodeId))
        is PipelineValidationError.PipelineCycle ->
            UiText.of(
                R.string.errors_orchestrator_validation_pipeline_cycle,
                err.pipelineChain.joinToString(" → ") { it.toDisplaySafe() },
            )
        is PipelineValidationError.PipelineNestingTooDeep ->
            UiText.of(R.string.errors_orchestrator_validation_pipeline_nesting_too_deep, err.limit)
        is PipelineValidationError.MissingSkill ->
            UiText.of(R.string.errors_orchestrator_validation_missing_skill, nodeName(graph, err.nodeId))
        is PipelineValidationError.SkillNotFound ->
            UiText.of(R.string.errors_orchestrator_validation_skill_not_found, nodeName(graph, err.nodeId))
    }

    /**
     * Lifts a thrown exception into a `UiText`, falling back to the generic
     * "unexpected error" resource when the throwable carries no message.
     *
     * @param e What was thrown.
     * @return Its message, or the generic text.
     */
    fun forThrowable(e: Throwable): UiText =
        e.message?.let { UiText.Dynamic(it) } ?: UiText(R.string.errors_generic_unexpected)

    /**
     * The name a validation error calls a node by: its label, or its id when
     * the node is not on the canvas.
     *
     * Display-safe, because both may come from a file the user did not write —
     * an imported label is bounded at parse, a node id is not — and the error
     * is shown in a snackbar with no line limit.
     *
     * @param graph The pipeline to look the node up in.
     * @param nodeId the id the validator reported.
     * @return one line of at most [ImportedText.MAX_QUOTED_VALUE_LENGTH] characters.
     */
    private fun nodeName(graph: PipelineGraph, nodeId: String): String =
        (graph.nodes.find { it.id == nodeId }?.label ?: nodeId).toDisplaySafe()
}
