package app.knotwork.android.presentation.ui.orchestrator.presets

import app.knotwork.android.R
import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelinePreset
import app.knotwork.android.domain.models.PresetCategory
import app.knotwork.android.domain.repositories.PipelinePresetRepository
import app.knotwork.android.domain.usecases.LoadPipelineFromPresetUseCase
import app.knotwork.android.domain.usecases.SavePipelineAsPresetUseCase
import app.knotwork.android.presentation.ui.common.FakePickedDocument
import app.knotwork.android.presentation.ui.common.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PipelinePresetsViewModelTest {

    private lateinit var pipelinePresetRepository: PipelinePresetRepository
    private lateinit var loadPipelineFromPresetUseCase: LoadPipelineFromPresetUseCase
    private lateinit var savePipelineAsPresetUseCase: SavePipelineAsPresetUseCase

    private val testDispatcher = StandardTestDispatcher()

    private val bundledFlow = MutableStateFlow<List<PipelinePreset>>(emptyList())
    private val userFlow = MutableStateFlow<List<PipelinePreset>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        pipelinePresetRepository = mockk(relaxed = true)
        loadPipelineFromPresetUseCase = mockk()
        savePipelineAsPresetUseCase = mockk()
        coEvery { pipelinePresetRepository.getBundledPresets() } returns bundledFlow
        coEvery { pipelinePresetRepository.getUserPresets() } returns userFlow
    }

    @After
    fun tearDown() {
        // Reset the shared flows BEFORE detaching the test dispatcher so any
        // active VM collector (collected on Dispatchers.Main = testDispatcher)
        // sees the change through the right dispatcher. Reversing this order
        // would attempt to resume the collector via the just-reset Main and
        // throw DispatchException.
        bundledFlow.value = emptyList()
        userFlow.value = emptyList()
        Dispatchers.resetMain()
    }

    private fun newVm(): PipelinePresetsViewModel = PipelinePresetsViewModel(
        pipelinePresetRepository,
        loadPipelineFromPresetUseCase,
        savePipelineAsPresetUseCase,
        mockk(relaxed = true),
        ioDispatcher = testDispatcher,
    )

    @Test
    fun `given a preset held for the picker when the document arrives then the preset is written into it`() = runTest {
        val vm = newVm()
        val exported = preset("p1", isBundled = false, category = PresetCategory.LOCAL)
        val picked = FakePickedDocument()

        vm.holdForExport(exported)
        vm.exportTo(picked)
        advanceUntilIdle()

        assertEquals(vm.exportPresetToJson(exported), picked.bytes.toString(Charsets.UTF_8))
        assertTrue("the picked stream must be closed", picked.stream.closed)
        assertFalse(picked.deleteAsked)
        assertEquals(UiText(R.string.orchestrator_preset_exported), vm.uiState.value.feedbackMessage)
    }

    @Test
    fun `given no held preset when the document arrives then the empty file is removed and the failure said`() =
        runTest {
            // A process death while the picker was open: the new view model holds nothing.
            val vm = newVm()
            val picked = FakePickedDocument()

            vm.exportTo(picked)
            advanceUntilIdle()

            assertTrue("the empty file the picker created must be removed", picked.deleteAsked)
            assertEquals(0, picked.openCount)
            assertEquals(UiText(R.string.orchestrator_preset_export_failed), vm.uiState.value.errorMessage)
        }

    @Test
    fun `given no held preset and a file that cannot be deleted when it arrives then its name is said`() = runTest {
        val vm = newVm()

        vm.exportTo(FakePickedDocument(name = "preset.json", deletes = false))
        advanceUntilIdle()

        assertEquals(UiText.of(R.string.export_empty_file_left, "preset.json"), vm.uiState.value.errorMessage)
    }

    @Test
    fun `given a cancelled pick when a document later arrives then the dropped preset is not written`() = runTest {
        val vm = newVm()
        val picked = FakePickedDocument()

        vm.holdForExport(preset("p1", isBundled = false, category = PresetCategory.LOCAL))
        vm.cancelExport()
        vm.exportTo(picked)
        advanceUntilIdle()

        assertEquals(0, picked.bytes.size)
        assertTrue(picked.deleteAsked)
    }

    @Test
    fun `given a document that will not open when the preset is exported then it is removed`() = runTest {
        val vm = newVm()
        val picked = FakePickedDocument(opens = false)

        vm.holdForExport(preset("p1", isBundled = false, category = PresetCategory.LOCAL))
        vm.exportTo(picked)
        advanceUntilIdle()

        assertTrue(picked.deleteAsked)
        assertEquals(UiText(R.string.orchestrator_preset_export_failed), vm.uiState.value.errorMessage)
    }

    @Test
    fun `given bundled and user emissions when subscribed then state mirrors both lists`() = runTest {
        val bundled = listOf(preset("b1", isBundled = true, category = PresetCategory.LOCAL))
        val user = listOf(preset("u1", isBundled = false, category = PresetCategory.CLOUD))
        coEvery { pipelinePresetRepository.getBundledPresets() } returns flowOf(bundled)
        coEvery { pipelinePresetRepository.getUserPresets() } returns flowOf(user)

        val vm = newVm()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(bundled, state.bundledPresets)
        assertEquals(user, state.userPresets)
    }

    @Test
    fun `selectTab clears the category filter`() {
        val vm = newVm()
        vm.selectCategory(PresetCategory.LOCAL)
        vm.selectTab(PresetPickerTab.Mine)

        assertEquals(PresetPickerTab.Mine, vm.uiState.value.activeTab)
        assertNull(vm.uiState.value.selectedCategory)
    }

    @Test
    fun `loadFromPreset on success populates pendingPipelineIdFromPreset and feedback`() = runTest {
        coEvery { loadPipelineFromPresetUseCase("b1") } returns Result.success("new-pipeline-id")
        val vm = newVm()

        vm.loadFromPreset("b1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("new-pipeline-id", vm.uiState.value.pendingPipelineIdFromPreset)
        assertNotNull(vm.uiState.value.feedbackMessage)
    }

    @Test
    fun `loadFromPreset on failure surfaces error message`() = runTest {
        coEvery { loadPipelineFromPresetUseCase("x") } returns Result.failure(IllegalStateException("nope"))
        val vm = newVm()

        vm.loadFromPreset("x")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(vm.uiState.value.errorMessage)
        assertNull(vm.uiState.value.pendingPipelineIdFromPreset)
    }

    @Test
    fun `deleteUserPreset forwards to repository and emits feedback`() = runTest {
        coEvery { pipelinePresetRepository.deleteUserPreset("u1") } returns Unit
        val vm = newVm()

        vm.deleteUserPreset("u1")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { pipelinePresetRepository.deleteUserPreset("u1") }
        assertNotNull(vm.uiState.value.feedbackMessage)
    }

    @Test
    fun `renameUserPreset rejects blank name and surfaces error`() {
        val vm = newVm()
        vm.renameUserPreset(presetId = "u1", newName = "   ")
        assertNotNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `renameUserPreset persists the updated copy via repository`() = runTest {
        val target = preset("u9", isBundled = false, category = PresetCategory.LOCAL)
        userFlow.value = listOf(target)
        val vm = newVm()
        testDispatcher.scheduler.advanceUntilIdle()

        coEvery { pipelinePresetRepository.saveUserPreset(any()) } returns Unit
        vm.renameUserPreset(presetId = "u9", newName = "Renamed")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            pipelinePresetRepository.saveUserPreset(match { it.id == "u9" && it.name == "Renamed" })
        }
    }

    @Test
    fun `consumePendingPipelineNavigation clears the hand-off slot`() = runTest {
        coEvery { loadPipelineFromPresetUseCase(any()) } returns Result.success("p")
        val vm = newVm()
        vm.loadFromPreset("b1")
        testDispatcher.scheduler.advanceUntilIdle()

        vm.consumePendingPipelineNavigation()
        assertNull(vm.uiState.value.pendingPipelineIdFromPreset)
    }

    @Test
    fun `exportPresetToJson returns a non-empty JSON document`() {
        val vm = newVm()
        val p = preset("e1", isBundled = false, category = PresetCategory.OTHER)
        val json = vm.exportPresetToJson(p)
        assertEquals(true, json.contains("\"id\":\"e1\""))
    }

    @Test
    fun `findPreset looks up across both catalogues`() = runTest {
        val bundled = preset("b1", isBundled = true, category = PresetCategory.LOCAL)
        val user = preset("u1", isBundled = false, category = PresetCategory.LOCAL)
        bundledFlow.value = listOf(bundled)
        userFlow.value = listOf(user)
        val vm = newVm()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(bundled, vm.findPreset("b1"))
        assertEquals(user, vm.findPreset("u1"))
        assertNull(vm.findPreset("nope"))
    }

    private fun preset(id: String, isBundled: Boolean, category: PresetCategory): PipelinePreset = PipelinePreset(
        id = id,
        name = "Preset $id",
        description = "demo",
        category = category,
        graph = sampleGraph(),
        tags = emptyList(),
        isBundled = isBundled,
    )

    private fun sampleGraph(): PipelineGraph {
        val nIn = NodeModel(id = "in", type = NodeType.INPUT, x = 0f, y = 0f, contextConfig = NodeContextConfig())
        val nOut = NodeModel(id = "out", type = NodeType.OUTPUT, x = 0f, y = 0f, contextConfig = NodeContextConfig())
        val edge = ConnectionModel(id = "c", sourceNodeId = "in", targetNodeId = "out")
        return PipelineGraph(id = "p", name = "n", nodes = listOf(nIn, nOut), connections = listOf(edge))
    }
}
