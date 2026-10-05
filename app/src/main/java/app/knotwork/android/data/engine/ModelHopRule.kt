package app.knotwork.android.data.engine

import app.knotwork.android.domain.connection.AddressRefusal
import app.knotwork.android.domain.connection.EndpointRule
import app.knotwork.android.domain.services.CleartextPolicy
import java.io.IOException

/**
 * Where one request of a model client may go: the rule [KoogTransportFactory] applies to every
 * hop — the request the client sends and each redirect a server answers with.
 *
 * The address the user entered is judged once, by [ModelNetworkGate.endpointRefusal], before a
 * client is built. A server can then redirect, and inside the Koog/Ktor stack nothing used to
 * look at where to: an approved LAN address could forward the prompt to any host, over plain
 * HTTP. (The platform used to block cleartext redirects; it stopped when the cleartext rule
 * moved into the app, and this was named as the residual risk of that move.) This rule is the
 * same decision, [EndpointRule], applied per hop:
 * - while "Block network from local model" is on, the host must be this device or a private
 *   address;
 * - unencrypted traffic may go only to a private address the user approved.
 *
 * Its messages are its own rather than [CleartextPolicy.refusalMessage]'s: those tell the user
 * to re-save an address in Settings, which is wrong advice for an address a server redirected to.
 *
 * @property approvedCleartextOrigins The private origins approved for unencrypted traffic.
 * @property localOnly Whether "Block network from local model" is on.
 */
class ModelHopRule(val approvedCleartextOrigins: Set<String>, val localOnly: Boolean) {

    /**
     * Judges one hop.
     *
     * @param url The full address the request is about to be sent to.
     * @return Why the request must not be sent, or `null` when it may.
     */
    fun refusal(url: String): String? =
        when (val refusal = EndpointRule.refusal(url, approvedCleartextOrigins, localOnly)) {
            is AddressRefusal.HostNotLocal ->
                "Stopped a request to '${refusal.host}': while \"Block network from local model\" is on, only this " +
                    "device and private network addresses can be reached. If the server redirected there, fix the " +
                    "redirect, or turn the setting off in Settings → Tools & workspace."
            is AddressRefusal.PublicCleartext ->
                "Stopped an unencrypted request to ${refusal.host}: unencrypted traffic " +
                    "may go only to this device or a private network address. If the server redirected there, " +
                    "make it use https://."
            is AddressRefusal.CleartextNeedsApproval ->
                "Stopped an unencrypted request to ${refusal.origin}: that address has not been approved for " +
                    "unencrypted traffic. If the server redirected there, approve the address in Settings or " +
                    "fix the redirect."
            null -> null
        }
}

/**
 * A request of a model client was stopped before it was sent, because [ModelHopRule] refused its
 * address. The message is the rule's own sentence: which address, why, and what to do.
 *
 * An [IOException], like the transport failures it travels with; no retry keyword appears in it,
 * so the retry policy does not try the refused address again.
 *
 * @param message The refusal, as [ModelHopRule.refusal] worded it.
 */
class HopRefusedException(message: String) : IOException(message)
