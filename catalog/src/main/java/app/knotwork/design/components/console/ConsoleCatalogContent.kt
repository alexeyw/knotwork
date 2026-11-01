package app.knotwork.design.components.console

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Demo log lines used by [ConsoleCatalogContent] previews and snapshots. */
private val DemoLogs = listOf(
    ConsoleLine(
        timestamp = "09:14:01.122",
        source = ConsoleSource.RUNTIME,
        level = ConsoleLevel.Info,
        text = "Pipeline run started",
    ),
    ConsoleLine(
        timestamp = "09:14:01.341",
        source = ConsoleSource.NODE,
        level = ConsoleLevel.Trace,
        text = "INTENT_ROUTER → 'summarise' (0.92)",
    ),
    ConsoleLine(
        timestamp = "09:14:01.500",
        source = ConsoleSource.TOOL,
        level = ConsoleLevel.Info,
        text = "fs.read_file ok (1.2 KB)",
    ),
    ConsoleLine(
        timestamp = "09:14:02.812",
        source = ConsoleSource.NODE,
        level = ConsoleLevel.Warn,
        text = "LITE_RT context window approaching limit",
    ),
    ConsoleLine(
        timestamp = "09:14:04.024",
        source = ConsoleSource.RUNTIME,
        level = ConsoleLevel.Error,
        text = "EVALUATION node failed: rubric threshold not met",
    ),
)

/** Demo var rows used by [ConsoleCatalogContent] previews and snapshots. */
private val DemoVars = listOf(
    ConsoleVarRow(node = "intent_router_1", key = "predicted", value = "summarise"),
    ConsoleVarRow(node = "intent_router_1", key = "confidence", value = "0.92"),
    // Line breaks drawn as marks, and more than three lines' worth: a cut row with its size.
    ConsoleVarRow(
        node = "lite_rt_1",
        key = "input",
        value = "You are summarising today's calendar for one person.\n\nRules:\n" +
            "- One sentence per event, in time order.\n- Give the start time first.\n" +
            "- Leave out events that were declined.",
    ),
    ConsoleVarRow(node = "lite_rt_1", key = "reason", value = ""),
)

/** Demo trace spans used by [ConsoleCatalogContent] previews and snapshots. */
private val DemoTraces = listOf(
    ConsoleTraceSpan(name = "input", durationMs = 12, startedAt = "09:14:01.122", status = SpanStatus.Ok),
    ConsoleTraceSpan(name = "intent_router_1", durationMs = 219, startedAt = "09:14:01.134", status = SpanStatus.Ok),
    ConsoleTraceSpan(name = "lite_rt_1", durationMs = 1312, startedAt = "09:14:01.500", status = SpanStatus.Ok),
    ConsoleTraceSpan(name = "evaluation_1", durationMs = 412, startedAt = "09:14:03.612", status = SpanStatus.Error),
)

/**
 * Composable harness exercising [ConsolePane] in all three snap points
 * (`Peek`, `Partial`, `Full`) across each tab. Used by the Android Studio
 * preview pane and by the Roborazzi snapshot baseline.
 *
 * Renders inside the parent [KnotworkTheme]; the console itself is dark in
 * both themes by design (`extended.consoleBg` is theme-locked).
 */
@Composable
fun ConsoleCatalogContent() {
    Surface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(KnotworkTheme.spacing.sp4),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp4),
        ) {
            SectionLabel(text = "ConsolePane — Logs")
            var filter by remember { mutableStateOf(ConsoleFilter.allOn) }
            ConsolePane(
                tab = ConsoleTab.Logs,
                onTabChange = {},
                logs = DemoLogs,
                vars = DemoVars,
                traces = DemoTraces,
                filter = filter,
                onFilterChange = { filter = it },
                onSearch = {},
                onCopyAll = {},
                onClear = {},
            )

            SectionLabel(text = "ConsolePane — Vars")
            ConsolePane(
                tab = ConsoleTab.Vars,
                onTabChange = {},
                logs = DemoLogs,
                vars = DemoVars,
                traces = DemoTraces,
                filter = ConsoleFilter.allOn,
                onFilterChange = {},
                onSearch = {},
                onCopyAll = {},
                onClear = {},
            )

            SectionLabel(text = "ConsolePane — Traces")
            ConsolePane(
                tab = ConsoleTab.Traces,
                onTabChange = {},
                logs = DemoLogs,
                vars = DemoVars,
                traces = DemoTraces,
                filter = ConsoleFilter.allOn,
                onFilterChange = {},
                onSearch = {},
                onCopyAll = {},
                onClear = {},
            )
        }
    }
}

/** Section title rendered above each [ConsolePane] variant. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = KnotworkTextStyles.LabelMd,
        color = KnotworkTheme.extended.onSurfaceMuted,
    )
}

/** Light-theme preview — console body stays dark by design. */
@Preview(name = "Console — Light", showBackground = true, heightDp = 1700)
@Composable
private fun ConsoleCatalogLightPreview() {
    KnotworkTheme(darkTheme = false) { ConsoleCatalogContent() }
}

/** Dark-theme preview. */
@Preview(name = "Console — Dark", showBackground = true, heightDp = 1700)
@Composable
private fun ConsoleCatalogDarkPreview() {
    KnotworkTheme(darkTheme = true) { ConsoleCatalogContent() }
}
