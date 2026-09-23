package app.knotwork.android.domain.engine.executors

import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.NodeOutput
import app.knotwork.android.domain.models.PendingDecision
import app.knotwork.android.domain.models.PendingInteraction
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.ToolApprovalPolicy
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.models.TriggerHitlEvent
import app.knotwork.android.domain.models.TriggerHitlResolution
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.ToolRepository
import app.knotwork.android.domain.services.ApprovalNotifier
import app.knotwork.android.domain.usecases.RecordTriggerHitlEventUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ToolInvocationGate] driven through `dispatch` directly, for
 * the one property the executor-level suite (`ToolNodeExecutorTest`) crosses in
 * neither direction: **a decision the user recorded on a parked request is the
 * decision the resumed run applies**, whatever the approval policy, the tool's
 * risk or the node's `alwaysConfirm` switch say by the time it resumes.
 *
 * The policy and the risk are re-evaluated on resume on purpose — they are the
 * user's current settings — but they decide only whether a *new* question has
 * to be asked. A recorded answer is the answer to a question that *was* asked,
 * and no later setting can un-ask it: a denial must stay a denial.
 */
class ToolInvocationGateTest {

    @Test
    fun `given a DESTRUCTIVE call denied then policy set to NeverPrompt when resumed then it does not run`() = runTest {
        val fixture = Fixture(
            policy = ToolApprovalPolicy.NeverPrompt,
            risk = ToolRisk.DESTRUCTIVE,
            record = parkedRecord(PendingDecision.DENIED, recordedRisk = ToolRisk.DESTRUCTIVE),
        )

        val outputs = fixture.dispatch()

        fixture.assertDenied(outputs)
    }

    @Test
    fun `given a READ_ONLY call denied under AllCalls then policy relaxed when resumed then it does not run`() =
        runTest {
            val fixture = Fixture(
                policy = ToolApprovalPolicy.SensitiveOrDestructive,
                risk = ToolRisk.READ_ONLY,
                record = parkedRecord(PendingDecision.DENIED, recordedRisk = ToolRisk.READ_ONLY),
            )

            val outputs = fixture.dispatch()

            fixture.assertDenied(outputs)
        }

    @Test
    fun `given a call denied then its risk overridden to READ_ONLY when resumed then it does not run`() = runTest {
        // The AppFunction variant: parked as SENSITIVE (no override), then the
        // user set the tool's risk override to Read-only on the Tools screen
        // while the run waited — getRisk now answers READ_ONLY.
        val fixture = Fixture(
            policy = ToolApprovalPolicy.SensitiveOrDestructive,
            risk = ToolRisk.READ_ONLY,
            record = parkedRecord(PendingDecision.DENIED, recordedRisk = ToolRisk.SENSITIVE),
        )

        val outputs = fixture.dispatch()

        fixture.assertDenied(outputs)
    }

    @Test
    fun `given any recorded decision policy risk and node switch when resumed then it runs only if approved`() =
        runTest {
            // Guard for the whole class, not the three instances above: every
            // combination the resumed run can meet. A branch that consults the
            // recorded decision only under some of them fails here.
            val failures = mutableListOf<String>()
            for (decision in PendingDecision.entries) {
                for (policy in ToolApprovalPolicy.entries) {
                    for (risk in ToolRisk.entries) {
                        for (alwaysConfirm in listOf(false, true)) {
                            val fixture = Fixture(
                                policy = policy,
                                risk = risk,
                                record = parkedRecord(decision, recordedRisk = risk),
                            )

                            val outputs = fixture.dispatch(alwaysConfirm)

                            val combo = "decision=$decision policy=$policy risk=$risk alwaysConfirm=$alwaysConfirm"
                            val expectedRuns = if (decision == PendingDecision.APPROVED) 1 else 0
                            if (fixture.executions != expectedRuns) {
                                failures += "$combo: executed ${fixture.executions}x, expected ${expectedRuns}x"
                            }
                            if (outputs.filterStates<AgentOrchestratorState.WaitingForApproval>().isNotEmpty()) {
                                failures += "$combo: raised a fresh gate for an answered request"
                            }
                        }
                    }
                }
            }
            assertTrue(failures.joinToString(separator = "\n"), failures.isEmpty())
        }

    @Test
    fun `given a recorded decision and destructive tools blocked when resumed then the record is consumed`() = runTest {
        // The destructive block refuses the call before any approval logic —
        // it must not do so before the one-shot record is consumed, or the
        // answer outlives the gate it belonged to.
        val fixture = Fixture(
            policy = ToolApprovalPolicy.SensitiveOrDestructive,
            risk = ToolRisk.DESTRUCTIVE,
            record = parkedRecord(PendingDecision.APPROVED, recordedRisk = ToolRisk.DESTRUCTIVE),
            blockDestructive = true,
        )

        val outputs = fixture.dispatch()

        assertTrue(outputs.lastResult().error.orEmpty().contains("blocked by Settings"))
        assertEquals(0, fixture.executions)
        coVerify { fixture.pendingInteractionRepository.delete(RUN_ID) }
        coVerify {
            fixture.recordTriggerHitlEvent(RUN_ID, TriggerHitlEvent.Resolved(TriggerHitlResolution.APPROVED))
        }
    }

