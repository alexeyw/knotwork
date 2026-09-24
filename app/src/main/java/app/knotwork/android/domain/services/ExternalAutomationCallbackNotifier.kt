package app.knotwork.android.domain.services

import app.knotwork.android.domain.models.ExternalAutomationStatus

/**
 * Delivers the outcome of an external-automation request back to the app that
 * asked for it.
 *
 * A port rather than a direct broadcast so the domain keeps its decision about
 * **what** is reported separate from the framework's business of how it travels —
 * and so the "never carries run content" rule below is expressible in a signature
 * instead of relied on in review: there is simply no parameter for the answer.
 *
 * **The callback is a courtesy, not a channel.** It carries the caller's own
 * correlation id, the status and — for a refusal — its reason, and nothing else.
 * The content a run produced stays inside the app: a caller that wants an answer
 * asks the user's pipeline to put it somewhere, rather than receiving it over an
 * unauthenticated broadcast.
 *
 * **The target is unverified.** The package is the one the caller named, and a
 * broadcast does not attest its sender, so this may deliver to a package that did
 * not send the request. That is why the payload is deliberately this thin: a
 * misdirected callback tells the wrong app only an id it did not choose and a
 * status about work it did not ask for.
 *
 * **Nothing is sent while the contract is switched off**, and nothing whose
 * caller-chosen parts exceed the contract's length ceilings. Both rules belong to
 * the implementation rather than to its callers: a request is parsed — and can be
 * refused, and answered — before the switch is ever read, so a rule keyed on the
 * refusal reason would leave every malformed request answered while the user has
 * the contract off. Enforced at the one place a callback leaves the app, the rules
 * hold for the immediate answer and for a run's final report alike.
 */
interface ExternalAutomationCallbackNotifier {

    /**
     * Sends one status callback, unless the contract forbids it.
     *
     * Best-effort by contract: a callback that cannot be delivered — an
     * uninstalled target, a package with no matching receiver — must not disturb
     * the run it reports on, so implementations absorb and log delivery failures
     * rather than propagating them. A callback withheld because the contract is
     * off, or because a value is over its ceiling, is dropped the same way.
     *
     * @param returnPackage Package to deliver to, as named by the caller.
     * @param returnAction Broadcast action to send it with.
     * @param requestId The caller's correlation id, echoed back unchanged.
     * @param status The status to report.
     */
    suspend fun notifyOutcome(
        returnPackage: String,
        returnAction: String,
        requestId: String,
        status: ExternalAutomationStatus,
    )
}
