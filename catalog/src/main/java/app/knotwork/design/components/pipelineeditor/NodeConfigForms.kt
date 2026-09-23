@file:Suppress("LongMethod", "TooManyFunctions") // 13 forms by spec; splitting per-file would only add ceremony.

package app.knotwork.design.components.pipelineeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.components.chips.ChipStyle
import app.knotwork.design.components.chips.KnotworkChip
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.screens.settings.KnotworkParamSlider
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Lower bound on IntentRouter class count, mirrors `NodeConfigValidation.INTENT_CLASSES_RANGE.first`. */
private const val INTENT_ROUTER_MIN_CLASSES = 2

/** Upper bound on IntentRouter class count, mirrors `NodeConfigValidation.INTENT_CLASSES_RANGE.last`. */
private const val INTENT_ROUTER_MAX_CLASSES = 6

/**
 * Per-type form bodies for [NodeConfigSheet].
 *
 * Each `when` arm dispatches to a private composable named
 * `<Type>FormBody` so a search for "FormBody" surfaces the entire form
 * surface. The forms share a small set of helper composables ([FieldLabel],
 * [InlineError], [VariableChipsRow]) so each per-type body stays focused
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
    @Suppress("LongParameterList")
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
private typealias PromptLibraryHook = (category: String, currentPrompt: String, apply: (String) -> Unit) -> Unit

/**
 * Typed shorthand for the optional save-as-preset callback the screen passes down to
 * forms. The form invokes it as `hook(category, currentPrompt)`; the screen renders
 * its own name/description/tags dialog and persists the new preset on confirm.
 */
private typealias SavePresetHook = (category: String, currentPrompt: String) -> Unit

// ─────────────────────────────────────────────────────────────────────────
// Shared helpers
// ─────────────────────────────────────────────────────────────────────────

/** Field label rendered above each input. */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = KnotworkTextStyles.LabelMd,
        color = KnotworkTheme.extended.onSurface2,
    )
}

/** Muted helper caption rendered under a field to explain its purpose. */
@Composable
private fun FieldCaption(text: String) {
    Text(
        text = text,
        style = KnotworkTextStyles.BodySm,
        color = KnotworkTheme.extended.onSurfaceMuted,
    )
}

/** Inline error rendered under a field when validation fails. */
@Composable
private fun InlineError(failure: ValidationFailure?) {
    if (failure != null) {
        Text(
            text = stringResource(failure.stringRes),
            style = KnotworkTextStyles.BodySm,
            color = KnotworkTheme.extended.signalError,
        )
    }
}

/**
 * Catalog-side variable chips row. The production app uses the
 * `presentation/components/VariableChipsRow` which talks to the prompt
 * variable providers in `:app`; the catalog version is a static
 * presentational stub so the design system surfaces the chips without
 * pulling in the `:app` module.
 *
 * @param onInsert invoked with the chip text when the user taps a chip;
 * forms route this to the field caret.
 */
@Composable
private fun VariableChipsRow(onInsert: (String) -> Unit) {
    // Full catalog of `PromptVariableProvider` keys exposed in
    // `app/.../data/prompt/*`. Order mirrors the order users most often reach
    // for in templates (date / time first, contextual identity last). Add new
    // keys here when a `PromptVariableProvider` is registered in
    // `PromptTemplateModule`.
    val variables = PROMPT_VARIABLE_KEYS
    // LazyRow with `horizontalScroll`-like behaviour: the chips no longer wrap
    // to a second row (the previous `FlowRow` was visually noisy and pushed
    // the slider section down). Keep a clear right-edge padding so the last
    // chip doesn't kiss the sheet edge.
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(VARIABLE_CHIPS_ROW_HEIGHT.dp),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        contentPadding = PaddingValues(end = KnotworkTheme.spacing.sp2),
    ) {
        items(variables) { name ->
            KnotworkChip(label = name, onClick = { onInsert(name) }, style = ChipStyle.Outline)
        }
    }
}

/**
 * Variable keys surfaced by the prompt-template engine — kept here as a
 * snapshot so the catalog stub matches what the `:app` providers register at
 * runtime. When a new `PromptVariableProvider` lands, add the key here too.
 */
private val PROMPT_VARIABLE_KEYS: List<String> = listOf(
    "\$DATE",
    "\$TIME",
    "\$LANG",
    "\$LOCATION",
    "\$USER",
    "\$DEVICE",
    "\$MODEL",
    "\$TOOLS",
    "\$MEMORY_SUMMARY",
)

/** Fixed row height — chip baseline plus a small breathing budget. */
private const val VARIABLE_CHIPS_ROW_HEIGHT: Int = 40

/** Single-line title field — shared across every node type. */
@Composable
private fun TitleField(title: String, error: ValidationFailure?, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        FieldLabel(text = stringResource(R.string.knotwork_node_field_title))
        OutlinedTextField(
            value = title,
            onValueChange = onChange,
            singleLine = true,
            isError = error != null,
            // Every field on the sheet
            // adopts `MonoBase` so identifiers, prompts, model ids, and titles
            // all read in the same JetBrains Mono face. Mixed body/mono fonts
            // read as accidentally inconsistent on the sheet.
            textStyle = KnotworkTextStyles.MonoBase,
            modifier = Modifier.fillMaxWidth(),
        )
        InlineError(failure = error)
    }
}

/**
 * Stringly-typed text field used for most per-type rows.
 *
 * Layout: `[FieldLabel + optional library button] / [OutlinedTextField] /
 * [InlineError]`. The library button stays in a sibling row above the field
 * (not inside the field's `trailingIcon`) so prompt-bearing fields preserve
 * room for the chips row underneath. Every field uses `MonoBase`
 * so the sheet reads as a uniform stack regardless
 * of which field is prose vs identifier vs prompt.
 */
