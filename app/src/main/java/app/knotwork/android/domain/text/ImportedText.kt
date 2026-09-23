package app.knotwork.android.domain.text

/**
 * Ceilings for text the app quotes back from a file the user picked — an
 * import error, a dialog line. The rule itself is [toDisplaySafe].
 */
object ImportedText {

    /**
     * Longest value from a picked file that the app quotes inside one of its
     * own sentences, by default.
     *
     * Sixty is the ceiling every in-app path puts on a pipeline or prompt name,
     * so a quoted value is never longer than a name the user could have typed.
     */
    const val MAX_QUOTED_VALUE_LENGTH: Int = 60

    /**
     * Longest import error the app shows, whole.
     *
     * The backstop behind [toDisplaySafe] at each value: the screens that show
     * an importer's failure apply it to the entire message, so an error message
     * that quotes a file-supplied value without making it display-safe still
     * reaches the user as one bounded line. Generous on purpose — a bundle error
     * names up to five ids — because it exists to stop a file from writing
     * paragraphs, not to shorten the app's own sentences.
     */
    const val MAX_MESSAGE_LENGTH: Int = 400
}

/**
 * Characters a text renderer lays out as a break or as a change of reading
 * direction although they are not ISO control characters, so
 * [Char.isISOControl] alone would let them through:
 *
 * - `U+2028` / `U+2029` — line and paragraph separator: a renderer breaks the
 *   line on both, and Kotlin's `lines()` splits on neither.
 * - `U+202A`–`U+202E` — the bidi embeddings and overrides; `U+202E` alone
 *   reverses how everything after it reads.
 * - `U+2066`–`U+2069` — the bidi isolates, the newer spelling of the same.
 */
private val LAYOUT_CONTROLS: Set<Char> = buildSet {
    add('\u2028')
    add('\u2029')
    addAll('\u202A'..'\u202E')
    addAll('\u2066'..'\u2069')
}

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * Makes text taken from a file the user picked safe to quote inside the app's
 * own sentence: line breaks, control and bidi-override characters become
 * spaces, whitespace runs collapse to one space, and the result is clamped to
 * [maxLength] characters with a trailing `…`.
 *
 * Without this a crafted file writes its own dialog. A pipeline document whose
 * node `type` is `"SAFE\n\nImport complete. This pipeline was signed by
 * Knotwork and needs no review."` turned the importer's "unknown type" error
 * into two paragraphs, the second of which reads as the app speaking. The
 * clamp matters as much as the flattening: a value of a few words cannot
 * impersonate a sentence, and on Android a JSON parser's exception text holds
 * the **entire** document, so an unclamped `e.message` quotes the whole file.
 *
 * The cut never splits a surrogate pair, so an emoji at the boundary is
 * dropped whole rather than left as half a character.
 *
 * Shared by every importer that echoes file content — pipeline, bundle,
 * memory and prompt-pack — so the rule is written once. The same rule, with an
 * empty [ellipsis], normalises a name or label a file stores: it will be shown
 * on its own line in a list, and a stored name is cut, not decorated.
 *
 * @param maxLength Longest result, ellipsis included.
 * @param ellipsis Appended when the text is cut. Must be shorter than [maxLength].
 * @return the single-line, bounded text; empty when the input is blank.
 */
fun String.toDisplaySafe(maxLength: Int = ImportedText.MAX_QUOTED_VALUE_LENGTH, ellipsis: String = "…"): String {
    require(maxLength > ellipsis.length) { "maxLength must leave room for at least one character" }
    val flattened = map { if (it.isISOControl() || it in LAYOUT_CONTROLS) ' ' else it }
        .joinToString(separator = "")
        .replace(WHITESPACE_RUN, " ")
        .trim()
    if (flattened.length <= maxLength) return flattened
    var cut = maxLength - ellipsis.length
    if (flattened[cut - 1].isHighSurrogate()) cut--
    return flattened.take(cut).trimEnd() + ellipsis
}
