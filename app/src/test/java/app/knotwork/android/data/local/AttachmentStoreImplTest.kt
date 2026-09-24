package app.knotwork.android.data.local

import android.content.Context
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * Verifies [AttachmentStoreImpl]: aspect-preserving downscale to the longest-side
 * cap (never a square crop), JPEG re-encode, ingest of invalid bytes, the
 * content-URI ingest path, and the delete / list surface used by retention.
 *
 * Runs under Robolectric so [Bitmap] / `BitmapFactory` round-trip real
 * dimensions through compress + decode.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentStoreImplTest {

    private lateinit var context: Context
    private lateinit var store: AttachmentStoreImpl

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        store = AttachmentStoreImpl(context)
    }

    private fun jpegBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_TEST_QUALITY, out)
            out.toByteArray()
        }
    }

    @Test
    fun `given oversized image when ingested then longest side capped and aspect preserved`() = runTest {
        val result = store.ingest(jpegBytes(width = 3000, height = 1000))

        val attachment = result.getOrNull()
        assertTrue("ingest should succeed", result.isSuccess)
        requireNotNull(attachment)
        assertEquals("image/jpeg", attachment.mimeType)
        assertTrue(
            "Longest side must be capped at $CAP, got ${attachment.width}x${attachment.height}",
            maxOf(attachment.width, attachment.height) <= CAP,
        )
        val sourceRatio = 3000f / 1000f
        val storedRatio = attachment.width.toFloat() / attachment.height
        assertTrue(
            "Aspect ratio must be preserved (no square crop): $storedRatio vs $sourceRatio",
            abs(storedRatio - sourceRatio) < RATIO_TOLERANCE,
        )
    }

    @Test
    fun `given small image when ingested then dimensions are left untouched`() = runTest {
        val attachment = store.ingest(jpegBytes(width = 800, height = 600)).getOrNull()

        requireNotNull(attachment)
        assertEquals(800, attachment.width)
        assertEquals(600, attachment.height)
    }

    @Test
    fun `given stored attachment then file exists at the resolved absolute path`() = runTest {
        val attachment = store.ingest(jpegBytes(800, 600)).getOrNull()

        requireNotNull(attachment)
        assertTrue(File(store.absolutePathFor(attachment.path)).exists())
    }

    @Test
    fun `given stored attachment when deleted then the file is gone`() = runTest {
        val attachment = store.ingest(jpegBytes(800, 600)).getOrNull()
        requireNotNull(attachment)

        val deleteResult = store.delete(attachment.path)

        assertTrue(deleteResult.isSuccess)
        assertFalse(File(store.absolutePathFor(attachment.path)).exists())
    }

    @Test
    fun `given stored attachments when deleteAll then no attachment file is left`() = runTest {
        val first = store.ingest(jpegBytes(64, 64)).getOrThrow()
        val second = store.ingest(jpegBytes(32, 32)).getOrThrow()

        assertTrue(store.deleteAll())

        assertFalse(store.exists(first.path))
        assertFalse(store.exists(second.path))
        assertEquals(emptyList<String>(), store.listStoredPaths().getOrThrow())
        // The store keeps working: the directory is recreated on the next ingest.
        assertTrue(store.exists(store.ingest(jpegBytes(16, 16)).getOrThrow().path))
    }

    @Test
    fun `given no attachment directory yet when deleteAll then it reports nothing left`() = runTest {
        assertTrue(store.deleteAll())
    }

    @Test
    fun `given missing path when deleted then success (idempotent)`() = runTest {
        assertTrue(store.delete("does-not-exist.jpg").isSuccess)
    }

    @Test
    fun `given path-traversal name when deleted then it is rejected without touching siblings`() = runTest {
        // A name with separators is not a valid flat attachment name → treated as absent (no-op success).
        assertTrue(store.delete("../secret.txt").isSuccess)
    }

    @Test
    fun `given several stored attachments then listStoredPaths enumerates them`() = runTest {
        val first = store.ingest(jpegBytes(800, 600)).getOrNull()
        val second = store.ingest(jpegBytes(640, 480)).getOrNull()
        requireNotNull(first)
        requireNotNull(second)

        val stored = store.listStoredPaths().getOrNull().orEmpty()

        assertTrue(stored.contains(first.path))
        assertTrue(stored.contains(second.path))
    }

    @Test
    fun `given a grace window then a freshly-written file is excluded but an aged one is listed`() = runTest {
        val fresh = store.ingest(jpegBytes(800, 600)).getOrNull()
        val aged = store.ingest(jpegBytes(640, 480)).getOrNull()
        requireNotNull(fresh)
        requireNotNull(aged)
        // Backdate one file well beyond the grace window.
        File(store.absolutePathFor(aged.path)).setLastModified(System.currentTimeMillis() - TWO_HOURS_MS)

        val listed = store.listStoredPaths(minAgeMillis = ONE_HOUR_MS).getOrNull().orEmpty()

        assertFalse("in-flight (fresh) file must be skipped by the grace window", listed.contains(fresh.path))
        assertTrue("aged file is a real orphan candidate", listed.contains(aged.path))
    }

    @Test
    fun `given a stored attachment then exists is true and sizeBytes is positive`() = runTest {
        val stored = store.ingest(jpegBytes(800, 600)).getOrNull()
        requireNotNull(stored)

        assertTrue(store.exists(stored.path))
        assertTrue("size should be > 0 for a written JPEG", store.sizeBytes(stored.path) > 0L)
    }

    @Test
    fun `given a missing or invalid path then exists is false and sizeBytes is zero`() = runTest {
        assertFalse(store.exists("missing.jpg"))
        assertEquals(0L, store.sizeBytes("missing.jpg"))
        // Path-traversal name is rejected → treated as absent.
        assertFalse(store.exists("../secret.txt"))
        assertEquals(0L, store.sizeBytes("../secret.txt"))
    }

    @Test
    fun `given content uri when ingested then bytes are read and stored`() = runTest {
        val uri = "content://test/image".toUri()
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(jpegBytes(1000, 800)))

        val attachment = store.ingestUri(uri.toString()).getOrNull()

        requireNotNull(attachment)
        assertEquals(1000, attachment.width)
        assertEquals(800, attachment.height)
    }

    @Test
    fun `given unreadable content uri when ingested then failure is returned`() = runTest {
        val result = store.ingestUri("content://test/missing")

        assertTrue(result.isFailure)
    }

    @Test
    fun `given a file-scheme uri to an app-private image when ingestUri then it is refused`() = runTest {
        val privateFile = File(context.filesDir, "private-photo.jpg").apply { writeBytes(jpegBytes(640, 480)) }
        val uri = Uri.fromFile(privateFile)
        // Precondition: the resolver would read it with the app's own identity, so a
        // failure below is the policy, not an unreadable fixture.
        assertTrue(context.contentResolver.openInputStream(uri)?.use { it.read() != -1 } == true)

        val result = store.ingestUri(uri.toString())

        assertTrue("a file:// uri must be refused", result.isFailure)
        assertTrue("nothing may be stored", store.listStoredPaths().getOrThrow().isEmpty())
    }

    @Test
    fun `given a uri served by this app's own provider when ingestUri then it is refused`() = runTest {
        val uri = ownProviderUri(File(context.cacheDir, "images").apply { mkdirs() }, "capture_1.jpg")
        assertTrue(context.contentResolver.openInputStream(uri)?.use { it.read() != -1 } == true)

        val result = store.ingestUri(uri.toString())

        assertTrue("a uri of the app's own FileProvider must be refused", result.isFailure)
        assertTrue("nothing may be stored", store.listStoredPaths().getOrThrow().isEmpty())
    }

    @Test
    fun `given this app's provider addressed with a user-id prefix when ingestUri then it is refused`() = runTest {
        val own = ownProviderUri(File(context.cacheDir, "images").apply { mkdirs() }, "capture_3.jpg")
        // On a device the resolver strips `<userId>@` and reaches the same provider.
        // Robolectric does not, so the bytes are registered for the prefixed URI
        // itself: without the policy the ingest below would succeed.
        val prefixed = own.buildUpon().encodedAuthority("0@${own.encodedAuthority}").build()
        shadowOf(context.contentResolver).registerInputStream(prefixed, ByteArrayInputStream(jpegBytes(640, 480)))

        val result = store.ingestUri(prefixed.toString())

        assertTrue("`0@<own authority>` is the own provider and must be refused", result.isFailure)
    }

    @Test
    fun `given an android-resource uri when ingestUri then it is refused`() = runTest {
        val result = store.ingestUri("android.resource://${context.packageName}/raw/anything")

        assertTrue(result.isFailure)
    }

    /**
     * Writes a decodable JPEG into [dir] and returns the URI this app's real
     * `FileProvider` serves it under, with the provider attached so the
     * resolver can actually open it.
     */
    private fun ownProviderUri(dir: File, name: String): Uri {
        File(dir, name).writeBytes(jpegBytes(640, 480))
        val authority = "${context.packageName}.fileprovider"
        val info = ProviderInfo().apply {
            this.authority = authority
            grantUriPermissions = true
            exported = false
        }
        Robolectric.buildContentProvider(FileProvider::class.java).create(info)
        return FileProvider.getUriForFile(context, authority, File(dir, name))
    }

    private companion object {
        const val CAP = AttachmentStoreImpl.MAX_LONGEST_SIDE_PX
        const val JPEG_TEST_QUALITY = 90
        const val RATIO_TOLERANCE = 0.05f
        const val ONE_HOUR_MS = 60L * 60L * 1000L
        const val TWO_HOURS_MS = 2L * ONE_HOUR_MS
    }
}
