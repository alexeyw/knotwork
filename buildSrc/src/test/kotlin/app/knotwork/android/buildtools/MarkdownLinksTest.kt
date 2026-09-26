package app.knotwork.android.buildtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MarkdownLinks].
 *
 * They pin the three things the link gate depends on and that are easy to get
 * subtly wrong: which constructs count as links, which do not (code, comments),
 * and the exact slug GitHub generates for a heading. Run with
 * `./gradlew -p buildSrc test`.
 */
class MarkdownLinksTest {

    @Test
    fun `given inline links when read then targets and lines are reported`() {
        val markdown = """
            # Title

            See [the guide](docs/user-guide.md) and ![shot](docs/images/a.png).
            Also [an anchor](#title).
        """.trimIndent()

        assertEquals(
            listOf(
                MarkdownLinks.Link("docs/user-guide.md", 3),
                MarkdownLinks.Link("docs/images/a.png", 3),
                MarkdownLinks.Link("#title", 4),
            ),
            MarkdownLinks.linksOf(markdown),
        )
    }

    @Test
    fun `given a reference definition and an autolink when read then both are targets`() {
        val markdown = """
            Text with <https://example.com/x> inline.

            [0.8.0]: https://example.com/compare/v0.7.3...v0.8.0
        """.trimIndent()

        assertEquals(
            listOf("https://example.com/x", "https://example.com/compare/v0.7.3...v0.8.0"),
            MarkdownLinks.linksOf(markdown).map { it.target },
        )
    }

    @Test
    fun `given a link inside a fenced block when read then it is ignored`() {
        val markdown = """
            Before [real](docs/a.md).

            ```markdown
            [sample](docs/does-not-exist.md)
            ```

            After.
        """.trimIndent()

        assertEquals(listOf("docs/a.md"), MarkdownLinks.linksOf(markdown).map { it.target })
    }

    @Test
    fun `given a tilde fence when read then its contents are ignored`() {
        val markdown = "~~~\n[sample](nope.md)\n~~~\n[real](yes.md)\n"

        assertEquals(listOf("yes.md"), MarkdownLinks.linksOf(markdown).map { it.target })
    }

    @Test
    fun `given a link inside a code span when read then it is ignored`() {
        val markdown = "Write `[label](target.md)` like this, then [real](yes.md).\n"

        assertEquals(listOf("yes.md"), MarkdownLinks.linksOf(markdown).map { it.target })
    }

    @Test
    fun `given a stray backtick run before a code span holding a link when read then the span is still masked`() {
        val links = MarkdownLinks.linksOf("a ``stray `[x](gone.md)` and [y](real.md)\n")

        assertEquals(listOf("real.md"), links.map { it.target })
    }

    @Test
    fun `given a link inside an HTML comment when read then it is ignored`() {
        val markdown = "<!-- [hidden](nope.md)\nstill hidden [x](nope2.md) -->\n[real](yes.md)\n"

        assertEquals(listOf("yes.md"), MarkdownLinks.linksOf(markdown).map { it.target })
    }

    @Test
    fun `given link text holding brackets when read then the target is still found`() {
        val markdown = "See [the `[Unreleased]` section](CHANGELOG.md#unreleased).\n"

        assertEquals(listOf("CHANGELOG.md#unreleased"), MarkdownLinks.linksOf(markdown).map { it.target })
    }

    @Test
    fun `given a destination with a title or angle brackets when read then only the target is taken`() {
        val markdown = """
            [a](docs/a.md "A title")
            [b](<docs/b file.md>)
            [c](https://example.com/x_(y))
        """.trimIndent()

        assertEquals(
            listOf("docs/a.md", "docs/b file.md", "https://example.com/x_(y)"),
            MarkdownLinks.linksOf(markdown).map { it.target },
        )
    }

    @Test
    fun `given headings when anchors are read then GitHub slugs are produced`() {
        val markdown = """
            # Static Analysis & Coverage — Permanent Rules

            ## `./gradlew check`

            ### Adding an intentional suppression
        """.trimIndent()

        assertEquals(
            setOf(
                "static-analysis--coverage--permanent-rules",
                "gradlew-check",
                "adding-an-intentional-suppression",
            ),
            MarkdownLinks.anchorsOf(markdown),
        )
    }

