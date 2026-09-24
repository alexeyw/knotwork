package app.knotwork.android.data.repositories

import app.knotwork.android.domain.repositories.NetworkActivityTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory [NetworkActivityTracker] backed by a single [MutableStateFlow].
 *
 * Lives at process scope (`@Singleton`); the value resets when the
 * process is recreated, which matches the privacy-indicator semantics
 * ("since you opened the app").
 *
 * Streaming callers record per frame and the model downloader per chunk — at
 * download speed that is well over a hundred calls a second. The stored time
 * therefore moves only once [RESOLUTION_MS] has passed since the last stored
 * value: the pill it feeds counts minutes, and an unchanged value does not wake
 * its observers.
 *
 * @property now Clock in epoch milliseconds; the system clock in the app.
 */
@Singleton
class NetworkActivityTrackerImpl internal constructor(private val now: () -> Long) : NetworkActivityTracker {

    /** Production constructor: the system clock. */
    @Inject
    constructor() : this(System::currentTimeMillis)

    private val _lastOutboundAt = MutableStateFlow<Long?>(value = null)

    override val lastOutboundAt: StateFlow<Long?> = _lastOutboundAt.asStateFlow()

    override fun recordOutbound() {
        val time = now()
        // A clock set back is followed rather than ignored: kept, the stored time would sit
        // in the future and read as "just now" until the clock caught up with it.
        _lastOutboundAt.update { last ->
            if (last == null || time - last >= RESOLUTION_MS || time < last) time else last
        }
    }

    private companion object {
        /** Smallest step the stored time moves by. */
        const val RESOLUTION_MS: Long = 1_000L
    }
}
