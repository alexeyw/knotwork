package app.knotwork.android.domain.connection

import app.knotwork.android.domain.services.CleartextPolicy
import app.knotwork.android.domain.services.LocalOnlyPolicy

/**
 * The rule every request to a server the user runs is held to, decided once: while "Block
 * network from local model" is on the host must be this device or a private address
 * ([LocalOnlyPolicy]); and unencrypted traffic may go only to a private address the user approved
 * ([CleartextPolicy]).
 *
 * One function, read in three places that used to repeat the pair of checks — the gate that
 * judges an address before a model client is built, the transport that judges every hop of a
 * request, and the settings form that says why an address will be refused while it is typed — so
 * the form cannot promise what the send-time check then refuses.
 */
object EndpointRule {

    /**
     * Judges [url] against the current settings.
     *
     * The restriction is checked first: the user switched it on deliberately, so it is the reason
     * worth naming when both checks refuse.
     *
     * @param url The address a request would be sent to.
     * @param approvedCleartextOrigins The private origins approved for unencrypted traffic.
     * @param localOnly Whether "Block network from local model" is on.
     * @return The refusal, or `null` when a request to [url] may be sent.
     */
    fun refusal(url: String, approvedCleartextOrigins: Set<String>, localOnly: Boolean): AddressRefusal? {
        if (localOnly && !LocalOnlyPolicy.isLocalEndpoint(url)) {
            return AddressRefusal.HostNotLocal(CleartextPolicy.hostOf(url) ?: url)
        }
        return when (val verdict = CleartextPolicy.classify(url, approvedCleartextOrigins)) {
            is CleartextPolicy.Verdict.PublicRefused ->
                AddressRefusal.PublicCleartext(CleartextPolicy.hostOf(url) ?: url)
            is CleartextPolicy.Verdict.NeedsApproval -> AddressRefusal.CleartextNeedsApproval(verdict.origin)
            is CleartextPolicy.Verdict.NotCleartext, is CleartextPolicy.Verdict.ApprovedPrivate -> null
        }
    }
}
