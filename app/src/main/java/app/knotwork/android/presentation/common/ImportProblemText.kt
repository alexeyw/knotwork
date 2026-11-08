package app.knotwork.android.presentation.common

import app.knotwork.android.R
import app.knotwork.android.domain.models.ImportProblem
import app.knotwork.android.domain.models.InputProblem
import app.knotwork.android.domain.text.toDisplaySafe
import app.knotwork.android.domain.usecases.BundleExportProblem
import app.knotwork.android.presentation.ui.common.UiText
import app.knotwork.android.presentation.ui.orchestrator.OrchestratorErrorText

/**
 * The words for why an import failed, in the interface language.
 *
 * Every string a problem carries was quoted from the file the user picked. The
 * parser stored it display-safe, and it is bounded again here as it goes into
 * the sentence — a value that slipped past the parser can lengthen the
 * sentence, never break it into lines.
 *
 * @param problem Why the file was refused.
 * @return The sentence, resolved by the screen.
 */
fun importProblemText(problem: ImportProblem): UiText = when (problem) {
    is ImportProblem.TooDeep -> UiText.of(R.string.import_problem_too_deep, problem.maxDepth)
    is ImportProblem.NotJson -> UiText.of(R.string.import_problem_not_json, quoted(problem.detail))
    is ImportProblem.Malformed -> UiText.of(R.string.import_problem_malformed, quoted(problem.detail))
    is ImportProblem.MissingField -> UiText.of(R.string.import_problem_missing_field, quoted(problem.field))
    is ImportProblem.GraphProblem -> graphProblemText(problem)
    ImportProblem.EmptyBundle -> UiText(R.string.import_problem_empty_bundle)
    is ImportProblem.BundleTooLarge -> UiText.of(R.string.import_problem_bundle_too_large, problem.count, problem.limit)
    is ImportProblem.BundleEntryNotObject -> UiText.of(R.string.import_problem_bundle_entry_not_object, problem.index)
    is ImportProblem.BundleEntryInvalid ->
        UiText.of(R.string.import_problem_bundle_entry_invalid, problem.index, importProblemText(problem.problem))
    is ImportProblem.DuplicatePipelineIds ->
        UiText.of(R.string.import_problem_duplicate_pipeline_ids, someOf(problem.ids.map(::quotedValue), problem.more))
    is ImportProblem.MissingPipelines ->
        UiText.of(R.string.import_problem_missing_pipelines, someOf(problem.ids.map(::quotedValue), problem.more))
    is ImportProblem.PipelineFailsChecks -> UiText.of(
        R.string.import_problem_pipeline_fails_checks,
        quoted(problem.graph.name),
        someOf(
            parts = problem.errors.take(MAX_LISTED).map { OrchestratorErrorText.forValidation(it, problem.graph) },
            more = (problem.errors.size - MAX_LISTED).coerceAtLeast(0),
        ),
    )
    is ImportProblem.TooManyMemories ->
        UiText.of(R.string.import_problem_too_many_memories, problem.count, problem.limit)
    is ImportProblem.MemoryEntryNotObject -> UiText.of(R.string.import_problem_memory_entry_not_object, problem.index)
    is ImportProblem.MemoryEntryInvalid -> UiText.of(R.string.import_problem_memory_entry_invalid, problem.index)
    is ImportProblem.MemoryHistoryInvalid -> UiText.of(R.string.import_problem_memory_history_invalid, problem.index)
}

