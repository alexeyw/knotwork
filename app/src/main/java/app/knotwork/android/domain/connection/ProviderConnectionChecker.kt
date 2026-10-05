package app.knotwork.android.domain.connection

import app.knotwork.android.domain.models.CloudProvider

/**
 * Checks that a model provider can be reached with the values in its settings form, by asking for
 * its model list — no prompt, no tokens, one attempt.
 *
 * The check is held to the rules a run is: the same gate, the same per-hop check of redirects, the
 * same deadlines. It reads the values the user entered rather than the saved ones, so it answers
 * for what is on the screen.
 */
interface ProviderConnectionChecker {

    /**
     * The host a check of [provider] would ask, for the "Asking … for its models" line while it
     * runs.
     *
     * @param provider The provider to check.
     * @param baseUrl The address entered for a server the user runs; ignored for a hosted one.
     * @return The host, or `null` when a server the user runs has no usable address.
     */
    fun destination(provider: CloudProvider, baseUrl: String?): String?

    /**
     * Asks [provider] for its model list with the values in [draft].
     *
     * Total: every outcome, a failed request included, comes back as a result.
     *
     * @param provider The provider to check.
     * @param draft The key and address as entered.
     * @return What the check found.
     */
    suspend fun check(provider: CloudProvider, draft: ProviderConnectionDraft): ConnectionCheckResult
}

/**
 * The values a provider check reads from its settings form, as entered. Blank means absent, and a
 * value the provider has no use for is ignored.
 *
 * @property apiKey The API key entered; optional for a server the user runs.
 * @property baseUrl The server address entered, for a server the user runs.
 */
data class ProviderConnectionDraft(val apiKey: String?, val baseUrl: String?)
