package app.knotwork.android.presentation.ui.files

import app.knotwork.android.domain.models.WorkspaceFile
import app.knotwork.android.domain.models.WorkspaceTextPreview
import app.knotwork.android.domain.models.WorkspaceUsage
import app.knotwork.android.presentation.common.AgeUnits
import app.knotwork.android.presentation.common.ByteUnits
import app.knotwork.design.screens.files.FileKind
import app.knotwork.design.screens.files.FilesVisualState
import app.knotwork.design.screens.files.QuotaTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the pure `FilesUiState.toViewState(ByteUnits.ENGLISH, ENGLISH_AGES, quotaFull = "full")` projection used by `FilesScreen`. */
class FilesScreenMappingTest {

    private fun usage(used: Long, limit: Long) = WorkspaceUsage(usedBytes = used, limitBytes = limit)

    @Test
    fun `given no files when mapped then visual state is empty`() {
        val state = FilesUiState(
            files = emptyList(),
            loading = false,
        ).toViewState(ByteUnits.ENGLISH, ENGLISH_AGES, quotaFull = "full")
        assertEquals(FilesVisualState.Empty, state.visualState)
    }

    @Test
    fun `given load failed when mapped then visual state is error`() {
        val state = FilesUiState(
            loadFailed = true,
            loading = false,
        ).toViewState(ByteUnits.ENGLISH, ENGLISH_AGES, quotaFull = "full")
        assertEquals(FilesVisualState.Error, state.visualState)
    }

    @Test
    fun `given files when mapped then rows split dir and basename and classify kind`() {
        val files = listOf(
            WorkspaceFile("reports/q2.md", sizeBytes = 10, lastModified = 0, isDirectory = false, isText = true),
            WorkspaceFile("blob.bin", sizeBytes = 20, lastModified = 0, isDirectory = false, isText = false),
        )
        val state = FilesUiState(
            files = files,
            loading = false,
        ).toViewState(ByteUnits.ENGLISH, ENGLISH_AGES, quotaFull = "full")

        assertEquals(FilesVisualState.Populated, state.visualState)
        val report = state.files[0]
        assertEquals("reports/", report.dir)
        assertEquals("q2.md", report.name)
        assertEquals(FileKind.Text, report.kind)
        assertEquals(FileKind.Binary, state.files[1].kind)
        assertEquals("", state.files[1].dir)
    }

    @Test
    fun `given low usage when mapped then quota tone is normal`() {
        val state = FilesUiState(
            usage = usage(used = 50, limit = 100),
            loading = false,
        ).toViewState(ByteUnits.ENGLISH, ENGLISH_AGES, quotaFull = "full")
        assertEquals(QuotaTone.Normal, state.quota.tone)
        assertEquals("50 B / 100 B", state.quota.usageText)
        assertTrue(!state.quota.full)
    }

    @Test
    fun `given near-full usage when mapped then quota tone is warn with percent`() {
        val state = FilesUiState(
            usage = usage(used = 95, limit = 100),
            loading = false,
        ).toViewState(ByteUnits.ENGLISH, ENGLISH_AGES, quotaFull = "full")
        assertEquals(QuotaTone.Warn, state.quota.tone)
        assertTrue(state.quota.usageText.endsWith("95%"))
    }

    @Test
    fun `given full usage when mapped then quota tone is over and banner shows`() {
        val state = FilesUiState(
            usage = usage(used = 100, limit = 100),
            loading = false,
        ).toViewState(ByteUnits.ENGLISH, ENGLISH_AGES, quotaFull = "full")
        assertEquals(QuotaTone.Over, state.quota.tone)
        assertTrue(state.quota.full)
        assertTrue(state.quota.usageText.endsWith("full"))
    }

    @Test
    fun `given an open preview when mapped then preview view carries truncation and size`() {
        val state = FilesUiState(
            files = listOf(WorkspaceFile("reports/q2.md", 999, 0, isDirectory = false, isText = true)),
            preview = FilePreviewState(
                path = "reports/q2.md",
                preview = WorkspaceTextPreview(text = "head", totalBytes = 9_999, truncated = true),
            ),
            loading = false,
        ).toViewState(ByteUnits.ENGLISH, ENGLISH_AGES, quotaFull = "full")

        assertEquals("reports/", state.preview?.dir)
        assertEquals("q2.md", state.preview?.name)
        assertTrue(state.preview?.truncated == true)
    }

    @Test
    fun `given a pending delete when mapped then dialog lists basenames and count`() {
        val state = FilesUiState(
            files = listOf(WorkspaceFile("a.txt", 1, 0, isDirectory = false, isText = true)),
            pendingDelete = listOf("reports/q2.md", "a.txt"),
            loading = false,
        ).toViewState(ByteUnits.ENGLISH, ENGLISH_AGES, quotaFull = "full")

        assertEquals(2, state.deleteDialog?.count)
        assertEquals(listOf("q2.md", "a.txt"), state.deleteDialog?.names)
    }

    @Test
    fun `given a collision when mapped then dialog carries names`() {
        val state = FilesUiState(
            files = listOf(WorkspaceFile("a.txt", 1, 0, isDirectory = false, isText = true)),
            collision = CollisionState(name = "a.txt", keepBothName = "a (1).txt"),
            loading = false,
        ).toViewState(ByteUnits.ENGLISH, ENGLISH_AGES, quotaFull = "full")

        assertEquals("a.txt", state.collisionDialog?.name)
        assertEquals("a (1).txt", state.collisionDialog?.keepBothName)
    }
}

private val ENGLISH_AGES =
    AgeUnits(now = "now", minutes = "%1\$d m", hours = "%1\$d h", days = "%1\$d d", weeks = "%1\$d w")
