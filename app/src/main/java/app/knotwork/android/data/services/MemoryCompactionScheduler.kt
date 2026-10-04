package app.knotwork.android.data.services

import android.annotation.SuppressLint
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.MemorySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the WorkManager scheduling of [MemoryCompactionWorker].
 *
 * Two complementary schedules:
 *  - **Daily periodic** ([schedulePeriodic]) — the routine maintenance pass,
 *    constrained to **charging + device-idle** (plus battery-not-low) so it
 *    never runs while the user is actively on the device or draining battery.
 *    Combining charging and idle can delay or skip a run when those conditions
 *    are not met inside the interval; that is acceptable for once-a-day tidying.
 *  - **Out-of-schedule one-off** ([triggerImmediate], driven by
 *    [observeHardLimit]) — fired when the chunk count crosses the hard limit.
 *    It uses **relaxed** constraints (battery-not-low only, no charging/idle
 *    gate) because a bloated database needs draining promptly, not whenever the
 *    phone next sits idle on a charger.
 *
 * Both jobs are enqueued under unique names so re-scheduling never stacks
 * duplicates.
 *
 * @property workManager The WorkManager instance work is enqueued on.
 * @property memoryRepository Source of the live chunk count for the hard-limit watch.
 * @property memorySettings Source of the max-chunks hard limit.
 */
@Singleton
class MemoryCompactionScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val memoryRepository: MemoryRepository,
    private val memorySettings: MemorySettings,
) {

    /**
     * Application-lifetime scope the hard-limit watch collects on. Independent
     * of any UI lifecycle so the watch survives activity recreation. Overridable
     * so tests can drive it with a test scheduler and virtual time.
     */
    internal var watchScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Guards [startHardLimitWatch] so repeated calls register a single collector. */
    private val watchStarted = AtomicBoolean(false)

    /**
     * Enqueues (or keeps) the daily periodic compaction job. Idempotent: a
     * second call while the job is already scheduled is a no-op thanks to
     * [ExistingPeriodicWorkPolicy.KEEP], so it is safe to call on every cold
     * start.
     */
    @SuppressLint("IdleBatteryChargingConstraints")
    fun schedulePeriodic() {
        // Charging + device-idle is intentional: compaction runs the local model
        // and re-embeds, so it must never compete with foreground use or drain
        // battery. Lint warns the two constraints may rarely co-occur on some
        // devices; that is acceptable here because the hard-limit watch
        // ([startHardLimitWatch]) provides a relaxed-constraint safety net, and
        // delaying a tidy-up pass costs nothing but a slightly larger table.
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<MemoryCompactionWorker>(
            PERIOD_HOURS,
            TimeUnit.HOURS,
            FLEX_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            MemoryCompactionWorker.UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        Timber.tag(TAG).d("Scheduled periodic memory compaction (every %d h)", PERIOD_HOURS)
    }

    /**
     * Enqueues a one-off compaction run with relaxed constraints. Used by the
     * hard-limit watch; [ExistingWorkPolicy.KEEP] coalesces bursts so an
     * already-queued run is not duplicated.
     */
    fun triggerImmediate() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<MemoryCompactionWorker>()
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniqueWork(
            MemoryCompactionWorker.UNIQUE_IMMEDIATE_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        Timber.tag(TAG).d("Triggered out-of-schedule memory compaction (hard limit exceeded)")
    }

    /**
     * Starts watching the stored-chunk count against the configured hard limit,
     * firing [triggerImmediate] whenever the table grows past it. Idempotent:
     * repeated calls (e.g. across activity recreation) register a single
     * collector thanks to [watchStarted].
     *
     * The watch signal is the chunk count **while over the limit** (and `null`
     * while within it), de-duplicated with [distinctUntilChanged]. This re-arms
     * the trigger on every distinct over-limit count, not just the first rising
     * edge: if an immediate run cannot drop the table below the ceiling (too few
     * old candidates, model unavailable, a summary that failed the coverage
     * gate, …) a later insert that bumps the count
     * still enqueues another pass, so the hard ceiling is not left unenforced
     * until the much-more-constrained daily job happens to run. It still avoids a
     * tight loop — an unchanged over-limit count emits nothing new, and
     * [triggerImmediate] coalesces via `KEEP` while a run is in flight, so the
     * worker simply drains the table step by step until it is within the limit
     * or out of eligible candidates. Collection runs on [watchScope] for the
     * process lifetime.
     */
    fun startHardLimitWatch() {
        if (!watchStarted.compareAndSet(false, true)) return
        combine(
            memoryRepository.observeStats().map { it.chunkCount },
            memorySettings.maxMemoryChunks,
        ) { count, max -> count.takeIf { it > max } }
            .distinctUntilChanged()
            .onEach { overLimitCount -> if (overLimitCount != null) triggerImmediate() }
            // The stats flow is the first database access on this app-lifetime scope: when the
            // DB cannot be opened (e.g. SQLCipher passphrase unavailable — the splash recovery
            // screen owns that failure), an uncaught throw here would crash the process before
            // the recovery UI renders. Log, re-arm the guard, and let the next startHardLimitWatch
            // call (activity recreation, post-recovery cold path) start a fresh collector.
            .catch { e ->
                watchStarted.set(false)
                Timber.tag(TAG).w(e, "Hard-limit watch stopped: memory stats unavailable")
            }
            .launchIn(watchScope)
    }

    private companion object {
        const val TAG = "MemoryCompaction"

        /** Periodic interval — once per day. */
        const val PERIOD_HOURS = 24L

        /**
         * Flex window — WorkManager may run the job any time in the last
         * [FLEX_HOURS] of each period once constraints are met, giving the OS
         * latitude to batch it with other idle-time work.
         */
        const val FLEX_HOURS = 6L
    }
}
