package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.design.components.console.ConsoleLevel
import app.knotwork.design.components.console.ConsoleLine
import app.knotwork.design.components.console.ConsoleSource
import app.knotwork.design.components.console.ConsoleTab
import app.knotwork.design.components.console.ConsoleTraceSpan
import app.knotwork.design.components.console.ConsoleVarRow
import app.knotwork.design.components.console.SpanStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ConsoleCopyPayloads].
 *
 * The defect these pin: the Vars and Traces tabs had no copy path at all, and
 * the header `Copy all` built its payload from log lines whatever tab was open —
 * so on those two tabs a visible button put something other than the visible
 * content on the clipboard. Pure formatting, so neither a delegate nor a
 * coroutine scope is needed to hold them.
 */
class ConsoleCopyPayloadsTest {

    private val logs = listOf(
        ConsoleLine("12:00:00.000", ConsoleSource.NODE, ConsoleLevel.Trace, "▶ INPUT"),
        ConsoleLine("12:00:01.000", ConsoleSource.TOOL, ConsoleLevel.Info, "tool"),
    )

    private val vars = listOf(
        ConsoleVarRow(node = "LITE_RT#1", key = "input", value = "prompt text"),
        ConsoleVarRow(node = "LITE_RT#1", key = "output", value = "answer text"),
    )

    private val traces = listOf(
        ConsoleTraceSpan("LITE_RT#1", durationMs = 12L, startedAt = "12:00:00.000", status = SpanStatus.Ok),
    )

    @Test
    fun `given a line when formatting then it renders timestamp source and text`() {
        val line = ConsoleLine("12:00:00.000", ConsoleSource.TOOL, ConsoleLevel.Info, "calendar.create")

        assertEquals("[12:00:00.000] [TOOL] calendar.create", ConsoleCopyPayloads.line(line))
    }

    @Test
    fun `given several lines when formatting then they are joined with newlines`() {
        assertEquals(
            "[12:00:00.000] [NODE] ▶ INPUT\n[12:00:01.000] [TOOL] tool",
            ConsoleCopyPayloads.allLines(logs),
        )
    }

    @Test
    fun `given a variable when formatting then the payload is the whole value and nothing else`() {
        // A node's assembled prompt is routinely thousands of characters and the
        // row shows a preview, so the payload must not abbreviate it — and it is
        // data to paste, so no header, no quotes, its own line breaks.
        val longValue = "--- Original Task ---\n\"quoted\"\n" + "x".repeat(4_000)
        val row = ConsoleVarRow(node = "LITE_RT#501fab", key = "input", value = longValue)

        assertEquals(longValue, ConsoleCopyPayloads.variable(row))
    }

    @Test
    fun `given a value with line breaks when copying the Vars tab then each value stays on one quoted line`() {
        val rows = listOf(ConsoleVarRow(node = "LITE_RT#1", key = "input", value = "one\ntwo"))

        assertEquals(
            "LITE_RT#1 input\n\"one\\ntwo\"",
            ConsoleCopyPayloads.forTab(ConsoleTab.Vars, logs, rows, traces),
        )
    }

    @Test
    fun `given a span when formatting then it renders time name duration and status`() {
        val span = ConsoleTraceSpan(
            name = "LITE_RT#501fab",
            durationMs = 1_240L,
            startedAt = "12:00:00.000",
            status = SpanStatus.Ok,
        )

        assertEquals("[12:00:00.000] LITE_RT#501fab — 1240 ms (Ok)", ConsoleCopyPayloads.span(span))
    }

    @Test
    fun `given each tab in turn when formatting for the tab then it copies that tab`() {
        // The defect itself: `Copy all` used to build its payload from the log
        // lines whatever tab was open.
        assertEquals(
            "[12:00:00.000] [NODE] ▶ INPUT\n[12:00:01.000] [TOOL] tool",
            ConsoleCopyPayloads.forTab(ConsoleTab.Logs, logs, vars, traces),
        )
        assertEquals(
            "LITE_RT#1 input\n\"prompt text\"\n\nLITE_RT#1 output\n\"answer text\"",
            ConsoleCopyPayloads.forTab(ConsoleTab.Vars, logs, vars, traces),
        )
        assertEquals(
            "[12:00:00.000] LITE_RT#1 — 12 ms (Ok)",
            ConsoleCopyPayloads.forTab(ConsoleTab.Traces, logs, vars, traces),
        )
    }
}
