package app.knotwork.android.presentation.startup

import app.knotwork.android.data.services.AttachmentOrphanCleanupScheduler
import app.knotwork.android.data.services.MemoryCompactionScheduler
import app.knotwork.android.data.services.PendingInteractionMaintenanceScheduler
import app.knotwork.android.data.services.RunRetentionScheduler
import app.knotwork.android.domain.models.DbPassphraseUnavailableException
import app.knotwork.android.domain.services.MemoryReembedScheduler
import app.knotwork.android.domain.usecases.SyncTriggersUseCase
import app.knotwork.android.presentation.shortcuts.AppShortcutPublisher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * [StartupMaintenance] must survive every one of its steps failing — the case it was
 * written for is a database whose key is lost, where the trigger sync threw
 * `DbPassphraseUnavailableException` out of `MainActivity` and killed the process
 * before the splash could show *Erase data*, on every launch.
 */
class StartupMaintenanceTest {

    private val compaction = mockk<MemoryCompactionScheduler>(relaxed = true)
    private val reembed = mockk<MemoryReembedScheduler>(relaxed = true)
    private val pendingExpiry = mockk<PendingInteractionMaintenanceScheduler>(relaxed = true)
    private val retention = mockk<RunRetentionScheduler>(relaxed = true)
    private val orphans = mockk<AttachmentOrphanCleanupScheduler>(relaxed = true)
    private val syncTriggers = mockk<SyncTriggersUseCase>(relaxed = true)
    private val shortcuts = mockk<AppShortcutPublisher>(relaxed = true)
    private val maintenance =
        StartupMaintenance(compaction, reembed, pendingExpiry, retention, orphans, syncTriggers, shortcuts)

    @Test
    fun `given the database key is lost when the trigger sync throws then the run completes and later steps run`() =
        runTest {
            coEvery { syncTriggers() } throws DbPassphraseUnavailableException(
                DbPassphraseUnavailableException.Reason.PASSPHRASE_MISSING,
            )

            maintenance.run(refreshShortcuts = true)

            coVerify(exactly = 1) { shortcuts.refresh() }
        }

    @Test
    fun `given each step failing in turn when run then every other step still runs`() = runTest {
        val steps = stepFailures()
        for ((failing, arrange) in steps) {
            val calls = mutableListOf<String>()
            record(calls)
            arrange()

            maintenance.run(refreshShortcuts = true)

            assertEquals("with $failing failing", ALL_STEPS - failing, calls.toSet() - failing)
        }
    }

    @Test
    fun `given a configuration-change recreation when run then the shortcuts are not re-published`() = runTest {
        maintenance.run(refreshShortcuts = false)

        coVerify(exactly = 0) { shortcuts.refresh() }
        coVerify(exactly = 1) { syncTriggers() }
    }

    @Test
    fun `given the run is cancelled when a step is cancelled then the cancellation propagates`() = runTest {
        every { compaction.schedulePeriodic() } throws CancellationException("activity destroyed")

        try {
            maintenance.run(refreshShortcuts = true)
            fail("cancellation was swallowed")
        } catch (_: CancellationException) {
            // expected
        }
        verify(exactly = 0) { retention.schedulePeriodic() }
    }

    /** Stubs every step to record its name when it runs. */
    private fun record(calls: MutableList<String>) {
        every { compaction.schedulePeriodic() } answers { calls += COMPACTION }
        every { compaction.startHardLimitWatch() } answers { calls += COMPACTION }
        every { pendingExpiry.schedulePeriodic() } answers { calls += EXPIRY }
        every { retention.schedulePeriodic() } answers { calls += RETENTION }
        every { orphans.schedulePeriodic() } answers { calls += ORPHANS }
        coEvery { reembed.rearmIfPending() } answers { calls += REEMBED }
        coEvery { syncTriggers() } answers { calls += TRIGGERS }
        coEvery { shortcuts.refresh() } answers { calls += SHORTCUTS }
    }

    /** One entry per step: its name and the stub that makes it throw. */
    private fun stepFailures(): List<Pair<String, () -> Unit>> = listOf(
        COMPACTION to { every { compaction.schedulePeriodic() } throws IllegalStateException("x") },
        EXPIRY to { every { pendingExpiry.schedulePeriodic() } throws IllegalStateException("x") },
        RETENTION to { every { retention.schedulePeriodic() } throws IllegalStateException("x") },
        ORPHANS to { every { orphans.schedulePeriodic() } throws IllegalStateException("x") },
        REEMBED to { coEvery { reembed.rearmIfPending() } throws IllegalStateException("x") },
        TRIGGERS to { coEvery { syncTriggers() } throws IllegalStateException("x") },
        SHORTCUTS to { coEvery { shortcuts.refresh() } throws IllegalStateException("x") },
    )

    private companion object {
        const val COMPACTION = "compaction"
        const val EXPIRY = "expiry"
        const val RETENTION = "retention"
        const val ORPHANS = "orphans"
        const val REEMBED = "reembed"
        const val TRIGGERS = "triggers"
        const val SHORTCUTS = "shortcuts"
        val ALL_STEPS = setOf(COMPACTION, EXPIRY, RETENTION, ORPHANS, REEMBED, TRIGGERS, SHORTCUTS)
    }
}
