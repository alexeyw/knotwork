package app.knotwork.android.data.local

import android.content.Context
import app.knotwork.android.domain.constants.TransientCacheDirectory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Verifies [TransientCacheSweeperImpl] on a real filesystem: every registered
 * handoff directory is swept, only past the shared retention, a share slot counts
 * as fresh while its copy is, and nothing outside the registry is touched.
 */
class TransientCacheSweeperImplTest {

    @get:Rule
    val cacheFolder = TemporaryFolder()

    private val now = 10L * TransientCacheDirectory.RETENTION_MILLIS
    private lateinit var sweeper: TransientCacheSweeperImpl

    @Before
    fun setup() {
        val context = mockk<Context>()
        every { context.cacheDir } returns cacheFolder.root
        sweeper = TransientCacheSweeperImpl(context) { now }
    }

    /** Creates a file under the cache and dates it [ageMillis] before [now]. */
    private fun fileAt(path: String, ageMillis: Long): File = File(cacheFolder.root, path).apply {
        parentFile!!.mkdirs()
        writeText("x")
        setLastModified(now - ageMillis)
    }

    @Test
    fun `given an expired entry in every registered directory when swept then all are removed`() = runTest {
        val expired = TransientCacheDirectory.entries.map { fileAt("${it.dirName}/old", EXPIRED) }

        val removed = sweeper.sweepExpired()

        assertEquals(TransientCacheDirectory.entries.size, removed)
        expired.forEach { assertFalse("${it.path} outlived the retention", it.exists()) }
    }

    @Test
    fun `given entries within the retention when swept then they stay`() = runTest {
        val fresh = TransientCacheDirectory.entries.map { fileAt("${it.dirName}/new", FRESH) }

        assertEquals(0, sweeper.sweepExpired())
        fresh.forEach { assertTrue(it.exists()) }
    }

    @Test
    fun `given a share slot whose copy is fresh when swept then the slot stays`() = runTest {
        val copy = fileAt("shared/key-slot/report.md", FRESH)
        copy.parentFile!!.setLastModified(now - EXPIRED)

        sweeper.sweepExpired()

        assertTrue("a slot is as old as its newest content", copy.exists())
    }

    @Test
    fun `given an old file outside the registry when swept then it is left alone`() = runTest {
        val foreign = fileAt("litert-compile-cache/model.bin", EXPIRED)

        sweeper.sweepExpired()

        assertTrue(foreign.exists())
    }

    @Test
    fun `given fresh entries in every registered directory when swept whole then all are removed`() = runTest {
        val fresh = TransientCacheDirectory.entries.map { fileAt("${it.dirName}/new", FRESH) }
        val slotCopy = fileAt("shared/key-slot/report.md", FRESH)

        assertTrue(sweeper.sweepAll())

        (fresh + slotCopy).forEach { assertFalse("${it.path} survived the wipe", it.exists()) }
        TransientCacheDirectory.entries.forEach { directory ->
            assertTrue(File(cacheFolder.root, directory.dirName).listFiles().isNullOrEmpty())
        }
    }

    @Test
    fun `given no registered directory exists when swept whole then it reports nothing left`() = runTest {
        assertTrue(sweeper.sweepAll())
    }

    @Test
    fun `given a file outside the registry when swept whole then it is left alone`() = runTest {
        val foreign = fileAt("litert-compile-cache/model.bin", FRESH)

        sweeper.sweepAll()

        assertTrue(foreign.exists())
    }

    private companion object {
        val EXPIRED = TransientCacheDirectory.RETENTION_MILLIS + 1_000L
        val FRESH = TransientCacheDirectory.RETENTION_MILLIS - 60_000L
    }
}
