package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.PendingInteraction
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunCeilingAxis
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunTerminationReason
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.models.TriggerHitlEvent
import app.knotwork.android.domain.models.TriggerHitlResolution
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.ApprovalNotifier
import app.knotwork.android.domain.services.CeilingNotifier
import app.knotwork.android.domain.services.ClarificationNotifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ParkedRunResumer] — the shared submission tail of the
 * background-HITL decision use cases.
 *
 * Cover: notification teardown by kind, the lazy approval-window expiry, the
 * first-writer-wins response gate, the resume-outcome mapping (including the
 * failure settlement of GraphChanged / Expired / NotResumable parks), and the
 * [ParkedRunResumer.failPark] settlement shared with the maintenance worker.
 */
class ParkedRunResumerTest {

    private lateinit var pendingInteractionRepository: PendingInteractionRepository
    private lateinit var pipelineRunRepository: PipelineRunRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var approvalNotifier: ApprovalNotifier
    private lateinit var clarificationNotifier: ClarificationNotifier
    private lateinit var ceilingNotifier: CeilingNotifier
    private lateinit var resumePipelineRunUseCase: ResumePipelineRunUseCase
    private lateinit var recordTriggerHitlEvent: RecordTriggerHitlEventUseCase
    private lateinit var resumer: ParkedRunResumer

    @Before
    fun setup() {
        pendingInteractionRepository = mockk(relaxed = true)
        pipelineRunRepository = mockk(relaxed = true)
        settingsRepository = mockk()
        approvalNotifier = mockk(relaxed = true)
        clarificationNotifier = mockk(relaxed = true)
        ceilingNotifier = mockk(relaxed = true)
        resumePipelineRunUseCase = mockk()
        recordTriggerHitlEvent = mockk(relaxed = true)
        resumer = ParkedRunResumer(
            pendingInteractionRepository = pendingInteractionRepository,
            pipelineRunRepository = pipelineRunRepository,
            settingsRepository = settingsRepository,
            approvalNotifier = approvalNotifier,
            clarificationNotifier = clarificationNotifier,
            ceilingNotifier = ceilingNotifier,
            resumePipelineRunUseCase = resumePipelineRunUseCase,
            recordTriggerHitlEvent = recordTriggerHitlEvent,
        )
        coEvery { settingsRepository.backgroundApprovalWindowHours } returns flowOf(24)
        coEvery { resumePipelineRunUseCase("run-1") } returns ResumeOutcome.Resumed
        // The parked run is top-level, so its root is itself; failPark fails the root.
        coEvery { pipelineRunRepository.getRootRunId("run-1") } returns "run-1"
    }

    /** A parked approval still inside its window unless [requestedAt] says otherwise. */
    private fun parkedApproval(requestedAt: Long = System.currentTimeMillis()): PendingInteraction = PendingInteraction(
        runId = "run-1",
        sessionId = "session-1",
        kind = PendingInteractionKind.APPROVAL,
        toolName = "tool",
        toolArgs = "{}",
        risk = ToolRisk.SENSITIVE,
        requestedAt = requestedAt,
        requestId = "request-1",
    )

    /** A run record that is still open, so the not-resumable branch has to settle it. */
    private fun openRun(): PipelineRun = PipelineRun(
        id = "run-1",
        sessionId = "session-1",
        pipelineId = "pipe-1",
        origin = RunOrigin.TRIGGER,
        status = PipelineRunStatus.WAITING_APPROVAL,
        currentNodeId = "node-1",
        startedAt = 0L,
        finishedAt = null,
        errorMessage = null,
        graphContentHash = "hash",
        userPrompt = "prompt",
    )

    @Test
    fun `given fresh park and recorded response when submit then run resumes`() = runTest {
        val outcome = resumer.submit(parkedApproval()) { true }

        assertEquals(PendingSubmissionOutcome.Resumed, outcome)
        coVerify { resumePipelineRunUseCase("run-1") }
        verify { approvalNotifier.cancelApprovalNotification("request-1") }
        // A request with its own identity was posted by this release: no old slot to clear.
        verify(exactly = 0) { approvalNotifier.cancelPreUpdateNotification(any()) }
    }

