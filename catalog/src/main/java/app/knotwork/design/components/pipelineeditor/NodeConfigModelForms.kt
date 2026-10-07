package app.knotwork.design.components.pipelineeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.knotwork.design.R
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

@Composable
internal fun OutputFormBody(
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
internal fun LiteRtFormBody(
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
internal fun CloudFormBody(
    config: CloudConfig,
    errors: Map<FieldId, ValidationFailure>,
    onChange: (NodeConfig) -> Unit,
    onPickFromLibrary: PromptLibraryHook?,
    onSavePreset: SavePresetHook?,
) {
    CloudProviderField(selected = config.provider, onSelect = { next -> onChange(config.copy(provider = next)) })
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
internal fun DecompositionFormBody(
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
internal fun EvaluationFormBody(
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
internal fun SummaryFormBody(
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
