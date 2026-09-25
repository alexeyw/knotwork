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
import app.knotwork.android.domain.services.ClarificationNotifier
import app.knotwork.android.presentation.receivers.AgentApprovalReceiver
import app.knotwork.android.presentation.receivers.ApprovalAction
import app.knotwork.android.presentation.ui.MainActivity
import app.knotwork.android.presentation.ui.navigation.NavRoutes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Manager that posts the "Agent needs your input" notification for runs
 * parked on an unanswered clarification question.
 *
 * Counterpart of [ApprovalNotificationManager] for the clarification gate;
 * only the persistent waiting phase posts here (the live phase renders an
 * in-chat card). The notification carries no inline answer actions — a
 * clarification expects arbitrary input — so its body taps through to the
 * chat session where the persisted question is restored for answering. Like
 * the persistent approval notification it is `ongoing`, alerts only once,
 * and re-posts itself through the [ApprovalAction.REPOST] delete-intent when
 * swiped away on Android 14+, because it is the user's primary path back to
 * the parked run.
 */
class ClarificationNotificationManager @Inject constructor(@ApplicationContext private val context: Context) :
    ClarificationNotifier {

    override fun sendPersistentClarificationRequest(runId: String, sessionId: String, question: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannelRegistered(notificationManager)

        val notification = NotificationCompat.Builder(context, NotificationChannels.AGENT_CLARIFICATION)
            .setSmallIcon(R.drawable.ic_notif_question)
            .setContentTitle(context.getString(R.string.clarification_notification_title))
            .setContentText(question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(question))
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
    override fun cancelClarificationNotification(sessionId: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId(sessionId))
        PreUpdateSlot.cancel(
            notificationManager,
            NotificationIds.PreUpdate.slot(NotificationIds.PreUpdate.CLARIFICATION_BASE, sessionId),
            setOf(NotificationChannels.AGENT_CLARIFICATION),
        )
    }

    /**
     * Registers the clarification channel; idempotent, survives process death.
     *
     * @param notificationManager The system notification manager.
     */
    private fun ensureChannelRegistered(notificationManager: NotificationManager) {
        val channel = NotificationChannel(
            NotificationChannels.AGENT_CLARIFICATION,
            context.getString(R.string.clarification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.clarification_channel_description)
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
     * Navigation routes straight into the session where the persisted
     * question is restored.
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
     * Notification slot of [sessionId] within the clarification family.
     *
     * @param sessionId Id of the chat session owning the slot.
     */
    private fun notificationId(sessionId: String): Int = NotificationIds.Family.CLARIFICATION.idFor(sessionId)

    companion object {
        /**
         * Request-code offset of the repost delete-intent. Distinct from the
         * approval family's offsets so one run's clarification and approval
         * intents can never collide on `PendingIntent` identity.
         */
        private const val REPOST_OFFSET = 3
    }
}
