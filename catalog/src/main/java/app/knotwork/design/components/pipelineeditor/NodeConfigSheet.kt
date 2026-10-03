package app.knotwork.design.components.pipelineeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.components.chips.ChipStyle
import app.knotwork.design.components.chips.KnotworkChip
import app.knotwork.design.theme.KnotworkTheme

/**
 * Modal bottom sheet that hosts the per-type configuration form for one
 * pipeline node.
 *
 * The
 * sheet's chrome (drag handle / type pill / scrollable body / sticky
 * Cancel-Save row) is fixed; the body switches on `when (config)` via
 * [NodeConfigForms.Body] and dispatches to the per-type form. Save is
 * gated on [NodeConfigValidation.validate] — if any rule fails the
 * inline error surfaces under the offending field and the primary CTA
 * renders disabled.
 *
 * **Stateless** — the caller owns the working [config] value and the
 * dirty/clean transitions. The sheet calls [onChange] for every keystroke
 * / chip toggle, [onCancel] when the user dismisses without saving, and
 * [onSave] with the validated payload when Save is pressed.
 *
 * @param config the working configuration being edited.
 * @param peerTitles set of sibling node titles in the same pipeline,
 * excluding the one currently being edited; drives the uniqueness rule.
 * @param onChange invoked with the next [NodeConfig] every time the user
 * edits a field.
 * @param onCancel invoked when the user dismisses the sheet.
 * @param onSave invoked with [config] when the user taps Save and
 * validation passes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeConfigSheet(
    config: NodeConfig,
    peerTitles: Set<String>,
    onChange: (NodeConfig) -> Unit,
    onCancel: () -> Unit,
    onSave: (NodeConfig) -> Unit,
    availableToolIds: List<String> = emptyList(),
    availableModels: List<LocalModelOption> = emptyList(),
    availablePipelines: List<PipelineTargetOption> = emptyList(),
    availableSkills: List<SkillOption> = emptyList(),
    onPickFromLibrary: ((category: String, currentPrompt: String, apply: (String) -> Unit) -> Unit)? = null,
    onSavePreset: ((category: String, currentPrompt: String) -> Unit)? = null,
    extraSection: (@Composable () -> Unit)? = null,
) {
    // `skipPartiallyExpanded = true` opens the sheet at its full height immediately
    // (no half-expanded state). Tall configs — IntentRouter with several classes, LiteRt
    // with stop-tokens + prompt + sliders, Tool with many argument rows — would
    // otherwise hide the Save row at the bottom in the partial state, requiring an
    // extra drag-up before the user could see it.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val errors = NodeConfigValidation.validate(config = config, peerTitles = peerTitles)
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheetState) {
        ScrollableNodeConfigSheetBody(
            config = config,
            errors = errors,
            onChange = onChange,
            onCancel = onCancel,
            onSave = onSave,
            availableToolIds = availableToolIds,
            availableModels = availableModels,
            availablePipelines = availablePipelines,
            availableSkills = availableSkills,
            onPickFromLibrary = onPickFromLibrary,
            onSavePreset = onSavePreset,
            extraSection = extraSection,
        )
    }
}

/**
 * Variant of [NodeConfigSheetBody] for the modal sheet — wraps the entire body in a
 * single scrollable Column so tall configs (e.g., IntentRouter after adding several
 * classes) can reach the Save action by scrolling down to it.
 *
 * The action row scrolls with the content instead of being pinned. The earlier
 * "weight(1f, fill = false) + verticalScroll on the body + action row pinned outside"
 * arrangement is correct in principle but can drive Compose into a measurement /
 * layout loop on real devices (LayoutCoordinates from `onGloballyPositioned` re-fires
 * every layout pass, the weighted child re-measures, etc.), which manifested as an
 * input-dispatch ANR. A single scrolling Column avoids the combo entirely.
 *
 * Lives inside the [NodeConfigSheet] wrapper rather than replacing [NodeConfigSheetBody]
 * because the catalog harness ([PipelineEditorCatalogContent]) embeds the body inside
 * an unbounded Column — `Modifier.verticalScroll` requires bounded height and would
 * crash at measure time there.
 */
