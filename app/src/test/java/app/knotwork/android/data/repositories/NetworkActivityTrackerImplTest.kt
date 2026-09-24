package app.knotwork.android.data.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [NetworkActivityTrackerImpl].
 */
class NetworkActivityTrackerImplTest {

    private var clock = 10_000L
    private val tracker = NetworkActivityTrackerImpl { clock }

    @Test
    fun `given nothing recorded when read then there is no last call`() {
        assertNull(tracker.lastOutboundAt.value)
    }

    @Test
    fun `given a call when recorded then its time is stored`() {
        tracker.recordOutbound()

        assertEquals(10_000L, tracker.lastOutboundAt.value)
    }

    @Test
    fun `given calls within a second of the stored one when recorded then the time does not move`() {
        // A stream records per frame; the observers must not be woken for each one.
        tracker.recordOutbound()
        clock += 999L
        tracker.recordOutbound()

        assertEquals(10_000L, tracker.lastOutboundAt.value)
    }

    @Test
    fun `given a call a second or more after the stored one when recorded then the time moves`() {
        tracker.recordOutbound()
        clock += 1_000L
        tracker.recordOutbound()

        assertEquals(11_000L, tracker.lastOutboundAt.value)
    }

    @Test
    fun `given a clock that went backwards when recorded then the stored time follows it`() {
        // Kept, a time in the future would read as "just now" until the clock caught up.
        tracker.recordOutbound()
        clock -= 5_000L
        tracker.recordOutbound()

        assertEquals(5_000L, tracker.lastOutboundAt.value)
    }
}
