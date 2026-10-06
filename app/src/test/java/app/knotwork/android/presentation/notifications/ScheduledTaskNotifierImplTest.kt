package app.knotwork.android.presentation.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import app.knotwork.android.domain.constants.NotificationChannels
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/**
 * Robolectric coverage for [ScheduledTaskNotifierImpl] — the notifier that
 * announces scheduled-run outcomes ("Task completed" / "Task failed") with a
 * deep-link into the bound chat session. The notifier is double-gated (the
 * "Scheduled task results" settings toggle + the `POST_NOTIFICATIONS` runtime
 * permission); both gates are exercised here along with the channel contract
 * and the deep-link payload.
 */
@RunWith(RobolectricTestRunner::class)
class ScheduledTaskNotifierImplTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var notifier: ScheduledTaskNotifierImpl

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        settings = mockk()
        notifier = ScheduledTaskNotifierImpl(context, settings)
    }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun grantPostNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Shadows.shadowOf(context as android.app.Application)
                .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun denyPostNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Shadows.shadowOf(context as android.app.Application)
                .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun enable() {
        every { settings.scheduledTaskNotificationsEnabled } returns flowOf(true)
        grantPostNotifications()
        notifier.registerChannel()
    }

    @Test
    fun `given registerChannel is called then TASK_RESULTS channel is created with IMPORTANCE_DEFAULT`() {
        notifier.registerChannel()

        val channel = notificationManager().getNotificationChannel(NotificationChannels.TASK_RESULTS)
        assertNotNull("Channel must be registered after registerChannel()", channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals("Scheduled task results", channel.name)
    }

    @Test
    fun `given notifications disabled when notifyCompleted is called then nothing is posted`() = runTest {
        every { settings.scheduledTaskNotificationsEnabled } returns flowOf(false)
        grantPostNotifications()

        notifier.notifyCompleted("session-1", "All done.")

        assertEquals(0, Shadows.shadowOf(notificationManager()).size())
    }

    @Test
    fun `given POST_NOTIFICATIONS denied when notifyCompleted is called then nothing is posted`() = runTest {
        every { settings.scheduledTaskNotificationsEnabled } returns flowOf(true)
        denyPostNotifications()

        notifier.notifyCompleted("session-1", "All done.")

        assertEquals(0, Shadows.shadowOf(notificationManager()).size())
    }

    @Test
    fun `given the settings flow is empty when notifyCompleted is called then nothing is posted`() = runTest {
        // `firstOrNull()` returns null when the flow is empty; the impl maps
        // that to "feature disabled" (?: false) so an unreadable preferences
        // file never produces a surprise notification.
        every { settings.scheduledTaskNotificationsEnabled } returns flowOf()
        grantPostNotifications()

        notifier.notifyCompleted("session-1", "All done.")

        assertEquals(0, Shadows.shadowOf(notificationManager()).size())
    }

    @Test
    fun `given enabled when notifyCompleted is called then posts completed title with preview body`() = runTest {
        enable()

        notifier.notifyCompleted("session-1", "Summary is ready.")

        val shadow = Shadows.shadowOf(notificationManager())
        assertEquals(1, shadow.size())
        val posted = shadow.allNotifications.first()
        assertEquals(NotificationChannels.TASK_RESULTS, posted.channelId)
        val postedShadow = Shadows.shadowOf(posted)
        assertEquals("Task completed", postedShadow.contentTitle)
        assertEquals("Summary is ready.", postedShadow.contentText)
    }

    @Test
    fun `given a run's answer when notified then a locked screen shows the title only`() = runTest {
        enable()

        notifier.notifyCompleted("session-1", "Your blood test results are normal.")

        val posted = Shadows.shadowOf(notificationManager()).allNotifications.single()
        val public = posted.publicVersion
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertEquals("Task completed", public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(null, public.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(null, public.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
    }

    @Test
    fun `given enabled when notifyTriggerFired is called then posts fired title with trigger name body`() = runTest {
        enable()

        notifier.notifyTriggerFired("session-1", "Morning brief")

        val shadow = Shadows.shadowOf(notificationManager())
        assertEquals(1, shadow.size())
        val posted = shadow.allNotifications.first()
        assertEquals(NotificationChannels.TASK_RESULTS, posted.channelId)
        val postedShadow = Shadows.shadowOf(posted)
        assertEquals("Trigger fired", postedShadow.contentTitle)
        assertEquals("Morning brief", postedShadow.contentText)
    }

    @Test
    fun `given notifications disabled when notifyTriggerFired is called then nothing is posted`() = runTest {
        every { settings.scheduledTaskNotificationsEnabled } returns flowOf(false)
        grantPostNotifications()

        notifier.notifyTriggerFired("session-1", "Morning brief")

        assertEquals(0, Shadows.shadowOf(notificationManager()).size())
    }

    @Test
    fun `given a fired notification then its tap action deep-links into the bound session`() = runTest {
        enable()

        notifier.notifyTriggerFired("session-7", "Morning brief")

        val posted = Shadows.shadowOf(notificationManager()).allNotifications.first()
        val savedIntent: Intent = Shadows.shadowOf(posted.contentIntent).savedIntent
        assertEquals("knotwork://chat/session-7", savedIntent.data.toString())
    }

    @Test
    fun `given a fired notification when the outcome lands on the same session then it is superseded`() = runTest {
        enable()

        notifier.notifyTriggerFired("session-a", "Morning brief")
        notifier.notifyCompleted("session-a", "Summary is ready.")

        // Same session id → the outcome replaces the "fired" ping, never stacks.
        val shadow = Shadows.shadowOf(notificationManager())
        assertEquals(1, shadow.size())
        assertEquals("Task completed", Shadows.shadowOf(shadow.allNotifications.first()).contentTitle)
    }

    @Test
    fun `given enabled when notifyFailed is called then posts failed title with reason body`() = runTest {
        enable()

        notifier.notifyFailed("session-1", "No default pipeline configured")

        val shadow = Shadows.shadowOf(notificationManager())
        assertEquals(1, shadow.size())
        val postedShadow = Shadows.shadowOf(shadow.allNotifications.first())
        assertEquals("Task failed", postedShadow.contentTitle)
        assertEquals("No default pipeline configured", postedShadow.contentText)
    }

    @Test
    fun `given a posted notification then its tap action deep-links into the session chat`() = runTest {
        enable()

        notifier.notifyCompleted("session-42", "Done.")

        val posted = Shadows.shadowOf(notificationManager()).allNotifications.first()
        val contentIntent = posted.contentIntent
        assertNotNull("Completion notification must carry a tap action", contentIntent)
        val savedIntent: Intent = Shadows.shadowOf(contentIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, savedIntent.action)
        assertEquals("knotwork://chat/session-42", savedIntent.data.toString())
    }

    @Test
    fun `given two sessions when both notify then notifications do not replace each other`() = runTest {
        enable()

        notifier.notifyCompleted("session-a", "Done A.")
        notifier.notifyFailed("session-b", "Failed B.")

        // Distinct session ids must map to distinct notification ids — a
        // collision would silently replace the first announcement.
        assertEquals(2, Shadows.shadowOf(notificationManager()).size())
    }

    @Test
    fun `given the same session twice when notified then the later outcome replaces the earlier`() = runTest {
        enable()

        notifier.notifyCompleted("session-a", "First run done.")
        notifier.notifyCompleted("session-a", "Second run done.")

        // Same session — stable id, the shade shows only the latest outcome.
        assertEquals(1, Shadows.shadowOf(notificationManager()).size())
    }
}
