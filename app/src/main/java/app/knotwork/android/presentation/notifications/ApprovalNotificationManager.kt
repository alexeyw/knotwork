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
 *
 * Everything is keyed by the **request**: the notification slot, the identity
 * of every action intent, and the answer each action carries. The live and the
 * persistent notification of one request share a slot (the park replaces the
 * one with the other); two requests of one session get two notifications, and
 * settling one leaves the other.
 */
class ApprovalNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activeSessionTracker: ActiveSessionTracker,
) : ApprovalNotifier {

    companion object {
        /** Request-code offset of the Approve action within one request's intent family. */
        private const val APPROVE_OFFSET = 0

        /** Request-code offset of the Deny action within one request's intent family. */
        private const val DENY_OFFSET = 1

        /** Request-code offset of the repost delete-intent within one request's intent family. */
        private const val REPOST_OFFSET = 2

        /**
         * Longest argument string, in characters, a notification shows whole and
         * lets the user approve from the shade.
         *
         * One-tap Approve authorises the entire string, while the shade shows a
         * bounded part of it: the platform caps every text a notification holds
         * (1 024 characters) and the expanded view shows a limited number of
         * lines below that. Past this budget the text is cut with a note and
         * only Deny stays in the shade; the chat card expands every argument.
         * The value is a judgement — about eight lines of a phone-width shade —
         * not a measurement of any device's layout.
         */
        internal const val SHADE_ARGUMENT_BUDGET = 300

        /**
         * Notification slot of request [requestId]: shared by its live and
         * persistent notification, so the park replaces the one with the other
         * and a single cancel clears whichever is showing — and distinct from
         * the slot of every other request, of the same session included.
         *
         * @param requestId Identity of the request owning the slot.
         * @return The notification id.
         */
        fun notificationId(requestId: String): Int = NotificationIds.Family.APPROVAL.idFor(requestId)

        /** The channels approval notifications are posted on, whatever the risk. */
        private val CHANNELS =
            setOf(NotificationChannels.AGENT_APPROVAL, NotificationChannels.AGENT_APPROVAL_DESTRUCTIVE)
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
     * @param requestId Identity of the request: the slot, and the answer the actions carry.
     * @param toolName The name of the tool to be executed.
     * @param arguments The arguments to be passed to the tool.
     * @param risk Risk classification, drives the channel / icon / title selection.
     */
    override fun sendApprovalRequest(
        sessionId: String,
        requestId: String,
        toolName: String,
        arguments: String,
        risk: ToolRisk,
    ) {
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
        val shade = shadeText(toolName, arguments)
        val address = RequestAddress(sessionId, requestId)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(shade.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addDecisionActions(
                approvable = risk != ToolRisk.DESTRUCTIVE && shade.whole,
                sessionId = sessionId,
                approveIntent = { decisionPendingIntent(address, ApprovalAction.APPROVE, APPROVE_OFFSET) },
                denyIntent = decisionPendingIntent(address, ApprovalAction.DENY, DENY_OFFSET),
            )
            .build()

        notificationManager.notify(notificationId(requestId), notification)
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
     * @param runId Id of the parked run, carried for the re-post of a dismissed notification.
     * @param sessionId The ID of the session that triggered the request.
     * @param requestId Identity of the parked request: the slot, and the answer the actions carry.
     * @param toolName The name of the tool to be executed.
     * @param arguments The arguments to be passed to the tool.
     * @param risk Risk classification, drives the channel / icon / title / actions.
     */
    override fun sendPersistentApprovalRequest(
        runId: String,
        sessionId: String,
        requestId: String,
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
        val shade = shadeText(toolName, arguments)
        val deepLink = chatDeepLinkIntent(sessionId)

        val address = RequestAddress(sessionId, requestId, runId)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(shade.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(deepLink)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setDeleteIntent(decisionPendingIntent(address, ApprovalAction.REPOST, REPOST_OFFSET))
            .addDecisionActions(
                approvable = risk != ToolRisk.DESTRUCTIVE && shade.whole,
                sessionId = sessionId,
                approveIntent = { decisionPendingIntent(address, ApprovalAction.APPROVE, APPROVE_OFFSET) },
                denyIntent = decisionPendingIntent(address, ApprovalAction.DENY, DENY_OFFSET),
            )

        notificationManager.notify(notificationId(requestId), builder.build())
    }

    /**
     * Removes the notification of request [requestId], if it is showing; the
     * notifications of other requests, of the same session included, stay.
     *
     * @param requestId The request whose notification to remove.
     */
    override fun cancelApprovalNotification(requestId: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId(requestId))
    }

    /**
     * Removes the approval notification a release before request addressing posted
     * for [sessionId] in the slot it keyed by session — only if the notification
     * showing there is an approval's (see [PreUpdateSlot]).
     *
     * @param sessionId The session the earlier release keyed the notification by.
     */
    override fun cancelPreUpdateNotification(sessionId: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        PreUpdateSlot.cancel(
            notificationManager,
            NotificationIds.PreUpdate.slot(NotificationIds.PreUpdate.APPROVAL_BASE, sessionId),
            CHANNELS,
        )
    }

    /**
     * Adds the decision actions of an approval notification — the one place
     * that decides what can be answered from the shade, shared by both
     * waiting phases so they cannot drift apart.
     *
     * A request that is not approvable from the shade gets a "Review in chat"
     * deep link in place of Approve. Two kinds are not: [ToolRisk.DESTRUCTIVE],
     * because the in-chat card asks for a typed confirmation before a
     * destructive call may run and a notification action cannot collect one;
     * and a request whose arguments the shade cannot show whole, because an
     * Approve there would authorise text the user never saw. [approveIntent] is
     * a factory so that no Approve intent is even created for either. Deny is
     * always offered — refusing needs no ceremony.
     *
     * @param approvable Whether the request may be approved from the shade.
     * @param sessionId Chat session the "Review in chat" link opens.
     * @param approveIntent Builds the Approve broadcast; invoked only when [approvable].
     * @param denyIntent The Deny broadcast.
     * @return this builder, for chaining.
     */
    private fun NotificationCompat.Builder.addDecisionActions(
        approvable: Boolean,
        sessionId: String,
        approveIntent: () -> PendingIntent,
        denyIntent: PendingIntent,
    ): NotificationCompat.Builder {
        if (!approvable) {
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

    /**
     * What the shade shows of one request, and whether that is all of it.
     *
     * @property text The expanded text of the notification.
     * @property whole `true` when [text] holds the complete argument string, so
     *   an Approve in the shade authorises only what the user can read there.
     */
    private data class ShadeText(val text: String, val whole: Boolean)

    /**
     * Renders a request's expanded text, cutting the arguments at
     * [SHADE_ARGUMENT_BUDGET] with a note that says so.
     *
     * @param toolName The tool the request is for.
     * @param arguments The argument string the request would authorise.
     * @return The text, and whether it carries the arguments whole.
     */
    private fun shadeText(toolName: String, arguments: String): ShadeText {
        if (arguments.length <= SHADE_ARGUMENT_BUDGET) {
            return ShadeText(context.getString(R.string.approval_notification_big_text, toolName, arguments), true)
        }
        // Never split a surrogate pair: a lone high surrogate renders as a box.
        val end = if (arguments[SHADE_ARGUMENT_BUDGET - 1].isHighSurrogate()) {
            SHADE_ARGUMENT_BUDGET - 1
        } else {
            SHADE_ARGUMENT_BUDGET
        }
        val shown = context.getString(R.string.approval_notification_big_text, toolName, arguments.take(end) + "…")
        return ShadeText(shown + "\n\n" + context.getString(R.string.approval_notification_arguments_cut), false)
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

    /**
     * Builds a broadcast [PendingIntent] carrying one request's answer (or its
     * re-post) to [AgentApprovalReceiver].
     *
     * The intent's identity is the request: [Intent.setIdentifier] takes part
     * in `Intent.filterEquals` while extras do not, so without it two requests
     * of one session would share one `PendingIntent`, and posting the second
     * would rewrite — through `FLAG_UPDATE_CURRENT` — the extras behind the
     * first notification's buttons to answer the second. The live and the
     * persistent intents of one request do share an identity, on purpose: the
     * park replaces the one notification with the other, and the update adds
     * the run id to the intents the live one already had.
     *
     * @param address The request the intent answers.
     * @param action The wire action to emit.
     * @param requestCodeOffset Disambiguates the actions of one request.
     */
    private fun decisionPendingIntent(
        address: RequestAddress,
        action: ApprovalAction,
        requestCodeOffset: Int,
    ): PendingIntent {
        val intent = Intent(context, AgentApprovalReceiver::class.java).apply {
            this.action = action.action
            identifier = address.requestId
            putExtra(AgentApprovalReceiver.EXTRA_SESSION_ID, address.sessionId)
            putExtra(AgentApprovalReceiver.EXTRA_REQUEST_ID, address.requestId)
            address.runId?.let { putExtra(AgentApprovalReceiver.EXTRA_RUN_ID, it) }
        }
        return PendingIntent.getBroadcast(
            context,
            address.requestId.hashCode() + requestCodeOffset,
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
            // The deep link is per session (its uri names the chat), not per
            // request: every request of a chat opens the same screen.
            getPendingIntent(
                sessionId.hashCode(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }

    /**
     * Where a decision intent points: the request it answers, the session it
     * belongs to, and — for a parked request — the run it parks, which the
     * re-post of a dismissed notification reads its record by.
     *
     * @property sessionId Id of the owning chat session.
     * @property requestId Identity of the request.
     * @property runId Id of the parked run; `null` for a live request.
     */
    private data class RequestAddress(val sessionId: String, val requestId: String, val runId: String? = null)
}
