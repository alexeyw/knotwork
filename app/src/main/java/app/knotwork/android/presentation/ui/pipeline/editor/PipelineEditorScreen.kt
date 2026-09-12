package app.knotwork.android.presentation.ui.pipeline.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.knotwork.android.R
import app.knotwork.android.domain.constants.DocumentationLinks
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineTargetAvailability
import app.knotwork.android.domain.models.Skill
import app.knotwork.android.presentation.ui.common.openDocumentation
import app.knotwork.android.presentation.ui.common.resolve
import app.knotwork.android.presentation.ui.components.PromptPreviewBottomSheet
import app.knotwork.android.presentation.ui.orchestrator.OrchestratorViewModel
import app.knotwork.android.presentation.ui.orchestrator.PromptPreviewState
import app.knotwork.android.presentation.ui.orchestrator.components.NodeContextConfigSection
import app.knotwork.android.presentation.ui.orchestrator.components.PromptPresetPickerDialog
import app.knotwork.android.presentation.ui.orchestrator.components.SavePromptAsPresetDialog
import app.knotwork.android.presentation.ui.orchestrator.presets.PipelinePresetsViewModel
import app.knotwork.android.presentation.ui.orchestrator.presets.PresetPickerSheet
import app.knotwork.android.presentation.ui.orchestrator.presets.SaveAsPresetDialog
import app.knotwork.android.presentation.ui.pipeline.editor.canvas.formatScalePercent
import app.knotwork.android.presentation.ui.pipeline.editor.config.NodeConfigCodec
import app.knotwork.android.presentation.ui.pipeline.editor.config.NodeTypeMapper
import app.knotwork.android.presentation.ui.pipeline.editor.core.AutoLayout
import app.knotwork.android.presentation.ui.pipeline.editor.core.Bounds
import app.knotwork.android.presentation.ui.pipeline.editor.core.CanvasTransform
import app.knotwork.android.presentation.ui.pipeline.editor.core.EditorState
import app.knotwork.android.presentation.ui.pipeline.editor.core.ValidationAutoFix
import app.knotwork.android.presentation.ui.pipeline.editor.core.rememberEditorState
import app.knotwork.android.presentation.ui.pipeline.editor.sheet.NodeConfigSheetHost
import app.knotwork.design.components.dialogs.ConfirmDialog
import app.knotwork.design.components.dialogs.ConfirmDialogUi
import app.knotwork.design.components.dialogs.SingleFieldDialog
import app.knotwork.design.components.dialogs.SingleFieldDialogUi
import app.knotwork.design.components.pipelineeditor.LocalModelOption
import app.knotwork.design.components.pipelineeditor.PipelineTargetDisabledReason
import app.knotwork.design.components.pipelineeditor.PipelineTargetOption
import app.knotwork.design.components.pipelineeditor.SkillConfig
import app.knotwork.design.components.pipelineeditor.SkillOption
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import kotlinx.coroutines.launch

/**
 * Production pipeline editor — the Knotwork-redesigned canvas surface.
 *
 * Replaces the legacy `VisualOrchestratorScreen`. Shares the `OrchestratorViewModel`
 * scoped to the `pipelines` nested nav-graph with the library screen so a freshly
 * created / loaded pipeline appears here without an extra read.
 *
 * The toolbar follows a `[← back] [title +
 * subtitle] [primary action] [overflow]` layout. The overflow `DropdownMenu`
 * lives here (not in the catalog atom) so the production surface can fold in
 * features (Fit to view, Toggle grid, Find node, …) without churning the
 * catalog API every iteration.
 *
 * Responsibilities:
 *  - Subscribes to the VM's `uiState` + `focusNodeRequest` streams.
 *  - Owns the screen-local [EditorState] (selection / undo / drafts).
 *  - Computes the toolbar subtitle from validation / node count.
 *  - Hosts the [PipelineEditorContent] layout, the overflow menu, the catalog
 *    `NodeConfigSheet`, and the edge-removal confirm dialog.
 *  - Dispatches graph mutations back to the VM (which persists through `SavePipelineUseCase`).
 *
 * @param viewModel the shared orchestrator view model (graph-scoped).
 * @param onBack invoked when the user navigates back to the library. The editor surfaces
 * the back affordance via `BackHandler` AND the toolbar's leading back icon — system
 * back closes any open sheet or multi-select session first, then falls through to
 * this lambda.
 */
