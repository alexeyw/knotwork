package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.NodeExecutionResult
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.repositories.RunTraceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [RunConsole].
 *
 * The property everything else rests on is the single numbering: console lines
 * and trace-only records draw from one sequence, a resumed run starts where the
 * interrupted one stopped, and a run that is not persisted takes numbers only for
 * what it shows. The console deduplicates the replay/live seam by that number.
 */
class RunConsoleTest {

    private val appended = mutableListOf<RunTraceRecord>()
    private val calls = mutableListOf<String>()
    private val trace: RunTraceRepository = mockk {
        coEvery { append(any()) } answers {
            appended += firstArg<RunTraceRecord>()
            calls += "append"
        }
        coEvery { flush() } answers { calls += "flush" }
    }
    private val emitted = mutableListOf<AgentOrchestratorState>()
    private val collector = FlowCollector<AgentOrchestratorState> { emitted += it }

    private fun console(runId: String? = "run-1", depth: Int = 0, firstSeq: Long = 0L) = RunConsole(
        collector = collector,
        runTraceRepository = trace,
        sessionId = "session-1",
        runId = runId,
        depth = depth,
        pipelineName = "Translator",
        firstSeq = firstSeq,
    )

    private fun node(type: NodeType) = NodeModel(id = "n-${type.name}", type = type, x = 0f, y = 0f)

    private fun chunk(id: Long) = MemoryChunk(id = id, text = "fact $id", embedding = floatArrayOf(), timestamp = 0L)

    @Test
    fun `given lines and records when written then they share one numbering from the first seq`() = runTest {
        val console = console(firstSeq = 7L)

        console.push(ConsoleEventType.NodeExecution, "▶ LITE_RT")
        console.recordMemorySnapshot(listOf(chunk(1)))
        console.recordNodeIo(node(NodeType.LITE_RT), "in", "out", durationMs = 5L, result = null)
        console.push(ConsoleEventType.NodeExecution, "✓ LITE_RT in 5ms")

        assertEquals(listOf(7L, 8L, 9L, 10L), appended.map { it.seq })
        val shown = (emitted.last() as AgentOrchestratorState.ConsoleLog).events
        assertEquals(listOf(7L, 10L), shown.map { it.seq })
    }

    @Test
    fun `given a pushed line then the screen gets every line so far and the trace gets this one`() = runTest {
        val console = console()

        console.push(ConsoleEventType.NodeExecution, "▶ INPUT")
        console.push(ConsoleEventType.SystemMessage, "Image input: 1×1, 1 KB")

        val snapshots = emitted.filterIsInstance<AgentOrchestratorState.ConsoleLog>()
        assertEquals(listOf(1, 2), snapshots.map { it.events.size })
        assertEquals("run-1", snapshots.last().runId)
        val entry = appended.last() as RunTraceRecord.ConsoleEntry
        assertEquals("run-1", entry.runId)
        assertEquals("session-1", entry.sessionId)
        assertEquals(ConsoleEventType.SystemMessage, entry.type)
        assertEquals("Image input: 1×1, 1 KB", entry.message)
        assertEquals(snapshots.last().events.last().timestamp, entry.timestamp)
    }

    @Test
    fun `given a line quoting a credential then neither the screen nor the trace keeps it`() = runTest {
        console().push(ConsoleEventType.Error, "CLOUD: GET https://h/x?api_key=abc failed: Bearer sk-1")

        val expected = "CLOUD: GET https://h/x?api_key=*** failed: Bearer ***"
        assertEquals(expected, (emitted.last() as AgentOrchestratorState.ConsoleLog).events.single().message)
        assertEquals(expected, (appended.single() as RunTraceRecord.ConsoleEntry).message)
    }

    @Test
    fun `given a nested run then each line is prefixed with the pipeline name and stamped with the depth`() = runTest {
        val console = console(depth = 2)

        console.push(ConsoleEventType.NodeExecution, "▶ INPUT")
        console.recordNodeIo(node(NodeType.LITE_RT), "in", "out", durationMs = 1L, result = null)

        val event = (emitted.last() as AgentOrchestratorState.ConsoleLog).events.single()
        assertEquals("[Translator] ▶ INPUT", event.message)
        assertEquals(2, event.depth)
        assertEquals(2, (appended[0] as RunTraceRecord.ConsoleEntry).depth)
        assertEquals(2, (appended[1] as RunTraceRecord.NodeIo).depth)
    }

    @Test
    fun `given a run that is not persisted then lines are shown and numbered but nothing is recorded`() = runTest {
        val console = console(runId = null)

        console.push(ConsoleEventType.NodeExecution, "▶ TOOL")
        console.recordMemorySnapshot(listOf(chunk(1)))
        console.recordNodeIo(node(NodeType.TOOL), "in", "out", durationMs = 1L, result = null)
        console.push(ConsoleEventType.NodeExecution, "✓ TOOL in 1ms")

        assertTrue(appended.isEmpty())
        assertTrue("a TOOL record that was never written is not flushed", calls.isEmpty())
        // The skipped records took no number: the two lines stay consecutive.
        val shown = (emitted.last() as AgentOrchestratorState.ConsoleLog).events
        assertEquals(listOf(0L, 1L), shown.map { it.seq })
        assertEquals(null, (emitted.last() as AgentOrchestratorState.ConsoleLog).runId)
    }

    @Test
    fun `given a node record then it carries the input, output and the result's verdicts`() = runTest {
        val result = NodeExecutionResult(
            outputText = "ignored — the caller passes the output",
            conditionResult = true,
            routingKey = "billing",
            tokenCount = 42,
            resolvedToolName = "search_tool",
        )

        console().recordNodeIo(node(NodeType.INTENT_ROUTER), "the input", "the output", durationMs = 30L, result)

        val record = appended.single() as RunTraceRecord.NodeIo
        assertEquals("n-INTENT_ROUTER", record.nodeId)
        assertEquals("INTENT_ROUTER", record.nodeType)
        assertEquals("the input", record.inputText)
        assertEquals("the output", record.outputText)
        assertEquals(30L, record.durationMs)
        assertEquals(42, record.tokenCount)
        assertEquals(true, record.conditionResult)
        assertEquals("billing", record.routingKey)
        assertEquals("search_tool", record.resolvedToolName)
    }

    @Test
    fun `given the memory a run retrieved then the snapshot carries the chunks`() = runTest {
        console().recordMemorySnapshot(listOf(chunk(1), chunk(2)))

        val record = appended.single() as RunTraceRecord.MemorySnapshot
        assertEquals(listOf(1L, 2L), record.entries.map { it.id })
        assertEquals("run-1", record.runId)
    }

    @Test
    fun `given a TOOL or CLOUD node then its record is flushed at once, any other waits for the batch`() = runTest {
        val console = console()

        console.recordNodeIo(node(NodeType.LITE_RT), "in", "out", durationMs = 1L, result = null)
        console.recordNodeIo(node(NodeType.TOOL), "in", "out", durationMs = 1L, result = null)
        console.recordNodeIo(node(NodeType.CLOUD), "in", "out", durationMs = 1L, result = null)

        assertEquals(listOf("append", "append", "flush", "append", "flush"), calls)
    }

    @Test
    fun `given flush then the trace is flushed`() = runTest {
        console().flush()

        coVerify(exactly = 1) { trace.flush() }
    }
}
