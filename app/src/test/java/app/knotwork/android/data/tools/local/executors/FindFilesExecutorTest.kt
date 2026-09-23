package app.knotwork.android.data.tools.local.executors

import app.knotwork.android.domain.models.WorkspaceError
import app.knotwork.android.domain.models.WorkspaceFile
import app.knotwork.android.domain.models.WorkspaceResult
import app.knotwork.android.domain.services.AgentWorkspace
import app.knotwork.android.domain.services.WorkspaceGlob
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [FindFilesExecutor]: glob filtering over the workspace listing,
 * the no-match and missing-argument paths, the result cap, and error mapping.
 */
class FindFilesExecutorTest {

    private lateinit var workspace: AgentWorkspace
    private lateinit var executor: FindFilesExecutor

    @Before
    fun setup() {
        workspace = mockk()
        executor = FindFilesExecutor(workspace)
    }

    private fun file(path: String) =
        WorkspaceFile(relativePath = path, sizeBytes = 1L, lastModified = 0L, isDirectory = false, isText = true)

    private fun listReturns(vararg files: WorkspaceFile) {
        coEvery { workspace.list() } returns WorkspaceResult.Success(files.toList())
    }

    @Test
    fun `toolName is find_files`() {
        assertEquals("find_files", executor.toolName)
    }

    @Test
    fun `given blank glob when execute then errors without listing`() = runTest {
        val result = executor.execute("""{"glob":""}""")

        assertEquals("Error: missing 'glob' argument.", result)
        coVerify(exactly = 0) { workspace.list() }
    }

    @Test
    fun `given a glob longer than the limit when execute then errors without listing`() = runTest {
        val glob = "a".repeat(WorkspaceGlob.MAX_GLOB_LENGTH + 1)

        val result = executor.execute("""{"glob":"$glob"}""")

        assertEquals(
            "Error: the glob is longer than ${WorkspaceGlob.MAX_GLOB_LENGTH} characters.",
            result,
        )
        coVerify(exactly = 0) { workspace.list() }
    }

    @Test
    fun `given a glob at the limit when execute then it is matched`() = runTest {
        val name = "a".repeat(WorkspaceGlob.MAX_GLOB_LENGTH)
        listReturns(file(name))

        val result = executor.execute("""{"glob":"$name"}""")

        assertTrue(result.startsWith(name))
    }

    @Test
    fun `given glob when execute then returns only matching files`() = runTest {
        listReturns(file("a.md"), file("b.txt"), file("reports/c.md"))

        val result = executor.execute("""{"glob":"*.md"}""")

        assertTrue(result.contains("a.md"))
        assertFalse("nested file must not match single-star glob", result.contains("reports/c.md"))
        assertFalse(result.contains("b.txt"))
    }

    @Test
    fun `given cross-directory glob when execute then matches nested files`() = runTest {
        listReturns(file("a.md"), file("reports/c.md"), file("reports/2026/d.md"))

        val result = executor.execute("""{"glob":"reports/**"}""")

        assertTrue(result.contains("reports/c.md"))
        assertTrue(result.contains("reports/2026/d.md"))
        assertFalse(result.contains("\na.md"))
    }

    @Test
    fun `given no matches when execute then reports none`() = runTest {
        listReturns(file("a.txt"))

        val result = executor.execute("""{"glob":"*.md"}""")

        assertEquals("No files match '*.md'.", result)
    }

    @Test
    fun `given more matches than the cap when execute then truncates with marker`() = runTest {
        val files = (1..(WorkspaceListingFormat.MAX_LINES + 3))
            .map { file("doc%04d.md".format(it)) }
            .toTypedArray()
        listReturns(*files)

        val result = executor.execute("""{"glob":"*.md"}""")

        assertTrue(result.contains("[... 3 more entries truncated]"))
    }

    @Test
    fun `given workspace listing failure when execute then maps to readable error`() = runTest {
        coEvery { workspace.list() } returns WorkspaceResult.Failure(WorkspaceError.NotFound)

        val result = executor.execute("""{"glob":"*.md"}""")

        assertEquals("Error: could not search the workspace.", result)
    }
}
