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
 * Unit tests for [EditFileExecutor].
 *
 * Pins the anchored-edit contract: a non-empty anchor is required, arguments are
 * forwarded verbatim to the workspace, the ambiguous-anchor error reports the
 * occurrence count, and every [WorkspaceError] maps to a readable observation.
 */
class EditFileExecutorTest {

    private lateinit var workspace: AgentWorkspace
    private lateinit var executor: EditFileExecutor

    @Before
    fun setup() {
        workspace = mockk()
        executor = EditFileExecutor(workspace)
    }

    private fun editReturns(file: WorkspaceFile) {
        coEvery { workspace.editText(any(), any(), any()) } returns WorkspaceResult.Success(file)
    }

    private fun editFails(error: WorkspaceError) {
        coEvery { workspace.editText(any(), any(), any()) } returns WorkspaceResult.Failure(error)
    }

    private fun fileOf(path: String, size: Long) =
        WorkspaceFile(relativePath = path, sizeBytes = size, lastModified = 0, isDirectory = false, isText = true)

    @Test
    fun `toolName is edit_file`() {
        assertEquals("edit_file", executor.toolName)
    }

    @Test
    fun `given blank path when execute then errors without touching workspace`() = runTest {
        val result = executor.execute("""{"path":"","oldText":"a","newText":"b"}""")

        assertEquals("Error: missing 'path' argument.", result)
        coVerify(exactly = 0) { workspace.editText(any(), any(), any()) }
    }

    @Test
    fun `given empty oldText when execute then errors without touching workspace`() = runTest {
        val result = executor.execute("""{"path":"a.txt","oldText":"","newText":"b"}""")

        assertTrue(result.contains("non-empty anchor"))
        coVerify(exactly = 0) { workspace.editText(any(), any(), any()) }
    }

    @Test
    fun `given valid args when execute then forwards them and reports success`() = runTest {
        editReturns(fileOf("a.txt", 12))

        val result = executor.execute("""{"path":"a.txt","oldText":"foo","newText":"bar"}""")

        assertEquals("Edited 'a.txt' (12 bytes).", result)
        coVerify { workspace.editText("a.txt", "foo", "bar") }
    }

    @Test
    fun `given empty newText when execute then forwards it as a deletion`() = runTest {
        editReturns(fileOf("a.txt", 4))

        executor.execute("""{"path":"a.txt","oldText":"drop","newText":""}""")

        coVerify { workspace.editText("a.txt", "drop", "") }
    }

    @Test
    fun `given anchor-not-found failure when execute then maps to readable error`() = runTest {
        editFails(WorkspaceError.AnchorNotFound)

        val result = executor.execute("""{"path":"a.txt","oldText":"x","newText":"y"}""")

        assertEquals("Error: 'oldText' was not found in 'a.txt'.", result)
    }

    @Test
    fun `given anchor-not-unique failure when execute then reports the occurrence count`() = runTest {
        editFails(WorkspaceError.AnchorNotUnique(3))

        val result = executor.execute("""{"path":"a.txt","oldText":"x","newText":"y"}""")

        assertEquals(
            "Error: 'oldText' found 3 occurrences in 'a.txt', provide a more specific anchor.",
            result,
        )
    }

    @Test
    fun `given not-found failure when execute then maps to readable error`() = runTest {
        editFails(WorkspaceError.NotFound)

        val result = executor.execute("""{"path":"missing.txt","oldText":"x","newText":"y"}""")

        assertEquals("Error: file 'missing.txt' not found.", result)
    }

    @Test
    fun `given not-text failure when execute then maps to readable error`() = runTest {
        editFails(WorkspaceError.NotAText)

        val result = executor.execute("""{"path":"image.png","oldText":"x","newText":"y"}""")

        assertTrue(result.startsWith("Error: 'image.png' is not a UTF-8 text file"))
    }

    @Test
    fun `given quota-exceeded failure when execute then maps to readable error`() = runTest {
        editFails(WorkspaceError.QuotaExceeded)

        val result = executor.execute("""{"path":"a.txt","oldText":"x","newText":"yyyy"}""")

        assertEquals("Error: editing 'a.txt' would exceed the workspace storage quota.", result)
    }

    @Test
    fun `given an invalid-path failure when execute then names the rules without echoing the path`() = runTest {
        editFails(WorkspaceError.InvalidPath)

        val result = executor.execute("""{"path":"bad","oldText":"x","newText":"y"}""")

        assertEquals(WorkspaceToolMessages.INVALID_PATH, result)
    }
}
