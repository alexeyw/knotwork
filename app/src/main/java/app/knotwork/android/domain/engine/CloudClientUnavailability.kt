package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.CloudProvider

/**
 * Why a model client for a provider cannot be constructed right now.
 *
 * Construction used to collapse every cause into one `null`, and each caller then guessed
 * the cause back — a Cloud node re-read the "Block network from local model" flag and
 * otherwise blamed a missing API key. The guess was wrong whenever the real cause was a
 * third one: an Ollama address refused by the cleartext rule surfaced as "no API key", and
 * with the restriction on, as the restriction. The cause is now decided once, where the
 * client is built, and every caller words it the same way through [message].
 */
sealed interface CloudClientUnavailability {

    /**
     * The sentence shown to the user (and returned to a model as a tool observation)
     * when a client for [provider] cannot be built. Each one names the setting that
     * resolves it, because the remedies live in different places.
     *
     * @param provider The provider the client was requested for; its id is quoted.
     * @return A complete, user-facing sentence.
     */
    fun message(provider: CloudProvider): String

    /** No API key is saved for the provider — or, for Ollama, no server address. */
    data object MissingCredentials : CloudClientUnavailability {
        override fun message(provider: CloudProvider): String = if (provider == CloudProvider.OLLAMA) {
            "Cloud provider '${provider.id}' has no server address configured. Add one in Settings."
        } else {
            "Cloud provider '${provider.id}' has no API key configured. Add one in Settings."
        }
    }

    /** The "Block network from local model" restriction refuses every cloud provider. */
    data object BlockedByLocalOnlyMode : CloudClientUnavailability {
        override fun message(provider: CloudProvider): String =
            "Cloud provider '${provider.id}' is blocked by the \"Block network from local model\" " +
                "restriction. Turn it off in Settings to use this provider."
    }

    /**
     * The restriction is on and the provider's server is not on this device or at a
     * private network address, whatever its scheme.
     *
     * @property host The host the address names, or `null` when none can be read —
     *   quoted so the user can see which address was refused.
     */
    data class EndpointNotLocal(val host: String?) : CloudClientUnavailability {
        override fun message(provider: CloudProvider): String {
            val where = host?.let { "at '$it'" } ?: "at the configured address"
            return "The '${provider.id}' server $where is blocked by the \"Block network from local model\" " +
                "restriction: while it is on, only a server on this device or at a private network " +
                "address (localhost, 10.x.x.x, 172.16–31.x.x or 192.168.x.x) can be used. " +
                "Change the address, or turn the restriction off in Settings."
        }
    }

    /**
     * The address would send traffic unencrypted where the cleartext rule does not allow it.
     *
     * @property reason The cleartext rule's own explanation, which already says what to do.
     */
    data class CleartextRefused(val reason: String) : CloudClientUnavailability {
        override fun message(provider: CloudProvider): String =
            "Cloud provider '${provider.id}' cannot be reached. $reason"
    }
}
