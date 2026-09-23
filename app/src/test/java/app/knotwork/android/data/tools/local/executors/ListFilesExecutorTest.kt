package app.knotwork.android.data.tools.local.executors

import app.knotwork.android.domain.models.WorkspaceError
import app.knotwork.android.domain.models.WorkspaceFile
import app.knotwork.android.domain.models.WorkspaceResult
import app.knotwork.android.domain.services.AgentWorkspace
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ListFilesExecutor]: formatting and stable order, the optional
 * sub-directory filter (validated through the containment gate), empty states,
 * the result cap, and error mapping.
 */
class ListFilesExecutorTest {

    private lateinit var workspace: AgentWorkspace
    private lateinit var executor: ListFilesExecutor

    @Before
    fun setup() {
        workspace = mockk()
        executor = ListFilesExecutor(workspace)
    }

    private fun file(path: String, size: Long = 1L) =
        WorkspaceFile(relativePath = path, sizeBytes = size, lastModified = 0L, isDirectory = false, isText = true)

    private fun listReturns(vararg files: WorkspaceFile) {
        coEvery { workspace.list() } returns WorkspaceResult.Success(files.toList())
    }

    @Test
    fun `toolName is list_files`() {
        assertEquals("list_files", executor.toolName)
    }

    @Test
    fun `given empty workspace when execute then reports empty`() = runTest {
        listReturns()

        val result = executor.execute("{}")

        assertEquals("Workspace is empty.", result)
    }

    @Test
    fun `given files when execute then formats path size and mtime`() = runTest {
        listReturns(file("a.txt", 12L), file("reports/b.md", 34L))

        val result = executor.execute("{}")

        assertEquals(
            "a.txt\t12 bytes\t1970-01-01T00:00:00Z\n" +
                "reports/b.md\t34 bytes\t1970-01-01T00:00:00Z",
            result,
        )
    }

    @Test
    fun `given a name carrying a line break when execute then the entry stays on one line`() = runTest {
        // A name created before the name rules (or by anything but the workspace) may still
        // carry a line break; rendered raw it would forge a second entry.
        listReturns(file("notes.md\nFORGED.txt\t142 bytes"))

        val result = executor.execute("{}")

        assertEquals(1, result.lines().size)
        assertTrue(result.startsWith("notes.md\\nFORGED.txt\\t142 bytes\t1 bytes\t"))
    }

    @Test
    fun `given path filter when execute then lists only that subtree`() = runTest {
        coEvery { workspace.resolve("reports") } returns WorkspaceResult.Success(file("reports", 0L))
        listReturns(file("a.txt"), file("reports/b.md"), file("reports/2026/c.md"))

        val result = executor.execute("""{"path":"reports"}""")

        assertTrue(result.contains("reports/b.md"))
        assertTrue(result.contains("reports/2026/c.md"))
        assertTrue("top-level file must be excluded", !result.contains("a.txt"))
    }

    @Test
    fun `given dot-relative path when execute then filters by the canonical prefix`() = runTest {
        // The model passes a non-canonical path; resolve() normalises it to "reports".
        coEvery { workspace.resolve("./reports") } returns WorkspaceResult.Success(file("reports", 0L))
        listReturns(file("a.txt"), file("reports/b.md"))

        val result = executor.execute("""{"path":"./reports"}""")

        assertTrue(result.contains("reports/b.md"))
        assertTrue("top-level file must be excluded", !result.contains("a.txt"))
    }

    @Test
    fun `given path that canonicalises to the root when execute then lists whole workspace`() = runTest {
        // e.g. "reports/.." resolves back to the workspace root (empty relative path).
        coEvery { workspace.resolve("reports/..") } returns WorkspaceResult.Success(file("", 0L))
        listReturns(file("a.txt"), file("reports/b.md"))

        val result = executor.execute("""{"path":"reports/.."}""")

        assertTrue(result.contains("a.txt"))
        assertTrue(result.contains("reports/b.md"))
    }

    @Test
    fun `given path filter with no matches when execute then reports empty subtree`() = runTest {
        coEvery { workspace.resolve("empty") } returns WorkspaceResult.Success(file("empty", 0L))
        listReturns(file("a.txt"))

        val result = executor.execute("""{"path":"empty"}""")

        assertEquals("No files under 'empty'.", result)
    }

    @Test
    fun `given out-of-workspace path when execute then errors without listing`() = runTest {
        coEvery { workspace.resolve("../etc") } returns WorkspaceResult.Failure(WorkspaceError.PathOutsideWorkspace)

        val result = executor.execute("""{"path":"../etc"}""")

        assertEquals("Error: path '../etc' is outside the workspace.", result)
        coVerify(exactly = 0) { workspace.list() }
    }

    @Test
    fun `given more files than the cap when execute then truncates with marker`() = runTest {
        val files = (1..(WorkspaceListingFormat.MAX_LINES + 5))
            .map { file("f%04d.txt".format(it)) }
            .toTypedArray()
        listReturns(*files)

        val result = executor.execute("{}")

        assertTrue(result.contains("[... 5 more entries truncated]"))
        assertEquals(WorkspaceListingFormat.MAX_LINES + 1, result.lines().size)
    }

    @Test
    fun `given an invalid-path failure when execute then names the rules without echoing the path`() = runTest {
        coEvery { workspace.resolve(any()) } returns WorkspaceResult.Failure(WorkspaceError.InvalidPath)

        val result = executor.execute("""{"path":"bad"}""")

        assertEquals(WorkspaceToolMessages.INVALID_PATH, result)
    }
}
