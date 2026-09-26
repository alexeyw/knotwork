package app.knotwork.android.data.local

import android.content.Context
import app.knotwork.android.domain.models.WorkspaceError
import app.knotwork.android.domain.models.WorkspaceResult
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Verifies the [AgentWorkspaceImpl] foundation: the path-traversal containment
 * boundary (the single canonicalisation gate), the per-file and total-size
 * quotas at their exact boundaries, the text/binary read distinction, and the
 * overwrite semantics — each surfaced as a typed [WorkspaceError].
 */
class AgentWorkspaceImplTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Stands in for the app's cache directory, where share copies are staged. */
    @get:Rule
    val cacheFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setup() {
        context = mockk()
        every { context.filesDir } returns tempFolder.root
        every { context.cacheDir } returns cacheFolder.root
    }

    // region helpers

    private fun workspaceWith(
        maxFileSize: Long = DEFAULT_FILE_SIZE,
        maxTotal: Long = DEFAULT_TOTAL,
        maxEntries: Int? = null,
    ): AgentWorkspaceImpl {
        val settings = mockk<SettingsRepository>()
        every { settings.workspaceMaxFileSizeBytes } returns flowOf(maxFileSize)
        every { settings.workspaceMaxTotalBytes } returns flowOf(maxTotal)
        // Without an explicit ceiling, the constructor Hilt uses — so every other test
        // runs against the shipped entry ceiling, not a test value.
        return if (maxEntries == null) {
            AgentWorkspaceImpl(context, settings)
        } else {
            AgentWorkspaceImpl(context, settings, maxEntries)
        }
    }

    /** The on-disk workspace root the implementation manages. */
    private fun workspaceRoot() = File(tempFolder.root, "agent_workspace")

    /** Writes a raw file directly under the workspace root, bypassing the workspace API. */
    private fun putRawFile(relativePath: String, bytes: ByteArray) {
        val file = File(workspaceRoot(), relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private fun <T> assertSuccess(result: WorkspaceResult<T>): T {
        assertTrue("expected Success but was $result", result is WorkspaceResult.Success)
        return (result as WorkspaceResult.Success).value
    }

    private fun assertFailure(result: WorkspaceResult<*>, expected: WorkspaceError) {
        assertTrue("expected Failure($expected) but was $result", result is WorkspaceResult.Failure)
        assertEquals(expected, (result as WorkspaceResult.Failure).error)
    }

    // endregion

    @Test
    fun `given text content when writeText then readText round-trips and file lands in workspace`() = runTest {
        val workspace = workspaceWith()

        val written = assertSuccess(workspace.writeText("notes/todo.md", "hello world"))
        assertEquals("notes/todo.md", written.relativePath)
        assertTrue(written.isText)
        assertFalse(written.isDirectory)

        assertEquals("hello world", assertSuccess(workspace.readText("notes/todo.md")))
        assertTrue(File(workspaceRoot(), "notes/todo.md").isFile)
    }

    @Test
    fun `given existing file when writeText without overwrite then AlreadyExists`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("a.txt", "first"))

        assertFailure(workspace.writeText("a.txt", "second"), WorkspaceError.AlreadyExists)
        assertEquals("first", assertSuccess(workspace.readText("a.txt")))
    }

    @Test
    fun `given existing file when writeText with overwrite then replaced`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("a.txt", "first"))

        assertSuccess(workspace.writeText("a.txt", "second", overwrite = true))
        assertEquals("second", assertSuccess(workspace.readText("a.txt")))
    }

    @Test
    fun `given no file when appendText then creates it with the content`() = runTest {
        val workspace = workspaceWith()

        val file = assertSuccess(workspace.appendText("reports/daily.md", "first entry"))

        assertEquals("reports/daily.md", file.relativePath)
        assertEquals("first entry", assertSuccess(workspace.readText("reports/daily.md")))
    }

    @Test
    fun `given existing file when appendText then concatenates to the end preserving prior content`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("log.md", "line 1\n"))

        assertSuccess(workspace.appendText("log.md", "line 2\n"))
        assertSuccess(workspace.appendText("log.md", "line 3\n"))

        assertEquals("line 1\nline 2\nline 3\n", assertSuccess(workspace.readText("log.md")))
    }

    @Test
    fun `given content that would exceed the total quota when appendText then QuotaExceeded and file unchanged`() =
        runTest {
            val workspace = workspaceWith(maxTotal = 10)
            assertSuccess(workspace.writeText("a.txt", "12345"))

            assertFailure(workspace.appendText("a.txt", "678901"), WorkspaceError.QuotaExceeded)
            assertEquals("12345", assertSuccess(workspace.readText("a.txt")))
        }

    @Test
    fun `given a directory path when appendText then IsDirectory`() = runTest {
        val workspace = workspaceWith()
        File(workspaceRoot(), "reports").mkdirs()

        assertFailure(workspace.appendText("reports", "x"), WorkspaceError.IsDirectory)
    }

    @Test
    fun `given a binary file when appendText then NotAText and file unchanged`() = runTest {
        val workspace = workspaceWith()
        val binary = byteArrayOf(0x00, 0x01, 0x02, 0x00)
        putRawFile("blob.bin", binary)

        assertFailure(workspace.appendText("blob.bin", "text"), WorkspaceError.NotAText)
        assertTrue(File(workspaceRoot(), "blob.bin").readBytes().contentEquals(binary))
    }

    @Test
    fun `given path escaping the workspace when appendText then PathOutsideWorkspace`() = runTest {
        val workspace = workspaceWith()

        assertFailure(workspace.appendText("../escape.txt", "x"), WorkspaceError.PathOutsideWorkspace)
    }

    @Test
    fun `given non-existent in-bounds path when resolve then succeeds with empty metadata`() = runTest {
        val workspace = workspaceWith()

        val resolved = assertSuccess(workspace.resolve("future/report.md"))
        assertEquals("future/report.md", resolved.relativePath)
        assertEquals(0L, resolved.sizeBytes)
        assertFalse(resolved.isDirectory)
    }

    @Test
    fun `given parent-traversal path when readText then PathOutsideWorkspace`() = runTest {
        val workspace = workspaceWith()

        assertFailure(workspace.readText("../secret.txt"), WorkspaceError.PathOutsideWorkspace)
        assertFailure(workspace.readText("../../shared_prefs/keys.xml"), WorkspaceError.PathOutsideWorkspace)
    }

    @Test
    fun `given absolute path when writeText then PathOutsideWorkspace`() = runTest {
        val workspace = workspaceWith()

        assertFailure(
            workspace.writeText("/etc/passwd", "x"),
            WorkspaceError.PathOutsideWorkspace,
        )
    }

    @Test
    fun `given sibling-directory escape when writeText then PathOutsideWorkspace`() = runTest {
        val workspace = workspaceWith()

        // `agent_workspace_evil` shares the `agent_workspace` prefix; the trailing-separator
        // check in canonicalResolve must still reject it.
        assertFailure(
            workspace.writeText("../agent_workspace_evil/loot.txt", "x"),
            WorkspaceError.PathOutsideWorkspace,
        )
    }

    @Test
    fun `given traversal that stays inside when writeText then succeeds`() = runTest {
        val workspace = workspaceWith()

        val written = assertSuccess(workspace.writeText("a/../b.txt", "inside"))
        assertEquals("b.txt", written.relativePath)
        assertEquals("inside", assertSuccess(workspace.readText("b.txt")))
    }

    @Test
    fun `given symlink escaping the root when resolve then PathOutsideWorkspace`() = runTest {
        val outside = tempFolder.newFolder("outside")
        File(outside, "secret.txt").writeText("top secret")
        workspaceRoot().mkdirs()
        try {
            Files.createSymbolicLink(File(workspaceRoot(), "link").toPath(), outside.toPath())
        } catch (e: Exception) {
            assumeNoException("Symlinks unsupported on this platform", e)
        }

        val workspace = workspaceWith()
        assertFailure(workspace.readText("link/secret.txt"), WorkspaceError.PathOutsideWorkspace)
    }

    @Test
    fun `given missing file when readText then NotFound`() = runTest {
        val workspace = workspaceWith()

        assertFailure(workspace.readText("nope.txt"), WorkspaceError.NotFound)
    }

    @Test
    fun `given binary file when readText then NotAText`() = runTest {
        val workspace = workspaceWith()
        putRawFile("image.bin", byteArrayOf(0x00, 0x01, 0x02, 0x7F.toByte(), 0xFF.toByte()))

        assertFailure(workspace.readText("image.bin"), WorkspaceError.NotAText)
    }

    @Test
    fun `given content exactly at the per-file limit when writeText then succeeds`() = runTest {
        val workspace = workspaceWith(maxFileSize = 10L)

        assertSuccess(workspace.writeText("ok.txt", "0123456789")) // 10 ASCII bytes
    }

    @Test
    fun `given content one byte over the per-file limit when writeText then TooLarge`() = runTest {
        val workspace = workspaceWith(maxFileSize = 10L)

        assertFailure(workspace.writeText("big.txt", "01234567890"), WorkspaceError.TooLarge) // 11 bytes
    }

    @Test
    fun `given on-disk file larger than the per-file limit when readText then TooLarge`() = runTest {
        putRawFile("huge.txt", ByteArray(20) { 'a'.code.toByte() })
        val workspace = workspaceWith(maxFileSize = 10L)

        assertFailure(workspace.readText("huge.txt"), WorkspaceError.TooLarge)
    }

    @Test
    fun `given a write that exactly fills the total quota when writeText then succeeds`() = runTest {
        val workspace = workspaceWith(maxFileSize = 100L, maxTotal = 20L)
        assertSuccess(workspace.writeText("a.txt", "x".repeat(15))) // total 15

        assertSuccess(workspace.writeText("b.txt", "x".repeat(5))) // total exactly 20
    }

    @Test
    fun `given a write that crosses the total quota when writeText then QuotaExceeded`() = runTest {
        val workspace = workspaceWith(maxFileSize = 100L, maxTotal = 20L)
        assertSuccess(workspace.writeText("a.txt", "x".repeat(15))) // total 15

        // 15 + 10 = 25 > 20 — the cached total must reflect the first write.
        assertFailure(workspace.writeText("b.txt", "x".repeat(10)), WorkspaceError.QuotaExceeded)
    }

    @Test
    fun `given an overwrite shrinking a file when writeText then prior size is not double-counted`() = runTest {
        val workspace = workspaceWith(maxFileSize = 100L, maxTotal = 20L)
        assertSuccess(workspace.writeText("a.txt", "x".repeat(20))) // total 20, at the ceiling

        // Replacing the 20-byte file with a 5-byte one nets to 5, well within quota.
        val written = assertSuccess(workspace.writeText("a.txt", "x".repeat(5), overwrite = true))
        assertEquals(5L, written.sizeBytes)
    }

    @Test
    fun `given an empty workspace when list then empty`() = runTest {
        val workspace = workspaceWith()

        assertEquals(emptyList<Any>(), assertSuccess(workspace.list()))
    }

    @Test
    fun `given text and binary files when list then sorted with text flag set per file`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("z/note.md", "text"))
        assertSuccess(workspace.writeText("a.txt", "more text"))
        putRawFile("blob.bin", byteArrayOf(0x00, 0x01))

        val listed = assertSuccess(workspace.list())
        assertEquals(listOf("a.txt", "blob.bin", "z/note.md"), listed.map { it.relativePath })
        assertEquals(
            mapOf("a.txt" to true, "blob.bin" to false, "z/note.md" to true),
            listed.associate {
                it.relativePath to
                    it.isText
            },
        )
    }

    @Test
    fun `given a binary marker only beyond the sniff window when list then file still reports as text`() = runTest {
        val workspace = workspaceWith()
        // 8 KB of ASCII then a NUL: the NUL sits past the sniff window, so the advisory
        // isText flag stays true. readText (full validation) remains authoritative.
        putRawFile("long.txt", ByteArray(SNIFF_BYTES) { 'a'.code.toByte() } + byteArrayOf(0))

        val entry = assertSuccess(workspace.list()).single { it.relativePath == "long.txt" }
        assertTrue(entry.isText)
    }

    @Test
    fun `given a small non-ASCII text file when list then it reports as text`() = runTest {
        val workspace = workspaceWith()
        // Cyrillic Markdown: every character is a multi-byte UTF-8 sequence.
        putRawFile("отчёт.md", "# Отчёт\n\nКраткое содержание исследования.".toByteArray(Charsets.UTF_8))

        val entry = assertSuccess(workspace.list()).single { it.relativePath == "отчёт.md" }
        assertTrue("non-ASCII Markdown must be detected as text", entry.isText)
        // ...and it must therefore be previewable.
        assertSuccess(workspace.readTextPreview("отчёт.md", maxBytes = SNIFF_BYTES))
    }

    @Test
    fun `given non-ASCII text straddling the sniff window when list then it reports as text`() = runTest {
        val workspace = workspaceWith()
        // One ASCII byte then Cyrillic so the 8 KB sniff window ends mid-character;
        // the boundary tail-drop must keep the file classified as text, not binary.
        val content = "a" + "я".repeat(SNIFF_BYTES) // far larger than the sniff window
        putRawFile("big-ru.md", content.toByteArray(Charsets.UTF_8))

        val entry = assertSuccess(workspace.list()).single { it.relativePath == "big-ru.md" }
        assertTrue("multi-byte text across the sniff boundary must stay text", entry.isText)
    }

    @Test
    fun `given non-ASCII text whose window ends on a char boundary when list then it reports as text`() = runTest {
        val workspace = workspaceWith()
        // 5 000 two-byte chars = 10 000 bytes; the 8 KB window ends exactly on a
        // character boundary, so an unconditional tail-drop would slice a valid char and
        // wrongly mark the file binary — this is the Cyrillic Markdown/CSV regression.
        putRawFile("aligned-ru.md", "я".repeat(5_000).toByteArray(Charsets.UTF_8))

        val entry = assertSuccess(workspace.list()).single { it.relativePath == "aligned-ru.md" }
        assertTrue("boundary-aligned multi-byte text must stay text", entry.isText)
    }

    @Test
    fun `given a large non-ASCII text file when readTextPreview then returns truncated text`() = runTest {
        val workspace = workspaceWith()
        putRawFile("aligned-ru.md", "я".repeat(5_000).toByteArray(Charsets.UTF_8))

        val preview = assertSuccess(workspace.readTextPreview("aligned-ru.md", maxBytes = SNIFF_BYTES))

        assertTrue("a large UTF-8 file must preview, not fail as binary", preview.text.isNotEmpty())
        assertTrue(preview.truncated)
    }

    @Test
    fun `given a binary marker beyond the sniff window when readText then NotAText`() = runTest {
        val workspace = workspaceWith()
        putRawFile("long.txt", ByteArray(SNIFF_BYTES) { 'a'.code.toByte() } + byteArrayOf(0))

        // The authoritative read scans the whole file and catches the trailing NUL.
        assertFailure(workspace.readText("long.txt"), WorkspaceError.NotAText)
    }

    @Test
    fun `given a failed write when next write checks quota then the stale cache is not trusted`() = runTest {
        val workspace = workspaceWith(maxFileSize = 100L, maxTotal = 30L)
        assertSuccess(workspace.writeText("a", "12345")) // 5 bytes; primes the cache to 5
        putRawFile("ext.txt", ByteArray(10) { 'x'.code.toByte() }) // +10 on disk, behind the cache's back

        // A directory squatting on the write's scratch name makes the write throw
        // partway, after every refusal check has passed (a path through a file is
        // refused up front now); the fix invalidates the cache instead of leaving
        // it stuck at 5.
        File(workspaceRoot(), "b.txt.knotwork-tmp").mkdirs()
        try {
            workspace.writeText("b.txt", "x")
            fail("expected the write onto a directory-shaped scratch file to throw")
        } catch (expected: IOException) {
            // Staging into a path that is a directory is supposed to fail.
        }

        // The next quota check must recompute the true 15 bytes on disk: 15 + 20 = 35 > 30.
        // A stale cache (5) would wrongly allow the write.
        assertFailure(workspace.writeText("c.txt", "x".repeat(20)), WorkspaceError.QuotaExceeded)
    }

    @Test
    fun `given a symlink to an outside directory when list then escaping entries are excluded`() = runTest {
        val outside = tempFolder.newFolder("outside")
        File(outside, "secret.txt").writeText("top secret")
        workspaceRoot().mkdirs()
        try {
            Files.createSymbolicLink(File(workspaceRoot(), "link").toPath(), outside.toPath())
        } catch (e: Exception) {
            assumeNoException("Symlinks unsupported on this platform", e)
        }
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("inside.txt", "ok"))

        // `walkTopDown` descends through the symlink, but the containment filter drops
        // anything whose canonical path escapes the workspace.
        assertEquals(listOf("inside.txt"), assertSuccess(workspace.list()).map { it.relativePath })
    }

    // region editText

    @Test
    fun `given unique anchor when editText then replaces and round-trips`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("notes.md", "hello world"))

        val edited = assertSuccess(workspace.editText("notes.md", "world", "there"))
        assertEquals("notes.md", edited.relativePath)

        assertEquals("hello there", assertSuccess(workspace.readText("notes.md")))
    }

    @Test
    fun `given empty newText when editText then deletes the fragment`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("notes.md", "keep [drop] keep"))

        assertSuccess(workspace.editText("notes.md", "[drop] ", ""))

        assertEquals("keep keep", assertSuccess(workspace.readText("notes.md")))
    }

    @Test
    fun `given anchor absent when editText then fails AnchorNotFound and leaves file intact`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("notes.md", "hello world"))

        assertFailure(workspace.editText("notes.md", "missing", "x"), WorkspaceError.AnchorNotFound)

        assertEquals("hello world", assertSuccess(workspace.readText("notes.md")))
    }

    @Test
    fun `given ambiguous anchor when editText then fails AnchorNotUnique with count`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("notes.md", "a-a-a"))

        assertFailure(workspace.editText("notes.md", "a", "b"), WorkspaceError.AnchorNotUnique(3))

        assertEquals("a-a-a", assertSuccess(workspace.readText("notes.md")))
    }

    @Test
    fun `given missing file when editText then fails NotFound`() = runTest {
        val workspace = workspaceWith()

        assertFailure(workspace.editText("ghost.md", "a", "b"), WorkspaceError.NotFound)
    }

    @Test
    fun `given binary file when editText then fails NotAText`() = runTest {
        val workspace = workspaceWith()
        putRawFile("blob.bin", byteArrayOf(1, 0, 2, 3))

        assertFailure(workspace.editText("blob.bin", "x", "y"), WorkspaceError.NotAText)
    }

    @Test
    fun `given traversal path when editText then fails PathOutsideWorkspace`() = runTest {
        val workspace = workspaceWith()

        assertFailure(workspace.editText("../escape.md", "a", "b"), WorkspaceError.PathOutsideWorkspace)
    }

    @Test
    fun `given edit growing past total quota when editText then fails QuotaExceeded and keeps original`() = runTest {
        val workspace = workspaceWith(maxTotal = 10)
        assertSuccess(workspace.writeText("a.txt", "abcde")) // 5 bytes, workspace cap is 10

        // Replacing the single 'e' with 7 chars grows the file to 11 bytes, over the cap.
        assertFailure(workspace.editText("a.txt", "e", "eeeeeee"), WorkspaceError.QuotaExceeded)

        assertEquals("abcde", assertSuccess(workspace.readText("a.txt")))
    }

    // endregion

    // region delete

    @Test
    fun `given existing file when delete then removed and gone from listing`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("reports/old.md", "stale"))

        assertSuccess(workspace.delete("reports/old.md"))

        assertFalse(File(workspaceRoot(), "reports/old.md").exists())
        assertFailure(workspace.readText("reports/old.md"), WorkspaceError.NotFound)
    }

    @Test
    fun `given missing file when delete then fails NotFound`() = runTest {
        val workspace = workspaceWith()

        assertFailure(workspace.delete("ghost.md"), WorkspaceError.NotFound)
    }

    @Test
    fun `given directory path when delete then fails NotFound and directory survives`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("dir/inside.md", "x"))

        // 'dir' resolves to a directory, not a regular file — refused, not traversed.
        assertFailure(workspace.delete("dir"), WorkspaceError.NotFound)
        assertTrue(File(workspaceRoot(), "dir").isDirectory)
    }

    @Test
    fun `given traversal path when delete then fails PathOutsideWorkspace`() = runTest {
        val workspace = workspaceWith()

        assertFailure(workspace.delete("../../shared_prefs/keys.xml"), WorkspaceError.PathOutsideWorkspace)
    }

    @Test
    fun `given a delete after hitting quota when delete then freed space allows a new write`() = runTest {
        val workspace = workspaceWith(maxTotal = 10)
        assertSuccess(workspace.writeText("a.txt", "aaaaa")) // 5 bytes
        assertSuccess(workspace.writeText("b.txt", "bbbbb")) // 5 bytes → workspace full at 10
        // No room left for a third file.
        assertFailure(workspace.writeText("c.txt", "c"), WorkspaceError.QuotaExceeded)

        assertSuccess(workspace.delete("a.txt")) // frees 5 bytes; cached total must follow

        // The freed space is now usable, proving the delete updated the cached counter.
        assertSuccess(workspace.writeText("c.txt", "ccccc"))
    }

    // endregion

    // region atomic write

    @Test
    fun `given a successful overwrite when writeText then leaves no scratch file and replaces content exactly`() =
        runTest {
            val workspace = workspaceWith()
            assertSuccess(workspace.writeText("a.txt", "a long original content"))

            assertSuccess(workspace.writeText("a.txt", "short", overwrite = true))

            // The whole file is replaced (no tail of the longer previous content survives).
            assertEquals("short", assertSuccess(workspace.readText("a.txt")))
            // No staging artifact is left behind on the happy path.
            val names = workspaceRoot().listFiles()?.map { it.name }.orEmpty()
            assertEquals(listOf("a.txt"), names)
        }

    @Test
    fun `given the scratch path is occupied when overwrite then original survives and no garbage remains`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("a.txt", "original"))

        // Occupy the reserved scratch path with a directory so staging the new content
        // fails before the rename — modelling a crash between stage and rename. The
        // literal suffix mirrors AgentWorkspaceImpl.RESERVED_TMP_SUFFIX.
        val scratch = File(workspaceRoot(), "a.txt$RESERVED_TMP_SUFFIX")
        assertTrue(scratch.mkdirs())

        try {
            workspace.writeText("a.txt", "REPLACED", overwrite = true)
            fail("expected the staged write to fail while the scratch path is occupied")
        } catch (e: IOException) {
            // expected: the staged write could not be created at the occupied scratch path.
        }

        // The original is untouched (the rename never happened) and no scratch garbage remains.
        assertEquals("original", assertSuccess(workspace.readText("a.txt")))
        val names = workspaceRoot().listFiles()?.map { it.name }.orEmpty()
        assertEquals(listOf("a.txt"), names)
    }

    // endregion

    // region reserved scratch suffix

    @Test
    fun `given a reserved scratch-suffix path when writeText then refused and nothing is created`() = runTest {
        val workspace = workspaceWith()

        // The agent must not be able to address the reserved atomic-write suffix: such a
        // file would be hidden from listings and excluded from quota accounting, so
        // allowing it would open a quota-bypass / invisible-storage hole.
        assertFailure(workspace.writeText("evil$RESERVED_TMP_SUFFIX", "x"), WorkspaceError.NotFound)
        assertFalse(File(workspaceRoot(), "evil$RESERVED_TMP_SUFFIX").exists())
    }

    @Test
    fun `given a reserved scratch-suffix path when readText or delete then reported NotFound`() = runTest {
        val workspace = workspaceWith()
        // Even if one already exists on disk (e.g. a crashed write's residue), it stays
        // invisible and unaddressable through the gate.
        putRawFile("residue$RESERVED_TMP_SUFFIX", "leftover".toByteArray())

        assertFailure(workspace.readText("residue$RESERVED_TMP_SUFFIX"), WorkspaceError.NotFound)
        assertFailure(workspace.delete("residue$RESERVED_TMP_SUFFIX"), WorkspaceError.NotFound)
    }

    // endregion

    // region directory write

    @Test
    fun `given a directory path when writeText then fails IsDirectory regardless of overwrite`() = runTest {
        val workspace = workspaceWith()
        // Writing a nested file implicitly creates the 'reports' directory.
        assertSuccess(workspace.writeText("reports/a.md", "x"))

        // Targeting the directory itself must report IsDirectory — not AlreadyExists,
        // which would (mis)invite an endless retry with overwrite:true.
        assertFailure(workspace.writeText("reports", "y"), WorkspaceError.IsDirectory)
        assertFailure(workspace.writeText("reports", "y", overwrite = true), WorkspaceError.IsDirectory)
        assertTrue(File(workspaceRoot(), "reports").isDirectory)
    }

    // endregion

    // region usage

    @Test
    fun `given files when usage then reports summed bytes and the configured limit`() = runTest {
        val workspace = workspaceWith(maxTotal = 1_000L)
        assertSuccess(workspace.writeText("a.txt", "12345")) // 5 bytes
        assertSuccess(workspace.writeText("dir/b.txt", "678")) // 3 bytes

        val usage = assertSuccess(workspace.usage())

        assertEquals(8L, usage.usedBytes)
        assertEquals(1_000L, usage.limitBytes)
    }

    @Test
    fun `given empty workspace when usage then reports zero used`() = runTest {
        val usage = assertSuccess(workspaceWith().usage())
        assertEquals(0L, usage.usedBytes)
    }

    // endregion

    // region eraseAll

    @Test
    fun `given files when eraseAll then the workspace is gone and usage restarts at zero`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("a.txt", "12345"))
        assertSuccess(workspace.writeText("dir/nested/b.txt", "678"))
        assertEquals(8L, assertSuccess(workspace.usage()).usedBytes) // primes the cached tally

        assertTrue(workspace.eraseAll())

        assertFalse(workspaceRoot().exists())
        // The cached tally went with the files: the quota does not count what is gone.
        assertEquals(0L, assertSuccess(workspace.usage()).usedBytes)
        assertEquals(emptyList<Any>(), assertSuccess(workspace.list()))
        // The workspace is usable again straight away.
        assertSuccess(workspace.writeText("fresh.txt", "ok"))
    }

    @Test
    fun `given no workspace directory yet when eraseAll then it reports nothing left`() = runTest {
        assertTrue(workspaceWith().eraseAll())
        assertFalse(workspaceRoot().exists())
    }

    // endregion

    // region readTextPreview

    @Test
    fun `given a small text file when readTextPreview then returns full content untruncated`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("note.txt", "hello world"))

        val preview = assertSuccess(workspace.readTextPreview("note.txt", maxBytes = 1024))

        assertEquals("hello world", preview.text)
        assertEquals(11L, preview.totalBytes)
        assertFalse(preview.truncated)
    }

    @Test
    fun `given a file larger than the budget when readTextPreview then truncates at a boundary`() = runTest {
        val workspace = workspaceWith()
        val body = "x".repeat(500)
        assertSuccess(workspace.writeText("big.txt", body))

        val preview = assertSuccess(workspace.readTextPreview("big.txt", maxBytes = 100))

        assertTrue(preview.truncated)
        assertTrue("preview should be bounded by the budget", preview.text.length <= 100)
        assertEquals(500L, preview.totalBytes)
        assertTrue(body.startsWith(preview.text))
    }

    @Test
    fun `given a binary file when readTextPreview then fails NotAText`() = runTest {
        val workspace = workspaceWith()
        putRawFile("blob.bin", byteArrayOf(0x00, 0x01, 0x02))

        assertFailure(workspace.readTextPreview("blob.bin", maxBytes = 1024), WorkspaceError.NotAText)
    }

    @Test
    fun `given a missing file when readTextPreview then fails NotFound`() = runTest {
        assertFailure(workspaceWith().readTextPreview("nope.txt", maxBytes = 1024), WorkspaceError.NotFound)
    }

    @Test
    fun `given an escaping path when readTextPreview then fails PathOutsideWorkspace`() = runTest {
        assertFailure(
            workspaceWith().readTextPreview("../escape.txt", maxBytes = 1024),
            WorkspaceError.PathOutsideWorkspace,
        )
    }

    // endregion

    // region importBytes

    @Test
    fun `given a new path when importBytes then writes the bytes verbatim`() = runTest {
        val workspace = workspaceWith()
        val bytes = byteArrayOf(0x00, 0x10, 0x20, 0x7F) // binary content

        val file = assertSuccess(workspace.importBytes("data.bin", ByteArrayInputStream(bytes), overwrite = false))

        assertEquals("data.bin", file.relativePath)
        assertTrue(File(workspaceRoot(), "data.bin").readBytes().contentEquals(bytes))
    }

    @Test
    fun `given an existing path without overwrite when importBytes then fails AlreadyExists`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("data.txt", "old"))

        assertFailure(
            workspace.importBytes("data.txt", ByteArrayInputStream("new".toByteArray()), overwrite = false),
            WorkspaceError.AlreadyExists,
        )
    }

    @Test
    fun `given an existing path with overwrite when importBytes then replaces it`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("data.txt", "old"))

        assertSuccess(
            workspace.importBytes("data.txt", ByteArrayInputStream("brand-new".toByteArray()), overwrite = true),
        )

        assertEquals("brand-new", assertSuccess(workspace.readText("data.txt")))
    }

    @Test
    fun `given a source over the per-file limit when importBytes then fails TooLarge`() = runTest {
        val workspace = workspaceWith(maxFileSize = 4L)

        assertFailure(
            workspace.importBytes("big.bin", ByteArrayInputStream(ByteArray(5)), overwrite = false),
            WorkspaceError.TooLarge,
        )
    }

    @Test
    fun `given a source that would exceed the quota when importBytes then fails QuotaExceeded`() = runTest {
        val workspace = workspaceWith(maxFileSize = 100L, maxTotal = 6L)
        assertSuccess(workspace.writeText("a.txt", "1234")) // 4 bytes used, 2 left

        assertFailure(
            workspace.importBytes("b.bin", ByteArrayInputStream(ByteArray(3)), overwrite = false),
            WorkspaceError.QuotaExceeded,
        )
    }

    // endregion

    // region exportTo

    @Test
    fun `given an existing file when exportTo then streams its full bytes to the sink`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("report.md", "the whole thing"))
        val sink = ByteArrayOutputStream()

        assertSuccess(workspace.exportTo("report.md", sink))

        assertEquals("the whole thing", sink.toString("UTF-8"))
    }

    @Test
    fun `given a file over the read limit when exportTo then still streams it`() = runTest {
        // exportTo must bypass the per-file read cap, unlike readText.
        val workspace = workspaceWith(maxFileSize = 4L)
        putRawFile("big.txt", "0123456789".toByteArray()) // 10 bytes, over the 4-byte cap
        val sink = ByteArrayOutputStream()

        assertSuccess(workspace.exportTo("big.txt", sink))

        assertEquals("0123456789", sink.toString("UTF-8"))
    }

    @Test
    fun `given a missing file when exportTo then fails NotFound`() = runTest {
        assertFailure(workspaceWith().exportTo("nope.txt", ByteArrayOutputStream()), WorkspaceError.NotFound)
    }

    @Test
    fun `given an escaping path when exportTo then fails PathOutsideWorkspace`() = runTest {
        assertFailure(
            workspaceWith().exportTo("../escape.txt", ByteArrayOutputStream()),
            WorkspaceError.PathOutsideWorkspace,
        )
    }

    // endregion

    // region share copies

    /** The share staging directory the workspace copies into. */
    private fun shareDir() = File(cacheFolder.root, "shared")

    @Test
    fun `given a file when staged for share then an identical copy lands in the share cache`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("reports/salary.md", "the figures"))

        val staged = File(assertSuccess(workspace.stageForShare("reports/salary.md")))

        assertEquals("salary.md", staged.name)
        assertEquals("the figures", staged.readText())
        assertTrue("the copy lives under the share cache", staged.canonicalPath.startsWith(shareDir().canonicalPath))
    }

    @Test
    fun `given a staged copy when its file is deleted then the copy is deleted too`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("reports/salary.md", "the figures"))
        assertSuccess(workspace.writeText("notes.md", "keep me"))
        val salaryCopy = File(assertSuccess(workspace.stageForShare("reports/salary.md")))
        val notesCopy = File(assertSuccess(workspace.stageForShare("notes.md")))

        assertSuccess(workspace.delete("reports/salary.md"))

        // Before: "delete" on the Files screen left this byte-identical copy behind.
        assertFalse("a deleted file must leave no share copy", salaryCopy.exists())
        assertTrue("another file's copy is untouched", notesCopy.exists())
    }

    @Test
    fun `given a file deleted by a path spelled differently then its copy is still found`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("a/b.md", "x"))
        val copy = File(assertSuccess(workspace.stageForShare("a/b.md")))

        assertSuccess(workspace.delete("a/./../a/b.md"))

        assertFalse(copy.exists())
    }

    @Test
    fun `given a fresh share when another file is shared then the first copy survives`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("a.md", "first"))
        assertSuccess(workspace.writeText("b.md", "second"))
        val first = File(assertSuccess(workspace.stageForShare("a.md")))

        assertSuccess(workspace.stageForShare("b.md"))

        // Before: every share wiped the whole directory, pulling this copy away from
        // an app that may not have read it yet.
        assertTrue("a share still being read must not be removed by the next", first.exists())
    }

    @Test
    fun `given a copy older than the retention when another file is shared then the old copy is pruned`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("a.md", "first"))
        assertSuccess(workspace.writeText("b.md", "second"))
        val old = File(assertSuccess(workspace.stageForShare("a.md")))
        val longAgo = System.currentTimeMillis() - 2 * ONE_HOUR_MS
        old.setLastModified(longAgo)
        old.parentFile!!.setLastModified(longAgo)

        assertSuccess(workspace.stageForShare("b.md"))

        assertFalse(old.parentFile!!.exists())
    }

    @Test
    fun `given a missing or escaping path when staged for share then it fails without a copy`() = runTest {
        val workspace = workspaceWith()

        assertFailure(workspace.stageForShare("nope.md"), WorkspaceError.NotFound)
        assertFailure(workspace.stageForShare("../escape.md"), WorkspaceError.PathOutsideWorkspace)
        assertTrue(shareDir().listFiles().orEmpty().isEmpty())
    }

    // endregion

    // region directories and the entry ceiling

    @Test
    fun `given a nested file when it is deleted then its emptied directories go with it`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("a/b/c/f.txt", "x"))

        assertSuccess(workspace.delete("a/b/c/f.txt"))

        // Before the fix the three directories survived: free in the quota, absent from
        // every listing, and removable by no action in the app.
        assertFalse(File(workspaceRoot(), "a").exists())
        assertTrue(workspaceRoot().isDirectory)
    }

    @Test
    fun `given a sibling in an ancestor when a nested file is deleted then pruning stops at that ancestor`() = runTest {
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("a/keep.txt", "k"))
        assertSuccess(workspace.writeText("a/b/c/f.txt", "x"))

        assertSuccess(workspace.delete("a/b/c/f.txt"))

        assertFalse(File(workspaceRoot(), "a/b").exists())
        assertEquals("k", assertSuccess(workspace.readText("a/keep.txt")))
    }

    @Test
    fun `given empty directories left behind earlier when the workspace is next measured then they are removed`() =
        runTest {
            assertTrue(File(workspaceRoot(), "old/x/y").mkdirs())
            putRawFile("kept/file.txt", "k".toByteArray())
            val workspace = workspaceWith()

            assertSuccess(workspace.usage())

            assertFalse(File(workspaceRoot(), "old").exists())
            assertTrue(File(workspaceRoot(), "kept/file.txt").isFile)
        }

    @Test
    fun `given the entry ceiling is reached when a new file is written then QuotaExceeded`() = runTest {
        val workspace = workspaceWith(maxEntries = 3)
        // `a`, `a/b` and `f.txt`: three entries, exactly the ceiling.
        assertSuccess(workspace.writeText("a/b/f.txt", "x"))

        assertFailure(workspace.writeText("c.txt", "x"), WorkspaceError.QuotaExceeded)
        assertFalse(File(workspaceRoot(), "c.txt").exists())
        // Replacing an existing file creates no entry, so it is still allowed at the ceiling.
        assertSuccess(workspace.writeText("a/b/f.txt", "y", overwrite = true))
    }

    @Test
    fun `given a nested write that needs more entries than remain when writeText then nothing is created`() = runTest {
        val workspace = workspaceWith(maxEntries = 2)

        // The two directories count as well as the file: three entries against a ceiling of two.
        assertFailure(workspace.writeText("a/b/f.txt", "x"), WorkspaceError.QuotaExceeded)
        assertFalse(File(workspaceRoot(), "a").exists())
    }

    @Test
    fun `given a delete at the entry ceiling when a new file is written then the freed entries are reusable`() =
        runTest {
            val workspace = workspaceWith(maxEntries = 3)
            assertSuccess(workspace.writeText("a/b/f.txt", "x"))

            assertSuccess(workspace.delete("a/b/f.txt"))

            // The delete freed the file and both directories; the cached count must follow.
            assertSuccess(workspace.writeText("d/e/g.txt", "x"))
        }

    @Test
    fun `given the entry ceiling is reached when importBytes then QuotaExceeded`() = runTest {
        val workspace = workspaceWith(maxEntries = 1)
        assertSuccess(workspace.writeText("a.txt", "x"))

        assertFailure(
            workspace.importBytes("b.txt", ByteArrayInputStream(byteArrayOf(1)), overwrite = false),
            WorkspaceError.QuotaExceeded,
        )
    }

    @Test
    fun `given entries already on disk when the first write is checked then they count against the ceiling`() =
        runTest {
            putRawFile("one.txt", "1".toByteArray())
            putRawFile("two.txt", "2".toByteArray())
            val workspace = workspaceWith(maxEntries = 2)

            assertFailure(workspace.writeText("three.txt", "3"), WorkspaceError.QuotaExceeded)
        }

    // endregion

    // region the walk behind listings and the quota

    @Test
    fun `given a symlink to an outside directory when usage then the outside bytes are not charged`() = runTest {
        val outside = tempFolder.newFolder("outside")
        File(outside, "secret.txt").writeText("0123456789")
        workspaceRoot().mkdirs()
        try {
            Files.createSymbolicLink(File(workspaceRoot(), "link").toPath(), outside.toPath())
        } catch (e: Exception) {
            assumeNoException("Symlinks unsupported on this platform", e)
        }
        val workspace = workspaceWith()
        assertSuccess(workspace.writeText("inside.txt", "ok"))

        // `list()` already dropped the escaping entry; the quota walk used to add its bytes.
        assertEquals(2L, assertSuccess(workspace.usage()).usedBytes)
    }

    @Test
    fun `given a symlink cycle when list and usage then each file appears once`() = runTest {
        workspaceRoot().mkdirs()
        putRawFile("a.txt", "12345".toByteArray())
        try {
            Files.createSymbolicLink(File(workspaceRoot(), "loop").toPath(), workspaceRoot().toPath())
        } catch (e: Exception) {
            assumeNoException("Symlinks unsupported on this platform", e)
        }
        val workspace = workspaceWith()

        assertEquals(listOf("a.txt"), assertSuccess(workspace.list()).map { it.relativePath })
        assertEquals(5L, assertSuccess(workspace.usage()).usedBytes)
    }

    @Test
    fun `given a symlink to an empty outside directory when the workspace is measured then the target survives`() =
        runTest {
            val outside = tempFolder.newFolder("outside-empty")
            File(outside, "nested").mkdirs()
            workspaceRoot().mkdirs()
            try {
                Files.createSymbolicLink(File(workspaceRoot(), "link").toPath(), outside.toPath())
            } catch (e: Exception) {
                assumeNoException("Symlinks unsupported on this platform", e)
            }

            assertSuccess(workspaceWith().usage())

            // Pruning empty directories must never reach through a link.
            assertTrue(File(outside, "nested").isDirectory)
        }

    // endregion

    // region names

    @Test
    fun `given a name with a line break when writeText then InvalidPath and nothing is created`() = runTest {
        val workspace = workspaceWith()

        assertFailure(workspace.writeText("notes.md\nforged.txt", "x"), WorkspaceError.InvalidPath)
        assertTrue(workspaceRoot().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `given a name with a lone surrogate when writeText then InvalidPath and nothing is created`() = runTest {
        // The JVM would write it as "?.txt" and report success; on the device the
        // name is one java.nio cannot represent, and it jammed every later walk.
        val workspace = workspaceWith()

        assertFailure(workspace.writeText("\uD800.txt", "x"), WorkspaceError.InvalidPath)
        assertTrue(workspaceRoot().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `given a path that runs through an existing file when written or appended then InvalidPath`() = runTest {
        // A directory level that is a file (ENOTDIR) is a path the filesystem
        // rejects; the promise is a typed refusal, not an exception.
        val workspace = workspaceWith()
        workspace.writeText("notes.md", "x")

        assertFailure(workspace.writeText("notes.md/x.txt", "y"), WorkspaceError.InvalidPath)
        assertFailure(workspace.appendText("notes.md/x.txt", "y"), WorkspaceError.InvalidPath)
    }

    @Test
    fun `given a directory name with a tab when writeText then InvalidPath`() = runTest {
        assertFailure(workspaceWith().writeText("a\tb/f.txt", "x"), WorkspaceError.InvalidPath)
    }

    @Test
    fun `given a new name with a control character when appendText then InvalidPath`() = runTest {
        assertFailure(workspaceWith().appendText("log\r.txt", "x"), WorkspaceError.InvalidPath)
    }

    @Test
    fun `given an imported name with a line break when importBytes then InvalidPath`() = runTest {
        val source = ByteArrayInputStream("x".toByteArray())

        assertFailure(
            workspaceWith().importBytes("a\nb.txt", source, overwrite = false),
            WorkspaceError.InvalidPath,
        )
    }

    @Test
    fun `given a file whose name predates the rules when read overwritten and deleted then all succeed`() = runTest {
        putRawFile("old\nname.txt", "legacy".toByteArray())
        val workspace = workspaceWith()

        assertEquals("legacy", assertSuccess(workspace.readText("old\nname.txt")))
        assertSuccess(workspace.writeText("old\nname.txt", "new", overwrite = true))
        assertEquals(listOf("old\nname.txt"), assertSuccess(workspace.list()).map { it.relativePath })
        assertSuccess(workspace.delete("old\nname.txt"))
    }

    @Test
    fun `given a path with a NUL byte when any operation then InvalidPath instead of an exception`() = runTest {
        val workspace = workspaceWith()
        val path = "a${Char(0)}b.txt"

        assertFailure(workspace.readText(path), WorkspaceError.InvalidPath)
        assertFailure(workspace.writeText(path, "x"), WorkspaceError.InvalidPath)
        assertFailure(workspace.delete(path), WorkspaceError.InvalidPath)
        assertFailure(workspace.resolve(path), WorkspaceError.InvalidPath)
    }

    @Test
    fun `given a name at the byte limit when writeText then succeeds`() = runTest {
        val name = "n".repeat(MAX_NAME_BYTES)

        assertSuccess(workspaceWith().writeText(name, "x"))
    }

    @Test
    fun `given a name one byte over the limit when writeText then InvalidPath instead of an exception`() = runTest {
        val name = "n".repeat(MAX_NAME_BYTES + 1)

        assertFailure(workspaceWith().writeText(name, "x"), WorkspaceError.InvalidPath)
    }

    @Test
    fun `given a path at the byte limit when writeText then succeeds`() = runTest {
        assertEquals(MAX_PATH_BYTES, pathOfBytes(MAX_PATH_BYTES).length)

        assertSuccess(workspaceWith().writeText(pathOfBytes(MAX_PATH_BYTES), "x"))
    }

    @Test
    fun `given a path one byte over the limit when writeText then InvalidPath`() = runTest {
        assertFailure(workspaceWith().writeText(pathOfBytes(MAX_PATH_BYTES + 1), "x"), WorkspaceError.InvalidPath)
    }

    /** An ASCII path of exactly [bytes] bytes whose every segment stays under the name limit. */
    private fun pathOfBytes(bytes: Int): String {
        val segment = "s".repeat(SEGMENT_FOR_PATH_TESTS)
        val builder = StringBuilder()
        while (builder.length + 1 + SEGMENT_FOR_PATH_TESTS < bytes) builder.append(segment).append('/')
        return builder.append("f".repeat(bytes - builder.length)).toString()
    }

    // endregion

    private companion object {
        const val DEFAULT_FILE_SIZE = 5L * 1024 * 1024

        /** Mirrors `WorkspaceNamePolicy.MAX_NAME_BYTES`, kept literal so the test pins the value. */
        const val MAX_NAME_BYTES = 242

        /** Mirrors `WorkspaceNamePolicy.MAX_PATH_BYTES`, kept literal so the test pins the value. */
        const val MAX_PATH_BYTES = 512

        /** Segment length used to build long paths out of legal names. */
        const val SEGMENT_FOR_PATH_TESTS = 100
        const val DEFAULT_TOTAL = 100L * 1024 * 1024

        /** Mirrors `AgentWorkspaceImpl.RESERVED_TMP_SUFFIX`. */
        const val RESERVED_TMP_SUFFIX = ".knotwork-tmp"

        /** Mirrors `AgentWorkspaceImpl.TEXT_SNIFF_BYTES`. */
        const val SNIFF_BYTES = 8 * 1024

        const val ONE_HOUR_MS = 60L * 60L * 1000L
    }
}
