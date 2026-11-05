package app.knotwork.android.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every English default the design system gives a piece of interface text is
 * replaced by the app with a resource.
 *
 * **Why.** The catalog draws screens without depending on the app, so its
 * state and copy classes carry English defaults (`val title: String = "Performance"`)
 * that keep previews and screenshot tests free of resources. The app is meant
 * to pass every one of them from its string resources. When it does not, the
 * default ships: the Models screen's Performance card kept 21 English defaults
 * in every language, because the app never built `PerformanceStrings` at all
 * (no call of it in `:app`, found by this test).
 * No translation reaches such a string.
 *
 * **What it reads.** Every class of `:catalog`'s production sources (preview
 * data and showcase pages aside) whose primary constructor gives a `String`
 * parameter a literal default with a letter in it. Such a parameter is wired
 * when some call of that constructor in `:app` or `:catalog` production code
 * passes it — by name or by position. A call inside the catalog counts: one
 * that resolves the text from the catalog's own resources is as good as one in
 * the app.
 *
 * **The list.** `config/l10n/unwired-strings.txt` names, as `Class.parameter`,
 * the defaults not wired yet. It only shrinks: a new default is wired in the
 * change that adds it; a listed one that is wired by now must leave the list.
 */
class CatalogStringsWiringTest {

    @Test
    fun `every English default of the design system is wired from resources or listed`() {
        val unwired = unwiredDefaults()
        val listed = listed()

        val unlisted = (unwired - listed).sorted()
        assertTrue(
            "a design-system text default reaches the screen as English — pass it from a string resource " +
                "where the class is built:\n" + unlisted.joinToString("\n"),
            unlisted.isEmpty(),
        )
        val stale = (listed - unwired).sorted()
        assertTrue(
            "${LIST.name} names defaults that are wired by now or no longer exist — remove:\n" +
                stale.joinToString("\n"),
            stale.isEmpty(),
        )
    }

    @Test
    fun `the census finds defaults and sees a wired one`() {
        val defaults = catalogConstructors().flatMap { c -> c.englishDefaults.keys.map { "${c.className}.$it" } }

        assertTrue("too few defaults found — the parser is blind", defaults.size > MIN_DEFAULTS)
        // ModelsStrings is built by the app with its title from a resource.
        assertTrue("ModelsStrings.title" in defaults)
        assertTrue("ModelsStrings.title" !in unwiredDefaults())
    }

    private fun unwiredDefaults(): Set<String> {
        val callers = callerSources()
        return catalogConstructors().flatMap { constructor ->
            val passed = callers.flatMap { code ->
                KotlinStringLiterals.constructorCalls(code, constructor.className).flatMap { arguments ->
                    arguments.mapIndexedNotNull { index, argument ->
                        NAMED_ARGUMENT.find(argument)?.groupValues?.get(1)
                            ?: constructor.parameters.getOrNull(index)
                    }
                }
            }.toSet()
            (constructor.englishDefaults.keys - passed).map { "${constructor.className}.$it" }
        }.toSet()
    }

    private fun catalogConstructors(): List<KotlinStringLiterals.ConstructorParameters> =
        catalogSources().values.flatMap { KotlinStringLiterals.constructors(it) }
            .filter { it.englishDefaults.isNotEmpty() }

    /** Production code that may build a catalog class: all of `:app`, and `:catalog` minus previews. */
    private fun callerSources(): List<String> = ProductionSources.code.values + catalogSources().values

    private fun catalogSources(): Map<String, String> {
        val root = File(ProductionSources.moduleDirectory().parentFile, "catalog/src/main/java")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !InterfaceTextScope.isShowcase(it.name) }
            .associate { it.relativeTo(root).invariantSeparatorsPath to ProductionSources.stripComments(it.readText()) }
    }

    private fun listed(): Set<String> =
        LIST.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()

    private companion object {
        const val MIN_DEFAULTS = 200
        val LIST = File(ProductionSources.moduleDirectory().parentFile, "config/l10n/unwired-strings.txt")
        val NAMED_ARGUMENT = Regex("""^(\w+)\s*=(?!=)""")
    }
}
