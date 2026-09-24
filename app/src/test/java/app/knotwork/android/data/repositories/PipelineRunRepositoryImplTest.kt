package app.knotwork.android.data.repositories

import app.knotwork.android.data.local.dao.PipelineRunDao
import app.knotwork.android.data.local.models.PipelineRunEntity
import app.knotwork.android.data.local.models.RunChatIdentityProjection
import app.knotwork.android.data.local.models.RunSpendProjection
import app.knotwork.android.domain.constants.ExternalAutomationContract
import app.knotwork.android.domain.models.ExternalAutomationJournalEntry
import app.knotwork.android.domain.models.ExternalAutomationStatus
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunSpend
import app.knotwork.android.domain.models.RunTerminationReason
import app.knotwork.android.domain.models.TriggerRunOutcome
import app.knotwork.android.domain.repositories.ExternalAutomationJournalRepository
import app.knotwork.android.domain.repositories.TriggerJournalRepository
import app.knotwork.android.domain.repositories.UsageTelemetryRepository
import app.knotwork.android.domain.services.ExternalAutomationCallbackNotifier
import app.knotwork.android.domain.services.RunOutcomeAnnouncer
import app.knotwork.android.domain.usecases.ParkedRunResumer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PipelineRunRepositoryImpl]: entity↔domain mapping, the
 * terminal-guard plumbing (every mutating call must pass the terminal status
 * list to the DAO), the ownership-filtered orphan query, and the best-effort
 * contract (storage failures are absorbed, never propagated).
 */
class PipelineRunRepositoryImplTest {

    private lateinit var pipelineRunDao: PipelineRunDao
    private lateinit var usageTelemetry: UsageTelemetryRepository
    private lateinit var triggerJournal: TriggerJournalRepository
    private lateinit var externalAutomationJournal: ExternalAutomationJournalRepository
    private lateinit var externalAutomationCallback: ExternalAutomationCallbackNotifier
    private lateinit var runOutcomeAnnouncer: RunOutcomeAnnouncer
    private lateinit var repository: PipelineRunRepositoryImpl

    private val terminalNames = listOf("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED")
    private val activeNames =
        listOf("QUEUED", "RUNNING", "WAITING_APPROVAL", "WAITING_CLARIFICATION", "WAITING_CEILING")

    private val sampleRun = PipelineRun(
        id = "run-1",
        sessionId = "session-1",
        pipelineId = null,
        origin = RunOrigin.CHAT,
        status = PipelineRunStatus.QUEUED,
        currentNodeId = null,
        startedAt = 1_000L,
        finishedAt = null,
        errorMessage = null,
        graphContentHash = null,
    )

    private val sampleEntity = PipelineRunEntity(
        id = "run-1",
        sessionId = "session-1",
        pipelineId = "pipe-1",
        origin = "SCHEDULER",
        status = "WAITING_APPROVAL",
        currentNodeId = "node-7",
        startedAt = 1_000L,
        finishedAt = null,
        errorMessage = null,
        graphContentHash = "abc",
    )

    @Before
    fun setup() {
        pipelineRunDao = mockk(relaxed = true)
        // Telemetry disabled by default so the existing assertions are unaffected;
        // the dedicated recordRunTelemetry tests flip it on explicitly.
        usageTelemetry = mockk(relaxed = true)
        coEvery { usageTelemetry.isEnabled() } returns false
        // The trigger journal is a best-effort observer: relaxed so the outcome
        // write is a no-op for the runs the pre-existing tests finish.
        triggerJournal = mockk(relaxed = true)
        // Same posture for the external-request journal: these tests finish runs of
        // other origins, for which the hook must do nothing at all.
        externalAutomationJournal = mockk(relaxed = true)
        // The settle result is what gates the terminal callback; a relaxed Boolean
        // would default to false and silently disable every callback assertion.
        coEvery { externalAutomationJournal.recordOutcome(any(), any()) } returns true
        externalAutomationCallback = mockk(relaxed = true)
        runOutcomeAnnouncer = mockk(relaxed = true)
        repository = PipelineRunRepositoryImpl(
            pipelineRunDao,
            usageTelemetry,
            triggerJournal,
            externalAutomationJournal,
            externalAutomationCallback,
            runOutcomeAnnouncer,
        )
    }

    @Test
    fun `given queued run when createRun then entity stores enum names`() = runTest {
        val captured = slot<PipelineRunEntity>()
        coEvery { pipelineRunDao.insertRun(capture(captured)) } returns Unit

        repository.createRun(sampleRun)

        assertEquals("run-1", captured.captured.id)
        assertEquals("CHAT", captured.captured.origin)
        assertEquals("QUEUED", captured.captured.status)
        assertNull(captured.captured.pipelineId)
        assertNull(captured.captured.graphContentHash)
    }

    @Test
    fun `given run when markRunning then DAO receives RUNNING and terminal guard`() = runTest {
        repository.markRunning("run-1", "pipe-1", "hash-1")

        coVerify {
            pipelineRunDao.markRunning(
                runId = "run-1",
                status = "RUNNING",
                pipelineId = "pipe-1",
                graphContentHash = "hash-1",
                terminalStatuses = terminalNames,
            )
        }
    }

