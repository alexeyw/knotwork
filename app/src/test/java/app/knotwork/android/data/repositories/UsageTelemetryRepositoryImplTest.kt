package app.knotwork.android.data.repositories

import androidx.room.Room
import app.knotwork.android.data.local.AppDatabase
import app.knotwork.android.data.local.dao.UsageTelemetryDao
import app.knotwork.android.domain.models.OnboardingMilestone
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.usecases.CalculateUsageRetentionUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Verifies the on-device [UsageTelemetryRepositoryImpl] against a real in-memory
 * Room database: opt-in gating, per-pipeline / per-outcome / per-kind tallies,
 * the daily-active set, the write-once onboarding markers with their first-value
 * attribution rules, and reset.
 */
@RunWith(RobolectricTestRunner::class)
class UsageTelemetryRepositoryImplTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: UsageTelemetryDao
    private lateinit var settingsRepository: SettingsRepository

    // 2026-06-25T10:00:00Z in UTC → local day "2026-06-25".
    private val zone = ZoneId.of("UTC")
    private val day1 = Instant.parse("2026-06-25T10:00:00Z").toEpochMilli()
    private val day1Later = Instant.parse("2026-06-25T23:00:00Z").toEpochMilli()
    private val day2 = Instant.parse("2026-06-26T08:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.usageTelemetryDao()
        settingsRepository = mockk()
        every { settingsRepository.usageTelemetryEnabled } returns flowOf(true)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun repository(nowMillis: Long = day1): UsageTelemetryRepositoryImpl = UsageTelemetryRepositoryImpl(
        dao = dao,
        privacySettings = settingsRepository,
        calculateRetention = CalculateUsageRetentionUseCase(),
        clockProvider = { Clock.fixed(Instant.ofEpochMilli(nowMillis), zone) },
    ).apply { dispatcher = Dispatchers.Unconfined }

    @Test
    fun `given recording enabled when a root run finishes then it is tallied by pipeline outcome and day`() = runTest {
        val repository = repository()

        repository.recordPipelineRunOutcome("pipe-1", PipelineRunStatus.COMPLETED, day1)

        val summary = repository.summary.first()
        assertEquals(1, summary.totalRuns)
        assertEquals(1, summary.runsByOutcome[PipelineRunStatus.COMPLETED])
        assertEquals("pipe-1", summary.runsByPipeline.single().pipelineId)
        assertEquals(1, summary.runsByPipeline.single().runCount)
        assertEquals(1, summary.activeDays)
        assertEquals("2026-06-25", summary.firstActiveDay)
        assertEquals("2026-06-25", summary.lastActiveDay)
    }

    @Test
    fun `given recording disabled when a run finishes then nothing is tallied`() = runTest {
        every { settingsRepository.usageTelemetryEnabled } returns flowOf(false)
        val repository = repository()

        repository.recordPipelineRunOutcome("pipe-1", PipelineRunStatus.COMPLETED, day1)

        val summary = repository.summary.first()
        assertTrue(summary.isEmpty)
        assertEquals(0, summary.totalRuns)
    }

    @Test
    fun `given a non-terminal status when recorded then it is ignored`() = runTest {
        val repository = repository()

        repository.recordPipelineRunOutcome("pipe-1", PipelineRunStatus.RUNNING, day1)

        assertTrue(repository.summary.first().isEmpty)
    }

    @Test
    fun `given a run with no resolved pipeline when recorded then it tallies under a null pipeline id`() = runTest {
        val repository = repository()

        repository.recordPipelineRunOutcome(null, PipelineRunStatus.INTERRUPTED, day1)

        val summary = repository.summary.first()
        assertNull(summary.runsByPipeline.single().pipelineId)
        assertEquals(1, summary.runsByOutcome[PipelineRunStatus.INTERRUPTED])
    }

    @Test
    fun `given trigger firings when recorded then they tally by kind`() = runTest {
        val repository = repository()

        repository.recordTriggerFired("CHARGING", day1)
        repository.recordTriggerFired("CHARGING", day1)
        repository.recordTriggerFired("INTERVAL", day1)

        val summary = repository.summary.first()
        assertEquals(2, summary.triggerFiresByKind["CHARGING"])
        assertEquals(1, summary.triggerFiresByKind["INTERVAL"])
        assertEquals(3, summary.totalTriggerFires)
    }

    @Test
    fun `given events on the same day when recorded then the active-day set keeps one entry`() = runTest {
        val repository = repository()

        repository.recordPipelineRunOutcome("pipe-1", PipelineRunStatus.COMPLETED, day1)
        repository.recordPipelineRunOutcome("pipe-1", PipelineRunStatus.FAILED, day1Later)
        repository.recordTriggerFired("CHARGING", day2)

        val summary = repository.summary.first()
        assertEquals(2, summary.activeDays)
        assertEquals("2026-06-25", summary.firstActiveDay)
        assertEquals("2026-06-26", summary.lastActiveDay)
        assertEquals(2, summary.runsByPipeline.single().runCount)
    }

    @Test
    fun `given recorded statistics when reset then everything is cleared`() = runTest {
        val repository = repository()
        repository.recordPipelineRunOutcome("pipe-1", PipelineRunStatus.COMPLETED, day1)
        repository.recordTriggerFired("CHARGING", day1)
        repository.recordOnboardingMilestone(OnboardingMilestone.ONBOARDING_STARTED, day1)

        repository.reset()

        val summary = repository.summary.first()
        assertTrue(summary.isEmpty)
        assertTrue(summary.onboarding.isEmpty)
    }

    @Test
    fun `given onboarding markers when recorded then the journey aggregates them with its pipeline scope`() = runTest {
        val repository = repository()

        repository.recordOnboardingMilestone(OnboardingMilestone.ONBOARDING_STARTED, day1)
        repository.recordOnboardingMilestone(OnboardingMilestone.SCENARIO_CHOSEN, day1 + 5_000L, detail = "pipe-1")
        repository.recordOnboardingMilestone(OnboardingMilestone.MODEL_DOWNLOAD_STARTED, day1 + 10_000L)
        repository.recordOnboardingMilestone(OnboardingMilestone.MODEL_DOWNLOAD_FINISHED, day1 + 70_000L)
        repository.recordOnboardingFirstValue("pipe-1", day1 + 90_000L)

        val journey = repository.summary.first().onboarding
        assertEquals("pipe-1", journey.scenarioPipelineId)
        assertEquals(90_000L, journey.totalToValueMillis)
        assertEquals(60_000L, journey.modelDownloadMillis)
        assertEquals(30_000L, journey.productToValueMillis)
    }

    @Test
    fun `given only onboarding markers when read then the statistics still count as empty`() = runTest {
        // Opening onboarding must not swap the screen's "nothing recorded yet"
        // copy for a dashboard of zeros; markers alone are not usage.
        val repository = repository()

        repository.recordOnboardingMilestone(OnboardingMilestone.ONBOARDING_STARTED, day1)

        val summary = repository.summary.first()
        assertTrue(summary.isEmpty)
        assertFalse(summary.onboarding.isEmpty)
    }

    @Test
    fun `given a marker already recorded when recorded again then the first occurrence is kept`() = runTest {
        val repository = repository()

        repository.recordOnboardingMilestone(OnboardingMilestone.ONBOARDING_STARTED, day1)
        // A second pass through onboarding must not move the measured start.
        repository.recordOnboardingMilestone(OnboardingMilestone.ONBOARDING_STARTED, day2)

        val journey = repository.summary.first().onboarding
        assertEquals(day1, journey.startedAtMillis)
    }

    @Test
    fun `given recording disabled when onboarding markers are recorded then nothing is stored`() = runTest {
        every { settingsRepository.usageTelemetryEnabled } returns flowOf(false)
        val repository = repository()

        repository.recordOnboardingMilestone(OnboardingMilestone.ONBOARDING_STARTED, day1)
        repository.recordOnboardingFirstValue("pipe-1", day1 + 1_000L)

        assertTrue(repository.summary.first().onboarding.isEmpty)
    }

    @Test
    fun `given a scenario journey when another pipeline completes then first value is not recorded`() = runTest {
        val repository = repository()
        repository.recordOnboardingMilestone(OnboardingMilestone.ONBOARDING_STARTED, day1)
        repository.recordOnboardingMilestone(OnboardingMilestone.SCENARIO_CHOSEN, day1 + 5_000L, detail = "pipe-1")

        repository.recordOnboardingFirstValue("pipe-2", day1 + 20_000L)

        val journey = repository.summary.first().onboarding
        assertNull(journey.firstValueAtMillis)
        // …and the scenario's own run still closes the metric afterwards.
        repository.recordOnboardingFirstValue("pipe-1", day1 + 30_000L)
        assertEquals(day1 + 30_000L, repository.summary.first().onboarding.firstValueAtMillis)
    }

    @Test
    fun `given first value already recorded when a later run completes then the first one stands`() = runTest {
        val repository = repository()
        repository.recordOnboardingMilestone(OnboardingMilestone.ONBOARDING_STARTED, day1)

        repository.recordOnboardingFirstValue("pipe-1", day1 + 20_000L)
        repository.recordOnboardingFirstValue("pipe-1", day1 + 60_000L)

        assertEquals(day1 + 20_000L, repository.summary.first().onboarding.firstValueAtMillis)
    }

    @Test
    fun `given onboarding never started when a run completes then no first value is recorded`() = runTest {
        // Installs that upgraded into the markers have no measurable journey.
        val repository = repository()

        repository.recordOnboardingFirstValue("pipe-1", day1)

        assertTrue(repository.summary.first().onboarding.isEmpty)
    }

    @Test
    fun `given an unknown marker key in the store when aggregated then it is ignored`() = runTest {
        val repository = repository()
        repository.recordOnboardingMilestone(OnboardingMilestone.ONBOARDING_STARTED, day1)
        // A marker written by a future release must not break this one's screen.
        dao.recordMilestone("FUTURE_MARKER", day2, detail = null)

        val journey = repository.summary.first().onboarding
        assertEquals(1, journey.milestones.size)
        assertEquals(day1, journey.startedAtMillis)
    }

    @Test
    fun `given runs across days when read then the retention window reflects them`() = runTest {
        val repository = repository(nowMillis = day2)
        repository.recordPipelineRunOutcome("pipe-1", PipelineRunStatus.COMPLETED, day1)
        repository.recordPipelineRunOutcome("pipe-2", PipelineRunStatus.FAILED, day2)
        // Same pipeline twice in a day is one (day, pipeline) pair, not two.
        repository.recordPipelineRunOutcome("pipe-2", PipelineRunStatus.COMPLETED, day2)

        val retention = repository.summary.first().retention
        assertEquals(2, retention.activeDaysInWindow)
        assertEquals(0, retention.activeDaysInPreviousWindow)
        assertEquals(listOf("pipe-1", "pipe-2"), retention.livePipelineIds)
        assertEquals(2, retention.currentStreakDays)
        assertEquals(0, retention.returnsAfterBreak)
        assertNull(retention.firstWeekActiveDays)
    }

    @Test
    fun `given a run with no resolved pipeline when recorded then it makes no pipeline live`() = runTest {
        // An unattributable run is still usage (it marks the day active), but it
        // cannot make any particular pipeline count as alive this week.
        val repository = repository()

        repository.recordPipelineRunOutcome(null, PipelineRunStatus.INTERRUPTED, day1)

        val retention = repository.summary.first().retention
        assertEquals(1, retention.activeDaysInWindow)
        assertEquals(emptyList<String>(), retention.livePipelineIds)
        assertEquals(0, retention.livePipelinesInWindow)
    }

    @Test
    fun `given activity outside the window when read then it is excluded from the week`() = runTest {
        // 2026-06-25 is nine days before 2026-07-04, so it sits outside the
        // window but inside the previous one.
        val later = Instant.parse("2026-07-04T09:00:00Z").toEpochMilli()
        val repository = repository(nowMillis = later)
        repository.recordPipelineRunOutcome("pipe-1", PipelineRunStatus.COMPLETED, day1)
        repository.recordPipelineRunOutcome("pipe-2", PipelineRunStatus.COMPLETED, later)

        val retention = repository.summary.first().retention
        assertEquals(1, retention.activeDaysInWindow)
        assertEquals(1, retention.activeDaysInPreviousWindow)
        assertEquals(listOf("pipe-2"), retention.livePipelineIds)
        // Eight inactive days between the two runs — a break the user returned from.
        assertEquals(1, retention.returnsAfterBreak)
        assertEquals(8, retention.longestBreakDays)
        assertEquals(1, retention.firstWeekActiveDays)
    }

    @Test
    fun `given recorded pipeline days when reset then the per-day activity is cleared too`() = runTest {
        val repository = repository()
        repository.recordPipelineRunOutcome("pipe-1", PipelineRunStatus.COMPLETED, day1)

        repository.reset()

        val summary = repository.summary.first()
        assertEquals(0, summary.retention.livePipelinesInWindow)
        assertEquals(0, summary.retention.activeDaysInWindow)
    }

    @Test
    fun `given an unparseable active day in the store when aggregated then it is ignored`() = runTest {
        val repository = repository()
        repository.recordPipelineRunOutcome("pipe-1", PipelineRunStatus.COMPLETED, day1)
        // A day written in some future format must not take the screen down.
        dao.recordActiveDay("not-a-day")

        val summary = repository.summary.first()
        assertEquals(1, summary.activeDays)
        assertEquals("2026-06-25", summary.lastActiveDay)
        assertEquals(1, summary.retention.activeDaysInWindow)
    }

    @Test
    fun `isEnabled mirrors the settings flag`() = runTest {
        assertTrue(repository().isEnabled())

        every { settingsRepository.usageTelemetryEnabled } returns flowOf(false)
        assertFalse(repository().isEnabled())
    }
}
