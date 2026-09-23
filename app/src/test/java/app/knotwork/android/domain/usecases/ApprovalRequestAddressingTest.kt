package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.engine.TaskQueueManager
import app.knotwork.android.domain.engine.executors.ToolInvocationGate
import app.knotwork.android.domain.models.NodeOutput
import app.knotwork.android.domain.models.PendingInteraction
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.ToolApprovalPolicy
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.ToolRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The decision channel end to end, from the surface that shows a request to
 * the gate that waits on one: a real [SubmitApprovalDecisionUseCase] in front
 * of a real [ToolInvocationGate].
 *
 * The property under test is that **an answer settles only the request it was
 * shown for**. A session is not an address: a parked request and a live one
 * coexist in the same session whenever a second run starts there. The shade can
 * then hold the parked request's notification while the chat card, or the
 * gate, belongs to the live one.
 */
class ApprovalRequestAddressingTest {

    @Test
    fun `given parked SENSITIVE A and live DESTRUCTIVE B in one session when A's notification approves then B waits`() =
        runTest {
            // The required case: A's notification offers one-tap Approve (it is
            // SENSITIVE); B would need the typed confirmation of the chat card.
            val fixture = Fixture()
            fixture.raiseLiveGate(this)

            val outcome = fixture.useCase(SESSION_ID, PARKED_REQUEST_ID, isApproved = true)
            runCurrent()

            fixture.assertLiveGateUntouched()
            assertEquals(PendingSubmissionOutcome.Resumed, outcome)
            coVerify { fixture.parkedRunResumer.submit(match { it.runId == PARKED_RUN_ID }, any()) }
            fixture.stop()
        }

    @Test
    fun `given a card restored from parked SENSITIVE A when approved with one tap then live DESTRUCTIVE B waits`() =
        runTest {
            // The same crossing from the chat: a card restored from A's record
            // passes the one-tap check by A's risk, and answers with A's identity.
            val fixture = Fixture()
            fixture.raiseLiveGate(this)
            val card = fixture.restoredCardRequestId()

            fixture.useCase(SESSION_ID, card, isApproved = true)
            runCurrent()

            fixture.assertLiveGateUntouched()
            fixture.stop()
        }

    @Test
    fun `given an answer naming no waiting request when submitted then nothing is settled`() = runTest {
        // A notification that outlived its request (the run was stopped, the
        // process died) names a request nothing waits on any more.
        val fixture = Fixture()
        fixture.raiseLiveGate(this)

        val outcome = fixture.useCase(SESSION_ID, "request-long-gone", isApproved = true)
        runCurrent()

        assertEquals(PendingSubmissionOutcome.NothingPending, outcome)
        fixture.assertLiveGateUntouched()
        coVerify(exactly = 0) { fixture.parkedRunResumer.submit(any(), any()) }
        fixture.stop()
    }

    @Test
    fun `given live B when an answer names B then B is settled and nothing parked is touched`() = runTest {
        val fixture = Fixture()
        fixture.raiseLiveGate(this)

        val outcome = fixture.useCase(SESSION_ID, fixture.liveRequestId(), isApproved = true)
        runCurrent()

        assertEquals(PendingSubmissionOutcome.LiveResumed, outcome)
        assertEquals(1, fixture.liveExecutions)
        coVerify(exactly = 0) { fixture.parkedRunResumer.submit(any(), any()) }
        fixture.stop()
    }

    /**
     * A session holding parked request A (SENSITIVE, run [PARKED_RUN_ID]) and,
     * once [raiseLiveGate] runs, live request B (DESTRUCTIVE, run [LIVE_RUN_ID]).
     */
    private class Fixture {
        private val toolRepository: ToolRepository = mockk(relaxed = true)
        private val settingsRepository: SettingsRepository = mockk(relaxed = true)
        private val pendingInteractionRepository: PendingInteractionRepository = mockk(relaxed = true)
        val parkedRunResumer: ParkedRunResumer = mockk(relaxed = true)
        private val gate = ToolInvocationGate(
            toolRepository = toolRepository,
            settingsRepository = settingsRepository,
            approvalNotifier = mockk(relaxed = true),
            chatRepository = mockk(relaxed = true),
            pendingInteractionRepository = pendingInteractionRepository,
            recordTriggerHitlEvent = mockk(relaxed = true),
        )
        private val taskQueueManager: TaskQueueManager = mockk(relaxed = true)
        val useCase = SubmitApprovalDecisionUseCase(taskQueueManager, pendingInteractionRepository, parkedRunResumer)

