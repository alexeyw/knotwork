package app.knotwork.android.domain.text

/**
 * Ceilings for the request an approval shows next to the tool call: the text a
 * run was asked to do, and the name of the trigger that asked it. The rule
 * that applies them is [toDisplaySafeExcerpt].
 *
 * The request is untrusted text — a shared text, another app's request, an
 * instruction the agent composed through `schedule_task` — so it is shown only
 * as one display-safe line of bounded length, never as markup.
 */
object ApprovalRequestText {

    /**
     * Longest request the approval card shows, ellipsis included.
     *
     * The card collapses the request to a few lines and expands it on demand;
     * this ceiling bounds the expanded form, so a prompt of pages cannot push
     * the decision buttons a screen away.
     */
    const val MAX_CARD_LENGTH: Int = 400

    /**
     * Longest request the approval notification shows, ellipsis included.
     *
     * The request is the last line of the notification's expanded text and is
     * never counted toward the argument budget, but every text of a
     * notification shares the platform's 1 024-character cap; two short lines
     * leave the call the room it needs.
     */
    const val MAX_NOTIFICATION_LENGTH: Int = 120

    /**
     * Longest trigger name the approval's source label quotes, ellipsis
     * included: the name sits inside a one-line label, not on a line of its own.
     */
    const val MAX_TRIGGER_NAME_LENGTH: Int = 40
}

/**
 * Makes [this] display-safe with [toDisplaySafe] — line breaks, control and
 * bidi-override characters become spaces, whitespace runs collapse — and clamps
 * it to [maxLength] characters **on a word**: the cut backs off to the last
 * space before the limit, so the excerpt does not end in half a word.
 *
 * A text with no space in the second half of the kept part (one long word, or
 * a script written without spaces) is cut at the limit instead — backing off
 * further would throw away most of what fits. The cut never splits a surrogate
 * pair, and [ellipsis] counts toward [maxLength].
 *
 * @param maxLength Longest result, ellipsis included.
 * @param ellipsis Appended when the text is cut. Must be shorter than [maxLength].
 * @return the single-line, bounded text; empty when the input is blank.
 */
fun String.toDisplaySafeExcerpt(maxLength: Int, ellipsis: String = "…"): String {
    require(maxLength > ellipsis.length) { "maxLength must leave room for at least one character" }
    val flattened = toDisplaySafe(maxLength = Int.MAX_VALUE)
    if (flattened.length <= maxLength) return flattened
    var cut = maxLength - ellipsis.length
    if (flattened[cut - 1].isHighSurrogate()) cut--
    val lastSpace = flattened.lastIndexOf(' ', startIndex = cut)
    val end = if (lastSpace > cut / 2) lastSpace else cut
    return flattened.take(end).trimEnd() + ellipsis
}
