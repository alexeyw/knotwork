package app.knotwork.android.domain.verification

import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.models.ModelFileStatus
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.models.RunTreeIds
import app.knotwork.android.domain.repositories.GenerationSettings
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.verification.VerificationFixtures.HEADER
import app.knotwork.android.domain.verification.VerificationFixtures.MODEL_PATH
import app.knotwork.android.domain.verification.VerificationFixtures.MODEL_SHA
import app.knotwork.android.domain.verification.VerificationFixtures.ROOT
import app.knotwork.android.domain.verification.VerificationFixtures.cloudCall
import app.knotwork.android.domain.verification.VerificationFixtures.localCall
import app.knotwork.android.domain.verification.VerificationFixtures.nodeIo
import app.knotwork.android.domain.verification.VerificationFixtures.run
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PlanRunVerificationUseCase]: whether a finished run can be checked here now,
 * and the plan — every visit in run order, sub-pipelines in place, each with the
 * calls it repeats or the reason it does not.
 */
class PlanRunVerificationUseCaseTest {

    private val runs: PipelineRunRepository = mockk()
    private val traces = mutableMapOf<String, List<RunTraceRecord>>()
    private val trace: RunTraceRepository = mockk {
        coEvery { getTraceForRun(any()) } answers
            { traces[firstArg()].orEmpty() }
    }
    private var fileStatus: ModelFileStatus = ModelFileStatus.Hashed(MODEL_SHA)
    private val models: LocalModelRepository = mockk {
        coEvery { findByPath(MODEL_PATH) } returns
            LocalModel(name = "Gemma 4 E2B", path = MODEL_PATH, size = 1, isActive = true)
        coEvery { fileStatus(MODEL_PATH) } answers { fileStatus }
    }
    private val pipelines: PipelineRepository = mockk {
        coEvery { getPipelineById("p") } returns PipelineGraph(
            id = "p",
            name = "P",
            nodes = listOf(NodeModel(id = "llm", type = NodeType.LITE_RT, x = 0f, y = 0f, label = "summarise")),
        )
        coEvery { getPipelineById("child") } returns null
    }
    private var backend = "CPU"
    private var window = 4096
    private val settings: GenerationSettings = mockk {
        every { localModelBackend } answers { flowOf(backend) }
        every { maxContextLength } answers { flowOf(window) }
    }
    private val planner = PlanRunVerificationUseCase(runs, trace, models, pipelines, settings)

    private fun givenRun(vararg records: RunTraceRecord, status: PipelineRunStatus = PipelineRunStatus.COMPLETED) {
        coEvery { runs.getRun(ROOT) } returns run(status = status)
        coEvery { runs.getDescendantRuns(ROOT) } returns emptyList()
        traces[ROOT] = records.toList()
    }

    private suspend fun plan(): VerificationPlan = (planner(ROOT) as VerifyAvailability.Available).plan

    @Test
    fun `given a run still going when planned then it is busy`() = runTest {
        givenRun(localCall(0, "llm"), status = PipelineRunStatus.RUNNING)

        assertEquals(VerifyAvailability.Busy, planner(ROOT))
    }

    @Test
    fun `given a run without a header or no run at all when planned then it predates the check`() = runTest {
        coEvery { runs.getRun(ROOT) } returns run(header = null)
        assertEquals(VerifyAvailability.PreVersion, planner(ROOT))

        coEvery { runs.getRun(ROOT) } returns null
        assertEquals(VerifyAvailability.PreVersion, planner(ROOT))
    }

    @Test
    fun `given a run that only asked a cloud model when planned then there is nothing local to repeat`() = runTest {
        givenRun(cloudCall(0, "cloud"), nodeIo(1, "cloud", "CLOUD"))

        assertEquals(VerifyAvailability.NoLocalCalls, planner(ROOT))
    }

