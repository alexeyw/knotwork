package app.knotwork.android.buildtools

/**
 * Finds places where the browser pipeline editor reads a pipeline file by a different
 * rule than the app's importer, so the browser shows one pipeline and the app runs
 * another.
 *
 * People open a file in the browser editor to look at it before importing it into the
 * app. [BrowserEditorRuntimeFieldGuard] checks that every run-time node field travels
 * through the editor; it does not check that the editor reads the file the way the app
 * does. The security audit of the editor as a second parser found three such shapes,
 * each measured on a crafted file:
 * - the import read `config` keys the app never reads (a legacy prompt key shown as
 *   any node's prompt);
 * - the node's *Input data* flags were filled from the node type's defaults, and `0` /
 *   `null` read as off, while the app reads a missing key, `0` and `null` as **on** —
 *   long-term memory and tool results shown off on a cloud node the app sent them to;
 * - provider ids were matched case-sensitively, so `"Anthropic"` showed as on-device
 *   while the app called Anthropic.
 *
 * Each check takes its reference from the app's own source, not from the editor:
 * `CONFIG_KEYS` / `CONTEXT_CONFIG_KEYS` in `PipelineJsonSerializer` and the ids
 * `CloudProvider.fromId` accepts. What the checks cannot see is how a value is coerced
 * once it is read — there is no JavaScript runtime in the build — so the coercion rules
 * live in two named editor functions (`wireBool`, `readContextConfig`) whose KDoc states
 * the app's rule, and the checks make every read go through them.
 */
object BrowserEditorImportParityGuard {

    /**
     * `config` keys the editor's import may read although the app's importer does not:
     * early editors stored a router's prompt under `intentRouterPrompt`. The list is
     * closed; a key on it is shown for the file's author only and never decides what a
     * run does, since the app drops it.
     */
    val LEGACY_IMPORT_KEYS: Set<String> = setOf("intentRouterPrompt")

    /**
     * Returns one line per mismatch, sorted.
     *
     * @param html Content of `pipeline-editor.html`.
     * @param serializerSource Content of `PipelineJsonSerializer.kt`.
     * @param cloudProviderSource Content of `domain/models/CloudProvider.kt`.
     * @return The mismatches; empty when the editor reads a file by the app's rules.
     * @throws IllegalStateException when a function, a key set or `fromId` cannot be
     *   found, so a rename cannot make the guard pass by checking nothing.
     */
    fun mismatches(html: String, serializerSource: String, cloudProviderSource: String): List<String> {
        val configKeys = kotlinStringSet(serializerSource, "CONFIG_KEYS")
        val contextKeys = kotlinStringSet(serializerSource, "CONTEXT_CONFIG_KEYS")
        val providerIds = fromIdLiterals(cloudProviderSource)
        val import = functionBody(html, "importFromJson")
        return (
            unknownConfigReads(import, configKeys) +
                contextConfigReads(html, import, contextKeys) +
                providerMapping(html, providerIds)
            ).sorted()
    }

    /** Every `jn.config?.<key>` / `jn.config.<key>` the import reads must be a key the app reads. */
    private fun unknownConfigReads(import: String, configKeys: Set<String>): List<String> =
        CONFIG_READ.findAll(import).map { it.groupValues[1] }.toSet()
            .filter { it !in configKeys && it !in LEGACY_IMPORT_KEYS }
            .map { "importFromJson reads config.$it, which the app's importer does not read" }

    /**
     * The import reads `contextConfig` only through `readContextConfig`, and that function
     * reads every flag the app reads (`nodeInput` excepted: the form keeps it on).
     */
    private fun contextConfigReads(html: String, import: String, contextKeys: Set<String>): List<String> {
        val problems = mutableListOf<String>()
        val reads = CONTEXT_READ.findAll(import).count()
        val routed = ROUTED_CONTEXT_READ.findAll(import).count()
        if (reads == 0) problems += "importFromJson does not read contextConfig at all"
        if (reads != routed) problems += "importFromJson reads contextConfig outside readContextConfig"
        val reader = functionBody(html, "readContextConfig")
        contextKeys.filter { it != "nodeInput" }
            .filterNot { Regex("""\braw\.${Regex.escape(it)}\b""").containsMatchIn(reader) }
            .forEach { problems += "readContextConfig does not read contextConfig.$it" }
        if ("wireBool(" !in reader) problems += "readContextConfig does not read flags through wireBool"
        return problems
    }

    /**
     * `wireToTile` lower-cases the id and names every id `CloudProvider.fromId` accepts,
     * and both provider readers resolve through it.
     */
    private fun providerMapping(html: String, providerIds: Set<String>): List<String> {
        val problems = mutableListOf<String>()
        val resolver = functionBody(html, "wireToTile")
        if (".toLowerCase()" !in resolver) problems += "wireToTile matches provider ids case-sensitively"
        providerIds.filterNot { "case '$it'" in resolver }
            .forEach { problems += "wireToTile does not know provider id '$it'" }
        listOf("flatToRichCloud", "flatToEngineProvider")
            .filterNot { "wireToTile(" in functionBody(html, it) }
            .forEach { problems += "$it does not resolve provider ids through wireToTile" }
        return problems
    }

    /** String literals of `private val NAME = setOf( … )` in a Kotlin source. */
    private fun kotlinStringSet(source: String, name: String): Set<String> {
        val start = source.indexOf("val $name = setOf(")
        check(start >= 0) { "$name not found in PipelineJsonSerializer.kt" }
        val open = source.indexOf('(', start)
        val body = source.substring(open, source.indexOf(')', open))
        return STRING_LITERAL.findAll(body).map { it.groupValues[1] }.toSet()
            .also { check(it.isNotEmpty()) { "$name has no string literals" } }
    }

    /** The string literals of the `when` in `CloudProvider.fromId`. */
    private fun fromIdLiterals(source: String): Set<String> {
        val start = source.indexOf("fun fromId(")
        check(start >= 0) { "CloudProvider.fromId not found" }
        val open = source.indexOf('{', start)
        val body = source.substring(open, closingBrace(source, open))
        return STRING_LITERAL.findAll(body).map { it.groupValues[1] }.toSet()
            .also { check(it.isNotEmpty()) { "CloudProvider.fromId has no string literals" } }
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

    private val CONFIG_READ = Regex("""\bjn\.config\??\.(\w+)""")
    private val CONTEXT_READ = Regex("""\bjn\.contextConfig\b""")
    private val ROUTED_CONTEXT_READ = Regex("""readContextConfig\(\s*jn\.type\s*,\s*jn\.contextConfig\s*\)""")
    private val STRING_LITERAL = Regex(""""([^"\\]+)"""")
}
