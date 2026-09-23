package app.knotwork.android.presentation.ui.files

import android.content.Context
import app.knotwork.android.R
import app.knotwork.android.domain.models.WorkspaceError
import app.knotwork.android.domain.models.WorkspaceFile
import app.knotwork.android.domain.models.WorkspaceListing
import app.knotwork.android.domain.models.WorkspaceResult
import app.knotwork.android.domain.models.WorkspaceTextPreview
import app.knotwork.android.domain.models.WorkspaceUsage
import app.knotwork.android.domain.usecases.workspace.DeleteWorkspaceFilesUseCase
import app.knotwork.android.domain.usecases.workspace.ExportWorkspaceFileUseCase
import app.knotwork.android.domain.usecases.workspace.ImportFileToWorkspaceUseCase
import app.knotwork.android.domain.usecases.workspace.ImportMode
import app.knotwork.android.domain.usecases.workspace.ListWorkspaceUseCase
import app.knotwork.android.domain.usecases.workspace.PreviewWorkspaceFileUseCase
import app.knotwork.android.domain.usecases.workspace.StageWorkspaceFileForShareUseCase
import app.knotwork.android.domain.usecases.workspace.WorkspaceDeleteSummary
import app.knotwork.android.presentation.state.TransientMessageRelay
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var listUseCase: ListWorkspaceUseCase
    private lateinit var previewUseCase: PreviewWorkspaceFileUseCase
    private lateinit var deleteUseCase: DeleteWorkspaceFilesUseCase
    private lateinit var importUseCase: ImportFileToWorkspaceUseCase
    private lateinit var exportUseCase: ExportWorkspaceFileUseCase
    private lateinit var stageForShareUseCase: StageWorkspaceFileForShareUseCase
    private lateinit var relay: TransientMessageRelay
    private lateinit var appContext: Context

    /** Captures the string resource each failure path resolves for its snackbar. */
    private val resolvedRes = slot<Int>()

    private val textFile = WorkspaceFile("a.txt", sizeBytes = 5, lastModified = 1, isDirectory = false, isText = true)
    private val binFile = WorkspaceFile("b.bin", sizeBytes = 9, lastModified = 1, isDirectory = false, isText = false)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        listUseCase = mockk()
        previewUseCase = mockk()
        deleteUseCase = mockk()
        importUseCase = mockk()
        exportUseCase = mockk()
        stageForShareUseCase = mockk()
        relay = mockk(relaxed = true)
        appContext = mockk()
        every { appContext.getString(capture(resolvedRes)) } returns SNACKBAR_TEXT
        coEvery { listUseCase() } returns WorkspaceResult.Success(
            WorkspaceListing(
                files = listOf(textFile, binFile),
                usage = WorkspaceUsage(usedBytes = 14, limitBytes = 100),
            ),
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun build(): FilesViewModel = FilesViewModel(
        listUseCase,
        previewUseCase,
        deleteUseCase,
        importUseCase,
        exportUseCase,
        stageForShareUseCase,
        FilesMessenger(relay, appContext),
    )

    @Test
    fun `given listing succeeds when initialised then state is populated`() = runTest {
        val vm = build()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(2, state.files.size)
        assertEquals(14L, state.usage.usedBytes)
        assertFalse(state.loading)
        assertFalse(state.loadFailed)
    }

    @Test
    fun `given listing fails when initialised then load failed flag is set`() = runTest {
        coEvery { listUseCase() } returns WorkspaceResult.Failure(WorkspaceError.NotFound)
        val vm = build()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.loadFailed)
    }

    @Test
    fun `given long press when handled then selection mode starts with the row selected`() = runTest {
        val vm = build()
        advanceUntilIdle()

        vm.onRowLongClick("a.txt")

        assertTrue(vm.uiState.value.selectionMode)
        assertEquals(setOf("a.txt"), vm.uiState.value.selectedPaths)
    }

    @Test
    fun `given selection mode when tapping a row then it toggles selection`() = runTest {
        val vm = build()
        advanceUntilIdle()
        vm.onRowLongClick("a.txt")

        vm.onRowClick("b.bin")
        assertEquals(setOf("a.txt", "b.bin"), vm.uiState.value.selectedPaths)

        vm.onRowClick("a.txt")
        assertEquals(setOf("b.bin"), vm.uiState.value.selectedPaths)
    }

    @Test
    fun `given deselecting the last item then selection mode exits`() = runTest {
        val vm = build()
        advanceUntilIdle()
        vm.onRowLongClick("a.txt")

        vm.onRowClick("a.txt")

        assertFalse(vm.uiState.value.selectionMode)
        assertTrue(vm.uiState.value.selectedPaths.isEmpty())
    }

    @Test
    fun `given select all then every file is selected`() = runTest {
        val vm = build()
        advanceUntilIdle()
        vm.onRowLongClick("a.txt")

        vm.selectAll()

        assertEquals(setOf("a.txt", "b.bin"), vm.uiState.value.selectedPaths)
    }

    @Test
    fun `given a text file tapped outside selection then a preview opens`() = runTest {
        coEvery { previewUseCase("a.txt") } returns WorkspaceResult.Success(
            WorkspaceTextPreview(text = "hi", totalBytes = 2, truncated = false),
        )
        val vm = build()
        advanceUntilIdle()

        vm.onRowClick("a.txt")
        advanceUntilIdle()

        assertEquals("a.txt", vm.uiState.value.preview?.path)
    }

    @Test
    fun `given a binary file tapped then no preview opens`() = runTest {
        val vm = build()
        advanceUntilIdle()

        vm.onRowClick("b.bin")
        advanceUntilIdle()

        assertNull(vm.uiState.value.preview)
    }

    @Test
    fun `given a delete request when confirmed then the use case runs and the dialog closes`() = runTest {
        coEvery { deleteUseCase(listOf("a.txt")) } returns
            WorkspaceDeleteSummary(deleted = listOf("a.txt"), failed = emptyMap())
        val vm = build()
        advanceUntilIdle()

        vm.requestDelete("a.txt")
        assertEquals(listOf("a.txt"), vm.uiState.value.pendingDelete)

        vm.confirmDelete()
        advanceUntilIdle()

        coVerify { deleteUseCase(listOf("a.txt")) }
        assertNull(vm.uiState.value.pendingDelete)
    }

    @Test
    fun `given a delete request when cancelled then nothing is deleted`() = runTest {
        val vm = build()
        advanceUntilIdle()
        vm.requestDelete("a.txt")

        vm.cancelDelete()

        assertNull(vm.uiState.value.pendingDelete)
        coVerify(exactly = 0) { deleteUseCase(any()) }
    }

    @Test
    fun `given an import pick with no collision then it imports create-or-fail`() = runTest {
        coEvery { importUseCase(any(), any(), ImportMode.CreateOrFail) } returns
            WorkspaceResult.Success(textFile)
        val vm = build()
        advanceUntilIdle()

        vm.onImportPicked("fresh.txt") { streamOf() }
        advanceUntilIdle()

        coVerify { importUseCase("fresh.txt", any(), ImportMode.CreateOrFail) }
        assertNull(vm.uiState.value.collision)
    }

    @Test
    fun `given an import pick colliding with a file then the collision dialog opens`() = runTest {
        val vm = build()
        advanceUntilIdle()

        vm.onImportPicked("a.txt") { streamOf() }
        advanceUntilIdle()

        val collision = vm.uiState.value.collision
        assertEquals("a.txt", collision?.name)
        assertEquals("a (1).txt", collision?.keepBothName)
        coVerify(exactly = 0) { importUseCase(any(), any(), any()) }
    }

    @Test
    fun `given a collision when keep-both chosen then it imports keep-both`() = runTest {
        coEvery { importUseCase(any(), any(), ImportMode.KeepBoth) } returns WorkspaceResult.Success(textFile)
        val vm = build()
        advanceUntilIdle()
        vm.onImportPicked("a.txt") { streamOf() }
        advanceUntilIdle()

        vm.resolveCollisionKeepBoth()
        advanceUntilIdle()

        coVerify { importUseCase("a.txt", any(), ImportMode.KeepBoth) }
        assertNull(vm.uiState.value.collision)
    }

    @Test
    fun `given a save-as request then a launch event is emitted`() = runTest {
        val vm = build()
        advanceUntilIdle()
        val events = mutableListOf<FilesEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

        vm.requestSaveAs("reports/x.md")
        advanceUntilIdle()

        assertTrue(events.any { it is FilesEvent.LaunchSaveAs && it.suggestedName == "x.md" })
        job.cancel()
    }

    @Test
    fun `given share requested then the workspace stages the copy and the event carries it`() = runTest {
        coEvery { stageForShareUseCase("a.txt") } returns WorkspaceResult.Success("/cache/shared/k-1/a.txt")
        val vm = build()
        advanceUntilIdle()
        val events = mutableListOf<FilesEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

        vm.requestShare("a.txt")
        advanceUntilIdle()

        assertTrue(events.any { it is FilesEvent.ShareStaged && it.stagedPaths == listOf("/cache/shared/k-1/a.txt") })
        verify(exactly = 0) { relay.post(any()) }
        job.cancel()
    }

    @Test
    fun `given no selected file can be staged then no share sheet opens and the failure is said`() = runTest {
        coEvery { stageForShareUseCase(any()) } returns WorkspaceResult.Failure(WorkspaceError.NotFound)
        val vm = build()
        advanceUntilIdle()
        val events = mutableListOf<FilesEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

        vm.requestShare("a.txt")
        advanceUntilIdle()

        assertFalse(events.any { it is FilesEvent.ShareStaged })
        assertEquals(R.string.files_message_share_failed, resolvedRes.captured)
        verify { relay.post(SNACKBAR_TEXT) }
        job.cancel()
    }

    @Test
    fun `given staging throws for one file then the others are still shared`() = runTest {
        coEvery { stageForShareUseCase("a.txt") } throws IOException("disk full")
        coEvery { stageForShareUseCase("b.bin") } returns WorkspaceResult.Success("/cache/shared/k-2/b.bin")
        every { appContext.resources.getQuantityString(any(), any(), any(), any()) } returns SNACKBAR_TEXT
        val vm = build()
        advanceUntilIdle()
        val events = mutableListOf<FilesEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.onRowLongClick("a.txt")
        vm.onRowClick("b.bin")

        vm.requestShareSelected()
        advanceUntilIdle()

        assertTrue(events.any { it is FilesEvent.ShareStaged && it.stagedPaths == listOf("/cache/shared/k-2/b.bin") })
        verify { relay.post(SNACKBAR_TEXT) }
        job.cancel()
    }

    @Test
    fun `given export delegated then it returns the use case outcome`() = runTest {
        val sink = java.io.ByteArrayOutputStream()
        coEvery { exportUseCase("a.txt", sink) } returns WorkspaceResult.Success(Unit)
        val vm = build()
        advanceUntilIdle()

        assertTrue(vm.exportTo("a.txt", sink))
    }

    @Test
    fun `given listing throws when refreshing then the error state is set instead of crashing`() = runTest {
        coEvery { listUseCase() } throws IOException("disk fault")
        val vm = build()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.loadFailed)
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun `given preview throws when opening then a failure message is emitted and no preview opens`() = runTest {
        coEvery { previewUseCase("a.txt") } throws IOException("disk fault")
        val vm = build()
        advanceUntilIdle()

        vm.onRowClick("a.txt")
        advanceUntilIdle()

        assertNull(vm.uiState.value.preview)
        // Asserted at the seam that actually reaches a person. The previous
        // assertion stopped at a `FilesEvent.ShowMessage` that `FilesScreen`
        // dropped in an empty `when` branch, so it stayed green while the user
        // saw nothing at all.
        assertEquals(R.string.files_message_preview_failed, resolvedRes.captured)
        verify { relay.post(SNACKBAR_TEXT) }
    }

    @Test
    fun `given delete throws when confirming then the dialog closes and a failure message is emitted`() = runTest {
        coEvery { deleteUseCase(any()) } throws IOException("disk fault")
        val vm = build()
        advanceUntilIdle()
        vm.requestDelete("a.txt")

        vm.confirmDelete()
        advanceUntilIdle()

        assertNull(vm.uiState.value.pendingDelete)
        assertEquals(R.string.files_message_delete_partial, resolvedRes.captured)
        verify { relay.post(SNACKBAR_TEXT) }
    }

    private fun streamOf(content: String = "data"): InputStream = ByteArrayInputStream(content.toByteArray())

    private companion object {
        /**
         * Stand-in for every resolved snackbar sentence. The test asserts
         * *which resource* was chosen via [resolvedRes]; the rendered text is
         * the string file's business, not this test's.
         */
        const val SNACKBAR_TEXT = "resolved-snackbar-text"
    }
}
