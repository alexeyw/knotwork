package app.knotwork.design.foundations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkPalette
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Catalog page that renders the Knotwork foundations — palette, type scale
 * and spacing — in a single scrollable surface.
 *
 * Used both as the entry-point preview for the design system (see
 * [FoundationsCatalogPagePreview]) and as the deterministic input for the
 * baseline Roborazzi snapshots in `:catalog/src/test/snapshots/`. The page
 * must therefore depend only on token state that is guaranteed-deterministic
 * across runs (no time, no random ids, no animations).
 *
 * Layout:
 *  1. Palette — accent ramp (50..900), surfaces, signal, risk, the 12 node hues.
 *  2. Type scale — every entry from [KnotworkTextStyles] with a label + sample.
 *  3. Spacing — horizontal bars sized by every step in `KnotworkTheme.spacing`.
 *
 * Renders inside the parent [KnotworkTheme]; the page itself does not wrap
 * itself in the theme so callers can pin `darkTheme` deterministically.
 */
@Composable
fun FoundationsCatalogPage() {
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
            item { SectionHeader(text = "Palette") }
            item { PaletteSection() }
            item { SectionHeader(text = "Type scale") }
            item { TypeScaleSection() }
            item { SectionHeader(text = "Spacing") }
            item { SpacingSection() }
        }
    }
}

/** Section title rendered above each foundation block. */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = KnotworkTextStyles.TitleLg,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = KnotworkTheme.spacing.sp2),
    )
}

/** Palette section — accent / surfaces / signal / risk / node hues. */
@Composable
private fun PaletteSection() {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3)) {
        SwatchRow(
            label = "Accent ramp",
            swatches = listOf(
                "50" to KnotworkPalette.Accent50,
                "100" to KnotworkPalette.Accent100,
                "200" to KnotworkPalette.Accent200,
                "300" to KnotworkPalette.Accent300,
                "400" to KnotworkPalette.Accent400,
                "500" to KnotworkPalette.Accent500,
                "600" to KnotworkPalette.Accent600,
                "700" to KnotworkPalette.Accent700,
                "800" to KnotworkPalette.Accent800,
                "900" to KnotworkPalette.Accent900,
            ),
        )
        SwatchRow(
            label = "Surface",
            swatches = listOf(
                "surface" to MaterialTheme.colorScheme.surface,
                "s1" to KnotworkTheme.extended.surface1,
                "s2" to KnotworkTheme.extended.surface2,
                "s3" to KnotworkTheme.extended.surface3,
                "s4" to KnotworkTheme.extended.surface4,
                "inv" to MaterialTheme.colorScheme.inverseSurface,
            ),
        )
        SwatchRow(
            label = "Signal",
            swatches = listOf(
                "success" to KnotworkTheme.extended.signalSuccess,
                "warn" to KnotworkTheme.extended.signalWarn,
                "error" to KnotworkTheme.extended.signalError,
            ),
        )
        SwatchRow(
            label = "Risk",
            swatches = listOf(
                "readonly" to KnotworkTheme.extended.riskReadonly,
                "sensitive" to KnotworkTheme.extended.riskSensitive,
                "destructive" to KnotworkTheme.extended.riskDestructive,
            ),
        )
        SwatchRow(
            label = "Node hues",
            swatches = listOf(
                "input" to KnotworkTheme.extended.nodeInput,
                "intent" to KnotworkTheme.extended.nodeIntentRouter,
                "if" to KnotworkTheme.extended.nodeIfCondition,
                "clarif" to KnotworkTheme.extended.nodeClarification,
                "lite" to KnotworkTheme.extended.nodeLiteRt,
                "cloud" to KnotworkTheme.extended.nodeCloud,
                "tool" to KnotworkTheme.extended.nodeTool,
                "decomp" to KnotworkTheme.extended.nodeDecomposition,
                "queue" to KnotworkTheme.extended.nodeQueueProcessor,
                "eval" to KnotworkTheme.extended.nodeEvaluation,
                "summary" to KnotworkTheme.extended.nodeSummary,
                "output" to KnotworkTheme.extended.nodeOutput,
            ),
        )
    }
}