@Composable
@Suppress("LongParameterList") // Adding the optional library hook here keeps every prompt field DRY.
private fun TextField(
    label: String,
    value: String,
    error: ValidationFailure?,
    singleLine: Boolean,
    onChange: (String) -> Unit,
    libraryCategory: String? = null,
    onPickFromLibrary: PromptLibraryHook? = null,
    onSavePreset: SavePresetHook? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        // When the field is prompt-bearing AND the screen provided a library/save
        // hook, surface small icon buttons next to the label so the user can replace
        // the field with a saved prompt (📚) or persist the current draft as a new
        // user preset (💾). The buttons sit in a row with the label (not inside the
        // field) so the VariableChipsRow below the field stays visually attached to
        // the prompt area.
        val hasLibrary = libraryCategory != null && onPickFromLibrary != null
        val hasSave = libraryCategory != null && onSavePreset != null
        if (hasLibrary || hasSave) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FieldLabel(text = label)
                Spacer(modifier = Modifier.weight(1f))
                if (hasLibrary) {
                    IconButton(
                        onClick = {
                            onPickFromLibrary(libraryCategory, value) { picked -> onChange(picked) }
                        },
                        modifier = Modifier.size(LIBRARY_BUTTON_TARGET_DP.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.Book,
                            contentDescription = stringResource(R.string.knotwork_node_action_load_from_library),
                        )
                    }
                }
                if (hasSave) {
                    IconButton(
                        onClick = { onSavePreset(libraryCategory, value) },
                        modifier = Modifier.size(LIBRARY_BUTTON_TARGET_DP.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.BookmarkAdd,
                            contentDescription = stringResource(R.string.knotwork_node_action_save_as_preset),
                        )
                    }
                }
            }
        } else {
            FieldLabel(text = label)
        }
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            isError = error != null,
            // Same MonoBase rationale as TitleField — the sheet reads as a
            // uniform mono stack regardless of which field is prose vs ident.
            textStyle = KnotworkTextStyles.MonoBase,
            modifier = Modifier.fillMaxWidth(),
        )
        InlineError(failure = error)
    }
}

/**
 * Compact tap-target for the prompt-library trigger button next to a field
 * label. 32 dp keeps the label row tighter than the M3 default 48 dp
 * `IconButton` (which would otherwise inflate the row to fit the touch area).
 */
private const val LIBRARY_BUTTON_TARGET_DP: Float = 32f

/** Float field rendered as a slider plus the resolved numeric value. */
@Suppress("LongParameterList") // Slider field has a stable contract; collapsing the params hides intent.
@Composable
private fun FloatSliderField(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    error: ValidationFailure?,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    KnotworkParamSlider(
        label = label,
        valueLabel = "%.2f".format(value),
        value = value,
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        errorText = error?.let { stringResource(it.stringRes) },
    )
}

