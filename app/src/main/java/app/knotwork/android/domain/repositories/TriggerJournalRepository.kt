package app.knotwork.android.domain.repositories

import app.knotwork.android.domain.models.TriggerEvaluation
import app.knotwork.android.domain.models.TriggerHealthInputs
import app.knotwork.android.domain.models.TriggerHitlEvent
import app.knotwork.android.domain.models.TriggerRunOutcome
import kotlinx.coroutines.flow.Flow

/**
 * Persistence port for the **trigger-evaluation journal** — the durable,
 * on-device log of every trigger evaluation and the fate of the runs it started
 * (see [TriggerEvaluation]).
 *
 * The interface lives in `domain` (implemented in `data` by
 * `TriggerJournalRepositoryImpl`) so the journal use cases depend only on this
 * port, never on Room. The journal is stored in the same SQLCipher-encrypted
 * database as the rest of the app and nothing it holds ever leaves the device.
 *
 * **Observer contract.** Recording is a pure observer of the trigger runtime: an
 * implementation must never let a storage failure propagate into (and abort) the
 * evaluation or run being observed. It absorbs write failures per the
 * best-effort convention shared with the usage-telemetry store, re-throwing only
 * `CancellationException`.
 */
interface TriggerJournalRepository {

    /**
     * Records one trigger evaluation. Called once per evaluated
     * *(trigger × moment)* to honour the journal's no-silent-skips invariant.
     *
     * For a [Fired][app.knotwork.android.domain.models.TriggerEvaluationVerdict.Fired]
     * verdict the row is written with [TriggerEvaluation.runId] set and its
     * outcome still `null`; the terminal outcome is filled in later via
     * [recordRunOutcome].
     *
     * @param evaluation The evaluation record to persist.
     */
    suspend fun recordEvaluation(evaluation: TriggerEvaluation)

    /**
     * Attributes the terminal [outcome] of a background run back onto the
     * journal row that started it (matched by [TriggerEvaluation.runId]).
     * Idempotent and a no-op when no row references [runId] (e.g. the journal
     * entry was already aged out by retention).
     *
     * @param runId Id of the enqueued run whose outcome is now known.
     * @param outcome The terminal fate to record.
     */
    suspend fun recordRunOutcome(runId: String, outcome: TriggerRunOutcome)

    /**
     * Folds one human-in-the-loop [event] onto the journal row that started the
     * run identified by [runId] (matched exactly as [recordRunOutcome] does), so
     * a background run that stopped to ask the user is visible as such instead of
     * settling into an anonymous success.
     *
     * Idempotent in the same sense as [recordRunOutcome]: a no-op when no row
     * references [runId] — which is the normal case for every interactive,
     * scheduled-but-untriggered or child run, none of which own a journal row.
     * Callers therefore report events unconditionally rather than pre-checking
     * the run's origin.
     *
     * @param runId Id of the **root** run whose gate this is; a gate raised
     *   inside a child pipeline run must be reported under the root, because that
     *   is the id the journal row carries.
     * @param event The transition to fold onto the row.
     */
    suspend fun recordHitlEvent(runId: String, event: TriggerHitlEvent)

    /**
     * Returns the id of the trigger whose firing started run [runId], read from
     * the journal row written when it fired. Lets an approval raised in a
     * trigger's run name that trigger — the run record itself does not carry it.
     *
     * Best-effort like every journal read: an unreadable store answers `null`,
     * the same as a run no trigger started.
     *
     * @param runId Id of the **root** run — the id the journal row carries.
     * @return the trigger id, or `null` when no row links [runId] to a trigger.
     */
    suspend fun findTriggerIdForRun(runId: String): String?

    /**
     * Observes the journal of a single trigger, newest evaluation first. Backs
     * the per-trigger journal UI.
     *
     * @param triggerId The trigger whose evaluations to observe.
     * @return A hot [Flow] emitting the trigger's evaluation list on every change.
     */
    fun observeByTrigger(triggerId: String): Flow<List<TriggerEvaluation>>

    /**
     * Observes the health-relevant journal facts of **every** trigger at once,
     * collapsed to one [TriggerHealthInputs] per trigger id. Backs the list's
     * health-badge, which must classify every row from a single reactive source
     * rather than opening one journal stream per trigger.
     *
     * A trigger with no journal rows is simply absent from the map (the caller
     * treats an absent entry as "not evaluated yet"). The map re-emits whenever a
     * new evaluation is recorded or a pending run outcome settles.
     *
     * @return A hot [Flow] of `triggerId → latest health inputs`.
     */
    fun observeHealthInputs(): Flow<Map<String, TriggerHealthInputs>>

    /**
     * Reads the entire journal as a one-shot snapshot, newest evaluation first.
     *
     * Unlike the reactive observers this is a plain suspend read returning a
     * stable point-in-time copy of every trigger's rows at once — it backs the
     * diagnostic journal export a soak run pulls off the device for offline
     * analysis, where the closed-app protocol rules out reading the in-app UI.
     * Per the best-effort observer contract it degrades to an **empty list** on
     * any storage error (the failure is logged, never thrown), so a dump can
     * never take down the caller; `CancellationException` is still re-thrown.
     *
     * @return Every stored [TriggerEvaluation], newest first; empty on a read
     *   failure or an empty journal.
     */
    suspend fun readAll(): List<TriggerEvaluation>

    /**
     * Applies the journal retention policy in one bounded pass: deletes every
     * record older than [olderThanEpochMs] and then trims the table to its newest
     * [maxRecords] rows, so the journal can never grow without bound even under a
     * pathologically chatty poll.
     *
     * @param olderThanEpochMs Age cutoff, epoch-millis; rows with an earlier
     *   [TriggerEvaluation.evaluatedAt] are deleted.
     * @param maxRecords Hard cap on retained rows after the age pass; the newest
     *   [maxRecords] survive, the rest are deleted. Must be `> 0` — a non-positive
     *   cap would wipe the whole journal, so it is rejected as a programming error.
     * @return The total number of rows deleted across both passes.
     * @throws IllegalArgumentException if [maxRecords] is not positive.
     */
    suspend fun applyRetention(olderThanEpochMs: Long, maxRecords: Int): Int
}
