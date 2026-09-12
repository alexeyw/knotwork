package app.knotwork.android.buildtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BundledDocs].
 *
 * Every rule is exercised against a document written to break exactly that
 * rule. Asserting that today's bundled documents pass would prove only that
 * they currently pass — which the gate in `check` already guarantees — and
 * nothing about what the gate would catch tomorrow.
 *
 * The resolution tests matter more than usual here: the app does not resolve
 * links at all, it reads the verdicts this object writes. A wrong verdict is
 * not a build failure, it is a link that quietly opens the wrong thing.
 */
class BundledDocsTest {

    // ── Link resolution ──────────────────────────────────────────

    @Test
    fun `given a bare anchor when resolved then it stays in the same document`() {
        val target = BundledDocs.resolve(FAQ, "#known-limitations")
        assertEquals(BundledDocs.Target.SameDocument("known-limitations"), target)
    }

    @Test
    fun `given a link to the document itself when resolved then it returns to its top`() {
        val target = BundledDocs.resolve(FAQ, "faq.md")
        assertEquals(BundledDocs.Target.SameDocument(null), target)
    }

    @Test
    fun `given a sibling bundled document when resolved then it names that document`() {
        val target = BundledDocs.resolve(FAQ, "troubleshooting.md#a-trigger-didnt-fire")
        assertEquals(
            BundledDocs.Target.Bundled("troubleshooting", "a-trigger-didnt-fire"),
            target,
        )
    }

    @Test
    fun `given a remote document when resolved then it stays a repository path`() {
        val target = BundledDocs.resolve(FAQ, "user-guide.md#triggers")
        assertEquals(
            BundledDocs.Target.Repository("docs/user-guide.md", "triggers"),
            target,
        )
    }

    @Test
    fun `given a link out of the docs directory when resolved then the root file is addressed`() {
        // The case the consistency sweep singled out: `faq.md` links to three
        // files that live outside `docs/`, so a rule written only for registry
        // paths would have missed all of them.
        val target = BundledDocs.resolve(FAQ, "../SECURITY.md#api-keys-for-cloud-providers")
        assertEquals(
            BundledDocs.Target.Repository("SECURITY.md", "api-keys-for-cloud-providers"),
            target,
        )
    }

    @Test
    fun `given an absolute url when resolved then it is not this scheme's business`() {
        assertNull(BundledDocs.resolve(FAQ, "https://example.com/x"))
        assertNull(BundledDocs.resolve(FAQ, "mailto:someone@example.com"))
    }

    @Test
    fun `given a reference climbing out of the repository when resolved then it is refused`() {
        assertNull(BundledDocs.resolve(FAQ, "../../etc/passwd"))
    }

    @Test
    fun `given an absolute url when collecting links then it is left out of the index`() {
        // The reader hands a URL straight to the browser, so an entry for it
        // would be a row that never gets read.
        val links = BundledDocs.resolvedLinksOf(FAQ, "[a](https://example.com) [b](#top)\n\n# Top\n")
        assertEquals(setOf("#top"), links.keys)
    }

    // ── Renderability rules ──────────────────────────────────────

    @Test
    fun `given a mermaid block in a bundled document when checked then it is refused`() {
        val violations = BundledDocs.violationsOf(
            documentsWith(TROUBLESHOOTING to "# T\n\n```mermaid\nflowchart TD\n  A --> B\n```\n"),
        )
        assertSingleViolationMentioning(violations, "opens a Mermaid block")
    }

    @Test
    fun `given an image in a bundled document when checked then it is refused`() {
        val violations = BundledDocs.violationsOf(
            documentsWith(TROUBLESHOOTING to "# T\n\n![a screenshot](images/a.png)\n"),
        )
        // Two violations, not one: an image is also a link destination, so the
        // missing file is reported as well. Both are true, and the image rule
        // is the one that explains why the document cannot ship.
        assertTrue(
            "Expected an image violation, got $violations",
            violations.any { it.contains("embeds an image") },
        )
    }

    @Test
    fun `given an html tag in a bundled document when checked then it is refused`() {
        // The rule that keeps `cookbook.md` out: its generated tables carry 14
        // `<br>` tags, each welding a caption onto a code sample when dropped.
        val violations = BundledDocs.violationsOf(
            documentsWith(TROUBLESHOOTING to "# T\n\nfirst<br>second\n"),
        )
        assertSingleViolationMentioning(violations, "HTML tag `<br>`")
    }

    @Test
    fun `given a mermaid fence quoted inside another block when checked then it is not reported`() {
        // Documentation about diagrams shows the syntax. Reporting a quoted
        // fence would fail a document that renders perfectly, and a gate that
        // fails valid documents teaches everyone to distrust it.
        val markdown = "# T\n\n````\n```mermaid\nflowchart TD\n  A --> B\n```\n````\n"
        assertEquals(emptyList<Int>(), MarkdownLinks.mermaidBlockLines(markdown))
        assertEquals(emptyList<String>(), BundledDocs.violationsOf(documentsWith(TROUBLESHOOTING to markdown)))
    }

    @Test
    fun `given a mermaid block after a closed code block when checked then it is still found`() {
        // The other half of the same rule: tracking fence state must not lose
        // the diagram that follows an ordinary code block.
        val markdown = "# T\n\n```\nplain\n```\n\n```mermaid\nflowchart TD\n  A --> B\n```\n"
        assertEquals(listOf(7), MarkdownLinks.mermaidBlockLines(markdown))
    }

