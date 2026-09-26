package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.Trigger
import app.knotwork.android.domain.models.TriggerCondition
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.TriggerRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DeletePipelineUseCase].
 *
 * There is no "active pipeline" rule any more: every pipeline can be deleted, and
 * keeping the editor consistent is the ViewModel's job.
 */
class DeletePipelineUseCaseTest {

    private val pipelineRepository: PipelineRepository = mockk(relaxed = true)
    private val triggerRepository: TriggerRepository = mockk(relaxed = true) {
        coEvery { observeTriggers() } returns flowOf(emptyList())
    }
    private val syncTriggers: SyncTriggersUseCase = mockk(relaxed = true)
    private val useCase = DeletePipelineUseCase(pipelineRepository, triggerRepository, syncTriggers)

    private fun trigger(id: String, pipelineId: String?, enabled: Boolean = true) = Trigger(
        id = id,
        name = id,
        condition = TriggerCondition.Charging,
        pipelineId = pipelineId,
        prompt = "p",
        enabled = enabled,
        createdAt = 0L,
    )

    @Test
    fun `given triggers bound to the pipeline when it is deleted then they are disabled at once`() = runTest {
        // A trigger used to stay enabled until its next fire noticed the pipeline
        // was gone — and an import reusing the freed id in between made it run the
        // imported graph, unattended.
        coEvery { triggerRepository.observeTriggers() } returns flowOf(
            listOf(trigger("t1", "p1"), trigger("t2", "p1", enabled = false), trigger("t3", "other")),
        )

        useCase(pipelineId = "p1")

        coVerify(exactly = 1) { triggerRepository.setEnabled("t1", false) }
        coVerify(exactly = 0) { triggerRepository.setEnabled("t2", any()) }
        coVerify(exactly = 0) { triggerRepository.setEnabled("t3", any()) }
        // The runtime follows the table, as it does when the user switches a trigger off.
        coVerify(exactly = 1) { syncTriggers() }
    }

    @Test
    fun `given the triggers cannot be switched off when the pipeline is deleted then the delete still succeeds`() =
        runTest {
            // The pipeline is gone either way; reporting a failure would stop the
            // caller from clearing the default and the surfaces that named it. A
            // trigger left on is still switched off by its next fire.
            coEvery { triggerRepository.observeTriggers() } returns flowOf(listOf(trigger("t1", "p1")))
            coEvery { triggerRepository.setEnabled(any(), any()) } throws RuntimeException("db locked")

            val result = useCase(pipelineId = "p1")

            assertTrue(result.isSuccess)
        }

    @Test
    fun `given no trigger is bound to the pipeline when it is deleted then the runtime is not re-synced`() = runTest {
        useCase(pipelineId = "p1")

        coVerify(exactly = 0) { syncTriggers() }
    }

    @Test
    fun `given the delete fails when invoked then no trigger is touched`() = runTest {
        coEvery { pipelineRepository.deletePipeline("p1") } throws RuntimeException("io error")
        coEvery { triggerRepository.observeTriggers() } returns flowOf(listOf(trigger("t1", "p1")))

        useCase(pipelineId = "p1")

        coVerify(exactly = 0) { triggerRepository.setEnabled(any(), any()) }
    }

    @Test
    fun `given any pipeline when invoke then deletes it`() = runTest {
        val result = useCase(pipelineId = "p1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { pipelineRepository.deletePipeline("p1") }
    }

    @Test
    fun `given delete throws when invoke then returns failure`() = runTest {
        coEvery { pipelineRepository.deletePipeline("p2") } throws RuntimeException("io error")

        val result = useCase(pipelineId = "p2")

        assertTrue(result.isFailure)
        assertEquals("io error", result.exceptionOrNull()?.message)
    }
}
