package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.constants.OnboardingScenarioCatalog
import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.EntrySurface
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SetUpScenarioUseCase] — the one-tap onboarding set-up
 * orchestration. Every collaborator is mocked so the test pins the wiring:
 * materialise → set default → bind surface (when declared) → project recap.
 */
class SetUpScenarioUseCaseTest {

    private lateinit var loadPipelineFromPreset: LoadPipelineFromPresetUseCase
    private lateinit var setSurfacePipeline: SetSurfacePipelineUseCase
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var pipelineRepository: PipelineRepository
    private lateinit var useCase: SetUpScenarioUseCase

    @Before
    fun setUp() {
        loadPipelineFromPreset = mockk()
        setSurfacePipeline = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        pipelineRepository = mockk(relaxed = true)
        useCase = SetUpScenarioUseCase(
            loadPipelineFromPresetUseCase = loadPipelineFromPreset,
            setSurfacePipelineUseCase = setSurfacePipeline,
            entryPointSettings = settingsRepository,
            pipelineRepository = pipelineRepository,
        )
    }

    private fun graph(id: String): PipelineGraph = PipelineGraph(
        id = id,
        name = "Materialised",
        nodes = listOf(
            NodeModel(id = "a", type = NodeType.INPUT, x = 0f, y = 0f),
            NodeModel(id = "b", type = NodeType.LITE_RT, x = 0f, y = 0f),
            NodeModel(id = "c", type = NodeType.OUTPUT, x = 0f, y = 0f),
        ),
        connections = listOf(
            ConnectionModel(id = "c1", sourceNodeId = "a", targetNodeId = "b"),
            ConnectionModel(id = "c2", sourceNodeId = "b", targetNodeId = "c"),
        ),
    )

    @Test
    fun `given styled-translation when invoked then materialises and sets default without a surface`() = runTest {
        coEvery { loadPipelineFromPreset(OnboardingScenarioCatalog.ID_STYLED_TRANSLATION) } returns
            Result.success("pipe-styled")
        coEvery { pipelineRepository.getPipelineById("pipe-styled") } returns graph("pipe-styled")

        val result = useCase(OnboardingScenarioCatalog.ID_STYLED_TRANSLATION)

        assertTrue(result.isSuccess)
        val setup = result.getOrThrow()
        assertEquals("pipe-styled", setup.pipelineId)
        assertEquals(listOf("INPUT", "LITE_RT", "OUTPUT"), setup.nodeTypeNames)
        assertEquals(3, setup.nodeCount)
        assertEquals(2, setup.edgeCount)
        coVerify(exactly = 1) { settingsRepository.setDefaultPipelineId("pipe-styled") }
        coVerify(exactly = 0) { setSurfacePipeline(any(), any()) }
    }

    @Test
    fun `given share-handler scenario when invoked then binds the share surface`() = runTest {
        coEvery { loadPipelineFromPreset(OnboardingScenarioCatalog.ID_SHARE_HANDLER) } returns
            Result.success("pipe-share")
        coEvery { pipelineRepository.getPipelineById("pipe-share") } returns graph("pipe-share")

        val result = useCase(OnboardingScenarioCatalog.ID_SHARE_HANDLER)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { settingsRepository.setDefaultPipelineId("pipe-share") }
        coVerify(exactly = 1) { setSurfacePipeline(EntrySurface.SHARE, "pipe-share") }
    }

    @Test
    fun `given unknown scenario id when invoked then fails without touching settings`() = runTest {
        val result = useCase("does-not-exist")

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { settingsRepository.setDefaultPipelineId(any()) }
        coVerify(exactly = 0) { setSurfacePipeline(any(), any()) }
    }

    @Test
    fun `given preset materialisation failure when invoked then fails without setting a default`() = runTest {
        coEvery { loadPipelineFromPreset(OnboardingScenarioCatalog.ID_STYLED_TRANSLATION) } returns
            Result.failure(IllegalStateException("missing preset"))

        val result = useCase(OnboardingScenarioCatalog.ID_STYLED_TRANSLATION)

        assertFalse(result.isSuccess)
        coVerify(exactly = 0) { settingsRepository.setDefaultPipelineId(any()) }
        coVerify(exactly = 0) { setSurfacePipeline(any(), any()) }
    }
}
