package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ConsoleEvent
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.design.components.console.ConsoleLevel
import app.knotwork.design.components.console.ConsoleSource
import app.knotwork.design.components.console.SpanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * Unit coverage for the pure-Kotlin mappers in [ChatHomeConsoleMapping].
 *
 * Tests are fast (no Robolectric, no Compose) and total: every
 * [ConsoleEventType] is covered so a future addition triggers a
 * compile-time exhaustiveness break in the production mapper.
 */
class ChatHomeConsoleMappingTest {

    @Test
    fun `given NodeExecution event when mapping then source NODE and level Trace`() {
        val event = ConsoleEvent(
            timestamp = 0L,
            type = ConsoleEventType.NodeExecution,
            message = "▶ LITE_RT",
        )

        val line = event.toConsoleLine()

        assertEquals(ConsoleSource.NODE, line.source)
        assertEquals(ConsoleLevel.Trace, line.level)
        assertEquals("▶ LITE_RT", line.text)
        assertTrue(
            "timestamp must match HH:mm:ss.SSS — was '${line.timestamp}'",
            TIMESTAMP_REGEX.matcher(line.timestamp).matches(),
        )
    }

    @Test
    fun `given ToolCall event when mapping then source TOOL and level Info`() {
        val line = ConsoleEvent(timestamp = 0L, type = ConsoleEventType.ToolCall, message = "calendar.create_event")
            .toConsoleLine()

        assertEquals(ConsoleSource.TOOL, line.source)
        assertEquals(ConsoleLevel.Info, line.level)
    }

    @Test
    fun `given MemoryAccess event when mapping then source MEMORY and level Info`() {
        val line = ConsoleEvent(timestamp = 0L, type = ConsoleEventType.MemoryAccess, message = "Memory: 3 chunks")
            .toConsoleLine()

        assertEquals(ConsoleSource.MEMORY, line.source)
        assertEquals(ConsoleLevel.Info, line.level)
    }

    @Test
    fun `given SystemMessage event when mapping then source RUNTIME and level Info`() {
        val line = ConsoleEvent(timestamp = 0L, type = ConsoleEventType.SystemMessage, message = "started")
            .toConsoleLine()

        assertEquals(ConsoleSource.RUNTIME, line.source)
        assertEquals(ConsoleLevel.Info, line.level)
    }

    @Test
    fun `given Error event when mapping then source RUNTIME and level Error`() {
        val line = ConsoleEvent(timestamp = 0L, type = ConsoleEventType.Error, message = "boom")
            .toConsoleLine()

        assertEquals(ConsoleSource.RUNTIME, line.source)
        assertEquals(ConsoleLevel.Error, line.level)
    }

    @Test
    fun `given StructuredOutputRepair event when mapping then source RUNTIME and level Warn`() {
        val line = ConsoleEvent(
            timestamp = 0L,
            type = ConsoleEventType.StructuredOutputRepair,
            message = "Output repair 1/2 for node Router",
        ).toConsoleLine()

        assertEquals(ConsoleSource.RUNTIME, line.source)
        assertEquals(ConsoleLevel.Warn, line.level)
    }

    @Test
    fun `given trace step when mapping then status Ok and duration preserved`() {
        val trace = AgentOrchestratorState.TraceStep(
            nodeName = "LITE_RT",
            outputText = "irrelevant",
            durationMs = 1840L,
            tokenCount = 64,
        )

        val span = traceStepToConsoleSpan(trace, startedAtMs = 0L)

        assertEquals("LITE_RT", span.name)
        assertEquals(1840L, span.durationMs)
        assertEquals(SpanStatus.Ok, span.status)
        assertTrue("startedAt must match HH:mm:ss.SSS", TIMESTAMP_REGEX.matcher(span.startedAt).matches())
    }

    @Test
    fun `given node IO when mapping then returns ordered input output rows with the values as they are`() {
        val io = AgentOrchestratorState.NodeIO(
            nodeId = "lite_rt_abcdef",
            nodeType = "LITE_RT",
            input = "user prompt",
            output = "model reply",
        )

        val rows = nodeIoToVarRows(io)

        assertEquals(2, rows.size)
        assertEquals(CONSOLE_VAR_KEY_INPUT, rows[0].key)
        assertEquals("user prompt", rows[0].value)
        assertEquals("LITE_RT#lite_r", rows[0].node)
        assertEquals(CONSOLE_VAR_KEY_OUTPUT, rows[1].key)
        assertEquals("model reply", rows[1].value)
    }

