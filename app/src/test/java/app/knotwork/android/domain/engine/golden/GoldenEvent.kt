package app.knotwork.android.domain.engine.golden

/**
 * One entry of a golden run trace: a single header line plus named multi-line text blocks.
 *
 * Every fake of the harness describes what the engine asked of it as one event, so the trace
 * is the ordered list of everything a run did with the world outside the engine — model calls,
 * tool calls, trace and run-record writes, chat writes, notifications, suspension states and
 * the terminal state. Clocks, durations and random ids never enter an event: that is what
 * makes two runs of the same scenario render byte-identically.
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
 */
internal class GoldenEventLog {

    private val entries = mutableListOf<GoldenEvent>()

    /** The events recorded so far, oldest first. */
    val events: List<GoldenEvent> get() = entries.toList()

    /**
     * Appends one event.
     *
     * @param header The event's one-line summary.
     * @param blocks Named multi-line payloads, rendered in the given order.
     */
    fun record(header: String, vararg blocks: Pair<String, String>) {
        entries += GoldenEvent(header, blocks.toList())
    }
}
