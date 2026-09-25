package app.knotwork.android.presentation.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.models.ceilingBreach
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.services.ApprovalNotifier
import app.knotwork.android.domain.services.CeilingNotifier
import app.knotwork.android.domain.services.ClarificationNotifier
import app.knotwork.android.domain.usecases.SubmitApprovalDecisionUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * BroadcastReceiver that handles the user's action from the approval and
 * clarification notifications.
 *
 * Works in both waiting phases of the two-phase HITL protocol — and, for the
 * persistent phase, across process death: the receiver is manifest-declared,
 * so tapping a notification action launches the app process if needed, and
 * the routed [SubmitApprovalDecisionUseCase] falls through from the live
 * in-process gate to the parked pending-interaction record. Persistent
 * notifications additionally route their delete-intent here
 * ([ApprovalAction.REPOST]) so a swiped-away notification is re-posted from
 * the durable record — it is the user's primary path back to a parked run.
 *
 * Every decision action names the request it was posted for
 * ([EXTRA_REQUEST_ID]) and is submitted with that identity, so it settles that
 * request or nothing — never a different request the same session happens to
 * be waiting on when the tap arrives.
 *
 * The decision work suspends (Room + queue), which a `BroadcastReceiver`
 * cannot do inline: the receiver bridges via [goAsync] and an own supervisor
 * scope, keeping the process alive until the submission settles.
 */
@AndroidEntryPoint
class AgentApprovalReceiver : BroadcastReceiver() {

    /** Routes approve / deny to the live gate or the parked record. */
    @Inject
    lateinit var submitApprovalDecisionUseCase: SubmitApprovalDecisionUseCase

    /** Source of the parked request snapshot for notification re-posts. */
    @Inject
    lateinit var pendingInteractionRepository: PendingInteractionRepository

    /** Re-posts persistent approval notifications on [ApprovalAction.REPOST]. */
    @Inject
    lateinit var approvalNotifier: ApprovalNotifier

    /** Re-posts persistent clarification notifications on [ApprovalAction.REPOST]. */
    @Inject
    lateinit var clarificationNotifier: ClarificationNotifier

    /** Re-posts persistent ceiling-pause notifications on [ApprovalAction.REPOST]. */
    @Inject
    lateinit var ceilingNotifier: CeilingNotifier

    /**
     * Host scope of the suspending submission work bridged through
     * [goAsync]. Visible for tests so they can substitute a test scope and
     * await completion deterministically.
     */
    @VisibleForTesting
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Called when the BroadcastReceiver is receiving an Intent broadcast.
     *
     * Decodes [intent]'s action through [ApprovalAction.fromAction] so the dispatch is
     * exhaustive on the enum; unknown actions are silently ignored.
     *
     * @param context The Context in which the receiver is running.
     * @param intent The Intent being received.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val runId = intent.getStringExtra(EXTRA_RUN_ID)
        val action = ApprovalAction.fromAction(intent.action) ?: return

        when (action) {
            ApprovalAction.APPROVE, ApprovalAction.DENY -> {
                // A notification posted before requests had identities names
                // none. Its slot was the session's; a parked one of those still
                // names its run, which the store back-filled as that request's
                // identity. A live one names nothing — its run died with the
                // process the update killed — so it answers nothing.
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
                if (requestId == null) approvalNotifier.cancelPreUpdateNotification(sessionId)
                val answered = requestId ?: runId ?: return
                // Remove the notification immediately for responsive UX; the
                // submission below settles the request itself.
                approvalNotifier.cancelApprovalNotification(answered)
                launchAsync {
                    submitApprovalDecisionUseCase(sessionId, answered, isApproved = action == ApprovalAction.APPROVE)
                }
            }
            ApprovalAction.REPOST -> {
                if (runId != null) {
                    launchAsync { repostFromRecord(runId) }
                }
            }
        }
    }

    /**
     * Re-posts the persistent notification of a parked run from its durable
     * record. A missing record means the request settled between the swipe
     * and this broadcast — nothing to re-post.
     *
     * @param runId Id of the parked run whose notification was dismissed.
     */
    private suspend fun repostFromRecord(runId: String) {
        val pending = pendingInteractionRepository.getForRun(runId) ?: return
        when (pending.kind) {
            PendingInteractionKind.APPROVAL -> approvalNotifier.sendPersistentApprovalRequest(
                runId = pending.runId,
                sessionId = pending.sessionId,
                // Every approval record names its request: minted ones carry
                // the gate's token, older ones were back-filled with the run id.
                requestId = pending.requestId ?: pending.runId,
                toolName = pending.toolName.orEmpty(),
                arguments = pending.toolArgs.orEmpty(),
                risk = pending.risk ?: ToolRisk.SENSITIVE,
            )
            PendingInteractionKind.CLARIFICATION -> clarificationNotifier.sendPersistentClarificationRequest(
                runId = pending.runId,
                sessionId = pending.sessionId,
                question = pending.question.orEmpty(),
            )
            // A record that cannot name its axis and numbers has nothing to
            // re-post: the notification's whole content is those three facts.
            // Silently dropping it is right — the pause itself survives on the
            // record, and the chat still restores the card from it.
            PendingInteractionKind.CEILING -> pending.ceilingBreach()?.let { breach ->
                ceilingNotifier.sendCeilingPauseRequest(
                    runId = pending.runId,
                    sessionId = pending.sessionId,
                    breach = breach,
                )
            }
        }
    }

    /**
     * Bridges suspending work out of `onReceive` via [goAsync]: the pending
     * result keeps the (possibly freshly launched) process in the foreground
     * priority band until `finish()` is called. Nullable because a directly
     * invoked receiver (unit tests) has no framework-provided pending result.
     *
     * @param block The suspending work to run.
     */
    private fun launchAsync(block: suspend () -> Unit) {
        val pendingResult: PendingResult? = goAsync()
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Notification action handling failed")
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        /** Intent extra carrying the chat session id of the request. */
        const val EXTRA_SESSION_ID: String = "sessionId"

        /** Intent extra carrying the parked run id (persistent-phase notifications only). */
        const val EXTRA_RUN_ID: String = "runId"

        /** Intent extra carrying the identity of the request a decision action answers. */
        const val EXTRA_REQUEST_ID: String = "requestId"
    }
}
