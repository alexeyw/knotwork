package app.knotwork.android.domain.verification

import app.knotwork.android.domain.engine.golden.GoldenScenarios
import app.knotwork.android.domain.engine.golden.GoldenTraceHarness
import app.knotwork.android.domain.models.LocalSampling
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.RunHeader
import app.knotwork.android.domain.models.RunSampler
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.repositories.PipelineRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Run again with this seed" end to end, on runs the real engine made: the golden
 * harness runs `showcase_full_agent` / `task-every-subpipeline` — a router, a
 * decomposition, four sub-pipelines, two of them running a tool — as a recorded run
 * with its own seed and sampler, plans starting it again from the record, and runs
 * the request it gets.
 *
 * The started-again run must take the recorded header's seed and sampler, so every
 * on-device call in the tree — sub-pipelines included — runs with exactly the
 * sampling of the recorded call in the same place. The harness's scripted model
 * answers the same whatever the seed, so the content repeats and so must the run
 * digest; a run on a fresh seed repeats the content too, and its digest with it —
 * the digest stands for what came out, not for the seed.
 */
class RunAgainWithSeedGoldenTest {

    private val scenario = GoldenScenarios.all.single {
        it.source.fileStem == "showcase_full_agent" && it.name == "task-every-subpipeline"
    }

    /** The recorded run's seed and sampler — neither the harness's seed nor the settings' sampler. */
    private val recordedSampling = LocalSampling(RunSampler(temperature = 0.3, topK = 12, topP = 0.5), seed = 777)

    @Test
    fun `given a run started again with its seed then every call repeats its sampling and the digest repeats`() =
        runTest {
            val recorded = finishedRun(recordedSampling)
            val request = (plan(recorded) as RunAgainAvailability.Available).request

            val again = finishedRun(request.sampling)

            assertEquals(recordedSampling, request.sampling)
            assertEquals(header(recorded).seed, header(again).seed)
            assertEquals(header(recorded).sampler, header(again).sampler)
            val samplings = samplingsOf(recorded)
            assertTrue("the run has on-device calls one level down", samplings.keys.any { it.first != ROOT_RUN })
            assertEquals(samplings, samplingsOf(again))
            assertEquals(RunDigest.of(tree(recorded)), RunDigest.of(tree(again)))
        }

    @Test
    fun `given a run on a fresh seed when digested then the seeds differ and the digest does not`() = runTest {
        val recorded = finishedRun(recordedSampling)

        val fresh = finishedRun(samplingOverride = null)

        assertNotEquals(header(recorded).seed, header(fresh).seed)
        assertNotEquals(samplingsOf(recorded), samplingsOf(fresh))
        assertEquals(RunDigest.of(tree(recorded)), RunDigest.of(tree(fresh)))
    }

    /** Runs the scenario to its end, started with [samplingOverride] as the queue would. */
    private suspend fun TestScope.finishedRun(samplingOverride: LocalSampling?): GoldenTraceHarness =
        GoldenTraceHarness(scenario, samplingOverride = samplingOverride).also { it.run(this) }

    private suspend fun tree(harness: GoldenTraceHarness): RecordedRunTree =
        ReadRecordedRunTreeUseCase(harness.runRecords, harness.runTrace)(ROOT_RUN)!!

    private suspend fun header(harness: GoldenTraceHarness): RunHeader = harness.runRecords.getRun(ROOT_RUN)!!.header!!

    /** Each on-device call's sampling, keyed by its place in the run tree. */
    private suspend fun samplingsOf(harness: GoldenTraceHarness): Map<Pair<String, String>, LocalSampling> =
        tree(harness).records.filterIsInstance<RunTraceRecord.LocalModelCall>()
            .associate { (it.runId to "${it.nodeId}#${it.visit}.${it.call}") to it.sampling }

    private suspend fun plan(harness: GoldenTraceHarness): RunAgainAvailability {
        val pipelines: PipelineRepository = mockk {
            coEvery { getPipelineById(any()) } answers { PipelineGraph(id = firstArg(), name = "Full agent") }
        }
        return PlanRunAgainWithSeedUseCase(harness.runRecords, pipelines)(ROOT_RUN)
    }

    private companion object {
        /** The harness's root run id. */
        const val ROOT_RUN = "golden"
    }
}
