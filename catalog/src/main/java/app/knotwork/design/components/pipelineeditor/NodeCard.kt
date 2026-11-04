package app.knotwork.design.components.pipelineeditor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Minimum NodeCard surface height — body wraps within this floor. */
private val NodeCardMinHeight = NodePortGeometry.MIN_SURFACE_HEIGHT.dp

/** Maximum NodeCard height — body clamps to two lines past this ceiling. */
private val NodeCardMaxHeight = 96.dp

/** Header strip height. */
private val HeaderStripHeight = 28.dp

/** Selected outer-border stroke (spec). */
private val SelectedBorderWidth = 2.dp

/** Error outer-border stroke (spec). */
private val ErrorBorderWidth = 2.dp

/** Idle outer-border stroke (spec). */
private val IdleBorderWidth = 1.dp

/** Multi-select corner-chevron side (spec). */
private val MultiSelectChevronSize = 8.dp

/** Half the visual port-dot — equals how far each dot protrudes past the card edge. */
private val PortDotRadius = NodePortGeometry.DOT_RADIUS.dp

/** Visual port-dot diameter. */
private val PortDotVisualDiameter = PortDotRadius * 2

/** Stroke width applied to the port-dot border. */
private val PortDotBorderWidth = 1.dp

/** Header glyph diameter. */
private val HeaderGlyphSize = 16.dp

/** Vertical gap between an outbound dot and its label. */
private val PortLabelGap = NodePortGeometry.LABEL_GAP.dp

/** Test tag of the card's surface — the rounded body whose edges the port dots sit on. */
const val NODE_CARD_SURFACE_TEST_TAG: String = "node_card_surface"

/** Test tag of the inbound port dot. */
const val NODE_CARD_INBOUND_DOT_TEST_TAG: String = "node_card_inbound_dot"

/** Test-tag prefix of an outbound port dot; the port's index, left to right, follows. */
const val NODE_CARD_OUTBOUND_DOT_TEST_TAG_PREFIX: String = "node_card_outbound_dot_"

/** Header strip opacity. Constant — the card has no animated state. */
private const val HEADER_STRIP_ALPHA = 1.0f

/** Header label letter-tracking. */
private const val HEADER_LABEL_TRACKING_EM = 0.08f

/**
 * Pipeline-editor node card — single composable covering idle, selected,
 * multi-selected, and error (validation / runtime) states.
 *
 * Geometry comes from the constants above; tints from
 * [NodeType.headerTint] / [headerOnColor].
 *
 * **Stateless** — all interactivity (tap to select, long-press to multi-
 * select, drag from a port to enter connection mode) is owned by the
 * canvas. This composable just renders. Selection and error visuals are
 * driven by the parameters below.
 *
 * @param type the node type. Drives header tint, glyph, and uppercase
 * label.
 * @param title the node's display title shown as `TitleMd` in the body.
 * @param subtitle optional one-line secondary text shown as `BodySm`
 * below the title (model id, expression, …). `null` hides the line and
 * frees vertical space for a one-line body.
 * @param selected `true` swaps the outline for the 2 dp `accent500`
 * selected border and bumps elevation to `el3`.
 * @param error `null` for idle; [NodeError.Validation] swaps the type
 * label for a warning glyph; [NodeError.Runtime] surfaces the cause
 * inline.
 * @param multiSelected `true` swaps the outline for the 2 dp `accent300`
 * multi-select border and renders the top-right chevron marker.
 * @param ports inbound / outbound port descriptors. Multi-out nodes show
 * the per-port label under each dot in `LabelSm`. The card is
 * [NodePortGeometry.cardWidth] wide for their number, and each dot sits at
 * [NodePortGeometry.outboundOffset] — the positions the editor canvas
 * places its edges and touch targets by.
 * @param modifier optional layout modifier applied to the card root.
 */
