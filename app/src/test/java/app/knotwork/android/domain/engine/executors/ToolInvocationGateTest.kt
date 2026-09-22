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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    /**
     * One gate with its own mocks, so every combination of the matrix starts
     * from a clean slate (fresh call counters, fresh one-shot record).
     *
     * @param policy approval policy in force when the run resumes.
     * @param risk risk [ToolRepository.getRisk] answers on resume.
     * @param record the parked approval record the resumed run finds.
     * @param blockDestructive state of the destructive-tools hard block.
     */
    private class Fixture(
        policy: ToolApprovalPolicy,
        risk: ToolRisk,
        record: PendingInteraction?,
        blockDestructive: Boolean = false,
    ) {
        val toolRepository: ToolRepository = mockk(relaxed = true)
        val pendingInteractionRepository: PendingInteractionRepository = mockk(relaxed = true)
        val recordTriggerHitlEvent: RecordTriggerHitlEventUseCase = mockk(relaxed = true)
        private val settingsRepository: SettingsRepository = mockk(relaxed = true)
        private val approvalNotifier: ApprovalNotifier = mockk(relaxed = true)

        /** How many times the tool actually ran. */
        var executions = 0
            private set

        private val gate: ToolInvocationGate

        init {
            coEvery { toolRepository.getRisk(TOOL, ARGS) } returns risk
            coEvery { toolRepository.executeTool(TOOL, ARGS, any()) } answers {
                executions++
                "executed"
            }
            every { settingsRepository.toolApprovalPolicy } returns flowOf(policy)
            every { settingsRepository.blockDestructiveTools } returns flowOf(blockDestructive)
            every { settingsRepository.toolCallTimeoutMs } returns flowOf(100L)
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

        /** Dispatches the resumed call and collects everything the gate emits. */
        suspend fun dispatch(alwaysConfirm: Boolean = false): List<NodeOutput> = flow {
            gate.dispatch(
                collector = this,
                nodeType = "TOOL",
                nodeId = "node-1",
                sessionId = SESSION_ID,
                runId = RUN_ID,
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
