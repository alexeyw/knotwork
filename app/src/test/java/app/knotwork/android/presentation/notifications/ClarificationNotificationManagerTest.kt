package app.knotwork.android.presentation.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import app.knotwork.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/**
 * Robolectric coverage for [ClarificationNotificationManager]: the parked run's
 * question is the model's, written from the conversation, so where the system
 * hides sensitive content the notification shows its title alone.
 */
@RunWith(RobolectricTestRunner::class)
class ClarificationNotificationManagerTest {

    private lateinit var context: Context
    private lateinit var manager: ClarificationNotificationManager

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        manager = ClarificationNotificationManager(context)
    }

    @Test
    fun `given a parked question when posted then the full text shows unlocked and the title alone when locked`() {
        manager.sendPersistentClarificationRequest("run-1", "s1", "Which of your two doctors should I write to?")

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = Shadows.shadowOf(notificationManager).allNotifications.single()
        val public = posted.publicVersion
        assertEquals(
            "Which of your two doctors should I write to?",
            posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertEquals(
            context.getString(R.string.clarification_notification_title),
            public.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertEquals(null, public.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(null, public.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertTrue(public.actions.isNullOrEmpty())
    }
}
