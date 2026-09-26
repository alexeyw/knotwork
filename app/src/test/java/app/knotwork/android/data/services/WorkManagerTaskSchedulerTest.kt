package app.knotwork.android.data.services

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.repositories.BackgroundPromptRepository
import app.knotwork.android.domain.services.ScheduledTaskConstraints
import app.knotwork.android.domain.services.ScheduledTaskKind
import app.knotwork.android.domain.services.ScheduledTaskLabel
import app.knotwork.android.domain.services.ScheduledTaskTag
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Unit tests for [WorkManagerTaskScheduler].
 *
 * The property this suite exists for: **nothing the background runtime stores
 * carries a prompt.** `WorkManager` keeps each request's input, tags and unique
 * name in its own unencrypted database; the prompt goes to the encrypted
 * [BackgroundPromptRepository] and the request carries only its id.
 */
class WorkManagerTaskSchedulerTest {

    /** An in-memory prompt store, so a test can read back what the scheduler stored. */
    private class FakePrompts : BackgroundPromptRepository {
        val stored = MutableStateFlow<Map<String, String>>(emptyMap())

        override suspend fun store(id: String, prompt: String) {
            stored.value += (id to prompt)
        }

        override suspend fun get(id: String): String? = stored.value[id]

        override suspend fun delete(id: String) {
            stored.value -= id
        }

        var lastCutoff: Long? = null

        override suspend fun retainOnly(ids: Set<String>, storedBefore: Long): Int {
            lastCutoff = storedBefore
            val orphans = stored.value.keys - ids
            stored.value -= orphans
            return orphans.size
        }

        override fun observeAll(): Flow<Map<String, String>> = stored
    }

    private lateinit var workManager: WorkManager
    private lateinit var prompts: FakePrompts
    private lateinit var scheduler: WorkManagerTaskScheduler

    private val constraints = ScheduledTaskConstraints(requiresBatteryNotLow = true)

    @Before
    fun setup() {
        workManager = mockk()
        every { workManager.enqueue(any<OneTimeWorkRequest>()) } returns mockk()
        every {
            workManager.enqueueUniquePeriodicWork(
                any(),
                any(),
                any<PeriodicWorkRequest>(),
            )
        } returns mockk()
        prompts = FakePrompts()
        scheduler = WorkManagerTaskScheduler(workManager, prompts)
    }

    /** The prompt a request's input points at, read back from the store. */
    private fun storedPromptOf(request: WorkRequest): String? =
        request.workSpec.input.getString(AgentWorker.KEY_PROMPT_ID)?.let(prompts.stored.value::get)

    /** An operation that has already succeeded, as `await()` expects. */
    private fun doneOperation(): Operation {
        val future = mockk<ListenableFuture<Operation.State.SUCCESS>> {
            every { isDone } returns true
            every { get() } returns Operation.SUCCESS
        }
        return mockk { every { result } returns future }
    }

    // --- The prompt stays out of the runtime -------------------------------------

    @Test
    fun `given a scheduled prompt when enqueued then no field the runtime persists carries it`() = runTest {
        val prompt = "email the quarterly numbers to finance@example.com"
        val oneTime = slot<OneTimeWorkRequest>()
        val periodic = slot<PeriodicWorkRequest>()
        val name = slot<String>()

        scheduler.scheduleOneTime(prompt, delayMinutes = 0, sessionId = "s-1", constraints = constraints)
        scheduler.schedulePeriodic(prompt, intervalHours = 2, sessionId = "s-1", constraints = constraints)

        verify { workManager.enqueue(capture(oneTime)) }
        verify { workManager.enqueueUniquePeriodicWork(capture(name), any(), capture(periodic)) }
        for (request in listOf(oneTime.captured, periodic.captured)) {
            val persisted = request.workSpec.input.keyValueMap.values.map { it.toString() } + request.tags
            assertTrue(persisted.toString(), persisted.none { "quarterly" in it || "finance@" in it })
            assertEquals(prompt, storedPromptOf(request))
        }
        assertFalse(name.captured, "quarterly" in name.captured)
    }