    @Test
    fun `given a park from before request ids when submitted then the notification of its run is removed`() = runTest {
        // Such a record's notification was posted naming only its run, and the
        // store back-filled that run id as its request id.
        resumer.submit(parkedApproval().copy(requestId = null)) { true }

        verify { approvalNotifier.cancelApprovalNotification("run-1") }
    }

    @Test
    fun `given a park back-filled with its run id when submitted then the old session slot is cleared`() = runTest {
        // The migration gave such a record requestId = runId; its notification is
        // still where the release that posted it put it — the session's slot.
        resumer.submit(parkedApproval().copy(requestId = "run-1")) { true }

        verify { approvalNotifier.cancelApprovalNotification("run-1") }
        verify { approvalNotifier.cancelPreUpdateNotification("session-1") }
    }

    @Test
    fun `given clarification park when submit then clarification notification is cancelled`() = runTest {
        val pending = parkedApproval().copy(
            kind = PendingInteractionKind.CLARIFICATION,
            toolName = null,
            toolArgs = null,
            risk = null,
            question = "Q?",
        )

        resumer.submit(pending) { true }

        verify { clarificationNotifier.cancelClarificationNotification("session-1") }
        verify(exactly = 0) { approvalNotifier.cancelApprovalNotification(any()) }
    }

    @Test
    fun `given park older than the window when submit then run fails as expired without resuming`() = runTest {
        val expiredAt = System.currentTimeMillis() - 25 * 3_600_000L

        val outcome = resumer.submit(parkedApproval(requestedAt = expiredAt)) { true }

        assertEquals(PendingSubmissionOutcome.Expired, outcome)
        coVerify {
            pipelineRunRepository.finishRun(
                "run-1",
                PipelineRunStatus.FAILED,
                ParkedRunResumer.APPROVAL_WINDOW_EXPIRED_MESSAGE,
                RunTerminationReason.HitlWindowExpired,
            )
        }
        coVerify { pendingInteractionRepository.delete("run-1") }
        coVerify(exactly = 0) { resumePipelineRunUseCase(any()) }
    }

    @Test
    fun `given response already recorded by a racing writer when submit then NothingPending`() = runTest {
        val outcome = resumer.submit(parkedApproval()) { false }

        assertEquals(PendingSubmissionOutcome.NothingPending, outcome)
        coVerify(exactly = 0) { resumePipelineRunUseCase(any()) }
    }

    @Test
    fun `given resume reports GraphChanged when submit then park is failed and cleaned`() = runTest {
        coEvery { resumePipelineRunUseCase("run-1") } returns ResumeOutcome.GraphChanged

        val outcome = resumer.submit(parkedApproval()) { true }

        assertEquals(PendingSubmissionOutcome.GraphChanged, outcome)
        coVerify {
            pipelineRunRepository.finishRun(
                "run-1",
                PipelineRunStatus.FAILED,
                ParkedRunResumer.GRAPH_CHANGED_MESSAGE,
                RunTerminationReason.GraphChanged,
            )
        }
        coVerify { pendingInteractionRepository.delete("run-1") }
    }

    @Test
    fun `given resume reports Expired when submit then park is failed as expired`() = runTest {
        coEvery { resumePipelineRunUseCase("run-1") } returns ResumeOutcome.Expired

        val outcome = resumer.submit(parkedApproval()) { true }

        assertEquals(PendingSubmissionOutcome.Expired, outcome)
        coVerify {
            pipelineRunRepository.finishRun(
                "run-1",
                PipelineRunStatus.FAILED,
                ParkedRunResumer.APPROVAL_WINDOW_EXPIRED_MESSAGE,
                RunTerminationReason.HitlWindowExpired,
            )
        }
    }