    @Test
    fun `given a fenced example of html when checked then it is not mistaken for html`() {
        // Documentation quotes tags constantly. A gate that failed on a quoted
        // tag would teach everyone to distrust it.
        val violations = BundledDocs.violationsOf(
            documentsWith(TROUBLESHOOTING to "# T\n\n```html\n<br>\n```\n\nand `<br>` inline.\n"),
        )
        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `given an autolink when checked then it is not mistaken for html`() {
        val violations = BundledDocs.violationsOf(
            documentsWith(TROUBLESHOOTING to "# T\n\nSee <https://example.com> for more.\n"),
        )
        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `given a link to a missing anchor when checked then it is refused`() {
        val violations = BundledDocs.violationsOf(
            documentsWith(TROUBLESHOOTING to "# T\n\n[go](#nowhere)\n"),
        )
        assertSingleViolationMentioning(violations, "no heading in `docs/troubleshooting.md` produces `#nowhere`")
    }

    @Test
    fun `given a link to a missing document when checked then it is refused`() {
        val violations = BundledDocs.violationsOf(
            documentsWith(TROUBLESHOOTING to "# T\n\n[go](../NOPE.md)\n"),
        )
        assertSingleViolationMentioning(violations, "`NOPE.md` does not exist")
    }

    @Test
    fun `given a link to a duplicated heading when checked then it is refused`() {
        // Two headings spelled alike yield `slug` and `slug-1`, and both keep
        // resolving after the sections are reordered — pointing somewhere else.
        val violations = BundledDocs.violationsOf(
            documentsWith(TROUBLESHOOTING to "# T\n\n[go](#repeated)\n\n## Repeated\n\n## Repeated\n"),
        )
        assertSingleViolationMentioning(violations, "2 headings in `docs/troubleshooting.md` carry that text")
    }

    @Test
    fun `given a bundled document that does not exist when checked then it is reported`() {
        val violations = BundledDocs.violationsOf(emptyMap())
        assertTrue(
            "Expected one violation per bundled document, got $violations",
            violations.size == BundledDocs.bundledDocuments().size,
        )
        assertTrue(violations.all { it.contains("is bundled but") })
    }

    // ── The index ────────────────────────────────────────────────

    @Test
    fun `given a document when the index is rendered then anchors carry their line's offset`() {
        val markdown = "# Title\n\nsome text\n\n## A section\n\nmore\n"
        val index = BundledDocs.renderIndex(documentsWith(TROUBLESHOOTING to markdown, FAQ to "# F\n"))
        // "## A section" begins after "# Title\n\nsome text\n\n" — 20 characters.
        assertTrue("Index was:\n$index", index.contains(""""a-section": 20"""))
        assertEquals(markdown.indexOf("## A section"), 20)
    }

    @Test
    fun `given a fenced block above a heading when the index is rendered then the offset survives it`() {
        // The defect this guards: masking blanks a fenced block's lines, which
        // preserves the line count but not their lengths. An offset taken from
        // the masked text would drift by the width of every fence above it.
        val markdown = "# T\n\n```\na very long line indeed\n```\n\n## Later\n\ntext\n"
        val index = BundledDocs.renderIndex(documentsWith(TROUBLESHOOTING to markdown, FAQ to "# F\n"))
        assertTrue("Index was:\n$index", index.contains(""""later": ${markdown.indexOf("## Later")}"""))
    }

    @Test
    fun `given a document when the index is rendered then it is well-formed json`() {
        val index = BundledDocs.renderIndex(
            documentsWith(
                TROUBLESHOOTING to "# T\n\n[a](#t)\n\n[b](faq.md)\n\n[c](../SECURITY.md)\n",
                FAQ to "# F\n",
                "SECURITY.md" to "# S\n",
            ),
        )
        // Parsed rather than pattern-matched: the renderer hand-writes JSON, so
        // a missing comma is exactly the defect worth catching, and it is
        // invisible to `contains`.
        assertEquals("{", index.trim().first().toString())
        assertTrue(index.trimEnd().endsWith("}"))
        assertBalanced(index)
    }

    /**
     * Builds a document map with every bundled document present.
     *
     * @param overrides Documents to supply or replace.
     * @return A map holding each bundled document plus [overrides].
     */
    private fun documentsWith(vararg overrides: Pair<String, String>): Map<String, String> {
        val documents = BundledDocs.bundledDocuments().associate { it.path to "# ${it.id}\n" }.toMutableMap()
        overrides.forEach { (path, markdown) -> documents[path] = markdown }
        return documents
    }

    /**
     * Asserts that the braces, brackets and quotes of a JSON text balance.
     *
     * @param json The rendered text.
     */
    private fun assertBalanced(json: String) {
        var braces = 0
        var brackets = 0
        var quotes = 0
        var escaped = false
        var inString = false
        for (character in json) {
            when {
                escaped -> escaped = false
                character == '\\' && inString -> escaped = true
                character == '"' -> {
                    inString = !inString
                    quotes++
                }

                inString -> Unit
                character == '{' -> braces++
                character == '}' -> braces--
                character == '[' -> brackets++
                character == ']' -> brackets--
            }
            assertTrue("Unbalanced before:\n$json", braces >= 0 && brackets >= 0)
        }
        assertEquals("Braces unbalanced in:\n$json", 0, braces)
        assertEquals("Brackets unbalanced in:\n$json", 0, brackets)
        assertEquals("Odd number of quotes in:\n$json", 0, quotes % 2)
    }

    /**
     * Asserts exactly one violation was reported, mentioning [fragment].
     *
     * @param violations The reported violations.
     * @param fragment Text the violation must contain.
     */
    private fun assertSingleViolationMentioning(violations: List<String>, fragment: String) {
        assertEquals("Expected exactly one violation, got $violations", 1, violations.size)
        assertTrue(
            "Violation did not mention '$fragment': ${violations.single()}",
            violations.single().contains(fragment),
        )
    }

    private companion object {
        const val FAQ = "docs/faq.md"
        const val TROUBLESHOOTING = "docs/troubleshooting.md"
    }
}
