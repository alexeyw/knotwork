package app.knotwork.android.presentation.ui.triggers

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Names the moment an overdue trigger was last known alive, the same way on the
 * trigger's detail screen ("Not checked since …") and in the overdue-trigger
 * notification, whose tap opens that screen.
 *
 * A moment earlier today reads as a bare `HH:mm` ("07:15"); an earlier day is
 * qualified with its date ("Mon 14 Jul, 07:15"), so a trigger silent for days is
 * not misread as checked this morning.
 */
internal object TriggerSilenceLabel {

    /**
     * The day formatter for [locale] — also the detail screen's journal day headers.
     *
     * @param locale The locale the day and month names are written in.
     * @return The formatter.
     */
    fun dayFormatter(locale: Locale): DateTimeFormatter = DateTimeFormatter.ofPattern(DAY_PATTERN, locale)

    /**
     * Formats [epochMillis] as described on the object.
     *
     * @param epochMillis The moment to name.
     * @param nowMillis The current time; decides whether the moment is today.
     * @param zone The zone both moments are read in.
     * @param dayFormatter Formatter of the day part, from [dayFormatter].
     * @return The label.
     */
    fun format(epochMillis: Long, nowMillis: Long, zone: ZoneId, dayFormatter: DateTimeFormatter): String {
        val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val time = "%02d:%02d".format(moment.hour, moment.minute)
        return if (moment.toLocalDate() == today) time else "${moment.toLocalDate().format(dayFormatter)}, $time"
    }

    private const val DAY_PATTERN = "EEE d MMM"
}
