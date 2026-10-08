package app.knotwork.design.components.pipelineeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.chips.ChipStyle
import app.knotwork.design.components.chips.KnotworkChip
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

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

@Composable
internal fun ToolFormBody(
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
internal fun PipelineFormBody(
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
internal fun SkillFormBody(
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
