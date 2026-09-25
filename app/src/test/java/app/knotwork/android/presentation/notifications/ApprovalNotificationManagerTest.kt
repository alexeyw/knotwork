package app.knotwork.android.presentation.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import app.knotwork.android.R
import app.knotwork.android.domain.constants.NotificationChannels
import app.knotwork.android.domain.constants.NotificationIds
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.presentation.receivers.AgentApprovalReceiver
import app.knotwork.android.presentation.receivers.ApprovalAction
import app.knotwork.android.presentation.state.ActiveSessionTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/**
 * Robolectric coverage for [ApprovalNotificationManager] — the Human-in-the-loop
 * gate that surfaces tool-approval prompts in the system shade when the user is
 * not actively viewing the requesting chat session.
 *
 * The manager owns three risk-sensitive decisions (channel id, small icon, title)
 * plus the action-button `PendingIntent`s consumed by [AgentApprovalReceiver];
 * every one of those decisions is asserted against the real Android framework
 * objects via Robolectric (mockk-only would force fragile reflection over
 * `PendingIntent` extras and `Notification.actions`).
 */
@RunWith(RobolectricTestRunner::class)
class ApprovalNotificationManagerTest {

    private lateinit var context: Context
    private lateinit var activeSessionTracker: ActiveSessionTracker
    private lateinit var manager: ApprovalNotificationManager

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        activeSessionTracker = ActiveSessionTracker()
        manager = ApprovalNotificationManager(context, activeSessionTracker)
    }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun expectedNotificationId(requestId: String): Int = NotificationIds.Family.APPROVAL.idFor(requestId)

    @Test
    fun `given DESTRUCTIVE risk when sendApprovalRequest then posts on destructive channel with destructive title`() {
        manager.sendApprovalRequest("s1", "req-s1", "delete_file", "{\"path\":\"/x\"}", ToolRisk.DESTRUCTIVE)

        val shadow = Shadows.shadowOf(notificationManager())
        assertEquals(1, shadow.size())
        val notification = shadow.allNotifications.first()
        assertEquals(NotificationChannels.AGENT_APPROVAL_DESTRUCTIVE, notification.channelId)
        assertEquals(
            context.getString(R.string.approval_notification_title_destructive),
            notification.extras.getString(NotificationCompat.EXTRA_TITLE),
        )

        val channel = notificationManager().getNotificationChannel(NotificationChannels.AGENT_APPROVAL_DESTRUCTIVE)
        assertNotNull("Destructive channel must be registered on first send", channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun `given SENSITIVE risk when sendApprovalRequest then posts on sensitive channel with sensitive title`() {
        manager.sendApprovalRequest("s1", "req-s1", "send_email", "{\"to\":\"x\"}", ToolRisk.SENSITIVE)

        val shadow = Shadows.shadowOf(notificationManager())
        assertEquals(1, shadow.size())
        val notification = shadow.allNotifications.first()
        assertEquals(NotificationChannels.AGENT_APPROVAL, notification.channelId)
        assertEquals(
            context.getString(R.string.approval_notification_title_sensitive),
            notification.extras.getString(NotificationCompat.EXTRA_TITLE),
        )

        val channel = notificationManager().getNotificationChannel(NotificationChannels.AGENT_APPROVAL)
        assertNotNull("Sensitive channel must be registered on first send", channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun `given READ_ONLY risk when sendApprovalRequest then routes to the sensitive channel`() {
        // READ_ONLY shares the SENSITIVE channel by design — only DESTRUCTIVE is
        // split out so the user can tune visibility for irreversible actions.
        manager.sendApprovalRequest("s1", "req-s1", "search_tool", "{\"q\":\"x\"}", ToolRisk.READ_ONLY)

        val notification = Shadows.shadowOf(notificationManager()).allNotifications.first()
        assertEquals(NotificationChannels.AGENT_APPROVAL, notification.channelId)
    }

    @Test
    fun `given two sends in a row when sendApprovalRequest then both channels exist exactly once each`() {
        manager.sendApprovalRequest("s1", "req-s1", "t1", "{}", ToolRisk.SENSITIVE)
        manager.sendApprovalRequest("s2", "req-s2", "t2", "{}", ToolRisk.DESTRUCTIVE)

        // createNotificationChannel is idempotent; the call sites should not
        // produce duplicates regardless of how many times sendApprovalRequest fires.
        val channels = notificationManager().notificationChannels
            .map { it.id }
            .filter {
                it == NotificationChannels.AGENT_APPROVAL || it == NotificationChannels.AGENT_APPROVAL_DESTRUCTIVE
            }
        assertEquals(
            "Both approval channels must be registered exactly once",
            setOf(NotificationChannels.AGENT_APPROVAL, NotificationChannels.AGENT_APPROVAL_DESTRUCTIVE),
            channels.toSet(),
        )
        assertEquals(2, channels.size)
    }

    @Test
    fun `given active session matches sessionId when sendApprovalRequest then nothing is posted`() {
        activeSessionTracker.setActiveSessionId("s1")

        manager.sendApprovalRequest("s1", "req-s1", "delete_file", "{}", ToolRisk.DESTRUCTIVE)

        assertEquals(
            "Notification must be suppressed when the user is on the matching chat screen",
            0,
            Shadows.shadowOf(notificationManager()).size(),
        )
    }

    @Test
    fun `given active session differs from sessionId when sendApprovalRequest then notification is posted`() {
        activeSessionTracker.setActiveSessionId("other-session")

        manager.sendApprovalRequest("s1", "req-s1", "send_email", "{}", ToolRisk.SENSITIVE)

        assertEquals(1, Shadows.shadowOf(notificationManager()).size())
    }

    @Test
    fun `given a SENSITIVE send when actions are inspected then Approve and Deny intents carry the right extras`() {
        manager.sendApprovalRequest("session-42", "req-session-42", "send_email", "{\"to\":\"x\"}", ToolRisk.SENSITIVE)

        val notification = Shadows.shadowOf(notificationManager()).allNotifications.first()
        val actions = notification.actions
        assertNotNull("Notification must expose Approve/Deny actions", actions)
        assertEquals("Notification must expose exactly two actions", 2, actions.size)

        val approveAction = actions[0]
        val denyAction = actions[1]

        assertEquals(context.getString(R.string.chat_thought_approve), approveAction.title.toString())
        assertEquals(context.getString(R.string.chat_thought_deny), denyAction.title.toString())

        val approveIntent = Shadows.shadowOf(approveAction.actionIntent).savedIntent
        val denyIntent = Shadows.shadowOf(denyAction.actionIntent).savedIntent

        assertEquals(ApprovalAction.APPROVE.action, approveIntent.action)
        assertEquals(ApprovalAction.DENY.action, denyIntent.action)
        assertEquals("session-42", approveIntent.getStringExtra("sessionId"))
        assertEquals("session-42", denyIntent.getStringExtra("sessionId"))
        // Each action answers the request it was posted for, and is its own
        // PendingIntent: the identifier is part of `filterEquals`, extras are not.
        assertEquals("req-session-42", approveIntent.getStringExtra(AgentApprovalReceiver.EXTRA_REQUEST_ID))
        assertEquals("req-session-42", denyIntent.getStringExtra(AgentApprovalReceiver.EXTRA_REQUEST_ID))
        assertEquals("req-session-42", approveIntent.identifier)
        assertEquals("req-session-42", denyIntent.identifier)

        // Both PendingIntents target AgentApprovalReceiver.
        assertEquals(
            AgentApprovalReceiver::class.java.name,
            approveIntent.component?.className,
        )
        assertEquals(
            AgentApprovalReceiver::class.java.name,
            denyIntent.component?.className,
        )
    }

    @Test
    fun `given a DESTRUCTIVE live request when sendApprovalRequest then no approve action is attached`() {
        manager.sendApprovalRequest(
            "session-42",
            "req-session-42",
            "delete_file",
            "{\"path\":\"/x\"}",
            ToolRisk.DESTRUCTIVE,
        )

        val notification = Shadows.shadowOf(notificationManager()).allNotifications.single()
        assertFalse(
            "A destructive call must not be approvable from the shade: the typed confirmation lives in the chat",
            notification.approvesFromShade(),
        )
        assertEquals(
            listOf(
                context.getString(R.string.approval_notification_review_in_chat),
                context.getString(R.string.chat_thought_deny),
            ),
            notification.actions.map { it.title.toString() },
        )
        val denyIntent = Shadows.shadowOf(notification.actions[1].actionIntent).savedIntent
        assertEquals(ApprovalAction.DENY.action, denyIntent.action)
        assertEquals("session-42", denyIntent.getStringExtra(AgentApprovalReceiver.EXTRA_SESSION_ID))
    }

    @Test
    fun `given either approval surface and any risk when posted then Approve is offered only if not destructive`() {
        // Guard for the class, not the instance: the live and the persistent
        // notification are two builders of one decision surface, and a
        // destructive call must be unapprovable from both. A third surface
        // added to the notifier belongs in this list.
        val surfaces = listOf<Pair<String, (ToolRisk) -> Unit>>(
            "live" to { risk -> manager.sendApprovalRequest("s1", "req-s1", "t", "{}", risk) },
            "persistent" to { risk ->
                manager.sendPersistentApprovalRequest("run-1", "s1", "req-run-1", "t", "{}", risk)
            },
        )
        val failures = mutableListOf<String>()
        for ((surface, post) in surfaces) {
            for (risk in ToolRisk.entries) {
                notificationManager().cancelAll()
                post(risk)

                val notification = Shadows.shadowOf(notificationManager()).allNotifications.single()
                val expected = risk != ToolRisk.DESTRUCTIVE
                if (notification.approvesFromShade() != expected) {
                    failures += "$surface/$risk: Approve offered=${!expected}, expected=$expected"
                }
            }
        }
        assertTrue(failures.joinToString(separator = "\n"), failures.isEmpty())
    }

    @Test
    fun `given a parked request's notification when a later request of the same chat is posted then both show`() {
        // A parked run's ongoing notification is its primary way back. A later
        // request in the same chat (a scheduled task, a trigger, the next
        // message) must get its own notification, not take the parked one's.
        manager.sendPersistentApprovalRequest("run-1", "s1", "req-parked", "send_message", "{}", ToolRisk.SENSITIVE)
        manager.sendApprovalRequest("s1", "req-live", "delete_file", "{}", ToolRisk.DESTRUCTIVE)

        assertEquals(2, Shadows.shadowOf(notificationManager()).size())
    }

    /** `true` when any action of this notification sends the Approve wire action. */
    private fun Notification.approvesFromShade(): Boolean = actions.orEmpty().any { action ->
        Shadows.shadowOf(action.actionIntent).savedIntent?.action == ApprovalAction.APPROVE.action
    }

    @Test
    fun `given any send when PendingIntents are inspected then both carry FLAG_IMMUTABLE`() {
        manager.sendApprovalRequest(
            "session-immutable",
            "req-session-immutable",
            "delete_file",
            "{}",
            ToolRisk.DESTRUCTIVE,
        )

        val notification = Shadows.shadowOf(notificationManager()).allNotifications.first()
        notification.actions.forEach { action ->
            val flags = Shadows.shadowOf(action.actionIntent).flags
            // Robolectric's ShadowPendingIntent exposes the creation flags via getFlags();
            // FLAG_IMMUTABLE is mandatory on API 31+ and the manager always sets it.
            assertTrue(
                "Action ${action.title} must use an immutable PendingIntent",
                (flags and PendingIntent.FLAG_IMMUTABLE) != 0,
            )
        }
    }

    @Test
    fun `given two requests of one session when sendApprovalRequest then each gets a notification of its own`() {
        // Use ids whose hashes land in different slots of the approval family.
        val requestA = "alpha"
        val requestB = "beta"
        // Sanity-check the precondition for the assertion below — if hashes collide
        // into one slot the test would assert nothing useful.
        assertNotEquals(
            "Precondition: chosen request ids must hash to distinct slots",
            expectedNotificationId(requestA),
            expectedNotificationId(requestB),
        )

        manager.sendApprovalRequest("s1", requestA, "t", "{}", ToolRisk.SENSITIVE)
        manager.sendApprovalRequest("s1", requestB, "t", "{}", ToolRisk.SENSITIVE)

        val shadow = Shadows.shadowOf(notificationManager())
        assertEquals("Distinct requests must produce distinct notifications", 2, shadow.size())
        // And the earlier notification still answers its own request: posting
        // the second must not have rewritten the first one's buttons.
        val first = shadow.getNotification(expectedNotificationId(requestA))
        val firstApprove = Shadows.shadowOf(first.actions.first().actionIntent).savedIntent
        assertEquals(requestA, firstApprove.getStringExtra(AgentApprovalReceiver.EXTRA_REQUEST_ID))
    }

    @Test
    fun `given the same request posted twice when sendApprovalRequest then it replaces itself`() {
        manager.sendApprovalRequest("same", "req-same", "t", "{}", ToolRisk.SENSITIVE)
        manager.sendApprovalRequest("same", "req-same", "t", "{}", ToolRisk.SENSITIVE)

        // The second post must replace the first via stable id, not stack.
        assertEquals(1, Shadows.shadowOf(notificationManager()).size())
    }

    @Test
    fun `given a request that parks when the persistent notification is posted then it replaces the live one`() {
        manager.sendApprovalRequest("s1", "req-1", "t", "{}", ToolRisk.SENSITIVE)
        manager.sendPersistentApprovalRequest("run-1", "s1", "req-1", "t", "{}", ToolRisk.SENSITIVE)

        val shadow = Shadows.shadowOf(notificationManager())
        assertEquals(1, shadow.size())
        val approve = Shadows.shadowOf(shadow.allNotifications.single().actions.first().actionIntent).savedIntent
        assertEquals("req-1", approve.getStringExtra(AgentApprovalReceiver.EXTRA_REQUEST_ID))
        assertEquals("run-1", approve.getStringExtra(AgentApprovalReceiver.EXTRA_RUN_ID))
    }

    @Test
    fun `given two requests of one session when one is cancelled then the other stays`() {
        manager.sendPersistentApprovalRequest("run-1", "s1", "alpha", "send_message", "{}", ToolRisk.SENSITIVE)
        manager.sendApprovalRequest("s1", "beta", "delete_file", "{}", ToolRisk.DESTRUCTIVE)

        manager.cancelApprovalNotification("beta")

        val shadow = Shadows.shadowOf(notificationManager())
        assertEquals(1, shadow.size())
        assertNotNull(shadow.getNotification(expectedNotificationId("alpha")))
    }

    @Test
    fun `given any send when content is inspected then BigTextStyle carries tool and args`() {
        manager.sendApprovalRequest("s1", "req-s1", "delete_file", "{\"path\":\"/var/log\"}", ToolRisk.DESTRUCTIVE)

        val notification = Shadows.shadowOf(notificationManager()).allNotifications.first()
        val bigText = notification.extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)?.toString()
        assertNotNull("BigTextStyle must be applied", bigText)
        assertEquals(
            context.getString(R.string.approval_notification_big_text, "delete_file", "{\"path\":\"/var/log\"}"),
            bigText,
        )
        val contentText = notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()
        assertEquals(
            context.getString(R.string.approval_notification_text, "delete_file"),
            contentText,
        )
    }

    @Test
    fun `given any send when notification flags are inspected then auto-cancel is set`() {
        manager.sendApprovalRequest("s1", "req-s1", "t", "{}", ToolRisk.SENSITIVE)

        val notification = Shadows.shadowOf(notificationManager()).allNotifications.first()
        assertEquals(
            "Notification must auto-cancel on tap",
            Notification.FLAG_AUTO_CANCEL,
            notification.flags and Notification.FLAG_AUTO_CANCEL,
        )
        // High-priority surface so heads-up appears on pre-API-26 devices. On
        // modern Android channel importance (asserted per-risk above) supersedes
        // this — the field is deprecated but the builder still sets it.
        @Suppress("DEPRECATION")
        val notificationPriority = notification.priority
        assertEquals(NotificationCompat.PRIORITY_HIGH, notificationPriority)
    }

    @Test
    fun `given any send when posted then notification id matches the documented partition formula`() {
        val requestId = "deterministic-id"
        manager.sendApprovalRequest("s1", requestId, "t", "{}", ToolRisk.SENSITIVE)

        val expectedId = expectedNotificationId(requestId)
        // The formula is shared with the receiver through `notificationId`; pinning it
        // here keeps the slot of a request stable across releases.
        assertNotNull(
            "Notification must be posted at the documented slot",
            Shadows.shadowOf(notificationManager()).getNotification(expectedId),
        )
    }
}
