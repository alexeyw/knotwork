package app.knotwork.android.presentation.startup

import app.knotwork.android.data.services.AttachmentOrphanCleanupScheduler
import app.knotwork.android.data.services.MemoryCompactionScheduler
import app.knotwork.android.data.services.PendingInteractionMaintenanceScheduler
import app.knotwork.android.data.services.RunRetentionScheduler
import app.knotwork.android.domain.services.MemoryReembedScheduler
import app.knotwork.android.domain.usecases.SyncTriggersUseCase
import app.knotwork.android.presentation.shortcuts.AppShortcutPublisher
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject

/**
 * The background upkeep a cold start arms, run by `MainActivity` off the main thread
 * while the splash screen initialises the app.
 *
 * **Every step is isolated**: a step that throws is logged and the next one runs.
 * The steps run concurrently with the splash and several of them read the database,
 * so the failure they meet is the splash's own — above all a database whose key is
 * lost. There the splash offers *Erase data*, and it can only do so if this pass does
 * not take the process down first: an uncaught throw from the trigger sync once did,
 * on every launch, so the recovery screen could never appear.
 *
 * @property memoryCompactionScheduler Daily memory compaction and its hard-limit watch.
 * @property memoryReembedScheduler Re-arms an interrupted memory re-embed pass.
 * @property pendingInteractionMaintenanceScheduler Expiry pass for parked background requests.
 * @property runRetentionScheduler Retention pass over persisted pipeline runs.
 * @property attachmentOrphanCleanupScheduler Backstop sweep for orphaned attachment files.
 * @property syncTriggersUseCase Registers the watches of every enabled automation trigger.
 * @property appShortcutPublisher Publishes the recent-session launcher shortcuts.
 */
class StartupMaintenance @Inject constructor(
    private val memoryCompactionScheduler: MemoryCompactionScheduler,
    private val memoryReembedScheduler: MemoryReembedScheduler,
    private val pendingInteractionMaintenanceScheduler: PendingInteractionMaintenanceScheduler,
    private val runRetentionScheduler: RunRetentionScheduler,
    private val attachmentOrphanCleanupScheduler: AttachmentOrphanCleanupScheduler,
    private val syncTriggersUseCase: SyncTriggersUseCase,
    private val appShortcutPublisher: AppShortcutPublisher,
) {

    /**
     * Runs every step, each on its own. Idempotent: the schedulers replace their
     * periodic work by name and the trigger sync replaces watches by trigger id, so
     * running it again on activity recreation is harmless.
     *
     * @param refreshShortcuts Whether to re-publish the launcher shortcuts — `false` on a
     *   configuration-change recreation, which has nothing new to show.
     */
    suspend fun run(refreshShortcuts: Boolean) {
        step("memory compaction") {
            memoryCompactionScheduler.schedulePeriodic()
            memoryCompactionScheduler.startHardLimitWatch()
        }
        step("pending-interaction expiry") { pendingInteractionMaintenanceScheduler.schedulePeriodic() }
        step("run retention") { runRetentionScheduler.schedulePeriodic() }
        step("attachment orphan cleanup") { attachmentOrphanCleanupScheduler.schedulePeriodic() }
        step("memory re-embed re-arm") { memoryReembedScheduler.rearmIfPending() }
        step("trigger sync") { syncTriggersUseCase() }
        if (refreshShortcuts) step("launcher shortcuts") { appShortcutPublisher.refresh() }
    }

    /**
     * Runs [block], logging instead of propagating anything but cancellation.
     *
     * @param name What the step does, for the log.
     * @param block The step.
     */
    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Start-up maintenance step failed, continuing: %s", name)
        }
    }
}
