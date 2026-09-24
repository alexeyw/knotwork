package app.knotwork.android.domain.repositories

/**
 * Ledger of recent share-target admissions — the count the share rate ceiling
 * ([app.knotwork.android.domain.usecases.RunRateCeiling.SHARE]) is taken over.
 *
 * **Counted at the moment of admission, not over the run history.** A share's run
 * record is written off the enqueue path, after the share activity has already
 * moved on, so a burst of shares would each read the same pre-burst count and each
 * be admitted — the trap the external entry point avoids with its journal. The run
 * history is the wrong ledger for two more reasons: a nested sub-pipeline run
 * inherits its parent's origin (one share would count several times), and run
 * retention may prune rows that are still inside the window.
 *
 * The ledger holds admission times and nothing else — no content, no sender (which
 * is not known) — and only as many as the ceiling can count.
 */
interface ShareAdmissionRepository {

    /**
     * Atomically admits one share if the ceiling still allows it.
     *
     * The count and the record are one step, so concurrent shares cannot each read
     * the same count and each be admitted.
     *
     * @param nowMillis The moment of the share, epoch-millis; recorded when it is
     *   admitted.
     * @param windowStartEpochMs Earliest admission that still counts toward the
     *   ceiling.
     * @param limitPerWindow How many admissions the window allows; must be positive.
     * @return `true` when the share was admitted and recorded; `false` when the
     *   ceiling refused it **or** the ledger could not be written — both mean "do
     *   not start the run": an entry point any installed app can reach must not be
     *   admitted on the strength of a ledger that failed to count it.
     */
    suspend fun admitWithinCeiling(nowMillis: Long, windowStartEpochMs: Long, limitPerWindow: Int): Boolean
}
