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
 * - the node's *Input data* flags were read by two different rules — `0` / `null` shown
 *   as off while the app read them as **on** — so long-term memory and tool results
 *   showed off on a cloud node the app sent them to;
 * - provider ids were matched case-sensitively, so `"Anthropic"` showed as on-device
 *   while the app called Anthropic;
 * - branch labels were matched case-sensitively, so an IF edge labelled `false` hung
 *   from the True socket while the app took it for every message without the keyword.
 *
 * Each check takes its reference from the app's own source, not from the editor:
 * `CONFIG_KEYS` / `CONTEXT_CONFIG_KEYS` in `PipelineJsonSerializer`, the ids
 * `CloudProvider.fromId` accepts and the branches `RouteLabels.fixedBranches` lists. What the checks cannot see is how a value is coerced
 * once it is read — there is no JavaScript runtime in the build — so the coercion rules
 * live in named editor functions (`wireBool`, `readContextConfig`, `canonicalBranchLabel`)
 * whose comments state the app's rule, and the checks make every read go through them.
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
     * @param routeLabelsSource Content of `domain/models/RouteLabels.kt`.
     * @return The mismatches; empty when the editor reads a file by the app's rules.
     * @throws IllegalStateException when a function, a key set, `fromId` or a branch
     *   table cannot be found, so a rename cannot make the guard pass by checking nothing.
     */
    fun mismatches(
        html: String,
        serializerSource: String,
        cloudProviderSource: String,
        routeLabelsSource: String,
    ): List<String> {
        val configKeys = kotlinStringSet(serializerSource, "CONFIG_KEYS")
        val contextKeys = kotlinStringSet(serializerSource, "CONTEXT_CONFIG_KEYS")
        val providerIds = fromIdLiterals(cloudProviderSource)
        val import = functionBody(html, "importFromJson")
        return (
            unknownConfigReads(import, configKeys) +
                contextConfigReads(html, import, contextKeys) +
                providerMapping(html, providerIds) +
                branchLabels(html, import, appBranches(routeLabelsSource))
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

    /**
     * `PORT_AUTO_LABELS` spells every fixed-branch node's branches as `RouteLabels` does,
     * in the same socket order, and the import reads each edge label through
     * `canonicalBranchLabel`, which matches without regard to case.
     */
    private fun branchLabels(html: String, import: String, appBranches: Map<String, List<String>>): List<String> {
        val problems = mutableListOf<String>()
        val editorBranches = editorBranches(html)
        (appBranches.keys + editorBranches.keys).sorted().forEach { type ->
            val app = appBranches[type]
            val editor = editorBranches[type]
            if (app != editor) problems += "PORT_AUTO_LABELS spells $type's branches $editor, the app $app"
        }
        if ("canonicalBranchLabel(" !in import) {
            problems += "importFromJson does not read branch labels through canonicalBranchLabel"
        }
        val canonicalizer = functionBody(html, "canonicalBranchLabel")
        if (".toLowerCase()" !in canonicalizer || "PORT_AUTO_LABELS" !in canonicalizer) {
            problems += "canonicalBranchLabel does not match PORT_AUTO_LABELS without regard to case"
        }
        return problems
    }

    /** `RouteLabels.fixedBranches`: node type → branch labels, resolved through its constants. */
    private fun appBranches(source: String): Map<String, List<String>> {
        val constants = KOTLIN_STRING_CONST.findAll(source).associate { it.groupValues[1] to it.groupValues[2] }
        val start = source.indexOf("fun fixedBranches(")
        check(start >= 0) { "RouteLabels.fixedBranches not found" }
        val body = source.substring(start, source.indexOf("else ->", start))
        return FIXED_BRANCH_ENTRY.findAll(body).associate { match ->
            match.groupValues[1] to match.groupValues[2].split(',').map { name ->
                constants[name.trim()] ?: error("RouteLabels has no constant ${name.trim()}")
            }
        }.also { check(it.isNotEmpty()) { "RouteLabels.fixedBranches lists no branches" } }
    }

    /** The editor's `PORT_AUTO_LABELS`: node type → socket labels in socket order. */
    private fun editorBranches(html: String): Map<String, List<String>> {
        val start = html.indexOf("const PORT_AUTO_LABELS = {")
        check(start >= 0) { "PORT_AUTO_LABELS not found in pipeline-editor.html" }
        val open = html.indexOf('{', start)
        val table = html.substring(open + 1, closingBrace(html, open) - 1)
        return EDITOR_BRANCH_ENTRY.findAll(table).associate { match ->
            match.groupValues[1] to JS_STRING_LITERAL.findAll(match.groupValues[2]).map { it.groupValues[1] }.toList()
        }.also { check(it.isNotEmpty()) { "PORT_AUTO_LABELS lists no branches" } }
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
    private val KOTLIN_STRING_CONST = Regex("""const val (\w+): String = "([^"]*)"""")
    private val FIXED_BRANCH_ENTRY = Regex("""NodeType\.(\w+) -> listOf\(([^)]*)\)""")
    private val EDITOR_BRANCH_ENTRY = Regex("""(\w+):\s*\{([^}]*)}""")
    private val JS_STRING_LITERAL = Regex("""'([^']*)'""")
    private val CONTEXT_READ = Regex("""\bjn\.contextConfig\b""")
    private val ROUTED_CONTEXT_READ = Regex("""readContextConfig\(\s*jn\.type\s*,\s*jn\.contextConfig\s*\)""")
    private val STRING_LITERAL = Regex(""""([^"\\]+)"""")
}