    @Test
    fun `given run when updateStatus then DAO receives status name and terminal guard`() = runTest {
        repository.updateStatus("run-1", PipelineRunStatus.WAITING_CLARIFICATION)

        coVerify {
            pipelineRunDao.updateStatus(
                runId = "run-1",
                status = "WAITING_CLARIFICATION",
                terminalStatuses = terminalNames,
            )
        }
    }

    @Test
    fun `given run when updateCurrentNode then DAO receives node id and terminal guard`() = runTest {
        repository.updateCurrentNode("run-1", "node-3")

        coVerify {
            pipelineRunDao.updateCurrentNode(
                runId = "run-1",
                nodeId = "node-3",
                terminalStatuses = terminalNames,
            )
        }
    }

    @Test
    fun `given terminal status when finishRun then DAO receives timestamp and message`() = runTest {
        val finishedAt = slot<Long>()

        repository.finishRun("run-1", PipelineRunStatus.INTERRUPTED, "process died")

        coVerify {
            pipelineRunDao.finishRun(
                runId = "run-1",
                status = "INTERRUPTED",
                finishedAt = capture(finishedAt),
                errorMessage = "process died",
                terminationReason = null,
                terminalStatuses = terminalNames,
            )
        }
        assertTrue(finishedAt.captured > 0L)
    }

