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
 * or is named, one by one, on that language's untranslated list — and the
 * languages hold together: every module translates the same ones, the build
 * ships exactly those, and a translation is a translation.
 *
 * **Why a test and not lint.** Lint's `MissingTranslation` is the end state, but
 * a language's translation lands over several changes, and with it on, the first
 * translated string fails the build for each of the ~2 600 that are not yet.
 * So while any language has a list, the check is off
 * (`lint { disable += "MissingTranslation" }` in `app` and `catalog`) and
 * `config/l10n/untranslated-<lang>.txt` lists what that language still misses.
 * This test holds every list honest in both directions:
 * - a string missing from a language and not on its list fails — a string added
 *   during the series is translated with it, not left behind; a language with no
 *   list must be complete, so a finished language stays finished meanwhile;
 * - a list entry that is translated by now, or names no string, fails — the list
 *   only shrinks.
 *
 * Lint's check is off exactly while a list exists: the change that deletes the
 * last list turns it back on (decisions §113, §114).
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
 * - Each language's `interface_language_tag` is that language: it is how the
 *   app learns which translation the screens show.
 * - Each language without an untranslated list has the bundled help documents
 *   translated, with their index; a language still on its list may not have them
 *   yet, and its screens show the English documents until it does.
 * - A translation keeps the spaces at the edges of its English text: the
 *   resource compiler trims them unless the value is quoted.
 * - A translation that equals its English text is a copy, not a translation,
 *   unless `config/l10n/identical-<lang>.txt` names it (brand names, units).
 *   Text without a letter (`%1$s · %2$s`) is not compared.
 *
 * Reads `app/src/main/res`, `catalog/src/main/res`, `config/l10n/` and both
 * modules' `build.gradle.kts`; all but the first are declared inputs of the test
 * task.
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
    fun `each language's strings name that language`() {
        // `interface_language_tag` tells the app which language the screens resolved to;
        // it picks the bundled presets' translation, so a copied English value would
        // show those presets in English under a translated interface.
        val tags = (shippedLanguages() + DEFAULT_LANGUAGE).associateWith { language ->
            val folder = if (language == DEFAULT_LANGUAGE) defaultFolder("app") else languageFolders("app")[language]
            folder?.let { textsIn(it)["string/interface_language_tag"] }
        }

        assertEquals(tags.keys.associateWith { it }, tags)
    }

    @Test
    fun `every string is translated into every shipped language or on its untranslated list`() {
        for (language in shippedLanguages()) {
            val missing = MODULES.flatMap { module ->
                val translated = languageFolders(module)[language]?.let(::keysIn).orEmpty()
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
    fun `every untranslated list belongs to a shipped language`() {
        val orphans = listDirectory().listFiles().orEmpty()
            .mapNotNull { UNTRANSLATED_LIST.matchEntire(it.name)?.groupValues?.get(1) }
            .filterNot { it in shippedLanguages() }

        assertTrue(
            "config/l10n has an untranslated list for a language the build does not ship — delete it: $orphans",
            orphans.isEmpty(),
        )
    }

    @Test
    fun `lint's MissingTranslation is off exactly while a language has an untranslated list`() {
        val listsExist = shippedLanguages().any { untranslatedList(it).isFile }

        for (module in MODULES) {
            val script = File(moduleRoot(module), "build.gradle.kts").readText()
            val message = if (listsExist) {
                "$module/build.gradle.kts must keep disable += \"MissingTranslation\" while a list in " +
                    "config/l10n names untranslated strings: each would fail lint"
            } else {
                "no language has an untranslated list any more — delete disable += \"MissingTranslation\" " +
                    "from $module/build.gradle.kts, so lint holds every string in every language again"
            }
            assertEquals(message, listsExist, MISSING_TRANSLATION_OFF.containsMatchIn(script))
        }
    }

    @Test
    fun `every finished language reads the bundled documents in that language`() {
        // `verifyBundledDocs` checks each translation it finds; this is what makes
        // finding one per language a requirement rather than a possibility, once
        // the language's interface is translated (its untranslated list is gone).
        val missing = shippedLanguages().filterNot { untranslatedList(it).isFile }.filterNot { language ->
            File(moduleRoot("app"), "src/main/assets/docs/$language/index.json").isFile
        }

        assertTrue(
            "no translated help documents for $missing — translate docs/faq.md and docs/troubleshooting.md into " +
                "app/src/main/assets/docs/<language>/ and run ./gradlew :app:syncBundledDocs",
            missing.isEmpty(),
        )
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
        val UNTRANSLATED_LIST = Regex("""untranslated-(.+)\.txt""")
        val MISSING_TRANSLATION_OFF = Regex("""(?m)^\s*disable\s*\+=\s*"MissingTranslation"""")
        val FORMAT_SPECIFIER = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]""")
    }
}
