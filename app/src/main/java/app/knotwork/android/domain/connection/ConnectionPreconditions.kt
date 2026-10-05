package app.knotwork.android.domain.connection

import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.services.CleartextPolicy

/**
 * What stops a connection check before it sends anything, judged from the form and the network
 * settings — the reason the Test button is disabled, and the first thing a check that runs asks.
 *
 * Pure, and read by both: the screen while the user types, the checker when the button is pressed.
 * So a button that was enabled never meets a refusal the form could have shown, except one that
 * depends on a setting alone (a hosted provider while "Block network from local model" is on),
 * which only a check reports.
 */
object ConnectionPreconditions {

    /**
     * Judges a check of [provider] with the values in [draft].
     *
     * For a server the user runs: an address, an address that parses, then [EndpointRule]; its key
     * is optional. For a hosted provider: a key. The order is the order a user would fix them in.
     *
     * @param provider The provider to check.
     * @param draft The key and address as entered.
     * @param approvedCleartextOrigins The private origins approved for unencrypted traffic.
     * @param localOnly Whether "Block network from local model" is on.
     * @return The refusal, or `null` when the check may run.
     */
    fun provider(
        provider: CloudProvider,
        draft: ProviderConnectionDraft,
        approvedCleartextOrigins: Set<String>,
        localOnly: Boolean,
    ): ConnectionRefusal? = if (provider.usesBaseUrl) {
        address(draft.baseUrl)
            ?: EndpointRule.refusal(draft.baseUrl.orEmpty().trim(), approvedCleartextOrigins, localOnly)
    } else {
        ConnectionRefusal.MissingKey.takeIf { draft.apiKey.isNullOrBlank() }
    }

    /**
     * Judges a check of the MCP server at [url]. "Block network from local model" does not apply
     * to MCP servers, so only the address and the cleartext rule do.
     *
     * @param url The server URL as entered.
     * @param approvedCleartextOrigins The private origins approved for unencrypted traffic.
     * @return The refusal, or `null` when the check may run.
     */
    fun mcp(url: String, approvedCleartextOrigins: Set<String>): ConnectionRefusal? =
        address(url) ?: EndpointRule.refusal(url.trim(), approvedCleartextOrigins, localOnly = false)

    /** [ConnectionRefusal.MissingAddress] or [ConnectionRefusal.NotAnAddress] for [url], or `null`. */
    private fun address(url: String?): ConnectionRefusal? = when {
        url.isNullOrBlank() -> ConnectionRefusal.MissingAddress
        CleartextPolicy.hostOf(url) == null -> ConnectionRefusal.NotAnAddress
        else -> null
    }
}
