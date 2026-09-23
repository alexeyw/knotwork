package app.knotwork.android.data.tools.local.executors

import app.knotwork.android.domain.models.WorkspaceError
import app.knotwork.android.domain.models.WorkspaceResult
import app.knotwork.android.domain.services.AgentWorkspace
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DeleteFileExecutor].
 *
 * Pins the delete contract: a path is required, the call is forwarded to the
 * workspace, success is acknowledged, and every [WorkspaceError] maps to a
 * readable observation. The DESTRUCTIVE Human-in-the-Loop gate is enforced
 * upstream (`ToolNodeExecutor`) by the tool's risk classification, not here.
 */
class DeleteFileExecutorTest {

    private lateinit var workspace: AgentWorkspace
    private lateinit var executor: DeleteFileExecutor

    @Before
    fun setup() {
        workspace = mockk()
        executor = DeleteFileExecutor(workspace)
    }

    @Test
    fun `toolName is delete_file`() {
        assertEquals("delete_file", executor.toolName)
    }

    @Test
    fun `given blank path when execute then errors without touching workspace`() = runTest {
        val result = executor.execute("""{"path":""}""")

        assertEquals("Error: missing 'path' argument.", result)
        coVerify(exactly = 0) { workspace.delete(any()) }
    }

    @Test
    fun `given existing file when execute then deletes and acknowledges`() = runTest {
        coEvery { workspace.delete(any()) } returns WorkspaceResult.Success(Unit)

        val result = executor.execute("""{"path":"reports/old.md"}""")

        assertEquals("Deleted 'reports/old.md'.", result)
        coVerify { workspace.delete("reports/old.md") }
    }

    @Test
    fun `given not-found failure when execute then maps to readable error`() = runTest {
        coEvery { workspace.delete(any()) } returns WorkspaceResult.Failure(WorkspaceError.NotFound)

        val result = executor.execute("""{"path":"missing.txt"}""")

        assertEquals("Error: file 'missing.txt' not found.", result)
    }

    @Test
    fun `given path-outside failure when execute then maps to readable error`() = runTest {
        coEvery { workspace.delete(any()) } returns WorkspaceResult.Failure(WorkspaceError.PathOutsideWorkspace)

        val result = executor.execute("""{"path":"../../secrets"}""")

        assertEquals("Error: path '../../secrets' is outside the workspace.", result)
    }

    @Test
    fun `given an invalid-path failure when execute then names the rules without echoing the path`() = runTest {
        coEvery { workspace.delete(any()) } returns WorkspaceResult.Failure(WorkspaceError.InvalidPath)

        val result = executor.execute("""{"path":"bad"}""")

        assertEquals(WorkspaceToolMessages.INVALID_PATH, result)
    }
}
