package app.knotwork.android.presentation.ui.triggers

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Unit tests for [TriggerSilenceLabel]: a moment from today is a bare time, one
 * from an earlier day carries its date.
 */
class TriggerSilenceLabelTest {

    private val zone = ZoneOffset.UTC
    private val dayFormatter = TriggerSilenceLabel.dayFormatter(Locale.ENGLISH)
    private val now = millis(day = 8, hour = 12, minute = 0)

    private fun millis(day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `given a moment earlier today when formatted then it is the bare time`() {
        assertEquals(
            "07:15",
            TriggerSilenceLabel.format(millis(day = 8, hour = 7, minute = 15), now, zone, dayFormatter),
        )
    }

    @Test
    fun `given a moment on an earlier day when formatted then the date comes first`() {
        assertEquals(
            "Mon 5 Oct, 07:15",
            TriggerSilenceLabel.format(millis(day = 5, hour = 7, minute = 15), now, zone, dayFormatter),
        )
    }

    @Test
    fun `given yesterday late evening when formatted then it is not read as today`() {
        assertEquals(
            "Wed 7 Oct, 23:59",
            TriggerSilenceLabel.format(millis(day = 7, hour = 23, minute = 59), now, zone, dayFormatter),
        )
    }
}
