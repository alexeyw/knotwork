package app.knotwork.android.presentation.ui.pipeline.editor.sheet

import androidx.compose.runtime.Composable
import app.knotwork.design.components.pipelineeditor.LocalModelOption
import app.knotwork.design.components.pipelineeditor.NodeConfig
import app.knotwork.design.components.pipelineeditor.NodeConfigSheet
import app.knotwork.design.components.pipelineeditor.PipelineTargetOption
import app.knotwork.design.components.pipelineeditor.SkillOption

/**
 * Thin adapter around the catalog [NodeConfigSheet] that the pipeline editor uses to
 * configure a single node.
 *
 * The catalog ships the modal-sheet chrome, the per-type form bodies (all 12
 * `NodeConfigForms`), and the pure-Kotlin [app.knotwork.design.components.pipelineeditor.NodeConfigValidation]
 * gate. This host is what binds it to the production editor.
 *
 * @param config the working [NodeConfig] (decoded from `NodeModel.configJson` via
 * [app.knotwork.android.presentation.ui.pipeline.editor.config.NodeConfigCodec.decode]).
 * @param peerTitles set of sibling node titles in the pipeline, excluding [config.title].
 * Drives the catalog's pipeline-wide title-uniqueness rule.
 * @param onChange invoked for every form edit; the editor mirrors the working value
 * into `EditorState.workingConfig` so save commits the latest state.
 * @param onCancel invoked when the sheet is dismissed without saving.
 * @param onSave invoked with the final [NodeConfig] when validation passes and the user
 * taps Save. The editor encodes it via [NodeConfigCodec.apply] and dispatches to the
 * ViewModel for persistence.
 */
@Composable
internal fun NodeConfigSheetHost(
    config: NodeConfig,
    peerTitles: Set<String>,
    onChange: (NodeConfig) -> Unit,
    onCancel: () -> Unit,
    onSave: (NodeConfig) -> Unit,
    availableToolIds: List<String>,
    availableModels: List<LocalModelOption>,
    onPickFromLibrary: (category: String, currentPrompt: String, apply: (String) -> Unit) -> Unit,
    onSavePreset: (category: String, currentPrompt: String) -> Unit,
    availablePipelines: List<PipelineTargetOption> = emptyList(),
    availableSkills: List<SkillOption> = emptyList(),
    extraSection: (@Composable () -> Unit)? = null,
) {
    NodeConfigSheet(
        config = config,
        peerTitles = peerTitles,
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
