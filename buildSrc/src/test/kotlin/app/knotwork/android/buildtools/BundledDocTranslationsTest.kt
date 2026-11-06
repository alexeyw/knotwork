package app.knotwork.android.buildtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BundledDocTranslations].
 *
 * Each rule is exercised by a translation written to break exactly that rule,
 * against a small English pair that passes [BundledDocs.violationsOf].
 */
class BundledDocTranslationsTest {

    // ── A translation that holds ─────────────────────────────────

    @Test
    fun `given current translations with every English anchor when checked then nothing is reported`() {
        assertEquals(emptyList<String>(), violations())
    }

    @Test
    fun `given a translation when the index is rendered then English anchors point at the translated headings`() {
        val translation = troubleshootingTranslation()
        val index = BundledDocTranslations.renderIndex("ru", translations(TROUBLESHOOTING to translation))

        assertTrue(index, index.contains(""""asset": "docs/ru/troubleshooting.md""""))
        assertTrue(index, index.contains(""""path": "docs/troubleshooting.md""""))
        assertTrue(index, index.contains(""""a-problem": ${translation.indexOf("## Проблема")}"""))
        assertTrue(index, index.contains(""""#a-problem": {"kind": "anchor", "anchor": "a-problem"}"""))
    }

    @Test
    fun `given a marker with no heading after it when anchors are read then it maps nothing`() {
        val offsets = BundledDocTranslations.anchorOffsets("<!-- en: lost -->\n\ntext\n<!-- en: kept -->\n## Здесь\n")

        assertEquals(mapOf("kept" to "<!-- en: lost -->\n\ntext\n<!-- en: kept -->\n".length), offsets)
    }

    // ── Staleness ────────────────────────────────────────────────

    @Test
    fun `given English changed since the translation when checked then the new first line is named`() {
        val changed = ENGLISH_TROUBLESHOOTING.replace("text", "new text")

        val violations = violations(documents = documents(TROUBLESHOOTING to changed))

        assertSingle(violations, "`docs/troubleshooting.md` changed since `docs/ru/troubleshooting.md`")
        assertTrue(violations.single().contains(BundledDocTranslations.sourceLineFor(TROUBLESHOOTING, changed)))
    }

    @Test
    fun `given a translation without its source line when checked then it is refused`() {
        val translation = troubleshootingTranslation().substringAfter('\n')

        assertSingle(violations(TROUBLESHOOTING to translation), "does not name the English it translates")
    }

    @Test
    fun `given a source line naming another document when checked then it is refused`() {
        val translation = troubleshootingTranslation().replaceFirst(TROUBLESHOOTING, FAQ)

        assertSingle(violations(TROUBLESHOOTING to translation), "names `docs/faq.md` as its English")
    }

    // ── Anchors ──────────────────────────────────────────────────

    @Test
    fun `given a heading without its English anchor when checked then it is refused`() {
        val translation = troubleshootingTranslation().replace("<!-- en: a-problem -->\n", "")

        val violations = violations(TROUBLESHOOTING to translation)

        assertTrue(violations.toString(), violations.any { it.contains("is a heading without its English anchor") })
        assertTrue(violations.toString(), violations.any { it.contains("no heading for the English anchors: [a-") })
    }

    @Test
    fun `given a marker followed by text when checked then it is refused`() {
        val translation = troubleshootingTranslation() + "\n<!-- en: a-problem -->\nnot a heading\n"

        val violations = violations(TROUBLESHOOTING to translation)

        assertTrue(violations.toString(), violations.any { it.contains("marks `#a-problem`, but the next line") })
    }

    @Test
    fun `given an anchor the English does not have when checked then it is refused`() {
        val translation = troubleshootingTranslation().replace("<!-- en: a-problem -->", "<!-- en: a-problme -->")

        val violations = violations(TROUBLESHOOTING to translation)

        assertTrue(violations.toString(), violations.any { it.contains("marks anchors the English does not have") })
    }

    @Test
    fun `given an anchor marked twice when checked then it is refused`() {
        val translation = troubleshootingTranslation().replace("<!-- en: contents -->", "<!-- en: a-problem -->")

        val violations = violations(TROUBLESHOOTING to translation)

        assertTrue(violations.toString(), violations.any { it.contains("more than once: [a-problem]") })
    }

    @Test
    fun `given the English sections in another order when checked then it is refused`() {
        val translation = source(TROUBLESHOOTING, ENGLISH_TROUBLESHOOTING) +
            "<!-- en: troubleshooting -->\n# Неполадки\n\n<!-- en: a-problem -->\n## Проблема\n\nтекст\n\n" +
            "<!-- en: contents -->\n## Содержание\n\n- [Проблема](#a-problem)\n"

        assertSingle(violations(TROUBLESHOOTING to translation), "has the English sections in another order")
    }

    // ── Links and constructs ─────────────────────────────────────

    @Test
    fun `given a link to a translated heading's own slug when checked then it is refused`() {
        val translation = troubleshootingTranslation().replace("](#a-problem)", "](#проблема)")

        assertSingle(violations(TROUBLESHOOTING to translation), "produces `#проблема`")
    }

    @Test
    fun `given a link to another document's English anchor when checked then it resolves`() {
        val translation = troubleshootingTranslation() + "\nСм. [вопросы](faq.md#a-question).\n"

        assertEquals(emptyList<String>(), violations(TROUBLESHOOTING to translation))
    }

    @Test
    fun `given an image in a translation when checked then it is refused`() {
        val translation = troubleshootingTranslation() + "\n![схема](https://example.com/scheme.png)\n"

        assertSingle(violations(TROUBLESHOOTING to translation), "embeds an image")
    }

    @Test
    fun `given a bundled document without a translation when checked then it is reported`() {
        val violations = BundledDocTranslations.violationsOf(
            "ru",
            mapOf(TROUBLESHOOTING to troubleshootingTranslation()),
            documents(),
        )

        assertSingle(violations, "`docs/faq.md` is bundled but has no `ru` translation at `docs/ru/faq.md`")
    }

    @Test
    fun `given a translation of a document that is not bundled when checked then it is reported`() {
        val violations = violations("docs/user-guide.md" to "# Руководство\n")

        assertSingle(violations, "`docs/ru/user-guide.md` translates `docs/user-guide.md`, which is not a bundled")
    }

    // ── Fixtures ─────────────────────────────────────────────────

    private fun violations(
        vararg overrides: Pair<String, String>,
        documents: Map<String, String> = documents(),
    ): List<String> = BundledDocTranslations.violationsOf("ru", translations(*overrides), documents)

    /** The English documents, every bundled one present. */
    private fun documents(vararg overrides: Pair<String, String>): Map<String, String> {
        val documents = BundledDocs.bundledDocuments().associate { it.path to "# ${it.id}\n" }.toMutableMap()
        documents[TROUBLESHOOTING] = ENGLISH_TROUBLESHOOTING
        documents[FAQ] = ENGLISH_FAQ
        overrides.forEach { (path, markdown) -> documents[path] = markdown }
        return documents
    }

    /** Current translations of both bundled documents, plus [overrides]. */
    private fun translations(vararg overrides: Pair<String, String>): Map<String, String> {
        val translations = mutableMapOf(TROUBLESHOOTING to troubleshootingTranslation(), FAQ to faqTranslation())
        overrides.forEach { (path, markdown) -> translations[path] = markdown }
        return translations
    }

    private fun source(path: String, english: String) = BundledDocTranslations.sourceLineFor(path, english) + "\n"

    private fun troubleshootingTranslation(): String = source(TROUBLESHOOTING, ENGLISH_TROUBLESHOOTING) +
        "<!-- en: troubleshooting -->\n# Неполадки\n\n<!-- en: contents -->\n## Содержание\n\n" +
        "- [Проблема](#a-problem)\n\n<!-- en: a-problem -->\n## Проблема\n\nтекст\n"

    private fun faqTranslation(): String = source(FAQ, ENGLISH_FAQ) +
        "<!-- en: faq -->\n# Вопросы\n\n<!-- en: a-question -->\n## Вопрос\n\nответ\n"

    private fun assertSingle(violations: List<String>, fragment: String) {
        assertEquals("Expected exactly one violation, got $violations", 1, violations.size)
        assertTrue("Violation did not mention '$fragment': ${violations.single()}", fragment in violations.single())
    }

    private companion object {
        const val FAQ = "docs/faq.md"
        const val TROUBLESHOOTING = "docs/troubleshooting.md"
        const val ENGLISH_TROUBLESHOOTING =
            "# Troubleshooting\n\n## Contents\n\n- [A problem](#a-problem)\n\n## A problem\n\ntext\n"
        const val ENGLISH_FAQ = "# FAQ\n\n## A question\n\nanswer\n"
    }
}
