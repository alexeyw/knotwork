package app.knotwork.android.data.repositories

import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Runs [block] under the best-effort persistence contract shared by the
 * run-record and run-trace repositories: storage and mapping failures are
 * absorbed (logged, neutral `null` result) because observability writes must
 * never take down the execution they describe. [CancellationException] is
 * always re-thrown from the dedicated first catch clause, per the
 * coroutine-cancellation gate.
 *
 * @param failureMessage Lazily-built description of the failed operation,
 *   logged after a fixed prefix; evaluated only when the block actually fails.
 * @param block The storage operation to attempt.
 * @return The block's result, or `null` when the store failed.
 */
internal suspend fun <T> absorbingStoreFailure(failureMessage: () -> String, block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    // The fixed words are what a crash report keeps (it drops the arguments);
    // the stack frames of `e` name the store call that failed.
    Timber.e(e, "Best-effort store operation failed: %s", failureMessage())
    null
}
