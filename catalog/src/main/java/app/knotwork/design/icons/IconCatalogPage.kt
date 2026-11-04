package app.knotwork.design.icons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Catalog page that renders every custom icon from [AppIcons] plus a curated
 * sample of Material Icons Extended entries. The page
 * is the visual smoke-test for the hybrid icon strategy:
 * each row shows the glyph at the three canonical UI sizes (24/32/48 dp) over
 * a light and a dark swatch, with the source label ("Custom" or "Material")
 * pinned to the name.
 *
 * Renders inside the parent [KnotworkTheme]; the page itself does not wrap
 * itself in the theme so callers can pin `darkTheme` deterministically.
 */
@Composable
fun IconCatalogPage() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(KnotworkTheme.spacing.sp4),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp6),
        ) {
            item { SectionHeader(text = "Custom (AppIcons)") }
            items(customEntries) { entry ->
                IconRow(entry = entry)
            }
            item { SectionHeader(text = "Material") }
            items(materialEntries) { entry ->
                IconRow(entry = entry)
            }
        }
    }
}

/** Inventory of every custom icon ported under `icons/imagevector/`. */
private val customEntries: List<IconEntry> = listOf(
    IconEntry("Mark", AppIcons.Mark, IconSource.Custom),
    IconEntry("Wordmark", AppIcons.Wordmark, IconSource.Custom),
    IconEntry("Flow", AppIcons.Flow, IconSource.Custom),
    IconEntry("AutoLayout", AppIcons.AutoLayout, IconSource.Custom),
    IconEntry("Brain", AppIcons.Brain, IconSource.Custom),
    IconEntry("NodeInput", AppIcons.NodeInput, IconSource.Custom),
    IconEntry("NodeIntentRouter", AppIcons.NodeIntentRouter, IconSource.Custom),
    IconEntry("NodeBranch", AppIcons.NodeBranch, IconSource.Custom),
    IconEntry("NodeClarify", AppIcons.NodeClarify, IconSource.Custom),
    IconEntry("NodeLite", AppIcons.NodeLite, IconSource.Custom),
    IconEntry("NodeCloud", AppIcons.NodeCloud, IconSource.Custom),
    IconEntry("NodeTool", AppIcons.NodeTool, IconSource.Custom),
    IconEntry("NodeDecompose", AppIcons.NodeDecompose, IconSource.Custom),
    IconEntry("NodeQueue", AppIcons.NodeQueue, IconSource.Custom),
    IconEntry("NodeEval", AppIcons.NodeEval, IconSource.Custom),
    IconEntry("NodeSummary", AppIcons.NodeSummary, IconSource.Custom),
    IconEntry("NodeOutput", AppIcons.NodeOutput, IconSource.Custom),
)

/**
 * Curated sample of Material Icons Extended entries. Not exhaustive — it is
 * enough to confirm the visual
 * weight matches the custom glyphs at the same size.
 */
private val materialEntries: List<IconEntry> = listOf(
    IconEntry("Chat (nav)", AppIcons.Chat, IconSource.Material),
    IconEntry("Tools (nav)", AppIcons.Extension, IconSource.Material),
    IconEntry("More (nav)", AppIcons.More2, IconSource.Material),
    IconEntry("Send", AppIcons.ArrowUpLine, IconSource.Material),
    IconEntry("Console", AppIcons.Terminal, IconSource.Material),
    IconEntry("Run", AppIcons.Play, IconSource.Material),
    IconEntry("Confirm", AppIcons.Check, IconSource.Material),
    IconEntry("Delete", AppIcons.Trash, IconSource.Material),
    IconEntry("Search", AppIcons.Search, IconSource.Material),
    IconEntry("MCP", AppIcons.Link, IconSource.Material),
    IconEntry("Theme", AppIcons.Theme, IconSource.Material),
)

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = KnotworkTextStyles.TitleLg,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = KnotworkTheme.spacing.sp2),
    )
}

/**
 * Renders a single catalog row: name + source pill (left column) followed by
 * the glyph at 24/32/48 dp on a light surface swatch and then again on a
 * dark surface swatch — six renders per row total.
 */
@Composable
private fun IconRow(entry: IconEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.name,
                style = KnotworkTextStyles.LabelLg,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Box(
                modifier = Modifier
                    .padding(start = KnotworkTheme.spacing.sp2)
                    .background(
                        color = entry.source.tagBackground(),
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = entry.source.label,
                    style = KnotworkTextStyles.LabelSm,
                    color = entry.source.tagForeground(),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        ) {
            IconSwatch(vector = entry.vector, background = SwatchBackground.Light)
            IconSwatch(vector = entry.vector, background = SwatchBackground.Dark)
        }
    }
}

/**
 * Renders a single icon at 24/32/48 dp on the requested background swatch.
 * The icon tint follows the swatch's contrasting `onColor` so glyphs read at
 * any size regardless of which token mode the page itself is rendered in.
 */
@Composable
private fun IconSwatch(vector: ImageVector, background: SwatchBackground) {
    val bg = when (background) {
        SwatchBackground.Light -> MaterialTheme.colorScheme.surface
        SwatchBackground.Dark -> MaterialTheme.colorScheme.inverseSurface
    }
    val fg = when (background) {
        SwatchBackground.Light -> MaterialTheme.colorScheme.onSurface
        SwatchBackground.Dark -> MaterialTheme.colorScheme.inverseOnSurface
    }
    Row(
        modifier = Modifier
            .background(color = bg, shape = RoundedCornerShape(10.dp))
            .border(
                width = 1.dp,
                color = KnotworkTheme.extended.divider,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(KnotworkTheme.spacing.sp3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        Icon(
            imageVector = vector,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(24.dp),
        )
        Icon(
            imageVector = vector,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(32.dp),
        )
        Icon(
            imageVector = vector,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(48.dp),
        )
    }
}

private data class IconEntry(val name: String, val vector: ImageVector, val source: IconSource)

private enum class IconSource(val label: String) {
    Custom("Custom"),
    Material("Material"),
}

@Composable
private fun IconSource.tagBackground() = when (this) {
    IconSource.Custom -> KnotworkTheme.extended.surface2
    IconSource.Material -> KnotworkTheme.extended.surface3
}

@Composable
private fun IconSource.tagForeground() = when (this) {
    IconSource.Custom -> KnotworkTheme.extended.onSurface2
    IconSource.Material -> KnotworkTheme.extended.onSurfaceMuted
}

private enum class SwatchBackground { Light, Dark }
