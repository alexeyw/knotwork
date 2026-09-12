package app.knotwork.android.domain.constants

import app.knotwork.android.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drift guard for the generated [DocumentationLinks] registry as the app reads
 * it.
 *
 * The build already resolves every entry against the real Markdown, so these
 * tests do not repeat that. What they cover is the half the build cannot see:
 * that the contract the UI depends on — lookup by a stable id — actually holds
 * for every entry, and that the ref this variant links against is the one the
 * build type calls for.
 */
class DocumentationLinksTest {

    @Test
    fun `given the registry when read then every id resolves`() {
        DocumentationLinks.ENTRIES.forEach { entry ->
            assertEquals("byId must return the entry declaring the id", entry, DocumentationLinks.byId(entry.id))
        }
    }

    @Test
    fun `given an unknown id when looked up then nothing is returned`() {
        assertNull(DocumentationLinks.byId("no-such-document"))
    }

    @Test
    fun `given the registry when read then every id is unique`() {
        val ids = DocumentationLinks.ENTRIES.map { it.id }
        assertEquals("Entry ids must be unique", ids.distinct(), ids)
    }

    @Test
    fun `given the registry when read then every entry sits under docs`() {
        DocumentationLinks.ENTRIES.forEach { entry ->
            assertTrue(
                "`${entry.id}` points at `${entry.path}`, outside the documentation tree",
                entry.path.startsWith("docs/") && entry.path.endsWith(".md"),
            )
        }
    }

    @Test
    fun `given the surfaces that link out when resolved then each id is registered`() {
        // One assertion per in-app entry point. A surface wired to an id the
        // registry lost would otherwise present a control that silently does
        // nothing.
        listOf(
            DocumentationLinks.ID_USER_GUIDE,
            DocumentationLinks.ID_FAQ,
            DocumentationLinks.ID_COOKBOOK,
            DocumentationLinks.ID_TROUBLESHOOTING,
            DocumentationLinks.ID_EXTERNAL_AUTOMATION,
            DocumentationLinks.ID_MCP_SETUP,
            DocumentationLinks.ID_TRIGGERS,
        ).forEach { id -> assertNotNull("No registry entry is declared under '$id'", DocumentationLinks.byId(id)) }
    }

    @Test
    fun `given a section entry when read then it carries the anchor it is named for`() {
        assertEquals("adding-an-mcp-server", DocumentationLinks.byId(DocumentationLinks.ID_MCP_SETUP)?.anchor)
        assertEquals("triggers", DocumentationLinks.byId(DocumentationLinks.ID_TRIGGERS)?.anchor)
    }

    @Test
    fun `given a unit test build when the docs ref is read then it is the default branch`() {
        // Unit tests always run against the debug variant, so this asserts the
        // debug half of the rule only — the release half is covered in
        // `buildSrc` by `DocumentationRefTest`, where both branches are
        // ordinary arguments rather than a compile-time constant.
        assertEquals(RepositoryLinks.DEFAULT_BRANCH, BuildConfig.DOCS_REF)
    }
}