/** Integer field rendered as a slider over an `IntRange`. */
@Composable
private fun IntSliderField(
    label: String,
    value: Int,
    range: IntRange,
    error: ValidationFailure?,
    onChange: (Int) -> Unit,
) {
    // Keep the slider continuous (`steps = 0`) and round on change. Naïve
    // `steps = range.last - range.first - 1` would request one tick PER integer:
    // Cloud's `timeoutMs` range `1_000..600_000` then asks Material3 Slider to
    // allocate ~600 000 tick composables, freezing the main thread and ANRing the
    // app when the CLOUD config sheet opens. Continuous + round-on-change gives
    // identical integer increments at user-perceptible drag resolution without
    // the tick-mark blow-up.
    KnotworkParamSlider(
        label = label,
        valueLabel = "$value",
        value = value.toFloat(),
        onValueChange = { next -> onChange(next.toInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = 0,
        errorText = error?.let { stringResource(it.stringRes) },
    )
}

/** Labelled boolean toggle: a [FieldLabel] on the left and a [Switch] pushed to the right. */
@Composable
private fun ToggleRowField(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FieldLabel(text = label)
        Spacer(modifier = Modifier.fillMaxWidth().weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Segmented chip row that picks one enum value out of a labelled set. */
@Composable
private fun <T> SegmentedChipRow(label: String, values: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        FieldLabel(text = label)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        ) {
            values.forEach { (value, label) ->
                val isSelected = value == selected
                KnotworkChip(
                    label = label,
                    selected = isSelected,
                    onClick = { onSelect(value) },
                    style = ChipStyle.Tonal,
                    // Compose Tonal chips at the project's primaryContainer tint are visibly
                    // similar to the unselected tonal surface in some Knotwork palettes,
                    // which made the Summary format (and other segmented-chip groups) look
                    // unmarked. A leading check unambiguously surfaces the active state
                    // regardless of theme contrast.
                    leadingIcon = if (isSelected) AppIcons.Check else null,
                )
            }
        }
    }
}

/**
 * Optional engine selector shared by the structured node types
 * (INTENT_ROUTER / DECOMPOSITION / EVALUATION / IF_CONDITION / TOOL).
 *
 * Unlike the CLOUD node — which is always cloud — these nodes default to
 * on-device inference, so the first chip is `On-device` (a `null` provider) and
 * the remaining chips pick a concrete cloud provider that backs the node's
 * structured-output gate. `Auto` is intentionally omitted: structured nodes
 * select a concrete provider, never runtime auto-detection.
 *
 * @param selected the currently selected provider, or `null` for on-device.
 * @param onSelect invoked with the new selection (`null` ⇒ on-device).
 */
@Composable
private fun EngineProviderRow(selected: CloudProvider?, onSelect: (CloudProvider?) -> Unit) {
    SegmentedChipRow<CloudProvider?>(
        label = stringResource(R.string.knotwork_node_field_engine),
        values = listOf(
            null to stringResource(R.string.knotwork_node_field_engine_on_device),
            CloudProvider.OPEN_AI to "OpenAI",
            CloudProvider.ANTHROPIC to "Anthropic",
            CloudProvider.GOOGLE to "Google",
            CloudProvider.COMPATIBLE to "Compatible",
        ),
        selected = selected,
        onSelect = onSelect,
    )
}

// ─────────────────────────────────────────────────────────────────────────
// Per-type forms
// ─────────────────────────────────────────────────────────────────────────

/**
 * INPUT has no fields of its own beyond the shared title and description: the
 * entry contract is fixed and the run's text arrives as it is. The body is a
 * single line saying so, rather than an empty sheet that reads as unfinished.
 */
@Composable
private fun InputFormBody() {
    Text(
        text = stringResource(R.string.knotwork_node_input_no_settings),
        style = KnotworkTextStyles.BodySm,
        color = KnotworkTheme.extended.onSurfaceMuted,
    )
}

@Composable
private fun OutputFormBody(
    config: OutputConfig,
    onChange: (NodeConfig) -> Unit,
    onPickFromLibrary: PromptLibraryHook?,
    onSavePreset: SavePresetHook?,
) {
    // Optional system prompt — when blank the executor forwards the
    // upstream text verbatim; when set the LLM wraps the upstream payload
    // through this template.
    TextField(
        label = stringResource(R.string.knotwork_node_field_system_prompt),
        value = config.systemPrompt,
        // OUTPUT's systemPrompt is optional — blank means "echo upstream
        // verbatim" — so the field never carries a validation error.
        error = null,
        singleLine = false,
        onChange = { next -> onChange(config.copy(systemPrompt = next)) },
        libraryCategory = "OUTPUT",
        onPickFromLibrary = onPickFromLibrary,
        onSavePreset = onSavePreset,
    )
    VariableChipsRow(onInsert = { variable ->
        onChange(config.copy(systemPrompt = config.systemPrompt + variable))
    })
}

@Composable
@Suppress("LongParameterList")
private fun LiteRtFormBody(
    config: LiteRtConfig,
    errors: Map<FieldId, ValidationFailure>,
    onChange: (NodeConfig) -> Unit,
    availableModels: List<LocalModelOption>,
    onPickFromLibrary: PromptLibraryHook?,
    onSavePreset: SavePresetHook?,
) {
    ModelPicker(
        config = config,
        error = errors[FieldId.MODEL_ID],
        availableModels = availableModels,
        onChange = onChange,
    )
    TextField(
        label = stringResource(R.string.knotwork_node_field_system_prompt),
        value = config.systemPrompt,
        error = errors[FieldId.SYSTEM_PROMPT],
        singleLine = false,
        onChange = { next -> onChange(config.copy(systemPrompt = next)) },
        libraryCategory = "LITE_RT",
        onPickFromLibrary = onPickFromLibrary,
        onSavePreset = onSavePreset,
    )
    VariableChipsRow(onInsert = { variable ->
        onChange(config.copy(systemPrompt = config.systemPrompt + variable))
    })
    // Temperature / top-P / max-new-tokens are deliberately NOT offered here.
    // They round-tripped into the node's JSON but no executor ever read them:
    // `LiteRtNodeExecutor` calls the engine without a sampler config, so moving
    // any of these sliders changed nothing about the answer. A control that
    // silently does nothing is worse than an absent one, so they are gone until
    // per-node sampling is actually wired to the engine. The fields stay on
    // `LiteRtConfig` so existing pipeline JSON keeps round-tripping unchanged.
}

@Composable
@Suppress("LongParameterList")
private fun CloudFormBody(
    config: CloudConfig,
    errors: Map<FieldId, ValidationFailure>,
    onChange: (NodeConfig) -> Unit,
    onPickFromLibrary: PromptLibraryHook?,
    onSavePreset: SavePresetHook?,
) {
    SegmentedChipRow(
        label = stringResource(R.string.knotwork_node_field_provider),
        values = listOf(
            CloudProvider.AUTO to "Auto",
            CloudProvider.OPEN_AI to "OpenAI",
            CloudProvider.ANTHROPIC to "Anthropic",
            CloudProvider.GOOGLE to "Google",
            CloudProvider.COMPATIBLE to "Compatible",
        ),
        selected = config.provider,
        onSelect = { next -> onChange(config.copy(provider = next)) },
    )
    // The cloud-model id field is intentionally absent from the sheet —
    // cloud-provider model ids are
    // configured once per provider in Settings → External providers and
    // shared across every Cloud node, so duplicating the field on every
    // node confused users. The `CloudConfig.model` field stays on the
    // data class for backward-compat with persisted JSON (empty string is
    // the default) — the executor falls back to the provider's configured
    // model at runtime.
    TextField(
        label = stringResource(R.string.knotwork_node_field_system_prompt),
        value = config.systemPrompt,
        error = errors[FieldId.SYSTEM_PROMPT],
        singleLine = false,
        onChange = { next -> onChange(config.copy(systemPrompt = next)) },
        libraryCategory = "CLOUD",
        onPickFromLibrary = onPickFromLibrary,
        onSavePreset = onSavePreset,
    )
    VariableChipsRow(onInsert = { variable ->
        onChange(config.copy(systemPrompt = config.systemPrompt + variable))
    })
    // Temperature / max-tokens / timeout are deliberately NOT offered here, for
    // the same reason as on the LITE_RT node: they persisted but nothing read
    // them. `CloudLlmNodeExecutor` passes neither sampling parameter to the
    // provider, and the request deadlines are the fixed ones in
    // `KoogClientFactory`. Timeout was the most harmful of the three — the
    // person who reaches for it is the one whose provider has already hung, and
    // it was the one control guaranteed not to help. The fields stay on
    // `CloudConfig` so existing pipeline JSON keeps round-tripping unchanged.
}

@Composable
@Suppress("LongParameterList")
private fun IntentRouterFormBody(
    config: IntentRouterConfig,
    errors: Map<FieldId, ValidationFailure>,
    onChange: (NodeConfig) -> Unit,
    onPickFromLibrary: PromptLibraryHook?,
    onSavePreset: SavePresetHook?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        FieldLabel(text = stringResource(R.string.knotwork_node_field_classes))
        config.classes.forEachIndexed { index, intentClass ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            ) {
                OutlinedTextField(
                    value = intentClass.name,
                    onValueChange = { next ->
                        val updated = config.classes.toMutableList()
                        updated[index] = intentClass.copy(name = next)
                        onChange(config.copy(classes = updated))
                    },
                    singleLine = true,
                    textStyle = KnotworkTextStyles.MonoBase,
                    modifier = Modifier.weight(1f),
                    isError = intentClass.name.isBlank(),
                )
                // The remove control is disabled when removing would drop below the
                // 2-class minimum that `NodeConfigValidation` enforces — keeps the form
                // self-consistent without surfacing a "you cannot remove the last class"
                // error after the fact. The fallback selection is auto-cleared when its
                // class disappears so the now-stale dropdown does not silently survive.
                val canRemove = config.classes.size > INTENT_ROUTER_MIN_CLASSES
                IconButton(
                    onClick = {
                        val removedName = config.classes[index].name
                        val updated = config.classes.toMutableList().apply { removeAt(index) }
                        val nextFallback = config.fallbackClass?.takeIf { it != removedName }
                        onChange(config.copy(classes = updated, fallbackClass = nextFallback))
                    },
                    enabled = canRemove,
                ) {
                    Icon(
                        imageVector = AppIcons.MinusCircle,
                        contentDescription = stringResource(R.string.knotwork_node_action_remove_class),
                    )
                }
            }
        }
        // Add-class is gated on the same 6-class ceiling the validator enforces; together
        // with the per-row remove gate this means the form never lets the user push the
        // config into an invalid state — `Save` stays enabled for every reachable edit.
        val canAdd = config.classes.size < INTENT_ROUTER_MAX_CLASSES
        KnotworkTextButton(
            text = stringResource(R.string.knotwork_node_action_add_class),
            onClick = {
                // Auto-name the new class with a unique `class_N` placeholder so
                // it doesn't start blank. A blank name immediately fails the
                // `REQUIRED` validation rule and disables Save — locking the user
                // out of saving until they type a name. The placeholder is also
                // unique relative to existing names (it walks `N` until it finds
                // a free slot) so it doesn't trip CLASS_NAME_DUPLICATE either.
                val existing = config.classes.map { it.name }.toSet()
                val baseN = config.classes.size + 1
                val placeholder = generateSequence(baseN) { it + 1 }
                    .map { "class_$it" }
                    .first { it !in existing }
                val updated = config.classes + IntentClass(name = placeholder)
                onChange(config.copy(classes = updated))
            },
            enabled = canAdd,
            leadingIcon = AppIcons.Add,
        )
        InlineError(failure = errors[FieldId.CLASSES])
    }
    TextField(
        label = stringResource(R.string.knotwork_node_field_classifier_prompt),
        value = config.classifierPrompt,
        error = errors[FieldId.CLASSIFIER_PROMPT],
        singleLine = false,
        onChange = { next -> onChange(config.copy(classifierPrompt = next)) },
        libraryCategory = "INTENT_ROUTER",
        onPickFromLibrary = onPickFromLibrary,
        onSavePreset = onSavePreset,
    )
    VariableChipsRow(onInsert = { variable ->
        onChange(config.copy(classifierPrompt = config.classifierPrompt + variable))
    })
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        SegmentedChipRow(
            label = stringResource(R.string.knotwork_node_field_fallback_class),
            // Stale fallback values (a class that was renamed or removed) are
            // still listed as the trailing option so the chip row reflects
            // the saved state — the inline error below tells the user the
            // value no longer resolves, and selecting <none> or another
            // class clears it. Without surfacing the stale value the user
            // would not see what they are about to fix.
            values = listOf<Pair<String?, String>>(null to "<none>") +
                config.classes.map { it.name to it.name } +
                listOfNotNull(
                    config.fallbackClass
                        ?.takeIf { it.isNotBlank() && it !in config.classes.map { c -> c.name } }
                        ?.let { it to it },
                ),
            selected = config.fallbackClass,
            onSelect = { next -> onChange(config.copy(fallbackClass = next)) },
        )
        InlineError(failure = errors[FieldId.FALLBACK_CLASS])
    }
    EngineProviderRow(config.engineProvider) { next -> onChange(config.copy(engineProvider = next)) }
}

