package app.knotwork.android.presentation.ui.pipeline.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineValidationError
import app.knotwork.android.presentation.ui.pipeline.editor.bars.MultiSelectToolbar
import app.knotwork.android.presentation.ui.pipeline.editor.bars.ValidationBar
import app.knotwork.android.presentation.ui.pipeline.editor.canvas.EditorCanvas
import app.knotwork.android.presentation.ui.pipeline.editor.core.EditorState
import app.knotwork.design.components.pipelineeditor.EditorToolbar
import app.knotwork.design.components.pipelineeditor.NodeError

/**
 * Pure-layout content for the [PipelineEditorScreen] — caller provides the live state
 * and lambdas; this composable owns no Hilt / navigation dependencies and so is the
 * deterministic anchor for snapshot tests.
 *
 * Vertical stack: top toolbar (or multi-select bar) → editor canvas → validation bar
 * (or run-trace bar when a run is in progress).
 *
 * The toolbar follows a `[← back] [title +
 * subtitle] [primary action] [overflow]` layout. Undo / Redo / Delete /
 * Auto-layout have moved into the overflow `DropdownMenu` owned by
 * [PipelineEditorScreen] — keeping that lookup table out of this pure-layout
 * composable so the layout stays deterministic for snapshot tests regardless of
 * menu state.
 *
 * @param graph the current pipeline graph from the ViewModel.
 * @param editor the screen-local [EditorState] (gesture, selection, drafts, undo/redo).
 * @param validationErrors validation rule output from `PipelineGraph.validate()`.
 * @param validationLabels per-error human-readable copy (typically resolved from the
 * ViewModel's `labelFor` so wording stays single-sourced with the save-time toast).
 * @param errorsByNodeId map of `nodeId -> NodeError?` for the canvas to render the
 * inline error border / icon on the matching [app.knotwork.design.components.pipelineeditor.NodeCard].
 * @param reducedMotion reduced-motion flag — gates animations longer than `motionSm`.
 * @param unsavedChanges whether the editor holds work that is not in storage;
 * drives the toolbar marker and, on the screen, the leave guard.
 * @param toolbarSubtitle subtitle line under the pipeline name — pre-computed by
 * the screen from validation / node count / mini-map state.
 * @param onPipelineNameChange invoked when the inline name field accepts input.
 * @param onNavigateUp invoked when the leading back icon is tapped.
 * @param onOverflow `EditorToolbar` overflow tap — screen opens its own
 * `DropdownMenu` from here.
 * @param onMoveNode forwarded from the canvas drag handler — commits the canvas-space delta.
 * @param onAddNode forwarded from the radial quick-add menu.
 * @param onAddConnection forwarded from a connection-draft drop onto an inbound port.
 * @param onConnectionDropped forwarded when a connection drag ends without a valid target.
 * @param onFocusNode forwarded from a `ValidationBar` row tap.
 * @param onMultiSelectCancel exits multi-select without acting.
 * @param onMultiSelectDelete removes every multi-selected node + their connections.
 * @param snackbarHost the screen's snackbar host, drawn at the bottom of the canvas area —
 *   above the [ValidationBar], which a host at the bottom of the screen used to cover.
 */
@Composable
@Suppress("LongParameterList")
internal fun PipelineEditorContent(
    graph: PipelineGraph,
    editor: EditorState,
    validationErrors: List<PipelineValidationError>,
    validationLabels: List<String>,
    errorsByNodeId: Map<String, NodeError?>,
    reducedMotion: Boolean,
    toolbarSubtitle: String?,
    unsavedChanges: Boolean,
    onPipelineNameChange: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onOverflow: () -> Unit,
    onMoveNode: (nodeId: String, dxCanvas: Float, dyCanvas: Float) -> Unit,
    onAddNode: (type: NodeType, canvasX: Float, canvasY: Float) -> Unit,
    onAddConnection: (sourceNodeId: String, targetNodeId: String, label: String?) -> Unit,
    onConnectionDropped: () -> Unit,
    onOpenNodeConfig: (nodeId: String) -> Unit,
    onLongPressEdge: (connectionId: String) -> Unit,
    onStartWithInput: () -> Unit,
    onFromTemplate: () -> Unit,
    onFocusNode: (String) -> Unit,
    onAutoFix: () -> Unit,
    onMultiSelectCancel: () -> Unit,
    onMultiSelectCopy: () -> Unit,
    onMultiSelectDelete: () -> Unit,
    modifier: Modifier = Modifier,
    subtitleForNode: (NodeModel) -> String? = { null },
    snackbarHost: @Composable () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (editor.multiSelectMode && editor.selection.isNotEmpty()) {
            MultiSelectToolbar(
                count = editor.selection.size,
                onCancel = onMultiSelectCancel,
                onCopy = onMultiSelectCopy,
                onDelete = onMultiSelectDelete,
            )
        } else {
            EditorToolbar(
                name = graph.name,
                onNameChange = onPipelineNameChange,
                onNavigateUp = onNavigateUp,
                onOverflow = onOverflow,
                subtitle = toolbarSubtitle,
                unsavedChanges = unsavedChanges,
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            EditorCanvas(
                graph = graph,
                editor = editor,
                errorsByNodeId = errorsByNodeId,
                reducedMotion = reducedMotion,
                onMoveNode = onMoveNode,
                onAddNode = onAddNode,
                onAddConnection = onAddConnection,
                onConnectionDropped = onConnectionDropped,
                onOpenNodeConfig = onOpenNodeConfig,
                onLongPressEdge = onLongPressEdge,
                onStartWithInput = onStartWithInput,
                onFromTemplate = onFromTemplate,
                subtitleForNode = subtitleForNode,
                modifier = Modifier.fillMaxSize(),
            )
            Box(modifier = Modifier.align(Alignment.BottomCenter)) { snackbarHost() }
        }

        // ValidationBar at the bottom reports validation state so the user
        // always sees the save gate.
        ValidationBar(
            graph = graph,
            errors = validationErrors,
            errorLabels = validationLabels,
            nodeLookup = { id -> graph.nodes.find { it.id == id }?.label },
            onFocusNode = onFocusNode,
            onAutoFix = onAutoFix,
        )
    }
}