@Composable
fun NodeCard(
    type: NodeType,
    title: String,
    subtitle: String?,
    selected: Boolean,
    error: NodeError?,
    multiSelected: Boolean,
    ports: NodePorts,
    modifier: Modifier = Modifier,
) {
    val headerColor = type.headerTint()
    val onHeader = headerOnColor(strip = headerColor)
    val borderColor = nodeBorderColor(
        selected = selected,
        multiSelected = multiSelected,
        error = error,
    )
    val borderWidth = when {
        error != null || selected || multiSelected -> SelectedBorderWidth
        else -> IdleBorderWidth
    }
    val elevation = when {
        selected -> KnotworkTheme.elevation.el3
        else -> KnotworkTheme.elevation.el1
    }
    val showOutboundLabels = ports.outbound.size > 1
    val topInset = if (ports.inbound > 0) PortDotRadius else 0.dp
    val labelLineHeight = nodePortLabelLineHeight()
    val bottomInset = NodePortGeometry.belowOutboundDots(ports.outbound.size, labelLineHeight.value).dp
    // The outer Box hosts the Surface AND the port dots as siblings. Surface
    // clips its content to its rounded shape; placing dots at the Box level
    // (outside Surface) is what lets them protrude past the card edge per
    // spec ("baseline -6 dp into the header"). The Box pads the Surface
    // inward by exactly the protruding extent so dot centres land on the
    // Surface edges.
    Box(modifier = modifier.width(NodePortGeometry.cardWidth(ports.outbound.size).dp)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topInset, bottom = bottomInset)
                .testTag(NODE_CARD_SURFACE_TEST_TAG),
            color = MaterialTheme.colorScheme.surface,
            shape = KnotworkTheme.shapes.md,
            tonalElevation = elevation,
            shadowElevation = elevation,
            border = BorderStroke(width = borderWidth, color = borderColor),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = NodeCardMinHeight, max = NodeCardMaxHeight),
            ) {
                HeaderStrip(
                    type = type,
                    strip = headerColor,
                    onStrip = onHeader,
                    error = error,
                    alpha = HEADER_STRIP_ALPHA,
                )
                NodeBody(title = title, subtitle = subtitle, error = error)
            }
        }
        if (ports.inbound > 0) {
            InboundDot(color = headerColor)
        }
        if (ports.outbound.isNotEmpty()) {
            OutboundPortRow(
                ports = ports.outbound,
                color = headerColor,
                showLabels = showOutboundLabels,
                labelLineHeight = labelLineHeight,
            )
        }
        if (multiSelected) {
            MultiSelectChevron()
        }
    }
}

