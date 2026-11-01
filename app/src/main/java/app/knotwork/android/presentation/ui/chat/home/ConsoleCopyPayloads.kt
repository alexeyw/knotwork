package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.design.components.console.ConsoleFilter
import app.knotwork.design.components.console.ConsoleLine
import app.knotwork.design.components.console.ConsoleTab
import app.knotwork.design.components.console.ConsoleTraceSpan
import app.knotwork.design.components.console.ConsoleVarRow
import org.json.JSONObject

/**
 * Plain-text clipboard payloads for the agent console.
 *
 * Pure string formatting with no state of its own, which is why it is not a
 * member of [ChatHomeConsoleDelegate]: the delegate owns the console's live
 * state and its one-shot snackbar signals, and it was the `TooManyFunctions`
 * gate refusing three more methods that made the misplacement explicit rather
 * than merely tidy. Splitting it out also lets these formats be pinned by tests
 * that need no coroutine scope and no delegate.
 *
 * The clipboard write itself stays in the Composable layer, which is where
 * `LocalClipboardManager` lives; this object only decides what text to write.
 */
object ConsoleCopyPayloads {

    /**
     * Renders a single [ConsoleLine] as the plain-text clipboard payload
     * inserted by the chat screen's `onConsoleCopyLine`. Format: `[timestamp] [source] text`.
     */
    fun line(line: ConsoleLine): String = "[${line.timestamp}] [${line.source.name}] ${line.text}"

    /**
     * Renders the supplied list of [ConsoleLine]s as the multi-line
     * clipboard payload for the Logs tab. The caller is expected to apply the
     * current [ConsoleFilter] / search query before passing the list in — the
     * chat-home `Copy all` action only copies what the user is actively
     * looking at.
     */
    fun allLines(lines: List<ConsoleLine>): String = lines.joinToString(separator = "\n") { line(it) }

    /**
     * The clipboard payload of the chat screen's `onConsoleCopyVar`: the value
     * alone, exactly as the node had it — no `node key` header, no quotes, its
     * own line breaks.
     *
     * The value is copied **whole**. A row shows only a preview of it, and a
     * run's assembled prompt is routinely thousands of characters; Copy value
     * is how all of it leaves the tab, so it is data to paste, not a label.
     */
    fun variable(row: ConsoleVarRow): String = row.value

    /**
     * One [ConsoleVarRow] as a block of the Vars tab's `Copy all`: `node key`
     * on the first line, then the value as one quoted JSON string. The quoting
     * keeps one value to one line, so blocks stay apart however many line
     * breaks a value has.
     */
    private fun variableBlock(row: ConsoleVarRow): String = "${row.node} ${row.key}\n${JSONObject.quote(row.value)}"

    /**
     * Renders one [ConsoleTraceSpan] as the clipboard payload inserted by
     * the chat screen's `onConsoleCopySpan`. Format: `[startedAt] name — durationMs ms (status)`.
     */
    fun span(span: ConsoleTraceSpan): String =
        "[${span.startedAt}] ${span.name} — ${span.durationMs} ms (${span.status.name})"

    /**
     * Builds the payload for the header `Copy all` action on the tab the user
     * is actually looking at.
     *
     * The decision lives here rather than in the Composable because it is the
     * part that was wrong: the action copied log lines whatever tab was open,
     * so on Vars and Traces the button silently copied something other than
     * what was on screen. Keeping it as a pure function makes that behaviour
     * something a unit test can pin.
     *
     * @param tab The tab currently shown.
     * @param visibleLogs Logs after the caller has applied filter and search —
     *   the same rows the Logs tab renders.
     * @param vars Rows shown on the Vars tab.
     * @param traces Spans shown on the Traces tab.
     * @return The plain-text payload for the system clipboard.
     */
    fun forTab(
        tab: ConsoleTab,
        visibleLogs: List<ConsoleLine>,
        vars: List<ConsoleVarRow>,
        traces: List<ConsoleTraceSpan>,
    ): String = when (tab) {
        ConsoleTab.Logs -> allLines(visibleLogs)
        ConsoleTab.Vars -> vars.joinToString(separator = "\n\n") { variableBlock(it) }
        ConsoleTab.Traces -> traces.joinToString(separator = "\n") { span(it) }
    }
}
