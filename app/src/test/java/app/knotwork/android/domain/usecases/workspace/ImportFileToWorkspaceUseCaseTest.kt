package app.knotwork.android.domain.usecases.workspace

import app.knotwork.android.domain.models.WorkspaceError
import app.knotwork.android.domain.models.WorkspaceFile
import app.knotwork.android.domain.models.WorkspaceResult
import app.knotwork.android.domain.services.AgentWorkspace
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

/** Verifies [ImportFileToWorkspaceUseCase]'s collision policy, basename sanitisation and free-name logic. */
class ImportFileToWorkspaceUseCaseTest {

    private val workspace = mockk<AgentWorkspace>()
    private val useCase = ImportFileToWorkspaceUseCase(workspace)

    private fun file(path: String): WorkspaceFile =
        WorkspaceFile(path, sizeBytes = 1, lastModified = 1, isDirectory = false, isText = true)

    private fun stream() = ByteArrayInputStream("data".toByteArray())

    @Test
    fun `given CreateOrFail when invoked then imports without overwrite`() = runTest {
        coEvery { workspace.importBytes("a.txt", any(), false) } returns WorkspaceResult.Success(file("a.txt"))

        val result = useCase("a.txt", stream(), ImportMode.CreateOrFail)

        assertEquals(WorkspaceResult.Success(file("a.txt")), result)
        coVerify { workspace.importBytes("a.txt", any(), false) }
    }

    @Test
    fun `given Overwrite when invoked then imports with overwrite`() = runTest {
        coEvery { workspace.importBytes("a.txt", any(), true) } returns WorkspaceResult.Success(file("a.txt"))

        useCase("a.txt", stream(), ImportMode.Overwrite)

        coVerify { workspace.importBytes("a.txt", any(), true) }
    }

    @Test
    fun `given KeepBoth and a collision when invoked then imports under the next free name`() = runTest {
        coEvery { workspace.list() } returns WorkspaceResult.Success(listOf(file("report.md")))
        val nameSlot = slot<String>()
        coEvery { workspace.importBytes(capture(nameSlot), any(), false) } returns WorkspaceResult.Success(file("x"))

        useCase("report.md", stream(), ImportMode.KeepBoth)

        assertEquals("report (1).md", nameSlot.captured)
    }

    @Test
    fun `given KeepBoth and listing fails when invoked then returns that failure`() = runTest {
        coEvery { workspace.list() } returns WorkspaceResult.Failure(WorkspaceError.NotFound)

        val result = useCase("report.md", stream(), ImportMode.KeepBoth)

        assertEquals(WorkspaceResult.Failure(WorkspaceError.NotFound), result)
    }

    @Test
    fun `given a path-bearing display name when invoked then only the basename is used`() = runTest {
        coEvery { workspace.importBytes("note.txt", any(), false) } returns WorkspaceResult.Success(file("note.txt"))

        useCase("/storage/emulated/0/Download/note.txt", stream(), ImportMode.CreateOrFail)

        coVerify { workspace.importBytes("note.txt", any(), false) }
    }

    @Test
    fun `given a display name carrying line breaks and tabs when invoked then each is replaced`() = runTest {
        // The name comes from whichever app's provider the user picked from; a line break in
        // it would open a forged entry in the agent's file listing.
        coEvery { workspace.importBytes(any(), any(), false) } returns WorkspaceResult.Success(file("x"))

        useCase("notes.md\nFORGED.txt\t142 bytes", stream(), ImportMode.CreateOrFail)

        coVerify { workspace.importBytes("notes.md_FORGED.txt_142 bytes", any(), false) }
    }

    @Test
    fun `given non-whitespace control characters at the ends of a name when sanitize then they are replaced`() {
        // `trim` removes only whitespace, so NUL and DEL at the ends are left to the replacement.
        assertEquals("_a_", ImportFileToWorkspaceUseCase.sanitize("${Char(0)}a${Char(0x7F)}"))
    }

    @Test
    fun `given a blank name when invoked then falls back to the default name`() = runTest {
        coEvery {
            workspace.importBytes(ImportFileToWorkspaceUseCase.DEFAULT_NAME, any(), false)
        } returns WorkspaceResult.Success(file("x"))

        useCase("   ", stream(), ImportMode.CreateOrFail)

        coVerify { workspace.importBytes(ImportFileToWorkspaceUseCase.DEFAULT_NAME, any(), false) }
    }

    @Test
    fun `freeName returns the name unchanged when free`() {
        assertEquals("a.txt", ImportFileToWorkspaceUseCase.freeName("a.txt", emptySet()))
    }

    @Test
    fun `freeName skips taken numbered variants`() {
        val taken = setOf("a.txt", "a (1).txt", "a (2).txt")
        assertEquals("a (3).txt", ImportFileToWorkspaceUseCase.freeName("a.txt", taken))
    }

    @Test
    fun `freeName appends suffix to extensionless names`() {
        assertEquals("README (1)", ImportFileToWorkspaceUseCase.freeName("README", setOf("README")))
    }

    @Test
    fun `freeName treats a dotfile as having no extension`() {
        assertEquals(".gitignore (1)", ImportFileToWorkspaceUseCase.freeName(".gitignore", setOf(".gitignore")))
    }

    @Test
    fun `sanitize strips directories and trims`() {
        assertEquals("note.txt", ImportFileToWorkspaceUseCase.sanitize("  a/b/note.txt  "))
        assertEquals("file.bin", ImportFileToWorkspaceUseCase.sanitize("C:\\Users\\x\\file.bin"))
    }
}
