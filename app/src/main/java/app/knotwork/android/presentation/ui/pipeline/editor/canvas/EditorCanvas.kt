package app.knotwork.android.presentation.ui.pipeline.editor.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.knotwork.android.R
import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.presentation.ui.pipeline.editor.core.Bounds
import app.knotwork.android.presentation.ui.pipeline.editor.core.CanvasTransform
import app.knotwork.android.presentation.ui.pipeline.editor.core.ConnectionDraft
import app.knotwork.android.presentation.ui.pipeline.editor.core.EditorState
import app.knotwork.android.presentation.ui.pipeline.editor.core.NodeCardFootprint
import app.knotwork.design.components.pipelineeditor.NodeError
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme

/**
 * Top-level editor canvas. Hosts pan / pinch zoom, all node renderers, the edge layer,
 * and the radial quick-add menu.
 *
 * Responsibilities (kept here so the screen file stays a thin orchestrator):
 *  - Owns the `Box` modifier chain wiring pinch-to-zoom (`transformable`), one-finger
 *    canvas pan (`detectDragGestures`), and tap / long-press (`detectTapGestures`).
 *  - Renders the [EditorEdges] layer first (so it draws under nodes).
 *  - Renders one [EditorNode] per `PipelineGraph.nodes`.
 *  - Renders the [QuickAddRadialMenu] on top when an anchor is set.
 *
 * Node positions are persisted in canvas units, which are dp (see [CanvasTransform]); the
 * [EditorState] transform projects them to screen pixels at render time, using the
 * display density this composable keeps it in step with. Drag-to-move accumulates a
 * canvas-space delta into a per-node session state and commits it to the ViewModel via
 * [onMoveNode] once the gesture settles.
 *
 * The first time a pipeline is shown here — and again after [EditorState.requestFit] —
 * the viewport is framed on the whole graph at no more than 100 %, so a pipeline opens
 * legible instead of at the canvas origin with part of it off screen.
 *
 * @param graph the current pipeline graph (from the ViewModel).
 * @param editor screen-local state (transform, selection, drafts).
 * @param errorsByNodeId map of node-id → optional inline error for the catalog NodeCard.
 * @param reducedMotion reduced-motion flag — gates animations longer than `motionSm`.
 * @param onMoveNode invoked on drag-end with the committed canvas-space delta.
 * @param onAddNode invoked when a quick-add tile is picked.
 * @param onAddConnection invoked when a connection draft drops on a valid target node.
 * @param onConnectionDropped invoked when a connection drag ends without a valid target
 * (released in empty space or back on the source node) so the screen can hint how
 * connections are made instead of failing silently.
 * @param modifier optional layout modifier applied to the canvas root.
 */
