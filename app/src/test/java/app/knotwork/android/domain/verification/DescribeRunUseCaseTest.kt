package app.knotwork.android.domain.verification

import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.models.ModelFileStatus
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.repositories.GenerationSettings
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.verification.VerificationFixtures.MODEL_PATH
import app.knotwork.android.domain.verification.VerificationFixtures.MODEL_SHA
import app.knotwork.android.domain.verification.VerificationFixtures.ROOT
import app.knotwork.android.domain.verification.VerificationFixtures.cloudCall
import app.knotwork.android.domain.verification.VerificationFixtures.localCall
import app.knotwork.android.domain.verification.VerificationFixtures.nodeIo
import app.knotwork.android.domain.verification.VerificationFixtures.run
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DescribeRunUseCase]: what the run header shows — the model files the calls used,
 * how much a cloud model answered, the digest, the promise, and whether the check
 * and the start-again are offered — from one reading of the run.
 */
class DescribeRunUseCaseTest {

    private var records: List<RunTraceRecord> = listOf(
        localCall(0, "llm"),
        localCall(1, "llm", call = 1),
        nodeIo(2, "llm", "LITE_RT"),
        localCall(3, "check", backend = LocalBackend.GPU),
        nodeIo(4, "check", "EVALUATION"),
        cloudCall(5, "cloud"),
        nodeIo(6, "cloud", "CLOUD"),
    )
    private val runs: PipelineRunRepository = mockk {
        coEvery { getRun(ROOT) } returns run().copy(userPrompt = "hi")
        coEvery { getRun("missing") } returns null
        coEvery { getDescendantRuns(ROOT) } returns emptyList()
    }
    private val traces: RunTraceRepository = mockk {
        coEvery { getTraceForRun(ROOT) } answers { records }
    }
    private val models: LocalModelRepository = mockk {
        coEvery { findByPath(MODEL_PATH) } returns
            LocalModel(name = "Gemma 4 E2B", path = MODEL_PATH, size = 1, isActive = true)
        coEvery { fileStatus(MODEL_PATH) } returns ModelFileStatus.Hashed(MODEL_SHA)
    }
    private val pipelines: PipelineRepository = mockk {
        coEvery { getPipelineById("p") } returns PipelineGraph(id = "p", name = "Daily brief")
    }
    private val settings: GenerationSettings = mockk {
        every { localModelBackend } returns flowOf("CPU")
        every { maxContextLength } returns flowOf(4096)
    }
    private val readTree = ReadRecordedRunTreeUseCase(runs, traces)
    private val describe = DescribeRunUseCase(
        readTree,
        PlanRunVerificationUseCase(readTree, models, pipelines, settings),
        PlanRunAgainWithSeedUseCase(runs, pipelines),
        models,
    )

    @Test
    fun `given a mixed run when described then it names each model use, the cloud share and the actions`() = runTest {
        val description = describe(ROOT)!!

        assertEquals(
            listOf(
                RunModelUse("Gemma 4 E2B", MODEL_SHA, LocalBackend.CPU, 4096),
                RunModelUse("Gemma 4 E2B", MODEL_SHA, LocalBackend.GPU, 4096),
            ),
            description.models,
        )
        assertEquals(3, description.localCalls)
        assertEquals(1, description.cloudCalls)
        assertEquals(
            Reproducibility.Promised(setOf(LocalBackend.CPU, LocalBackend.GPU), usedCloud = true),
            description.reproducibility,
        )
        assertEquals(RunDigest.of(readTree(ROOT)!!), description.digest)
        // The GPU call ran at the window the settings still have, so only the backend differs.
        assertEquals(
            VerifyAvailability.Mismatch(VerifyMismatch.Backend(LocalBackend.GPU, LocalBackend.CPU)),
            description.verify,
        )
        assertTrue(description.runAgain is RunAgainAvailability.Available)
    }

    @Test
    fun `given a run when described then its trace is read once for the header and the check`() = runTest {
        describe(ROOT)

        coVerify(exactly = 1) { traces.getTraceForRun(ROOT) }
    }

    @Test
    fun `given a file no longer registered when described then it is named by its file name`() = runTest {
        coEvery { models.findByPath(MODEL_PATH) } returns null
        records = listOf(localCall(0, "llm"), nodeIo(1, "llm", "LITE_RT"))

        assertEquals("gemma.litertlm", describe(ROOT)!!.models.single().name)
    }

    @Test
    fun `given no such run when described then there is nothing`() = runTest {
        assertNull(describe("missing"))
    }
}
