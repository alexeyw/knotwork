package app.knotwork.android.domain.verification

import app.knotwork.android.domain.models.LocalSampling
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.verification.VerificationFixtures.HEADER
import app.knotwork.android.domain.verification.VerificationFixtures.ROOT
import app.knotwork.android.domain.verification.VerificationFixtures.SESSION
import app.knotwork.android.domain.verification.VerificationFixtures.run
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PlanRunAgainWithSeedUseCase]: offered for a finished chat or share run without
 * an image, on the pipeline it executed, with its own seed and sampler — and
 * otherwise named why not.
 */
class PlanRunAgainWithSeedUseCaseTest {

    private var recorded: PipelineRun? = run().copy(userPrompt = "Summarise my day")
    private val runs: PipelineRunRepository = mockk {
        coEvery { getRun(ROOT) } answers { recorded }
    }
    private val pipelines: PipelineRepository = mockk {
        coEvery { getPipelineById("p") } returns PipelineGraph(id = "p", name = "Daily brief")
        coEvery { getPipelineById("gone") } returns null
    }
    private val plan = PlanRunAgainWithSeedUseCase(runs, pipelines)

    private fun notOffered(reason: RunAgainNotOfferedReason) = RunAgainAvailability.NotOffered(reason)

    @Test
    fun `given a finished chat run when planned then the request repeats its message, pipeline, seed and sampler`() =
        runTest {
            assertEquals(
                RunAgainAvailability.Available(
                    RunAgainRequest(
                        sessionId = SESSION,
                        pipelineId = "p",
                        pipelineName = "Daily brief",
                        userPrompt = "Summarise my day",
                        sampling = LocalSampling(HEADER.sampler, HEADER.seed),
                    ),
                ),
                plan(ROOT),
            )
        }

    @Test
    fun `given a share run when planned then it is offered like a chat run`() = runTest {
        recorded = recorded?.copy(origin = RunOrigin.SHARE)

        assertEquals(RunAgainAvailability.Available::class, plan(ROOT)::class)
    }

    @Test
    fun `given a run still going when planned then it is busy`() = runTest {
        recorded = recorded?.copy(status = PipelineRunStatus.WAITING_CLARIFICATION)

        assertEquals(RunAgainAvailability.Busy, plan(ROOT))
    }

    @Test
    fun `given no run, no header or no message when planned then no seed was recorded`() = runTest {
        val finished = recorded
        recorded = null
        assertEquals(notOffered(RunAgainNotOfferedReason.PRE_VERSION), plan(ROOT))
        recorded = finished?.copy(header = null)
        assertEquals(notOffered(RunAgainNotOfferedReason.PRE_VERSION), plan(ROOT))
        recorded = finished?.copy(userPrompt = null)
        assertEquals(notOffered(RunAgainNotOfferedReason.PRE_VERSION), plan(ROOT))
    }

    @Test
    fun `given each background origin when planned then it is not offered`() = runTest {
        val finished = recorded
        for (origin in listOf(RunOrigin.SCHEDULER, RunOrigin.QUICK_TILE, RunOrigin.TRIGGER, RunOrigin.EXTERNAL)) {
            recorded = finished?.copy(origin = origin)

            assertEquals(origin.name, notOffered(RunAgainNotOfferedReason.BACKGROUND_ORIGIN), plan(ROOT))
        }
    }

    @Test
    fun `given a message with an image when planned then it is not offered`() = runTest {
        recorded = recorded?.copy(hadImage = true)

        assertEquals(notOffered(RunAgainNotOfferedReason.IMAGE), plan(ROOT))
    }

    @Test
    fun `given the pipeline deleted or never resolved when planned then it is not offered`() = runTest {
        val finished = recorded
        recorded = finished?.copy(pipelineId = "gone")
        assertEquals(notOffered(RunAgainNotOfferedReason.PIPELINE_DELETED), plan(ROOT))
        recorded = finished?.copy(pipelineId = null)
        assertEquals(notOffered(RunAgainNotOfferedReason.PIPELINE_DELETED), plan(ROOT))
    }
}