@Composable
@Suppress("LongParameterList")
private fun IfConditionFormBody(
    config: IfConditionConfig,
    errors: Map<FieldId, ValidationFailure>,
    onChange: (NodeConfig) -> Unit,
    onPickFromLibrary: PromptLibraryHook?,
    onSavePreset: SavePresetHook?,
) {
    // The four checks are laid out in the order the engine applies them, and the
    // first that matches decides the branch. That order used to be invisible:
    // the expression sat directly under the image toggle, above the two
    // deterministic checks that in fact run before it, so the sheet read as if
    // it were evaluated first when it is evaluated last.
    //
    // First: deterministic image presence. When on, the node forks True whenever
    // the user's message carries an image — no LLM call, nothing below consulted.
    ToggleRowField(
        label = stringResource(R.string.knotwork_node_field_branch_on_image),
        checked = config.branchOnImage,
        onChange = { next -> onChange(config.copy(branchOnImage = next)) },
    )
    // The two deterministic checks. Both were read by the engine long before
    // they had controls: an imported pipeline could carry keywords that decided
    // every branch, and the sheet said nothing about it.
    TextField(
        label = stringResource(R.string.knotwork_node_field_keywords),
        value = config.keywords,
        error = errors[FieldId.KEYWORDS],
        singleLine = true,
        onChange = { next -> onChange(config.copy(keywords = next)) },
    )
    FieldCaption(text = stringResource(R.string.knotwork_node_field_keywords_help))
    IntSliderField(
        label = stringResource(R.string.knotwork_node_field_complexity_threshold),
        value = config.complexityThreshold ?: 0,
        range = 0..COMPLEXITY_THRESHOLD_MAX,
        error = errors[FieldId.COMPLEXITY_THRESHOLD],
        onChange = { next -> onChange(config.copy(complexityThreshold = next.takeIf { it > 0 })) },
    )
    FieldCaption(text = stringResource(R.string.knotwork_node_field_complexity_threshold_help))
    // Last of the four, and the only one that costs a model call. The field maps
    // to domain `NodeModel.conditionPrompt` — a free-form natural-language
    // condition the LLM classifies as `true`/`false` via
    // `DefaultPrompts.IfCondition.EVALUATION_TEMPLATE`. It accepts presets
    // exactly like the other LLM-driven fields.
    TextField(
        label = stringResource(R.string.knotwork_node_field_expression),
        value = config.expression,
        error = errors[FieldId.EXPRESSION],
        singleLine = false,
        onChange = { next -> onChange(config.copy(expression = next)) },
        libraryCategory = "IF_CONDITION",
        onPickFromLibrary = onPickFromLibrary,
        onSavePreset = onSavePreset,
    )
    FieldCaption(text = stringResource(R.string.knotwork_node_field_expression_help))
    EngineProviderRow(config.engineProvider) { next -> onChange(config.copy(engineProvider = next)) }
}

@Composable
@Suppress("LongParameterList")
private fun ClarificationFormBody(
    config: ClarificationConfig,
    errors: Map<FieldId, ValidationFailure>,
    onChange: (NodeConfig) -> Unit,
    onPickFromLibrary: PromptLibraryHook?,
    onSavePreset: SavePresetHook?,
) {
    TextField(
        label = stringResource(R.string.knotwork_node_field_question),
        value = config.questionTemplate,
        error = errors[FieldId.QUESTION_TEMPLATE],
        singleLine = false,
        onChange = { next -> onChange(config.copy(questionTemplate = next)) },
        libraryCategory = "CLARIFICATION",
        onPickFromLibrary = onPickFromLibrary,
        onSavePreset = onSavePreset,
    )
    VariableChipsRow(onInsert = { variable ->
        onChange(config.copy(questionTemplate = config.questionTemplate + variable))
    })
    QuickRepliesField(
        replies = config.quickReplies,
        error = errors[FieldId.QUICK_REPLIES],
        onChange = { next -> onChange(config.copy(quickReplies = next)) },
    )
    // Timeout is edited in whole seconds (0–360). 0 means "no timeout" and
    // maps back to a null `timeoutMs`; any other value is stored as
    // milliseconds. A slider replaces the old free-text ms field so the unit
    // and bounds are unambiguous.
    val timeoutSeconds = ((config.timeoutMs ?: 0) / MILLIS_PER_SECOND).coerceIn(0, CLARIFY_TIMEOUT_MAX_SECONDS)
    KnotworkParamSlider(
        label = stringResource(R.string.knotwork_node_field_timeout_seconds),
        valueLabel = if (timeoutSeconds == 0) {
            stringResource(R.string.knotwork_node_field_timeout_none)
        } else {
            stringResource(R.string.knotwork_node_field_timeout_seconds_value, timeoutSeconds)
        },
        value = timeoutSeconds.toFloat(),
        onValueChange = { next ->
            val secs = next.toInt()
            onChange(config.copy(timeoutMs = if (secs == 0) null else secs * MILLIS_PER_SECOND))
        },
        valueRange = 0f..CLARIFY_TIMEOUT_MAX_SECONDS.toFloat(),
        steps = 0,
        errorText = errors[FieldId.TIMEOUT_OPTIONAL]?.let { stringResource(it.stringRes) },
    )
}

