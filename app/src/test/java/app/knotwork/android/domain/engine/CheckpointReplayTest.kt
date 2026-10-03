package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.ResumeContext
import app.knotwork.android.domain.models.RunTraceRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [CheckpointReplay].
 *
 * A replay that consumed a record for the wrong node, or skipped one, would
 * re-derive a resumed run's branches from someone else's output — so the cursor
 * is pinned here record by record: what advances it, what does not, and what
 * stops the resume.
 */
class CheckpointReplayTest {

    private fun node(id: String, type: NodeType = NodeType.LITE_RT) = NodeModel(id = id, type = type, x = 0f, y = 0f)

    private fun record(nodeId: String, seq: Long) = RunTraceRecord.NodeIo(
        runId = "run-1",
        sessionId = "s",
        seq = seq,
        timestamp = 0L,
        nodeId = nodeId,
        nodeType = "LITE_RT",
        inputText = "in $nodeId",
        outputText = "out $nodeId",
        durationMs = 40L + seq,
        tokenCount = 7,
        conditionResult = true,
        routingKey = "billing",
        resolvedToolName = "search_tool",
    )

    private fun resumed(vararg nodeIds: String) = CheckpointReplay(
        ResumeContext(
            records = nodeIds.mapIndexed { i, id ->
                record(id, i.toLong())
            },
            memorySnapshot = null,
            nextSeq = 9L,
        ),
    )

    @Test
    fun `given a fresh run then every node runs live`() {
        val replay = CheckpointReplay(null)

        assertFalse(replay.resuming)
        assertEquals(ReplayStep.Live, replay.take(node("a")))
    }

    @Test
    fun `given recorded nodes then each is replayed from its record in order, then the walk goes live`() {
        val replay = resumed("a", "b")

        val a = replay.take(node("a")) as ReplayStep.Replayed
        val b = replay.take(node("b")) as ReplayStep.Replayed

        assertTrue(replay.resuming)
        assertEquals("out a", a.result.outputText)
        assertEquals(true, a.result.conditionResult)
        assertEquals("billing", a.result.routingKey)
        assertEquals(7, a.result.tokenCount)
        assertEquals("search_tool", a.result.resolvedToolName)
        assertEquals("in a", a.input)
        assertEquals(40L, a.durationMs)
        assertEquals("out b", b.result.outputText)
        assertEquals(ReplayStep.Live, replay.take(node("c")))
    }

    @Test
    fun `given INPUT or OUTPUT mid-replay then they run live and keep the next record for the next node`() {
        val replay = resumed("a")

        assertEquals(ReplayStep.Live, replay.take(node("in", NodeType.INPUT)))
        assertEquals(ReplayStep.Live, replay.take(node("out", NodeType.OUTPUT)))
        assertEquals("out a", (replay.take(node("a")) as ReplayStep.Replayed).result.outputText)
    }

    @Test
    fun `given a record for another node then the resume has diverged and nothing is consumed`() {
        val replay = resumed("a")

        assertEquals(ReplayStep.Diverged, replay.take(node("x")))
        assertEquals("out a", (replay.take(node("a")) as ReplayStep.Replayed).result.outputText)
    }
}