    @Test
    fun `given resume reports NotResumable when submit then stale record is dropped`() = runTest {
        coEvery { resumePipelineRunUseCase("run-1") } returns ResumeOutcome.NotResumable

        val outcome = resumer.submit(parkedApproval()) { true }

        assertEquals(PendingSubmissionOutcome.NothingPending, outcome)
        coVerify { pendingInteractionRepository.delete("run-1") }
        // The run record was settled elsewhere — no second terminal write.
        coVerify(exactly = 0) { pipelineRunRepository.finishRun(any(), any(), any(), any()) }
        // …but the gate must not be left journalled as still waiting on a run
        // that is already over: the response arrived and could not be applied.
        coVerify(exactly = 1) {
            recordTriggerHitlEvent("run-1", TriggerHitlEvent.Resolved(TriggerHitlResolution.ABANDONED))
        }
    }

    @Test
    fun `given NotResumable on a still-open run then it settles as not-resumable, not as a timeout`() = runTest {
        // The branch every other NotResumable test skips, because it needs the
        // run to still be open. The user *did* answer here — the run behind the
        // gate simply could not be continued — so the gate must be journalled as
        // abandoned, not as "no response before the window closed". Typing it as
        // an expired window would rewrite the record of who failed to respond.
        coEvery { resumePipelineRunUseCase("run-1") } returns ResumeOutcome.NotResumable
        coEvery { pipelineRunRepository.getRun("run-1") } returns openRun()

        resumer.submit(parkedApproval()) { true }

        coVerify(exactly = 1) {
            pipelineRunRepository.finishRun(
                "run-1",
                PipelineRunStatus.FAILED,
                ParkedRunResumer.NOT_RESUMABLE_MESSAGE,
                RunTerminationReason.NotResumable,
            )
        }
        coVerify(exactly = 1) {
            recordTriggerHitlEvent("run-1", TriggerHitlEvent.Resolved(TriggerHitlResolution.ABANDONED))
        }
    }

    @Test
    fun `failPark fails the run deletes the record and removes the notification`() = runTest {
        resumer.failPark(parkedApproval(), "reason", RunTerminationReason.HitlWindowExpired)

        coVerify {
            pipelineRunRepository.finishRun(
                "run-1",
                PipelineRunStatus.FAILED,
                "reason",
                RunTerminationReason.HitlWindowExpired,
            )
        }
        coVerify { pendingInteractionRepository.delete("run-1") }
        verify { approvalNotifier.cancelApprovalNotification("request-1") }
    }

    @Test
    fun `failPark on an elapsed window journals the gate as timed out`() = runTest {
        resumer.failPark(
            parkedApproval(),
            ParkedRunResumer.APPROVAL_WINDOW_EXPIRED_MESSAGE,
            RunTerminationReason.HitlWindowExpired,
        )

        // The run's own outcome (FAILED) cannot say whether the user was asked
        // and never answered, or whether the park was discarded for another
        // reason — the journal has to carry that.
        coVerify(exactly = 1) {
            recordTriggerHitlEvent("run-1", TriggerHitlEvent.Resolved(TriggerHitlResolution.TIMED_OUT))
        }
    }

    @Test
    fun `failPark on a changed graph journals the gate as abandoned`() = runTest {
        resumer.failPark(parkedApproval(), ParkedRunResumer.GRAPH_CHANGED_MESSAGE, RunTerminationReason.GraphChanged)

        coVerify(exactly = 1) {
            recordTriggerHitlEvent("run-1", TriggerHitlEvent.Resolved(TriggerHitlResolution.ABANDONED))
        }
    }

    @Test
    fun `failPark on a nested park journals against the root run`() = runTest {
        // The park sits on a sub-pipeline run, but the journal row belongs to
        // the root — the run the trigger actually enqueued.
        coEvery { pipelineRunRepository.getRootRunId("run-1") } returns "root-run"

        resumer.failPark(
            parkedApproval(),
            ParkedRunResumer.APPROVAL_WINDOW_EXPIRED_MESSAGE,
            RunTerminationReason.HitlWindowExpired,
        )

        coVerify(exactly = 1) {
            recordTriggerHitlEvent("root-run", TriggerHitlEvent.Resolved(TriggerHitlResolution.TIMED_OUT))
        }
    }

