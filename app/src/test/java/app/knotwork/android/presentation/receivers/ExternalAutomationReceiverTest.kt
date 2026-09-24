package app.knotwork.android.presentation.receivers

import android.content.Context
import android.content.Intent
import app.knotwork.android.data.services.RunRetentionScheduler
import app.knotwork.android.domain.constants.ExternalAutomationContract
import app.knotwork.android.domain.models.ExternalAutomationInvocation
import app.knotwork.android.domain.models.ExternalAutomationRejectionReason
import app.knotwork.android.domain.models.ExternalAutomationStatus
import app.knotwork.android.domain.services.ExternalAutomationCallbackNotifier
import app.knotwork.android.domain.usecases.automation.HandleExternalAutomationRequestUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Robolectric coverage for [ExternalAutomationReceiver] — the exported entry point
 * a third-party automation app broadcasts to.
 *
 * The receiver is deliberately decision-free, so these tests assert exactly the
 * three things it *is* responsible for: copying the contract's keys faithfully out
 * of a caller-supplied intent, surviving a hostile one, and replying to the caller
 * with the decision the use case made.
 *
 * Hilt injection is bypassed the same way [AgentApprovalReceiverTest] does it —
 * flipping the generated `injected` flag and assigning the fields by hand — and the
 * receiver's scope is replaced with an unconfined test scope so the work bridged
 * through `goAsync` completes inside the test body.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExternalAutomationReceiverTest {

    private lateinit var context: Context
    private lateinit var handleRequest: HandleExternalAutomationRequestUseCase
    private lateinit var callbackNotifier: ExternalAutomationCallbackNotifier
    private lateinit var retentionScheduler: RunRetentionScheduler
    private lateinit var receiver: ExternalAutomationReceiver

    @Before
    fun setUp() {
        // Process-wide flag shared across every test class in this JVM: a leaked
        // `true` would make the retention assertions below silently vacuous.
        ExternalAutomationReceiver.retentionScheduled.set(false)
        context = RuntimeEnvironment.getApplication()
        handleRequest = mockk()
        callbackNotifier = mockk(relaxed = true)
        retentionScheduler = mockk(relaxed = true)
        coEvery { handleRequest(any(), any(), any()) } returns ExternalAutomationStatus.Accepted
        receiver = ExternalAutomationReceiver().also { instance ->
            val injectedField = instance.javaClass.superclass!!.getDeclaredField("injected")
            injectedField.isAccessible = true
            injectedField.setBoolean(instance, true)
            instance.handleRequest = handleRequest
            instance.callbackNotifier = callbackNotifier
            instance.runRetentionScheduler = retentionScheduler
        }
    }

    private fun runReceiver(intent: Intent, scope: CoroutineScope) {
        receiver.scope = scope
        receiver.onReceive(context, intent)
    }

    private fun requestIntent(
        action: String = ExternalAutomationContract.ACTION_RUN_PIPELINE,
        extras: Map<String, String?> = mapOf(
            ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
            ExternalAutomationContract.EXTRA_PROMPT to "Summarise my day",
            ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
            ExternalAutomationContract.EXTRA_RETURN_PACKAGE to "com.example.caller",
        ),
    ): Intent = Intent(action).apply { extras.forEach { (k, v) -> putExtra(k, v) } }

    @Test
    fun `given a request intent when received then the contract keys reach the use case unchanged`() = runTest {
        val slot = slot<ExternalAutomationInvocation>()
        coEvery { handleRequest(capture(slot), any(), any()) } returns ExternalAutomationStatus.Accepted

        runReceiver(requestIntent(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        val invocation = slot.captured
        assertEquals(ExternalAutomationContract.ACTION_RUN_PIPELINE, invocation.action)
        assertEquals("pipe-1", invocation.value(ExternalAutomationContract.EXTRA_PIPELINE_ID))
        assertEquals("Summarise my day", invocation.value(ExternalAutomationContract.EXTRA_PROMPT))
        assertEquals("req-1", invocation.value(ExternalAutomationContract.EXTRA_REQUEST_ID))
        assertEquals("com.example.caller", invocation.value(ExternalAutomationContract.EXTRA_RETURN_PACKAGE))
    }

    @Test
    fun `given an unknown action when received then it still reaches the use case rather than being dropped`() =
        runTest {
            val slot = slot<ExternalAutomationInvocation>()
            coEvery { handleRequest(capture(slot), any(), any()) } returns
                ExternalAutomationStatus.Rejected(ExternalAutomationRejectionReason.UNKNOWN_ACTION)

            runReceiver(
                requestIntent(action = "com.example.WRONG"),
                CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            )

            // Dropping it here would hide the arrival from the journal, and a caller
            // with a typo would see nothing at all rather than a typed refusal.
            assertEquals("com.example.WRONG", slot.captured.action)
        }

    @Test
    fun `given an intent carrying no extras when received then absent keys arrive as null`() = runTest {
        val slot = slot<ExternalAutomationInvocation>()
        coEvery { handleRequest(capture(slot), any(), any()) } returns
            ExternalAutomationStatus.Rejected(ExternalAutomationRejectionReason.REQUEST_ID_MISSING)

        runReceiver(requestIntent(extras = emptyMap()), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        assertNull(slot.captured.value(ExternalAutomationContract.EXTRA_REQUEST_ID))
        assertNull(slot.captured.value(ExternalAutomationContract.EXTRA_PROMPT))
    }

    @Test
    fun `given callback-side keys on the request when received then they are not read`() = runTest {
        val slot = slot<ExternalAutomationInvocation>()
        coEvery { handleRequest(capture(slot), any(), any()) } returns ExternalAutomationStatus.Accepted

        runReceiver(
            requestIntent(
                extras = mapOf(
                    ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                    ExternalAutomationContract.EXTRA_STATUS to "Completed",
                    ExternalAutomationContract.EXTRA_STATUS_REASON to "RATE_LIMITED",
                ),
            ),
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )

        // The receiver reads a closed vocabulary of request keys. A caller cannot
        // smuggle in the outbound half of the contract.
        assertNull(slot.captured.extras[ExternalAutomationContract.EXTRA_STATUS])
    }

    @Test
    fun `given a request asking for a callback when handled then the decision is sent back to the caller`() = runTest {
        coEvery { handleRequest(any(), any(), any()) } returns
            ExternalAutomationStatus.Blocked(ExternalAutomationRejectionReason.RATE_LIMITED)

        runReceiver(requestIntent(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        coVerify(exactly = 1) {
            callbackNotifier.notifyOutcome(
                returnPackage = "com.example.caller",
                returnAction = ExternalAutomationContract.ACTION_RUN_RESULT,
                requestId = "req-1",
                status = ExternalAutomationStatus.Blocked(ExternalAutomationRejectionReason.RATE_LIMITED),
            )
        }
    }

    @Test
    fun `given a request naming its own callback action when handled then that action is used`() = runTest {
        runReceiver(
            requestIntent(
                extras = mapOf(
                    ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                    ExternalAutomationContract.EXTRA_RETURN_PACKAGE to "com.example.caller",
                    ExternalAutomationContract.EXTRA_RETURN_ACTION to "com.example.MY_RESULT",
                ),
            ),
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )

        coVerify(exactly = 1) {
            callbackNotifier.notifyOutcome(any(), "com.example.MY_RESULT", any(), any())
        }
    }

    @Test
    fun `given a callback-address mismatch when refused then nothing is broadcast at the named package`() = runTest {
        coEvery { handleRequest(any(), any(), any()) } returns
            ExternalAutomationStatus.Rejected(ExternalAutomationRejectionReason.RETURN_PACKAGE_MISMATCH)

        runReceiver(
            requestIntent(
                extras = mapOf(
                    ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                    ExternalAutomationContract.EXTRA_RETURN_PACKAGE to "com.example.victim",
                ),
            ),
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )

        // Answering this refusal would send the victim exactly the broadcast the
        // refusal exists to prevent, and leave a journal row claiming otherwise.
        coVerify(exactly = 0) { callbackNotifier.notifyOutcome(any(), any(), any(), any()) }
    }

    @Test
    fun `given a fire-and-forget request when handled then no callback is attempted`() = runTest {
        runReceiver(
            requestIntent(
                extras = mapOf(
                    ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                    ExternalAutomationContract.EXTRA_PROMPT to "hello",
                ),
            ),
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )

        coVerify(exactly = 0) { callbackNotifier.notifyOutcome(any(), any(), any(), any()) }
    }

    @Test
    fun `given a request when handled then the journal retention pass is enqueued`() = runTest {
        // A broadcast can wake this process without any activity ever being created,
        // and the activity is the only other place retention is scheduled from. On an
        // install driven purely by an automation app, this call is the sole thing
        // bounding a table that a third-party app writes to.
        runReceiver(requestIntent(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        verify(exactly = 1) { retentionScheduler.schedulePeriodic() }
    }

    @Test
    fun `given repeated requests when handled then retention is enqueued once per process`() = runTest {
        // The call rate here belongs to a third-party app. Scheduling on every
        // broadcast would put a WorkManager transaction on the path of every packet
        // a loop can send, which is the very abuse this table is being defended from.
        repeat(5) { runReceiver(requestIntent(), CoroutineScope(UnconfinedTestDispatcher(testScheduler))) }

        verify(exactly = 1) { retentionScheduler.schedulePeriodic() }
    }

    @Test
    fun `given an intent whose extras cannot be unmarshalled when received then the call degrades to empty`() =
        runTest {
            val hostile = mockk<Intent>()
            every { hostile.action } returns ExternalAutomationContract.ACTION_RUN_PIPELINE
            every { hostile.getStringExtra(any()) } throws RuntimeException("bad parcel")
            val slot = slot<ExternalAutomationInvocation>()
            coEvery { handleRequest(capture(slot), any(), any()) } returns
                ExternalAutomationStatus.Rejected(ExternalAutomationRejectionReason.REQUEST_ID_MISSING)

            // An exception escaping onReceive would crash the app on demand from any
            // installed app; this degrades to a call that says nothing, which the
            // parser then refuses with a typed reason.
            runReceiver(hostile, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            assertNull(slot.captured.value(ExternalAutomationContract.EXTRA_REQUEST_ID))
        }
}
