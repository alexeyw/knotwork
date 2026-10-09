package app.knotwork.android.presentation.ui.triggers

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import app.knotwork.android.R
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.Trigger
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.TriggerJournalRepository
import app.knotwork.android.domain.repositories.TriggerRepository
import app.knotwork.android.domain.usecases.BuildTriggerJournalExportUseCase
import app.knotwork.android.domain.usecases.ExportTriggerJournalUseCase
import app.knotwork.android.domain.usecases.ObserveTriggerHealthInputsUseCase
import app.knotwork.android.domain.usecases.ObserveTriggerJournalUseCase
import app.knotwork.android.domain.usecases.SaveTriggerUseCase
import app.knotwork.android.domain.usecases.SyncTriggersUseCase
import app.knotwork.design.theme.KnotworkTheme
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

/**
 * Compose happy-path for the Triggers surface: starting from the empty state,
 * create a trigger, bind a pipeline, leave it enabled, save, and confirm the new
 * trigger appears in the list. Backed by a real [TriggersViewModel] over a fake
 * [TriggerRepository] whose `saveTrigger` pushes into the observed flow so the
 * round-trip is genuine.
 */
class TriggersScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun createTrigger_bindPipeline_enabled_appearsInList() {
        val triggers = MutableStateFlow<List<Trigger>>(emptyList())
        val triggerRepository = mockk<TriggerRepository>(relaxed = true) {
            every { observeTriggers() } returns triggers
        }
        val saved = slot<Trigger>()
        coEvery { triggerRepository.saveTrigger(capture(saved)) } answers {
            triggers.value = triggers.value + saved.captured
        }
        val pipelineRepository = mockk<PipelineRepository>(relaxed = true) {
            every { getAllPipelines() } returns flowOf(listOf(PipelineGraph(id = "p1", name = "Daily briefing")))
        }
        val syncTriggers = mockk<SyncTriggersUseCase>(relaxed = true)
        val saveTrigger = SaveTriggerUseCase(triggerRepository, mockk(relaxed = true))
        // Real use cases over a faked journal, as with saveTrigger above: the
        // health flow feeds the same combine() as the trigger list, so it has to
        // emit for the list to render at all — a relaxed mock's Flow never does.
        val journalRepository = mockk<TriggerJournalRepository>(relaxed = true) {
            every { observeHealthInputs() } returns flowOf(emptyMap())
            every { observeByTrigger(any()) } returns flowOf(emptyList())
        }
        val viewModel = TriggersViewModel(
            triggerRepository,
            pipelineRepository,
            saveTrigger,
            syncTriggers,
            ObserveTriggerHealthInputsUseCase(journalRepository),
            ObserveTriggerJournalUseCase(journalRepository),
            ExportTriggerJournalUseCase(journalRepository, BuildTriggerJournalExportUseCase()),
            SavedStateHandle(),
        )

        composeTestRule.setContent {
            KnotworkTheme { TriggersScreen(viewModel = viewModel) }
        }

        // Empty state → open the editor via its primary CTA.
        composeTestRule.onNodeWithText(context.getString(R.string.triggers_empty_cta)).performClick()

        // Fill the name (the first editable field in the editor).
        composeTestRule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Morning briefing")

        // Bind a pipeline: open the picker, then choose the pipeline.
        composeTestRule.onNodeWithText(context.getString(R.string.triggers_pipeline_none)).performClick()
        composeTestRule.onNodeWithText("Daily briefing").performClick()

        // Save the trigger.
        composeTestRule.onNodeWithText(context.getString(R.string.common_save)).performClick()
        composeTestRule.waitForIdle()

        // The new trigger shows in the list.
        composeTestRule.onNodeWithText("Morning briefing").assertExists()
    }
}
