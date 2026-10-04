package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunTerminationReason
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class InitializeAppUseCaseTest {

    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val pipelineRepository: PipelineRepository = mockk(relaxed = true)
    private val loadPipelineFromPresetUseCase: LoadPipelineFromPresetUseCase = mockk(relaxed = true)
    private val pipelineRunRepository: PipelineRunRepository = mockk(relaxed = true)
    private val pendingInteractionRepository: PendingInteractionRepository = mockk(relaxed = true) {
        coEvery { getAllRunIds() } returns emptySet()
    }
    private val seedBundledSkillsUseCase: SeedBundledSkillsUseCase = mockk(relaxed = true)
    private val useCase = InitializeAppUseCase(
        appStateSettings = settingsRepository,
        entryPointSettings = settingsRepository,
        generationSettings = settingsRepository,
        pipelineRepository = pipelineRepository,
        loadPipelineFromPresetUseCase = loadPipelineFromPresetUseCase,
        pipelineRunRepository = pipelineRunRepository,
        pendingInteractionRepository = pendingInteractionRepository,
        seedBundledSkillsUseCase = seedBundledSkillsUseCase,
    )

    @Test
    fun `invoke seeds from showcase preset and sets it as default on first launch`() = runTest {
        // Given — first launch, and the bundled showcase preset materialises cleanly.
        every { settingsRepository.isFirstLaunch } returns flowOf(true)
        coEvery { loadPipelineFromPresetUseCase(SHOWCASE_PRESET_ID) } returns Result.success(SEEDED_ID)

        // When
        useCase()

        // Then — the default prompt is persisted, the seed is materialised from
        // the showcase preset (not the code-level factory), the returned id
        // becomes the application default, and first launch is cleared.
        coVerify { settingsRepository.setSystemPromptPrefix(DefaultPrompts.SYSTEM_PROMPT_PREFIX) }
        coVerify { loadPipelineFromPresetUseCase(SHOWCASE_PRESET_ID) }
        coVerify { settingsRepository.setDefaultPipelineId(SEEDED_ID) }
        coVerify { settingsRepository.setFirstLaunch(false) }
        // The factory fallback must not run when the preset loads.
        coVerify(exactly = 0) { pipelineRepository.savePipeline(any()) }
    }

    @Test
    fun `invoke falls back to factory pipeline when showcase preset fails`() = runTest {
        // Given — first launch, but the showcase preset cannot be materialised.
        every { settingsRepository.isFirstLaunch } returns flowOf(true)
        coEvery { loadPipelineFromPresetUseCase(SHOWCASE_PRESET_ID) } returns
            Result.failure(IllegalStateException("missing asset"))
        val savedPipeline = slot<PipelineGraph>()

        // When
        useCase()

        // Then — the code-level factory pipeline is persisted, is structurally
        // valid, and its id becomes the application default so the user still
        // lands on a runnable pipeline.
        coVerify { pipelineRepository.savePipeline(capture(savedPipeline)) }
        assertEquals(emptyList<Any>(), savedPipeline.captured.validate())
        coVerify { settingsRepository.setDefaultPipelineId(savedPipeline.captured.id) }
        coVerify { settingsRepository.setFirstLaunch(false) }
    }

    @Test
    fun `invoke should do nothing if not first launch`() = runTest {
        every { settingsRepository.isFirstLaunch } returns flowOf(false)

        useCase()

        coVerify(exactly = 0) { settingsRepository.setSystemPromptPrefix(any()) }
        coVerify(exactly = 0) { loadPipelineFromPresetUseCase(any()) }
        coVerify(exactly = 0) { pipelineRepository.savePipeline(any()) }
        coVerify(exactly = 0) { settingsRepository.setDefaultPipelineId(any()) }
        coVerify(exactly = 0) { settingsRepository.setFirstLaunch(any()) }
    }

    @Test
    fun `invoke seeds bundled skills on every launch regardless of first launch`() = runTest {
        // Given — NOT a first launch (the first-launch gate must not apply to
        // the skill seed, so upgrading users still receive the bundled set).
        every { settingsRepository.isFirstLaunch } returns flowOf(false)

        // When
        useCase()

        // Then — the idempotent seed runs anyway.
        coVerify(exactly = 1) { seedBundledSkillsUseCase() }
    }

    @Test
    fun `invoke sweeps orphaned runs to INTERRUPTED on every launch`() = runTest {
        // Given — NOT a first launch, with three runs stranded by a dead
        // process, including one suspended on a HITL approval (its in-memory
        // deferred died with the process, so nothing else can settle it).
        every { settingsRepository.isFirstLaunch } returns flowOf(false)
        coEvery { pipelineRunRepository.getOrphanedRuns() } returns listOf(
            orphanRun("run-1", PipelineRunStatus.RUNNING),
            orphanRun("run-2", PipelineRunStatus.QUEUED),
            orphanRun("run-3", PipelineRunStatus.WAITING_APPROVAL),
        )

        // When
        useCase()

        // Then — both records are finalised as INTERRUPTED with the canonical reason.
        coVerify {
            pipelineRunRepository.finishRun(
                "run-1",
                PipelineRunStatus.INTERRUPTED,
                "Process terminated during execution",
                RunTerminationReason.ProcessDied,
            )
        }
        coVerify {
            pipelineRunRepository.finishRun(
                "run-2",
                PipelineRunStatus.INTERRUPTED,
                "Process terminated during execution",
                RunTerminationReason.ProcessDied,
            )
        }
        coVerify {
            pipelineRunRepository.finishRun(
                "run-3",
                PipelineRunStatus.INTERRUPTED,
                "Process terminated during execution",
                RunTerminationReason.ProcessDied,
            )
        }
    }

    @Test
    fun `invoke sweeps orphaned runs on first launch too`() = runTest {
        // Given — first launch AND an orphaned run (e.g. data restored from backup).
        every { settingsRepository.isFirstLaunch } returns flowOf(true)
        coEvery { loadPipelineFromPresetUseCase(SHOWCASE_PRESET_ID) } returns Result.success(SEEDED_ID)
        coEvery { pipelineRunRepository.getOrphanedRuns() } returns listOf(
            orphanRun("run-1", PipelineRunStatus.RUNNING),
        )

        // When
        useCase()

        // Then — the sweep is not gated behind the first-launch branch.
        coVerify {
            pipelineRunRepository.finishRun(
                "run-1",
                PipelineRunStatus.INTERRUPTED,
                any(),
                RunTerminationReason.ProcessDied,
            )
        }
    }

    @Test
    fun `invoke leaves run store untouched when nothing is orphaned`() = runTest {
        every { settingsRepository.isFirstLaunch } returns flowOf(false)
        coEvery { pipelineRunRepository.getOrphanedRuns() } returns emptyList()

        useCase()

        coVerify(exactly = 0) { pipelineRunRepository.finishRun(any(), any(), any(), any()) }
    }

    /**
     * Builds an orphaned run fixture in the given non-terminal [status].
     */
    private fun orphanRun(id: String, status: PipelineRunStatus): PipelineRun = PipelineRun(
        id = id,
        sessionId = "session-1",
        pipelineId = null,
        origin = RunOrigin.CHAT,
        status = status,
        currentNodeId = null,
        startedAt = 0L,
        finishedAt = null,
        errorMessage = null,
        graphContentHash = null,
    )

    private companion object {
        const val SHOWCASE_PRESET_ID = "showcase_full_agent"
        const val SEEDED_ID = "seeded-pipeline-id"
    }

    @Test
    fun `invoke exempts runs parked on a pending interaction from the orphan sweep`() = runTest {
        every { settingsRepository.isFirstLaunch } returns flowOf(false)
        coEvery { pendingInteractionRepository.getAllRunIds() } returns setOf("run-parked")
        coEvery { pipelineRunRepository.getOrphanedRuns() } returns listOf(
            orphanRun("run-parked", PipelineRunStatus.WAITING_APPROVAL),
            orphanRun("run-dead", PipelineRunStatus.RUNNING),
        )

        useCase()

        coVerify(exactly = 0) { pipelineRunRepository.finishRun("run-parked", any(), any(), any()) }
        coVerify(exactly = 1) {
            pipelineRunRepository.finishRun(
                "run-dead",
                PipelineRunStatus.INTERRUPTED,
                any(),
                RunTerminationReason.ProcessDied,
            )
        }
    }
}
