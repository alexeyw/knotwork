package app.knotwork.android.presentation.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import app.knotwork.android.R
import app.knotwork.android.domain.constants.NotificationChannels
import app.knotwork.android.domain.constants.NotificationIds
import app.knotwork.android.domain.models.HardCeilingBreach
import app.knotwork.android.domain.models.RunCeilingAxis
import app.knotwork.android.domain.services.CeilingNotifier
import app.knotwork.android.presentation.receivers.AgentApprovalReceiver
import app.knotwork.android.presentation.receivers.ApprovalAction
import app.knotwork.android.presentation.state.ActiveSessionTracker
import app.knotwork.android.presentation.ui.MainActivity
import app.knotwork.android.presentation.ui.navigation.NavRoutes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Manager that posts the "a run is waiting on you" notification for runs paused
 * at one of their own ceilings.
 *
 * Third of the parked-run notification managers, and modelled on
 * [ClarificationNotificationManager] rather than [ApprovalNotificationManager]:
 * it carries **no inline actions**. The decision — let the run spend another
 * allowance, or stop it — turns entirely on how much has already been spent,
 * and a Continue button in the shade would ask the user to authorise more work
 * without showing them that number. The body tap deep-links into the chat,
 * where the card states both.
 *
 * Suppressed while the user is looking at the session that raised it, on the
 * same terms as the approval notification: the in-chat card is already on
 * screen, and a notification for a question visible in front of the user is
 * noise. Otherwise `ongoing` and re-posted through the
 * [ApprovalAction.REPOST] delete-intent, because a paused run has no other way
 * back — it waits until it is answered or its window closes.
 */
class CeilingNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activeSessionTracker: ActiveSessionTracker,
) : CeilingNotifier {

    override fun sendCeilingPauseRequest(runId: String, sessionId: String, breach: HardCeilingBreach) {
        if (activeSessionTracker.activeSessionId.value == sessionId) {
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannelRegistered(notificationManager)

        val text = context.getString(bodyResFor(breach.axis), breach.spent, breach.limit)
        val notification = NotificationCompat.Builder(context, NotificationChannels.AGENT_RUN_CEILING)
            .setSmallIcon(R.drawable.ic_notif_question)
            .setContentTitle(context.getString(R.string.run_ceiling_pause_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(chatDeepLinkIntent(sessionId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setDeleteIntent(repostPendingIntent(sessionId, runId))
            .build()

        notificationManager.notify(notificationId(sessionId), notification)
    }

    /**
     * Removes the notification of [sessionId] — and the one a release before the id
     * registry posted for it in its old slot, which a run parked across the update
     * still has in the shade (see [PreUpdateSlot]).
     *
     * @param sessionId Id of the session whose notification to remove.
     */
    override fun cancelCeilingNotification(sessionId: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId(sessionId))
        PreUpdateSlot.cancel(
            notificationManager,
            NotificationIds.PreUpdate.slot(NotificationIds.PreUpdate.CEILING_BASE, sessionId),
            setOf(NotificationChannels.AGENT_RUN_CEILING),
        )
    }

    /**
     * Which body string states this axis's numbers.
     *
     * @param axis The ceiling that bound. The unmeasured money axis cannot
     *   raise a pause, and falls back to the token wording — the closest true
     *   statement — rather than inventing a currency line the app has no price
     *   source for.
     */
    private fun bodyResFor(axis: RunCeilingAxis): Int = when (axis) {
        RunCeilingAxis.STEPS -> R.string.run_ceiling_pause_notification_text_steps
        RunCeilingAxis.TOKENS, RunCeilingAxis.MONEY -> R.string.run_ceiling_pause_notification_text_tokens
    }

    /**
     * Registers the ceiling channel; idempotent, survives process death.
     *
     * @param notificationManager The system notification manager.
     */
    private fun ensureChannelRegistered(notificationManager: NotificationManager) {
        val channel = NotificationChannel(
            NotificationChannels.AGENT_RUN_CEILING,
            context.getString(R.string.run_ceiling_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.run_ceiling_channel_description)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Builds the swipe-dismiss delete-intent: routed to
     * [AgentApprovalReceiver] as [ApprovalAction.REPOST], which re-posts the
     * notification from the durable pending record.
     *
     * @param sessionId Id of the owning chat session.
     * @param runId Id of the parked run.
     */
    private fun repostPendingIntent(sessionId: String, runId: String): PendingIntent {
        val intent = Intent(context, AgentApprovalReceiver::class.java).apply {
            action = ApprovalAction.REPOST.action
            putExtra(AgentApprovalReceiver.EXTRA_SESSION_ID, sessionId)
            putExtra(AgentApprovalReceiver.EXTRA_RUN_ID, runId)
        }
        return PendingIntent.getBroadcast(
            context,
            runId.hashCode() + REPOST_OFFSET,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Builds the body-tap action: an activity [PendingIntent] whose
     * `ACTION_VIEW` uri matches the `knotwork://chat/{threadId}` pattern, so
     * Navigation routes straight into the session where the pause is restored.
     *
     * @param sessionId Id of the chat session to open.
     */
    private fun chatDeepLinkIntent(sessionId: String): PendingIntent? {
        val intent = Intent(
            Intent.ACTION_VIEW,
            "${NavRoutes.DEEP_LINK_SCHEME}://${NavRoutes.chatRoute(sessionId)}".toUri(),
            context,
            MainActivity::class.java,
        )
        return TaskStackBuilder.create(context).run {
            addNextIntentWithParentStack(intent)
            getPendingIntent(
                notificationId(sessionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }

    /**
     * Notification slot of [sessionId] within the ceiling family.
     *
     * @param sessionId Id of the chat session owning the slot.
     */
    private fun notificationId(sessionId: String): Int = NotificationIds.Family.CEILING.idFor(sessionId)

    companion object {
        /**
         * Request-code offset of the repost delete-intent. Distinct from the
         * approval (0–2) and clarification (3) offsets so one run's ceiling,
         * clarification and approval intents can never collide on
         * `PendingIntent` identity.
         */
        private const val REPOST_OFFSET = 4
    }
}
