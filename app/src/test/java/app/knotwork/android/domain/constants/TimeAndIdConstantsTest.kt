package app.knotwork.android.domain.constants

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the time-unit constants exposed by
 * [TimeAndIdConstants].
 *
 * The arithmetic relationships ([MS_PER_MINUTE] = 60 × [MS_PER_SECOND]) are
 * asserted because callers do unit conversions inline and would otherwise
 * silently desynchronise if either constant moved on its own.
 */
class TimeAndIdConstantsTest {

    @Test
    fun `given ms per second when read then matches documented value`() {
        assertEquals(1_000L, TimeAndIdConstants.MS_PER_SECOND)
    }

    @Test
    fun `given ms per minute when read then equals 60 times ms per second`() {
        assertEquals(60L * TimeAndIdConstants.MS_PER_SECOND, TimeAndIdConstants.MS_PER_MINUTE)
    }
}
