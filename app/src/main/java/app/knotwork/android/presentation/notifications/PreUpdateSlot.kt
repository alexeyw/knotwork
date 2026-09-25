package app.knotwork.android.presentation.notifications

import android.app.NotificationManager
import app.knotwork.android.domain.constants.NotificationIds

/**
 * Clears a notification that a release before [NotificationIds] posted in its old slot.
 *
 * The old slots overlapped each other and the fixed ids, so the id alone does not say
 * whose notification sits there: another family's may. A slot is cleared only when the
 * notification in it is on one of the calling family's channels — which, since no
 * current id falls into the old ranges, can only be the notification that family
 * posted before the update.
 */
internal object PreUpdateSlot {

    /**
     * Cancels [id] if a notification on one of [channelIds] is showing there.
     *
     * @param notificationManager The system notification manager.
     * @param id The pre-update slot, from [NotificationIds.PreUpdate.slot].
     * @param channelIds The channels the calling family posts on.
     */
    fun cancel(notificationManager: NotificationManager, id: Int, channelIds: Set<String>) {
        val showing = notificationManager.activeNotifications.any { active ->
            active.id == id && active.tag == null && active.notification.channelId in channelIds
        }
        if (showing) notificationManager.cancel(id)
    }
}