    @Test
    fun `given a recorded decision and the risk lookup fails when resumed then the record is consumed`() = runTest {
        val fixture = Fixture(
            policy = ToolApprovalPolicy.SensitiveOrDestructive,
            risk = ToolRisk.SENSITIVE,
            record = parkedRecord(PendingDecision.APPROVED, recordedRisk = ToolRisk.SENSITIVE),
        )
        coEvery { fixture.toolRepository.getRisk(TOOL, ARGS) } throws IllegalArgumentException("unknown tool")

        val outputs = fixture.dispatch()

        assertTrue(outputs.lastResult().error.orEmpty().startsWith("Risk lookup failed"))
        assertEquals(0, fixture.executions)
        coVerify { fixture.pendingInteractionRepository.delete(RUN_ID) }
    }

    @Test
    fun `given a live gate when its run is stopped then its approval notification is removed`() = runTest {
        // A notification left behind by a run that ended unanswered is an
        // Approve button for a request nothing waits on any more.
        val fixture =
            Fixture(policy = ToolApprovalPolicy.SensitiveOrDestructive, risk = ToolRisk.SENSITIVE, record = null)
        val job = launch { fixture.dispatch() }
        runCurrent()
        val requestId = fixture.liveRequestId()

        job.cancel()
        runCurrent()

        verify { fixture.approvalNotifier.cancelApprovalNotification(requestId) }
        assertEquals(0, fixture.executions)
    }

    @Test
    fun `given a live gate when a decision names another request then the gate keeps waiting`() = runTest {
        val fixture =
            Fixture(policy = ToolApprovalPolicy.SensitiveOrDestructive, risk = ToolRisk.SENSITIVE, record = null)
        val job = launch { fixture.dispatch() }
        runCurrent()
        val requestId = fixture.liveRequestId()

        val settledOther = fixture.gate.resumeWithApproval(SESSION_ID, "another-request", isApproved = true)
        runCurrent()

        assertFalse("a decision for another request settled this one", settledOther)
        assertEquals(0, fixture.executions)
        assertEquals(requestId, fixture.liveRequestId())

        assertTrue(fixture.gate.resumeWithApproval(SESSION_ID, requestId, isApproved = true))
        runCurrent()
        assertEquals(1, fixture.executions)
        assertFalse("a request is settled once", fixture.gate.resumeWithApproval(SESSION_ID, requestId, true))
        job.cancel()
    }

    @Test
    fun `given an answer that claims the request as the live wait runs out then that answer is applied`() = runTest {
        // The answer was accepted — resumeWithApproval returned true, and the
        // surface was told the request is settled — so the timeout firing in
        // the same moment must not park the run and drop it.
        val timeoutRead = CompletableDeferred<Long>()
        val fixture = Fixture(
            policy = ToolApprovalPolicy.SensitiveOrDestructive,
            risk = ToolRisk.SENSITIVE,
            record = null,
            liveWindowMs = flow { emit(timeoutRead.await()) },
        )
        val outputs = mutableListOf<NodeOutput>()
        val job = launch { outputs += fixture.dispatch() }
        runCurrent()

        assertTrue(fixture.gate.resumeWithApproval(SESSION_ID, fixture.liveRequestId(), isApproved = true))
        timeoutRead.complete(0L) // the window has already run out when the gate starts waiting
        job.join()

        assertEquals(1, fixture.executions)
        coVerify(exactly = 0) { fixture.pendingInteractionRepository.save(any()) }
        assertTrue(outputs.filterStates<AgentOrchestratorState.SuspendedInBackground>().isEmpty())
    }

    @Test
    fun `given two gates raised in turn when each is raised then each carries an identity of its own`() = runTest {
        val fixture =
            Fixture(policy = ToolApprovalPolicy.SensitiveOrDestructive, risk = ToolRisk.SENSITIVE, record = null)

        val first = launch { fixture.dispatch() }
        runCurrent()
        val firstId = fixture.liveRequestId()
        fixture.gate.resumeWithApproval(SESSION_ID, firstId, isApproved = false)
        runCurrent()
        first.join()
        val second = launch { fixture.dispatch() }
        runCurrent()

        assertNotEquals(firstId, fixture.liveRequestId())
        second.cancel()
    }

    @Test
    fun `given a run that cannot park when its live wait times out then its approval notification is removed`() =
        runTest {
            val fixture = Fixture(
                policy = ToolApprovalPolicy.SensitiveOrDestructive,
                risk = ToolRisk.SENSITIVE,
                record = null,
                runId = null,
            )

            val outputs = fixture.dispatch()

            assertEquals("Approval request timed out", outputs.lastResult().error)
            val raised = outputs.filterStates<AgentOrchestratorState.WaitingForApproval>().single()
            verify { fixture.approvalNotifier.cancelApprovalNotification(raised.requestId) }
        }