    @Test
    fun `given a run when planned then every visit is in run order with what the check does with it`() = runTest {
        givenRun(
            localCall(0, "llm", call = 0),
            localCall(1, "llm", call = 1, durationMs = 500L),
            nodeIo(2, "llm", "LITE_RT"),
            localCall(3, "tool", "TOOL"),
            nodeIo(4, "tool", "TOOL"),
            cloudCall(5, "cloud"),
            nodeIo(6, "cloud", "CLOUD"),
            nodeIo(7, "queue", "QUEUE_PROCESSOR"),
            localCall(8, "npu", backend = LocalBackend.NPU),
            localCall(9, "eye", hadImage = true),
            localCall(10, "old", modelSha = null),
            localCall(11, "out", "OUTPUT"),
        )

        val plan = plan()

        assertEquals(
            listOf("llm", "tool", "cloud", "queue", "npu", "eye", "old", "out"),
            plan.visits.map { it.nodeId },
        )
        assertEquals(
            listOf(
                VisitKind.Repeat,
                VisitKind.NotVerifiable(NotVerifiableReason.TOOL_NOT_EXECUTED),
                VisitKind.NotVerifiable(NotVerifiableReason.CLOUD_MODEL),
                VisitKind.NotVerifiable(NotVerifiableReason.NO_MODEL_CALL),
                VisitKind.NotVerifiable(NotVerifiableReason.NPU),
                VisitKind.NotVerifiable(NotVerifiableReason.IMAGE_INPUT),
                VisitKind.NotVerifiable(NotVerifiableReason.MODEL_NOT_RECORDED),
                VisitKind.Repeat,
            ),
            plan.visits.map { it.kind },
        )
        // A TOOL node's own calls are not repeated: its row says the tool was not run again.
        assertEquals(3, plan.calls)
        assertEquals("summarise", plan.visits.first().label)
        assertEquals(1, plan.cloudCalls)
        assertEquals(2_500L, plan.recordedModelMs)
        assertEquals(HEADER, plan.header)
    }

    @Test
    fun `given a call recorded without a duration when planned then there is no estimate`() = runTest {
        givenRun(localCall(0, "llm"), localCall(1, "out", "OUTPUT", durationMs = null))

        assertNull(plan().recordedModelMs)
    }

    @Test
    fun `given a sub-pipeline when planned then its visits follow its PIPELINE visit one level deeper`() = runTest {
        val child = RunTreeIds.child(ROOT, "pipe", 0)
        coEvery { runs.getRun(ROOT) } returns run()
        coEvery { runs.getDescendantRuns(ROOT) } returns
            listOf(run(id = child, parentRunId = ROOT, pipelineId = "child"))
        traces[ROOT] = listOf(nodeIo(3, "pipe", "PIPELINE"), localCall(4, "llm"), nodeIo(5, "llm", "LITE_RT"))
        traces[child] = listOf(localCall(0, "inner", runId = child), nodeIo(1, "inner", "LITE_RT", runId = child))

        val plan = plan()

        assertEquals(listOf("pipe", "inner", "llm"), plan.visits.map { it.nodeId })
        assertEquals(listOf(0, 1, 0), plan.visits.map { it.depth })
        assertEquals(VisitKind.SubPipeline, plan.visits.first().kind)
        assertEquals("inner", plan.visits[1].label)
    }

    @Test
    fun `given the model file gone, unhashed or replaced when planned then the check names the model`() = runTest {
        givenRun(localCall(0, "llm"))

        fileStatus = ModelFileStatus.Missing
        assertEquals(VerifyAvailability.Mismatch(VerifyMismatch.ModelMissing("Gemma 4 E2B")), planner(ROOT))
        fileStatus = ModelFileStatus.HashPending
        assertEquals(VerifyAvailability.Mismatch(VerifyMismatch.HashPending("Gemma 4 E2B")), planner(ROOT))
        fileStatus = ModelFileStatus.Hashed("another")
        assertEquals(VerifyAvailability.Mismatch(VerifyMismatch.ModelChanged("Gemma 4 E2B")), planner(ROOT))
    }

    @Test
    fun `given other settings now when planned then the difference names both values`() = runTest {
        givenRun(localCall(0, "llm", backend = LocalBackend.GPU, window = 4096))

        backend = "CPU"
        window = 4096
        assertEquals(
            VerifyAvailability.Mismatch(VerifyMismatch.Backend(LocalBackend.GPU, LocalBackend.CPU)),
            planner(ROOT),
        )
        backend = "GPU"
        window = 2048
        assertEquals(VerifyAvailability.Mismatch(VerifyMismatch.Window(4096, 2048)), planner(ROOT))
        backend = "CPU"
        assertEquals(
            VerifyAvailability.Mismatch(VerifyMismatch.BackendAndWindow(LocalBackend.GPU, 4096, LocalBackend.CPU)),
            planner(ROOT),
        )
    }

    @Test
    fun `given a CPU run and another window now when planned then the window does not matter`() = runTest {
        givenRun(localCall(0, "llm", backend = LocalBackend.CPU, window = 4096))
        window = 2048

        assertEquals(1, plan().calls)
    }

    @Test
    fun `given every on-device call on the NPU when planned then the check is available with nothing to repeat`() =
        runTest {
            givenRun(localCall(0, "llm", backend = LocalBackend.NPU))
            // The NPU call is not repeated, so the settings' backend does not stop the check.
            backend = "GPU"

            assertEquals(0, plan().calls)
        }
}
