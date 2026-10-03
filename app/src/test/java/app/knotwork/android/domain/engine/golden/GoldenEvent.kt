package app.knotwork.android.domain.engine.golden

/**
 * One entry of a golden run trace: a single header line plus named multi-line text blocks.
 *
 * Every fake of the harness describes what the engine asked of it as one event, so the trace
 * is the ordered list of everything a run did with the world outside the engine — model calls,
 * tool calls, trace and run-record writes, chat writes, metrics, journal entries,
 * notifications, orchestrator states and the terminal state. Clocks, durations and random ids
 * never enter an event: that is what makes two runs of the same scenario render
 * byte-identically.
 *
 * @property header The event's one-line summary (`visit golden node-2 LITE_RT #1`).
 * @property blocks Named free-text payloads rendered below the header (`prompt`, `answer`,
 *   `input`, …), in declaration order.
 */
internal data class GoldenEvent(val header: String, val blocks: List<Pair<String, String>> = emptyList())

/**
 * Append-only, ordered log shared by every fake of one harness run.
 *
 * Execution under `runTest` is single-threaded, so the order of `record` calls is the order in
 * which the engine reached its collaborators — the property the golden comparison pins.
 *
 * Three services keep the log both complete and stable:
 * - **Aliases.** A random id (an approval's request id, a clarification's id) is rendered as
 *   `#1`, `#2`, … in order of first appearance, so the trace still shows which surfaces carry
 *   *the same* request without carrying a random value.
 * - **Streaming.** Consecutive `Thinking` / `Answering` states collapse into one event that
 *   counts them and keeps the final text: where a stream reaches matters, every partial does
 *   not.
 * - **Violations.** A fake that is used in a way the scenario does not allow calls
 *   [violation], which throws an [AssertionError]. The engine catches `Exception` around every
 *   node on purpose, so an `Exception` from a fake would become an ordinary run error and be
 *   recorded as golden output; an `Error` is not caught there, and the run asserts the list is
 *   empty in any case.
 */
internal class GoldenEventLog {

    private val entries = mutableListOf<GoldenEvent>()
    private val aliases = linkedMapOf<String, String>()
    private val violationMessages = mutableListOf<String>()
    private var stream: StreamRun? = null

    /** The events recorded so far, oldest first. */
    val events: List<GoldenEvent>
        get() {
            flushStream()
            return entries.toList()
        }

    /** Every harness violation raised during the run, oldest first. */
    val violations: List<String> get() = violationMessages.toList()

    /**
     * Appends one event.
     *
     * @param header The event's one-line summary.
     * @param blocks Named multi-line payloads, rendered in the given order.
     */
    fun record(header: String, vararg blocks: Pair<String, String>) {
        flushStream()
        entries += GoldenEvent(header, blocks.toList())
    }

    /**
     * The stable alias of a random id.
     *
     * @param id The id, or `null`.
     * @return `#n` for the n-th distinct id seen in this run, or `none`.
     */
    fun alias(id: String?): String = if (id == null) "none" else aliases.getOrPut(id) { "#${aliases.size + 1}" }

    /**
     * Accumulates one streaming state into the current stream run.
     *
     * @param kind The state kind (`Thinking`, `Answering`).
     * @param text The state's partial text.
     */
    fun stream(kind: String, text: String) {
        val current = stream ?: StreamRun().also { stream = it }
        current.counts[kind] = (current.counts[kind] ?: 0) + 1
        current.lastText = text
    }

    /**
     * Records a harness violation and fails the run.
     *
     * @param message What the scenario did not allow.
     * @return Never returns.
     */
    fun violation(message: String): Nothing {
        violationMessages += message
        throw AssertionError(message)
    }

    private fun flushStream() {
        val current = stream ?: return
        stream = null
        val counts = current.counts.entries.joinToString(" ") { (kind, count) -> "${kind.lowercase()}=$count" }
        entries += GoldenEvent("state Streaming $counts", listOf("text" to current.lastText))
    }

    private class StreamRun {
        val counts = linkedMapOf<String, Int>()
        var lastText = ""
    }
}
