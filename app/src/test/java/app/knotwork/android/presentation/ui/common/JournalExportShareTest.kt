package app.knotwork.android.presentation.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.knotwork.android.domain.constants.TransientCacheDirectory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * The share half of the journal export — the part that touches the platform and
 * therefore cannot be reached from the delegate's own tests.
 *
 * Three things here fail silently if they are wrong, and all three are the kind
 * that only show up on a user's phone: the `FileProvider` authority (a mismatch
 * throws at share time, never at build time), the staging directory (a path the
 * provider does not declare is refused the same way), and the case where no app
 * on the device can receive the file — which must be **reported**, not swallowed
 * into a tap that appears to do nothing.
 */
@RunWith(RobolectricTestRunner::class)
class JournalExportShareTest {

    private lateinit var context: Context

    private val document = """{"schemaVersion": 3, "totalEvaluations": 0}"""

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.cacheDir, "journal").deleteRecursively()
        // Stated rather than inherited: Robolectric's default for implicit-intent
        // resolution has changed across releases, and leaving it implicit would
        // make the happy path and the no-receiver path silently swap results.
        shadowOf(context as android.app.Application).checkActivities(false)
        clearFileProviderCache()
    }

    /**
     * Drops `FileProvider`'s static per-authority path strategy.
     *
     * A harness artefact with no counterpart on a device: `FileProvider` resolves
     * an authority's roots once and caches them for the process, which is correct
     * when `cacheDir` is fixed for the process lifetime — and wrong under
     * Robolectric, which hands every **test method** its own temp data dir. Without
     * this, the first test to share wins and every later one is told its perfectly
     * valid path lies outside the configured root.
     */
    private fun clearFileProviderCache() {
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    /** Shares [json] the way the journal screens do. */
    private suspend fun shareJournal(json: String, fileName: String): Boolean = ExportDocumentHandOff.share(
        context = context,
        json = json,
        fileName = fileName,
        mimeType = JOURNAL_EXPORT_MIME,
        stagingDirectory = TransientCacheDirectory.JOURNAL_EXPORT,
        chooserTitle = "Share journal",
    )

    @Test
    fun `given a document when shared then it is staged and offered as a content uri`() = runTest {
        val fileName = journalExportFileName(TRIGGER_JOURNAL_EXPORT_STEM)

        val shared = shareJournal(document, fileName)

        assertTrue("the share sheet should have opened", shared)
        val chooser = shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull("a chooser must be started", chooser)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull("the chooser must wrap the ACTION_SEND intent", send)
        assertEquals(Intent.ACTION_SEND, send!!.action)
        assertEquals(JOURNAL_EXPORT_MIME, send.type)

        // A `content://` URI, not a `file://` one: handing a receiving app a raw
        // file path throws FileUriExposedException on every supported release.
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        assertEquals("content", uri?.scheme)
        assertEquals("${context.packageName}.fileprovider", uri?.authority)
        assertTrue(
            "the grant flag must travel, or the receiver cannot read the file",
            send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
    }

    @Test
    fun `given a document when shared then the staged file holds it verbatim under the declared path`() = runTest {
        val fileName = journalExportFileName(TRIGGER_JOURNAL_EXPORT_STEM)

        shareJournal(document, fileName)

        // `cacheDir/journal/`, the path `file_paths.xml` declares — and a sibling
        // of the Files screen's `shared/`, which that screen wipes on every
        // workspace share.
        val staged = File(File(context.cacheDir, "journal"), fileName)
        assertTrue("the export must be staged where the provider can serve it", staged.exists())
        assertEquals(document, staged.readText())
    }

    @Test
    fun `given an earlier share when another runs then the stale document is gone`() = runTest {
        val first = journalExportFileName(TRIGGER_JOURNAL_EXPORT_STEM)
        shareJournal(document, first)

        val second = "external-requests-19700101-000000.json"
        shareJournal("""{"schemaVersion": 1}""", second)

        // A stale journal must never be handed to the next share — the staging
        // directory holds one document at a time by construction.
        val dir = File(context.cacheDir, "journal")
        assertEquals(listOf(second), dir.list()?.toList())
    }

    @Test
    fun `given no app able to receive the file when shared then the refusal is reported`() = runTest {
        // A device with nothing able to accept the file: with activity checking
        // on, Robolectric throws the same ActivityNotFoundException the platform
        // would.
        shadowOf(context as android.app.Application).checkActivities(true)

        val shared = shareJournal(document, journalExportFileName(TRIGGER_JOURNAL_EXPORT_STEM))

        // Reported, not swallowed: a share that silently does nothing is
        // indistinguishable from a dead control, and the caller turns this into a
        // message rather than leaving the user tapping.
        assertFalse("an unreceivable share must report failure", shared)
    }

    @Test
    fun `given the two journals when named then their files are told apart by stem`() {
        // A user who exports both ends up with two files in one folder.
        assertTrue(journalExportFileName(TRIGGER_JOURNAL_EXPORT_STEM).startsWith("trigger-journal-"))
        assertTrue(journalExportFileName(EXTERNAL_REQUESTS_EXPORT_STEM).startsWith("external-requests-"))
        assertTrue(journalExportFileName(TRIGGER_JOURNAL_EXPORT_STEM).endsWith(".json"))
    }
}
