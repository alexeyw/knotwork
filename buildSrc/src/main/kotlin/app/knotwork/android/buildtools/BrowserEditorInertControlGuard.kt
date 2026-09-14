package app.knotwork.android.buildtools

/**
 * Finds node-form controls in the browser pipeline editor for configuration fields
 * that no run reads.
 *
 * The app removed such controls on principle (`docs/decisions/0005`): a control that
 * saves a value the run ignores teaches the user that the app ignores them. The browser
 * editor is a separate, hand-written form, and it kept offering eight of them — sampling,
 * token and timeout fields on the on-device and cloud nodes — with validation that could
 * block a save over a value nobody could see the effect of.
 *
 * Which fields count is not restated here. It is read from
 * [CookbookDocsGenerator.FIELD_REACH], whose [CookbookDocsGenerator.Reach.RoundTripOnly]
 * verdicts are themselves checked against the app's codec, so the browser follows the
 * same list the published cookbook does.
 */
object BrowserEditorInertControlGuard {

    /**
     * Returns `NODE_TYPE.field` for every round-trip-only field the browser editor's
     * `renderFormFields` still references in that node type's `case`.
     *
     * A reference is the field name quoted as a control key or read as `cfg.<field>`.
     * The node type of a `XxxConfig` class is its name in upper snake case
     * (`LiteRtConfig` → `LITE_RT`).
     *
     * @param html Content of `pipeline-editor.html`.
     * @param reach Field verdicts keyed `ConfigClass.field`.
     * @return The offending fields, sorted; empty when the forms are clean.
     * @throws IllegalStateException when `renderFormFields` or a needed `case` is absent,
     *   so a rename cannot make the guard pass by checking nothing.
     */
    fun inertControls(html: String, reach: Map<String, CookbookDocsGenerator.Reach>): List<String> {
        val formStart = html.indexOf("function renderFormFields(")
        check(formStart >= 0) { "renderFormFields not found in pipeline-editor.html" }
        val switchStart = html.indexOf("switch (typeId)", formStart)
        check(switchStart >= 0) { "renderFormFields has no switch (typeId)" }
        val switchBody = html.substring(switchStart, closingBrace(html, html.indexOf('{', switchStart)))
        return reach.filterValues { it is CookbookDocsGenerator.Reach.RoundTripOnly }.keys
            .map { key -> key.substringBefore('.') to key.substringAfter('.') }
            .groupBy({ nodeTypeOf(it.first) }, { it.second })
            .flatMap { (nodeType, fields) ->
                val case = caseBody(switchBody, nodeType)
                    ?: error("renderFormFields has no case '$nodeType'")
                fields.filter { field -> referencePattern(field).containsMatchIn(case) }.map { "$nodeType.$it" }
            }
            .sorted()
    }

    /** `LiteRtConfig` → `LITE_RT`. */
    fun nodeTypeOf(configClass: String): String =
        configClass.removeSuffix("Config").replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()

    private fun referencePattern(field: String) = Regex("""['"]$field['"]|\bcfg\.$field\b""")

    private fun caseBody(switchBody: String, nodeType: String): String? {
        val start = switchBody.indexOf("case '$nodeType':")
        if (start < 0) return null
        val next = Regex("""\n\s*(case '|default:)""").find(switchBody, start + 1)
        return switchBody.substring(start, next?.range?.first ?: switchBody.length)
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
}
