package app.knotwork.android.domain.connection

/**
 * Why a connection check sent nothing: what it would need is missing, or a rule refuses the
 * address before a request leaves the device.
 *
 * The same values say why the Test button cannot run yet ([ConnectionPreconditions]) and why a
 * check that did run came back [ConnectionCheckResult.Refused]; a refusal is never a failure,
 * because nothing was sent.
 */
sealed interface ConnectionRefusal {

    /** A server the user runs has no address entered. */
    data object MissingAddress : ConnectionRefusal

    /** The address entered has no scheme and host to connect to. */
    data object NotAnAddress : ConnectionRefusal

    /** A hosted provider has no API key entered; it cannot be asked anything without one. */
    data object MissingKey : ConnectionRefusal

    /**
     * "Block network from local model" is on, and the provider is hosted on the internet. This
     * depends on a setting, not on anything in the form, so only a check that ran reports it.
     */
    data object BlockedByLocalOnlyMode : ConnectionRefusal
}

/**
 * A refusal decided by the address alone, under the rules a request is held to when it is sent
 * ([EndpointRule]). A form can say it while the address is typed, where it can be fixed.
 */
sealed interface AddressRefusal : ConnectionRefusal {

    /**
     * "Block network from local model" is on, and [host] is neither this device nor a private
     * IP address.
     *
     * @property host The host of the address, or the address itself when it has none.
     */
    data class HostNotLocal(val host: String) : AddressRefusal

    /**
     * The address is unencrypted (`http://`) and [host] is on the public internet. Never
     * allowed, and not something the user can approve.
     *
     * @property host The public host.
     */
    data class PublicCleartext(val host: String) : AddressRefusal

    /**
     * The address is unencrypted and private, and the user has not approved [origin] for
     * unencrypted traffic yet.
     *
     * @property origin The canonical `scheme://host[:port]` the user would approve.
     */
    data class CleartextNeedsApproval(val origin: String) : AddressRefusal
}