    @Test
    fun `given positive interval when schedulePeriodic then enqueues unique periodic work kept on repeat`() = runTest {
        val policySlot = slot<ExistingPeriodicWorkPolicy>()
        val requestSlot = slot<PeriodicWorkRequest>()

        scheduler.schedulePeriodic("check emails", intervalHours = 2, sessionId = null, constraints = constraints)

        verify {
            workManager.enqueueUniquePeriodicWork(any(), capture(policySlot), capture(requestSlot))
        }
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, policySlot.captured)
        // The periodic request is built at the requested cadence (hours), points at
        // the prompt, and maps the battery constraint — parity with the one-time path.
        assertEquals(2L * 60L * 60L * 1000L, requestSlot.captured.workSpec.intervalDuration)
        assertEquals("check emails", storedPromptOf(requestSlot.captured))
        assertTrue(requestSlot.captured.workSpec.input.getBoolean(AgentWorker.KEY_PROMPT_REUSED, false))
        assertTrue(requestSlot.captured.workSpec.constraints.requiresBatteryNotLow())
    }

    @Test
    fun `given same prompt and interval but different sessions when schedulePeriodic then unique names differ`() =
        runTest {
            val names = mutableListOf<String>()

            scheduler.schedulePeriodic(
                "check emails",
                intervalHours = 2,
                sessionId = "session-a",
                constraints = constraints,
            )
            scheduler.schedulePeriodic(
                "check emails",
                intervalHours = 2,
                sessionId = "session-b",
                constraints = constraints,
            )

            verify(exactly = 2) {
                workManager.enqueueUniquePeriodicWork(capture(names), any(), any<PeriodicWorkRequest>())
            }
            // Schedules bound to different sessions must not collapse onto each other:
            // each session keeps its own recurring task so results land where intended.
            assertNotEquals(names[0], names[1])
        }

    @Test
    fun `given the same recurring task scheduled twice when schedulePeriodic then one name and one stored prompt`() =
        runTest {
            val names = mutableListOf<String>()

            scheduler.schedulePeriodic(
                "check emails ",
                intervalHours = 2,
                sessionId = "session-a",
                constraints = constraints,
            )
            scheduler.schedulePeriodic(
                "check emails",
                intervalHours = 2,
                sessionId = "session-a",
                constraints = constraints,
            )

            verify(exactly = 2) {
                workManager.enqueueUniquePeriodicWork(capture(names), any(), any<PeriodicWorkRequest>())
            }
            // A stray trailing space must not slip past the de-dup and stack a
            // duplicate, and the second call must not leave a second stored copy
            // behind the schedule `KEEP` preserves.
            assertEquals(names[0], names[1])
            assertEquals(1, prompts.stored.value.size)
        }

    @Test
    fun `given prompt with surrounding whitespace when schedulePeriodic then the stored prompt is verbatim`() =
        runTest {
            val requestSlot = slot<PeriodicWorkRequest>()

            scheduler.schedulePeriodic(
                "check emails ",
                intervalHours = 2,
                sessionId = "session-a",
                constraints = constraints,
            )

            verify { workManager.enqueueUniquePeriodicWork(any(), any(), capture(requestSlot)) }
            // Trimming is a de-dup-key concern only; the worker still runs the exact prompt.
            assertEquals("check emails ", storedPromptOf(requestSlot.captured))
        }

    @Test
    fun `given a legacy recurring task when migrated then it waits one interval before its first run`() = runTest {
        val requestSlot = slot<PeriodicWorkRequest>()

        scheduler.migrateLegacyPeriodic("check emails", intervalHours = 6, sessionId = null)

        verify { workManager.enqueueUniquePeriodicWork(any(), any(), capture(requestSlot)) }
        // The legacy request is running when this is called: a replacement due at
        // once would run the task twice.
        assertEquals(6L * 60L * 60L * 1000L, requestSlot.captured.workSpec.initialDelay)
        assertEquals("check emails", storedPromptOf(requestSlot.captured))
    }

    // --- One-time requests -------------------------------------------------------

    @Test
    fun `given delay when scheduleOneTime then enqueues one-time work with the initial delay`() = runTest {
        val requestSlot = slot<OneTimeWorkRequest>()

        scheduler.scheduleOneTime("check emails once", delayMinutes = 10, sessionId = null, constraints = constraints)

        verify { workManager.enqueue(capture(requestSlot)) }
        assertEquals(10L * 60L * 1000L, requestSlot.captured.workSpec.initialDelay)
    }

    @Test
    fun `given no delay when scheduleOneTime then enqueues one-time work without an initial delay`() = runTest {
        val requestSlot = slot<OneTimeWorkRequest>()

        scheduler.scheduleOneTime("run now", delayMinutes = 0, sessionId = null, constraints = constraints)

        verify { workManager.enqueue(capture(requestSlot)) }
        assertEquals(0L, requestSlot.captured.workSpec.initialDelay)
    }

    @Test
    fun `given session id when scheduleOneTime then carries the prompt id and session id into input data`() = runTest {
        val requestSlot = slot<OneTimeWorkRequest>()

        scheduler.scheduleOneTime(
            "check emails once",
            delayMinutes = 0,
            sessionId = "session-7",
            constraints = constraints,
        )

        verify { workManager.enqueue(capture(requestSlot)) }
        assertEquals("session-7", requestSlot.captured.workSpec.input.getString(AgentWorker.KEY_SESSION_ID))
        assertEquals("check emails once", storedPromptOf(requestSlot.captured))
        // A one-time run's prompt is its own: the worker drops it once enqueued.
        assertFalse(requestSlot.captured.workSpec.input.getBoolean(AgentWorker.KEY_PROMPT_REUSED, true))
    }

    @Test
    fun `given session id when schedulePeriodic then carries it into input data`() = runTest {
        val requestSlot = slot<PeriodicWorkRequest>()

        scheduler.schedulePeriodic(
            "check emails",
            intervalHours = 2,
            sessionId = "session-7",
            constraints = constraints,
        )

        verify { workManager.enqueueUniquePeriodicWork(any(), any(), capture(requestSlot)) }
        assertEquals("session-7", requestSlot.captured.workSpec.input.getString(AgentWorker.KEY_SESSION_ID))
    }

    @Test
    fun `given no session id when scheduleOneTime then leaves the session key absent`() = runTest {
        val requestSlot = slot<OneTimeWorkRequest>()

        scheduler.scheduleOneTime("check emails once", delayMinutes = 0, sessionId = null, constraints = constraints)

        verify { workManager.enqueue(capture(requestSlot)) }
        assertNull(requestSlot.captured.workSpec.input.getString(AgentWorker.KEY_SESSION_ID))
    }

    @Test
    fun `given a pre-minted run id when scheduleOneTime then carries it into input data`() = runTest {
        val requestSlot = slot<OneTimeWorkRequest>()

        scheduler.scheduleOneTime(
            "evening journal",
            delayMinutes = 0,
            sessionId = "session-7",
            constraints = constraints,
            runId = "trigger-run-1",
        )

        verify { workManager.enqueue(capture(requestSlot)) }
        // The id survives into input data so AgentWorker can reuse it verbatim as
        // the run id the trigger's journal row already references.
        assertEquals("trigger-run-1", requestSlot.captured.workSpec.input.getString(AgentWorker.KEY_RUN_ID))
    }

    @Test
    fun `given no run id when scheduleOneTime then leaves the run-id key absent`() = runTest {
        val requestSlot = slot<OneTimeWorkRequest>()

        scheduler.scheduleOneTime("check emails once", delayMinutes = 0, sessionId = null, constraints = constraints)

        verify { workManager.enqueue(capture(requestSlot)) }
        assertNull(requestSlot.captured.workSpec.input.getString(AgentWorker.KEY_RUN_ID))
    }

    @Test
    fun `given battery-not-low constraint when scheduleOneTime then maps it onto the work constraints`() = runTest {
        val requestSlot = slot<OneTimeWorkRequest>()

        scheduler.scheduleOneTime("check emails once", delayMinutes = 0, sessionId = null, constraints = constraints)

        verify { workManager.enqueue(capture(requestSlot)) }
        assertTrue(requestSlot.captured.workSpec.constraints.requiresBatteryNotLow())
        // Other constraints stay at their permissive defaults.
        assertEquals(NetworkType.NOT_REQUIRED, requestSlot.captured.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `given battery constraint disabled when scheduleOneTime then maps false onto the work constraints`() = runTest {
        val requestSlot = slot<OneTimeWorkRequest>()

        scheduler.scheduleOneTime(
            "check emails once",
            delayMinutes = 0,
            sessionId = null,
            constraints = ScheduledTaskConstraints(requiresBatteryNotLow = false),
        )

        verify { workManager.enqueue(capture(requestSlot)) }
        assertTrue(!requestSlot.captured.workSpec.constraints.requiresBatteryNotLow())
    }

    // --- Tagging, bulk cancellation, pruning -----------------------------------

    @Test
    fun `given a one-time task when scheduled then it carries the marker and a label naming its prompt`() = runTest {
        val requestSlot = slot<OneTimeWorkRequest>()

        scheduler.scheduleOneTime("check emails once", delayMinutes = 0, sessionId = "s-1", constraints = constraints)

        verify { workManager.enqueue(capture(requestSlot)) }
        val tags = requestSlot.captured.tags
        // The marker scopes bulk cancellation; the label is the only thing the
        // monitor can read, since a queued task's input data is not readable.
        assertTrue(ScheduledTaskTag.MARKER in tags)
        assertEquals(
            ScheduledTaskLabel(
                kind = ScheduledTaskKind.ONE_TIME,
                intervalHours = 0,
                sessionId = "s-1",
                promptId = requestSlot.captured.workSpec.input.getString(AgentWorker.KEY_PROMPT_ID),
            ),
            ScheduledTaskTag.parse(tags),
        )
    }

    @Test
    fun `given a trigger or tile origin when scheduled then the task carries no scheduled marker`() = runTest {
        val requestSlot = mutableListOf<OneTimeWorkRequest>()

        scheduler.scheduleOneTime("fire", 0, null, constraints, origin = RunOrigin.TRIGGER)
        scheduler.scheduleOneTime("tile", 0, null, constraints, origin = RunOrigin.QUICK_TILE)

        verify(exactly = 2) { workManager.enqueue(capture(requestSlot)) }
        // Both reach the same method as the scheduling tool. Marking them would
        // let "stop all scheduled tasks" kill an automation the user never asked
        // to stop. Their prompts are stored and tagged by id all the same.
        requestSlot.forEach { request ->
            assertTrue(ScheduledTaskTag.MARKER !in request.tags)
            assertNull(ScheduledTaskTag.parse(request.tags))
            assertTrue(request.tags.any { WorkManagerTaskScheduler.promptIdOf(it) != null })
        }
    }

    @Test
    fun `given a periodic task when scheduled then its label carries the interval`() = runTest {
        val requestSlot = slot<PeriodicWorkRequest>()

        scheduler.schedulePeriodic("check emails", intervalHours = 6, sessionId = null, constraints = constraints)

        verify { workManager.enqueueUniquePeriodicWork(any(), any(), capture(requestSlot)) }
        val label = ScheduledTaskTag.parse(requestSlot.captured.tags)
        assertEquals(ScheduledTaskKind.PERIODIC, label?.kind)
        assertEquals(6L, label?.intervalHours)
        assertTrue(ScheduledTaskTag.MARKER in requestSlot.captured.tags)
    }

    @Test
    fun `when cancelAllScheduled then only work carrying the scheduled marker is cancelled`() {
        every { workManager.cancelAllWorkByTag(any()) } returns mockk()

        scheduler.cancelAllScheduled()

        // Scoped by the marker on purpose: automation triggers, the tile and
        // model downloads run on the same runtime and must survive this.
        verify(exactly = 1) { workManager.cancelAllWorkByTag(ScheduledTaskTag.MARKER) }
    }

    @Test
    fun `when every background run is cancelled then all agent work goes and finished rows are pruned`() = runTest {
        every { workManager.cancelAllWorkByTag(any()) } returns doneOperation()
        every { workManager.pruneWork() } returns doneOperation()

        scheduler.cancelAllBackgroundRuns()

        // By the worker's class tag, which the runtime adds to every request —
        // including ones an earlier release enqueued without a tag of its own.
        verifyOrder {
            workManager.cancelAllWorkByTag(AgentWorker::class.java.name)
            workManager.pruneWork()
        }
    }

    @Test
    fun `given prompts of finished and cancelled requests when pruned then only unfinished requests keep theirs`() =
        runTest {
            prompts.store("live", "a")
            prompts.store("done", "b")
            prompts.store("orphan", "c")
            fun info(state: WorkInfo.State, promptId: String) =
                WorkInfo(UUID.randomUUID(), state, setOf(WorkManagerTaskScheduler.promptTag(promptId)))
            every { workManager.getWorkInfosByTagFlow(AgentWorker::class.java.name) } returns flowOf(
                listOf(info(WorkInfo.State.ENQUEUED, "live"), info(WorkInfo.State.SUCCEEDED, "done")),
            )

            val before = System.currentTimeMillis()
            val pruned = scheduler.pruneOrphanPrompts()

            assertEquals(2, pruned)
            assertEquals(setOf("live"), prompts.stored.value.keys)
            // A prompt stored a moment ago may belong to a request not enqueued yet.
            assertTrue(prompts.lastCutoff!! <= before - WorkManagerTaskScheduler.PRUNE_GRACE_MS + 1_000L)
        }
}
