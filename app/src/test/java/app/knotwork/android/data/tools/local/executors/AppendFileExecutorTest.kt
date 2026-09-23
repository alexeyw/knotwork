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
 * Unit tests for [AppendFileExecutor].
 *
 * Pins the append contract: argument parsing (path / content), delegation to
 * [AgentWorkspace.appendText] (no overwrite flag — append is always additive), the
 * byte-count observation, and the mapping of every [WorkspaceError] to a readable
 * observation string.
 */
class AppendFileExecutorTest {

    private lateinit var workspace: AgentWorkspace
    private lateinit var executor: AppendFileExecutor

    @Before
    fun setup() {
        workspace = mockk()
        executor = AppendFileExecutor(workspace)
    }

    private fun appendReturns(file: WorkspaceFile) {
        coEvery { workspace.appendText(any(), any()) } returns WorkspaceResult.Success(file)
    }

    private fun fileOf(path: String, size: Long) =
        WorkspaceFile(relativePath = path, sizeBytes = size, lastModified = 0, isDirectory = false, isText = true)

    @Test
    fun `toolName is append_file`() {
        assertEquals("append_file", executor.toolName)
    }

    @Test
    fun `given blank path when execute then errors without touching workspace`() = runTest {
        val result = executor.execute("""{"path":"","content":"x"}""")

        assertEquals("Error: missing 'path' argument.", result)
        coVerify(exactly = 0) { workspace.appendText(any(), any()) }
    }

    @Test
    fun `given path and content when execute then appends and reports bytes`() = runTest {
        appendReturns(fileOf("reports/daily.md", 42))

        val result = executor.execute("""{"path":"reports/daily.md","content":"hello"}""")

        assertEquals("Appended 5 bytes to 'reports/daily.md' (now 42 bytes).", result)
        coVerify { workspace.appendText("reports/daily.md", "hello") }
    }

    @Test
    fun `given missing content when execute then appends empty string`() = runTest {
        appendReturns(fileOf("a.txt", 0))

        executor.execute("""{"path":"a.txt"}""")

        coVerify { workspace.appendText("a.txt", "") }
    }

    @Test
    fun `given is-directory failure when execute then maps to readable error`() = runTest {
        coEvery { workspace.appendText(any(), any()) } returns WorkspaceResult.Failure(WorkspaceError.IsDirectory)

        val result = executor.execute("""{"path":"reports","content":"x"}""")

        assertTrue(result.contains("is a directory"))
    }

    @Test
    fun `given not-a-text failure when execute then maps to readable error`() = runTest {
        coEvery { workspace.appendText(any(), any()) } returns WorkspaceResult.Failure(WorkspaceError.NotAText)

        val result = executor.execute("""{"path":"photo.jpg","content":"x"}""")

        assertTrue(result.contains("not a UTF-8 text file"))
    }

    @Test
    fun `given path-outside failure when execute then maps to readable error`() = runTest {
        coEvery { workspace.appendText(any(), any()) } returns
            WorkspaceResult.Failure(WorkspaceError.PathOutsideWorkspace)

        val result = executor.execute("""{"path":"../escape","content":"x"}""")

        assertEquals("Error: path '../escape' is outside the workspace.", result)
    }

    @Test
    fun `given too-large failure when execute then maps to readable error`() = runTest {
        coEvery { workspace.appendText(any(), any()) } returns WorkspaceResult.Failure(WorkspaceError.TooLarge)

        val result = executor.execute("""{"path":"big.txt","content":"x"}""")

        assertEquals("Error: appending to 'big.txt' would exceed the per-file size limit.", result)
    }

    @Test
    fun `given quota-exceeded failure when execute then maps to readable error`() = runTest {
        coEvery { workspace.appendText(any(), any()) } returns WorkspaceResult.Failure(WorkspaceError.QuotaExceeded)

        val result = executor.execute("""{"path":"a.txt","content":"x"}""")

        assertEquals("Error: appending to 'a.txt' would exceed the workspace storage quota.", result)
    }

    @Test
    fun `given an invalid-path failure when execute then names the rules without echoing the path`() = runTest {
        coEvery { workspace.appendText(any(), any()) } returns
            WorkspaceResult.Failure(WorkspaceError.InvalidPath)

        val result = executor.execute("""{"path":"bad","content":"x"}""")

        assertEquals(WorkspaceToolMessages.INVALID_PATH, result)
    }
}