    @Test
    fun `given a live gate when its live wait times out and the run parks then the notification is kept`() = runTest {
        // The park posts the ongoing notification in place of the live one;
        // removing it afterwards would strand the parked run.
        val fixture =
            Fixture(policy = ToolApprovalPolicy.SensitiveOrDestructive, risk = ToolRisk.SENSITIVE, record = null)

        val outputs = fixture.dispatch()

        val raised = outputs.filterStates<AgentOrchestratorState.WaitingForApproval>().single()
        verify {
            fixture.approvalNotifier.sendPersistentApprovalRequest(
                RUN_ID,
                SESSION_ID,
                raised.requestId,
                TOOL,
                ARGS,
                ToolRisk.SENSITIVE,
            )
        }
        coVerify {
            fixture.pendingInteractionRepository.save(match { it.requestId == raised.requestId })
        }
        verify(exactly = 0) { fixture.approvalNotifier.cancelApprovalNotification(any()) }
    }

    /**
     * One gate with its own mocks, so every combination of the matrix starts
     * from a clean slate (fresh call counters, fresh one-shot record).
     *
     * @param policy approval policy in force when the run resumes.
     * @param risk risk [ToolRepository.getRisk] answers on resume.
     * @param record the parked approval record the resumed run finds.
     * @param blockDestructive state of the destructive-tools hard block.
     * @param runId the run the call belongs to; `null` for a non-persisted (editor test) run, which cannot park.
     * @param liveWindowMs the live approval window setting, read once per raised gate.
     */
    private class Fixture(
        policy: ToolApprovalPolicy,
        risk: ToolRisk,
        record: PendingInteraction?,
        blockDestructive: Boolean = false,
        private val runId: String? = RUN_ID,
        liveWindowMs: Flow<Long> = flowOf(100L),
    ) {
        val toolRepository: ToolRepository = mockk(relaxed = true)
        val pendingInteractionRepository: PendingInteractionRepository = mockk(relaxed = true)
        val recordTriggerHitlEvent: RecordTriggerHitlEventUseCase = mockk(relaxed = true)
        private val settingsRepository: SettingsRepository = mockk(relaxed = true)
        val approvalNotifier: ApprovalNotifier = mockk(relaxed = true)

        /** How many times the tool actually ran. */
        var executions = 0
            private set

        val gate: ToolInvocationGate

        init {
            coEvery { toolRepository.getRisk(TOOL, ARGS) } returns risk
            coEvery { toolRepository.executeTool(TOOL, ARGS, any()) } answers {
                executions++
                "executed"
            }
            every { settingsRepository.toolApprovalPolicy } returns flowOf(policy)
            every { settingsRepository.blockDestructiveTools } returns flowOf(blockDestructive)
            every { settingsRepository.toolCallTimeoutMs } returns liveWindowMs
            coEvery { pendingInteractionRepository.getForRun(RUN_ID) } returns record
            coEvery { pendingInteractionRepository.save(any()) } returns true
            gate = ToolInvocationGate(
                toolRepository = toolRepository,
                settingsRepository = settingsRepository,
                approvalNotifier = approvalNotifier,
                chatRepository = mockk(relaxed = true),
                pendingInteractionRepository = pendingInteractionRepository,
                recordTriggerHitlEvent = recordTriggerHitlEvent,
            )
        }

        /** Identity of the request the gate is live on now. */
        fun liveRequestId(): String = requireNotNull(gate.pendingApprovalFor(SESSION_ID)) { "no live gate" }.requestId

        /** Dispatches the call and collects everything the gate emits. */
        suspend fun dispatch(alwaysConfirm: Boolean = false): List<NodeOutput> = flow {
            gate.dispatch(
                collector = this,
                nodeType = "TOOL",
                nodeId = "node-1",
                sessionId = SESSION_ID,
                runId = runId,
                resolvedToolName = TOOL,
                resolvedToolArgs = ARGS,
                alwaysConfirm = alwaysConfirm,
            )
        }.toList()

        /** The recorded denial took effect: nothing ran, the run saw a denial, the gate settled as denied. */
        suspend fun assertDenied(outputs: List<NodeOutput>) {
            assertEquals("the denied tool ran", 0, executions)
            assertEquals("Execution denied by user", outputs.lastResult().outputText)
            assertTrue(outputs.filterStates<AgentOrchestratorState.WaitingForApproval>().isEmpty())
            coVerify { pendingInteractionRepository.delete(RUN_ID) }
            coVerify { recordTriggerHitlEvent(RUN_ID, TriggerHitlEvent.Resolved(TriggerHitlResolution.DENIED)) }
        }
    }

    private companion object {
        const val RUN_ID = "run-1"
        const val SESSION_ID = "session-1"
        const val TOOL = "some_tool"
        const val ARGS = """{"path":"notes.md"}"""

        /**
         * The approval record a run parked on, answered with [decision] and
         * matching the resumed call exactly (so the TOCTOU guard lets it apply).
         */
        fun parkedRecord(decision: PendingDecision, recordedRisk: ToolRisk) = PendingInteraction(
            runId = RUN_ID,
            sessionId = SESSION_ID,
            kind = PendingInteractionKind.APPROVAL,
            toolName = TOOL,
            toolArgs = ARGS,
            risk = recordedRisk,
            decision = decision,
            requestedAt = 0L,
        )
    }
}
