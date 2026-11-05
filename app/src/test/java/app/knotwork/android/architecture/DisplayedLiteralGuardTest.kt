package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Interface text comes from string resources, not from string literals in code.
 *
 * **Why.** A sentence written into Kotlin is shown in English whatever language
 * the app is in, and no translation reaches it. Before the interface was
 * translated, about 300 places drew text that way: subtitles and counters built
 * in ViewModels, error and snackbar texts kept as constants, the background
 * service's notification status, design-system labels and accessibility
 * descriptions.
 *
 * **What it reads.** The code that draws interface: `:app`'s `presentation`
 * and `data/services` (notifications), every source set, and `:catalog`'s
 * production sources without its showcase ([InterfaceTextScope]) — comments
 * removed. A literal counts as interface text when it reads like words: two
 * words with a space between, or one capitalised word. It is left alone when it
 * is the argument of a call that never shows text (logging, preconditions,
 * exceptions, test tags, patterns, intents), when it has no word in it, when it
 * is a URL or a date pattern, and when it is a design-system class's default in
 * its constructor — `CatalogStringsWiringTest` holds those.
 *
 * **The list.** `config/l10n/displayed-literals.txt` names, as
 * `path<TAB>literal`, the literals that are interface text today. It only
 * shrinks: move a listed text to a resource and delete its line; write new
 * text into a resource from the start. A literal that is rightly not
 * translated (a brand name) stays on the list with its reason after `#`.
 */
class DisplayedLiteralGuardTest {

    @Test
    fun `interface text is in string resources, or on the list`() {
        val found = displayedLiterals()
        val listed = listed()

        val unlisted = (found - listed).sorted()
        assertTrue(
            "interface text written as a literal in code — it would show in English in every language. Move " +
                "it to a string resource (a ViewModel passes the resource id or a typed value; the screen " +
                "resolves it):\n" + unlisted.joinToString("\n"),
            unlisted.isEmpty(),
        )
        val stale = (listed - found).sorted()
        assertTrue(
            "${LIST.name} names literals that are gone — remove these lines:\n" + stale.joinToString("\n"),
            stale.isEmpty(),
        )
    }

    @Test
    fun `the heuristic tells words from identifiers`() {
        listOf("Agent is waiting", "Cancel", "Using tool: \${state.toolName}...", "WILL BREAK · %1\$d")
            .forEach { assertTrue(it, isProse(it)) }
        listOf("chat_session_id", "text/plain", "https://example.org/a b", "dd MMM yyyy", "%1\$s · %2\$s", "MCP", "")
            .forEach { assertFalse(it, isProse(it)) }
    }

    @Test
    fun `no left-out preview file declares anything the app uses`() {
        val root = File(ProductionSources.moduleDirectory().parentFile, "catalog/src/main/java")
        val appCode = ProductionSources.code.values.joinToString("\n")
        val used = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && InterfaceTextScope.isShowcase(it.name) }
            .flatMap { file ->
                TOP_LEVEL_DECLARATION.findAll(ProductionSources.stripComments(file.readText()))
                    .map { it.groupValues[1] }
                    .filter { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(appCode) }
                    .map { "${file.name}: $it" }
            }
            .toList()

