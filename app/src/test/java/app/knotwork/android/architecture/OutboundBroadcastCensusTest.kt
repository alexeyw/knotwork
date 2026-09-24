package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Census of the places the app sends a broadcast.
 *
 * **Why it is one place.** The only broadcast the app sends is the external-automation
 * callback, and its address, action and one string extra are the caller's choice. So
 * the sender (`ExternalAutomationCallbackSender`) is also where the contract says no:
 * nothing while the contract is switched off, nothing whose caller-chosen parts are
 * over their ceilings. Those rules used to be expected of each caller, and the first
 * caller — answering a request parsed before the switch was ever read — skipped the
 * switch. A second `sendBroadcast` would be a second path around both rules; this
 * test makes it a decision instead of an accident.
 */
class OutboundBroadcastCensusTest {

    @Test
    fun `given the production sources then only the callback sender sends a broadcast`() {
        val senders = ProductionSources.code.filterValues { BROADCAST_API.containsMatchIn(it) }.keys

        assertEquals(setOf(CALLBACK_SENDER), senders)
    }

    @Test
    fun `given the pattern then it matches every broadcast-sending call shape`() {
        // The census is only as good as its pattern: pin what it catches.
        listOf(
            "context.sendBroadcast(intent)",
            "sendOrderedBroadcast(intent, null)",
            "context.sendBroadcastAsUser(intent, user)",
            "LocalBroadcastManager.getInstance(context).sendBroadcast(intent)",
        ).forEach { assertTrue(it, BROADCAST_API.containsMatchIn(it)) }
    }

    private companion object {
        const val CALLBACK_SENDER = "main/java/app/knotwork/android/data/services/ExternalAutomationCallbackSender.kt"

        /** Any `send…Broadcast…(` call. */
        val BROADCAST_API = Regex("""\bsend\w*Broadcast\w*\s*\(""")
    }
}
