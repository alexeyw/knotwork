package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.LocalSampling
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunHeader
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunSampler
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.verification.NotPromisedReason
import app.knotwork.android.domain.verification.PlannedVisit
import app.knotwork.android.domain.verification.Reproducibility
import app.knotwork.android.domain.verification.RunAgainAvailability
import app.knotwork.android.domain.verification.RunAgainNotOfferedReason
import app.knotwork.android.domain.verification.RunAgainRequest
import app.knotwork.android.domain.verification.RunDescription
import app.knotwork.android.domain.verification.RunModelUse
import app.knotwork.android.domain.verification.VerificationPlan
import app.knotwork.android.domain.verification.VerifyAvailability
import app.knotwork.android.domain.verification.VerifyMismatch
import app.knotwork.android.domain.verification.VisitKind
import app.knotwork.design.components.console.RunAgainActionUi
import app.knotwork.design.components.console.RunCloudUse
import app.knotwork.design.components.console.RunDetailUi
import app.knotwork.design.components.console.RunExportActionUi
import app.knotwork.design.components.console.RunLineUi
import app.knotwork.design.components.console.RunMismatchUi
import app.knotwork.design.components.console.RunPromiseUi
import app.knotwork.design.components.console.RunVerifyActionUi
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run strip's projection of a run: numbers written for reading, the line and
 * fields per case, the promise's wording, the actions' reasons, and when a check
 * asks first.
 */
class RunDisplayTest {

    private val header = RunHeader(
        seed = 1_482_913,
        sampler = RunSampler(temperature = 0.7, topK = 40, topP = 0.95),
        appVersion = "0.12.0 (17)",
        runtimeVersion = "LiteRT-LM 0.17.1",
        device = "Google Pixel 8 · Android 16",
    )
    private val cpu = RunModelUse("Gemma 4 E2B", "sha", LocalBackend.CPU, 4096)

    private fun run(status: PipelineRunStatus = PipelineRunStatus.COMPLETED, header: RunHeader? = this.header) =
        PipelineRun(
            id = "run",
            sessionId = "s",
            pipelineId = "p",
            origin = RunOrigin.CHAT,
            status = status,
            currentNodeId = null,
            startedAt = 0,
            finishedAt = 1,
            errorMessage = null,
            graphContentHash = null,
            header = header,
        )

    private fun plan(calls: Int, ms: Long?) = VerificationPlan(
        rootRunId = "run",
        header = header,
        visits = listOf(
            PlannedVisit(
                runId = "run",
                depth = 0,
                nodeId = "llm",
                nodeType = "LITE_RT",
                label = "answer",
                visit = 0,
                kind = VisitKind.Repeat,
                calls = List(calls) { mockk<RunTraceRecord.LocalModelCall>() },
            ),
        ),
        cloudCalls = 0,
        recordedModelMs = ms,
    )

    private fun description(
        run: PipelineRun = run(),
        models: List<RunModelUse> = listOf(cpu),
        localCalls: Int = 2,
        cloudCalls: Int = 0,
        reproducibility: Reproducibility = Reproducibility.Promised(setOf(LocalBackend.CPU), usedCloud = false),
        verify: VerifyAvailability = VerifyAvailability.Available(plan(calls = 2, ms = 95_000)),
        runAgain: RunAgainAvailability = RunAgainAvailability.Available(
            RunAgainRequest("s", "p", "Daily brief", "hi", LocalSampling(header.sampler, header.seed)),
        ),
    ) = RunDescription(run, models, localCalls, cloudCalls, "digest", reproducibility, verify, runAgain)

    @Test
    fun `given numbers when written for reading then they are grouped, trimmed and rounded`() {
        assertEquals("1 482 913", RunDisplay.grouped(1_482_913))
        assertEquals("4 096", RunDisplay.grouped(4096))
        assertEquals("512", RunDisplay.grouped(512))
        assertEquals("0.7", RunDisplay.decimal(0.7))
        assertEquals("1", RunDisplay.decimal(1.0))
        assertEquals("55 s", RunDisplay.duration(54_600))
        assertEquals("1 min 35 s", RunDisplay.duration(95_000))
        assertEquals("2 min", RunDisplay.duration(120_000))
        assertEquals("1 s", RunDisplay.duration(20))
    }

    @Test
    fun `given a finished local run when projected then the line, fields and actions say so`() {
        val ui = description().toRunHeaderUi()

        assertEquals(
            RunLineUi.Seeded("1 482 913", "1482913", "CPU", "Gemma 4 E2B", "0.7", withCloud = false),
            ui.line,
        )
        val detail = ui.detail as RunDetailUi.Recorded
        assertEquals("0.95", detail.topP)
        assertEquals(RunPromiseUi.CPU, detail.promise)
        assertEquals("4 096", detail.models.single().window)
        assertEquals(RunVerifyActionUi.Available(calls = 2, estimate = "1 min 35 s"), ui.actions.verify)
        assertEquals(RunAgainActionUi.AVAILABLE, ui.actions.runAgain)
        assertEquals(RunExportActionUi.FULL, ui.actions.export)
        assertFalse(ui.actions.busy)
    }

