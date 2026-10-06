package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.models.ApprovalRequestContext
import app.knotwork.android.domain.models.ApprovalRequestSource
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.TriggerJournalRepository
import app.knotwork.android.domain.repositories.TriggerRepository
import app.knotwork.android.domain.text.ApprovalRequestText
import app.knotwork.android.domain.text.toDisplaySafe
import app.knotwork.android.domain.text.toDisplaySafeExcerpt
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject

/**
 * Finds what the run behind an approval was asked to do, for the approval card
 * and notification to show next to the tool call.
 *
 * **The root's request.** A gate can be raised deep inside a child run started
 * by a `PIPELINE` node, whose recorded prompt is that node's input — text the
 * pipeline produced, not the user. The executing run is resolved to the root of
 * its tree first (as [RecordTriggerHitlEventUseCase] does for the journal), and
 * the root's recorded prompt is the request.
 *
 * **Nothing new is computed.** The request is the prompt the root run record has
 * held since it was enqueued; a trigger's name is read through the trigger
 * journal, whose row links the run the trigger started to the trigger. No model
 * is asked anything, so the same run resolves to the same context in every
 * process — which is why a parked request's surfaces resolve it again from the
 * run id instead of storing a copy.
 *
 * **Best-effort.** The context is an aid to the decision, never a condition of
 * it: any failure answers `null`, and the approval is shown as it would be
 * without one. Only cancellation propagates.
 *
 * @property pipelineRunRepository Resolves the executing run to its root and reads the root's record.
 * @property triggerJournal Links a trigger-started run back to its trigger.
 * @property triggerRepository Names the trigger.
 */
class ResolveApprovalRequestContextUseCase @Inject constructor(
    private val pipelineRunRepository: PipelineRunRepository,
    private val triggerJournal: TriggerJournalRepository,
    private val triggerRepository: TriggerRepository,
) {

    /**
     * Resolves the request behind an approval raised in run [runId].
     *
     * @param runId Id of the run whose gate this is — the executing, possibly
     *   child, run. `null` for a run that is not persisted (an editor test run),
     *   which has no record to read.
     * @return the request and its source; `null` when the run is not persisted,
     *   has no record or no recorded prompt, its prompt is blank, or the lookup
     *   failed.
     */
    suspend operator fun invoke(runId: String?): ApprovalRequestContext? {
        if (runId == null) return null
        return try {
            resolve(runId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not resolve the request behind an approval")
            null
        }
    }

    private suspend fun resolve(runId: String): ApprovalRequestContext? {
        val rootId = pipelineRunRepository.getRootRunId(runId) ?: return null
        val root = pipelineRunRepository.getRun(rootId) ?: return null
        val shown = requestOf(root) ?: return null
        return ApprovalRequestContext(
            source = sourceOf(root.origin, rootId),
            request = shown.text,
            shortened = shown.shortened,
            hadImage = root.hadImage,
        )
    }

    /**
     * The root run's recorded prompt as an approval shows it: one display-safe
     * line clamped on a word. An image sent without text yields no text — the run
     * received the app's default instruction, which the user never typed.
     *
     * @return the request to show; `null` when the run recorded none, or only blanks.
     */
    private fun requestOf(root: PipelineRun): ShownRequest? {
        val prompt = root.userPrompt ?: return null
        if (root.hadImage && prompt == DefaultPrompts.IMAGE_ONLY_DEFAULT_INSTRUCTION) {
            return ShownRequest(text = null, shortened = false)
        }
        val flattened = prompt.toDisplaySafe(maxLength = Int.MAX_VALUE)
        if (flattened.isEmpty()) return null
        val excerpt = flattened.toDisplaySafeExcerpt(ApprovalRequestText.MAX_CARD_LENGTH)
        return ShownRequest(text = excerpt, shortened = excerpt != flattened)
    }

    private suspend fun sourceOf(origin: RunOrigin, rootId: String): ApprovalRequestSource = when (origin) {
        RunOrigin.CHAT -> ApprovalRequestSource.Chat
        RunOrigin.SHARE -> ApprovalRequestSource.Shared
        RunOrigin.TRIGGER -> ApprovalRequestSource.Trigger(name = triggerName(rootId))
        RunOrigin.SCHEDULER -> ApprovalRequestSource.ScheduledTask
        RunOrigin.QUICK_TILE -> ApprovalRequestSource.QuickTile
        RunOrigin.EXTERNAL -> ApprovalRequestSource.OtherApp
    }

    /**
     * Names the trigger that started root run [rootId]: the journal row written
     * when it fired carries the trigger's id. `null` when the trigger was deleted
     * since — or, in the rare case its row is gone (a journal write is
     * best-effort, retention keeps the newest 2 000 rows), when it cannot be
     * told apart from a deleted one.
     */
    private suspend fun triggerName(rootId: String): String? {
        val triggerId = triggerJournal.findTriggerIdForRun(rootId) ?: return null
        val name = triggerRepository.getTriggerById(triggerId)?.name ?: return null
        return name.toDisplaySafeExcerpt(ApprovalRequestText.MAX_TRIGGER_NAME_LENGTH).ifEmpty { null }
    }

    /**
     * The request text an approval shows, and whether it was clamped.
     *
     * @property text The display-safe request; `null` for an image sent without text.
     * @property shortened `true` when [text] was clamped.
     */
    private data class ShownRequest(val text: String?, val shortened: Boolean)

    private companion object {
        const val TAG = "ApprovalRequest"
    }
}
