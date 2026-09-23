package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.engine.TaskQueueManager
import app.knotwork.android.domain.models.PendingDecision
import app.knotwork.android.domain.models.PendingInteraction
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SubmitApprovalDecisionUseCase] — the single entry point of
 * the user's approve / deny decision across both waiting phases.
 *
 * Cover: live-gate routing by request, the parked-record fallthrough by
 * request, the decision recording handed to [ParkedRunResumer], kind
 * filtering, the no-pending case — and that the session is never used as an
 * address: a decision for one request cannot reach another request of the
 * same session, live or parked.
 */
class SubmitApprovalDecisionUseCaseTest {

    private lateinit var taskQueueManager: TaskQueueManager
    private lateinit var pendingInteractionRepository: PendingInteractionRepository
    private lateinit var parkedRunResumer: ParkedRunResumer
    private lateinit var useCase: SubmitApprovalDecisionUseCase

    @Before
    fun setup() {
        taskQueueManager = mockk(relaxed = true)
        pendingInteractionRepository = mockk(relaxed = true)
        parkedRunResumer = mockk()
        useCase = SubmitApprovalDecisionUseCase(
            taskQueueManager = taskQueueManager,
            pendingInteractionRepository = pendingInteractionRepository,
            parkedRunResumer = parkedRunResumer,
        )
        every { taskQueueManager.resumeWithApproval(any(), any(), any()) } returns false
        coEvery { pendingInteractionRepository.getForRequest(any()) } returns null
        coEvery { parkedRunResumer.submit(any(), any()) } returns PendingSubmissionOutcome.Resumed
    }

    /** A parked approval record for the fallthrough tests. */
    private fun parkedApproval(): PendingInteraction = PendingInteraction(
        runId = "run-1",
        sessionId = "session-1",
        kind = PendingInteractionKind.APPROVAL,
        toolName = "tool",
        toolArgs = "{}",
        risk = ToolRisk.SENSITIVE,
        requestedAt = 0L,
        requestId = "request-1",
    )

    @Test
    fun `given the live gate waits on the named request when invoked then resumes it and never touches the store`() =
        runTest {
            every { taskQueueManager.resumeWithApproval("session-1", "request-live", true) } returns true

            val outcome = useCase("session-1", "request-live", isApproved = true)

            assertEquals(PendingSubmissionOutcome.LiveResumed, outcome)
            coVerify(exactly = 0) { pendingInteractionRepository.getForRequest(any()) }
            coVerify(exactly = 0) { parkedRunResumer.submit(any(), any()) }
        }

    @Test
    fun `given a live gate for another request when invoked with a parked request then the live gate is not resumed`() =
        runTest {
            // The live gate belongs to request Z; the notification answered
            // parked request X. Only X may be settled.
            every { taskQueueManager.resumeWithApproval("session-1", "request-z", any()) } returns true
            coEvery { pendingInteractionRepository.getForRequest("request-1") } returns parkedApproval()

            val outcome = useCase("session-1", "request-1", isApproved = true)

            assertEquals(PendingSubmissionOutcome.Resumed, outcome)
            verify(exactly = 0) { taskQueueManager.resumeWithApproval(any(), "request-z", any()) }
            coVerify { parkedRunResumer.submit(match { it.runId == "run-1" }, any()) }
        }

    @Test
    fun `given a parked record for the named request when invoked then submits through the resumer`() = runTest {
        coEvery { pendingInteractionRepository.getForRequest("request-1") } returns parkedApproval()

        val outcome = useCase("session-1", "request-1", isApproved = false)

        assertEquals(PendingSubmissionOutcome.Resumed, outcome)
        coVerify { parkedRunResumer.submit(match { it.runId == "run-1" }, any()) }
    }

    @Test
    fun `given any submission when routed then the session is never used to find what to settle`() = runTest {
        // "Whatever the session is waiting on" is the address this use case
        // must not have: the session's newest parked record may be another request.
        coEvery { pendingInteractionRepository.getForSession(any()) } returns parkedApproval()

        val outcome = useCase("session-1", "request-other", isApproved = true)

        assertEquals(PendingSubmissionOutcome.NothingPending, outcome)
        coVerify(exactly = 0) { pendingInteractionRepository.getForSession(any()) }
        coVerify(exactly = 0) { parkedRunResumer.submit(any(), any()) }
    }

    @Test
    fun `given approval decision when submitted then APPROVED is recorded onto the record of that request`() = runTest {
        coEvery { pendingInteractionRepository.getForRequest("request-1") } returns parkedApproval()
        val recorder = slot<suspend (String) -> Boolean>()
        coEvery { parkedRunResumer.submit(any(), capture(recorder)) } returns PendingSubmissionOutcome.Resumed
        coEvery {
            pendingInteractionRepository.recordApprovalDecision("run-1", "request-1", PendingDecision.APPROVED)
        } returns true

        useCase("session-1", "request-1", isApproved = true)

        assertTrue(recorder.captured("run-1"))
        coVerify { pendingInteractionRepository.recordApprovalDecision("run-1", "request-1", PendingDecision.APPROVED) }
    }

    @Test
    fun `given denial when submitted then DENIED is recorded onto the record of that request`() = runTest {
        coEvery { pendingInteractionRepository.getForRequest("request-1") } returns parkedApproval()
        val recorder = slot<suspend (String) -> Boolean>()
        coEvery { parkedRunResumer.submit(any(), capture(recorder)) } returns PendingSubmissionOutcome.Resumed
        coEvery {
            pendingInteractionRepository.recordApprovalDecision("run-1", "request-1", PendingDecision.DENIED)
        } returns true

        useCase("session-1", "request-1", isApproved = false)

        assertTrue(recorder.captured("run-1"))
        coVerify { pendingInteractionRepository.recordApprovalDecision("run-1", "request-1", PendingDecision.DENIED) }
    }

    @Test
    fun `given the named record is not an approval when invoked then NothingPending`() = runTest {
        coEvery { pendingInteractionRepository.getForRequest("request-1") } returns parkedApproval().copy(
            kind = PendingInteractionKind.CLARIFICATION,
        )

        val outcome = useCase("session-1", "request-1", isApproved = true)

        assertEquals(PendingSubmissionOutcome.NothingPending, outcome)
        coVerify(exactly = 0) { parkedRunResumer.submit(any(), any()) }
    }

    @Test
    fun `given nothing waits on the named request when invoked then NothingPending`() = runTest {
        val outcome = useCase("session-1", "request-1", isApproved = true)

        assertEquals(PendingSubmissionOutcome.NothingPending, outcome)
        coVerify(exactly = 0) { parkedRunResumer.submit(any(), any()) }
    }
}
