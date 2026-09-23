package app.knotwork.android.data.local

import android.content.Context
import android.content.pm.ProviderInfo
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Verifies [ImageCaptureStoreImpl]: the camera's full-resolution original —
 * the only copy that keeps its EXIF — is deleted on every way out of the store,
 * and a URI that is not one of its captures can reach no file.
 *
 * Runs under Robolectric with the app's real `FileProvider` attached, so the
 * URIs are the ones the camera app would receive.
 */
@RunWith(RobolectricTestRunner::class)
class ImageCaptureStoreImplTest {

    private lateinit var context: Context
    private lateinit var store: ImageCaptureStoreImpl

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        val info = ProviderInfo().apply {
            authority = "${context.packageName}.fileprovider"
            grantUriPermissions = true
            exported = false
        }
        Robolectric.buildContentProvider(FileProvider::class.java).create(info)
        store = ImageCaptureStoreImpl(context)
    }

    private fun captureDir(): File = File(context.cacheDir, "images")

    /** Plays the camera app: writes [bytes] to the file behind [uri]. */
    private fun cameraWrites(uri: String, bytes: ByteArray) {
        context.contentResolver.openOutputStream(uri.toUri())!!.use { it.write(bytes) }
    }

    @Test
    fun `given a new capture uri then it is this app's provider over the capture directory`() {
        val uri = store.newCaptureUri().toUri()

        assertEquals("${context.packageName}.fileprovider", uri.authority)
        assertEquals("images", uri.pathSegments.first())
        assertTrue("the directory exists for the camera to write into", captureDir().isDirectory)
    }

    @Test
    fun `given two captures then their uris differ`() {
        assertFalse(store.newCaptureUri() == store.newCaptureUri())
    }

    @Test
    fun `given a capture the camera wrote when consumed then the bytes come back and the original is deleted`() =
        runTest {
            val uri = store.newCaptureUri()
            val photo = byteArrayOf(1, 2, 3, 4)
            cameraWrites(uri, photo)

            val result = store.consume(uri)

            assertArrayEquals(photo, result.getOrThrow())
            assertTrue("the full-resolution original must not outlive the ingest", captureDir().listFiles()!!.isEmpty())
        }

    @Test
    fun `given the camera wrote nothing when consumed then it fails`() = runTest {
        val result = store.consume(store.newCaptureUri())

        assertTrue(result.isFailure)
    }

    @Test
    fun `given a cancelled capture that left a partial file when discarded then the file is gone`() = runTest {
        val uri = store.newCaptureUri()
        cameraWrites(uri, byteArrayOf(9))

        store.discard(uri)

        assertTrue(captureDir().listFiles()!!.isEmpty())
    }

    @Test
    fun `given a uri of another authority when consumed or discarded then no file is touched`() = runTest {
        val victim = File(captureDir().apply { mkdirs() }, "capture_x.jpg").apply { writeBytes(byteArrayOf(5)) }
        val foreign = "content://com.example.photos/images/${victim.name}"

        val result = store.consume(foreign)
        store.discard(foreign)

        assertTrue(result.isFailure)
        assertTrue(victim.exists())
    }

    @Test
    fun `given a capture name that climbs out of the directory when discarded then the outside file survives`() =
        runTest {
            val outside = File(context.cacheDir, "outside.jpg").apply { writeBytes(byteArrayOf(7)) }
            val climbing = "content://${context.packageName}.fileprovider/images/..%2Foutside.jpg"

            store.discard(climbing)
            val result = store.consume(climbing)

            assertTrue(result.isFailure)
            assertTrue(outside.exists())
        }
}