    @Test
    fun `given punctuation between words when slugified then each space keeps its own hyphen`() {
        // GitHub strips punctuation in place and then replaces every remaining
        // space, so the two spaces around the `+` survive as two hyphens.
        assertEquals(
            "type-resolution-gate-detektfulldebug--detektfossdebug",
            MarkdownLinks.slug("Type-resolution gate (`detektFullDebug` + `detektFossDebug`)"),
        )
    }

    @Test
    fun `given repeated headings when anchors are read then later ones are numbered`() {
        val markdown = "## Notes\n\n## Notes\n\n## Notes\n"

        assertEquals(setOf("notes", "notes-1", "notes-2"), MarkdownLinks.anchorsOf(markdown))
    }

    @Test
    fun `given an explicit HTML anchor when anchors are read then it is included`() {
        val markdown = "<a id=\"hand-written\"></a>\n\n## Real heading\n"

        assertTrue(MarkdownLinks.anchorsOf(markdown).contains("hand-written"))
    }

    @Test
    fun `given a heading inside a fence when anchors are read then it is ignored`() {
        val markdown = "```\n## Not a heading\n```\n\n## A heading\n"

        assertEquals(setOf("a-heading"), MarkdownLinks.anchorsOf(markdown))
    }

    @Test
    fun `given a heading holding a link when slugified then only its text counts`() {
        assertEquals("see-the-guide", MarkdownLinks.slug("See [the guide](docs/user-guide.md)"))
    }

    @Test
    fun `given repeated headings when slugs are counted then the base is counted once per heading`() {
        val markdown = "## Notes\n\n## Notes\n\n## Notes\n"

        assertEquals(mapOf("notes" to 3), MarkdownLinks.headingSlugCounts(markdown))
    }

    @Test
    fun `given distinct headings when slugs are counted then each is counted once`() {
        val markdown = "# Guide\n\n## Triggers\n\n### Background & triggers\n"

        assertEquals(
            mapOf("guide" to 1, "triggers" to 1, "background--triggers" to 1),
            MarkdownLinks.headingSlugCounts(markdown),
        )
    }

    @Test
    fun `given an explicit HTML anchor when slugs are counted then it is not counted`() {
        // Hand-written anchors never carry an ordinal suffix, so they cannot
        // produce the drift the count exists to detect.
        val markdown = "<a id=\"hand-written\"></a>\n\n## Real heading\n"

        assertEquals(mapOf("real-heading" to 1), MarkdownLinks.headingSlugCounts(markdown))
    }

    @Test
    fun `given a heading inside a fence when slugs are counted then it is ignored`() {
        val markdown = "```\n## Not a heading\n```\n\n## A heading\n"

        assertEquals(mapOf("a-heading" to 1), MarkdownLinks.headingSlugCounts(markdown))
    }

    @Test
    fun `given code spans when read then their text and lines are reported`() {
        val markdown = "Use `a/b.kt` and ``c`d.kt``.\n\nThen ` e/f.md `.\n"

        assertEquals(
            listOf(
                MarkdownLinks.CodeSpan("a/b.kt", 1),
                MarkdownLinks.CodeSpan("c`d.kt", 1),
                MarkdownLinks.CodeSpan("e/f.md", 3),
            ),
            MarkdownLinks.codeSpansOf(markdown),
        )
    }

    @Test
    fun `given code inside a fence or a comment when spans are read then it is ignored`() {
        val markdown = "```\n`a/b.kt`\n```\n<!-- `c/d.kt` -->\n~~~\n`e/f.kt`\n~~~\n"

        assertTrue(MarkdownLinks.codeSpansOf(markdown).isEmpty())
    }

    @Test
    fun `given an unclosed backtick run when spans are read then it is literal and later spans are still read`() {
        assertEquals(
            listOf(MarkdownLinks.CodeSpan("x/y.kt", 1)),
            MarkdownLinks.codeSpansOf("a ``stray `x/y.kt` end\n"),
        )
    }
}