    @Test
    fun `given non-terminal status when finishRun then throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.finishRun("run-1", PipelineRunStatus.RUNNING)
            }
        }
        coVerify(exactly = 0) { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `given entity when getActiveRunForSession then maps to domain with parsed enums`() = runTest {
        val statuses = slot<List<String>>()
        coEvery {
            pipelineRunDao.getActiveRunForSession("session-1", capture(statuses))
        } returns sampleEntity

        val run = repository.getActiveRunForSession("session-1")

        assertEquals(RunOrigin.SCHEDULER, run?.origin)
        assertEquals(PipelineRunStatus.WAITING_APPROVAL, run?.status)
        assertEquals("node-7", run?.currentNodeId)
        // Active lookup must match exactly the non-terminal statuses.
        assertEquals(activeNames, statuses.captured)
    }

    @Test
    fun `given no active run when getActiveRunForSession then returns null`() = runTest {
        coEvery { pipelineRunDao.getActiveRunForSession("session-1", any()) } returns null

        assertNull(repository.getActiveRunForSession("session-1"))
    }

    @Test
    fun `given entity when getLatestRunForSession then maps to domain regardless of status`() = runTest {
        coEvery {
            pipelineRunDao.getLatestRunForSession("session-1")
        } returns sampleEntity.copy(status = "COMPLETED")

        val run = repository.getLatestRunForSession("session-1")

        assertEquals(PipelineRunStatus.COMPLETED, run?.status)
        assertEquals(RunOrigin.SCHEDULER, run?.origin)
    }

    @Test
    fun `given no runs when getLatestRunForSession then returns null`() = runTest {
        coEvery { pipelineRunDao.getLatestRunForSession("session-1") } returns null

        assertNull(repository.getLatestRunForSession("session-1"))
    }

    @Test
    fun `given store failure when getLatestRunForSession then degrades to null`() = runTest {
        coEvery { pipelineRunDao.getLatestRunForSession("session-1") } throws RuntimeException("corrupt")

        assertNull(repository.getLatestRunForSession("session-1"))
    }

    @Test
    fun `orphan query covers every non-terminal status`() = runTest {
        val statuses = slot<List<String>>()
        coEvery { pipelineRunDao.getRunsByStatuses(capture(statuses)) } returns listOf(
            sampleEntity.copy(status = "WAITING_APPROVAL"),
        )

        val orphans = repository.getOrphanedRuns()

        assertEquals(activeNames, statuses.captured)
        assertEquals(1, orphans.size)
        assertEquals(PipelineRunStatus.WAITING_APPROVAL, orphans.first().status)
    }

    @Test
    fun `orphan query excludes runs created by the current process`() = runTest {
        // Register run-1 as owned by this process; run-2 belongs to a dead one.
        repository.createRun(sampleRun)
        coEvery { pipelineRunDao.getRunsByStatuses(any()) } returns listOf(
            sampleEntity.copy(id = "run-1", status = "RUNNING", origin = "CHAT"),
            sampleEntity.copy(id = "run-2", status = "RUNNING", origin = "CHAT"),
        )

        val orphans = repository.getOrphanedRuns()

        assertEquals(listOf("run-2"), orphans.map { it.id })
    }

    @Test
    fun `given runs flow when observeRunsForSession then maps every entity`() = runTest {
        coEvery { pipelineRunDao.observeRunsForSession("session-1") } returns flowOf(
            listOf(sampleEntity, sampleEntity.copy(id = "run-2", status = "COMPLETED")),
        )

        val runs = repository.observeRunsForSession("session-1").first()

        assertEquals(2, runs.size)
        assertEquals(PipelineRunStatus.COMPLETED, runs[1].status)
    }

    @Test
    fun `observeActiveRunSessionIds queries every non-terminal status and dedups to a set`() = runTest {
        coEvery { pipelineRunDao.observeSessionIdsByStatuses(activeNames) } returns flowOf(
            listOf("session-1", "session-2"),
        )

        val sessionIds = repository.observeActiveRunSessionIds().first()

        assertEquals(setOf("session-1", "session-2"), sessionIds)
    }

    @Test
    fun `observeActiveRunSessionIds suppresses emissions with an unchanged set`() = runTest {
        coEvery { pipelineRunDao.observeSessionIdsByStatuses(any()) } returns flowOf(
            listOf("session-1"),
            // Room re-runs the query on every table write; an order change
            // or a re-emission with the same membership must not reach the
            // consumer.
            listOf("session-1"),
            listOf("session-1", "session-2"),
        )

        val emissions = mutableListOf<Set<String>>()
        repository.observeActiveRunSessionIds().collect { emissions.add(it) }

        assertEquals(listOf(setOf("session-1"), setOf("session-1", "session-2")), emissions)
    }

    @Test
    fun `given failing upstream when observeActiveRunSessionIds then emits empty set`() = runTest {
        coEvery { pipelineRunDao.observeSessionIdsByStatuses(any()) } returns flow {
            throw IllegalStateException("io")
        }

        assertEquals(emptySet<String>(), repository.observeActiveRunSessionIds().first())
    }

    @Test
    fun `discardInterruptedRun issues the guarded INTERRUPTED to FAILED transition`() = runTest {
        repository.discardInterruptedRun("run-1")

        coVerify {
            pipelineRunDao.discardInterruptedRun(
                runId = "run-1",
                fromStatus = "INTERRUPTED",
                toStatus = "FAILED",
                errorMessage = "Discarded by user",
                terminationReason = "DISCARDED_BY_USER",
            )
        }
    }

    @Test
    fun `given DAO failure when discardInterruptedRun then absorbed`() = runTest {
        coEvery { pipelineRunDao.discardInterruptedRun(any(), any(), any(), any(), any()) } throws
            IllegalStateException("disk full")

        repository.discardInterruptedRun("run-1") // must not throw
    }

    // region Best-effort contract — storage failures are absorbed

    @Test
    fun `given DAO insert failure when createRun then absorbed and id still owned`() = runTest {
        coEvery { pipelineRunDao.insertRun(any()) } throws IllegalStateException("disk full")

        repository.createRun(sampleRun) // must not throw

        // Ownership registration must precede the failed insert: the orphan
        // sweep may never claim a run whose machinery lives in this process.
        coEvery { pipelineRunDao.getRunsByStatuses(any()) } returns listOf(
            sampleEntity.copy(id = sampleRun.id, status = "QUEUED", origin = "CHAT"),
        )
        assertEquals(emptyList<PipelineRun>(), repository.getOrphanedRuns())
    }

    @Test
    fun `given DAO failure when writes then absorbed`() = runTest {
        coEvery { pipelineRunDao.markRunning(any(), any(), any(), any(), any()) } throws
            IllegalStateException("disk full")
        coEvery { pipelineRunDao.updateStatus(any(), any(), any()) } throws IllegalStateException("disk full")
        coEvery { pipelineRunDao.updateCurrentNode(any(), any(), any()) } throws IllegalStateException("disk full")
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("disk full")

        // None of these may throw — run records are observability only.
        repository.markRunning("run-1", "pipe-1", "hash-1")
        repository.updateStatus("run-1", PipelineRunStatus.RUNNING)
        repository.updateCurrentNode("run-1", "node-3")
        repository.finishRun("run-1", PipelineRunStatus.COMPLETED)
    }

    @Test
    fun `given DAO failure when reads then degrade to neutral results`() = runTest {
        coEvery { pipelineRunDao.getActiveRunForSession(any(), any()) } throws IllegalStateException("io")
        coEvery { pipelineRunDao.getRunsByStatuses(any()) } throws IllegalStateException("io")

        assertNull(repository.getActiveRunForSession("session-1"))
        assertEquals(emptyList<PipelineRun>(), repository.getOrphanedRuns())
    }

    @Test
    fun `given corrupt status string when reading then degrades to null instead of crashing`() = runTest {
        coEvery { pipelineRunDao.getActiveRunForSession("session-1", any()) } returns
            sampleEntity.copy(status = "NOT_A_STATUS")

        assertNull(repository.getActiveRunForSession("session-1"))
    }

    @Test
    fun `given failing upstream when observeRunsForSession then emits empty list`() = runTest {
        coEvery { pipelineRunDao.observeRunsForSession("session-1") } returns flow {
            throw IllegalStateException("io")
        }

        assertEquals(emptyList<PipelineRun>(), repository.observeRunsForSession("session-1").first())
    }

    // endregion

    // region Checkpoint resume

    @Test
    fun `given entity when getRun then maps to domain including userPrompt`() = runTest {
        coEvery { pipelineRunDao.getRun("run-1") } returns sampleEntity.copy(userPrompt = "original prompt")

        val run = repository.getRun("run-1")

        assertEquals("run-1", run?.id)
        assertEquals(RunOrigin.SCHEDULER, run?.origin)
        assertEquals(PipelineRunStatus.WAITING_APPROVAL, run?.status)
        assertEquals("original prompt", run?.userPrompt)
    }

    @Test
    fun `given missing row when getRun then returns null`() = runTest {
        coEvery { pipelineRunDao.getRun("run-1") } returns null

        assertNull(repository.getRun("run-1"))
    }

    @Test
    fun `given userPrompt on createRun then entity carries it`() = runTest {
        val captured = slot<PipelineRunEntity>()
        coEvery { pipelineRunDao.insertRun(capture(captured)) } returns Unit

        repository.createRun(sampleRun.copy(userPrompt = "ask the agent"))

        assertEquals("ask the agent", captured.captured.userPrompt)
    }

    @Test
    fun `markResumed issues the guarded INTERRUPTED to QUEUED transition and reports success`() = runTest {
        coEvery { pipelineRunDao.markResumed("run-1", "INTERRUPTED", "QUEUED") } returns 1

        assertTrue(repository.markResumed("run-1", PipelineRunStatus.INTERRUPTED))
        coVerify { pipelineRunDao.markResumed("run-1", "INTERRUPTED", "QUEUED") }
    }

    @Test
    fun `given row not INTERRUPTED when markResumed then reports failure`() = runTest {
        coEvery { pipelineRunDao.markResumed(any(), any(), any()) } returns 0

        assertTrue(!repository.markResumed("run-1", PipelineRunStatus.INTERRUPTED))
    }

    @Test
    fun `given DAO failure when markResumed then absorbed as failure`() = runTest {
        coEvery { pipelineRunDao.markResumed(any(), any(), any()) } throws IllegalStateException("io")

        assertTrue(!repository.markResumed("run-1", PipelineRunStatus.INTERRUPTED))
    }

    @Test
    fun `markResumed from a WAITING status pins the guarded transition to it`() = runTest {
        coEvery { pipelineRunDao.markResumed("run-1", "WAITING_APPROVAL", "QUEUED") } returns 1

        assertTrue(repository.markResumed("run-1", PipelineRunStatus.WAITING_APPROVAL))
        coVerify { pipelineRunDao.markResumed("run-1", "WAITING_APPROVAL", "QUEUED") }
    }

    @Test
    fun `markResumed re-registers process ownership so the run is no orphan`() = runTest {
        coEvery { pipelineRunDao.markResumed(any(), any(), any()) } returns 1
        coEvery { pipelineRunDao.getRunsByStatuses(activeNames) } returns listOf(
            sampleEntity.copy(id = "run-1", status = "QUEUED"),
        )

        repository.markResumed("run-1", PipelineRunStatus.INTERRUPTED)

        assertEquals(emptyList<PipelineRun>(), repository.getOrphanedRuns())
    }

    // endregion

    // region Retention

    @Test
    fun `applyRetention passes terminal guard to both deletes and sums the counts`() = runTest {
        coEvery { pipelineRunDao.deleteTerminalRunsBeyondSessionLimit(20, terminalNames) } returns 3
        coEvery { pipelineRunDao.deleteTerminalRunsFinishedBefore(1_234L, terminalNames) } returns 2

        val deleted = repository.applyRetention(keepPerSession = 20, maxAgeCutoffEpochMs = 1_234L)

        assertEquals(5, deleted)
        coVerify(exactly = 1) { pipelineRunDao.deleteTerminalRunsBeyondSessionLimit(20, terminalNames) }
        coVerify(exactly = 1) { pipelineRunDao.deleteTerminalRunsFinishedBefore(1_234L, terminalNames) }
    }

    @Test
    fun `given DAO failure when applyRetention then absorbed as zero deletions`() = runTest {
        coEvery {
            pipelineRunDao.deleteTerminalRunsBeyondSessionLimit(any(), any())
        } throws IllegalStateException("disk full")

        assertEquals(0, repository.applyRetention(keepPerSession = 20, maxAgeCutoffEpochMs = 1_234L))
    }

    // endregion

    @Test
    fun `given a child run when createRun then parentRunId is persisted`() = runTest {
        val captured = slot<PipelineRunEntity>()
        coEvery { pipelineRunDao.insertRun(capture(captured)) } returns Unit

        repository.createRun(sampleRun.copy(id = "child", parentRunId = "root"))

        assertEquals("root", captured.captured.parentRunId)
    }

    @Test
    fun `given a run tree when getDescendantRuns then returns every descendant breadth-first`() = runTest {
        coEvery { pipelineRunDao.getChildRuns("root") } returns listOf(
            sampleEntity.copy(id = "a", parentRunId = "root"),
            sampleEntity.copy(id = "b", parentRunId = "root"),
        )
        coEvery { pipelineRunDao.getChildRuns("a") } returns listOf(
            sampleEntity.copy(id = "a1", parentRunId = "a"),
        )
        coEvery { pipelineRunDao.getChildRuns("b") } returns emptyList()
        coEvery { pipelineRunDao.getChildRuns("a1") } returns emptyList()

        val descendants = repository.getDescendantRuns("root").map { it.id }

        assertEquals(listOf("a", "b", "a1"), descendants)
    }

    @Test
    fun `given a nested run when getRootRunId then walks parentRunId up to the root`() = runTest {
        coEvery { pipelineRunDao.getRun("a1") } returns sampleEntity.copy(id = "a1", parentRunId = "a")
        coEvery { pipelineRunDao.getRun("a") } returns sampleEntity.copy(id = "a", parentRunId = "root")
        coEvery { pipelineRunDao.getRun("root") } returns sampleEntity.copy(id = "root", parentRunId = null)

        assertEquals("root", repository.getRootRunId("a1"))
    }

    @Test
    fun `given a top-level run when getRootRunId then returns itself`() = runTest {
        coEvery { pipelineRunDao.getRun("root") } returns sampleEntity.copy(id = "root", parentRunId = null)

        assertEquals("root", repository.getRootRunId("root"))
    }

    @Test
    fun `given a missing run when getRootRunId then null`() = runTest {
        coEvery { pipelineRunDao.getRun("ghost") } returns null

        assertNull(repository.getRootRunId("ghost"))
    }

    @Test
    fun `terminal flag covers exactly the four terminal statuses`() {
        val terminal = PipelineRunStatus.entries.filter { it.isTerminal }
        assertEquals(
            listOf(
                PipelineRunStatus.COMPLETED,
                PipelineRunStatus.FAILED,
                PipelineRunStatus.CANCELLED,
                PipelineRunStatus.INTERRUPTED,
            ),
            terminal,
        )
        assertTrue(PipelineRunStatus.entries.filterNot { it.isTerminal }.none { it.isTerminal })
    }

    @Test
    fun `given telemetry enabled when a root run finishes then its outcome is recorded`() = runTest {
        coEvery { usageTelemetry.isEnabled() } returns true
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { pipelineRunDao.getRun("run-1") } returns
            sampleEntity.copy(id = "run-1", pipelineId = "pipe-1", parentRunId = null)

        repository.finishRun("run-1", PipelineRunStatus.COMPLETED)

        coVerify(exactly = 1) {
            usageTelemetry.recordPipelineRunOutcome("pipe-1", PipelineRunStatus.COMPLETED, any())
        }
    }

    @Test
    fun `given a root run completes then the onboarding first-value marker is offered`() = runTest {
        coEvery { usageTelemetry.isEnabled() } returns true
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { pipelineRunDao.getRun("run-1") } returns
            sampleEntity.copy(id = "run-1", pipelineId = "pipe-1", parentRunId = null)

        repository.finishRun("run-1", PipelineRunStatus.COMPLETED)

        // The repository owns the attribution rules; the chokepoint only reports
        // a completed, root, pipeline-bound run.
        coVerify(exactly = 1) { usageTelemetry.recordOnboardingFirstValue("pipe-1", any()) }
    }

    @Test
    fun `given a root run fails then no onboarding first-value marker is offered`() = runTest {
        coEvery { usageTelemetry.isEnabled() } returns true
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { pipelineRunDao.getRun("run-1") } returns
            sampleEntity.copy(id = "run-1", pipelineId = "pipe-1", parentRunId = null)

        repository.finishRun("run-1", PipelineRunStatus.FAILED, errorMessage = "boom")

        // A failed run is not "first value" — only COMPLETED closes the metric.
        coVerify(exactly = 0) { usageTelemetry.recordOnboardingFirstValue(any(), any()) }
    }

    @Test
    fun `given a nested sub-pipeline run completes then no onboarding first-value marker is offered`() = runTest {
        coEvery { usageTelemetry.isEnabled() } returns true
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { pipelineRunDao.getRun("child-1") } returns
            sampleEntity.copy(id = "child-1", pipelineId = "pipe-1", parentRunId = "root-1")

        repository.finishRun("child-1", PipelineRunStatus.COMPLETED)

        coVerify(exactly = 0) { usageTelemetry.recordOnboardingFirstValue(any(), any()) }
    }

    @Test
    fun `given a nested sub-pipeline run finishes then it is not recorded`() = runTest {
        coEvery { usageTelemetry.isEnabled() } returns true
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { pipelineRunDao.getRun("child-1") } returns
            sampleEntity.copy(id = "child-1", pipelineId = "pipe-1", parentRunId = "root-1")

        repository.finishRun("child-1", PipelineRunStatus.COMPLETED)

        coVerify(exactly = 0) { usageTelemetry.recordPipelineRunOutcome(any(), any(), any()) }
    }

    // region The line a stopped run leaves in its chat

    @Test
    fun `given a terminal transition then the run's chat is told what happened`() = runTest {
        coEvery { usageTelemetry.isEnabled() } returns false
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { pipelineRunDao.getRunChatIdentity("run-1") } returns
            RunChatIdentityProjection(sessionId = "session-a", parentRunId = null)
        val reason = RunTerminationReason.StepCeiling(spent = 40, limit = 40)

