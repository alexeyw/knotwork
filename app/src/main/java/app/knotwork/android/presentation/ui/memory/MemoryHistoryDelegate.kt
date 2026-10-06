package app.knotwork.android.presentation.ui.memory

import app.knotwork.android.domain.repositories.MemoryHistoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The Memory screen's history actions, split from [MemoryViewModel] like the chat
 * screen's delegates: resolving an update waiting on a pinned entry, and deleting one
 * earlier version. Shares the ViewModel's scope and state; after each action the
 * ViewModel reloads the list.
 *
 * @property scope The ViewModel's scope.
 * @property state The ViewModel's state; the delegate moves or closes the open sheet.
 * @property memoryHistoryRepository Resolves pairs and deletes versions.
 * @property onChanged Reloads the screen after a change.
 * @property onFailed Reports that a change could not be made.
 */
class MemoryHistoryDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<MemoryUiState>,
    private val memoryHistoryRepository: MemoryHistoryRepository,
    private val onChanged: () -> Unit,
    private val onFailed: () -> Unit,
) {

    /**
     * *Use the update* on either half of a pair: the pinned entry takes the update's
     * text and stays pinned. The open sheet moves to (or stays on) the pinned entry,
     * since the update entry is gone.
     *
     * @param id Identifier of either half.
     */
    fun useUpdate(id: Long) = change {
        val holder = memoryHistoryRepository.applyWaitingUpdate(id)
        state.update { it.copy(expandedId = holder ?: it.expandedId, editing = false) }
    }

    /**
     * *Keep pinned* on either half of a pair: the waiting update is discarded. From the
     * update's own sheet the sheet closes, since its entry is gone; from the pinned
     * entry's sheet it stays.
     *
     * @param id Identifier of either half.
     */
    fun keepPinned(id: Long) = change {
        val pinned = memoryHistoryRepository.discardWaitingUpdate(id)
        if (pinned != null && pinned != id) state.update { it.copy(expandedId = null, editing = false) }
    }

    /**
     * Deletes one earlier version of an entry; the entry and its other versions stay.
     *
     * @param versionId Identifier of the version.
     */
    fun deleteVersion(versionId: Long) = change { memoryHistoryRepository.deleteVersion(versionId) }

    private fun change(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
                onChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Failed to change a memory entry's history")
                onFailed()
            }
        }
    }
}
