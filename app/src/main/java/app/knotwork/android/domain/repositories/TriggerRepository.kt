package app.knotwork.android.domain.repositories

import app.knotwork.android.domain.models.Trigger
import kotlinx.coroutines.flow.Flow

/**
 * Repository for user-defined automation [Trigger]s.
 *
 * The interface lives in `domain` (implemented in `data` by
 * `TriggerRepositoryImpl`) so the trigger use cases and the background runtime
 * depend only on this port, never on Room. Reads are reactive `Flow`s (the
 * trigger list and the active-trigger subset drive both the UI and the
 * scheduler sync); writes are `suspend` functions.
 */
interface TriggerRepository {

    /**
     * Observes every trigger, newest first. Backs the management UI.
     *
     * @return A hot [Flow] emitting the full trigger list on every change.
     */
    fun observeTriggers(): Flow<List<Trigger>>

    /**
     * Observes the triggers eligible to run in the background — those that are
     * [enabled][Trigger.enabled] **and** bound to a pipeline
     * ([Trigger.isActive]). Backs the scheduler sync, which registers exactly
     * this set with the background runtime.
     *
     * @return A hot [Flow] emitting the active-trigger subset on every change.
     */
    fun observeActiveTriggers(): Flow<List<Trigger>>

    /**
     * Loads a single trigger by id.
     *
     * @param id The trigger id.
     * @return The trigger, or `null` if no row matches (e.g. deleted between a
     *   background wakeup and its handling).
     */
    suspend fun getTriggerById(id: String): Trigger?

    /**
     * Inserts or replaces a trigger (upsert by [Trigger.id]).
     *
     * [Trigger.activatedAt] and [Trigger.staleNoticeFor] are the repository's, not
     * the caller's: a save that makes the trigger [active][Trigger.isActive] —
     * a new bound trigger, or binding or enabling an inactive one — records now as
     * its activation; any other save keeps the stored values.
     *
     * @param trigger The trigger to persist.
     */
    suspend fun saveTrigger(trigger: Trigger)

    /**
     * Deletes a trigger by id. Idempotent: deleting a missing id is a no-op.
     *
     * @param id The trigger id to remove.
     */
    suspend fun deleteTrigger(id: String)

    /**
     * Flips a trigger's [enabled][Trigger.enabled] flag. Enabling a disabled
     * trigger records now as its [activation][Trigger.activatedAt].
     *
     * @param id The trigger id.
     * @param enabled The new enabled state.
     */
    suspend fun setEnabled(id: String, enabled: Boolean)

    /**
     * Records that the user was told the trigger has been silent since [signOfLife].
     *
     * @param id The trigger id.
     * @param signOfLife The last evaluation or activation the notice was about.
     */
    suspend fun markStaleNoticed(id: String, signOfLife: Long)

    /**
     * Sets a trigger's [armed][Trigger.armed] edge latch (event conditions).
     *
     * @param id The trigger id.
     * @param armed The new armed state (`false` after a fire, `true` once the
     *   condition drops).
     */
    suspend fun setArmed(id: String, armed: Boolean)

    /**
     * Records the most recent fire time, backing the re-arm debounce.
     *
     * @param id The trigger id.
     * @param firedAt Epoch-millis of the fire.
     */
    suspend fun markFired(id: String, firedAt: Long)

    /**
     * Binds the trigger to the chat session its background runs land in. Written
     * lazily on the first fire (and again if the previously bound session was
     * deleted), so recurring fires accumulate their results in one conversation.
     *
     * @param id The trigger id.
     * @param sessionId The chat session id to bind.
     */
    suspend fun setSessionId(id: String, sessionId: String)
}