/**
 * Top of the IF_CONDITION length-threshold slider, in characters.
 *
 * 2 000 rather than a round 1 000 or 10 000: the check exists to fork "short
 * question" from "long brief", and a brief long enough to matter is a few
 * paragraphs. Past this the condition would never fire on anything a person
 * types into a chat.
 */
private const val COMPLEXITY_THRESHOLD_MAX: Int = 2_000

/** Milliseconds per second — Clarify timeout slider works in whole seconds. */
private const val MILLIS_PER_SECOND: Int = 1_000

/** Upper bound (seconds) of the Clarify wait-timeout slider. */
private const val CLARIFY_TIMEOUT_MAX_SECONDS: Int = 360

/**
 * Comma-separated quick-reply editor.
 *
 * Holds the user's raw text in a `remember`-backed local state so a
 * keystroke that adds a comma is not parsed-and-re-serialised on the
 * way back through the model. The previous implementation called
 * `text.split(',').map { it.trim() }.filter { it.isNotEmpty() }` on
 * every keystroke and then re-rendered `joinToString(", ")` from the
 * filtered list — so typing `yes,` produced `["yes"]`, which rendered
 * as `yes` and dropped the user's pending comma. The field was
 * effectively unable to accept more than one quick reply.
 *
 * The local-state approach is necessary because the model carries
 * `List<String>`: the trailing empty token a user types ahead of a new
 * reply has no canonical representation in the list. We sync the
 * trimmed-and-empty-filtered list to the model so validation
 * (`QUICK_REPLIES_RANGE`) still fires from the model, but the field
 * always shows what the user actually typed.
 *
 * If the caller hands in a new `replies` value (e.g. config loaded
 * from disk), the `LaunchedEffect` keyed on the serialised form
 * re-syncs the raw text so the field stays in sync with the model.
 */
