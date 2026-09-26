package app.knotwork.android.domain.repositories

import kotlinx.coroutines.flow.Flow

/**
 * The prompts of background runs waiting in the scheduler, kept in the
 * encrypted database rather than handed to the background runtime.
 *
 * The background runtime (WorkManager) persists every queued request's input in
 * a database of its own, **in the clear**, and keeps a finished request's row for
 * a day after it ran. A prompt put there — a `schedule_task` instruction, a
 * trigger's prompt, an external caller's text — sat outside the at-rest
 * encryption that covers everything else derived from user input, and *Erase
 * data* never reached it. So the runtime is handed only an opaque id, and the
 * prompt lives here, under the same key as the chats it will land in.
 */
interface BackgroundPromptRepository {

    /**
     * Stores [prompt] under [id], replacing whatever was stored under it before.
     *
     * @param id The id the background request will carry.
     * @param prompt The prompt the run will execute.
     */
    suspend fun store(id: String, prompt: String)

    /**
     * Reads the prompt stored under [id].
     *
     * @param id The id a background request carries.
     * @return The prompt, or `null` when none is stored — the request was
     *   cancelled and pruned, or the data was erased.
     */
    suspend fun get(id: String): String?

    /**
     * Removes the prompt stored under [id], if any.
     *
     * @param id The id of a prompt no request needs any more.
     */
    suspend fun delete(id: String)

    /**
     * Removes every prompt stored before [storedBefore] whose id is not in
     * [ids] — the housekeeping that drops the prompts of requests cancelled
     * before they ran.
     *
     * The cutoff keeps a prompt that was stored a moment ago, whose request has
     * not been handed to the runtime yet: [ids] cannot name it, and removing it
     * would leave the request with nothing to run.
     *
     * @param ids The ids still carried by a request that has yet to finish.
     * @param storedBefore Only prompts stored earlier than this, epoch millis,
     *   may be removed.
     * @return How many prompts were removed.
     */
    suspend fun retainOnly(ids: Set<String>, storedBefore: Long): Int

    /**
     * Observes every stored prompt by id, for the task monitor's labels.
     *
     * @return The stored prompts keyed by id, re-emitted on every change.
     */
    fun observeAll(): Flow<Map<String, String>>
}