@Composable
@Suppress("LongParameterList", "LongMethod")
internal fun EditorCanvas(
    graph: PipelineGraph,
    editor: EditorState,
    errorsByNodeId: Map<String, NodeError?>,
    reducedMotion: Boolean,
    onMoveNode: (nodeId: String, dxCanvas: Float, dyCanvas: Float) -> Unit,
    onAddNode: (type: NodeType, canvasX: Float, canvasY: Float) -> Unit,
    onAddConnection: (sourceNodeId: String, targetNodeId: String, label: String?) -> Unit,
    onConnectionDropped: () -> Unit,
    onOpenNodeConfig: (nodeId: String) -> Unit,
    onLongPressEdge: (connectionId: String) -> Unit,
    onStartWithInput: () -> Unit,
    onFromTemplate: () -> Unit,
    modifier: Modifier = Modifier,
    subtitleForNode: (NodeModel) -> String? = { null },
) {
    val density = LocalDensity.current
    var viewportSize by remember { mutableStateOf(IntPair(0, 0)) }

    // Canvas units are dp, so the transform must know the display density. The screen's
    // `rememberEditorState()` seeds it; this keeps it right if the density changes under a
    // live state, or when a state was built without one (tests construct `EditorState()`).
    SideEffect {
        if (editor.transform.density != density.density) {
            editor.transform = editor.transform.copy(density = density.density)
        }
    }

    // Frame the graph when a pipeline is first shown, and when a fit was requested for
    // content that arrives later (a template applied to an empty pipeline). Keyed on
    // emptiness rather than on the node list, so editing nodes never re-frames.
    val fitPaddingPx = with(density) { ZOOM_RAIL_FIT_PADDING_DP.dp.toPx() }
    LaunchedEffect(graph.id, viewportSize, graph.nodes.isEmpty(), editor.fitRequested) {
        editor.frameIfNeeded(
            graph = graph,
            viewportW = viewportSize.first.toFloat(),
            viewportH = viewportSize.second.toFloat(),
            paddingPx = fitPaddingPx,
        )
    }

    // Per-node session deltas keep drag fluid without thrashing the VM each frame.
    val dragDeltas = remember { mutableStateOf<Map<String, Pair<Float, Float>>>(emptyMap()) }
    val nodesById = remember(graph) { graph.nodes.associateBy { it.id } }
    val nodesWithDrag = remember(graph, dragDeltas.value) {
        graph.nodes.map { node ->
            val delta = dragDeltas.value[node.id]
            if (delta == null) node else node.copy(x = node.x + delta.first, y = node.y + delta.second)
        }
    }
    val nodesByIdLive = remember(nodesWithDrag) { nodesWithDrag.associateBy { it.id } }

    // The tap / long-press lambdas inside `detectTapGestures` capture `graph` and
    // `nodesByIdLive` at the moment their pointerInput block first runs. Since the
    // pointerInput block is keyed on `graph.id` (stable for the lifetime of one
    // pipeline), the block never re-runs after we add a connection — the lambda's
    // captured `graph.connections` stays stuck at the value seen on first composition,
    // so `hitTestEdge` can't find the brand-new edge and tap-to-select silently fails.
    // `rememberUpdatedState` parks the latest values in a State that the lambdas read
    // each invocation, refreshing the snapshot without re-keying the pointerInput.
    val currentGraph by rememberUpdatedState(graph)
    val currentNodesById by rememberUpdatedState(nodesByIdLive)

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds() // Prevent node graphics from spilling outside the canvas surface
            // into the EditorToolbar / bottom bars when nodes sit near the viewport edge.
            .background(KnotworkTheme.extended.surface1)
            .onSizeChanged { size ->
                viewportSize = IntPair(size.width, size.height)
                editor.viewportWidthPx = size.width.toFloat()
                editor.viewportHeightPx = size.height.toFloat()
            }
            // Capture canvas LayoutCoordinates into a non-state ref so EditorNode's
            // port-drag handlers can convert pointer positions via
            // `LayoutCoordinates.localPositionOf` without triggering a recomposition each
            // layout pass — see EditorState.canvasLayoutCoordinatesRef KDoc for the
            // recomposition-cycle / ANR motivation.
            .onGloballyPositioned { editor.canvasLayoutCoordinatesRef.value = it }
            .pointerInput(graph.id) {
                detectTapGestures(
                    onTap = { tapScreen ->
                        // Hit-test edges in canvas-space before falling through to "clear selection".
                        // Bezier edges are pure draw output (no per-edge pointerInput consumer), so
                        // the tap reaches the canvas; we project to canvas-space and ask the helper
                        // in EditorEdges which connection — if any — sits within the screen-space
                        // tolerance. Tap-on-edge selects it (toolbar Delete then removes it);
                        // tap-elsewhere clears selection.
                        val canvasX = editor.transform.screenToCanvasX(tapScreen.x)
                        val canvasY = editor.transform.screenToCanvasY(tapScreen.y)
                        val edgeId = hitTestEdge(
                            pointerCanvasX = canvasX,
                            pointerCanvasY = canvasY,
                            connections = currentGraph.connections,
                            nodesById = currentNodesById,
                            transform = editor.transform,
                        )
                        if (edgeId != null) {
                            editor.selectEdge(edgeId)
                            editor.multiSelectMode = false
                        } else {
                            editor.selection = emptySet()
                            editor.selectedEdgeId = null
                            editor.multiSelectMode = false
                        }
                    },
                    onLongPress = { tapScreen ->
                        // Long-press hit-tests edges first — if the press lands on an edge,
                        // forward to `onLongPressEdge` (screen shows a "Remove connection?"
                        // confirmation). Otherwise open the radial quick-add menu at the
                        // long-press point. Two discoverable paths to delete a connection
                        // (tap-select + toolbar 🗑, OR long-press + confirm) so users find at
                        // least one of them.
                        val canvasX = editor.transform.screenToCanvasX(tapScreen.x)
                        val canvasY = editor.transform.screenToCanvasY(tapScreen.y)
                        val edgeId = hitTestEdge(
                            pointerCanvasX = canvasX,
                            pointerCanvasY = canvasY,
                            connections = currentGraph.connections,
                            nodesById = currentNodesById,
                            transform = editor.transform,
                        )
                        if (edgeId != null) {
                            onLongPressEdge(edgeId)
                        } else {
                            editor.quickAddAnchor = tapScreen.x to tapScreen.y
                        }
                    },
                )
            }
            // Pan / pinch-zoom, but only when the gesture does NOT start on an outbound
            // port. A press that begins on a port is a connection drag, owned by that
            // node's port handler; the canvas opts out here (returns before consuming
            // anything) so it never races the port drag and pans instead — the failure
            // that hijacked edge creation at maximum zoom. A press on a node body is left
            // to the node's own move/tap handlers: the touch-slop wait below mirrors
            // `detectTransformGestures` so a child node-move detector crosses its slop and
            // consumes FIRST, after which the `isConsumed` guard makes the canvas stand
            // down — without the slop the canvas panned on the very first pixel and stole
            // node dragging (most visibly on nodes with no outbound port, e.g. OUTPUT).
            .pointerInput(graph.id) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downCanvasX = editor.transform.screenToCanvasX(down.position.x)
                    val downCanvasY = editor.transform.screenToCanvasY(down.position.y)
                    if (hitTestOutboundPort(
                            downCanvasX,
                            downCanvasY,
                            currentNodesById.values,
                            editor.transform,
                            currentGraph.connections,
                        ) != null
                    ) {
                        return@awaitEachGesture
                    }
                    var pastSlop = false
                    var slopTravel = 0f
                    do {
                        val event = awaitPointerEvent()
                        // A node-body drag (move) or tap consumes first; don't also pan.
                        if (event.changes.any { it.isConsumed }) {
                            if (event.changes.none { it.pressed }) break else continue
                        }
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (!pastSlop) {
                            // Accumulate motion until it clears touch slop, giving the node's
                            // own move/tap detectors first claim on small movements.
                            slopTravel += pan.getDistance() + kotlin.math.abs(1f - zoom) * touchSlop
                            if (slopTravel > touchSlop) pastSlop = true
                        }
                        if (pastSlop) {
                            val centroid = event.calculateCentroid()
                            if (zoom != 1f) {
                                editor.transform = editor.transform.zoomedBy(zoom, centroid.x, centroid.y)
                            }
                            if (pan != Offset.Zero) {
                                editor.transform = editor.transform.panBy(pan.x, pan.y)
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        // Dot grid lives at the bottom of the draw stack — under edges so a node
        // never bleeds onto a dot, and visible only when the user has not
        // toggled it off via the overflow menu. Tracks `transform` so the
        // dots pan + zoom with the canvas.
        if (editor.gridVisible) {
            DotGridBackground(transform = editor.transform)
        }

        val draftDraw = editor.connectionInProgress?.let { draft ->
            val source = nodesByIdLive[draft.sourceNodeId] ?: return@let null
            val ports = portsFor(source, outboundLabelsOf(source.id, graph.connections))
            val anchor = outboundPortAnchor(source, ports, draft.sourcePortLabel)
            // The draft is stored in canvas-space (see ConnectionDraft KDoc); project both
            // anchor and live pointer through `transform` here so the draw layer stays in
            // screen-space and doesn't need to know about CanvasTransform.
            ConnectionDraftDrawData(
                sourceScreen = Offset(
                    editor.transform.canvasToScreenX(anchor.xCanvas),
                    editor.transform.canvasToScreenY(anchor.yCanvas),
                ),
                pointerScreen = Offset(
                    editor.transform.canvasToScreenX(draft.pointerCanvasX),
                    editor.transform.canvasToScreenY(draft.pointerCanvasY),
                ),
            )
        }
        EditorEdges(
            connections = graph.connections,
            nodesById = nodesByIdLive,
            transform = editor.transform,
            connectionDraft = draftDraw,
            selectedEdgeId = editor.selectedEdgeId,
        )

        // Per-node ports are derived from the decoded NodeConfig (for IntentRouter
        // classes + Evaluation retry visibility), which would otherwise re-decode the
        // config JSON for every recomposition × every node × every connection during
        // a hot drag. Memoise once per `graph.nodes` change so the canvas stays fluid.
        val portsByNodeId = remember(graph.nodes, graph.connections) {
            graph.nodes.associate { it.id to portsFor(it, outboundLabelsOf(it.id, graph.connections)) }
        }

        nodesWithDrag.forEach { node ->
            val originalNode = nodesById[node.id] ?: node
            val ports = portsByNodeId[node.id] ?: portsFor(node, outboundLabelsOf(node.id, graph.connections))
            EditorNode(
                node = node,
                transform = editor.transform,
                selected = node.id in editor.selection && !editor.multiSelectMode,
                multiSelected = node.id in editor.selection && editor.multiSelectMode,
                error = errorsByNodeId[node.id],
                ports = ports,
                reducedMotion = reducedMotion,
                subtitle = subtitleForNode(originalNode),
                onSelect = { editor.toggleSelection(node.id) },
                onOpenConfig = { onOpenNodeConfig(node.id) },
                onLongPress = {
                    editor.multiSelectMode = true
                    editor.toggleSelection(node.id)
                },
                onDrag = { dxCanvas, dyCanvas ->
                    // EditorNode already emits canvas-space deltas: the zoom is undone by the
                    // `graphicsLayer` its pointerInput sits under, and the density by EditorNode
                    // itself. Dividing again here would make the node lag behind the finger.
                    // Accumulate the canvas-space delta directly.
                    val prev = dragDeltas.value[node.id] ?: (0f to 0f)
                    dragDeltas.value = dragDeltas.value +
                        (node.id to (prev.first + dxCanvas to prev.second + dyCanvas))
                },
                onDragEnd = {
                    val delta = dragDeltas.value[node.id]
                    if (delta != null) {
                        // Snap to grid on commit.
                        val targetX = CanvasTransform.snapToGrid(originalNode.x + delta.first)
                        val targetY = CanvasTransform.snapToGrid(originalNode.y + delta.second)
                        val snappedDx = targetX - originalNode.x
                        val snappedDy = targetY - originalNode.y
                        dragDeltas.value = dragDeltas.value - node.id
                        if (snappedDx != 0f || snappedDy != 0f) {
                            onMoveNode(node.id, snappedDx, snappedDy)
                        }
                    }
                },
                onConnectionStart = { portLabel, pointerBoxX, pointerBoxY ->
                    // pointerBoxX/Y arrive as the absolute pointer position in canvas-Box-local
                    // coords (from `LayoutCoordinates.localPositionOf`), so project once through
                    // the inverse canvas transform to land in canvas-space.
                    editor.connectionInProgress = ConnectionDraft(
                        sourceNodeId = node.id,
                        sourcePortLabel = portLabel,
                        pointerCanvasX = editor.transform.screenToCanvasX(pointerBoxX),
                        pointerCanvasY = editor.transform.screenToCanvasY(pointerBoxY),
                    )
                },
                onConnectionMove = { pointerBoxX, pointerBoxY ->
                    val draft = editor.connectionInProgress ?: return@EditorNode
                    editor.connectionInProgress = draft.copy(
                        pointerCanvasX = editor.transform.screenToCanvasX(pointerBoxX),
                        pointerCanvasY = editor.transform.screenToCanvasY(pointerBoxY),
                    )
                },
                onConnectionEnd = {
                    val draft = editor.connectionInProgress
                    editor.connectionInProgress = null
                    if (draft != null) {
                        val target = hitTestInputNode(
                            pointerCanvasX = draft.pointerCanvasX,
                            pointerCanvasY = draft.pointerCanvasY,
                            nodes = nodesByIdLive.values,
                            excludeNodeId = draft.sourceNodeId,
                        )
                        if (target != null) {
                            // Forward the source-port label so multi-out nodes (IF / Queue /
                            // Eval / IntentRouter) persist which port the user dragged from.
                            onAddConnection(
                                draft.sourceNodeId,
                                target.id,
                                draft.sourcePortLabel.ifBlank { null },
                            )
                        } else {
                            // The drag ended without landing on another node's inbound port
                            // (released in empty space, or back on the source). Without a
                            // cue the edge just silently fails to appear; tell the user how
                            // connections are made instead.
                            onConnectionDropped()
                        }
                    }
                },
                onConnectionCancel = {
                    // System-cancelled gesture: drop the draft silently, no "missed" hint.
                    editor.connectionInProgress = null
                },
                canvasLayoutCoordinatesRef = editor.canvasLayoutCoordinatesRef,
            )
        }

        if (graph.nodes.isEmpty()) {
            EmptyPipelineState(
                scale = editor.transform.scale,
                onStartWithInput = onStartWithInput,
                onFromTemplate = onFromTemplate,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(KnotworkTheme.spacing.sp6),
            )
        }

        val anchor = editor.quickAddAnchor
        if (anchor != null) {
            QuickAddRadialMenu(
                screenAnchorX = anchor.first,
                screenAnchorY = anchor.second,
                onPick = { type ->
                    val canvasX = editor.transform.screenToCanvasX(anchor.first)
                    val canvasY = editor.transform.screenToCanvasY(anchor.second)
                    editor.quickAddAnchor = null
                    onAddNode(type, CanvasTransform.snapToGrid(canvasX), CanvasTransform.snapToGrid(canvasY))
                },
                onDismiss = { editor.quickAddAnchor = null },
            )
        }

        // Search / Find-node bar pinned to the top edge of the canvas when the
        // user has opened it from the overflow menu. Stretches edge-to-edge so
        // the text field has room without competing with the zoom rail.
        if (editor.searchOpen) {
            val matchCount = remember(graph.nodes, editor.searchQuery) {
                if (editor.searchQuery.isBlank()) {
                    0
                } else {
                    graph.nodes.count { node ->
                        node.label.contains(editor.searchQuery, ignoreCase = true) ||
                            node.type.name.contains(editor.searchQuery, ignoreCase = true)
                    }
                }
            }
            FilterBar(
                query = editor.searchQuery,
                matchCount = matchCount,
                onQueryChange = { editor.searchQuery = it },
                onSubmit = {
                    val firstMatch = graph.nodes.firstOrNull { node ->
                        node.label.contains(editor.searchQuery, ignoreCase = true) ||
                            node.type.name.contains(editor.searchQuery, ignoreCase = true)
                    } ?: return@FilterBar
                    editor.transform = editor.transform.centeredOn(
                        x = firstMatch.x,
                        y = firstMatch.y,
                        viewportW = viewportSize.first.toFloat(),
                        viewportH = viewportSize.second.toFloat(),
                    )
                    editor.selection = setOf(firstMatch.id)
                    editor.multiSelectMode = false
                },
                onClose = {
                    editor.searchOpen = false
                    editor.searchQuery = ""
                },
                modifier = Modifier.align(Alignment.TopStart),
            )
        }

        // Floating `+` button anchored bottom-right. Tap opens the same radial
        // quick-add menu the long-press-on-canvas gesture surfaces, but anchored
        // at the viewport centre so the user always has a discoverable way to
        // drop the first node — the empty-state helper text literally promises
        // "Tap + to drop your first node". Hidden when the mini-map is open so
        // the two bottom-right overlays don't collide.
        if (!editor.miniMapOpen) {
            FloatingActionButton(
                onClick = {
                    val cx = viewportSize.first / 2f
                    val cy = viewportSize.second / 2f
                    editor.quickAddAnchor = cx to cy
                },
                // Spec §2.3: regular FAB fill primary / on-primary, radius 16 (`shapes.lg`).
                // Matches the memory / library FAB family rather than the M3 default
                // primary-container pill.
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(KnotworkTheme.spacing.sp4),
                shape = KnotworkTheme.shapes.lg,
            ) {
                Icon(
                    imageVector = AppIcons.Add,
                    contentDescription = stringResource(R.string.pipeline_editor_fab_add_node),
                )
            }
        }

        // Mini-map overlay anchored to the bottom-right corner. Renders only when
        // the user has opened it from the overflow menu; tap inside the body
        // centres the canvas on the corresponding canvas-space point.
        if (editor.miniMapOpen) {
            MiniMap(
                graph = graph,
                transform = editor.transform,
                viewportSize = IntSize(viewportSize.first, viewportSize.second),
                onTapCanvasPoint = { canvasX, canvasY ->
                    editor.transform = editor.transform.centeredOn(
                        x = canvasX,
                        y = canvasY,
                        viewportW = viewportSize.first.toFloat(),
                        viewportH = viewportSize.second.toFloat(),
                    )
                },
                onClose = { editor.miniMapOpen = false },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(KnotworkTheme.spacing.sp3),
            )
        }

        // Always-visible zoom rail anchored to the top-right corner of the canvas.
        // Tile callbacks read `viewportSize` (recorded by `onSizeChanged` above) so
        // the +/− buttons zoom around the viewport centre, not (0, 0). Fit-to-view
        // uses `Bounds.ofNodes` over the live node positions; falls back to no-op
        // when the graph is empty.
        ZoomRail(
            onZoomIn = {
                editor.transform = editor.transform.zoomedOneStep(
                    direction = 1,
                    viewportW = viewportSize.first.toFloat(),
                    viewportH = viewportSize.second.toFloat(),
                )
            },
            onZoomOut = {
                editor.transform = editor.transform.zoomedOneStep(
                    direction = -1,
                    viewportW = viewportSize.first.toFloat(),
                    viewportH = viewportSize.second.toFloat(),
                )
            },
            onFit = {
                val bbox = Bounds.ofNodes(
                    positions = graph.nodes.map { it.x to it.y },
                    nodeWidth = NodeCardFootprint.WIDTH,
                    nodeHeight = NodeCardFootprint.MAX_HEIGHT,
                ) ?: return@ZoomRail
                editor.transform = editor.transform.fitToBounds(
                    bbox = bbox,
                    viewportW = viewportSize.first.toFloat(),
                    viewportH = viewportSize.second.toFloat(),
                    paddingPx = fitPaddingPx,
                )
            },
            canZoomIn = editor.transform.scale < CanvasTransform.MAX_SCALE,
            canZoomOut = editor.transform.scale > CanvasTransform.MIN_SCALE,
            canFit = graph.nodes.isNotEmpty(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(KnotworkTheme.spacing.sp3),
        )
    }

    LaunchedEffect(reducedMotion) {
        // Touchpoint reserved for future a11y signals; the dependency keys the effect
        // to motion-flag changes so animations recreate cleanly across system toggles.
    }
}

private typealias IntPair = Pair<Int, Int>

/**
 * Returns the node a release-from-port gesture lands on, in **canvas-space**: the user only
 * has to drop the connection anywhere on (or within [INBOUND_HIT_DP] of) the target node's
 * card — not on a pixel-precise top-edge anchor. Whole-card targeting is what users expect
 * ("drag onto the node") and, unlike the old anchor-radius test, it does not shrink in
 * canvas-space as you zoom in, so it works at maximum zoom. When the point is inside more
 * than one padded rectangle the nearest-centre node wins.
 *
 * [excludeNodeId] (the source node) is skipped: when the target sits just below the source,
 * a release near the target's TOP edge can be closer to the *source's* centre than the
 * target's, which would otherwise resolve to a self-drop and be rejected — the exact reason
 * a tightly-stacked pair (e.g. a node directly above the one you're connecting to) refused
 * to connect while every other pair worked.
 */
internal fun hitTestInputNode(
    pointerCanvasX: Float,
    pointerCanvasY: Float,
    nodes: Collection<NodeModel>,
    excludeNodeId: String? = null,
): NodeModel? {
    // A canvas unit is a dp, so the padding needs no conversion — and, like the card it
    // pads, it scales with zoom.
    val padCanvas = INBOUND_HIT_DP
    var best: NodeModel? = null
    var bestDist = Float.MAX_VALUE
    nodes.forEach { node ->
        if (node.id == excludeNodeId) return@forEach
        val b = nodeCanvasBounds(node)
        val inside = pointerCanvasX in (b.left - padCanvas)..(b.right + padCanvas) &&
            pointerCanvasY in (b.top - padCanvas)..(b.bottom + padCanvas)
        if (inside) {
            val cx = (b.left + b.right) / 2f
            val cy = (b.top + b.bottom) / 2f
            val dist = kotlin.math.hypot(cx - pointerCanvasX, cy - pointerCanvasY)
            if (dist < bestDist) {
                best = node
                bestDist = dist
            }
        }
    }
    return best
}

/**
 * Returns the outbound port (node id + label) nearest a **canvas-space** press, or `null`
 * when the press is not within [OUTBOUND_HIT_DP] of any port. Lets the canvas decide whether
 * a gesture is a connection drag (→ leave it to the node's port handler, do not pan) or a
 * pan. The tolerance is a screen-space dp divided by the canvas scale — a canvas unit is a
 * dp at zoom 1 — so the grab area stays visually constant across zoom.
 */
internal fun hitTestOutboundPort(
    pointerCanvasX: Float,
    pointerCanvasY: Float,
    nodes: Collection<NodeModel>,
    transform: CanvasTransform,
    connections: List<ConnectionModel> = emptyList(),
): OutboundHit? {
    val toleranceCanvas = OUTBOUND_HIT_DP / transform.scale
    var best: OutboundHit? = null
    var bestDist = Float.MAX_VALUE
    nodes.forEach { node ->
        outboundPortAnchors(node, outboundLabelsOf(node.id, connections)).forEach { (label, anchor) ->
            val dist = kotlin.math.hypot(anchor.xCanvas - pointerCanvasX, anchor.yCanvas - pointerCanvasY)
            if (dist < toleranceCanvas && dist < bestDist) {
                best = OutboundHit(node.id, label)
                bestDist = dist
            }
        }
    }
    return best
}

internal data class OutboundHit(val nodeId: String, val label: String)

private const val INBOUND_HIT_DP = 32f

/**
 * Grab radius (dp) around an outbound port anchor for the canvas's "is this gesture a
 * connection drag?" opt-out test. Sized to cover the per-port hit circle (24 dp ⇒ ~12 dp
 * radius) with a small margin so the opt-out reliably overlaps wherever the port handler
 * grabs, while keeping the no-pan zone just below a node tight.
 */
private const val OUTBOUND_HIT_DP = 20f

/** Screen-space padding (dp) around the framed bbox when the user taps Fit-to-view. */
private const val ZOOM_RAIL_FIT_PADDING_DP = 32f
