package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.ChatSession
import app.knotwork.android.domain.models.NetworkState
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PowerState
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.Trigger
import app.knotwork.android.domain.models.TriggerCondition
import app.knotwork.android.domain.models.TriggerEvaluationSource
import app.knotwork.android.domain.models.TriggerEvaluationVerdict
import app.knotwork.android.domain.models.TriggerSkipReason
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.NetworkStateRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PowerStateRepository
import app.knotwork.android.domain.repositories.TriggerRepository
import app.knotwork.android.domain.repositories.UsageTelemetryRepository
import app.knotwork.android.domain.services.ScheduledTaskNotifier
import app.knotwork.android.domain.services.TaskScheduler
import app.knotwork.android.domain.services.TriggerScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [FireTriggerUseCase] — the worker-side orchestration that loads
 * a trigger, defers the decision to [EvaluateTriggerFiringUseCase], acts
 * (resolve the bound session, enqueue, mark fired, disarm event triggers,
 * re-arm, or auto-disable), and — the focus of the journal write-point tests —
 * records exactly one evaluation verdict per evaluated trigger. The evaluator is
 * mocked so each act-branch is exercised in isolation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FireTriggerUseCaseTest {

    private lateinit var triggerRepository: TriggerRepository
    private lateinit var pipelineRepository: PipelineRepository
    private lateinit var powerStateRepository: PowerStateRepository
    private lateinit var networkStateRepository: NetworkStateRepository
    private lateinit var evaluate: EvaluateTriggerFiringUseCase
    private lateinit var taskScheduler: TaskScheduler
    private lateinit var chatRepository: ChatRepository
    private lateinit var scheduledTaskNotifier: ScheduledTaskNotifier
    private lateinit var triggerScheduler: TriggerScheduler
    private lateinit var usageTelemetry: UsageTelemetryRepository
    private lateinit var recordEvaluation: RecordTriggerEvaluationUseCase
    private lateinit var useCase: FireTriggerUseCase

    private val triggerId = "t1"
    private val now = 1_000L
    private val source = TriggerEvaluationSource.POLL

    private val chargingTrigger = Trigger(
        id = triggerId,
        name = "T",
        condition = TriggerCondition.Charging,
        pipelineId = "pipe-1",
        prompt = "do it",
        enabled = true,
        createdAt = 0L,
    )
    private val intervalTrigger = chargingTrigger.copy(condition = TriggerCondition.IntervalSchedule(30))

    @Before
    fun setUp() {
        triggerRepository = mockk(relaxed = true)
        pipelineRepository = mockk()
        powerStateRepository = mockk { every { powerState } returns MutableStateFlow(PowerState()) }
        networkStateRepository = mockk { every { networkState } returns MutableStateFlow(NetworkState()) }
        evaluate = mockk()
        taskScheduler = mockk(relaxed = true)
        chatRepository = mockk(relaxed = true)
        scheduledTaskNotifier = mockk(relaxed = true)
        triggerScheduler = mockk(relaxed = true)
        usageTelemetry = mockk(relaxed = true)
        recordEvaluation = mockk(relaxed = true)
        useCase = FireTriggerUseCase(
            triggerRepository = triggerRepository,
            pipelineRepository = pipelineRepository,
            powerStateRepository = powerStateRepository,
            networkStateRepository = networkStateRepository,
            evaluateTriggerFiring = evaluate,
            taskScheduler = taskScheduler,
            chatRepository = chatRepository,
            scheduledTaskNotifier = scheduledTaskNotifier,
            triggerScheduler = triggerScheduler,
            usageTelemetry = usageTelemetry,
            recordTriggerEvaluation = recordEvaluation,
        )
    }

    @Test
    fun `given missing trigger when invoked then NotFound, nothing scheduled, nothing journaled`() = runTest {
        coEvery { triggerRepository.getTriggerById(triggerId) } returns null

        val outcome = useCase(triggerId, source, now)

        assertEquals(TriggerFireOutcome.NotFound, outcome)
        coVerify(exactly = 0) { taskScheduler.scheduleOneTime(any(), any(), any(), any(), any(), any(), any()) }
        // A deleted trigger has no row to attribute a journal entry to.
        coVerify(exactly = 0) { recordEvaluation(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `given skip decision when invoked then Skipped, nothing scheduled, one Skipped journal row`() = runTest {
        coEvery { triggerRepository.getTriggerById(triggerId) } returns chargingTrigger
        every { evaluate(any(), any(), any(), any(), any()) } returns
            TriggerFiringDecision.Skip(TriggerSkipReason.ALREADY_FIRED)

        val outcome = useCase(triggerId, source, now)

        assertEquals(TriggerFireOutcome.Skipped(TriggerSkipReason.ALREADY_FIRED), outcome)
        coVerify(exactly = 0) { taskScheduler.scheduleOneTime(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { triggerRepository.markFired(any(), any()) }
        assertExactlyOneJournalRow(
            verdict = TriggerEvaluationVerdict.Skipped(TriggerSkipReason.ALREADY_FIRED),
        )
    }

    @Test
    fun `given a charging-sweep source when skipped then the journal row carries that source`() = runTest {
        coEvery { triggerRepository.getTriggerById(triggerId) } returns chargingTrigger
        every { evaluate(any(), any(), any(), any(), any()) } returns
            TriggerFiringDecision.Skip(TriggerSkipReason.CONDITION_NOT_MET)

        useCase(triggerId, TriggerEvaluationSource.CHARGING_SWEEP, now)

        coVerify(exactly = 1) {
            recordEvaluation(
                triggerId = triggerId,
                source = TriggerEvaluationSource.CHARGING_SWEEP,
                verdict = TriggerEvaluationVerdict.Skipped(TriggerSkipReason.CONDITION_NOT_MET),
                runId = any(),
                evaluatedAt = now,
                id = any(),
            )
        }
    }

    @Test
    fun `given re-arm decision when invoked then re-arms, does not schedule, one ReArmed journal row`() = runTest {
        coEvery { triggerRepository.getTriggerById(triggerId) } returns chargingTrigger
        every { evaluate(any(), any(), any(), any(), any()) } returns TriggerFiringDecision.ReArm

        val outcome = useCase(triggerId, source, now)

        assertEquals(TriggerFireOutcome.ReArmed, outcome)
        coVerify(exactly = 1) { triggerRepository.setArmed(triggerId, true) }
        // Re-arm re-registers so the consumed charging one-shot watch is recreated.
        verify(exactly = 1) { triggerScheduler.register(chargingTrigger) }
        coVerify(exactly = 0) { taskScheduler.scheduleOneTime(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { triggerRepository.markFired(any(), any()) }
        assertExactlyOneJournalRow(verdict = TriggerEvaluationVerdict.ReArmed)
    }

    @Test
    fun `given fire on an event trigger then marks fired, disarms, enqueues, and journals Fired with the run id`() =
        runTest {
            coEvery { triggerRepository.getTriggerById(triggerId) } returns chargingTrigger
            every { evaluate(any(), any(), any(), any(), any()) } returns TriggerFiringDecision.Fire("pipe-1", "do it")
            coEvery { pipelineRepository.getPipelineById("pipe-1") } returns mockk<PipelineGraph>()
            val scheduledRunId = slot<String?>()
            coEvery {
                taskScheduler.scheduleOneTime(any(), any(), any(), any(), any(), any(), captureNullable(scheduledRunId))
            } returns Unit
            val journaledRunId = slot<String?>()
            coEvery {
                recordEvaluation(any(), any(), any(), captureNullable(journaledRunId), any(), any())
            } returns mockk(relaxed = true)

            val outcome = useCase(triggerId, source, now)

            assertEquals(TriggerFireOutcome.Fired("pipe-1"), outcome)
            coVerify(exactly = 1) { triggerRepository.markFired(triggerId, now) }
            coVerify(exactly = 1) { triggerRepository.setArmed(triggerId, false) }
            coVerify(exactly = 1) {
                taskScheduler.scheduleOneTime(
                    prompt = "do it",
                    delayMinutes = 0,
                    sessionId = any(),
                    constraints = any(),
                    pipelineId = "pipe-1",
                    origin = RunOrigin.TRIGGER,
                    runId = any(),
                )
            }
            // The firing is tallied into the privacy-preserving local statistics by kind.
            coVerify(exactly = 1) { usageTelemetry.recordTriggerFired("CHARGING", now) }
            // Exactly one journal row, a Fired verdict, whose run id is the very id
            // threaded to the scheduler — the key the run's outcome is attributed by.
            coVerify(exactly = 1) {
                recordEvaluation(
                    triggerId = triggerId,
                    source = source,
                    verdict = TriggerEvaluationVerdict.Fired,
                    runId = any(),
                    evaluatedAt = now,
                    id = any(),
                )
            }
            assertTrue(scheduledRunId.captured?.isNotBlank() == true)
            assertEquals(scheduledRunId.captured, journaledRunId.captured)
        }

    @Test
    fun `given a skipped trigger then no firing is recorded into usage statistics`() = runTest {
        coEvery { triggerRepository.getTriggerById(triggerId) } returns chargingTrigger
        every { evaluate(any(), any(), any(), any(), any()) } returns
            TriggerFiringDecision.Skip(TriggerSkipReason.CONDITION_NOT_MET)

        useCase(triggerId, source, now)

        coVerify(exactly = 0) { usageTelemetry.recordTriggerFired(any(), any()) }
    }

    @Test
    fun `given fire on a time trigger then marks fired and enqueues but does not touch the armed latch`() = runTest {
        coEvery { triggerRepository.getTriggerById(triggerId) } returns intervalTrigger
        every { evaluate(any(), any(), any(), any(), any()) } returns TriggerFiringDecision.Fire("pipe-1", "do it")
        coEvery { pipelineRepository.getPipelineById("pipe-1") } returns mockk<PipelineGraph>()
        val scheduledSessionId = slot<String?>()
        coEvery {
            taskScheduler.scheduleOneTime(any(), any(), captureNullable(scheduledSessionId), any(), any(), any(), any())
        } returns Unit

        val outcome = useCase(triggerId, source, now)

        assertEquals(TriggerFireOutcome.Fired("pipe-1"), outcome)
        coVerify(exactly = 1) { triggerRepository.markFired(triggerId, now) }
        coVerify(exactly = 0) { triggerRepository.setArmed(any(), any()) }
        // The interval branch must also thread a valid (non-blank) bound session.
        assertTrue(scheduledSessionId.captured?.isNotBlank() == true)
    }

    @Test
    fun `given fire but deleted pipeline when invoked then auto-disables, no schedule, one UNBOUND journal row`() =
        runTest {
            coEvery { triggerRepository.getTriggerById(triggerId) } returns chargingTrigger
            every { evaluate(any(), any(), any(), any(), any()) } returns TriggerFiringDecision.Fire("pipe-1", "do it")
            coEvery { pipelineRepository.getPipelineById("pipe-1") } returns null

            val outcome = useCase(triggerId, source, now)

            assertEquals(TriggerFireOutcome.Disabled("pipe-1"), outcome)
            coVerify(exactly = 1) { triggerRepository.setEnabled(triggerId, false) }
            coVerify(exactly = 0) { taskScheduler.scheduleOneTime(any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { triggerRepository.markFired(any(), any()) }
            // A fire into a deleted pipeline enqueues no run, so it journals as a
            // skip: the bound pipeline is gone, which reads as UNBOUND after the fact.
            assertExactlyOneJournalRow(verdict = TriggerEvaluationVerdict.Skipped(TriggerSkipReason.UNBOUND))
        }

    @Test
    fun `given an unbound trigger when it fires then mints a session, binds it, and threads it everywhere`() = runTest {
        coEvery { triggerRepository.getTriggerById(triggerId) } returns chargingTrigger
        every { evaluate(any(), any(), any(), any(), any()) } returns TriggerFiringDecision.Fire("pipe-1", "do it")
        coEvery { pipelineRepository.getPipelineById("pipe-1") } returns mockk<PipelineGraph>()
        val savedSession = slot<ChatSession>()
        coEvery { chatRepository.saveSession(capture(savedSession)) } returns Unit
        val boundSessionId = slot<String>()
        coEvery { triggerRepository.setSessionId(triggerId, capture(boundSessionId)) } returns Unit
        val scheduledSessionId = slot<String?>()
        coEvery {
            taskScheduler.scheduleOneTime(any(), any(), captureNullable(scheduledSessionId), any(), any(), any(), any())
        } returns Unit
        val notifiedSessionId = slot<String>()
        coEvery { scheduledTaskNotifier.notifyTriggerFired(capture(notifiedSessionId), any()) } returns Unit

        useCase(triggerId, source, now)

        // The same freshly-minted id flows to the session row, the binding, the
        // scheduler and the notification.
        val sessionId = savedSession.captured.id
        assertEquals(sessionId, boundSessionId.captured)
        assertEquals(sessionId, scheduledSessionId.captured)
        assertEquals(sessionId, notifiedSessionId.captured)
        // The new session is named after the trigger and carries NO pipeline
        // theming (it is a result log; manual follow-ups use the app default).
        assertEquals("T", savedSession.captured.name)
        assertNull(savedSession.captured.pipelineId)
    }

    @Test
    fun `given a trigger with an existing bound session when it fires then reuses it without re-binding`() = runTest {
        coEvery { triggerRepository.getTriggerById(triggerId) } returns chargingTrigger.copy(sessionId = "sess-1")
        every { evaluate(any(), any(), any(), any(), any()) } returns TriggerFiringDecision.Fire("pipe-1", "do it")
        coEvery { pipelineRepository.getPipelineById("pipe-1") } returns mockk<PipelineGraph>()
        coEvery { chatRepository.sessionExists("sess-1") } returns true

        useCase(triggerId, source, now)

        coVerify(exactly = 0) { chatRepository.saveSession(any()) }
        coVerify(exactly = 0) { triggerRepository.setSessionId(any(), any()) }
        coVerify(exactly = 1) {
            taskScheduler.scheduleOneTime(any(), any(), "sess-1", any(), any(), any(), any())
        }
        coVerify(exactly = 1) { scheduledTaskNotifier.notifyTriggerFired("sess-1", "T") }
    }

    @Test
    fun `given a deleted bound session when the trigger fires then mints and re-binds a fresh one`() = runTest {
        coEvery { triggerRepository.getTriggerById(triggerId) } returns chargingTrigger.copy(sessionId = "gone")
        every { evaluate(any(), any(), any(), any(), any()) } returns TriggerFiringDecision.Fire("pipe-1", "do it")
        coEvery { pipelineRepository.getPipelineById("pipe-1") } returns mockk<PipelineGraph>()
        coEvery { chatRepository.sessionExists("gone") } returns false
        val rebound = slot<String>()
        coEvery { triggerRepository.setSessionId(triggerId, capture(rebound)) } returns Unit

        useCase(triggerId, source, now)

        coVerify(exactly = 1) { chatRepository.saveSession(any()) }
        coVerify(exactly = 1) { triggerRepository.setSessionId(triggerId, any()) }
        // The replacement is a fresh id, not the deleted one.
        assert(rebound.captured != "gone")
    }

    @Test
    fun `given the enqueue fails on a fresh session then the session is rolled back, not bound, and not journaled`() =
        runTest {
            coEvery { triggerRepository.getTriggerById(triggerId) } returns chargingTrigger
            every { evaluate(any(), any(), any(), any(), any()) } returns TriggerFiringDecision.Fire("pipe-1", "do it")
            coEvery { pipelineRepository.getPipelineById("pipe-1") } returns mockk<PipelineGraph>()
            val savedSession = slot<ChatSession>()
            coEvery { chatRepository.saveSession(capture(savedSession)) } returns Unit
            coEvery { taskScheduler.scheduleOneTime(any(), any(), any(), any(), any(), any(), any()) } throws
                RuntimeException("enqueue failed")

            assertThrows(RuntimeException::class.java) { runBlockingFire() }

            // The freshly-minted session is deleted and the binding is never
            // persisted, so a failed fire leaves no empty orphan chat behind.
            coVerify(exactly = 1) { chatRepository.deleteSession(savedSession.captured.id) }
            coVerify(exactly = 0) { triggerRepository.setSessionId(any(), any()) }
            // No Fired row is written for a fire that never enqueued a run — the
            // worker retry re-evaluates and records the resulting verdict instead.
            coVerify(exactly = 0) { recordEvaluation(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `given the trigger-fired notification throws then the fire still succeeds`() = runTest {
        coEvery { triggerRepository.getTriggerById(triggerId) } returns chargingTrigger
        every { evaluate(any(), any(), any(), any(), any()) } returns TriggerFiringDecision.Fire("pipe-1", "do it")
        coEvery { pipelineRepository.getPipelineById("pipe-1") } returns mockk<PipelineGraph>()
        coEvery { scheduledTaskNotifier.notifyTriggerFired(any(), any()) } throws RuntimeException("notify failed")

        // A best-effort notification failure must not propagate (a worker retry
        // past the completed enqueue would double-schedule the run).
        val outcome = useCase(triggerId, source, now)

        assertEquals(TriggerFireOutcome.Fired("pipe-1"), outcome)
        coVerify(exactly = 1) { triggerRepository.setSessionId(triggerId, any()) }
    }

    /**
     * Asserts exactly one journal row was written for [triggerId] with the given
     * [verdict], the test [source] and the injected clock — the "no silent skips,
     * no duplicate rows" invariant for a single evaluation. `runId` / `id` use
     * `any()` because they are defaulted arguments the caller does not pin.
     */
    private fun assertExactlyOneJournalRow(verdict: TriggerEvaluationVerdict) {
        coVerify(exactly = 1) {
            recordEvaluation(
                triggerId = triggerId,
                source = source,
                verdict = verdict,
                runId = any(),
                evaluatedAt = now,
                id = any(),
            )
        }
        coVerify(exactly = 1) { recordEvaluation(any(), any(), any(), any(), any(), any()) }
    }

    /** Helper invoking the suspend use case from a non-suspend assertThrows lambda. */
    private fun runBlockingFire() = kotlinx.coroutines.runBlocking { useCase(triggerId, source, now) }
}
