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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [WriteFileExecutor].
 *
 * Pins the write contract: argument parsing (path / content / overwrite), the
 * default no-overwrite semantics surfaced as an actionable hint, delegation to
 * the workspace, and the mapping of every [WorkspaceError] to a readable
 * observation string.
 */
class WriteFileExecutorTest {

    private lateinit var workspace: AgentWorkspace
    private lateinit var executor: WriteFileExecutor

    @Before
    fun setup() {
        workspace = mockk()
        executor = WriteFileExecutor(workspace)
    }

    private fun writeReturns(file: WorkspaceFile) {
        coEvery { workspace.writeText(any(), any(), any()) } returns WorkspaceResult.Success(file)
    }

    private fun fileOf(path: String, size: Long) =
        WorkspaceFile(relativePath = path, sizeBytes = size, lastModified = 0, isDirectory = false, isText = true)

    @Test
    fun `toolName is write_file`() {
        assertEquals("write_file", executor.toolName)
    }

    @Test
    fun `given blank path when execute then errors without touching workspace`() = runTest {
        val result = executor.execute("""{"path":"","content":"x"}""")

        assertEquals("Error: missing 'path' argument.", result)
        coVerify(exactly = 0) { workspace.writeText(any(), any(), any()) }
    }

    @Test
    fun `given path and content when execute then writes with overwrite false by default`() = runTest {
        writeReturns(fileOf("a.txt", 5))

        val result = executor.execute("""{"path":"a.txt","content":"hello"}""")

        assertEquals("Wrote 5 bytes to 'a.txt'.", result)
        coVerify { workspace.writeText("a.txt", "hello", false) }
    }

    @Test
    fun `given overwrite true when execute then forwards the flag`() = runTest {
        writeReturns(fileOf("a.txt", 3))

        executor.execute("""{"path":"a.txt","content":"new","overwrite":true}""")

        coVerify { workspace.writeText("a.txt", "new", true) }
    }

    @Test
    fun `given missing content when execute then writes empty string`() = runTest {
        writeReturns(fileOf("a.txt", 0))

        executor.execute("""{"path":"a.txt"}""")

        coVerify { workspace.writeText("a.txt", "", false) }
    }

    @Test
    fun `given already-exists failure when execute then suggests overwrite flag`() = runTest {
        coEvery { workspace.writeText(any(), any(), any()) } returns
            WorkspaceResult.Failure(WorkspaceError.AlreadyExists)

        val result = executor.execute("""{"path":"a.txt","content":"x"}""")

        assertTrue(result.contains("already exists"))
        assertTrue(result.contains("overwrite"))
    }

    @Test
    fun `given is-directory failure when execute then errors without an overwrite hint`() = runTest {
        coEvery { workspace.writeText(any(), any(), any()) } returns
            WorkspaceResult.Failure(WorkspaceError.IsDirectory)

        val result = executor.execute("""{"path":"reports","content":"x"}""")

        assertTrue(result.contains("is a directory"))
        // The hint that would otherwise loop the agent must NOT appear for a directory.
        assertFalse(result.contains("overwrite"))
    }

    @Test
    fun `given path-outside failure when execute then maps to readable error`() = runTest {
        coEvery { workspace.writeText(any(), any(), any()) } returns
            WorkspaceResult.Failure(WorkspaceError.PathOutsideWorkspace)

        val result = executor.execute("""{"path":"../escape","content":"x"}""")

        assertEquals("Error: path '../escape' is outside the workspace.", result)
    }

    @Test
    fun `given too-large failure when execute then maps to readable error`() = runTest {
        coEvery { workspace.writeText(any(), any(), any()) } returns
            WorkspaceResult.Failure(WorkspaceError.TooLarge)

        val result = executor.execute("""{"path":"big.txt","content":"x"}""")

        assertEquals("Error: content for 'big.txt' exceeds the per-file size limit.", result)
    }

    @Test
    fun `given quota-exceeded failure when execute then maps to readable error`() = runTest {
        coEvery { workspace.writeText(any(), any(), any()) } returns
            WorkspaceResult.Failure(WorkspaceError.QuotaExceeded)

        val result = executor.execute("""{"path":"a.txt","content":"x"}""")

        assertEquals("Error: writing 'a.txt' would exceed the workspace storage quota.", result)
    }

    @Test
    fun `given an invalid-path failure when execute then names the rules without echoing the path`() = runTest {
        coEvery { workspace.writeText(any(), any(), any()) } returns
            WorkspaceResult.Failure(WorkspaceError.InvalidPath)

        val result = executor.execute("""{"path":"bad","content":"x"}""")

        assertEquals(WorkspaceToolMessages.INVALID_PATH, result)
    }
}
