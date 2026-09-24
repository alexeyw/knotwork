package app.knotwork.android.buildtools

/**
 * Finds node-configuration fields the app runs on that the browser pipeline editor
 * loses somewhere between opening a file and saving one.
 *
 * A run-time field crosses the editor through a chain of hand-written functions:
 * the import reads it from the file's flat `config` block, `deriveRichFromFlat`
 * turns it into the form's value, `renderFormFields` offers a control for it,
 * `encodeRichEnvelope` and `richToFlat` write it back into the node's two copies,
 * and `exportToJson` writes the flat copy — the one the app runs and shows — into
 * the file. A field missing from any one link is a setting that silently resets:
 * the queue's "Stop on first error" was edited and never exported, and the
 * condition's "branch on image" had no link at all, so a file passed through the
 * editor came back with both at their defaults.
 *
 * The chain has one more rule. The import takes a run-time field from the flat
 * copy only, never from the `nodeConfig` envelope — as the app's node sheet does —
 * so a file whose two copies disagree is shown the way the app will run it.
 *
 * Which fields count is not listed here or in the editor: it is every
 * [CookbookDocsGenerator.Reach.Runtime] verdict in [CookbookDocsGenerator.FIELD_REACH],
 * the list the cookbook publishes and the app's codec is checked against. A guard
 * that took its field list from the editor would be counting with the code it
 * guards.
 */
object BrowserEditorRuntimeFieldGuard {

    /** One step of the chain, named for the report. */
    enum class Link(val description: String) {
        /** `importFromJson` reads the flat key from the file's `config` block. */
        IMPORT("importFromJson does not read config.%s"),

        /** `deriveRichFromFlat` sets the form value from the flat copy. */
        DERIVE("deriveRichFromFlat does not set cfg.%s"),

        /** `renderFormFields` offers a control for the field. */
        CONTROL("renderFormFields offers no control for %s"),

        /** `encodeRichEnvelope` writes the field into the `nodeConfig` envelope. */
        ENVELOPE("encodeRichEnvelope does not write env.%s"),

        /** `richToFlat` derives the flat key from the form value. */
        FLAT("richToFlat does not set flat.%s"),

        /** `exportToJson` writes the flat key into the file's `config` block. */
        EXPORT("exportToJson does not write config.%s"),

        /** `decodeRichEnvelope` reads the field from the envelope instead of the flat copy. */
        ENVELOPE_READ("decodeRichEnvelope reads env.%s — the run reads the flat copy"),
    }

    /**
     * Returns one line per broken link, `NODE_TYPE.field (flatKey): <what is wrong>`,
     * sorted.
     *
     * @param html Content of `pipeline-editor.html`.
     * @param reach Field verdicts keyed `ConfigClass.field`; only
     *   [CookbookDocsGenerator.Reach.Runtime] entries are checked.
     * @return The broken links; empty when every run-time field survives the editor.
     * @throws IllegalStateException when a function of the chain or the export's
     *   `config` block is absent, so a rename cannot make the guard pass by checking
     *   nothing. A node type's missing `case` is reported as broken links instead.
     */
    fun brokenLinks(html: String, reach: Map<String, CookbookDocsGenerator.Reach>): List<String> {
        val import = functionBody(html, "importFromJson")
        val derive = functionBody(html, "deriveRichFromFlat")
        val form = functionBody(html, "renderFormFields")
        val encode = functionBody(html, "encodeRichEnvelope")
        val decode = functionBody(html, "decodeRichEnvelope")
        // A read placed before the per-type switch applies to every node type.
        val decodeShared = decode.substringBefore("switch (typeId)")
        val flat = functionBody(html, "richToFlat")
        val exported = exportedConfigKeys(functionBody(html, "exportToJson"))
        val runtime = reach.mapNotNull { (key, verdict) ->
            (verdict as? CookbookDocsGenerator.Reach.Runtime)?.let { key to it.field }
        }
        check(runtime.isNotEmpty()) { "FIELD_REACH has no Runtime verdicts — nothing would be checked" }
        return runtime.flatMap { (key, flatKey) ->
            val nodeType = BrowserEditorInertControlGuard.nodeTypeOf(key.substringBefore('.'))
            val field = key.substringAfter('.')
            val broken = buildList {
                if (!mentions(import, """\.config\?\.${Regex.escape(flatKey)}\b""")) add(Link.IMPORT to flatKey)
                if (!mentions(caseOf(derive, nodeType), assignment("cfg", field))) {
                    add(Link.DERIVE to field)
                }
                if (!mentions(controlsOf(html, form, nodeType), reference(field))) add(Link.CONTROL to field)
                if (!mentions(caseOf(encode, nodeType), assignment("env", field))) {
                    add(Link.ENVELOPE to field)
                }
                if (!mentions(caseOf(flat, nodeType), assignment("flat", flatKey))) {
                    add(Link.FLAT to flatKey)
                }
                if (flatKey !in exported) add(Link.EXPORT to flatKey)
                val envelopeRead = """\benv\.${Regex.escape(field)}\b"""
                if (mentions(decodeShared, envelopeRead) || mentions(caseOf(decode, nodeType), envelopeRead)) {
                    add(Link.ENVELOPE_READ to field)
                }
            }
            broken.map { (link, name) -> "$nodeType.$field ($flatKey): ${link.description.format(name)}" }
        }.sorted()
    }

    /**
     * The form case for [nodeType], with the body of every helper it hands the whole
     * `cfg` to (`cfgPipelinePicker(cfg)`), since such a helper reads its fields there.
     */
    private fun controlsOf(html: String, form: String, nodeType: String): String {
        val case = caseOf(form, nodeType)
        val helpers = HELPER_CALL.findAll(case).map { it.groupValues[1] }.toSet()
        return helpers.fold(case) { text, helper -> text + functionBody(html, helper) }
    }

    private fun mentions(text: String, pattern: String): Boolean = Regex(pattern).containsMatchIn(text)

    private fun assignment(receiver: String, name: String) = """\b$receiver\.${Regex.escape(name)}\s*=(?!=)"""

    private fun reference(field: String) = """['"]${Regex.escape(field)}['"]|\bcfg\.${Regex.escape(field)}\b"""

    /** A missing case is a broken link like any other, so it reads as empty rather than failing the parse. */
    private fun caseOf(body: String, nodeType: String): String = caseOrNull(body, nodeType).orEmpty()

    private fun caseOrNull(body: String, nodeType: String): String? {
        val start = body.indexOf("case '$nodeType':")
        if (start < 0) return null
        val next = NEXT_CASE.find(body, start + 1)
        return body.substring(start, next?.range?.first ?: body.length)
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

    private val NEXT_CASE = Regex("""\n\s*(case '|default:)""")
    private val HELPER_CALL = Regex("""\b(\w+)\(cfg\)""")
    private val EXPORTED_KEY = Regex("""^\s*(\w+)\s*:""", RegexOption.MULTILINE)
}
