package app.knotwork.android.data.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * [StreamingModelFileHasher] against real files: the digest must be the plain
 * SHA-256 of the bytes whatever the chunking, and a file that cannot be read or
 * changes mid-read must yield no hash at all.
 */
class StreamingModelFileHasherTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val hasher = StreamingModelFileHasher(Dispatchers.Unconfined)

    @Test
    fun `given the FIPS abc vector when hashed then the digest is the published one`() = runTest {
        val file = folder.newFile("abc.bin").apply { writeText("abc") }

        val hash = hasher.hash(file.path)

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash?.sha256)
    }

    @Test
    fun `given an empty file when hashed then the digest is the empty-input SHA-256`() = runTest {
        val file = folder.newFile("empty.bin")

        val hash = hasher.hash(file.path)

        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash?.sha256)
        assertEquals(0L, hash?.fileSizeBytes)
    }

    @Test
    fun `given a file spanning several chunks when hashed then it equals the one-shot digest`() = runTest {
        // Three and a half chunks: the last read is partial, which is where an
        // off-by-one in `update(buffer, 0, read)` would show.
        val bytes = ByteArray(3 * CHUNK + CHUNK / 2) { (it * 31 + 7).toByte() }
        val file = folder.newFile("big.bin").apply { writeBytes(bytes) }

        val hash = hasher.hash(file.path)

        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(expected, hash?.sha256)
        assertEquals(bytes.size.toLong(), hash?.fileSizeBytes)
        assertEquals(file.lastModified(), hash?.fileModifiedAtMs)
    }

    @Test
    fun `given a missing file when hashed then there is no hash`() = runTest {
        assertNull(hasher.hash(File(folder.root, "gone.bin").path))
    }

    @Test
    fun `given a directory when hashed then there is no hash`() = runTest {
        assertNull(hasher.hash(folder.newFolder("dir").path))
    }

    @Test
    fun `given a file whose stamp changes during the read when hashed then there is no hash`() = runTest {
        val file = folder.newFile("moving.bin").apply { writeText("first") }
        var reads = 0
        val moving = StreamingModelFileHasher(Dispatchers.Unconfined) {
            reads++
            StreamingModelFileHasher.FileStamp(sizeBytes = 5L, modifiedAtMs = reads.toLong())
        }

        assertNull(moving.hash(file.path))
        assertEquals("stamped before and after the read", 2, reads)
    }

    @Test
    fun `given the pass is cancelled when hashing then it stops with cancellation`() = runTest {
        val file = folder.newFile("cancel.bin").apply { writeBytes(ByteArray(2 * CHUNK)) }
        lateinit var job: Job
        var stamps = 0
        // The stamp is read right before the first chunk: cancelling there must stop
        // the read at the first cancellation check instead of finishing the file.
        val cancelling = StreamingModelFileHasher(StandardTestDispatcher(testScheduler)) {
            stamps++
            job.cancel()
            StreamingModelFileHasher.FileStamp(sizeBytes = it.length(), modifiedAtMs = it.lastModified())
        }

        val deferred = async { cancelling.hash(file.path) }
        job = deferred

        val outcome = runCatching { deferred.await() }.exceptionOrNull()
        assertTrue("expected cancellation, got $outcome", outcome is CancellationException)
        // The closing stamp is taken only after the whole file was read; withContext
        // would report the cancellation either way, so this is what proves the read stopped.
        assertEquals("the read ran to the end of the file", 1, stamps)
    }

    private companion object {
        /** The hasher's buffer size: one mebibyte. */
        const val CHUNK = 1 shl 20
    }
}
