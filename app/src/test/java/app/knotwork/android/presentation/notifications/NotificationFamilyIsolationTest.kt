package app.knotwork.android.presentation.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import app.knotwork.android.domain.constants.NotificationChannels
import app.knotwork.android.domain.constants.NotificationIds
import app.knotwork.android.domain.models.HardCeilingBreach
import app.knotwork.android.domain.models.RunCeilingAxis
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.presentation.state.ActiveSessionTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/**
 * The notification families are posted by three managers that know nothing of each
 * other; these tests pin what they share — the id space — against the real
 * `NotificationManager`.
 *
 * Two properties: a notification of one family is never replaced or removed by
 * another family's `notify` / `cancel`, and a notification posted by a release
 * before the id registry, still in the shade after the update, is removed when its
 * run is settled — but only if the slot really holds that family's notification.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationFamilyIsolationTest {

    private lateinit var context: Context
    private lateinit var approvals: ApprovalNotificationManager
    private lateinit var clarifications: ClarificationNotificationManager
    private lateinit var ceilings: CeilingNotificationManager

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        val tracker = ActiveSessionTracker()
        approvals = ApprovalNotificationManager(context, tracker)
        clarifications = ClarificationNotificationManager(context)
        ceilings = CeilingNotificationManager(context, tracker)
    }

    @Test
    fun `given approval and question sharing an old slot when the approval settles then the question stays`() {
        // Keys chosen so that the formulas before the registry put both at one id:
        // posting the approval replaced the parked question, and settling the
        // approval then removed the run's only way back.
        val (requestId, sessionId) = keysWithCoincidingOldSlots(
            NotificationIds.PreUpdate.APPROVAL_BASE,
            NotificationIds.PreUpdate.CLARIFICATION_BASE,
        )
        clarifications.sendPersistentClarificationRequest("run-c", sessionId, "Which folder?")
        approvals.sendPersistentApprovalRequest(
            "run-a",
            "other-session",
            requestId,
            "send_email",
            "{}",
            ToolRisk.SENSITIVE,
        )

        assertEquals("posting the approval must not replace the question", 2, shade().size())

        approvals.cancelApprovalNotification(requestId)

        val remaining = shade().allNotifications
        assertEquals(1, remaining.size)
        assertEquals(NotificationChannels.AGENT_CLARIFICATION, remaining.single().channelId)
    }

    @Test
    fun `given pause and question sharing an old slot when the question settles then the pause stays`() {
        val (ceilingSession, clarificationSession) = keysWithCoincidingOldSlots(
            NotificationIds.PreUpdate.CEILING_BASE,
            NotificationIds.PreUpdate.CLARIFICATION_BASE,
        )
        ceilings.sendCeilingPauseRequest("run-p", ceilingSession, HardCeilingBreach(RunCeilingAxis.STEPS, 40, 40))
        clarifications.sendPersistentClarificationRequest("run-c", clarificationSession, "Which folder?")

        clarifications.cancelClarificationNotification(clarificationSession)

        assertEquals(NotificationChannels.AGENT_RUN_CEILING, shade().allNotifications.single().channelId)
    }

    @Test
    fun `given a pre-update clarification in the shade when its session's question settles then it is removed`() {
        val oldSlot = NotificationIds.PreUpdate.slot(NotificationIds.PreUpdate.CLARIFICATION_BASE, "s1")
        post(oldSlot, NotificationChannels.AGENT_CLARIFICATION)

        clarifications.cancelClarificationNotification("s1")

        assertNull(shade().getNotification(oldSlot))
    }

    @Test
    fun `given another family's notification in a pre-update slot when a question settles then it stays`() {
        // The old slots overlapped everything, so an id alone does not say whose
        // notification it is — the channel does.
        val oldSlot = NotificationIds.PreUpdate.slot(NotificationIds.PreUpdate.CLARIFICATION_BASE, "s1")
        post(oldSlot, NotificationChannels.AGENT_APPROVAL)

        clarifications.cancelClarificationNotification("s1")

        assertNotNull(shade().getNotification(oldSlot))
    }

    @Test
    fun `given a pre-update ceiling pause in the shade when its session's pause settles then it is removed`() {
        val oldSlot = NotificationIds.PreUpdate.slot(NotificationIds.PreUpdate.CEILING_BASE, "s1")
        post(oldSlot, NotificationChannels.AGENT_RUN_CEILING)

        ceilings.cancelCeilingNotification("s1")

        assertNull(shade().getNotification(oldSlot))
    }

    @Test
    fun `given a pre-update approval on either approval channel when its session is cleared then it is removed`() {
        for (channel in listOf(NotificationChannels.AGENT_APPROVAL, NotificationChannels.AGENT_APPROVAL_DESTRUCTIVE)) {
            val oldSlot = NotificationIds.PreUpdate.slot(NotificationIds.PreUpdate.APPROVAL_BASE, "s1")
            post(oldSlot, channel)

            approvals.cancelPreUpdateNotification("s1")

            assertNull("left on $channel", shade().getNotification(oldSlot))
        }
    }

    @Test
    fun `given another family's notification in the pre-update approval slot when cleared then it stays`() {
        val oldSlot = NotificationIds.PreUpdate.slot(NotificationIds.PreUpdate.APPROVAL_BASE, "s1")
        post(oldSlot, NotificationChannels.AGENT_RUN_CEILING)

        approvals.cancelPreUpdateNotification("s1")

        assertNotNull(shade().getNotification(oldSlot))
    }

    private fun shade() = Shadows.shadowOf(
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager,
    )

    private fun post(id: Int, channelId: String) {
        val notification = Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("posted by an earlier release")
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification)
    }

    /**
     * A pair of keys the pre-registry formulas mapped to one id, one per family:
     * `firstBase + first % 1000 == secondBase + second % 1000`.
     */
    private fun keysWithCoincidingOldSlots(firstBase: Int, secondBase: Int): Pair<String, String> {
        val secondBySlot = (0 until SEARCH).associateBy { NotificationIds.PreUpdate.slot(secondBase, "session-$it") }
        val first = (0 until SEARCH).first { NotificationIds.PreUpdate.slot(firstBase, "request-$it") in secondBySlot }
        val second = secondBySlot.getValue(NotificationIds.PreUpdate.slot(firstBase, "request-$first"))
        return "request-$first" to "session-$second"
    }

    private companion object {
        /** Keys tried per family; a few thousand cover every slot of the old 2000-wide fold. */
        const val SEARCH = 5_000
    }
}
