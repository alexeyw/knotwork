package app.knotwork.android.presentation.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.knotwork.android.R
import app.knotwork.android.domain.constants.NotificationChannels
import app.knotwork.android.domain.constants.NotificationIds
import app.knotwork.android.domain.models.RunTerminationKind
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.ScheduledTaskNotifier
import app.knotwork.android.presentation.ui.common.RunTerminationCopyMapper
import app.knotwork.android.presentation.ui.common.RunTerminationTone
import app.knotwork.android.presentation.ui.common.resolve
import app.knotwork.android.presentation.ui.navigation.ChatDeepLink
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [ScheduledTaskNotifier] backed by [NotificationManagerCompat].
 *
 * Lives in the presentation layer (next to [ApprovalNotificationManager])
 * because the tap action deep-links into the app via the shared [ChatDeepLink]
 * (`knotwork://chat/{threadId}`) navigation pattern — a dependency the data
 * layer must not carry. Background components consume the domain-level
 * [ScheduledTaskNotifier] interface and stay presentation-agnostic.
 *
 * Posting is gated twice: by the user's "Scheduled task results" Settings
 * toggle and by the POST_NOTIFICATIONS runtime permission. When either gate
 * is closed the post is silently skipped — the run's result still lands in
 * the chat session, so nothing is lost beyond the announcement.
 */
@Singleton
class ScheduledTaskNotifierImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) : ScheduledTaskNotifier {

    override fun registerChannel() {
        val channel = NotificationChannelCompat.Builder(
            NotificationChannels.TASK_RESULTS,
            NotificationManager.IMPORTANCE_DEFAULT,
        )
            .setName(context.getString(R.string.notifications_task_results_channel_name))
            .setDescription(context.getString(R.string.notifications_task_results_channel_description))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    override suspend fun notifyTriggerFired(sessionId: String, triggerName: String) {
        post(
            sessionId = sessionId,
            title = context.getString(R.string.notifications_trigger_fired_title),
            body = triggerName,
            icon = R.drawable.ic_stat_agent,
        )
    }

    override suspend fun notifyCompleted(sessionId: String, resultPreview: String) {
        post(
            sessionId = sessionId,
            title = context.getString(R.string.notifications_task_completed_title),
            body = resultPreview,
            icon = R.drawable.ic_notif_done,
        )
    }

    override suspend fun notifyFailed(sessionId: String, reason: String) {
        post(
            sessionId = sessionId,
            title = context.getString(R.string.notifications_task_failed_title),
            body = reason,
            icon = R.drawable.ic_notif_failed,
        )
    }

    override suspend fun notifyTerminated(
        sessionId: String,
        kind: RunTerminationKind,
        runLabel: String,
        stepsSpent: Int,
        tokensSpent: Int,
    ) {
        // Same vocabulary as the chat tile and the trigger journal — resolved
        // from the typed cause rather than composed here, so this surface
        // cannot be the one that drifts.
        val copy = RunTerminationCopyMapper.notificationCopy(
            kind = kind,
            runLabel = runLabel,
            stepsSpent = stepsSpent,
            tokensSpent = tokensSpent,
        )
        post(
            sessionId = sessionId,
            title = context.resolve(copy.title),
            body = context.resolve(copy.body),
            // The shield is earned, not given to every typed cause. A limit the
            // user configured did its job, and announcing that as a failure is
            // what taught users to distrust their own automations. A run the
            // watchdog killed, or one the platform killed, did not do what it
            // was asked — dressing those as a working guard is the same mistake
            // pointing the other way.
            icon = when (RunTerminationCopyMapper.toneFor(kind)) {
                RunTerminationTone.LIMIT -> R.drawable.ic_notif_limit
                // Everything else keeps the did-not-complete cross, whose own
                // definition already covers this set: "a background run also
                // fails because the platform killed the process". The success
                // tick was briefly used here and was simply wrong — it is the
                // glyph for a task that finished, and it doubles as Approve.
                RunTerminationTone.STUCK, RunTerminationTone.INFO -> R.drawable.ic_notif_failed
            },
        )
    }

    @SuppressLint("MissingPermission") // hasPostNotificationsPermission() gates the call below.
    private suspend fun post(sessionId: String, title: String, body: String, icon: Int) {
        val enabled = settingsRepository.scheduledTaskNotificationsEnabled.firstOrNull() ?: false
        if (!enabled) return
        if (!hasPostNotificationsPermission()) return
        val notification = NotificationCompat.Builder(context, NotificationChannels.TASK_RESULTS)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(chatDeepLinkIntent(sessionId))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(sessionId), notification)
    }

    /**
     * Builds the tap action: an activity [PendingIntent] deep-linking into the
     * session via the shared [ChatDeepLink] back-stack builder (the
     * `knotwork://chat/{threadId}` pattern registered on the chat destination),
     * so Back from the deep-linked chat lands on the app's start destination
     * instead of the launcher.
     */
    private fun chatDeepLinkIntent(sessionId: String): PendingIntent? =
        ChatDeepLink.backStack(context, sessionId).getPendingIntent(
            notificationId(sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun hasPostNotificationsPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

    private fun notificationId(sessionId: String): Int = NotificationIds.Family.TASK_RESULT.idFor(sessionId)
}
