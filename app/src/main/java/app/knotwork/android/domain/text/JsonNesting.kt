package app.knotwork.android.domain.text

/**
 * Bounds how deeply an imported JSON document may nest before it reaches the parser.
 *
 * Android's `org.json` builds a document by recursion, one call per nesting level,
 * so a deep enough `[[[[…` throws `StackOverflowError` — an `Error`, which no
 * `catch (JSONException)` sees — on whatever thread is parsing, the main thread
 * included for a pipeline import. Measured under Robolectric (the platform parser
 * on a test thread's stack): 10 000 levels, about 20 KB of a file the import
 * accepts up to 8 MB, overflowed; 1 000 parsed. Every import parser asks
 * [exceeds] first and answers an over-deep document with its ordinary failure.
 *
 * The scan is a single pass with no recursion and constant memory: it counts
 * brackets outside string literals and stops at the first level past the limit. It
 * does not validate the document — malformed JSON is the parser's to report.
 */
object JsonNesting {

    /**
     * The deepest nesting an import may have. The app's own documents nest at
     * most 7 levels (bundled presets, cookbook recipes and golden fixtures,
     * measured; a memory export is 6 by its layout), and the platform parser
     * handled 1 000 in the measurement above.
     */
    const val MAX_DEPTH = 64

    /**
     * Whether [text] nests objects and arrays deeper than [maxDepth].
     *
     * @param text The JSON text about to be parsed.
     * @param maxDepth The deepest nesting allowed.
     * @return `true` at the first `{` or `[` that opens level `maxDepth + 1`;
     *   `false` when no level goes past it.
     */
    fun exceeds(text: String, maxDepth: Int = MAX_DEPTH): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (char in text) {
            when {
                escaped -> escaped = false
                inString && char == '\\' -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '{' || char == '[' -> if (++depth > maxDepth) return true
                char == '}' || char == ']' -> depth--
            }
        }
        return false
    }

    /** The failure message every import parser gives for an over-deep document. */
    const val FAILURE_MESSAGE = "Invalid JSON: nested deeper than $MAX_DEPTH levels"
}
