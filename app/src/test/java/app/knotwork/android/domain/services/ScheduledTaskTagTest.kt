package app.knotwork.android.domain.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [ScheduledTaskTag]: the label a scheduled task carries is the only
 * thing the task monitor can say about it (a queued task's input data is not
 * readable), so it has to survive a round trip through a plain tag string and
 * degrade to `null` — never to a wrong label — on anything it does not recognise.
 */
class ScheduledTaskTagTest {

    @Test
    fun `given a periodic task when encoded then it round-trips with its prompt id`() {
        val tag = ScheduledTaskTag.encode(
            kind = ScheduledTaskKind.PERIODIC,
            intervalHours = 6,
            sessionId = "session-1",
            promptId = "periodic-abc",
        )

        assertEquals(
            ScheduledTaskLabel(
                kind = ScheduledTaskKind.PERIODIC,
                intervalHours = 6,
                sessionId = "session-1",
                promptId = "periodic-abc",
            ),
            ScheduledTaskTag.parse(setOf(tag)),
        )
    }

    @Test
    fun `given any task when encoded then the tag carries no prompt text`() {
        // The runtime stores tags in the clear; the label names the prompt by id
        // and the monitor looks the text up in the encrypted store.
        val tag = ScheduledTaskTag.encode(ScheduledTaskKind.ONE_TIME, 0, null, promptId = "7f0e")

        assertEquals("kst2|ONE_TIME|0||7f0e", tag)
    }

    @Test
    fun `given a one-time task without a session when encoded then the session reads back as null`() {
        val tag = ScheduledTaskTag.encode(
            ScheduledTaskKind.ONE_TIME,
            intervalHours = 0,
            sessionId = null,
            promptId = "x",
        )

        val label = ScheduledTaskTag.parse(setOf(tag))

        assertEquals(ScheduledTaskKind.ONE_TIME, label?.kind)
        assertEquals(0L, label?.intervalHours)
        // An empty field must not become an empty-string session id that then
        // fails to resolve against any chat.
        assertNull(label?.sessionId)
    }

    @Test
    fun `given a label written by an earlier release when parsed then its own preview is kept`() {
        // A task scheduled before prompts left the runtime is still queued after
        // the update, with the preview in its tag and no prompt id.
        val label = ScheduledTaskTag.parse(setOf("kst1|PERIODIC|6|s-1|summarise a|b|c"))

        assertEquals(
            ScheduledTaskLabel(
                kind = ScheduledTaskKind.PERIODIC,
                intervalHours = 6,
                sessionId = "s-1",
                promptId = null,
                promptPreview = "summarise a|b|c",
            ),
            label,
        )
    }

    @Test
    fun `given a multi-line prompt when previewed then the preview is a single line`() {
        assertEquals("first second third", ScheduledTaskTag.preview("  first\n\tsecond   third  "))
    }

    @Test
    fun `given a long prompt when previewed then the preview is truncated and marked`() {
        val preview = ScheduledTaskTag.preview("a".repeat(500))

        // A label shows enough to recognise the task, not a copy of the instruction.
        assertEquals(ScheduledTaskTag.PROMPT_PREVIEW_MAX_CHARS + 1, preview.length)
        assertTrue(preview.endsWith("…"))
    }

    @Test
    fun `given only unrelated tags when parsed then there is no label`() {
        // Every work item also carries the worker class name and the marker.
        val label = ScheduledTaskTag.parse(
            setOf(ScheduledTaskTag.MARKER, WORKER_CLASS_TAG),
        )

        assertNull(label)
    }

    @Test
    fun `given a truncated or future-format tag when parsed then it degrades to no label`() {
        assertNull(ScheduledTaskTag.parse(setOf("kst1|ONE_TIME|0")))
        assertNull(ScheduledTaskTag.parse(setOf("kst2|ONE_TIME|0")))
        assertNull(ScheduledTaskTag.parse(setOf("kst9|ONE_TIME|0|s|id")))
        assertNull(ScheduledTaskTag.parse(setOf("kst1|TELEPORT|0|s|prompt")))
        assertNull(ScheduledTaskTag.parse(setOf("kst1|ONE_TIME|soon|s|prompt")))
        assertNull(ScheduledTaskTag.parse(emptySet()))
    }

    private companion object {
        /**
         * Stand-in for the tag the background runtime adds by itself (the worker
         * class name). Only its presence matters here: an unrelated tag must not
         * be mistaken for a label.
         */
        const val WORKER_CLASS_TAG = "AgentWorker"
    }
}
