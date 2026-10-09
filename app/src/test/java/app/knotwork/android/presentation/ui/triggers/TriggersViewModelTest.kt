package app.knotwork.android.presentation.ui.triggers

import androidx.lifecycle.SavedStateHandle
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.Trigger
import app.knotwork.android.domain.models.TriggerCondition
import app.knotwork.android.domain.models.TriggerEvaluation
import app.knotwork.android.domain.models.TriggerEvaluationSource
import app.knotwork.android.domain.models.TriggerEvaluationVerdict
import app.knotwork.android.domain.models.TriggerHealthInputs
import app.knotwork.android.domain.models.TriggerRunOutcome
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.TriggerJournalRepository
import app.knotwork.android.domain.repositories.TriggerRepository
import app.knotwork.android.domain.usecases.BuildTriggerJournalExportUseCase
import app.knotwork.android.domain.usecases.ExportTriggerJournalUseCase
import app.knotwork.android.domain.usecases.ObserveTriggerHealthInputsUseCase
import app.knotwork.android.domain.usecases.ObserveTriggerJournalUseCase
import app.knotwork.android.domain.usecases.SaveTriggerUseCase
import app.knotwork.android.domain.usecases.SyncTriggersUseCase
import app.knotwork.android.presentation.ui.common.JournalExportEvent
import app.knotwork.android.presentation.ui.navigation.NavRoutes
import app.knotwork.design.screens.triggers.TriggerConditionType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
class TriggersViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var triggerRepository: TriggerRepository
    private lateinit var pipelineRepository: PipelineRepository
    private lateinit var saveTrigger: SaveTriggerUseCase
    private lateinit var syncTriggers: SyncTriggersUseCase
    private lateinit var observeHealthInputs: ObserveTriggerHealthInputsUseCase
    private lateinit var observeJournal: ObserveTriggerJournalUseCase

    /**
     * The journal store behind the export. Mocked, but the export use case around
     * it is real: the assertions below are about the document the screen hands
     * out, and a mocked renderer could not tell whether it is the right one.
     */
    private lateinit var journalStore: TriggerJournalRepository
    private lateinit var exportTriggerJournal: ExportTriggerJournalUseCase

    private val daily = trigger(
        id = "morning",
        condition = TriggerCondition.DailySchedule(hour = 8, minute = 0),
        pipelineId = "p1",
        enabled = true,
    )
    private val unbound = trigger(
        id = "backup",
        condition = TriggerCondition.IntervalSchedule(intervalMinutes = 360L),
        pipelineId = null,
        enabled = false,
    )

    private fun trigger(id: String, condition: TriggerCondition, pipelineId: String?, enabled: Boolean) = Trigger(
        id = id,
        name = id,
        condition = condition,
        pipelineId = pipelineId,
        prompt = "do it",
        enabled = enabled,
        armed = true,
        createdAt = 10L,
        lastFiredAt = null,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        triggerRepository = mockk(relaxed = true) {
            every { observeTriggers() } returns flowOf(listOf(daily, unbound))
        }
        pipelineRepository = mockk(relaxed = true) {
            every { getAllPipelines() } returns flowOf(listOf(PipelineGraph(id = "p1", name = "Daily briefing")))
        }
        // Use a real SaveTriggerUseCase over the mocked repo so the lifecycle
        // derivation (armed / createdAt) is exercised end-to-end.
        saveTrigger = SaveTriggerUseCase(triggerRepository, mockk(relaxed = true))
        syncTriggers = mockk(relaxed = true)
        observeHealthInputs = mockk { every { this@mockk.invoke() } returns flowOf(emptyMap()) }
        observeJournal = mockk { every { this@mockk.invoke(any()) } returns flowOf(emptyList()) }
        journalStore = mockk()
        coEvery { journalStore.readAll() } returns emptyList()
        exportTriggerJournal = ExportTriggerJournalUseCase(journalStore, BuildTriggerJournalExportUseCase())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(savedStateHandle: SavedStateHandle = SavedStateHandle()) = TriggersViewModel(
        triggerRepository,
        pipelineRepository,
        saveTrigger,
        syncTriggers,
        observeHealthInputs,
        observeJournal,
        exportTriggerJournal,
        savedStateHandle,
    )

    @Test
    fun `given a trigger id in the route when opened then its detail opens once`() = runTest(testDispatcher) {
        // An overdue trigger's notification opens the screen on that trigger.
        val handle = SavedStateHandle(mapOf(NavRoutes.TRIGGERS_OPEN_ARG to "morning"))

        val vm = viewModel(handle)
        advanceUntilIdle()

        assertEquals("morning", vm.uiState.value.detailTriggerId)
        // Consumed: a screen restored after the user closed the detail stays closed.
        assertEquals("", handle.get<String>(NavRoutes.TRIGGERS_OPEN_ARG))
    }

    @Test
    fun `given repositories when initialised then loads triggers and pipelines`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf(daily, unbound), state.triggers)
        assertEquals(1, state.pipelines.size)
        assertEquals(1, state.activeCount)
    }

    @Test
    fun `given a load failure when initialised then surfaces an error`() = runTest(testDispatcher) {
        every { triggerRepository.observeTriggers() } returns flow { throw RuntimeException("boom") }
        val vm = viewModel()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.loadError)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `given the new-trigger action when invoked then opens a fresh draft`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.openNewTrigger()

        val draft = vm.uiState.value.editor
        assertNotNull(draft)
        assertNull(draft?.id)
        assertNull(draft?.pipelineId)
        assertEquals(TriggerConditionType.Daily, draft?.type)
    }

    @Test
    fun `given a valid draft when saved then persists the trigger and re-syncs the runtime`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.openNewTrigger()
            vm.onNameChange("Morning")
            vm.onTypeChange(TriggerConditionType.Charging)
            vm.onPipelineSelect("p1")
            vm.onPromptChange("summarise")

            val saved = slot<Trigger>()
            coEvery { triggerRepository.saveTrigger(capture(saved)) } returns Unit

            vm.saveEditor()
            advanceUntilIdle()

            assertEquals("Morning", saved.captured.name)
            assertEquals(TriggerCondition.Charging, saved.captured.condition)
            assertEquals("p1", saved.captured.pipelineId)
            coVerify(exactly = 1) { syncTriggers() }
            assertNull(vm.uiState.value.editor)
        }

    @Test
    fun `given a blank name when saved then does nothing`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.openNewTrigger() // name is blank by default

        vm.saveEditor()
        advanceUntilIdle()

        coVerify(exactly = 0) { triggerRepository.saveTrigger(any()) }
        assertNotNull(vm.uiState.value.editor)
    }

    @Test
    fun `given a custom interval below the floor when editing then save is disabled`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.openNewTrigger()
        vm.onNameChange("Too fast")
        vm.onTypeChange(TriggerConditionType.Interval)
        vm.onIntervalCustomToggle()
        vm.onIntervalCustomAmountChange("5")

        val draft = vm.uiState.value.editor
        assertNotNull(draft)
        assertTrue(draft!!.intervalError)
        assertFalse(draft.canSave)
    }

    @Test
    fun `given a preset interval above the floor when editing then resolves the condition`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.openNewTrigger()
        vm.onNameChange("Every six hours")
        vm.onTypeChange(TriggerConditionType.Interval)
        vm.onIntervalPreset(360L)
        vm.onPipelineSelect("p1")

        val saved = slot<Trigger>()
        coEvery { triggerRepository.saveTrigger(capture(saved)) } returns Unit
        vm.saveEditor()
        advanceUntilIdle()

        assertEquals(TriggerCondition.IntervalSchedule(360L), saved.captured.condition)
    }

    @Test
    fun `given a network draft with ssids when saved then persists an ssid-scoped wifi condition`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.openNewTrigger()
            vm.onNameChange("At home")
            vm.onTypeChange(TriggerConditionType.Network)
            vm.onWifiOnlyToggle() // user turns Wi-Fi-only on explicitly
            vm.onSsidAdd("Home")
            vm.onSsidAdd("  home  ") // case-insensitive duplicate → ignored
            vm.onSsidAdd("Office")
            vm.onPipelineSelect("p1")

            val saved = slot<Trigger>()
            coEvery { triggerRepository.saveTrigger(capture(saved)) } returns Unit
            vm.saveEditor()
            advanceUntilIdle()

            assertEquals(
                TriggerCondition.NetworkConnected(wifiOnly = true, ssids = listOf("Home", "Office")),
                saved.captured.condition,
            )
        }

    @Test
    fun `given ssids added without toggling wifiOnly when saved then wifiOnly stays as the user left it`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.openNewTrigger()
            vm.onNameChange("Scoped")
            vm.onTypeChange(TriggerConditionType.Network)
            vm.onSsidAdd("Home") // adding an SSID must NOT silently force wifiOnly on
            vm.onPipelineSelect("p1")

            val saved = slot<Trigger>()
            coEvery { triggerRepository.saveTrigger(capture(saved)) } returns Unit
            vm.saveEditor()
            advanceUntilIdle()

            assertEquals(
                TriggerCondition.NetworkConnected(wifiOnly = false, ssids = listOf("Home")),
                saved.captured.condition,
            )
        }

    @Test
    fun `given ssids added when one removed then the draft drops only that ssid`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.openNewTrigger()
        vm.onTypeChange(TriggerConditionType.Network)
        vm.onSsidAdd("Home")
        vm.onSsidAdd("Office")

        vm.onSsidRemove("Home")

        assertEquals(listOf("Office"), vm.uiState.value.editor?.ssids)
    }

    @Test
    fun `given an existing trigger when editing then the draft round-trips the condition`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.openEditTrigger("backup")

        val draft = vm.uiState.value.editor
        assertNotNull(draft)
        assertEquals(TriggerConditionType.Interval, draft?.type)
        assertEquals(360L, draft?.intervalPresetMinutes)
        assertFalse(draft!!.intervalCustom)
    }

    @Test
    fun `given a trigger row when toggled then flips enabled and re-syncs`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.toggleEnabled("morning")
        advanceUntilIdle()

        coVerify(exactly = 1) { triggerRepository.setEnabled("morning", false) }
        coVerify(exactly = 1) { syncTriggers() }
    }

    @Test
    fun `given a delete request when confirmed then deletes and re-syncs`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.requestDelete("morning")
        assertNotNull(vm.uiState.value.deleteTarget)

        vm.confirmDelete()
        advanceUntilIdle()

        coVerify(exactly = 1) { triggerRepository.deleteTrigger("morning") }
        coVerify(exactly = 1) { syncTriggers() }
        assertNull(vm.uiState.value.deleteTarget)
    }

    @Test
    fun `given the editor is open when its trigger is deleted then the editor closes so save cannot resurrect it`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.openEditTrigger("morning")

            vm.requestDelete("morning")
            vm.confirmDelete()
            advanceUntilIdle()

            assertNull(vm.uiState.value.editor)
            coVerify(exactly = 1) { triggerRepository.deleteTrigger("morning") }
        }

    @Test
    fun `given a delete request when cancelled then nothing is deleted`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.requestDelete("morning")
        vm.cancelDelete()
        advanceUntilIdle()

        coVerify(exactly = 0) { triggerRepository.deleteTrigger(any()) }
        assertNull(vm.uiState.value.deleteTarget)
    }

    // ── Detail surface ───────────────────────────────────────────────────────

    @Test
    fun `given a row tap when clicked then opens the detail surface, not the editor`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onRowClick("morning")
        advanceUntilIdle()

        assertEquals("morning", vm.uiState.value.detailTriggerId)
        assertEquals(daily, vm.uiState.value.detailTrigger)
        assertNull(vm.uiState.value.editor)
    }

    @Test
    fun `given a detail is open when its journal emits then the journal lands in state`() = runTest(testDispatcher) {
        val journal = listOf(
            TriggerEvaluation(
                id = "e1",
                triggerId = "morning",
                evaluatedAt = 42L,
                source = TriggerEvaluationSource.POLL,
                verdict = TriggerEvaluationVerdict.Fired,
                runId = "r1",
                outcome = TriggerRunOutcome.Success,
            ),
        )
        every { observeJournal("morning") } returns flowOf(journal)
        val vm = viewModel()
        advanceUntilIdle()

        vm.openDetail("morning")
        advanceUntilIdle()

        assertEquals(journal, vm.uiState.value.detailJournal)
    }

    @Test
    fun `given a detail is open when edit is requested then opens the editor for that trigger`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.openDetail("morning")

            vm.editFromDetail()

            assertEquals("morning", vm.uiState.value.editor?.id)
        }

    @Test
    fun `given a detail is open when closed then clears the detail and its journal`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.openDetail("morning")
        advanceUntilIdle()

        vm.closeDetail()

        assertNull(vm.uiState.value.detailTriggerId)
        assertNull(vm.uiState.value.detailJournal)
    }

    @Test
    fun `given the open detail trigger is deleted when confirmed then the detail closes`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.openDetail("morning")
        advanceUntilIdle()

        vm.requestDelete("morning")
        vm.confirmDelete()
        advanceUntilIdle()

        assertNull(vm.uiState.value.detailTriggerId)
        assertNull(vm.uiState.value.detailJournal)
        coVerify(exactly = 1) { triggerRepository.deleteTrigger("morning") }
    }

    @Test
    fun `given health inputs when initialised then they land in state`() = runTest(testDispatcher) {
        val inputs = mapOf("morning" to TriggerHealthInputs(latestEvaluatedAt = 99L))
        every { observeHealthInputs() } returns flowOf(inputs)
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(inputs, vm.uiState.value.healthInputs)
    }

    // --- Journal export ----------------------------------------------------

    @Test
    fun `given several triggers when the journal is exported then every trigger's evaluations travel`() =
        runTest(testDispatcher) {
            coEvery { journalStore.readAll() } returns listOf(
                evaluation(id = "e1", triggerId = "morning"),
                evaluation(id = "e2", triggerId = "backup"),
            )
            val vm = viewModel()
            val received = mutableListOf<JournalExportEvent>()
            val collector = launch { vm.journalExportEvents.collect(received::add) }

            vm.journalExport.share()
            advanceUntilIdle()
            collector.cancel()

            // The action sits on the list, and the document is the whole journal —
            // not the trigger a detail sheet happens to have open. A per-trigger
            // export could not answer "was there a day with no evaluation at all?".
            val event = received.single() as JournalExportEvent.Share
            assertEquals(2, event.document.entryCount)
            assertTrue(event.document.json.contains("\"triggerId\": \"morning\""))
            assertTrue(event.document.json.contains("\"triggerId\": \"backup\""))
        }

    @Test
    fun `given a trigger detail is open when the journal is exported then the export is still unfiltered`() =
        runTest(testDispatcher) {
            coEvery { journalStore.readAll() } returns listOf(
                evaluation(id = "e1", triggerId = "morning"),
                evaluation(id = "e2", triggerId = "backup"),
            )
            val vm = viewModel()
            advanceUntilIdle()
            vm.onRowClick("morning")
            advanceUntilIdle()
            val received = mutableListOf<JournalExportEvent>()
            val collector = launch { vm.journalExportEvents.collect(received::add) }

            vm.journalExport.share()
            advanceUntilIdle()
            collector.cancel()

            // The open detail narrows what is *rendered*, never what is exported.
            assertEquals(2, (received.single() as JournalExportEvent.Share).document.entryCount)
        }

    @Test
    fun `given an unreadable journal when exported then an empty document is produced rather than a failure`() =
        runTest(testDispatcher) {
            // The store degrades a storage error to an empty snapshot; the export
            // inherits that, so a broken database yields an empty file instead of
            // crashing the screen the user opened to diagnose it.
            coEvery { journalStore.readAll() } returns emptyList()
            val vm = viewModel()
            val received = mutableListOf<JournalExportEvent>()
            val collector = launch { vm.journalExportEvents.collect(received::add) }

            vm.journalExport.share()
            advanceUntilIdle()
            collector.cancel()

            val event = received.single() as JournalExportEvent.Share
            assertEquals(0, event.document.entryCount)
            assertTrue(event.document.json.contains("\"totalEvaluations\": 0"))
        }

    private fun evaluation(id: String, triggerId: String) = TriggerEvaluation(
        id = id,
        triggerId = triggerId,
        evaluatedAt = 42L,
        source = TriggerEvaluationSource.POLL,
        verdict = TriggerEvaluationVerdict.Fired,
        runId = "run-$id",
        outcome = TriggerRunOutcome.Success,
    )
}
