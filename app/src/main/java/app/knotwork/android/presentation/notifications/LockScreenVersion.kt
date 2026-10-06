package app.knotwork.android.presentation.notifications

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat

/**
 * Makes this notification show only its title where the system hides sensitive
 * content — on a secure lock screen set to hide it, or while the screen is
 * shared — and its full text once the device is unlocked.
 *
 * For a notification that carries what the user or the model wrote: a tool call
 * awaiting approval and the request behind it, a clarifying question, a run's
 * answer. The visibility is set explicitly ([NotificationCompat.VISIBILITY_PRIVATE],
 * the platform default) so the choice is written down, and the public version
 * replaces the platform's own redacted view, which would show the app name alone.
 * It holds the same title on the same channel with the same icon, and nothing
 * else: no text and no actions, so nothing can be answered from it.
 *
 * Whether the full or the public version appears on the lock screen is the
 * user's system setting, not the app's: with "show all notification content" the
 * full text is shown there too.
 *
 * @param context Context the public version is built with.
 * @param channelId Channel of the notification, reused by its public version.
 * @param smallIcon Small icon of the notification, reused by its public version.
 * @param title The title — the only text the public version shows.
 * @return this builder, for chaining.
 */
fun NotificationCompat.Builder.lockScreenVersion(
    context: Context,
    channelId: String,
    @DrawableRes smallIcon: Int,
    title: String,
): NotificationCompat.Builder = setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    .setPublicVersion(
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .build(),
    )
