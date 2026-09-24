package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.RunOrigin

/**
 * A ceiling on how many runs of one [RunOrigin] may start within a rolling
 * window, and the arithmetic that decides whether the ceiling is crossed.
 *
 * Extracted so the runaway guards in the app share one mechanism instead of one
 * implementation each. The mechanism — *rate*, not queue depth, and refuse rather
 * than silently defer — is what the scheduler guard learned the hard way: a task
 * that re-schedules itself keeps exactly one item queued at any moment, so
 * nothing about the queue ever looks wrong while the agent runs forever.
 *
 * What the guards do **not** share is where the count comes from, and the
 * difference is load-bearing rather than incidental:
 * - the scheduler counts rows in the run history, because its runs are created
 *   by the app itself and a row exists by the time it matters;
 * - the external entry point must count **accepted requests at the moment of
 *   acceptance**. A run row appears only once the work actually reaches
 *   execution in-process, while a receiver has to answer immediately — a burst
 *   of broadcasts would every one of them read a count of zero and every one of
 *   them be admitted, and a unit test against a mocked repository would look
 *   perfectly green while doing it;
 * - the share target has the same burst problem for the same reason (the run's
 *   row is written off the enqueue path, after the share activity has moved on),
 *   so it too counts admissions at the moment of admission, in a ledger of its
 *   own.
 *
 * So this type carries the ceiling and the window; the counter stays with the
 * caller that knows what it is counting.
 *
 * @property origin The run origin this ceiling governs.
 * @property limitPerWindow How many runs may start within [windowMillis] before
 *   further starts are refused.
 * @property windowMillis Length of the rolling window, in milliseconds.
 */
data class RunRateCeiling(val origin: RunOrigin, val limitPerWindow: Int, val windowMillis: Long) {

    /**
     * Whether a further run must be refused.
     *
     * @param recentCount How many runs of [origin] started inside the window
     *   ending now.
     * @return `true` when the ceiling is reached and the next run must be
     *   refused rather than queued.
     */
    fun isExceededBy(recentCount: Int): Boolean = recentCount >= limitPerWindow

    /**
     * Start of the rolling window for a given moment.
     *
     * @param nowMillis The current time, epoch-millis.
     * @return Epoch-millis of the earliest run that still counts.
     */
    fun windowStart(nowMillis: Long): Long = nowMillis - windowMillis

    /** The ceilings the app actually ships, and the window they share. */
    companion object {
        /** One hour in milliseconds — the window every ceiling uses. */
        const val ONE_HOUR_MILLIS: Long = 3_600_000L

        /**
         * Ceiling for the agent's own scheduling tool. Twelve is comfortably
         * above any cadence the background runtime can actually honour for
         * repeating work (its floor is well over a minute, and periodic work is
         * hourly at best here) and far below the rate a self-re-scheduling chain
         * reaches.
         */
        val SCHEDULED: RunRateCeiling = RunRateCeiling(
            origin = RunOrigin.SCHEDULER,
            limitPerWindow = 12,
            windowMillis = ONE_HOUR_MILLIS,
        )

        /**
         * Ceiling for the external-automation entry point. Set to the same rate
         * as [SCHEDULED] on purpose: an external caller gets no allowance the
         * app's own background work does not have. Counted over accepted
         * requests rather than run rows — see the class documentation.
         */
        val EXTERNAL: RunRateCeiling = RunRateCeiling(
            origin = RunOrigin.EXTERNAL,
            limitPerWindow = 12,
            windowMillis = ONE_HOUR_MILLIS,
        )

        /**
         * Ceiling for the share target. Higher than the other two on purpose: a
         * share is normally a person's hand action — each one opens the app into
         * the run it started — and sharing a handful of links in a row is ordinary
         * use. The ceiling is aimed at a script, not at a person: the activity is
         * exported without a permission (it has to be, for the system share sheet
         * to start it on another app's behalf), so any installed app can start it
         * directly, and a loop of such starts is what thirty an hour bounds. It is
         * one budget for every sender, because the sender is not known.
         */
        val SHARE: RunRateCeiling = RunRateCeiling(
            origin = RunOrigin.SHARE,
            limitPerWindow = 30,
            windowMillis = ONE_HOUR_MILLIS,
        )
    }
}
