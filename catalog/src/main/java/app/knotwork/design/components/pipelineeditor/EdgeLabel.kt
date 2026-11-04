package app.knotwork.design.components.pipelineeditor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Outer-border stroke width for [EdgeLabel]. */
private val EdgeLabelBorderWidth = 1.dp

/** Inner padding around the [EdgeLabel] text. */
private val EdgeLabelInnerPadding = 4.dp

/**
 * Floating chip rendered on top of a pipeline edge to label the branch
 * the edge represents (`True / False / Item / Done / Pass / Retry / Fail`
 * or an `IntentRouter` class name).
 *
 * Visual contract: `LabelSm` text on `surface1` with a
 * 1 dp `outline` border and 4 dp padding. `Shape.Sm` corners.
 *
 * **Stateless** — the canvas owns positioning the chip over the edge; this
 * composable just renders the chip itself.
 *
 * @param text label content (e.g. "True", "Item", "simple-query").
 * @param modifier optional layout modifier applied to the chip root.
 */
@Composable
fun EdgeLabel(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(KnotworkTheme.shapes.sm)
            .background(color = KnotworkTheme.extended.surface1)
            .border(
                border = BorderStroke(
                    width = EdgeLabelBorderWidth,
                    color = MaterialTheme.colorScheme.outline,
                ),
                shape = KnotworkTheme.shapes.sm,
            )
            .padding(EdgeLabelInnerPadding),
    ) {
        Text(
            text = text,
            style = KnotworkTextStyles.LabelSm,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Convenience overload that derives the label text from an
 * [OutboundPort]'s [OutboundPort.label]. Falls back to an empty chip for
 * [OutboundPort.Default] so callers do not have to special-case the
 * "no label" port.
 *
 * @param port the outbound port whose label drives the chip text.
 * @param modifier optional layout modifier applied to the chip root.
 */
@Composable
fun EdgeLabel(port: OutboundPort, modifier: Modifier = Modifier) {
    EdgeLabel(text = port.label, modifier = modifier)
}

/** Light-theme preview rendering every canonical label kind. */
@Preview(name = "EdgeLabel — variants", showBackground = true)
@Composable
private fun EdgeLabelPreview() {
    KnotworkTheme(darkTheme = false) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(KnotworkTheme.spacing.sp4),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                KnotworkTheme.spacing.sp2,
            ),
        ) {
            EdgeLabel(text = "True")
            EdgeLabel(text = "False")
            EdgeLabel(text = "Item")
            EdgeLabel(text = "Done")
            EdgeLabel(text = "Pass")
            EdgeLabel(text = "Retry")
            EdgeLabel(text = "Fail")
            EdgeLabel(text = "simple-query")
        }
    }
}