        assertEquals(
            "a catalog file named like a preview declares something the app uses — it draws real interface. " +
                "Name it in InterfaceTextScope.REAL_COMPONENTS with what it draws.",
            emptyList<String>(),
            used,
        )
        InterfaceTextScope.REAL_COMPONENTS.keys.forEach { name ->
            assertTrue("$name is gone", root.walkTopDown().any { it.name == name })
        }
    }

    @Test
    fun `the census reads both modules`() {
        val found = displayedLiterals()
        assertTrue(found.any { it.startsWith("app/") })
        assertTrue(found.any { it.startsWith("catalog/") })
    }

    // ─── The census ──────────────────────────────────────────────────────────

    private fun displayedLiterals(): Set<String> = interfaceSources().flatMap { (path, code) ->
        val defaults = KotlinStringLiterals.constructors(code).filter { it.englishDefaults.isNotEmpty() }
        KotlinStringLiterals.literals(code)
            .filter { literal -> defaults.none { literal.start in it.start until it.end } }
            .filter { isProse(it.content) && !inSilentCall(code, it.start) }
            .map { "$path\t${it.content}" }
    }.toSet()

    private fun interfaceSources(): Map<String, String> {
        val app = ProductionSources.code
            .filterKeys { path -> APP_SCOPES.any { it in path } }
            .mapKeys { (path, _) -> "app/src/$path" }
        val root = File(ProductionSources.moduleDirectory().parentFile, "catalog/src/main/java")
        val catalog = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !InterfaceTextScope.isShowcase(it.name) }
            .associate {
                "catalog/src/main/java/${it.relativeTo(root).invariantSeparatorsPath}" to
                    ProductionSources.stripComments(it.readText())
            }
        return app + catalog
    }

    /** Whether the literal at [offset] is an argument of a call that never shows its text. */
    private fun inSilentCall(code: String, offset: Int): Boolean {
        var depth = 0
        var i = offset - 1
        val limit = maxOf(0, offset - CALL_LOOKBACK)
        while (i >= limit) {
            when (code[i]) {
                ')', ']', '}' -> depth++
                '(', '[' -> if (depth == 0) {
                    if (code[i] == '[') return false
                    val callee = CALLEE.find(code.substring(maxOf(0, i - CALLEE_LOOKBACK), i))?.groupValues?.get(1)
                    return callee != null && callee.substringAfterLast('.') in SILENT_CALLS
                } else {
                    depth--
                }
                '{' -> if (depth == 0) return false else depth--
            }
            i--
        }
        return false
    }

    private fun isProse(content: String): Boolean {
        val text = TEMPLATE.replace(content, " ").trim()
        if ("://" in text) return false
        val words = WORD.findAll(text).map { it.value }.toList()
        if (words.isEmpty() || words.all { DATE_PATTERN_WORD.matches(it) }) return false
        return (words.size >= 2 && ' ' in text) || CAPITALISED_WORD.matches(text)
    }

    private fun listed(): Set<String> = LIST.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        // Not trimmed: a literal may end in a space, and the line holds it exactly.
        .map { it.substringBefore("\t#") }
        .toSet()

    private companion object {
        const val CALL_LOOKBACK = 400
        const val CALLEE_LOOKBACK = 80
        val LIST = File(ProductionSources.moduleDirectory().parentFile, "config/l10n/displayed-literals.txt")
        val APP_SCOPES = listOf("/presentation/", "/data/services/")

        // Kotlin templates (`${…}`, `$name`) and format specifiers, written in source as `%1\$s`.
        val TEMPLATE = Regex("""\$\{[^}]*}|(?<!\\)\$[A-Za-z_]\w*|%\d*(?:\\?\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]""")
        val WORD = Regex("[A-Za-z]{2,}")
        val DATE_PATTERN_WORD = Regex("[yMdHhmsSEaKkzZ]+")
        val CAPITALISED_WORD = Regex("""[A-Z][a-z]+[^\sA-Za-z]*""")
        val CALLEE = Regex("""(@?[\w.]+)\s*(?:<[^>]*>)?\s*$""")

        /** A public or internal top-level name; an extension's receiver is not the name. */
        val TOP_LEVEL_DECLARATION = Regex(
            """(?m)^(?:public\s+|internal\s+)?(?:fun|val|object|class|data class)\s+(?:<[^>]*>\s*)?""" +
                """(?:\w+(?:<[^>]*>)?\??\.)?(\w+)""",
        )
        val SILENT_CALLS = setOf(
            "v", "d", "i", "w", "e", "wtf", "Log",
            "require", "requireNotNull", "check", "checkNotNull", "error", "TODO", "assert",
            "IllegalStateException", "IllegalArgumentException", "UnsupportedOperationException",
            "IOException", "Exception", "RuntimeException", "CancellationException",
            "testTag", "Regex", "toRegex", "ofPattern", "SimpleDateFormat",
            "@Suppress", "@Deprecated", "@JvmName", "@SerialName", "@SuppressLint", "@VisibleForTesting",
            "Intent", "setAction", "putExtra", "getStringExtra", "setPackage", "Uri.parse", "parse",
            "startsWith", "endsWith", "contains", "split", "replace", "removePrefix", "removeSuffix",
            "substringBefore", "substringAfter", "substringAfterLast", "substringBeforeLast",
            "withPropertyName", "getIdentifier", "trace", "traceSection", "beginSection",
        )
    }
}
