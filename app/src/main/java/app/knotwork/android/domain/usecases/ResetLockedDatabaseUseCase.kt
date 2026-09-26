package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.services.AgentWorkspace
import app.knotwork.android.domain.services.AttachmentStore
import app.knotwork.android.domain.services.DatabaseResetService
import app.knotwork.android.domain.services.TaskScheduler
import app.knotwork.android.domain.services.TransientCacheSweeper
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject

/**
 * The recovery wipe behind *Erase data* on the splash screen's data-locked surface,
 * run only after the user has typed the confirmation.
 *
 * **What it erases.** The encrypted database and its stored passphrase first
 * ([DatabaseResetService]); then, and only once that succeeded, the plain-text content
 * that belongs to the same data: the agent workspace ([AgentWorkspace.eraseAll]), the
 * stored image attachments ([AttachmentStore.deleteAll]) and every transient cache copy
 * made from either ([TransientCacheSweeper.sweepAll]) — share copies, staged journal
 * exports, camera captures, voice clips — and every queued background run
 * ([TaskScheduler.cancelAllBackgroundRuns]). The runtime keeps those in a store of
 * its own the database wipe cannot reach; a recurring task would otherwise fire on
 * after the wipe and re-create its chat.
 *
 * **What it keeps, deliberately.** Settings, saved cloud API keys, the Hugging Face token
 * and MCP credentials. The wipe exists to escape a database that cannot be opened; on the
 * device where that happens those still work, and the dialog says they are kept. On a
 * device the data was restored to they are unreadable anyway and read as unset.
 *
 * A failed database wipe throws and leaves every file untouched, so a retry still finds
 * the data a recovered key could open. The three file steps are independent — one that
 * cannot delete something does not stop the next — and report only how many fell short,
 * never a path.
 *
 * @property databaseResetService Deletes the database file and its passphrase.
 * @property agentWorkspace Owner of the agent workspace directory.
 * @property attachmentStore Owner of the stored image attachments.
 * @property transientCacheSweeper Owner of the transient cache directories.
 * @property taskScheduler Owner of the queued background runs.
 */
class ResetLockedDatabaseUseCase @Inject constructor(
    private val databaseResetService: DatabaseResetService,
    private val agentWorkspace: AgentWorkspace,
    private val attachmentStore: AttachmentStore,
    private val transientCacheSweeper: TransientCacheSweeper,
    private val taskScheduler: TaskScheduler,
) {

    /**
     * Executes the wipe. Once it returns, re-running the application initialization
     * sequence creates a fresh database with a new passphrase.
     *
     * @return `true` when everything the wipe covers is gone; `false` when the database
     *   is gone but some file could not be deleted (logged by count).
     * @throws Exception Whatever [DatabaseResetService.wipeAllData] throws when the
     *   database survives; nothing else has been touched then.
     */
    suspend operator fun invoke(): Boolean {
        databaseResetService.wipeAllData()
        val erased = listOf(
            agentWorkspace.eraseAll(),
            attachmentStore.deleteAll(),
            transientCacheSweeper.sweepAll(),
            cancelBackgroundRuns(),
        )
        val shortfall = erased.count { !it }
        if (shortfall > 0) {
            Timber.w(
                "Data wipe: the database is gone, but %d of %d file stores kept something.",
                shortfall,
                erased.size,
            )
        }
        return shortfall == 0
    }

    /**
     * Cancels every queued background run and clears the runtime's record of them.
     *
     * @return `true` when that succeeded; `false` (logged by type) otherwise, so it
     *   counts as a store that kept something rather than stopping the steps after it.
     */
    private suspend fun cancelBackgroundRuns(): Boolean = try {
        taskScheduler.cancelAllBackgroundRuns()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Data wipe: queued background runs could not be cancelled")
        false
    }
}
