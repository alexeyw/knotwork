package app.knotwork.android.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.knotwork.android.R

/**
 * How a short age is written in one language — "now", "5 m", "3 h", "2 d",
 * "1 w" in English.
 *
 * Several lists show how long ago something happened in one compact token.
 * Each wrote its own English units into code; the units are string resources
 * here, so the token reads in the interface language.
 *
 * @property now The token for less than a minute.
 * @property minutes Format for whole minutes; `%1$d` is the count.
 * @property hours Format for whole hours.
 * @property days Format for whole days.
 * @property weeks Format for whole weeks.
 */
data class AgeUnits(val now: String, val minutes: String, val hours: String, val days: String, val weeks: String) {

    /**
     * The largest whole unit of [elapsedMs]: minutes below an hour, hours below a
     * day, days below a week, weeks after that.
     *
     * @param elapsedMs Time since the event; a negative value counts as none.
     * @return The age as one short token.
     */
    fun short(elapsedMs: Long): String {
        val minutes = elapsedMs.coerceAtLeast(0L) / MS_PER_MINUTE
        return when {
            minutes < 1L -> now
            minutes < MINUTES_PER_HOUR -> this.minutes.format(minutes)
            minutes < MINUTES_PER_DAY -> hours.format(minutes / MINUTES_PER_HOUR)
            minutes < MINUTES_PER_WEEK -> days.format(minutes / MINUTES_PER_DAY)
            else -> weeks.format(minutes / MINUTES_PER_WEEK)
        }
    }

    /**
     * Whether [elapsedMs] is shorter than the smallest unit, so [short] gives
     * [now] — which reads wrong inside "… ago".
     *
     * @param elapsedMs Time since the event.
     * @return `true` below one minute.
     */
    fun isNow(elapsedMs: Long): Boolean = elapsedMs.coerceAtLeast(0L) < MS_PER_MINUTE

    private companion object {
        const val MS_PER_MINUTE = 60_000L
        const val MINUTES_PER_HOUR = 60L
        const val MINUTES_PER_DAY = MINUTES_PER_HOUR * 24
        const val MINUTES_PER_WEEK = MINUTES_PER_DAY * 7
    }
}

/**
 * The [AgeUnits] of the current interface language, for a composable.
 *
 * @return The units, resolved through `stringResource`.
 */
@Composable
fun rememberAgeUnits(): AgeUnits = AgeUnits(
    now = stringResource(R.string.unit_age_now),
    minutes = stringResource(R.string.unit_age_minutes),
    hours = stringResource(R.string.unit_age_hours),
    days = stringResource(R.string.unit_age_days),
    weeks = stringResource(R.string.unit_age_weeks),
)