/**
 * Labelled grid of square colour swatches.
 *
 * Swatches wrap onto additional rows via [FlowRow] so the full token set
 * stays visible at any viewport width without horizontal scrolling. At the
 * snapshot reference width (360 dp) the 10-step accent ramp and 12 node
 * hues lay out as two short rows; on a wider viewport they collapse back
 * into a single row.
 */
@Composable
private fun SwatchRow(label: String, swatches: List<Pair<String, Color>>) {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
        Text(
            text = label,
            style = KnotworkTextStyles.LabelMd,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        ) {
            swatches.forEach { (name, color) -> Swatch(name = name, color = color) }
        }
    }
}

/** Single 56 dp square swatch with a label below. */
@Composable
private fun Swatch(name: String, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(color = color, shape = RoundedCornerShape(8.dp))
                .border(
                    width = 1.dp,
                    color = KnotworkTheme.extended.divider,
                    shape = RoundedCornerShape(8.dp),
                ),
        )
        Text(
            text = name,
            style = KnotworkTextStyles.Caption,
            color = KnotworkTheme.extended.onSurface2,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(56.dp),
        )
    }
}

/** Type-scale section — one row per [KnotworkTextStyles] entry. */
@Composable
private fun TypeScaleSection() {
    val entries: List<Pair<String, TextStyle>> = listOf(
        "Display3xl" to KnotworkTextStyles.Display3xl,
        "Display2xl" to KnotworkTextStyles.Display2xl,
        "TitleXl" to KnotworkTextStyles.TitleXl,
        "TitleLg" to KnotworkTextStyles.TitleLg,
        "TitleMd" to KnotworkTextStyles.TitleMd,
        "BodyLg" to KnotworkTextStyles.BodyLg,
        "BodyBase" to KnotworkTextStyles.BodyBase,
        "BodySm" to KnotworkTextStyles.BodySm,
        "LabelLg" to KnotworkTextStyles.LabelLg,
        "LabelMd" to KnotworkTextStyles.LabelMd,
        "LabelSm" to KnotworkTextStyles.LabelSm,
        "Caption" to KnotworkTextStyles.Caption,
        "MonoBase" to KnotworkTextStyles.MonoBase,
        "MonoSm" to KnotworkTextStyles.MonoSm,
    )
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
        entries.forEach { (name, style) ->
            Column {
                Text(
                    text = name,
                    style = KnotworkTextStyles.Caption.copy(fontWeight = FontWeight.Medium),
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
                Text(
                    text = "The quick brown fox jumps over the lazy dog",
                    style = style,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

/** Spacing section — horizontal bars sized by every spacing token. */
@Composable
private fun SpacingSection() {
    val entries: List<Pair<String, Dp>> = listOf(
        "sp1" to KnotworkTheme.spacing.sp1,
        "sp2" to KnotworkTheme.spacing.sp2,
        "sp3" to KnotworkTheme.spacing.sp3,
        "sp4" to KnotworkTheme.spacing.sp4,
        "sp5" to KnotworkTheme.spacing.sp5,
        "sp6" to KnotworkTheme.spacing.sp6,
        "sp8" to KnotworkTheme.spacing.sp8,
        "sp10" to KnotworkTheme.spacing.sp10,
        "sp12" to KnotworkTheme.spacing.sp12,
        "sp16" to KnotworkTheme.spacing.sp16,
    )
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
        entries.forEach { (name, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                    modifier = Modifier.width(40.dp),
                )
                Spacer(modifier = Modifier.width(KnotworkTheme.spacing.sp2))
                Box(
                    modifier = Modifier
                        .height(12.dp)
                        .width(value)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
    }
}
