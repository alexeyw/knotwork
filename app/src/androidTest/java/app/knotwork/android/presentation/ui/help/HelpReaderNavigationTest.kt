package app.knotwork.android.presentation.ui.help

import androidx.test.platform.app.InstrumentationRegistry
import app.knotwork.android.data.repositories.AssetBundledDocumentationRepository
import app.knotwork.android.domain.models.DocumentationTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walks the path the task specifies, on a real device, against the assets the
 * APK actually carries: open the FAQ, follow one of its internal links, and
 * land on the named anchor of the troubleshooting guide.
 *
 * **Why this is an instrumented test and not a Robolectric one.** The unit
 * suite already reads the same index through Robolectric's asset loader, which
 * reads it from the *build directory*. What is unverified until an APK is
 * installed is that the assets survive packaging — that `syncBundledDocs` wrote
 * somewhere `mergeAssets` actually collects, and that nothing in the release
 * pipeline strips a `.md` on the way. A gate on the source tree cannot answer
 * that; only a device can.
 *
 * The navigation itself is deliberately exercised at the seam that decides it
 * — the resolver and the index — rather than by driving Compose. What a tap
 * does is one lookup, and asserting on pixels afterwards would test the
 * scaffold while leaving the lookup, which is the part that can be wrong, to
 * inference.
 */
class HelpReaderNavigationTest {

    private val repository =
        AssetBundledDocumentationRepository(InstrumentationRegistry.getInstrumentation().targetContext)

    private val resolve = ResolveDocumentationLinkUseCaseHolder.instance

    @Test
    fun faq_link_to_troubleshooting_lands_on_the_named_anchor() = runBlocking {
        val documents = repository.documents().getOrThrow()
        val faq = documents.first { it.id == "faq" }

        // The link as `docs/faq.md` really writes it.
        val rawTarget = faq.links.keys.firstOrNull {
            it.startsWith("troubleshooting.md#")
        }
        assertNotNull("The FAQ must link into the troubleshooting guide by anchor", rawTarget)

        val target = resolve(faq, rawTarget!!)
        assertTrue("Expected a bundled target, got $target", target is DocumentationTarget.Bundled)
        val bundled = target as DocumentationTarget.Bundled
        assertEquals("troubleshooting", bundled.id)

        // The anchor has to resolve in the document it names, and the offset it
        // resolves to has to be that heading's line — an offset that drifted by
        // one construct would scroll to a plausible-looking wrong place.
        val troubleshooting = documents.first { it.id == bundled.id }
        val offset = troubleshooting.anchors[bundled.anchor]
        assertNotNull("Anchor '${bundled.anchor}' must exist in the troubleshooting guide", offset)

        val text = repository.content(troubleshooting.id).getOrThrow()
        val line = text.substring(offset!!, text.indexOf('\n', offset).takeIf { it >= 0 } ?: text.length)
        assertTrue("Expected a heading at offset $offset, found '$line'", line.startsWith("#"))
    }

    @Test
    fun every_bundled_document_survives_packaging() {
        runBlocking {
            val documents = repository.documents().getOrThrow()
            assertTrue("The APK must carry at least one bundled document", documents.isNotEmpty())
            documents.forEach { document ->
                val content = repository.content(document.id).getOrThrow()
                assertTrue("${document.assetPath} is empty in the installed APK", content.isNotBlank())
            }
        }
    }

    /**
     * Holds the one use case this test needs.
     *
     * Constructed directly rather than injected: it has no dependencies, and a
     * Hilt test rule here would buy nothing but a longer setup.
     */
    private object ResolveDocumentationLinkUseCaseHolder {
        val instance = app.knotwork.android.domain.usecases.ResolveDocumentationLinkUseCase()
    }
}