        repository.finishRun("run-1", PipelineRunStatus.FAILED, "over the cap", reason)

        coVerify {
            runOutcomeAnnouncer.announce("session-a", PipelineRunStatus.FAILED, reason, "over the cap")
        }
    }

    @Test
    fun `given a duplicate finishRun that transitions no row then the chat is not told twice`() = runTest {
        // The reason this lives inside the `rowsTransitioned > 0` guard rather
        // than at the call sites. `ParkedRunResumer.failPark` is reachable both
        // from the user's response and from the expiry pass, so a second
        // settlement of one run is a real race — and outside the guard it would
        // put a second identical line in the conversation, where a duplicate is
        // visible rather than merely wasteful.
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 0

        repository.finishRun("run-1", PipelineRunStatus.FAILED, "boom")

        coVerify(exactly = 0) { pipelineRunDao.getRunChatIdentity(any()) }
        coVerify(exactly = 0) { runOutcomeAnnouncer.announce(any(), any(), any(), any()) }
    }

    @Test
    fun `given a nested sub-pipeline child finishes then its chat is not told`() = runTest {
        // A child shares its parent's session and its failure already reaches
        // the user as the root's, so announcing here would print the same stop
        // once per nesting level.
        coEvery { usageTelemetry.isEnabled() } returns false
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { pipelineRunDao.getRunChatIdentity("child-1") } returns
            RunChatIdentityProjection(sessionId = "session-a", parentRunId = "root-1")

        repository.finishRun("child-1", PipelineRunStatus.FAILED, "child blew up")

        coVerify(exactly = 0) { runOutcomeAnnouncer.announce(any(), any(), any(), any()) }
    }

    @Test
    fun `given the run row cannot be read then finishing it still succeeds`() = runTest {
        coEvery { usageTelemetry.isEnabled() } returns false
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { pipelineRunDao.getRunChatIdentity("run-1") } returns null

        repository.finishRun("run-1", PipelineRunStatus.FAILED, "boom")

        coVerify(exactly = 0) { runOutcomeAnnouncer.announce(any(), any(), any(), any()) }
    }

    // endregion

    @Test
    fun `given a duplicate finishRun that transitions no row then telemetry is not recorded`() = runTest {
        coEvery { usageTelemetry.isEnabled() } returns true
        // The run is already terminal: the guarded UPDATE matches no row.
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 0

        repository.finishRun("run-1", PipelineRunStatus.COMPLETED)

        coVerify(exactly = 0) { pipelineRunDao.getRun(any()) }
        coVerify(exactly = 0) { usageTelemetry.recordPipelineRunOutcome(any(), any(), any()) }
    }

    @Test
    fun `given a run that never resolved a pipeline finishes then it is not recorded`() = runTest {
        coEvery { usageTelemetry.isEnabled() } returns true
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
        // A queued run reaped as INTERRUPTED by the orphan sweep: pipelineId never resolved.
        coEvery { pipelineRunDao.getRun("orphan-1") } returns
            sampleEntity.copy(id = "orphan-1", pipelineId = null, parentRunId = null)

        repository.finishRun("orphan-1", PipelineRunStatus.INTERRUPTED)

        coVerify(exactly = 0) { usageTelemetry.recordPipelineRunOutcome(any(), any(), any()) }
    }

    @Test
    fun `given telemetry disabled when a run finishes then the run is not read back for telemetry`() = runTest {
        // isEnabled() is stubbed false in setup; the run should never be re-read.
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
        repository.finishRun("run-1", PipelineRunStatus.COMPLETED)

        coVerify(exactly = 0) { pipelineRunDao.getRun(any()) }
        coVerify(exactly = 0) { usageTelemetry.recordPipelineRunOutcome(any(), any(), any()) }
    }

    @Test
    fun `given a completed trigger run finishes then Success is attributed to its trigger-journal row`() = runTest {
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { pipelineRunDao.getRunOrigin("run-1") } returns RunOrigin.TRIGGER.name

        repository.finishRun("run-1", PipelineRunStatus.COMPLETED)

        coVerify(exactly = 1) { triggerJournal.recordRunOutcome("run-1", TriggerRunOutcome.Success) }
    }

    @Test
    fun `given a failed trigger run finishes then a typed Failure carrying the message is attributed`() = runTest {
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { pipelineRunDao.getRunOrigin("run-1") } returns RunOrigin.TRIGGER.name

        repository.finishRun("run-1", PipelineRunStatus.FAILED, "boom")

        coVerify(exactly = 1) { triggerJournal.recordRunOutcome("run-1", TriggerRunOutcome.Failure("boom")) }
    }

    @Test
    fun `given a user-cancelled trigger run finishes then a deliberate Cancelled is attributed, not a platform kill`() =
        runTest {
            coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { pipelineRunDao.getRunOrigin("run-1") } returns RunOrigin.TRIGGER.name

            repository.finishRun("run-1", PipelineRunStatus.CANCELLED)

            coVerify(exactly = 1) { triggerJournal.recordRunOutcome("run-1", TriggerRunOutcome.Cancelled) }
        }

    @Test
    fun `given an orphaned trigger run reaped as INTERRUPTED then CancelledBySystem is attributed`() = runTest {
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { pipelineRunDao.getRunOrigin("run-1") } returns RunOrigin.TRIGGER.name

        repository.finishRun("run-1", PipelineRunStatus.INTERRUPTED, "Owning process died")

        coVerify(exactly = 1) { triggerJournal.recordRunOutcome("run-1", TriggerRunOutcome.CancelledBySystem) }
    }

    // --- External-automation runs -------------------------------------------

    /** A journal row for an admitted external request, as the hook will find it. */
    private fun externalEntry(
        runId: String = "run-1",
        requestId: String = "req-1",
        declaredReturnPackage: String? = "com.example.caller",
        returnAction: String = ExternalAutomationContract.ACTION_RUN_RESULT,
    ) = ExternalAutomationJournalEntry(
        id = "j-1",
        requestId = requestId,
        receivedAt = 1L,
        action = ExternalAutomationContract.ACTION_RUN_PIPELINE,
        target = null,
        declaredReturnPackage = declaredReturnPackage,
        returnAction = returnAction,
        attestedSenderPackage = null,
        status = ExternalAutomationStatus.Accepted,
        runId = runId,
        repeatCount = 1,
    )

    @Test
    fun `given a completed external run finishes then Completed is settled and the caller is told`() = runTest {
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { pipelineRunDao.getRunOrigin("run-1") } returns RunOrigin.EXTERNAL.name
        coEvery { externalAutomationJournal.findByRunId("run-1") } returns externalEntry()

        repository.finishRun("run-1", PipelineRunStatus.COMPLETED)

        coVerify(exactly = 1) {
            externalAutomationJournal.recordOutcome("run-1", ExternalAutomationStatus.Completed)
        }
        coVerify(exactly = 1) {
            externalAutomationCallback.notifyOutcome(
                returnPackage = "com.example.caller",
                returnAction = ExternalAutomationContract.ACTION_RUN_RESULT,
                requestId = "req-1",
                status = ExternalAutomationStatus.Completed,
            )
        }
    }

    @Test
    fun `given a cancelled external run finishes then the caller is told Failed, not silence`() = runTest {
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { pipelineRunDao.getRunOrigin("run-1") } returns RunOrigin.EXTERNAL.name
        coEvery { externalAutomationJournal.findByRunId("run-1") } returns externalEntry()

        // The contract publishes one settled failure status. A caller in another
        // process can act on exactly one bit: did the thing I asked for happen.
        repository.finishRun("run-1", PipelineRunStatus.CANCELLED)

        coVerify(exactly = 1) {
            externalAutomationCallback.notifyOutcome(any(), any(), any(), ExternalAutomationStatus.Failed)
        }
    }

    @Test
    fun `given a fire-and-forget external request when its run finishes then the row settles without a callback`() =
        runTest {
            coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { pipelineRunDao.getRunOrigin("run-1") } returns RunOrigin.EXTERNAL.name
            coEvery { externalAutomationJournal.findByRunId("run-1") } returns
                externalEntry(declaredReturnPackage = null)

            repository.finishRun("run-1", PipelineRunStatus.COMPLETED)

            // The journal is for the user, the callback is for the caller.
            coVerify(exactly = 1) {
                externalAutomationJournal.recordOutcome("run-1", ExternalAutomationStatus.Completed)
            }
            coVerify(exactly = 0) { externalAutomationCallback.notifyOutcome(any(), any(), any(), any()) }
        }

    @Test
    fun `given a nested child of an external run finishes then no second callback is sent`() = runTest {
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
        // A sub-pipeline child inherits its parent's origin but owns no journal row.
        coEvery { pipelineRunDao.getRunOrigin("child-run") } returns RunOrigin.EXTERNAL.name
        coEvery { externalAutomationJournal.findByRunId("child-run") } returns null

        repository.finishRun("child-run", PipelineRunStatus.COMPLETED)

        coVerify(exactly = 0) { externalAutomationJournal.recordOutcome(any(), any()) }
        coVerify(exactly = 0) { externalAutomationCallback.notifyOutcome(any(), any(), any(), any()) }
    }

    @Test
    fun `given a run of another origin finishes then the external journal is never consulted`() = runTest {
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { pipelineRunDao.getRunOrigin("run-1") } returns RunOrigin.CHAT.name

        repository.finishRun("run-1", PipelineRunStatus.COMPLETED)

        coVerify(exactly = 0) { externalAutomationJournal.findByRunId(any()) }
        coVerify(exactly = 0) { triggerJournal.recordRunOutcome(any(), any()) }
    }

    @Test
    fun `given an interrupted external run that is resumed and completes then only the first outcome is reported`() =
        runTest {
            coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
            coEvery { pipelineRunDao.getRunOrigin("run-1") } returns RunOrigin.EXTERNAL.name
            coEvery { externalAutomationJournal.findByRunId("run-1") } returns externalEntry()
            // First settlement wins; the second is refused by the journal.
            coEvery {
                externalAutomationJournal.recordOutcome("run-1", ExternalAutomationStatus.Failed)
            } returns true
            coEvery {
                externalAutomationJournal.recordOutcome("run-1", ExternalAutomationStatus.Completed)
            } returns false

            // INTERRUPTED is terminal AND resumable: the orphan sweep settles the run,
            // the user resumes it from the chat, and it finishes for real.
            repository.finishRun("run-1", PipelineRunStatus.INTERRUPTED)
            repository.finishRun("run-1", PipelineRunStatus.COMPLETED)

            // Exactly one callback, and it is the one the caller already acted on.
            coVerify(exactly = 1) {
                externalAutomationCallback.notifyOutcome(any(), any(), any(), any())
            }
            coVerify(exactly = 1) {
                externalAutomationCallback.notifyOutcome(any(), any(), any(), ExternalAutomationStatus.Failed)
            }
        }

    @Test
    fun `given a racing duplicate finish of an external run then no callback is sent twice`() = runTest {
        // The DAO update carries a `status NOT IN (terminal)` clause, so a racing
        // second finish transitions zero rows — and must not re-notify the caller.
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 0
        coEvery { pipelineRunDao.getRunOrigin("run-1") } returns RunOrigin.EXTERNAL.name
        coEvery { externalAutomationJournal.findByRunId("run-1") } returns externalEntry()

        repository.finishRun("run-1", PipelineRunStatus.COMPLETED)

        coVerify(exactly = 0) { externalAutomationCallback.notifyOutcome(any(), any(), any(), any()) }
    }

    @Test
    fun `given a trigger run failed by an expired background approval then HitlTimeout is attributed`() = runTest {
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { pipelineRunDao.getRunOrigin("run-1") } returns RunOrigin.TRIGGER.name

        // The typed reason the park-expiry settlement stamps on the run. The
        // message travels alongside it but no longer decides anything.
        repository.finishRun(
            "run-1",
            PipelineRunStatus.FAILED,
            ParkedRunResumer.APPROVAL_WINDOW_EXPIRED_MESSAGE,
            RunTerminationReason.HitlWindowExpired,
        )

        coVerify(exactly = 1) { triggerJournal.recordRunOutcome("run-1", TriggerRunOutcome.HitlTimeout) }
    }

    @Test
    fun `given a trigger run stopped by a ceiling then StoppedByCeiling is attributed, not Failure`() = runTest {
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { pipelineRunDao.getRunOrigin("run-1") } returns RunOrigin.TRIGGER.name

        repository.finishRun(
            "run-1",
            PipelineRunStatus.FAILED,
            "Pipeline execution exceeded the maximum of 15 steps shared across the pipeline tree.",
            RunTerminationReason.StepCeiling(limit = 15, spent = 15),
        )

        // A working safety limit is not a trigger defect; reporting it as a
        // Failure would redden the trigger's health badge for correct behaviour.
        coVerify(exactly = 1) { triggerJournal.recordRunOutcome("run-1", TriggerRunOutcome.StoppedByCeiling) }
    }

    @Test
    fun `given a ceiling stop then the typed reason is written to the run row`() = runTest {
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { pipelineRunDao.getRunOrigin(any()) } returns RunOrigin.CHAT.name

        repository.finishRun(
            "run-1",
            PipelineRunStatus.FAILED,
            "over budget",
            RunTerminationReason.TokenCeiling(limit = 100, spent = 140),
        )

        coVerify {
            pipelineRunDao.finishRun(
                runId = "run-1",
                status = "FAILED",
                finishedAt = any(),
                errorMessage = "over budget",
                terminationReason = "TOKEN_CEILING",
                terminalStatuses = any(),
            )
        }
    }

    @Test
    fun `given spend is recorded then the root row is written under the terminal guard`() = runTest {
        repository.recordSpend("root-1", stepsSpent = 7, tokensSpent = 4_200)

        coVerify {
            pipelineRunDao.recordSpend(
                rootRunId = "root-1",
                stepsSpent = 7,
                tokensSpent = 4_200,
                terminalStatuses = any(),
            )
        }
    }

    @Test
    fun `given no row when spend is read then it degrades to zero rather than failing the run`() = runTest {
        coEvery { pipelineRunDao.getSpend("missing") } returns null

        assertEquals(RunSpend(steps = 0, tokens = 0), repository.getSpend("missing"))
    }

    @Test
    fun `given a stored spend then it round-trips for the resume seed`() = runTest {
        coEvery { pipelineRunDao.getSpend("root-1") } returns RunSpendProjection(
            stepsSpent = 9,
            tokensSpent = 900,
            stepCeilingExtensions = 0,
            tokenCeilingExtensions = 0,
        )

        assertEquals(RunSpend(steps = 9, tokens = 900), repository.getSpend("root-1"))
    }

    @Test
    fun `given the store fails when spend is read then it degrades to zero`() = runTest {
        // Best-effort contract: losing the read makes the ceiling bind late,
        // which is safe. Refusing to run would not be.
        coEvery { pipelineRunDao.getSpend(any()) } throws IllegalStateException("disk full")

        assertEquals(RunSpend(), repository.getSpend("root-1"))
    }

    @Test
    fun `given a non-trigger run finishes then the journal is never touched`() = runTest {
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { pipelineRunDao.getRunOrigin("run-1") } returns RunOrigin.CHAT.name

        repository.finishRun("run-1", PipelineRunStatus.COMPLETED)

        // Only trigger-origin runs have a journal row, so a chat run skips the write entirely.
        coVerify(exactly = 0) { triggerJournal.recordRunOutcome(any(), any()) }
    }

    @Test
    fun `given a duplicate finishRun that transitions no row then no outcome is attributed`() = runTest {
        // Already terminal: the guarded UPDATE matches no row, so a racing write
        // must never overwrite the outcome already on the journal entry — and the
        // origin is not even read.
        coEvery { pipelineRunDao.finishRun(any(), any(), any(), any(), any(), any()) } returns 0

        repository.finishRun("run-1", PipelineRunStatus.COMPLETED)

        coVerify(exactly = 0) { pipelineRunDao.getRunOrigin(any()) }
        coVerify(exactly = 0) { triggerJournal.recordRunOutcome(any(), any()) }
    }
}
