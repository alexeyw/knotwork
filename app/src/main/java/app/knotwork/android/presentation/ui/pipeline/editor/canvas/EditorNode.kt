package app.knotwork.android.presentation.ui.pipeline.editor.canvas

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.presentation.ui.pipeline.editor.config.NodeTypeMapper
import app.knotwork.android.presentation.ui.pipeline.editor.core.CanvasTransform
import app.knotwork.android.presentation.ui.pipeline.editor.core.CoordinatesRef
import app.knotwork.design.components.pipelineeditor.NodeCard
import app.knotwork.design.components.pipelineeditor.NodeError
import app.knotwork.design.components.pipelineeditor.NodePorts
import app.knotwork.design.theme.KnotworkTheme
import androidx.compose.animation.core.Animatable as AnimFloat

private const val PORT_HIT_TARGET_DP = 24f
private const val DRAG_PICKUP_SCALE = 1.04f
private const val DRAG_PICKUP_DURATION_MS = 100

/**
 * Wraps the catalog [NodeCard] with editor-only behaviour: drag-to-move, tap-to-select,
 * long-press multi-select, and a hit-target overlay on the outbound port that hands off
 * to connection mode.
 *
 * Position is rendered through `graphicsLayer.translationX / Y` so a node moving never
 * triggers a re-measure of the canvas surface. The
 * pickup scale animation (1.00 → 1.04 over 100 ms, ease-out) and the release settle
 * (`spring(.7f, 15f)`) live on local [AnimFloat] handles.
 *
 * @param node the node to render.
 * @param transform pan / zoom applied to the canvas.
 * @param selected `true` when the node is the single-selected entry.
 * @param multiSelected `true` when the node is part of a multi-select set.
 * @param error optional validation / runtime error surface.
 * @param ports outbound / inbound port layout.
 * @param reducedMotion reduced-motion flag — pickup / release animations collapse to instant.
 * @param subtitle optional one-line secondary text shown under the title on the
 * card (`null` hides it). Used by PIPELINE nodes to surface the target pipeline
 * name (or a "no target" / "not found" note); every other type passes `null`.
 * @param onSelect invoked on tap when the node is **not** currently selected. This is the
 * canonical "select this node" entry point — the screen then highlights it via the catalog
 * `NodeCard.selected` flag and re-renders.
 * @param onOpenConfig invoked on tap when the node is **already** selected. Drives the
 * "tap twice to open properties" UX so a single gesture chain covers both select and
 * configure without requiring a separate dedicated affordance. The screen opens the
 * `NodeConfigSheet` pre-loaded from `NodeConfigCodec.decode(node)`.
 * @param onLongPress invoked on long-press (multi-select entry).
 * @param onDrag invoked while the user is dragging the node; receives **canvas-space**
 * deltas. Compose delivers `dragAmount` in the pointerInput modifier's local layout space,
 * which already undoes the `graphicsLayer` zoom wrapping this composable — so the deltas
 * are layout pixels, and dividing by the display density turns them into canvas units
 * (dp). No second division by `transform.scale` is needed.
 * @param onDragEnd invoked once the pickup-release animation has settled; caller commits
 * the final canvas-space position to the ViewModel.
 * @param onConnectionStart invoked when the user starts dragging from an outbound port,
 * with the **port's label** (empty string for default single-out nodes; "True" / "False" /
 * "Item" / "Done" / "Pass" / "Retry" / "Fail" / IntentRouter class names for multi-out
 * nodes). The caller stores the label on `ConnectionDraft.sourcePortLabel` so the eventual
 * `addConnection` carries it through to the persisted `ConnectionModel.label`.
 * @param onConnectionMove receives the **absolute pointer position in canvas-Box-local
 * space**, computed via
 * `canvasLayoutCoordinates.localPositionOf(portCoords, change.position)`. Absolute
 * positions are more robust than per-event deltas: they pass through every transform
 * (including the per-node `graphicsLayer` scale that bakes `transform.scale` into the
 * node's local coords) without accumulating rounding error or losing the touch-slop
 * distance Compose hides between `awaitDownEvent` and the first drag event.
 * @param onConnectionEnd invoked on a deliberate release. The caller hit-tests the pointer
 * position from `EditorState.connectionInProgress` (already in canvas-space) directly — no
 * coordinate parameters are needed here.
 * @param onConnectionCancel invoked when the connection gesture is cancelled by the system
 * (gesture-arbitration loss, a second pointer) rather than released by the user. The caller
 * discards the draft without the "missed target" feedback that [onConnectionEnd] surfaces.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("LongParameterList") // The canvas node needs every input as a single seam.
internal fun EditorNode(
    node: NodeModel,
    transform: CanvasTransform,
    selected: Boolean,
    multiSelected: Boolean,
    error: NodeError?,
    ports: NodePorts,
    reducedMotion: Boolean,
    subtitle: String? = null,
    onSelect: () -> Unit,
    onOpenConfig: () -> Unit,
    onLongPress: () -> Unit,
    onDrag: (dxCanvas: Float, dyCanvas: Float) -> Unit,
    onDragEnd: () -> Unit,
    onConnectionStart: (portLabel: String, pointerCanvasBoxX: Float, pointerCanvasBoxY: Float) -> Unit,
    onConnectionMove: (pointerCanvasBoxX: Float, pointerCanvasBoxY: Float) -> Unit,
    onConnectionEnd: () -> Unit,
    onConnectionCancel: () -> Unit,
    canvasLayoutCoordinatesRef: CoordinatesRef,
) {
    val scale = remember { AnimFloat(1f) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(isDragging) {
        val target = if (isDragging) DRAG_PICKUP_SCALE else 1f
        if (reducedMotion) {
            scale.snapTo(target)
        } else if (isDragging) {
            scale.animateTo(target, tween(DRAG_PICKUP_DURATION_MS))
        } else {
            scale.animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        }
    }

    val density = LocalDensity.current.density
    val screenX = transform.canvasToScreenX(node.x)
    val screenY = transform.canvasToScreenY(node.y)
    Box(
        modifier = Modifier
            .graphicsLayer {
                translationX = screenX
                translationY = screenY
                scaleX = scale.value * transform.scale
                scaleY = scale.value * transform.scale
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                // Tap on an unselected node selects it. Tap on the same node again
                // (already selected, not in multi-select mode) opens its configuration
                // sheet — this is how the user reaches "node properties" without an
                // extra affordance on the card.
                onClick = { if (selected) onOpenConfig() else onSelect() },
                onLongClick = onLongPress,
            )
            .pointerInput(node.id, density) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        onDragEnd()
                    },
                    onDragCancel = {
                        isDragging = false
                        onDragEnd()
                    },
                    onDrag = { _, drag ->
                        onDrag(drag.x / density, drag.y / density)
                    },
                )
            },
    ) {
        NodeCard(
            type = NodeTypeMapper.toCatalog(node.type),
            title = node.label.ifBlank { node.type.name },
            subtitle = subtitle,
            selected = selected,
            error = error,
            multiSelected = multiSelected,
            ports = ports,
        )
        // One hit-target circle per outbound port. Each is 24 dp wide and straddles the
        // node's bottom edge (via .offset, since Modifier.padding rejects negative values).
        // For multi-out nodes (IF / Queue / Eval / IntentRouter) the per-port X offset uses
        // the same `outboundPortOffsetDp` rule the edge layer uses, so the user grabs the
        // dot that corresponds to the label they want — and `onConnectionStart(port.label)`
        // forwards that label to the canvas so the eventual ConnectionModel carries it.
        //
        // Routed via its own pointerInput so the gesture arbitration is unambiguous:
        // dragging from this Box always means "start a connection", never "move the node".
        ports.outbound.forEachIndexed { index, port ->
            val portOffsetDp = outboundPortOffsetDp(index, ports.outbound.size)
            // Capture the port-box's LayoutCoordinates into a NON-state CoordinatesRef.
            // Holding it in `mutableStateOf` would trigger a re-layout pump for the same
            // reason canvasLayoutCoordinatesRef does (LayoutCoordinates has identity-only
            // equality, so every layout pass would mark state as changed). The pointer
            // handler reads `.value` just-in-time inside its drag callbacks; the same
            // instance always reflects the current layout, so just-in-time reads stay
            // correct without notifying Compose.
            val portCoordsRef = remember(node.id, port.label) { CoordinatesRef() }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .size(PORT_HIT_TARGET_DP.dp)
                    .offset(x = portOffsetDp.dp, y = (PORT_HIT_TARGET_DP / 2).dp)
                    .clip(CircleShape)
                    .background(Color.Transparent)
                    .onGloballyPositioned { portCoordsRef.value = it }
                    .pointerInput(node.id, port.label) {
                        detectDragGestures(
                            onDragStart = { localStart ->
                                val pc = portCoordsRef.value ?: return@detectDragGestures
                                val canvas = canvasLayoutCoordinatesRef.value ?: return@detectDragGestures
                                val pos = canvas.localPositionOf(pc, localStart)
                                onConnectionStart(port.label, pos.x, pos.y)
                            },
                            onDrag = { change, _ ->
                                val pc = portCoordsRef.value ?: return@detectDragGestures
                                val canvas = canvasLayoutCoordinatesRef.value ?: return@detectDragGestures
                                val pos = canvas.localPositionOf(pc, change.position)
                                onConnectionMove(pos.x, pos.y)
                            },
                            onDragEnd = { onConnectionEnd() },
                            // A *cancelled* gesture (gesture-arbitration loss, a second
                            // pointer) is not a deliberate drop, so it discards the draft
                            // silently — routing it through onConnectionEnd would fire the
                            // "drag onto a node to connect" hint the user never earned.
                            onDragCancel = { onConnectionCancel() },
                        )
                    },
            )
        }
    }
}

/**
 * Builds [NodePorts] for [node]. For [app.knotwork.android.domain.models.NodeType.INTENT_ROUTER]
 * the class names come from the persisted `NodeConfig` payload (via the codec); for
 * [app.knotwork.android.domain.models.NodeType.EVALUATION] the retry-port is derived from
 * the same payload.
 *
 * Pure helper — extracted so the snapshot tests can pass a deterministic ports object.
 */
internal fun portsForNode(node: NodeModel, ports: NodePorts? = null): NodePorts =
    ports ?: NodePorts.forType(type = NodeTypeMapper.toCatalog(node.type))

// Keep the KnotworkTheme import alive for callers that inline tokens against the same theme.
@Suppress("unused")
private val themeReferenceMarker = KnotworkTheme
