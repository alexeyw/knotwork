package app.knotwork.android.domain.models

/**
 * Why an external-automation request was **not** executed.
 *
 * The single reason dictionary of the external entry point, shared by every
 * consumer instead of being re-declared per surface:
 * - the pure request parser
 *   ([app.knotwork.android.domain.usecases.automation.ParseExternalAutomationRequestUseCase]),
 *   which returns it for a request it refuses to interpret,
 * - the pure authorizer
 *   ([app.knotwork.android.domain.usecases.automation.AuthorizeExternalAutomationRequestUseCase]),
 *   which returns it for a request it refuses to admit,
 * - the external-request journal, which records it verbatim so no refusal is
 *   silent, and
 * - the settings surface, which renders it in human language.
 *
 * Keeping malformed-request reasons in the *same* enum as policy refusals is
 * deliberate. Split across two types, the journal and the user-facing text would
 * each have to join two vocabularies back together, and the dictionary would be
 * declared a second time outside the domain — the exact duplication a typed
 * reason exists to prevent.
 *
 * The names are **persisted** (stored on the journal row as the reason
 * discriminator) and are part of the public automation contract surfaced in
 * `docs/external-automation.md`, so they must never be renamed: doing so would
 * make historical journal rows undecodable and break third-party callers that
 * branch on the reported reason.
 */
enum class ExternalAutomationRejectionReason {
    /** The external-automation contract is switched off (its default state). */
    CONTRACT_DISABLED,

    /** No pipeline is bound to the external-automation surface (inert until bound). */
    SURFACE_NOT_BOUND,

    /** The request named a pipeline other than the one bound to the surface. */
    TARGET_NOT_ALLOWED,

    /** The request carried no target pipeline at all. */
    TARGET_MISSING,

    /** The request named the target twice, by id and by name, and the two cannot be reconciled. */
    TARGET_AMBIGUOUS,

    /** The request carried an action this contract does not define. */
    UNKNOWN_ACTION,

    /** The request carried no prompt for the pipeline to run on. */
    PROMPT_MISSING,

    /** The request carried the prompt twice, in plain and base64 form. */
    PROMPT_AMBIGUOUS,

    /** The base64 prompt could not be decoded. */
    PROMPT_UNDECODABLE,

    /**
     * The request asked to be answered but carried no request id to correlate the
     * answer with. A call that asks for no callback may omit the id.
     */
    REQUEST_ID_MISSING,

    /**
     * A value the callback would carry back is longer than the contract allows: the
     * request id beyond 128 characters, or the callback action or package beyond
     * 256. Such a request gets no callback, because the callback would have to
     * repeat the value.
     *
     * The numbers are [app.knotwork.android.domain.constants.ExternalAutomationContract.MAX_REQUEST_ID_LENGTH]
     * and [app.knotwork.android.domain.constants.ExternalAutomationContract.MAX_RETURN_ADDRESS_LENGTH],
     * written out above because the first paragraph is what the published contract
     * table shows; `EntrySurfaceLimitsDocumentsTest` keeps the two equal.
     */
    VALUE_TOO_LONG,

    /** Too many external requests were accepted within the rate window. */
    RATE_LIMITED,

    /**
     * The request asked for its callback to be delivered to a package other than
     * the one the system reported as the sender.
     *
     * Only ever reported when the sender shared its identity, which is the rare
     * case; it means a caller tried to make this app broadcast at a third party.
     */
    RETURN_PACKAGE_MISMATCH,
}
