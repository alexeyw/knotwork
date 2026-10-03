package app.knotwork.android.domain.engine

import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [QueueCursor].
 *
 * Each case drives one queue loop the way the walk does — enter with the
 * processor's list, then advance with each item's result — and checks the input
 * every item is handed and where the walk leaves. Order matters: the items run
 * front to back, and each one sees the results of those before it.
 */
class QueueCursorTest {

    private val instruction = DefaultPrompts.QueueProcessor.SUBTASK_INSTRUCTION

    private fun node(id: String, type: NodeType, stopOnError: Boolean? = null) =
        NodeModel(id = id, type = type, x = 0f, y = 0f, stopOnError = stopOnError)

    private fun edge(from: String, to: String, label: String? = null) =
        ConnectionModel(id = "$from->$to", sourceNodeId = from, targetNodeId = to, label = label)

    /** queue --Item--> work --> queue; queue --Done--> summary --> out. */
    private fun loopGraph(queue: NodeModel) = PipelineGraph(
        id = "g",
        name = "G",
        nodes = listOf(
            queue,
            node("work", NodeType.LITE_RT),
            node("summary", NodeType.SUMMARY),
            node("out", NodeType.OUTPUT),
        ),
        connections = listOf(
            edge("queue", "work", "Item"),
            edge("work", "queue"),
            edge("queue", "summary", "Done"),
            edge("summary", "out"),
        ),
    )

    private val queue = node("queue", NodeType.QUEUE_PROCESSOR)

    @Test
    fun `given a list when entered then the first item goes down the Item edge alone`() {
        val cursor = QueueCursor(loopGraph(queue))

        val entry = cursor.enter(queue, """["first", "second", "third"]""") as QueueEntry.FirstItem

        assertEquals("work", entry.node?.id)
        assertEquals("$instruction\n\nCURRENT SUBTASK TO EXECUTE:\nfirst", entry.inputText)
        // Three items × the one-node item path, then summary + output after Done.
        assertEquals(3 * 1 + 2, entry.remainingSteps)
        assertTrue(cursor.isActive)
    }

    @Test
    fun `given each result when advanced then the next item carries every result before it, in order`() {
        val cursor = QueueCursor(loopGraph(queue))
        cursor.enter(queue, """["first", "second", "third"]""")

        val second = cursor.advance("r1")
        val third = cursor.advance("r2")

        assertEquals("work", second.node?.id)
        assertEquals(
            "PREVIOUS RESULTS CONTEXT:\nResult of Subtask 1:\nr1\n\n---\n\n$instruction" +
                "\n\nCURRENT SUBTASK TO EXECUTE:\nsecond",
            second.inputText,
        )
        assertEquals(
            "PREVIOUS RESULTS CONTEXT:\nResult of Subtask 1:\nr1\n\nResult of Subtask 2:\nr2\n\n---\n\n" +
                "$instruction\n\nCURRENT SUBTASK TO EXECUTE:\nthird",
            third.inputText,
        )
    }

    @Test
    fun `given the last result when advanced then the walk leaves by Done with a summary`() {
        val cursor = QueueCursor(loopGraph(queue))
        cursor.enter(queue, """["first", "second"]""")
        cursor.advance("r1")

        val done = cursor.advance("r2")

        assertEquals("summary", done.node?.id)
        assertEquals("Queue execution completed.\nResults:\n1. r1\n2. r2", done.inputText)
        assertFalse(cursor.isActive)
    }

    @Test
    fun `given a Markdown list or plain text then items are read from it`() {
        val markdown = QueueCursor(loopGraph(queue))
        val entry = markdown.enter(queue, "Plan:\n1. alpha\n- beta\n* gamma") as QueueEntry.FirstItem
        assertTrue(entry.inputText.endsWith("CURRENT SUBTASK TO EXECUTE:\nalpha"))
        assertEquals(3 * 1 + 2, entry.remainingSteps)

        val plain = QueueCursor(loopGraph(queue))
        val single = plain.enter(queue, "just one task") as QueueEntry.FirstItem
        assertTrue(single.inputText.endsWith("CURRENT SUBTASK TO EXECUTE:\njust one task"))
        assertEquals(1 * 1 + 2, single.remainingSteps)
    }

