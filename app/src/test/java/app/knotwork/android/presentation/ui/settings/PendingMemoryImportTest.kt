package app.knotwork.android.presentation.ui.settings

import app.knotwork.android.domain.models.MemoryExportDocument
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [PendingMemoryImport.warnings] — which notices the memory-import
 * dialog raises about a staged file, and in what order.
 */
class PendingMemoryImportTest {

    private fun pending(pinnedInFile: Int = 0, providerMismatch: Boolean = false, schemaMismatch: Boolean = false) =
        PendingMemoryImport(
            document = MemoryExportDocument(
                embeddingProviderId = "use",
                exportedAt = 0L,
                chunks = emptyList(),
                pinnedInFile = pinnedInFile,
            ),
            providerMismatch = providerMismatch,
            schemaMismatch = schemaMismatch,
        )

    @Test
    fun `given a clean file when staged then no warnings`() {
        assertEquals(emptyList<MemoryImportWarning>(), pending().warnings)
    }

    @Test
    fun `given a file with pinned chunks when staged then the dialog says the pins are not imported`() {
        // Security audit 07/F3: the dialog used to show a chunk count and nothing
        // else, so a file's pins were applied invisibly. They are now dropped, and
        // the user is told.
        assertEquals(listOf(MemoryImportWarning.PinsNotImported), pending(pinnedInFile = 3).warnings)
    }

    @Test
    fun `given every condition when staged then warnings keep schema, provider, pins order`() {
        assertEquals(
            listOf(
                MemoryImportWarning.SchemaMismatch,
                MemoryImportWarning.ProviderMismatch,
                MemoryImportWarning.PinsNotImported,
            ),
            pending(pinnedInFile = 1, providerMismatch = true, schemaMismatch = true).warnings,
        )
    }
}
