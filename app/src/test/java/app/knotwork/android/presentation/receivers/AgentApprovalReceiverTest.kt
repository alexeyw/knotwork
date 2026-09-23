package app.knotwork.android.presentation.receivers

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import app.knotwork.android.domain.models.PendingInteraction
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.services.ApprovalNotifier
import app.knotwork.android.domain.services.ClarificationNotifier
import app.knotwork.android.domain.usecases.SubmitApprovalDecisionUseCase
import app.knotwork.android.presentation.notifications.ApprovalNotificationManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/**
 * Robolectric coverage for [AgentApprovalReceiver] — the broadcast receiver that
 * routes the Approve / Deny notification actions through
 * [SubmitApprovalDecisionUseCase] (live gate or parked record) and re-posts
 * persistent notifications on [ApprovalAction.REPOST].
 *
 * The receiver is `@AndroidEntryPoint`; rather than spin up a full
 * `HiltTestApplication` runner just to instantiate one receiver, we flip the
 * private `injected` flag in the generated `Hilt_AgentApprovalReceiver`
 * superclass via reflection and assign the `@Inject lateinit var` fields by
 * hand (same pattern used by `AgentForegroundServiceTest`). The receiver's
 * own scope is replaced with an unconfined test scope so the bridged
 * suspending work completes synchronously inside each test body.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AgentApprovalReceiverTest {

    private lateinit var context: Context
    private lateinit var submitDecision: SubmitApprovalDecisionUseCase
    private lateinit var pendingInteractionRepository: PendingInteractionRepository
    private lateinit var approvalNotifier: ApprovalNotifier
    private lateinit var clarificationNotifier: ClarificationNotifier
    private lateinit var receiver: AgentApprovalReceiver

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        submitDecision = mockk(relaxed = true)
        pendingInteractionRepository = mockk(relaxed = true)
        approvalNotifier = mockk(relaxed = true)
        clarificationNotifier = mockk(relaxed = true)
        receiver = AgentApprovalReceiver().also { instance ->
            // Bypass Hilt's auto-inject — flip the generated `injected` flag and assign
            // the `@Inject lateinit var`s directly. See class KDoc for rationale.
            val injectedField = instance.javaClass.superclass!!.getDeclaredField("injected")
            injectedField.isAccessible = true
            injectedField.setBoolean(instance, true)
            instance.submitApprovalDecisionUseCase = submitDecision
            instance.pendingInteractionRepository = pendingInteractionRepository
            instance.approvalNotifier = approvalNotifier
            instance.clarificationNotifier = clarificationNotifier
        }
    }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Posts a placeholder notification at [id], standing in for one the receiver must remove. */
    private fun postPlaceholder(id: Int) {
        val notification = Notification.Builder(context, "test-channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("placeholder")
            .build()
        notificationManager().notify(id, notification)
    }

    private fun intent(action: String?, sessionId: String?, runId: String? = null, requestId: String? = null): Intent =
        Intent().apply {
            if (action != null) this.action = action
            if (sessionId != null) putExtra(AgentApprovalReceiver.EXTRA_SESSION_ID, sessionId)
            if (runId != null) putExtra(AgentApprovalReceiver.EXTRA_RUN_ID, runId)
            if (requestId != null) putExtra(AgentApprovalReceiver.EXTRA_REQUEST_ID, requestId)
        }

    /** Runs [body] with the receiver's scope bound to the test's unconfined dispatcher. */
    private fun runReceiverTest(body: suspend () -> Unit) = runTest {
        receiver.scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        body()
    }

    @Test
    fun `given APPROVE for a request when onReceive then the decision names that request`() = runReceiverTest {
        receiver.onReceive(context, intent(ApprovalAction.APPROVE.action, "s1", requestId = "request-1"))

        coVerify(exactly = 1) { submitDecision("s1", "request-1", true) }
        confirmVerified(submitDecision)
        verify { approvalNotifier.cancelApprovalNotification("request-1") }
    }

    @Test
    fun `given DENY for a request when onReceive then the decision names that request`() = runReceiverTest {
        receiver.onReceive(context, intent(ApprovalAction.DENY.action, "s1", requestId = "request-1"))

        coVerify(exactly = 1) { submitDecision("s1", "request-1", false) }
        confirmVerified(submitDecision)
    }

    @Test
    fun `given a parked request's action with its run too when onReceive then the request is what is answered`() =
        runReceiverTest {
            receiver.onReceive(
                context,
                intent(ApprovalAction.APPROVE.action, "s1", runId = "run-1", requestId = "request-1"),
            )

            coVerify(exactly = 1) { submitDecision("s1", "request-1", true) }
        }

    @Test
    fun `given a pre-update parked notification when onReceive then its run is answered and its old slot cleared`() =
        runReceiverTest {
            // Posted by a release that keyed notifications by session and named
            // only the run; the store back-filled that run id as the request id.
            postPlaceholder(ApprovalNotificationManager.legacySessionNotificationId("s1"))

            receiver.onReceive(context, intent(ApprovalAction.APPROVE.action, "s1", runId = "run-1"))

            coVerify(exactly = 1) { submitDecision("s1", "run-1", true) }
            assertEquals(0, Shadows.shadowOf(notificationManager()).size())
        }

    @Test
    fun `given a pre-update live notification when onReceive then nothing is answered and its slot is cleared`() =
        runReceiverTest {
            // It names neither a request nor a run: whatever it asked about died
            // with the process the update replaced. Answering "the session"
            // instead is exactly what the request address exists to rule out.
            postPlaceholder(ApprovalNotificationManager.legacySessionNotificationId("s1"))

            receiver.onReceive(context, intent(ApprovalAction.APPROVE.action, "s1"))

            coVerify(exactly = 0) { submitDecision(any(), any(), any()) }
            assertEquals(0, Shadows.shadowOf(notificationManager()).size())
        }

    @Test
    fun `given unknown action when onReceive then nothing is dispatched`() = runReceiverTest {
        receiver.onReceive(context, intent("app.knotwork.android.ACTION_UNKNOWN", "s1", requestId = "request-1"))

        coVerify(exactly = 0) { submitDecision(any(), any(), any()) }
        confirmVerified(submitDecision)
    }

    @Test
    fun `given missing sessionId extra when onReceive then use case skipped and notification kept`() = runReceiverTest {
        postPlaceholder(ApprovalNotificationManager.notificationId("request-1"))
        assertEquals(1, Shadows.shadowOf(notificationManager()).size())

        receiver.onReceive(context, intent(ApprovalAction.APPROVE.action, sessionId = null, requestId = "request-1"))

        coVerify(exactly = 0) { submitDecision(any(), any(), any()) }
        verify(exactly = 0) { approvalNotifier.cancelApprovalNotification(any()) }
        // Pre-existing notification must still be there — the receiver
        // returned before reaching the cancel call.
        assertEquals(1, Shadows.shadowOf(notificationManager()).size())
    }

    @Test
    fun `given REPOST for a parked approval when onReceive then notification reposted from record`() = runReceiverTest {
        coEvery { pendingInteractionRepository.getForRun("run-1") } returns PendingInteraction(
            runId = "run-1",
            sessionId = "s1",
            kind = PendingInteractionKind.APPROVAL,
            toolName = "send_email",
            toolArgs = "{\"to\":\"a@b.c\"}",
            risk = ToolRisk.DESTRUCTIVE,
            requestedAt = 0L,
            requestId = "request-1",
        )

        receiver.onReceive(context, intent(ApprovalAction.REPOST.action, "s1", runId = "run-1"))

        verify(exactly = 1) {
            approvalNotifier.sendPersistentApprovalRequest(
                runId = "run-1",
                sessionId = "s1",
                requestId = "request-1",
                toolName = "send_email",
                arguments = "{\"to\":\"a@b.c\"}",
                risk = ToolRisk.DESTRUCTIVE,
            )
        }
        coVerify(exactly = 0) { submitDecision(any(), any(), any()) }
    }

    @Test
    fun `given REPOST for a parked clarification when onReceive then question notification reposted`() =
        runReceiverTest {
            coEvery { pendingInteractionRepository.getForRun("run-2") } returns PendingInteraction(
                runId = "run-2",
                sessionId = "s2",
                kind = PendingInteractionKind.CLARIFICATION,
                question = "Which city?",
                requestedAt = 0L,
            )

            receiver.onReceive(context, intent(ApprovalAction.REPOST.action, "s2", runId = "run-2"))

            verify(exactly = 1) {
                clarificationNotifier.sendPersistentClarificationRequest("run-2", "s2", "Which city?")
            }
        }

    @Test
    fun `given REPOST with no surviving record when onReceive then nothing reposted`() = runReceiverTest {
        coEvery { pendingInteractionRepository.getForRun("run-3") } returns null

        receiver.onReceive(context, intent(ApprovalAction.REPOST.action, "s3", runId = "run-3"))

        verify(exactly = 0) {
            approvalNotifier.sendPersistentApprovalRequest(any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { clarificationNotifier.sendPersistentClarificationRequest(any(), any(), any()) }
    }

    @Test
    fun `given REPOST without runId when onReceive then record is never queried`() = runReceiverTest {
        receiver.onReceive(context, intent(ApprovalAction.REPOST.action, "s4"))

        coVerify(exactly = 0) { pendingInteractionRepository.getForRun(any()) }
    }
}