    @Test
    fun `given a JSON list inside a fenced block then the array is read, not the prose`() {
        val cursor = QueueCursor(loopGraph(queue))

        val entry = cursor.enter(queue, "Here you go:\n```json\n[\"a\", \"b\"]\n```") as QueueEntry.FirstItem

        assertTrue(entry.inputText.endsWith("CURRENT SUBTASK TO EXECUTE:\na"))
        assertEquals(2 * 1 + 2, entry.remainingSteps)
    }

    @Test
    fun `given a queue with no outgoing edge then the loop is skipped and nothing is active`() {
        val lonely = PipelineGraph(id = "g", name = "G", nodes = listOf(queue))
        val cursor = QueueCursor(lonely)

        val entry = cursor.enter(queue, """["a"]""")

        assertEquals(QueueEntry.Skipped(null), entry)
        assertFalse(cursor.isActive)
    }

    @Test
    fun `given a queue without a labelled Item edge then its first edge carries the items`() {
        val g = PipelineGraph(
            id = "g",
            name = "G",
            nodes = listOf(queue, node("work", NodeType.LITE_RT)),
            connections = listOf(edge("queue", "work")),
        )

        val entry = QueueCursor(g).enter(queue, """["a"]""") as QueueEntry.FirstItem

        assertEquals("work", entry.node?.id)
        // No Done edge: nothing is counted after the loop, and leaving it ends the branch.
        assertEquals(1, entry.remainingSteps)
    }

    @Test
    fun `given a new list while a loop runs then the new loop starts with no earlier results`() {
        val cursor = QueueCursor(loopGraph(queue))
        cursor.enter(queue, """["old", "older"]""")
        // The old loop has a result of its own by the time the new list arrives.
        cursor.advance("o1")

        cursor.enter(queue, """["new", "next"]""")
        val next = cursor.advance("n1")

        assertEquals(
            "PREVIOUS RESULTS CONTEXT:\nResult of Subtask 1:\nn1\n\n---\n\n$instruction\n\nCURRENT SUBTASK TO EXECUTE:\nnext",
            next.inputText,
        )
    }

    @Test
    fun `given stopOnError off then a failed item becomes its result and the loop goes on`() {
        val forgiving = node("queue", NodeType.QUEUE_PROCESSOR, stopOnError = false)
        val cursor = QueueCursor(loopGraph(forgiving))
        cursor.enter(forgiving, """["first", "second"]""")

        assertTrue(cursor.continuesAfterFailure())
        val next = cursor.advancePastFailure("boom")

        assertTrue(next.inputText.startsWith("PREVIOUS RESULTS CONTEXT:\nResult of Subtask 1:\nSubtask failed: boom"))
        assertTrue(next.inputText.endsWith("CURRENT SUBTASK TO EXECUTE:\nsecond"))
    }

    @Test
    fun `given stopOnError on, unset, or no queue running then a failure is not survived`() {
        for (stopOnError in listOf(true, null)) {
            val strict = node("queue", NodeType.QUEUE_PROCESSOR, stopOnError = stopOnError)
            val cursor = QueueCursor(loopGraph(strict))
            cursor.enter(strict, """["a"]""")
            assertFalse("stopOnError=$stopOnError", cursor.continuesAfterFailure())
        }
        val forgiving = node("queue", NodeType.QUEUE_PROCESSOR, stopOnError = false)
        assertFalse("outside a loop", QueueCursor(loopGraph(forgiving)).continuesAfterFailure())
    }

    @Test
    fun `given advance outside a loop then it fails loudly instead of inventing a queue`() {
        val cursor = QueueCursor(loopGraph(queue))

        assertThrows(IllegalStateException::class.java) { cursor.advance("r") }
    }
}
