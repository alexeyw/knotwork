package app.knotwork.android.architecture

import app.knotwork.android.data.repositories.AppLanguageRepositoryImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every string the interface shows exists in every language the app ships —
 * or is named, one by one, on that language's untranslated list.
 *
 * **Why a test and not lint.** Lint's `MissingTranslation` is the end state, but
 * the translation lands over several changes, and with it on, the first
 * translated string fails the build for each of the ~2 200 that are not yet.
 * So the check is off (`lint { disable += "MissingTranslation" }` in `app` and
 * `catalog`) while `config/l10n/untranslated-<lang>.txt` lists what is still
 * missing, and this test holds the list honest in both directions:
 * - a string missing from a language and not on its list fails — a string added
 *   during the series is translated with it, not left behind;
 * - a list entry that is translated by now, or names no string, fails — the list
 *   only shrinks.
 *
 * The change that empties a list deletes it and turns `MissingTranslation` back
 * on; the language and module checks below stay.
 *
 * **What else it holds.**
 * - The build's `localeFilters` names exactly the default language and the
 *   languages with translations, in `app` and `catalog` alike: a language that
 *   is filtered in but untranslated in one module would show that module in
 *   English, and one translated but filtered out would never show.
 * - The default resources are the language [AppLanguageRepositoryImpl] puts
 *   first in the picker.
 * - The app bundle keeps every language in every install
 *   (`bundle.language.enableSplit = false`): Google Play installs language
 *   resources by the phone's system language, so a language picked in the app
 *   would otherwise find nothing to show in a Play install.
 * - A translation keeps the spaces at the edges of its English text: the
 *   resource compiler trims them unless the value is quoted.
 * - A translation that equals its English text is a copy, not a translation,
 *   unless `config/l10n/identical-<lang>.txt` names it (brand names, units).
 *   Text without a letter (`%1$s · %2$s`) is not compared.
 *
 * Reads `app/src/main/res`, `catalog/src/main/res`, `config/l10n/` and
 * `app/build.gradle.kts`; the last three are declared inputs of the test task.
 */
class TranslationCoverageTest {

    @Test
    fun `the build ships exactly the default language and the translated ones`() {
        val translated = MODULES.flatMap { languageFolders(it).keys }.toSet()

        assertEquals(
            "androidResources.localeFilters in app/build.gradle.kts must list the default language plus every " +
                "language that has a values-<lang> folder in app or catalog",
            translated + DEFAULT_LANGUAGE,
            localeFilters(),
        )
        for (module in MODULES) {
            assertEquals(
                "$module has no values-<lang> folder for a language the build ships — its screens would stay " +
                    "in English",
                translated,
                languageFolders(module).keys,
            )
        }
    }

    @Test
    fun `the app bundle ships every language to every install`() {
        val script = File(moduleRoot("app"), "build.gradle.kts").readText()

        assertTrue(
            "app/build.gradle.kts must keep bundle { language { enableSplit = false } }: Play installs " +
                "languages by the system's, and the in-app picker chooses its own",
            LANGUAGE_SPLIT_OFF.containsMatchIn(script),
        )
    }

    @Test
    fun `the default resources are the language the picker puts first`() {
        val properties = Properties().apply {
            File(moduleRoot("app"), "src/main/res/resources.properties").inputStream().use(::load)
        }

        assertEquals(AppLanguageRepositoryImpl.DEFAULT_LANGUAGE, properties.getProperty("unqualifiedResLocale"))
        assertEquals(AppLanguageRepositoryImpl.DEFAULT_LANGUAGE, DEFAULT_LANGUAGE)
    }

    @Test
    fun `every translatable string is translated or on the language's untranslated list`() {
        for (language in shippedLanguages()) {
            val missing = MODULES.flatMap { module ->
                val translated = keysIn(languageFolders(module).getValue(language))
                (translatableKeys(module) - translated).map { "$module:$it" }
            }.toSet()
            val listed = listEntries(untranslatedList(language))

            val unlisted = (missing - listed).sorted()
            assertTrue(
                "strings missing from values-$language and not on ${untranslatedList(language).name} — " +
                    "translate them in the same change (the list only shrinks):\n" + unlisted.joinToString("\n"),
                unlisted.isEmpty(),
            )
            val stale = (listed - missing).sorted()
            assertTrue(
                "${untranslatedList(language).name} names strings that are translated by now or no longer " +
                    "exist — remove these lines:\n" + stale.joinToString("\n"),
                stale.isEmpty(),
            )
        }
    }

    @Test
    fun `no translation is a copy of the English text unless listed as identical`() {
        for (language in shippedLanguages()) {
            val allowed = listEntries(identicalList(language))
            val copies = MODULES.flatMap { module ->
                val english = textsIn(defaultFolder(module))
                val translated = textsIn(languageFolders(module).getValue(language))
                translated.filter { (key, text) -> english[key] == text && hasWords(text) }
                    .keys.map { "$module:$it" }
            }.toSet()

            val unexplained = (copies - allowed).sorted()
            assertTrue(
                "values-$language repeats the English text — translate it, or name it in " +
                    "${identicalList(language).name} when the English is right in this language too:\n" +
                    unexplained.joinToString("\n"),
                unexplained.isEmpty(),
            )
            val stale = (allowed - copies).sorted()
            assertTrue(
                "${identicalList(language).name} names strings that are not identical any more — remove:\n" +
                    stale.joinToString("\n"),
                stale.isEmpty(),
            )
        }
    }