@Composable
private fun QuickRepliesField(replies: List<String>, error: ValidationFailure?, onChange: (List<String>) -> Unit) {
    val serialised = replies.joinToString(", ")
    var rawText by remember { mutableStateOf(serialised) }
    LaunchedEffect(serialised) {
        if (serialised != rawText.parseQuickReplies().joinToString(", ")) {
            rawText = serialised
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        FieldLabel(text = stringResource(R.string.knotwork_node_field_quick_replies))
        FieldCaption(text = stringResource(R.string.knotwork_node_field_quick_replies_help))
        OutlinedTextField(
            value = rawText,
            onValueChange = { next ->
                rawText = next
                onChange(next.parseQuickReplies())
            },
            singleLine = true,
            isError = error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        InlineError(failure = error)
    }
}

/** Trims and drops empty tokens; the canonical model representation. */
private fun String.parseQuickReplies(): List<String> =
    if (isEmpty()) emptyList() else split(',').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Tool selector for [ToolFormBody]. When [availableToolIds] is non-empty, surfaces an
 * `ExposedDropdownMenu` with an "Auto" entry plus every registered tool — selecting one
 * commits the id straight into [ToolConfig.toolId]. The trailing "Custom tool id…"
 * option (and the empty-tools fallback) reveals a free-text input so the user can wire
 * an MCP tool that isn't yet enabled locally.
 *
 * Auto is modelled as `toolId = ""` (empty) — the runtime treats that as
 * "let the agent pick the tool"; the validator's `REQUIRED` check still surfaces a
 * Snackbar if the user saves without choosing, so blank stays a meaningful state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolPicker(
    config: ToolConfig,
    error: ValidationFailure?,
    availableToolIds: List<String>,
    onChange: (NodeConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        FieldLabel(text = stringResource(R.string.knotwork_node_field_tool))
        var menuExpanded by remember { mutableStateOf(false) }
        val autoLabel = stringResource(R.string.knotwork_node_field_tool_auto)
        val customLabel = stringResource(R.string.knotwork_node_field_tool_custom)
        // "Custom" mode lets the user enter a tool id not in `availableToolIds` (e.g., an
        // MCP tool that's still being plumbed). Tracked locally because the catalog
        // NodeConfig schema only has `toolId: String` — there's no separate "auto" /
        // "custom" sentinel to derive this from on read.
        var customMode by remember(config.toolId, availableToolIds) {
            mutableStateOf(config.toolId.isNotBlank() && config.toolId !in availableToolIds)
        }
        val selectedLabel = when {
            customMode || (config.toolId.isNotBlank() && config.toolId !in availableToolIds) ->
                config.toolId.ifBlank { customLabel }
            config.toolId.isBlank() -> autoLabel
            else -> config.toolId
        }
        if (availableToolIds.isNotEmpty()) {
            ExposedDropdownMenuBox(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedLabel,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    isError = error != null,
                    textStyle = KnotworkTextStyles.MonoBase,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(text = autoLabel) },
                        onClick = {
                            customMode = false
                            onChange(config.copy(toolId = ""))
                            menuExpanded = false
                        },
                    )
                    availableToolIds.forEach { id ->
                        DropdownMenuItem(
                            text = { Text(text = id, style = KnotworkTextStyles.MonoBase) },
                            onClick = {
                                customMode = false
                                onChange(config.copy(toolId = id))
                                menuExpanded = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(text = customLabel) },
                        onClick = {
                            customMode = true
                            menuExpanded = false
                        },
                    )
                }
            }
        }
        if (customMode || availableToolIds.isEmpty()) {
            OutlinedTextField(
                value = config.toolId,
                onValueChange = { next -> onChange(config.copy(toolId = next)) },
                singleLine = true,
                isError = error != null,
                textStyle = KnotworkTextStyles.MonoBase,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        InlineError(failure = error)
    }
}

/**
 * Local model selector for [LiteRtFormBody]. Mirrors [ToolPicker] but feeds off the
 * installed-models registry instead of the tool registry: when [availableModels] is
 * non-empty, surfaces an `ExposedDropdownMenu` with the "Active model" sentinel
 * pinned at the top (empty [LiteRtConfig.modelId]), every registered model below
 * (active one badged `· active`), and a trailing "Custom path…" entry that
 * reveals a free-text input for paths not in the registry (e.g., a sideloaded
 * `.tflite` the user hasn't added to the LocalModelRepository yet).
 *
 * **Active sentinel.** By rule,
 * an empty [LiteRtConfig.modelId] means "resolve to whichever model is active
 * at execute time". Previously the picker eagerly wrote the current active id
 * into the field on open via `LaunchedEffect`, which froze the pipeline to that
 * specific id even after the user switched the active model in Settings; the
 * pipeline would then error out with "Model file not found". With the explicit
 * sentinel the empty value is the persisted choice — the executor's
 * `LoadModelUseCase(modelPath = null)` path picks up the current active model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(
    config: LiteRtConfig,
    error: ValidationFailure?,
    availableModels: List<LocalModelOption>,
    onChange: (NodeConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        FieldLabel(text = stringResource(R.string.knotwork_node_field_model))
        var menuExpanded by remember { mutableStateOf(false) }
        val customLabel = stringResource(R.string.knotwork_node_field_model_custom)
        val activeLabel = stringResource(R.string.knotwork_node_field_model_active)
        val activeSuffixFmt = stringResource(R.string.knotwork_node_field_model_active_suffix)
        // Custom mode mirrors ToolPicker: tracks whether the user is editing a path that
        // isn't in `availableModels`. The catalog NodeConfig schema only has
        // `modelId: String`, so we derive the mode locally rather than storing a sentinel.
        var customMode by remember(config.modelId, availableModels) {
            mutableStateOf(
                config.modelId.isNotBlank() && availableModels.none { it.id == config.modelId },
            )
        }
        // Render the registered model in the dropdown anchor; fall back to the raw id
        // for custom paths; for a blank field render the "Active model" sentinel
        // label instead of a placeholder so the choice reads as a real selection.
        val matchedModel = availableModels.firstOrNull { it.id == config.modelId }
        val selectedLabel = when {
            config.modelId.isBlank() -> activeLabel
            matchedModel != null -> {
                if (matchedModel.isActive) {
                    activeSuffixFmt.format(matchedModel.displayName)
                } else {
                    matchedModel.displayName
                }
            }
            customMode || config.modelId.isNotBlank() -> config.modelId.ifBlank { customLabel }
            else -> activeLabel
        }

        if (availableModels.isNotEmpty()) {
            ExposedDropdownMenuBox(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedLabel,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    isError = error != null,
                    textStyle = KnotworkTextStyles.MonoBase,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    // Sentinel: blank `modelId` resolves to the live active
                    // model at execute time.
                    DropdownMenuItem(
                        text = { Text(text = activeLabel, style = KnotworkTextStyles.MonoBase) },
                        onClick = {
                            customMode = false
                            onChange(config.copy(modelId = ""))
                            menuExpanded = false
                        },
                    )
                    availableModels.sortedByDescending { it.isActive }.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                val label = if (option.isActive) {
                                    activeSuffixFmt.format(option.displayName)
                                } else {
                                    option.displayName
                                }
                                Text(text = label, style = KnotworkTextStyles.MonoBase)
                            },
                            onClick = {
                                customMode = false
                                onChange(config.copy(modelId = option.id))
                                menuExpanded = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(text = customLabel) },
                        onClick = {
                            customMode = true
                            menuExpanded = false
                        },
                    )
                }
            }
        }
        if (customMode || availableModels.isEmpty()) {
            OutlinedTextField(
                value = config.modelId,
                onValueChange = { next -> onChange(config.copy(modelId = next)) },
                singleLine = true,
                isError = error != null,
                textStyle = KnotworkTextStyles.MonoBase,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        InlineError(failure = error)
    }
}

@Composable
private fun ToolFormBody(
    config: ToolConfig,
    errors: Map<FieldId, ValidationFailure>,
    onChange: (NodeConfig) -> Unit,
    availableToolIds: List<String>,
) {
    ToolPicker(
        config = config,
        error = errors[FieldId.TOOL_ID],
        availableToolIds = availableToolIds,
        onChange = onChange,
    )
    FieldCaption(text = stringResource(R.string.knotwork_node_tool_arguments_help))
    ToggleRowField(
        label = stringResource(R.string.knotwork_node_field_always_confirm),
        checked = config.alwaysConfirm,
        onChange = { next -> onChange(config.copy(alwaysConfirm = next)) },
    )
    FieldCaption(text = stringResource(R.string.knotwork_node_field_always_confirm_help))
    EngineProviderRow(config.engineProvider) { next -> onChange(config.copy(engineProvider = next)) }
}

@Composable
@Suppress("LongParameterList")
private fun DecompositionFormBody(
    config: DecompositionConfig,
    errors: Map<FieldId, ValidationFailure>,
    onChange: (NodeConfig) -> Unit,
    onPickFromLibrary: PromptLibraryHook?,
    onSavePreset: SavePresetHook?,
) {
    TextField(
        label = stringResource(R.string.knotwork_node_field_planning_prompt),
        value = config.planningPrompt,
        error = errors[FieldId.PLANNING_PROMPT],
        singleLine = false,
        onChange = { next -> onChange(config.copy(planningPrompt = next)) },
        libraryCategory = "DECOMPOSITION",
        onPickFromLibrary = onPickFromLibrary,
        onSavePreset = onSavePreset,
    )
    VariableChipsRow(onInsert = { variable ->
        onChange(config.copy(planningPrompt = config.planningPrompt + variable))
    })
    IntSliderField(
        label = stringResource(R.string.knotwork_node_field_max_subtasks),
        value = config.maxSubtasks,
        range = 1..20,
        error = errors[FieldId.MAX_SUBTASKS],
        onChange = { next -> onChange(config.copy(maxSubtasks = next)) },
    )
    EngineProviderRow(config.engineProvider) { next -> onChange(config.copy(engineProvider = next)) }
}

@Composable
private fun QueueProcessorFormBody(config: QueueProcessorConfig, onChange: (NodeConfig) -> Unit) {
    FieldCaption(text = stringResource(R.string.knotwork_node_queue_source_help))
    ToggleRowField(
        label = stringResource(R.string.knotwork_node_field_stop_on_error),
        checked = config.stopOnError,
        onChange = { next -> onChange(config.copy(stopOnError = next)) },
    )
}

@Composable
@Suppress("LongParameterList")
private fun EvaluationFormBody(
    config: EvaluationConfig,
    errors: Map<FieldId, ValidationFailure>,
    onChange: (NodeConfig) -> Unit,
    onPickFromLibrary: PromptLibraryHook?,
    onSavePreset: SavePresetHook?,
) {
    TextField(
        label = stringResource(R.string.knotwork_node_field_criteria_prompt),
        value = config.criteriaPrompt,
        error = errors[FieldId.CRITERIA_PROMPT],
        singleLine = false,
        onChange = { next -> onChange(config.copy(criteriaPrompt = next)) },
        libraryCategory = "EVALUATION",
        onPickFromLibrary = onPickFromLibrary,
        onSavePreset = onSavePreset,
    )
    VariableChipsRow(onInsert = { variable ->
        onChange(config.copy(criteriaPrompt = config.criteriaPrompt + variable))
    })
    IntSliderField(
        label = stringResource(R.string.knotwork_node_field_max_retries),
        value = config.maxRetries,
        range = 0..5,
        error = errors[FieldId.MAX_RETRIES],
        onChange = { next -> onChange(config.copy(maxRetries = next)) },
    )
    EngineProviderRow(config.engineProvider) { next -> onChange(config.copy(engineProvider = next)) }
}

@Composable
@Suppress("LongParameterList")
private fun SummaryFormBody(
    config: SummaryConfig,
    errors: Map<FieldId, ValidationFailure>,
    onChange: (NodeConfig) -> Unit,
    onPickFromLibrary: PromptLibraryHook?,
    onSavePreset: SavePresetHook?,
) {
    // The prompt is the only lever the executor reads, so shape and length are
    // asked for in it. Always editable now: it used to be gated behind a
    // `format == CUSTOM` chip row that decided nothing, which meant the one
    // field that worked was hidden behind two that did not.
    TextField(
        label = stringResource(R.string.knotwork_node_field_custom_prompt),
        value = config.customPrompt.orEmpty(),
        error = errors[FieldId.CUSTOM_PROMPT],
        singleLine = false,
        onChange = { next -> onChange(config.copy(customPrompt = next.takeIf { it.isNotBlank() })) },
        libraryCategory = "SUMMARY",
        onPickFromLibrary = onPickFromLibrary,
        onSavePreset = onSavePreset,
    )
    VariableChipsRow(onInsert = { variable ->
        onChange(config.copy(customPrompt = (config.customPrompt.orEmpty() + variable)))
    })
    FieldCaption(text = stringResource(R.string.knotwork_node_summary_shape_help))
}

/**
 * Form body for [NodeType.PIPELINE]. A single field: the target-pipeline
 * [PipelinePicker]. Composition validity (cycles / depth / missing target)
 * is enforced by the domain validator at persist time; the form only blocks
 * Save while no target is chosen (see [NodeConfigValidation.validatePipeline]).
 *
 * @param config the working PIPELINE configuration.
 * @param errors validator output; the picker reads [FieldId.TARGET_PIPELINE_ID].
 * @param onChange emits the next config when the user picks a target.
 * @param availablePipelines the classified candidate targets supplied by the
 * app (every saved pipeline except cycle/depth-creating ones, which arrive
 * disabled with a reason).
 */
@Composable
private fun PipelineFormBody(
    config: PipelineConfig,
    errors: Map<FieldId, ValidationFailure>,
    onChange: (NodeConfig) -> Unit,
    availablePipelines: List<PipelineTargetOption>,
) {
    PipelinePicker(
        config = config,
        error = errors[FieldId.TARGET_PIPELINE_ID],
        availablePipelines = availablePipelines,
        onChange = onChange,
    )
}

/**
 * Target-pipeline selector for [PipelineFormBody]. Surfaces an
 * `ExposedDropdownMenu` over [availablePipelines]: selectable rows commit the
 * id into [PipelineConfig.targetPipelineId]; non-selectable rows render
 * disabled with a per-reason message (so the user sees *why* a pipeline can't
 * be chosen and how to fix it). When nothing is selectable the picker shows
 * the empty-state rationale in place of any clickable row.
 *
 * The catalog never resolves ids or walks the call graph — the app does that
 * once and passes the classified [PipelineTargetOption]s down.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PipelinePicker(
    config: PipelineConfig,
    error: ValidationFailure?,
    availablePipelines: List<PipelineTargetOption>,
    onChange: (NodeConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        FieldLabel(text = stringResource(R.string.knotwork_node_field_target_pipeline))
        if (availablePipelines.isEmpty()) {
            EmptyPipelinePickerNote()
        } else {
            var menuExpanded by remember { mutableStateOf(false) }
            val placeholder = stringResource(R.string.knotwork_node_field_target_pipeline_none)
            // Prefer the app-resolved name; fall back to the option list, then
            // the raw id, then the placeholder when nothing is set.
            val selectedLabel = config.targetPipelineName
                ?: availablePipelines.firstOrNull { it.id == config.targetPipelineId }?.name
                ?: config.targetPipelineId.ifBlank { placeholder }
            val anySelectable = availablePipelines.any { it.selectable }
            ExposedDropdownMenuBox(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedLabel,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    isError = error != null,
                    textStyle = KnotworkTextStyles.MonoBase,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    if (!anySelectable) {
                        DropdownMenuItem(
                            enabled = false,
                            text = { EmptyPipelinePickerNote() },
                            onClick = {},
                        )
                    }
                    availablePipelines.forEach { option ->
                        DropdownMenuItem(
                            enabled = option.selectable,
                            text = { PipelineOptionRow(option) },
                            leadingIcon = if (!option.selectable) {
                                {
                                    Icon(
                                        imageVector = AppIcons.Block,
                                        contentDescription = null,
                                        tint = KnotworkTheme.extended.signalError,
                                        modifier = Modifier.size(PICKER_REASON_ICON_DP.dp),
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = {
                                onChange(
                                    config.copy(
                                        targetPipelineId = option.id,
                                        targetPipelineName = option.name,
                                    ),
                                )
                                menuExpanded = false
                            },
                        )
                    }
                }
            }
        }
        FieldCaption(text = stringResource(R.string.knotwork_node_field_target_pipeline_help))
        InlineError(failure = error)
    }
}

/** One picker row — pipeline name plus, when disabled, the red reason line. */
@Composable
private fun PipelineOptionRow(option: PipelineTargetOption) {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        Text(text = option.name, style = KnotworkTextStyles.MonoBase)
        pipelineDisabledReasonText(option)?.let { reason ->
            Text(
                text = reason,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.signalError,
            )
        }
    }
}

/** Resolves the per-reason disabled message; `null` for a selectable option. */
@Composable
private fun pipelineDisabledReasonText(option: PipelineTargetOption): String? = when (option.disabledReason) {
    PipelineTargetDisabledReason.SELF ->
        stringResource(R.string.knotwork_node_pipeline_picker_self)
    PipelineTargetDisabledReason.CYCLE ->
        stringResource(R.string.knotwork_node_pipeline_picker_cycle, option.cycleCulprit ?: option.name)
    PipelineTargetDisabledReason.DEPTH ->
        stringResource(R.string.knotwork_node_pipeline_picker_depth, option.depthLimit ?: 0)
    null -> null
}

/** Empty-state note shown when no pipeline can be selected as a target. */
@Composable
private fun EmptyPipelinePickerNote() {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        Text(
            text = stringResource(R.string.knotwork_node_pipeline_picker_empty_title),
            style = KnotworkTextStyles.LabelMd,
            color = KnotworkTheme.extended.onSurface2,
        )
        FieldCaption(text = stringResource(R.string.knotwork_node_pipeline_picker_empty_body))
    }
}

