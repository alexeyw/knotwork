package app.knotwork.android.data.local

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Unit tests for [AudioCaptureStoreImpl], the ephemeral voice-input clip store.
 *
 * Runs under Robolectric so the real [Context.cacheDir] and a shadowed
 * `ContentResolver` back the file and URI operations.
 */
@RunWith(RobolectricTestRunner::class)
class AudioCaptureStoreImplTest {

    private lateinit var context: Context
    private lateinit var store: AudioCaptureStoreImpl

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        store = AudioCaptureStoreImpl(context)
    }

    private fun audioDir(): File = File(context.cacheDir, "audio")

    @Test
    fun `newRecordingFile returns a unique wav path inside the audio cache directory`() {
        val a = store.newRecordingFile()
        val b = store.newRecordingFile()

        assertTrue("must live under cacheDir/audio", a.startsWith(audioDir().absolutePath + File.separator))
        assertTrue("must be a .wav", a.endsWith(".wav"))
        assertFalse("two allocations must differ", a == b)
        assertTrue("parent directory created", audioDir().isDirectory)
    }

    @Test
    fun `importFromUri copies the source bytes into the store and returns an existing path`() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val uri = "content://media/audio/42"
        shadowOf(context.contentResolver).registerInputStream(uri.toUri(), ByteArrayInputStream(bytes))

        val result = store.importFromUri(uri)

        val path = result.getOrNull()
        assertTrue("import should succeed", result.isSuccess)
        requireNotNull(path)
        val file = File(path)
        assertTrue("copied file must exist", file.exists())
        assertTrue("copied file lives in the store", path.startsWith(audioDir().absolutePath + File.separator))
        assertArrayEqualsBytes(bytes, file.readBytes())
    }

    @Test
    fun `importFromUri refuses a file uri to app-private storage without copying it`() = runTest {
        val privateFile = File(context.filesDir, "private.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val uri = Uri.fromFile(privateFile)
        assertTrue(context.contentResolver.openInputStream(uri)?.use { it.read() != -1 } == true)

        val result = store.importFromUri(uri.toString())

        assertTrue("a file:// uri must be refused", result.isFailure)
        assertTrue("nothing may be copied into the store", audioDir().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `importFromUri returns failure when the URI cannot be read`() = runTest {
        val result = store.importFromUri("content://media/audio/missing")

        assertTrue("unreadable URI must fail", result.isFailure)
    }

    @Test
    fun `importFromUri deletes the partial clip when the copy fails midway`() = runTest {
        val uri = "content://media/audio/broken"
        // A stream that opens fine but throws once the copy starts reading, so a
        // target file is created and then the copy fails — the half-written clip
        // must not be left behind.
        val throwing = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("boom")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw java.io.IOException("boom")
        }
        shadowOf(context.contentResolver).registerInputStream(uri.toUri(), throwing)

        val result = store.importFromUri(uri)

        assertTrue("a failed copy must surface as failure", result.isFailure)
        val leftovers = audioDir().listFiles()?.toList().orEmpty()
        assertTrue("no partial clip must remain, found: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `delete removes an existing clip`() = runTest {
        val path = store.newRecordingFile()
        File(path).writeBytes(byteArrayOf(9, 9, 9))

        val result = store.delete(path)

        assertTrue(result.isSuccess)
        assertFalse("file must be gone", File(path).exists())
    }

    @Test
    fun `delete is idempotent for an absent clip`() = runTest {
        val path = store.newRecordingFile() // allocated but never written

        val result = store.delete(path)

        assertTrue("deleting an absent clip is a no-op success", result.isSuccess)
    }

    @Test
    fun `delete refuses a path outside the store as a no-op`() = runTest {
        val outside = File.createTempFile("outside", ".wav")
        outside.deleteOnExit()

        val result = store.delete(outside.absolutePath)

        // resolveSafe rejects anything outside cacheDir/audio → no-op success, file untouched.
        assertTrue(result.isSuccess)
        assertTrue("file outside the store must not be deleted", outside.exists())
    }

    @Test
    fun `given a path that traverses out of the audio directory when delete then the outside file survives`() =
        runTest {
            store.newRecordingFile() // creates the audio directory
            val outsideDir = File(context.cacheDir, "not-audio").apply { mkdirs() }
            val victim = File(outsideDir, "victim.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            // Starts with the store's root string, resolves outside it.
            val traversal = "${audioDir().absolutePath}/../${outsideDir.name}/${victim.name}"

            store.delete(traversal)

            assertTrue("a `..` path must not reach outside the store", victim.exists())
        }

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals("byte length", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("byte[$i]", expected[i], actual[i])
        }
    }
}