    @Test
    fun `a translation keeps the spaces at the edges of its English`() {
        for (language in shippedLanguages()) {
            val clipped = MODULES.flatMap { module ->
                val english = textsIn(defaultFolder(module))
                textsIn(languageFolders(module).getValue(language))
                    .filter { (key, text) -> english[key]?.let { edgeSpaces(it) != edgeSpaces(text) } == true }
                    .keys.map { "$module:$it" }
            }.sorted()
            assertTrue(
                "values-$language loses or adds a space at the edge of the English text — the build trims " +
                    "unquoted edge spaces, so quote the value (\", \"):\n" + clipped.joinToString("\n"),
                clipped.isEmpty(),
            )
        }
    }

    @Test
    fun `the census reads both modules and a real language`() {
        assertTrue(translatableKeys("app").size > MIN_KEYS_PER_MODULE)
        assertTrue(translatableKeys("catalog").size > MIN_KEYS_PER_MODULE)
        assertTrue("ru" in shippedLanguages())
        // A plural is a translatable entry like a string; the census must see both kinds.
        assertTrue(translatableKeys("app").any { it.startsWith("plurals/") })
    }

    // ─── Reading the resources ───────────────────────────────────────────────

    private fun shippedLanguages(): Set<String> = localeFilters() - DEFAULT_LANGUAGE

    private fun moduleRoot(module: String): File = File(ProductionSources.moduleDirectory().parentFile, module)

    private fun defaultFolder(module: String): File = File(moduleRoot(module), "src/main/res/values")

    /** `values-<lang>` folders holding at least one string, keyed by language. */
    private fun languageFolders(module: String): Map<String, File> =
        File(moduleRoot(module), "src/main/res").listFiles().orEmpty()
            .filter { it.isDirectory && LANGUAGE_FOLDER.matches(it.name) && keysIn(it).isNotEmpty() }
            .associateBy { it.name.removePrefix("values-") }

    /** Translatable entries of the default resources, as `string/<name>` and `plurals/<name>`. */
    private fun translatableKeys(module: String): Set<String> = entries(defaultFolder(module))
        .filter { it.getAttribute("translatable") != "false" }
        .map(::keyOf)
        .toSet()

    private fun keysIn(folder: File): Set<String> = entries(folder).map(::keyOf).toSet()

    /** Plain strings by key, as text; plurals are compared by lint's quantity checks, not here. */
    private fun textsIn(folder: File): Map<String, String> =
        entries(folder).filter { it.tagName == "string" }.associate { keyOf(it) to it.textContent }

    /**
     * Whether the value the build makes of [raw] starts and ends with a space. The resource
     * compiler trims unquoted spaces at the edges of a string; a quoted value keeps them.
     */
    private fun edgeSpaces(raw: String): Pair<Boolean, Boolean> {
        val trimmed = raw.trim()
        val quoted = trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"'
        val value = if (quoted) trimmed.substring(1, trimmed.length - 1) else trimmed
        return (value.firstOrNull()?.isWhitespace() == true) to (value.lastOrNull()?.isWhitespace() == true)
    }

    /** Whether [text] has a letter outside its format specifiers: `%1$s: %2$s` has none. */
    private fun hasWords(text: String): Boolean = text.replace(FORMAT_SPECIFIER, "").any(Char::isLetter)

    private fun keyOf(element: Element): String = "${element.tagName}/${element.getAttribute("name")}"

    private fun entries(folder: File): List<Element> = folder.listFiles().orEmpty()
        .filter { it.isFile && it.extension == "xml" }
        .flatMap { file ->
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            List(root.childNodes.length) { root.childNodes.item(it) }
                .filterIsInstance<Element>()
                .filter { it.tagName in TRANSLATABLE_TAGS }
        }

    private fun localeFilters(): Set<String> {
        val script = File(moduleRoot("app"), "build.gradle.kts").readText()
        val list =
            checkNotNull(LOCALE_FILTERS.find(script)) { "no localeFilters += listOf(...) in app/build.gradle.kts" }
        return QUOTED.findAll(list.groupValues[1]).map { it.groupValues[1] }.toSet()
    }

    // ─── The lists ───────────────────────────────────────────────────────────

    private fun untranslatedList(language: String): File = File(listDirectory(), "untranslated-$language.txt")

    private fun identicalList(language: String): File = File(listDirectory(), "identical-$language.txt")

    private fun listDirectory(): File = File(ProductionSources.moduleDirectory().parentFile, "config/l10n")

    /** `module:kind/name` lines; `#` starts a comment; a missing file is an empty list. */
    private fun listEntries(file: File): Set<String> {
        if (!file.isFile) return emptySet()
        return file.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private companion object {
        const val DEFAULT_LANGUAGE = "en"
        const val MIN_KEYS_PER_MODULE = 500
        val MODULES = listOf("app", "catalog")
        val TRANSLATABLE_TAGS = setOf("string", "plurals", "string-array")
        val LANGUAGE_FOLDER = Regex("values-[a-z]{2,3}(-r[A-Z]{2})?")
        val LOCALE_FILTERS = Regex("""localeFilters\s*\+=\s*listOf\(([^)]*)\)""")
        val LANGUAGE_SPLIT_OFF = Regex("""bundle\s*\{\s*language\s*\{\s*enableSplit\s*=\s*false\s*}""")
        val QUOTED = Regex(""""([^"]+)"""")
        val FORMAT_SPECIFIER = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]""")
    }
}
