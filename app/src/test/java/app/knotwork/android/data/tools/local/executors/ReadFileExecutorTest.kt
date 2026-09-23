package app.knotwork.android.data.tools.local.executors

import app.knotwork.android.domain.models.WorkspaceError
import app.knotwork.android.domain.models.WorkspaceResult
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.AgentWorkspace
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ReadFileExecutor].
 *
 * Pins the read contract: token-budget truncation with the byte-count marker,
 * byte-offset paging that stitches back together without loss (including across
 * a multi-byte UTF-8 boundary), end-of-file handling and the mapping of every
 * [WorkspaceError] to a readable observation string.
 */
class ReadFileExecutorTest {

    private lateinit var workspace: AgentWorkspace
    private lateinit var settings: SettingsRepository
    private lateinit var executor: ReadFileExecutor

    @Before
    fun setup() {
        workspace = mockk()
        settings = mockk()
        executor = ReadFileExecutor(workspace, settings)
        // Generous default budget; individual tests override via budget().
        budget(1_000)
    }

    private fun budget(tokens: Int) {
        every { settings.workspaceReadTokenBudget } returns flowOf(tokens)
    }

    private fun readReturns(text: String) {
        coEvery { workspace.readText(any()) } returns WorkspaceResult.Success(text)
    }

    /** Strips the trailing truncation marker line, if present. */
    private fun body(output: String): String = output.substringBefore("\n[... truncated,")

    /** Extracts the next byte offset advertised by the truncation marker. */
    private fun nextOffset(output: String): Int =
        Regex("use offset (\\d+) to continue").find(output)!!.groupValues[1].toInt()

    @Test
    fun `toolName is read_file`() {
        assertEquals("read_file", executor.toolName)
    }

    @Test
    fun `given blank path when execute then errors without touching workspace`() = runTest {
        val result = executor.execute("""{"path":""}""")

        assertEquals("Error: missing 'path' argument.", result)
        coVerify(exactly = 0) { workspace.readText(any()) }
    }

    @Test
    fun `given small file under budget when execute then returns full text without marker`() = runTest {
        readReturns("hello world")

        val result = executor.execute("""{"path":"a.txt"}""")

        assertEquals("hello world", result)
    }

    @Test
    fun `given empty file when execute then reports empty`() = runTest {
        readReturns("")

        val result = executor.execute("""{"path":"a.txt"}""")

        assertEquals("(empty file)", result)
    }

    @Test
    fun `given file larger than budget when execute then truncates with byte marker`() = runTest {
        budget(2) // 2 tokens * 4 = 8 bytes
        readReturns("0123456789") // 10 ASCII bytes

        val result = executor.execute("""{"path":"a.txt"}""")

        assertEquals("01234567\n[... truncated, 2 bytes remain — use offset 8 to continue]", result)
    }

    @Test
    fun `given offset paging over ascii when stitched then reconstructs original without loss`() = runTest {
        val full = "0123456789"
        readReturns(full)

        // Follow the offset advertised in each marker, exactly as the agent is told to.
        val r1 = executor.execute("""{"path":"a.txt","limit":4}""")
        val r2 = executor.execute("""{"path":"a.txt","offset":${nextOffset(r1)},"limit":4}""")
        val r3 = executor.execute("""{"path":"a.txt","offset":${nextOffset(r2)},"limit":4}""")

        assertEquals("0123", body(r1))
        assertEquals("4567", body(r2))
        assertEquals("89", body(r3))
        assertEquals(full, body(r1) + body(r2) + body(r3))
        // The final chunk reaches EOF, so it carries no truncation marker.
        assertFalse(r3.contains("truncated"))
    }

    @Test
    fun `given multibyte char straddling the limit when paging then char is not split or corrupted`() = runTest {
        val full = "abécd" // bytes: a b C3 A9 c d  (é is 2 bytes)
        readReturns(full)

        // limit 3 lands inside the 2-byte 'é'; the window backs up to before it. The marker
        // then advertises the exact byte offset to resume from — the agent never counts bytes.
        val r1 = executor.execute("""{"path":"a.txt","limit":3}""")
        val b1 = body(r1)
        assertEquals("ab", b1)
        assertFalse("decoded chunk must not contain the replacement char", b1.contains('�'))
        // The next offset is the byte after "ab" (2), not the requested limit (3).
        assertEquals(2, nextOffset(r1))

        val r2 = executor.execute("""{"path":"a.txt","offset":${nextOffset(r1)},"limit":3}""")
        val r3 = executor.execute("""{"path":"a.txt","offset":${nextOffset(r2)},"limit":3}""")

        assertEquals(full, b1 + body(r2) + body(r3))
    }

    @Test
    fun `given offset landing mid-character when execute then drops the leading partial byte`() = runTest {
        readReturns("abécd") // é occupies bytes index 2..3

        // Start at byte 3 (the continuation byte of 'é'); it must be dropped, leaving "cd".
        val result = executor.execute("""{"path":"a.txt","offset":3}""")

        assertEquals("cd", result)
    }

    @Test
    fun `given explicit limit above budget when execute then budget still caps`() = runTest {
        budget(2) // 8-byte ceiling
        readReturns("0123456789")

        val result = executor.execute("""{"path":"a.txt","limit":1000}""")

        assertEquals("01234567\n[... truncated, 2 bytes remain — use offset 8 to continue]", result)
    }

    @Test
    fun `given offset past end of file when execute then reports past-end`() = runTest {
        readReturns("0123456789")

        val result = executor.execute("""{"path":"a.txt","offset":50}""")

        assertEquals("[offset 50 is at or past end of file (10 bytes total)]", result)
    }

    @Test
    fun `given not-found failure when execute then maps to readable error`() = runTest {
        coEvery { workspace.readText(any()) } returns WorkspaceResult.Failure(WorkspaceError.NotFound)

        val result = executor.execute("""{"path":"missing.txt"}""")

        assertEquals("Error: file 'missing.txt' not found.", result)
    }

    @Test
    fun `given path-outside failure when execute then maps to readable error`() = runTest {
        coEvery { workspace.readText(any()) } returns WorkspaceResult.Failure(WorkspaceError.PathOutsideWorkspace)

        val result = executor.execute("""{"path":"../secrets"}""")

        assertEquals("Error: path '../secrets' is outside the workspace.", result)
    }

    @Test
    fun `given not-text failure when execute then maps to readable error`() = runTest {
        coEvery { workspace.readText(any()) } returns WorkspaceResult.Failure(WorkspaceError.NotAText)

        val result = executor.execute("""{"path":"image.png"}""")

        assertTrue(result.startsWith("Error: 'image.png' is not a UTF-8 text file"))
    }

    @Test
    fun `given too-large failure when execute then maps to readable error`() = runTest {
        coEvery { workspace.readText(any()) } returns WorkspaceResult.Failure(WorkspaceError.TooLarge)

        val result = executor.execute("""{"path":"big.log"}""")

        assertEquals("Error: 'big.log' exceeds the per-file read limit.", result)
    }

    @Test
    fun `given an invalid-path failure when execute then names the rules without echoing the path`() = runTest {
        coEvery { workspace.readText(any()) } returns WorkspaceResult.Failure(WorkspaceError.InvalidPath)

        val result = executor.execute("""{"path":"bad"}""")

        assertEquals(WorkspaceToolMessages.INVALID_PATH, result)
    }
}