/** The words for what is wrong with the graph a pipeline document describes. */
private fun graphProblemText(problem: ImportProblem.GraphProblem): UiText = when (problem) {
    is ImportProblem.InvalidPipelineId -> UiText.of(R.string.import_problem_invalid_pipeline_id, problem.maxLength)
    is ImportProblem.DuplicateNodeId -> UiText.of(R.string.import_problem_duplicate_node_id, quoted(problem.nodeId))
    is ImportProblem.DuplicateConnectionId ->
        UiText.of(R.string.import_problem_duplicate_connection_id, quoted(problem.connectionId))
    is ImportProblem.NodeWithoutId -> UiText.of(R.string.import_problem_node_without_id, problem.index)
    is ImportProblem.NodeWithoutType -> UiText.of(R.string.import_problem_node_without_type, quoted(problem.nodeId))
    is ImportProblem.UnknownNodeType ->
        UiText.of(R.string.import_problem_unknown_node_type, quoted(problem.nodeId), quoted(problem.type))
    is ImportProblem.ConnectionWithoutId -> UiText.of(R.string.import_problem_connection_without_id, problem.index)
    is ImportProblem.ConnectionWithoutEnd ->
        UiText.of(R.string.import_problem_connection_without_end, quoted(problem.connectionId), quoted(problem.field))
    is ImportProblem.ConnectionToUnknownNode ->
        UiText.of(R.string.import_problem_connection_unknown_node, quoted(problem.connectionId), quoted(problem.nodeId))
    is ImportProblem.LabelNotABranch -> UiText.of(
        R.string.import_problem_label_not_a_branch,
        quoted(problem.connectionId),
        quoted(problem.nodeType),
        quoted(problem.nodeId),
        quoted(problem.label),
        problem.branches.joinToString(", ") { quoted(it) },
    )
    is ImportProblem.ContextFlagNotBoolean ->
        UiText.of(R.string.import_problem_context_flag, quoted(problem.nodeId), quoted(problem.flag))
    is ImportProblem.UnknownCloudProvider ->
        UiText.of(R.string.import_problem_unknown_provider, quoted(problem.nodeId), quoted(problem.provider))
}

/**
 * The words for why a use case refused the user's input, in the interface language.
 *
 * @param problem What was wrong with the input.
 * @return The sentence, resolved by the screen.
 */
fun inputProblemText(problem: InputProblem): UiText = when (problem) {
    is InputProblem.BlankName -> UiText(
        if (problem.subject == InputProblem.Subject.PIPELINE) {
            R.string.input_problem_blank_pipeline_name
        } else {
            R.string.input_problem_blank_preset_name
        },
    )
    is InputProblem.NameTooLong -> UiText.of(
        if (problem.subject == InputProblem.Subject.PIPELINE) {
            R.string.input_problem_long_pipeline_name
        } else {
            R.string.input_problem_long_preset_name
        },
        problem.maxLength,
    )
    InputProblem.BlankSystemPrompt -> UiText(R.string.input_problem_blank_system_prompt)
    is InputProblem.SystemPromptTooLong -> UiText.of(R.string.input_problem_long_system_prompt, problem.maxLength)
    is InputProblem.NoPromptForNodeType -> UiText.of(R.string.input_problem_no_prompt_for_node, problem.nodeType)
    is InputProblem.NotFound -> notFoundText(problem)
}

/**
 * The words for why a pipeline bundle could not be exported, in the interface language.
 *
 * @param problem Why the export stopped.
 * @return The sentence, resolved by the screen.
 */
fun bundleExportProblemText(problem: BundleExportProblem): UiText = when (problem) {
    is BundleExportProblem.RootNotFound -> UiText.of(R.string.export_problem_root_not_found, quoted(problem.pipelineId))
    is BundleExportProblem.ReferenceNotFound ->
        UiText.of(R.string.export_problem_reference_not_found, quoted(problem.pipelineId))
    is BundleExportProblem.TooManyPipelines -> UiText.of(R.string.export_problem_too_many_pipelines, problem.limit)
}

/** An id that names nothing, worded by what it was meant to name. */
private fun notFoundText(problem: InputProblem.NotFound): UiText {
    val id = quoted(problem.id.orEmpty())
    return when (problem.subject) {
        InputProblem.Subject.PIPELINE -> UiText(R.string.input_problem_pipeline_not_found)
        InputProblem.Subject.PRESET -> UiText.of(R.string.input_problem_preset_not_found, id)
        InputProblem.Subject.PROMPT_PRESET -> UiText.of(R.string.input_problem_prompt_preset_not_found, id)
        InputProblem.Subject.SCENARIO -> UiText.of(R.string.input_problem_scenario_not_found, id)
    }
}

/** One value quoted from a file, bounded again: one line, at most the quoted-value length. */
private fun quoted(value: String): String = value.toDisplaySafe()

/** One value from a file in the interface language's quotation marks, bounded. */
private fun quotedValue(value: String): UiText = UiText.of(R.string.import_problem_quoted_value, quoted(value))

/**
 * A list of [parts], ending with how many more there are when [more] is above zero — the
 * parsers and the checks keep a few, so a file decides neither the list's length nor its words.
 */
private fun someOf(parts: List<UiText>, more: Int): UiText {
    val listed = UiText.Joined(parts)
    return if (more > 0) UiText.of(R.string.import_problem_and_more, listed, more) else listed
}

/** Most findings a sentence lists before counting the rest. */
private const val MAX_LISTED = 5
