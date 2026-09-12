package app.knotwork.android.buildtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DocumentationLinkRegistry].
 *
 * Each rule is exercised against a document written to break exactly that rule.
 * Asserting only that today's [DocumentationLinkRegistry.ENTRIES] resolves would
 * prove the registry is currently valid — which is the one thing the gate in
 * `check` already guarantees — and nothing about what the gate would catch.
 */
class DocumentationLinkRegistryTest {

    @Test
    fun `given a resolvable entry when checked then there are no violations`() {
        val violations = DocumentationLinkRegistry.violationsOf(
            entries = listOf(entry(anchor = "adding-an-mcp-server")),
            documents = mapOf(GUIDE to "# Guide\n\n## Adding an MCP server\n\ntext\n"),
        )
        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `given an entry with no anchor when checked then only the document has to exist`() {
        val violations = DocumentationLinkRegistry.violationsOf(
            entries = listOf(entry(anchor = null)),
            documents = mapOf(GUIDE to "# Guide\n"),
        )
        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `given a path outside docs when checked then it is refused`() {
        // The rule that keeps the gate's declared inputs complete: a target
        // anywhere else could be deleted without changing the task's
        // fingerprint, and the gate would report a cached pass over it.
        val violations = DocumentationLinkRegistry.violationsOf(
            entries = listOf(DocumentationLinkRegistry.Entry(id = "readme", path = "README.md", anchor = null)),
            documents = mapOf("README.md" to "# Readme\n"),
        )
        assertSingleViolationMentioning(violations, "not a `docs/*.md` document")
    }

    @Test
    fun `given a path that is not markdown when checked then it is refused`() {
        val violations = DocumentationLinkRegistry.violationsOf(
            entries = listOf(DocumentationLinkRegistry.Entry(id = "img", path = "docs/images/a.png", anchor = null)),
            documents = mapOf("docs/images/a.png" to ""),
        )
        assertSingleViolationMentioning(violations, "not a `docs/*.md` document")
    }

    @Test
    fun `given a missing document when checked then it is reported`() {
        val violations = DocumentationLinkRegistry.violationsOf(
            entries = listOf(entry(anchor = null)),
            documents = emptyMap(),
        )
        assertSingleViolationMentioning(violations, "does not exist")
    }

    @Test
    fun `given an anchor no heading produces when checked then it is reported`() {
        val violations = DocumentationLinkRegistry.violationsOf(
            entries = listOf(entry(anchor = "renamed-section")),
            documents = mapOf(GUIDE to "# Guide\n\n## The original section\n"),
        )
        assertSingleViolationMentioning(violations, "which no heading produces")
    }

    @Test
    fun `given two headings with the same text when the base anchor is used then it is refused`() {
        // The defect the "anchor exists" rule cannot see: both `background--triggers`
        // and `background--triggers-1` resolve forever, and reordering the two
        // sections swaps what each points at without breaking either.
        val violations = DocumentationLinkRegistry.violationsOf(
            entries = listOf(entry(anchor = "background--triggers")),
            documents = mapOf(GUIDE to TWICE_WRITTEN_HEADING),
        )
        assertSingleViolationMentioning(violations, "2 headings carry that text")
    }

    @Test
    fun `given an ordinal anchor when checked then it is refused`() {
        val violations = DocumentationLinkRegistry.violationsOf(
            entries = listOf(entry(anchor = "background--triggers-1")),
            documents = mapOf(GUIDE to TWICE_WRITTEN_HEADING),
        )
        assertSingleViolationMentioning(violations, "an ordinal anchor")
    }

    @Test
    fun `given a heading that ends in a number when checked then it is not mistaken for an ordinal`() {
        // `step-1` from a heading "Step 1" is indistinguishable from an ordinal
        // suffix by shape alone. It is a legitimate anchor and must resolve.
        val violations = DocumentationLinkRegistry.violationsOf(
            entries = listOf(entry(anchor = "step-1")),
            documents = mapOf(GUIDE to "# Guide\n\n## Step 1\n\ntext\n"),
        )
        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `given an id that is not a clean slug when checked then it is refused`() {
        // The id becomes a Kotlin constant name. An unexpected character does
        // not yield a bad link — it yields a generated file that will not
        // compile, and a syntax error in generated code is a far worse report
        // than this one.
        val violations = DocumentationLinkRegistry.violationsOf(
            entries = listOf(DocumentationLinkRegistry.Entry(id = "user.guide", path = GUIDE, anchor = null)),
            documents = mapOf(GUIDE to "# Guide\n"),
        )
        assertSingleViolationMentioning(violations, "not a valid id")
    }

    @Test
    fun `given a path holding a comment opener when checked then it is refused`() {
        // `/` followed by `*` inside the generated KDoc opens a nested block
        // comment, and Kotlin's comments nest — the generated file would not
        // compile, from a path that merely looks odd.
        val violations = DocumentationLinkRegistry.violationsOf(
            entries = listOf(DocumentationLinkRegistry.Entry(id = "guide", path = "docs/*wild.md", anchor = null)),
            documents = mapOf("docs/*wild.md" to "# Guide\n"),
        )
        assertSingleViolationMentioning(violations, "not a plain document path")
    }

    @Test
    fun `given a duplicated id when checked then it is reported`() {
        val violations = DocumentationLinkRegistry.violationsOf(
            entries = listOf(entry(anchor = null), entry(anchor = null)),
            documents = mapOf(GUIDE to "# Guide\n"),
        )
        assertTrue("Expected a duplicate-id violation, got $violations", violations.any { "declared 2 times" in it })
    }

    @Test
    fun `given the registry when rendered then it declares one constant and one entry per document`() {
        val source = DocumentationLinkRegistry.render(PACKAGE, renderDocuments())

        assertTrue("Generated file must declare its package", source.startsWith("package $PACKAGE\n"))
        assertTrue("Generated file must warn against hand edits", "DO NOT EDIT BY HAND" in source)
        DocumentationLinkRegistry.ENTRIES.forEach { entry ->
            val constant = "ID_" + entry.id.uppercase().replace('-', '_')
            assertTrue(
                "Generated file must declare $constant",
                "const val $constant: String = \"${entry.id}\"" in source,
            )
            assertTrue(
                "Generated file must list ${entry.id}",
                "id = $constant,\n            path = \"${entry.path}\"," in source,
            )
            assertTrue(
                "Generated file must carry ${entry.id}'s delivery",
                "delivery = Delivery.${entry.delivery.name}," in source,
            )
        }
    }

    @Test
    fun `given the registry when rendered twice then the output is identical`() {
        // The verification task compares a fresh render against the committed
        // file, so a render that varied between runs would fail `check` at
        // random rather than on a real change.
        assertEquals(
            DocumentationLinkRegistry.render(PACKAGE, renderDocuments()),
            DocumentationLinkRegistry.render(PACKAGE, renderDocuments()),
        )
    }

    @Test
    fun `given the registry when read then every id is unique`() {
        val ids = DocumentationLinkRegistry.ENTRIES.map { it.id }
        assertEquals("Entry ids must be unique", ids.distinct(), ids)
    }

    @Test
    fun `given one document delivered two ways when checked then it is refused`() {
        // Delivery belongs to the document. `mcp-setup` and `user-guide` name
        // the same file, and if one said BUNDLED the reader would open a copy
        // the sync task was never told to produce.
        val violations = DocumentationLinkRegistry.violationsOf(
            entries = listOf(
                DocumentationLinkRegistry.Entry(id = "guide", path = GUIDE, anchor = null),
                DocumentationLinkRegistry.Entry(
                    id = "guide-section",
                    path = GUIDE,
                    anchor = null,
                    delivery = DocumentationLinkRegistry.Delivery.BUNDLED,
                ),
            ),
            documents = mapOf(GUIDE to "# Guide\n"),
        )
        assertSingleViolationMentioning(violations, "Delivery is a property of the document")
    }

    @Test
    fun `given the registry when read then every bundled entry names a whole document`() {
        // A section entry cannot be bundled on its own: the reader opens whole
        // documents, and an anchor-only bundled entry would name a copy that
        // the sync task never produces.
        val bundledSections = DocumentationLinkRegistry.ENTRIES.filter {
            it.delivery == DocumentationLinkRegistry.Delivery.BUNDLED && it.anchor != null
        }
        assertEquals(emptyList<DocumentationLinkRegistry.Entry>(), bundledSections)
    }

    /**
     * Builds a single-entry fixture targeting [GUIDE].
     *
     * @param anchor The anchor under test, or `null` for a whole-document entry.
     * @return The entry.
     */
    private fun entry(anchor: String?): DocumentationLinkRegistry.Entry =
        DocumentationLinkRegistry.Entry(id = "guide", path = GUIDE, anchor = anchor)

    /**
     * Supplies a body for every document the registry names.
     *
     * [DocumentationLinkRegistry.render] reads each entry's document for the
     * per-document statistics it writes, so a render test has to hand it one.
     *
     * @return Repository-relative path to a minimal body.
     */
    private fun renderDocuments(): Map<String, String> =
        DocumentationLinkRegistry.ENTRIES.associate { it.path to "# ${it.id}\n\n## A section\n\ntext\n" }

    /**
     * Asserts exactly one violation was reported, and that it says why.
     *
     * @param violations The reported violations.
     * @param fragment Text the message must carry.
     */
    private fun assertSingleViolationMentioning(violations: List<String>, fragment: String) {
        assertEquals("Expected exactly one violation, got $violations", 1, violations.size)
        assertTrue("Violation must mention '$fragment', was '${violations.first()}'", fragment in violations.first())
    }

    private companion object {
        /** Path of the fixture document every entry under test targets. */
        const val GUIDE = "docs/user-guide.md"

        /** Package the render tests generate into. */
        const val PACKAGE = "app.knotwork.android.domain.constants"

        /** A guide whose "Background & triggers" heading is written twice. */
        const val TWICE_WRITTEN_HEADING =
            "# Guide\n\n### Background & triggers\n\na\n\n#### Background & triggers\n\nb\n"
    }
}
