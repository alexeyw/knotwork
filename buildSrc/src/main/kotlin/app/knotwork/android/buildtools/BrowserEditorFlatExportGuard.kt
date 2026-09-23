package app.knotwork.android.buildtools

/**
 * Finds run-time fields the browser pipeline editor derives for a node but leaves
 * out of the file it exports.
 *
 * The editor keeps each node's form in a rich config and derives the flat values
 * the app's engine reads in `richToFlat`; `exportToJson` then writes a `config`
 * block from a fixed list of keys. A key missing from that list is a setting the
 * user made in the browser that never reaches the run — and the app's node sheet
 * shows the flat copy (the one that runs), so after import the setting is not even
 * visible there. Three fields fell through this way (`fallbackClass`,
 * `maxSubtasks`, `quickReplies`) while the sheet still read the editor's copy and
 * so hid the loss.
 */
object BrowserEditorFlatExportGuard {

    /**
     * Returns every flat key `richToFlat` sets that `exportToJson`'s `config`
     * block does not write, sorted.
     *
     * @param html Content of `pipeline-editor.html`.
     * @return The missing keys; empty when the export carries everything derived.
     * @throws IllegalStateException when either function or the `config` block is
     *   absent, so a rename cannot make the guard pass by checking nothing.
     */
    fun unexportedFlatFields(html: String): List<String> {
        val derived = derivedKeys(functionBody(html, "richToFlat"))
        check(derived.isNotEmpty()) { "richToFlat derives no keys — the parse is wrong, not the editor" }
        val exported = exportedConfigKeys(functionBody(html, "exportToJson"))
        return (derived - exported).sorted()
    }

    /** Keys of the initial `const flat = { … }` literal plus every `flat.<key> =` assignment. */
    private fun derivedKeys(body: String): Set<String> {
        val literalStart = body.indexOf("const flat = {")
        check(literalStart >= 0) { "richToFlat has no `const flat = {` literal" }
        val open = body.indexOf('{', literalStart)
        val literal = body.substring(open + 1, closingBrace(body, open) - 1)
        val literalKeys = LITERAL_KEY.findAll(literal).map { it.groupValues[1] }
        val assigned = ASSIGNED_KEY.findAll(body).map { it.groupValues[1] }
        return (literalKeys + assigned).toSet()
    }

    /** Keys written inside the node's `config: { … }` object in `exportToJson`. */
    private fun exportedConfigKeys(body: String): Set<String> {
        val start = body.indexOf("config: {")
        check(start >= 0) { "exportToJson has no `config: {` block" }
        val open = body.indexOf('{', start)
        val block = body.substring(open + 1, closingBrace(body, open) - 1)
        return EXPORTED_KEY.findAll(block).map { it.groupValues[1] }.toSet()
    }

    private fun functionBody(html: String, name: String): String {
        val start = html.indexOf("function $name(")
        check(start >= 0) { "$name not found in pipeline-editor.html" }
        val open = html.indexOf('{', start)
        return html.substring(open, closingBrace(html, open))
    }

    private fun closingBrace(text: String, open: Int): Int {
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index + 1
            }
        }
        return text.length
    }

    private val LITERAL_KEY = Regex("""(\w+)\s*:""")
    private val ASSIGNED_KEY = Regex("""\bflat\.(\w+)\s*=(?!=)""")
    private val EXPORTED_KEY = Regex("""^\s*(\w+)\s*:""", RegexOption.MULTILINE)
}