    @Test
    fun `given a parked ceiling pause then its own notification is the one torn down`() = runTest {
        coEvery { resumePipelineRunUseCase("run-1") } returns ResumeOutcome.Resumed
        val pending = PendingInteraction(
            runId = "run-1",
            sessionId = "session-1",
            kind = PendingInteractionKind.CEILING,
            ceilingAxis = RunCeilingAxis.STEPS,
            ceilingLimit = 15,
            ceilingSpent = 15,
            requestedAt = System.currentTimeMillis(),
        )

        resumer.submit(pending) { true }

        verify { ceilingNotifier.cancelCeilingNotification("session-1") }
        // Cancelling by kind, not by shotgun: an approval parked on another
        // session must not lose its notification because a ceiling was answered.
        verify(exactly = 0) { approvalNotifier.cancelApprovalNotification(any()) }
        verify(exactly = 0) { clarificationNotifier.cancelClarificationNotification(any()) }
    }

    @Test
    fun `given an explicit resolution then it overrides the one derived from the cause`() = runTest {
        // Every settlement the two HITL gates can reach is one the user never
        // got to make, so the default reads "timed out" or "abandoned" off the
        // cause. Stopping a run at its ceiling is a decision, deliberately
        // given, and journalling it as abandonment would record the user as
        // absent at the moment they were most present.
        coEvery { pipelineRunRepository.getRootRunId("run-1") } returns "run-1"
        val pending = PendingInteraction(
            runId = "run-1",
            sessionId = "session-1",
            kind = PendingInteractionKind.CEILING,
            ceilingAxis = RunCeilingAxis.STEPS,
            ceilingLimit = 15,
            ceilingSpent = 15,
            requestedAt = System.currentTimeMillis(),
        )

        resumer.failPark(
            pending = pending,
            reason = "step-ceiling: 15/15 steps",
            terminationReason = RunTerminationReason.StepCeiling(limit = 15, spent = 15),
            resolution = TriggerHitlResolution.DENIED,
        )

        coVerify {
            recordTriggerHitlEvent("run-1", TriggerHitlEvent.Resolved(TriggerHitlResolution.DENIED))
        }
        coVerify {
            pipelineRunRepository.finishRun(
                "run-1",
                PipelineRunStatus.FAILED,
                "step-ceiling: 15/15 steps",
                RunTerminationReason.StepCeiling(limit = 15, spent = 15),
            )
        }
    }

    @Test
    fun `an unanswered ceiling pause expires at its limit, not as an unanswered approval`() = runTest {
        // The run never asked for an approval — it reached a number the user
        // set. Settling it as HitlWindowExpired would tell the owner of an
        // overnight run that the app had been waiting for their *approval*, and
        // send them looking for a tool call there was none of.
        coEvery { pipelineRunRepository.getRootRunId("run-1") } returns "run-1"
        val reason = slot<RunTerminationReason>()

        resumer.failExpiredPark(
            PendingInteraction(
                runId = "run-1",
                sessionId = "session-1",
                kind = PendingInteractionKind.CEILING,
                ceilingAxis = RunCeilingAxis.TOKENS,
                ceilingLimit = 100_000,
                ceilingSpent = 100_400,
                requestedAt = 0L,
            ),
        )

        coVerify {
            pipelineRunRepository.finishRun("run-1", PipelineRunStatus.FAILED, any(), capture(reason))
        }
        assertEquals(RunTerminationReason.TokenCeiling(limit = 100_000, spent = 100_400), reason.captured)
        // TIMED_OUT, not DENIED: the user was asked and the window closed, which
        // is exactly what separates this from a deliberate "stop the run".
        coVerify {
            recordTriggerHitlEvent("run-1", TriggerHitlEvent.Resolved(TriggerHitlResolution.TIMED_OUT))
        }
    }

    @Test
    fun `an unanswered approval still expires as an unanswered approval`() = runTest {
        coEvery { pipelineRunRepository.getRootRunId("run-1") } returns "run-1"
        val reason = slot<RunTerminationReason>()

        resumer.failExpiredPark(parkedApproval(requestedAt = 0L))

        coVerify {
            pipelineRunRepository.finishRun(
                "run-1",
                PipelineRunStatus.FAILED,
                ParkedRunResumer.APPROVAL_WINDOW_EXPIRED_MESSAGE,
                capture(reason),
            )
        }
        assertEquals(RunTerminationReason.HitlWindowExpired, reason.captured)
    }
}