/**
 * Form body for [NodeType.SKILL]. Four controls: the skill [SkillPicker], a
 * read-only instruction preview + allowlist indicator for the chosen skill, the
 * inference-engine segmented control, and the "always ask" switch the TOOL form
 * carries too — a skill dispatches tools, so a pipeline author can require a
 * confirmation on every call it makes. The instruction and allowlist are edited
 * in the Skill library, never here; the form only chooses *which* skill, *which
 * engine* runs it, and whether its tool calls always ask.
 *
 * @param config the working SKILL configuration.
 * @param errors validator output; the picker reads [FieldId.SKILL_ID].
 * @param onChange emits the next config when the user picks a skill, an engine or flips the switch.
 * @param availableSkills the skill library rows supplied by the app.
 */
@Composable
private fun SkillFormBody(
    config: SkillConfig,
    errors: Map<FieldId, ValidationFailure>,
    onChange: (NodeConfig) -> Unit,
    availableSkills: List<SkillOption>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
        SkillPicker(
            config = config,
            error = errors[FieldId.SKILL_ID],
            availableSkills = availableSkills,
            onChange = onChange,
        )
        if (config.skillId.isNotBlank()) {
            SkillReadOnlyDetails(config)
        }
        SegmentedChipRow(
            label = stringResource(R.string.knotwork_node_field_skill_engine),
            values = listOf(
                SkillEngine.LITE_RT to stringResource(R.string.knotwork_node_skill_engine_lite_rt),
                SkillEngine.CLOUD to stringResource(R.string.knotwork_node_skill_engine_cloud),
            ),
            selected = config.engine,
            onSelect = { engine -> onChange(config.copy(engine = engine)) },
        )
        ToggleRowField(
            label = stringResource(R.string.knotwork_node_field_always_confirm),
            checked = config.alwaysConfirm,
            onChange = { next -> onChange(config.copy(alwaysConfirm = next)) },
        )
        FieldCaption(text = stringResource(R.string.knotwork_node_field_always_confirm_help))
    }
}

