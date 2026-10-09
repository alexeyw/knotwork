package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.Trigger
import app.knotwork.android.domain.models.TriggerCondition
import app.knotwork.android.domain.models.TriggerHealthInputs
import app.knotwork.android.domain.repositories.TriggerRepository
import app.knotwork.android.domain.services.ScheduledTaskNotifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [NotifyStaleTriggersUseCase]: an overdue trigger is announced once
 * per silence, a healthy one never, and a new sign of life starts the next silence.
 */
class NotifyStaleTriggersUseCaseTest {

    private val minute = 60_000L
    private val now = 1_000_000_000L

    private val triggers = mockk<TriggerRepository>(relaxed = true)
    private val healthInputs = mockk<ObserveTriggerHealthInputsUseCase>()
    private val notifier = mockk<ScheduledTaskNotifier>(relaxed = true)
    private val useCase = NotifyStaleTriggersUseCase(triggers, healthInputs, TriggerHealthEvaluator(), notifier)

    /** A 30-minute trigger: overdue once silent for more than 60 minutes. */
    private fun trigger(id: String, staleNoticeFor: Long? = null) = Trigger(
        id = id,
        name = "Trigger $id",
        condition = TriggerCondition.IntervalSchedule(30L),
        pipelineId = "pipe",
        prompt = "",
        enabled = true,
        createdAt = 0L,
        staleNoticeFor = staleNoticeFor,
    )

    private fun given(vararg pairs: Pair<Trigger, Long>) {
        every { triggers.observeActiveTriggers() } returns flowOf(pairs.map { it.first })
        every { healthInputs.invoke() } returns
            flowOf(pairs.associate { (trigger, evaluatedAt) -> trigger.id to TriggerHealthInputs(evaluatedAt) })
    }

    @Test
    fun `given an overdue and a healthy trigger when checked then only the overdue one is announced`() = runTest {
        given(trigger("late") to now - 90 * minute, trigger("fine") to now - 10 * minute)

        val noticed = useCase(now)

        assertEquals(1, noticed)
        coVerify(exactly = 1) { notifier.notifyTriggerStale("late", "Trigger late", now - 90 * minute) }
        coVerify(exactly = 1) { triggers.markStaleNoticed("late", now - 90 * minute) }
        coVerify(exactly = 0) { notifier.notifyTriggerStale("fine", any(), any()) }
    }

    @Test
    fun `given an overdue trigger already announced for this silence when checked then nothing is sent`() = runTest {
        given(trigger("late", staleNoticeFor = now - 90 * minute) to now - 90 * minute)

        assertEquals(0, useCase(now))
        coVerify(exactly = 0) { notifier.notifyTriggerStale(any(), any(), any()) }
        coVerify(exactly = 0) { triggers.markStaleNoticed(any(), any()) }
    }

    @Test
    fun `given a trigger silent again after a later sign of life when checked then the new silence is announced`() =
        runTest {
            // Announced for a silence that began at -300 min; it checked in at -90 min and
            // has been silent since — a new silence.
            given(trigger("late", staleNoticeFor = now - 300 * minute) to now - 90 * minute)

            assertEquals(1, useCase(now))
            coVerify(exactly = 1) { triggers.markStaleNoticed("late", now - 90 * minute) }
        }

    @Test
    fun `given the notifier fails for one trigger when checked then the notice is not recorded as sent`() = runTest {
        given(trigger("late") to now - 90 * minute)
        coEvery { notifier.notifyTriggerStale(any(), any(), any()) } throws IllegalStateException("no channel")

        val thrown = try {
            useCase(now)
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertEquals("no channel", thrown?.message)
        coVerify(exactly = 0) { triggers.markStaleNoticed(any(), any()) }
    }
}
