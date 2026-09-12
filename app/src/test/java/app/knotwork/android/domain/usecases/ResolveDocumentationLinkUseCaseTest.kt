package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.BundledDocument
import app.knotwork.android.domain.models.DocumentationTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [ResolveDocumentationLinkUseCase].
 *
 * The use case is a lookup plus one rule, and the tests are shaped to say so:
 * what it must *not* do — invent a destination the build did not resolve — gets
 * as much coverage as what it must.
 */
class ResolveDocumentationLinkUseCaseTest {

    private val useCase = ResolveDocumentationLinkUseCase()

    @Test
    fun `given an anchor the build resolved when invoked then it stays in the document`() {
        val target = useCase(document(), "#known-limitations")
        assertEquals(DocumentationTarget.SameDocument("known-limitations"), target)
    }

    @Test
    fun `given a bundled sibling when invoked then it names that document`() {
        val target = useCase(document(), "troubleshooting.md#a-trigger-didnt-fire")
        assertEquals(DocumentationTarget.Bundled("troubleshooting", "a-trigger-didnt-fire"), target)
    }

    @Test
    fun `given a repository file when invoked then it keeps the path and not a url`() {
        // The path, not a URL: the ref a build links against, and the rule that
        // pins legal documents to the default branch, both live at the edge.
        val target = useCase(document(), "../SECURITY.md")
        assertEquals(DocumentationTarget.Repository("SECURITY.md", null), target)
    }

    @Test
    fun `given an absolute url when invoked then it goes to the browser as written`() {
        // The one rule the build does not express: absolute URLs carry no index
        // entry precisely because they need no resolving.
        val target = useCase(document(), "https://example.com/a?b=c#d")
        assertEquals(DocumentationTarget.External("https://example.com/a?b=c#d"), target)
    }

    @Test
    fun `given a mailto link when invoked then it goes to the browser as written`() {
        val target = useCase(document(), "mailto:someone@example.com")
        assertEquals(DocumentationTarget.External("mailto:someone@example.com"), target)
    }

    @Test
    fun `given a relative target the build did not resolve when invoked then nothing is invented`() {
        // `verifyBundledDocs` makes this unreachable: every relative link in a
        // bundled document resolves or the build fails. If it happens anyway,
        // the index and the document disagree — a build defect, not a case to
        // guess at. Guessing here would be the second path resolver this design
        // exists to avoid.
        assertNull(useCase(document(), "some-other-file.md"))
    }

    @Test
    fun `given a blank target when invoked then nothing happens`() {
        assertNull(useCase(document(), ""))
        assertNull(useCase(document(), "   "))
    }

    @Test
    fun `given a target with surrounding whitespace when invoked then it still resolves`() {
        assertEquals(
            DocumentationTarget.SameDocument("known-limitations"),
            useCase(document(), "  #known-limitations  "),
        )
    }

    /**
     * Builds a document whose link table mirrors the real `faq.md`.
     *
     * @return The fixture document.
     */
    private fun document(): BundledDocument = BundledDocument(
        id = "faq",
        path = "docs/faq.md",
        assetPath = "docs/faq.md",
        anchors = mapOf("known-limitations" to 1024),
        links = mapOf(
            "#known-limitations" to DocumentationTarget.SameDocument("known-limitations"),
            "troubleshooting.md#a-trigger-didnt-fire" to
                DocumentationTarget.Bundled("troubleshooting", "a-trigger-didnt-fire"),
            "../SECURITY.md" to DocumentationTarget.Repository("SECURITY.md", null),
        ),
    )
}
