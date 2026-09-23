package app.knotwork.android.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Unit tests for [WorkspaceShareCopies] on its own, for what the workspace tests
 * cannot reach: a copy that fails half-way leaves nothing staged.
 */
class WorkspaceShareCopiesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `given a source that cannot be read when staged then it throws and leaves no slot behind`() {
        val shareDir = tempFolder.newFolder("shared")
        val source = tempFolder.newFile("report.md").apply {
            writeText("secret")
            setReadable(false)
        }
        // A superuser reads anything; the failure this test needs would not happen.
        assumeFalse("running as a user that ignores file modes", source.canRead())
        val copies = WorkspaceShareCopies(root = { shareDir })

        val thrown = runCatching { copies.stage(source, "report.md") }.exceptionOrNull()

        assertTrue("expected an IOException, got $thrown", thrown is IOException)
        assertEquals("a failed copy must leave no slot", emptyList<String>(), shareDir.list()!!.toList())
    }

    @Test
    fun `given two files when one is discarded then only its copies go`() {
        val shareDir = tempFolder.newFolder("shared")
        val a = tempFolder.newFile("a.md").apply { writeText("a") }
        val b = tempFolder.newFile("b.md").apply { writeText("b") }
        val copies = WorkspaceShareCopies(root = { shareDir })
        val aCopies = listOf(copies.stage(a, "dir/a.md"), copies.stage(a, "dir/a.md"))
        val bCopy = copies.stage(b, "b.md")

        assertEquals(2, copies.discard("dir/a.md"))

        aCopies.forEach { assertTrue(!it.exists()) }
        assertTrue(bCopy.exists())
        assertEquals(1, shareDir.list()!!.size)
        assertTrue(File(shareDir, bCopy.parentFile!!.name).isDirectory)
    }
}