    @Test
    fun `given node IO with line breaks and quotes when mapping then the values keep them unescaped`() {
        val io = AgentOrchestratorState.NodeIO(
            nodeId = "x",
            nodeType = "T",
            input = "line1\nline2",
            output = "with \"quote\"",
        )

        val rows = nodeIoToVarRows(io)

        assertEquals("line1\nline2", rows[0].value)
        assertEquals("with \"quote\"", rows[1].value)
    }

    @Test
    fun `given overlapping baseline and live snapshot when merging then no duplicate seq survives`() {
        val baseline = listOf(
            ConsoleEvent(timestamp = 10L, type = ConsoleEventType.NodeExecution, message = "▶ INPUT", seq = 0),
            ConsoleEvent(timestamp = 11L, type = ConsoleEventType.NodeExecution, message = "✓ INPUT", seq = 1),
            ConsoleEvent(timestamp = 12L, type = ConsoleEventType.NodeExecution, message = "▶ LITE_RT", seq = 2),
        )
        // Live cumulative snapshot of the same run covers the baseline and adds the tail.
        val live = baseline + listOf(
            ConsoleEvent(timestamp = 20L, type = ConsoleEventType.NodeExecution, message = "✓ LITE_RT", seq = 3),
        )

        val merged = mergeConsoleEventsBySeq(baseline, live)

        assertEquals(listOf(0L, 1L, 2L, 3L), merged.map { it.seq })
        assertEquals("✓ LITE_RT", merged.last().message)
    }

    @Test
    fun `given live snapshot covering only the tail when merging then baseline head is preserved in order`() {
        // Post-resume shape: the restarted engine only re-emits from the
        // suspension point, so the live snapshot lacks the baseline head.
        val baseline = listOf(
            ConsoleEvent(timestamp = 10L, type = ConsoleEventType.NodeExecution, message = "▶ INPUT", seq = 0),
            ConsoleEvent(timestamp = 11L, type = ConsoleEventType.ToolCall, message = "tool.call", seq = 1),
        )
        val live = listOf(
            ConsoleEvent(timestamp = 30L, type = ConsoleEventType.SystemMessage, message = "resumed", seq = 2),
        )

        val merged = mergeConsoleEventsBySeq(baseline, live)

        assertEquals(listOf("▶ INPUT", "tool.call", "resumed"), merged.map { it.message })
    }

    @Test
    fun `given seq collision when merging then live event wins`() {
        val baseline = listOf(
            ConsoleEvent(timestamp = 10L, type = ConsoleEventType.NodeExecution, message = "stale", seq = 0),
        )
        val live = listOf(
            ConsoleEvent(timestamp = 10L, type = ConsoleEventType.NodeExecution, message = "fresh", seq = 0),
        )

        val merged = mergeConsoleEventsBySeq(baseline, live)

        assertEquals(1, merged.size)
        assertEquals("fresh", merged.single().message)
    }

    @Test
    fun `given persisted console entry when re-hydrating then event preserves seq type and message`() {
        val entry = RunTraceRecord.ConsoleEntry(
            runId = "run-1",
            sessionId = "s-1",
            seq = 7,
            timestamp = 123L,
            type = ConsoleEventType.Error,
            message = "LITE_RT: boom",
        )

        val event = consoleEntryToConsoleEvent(entry)

        assertEquals(7L, event.seq)
        assertEquals(123L, event.timestamp)
        assertEquals(ConsoleEventType.Error, event.type)
        assertEquals("LITE_RT: boom", event.message)
    }

    @Test
    fun `given persisted node IO record when re-hydrating then NodeIO and span match the live shapes`() {
        val record = RunTraceRecord.NodeIo(
            runId = "run-1",
            sessionId = "s-1",
            seq = 3,
            timestamp = 1_000L,
            nodeId = "lite_rt_abcdef",
            nodeType = "LITE_RT",
            inputText = "user prompt",
            outputText = "model reply",
            durationMs = 250L,
            tokenCount = 12,
        )

        val io = nodeIoRecordToNodeIo(record)
        assertEquals("lite_rt_abcdef", io.nodeId)
        assertEquals("LITE_RT", io.nodeType)
        assertEquals("user prompt", io.input)
        assertEquals("model reply", io.output)

        val span = nodeIoRecordToConsoleSpan(record)
        assertEquals("LITE_RT", span.name)
        assertEquals(250L, span.durationMs)
        assertEquals(SpanStatus.Ok, span.status)
        assertTrue(TIMESTAMP_REGEX.matcher(span.startedAt).matches())
    }

    private companion object {
        /** `HH:mm:ss.SSS` shape, locale-independent (digits + separators only). */
        val TIMESTAMP_REGEX: Pattern = Pattern.compile("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3}$")
    }
}