/**
 * Skill selector for [SkillFormBody]. An `ExposedDropdownMenu` over
 * [availableSkills]; selecting a row commits the skill id, name, instruction
 * preview, and allowlist summary into the [SkillConfig] in one step so the
 * read-only details below update immediately. Shows an empty-state note when
 * the library is empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillPicker(
    config: SkillConfig,
    error: ValidationFailure?,
    availableSkills: List<SkillOption>,
    onChange: (NodeConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        FieldLabel(text = stringResource(R.string.knotwork_node_field_skill))
        if (availableSkills.isEmpty()) {
            EmptySkillPickerNote()
        } else {
            var menuExpanded by remember { mutableStateOf(false) }
            val placeholder = stringResource(R.string.knotwork_node_field_skill_none)
            val selectedLabel = config.skillName
                ?: availableSkills.firstOrNull { it.id == config.skillId }?.name
                ?: config.skillId.ifBlank { placeholder }
            ExposedDropdownMenuBox(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedLabel,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    isError = error != null,
                    textStyle = KnotworkTextStyles.MonoBase,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    availableSkills.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(text = option.name, style = KnotworkTextStyles.MonoBase) },
                            onClick = {
                                onChange(
                                    config.copy(
                                        skillId = option.id,
                                        skillName = option.name,
                                        instructionPreview = option.instructionPreview,
                                        toolRestrictionSummary = option.toolRestrictionSummary,
                                    ),
                                )
                                menuExpanded = false
                            },
                        )
                    }
                }
            }
        }
        FieldCaption(text = stringResource(R.string.knotwork_node_field_skill_help))
        InlineError(failure = error)
    }
}

/** Read-only instruction preview + allowlist indicator for the chosen skill. */
@Composable
private fun SkillReadOnlyDetails(config: SkillConfig) {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        config.toolRestrictionSummary?.let { summary ->
            FieldLabel(text = stringResource(R.string.knotwork_node_field_skill_allowlist))
            KnotworkChip(
                label = summary,
                selected = false,
                onClick = null,
                style = ChipStyle.Outline,
                leadingIcon = AppIcons.Skill,
            )
        }
        FieldLabel(text = stringResource(R.string.knotwork_node_field_skill_instruction))
        OutlinedTextField(
            value = config.instructionPreview.orEmpty(),
            onValueChange = {},
            readOnly = true,
            textStyle = KnotworkTextStyles.MonoBase,
            modifier = Modifier.fillMaxWidth(),
        )
        FieldCaption(text = stringResource(R.string.knotwork_node_field_skill_instruction_help))
    }
}

/** Empty-state note shown when the skill library has no skills to pick. */
@Composable
private fun EmptySkillPickerNote() {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        Text(
            text = stringResource(R.string.knotwork_node_skill_picker_empty_title),
            style = KnotworkTextStyles.LabelMd,
            color = KnotworkTheme.extended.onSurface2,
        )
        FieldCaption(text = stringResource(R.string.knotwork_node_skill_picker_empty_body))
    }
}

/** Leading reason-icon diameter in a disabled picker row. */
private const val PICKER_REASON_ICON_DP: Float = 16f

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
