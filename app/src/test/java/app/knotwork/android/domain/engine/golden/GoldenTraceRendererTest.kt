package app.knotwork.android.domain.engine.golden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the parts of the golden-trace format a reviewer relies on when reading a diff: blocks,
 * trailing whitespace, chunking, the failure message, and how a script resolves an answer.
 */
internal class GoldenTraceRendererTest {

    @Test
    fun `given text with an empty line and trailing space when rendered then both stay visible`() {
        val lines = GoldenTraceRenderer.payloadLines("first\n\nAGENT: ")

        assertEquals(listOf("| first", "|", "| AGENT: ${GoldenTraceRenderer.TRAILING_WHITESPACE_MARK}"), lines)
    }

    @Test
    fun `given empty text when rendered then it is distinguishable from an empty line`() {
        assertEquals(listOf("(empty)"), GoldenTraceRenderer.payloadLines(""))
        assertEquals(listOf("|", "|"), GoldenTraceRenderer.payloadLines("\n"))
    }

    @Test
    fun `given an answer when chunked then the chunks rebuild it and split at words`() {
        val chunks = GoldenModel.chunks("Two words,  then more.")

        assertEquals("Two words,  then more.", chunks.joinToString(""))
        assertEquals(listOf("Two ", "words,  ", "then ", "more."), chunks)
    }

    @Test
    fun `given a mismatch when described then the first differing line and the diff command are named`() {
        val message = GoldenTraceDiff.describe("a\nb\nc\n", "a\nB\nc\n", "golden.trace", "actual.trace")

        assertTrue(message, message.contains("at line 2"))
        assertTrue(message, message.contains(">     2  b"))
        assertTrue(message, message.contains(">     2  B"))
        assertTrue(message, message.contains("git diff --no-index golden.trace actual.trace"))
    }

    @Test
    fun `given an actual trace shorter than the golden when described then the missing line is reported`() {
        val message = GoldenTraceDiff.describe("a\nb\n", "a", "g", "x")

        assertTrue(message, message.contains("at line 2"))
    }

    @Test
    fun `given repeated payload-free events when coalesced then they collapse but events with payloads do not`() {
        val ping = GoldenEvent("network.outbound")
        val withPayload = GoldenEvent("model local", listOf("prompt" to "p"))

        val grouped = GoldenTraceRenderer.coalesce(listOf(ping, ping, ping, withPayload, withPayload, ping))

        assertEquals(listOf(ping to 3, withPayload to 1, withPayload to 1, ping to 1), grouped)
    }

    @Test
    fun `given random ids when aliased then each gets a stable alias in order of first appearance`() {
        val log = GoldenEventLog()

        assertEquals("#1", log.alias("f3c1"))
        assertEquals("#2", log.alias("09ab"))
        assertEquals("#1", log.alias("f3c1"))
        assertEquals("none", log.alias(null))
    }

    @Test
    fun `given streaming states when another event is recorded then they collapse into one event first`() {
        val log = GoldenEventLog()

        log.stream("Thinking", "Hel")
        log.stream("Answering", "Hello")
        log.stream("Answering", "Hello there")
        log.record("end Completed")

        assertEquals(
            listOf(
                GoldenEvent("state Streaming thinking=1 answering=2", listOf("text" to "Hello there")),
                GoldenEvent("end Completed"),
            ),
            log.events,
        )
    }

    @Test
    fun `given a violation when raised then it is an Error the engine's Exception handlers cannot swallow`() {
        val log = GoldenEventLog()

        val thrown = runCatching { log.violation("unscripted call") }.exceptionOrNull()

        assertTrue(thrown is AssertionError)
        assertTrue(thrown !is Exception)
        assertEquals(listOf("unscripted call"), log.violations)
    }

    @Test
    fun `given a script when a visit makes more calls than scripted then the last answer repeats`() {
        val script = GoldenScript.of { onCalls("router", "first", "second") }

        assertEquals("first", script.answerFor("root", "root", "router", visit = 1, call = 1))
        assertEquals("second", script.answerFor("root", "root", "router", visit = 1, call = 2))
        assertEquals("second", script.answerFor("root", "root", "router", visit = 1, call = 5))
    }

    @Test
    fun `given a root-scoped script when a sub-pipeline has a node of the same id then it is not answered`() {
        val script = GoldenScript.of {
            on("node-2", "root answer")
            on("node-2", "child answer", pipelineId = "child")
        }

        assertEquals("root answer", script.answerFor("root", "root", "node-2", visit = 1, call = 1))
        assertEquals("child answer", script.answerFor("root", "child", "node-2", visit = 1, call = 1))
        assertEquals(null, script.answerFor("root", "other", "node-2", visit = 1, call = 1))
        assertEquals(null, script.answerFor("root", "root", "node-2", visit = 2, call = 1))
    }
}
