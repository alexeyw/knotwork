package app.knotwork.android.presentation.ui.orchestrator

import app.knotwork.android.R
import app.knotwork.android.presentation.ui.common.FakePickedDocument
import app.knotwork.android.presentation.ui.common.UiText
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the library's "Export bundle" answers the document the user picked.
 *
 * The bundle is held in the view model from the picker's launch until the picked
 * document arrives — the screen used to keep it in composition, so a rotation while the
 * picker was open lost it and left the file the picker created empty. A save that cannot
 * write a byte removes that file, and names it when its provider will not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorTransferDelegateTest {

    private val bundle = PendingBundleExport(fileName = "pipeline-bundle.json", content = "{\"bundle\": \"Привет\"}")

    private val uiState = MutableStateFlow(OrchestratorUiState())

    private fun TestScope.delegate() = OrchestratorTransferDelegate(
        scope = this,
        uiState = uiState,
        useCases = mockk(),
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `given a bundle taken for the picker when the document arrives then the bundle is written into it`() = runTest {
        uiState.value = OrchestratorUiState(pendingBundleExport = bundle)
        val delegate = delegate()
        val picked = FakePickedDocument(name = "pipeline-bundle (1).json")

        delegate.consumeBundleExport()
        // The picker launches from this field: once taken it must be gone, or a
        // recomposition would launch a second picker.
        assertNull(uiState.value.pendingBundleExport)
        delegate.saveBundleTo(picked)
        advanceUntilIdle()

        assertEquals(bundle.content, picked.bytes.toString(Charsets.UTF_8))
        assertTrue("the picked stream must be closed", picked.stream.closed)
        assertFalse(picked.deleteAsked)
        assertEquals(
            UiText.of(R.string.orchestrator_library_export_bundle_success, "pipeline-bundle (1).json"),
            uiState.value.feedbackMessage,
        )
        assertNull(uiState.value.errorMessage)
    }

    @Test
    fun `given no held bundle when the document arrives then the empty file is removed and the failure said`() =
        runTest {
            // A process death while the picker was open: the new view model holds nothing.
            val delegate = delegate()
            val picked = FakePickedDocument()

            delegate.saveBundleTo(picked)
            advanceUntilIdle()

            assertTrue("the empty file the picker created must be removed", picked.deleteAsked)
            assertEquals(0, picked.openCount)
            assertEquals(UiText.of(R.string.errors_generic_unexpected), uiState.value.errorMessage)
            assertNull(uiState.value.feedbackMessage)
        }

    @Test
    fun `given no held bundle and a file that cannot be deleted when it arrives then its name is said`() = runTest {
        val delegate = delegate()

        delegate.saveBundleTo(FakePickedDocument(name = "pipeline-bundle.json", deletes = false))
        advanceUntilIdle()

        assertEquals(
            UiText.of(R.string.export_empty_file_left, "pipeline-bundle.json"),
            uiState.value.errorMessage,
        )
    }

    @Test
    fun `given a cancelled pick when a document later arrives then the dropped bundle is not written`() = runTest {
        uiState.value = OrchestratorUiState(pendingBundleExport = bundle)
        val delegate = delegate()
        val picked = FakePickedDocument()

        delegate.consumeBundleExport()
        delegate.cancelBundleSave()
        delegate.saveBundleTo(picked)
        advanceUntilIdle()

        assertEquals(0, picked.bytes.size)
        assertTrue(picked.deleteAsked)
    }

    @Test
    fun `given a document that will not open when the bundle is saved then it is removed and the failure said`() =
        runTest {
            uiState.value = OrchestratorUiState(pendingBundleExport = bundle)
            val delegate = delegate()
            val picked = FakePickedDocument(opens = false)

            delegate.consumeBundleExport()
            delegate.saveBundleTo(picked)
            advanceUntilIdle()

            assertTrue(picked.deleteAsked)
            assertEquals(UiText.of(R.string.errors_generic_unexpected), uiState.value.errorMessage)
        }

    @Test
    fun `given the write fails when the bundle is saved then the failure is said and the stream closed`() = runTest {
        uiState.value = OrchestratorUiState(pendingBundleExport = bundle)
        val delegate = delegate()
        val picked = FakePickedDocument(failOnWrite = true)

        delegate.consumeBundleExport()
        delegate.saveBundleTo(picked)
        advanceUntilIdle()

        assertTrue(picked.stream.closed)
        assertEquals(UiText.of(R.string.errors_generic_unexpected), uiState.value.errorMessage)
        assertNull(uiState.value.feedbackMessage)
    }
}