@Composable
private fun ScrollableNodeConfigSheetBody(
    config: NodeConfig,
    errors: Map<FieldId, ValidationFailure>,
    onChange: (NodeConfig) -> Unit,
    onCancel: () -> Unit,
    onSave: (NodeConfig) -> Unit,
    availableToolIds: List<String>,
    availableModels: List<LocalModelOption>,
    availablePipelines: List<PipelineTargetOption>,
    availableSkills: List<SkillOption>,
    onPickFromLibrary: ((category: String, currentPrompt: String, apply: (String) -> Unit) -> Unit)?,
    onSavePreset: ((category: String, currentPrompt: String) -> Unit)?,
    extraSection: (@Composable () -> Unit)?,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
    ) {
        SheetHeader(type = config.type)
        NodeConfigForms.Body(
            config = config,
            errors = errors,
            onChange = onChange,
            availableToolIds = availableToolIds,
            availableModels = availableModels,
            availablePipelines = availablePipelines,
            availableSkills = availableSkills,
            onPickFromLibrary = onPickFromLibrary,
            onSavePreset = onSavePreset,
        )
        // App-provided section that the catalog isn't allowed to model (e.g.
        // `NodeContextConfigSection` — depends on a domain `NodeContextConfig`).
        // Rendered between the form body and the action row so it reads as
        // part of the configuration without making the catalog atom domain-aware.
        extraSection?.invoke()
        Spacer(modifier = Modifier.size(KnotworkTheme.spacing.sp2))
        SheetActionRow(
            saveEnabled = errors.isEmpty(),
            onCancel = onCancel,
            onSave = { onSave(config) },
        )
    }
}

/**
 * Renders the sheet body without the `ModalBottomSheet` wrapper. Pulled
 * out so the catalog can render the same body inline (without an Activity
 * window context) and so snapshot tests render deterministically without
 * the modal scrim.
 *
 * @param config the working configuration being edited.
 * @param errors validator output for [config].
 * @param onChange invoked with the next [NodeConfig] on every edit.
 * @param onCancel invoked on the Cancel action.
 * @param onSave invoked on the Save action when [errors] is empty.
 */
@Composable
fun NodeConfigSheetBody(
    config: NodeConfig,
    errors: Map<FieldId, ValidationFailure>,
    onChange: (NodeConfig) -> Unit,
    onCancel: () -> Unit,
    onSave: (NodeConfig) -> Unit,
    availableToolIds: List<String> = emptyList(),
    availableModels: List<LocalModelOption> = emptyList(),
    availablePipelines: List<PipelineTargetOption> = emptyList(),
    availableSkills: List<SkillOption> = emptyList(),
    onPickFromLibrary: ((category: String, currentPrompt: String, apply: (String) -> Unit) -> Unit)? = null,
    onSavePreset: ((category: String, currentPrompt: String) -> Unit)? = null,
    extraSection: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
    ) {
        SheetHeader(type = config.type)
        NodeConfigForms.Body(
            config = config,
            errors = errors,
            onChange = onChange,
            availableToolIds = availableToolIds,
            availableModels = availableModels,
            availablePipelines = availablePipelines,
            availableSkills = availableSkills,
            onPickFromLibrary = onPickFromLibrary,
            onSavePreset = onSavePreset,
        )
        extraSection?.invoke()
        Spacer(modifier = Modifier.size(KnotworkTheme.spacing.sp2))
        SheetActionRow(saveEnabled = errors.isEmpty(), onCancel = onCancel, onSave = { onSave(config) })
    }
}

/** Type pill rendered above the form body. */
@Composable
private fun SheetHeader(type: NodeType) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        KnotworkChip(label = type.displayLabel(), style = ChipStyle.Tonal)
    }
}

/** Bottom sticky row — Cancel + Save (Save gated on validation). */
@Composable
private fun SheetActionRow(
    saveEnabled: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(
            space = KnotworkTheme.spacing.sp2,
            alignment = Alignment.End,
        ),
    ) {
        KnotworkTextButton(
            text = stringResource(R.string.knotwork_node_config_action_cancel),
            onClick = onCancel,
        )
        KnotworkPrimaryButton(
            text = stringResource(R.string.knotwork_node_config_action_save),
            onClick = onSave,
            enabled = saveEnabled,
            size = KnotworkButtonSize.Sm,
        )
    }
}
