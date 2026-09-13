package app.knotwork.android.presentation.ui.pipeline.editor.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.knotwork.android.R
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.presentation.ui.pipeline.editor.config.NodeTypeMapper
import app.knotwork.android.presentation.ui.pipeline.editor.core.Bounds
import app.knotwork.android.presentation.ui.pipeline.editor.core.CanvasTransform
import app.knotwork.android.presentation.ui.pipeline.editor.core.MiniMapGeometry
import app.knotwork.android.presentation.ui.pipeline.editor.core.NodeCardFootprint
import app.knotwork.design.components.pipelineeditor.headerTint
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles
import kotlin.math.roundToInt

/**
 * Mini-map overlay: a scaled bird's-eye view of the pipeline canvas pinned to
 * the bottom-right corner. Each node renders as a coloured brick in its per-type
 * hue; the current viewport is outlined as an accent rectangle. Tapping inside
 * the body centres the canvas on the tapped canvas-space point.
 *
 * Visibility is driven by `EditorState.miniMapOpen`; this composable assumes the
 * caller already gated on that flag.
 *
 * @param graph the live pipeline graph — drives brick positions and hues.
 * @param transform the live canvas transform — drives the viewport rectangle.
 * @param viewportSize the canvas viewport size in screen pixels.
 * @param onTapCanvasPoint invoked with the canvas-space coordinates of the
 *   tap; caller typically pipes into `transform.centeredOn(x, y, …)`.
 * @param onClose invoked when the header's `×` button is tapped.
 * @param modifier optional layout modifier applied to the mini-map root (use
 *   the parent `Box` `Alignment.BottomEnd` to position it).
 */
@Composable
internal fun MiniMap(
    graph: PipelineGraph,
    transform: CanvasTransform,
    viewportSize: IntSize,
    onTapCanvasPoint: (canvasX: Float, canvasY: Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(width = MiniMapWidth, height = MiniMapHeight),
        color = KnotworkTheme.extended.surface1,
        shape = KnotworkTheme.shapes.md,
        tonalElevation = KnotworkTheme.elevation.el2,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = 1.dp,
                    color = KnotworkTheme.extended.outlineStrong,
                    shape = KnotworkTheme.shapes.md,
                ),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Header(transform = transform, onClose = onClose)
                MiniMapBody(
                    graph = graph,
                    transform = transform,
                    viewportSize = viewportSize,
                    onTapCanvasPoint = onTapCanvasPoint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = KnotworkTheme.spacing.sp2)
                        .padding(bottom = KnotworkTheme.spacing.sp2),
                )
            }
        }
    }
}

/** Header: uppercase OVERVIEW label + zoom percentage on the right + close button. */
@Composable
private fun Header(transform: CanvasTransform, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = KnotworkTheme.spacing.sp3, end = KnotworkTheme.spacing.sp1)
            .padding(vertical = KnotworkTheme.spacing.sp1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
    ) {
        Text(
            text = stringResource(R.string.pipeline_editor_mini_map_title),
            style = KnotworkTextStyles.LabelSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
        Text(
            text = formatScalePercent(transform.scale),
            style = KnotworkTextStyles.LabelSm,
            color = KnotworkTheme.extended.onSurface2,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = AppIcons.X,
                contentDescription = stringResource(R.string.pipeline_editor_mini_map_close),
                tint = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

/** Body: per-node bricks + viewport rectangle. */
@Composable
private fun MiniMapBody(
    graph: PipelineGraph,
    transform: CanvasTransform,
    viewportSize: IntSize,
    onTapCanvasPoint: (canvasX: Float, canvasY: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bbox = remember(graph.nodes) {
        Bounds.ofNodes(
            positions = graph.nodes.map { it.x to it.y },
            nodeWidth = NodeCardFootprint.WIDTH,
            nodeHeight = NodeCardFootprint.MAX_HEIGHT,
        )
    }
    val accent = KnotworkTheme.extended.signalWarn
    val viewportFill = accent.copy(alpha = 0.10f)
    // Map each node to its per-type hue once per graph mutation so the draw
    // loop stays linear in node count and the lookup table doesn't churn.
    val nodeHues = graph.nodes.map { it to NodeTypeMapper.toCatalog(it.type).headerTint() }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(graph, transform, viewportSize) {
                    if (bbox == null) return@pointerInput
                    val geometry = MiniMapGeometry(
                        bbox = bbox,
                        miniWidth = size.width.toFloat(),
                        miniHeight = size.height.toFloat(),
                    )
                    detectTapGestures { tap ->
                        val canvasX = geometry.miniToCanvasX(tap.x)
                        val canvasY = geometry.miniToCanvasY(tap.y)
                        onTapCanvasPoint(canvasX, canvasY)
                    }
                },
        ) {
            if (bbox == null) return@Canvas
            val geometry = MiniMapGeometry(
                bbox = bbox,
                miniWidth = size.width,
                miniHeight = size.height,
            )
            // Bricks.
            nodeHues.forEach { (node, hue) ->
                val left = geometry.canvasToMiniX(node.x)
                val top = geometry.canvasToMiniY(node.y)
                val w = NodeCardFootprint.WIDTH * geometry.scale
                val h = NodeCardFootprint.MAX_HEIGHT * geometry.scale
                drawRect(
                    color = hue,
                    topLeft = Offset(left, top),
                    size = Size(width = w, height = h),
                )
            }
            // Viewport rectangle on top of the bricks.
            val rect = geometry.viewportRect(
                transform = transform,
                viewportW = viewportSize.width.toFloat(),
                viewportH = viewportSize.height.toFloat(),
            )
            if (!rect.isEmpty) {
                drawRect(
                    color = viewportFill,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(width = rect.width, height = rect.height),
                )
                drawRect(
                    color = accent,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(width = rect.width, height = rect.height),
                    style = Stroke(width = 2f),
                )
            }
        }
    }
}

/** Formats a CanvasTransform scale as `"0.42×"`. */
internal fun formatScalePercent(scale: Float): String {
    val hundredths = (scale * 100f).roundToInt()
    val whole = hundredths / HUNDREDTHS_PER_UNIT
    val rem = hundredths % HUNDREDTHS_PER_UNIT
    val frac = if (rem < HUNDREDTHS_TWO_DIGIT_THRESHOLD) "0$rem" else "$rem"
    return "$whole.$frac×"
}

private const val HUNDREDTHS_PER_UNIT = 100
private const val HUNDREDTHS_TWO_DIGIT_THRESHOLD = 10

private val MiniMapWidth = 270.dp
private val MiniMapHeight = 290.dp
