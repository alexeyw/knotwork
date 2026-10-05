package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.LocalSampling
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunHeader
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunSampler
import app.knotwork.android.domain.models.RunTraceExportDocument
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.repositories.GenerationSettings
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.verification.DescribeRunUseCase
import app.knotwork.android.domain.verification.ExportRunTraceUseCase
import app.knotwork.android.domain.verification.NodeVerdict
import app.knotwork.android.domain.verification.PlannedVisit
import app.knotwork.android.domain.verification.Reproducibility
import app.knotwork.android.domain.verification.RunAgainAvailability
import app.knotwork.android.domain.verification.RunAgainRequest
import app.knotwork.android.domain.verification.RunDescription
import app.knotwork.android.domain.verification.RunModelUse
import app.knotwork.android.domain.verification.VerificationEvent
import app.knotwork.android.domain.verification.VerificationPlan
import app.knotwork.android.domain.verification.VerificationSummary
import app.knotwork.android.domain.verification.VerifyAvailability
import app.knotwork.android.domain.verification.VerifyRunUseCase
import app.knotwork.android.domain.verification.VisitKind
import app.knotwork.design.components.console.ConsoleHashCopy
import app.knotwork.design.components.console.HashKind
import app.knotwork.design.components.console.RunSettingsTarget
import app.knotwork.design.components.console.VerificationStageUi
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ChatHomeRunDelegate]: the strip follows the console's run, a long check asks
 * first and a short one starts, the check's events fill the sheet, a start-again
 * asks then hands the recorded seed to the send path, and the export renders
 * before its sheet opens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatHomeRunDelegateTest {

    private val header = RunHeader(1_482_913, RunSampler(0.7, 40, 0.95), "app", "runtime", "device")
    private val request = RunAgainRequest("s", "p", "Daily brief", "hi", LocalSampling(header.sampler, header.seed))

    private fun run(id: String, status: PipelineRunStatus, parent: String? = null) = PipelineRun(
        id = id,
        sessionId = "s",
        pipelineId = "p",
        origin = RunOrigin.CHAT,
        status = status,
        currentNodeId = null,
        startedAt = 0,
        finishedAt = null,
        errorMessage = null,
        graphContentHash = null,
        parentRunId = parent,
        header = header,
    )

    private fun plan(calls: Int) = VerificationPlan(
        rootRunId = "done",
        header = header,
        visits = listOf(
            PlannedVisit(
                runId = "done",
                depth = 0,
                nodeId = "llm",
                nodeType = "LITE_RT",
                label = "answer",
                visit = 0,
                kind = VisitKind.Repeat,
                calls = List(calls) { mockk<RunTraceRecord.LocalModelCall> { every { durationMs } returns 1_000 } },
            ),
        ),
        cloudCalls = 0,
        recordedModelMs = calls * 1_000L,
    )

    private var checkCalls = 2
    private fun description(runId: String) = RunDescription(
        run = run(runId, PipelineRunStatus.COMPLETED),
        models = listOf(RunModelUse("Gemma", "sha", LocalBackend.CPU, 4096)),
        localCalls = checkCalls,
        cloudCalls = 0,
        digest = "f".repeat(64),
        reproducibility = Reproducibility.Promised(setOf(LocalBackend.CPU), usedCloud = false),
        verify = VerifyAvailability.Available(plan(checkCalls)),
        runAgain = RunAgainAvailability.Available(request),
    )

    private val described = mutableListOf<String>()

    // Most recent first: a finished run started after the one still waiting, and a
    // sub-pipeline run of the waiting one — the console shows the waiting root.
    private val runs = MutableStateFlow(
        listOf(
            run("done", PipelineRunStatus.COMPLETED),
            run("child", PipelineRunStatus.RUNNING, parent = "active"),
            run("active", PipelineRunStatus.WAITING_APPROVAL),
        ),
    )
    private val runRepository: PipelineRunRepository = mockk {
        every { observeRunsForSession("s") } returns runs
    }
    private val describeRun: DescribeRunUseCase = mockk {
        coEvery { this@mockk.invoke(any()) } answers {
            val id = firstArg<String>()
            described += id
            description(id)
        }
    }
    private var verificationEvents = flowOf<VerificationEvent>(
        VerificationEvent.CallChecked(0, 1, 2, true, "a", "a", 1, 2),
        VerificationEvent.CallChecked(0, 2, 2, true, "b", "b", 2, 2),
        VerificationEvent.VisitSettled(0, NodeVerdict.Matched(2)),
        VerificationEvent.Finished(VerificationSummary.AllMatched(2, 0)),
    )
    private val verifyRun: VerifyRunUseCase =
        mockk { every { this@mockk.invoke(any()) } answers { verificationEvents } }
    private val exportRunTrace: ExportRunTraceUseCase = mockk {
        coEvery { this@mockk.invoke(any(), any()) } returns RunTraceExportDocument("{\"a\":1}", 3, "f".repeat(64))
    }
    private val settings: GenerationSettings = mockk {
        every { localModelBackend } returns flowOf("CPU")
        every { maxContextLength } returns flowOf(4096)
    }
    private val state = MutableStateFlow(ChatHomeScreenState())
    private val startedAgain = mutableListOf<RunAgainRequest>()

    private fun TestScope.delegate() = ChatHomeRunDelegate(
        scope = backgroundScope,
        state = state,
        pipelineRunRepository = runRepository,
        useCases = ChatHomeRunUseCases(describeRun, verifyRun, exportRunTrace),
        generationSettings = settings,
        startRunAgain = { startedAgain += it },
    )

    @Test
    fun `given a session when observed then the strip follows its active root run, then the latest`() =
        runTest(UnconfinedTestDispatcher()) {
            delegate().observe("s")
            assertEquals(listOf("active"), described)
            assertNotNull(state.value.console.runHeader)

            runs.value = listOf(run("done", PipelineRunStatus.COMPLETED), run("active", PipelineRunStatus.COMPLETED))
            assertEquals(listOf("active", "done"), described)
        }

    @Test
    fun `given a short check when verified then it starts at once and the sheet ends with the result`() =
        runTest(UnconfinedTestDispatcher()) {
            val delegate = delegate()
            delegate.observe("s")

            delegate.verification.verify()

            assertNull(state.value.run.verifyConfirm)
            assertEquals(
                VerificationStageUi.AllMatched(calls = 2, notVerifiable = 0),
                state.value.run.verification?.stage,
            )
        }

    @Test
    fun `given a check of more than three calls when verified then it asks first`() =
        runTest(UnconfinedTestDispatcher()) {
            checkCalls = 4
            val delegate = delegate()
            delegate.observe("s")

            delegate.verification.verify()
            assertEquals(4, state.value.run.verifyConfirm?.calls)
            assertNull(state.value.run.verification)

            delegate.verification.confirm()
            assertNull(state.value.run.verifyConfirm)
            assertNotNull(state.value.run.verification)
        }

    @Test
    fun `given a running check when cancelled then the sheet says how far it got`() =
        runTest(UnconfinedTestDispatcher()) {
            verificationEvents = flow {
                emit(VerificationEvent.CallChecked(0, 1, 2, true, "a", "a", 1, 2))
                awaitCancellation()
            }
            val delegate = delegate()
            delegate.observe("s")
            delegate.verification.verify()

            delegate.verification.cancel()

            assertEquals(
                VerificationStageUi.Cancelled(done = 1, total = 2, allMatched = true),
                state.value.run.verification?.stage,
            )
            delegate.verification.close()
            assertNull(state.value.run.verification)
        }

    @Test
    fun `given a start-again when confirmed then the recorded seed goes to the send path and is announced`() =
        runTest(UnconfinedTestDispatcher()) {
            val delegate = delegate()
            val events = mutableListOf<RunHeaderEvent>()
            backgroundScope.launch { delegate.events.collect { events += it } }
            delegate.observe("s")

            delegate.runAgain()
            assertEquals("Daily brief", state.value.run.runAgainConfirm?.pipeline)
            assertEquals("1 482 913", state.value.run.runAgainConfirm?.seed)

            delegate.confirmRunAgain()
            assertNull(state.value.run.runAgainConfirm)
            assertEquals(listOf(request), startedAgain)
            assertEquals(listOf<RunHeaderEvent>(RunHeaderEvent.RunningAgain("1 482 913")), events)
        }

    @Test
    fun `given an export when opened then the document is rendered first and its size shown`() =
        runTest(UnconfinedTestDispatcher()) {
            val delegate = delegate()
            delegate.observe("s")

            delegate.openExport()
            val sheet = state.value.run.export
            assertEquals("7 B", sheet?.size)
            assertEquals(sheet?.fileName, delegate.export.newFileName())

            delegate.dismissExport()
            assertNull(state.value.run.export)
        }

    @Test
    fun `given the copy and settings actions when used then the screen is asked to copy or open`() =
        runTest(UnconfinedTestDispatcher()) {
            val delegate = delegate()
            val events = mutableListOf<RunHeaderEvent>()
            backgroundScope.launch { delegate.events.collect { events += it } }
            delegate.observe("s")

            delegate.copySeed()
            delegate.copyDigest()
            delegate.copyNodeHash(ConsoleHashCopy("LITE_RT", HashKind.OUTPUT, "a".repeat(64)))
            delegate.openSettings(RunSettingsTarget.GENERATION)

            assertEquals(
                listOf(
                    RunHeaderEvent.Copy("1482913", Copied.Seed),
                    RunHeaderEvent.Copy("f".repeat(64), Copied.Digest),
                    RunHeaderEvent.Copy("a".repeat(64), Copied.NodeHash("LITE_RT", HashKind.OUTPUT)),
                    RunHeaderEvent.OpenSettings(RunSettingsTarget.GENERATION),
                ),
                events,
            )
        }

    @Test
    fun `given the strip when toggled then it opens and closes, and a new session closes it`() =
        runTest(UnconfinedTestDispatcher()) {
            val delegate = delegate()
            delegate.observe("s")

            delegate.toggleHeader()
            assertEquals(true, state.value.console.runHeaderExpanded)
            delegate.observe("s")
            assertEquals(false, state.value.console.runHeaderExpanded)
        }
}
