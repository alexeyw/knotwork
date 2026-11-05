package app.knotwork.android

import android.app.NotificationManager
import android.content.res.Configuration
import app.knotwork.android.domain.constants.NotificationChannels
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [App] registers the task-results channel again on a configuration change, so
 * its name follows a language picked for the app. The other channels are
 * registered before each notification they post; this one only at start.
 */
@RunWith(RobolectricTestRunner::class)
class AppConfigurationChangeTest {

    @Test
    fun `given the task-results channel is gone when the configuration changes then it is registered again`() {
        val app = RuntimeEnvironment.getApplication() as App
        val notifications = app.getSystemService(NotificationManager::class.java)
        notifications.deleteNotificationChannel(NotificationChannels.TASK_RESULTS)
        assertNull(notifications.getNotificationChannel(NotificationChannels.TASK_RESULTS))

        app.onConfigurationChanged(Configuration(app.resources.configuration))

        assertNotNull(notifications.getNotificationChannel(NotificationChannels.TASK_RESULTS))
    }
}
