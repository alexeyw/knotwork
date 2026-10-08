package app.knotwork.android.presentation.ui.settings

import android.content.Context
import app.knotwork.android.R
import app.knotwork.android.domain.models.MemoryExportDocument
import app.knotwork.android.domain.models.MemoryImportStrategy
import app.knotwork.android.domain.usecases.MemoryImportUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MemorySettingsDelegate] — the memory import's last step, where a
 * failure meets the user.
 */
class MemorySettingsDelegateTest {

    private val document = MemoryExportDocument(embeddingProviderId = "use", exportedAt = 0L, chunks = emptyList())
    private val state = MutableStateFlow(
        SettingsUiState(
            pendingImport = PendingMemoryImport(document, providerMismatch = false, schemaMismatch = false),
        ),
    )
    private val memoryImport = mockk<MemoryImportUseCase>(relaxed = true)

    /** Echoes the argument of the one-argument failure string, so a test sees what would be shown. */
    private val context = mockk<Context>(relaxed = true) {
        every { getString(R.string.settings_memory_import_failed, *anyVararg()) } answers {
            "Import failed: ${(args[1] as Array<*>).joinToString()}"
        }
        every { getString(R.string.settings_memory_import_failed_generic) } returns "Import failed."
    }

    private fun TestScope.delegate() = MemorySettingsDelegate(
        scope = this,
        state = state,
        appContext = context,
        memorySettings = mockk(relaxed = true),
        memoryRepository = mockk(relaxed = true),
        memorySearchStatsTracker = mockk(relaxed = true),
        embeddingProviders = emptyMap(),
        clearAllMemoryUseCase = mockk(relaxed = true),
        exportMemoryBaseUseCase = mockk(relaxed = true),
        memoryImportUseCase = memoryImport,
        reembedAllMemoriesUseCase = mockk(relaxed = true),
    )

    @Test
    fun `given the store fails while importing then the snackbar does not quote the database`() = runTest {
        // What SQLite says is for the log; the user is told the import did not happen.
        coEvery { memoryImport.import(any(), any()) } throws IllegalStateException("disk I/O error (code 4874)")
        val delegate = delegate()

        delegate.confirmImport(MemoryImportStrategy.Merge)
        advanceUntilIdle()

        val shown = state.value.snackbarMessage.orEmpty()
        assertTrue("a snackbar is shown", shown.isNotBlank())
        assertFalse("database text reached the user: $shown", shown.contains("disk I/O error"))
        assertNull(state.value.pendingImport)
    }

    @Test
    fun `given the store refuses the import then the snackbar says nothing was saved`() = runTest {
        every { context.getString(R.string.settings_memory_import_not_saved) } returns
            "Import failed — nothing was saved."
        coEvery { memoryImport.import(any(), any()) } returns Result.failure(IllegalStateException("disk I/O error"))
        val delegate = delegate()

        delegate.confirmImport(MemoryImportStrategy.Replace)
        advanceUntilIdle()

        assertEquals("Import failed — nothing was saved.", state.value.snackbarMessage)
    }

    @Test
    fun `given the import runs out of memory then the failure is reported, not thrown`() = runTest {
        // A large file's transaction can exhaust the heap; that is a failed import,
        // not a reason to take the app down.
        coEvery { memoryImport.import(any(), any()) } throws OutOfMemoryError("Java heap space")
        val delegate = delegate()

        delegate.confirmImport(MemoryImportStrategy.Replace)
        advanceUntilIdle()

        assertEquals("Import failed.", state.value.snackbarMessage)
    }
}
