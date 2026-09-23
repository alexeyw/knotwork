package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.ChatSession
import app.knotwork.android.domain.models.EntrySurface
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineBindings
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.Trigger
import app.knotwork.android.domain.models.TriggerCondition
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.TriggerRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [FindPipelineBindingsUseCase] — every id-keyed binding, one source at a time.
 */
class FindPipelineBindingsUseCaseTest {

    private val settingsRepository: SettingsRepository = mockk()
    private val resolveSurface: ResolveSurfacePipelineUseCase = mockk()
    private val triggerRepository: TriggerRepository = mockk()
    private val chatRepository: ChatRepository = mockk()
    private val pipelineRepository: PipelineRepository = mockk()
    private lateinit var useCase: FindPipelineBindingsUseCase

    @Before
    fun setup() {
        every { settingsRepository.defaultPipelineId } returns flowOf(null)
        coEvery { resolveSurface(any()) } returns null
        every { triggerRepository.observeTriggers() } returns flowOf(emptyList())
        every { chatRepository.getSessionsFlow(includeArchived = true) } returns flowOf(emptyList())
        every { pipelineRepository.getAllPipelines() } returns flowOf(emptyList())
        useCase = FindPipelineBindingsUseCase(
            settingsRepository,
            resolveSurface,
            triggerRepository,
            chatRepository,
            pipelineRepository,
        )
    }

    private fun trigger(id: String, pipelineId: String?, enabled: Boolean) = Trigger(
        id = id,
        name = id,
        condition = TriggerCondition.DailySchedule(hour = 8, minute = 0),
        pipelineId = pipelineId,
        prompt = "p",
        enabled = enabled,
        createdAt = 0L,
    )

    private fun caller(id: String, name: String, target: String) = PipelineGraph(
        id = id,
        name = name,
        nodes = listOf(NodeModel(id = "$id-n", type = NodeType.PIPELINE, x = 0f, y = 0f, targetPipelineId = target)),
    )

    @Test
    fun `given nothing bound when looked up then the bindings are empty`() = runTest {
        val bindings = useCase.of("p1")

        assertEquals(PipelineBindings(), bindings)
        assertTrue(bindings.isEmpty)
    }

    @Test
    fun `given the default pipeline when looked up then it is marked default`() = runTest {
        every { settingsRepository.defaultPipelineId } returns flowOf("p1")

        assertTrue(useCase.of("p1").isDefault)
        assertTrue(!useCase.of("p2").isDefault)
    }

    @Test
    fun `given entry surfaces bound to it when looked up then each is listed`() = runTest {
        coEvery { resolveSurface(EntrySurface.SHARE) } returns "p1"
        coEvery { resolveSurface(EntrySurface.EXTERNAL_AUTOMATION) } returns "p1"
        coEvery { resolveSurface(EntrySurface.QUICK_TILE) } returns "p2"

        assertEquals(setOf(EntrySurface.SHARE, EntrySurface.EXTERNAL_AUTOMATION), useCase.of("p1").surfaces)
    }

    @Test
    fun `given triggers bound to it when looked up then disabled ones count too`() = runTest {
        every { triggerRepository.observeTriggers() } returns flowOf(
            listOf(
                trigger("t1", "p1", enabled = true),
                trigger("t2", "p1", enabled = false),
                trigger("t3", "p2", true),
            ),
        )

        assertEquals(2, useCase.of("p1").triggerCount)
    }

    @Test
    fun `given chats bound to it when looked up then archived ones count too`() = runTest {
        every { chatRepository.getSessionsFlow(includeArchived = true) } returns flowOf(
            listOf(
                ChatSession(id = "c1", name = "a", updatedAt = 0L, pipelineId = "p1"),
                ChatSession(id = "c2", name = "b", updatedAt = 0L, pipelineId = "p1", isArchived = true),
                ChatSession(id = "c3", name = "c", updatedAt = 0L, pipelineId = null),
            ),
        )

        assertEquals(2, useCase.of("p1").chatCount)
    }

    @Test
    fun `given pipelines calling it when looked up then they are named`() = runTest {
        every { pipelineRepository.getAllPipelines() } returns flowOf(
            listOf(
                caller("full", "Full agent", target = "subtask_act"),
                caller("other", "Other", target = "subtask_lookup"),
                PipelineGraph(id = "subtask_act", name = "Act on the task"),
            ),
        )

        assertEquals(listOf("Full agent"), useCase.of("subtask_act").callerNames)
    }

    @Test
    fun `given several ids when looked up then each source is read once`() = runTest {
        val bindings = useCase(listOf("a", "b", "c"))

        assertEquals(setOf("a", "b", "c"), bindings.keys)
        coVerify(exactly = 1) { chatRepository.getSessionsFlow(includeArchived = true) }
        coVerify(exactly = 1) { pipelineRepository.getAllPipelines() }
    }

    @Test
    fun `given no ids when looked up then nothing is read`() = runTest {
        assertEquals(emptyMap<String, PipelineBindings>(), useCase(emptyList()))
        coVerify(exactly = 0) { pipelineRepository.getAllPipelines() }
    }
}
