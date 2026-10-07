package app.knotwork.design.components.pipelineeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.knotwork.design.theme.KnotworkTheme

/**
 * Per-type form bodies for [NodeConfigSheet].
 *
 * Each `when` arm dispatches to an internal composable named
 * `<Type>FormBody` so a search for "FormBody" surfaces the entire form
 * surface. The bodies are grouped by what the node does —
 * `NodeConfigModelForms.kt` (nodes that prompt a model),
 * `NodeConfigFlowForms.kt` (nodes that steer the run) and
 * `NodeConfigTargetForms.kt` (nodes that call a tool, pipeline or skill) —
 * and share the field composables of `NodeConfigFields.kt` ([FieldLabel],
 * [InlineError], [VariableChipsRow], …) so each per-type body stays focused
 * on the spec's field list.
 */
object NodeConfigForms {

    /**
     * Renders the form body matching [config]'s runtime type.
     *
     * @param config current configuration value.
     * @param errors validator output keyed by field id; forms render the
     * matching inline error under the field via [InlineError].
     * @param onChange emit the next [NodeConfig] when the user edits any
     * field. Forms perform pure copy()-style updates — no internal state.
     * @param availableToolIds canonical tool ids exposed to [ToolFormBody] for its
     * dropdown. Empty list (the default) falls back to a free-text input — useful for
     * the catalog harness which has no app-level [app.knotwork.android.domain.repositories.ToolRepository].
     * @param availableModels installed local models exposed to [LiteRtFormBody] for
     * its dropdown. Empty list (the default) falls back to a free-text input.
     * @param onPickFromLibrary optional hook to open the prompt-library picker from
     * any prompt-bearing field. The catalog form invokes it with
     * `(category, currentPrompt, applySelected)` where `category` is the LLM-driven
     * `NodeType.name` (e.g. `"LITE_RT"`), `currentPrompt` is the field's current draft
     * (so the picker can mark the matching row as `CURRENT`), and `applySelected` is
     * the lambda the form wants run when the user picks a preset; the screen renders
     * its own picker and calls `applySelected`. `null` (the default) hides the library
     * button entirely.
     * @param onSavePreset optional hook to capture the current draft of a prompt-bearing
     * field as a user prompt preset. The catalog form invokes it with
     * `(category, currentPrompt)`; the screen shows its own Save-as-preset dialog with
     * name/description/tags fields. `null` (the default) hides the save button entirely.
     */
    @Composable
    fun Body(
        config: NodeConfig,
        errors: Map<FieldId, ValidationFailure>,
        onChange: (NodeConfig) -> Unit,
        availableToolIds: List<String> = emptyList(),
        availableModels: List<LocalModelOption> = emptyList(),
        availablePipelines: List<PipelineTargetOption> = emptyList(),
        availableSkills: List<SkillOption> = emptyList(),
        onPickFromLibrary: ((category: String, currentPrompt: String, apply: (String) -> Unit) -> Unit)? = null,
        onSavePreset: ((category: String, currentPrompt: String) -> Unit)? = null,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            // Sheet density tightening: inter-field
            // gap dropped sp3 → sp2 so a 3-4 field form fits a phone viewport
            // without scroll. Per-form `Column` arrangements keep their own
            // tighter sp1 internal spacing.
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        ) {
            TitleField(
                title = config.title,
                error = errors[FieldId.TITLE],
                onChange = { next -> onChange(config.withTitle(next)) },
            )
            when (config) {
                is InputConfig -> InputFormBody()
                is OutputConfig -> OutputFormBody(config, onChange, onPickFromLibrary, onSavePreset)
                is LiteRtConfig -> LiteRtFormBody(
                    config = config,
                    errors = errors,
                    onChange = onChange,
                    availableModels = availableModels,
                    onPickFromLibrary = onPickFromLibrary,
                    onSavePreset = onSavePreset,
                )
                is CloudConfig -> CloudFormBody(config, errors, onChange, onPickFromLibrary, onSavePreset)
                is IntentRouterConfig -> IntentRouterFormBody(
                    config = config,
                    errors = errors,
                    onChange = onChange,
                    onPickFromLibrary = onPickFromLibrary,
                    onSavePreset = onSavePreset,
                )
                is IfConditionConfig -> IfConditionFormBody(
                    config = config,
                    errors = errors,
                    onChange = onChange,
                    onPickFromLibrary = onPickFromLibrary,
                    onSavePreset = onSavePreset,
                )
                is ClarificationConfig -> ClarificationFormBody(
                    config = config,
                    errors = errors,
                    onChange = onChange,
                    onPickFromLibrary = onPickFromLibrary,
                    onSavePreset = onSavePreset,
                )
                is ToolConfig -> ToolFormBody(config, errors, onChange, availableToolIds)
                is DecompositionConfig -> DecompositionFormBody(
                    config = config,
                    errors = errors,
                    onChange = onChange,
                    onPickFromLibrary = onPickFromLibrary,
                    onSavePreset = onSavePreset,
                )
                is QueueProcessorConfig -> QueueProcessorFormBody(config, onChange)
                is EvaluationConfig -> EvaluationFormBody(
                    config = config,
                    errors = errors,
                    onChange = onChange,
                    onPickFromLibrary = onPickFromLibrary,
                    onSavePreset = onSavePreset,
                )
                is SummaryConfig -> SummaryFormBody(config, errors, onChange, onPickFromLibrary, onSavePreset)
                is PipelineConfig -> PipelineFormBody(config, errors, onChange, availablePipelines)
                is SkillConfig -> SkillFormBody(config, errors, onChange, availableSkills)
            }
        }
    }
}

/**
 * Typed shorthand for the optional prompt-library callback the screen passes down to
 * forms. The form invokes it as `hook(category, currentPrompt) { picked -> onChange(...) }`;
 * the screen surfaces its own picker and calls the inner lambda with the chosen prompt.
 */
internal typealias PromptLibraryHook = (category: String, currentPrompt: String, apply: (String) -> Unit) -> Unit

/**
 * Typed shorthand for the optional save-as-preset callback the screen passes down to
 * forms. The form invokes it as `hook(category, currentPrompt)`; the screen renders
 * its own name/description/tags dialog and persists the new preset on confirm.
 */
internal typealias SavePresetHook = (category: String, currentPrompt: String) -> Unit

/**
 * Returns a copy of [this] with [title] swapped in. Each sealed variant
 * needs its own `copy()` call because Kotlin does not synthesise a shared
 * copy method on a sealed interface.
 */
private fun NodeConfig.withTitle(title: String): NodeConfig = when (this) {
    is InputConfig -> copy(title = title)
    is OutputConfig -> copy(title = title)
    is LiteRtConfig -> copy(title = title)
    is CloudConfig -> copy(title = title)
    is IntentRouterConfig -> copy(title = title)
    is IfConditionConfig -> copy(title = title)
    is ClarificationConfig -> copy(title = title)
    is ToolConfig -> copy(title = title)
    is DecompositionConfig -> copy(title = title)
    is QueueProcessorConfig -> copy(title = title)
    is EvaluationConfig -> copy(title = title)
    is SummaryConfig -> copy(title = title)
    is PipelineConfig -> copy(title = title)
    is SkillConfig -> copy(title = title)
}
