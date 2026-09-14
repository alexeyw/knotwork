package app.knotwork.android.domain.constants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RepositoryLinks].
 *
 * The URL shape is asserted for both refs a build can carry, because the
 * release ref is the one no app-side test can observe through `BuildConfig` —
 * a unit test always runs against the debug variant — and it is the ref that
 * reaches a user who installed from a store.
 */
class RepositoryLinksTest {

    @Test
    fun `given a release ref when a document url is built then it is pinned to the tag`() {
        assertEquals(
            "https://github.com/alexeyw/knotwork/blob/v0.9.0/docs/faq.md",
            RepositoryLinks.blobUrl(ref = "v0.9.0", path = "docs/faq.md"),
        )
    }

    @Test
    fun `given the default branch when a document url is built then it is pinned to main`() {
        assertEquals(
            "https://github.com/alexeyw/knotwork/blob/main/docs/faq.md",
            RepositoryLinks.blobUrl(ref = "main", path = "docs/faq.md"),
        )
    }

    @Test
    fun `given an anchor when a document url is built then it is appended as a fragment`() {
        assertEquals(
            "https://github.com/alexeyw/knotwork/blob/v0.9.0/docs/user-guide.md#adding-an-mcp-server",
            RepositoryLinks.blobUrl(ref = "v0.9.0", path = "docs/user-guide.md", anchor = "adding-an-mcp-server"),
        )
    }

    @Test
    fun `given no anchor when a document url is built then it carries no fragment`() {
        assertTrue('#' !in RepositoryLinks.blobUrl(ref = "main", path = "docs/faq.md"))
    }

    @Test
    fun `given a legal document when its url is built then it resolves on the default branch`() {
        // Legal documents deliberately do NOT follow the build's ref: what binds
        // a user is the current edition, and this URL is additionally the one
        // filed with the app stores, where a per-release link would break.
        assertEquals(
            "https://github.com/alexeyw/knotwork/blob/main/PRIVACY.md",
            RepositoryLinks.legalDocumentUrl("PRIVACY.md"),
        )
    }

    @Test
    fun `given the repository url when read then it has no trailing slash`() {
        // Every builder here concatenates a `/` of its own.
        assertTrue(!RepositoryLinks.REPOSITORY_URL.endsWith("/"))
    }

    @Test
    fun `given the issues endpoint when read then it is under the same repository`() {
        assertEquals("${RepositoryLinks.REPOSITORY_URL}/issues/new", RepositoryLinks.ISSUES_NEW_URL)
    }
}
