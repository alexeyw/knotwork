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
import app.knotwork.android.domain.constants.TimeAndIdConstants
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.services.ApprovalNotifier
import app.knotwork.android.presentation.receivers.AgentApprovalReceiver
import app.knotwork.android.presentation.receivers.ApprovalAction
import app.knotwork.android.presentation.state.ActiveSessionTracker
import app.knotwork.android.presentation.ui.MainActivity
import app.knotwork.android.presentation.ui.navigation.NavRoutes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Manager that sends a high-priority notification to the user requesting approval
 * to execute a tool, as part of the Human-in-the-loop mechanism.
 *
 * Maintains two `IMPORTANCE_HIGH` notification channels so the user can
 * distinguish [ToolRisk.DESTRUCTIVE] approvals (warning glyph, distinct title)
 * from reversible [ToolRisk.SENSITIVE] / opt-in [ToolRisk.READ_ONLY] approvals
 * even in the system shade. Both channels are eagerly registered on every send
 * — `NotificationManager.createNotificationChannel` is idempotent and the
 * channels survive process death.
 *
 * Both waiting phases build their decision actions through one helper, so the
 * shade offers the same choice for the same risk whichever phase the run is in:
 * [ToolRisk.DESTRUCTIVE] never gets a one-tap Approve — approving it takes the
 * typed confirmation of the in-chat card, which a notification cannot collect.
 */
class ApprovalNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activeSessionTracker: ActiveSessionTracker,
) : ApprovalNotifier {

    companion object {
        const val NOTIFICATION_ID = 201

        /** Request-code offset of the Approve action within one request's intent family. */
        private const val APPROVE_OFFSET = 0

        /** Request-code offset of the Deny action within one request's intent family. */
        private const val DENY_OFFSET = 1

        /** Request-code offset of the repost delete-intent within one request's intent family. */
        private const val REPOST_OFFSET = 2
    }

    /**
     * Sends a notification to request user approval for a tool execution.
     * It suppresses the notification if the user is currently viewing the active session.
     *
     * Actions are risk-gated exactly as in [sendPersistentApprovalRequest]:
     * [ToolRisk.DESTRUCTIVE] offers a "Review in chat" deep link and Deny
     * instead of Approve; the other tiers offer Approve and Deny.
     *
     * @param sessionId The ID of the session requesting approval.
     * @param toolName The name of the tool to be executed.
     * @param arguments The arguments to be passed to the tool.
     * @param risk Risk classification, drives the channel / icon / title selection.
     */
    override fun sendApprovalRequest(sessionId: String, toolName: String, arguments: String, risk: ToolRisk) {
        // If the user is currently on the chat screen for this session, they will see the inline prompt.
        if (activeSessionTracker.activeSessionId.value == sessionId) {
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannelsRegistered(notificationManager)

        val channelId = when (risk) {
            ToolRisk.DESTRUCTIVE -> NotificationChannels.AGENT_APPROVAL_DESTRUCTIVE
            ToolRisk.SENSITIVE, ToolRisk.READ_ONLY -> NotificationChannels.AGENT_APPROVAL
        }
        val smallIcon = when (risk) {
            ToolRisk.DESTRUCTIVE -> R.drawable.ic_notif_decision
            ToolRisk.SENSITIVE, ToolRisk.READ_ONLY -> R.drawable.ic_notif_decision
        }
        val title = when (risk) {
            ToolRisk.DESTRUCTIVE -> context.getString(R.string.approval_notification_title_destructive)
            ToolRisk.SENSITIVE, ToolRisk.READ_ONLY -> context.getString(R.string.approval_notification_title_sensitive)
        }
        val contentText = context.getString(R.string.approval_notification_text, toolName)
        val bigText = context.getString(R.string.approval_notification_big_text, toolName, arguments)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addDecisionActions(
                risk = risk,
                sessionId = sessionId,
                approveIntent = { approvalPendingIntent(sessionId, ApprovalAction.APPROVE, APPROVE_OFFSET) },
                denyIntent = approvalPendingIntent(sessionId, ApprovalAction.DENY, DENY_OFFSET),
            )
            .build()

        // Use sessionId hashcode as notification ID so multiple requests can be shown if needed
        notificationManager.notify(notificationId(sessionId), notification)
    }

    /**
     * Sends the persistent-phase approval notification for a parked run.
     *
     * Differences from the live-phase [sendApprovalRequest]:
     *  - posted unconditionally — the run is no longer live, so the in-chat
     *    card alone cannot be relied on once the user navigates away;
     *  - `ongoing` + a [ApprovalAction.REPOST] delete-intent: Android 14+
     *    lets the user swipe ongoing notifications, so the receiver
     *    re-posts it from the durable pending record (`onlyAlertOnce` keeps
     *    the re-post silent);
     *  - the body taps through to the chat session (deep link) where the
     *    standard confirmation card is restored from the record;
     *  - actions are risk-gated: [ToolRisk.DESTRUCTIVE] offers Deny plus a
     *    "Review in chat" deep link instead of a direct Approve — a typed
     *    confirmation cannot be collected from a notification.
     *
     * @param runId Id of the parked run the decision must address.
     * @param sessionId The ID of the session that triggered the request.
     * @param toolName The name of the tool to be executed.
     * @param arguments The arguments to be passed to the tool.
     * @param risk Risk classification, drives the channel / icon / title / actions.
     */
    override fun sendPersistentApprovalRequest(
        runId: String,
        sessionId: String,
        toolName: String,
        arguments: String,
        risk: ToolRisk,
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannelsRegistered(notificationManager)

        val channelId = when (risk) {
            ToolRisk.DESTRUCTIVE -> NotificationChannels.AGENT_APPROVAL_DESTRUCTIVE
            ToolRisk.SENSITIVE, ToolRisk.READ_ONLY -> NotificationChannels.AGENT_APPROVAL
        }
        val smallIcon = when (risk) {
            ToolRisk.DESTRUCTIVE -> R.drawable.ic_notif_decision
            ToolRisk.SENSITIVE, ToolRisk.READ_ONLY -> R.drawable.ic_notif_decision
        }
        val title = when (risk) {
            ToolRisk.DESTRUCTIVE -> context.getString(R.string.approval_notification_title_destructive)
            ToolRisk.SENSITIVE, ToolRisk.READ_ONLY -> context.getString(R.string.approval_notification_title_sensitive)
        }
        val contentText = context.getString(R.string.approval_notification_waiting_text, toolName)
        val bigText = context.getString(R.string.approval_notification_big_text, toolName, arguments)
        val deepLink = chatDeepLinkIntent(sessionId)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(deepLink)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setDeleteIntent(persistentPendingIntent(sessionId, runId, ApprovalAction.REPOST, REPOST_OFFSET))
            .addDecisionActions(
                risk = risk,
                sessionId = sessionId,
                approveIntent = { persistentPendingIntent(sessionId, runId, ApprovalAction.APPROVE, APPROVE_OFFSET) },
                denyIntent = persistentPendingIntent(sessionId, runId, ApprovalAction.DENY, DENY_OFFSET),
            )

        notificationManager.notify(notificationId(sessionId), builder.build())
    }

    /**
     * Removes the approval notification of [sessionId], if any is showing.
     *
     * @param sessionId The session whose approval notification to remove.
     */
    override fun cancelApprovalNotification(sessionId: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId(sessionId))
    }

    /**
     * Adds the decision actions of an approval notification — the one place
     * that decides what can be answered from the shade, shared by both
     * waiting phases so they cannot drift apart.
     *
     * [ToolRisk.DESTRUCTIVE] gets a "Review in chat" deep link in place of
     * Approve: the in-chat card asks for a typed confirmation before a
     * destructive call may run, and a notification action cannot collect one.
     * [approveIntent] is a factory so that no Approve intent is even created
     * for such a call. Deny is always offered — refusing needs no ceremony.
     *
     * @param risk Risk classification of the staged call.
     * @param sessionId Chat session the "Review in chat" link opens.
     * @param approveIntent Builds the Approve broadcast; not invoked for [ToolRisk.DESTRUCTIVE].
     * @param denyIntent The Deny broadcast.
     * @return this builder, for chaining.
     */
    private fun NotificationCompat.Builder.addDecisionActions(
        risk: ToolRisk,
        sessionId: String,
        approveIntent: () -> PendingIntent,
        denyIntent: PendingIntent,
    ): NotificationCompat.Builder {
        if (risk == ToolRisk.DESTRUCTIVE) {
            addAction(
                R.drawable.ic_action_open,
                context.getString(R.string.approval_notification_review_in_chat),
                chatDeepLinkIntent(sessionId),
            )
        } else {
            addAction(R.drawable.ic_notif_done, context.getString(R.string.chat_thought_approve), approveIntent())
        }
        return addAction(R.drawable.ic_action_deny, context.getString(R.string.chat_thought_deny), denyIntent)
    }

    private fun ensureChannelsRegistered(notificationManager: NotificationManager) {
        val sensitiveChannel = NotificationChannel(
            NotificationChannels.AGENT_APPROVAL,
            context.getString(R.string.approval_channel_sensitive_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.approval_channel_sensitive_description)
        }
        val destructiveChannel = NotificationChannel(
            NotificationChannels.AGENT_APPROVAL_DESTRUCTIVE,
            context.getString(R.string.approval_channel_destructive_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.approval_channel_destructive_description)
        }
        notificationManager.createNotificationChannel(sensitiveChannel)
        notificationManager.createNotificationChannel(destructiveChannel)
    }

    private fun approvalPendingIntent(
        sessionId: String,
        action: ApprovalAction,
        requestCodeOffset: Int,
    ): PendingIntent {
        val intent = Intent(context, AgentApprovalReceiver::class.java).apply {
            this.action = action.action
            putExtra(AgentApprovalReceiver.EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getBroadcast(
            context,
            sessionId.hashCode() + requestCodeOffset,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Builds a broadcast [PendingIntent] addressing a parked run. Request
     * codes derive from the run id (not the session id, which the live-phase
     * intents use) so the persistent intents of a run never overwrite — and
     * are never overwritten by — live-phase intents whose extras differ but
     * whose `Intent.filterEquals` identity matches.
     *
     * @param sessionId Id of the owning chat session.
     * @param runId Id of the parked run carried to [AgentApprovalReceiver].
     * @param action The wire action to emit.
     * @param requestCodeOffset Disambiguates the actions of one run.
     */
    private fun persistentPendingIntent(
        sessionId: String,
        runId: String,
        action: ApprovalAction,
        requestCodeOffset: Int,
    ): PendingIntent {
        val intent = Intent(context, AgentApprovalReceiver::class.java).apply {
            this.action = action.action
            putExtra(AgentApprovalReceiver.EXTRA_SESSION_ID, sessionId)
            putExtra(AgentApprovalReceiver.EXTRA_RUN_ID, runId)
        }
        return PendingIntent.getBroadcast(
            context,
            runId.hashCode() + requestCodeOffset,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Builds the body-tap action: an activity [PendingIntent] whose
     * `ACTION_VIEW` uri matches the `knotwork://chat/{threadId}` pattern
     * registered on the chat destination, so Navigation routes straight into
     * the session where the confirmation card is restored.
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
     * Notification slot of [sessionId]: shared by the live and persistent
     * phases so the persistent notification replaces the live one, and a
     * single cancel clears whichever is showing.
     *
     * @param sessionId Id of the chat session owning the slot.
     */
    private fun notificationId(sessionId: String): Int =
        NOTIFICATION_ID + sessionId.hashCode() % TimeAndIdConstants.NOTIFICATION_ID_RANGE
}