/** Header strip — 28 dp tall, hue-tinted, with glyph + uppercase type label. */
@Composable
private fun HeaderStrip(type: NodeType, strip: Color, onStrip: Color, error: NodeError?, alpha: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .background(color = strip)
            .padding(horizontal = KnotworkTheme.spacing.sp3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
    ) {
        Icon(
            imageVector = type.glyph(),
            contentDescription = null,
            tint = onStrip,
            modifier = Modifier.size(HeaderGlyphSize),
        )
        if (error is NodeError.Validation) {
            Icon(
                imageVector = AppIcons.Warn,
                contentDescription = null,
                tint = onStrip,
                modifier = Modifier.size(HeaderGlyphSize),
            )
        } else {
            Text(
                text = type.displayLabel(),
                style = KnotworkTextStyles.LabelSm.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = HEADER_LABEL_TRACKING_EM.em,
                ),
                color = onStrip,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Body — title + optional subtitle (or runtime error cause). */
@Composable
private fun NodeBody(title: String, subtitle: String?, error: NodeError?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
    ) {
        Text(
            text = title,
            style = KnotworkTextStyles.TitleMd,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when (error) {
            is NodeError.Runtime -> Text(
                text = error.message,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.signalError,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            else -> if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = KnotworkTextStyles.BodySm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Inbound dot — 12 dp visual circle centred on the Surface top edge.
 *
 * The dot is placed at the outer Box's `TopCenter`, so its top sits at
 * the Box top and its bottom 6 dp below. The Surface above is inset by
 * the same 6 dp, which means the dot's vertical centre lands exactly on
 * the Surface top edge (half above the card, half inside the header
 * strip — per spec "baseline -6 dp into the header").
 *
 * Hit-target wrapping (drag-from-port) is the canvas's responsibility;
 * the catalog renders the visual only.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.InboundDot(color: Color) {
    PortDot(
        color = color,
        modifier = Modifier.align(Alignment.TopCenter).testTag(NODE_CARD_INBOUND_DOT_TEST_TAG),
    )
}

/**
 * Outbound ports pinned to the bottom edge of the outer Box, each in its own slot: the dot at
 * [NodePortGeometry.outboundOffset] from the centre line, and — on a multi-port node — its
 * `LabelSm` label underneath, never wider than the slot. Every port is drawn, however many there
 * are; the card is as wide as [NodePortGeometry.cardWidth] needs.
 *
 * Vertical alignment is computed by the outer card layout — the Surface is inset by
 * [NodePortGeometry.belowOutboundDots] for a label exactly [labelLineHeight] tall, so the dot
 * centres land on the Surface bottom edge whether or not labels are present, at any font scale.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.OutboundPortRow(
    ports: List<OutboundPort>,
    color: Color,
    showLabels: Boolean,
    labelLineHeight: Dp,
) {
    val slot = NodePortGeometry.slotWidth(ports.size).dp
    ports.forEachIndexed { index, port ->
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(x = NodePortGeometry.outboundOffset(index, ports.size).dp)
                .width(slot),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PortDot(color = color, modifier = Modifier.testTag(NODE_CARD_OUTBOUND_DOT_TEST_TAG_PREFIX + index))
            if (showLabels) {
                Spacer(modifier = Modifier.size(PortLabelGap))
                Text(
                    text = port.label,
                    // Exactly one line tall at the current font scale: a single LabelSm line can
                    // measure short of it (13 dp of 14, measured) or, at 200 %, past the 14 dp the
                    // inset used to hold, which pushed the dots up onto the card's text.
                    modifier = Modifier.height(labelLineHeight),
                    style = KnotworkTextStyles.LabelSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Height of an outbound port's label line on screen: `LabelSm`'s line height converted at the
 * current font scale through [LocalDensity], the same conversion that lays the text out — so
 * Android 14's non-linear font scaling applies to both. 14 dp at font scale 1.
 *
 * [NodeCard] gives every port label exactly this height, so its outbound dots sit
 * [NodePortGeometry.belowOutboundDots] above the bottom of its layout box at any font scale; the
 * editor canvas reads the same value to find them.
 *
 * @return the label line height.
 */
@Composable
fun nodePortLabelLineHeight(): Dp = with(LocalDensity.current) { KnotworkTextStyles.LabelSm.lineHeight.toDp() }

/** Single port dot — 12 dp filled circle with a 1 dp surface-coloured ring. */
@Composable
private fun PortDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(PortDotVisualDiameter)
            .clip(CircleShape)
            .background(color = color)
            .border(width = PortDotBorderWidth, color = MaterialTheme.colorScheme.surface, shape = CircleShape),
    )
}

/** 4 dp accent-300 chevron rendered in the top-right when [multiSelected]. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.MultiSelectChevron() {
    Spacer(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(KnotworkTheme.spacing.sp1)
            .size(MultiSelectChevronSize)
            .clip(CircleShape)
            .background(color = KnotworkTheme.extended.outlineStrong),
    )
}

/** Border colour resolution — error wins over selected wins over multi-select. */
@Composable
private fun nodeBorderColor(selected: Boolean, multiSelected: Boolean, error: NodeError?): Color {
    val extended = KnotworkTheme.extended
    return when {
        error != null -> extended.signalError
        selected -> MaterialTheme.colorScheme.primary
        multiSelected -> extended.outlineStrong
        else -> extended.divider
    }
}

/** Light-theme idle preview. */
@Preview(name = "NodeCard — LiteRT idle", showBackground = true)
@Composable
private fun NodeCardLiteRtIdlePreview() {
    PreviewWrapper(darkTheme = false) {
        NodeCard(
            type = NodeType.LITE_RT,
            title = "Local response",
            subtitle = "gemma-2b-it",
            selected = false,
            error = null,
            multiSelected = false,
            ports = NodePorts.forType(NodeType.LITE_RT),
        )
    }
}

/** Light-theme selected preview. */
@Preview(name = "NodeCard — selected", showBackground = true)
@Composable
private fun NodeCardSelectedPreview() {
    PreviewWrapper(darkTheme = false) {
        NodeCard(
            type = NodeType.INTENT_ROUTER,
            title = "Route the request",
            subtitle = "5 classes",
            selected = true,
            error = null,
            multiSelected = false,
            ports = NodePorts.forType(
                NodeType.INTENT_ROUTER,
                intentClasses = listOf("simple", "complex"),
            ),
        )
    }
}

/** Wrap each preview in `KnotworkTheme` with a deterministic palette. */
@Composable
private fun PreviewWrapper(darkTheme: Boolean, content: @Composable () -> Unit) {
    app.knotwork.design.theme.KnotworkTheme(darkTheme = darkTheme) {
        Surface(modifier = Modifier.padding(KnotworkTheme.spacing.sp4)) {
            content()
        }
    }
}
