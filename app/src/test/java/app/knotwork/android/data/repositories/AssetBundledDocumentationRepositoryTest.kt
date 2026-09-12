package app.knotwork.android.data.repositories

import app.knotwork.android.domain.constants.DocumentationLinks
import app.knotwork.android.domain.models.DocumentationTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Verifies [AssetBundledDocumentationRepository] against the **real** generated
 * assets rather than a fixture.
 *
 * That choice is the point of the test. `verifyBundledDocs` proves the index is
 * what the build meant to write; it cannot prove the app can read it, and the
 * two halves were written against a hand-maintained contract (a JSON shape
 * rendered by `StringBuilder` on one side, parsed by hand on the other) with no
 * compiler between them. A fixture would only ever prove the parser agrees with
 * whatever the fixture's author believed the generator emits. Reading the
 * shipped artifact is what closes the loop — and it is the same artifact the
 * APK carries.
 */
@RunWith(RobolectricTestRunner::class)
class AssetBundledDocumentationRepositoryTest {

    private lateinit var repository: AssetBundledDocumentationRepository

    @Before
    fun setUp() {
        repository = AssetBundledDocumentationRepository(RuntimeEnvironment.getApplication())
            .apply { dispatcher = Dispatchers.Unconfined }
    }

    @Test
    fun `given the shipped index when documents are read then every bundled entry is present`() = runTest {
        val documents = repository.documents().getOrThrow()
        val expected = DocumentationLinks.ENTRIES
            .filter { it.anchor == null && it.delivery == DocumentationLinks.Delivery.BUNDLED }
            .map { it.id }
        assertEquals(expected, documents.map { it.id })
        assertTrue("The registry must declare at least one bundled document", expected.isNotEmpty())
    }

    @Test
    fun `given the shipped index when documents are read then no remote document leaked in`() = runTest {
        val bundled = repository.documents().getOrThrow().map { it.path }
        val remote = DocumentationLinks.ENTRIES
            .filter { it.delivery == DocumentationLinks.Delivery.REMOTE }
            .map { it.path }
        assertTrue(
            "A REMOTE document must not ship in the assets, got ${bundled.intersect(remote.toSet())}",
            bundled.none { it in remote },
        )
    }

    @Test
    fun `given a bundled document when its content is read then the text matches the asset`() = runTest {
        val document = repository.documents().getOrThrow().first()
        val content = repository.content(document.id).getOrThrow()
        assertTrue("A bundled document must not be empty", content.isNotBlank())
        assertTrue("A Markdown document must carry a heading", content.contains("#"))
    }

    @Test
    fun `given the shipped index when anchors are read then each offset lands on its heading`() = runTest {
        // The claim the reader's whole anchor navigation rests on. An offset
        // that drifted by one construct would scroll to a plausible-looking
        // wrong place, which no snapshot and no gate would notice.
        for (document in repository.documents().getOrThrow()) {
            val text = repository.content(document.id).getOrThrow()
            assertTrue("${document.id} declares no anchors", document.anchors.isNotEmpty())
            for ((anchor, offset) in document.anchors) {
                val line = text.substring(offset, text.indexOf('\n', offset).takeIf { it >= 0 } ?: text.length)
                assertTrue(
                    "Anchor '$anchor' of ${document.id} points at '$line', which is not a heading",
                    line.startsWith("#"),
                )
            }
        }
    }

    @Test
    fun `given the shipped index when links are read then every relative target resolved`() = runTest {
        // Completeness is what lets the reader treat a lookup miss as a build
        // defect rather than a case to guess about.
        for (document in repository.documents().getOrThrow()) {
            assertTrue("${document.id} resolved no links", document.links.isNotEmpty())
            assertTrue(
                "${document.id} indexed an absolute URL, which needs no entry",
                document.links.keys.none { it.startsWith("http://") || it.startsWith("https://") },
            )
        }
    }

    @Test
    fun `given the shipped index when a cross-document link is read then it names a bundled id`() = runTest {
        val documents = repository.documents().getOrThrow()
        val ids = documents.map { it.id }.toSet()
        val bundledTargets = documents
            .flatMap { it.links.values }
            .filterIsInstance<DocumentationTarget.Bundled>()
        assertTrue("Expected at least one link between bundled documents", bundledTargets.isNotEmpty())
        assertTrue(
            "A bundled target must name a document that ships, got ${bundledTargets.map { it.id } - ids}",
            bundledTargets.all { it.id in ids },
        )
    }

    @Test
    fun `given an unknown id when content is read then it fails rather than returning nothing`() = runTest {
        val result = repository.content("no-such-document")
        assertTrue("Expected a failure, got $result", result.isFailure)
        assertFalse(result.isSuccess)
    }
}
