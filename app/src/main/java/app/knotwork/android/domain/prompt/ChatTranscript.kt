package app.knotwork.android.domain.prompt

/**
 * Renders stored content into the line-oriented lists the app builds for a model
 * — a conversation transcript, the numbered memory and tool-result lists — so
 * that the content cannot open a line of its own.
 *
 * **The defect this exists for.** A transcript joined as
 * `"${message.role.name}: ${message.content}"` lets a message body containing a
 * line break followed by `User: …` produce a line byte-identical to a real user
 * turn. Tool output is untrusted, so a web page or an MCP server could write a
 * "user" statement into the one prompt whose only defence is to extract what the
 * user stated — and a numbered list or a `--- Block ---` header can be forged the
 * same way.
 *
 * **The rule.** An entry's first line carries its prefix; every further line of
 * the content is indented by [CONTINUATION_INDENT]. A line that starts in the
 * first column is therefore always the start of a real entry, whatever the
 * content says. Every line terminator a tokenizer may render as a line break is
 * honoured — CRLF, LF, VT, FF, CR, NEL, LINE SEPARATOR and PARAGRAPH SEPARATOR —
 * and each becomes a plain `\n`. Content without a line break is returned
 * unchanged, so single-line prompts keep their exact bytes.
 *
 * **What it does not promise.** The guarantee is structural: a forged line is no
 * longer identical to a real one. Whether a given model reads an indented
 * `User:` line as part of the turn above is not measured here, and text that
 * merely *asks* the model to do something remains the accepted prompt-injection
 * risk described in `SECURITY.md`.
 */
object ChatTranscript {

    /** Prefix of every continuation line. Never the first character of a label. */
    const val CONTINUATION_INDENT: String = "  "

    private val LINE_BREAK = Regex("\r\n|[\n\u000B\u000C\r\u0085  ]")

    /**
     * Renders one speaker turn as `"$label: $content"`, continuation lines indented.
     *
     * @param label Speaker label, possibly numbered (`User`, `3. AGENT`). Written
     *   by the caller, never taken from content.
     * @param content The stored message body.
     * @return The turn, of which only the first line starts in the first column.
     */
    fun turn(label: String, content: String): String = entry("$label: ", content)

    /**
     * Renders one list entry as [prefix] followed by [content], continuation lines
     * indented. For entries that have no speaker label, such as a numbered memory.
     *
     * @param prefix Text opening the entry's first line (e.g. `"2. "`).
     * @param content The stored text of the entry.
     * @return The entry, of which only the first line starts in the first column.
     */
    fun entry(prefix: String, content: String): String =
        prefix + LINE_BREAK.split(content).joinToString(separator = "\n$CONTINUATION_INDENT")
}