@Composable
@Suppress("LongMethod") // The editor screen is the orchestration seam; splitting would hide the data flow.
fun PipelineEditorScreen(viewModel: OrchestratorViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val editor: EditorState = rememberEditorState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Edge to confirm-and-delete on long-press, paired with the toolbar's tap-select-then-Delete
    // path. Two routes to the same action so users find at least one of them.
    var pendingEdgeDelete by remember { mutableStateOf<String?>(null) }
    // When the user taps the 📚 button on a prompt-bearing field inside NodeConfigSheet, the
    // catalog form invokes onPickFromLibrary(category, apply). We stash both here, render the
    // PromptPresetPickerDialog filtered by the node type, and on Apply call back `apply`
    // (the form's "set this field" lambda). Stays as a single state because only one library
    // request can be pending at a time (the sheet is modal and the dialog stacks on top).
    var pendingLibrary by remember { mutableStateOf<PendingPromptLibrary?>(null) }
    // When the user taps the 💾 button on a prompt-bearing field, the catalog form invokes
    // onSavePreset(category, currentPrompt). We stash both here and render
    // SavePromptAsPresetDialog; on confirm the VM dispatches SavePromptAsPresetUseCase.
    var pendingSavePreset by remember { mutableStateOf<PendingSavePromptPreset?>(null) }
    // Overflow DropdownMenu visibility — opened from the toolbar's overflow callback,
    // dismissed by tap-outside or by clicking any menu item.
    var overflowOpen by remember { mutableStateOf(false) }

    // Raised by either exit — system back or the toolbar's back icon — when the
    // editor holds work that is not in storage.
    var leaveRequested by remember { mutableStateOf(false) }
    // Rename-node dialog state — set to the target node id when the user picks
    // "Rename node…" from the overflow menu (requires exactly one node selected).
    var pendingRenameNodeId by remember { mutableStateOf<String?>(null) }
    // Save-as-preset dialog state — true while the dialog is visible, dismissed
    // on cancel or after submission.
    var saveAsPresetOpen by remember { mutableStateOf(false) }
    // Preset-picker sheet visibility — opened from the empty-pipeline state's
    // "From template" CTA. On pick, the preset is materialised into a fresh
    // pipeline and the editor swaps onto it (see the `pendingPipelineIdFromPreset`
    // effect below).
    var showPresetPicker by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.errorMessage) {
        val msg = uiState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message = context.resolve(msg))
        viewModel.clearError()
    }
    LaunchedEffect(uiState.feedbackMessage) {
        val msg = uiState.feedbackMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message = context.resolve(msg))
        viewModel.clearFeedback()
    }
    LaunchedEffect(Unit) {
        viewModel.focusNodeRequest.collect { nodeId ->
            val target = uiState.currentPipeline.nodes.find { it.id == nodeId } ?: return@collect
            editor.selection = setOf(nodeId)
            editor.multiSelectMode = false
            editor.transform = editor.transform.centeredOn(
                x = target.x,
                y = target.y,
                viewportW = 1f,
                viewportH = 1f,
            )
        }
    }

    BackHandler {
        when {
            editor.configuringNodeId != null -> {
                editor.configuringNodeId = null
                editor.workingConfig = null
                editor.workingContextConfig = null
            }
            editor.searchOpen -> {
                // System back is the natural "close search" gesture; the bar's
                // own × button stays as a discoverable alternative. Without
                // this branch, system back was falling through to `onBack` and
                // dragging the user out of the editor entirely.
                editor.searchOpen = false
                editor.searchQuery = ""
            }
            editor.multiSelectMode -> {
                editor.multiSelectMode = false
                editor.selection = emptySet()
            }
            editor.quickAddAnchor != null -> {
                editor.quickAddAnchor = null
            }
            editor.selectedEdgeId != null -> {
                editor.selectedEdgeId = null
            }
            // Leaving with unsaved work is the one exit that used to lose it
            // silently: Save lives in the overflow menu, and nothing on screen
            // said the pipeline had drifted from storage. Ask instead of
            // discarding — the same guard the toolbar's back icon goes through.
            uiState.hasUnsavedChanges -> leaveRequested = true
            else -> onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val validationErrors = uiState.validationErrors
        val validationLabels = remember(validationErrors) {
            validationErrors.map { viewModel.labelFor(it) }
        }
        val pipeline = uiState.currentPipeline
        val toolbarSubtitle = rememberToolbarSubtitle(
            graph = pipeline,
            validationErrorCount = validationErrors.size,
            miniMapOpen = editor.miniMapOpen,
            scale = editor.transform.scale,
        )
        // Pre-resolve snackbar copies at composition time so the action lambdas
        // never read from `Context` (the `LocalContextGetResourceValueCall` lint
        // rule forbids `context.getString` / `getQuantityString` from Composable
        // scope). Wrapping `getString` here is fine because `stringResource`
        // already does the work — we just close over the result.
        val autoFixNoneMessage = stringResource(R.string.pipeline_editor_validation_auto_fix_none)
        val pasteEmptyMessage = stringResource(R.string.pipeline_editor_overflow_paste_empty)
        val autoFixDoneMessage = stringResource(R.string.pipeline_editor_validation_auto_fix_done)
        val connectionDroppedHint = stringResource(R.string.pipeline_editor_connection_dropped_hint)
        val onUndoClick: () -> Unit = {
            val previous = editor.undoRedo.undo(pipeline)
            if (previous != null) viewModel.replaceCurrentPipeline(previous)
        }
        val onRedoClick: () -> Unit = {
            val next = editor.undoRedo.redo(pipeline)
            if (next != null) viewModel.replaceCurrentPipeline(next)
        }
        val onDeleteClick: () -> Unit = {
            val edgeId = editor.selectedEdgeId
            when {
                edgeId != null -> {
                    editor.undoRedo.push(pipeline)
                    viewModel.removeConnection(edgeId)
                    editor.selectedEdgeId = null
                }
                editor.selection.isNotEmpty() -> {
                    editor.undoRedo.push(pipeline)
                    editor.selection.forEach { id -> viewModel.removeNode(id) }
                    editor.selection = emptySet()
                    editor.multiSelectMode = false
                }
            }
        }
        // Auto-layout gaps are authored in dp and converted to canvas-px here, where the
        // screen [Density] is known. `CanvasTransform` maps 1 canvas-unit to 1 screen-px,
        // but a `NodeCard` is sized in dp, so it occupies `cardSizeDp × density` canvas-px
        // on screen — passing a fixed-px gap (as the bare `SIBLING_GAP_X`/`LAYER_GAP_Y`
        // defaults do) lets the cards overlap on any high-density display.
        val density = LocalDensity.current
        val autoLayoutSiblingGapPx = with(density) { AUTO_LAYOUT_SIBLING_GAP.toPx() }
        val autoLayoutLayerGapPx = with(density) { AUTO_LAYOUT_LAYER_GAP.toPx() }
        val onAutoLayoutClick: () -> Unit = autoLayoutClick@{
            editor.undoRedo.push(pipeline)
            val result = AutoLayout.compute(
                graph = pipeline,
                siblingGapPx = autoLayoutSiblingGapPx,
                layerGapPx = autoLayoutLayerGapPx,
            )
            if (result.positions.isEmpty()) return@autoLayoutClick
            // `AutoLayout.compute` emits coordinates anchored at the canvas
            // origin (`(0, 0)` for the seed layer); without an offset the
            // re-laid graph lands in the upper-left corner of the canvas
            // regardless of where the user was looking, which reads as "the
            // editor swallowed my nodes". Translate the freshly computed bbox
            // so its centre lands on the centre of the previously occupied
            // bbox — the user stays put visually and the layout simply
            // tightens around their viewport.
            val originalBbox = Bounds.ofNodes(
                positions = pipeline.nodes.map { it.x to it.y },
                nodeWidth = NODE_CARD_WIDTH_PX,
                nodeHeight = NODE_CARD_HEIGHT_PX,
            )
            val computedBbox = Bounds.ofNodes(
                positions = result.positions.values.toList(),
                nodeWidth = NODE_CARD_WIDTH_PX,
                nodeHeight = NODE_CARD_HEIGHT_PX,
            )
            val dx = if (originalBbox != null && computedBbox != null) {
                (originalBbox.minX + originalBbox.maxX) / 2f -
                    (computedBbox.minX + computedBbox.maxX) / 2f
            } else {
                0f
            }
            val dy = if (originalBbox != null && computedBbox != null) {
                (originalBbox.minY + originalBbox.maxY) / 2f -
                    (computedBbox.minY + computedBbox.maxY) / 2f
            } else {
                0f
            }
            val nextNodes = pipeline.nodes.map { node ->
                val pos = result.positions[node.id] ?: return@map node
                node.copy(
                    x = CanvasTransform.snapToGrid(pos.first + dx),
                    y = CanvasTransform.snapToGrid(pos.second + dy),
                )
            }
            viewModel.replaceCurrentPipeline(pipeline.copy(nodes = nextNodes))
        }

        // PIPELINE-node card subtitle: resolve the target pipeline id to its
        // display name from the saved-pipeline catalogue (or a "no target" /
        // "not found" note). Built here in the composable so the strings come
        // from `stringResource`; every other node type resolves to `null`.
        val pipelineNamesById = remember(uiState.savedPipelines) {
            uiState.savedPipelines.associate { it.id to it.name }
        }
        val noTargetSubtitle = stringResource(R.string.pipeline_editor_node_pipeline_no_target)
        val missingTargetSubtitle = stringResource(R.string.pipeline_editor_node_pipeline_missing)
        val targetSubtitleFormat = stringResource(R.string.pipeline_editor_node_pipeline_target)
        val subtitleForNode = remember(
            pipelineNamesById,
            noTargetSubtitle,
            missingTargetSubtitle,
            targetSubtitleFormat,
        ) {
            { candidate: NodeModel ->
                if (candidate.type == NodeType.PIPELINE) {
                    val target = candidate.targetPipelineId
                    when {
                        target.isNullOrBlank() -> noTargetSubtitle
                        else -> pipelineNamesById[target]
                            ?.let { name -> targetSubtitleFormat.format(name) }
                            ?: missingTargetSubtitle
                    }
                } else {
                    null
                }
            }
        }

        PipelineEditorContent(
            graph = pipeline,
            editor = editor,
            validationErrors = validationErrors,
            validationLabels = validationLabels.map { context.resolve(it) },
            errorsByNodeId = emptyMap(),
            reducedMotion = KnotworkTheme.a11y.reducedMotion(),
            toolbarSubtitle = toolbarSubtitle,
            unsavedChanges = uiState.hasUnsavedChanges,
            onPipelineNameChange = { name ->
                viewModel.replaceCurrentPipeline(pipeline.copy(name = name))
            },
            onNavigateUp = { if (uiState.hasUnsavedChanges) leaveRequested = true else onBack() },
            onOverflow = { overflowOpen = true },
            onMoveNode = { nodeId, dxCanvas, dyCanvas ->
                editor.undoRedo.push(pipeline)
                viewModel.moveNode(nodeId, dxCanvas, dyCanvas)
            },
            onAddNode = { type, canvasX, canvasY ->
                editor.undoRedo.push(pipeline)
                // `addNode` returns the new id synchronously — reading
                // `uiState.currentPipeline.nodes.lastOrNull()` here would observe the
                // pre-update snapshot since the StateFlow hasn't propagated yet.
                val newId = viewModel.addNode(type, canvasX, canvasY)
                editor.configuringNodeId = newId
                editor.workingConfig = NodeConfigCodec.defaultFor(
                    type = NodeTypeMapper.toCatalog(type),
                    title = type.name,
                )
                // Fresh nodes get the all-enabled context config so every
                // available context block flows into the prompt by default.
                editor.workingContextConfig = NodeContextConfig.ALL_ENABLED
            },
            onAddConnection = { sourceId, targetId, label ->
                editor.undoRedo.push(pipeline)
                viewModel.addConnection(sourceId, targetId, label)
            },
            onConnectionDropped = {
                scope.launch { snackbarHostState.showSnackbar(connectionDroppedHint) }
            },
            onOpenNodeConfig = { nodeId ->
                val target = pipeline.nodes.find { it.id == nodeId } ?: return@PipelineEditorContent
                editor.configuringNodeId = nodeId
                editor.workingConfig = NodeConfigCodec.decode(target)
                editor.workingContextConfig = target.contextConfig
            },
            onLongPressEdge = { connectionId -> pendingEdgeDelete = connectionId },
            onStartWithInput = {
                // Place INPUT at canvas origin (0, 0). The radial menu pattern
                // typically anchors to where the user tapped — here the tap was
                // a button, not the canvas, so we fall back to a sensible
                // grid-aligned spot. The user can immediately drag it.
                editor.undoRedo.push(pipeline)
                val newId = viewModel.addNode(NodeType.INPUT, x = 0f, y = 0f)
                editor.configuringNodeId = newId
                editor.workingConfig = NodeConfigCodec.defaultFor(
                    type = NodeTypeMapper.toCatalog(NodeType.INPUT),
                    title = NodeType.INPUT.name,
                )
                editor.workingContextConfig = NodeContextConfig.ALL_ENABLED
            },
            onFromTemplate = { showPresetPicker = true },
            onFocusNode = viewModel::requestFocusNode,
            onAutoFix = {
                val outcome = ValidationAutoFix.apply(pipeline, validationErrors)
                if (outcome.unchanged) {
                    scope.launch { snackbarHostState.showSnackbar(autoFixNoneMessage) }
                } else {
                    editor.undoRedo.push(pipeline)
                    viewModel.replaceCurrentPipeline(outcome.graph)
                    scope.launch { snackbarHostState.showSnackbar(autoFixDoneMessage) }
                }
            },
            onMultiSelectCancel = {
                editor.multiSelectMode = false
                editor.selection = emptySet()
            },
            onMultiSelectCopy = {
                editor.clipboard = pipeline.nodes.filter { it.id in editor.selection }
                editor.multiSelectMode = false
                editor.selection = emptySet()
            },
            onMultiSelectDelete = {
                if (editor.selection.isEmpty()) return@PipelineEditorContent
                editor.undoRedo.push(pipeline)
                editor.selection.forEach { id -> viewModel.removeNode(id) }
                editor.selection = emptySet()
                editor.multiSelectMode = false
            },
            subtitleForNode = subtitleForNode,
            modifier = Modifier.fillMaxSize(),
        )

        // Overflow DropdownMenu — anchored to the top-end of the screen so it drops
        // down from under the toolbar's overflow icon. Wrapper Box gives the menu a
        // positioning anchor; the top offset matches the catalog `EditorToolbar`
        // two-line height (64 dp) so the menu opens flush with the icon's baseline,
        // and falls just slightly under the single-line variant (56 dp) where the
        // ~8 dp gap is visually acceptable.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = KnotworkTheme.spacing.sp1, top = 64.dp),
        ) {
            DropdownMenu(
                expanded = overflowOpen,
                onDismissRequest = { overflowOpen = false },
            ) {
                // Explicit Save sits at the top of the overflow so the action is
                // discoverable. Most graph mutations currently update the in-memory
                // `currentPipeline` via `replaceCurrentPipeline` but don't persist
                // to disk; this item is the user's only "write to disk" lever.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pipeline_editor_overflow_save)) },
                    onClick = {
                        overflowOpen = false
                        // No eager snackbar: the confirmation now rides on the
                        // save's own outcome via `feedbackMessage`, so a
                        // rejected save no longer says "Pipeline saved."
                        viewModel.saveCurrentPipeline()
                    },
                    leadingIcon = {
                        Icon(AppIcons.Save, contentDescription = null)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pipeline_editor_overflow_undo)) },
                    onClick = {
                        overflowOpen = false
                        onUndoClick()
                    },
                    enabled = editor.undoRedo.canUndo,
                    leadingIcon = {
                        Icon(AppIcons.Undo, contentDescription = null)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pipeline_editor_overflow_redo)) },
                    onClick = {
                        overflowOpen = false
                        onRedoClick()
                    },
                    enabled = editor.undoRedo.canRedo,
                    leadingIcon = {
                        Icon(AppIcons.Redo, contentDescription = null)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pipeline_editor_overflow_rename)) },
                    onClick = {
                        overflowOpen = false
                        pendingRenameNodeId = editor.selection.singleOrNull()
                    },
                    enabled = editor.selection.size == 1,
                    leadingIcon = {
                        Icon(AppIcons.Edit, contentDescription = null)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pipeline_editor_overflow_delete)) },
                    onClick = {
                        overflowOpen = false
                        onDeleteClick()
                    },
                    enabled = editor.selection.isNotEmpty() || editor.selectedEdgeId != null,
                    leadingIcon = {
                        Icon(AppIcons.Trash, contentDescription = null)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pipeline_editor_overflow_save_as_preset)) },
                    onClick = {
                        overflowOpen = false
                        saveAsPresetOpen = true
                    },
                    leadingIcon = {
                        Icon(AppIcons.Bookmark, contentDescription = null)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pipeline_editor_overflow_auto_layout)) },
                    onClick = {
                        overflowOpen = false
                        onAutoLayoutClick()
                    },
                    leadingIcon = {
                        Icon(AppIcons.AutoLayout, contentDescription = null)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pipeline_editor_overflow_mini_map)) },
                    onClick = {
                        overflowOpen = false
                        editor.miniMapOpen = !editor.miniMapOpen
                    },
                    leadingIcon = {
                        Icon(AppIcons.Globe, contentDescription = null)
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (editor.gridVisible) {
                                    R.string.pipeline_editor_overflow_toggle_grid_hide
                                } else {
                                    R.string.pipeline_editor_overflow_toggle_grid_show
                                },
                            ),
                        )
                    },
                    onClick = {
                        overflowOpen = false
                        editor.gridVisible = !editor.gridVisible
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (editor.gridVisible) AppIcons.GridOff else AppIcons.Grid,
                            contentDescription = null,
                        )
                    },
                )
                // The cookbook is what answers "what does this node actually
                // do" — the question external testers asked of this screen.
                // A menu item rather than a toolbar icon: the toolbar carries
                // the editing actions, and this one is read once.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pipeline_editor_overflow_cookbook)) },
                    onClick = {
                        overflowOpen = false
                        openDocumentation(context, DocumentationLinks.ID_COOKBOOK)
                    },
                    leadingIcon = {
                        Icon(AppIcons.Book, contentDescription = null)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pipeline_editor_overflow_find)) },
                    onClick = {
                        overflowOpen = false
                        editor.searchOpen = true
                    },
                    leadingIcon = {
                        Icon(AppIcons.Search, contentDescription = null)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pipeline_editor_overflow_paste)) },
                    onClick = {
                        overflowOpen = false
                        if (editor.clipboard.isEmpty()) {
                            scope.launch { snackbarHostState.showSnackbar(pasteEmptyMessage) }
                        } else {
                            editor.undoRedo.push(pipeline)
                            val offsetCanvas = CanvasTransform.GRID_PX * 2f
                            // `viewModel.addNode` + `updateNodeFromEditor` propagate through
                            // the orchestrator's StateFlow; we never need to thread a local
                            // graph snapshot through the loop because nothing in this block
                            // reads it after the dispatch. The loop is fire-and-forget per
                            // node and the next composition re-renders from the fresh VM state.
                            editor.clipboard.forEach { original ->
                                val newId = viewModel.addNode(
                                    original.type,
                                    x = original.x + offsetCanvas,
                                    y = original.y + offsetCanvas,
                                )
                                // Replace the freshly-added node's payload with the original's
                                // config so the paste preserves every field (label, prompt, etc.).
                                viewModel.updateNodeFromEditor(
                                    nodeId = newId,
                                    updated = original.copy(
                                        id = newId,
                                        x = original.x + offsetCanvas,
                                        y = original.y + offsetCanvas,
                                    ),
                                )
                            }
                        }
                    },
                    enabled = editor.clipboard.isNotEmpty(),
                    leadingIcon = {
                        Icon(AppIcons.Paste, contentDescription = null)
                    },
                )
            }
        }

        val sheetNodeId = editor.configuringNodeId
        val workingConfig = editor.workingConfig
        if (sheetNodeId != null && workingConfig != null) {
            val node = pipeline.nodes.find { it.id == sheetNodeId }
            if (node != null) {
                val peerTitles = remember(pipeline.nodes, node.id) {
                    pipeline.nodes
                        .filter { it.id != node.id }
                        .map { it.label }
                        .toSet()
                }
                // For a PIPELINE node, classify every saved pipeline as a
                // candidate target (cycle / self / depth disabled with a reason)
                // off the main thread when the sheet opens. Other node types
                // never read this list, so the work is skipped for them.
                var pipelineTargets by remember(node.id) { mutableStateOf(emptyList<PipelineTargetOption>()) }
                val isPipelineNode = node.type == NodeType.PIPELINE
                LaunchedEffect(node.id, isPipelineNode) {
                    pipelineTargets = if (isPipelineNode) {
                        viewModel.classifyPipelineTargets().map { it.toCatalogOption() }
                    } else {
                        emptyList()
                    }
                }
                // For a SKILL node, load the skill library when the sheet opens
                // so the picker can offer every bundled + user skill. Other node
                // types never read this list.
                var skillsForNode by remember(node.id) { mutableStateOf(emptyList<Skill>()) }
                val isSkillNode = node.type == NodeType.SKILL
                LaunchedEffect(node.id, isSkillNode) {
                    skillsForNode = if (isSkillNode) viewModel.loadSkills() else emptyList()
                }
                val skillOptions = rememberSkillOptions(skillsForNode)
                // Baseline for the context section's inherited / overridden tags:
                // the selected skill's own default context.
                val skillContextBaseline = (workingConfig as? SkillConfig)
                    ?.skillId
                    ?.let { id -> skillsForNode.firstOrNull { it.id == id }?.contextConfig }
                NodeConfigSheetHost(
                    config = workingConfig,
                    peerTitles = peerTitles,
                    onChange = { next ->
                        // When the user picks a *different* skill, reseed the
                        // node's context from that skill's default so the node
                        // inherits the skill's context out of the box; explicit
                        // toggles the user makes afterwards still win.
                        val previousSkillId = (editor.workingConfig as? SkillConfig)?.skillId
                        if (next is SkillConfig && next.skillId != previousSkillId) {
                            skillsForNode.firstOrNull { it.id == next.skillId }?.let { skill ->
                                editor.workingContextConfig = skill.contextConfig
                            }
                        }
                        editor.workingConfig = next
                    },
                    onCancel = {
                        editor.configuringNodeId = null
                        editor.workingConfig = null
                        editor.workingContextConfig = null
                    },
                    onSave = { saved ->
                        val mutated = NodeConfigCodec.apply(node, saved)
                        // Preserve the user's edits to the per-node context
                        // flags (Original task / Chat history / Long-term
                        // memory / Tool results) which the catalog
                        // `NodeConfigSheet` doesn't model — they're tracked
                        // in `editor.workingContextConfig` and stitched
                        // back here.
                        val withContext = editor.workingContextConfig
                            ?.let { mutated.copy(contextConfig = it) }
                            ?: mutated
                        editor.undoRedo.push(pipeline)
                        viewModel.updateNodeFromEditor(node.id, withContext)
                        editor.configuringNodeId = null
                        editor.workingConfig = null
                        editor.workingContextConfig = null
                    },
                    availableToolIds = uiState.availableTools.map { it.name },
                    availableModels = uiState.availableLocalModels.map { model ->
                        // The catalog `LocalModelOption.id` is the canonical identifier
                        // written into `LiteRtConfig.modelId`. We use the model's `path`
                        // because the runtime path is what the LiteRT engine actually
                        // loads — and `NodeConfigCodec.deriveFromLegacy` already maps
                        // legacy `node.modelPath` into `LiteRtConfig.modelId`, so the
                        // catalog identifier stays consistent across read / write.
                        LocalModelOption(
                            id = model.path,
                            displayName = model.name,
                            isActive = model.isActive,
                        )
                    },
                    onPickFromLibrary = { category, currentPrompt, apply ->
                        // Categories emitted by the catalog are always LLM-driven NodeType
                        // names (`"LITE_RT"` etc.); see NodeConfigForms — non-LLM forms
                        // never expose the 📚 button. Defensive `runCatching` so a future
                        // typo in the catalog doesn't crash the editor.
                        val type = runCatching { NodeType.valueOf(category) }.getOrNull()
                        if (type != null) {
                            pendingLibrary = PendingPromptLibrary(
                                nodeType = type,
                                currentPrompt = currentPrompt,
                                apply = apply,
                            )
                        }
                    },
                    onSavePreset = { category, currentPrompt ->
                        val type = runCatching { NodeType.valueOf(category) }.getOrNull()
                        if (type != null) {
                            pendingSavePreset = PendingSavePromptPreset(
                                nodeType = type,
                                systemPrompt = currentPrompt,
                            )
                        }
                    },
                    availablePipelines = pipelineTargets,
                    availableSkills = skillOptions,
                    extraSection = {
                        // Bind the legacy `NodeContextConfigSection` ("Input
                        // Data" checkboxes) to `editor.workingContextConfig`
                        // — the catalog `NodeConfigSheet` doesn't model
                        // context flags (those are domain-level), so the
                        // production sheet adds them via the `extraSection`
                        // slot. Defaults to `ALL_ENABLED` if for any reason
                        // the per-open initialisation didn't run.
                        val ctx = editor.workingContextConfig
                            ?: NodeContextConfig.ALL_ENABLED
                        NodeContextConfigSection(
                            originalTask = ctx.originalTask,
                            chatHistory = ctx.chatHistory,
                            longTermMemory = ctx.longTermMemory,
                            toolResults = ctx.toolResults,
                            onOriginalTaskChange = { next ->
                                editor.workingContextConfig =
                                    ctx.copy(originalTask = next)
                            },
                            onChatHistoryChange = { next ->
                                editor.workingContextConfig =
                                    ctx.copy(chatHistory = next)
                            },
                            onLongTermMemoryChange = { next ->
                                editor.workingContextConfig =
                                    ctx.copy(longTermMemory = next)
                            },
                            onToolResultsChange = { next ->
                                editor.workingContextConfig =
                                    ctx.copy(toolResults = next)
                            },
                            inheritedBaseline = skillContextBaseline,
                        )
                    },
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        val libraryRequest = pendingLibrary
        if (libraryRequest != null) {
            val bundled by viewModel
                .bundledPresetsForType(libraryRequest.nodeType)
                .collectAsState(initial = emptyList())
            val mine by viewModel
                .userPresetsForType(libraryRequest.nodeType)
                .collectAsState(initial = emptyList())
            PromptPresetPickerDialog(
                nodeType = libraryRequest.nodeType,
                bundled = bundled,
                mine = mine,
                currentPrompt = libraryRequest.currentPrompt,
                onApply = { picked ->
                    libraryRequest.apply(picked)
                    pendingLibrary = null
                },
                onPreview = { prompt -> viewModel.requestPromptPreview(prompt) },
                onDismiss = { pendingLibrary = null },
            )
        }

        val savePresetRequest = pendingSavePreset
        if (savePresetRequest != null) {
            SavePromptAsPresetDialog(
                nodeType = savePresetRequest.nodeType,
                systemPromptPreview = savePresetRequest.systemPrompt,
                onConfirm = { result ->
                    viewModel.saveCurrentPromptAsPreset(
                        systemPrompt = savePresetRequest.systemPrompt,
                        name = result.name,
                        description = result.description,
                        nodeType = savePresetRequest.nodeType,
                        tags = result.tags,
                    )
                    pendingSavePreset = null
                },
                onDismiss = { pendingSavePreset = null },
            )
        }

        // Prompt preview bottom sheet — driven by `previewState`. Rendered as long
        // as the picker dialog or any other surface in the editor requests a
        // preview (loading -> ready -> hidden). The sheet content is empty
        // (segments = null) until the engine finishes resolving variables.
        val previewState = uiState.previewState
        if (previewState !is PromptPreviewState.Hidden) {
            PromptPreviewBottomSheet(
                segments = (previewState as? PromptPreviewState.Ready)?.segments,
                onDismiss = { viewModel.dismissPromptPreview() },
            )
        }

        val renameNodeId = pendingRenameNodeId
        if (renameNodeId != null) {
            val node = pipeline.nodes.find { it.id == renameNodeId }
            if (node == null) {
                pendingRenameNodeId = null
            } else {
                SingleFieldDialog(
                    ui = SingleFieldDialogUi(
                        title = stringResource(R.string.pipeline_editor_rename_title),
                        label = stringResource(R.string.pipeline_editor_rename_field_label),
                        initialValue = node.label,
                        confirmLabel = stringResource(R.string.pipeline_editor_rename_confirm),
                        cancelLabel = stringResource(R.string.pipeline_editor_rename_cancel),
                    ),
                    onDismiss = { pendingRenameNodeId = null },
                    onConfirm = { typed ->
                        // The component gates on non-blank; only a *changed* name is
                        // worth an undo entry, so that check stays here.
                        val trimmed = typed.trim()
                        if (trimmed.isNotEmpty() && trimmed != node.label) {
                            editor.undoRedo.push(pipeline)
                            viewModel.updateNodeFromEditor(node.id, node.copy(label = trimmed))
                        }
                        pendingRenameNodeId = null
                    },
                )
            }
        }

        if (saveAsPresetOpen) {
            SaveAsPresetDialog(
                initialName = pipeline.name,
                onDismiss = { saveAsPresetOpen = false },
                onConfirm = { result ->
                    viewModel.saveCurrentAsPreset(
                        name = result.name,
                        description = result.description,
                        category = result.category,
                        tags = result.tags,
                    )
                    saveAsPresetOpen = false
                },
            )
        }

        // "From template" picker — fills the CURRENT (empty) pipeline from a
        // preset rather than spawning a new library row: the regenerated graph
        // replaces the current pipeline's nodes / connections in place (see
        // OrchestratorViewModel.applyPresetToCurrentPipeline). The presets
        // ViewModel here only supplies the catalogue (tabs / categories / list).
        //
        // It is resolved lazily inside this branch (not as a screen parameter)
        // so the editor only touches Hilt when the picker is actually opened —
        // composable tests that drive the editor with a manual
        // OrchestratorViewModel and never open the picker stay Hilt-free.
        if (showPresetPicker) {
            val presetsViewModel: PipelinePresetsViewModel = hiltViewModel()
            val presetsState by presetsViewModel.uiState.collectAsState()
            PresetPickerSheet(
                state = presetsState,
                onTabSelected = presetsViewModel::selectTab,
                onCategorySelected = presetsViewModel::selectCategory,
                onUsePreset = { presetId ->
                    // The screen-local EditorState belongs to the (empty) graph
                    // we are replacing. Drop its undo/redo history and transient
                    // selection so a stale Undo snapshot can't clobber the
                    // freshly loaded preset.
                    editor.undoRedo.reset()
                    editor.clearTransient()
                    viewModel.applyPresetToCurrentPipeline(presetId)
                    showPresetPicker = false
                },
                onDismiss = { showPresetPicker = false },
            )
        }

        val edgeToDelete = pendingEdgeDelete
        if (edgeToDelete != null) {
            ConfirmDialog(
                ui = ConfirmDialogUi(
                    title = stringResource(R.string.pipeline_editor_remove_connection_title),
                    body = stringResource(R.string.pipeline_editor_remove_connection_text),
                    confirmLabel = stringResource(R.string.pipeline_editor_remove_connection_confirm),
                    cancelLabel = stringResource(R.string.pipeline_editor_remove_connection_cancel),
                ),
                onConfirm = {
                    editor.undoRedo.push(uiState.currentPipeline)
                    viewModel.removeConnection(edgeToDelete)
                    editor.selectedEdgeId = null
                    pendingEdgeDelete = null
                },
                onDismiss = { pendingEdgeDelete = null },
            )
        }

        if (leaveRequested) {
            AlertDialog(
                onDismissRequest = { leaveRequested = false },
                title = { Text(text = stringResource(R.string.pipeline_editor_unsaved_title)) },
                text = { Text(text = stringResource(R.string.pipeline_editor_unsaved_text)) },
                // Save is the confirm slot because it is the one that keeps the
                // work. Discarding is reachable, and named for what it does
                // rather than "OK" — the whole defect was an exit that looked
                // like it kept things.
                confirmButton = {
                    TextButton(onClick = {
                        leaveRequested = false
                        viewModel.saveCurrentPipeline()
                        onBack()
                    }) { Text(text = stringResource(R.string.pipeline_editor_unsaved_save)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        leaveRequested = false
                        onBack()
                    }) { Text(text = stringResource(R.string.pipeline_editor_unsaved_discard)) }
                },
            )
        }
    }

    // Surface a one-shot hint the first time the user selects an edge, so the toolbar
    // Delete path becomes discoverable. `LaunchedEffect(editor.selectedEdgeId)` fires
    // each time the selected edge changes — including from `null` to an id (tap-select).
    //
    // The snackbar call runs in the effect's own coroutine scope (not the outer
    // `rememberCoroutineScope()`) so that when the user clears or changes their
    // selection quickly the pending snackbar is cancelled with the effect, instead
    // of accumulating overlapping toasts via an orphaned outer-scope coroutine.
    val selectedEdge = editor.selectedEdgeId
    val edgeSelectedHint = stringResource(R.string.pipeline_editor_edge_selected_hint)
    LaunchedEffect(selectedEdge) {
        if (selectedEdge != null) {
            snackbarHostState.showSnackbar(message = edgeSelectedHint)
        }
    }
}

/**
 * Computes the [PipelineEditorContent] subtitle from the validation / graph
 * state. Pure-Compose (read-only) — no side effects.
 *
 * Priority: overview > issues > editing. The editor does not execute pipelines,
 * so there is no run-derived subtitle: runs happen from chat, where the console
 * reports them.
 */
@Composable
private fun rememberToolbarSubtitle(
    graph: PipelineGraph,
    validationErrorCount: Int,
    miniMapOpen: Boolean,
    scale: Float,
): String = when {
    miniMapOpen -> stringResource(
        R.string.pipeline_editor_subtitle_overview,
        formatScalePercent(scale),
        graph.nodes.size,
    )
    validationErrorCount > 0 -> pluralStringResource(
        R.plurals.pipeline_editor_subtitle_issues,
        validationErrorCount,
        validationErrorCount,
    )
    else -> stringResource(
        R.string.pipeline_editor_subtitle_editing,
        graph.nodes.size,
        graph.connections.size,
    )
}

/**
 * Pending prompt-library request raised by the catalog sheet's 📚 button. [nodeType]
 * scopes which `PromptPreset`s show up in the picker; [currentPrompt] is the field's
 * current draft so the picker can mark the matching row as `CURRENT`; [apply] is the
 * form's "set this field" lambda, invoked when the user picks a preset.
 */
private data class PendingPromptLibrary(val nodeType: NodeType, val currentPrompt: String, val apply: (String) -> Unit)

/**
 * Pending save-as-prompt-preset request raised by the catalog sheet's 💾 button.
 * [systemPrompt] is the current draft captured at click time; [nodeType] is the
 * active node's type, both forwarded to `SavePromptAsPresetUseCase` on submit.
 */
private data class PendingSavePromptPreset(val nodeType: NodeType, val systemPrompt: String)

/**
 * Canvas-space NodeCard width / height. Mirrors the catalog `NodeCardWidth = 168
 * dp` and `NodeCardMaxHeight = 96 dp`. Used by the auto-layout post-translate so
 * the bbox math matches what the canvas actually paints.
 */
private const val NODE_CARD_WIDTH_PX: Float = 168f
private const val NODE_CARD_HEIGHT_PX: Float = 96f

/**
 * Auto-layout horizontal centre-to-centre step, in **dp** (converted to canvas-px through
 * the screen [androidx.compose.ui.unit.Density] at the call site). The 168 dp card width
 * plus a 72 dp gutter, so siblings keep clear air between them at any display density.
 */
private val AUTO_LAYOUT_SIBLING_GAP = 240.dp

/**
 * Auto-layout vertical centre-to-centre step, in **dp**. The card runs up to ~126 dp tall
 * once inbound / outbound port labels inset it, so 216 dp leaves ~90 dp between layers.
 */
private val AUTO_LAYOUT_LAYER_GAP = 216.dp

/**
 * Maps a domain [PipelineTargetAvailability] (the validator's classification)
 * onto the catalog [PipelineTargetOption] the picker renders. Keeps the catalog
 * free of the domain reason hierarchy while carrying the per-reason detail
 * (cycle culprit name / depth limit) the picker messages need.
 */
private fun PipelineTargetAvailability.toCatalogOption(): PipelineTargetOption = PipelineTargetOption(
    id = pipelineId,
    name = name,
    selectable = selectable,
    disabledReason = when (reason) {
        PipelineTargetAvailability.Reason.Self -> PipelineTargetDisabledReason.SELF
        is PipelineTargetAvailability.Reason.Cycle -> PipelineTargetDisabledReason.CYCLE
        is PipelineTargetAvailability.Reason.Depth -> PipelineTargetDisabledReason.DEPTH
        null -> null
    },
    cycleCulprit = (reason as? PipelineTargetAvailability.Reason.Cycle)?.culpritPipelineName,
    depthLimit = (reason as? PipelineTargetAvailability.Reason.Depth)?.limit,
)

/**
 * Maps the domain [Skill] library to catalog [SkillOption]s for the SKILL-node
 * picker, resolving the localized tool-allowlist summary here (the catalog
 * stays free of skill-storage and string-plural knowledge). The tri-state
 * allowlist maps to "All tools" (`null`), "No tools" (empty), or an "N tools"
 * plural (subset).
 */
@Composable
private fun rememberSkillOptions(skills: List<Skill>): List<SkillOption> {
    val allLabel = stringResource(R.string.pipeline_editor_skill_allowlist_all)
    val noneLabel = stringResource(R.string.pipeline_editor_skill_allowlist_none)
    val options = ArrayList<SkillOption>(skills.size)
    for (skill in skills) {
        val allowlist = skill.toolAllowlist
        val summary = when {
            allowlist == null -> allLabel
            allowlist.isEmpty() -> noneLabel
            else -> pluralStringResource(
                R.plurals.pipeline_editor_skill_allowlist_subset,
                allowlist.size,
                allowlist.size,
            )
        }
        options.add(
            SkillOption(
                id = skill.id,
                name = skill.name,
                instructionPreview = skill.instruction,
                toolRestrictionSummary = summary,
            ),
        )
    }
    return options
}