        /** How many times the live request's destructive tool actually ran. */
        var liveExecutions = 0
            private set

        private val outputs = mutableListOf<NodeOutput>()
        private var liveJob: Job? = null

        init {
            coEvery { toolRepository.getRisk(LIVE_TOOL, LIVE_ARGS) } returns ToolRisk.DESTRUCTIVE
            coEvery { toolRepository.executeTool(LIVE_TOOL, LIVE_ARGS, any()) } answers {
                liveExecutions++
                "deleted"
            }
            every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
            every { settingsRepository.blockDestructiveTools } returns flowOf(false)
            every { settingsRepository.toolCallTimeoutMs } returns flowOf(LIVE_WINDOW_MS)
            coEvery { pendingInteractionRepository.getForRun(LIVE_RUN_ID) } returns null
            coEvery { pendingInteractionRepository.getForRun(PARKED_RUN_ID) } returns PARKED_REQUEST
            coEvery { pendingInteractionRepository.getForSession(SESSION_ID) } returns PARKED_REQUEST
            coEvery { pendingInteractionRepository.getForRequest(any()) } returns null
            coEvery { pendingInteractionRepository.getForRequest(PARKED_REQUEST_ID) } returns PARKED_REQUEST
            coEvery { parkedRunResumer.submit(any(), any()) } returns PendingSubmissionOutcome.Resumed
            // The queue is a pass-through to the gate, exactly like TaskQueueManagerImpl.
            every { taskQueueManager.pendingApproval(any()) } answers { gate.pendingApprovalFor(firstArg()) }
            every { taskQueueManager.resumeWithApproval(any(), any(), any()) } answers {
                gate.resumeWithApproval(firstArg(), secondArg(), thirdArg())
            }
        }

        /** Starts run B and lets it reach its live gate. */
        fun raiseLiveGate(scope: TestScope) {
            liveJob = scope.launch {
                flow {
                    gate.dispatch(
                        collector = this,
                        nodeType = "TOOL",
                        nodeId = "node-b",
                        sessionId = SESSION_ID,
                        runId = LIVE_RUN_ID,
                        resolvedToolName = LIVE_TOOL,
                        resolvedToolArgs = LIVE_ARGS,
                    )
                }.toList(outputs)
            }
            scope.runCurrent()
            assertNotNull("precondition: run B waits on its live gate", gate.pendingApprovalFor(SESSION_ID))
        }

        /** Identity of the request run B's live gate waits on. */
        fun liveRequestId(): String = requireNotNull(gate.pendingApprovalFor(SESSION_ID)).requestId

        /**
         * The identity a chat card restored from A's record answers with —
         * rebuilt the way `ChatHomeReattachDelegate` rebuilds it.
         */
        fun restoredCardRequestId(): String = PARKED_REQUEST.requestId ?: PARKED_REQUEST.runId

        /** Run B neither ran nor stopped waiting: the answer given for A did not reach it. */
        fun assertLiveGateUntouched() {
            assertEquals("the destructive call of request B ran on an answer given for A", 0, liveExecutions)
            assertNotNull("request B no longer waits for its own answer", gate.pendingApprovalFor(SESSION_ID))
        }

        fun stop() {
            liveJob?.cancel()
        }
    }

    private companion object {
        const val SESSION_ID = "session-1"
        const val PARKED_RUN_ID = "run-a"
        const val PARKED_REQUEST_ID = "request-a"
        const val LIVE_RUN_ID = "run-b"
        const val LIVE_TOOL = "delete_file"
        const val LIVE_ARGS = """{"path":"notes.md"}"""

        /** Long enough that no test ever reaches the park. */
        const val LIVE_WINDOW_MS = 60_000L

        val PARKED_REQUEST = PendingInteraction(
            runId = PARKED_RUN_ID,
            sessionId = SESSION_ID,
            kind = PendingInteractionKind.APPROVAL,
            toolName = "send_message",
            toolArgs = """{"to":"me"}""",
            risk = ToolRisk.SENSITIVE,
            requestedAt = 0L,
            requestId = PARKED_REQUEST_ID,
        )
    }
}