    @Test
    fun `given each kind of run when projected then the promise and the line name it`() {
        val gpuMixed = description(
            models = listOf(cpu.copy(backend = LocalBackend.GPU)),
            cloudCalls = 1,
            reproducibility = Reproducibility.Promised(setOf(LocalBackend.GPU), usedCloud = true),
        ).toRunHeaderUi()
        assertEquals(RunPromiseUi.MIXED_GPU, (gpuMixed.detail as RunDetailUi.Recorded).promise)
        assertEquals(RunCloudUse.SOME, (gpuMixed.detail as RunDetailUi.Recorded).cloud)
        assertTrue((gpuMixed.line as RunLineUi.Seeded).withCloud)

        val cloudOnly = description(
            models = emptyList(),
            localCalls = 0,
            cloudCalls = 3,
            reproducibility = Reproducibility.NotPromised(NotPromisedReason.CLOUD_ONLY),
            verify = VerifyAvailability.NoLocalCalls,
        ).toRunHeaderUi()
        assertEquals(RunLineUi.CloudOnly, cloudOnly.line)
        assertEquals(RunPromiseUi.CLOUD_ONLY, (cloudOnly.detail as RunDetailUi.Recorded).promise)
        assertEquals(RunVerifyActionUi.NoLocalCalls, cloudOnly.actions.verify)

        val preVersion = description(
            run = run(header = null),
            reproducibility = Reproducibility.NotPromised(NotPromisedReason.PRE_VERSION),
            verify = VerifyAvailability.PreVersion,
            runAgain = RunAgainAvailability.NotOffered(RunAgainNotOfferedReason.PRE_VERSION),
        ).toRunHeaderUi()
        assertEquals(RunLineUi.PreVersion, preVersion.line)
        assertEquals(RunDetailUi.PreVersion, preVersion.detail)
        assertEquals(RunExportActionUi.RECORDS_ONLY, preVersion.actions.export)
        assertEquals(RunAgainActionUi.NOT_OFFERED_PRE_VERSION, preVersion.actions.runAgain)
    }

    @Test
    fun `given a run whose on-device calls all ran on the NPU when projected then the check opens to its result`() {
        val ui = description(
            models = listOf(cpu.copy(backend = LocalBackend.NPU)),
            localCalls = 6,
            reproducibility = Reproducibility.NotPromised(NotPromisedReason.NPU),
            verify = VerifyAvailability.Available(plan(calls = 0, ms = null)),
        ).toRunHeaderUi()

        assertEquals(RunVerifyActionUi.NpuOnly(calls = 6), ui.actions.verify)
        assertEquals(RunPromiseUi.NPU, (ui.detail as RunDetailUi.Recorded).promise)
    }

    @Test
    fun `given a mismatch when projected then the reason names both values and the model row is tagged`() {
        val ui = description(
            models = listOf(cpu.copy(backend = LocalBackend.GPU)),
            verify = VerifyAvailability.Mismatch(
                VerifyMismatch.BackendAndWindow(LocalBackend.GPU, 4096, LocalBackend.CPU),
            ),
        ).toRunHeaderUi()

        assertEquals(
            RunVerifyActionUi.Mismatch(RunMismatchUi.BackendAndWindow("GPU", "4 096", "CPU")),
            ui.actions.verify,
        )
        assertEquals("CPU", (ui.detail as RunDetailUi.Recorded).models.single().nowTag)
        assertEquals(RunMismatchUi.Window("4 096", "2 048"), VerifyMismatch.Window(4096, 2048).toUi())
        assertEquals(RunMismatchUi.HashPending, VerifyMismatch.HashPending("Gemma").toUi())
    }

    @Test
    fun `given a run still going when projected then the actions are busy`() {
        val ui = description(run = run(status = PipelineRunStatus.RUNNING), verify = VerifyAvailability.Busy)
            .toRunHeaderUi()

        assertTrue(ui.actions.busy)
    }

    @Test
    fun `given a check when asked whether to confirm then it asks above 3 calls or from 30 s`() {
        assertFalse(asksBeforeVerifying(plan(calls = 3, ms = 29_999)))
        assertTrue(asksBeforeVerifying(plan(calls = 4, ms = 1_000)))
        assertTrue(asksBeforeVerifying(plan(calls = 1, ms = 30_000)))
        assertFalse(asksBeforeVerifying(plan(calls = 2, ms = null)))
    }
}
