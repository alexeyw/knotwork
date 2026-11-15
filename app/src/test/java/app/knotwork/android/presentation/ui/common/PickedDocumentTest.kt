package app.knotwork.android.presentation.ui.common

import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

/**
 * [ContentResolverPickedDocument] and [discardUnwritten]: a provider's refusal to open or
 * to delete is an answer, never a crash; a document already gone counts as removed; and
 * a document the provider will not delete is named, so the user can find the empty file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PickedDocumentTest {

    private val uri: Uri = Uri.parse(
        "content://com.android.externalstorage.documents/document/primary%3AKnotwork%2Fchat.json",
    )
    private val resolver = mockk<ContentResolver>(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.deleteDocument(any<ContentResolver>(), any()) } returns true
    }

    @After
    fun tearDown() {
        unmockkStatic(DocumentsContract::class)
    }

    @Test
    fun `given a provider that opens the document when opened then its stream is handed out`() {
        val stream = ByteArrayOutputStream()
        every { resolver.openOutputStream(uri) } returns stream

        assertSame(stream, ContentResolverPickedDocument(resolver, uri).openOutput())
    }

    @Test
    fun `given a provider that lost or refuses the document when opened then there is no stream`() {
        every { resolver.openOutputStream(uri) } throws FileNotFoundException("gone")
        assertNull(ContentResolverPickedDocument(resolver, uri).openOutput())

        every { resolver.openOutputStream(uri) } throws SecurityException("no grant")
        assertNull(ContentResolverPickedDocument(resolver, uri).openOutput())
    }

    @Test
    fun `given each answer of the provider when deleted then only a refusal keeps the document`() {
        val document = ContentResolverPickedDocument(resolver, uri)

        assertTrue(document.delete())
        every { DocumentsContract.deleteDocument(any<ContentResolver>(), any()) } throws FileNotFoundException("gone")
        assertTrue("a document already gone is removed", document.delete())
        every { DocumentsContract.deleteDocument(any<ContentResolver>(), any()) } throws SecurityException("no")
        assertFalse(document.delete())
        every { DocumentsContract.deleteDocument(any<ContentResolver>(), any()) } throws
            UnsupportedOperationException("delete not supported")
        assertFalse(document.delete())
        every { DocumentsContract.deleteDocument(any<ContentResolver>(), any()) } throws
            IllegalArgumentException("unknown document")
        assertFalse(document.delete())
        every { DocumentsContract.deleteDocument(any<ContentResolver>(), any()) } throws IllegalStateException("died")
        assertFalse(document.delete())
    }

    @Test
    fun `given the provider names the document when asked then its display name is given`() {
        val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("chat (1).json")) }
        every { resolver.query(uri, any(), null, null, null) } returns cursor

        assertEquals("chat (1).json", ContentResolverPickedDocument(resolver, uri).displayName())
    }

    @Test
    fun `given a provider that does not say when asked then the address's last segment names the document`() {
        every { resolver.query(uri, any(), null, null, null) } returns null
        assertEquals("primary:Knotwork/chat.json", ContentResolverPickedDocument(resolver, uri).displayName())

        every { resolver.query(uri, any(), null, null, null) } throws IllegalStateException("provider died")
        assertEquals("primary:Knotwork/chat.json", ContentResolverPickedDocument(resolver, uri).displayName())
    }

    @Test
    fun `given a document that is deleted when discarded then it is removed`() = runTest {
        val outcome = FakePickedDocument().discardUnwritten(StandardTestDispatcher(testScheduler))

        assertEquals(UnwrittenDocument.Removed, outcome)
    }

    @Test
    fun `given a document its provider will not delete when discarded then its name is given`() = runTest {
        val outcome = FakePickedDocument(name = "journal.json", deletes = false)
            .discardUnwritten(StandardTestDispatcher(testScheduler))

        assertEquals(UnwrittenDocument.Left("journal.json"), outcome)
    }
}
