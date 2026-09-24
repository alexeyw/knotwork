package app.knotwork.android.data.services

import android.content.Context
import app.knotwork.android.domain.constants.ExternalAutomationContract
import app.knotwork.android.domain.models.ExternalAutomationRejectionReason
import app.knotwork.android.domain.models.ExternalAutomationStatus
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/**
 * Robolectric coverage for [ExternalAutomationCallbackSender] — the outbound half
 * of the contract. Asserts the wire shape a third-party caller parses, the two
 * properties that keep a misdirected callback harmless (it is package-directed
 * rather than component-explicit, and it carries no run content), and the two
 * conditions under which nothing leaves at all: the contract switched off, and a
 * caller-chosen value over its ceiling.
 */
@RunWith(RobolectricTestRunner::class)
class ExternalAutomationCallbackSenderTest {

    private lateinit var context: Context
    private lateinit var contractEnabled: MutableStateFlow<Boolean>
    private lateinit var sender: ExternalAutomationCallbackSender

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        contractEnabled = MutableStateFlow(true)
        sender = ExternalAutomationCallbackSender(context, settingsWith(contractEnabled))
    }

    private fun settingsWith(enabled: MutableStateFlow<Boolean>): SettingsRepository = mockk {
        every { externalAutomationEnabled } returns enabled
    }

    private fun sentIntents() = Shadows.shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents

    @Test
    fun `given a terminal status when notified then the caller gets its id and the status`() = runTest {
        sender.notifyOutcome(
            returnPackage = "com.example.caller",
            returnAction = ExternalAutomationContract.ACTION_RUN_RESULT,
            requestId = "req-1",
            status = ExternalAutomationStatus.Completed,
        )

        val intent = sentIntents().single()
        assertEquals(ExternalAutomationContract.ACTION_RUN_RESULT, intent.action)
        // Package-directed, never a ComponentName: the app cannot know the caller's
        // receiver class, and automation apps register theirs at runtime.
        assertEquals("com.example.caller", intent.`package`)
        assertNull(intent.component)
        assertEquals("req-1", intent.getStringExtra(ExternalAutomationContract.EXTRA_REQUEST_ID))
        assertEquals("Completed", intent.getStringExtra(ExternalAutomationContract.EXTRA_STATUS))
    }

    @Test
    fun `given a refusal when notified then the reason travels with it`() = runTest {
        sender.notifyOutcome(
            returnPackage = "com.example.caller",
            returnAction = ExternalAutomationContract.ACTION_RUN_RESULT,
            requestId = "req-1",
            status = ExternalAutomationStatus.Rejected(ExternalAutomationRejectionReason.CONTRACT_DISABLED),
        )

        val intent = sentIntents().single()
        assertEquals("Rejected", intent.getStringExtra(ExternalAutomationContract.EXTRA_STATUS))
        assertEquals("CONTRACT_DISABLED", intent.getStringExtra(ExternalAutomationContract.EXTRA_STATUS_REASON))
    }

    @Test
    fun `given a non-refusal status when notified then no reason key is present at all`() = runTest {
        sender.notifyOutcome(
            returnPackage = "com.example.caller",
            returnAction = ExternalAutomationContract.ACTION_RUN_RESULT,
            requestId = "req-1",
            status = ExternalAutomationStatus.Accepted,
        )

        // "Key absent" and "key present and empty" are different statements; the
        // contract says the reason is present only for a refusal.
        assertFalse(sentIntents().single().hasExtra(ExternalAutomationContract.EXTRA_STATUS_REASON))
    }

    @Test
    fun `given every status when notified then the published wire names are used`() = runTest {
        val expected = mapOf(
            ExternalAutomationStatus.Accepted to "Accepted",
            ExternalAutomationStatus.Completed to "Completed",
            ExternalAutomationStatus.Failed to "Failed",
            ExternalAutomationStatus.Rejected(ExternalAutomationRejectionReason.TARGET_MISSING) to "Rejected",
            ExternalAutomationStatus.Blocked(ExternalAutomationRejectionReason.RATE_LIMITED) to "Blocked",
        )

        expected.forEach { (status, wireName) ->
            sender.notifyOutcome("com.example.caller", "a", "req", status)
            assertEquals(wireName, sentIntents().last().getStringExtra(ExternalAutomationContract.EXTRA_STATUS))
        }
    }

    @Test
    fun `given a callback that carries no run content when notified then only contract keys are present`() = runTest {
        sender.notifyOutcome("com.example.caller", "a", "req-1", ExternalAutomationStatus.Completed)

        val keys = sentIntents().single().extras?.keySet().orEmpty()
        assertEquals(
            setOf(ExternalAutomationContract.EXTRA_REQUEST_ID, ExternalAutomationContract.EXTRA_STATUS),
            keys,
        )
    }

    @Test
    fun `given the broadcast throws when notified then the failure is absorbed`() = runTest {
        val hostile = mockk<Context>()
        every { hostile.sendBroadcast(any()) } throws SecurityException("not allowed")

        // Delivery is a courtesy to a third-party app; nothing about the run depends
        // on it, so a caller that uninstalled itself cannot fail the run it started.
        ExternalAutomationCallbackSender(hostile, settingsWith(contractEnabled)).notifyOutcome(
            "com.example.gone",
            "a",
            "req-1",
            ExternalAutomationStatus.Completed,
        )
    }

    // --- What never leaves -------------------------------------------------

    @Test
    fun `given the contract is switched off when any status is notified then nothing is broadcast`() = runTest {
        contractEnabled.value = false
        // The three shapes that reach this seam: the refusal that says "off", a
        // refusal decided before the switch is ever read (a malformed request), and
        // the final report of a run admitted before the user switched it off.
        val statuses = listOf(
            ExternalAutomationStatus.Rejected(ExternalAutomationRejectionReason.CONTRACT_DISABLED),
            ExternalAutomationStatus.Rejected(ExternalAutomationRejectionReason.PROMPT_MISSING),
            ExternalAutomationStatus.Completed,
        )

        statuses.forEach { sender.notifyOutcome("com.example.caller", "com.example.ACTION", "req-1", it) }

        assertTrue(sentIntents().isEmpty())
    }

    @Test
    fun `given the contract is switched back on when notified then the callback is sent again`() = runTest {
        // Read at send time, not captured once: the switch is the user's, and it
        // moves while the process lives.
        contractEnabled.value = false
        sender.notifyOutcome("com.example.caller", "a", "req-1", ExternalAutomationStatus.Completed)
        contractEnabled.value = true
        sender.notifyOutcome("com.example.caller", "a", "req-2", ExternalAutomationStatus.Completed)

        assertEquals("req-2", sentIntents().single().getStringExtra(ExternalAutomationContract.EXTRA_REQUEST_ID))
    }

    @Test
    fun `given a request id over its ceiling when notified then nothing is broadcast`() = runTest {
        val tooLong = "r".repeat(ExternalAutomationContract.MAX_REQUEST_ID_LENGTH + 1)

        sender.notifyOutcome("com.example.caller", "a", tooLong, ExternalAutomationStatus.Completed)

        assertTrue(sentIntents().isEmpty())
    }

    @Test
    fun `given a callback address over its ceiling when notified then nothing is broadcast`() = runTest {
        val tooLong = "x".repeat(ExternalAutomationContract.MAX_RETURN_ADDRESS_LENGTH + 1)

        sender.notifyOutcome(tooLong, "a", "req-1", ExternalAutomationStatus.Completed)
        sender.notifyOutcome("com.example.caller", tooLong, "req-1", ExternalAutomationStatus.Completed)

        assertTrue(sentIntents().isEmpty())
    }

    @Test
    fun `given values exactly at their ceilings when notified then the callback is sent`() = runTest {
        val id = "r".repeat(ExternalAutomationContract.MAX_REQUEST_ID_LENGTH)
        val address = "x".repeat(ExternalAutomationContract.MAX_RETURN_ADDRESS_LENGTH)

        sender.notifyOutcome(address, address, id, ExternalAutomationStatus.Completed)

        assertEquals(id, sentIntents().single().getStringExtra(ExternalAutomationContract.EXTRA_REQUEST_ID))
    }
}
