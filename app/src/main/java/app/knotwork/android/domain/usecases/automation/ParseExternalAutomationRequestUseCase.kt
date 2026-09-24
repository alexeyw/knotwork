package app.knotwork.android.domain.usecases.automation

import app.knotwork.android.domain.constants.ExternalAutomationContract
import app.knotwork.android.domain.models.ExternalAutomationInvocation
import app.knotwork.android.domain.models.ExternalAutomationRejectionReason
import app.knotwork.android.domain.models.ExternalAutomationRequest
import app.knotwork.android.domain.models.ExternalAutomationTarget
import javax.inject.Inject
import kotlin.io.encoding.Base64

/**
 * Turns a raw [ExternalAutomationInvocation] into a validated
 * [ExternalAutomationRequest], or reports the typed reason it will not.
 *
 * A pure function of its input: no repository, no clock, no Android. That makes
 * the entire syntax surface of the public contract testable off-device, which
 * matters more here than anywhere else in the app — this is the one input path
 * whose callers the project does not control.
 *
 * **Nothing is repaired.** An unknown action, a missing target, a target named
 * twice, a prompt named twice, an undecodable prompt: each is refused with the
 * reason it failed for, never resolved to the nearest plausible candidate. The
 * project already learned this on tool-name matching — a guess that is usually
 * right produces a failure mode nobody can debug, because the request that ran
 * is not the request that was sent.
 */
class ParseExternalAutomationRequestUseCase @Inject constructor() {

    /**
     * Validates and interprets [invocation].
     *
     * Checks run in a fixed order — action, value ceilings, request id, target,
     * prompt — so a call that is wrong in several ways always reports the same
     * reason, and a caller fixing one problem at a time makes visible progress.
     *
     * @param invocation The raw call as received at the entry point.
     * @return [ExternalAutomationParseResult.Parsed] with the validated request,
     *   or [ExternalAutomationParseResult.Invalid] with the reason it was refused.
     */
    operator fun invoke(invocation: ExternalAutomationInvocation): ExternalAutomationParseResult {
        envelopeRefusal(invocation)?.let { return invalid(it) }
        val returnPackage = invocation.value(ExternalAutomationContract.EXTRA_RETURN_PACKAGE)
        val requestId = when (val resolved = resolveRequestId(invocation, returnPackage)) {
            is Resolved.Failure -> return invalid(resolved.reason)
            is Resolved.Success -> resolved.value
        }

        val target = when (val resolved = resolveTarget(invocation)) {
            is Resolved.Failure -> return invalid(resolved.reason)
            is Resolved.Success -> resolved.value
        }
        val prompt = when (val resolved = resolvePrompt(invocation)) {
            is Resolved.Failure -> return invalid(resolved.reason)
            is Resolved.Success -> resolved.value
        }

        return ExternalAutomationParseResult.Parsed(
            ExternalAutomationRequest(
                target = target,
                prompt = prompt,
                requestId = requestId,
                returnAction = invocation.value(ExternalAutomationContract.EXTRA_RETURN_ACTION)
                    ?: ExternalAutomationContract.ACTION_RUN_RESULT,
                returnPackage = returnPackage,
            ),
        )
    }

    /**
     * The checks that come before any value is read for its meaning: the action,
     * then the value ceilings.
     *
     * @param invocation The raw call.
     * @return `UNKNOWN_ACTION` or `VALUE_TOO_LONG`, or `null` when the call passes
     *   both.
     */
    private fun envelopeRefusal(invocation: ExternalAutomationInvocation): ExternalAutomationRejectionReason? = when {
        invocation.action != ExternalAutomationContract.ACTION_RUN_PIPELINE ->
            ExternalAutomationRejectionReason.UNKNOWN_ACTION
        exceedsCeilings(invocation) -> ExternalAutomationRejectionReason.VALUE_TOO_LONG
        else -> null
    }

    /**
     * Whether a value the callback would carry back is longer than the contract
     * allows.
     *
     * **Refused, never cut.** A shortened id no longer correlates with anything the
     * caller holds, and a shortened action or package addresses someone else — both
     * are the "nearest plausible reading" this parser exists not to produce. The
     * ceilings bound how much caller text the app will broadcast (the callback
     * carries the id back verbatim) and store in the journal of an admitted
     * request, whose row is where the final callback's address is read from.
     *
     * Checked for a fire-and-forget call too: the id is also what the journal shows.
     *
     * @param invocation The raw call.
     * @return `true` when the request id, callback action or callback package is
     *   over its ceiling.
     */
    private fun exceedsCeilings(invocation: ExternalAutomationInvocation): Boolean {
        fun lengthOf(key: String): Int = invocation.value(key)?.length ?: 0
        return lengthOf(ExternalAutomationContract.EXTRA_REQUEST_ID) >
            ExternalAutomationContract.MAX_REQUEST_ID_LENGTH ||
            lengthOf(ExternalAutomationContract.EXTRA_RETURN_ACTION) >
            ExternalAutomationContract.MAX_RETURN_ADDRESS_LENGTH ||
            lengthOf(ExternalAutomationContract.EXTRA_RETURN_PACKAGE) >
            ExternalAutomationContract.MAX_RETURN_ADDRESS_LENGTH
    }

    /**
     * Picks the request's correlation id, which is required only of a caller that
     * asked to be answered.
     *
     * **The id exists to correlate a callback, so a call that asks for no
     * callback is not asked for one.** A fire-and-forget caller has nothing to
     * match an answer against — it never receives one — and requiring an id of it
     * would be ceremony that refuses working calls. It also costs real callers:
     * Tasker's own *Send Intent* action carries exactly two extras, so an
     * unconditionally required id put the minimum call (target + prompt + id) one
     * field beyond the reach of the automation app this contract exists to
     * complement, for a value that would have been discarded.
     *
     * When the caller *did* ask to be answered, the id stays required: the
     * callback's entire payload is the id, the status and a reason, so an answer
     * without one is a broadcast the caller cannot attribute to anything.
     *
     * An omitted id is recorded as empty rather than invented. A minted id would
     * appear in the request journal looking exactly like one the caller chose,
     * and the user diagnosing a profile would search their automation app for a
     * string that only ever existed in this app; the journal row has its own
     * identity, and the surface already renders a blank correlation id by
     * omitting the chip.
     *
     * @param invocation The raw call.
     * @param returnPackage The caller's callback address, `null` for
     *   fire-and-forget.
     * @return The correlation id — empty when none was sent and none was needed —
     *   or the reason the call was refused.
     */
    private fun resolveRequestId(invocation: ExternalAutomationInvocation, returnPackage: String?): Resolved<String> {
        val requestId = invocation.value(ExternalAutomationContract.EXTRA_REQUEST_ID)
        return when {
            requestId != null -> Resolved.Success(requestId)
            returnPackage != null -> Resolved.Failure(ExternalAutomationRejectionReason.REQUEST_ID_MISSING)
            else -> Resolved.Success("")
        }
    }

    /**
     * Picks the request's target, refusing both "named twice" and "not named".
     *
     * @param invocation The raw call.
     * @return The target, or the reason it could not be determined.
     */
    private fun resolveTarget(invocation: ExternalAutomationInvocation): Resolved<ExternalAutomationTarget> {
        val id = invocation.value(ExternalAutomationContract.EXTRA_PIPELINE_ID)
        val name = invocation.value(ExternalAutomationContract.EXTRA_PIPELINE_NAME)
        return when {
            id != null && name != null -> Resolved.Failure(ExternalAutomationRejectionReason.TARGET_AMBIGUOUS)
            id != null -> Resolved.Success(ExternalAutomationTarget.ById(id))
            name != null -> Resolved.Success(ExternalAutomationTarget.ByName(name))
            else -> Resolved.Failure(ExternalAutomationRejectionReason.TARGET_MISSING)
        }
    }

    /**
     * Picks the request's prompt from whichever wire form the caller used.
     *
     * The base64 form exists for callers whose transport mangles the text (shell
     * quoting, automation apps that split on separators). Decoding accepts the
     * standard RFC 4648 alphabet with **optional** padding: an unpadded string is
     * the same encoding spelled differently, not a different one, so refusing it
     * would buy nothing. A different alphabet is not accepted — silently decoding
     * base64url with the standard table would hand the pipeline a prompt the
     * caller never wrote.
     *
     * @param invocation The raw call.
     * @return The decoded prompt, or the reason it could not be read.
     */
    private fun resolvePrompt(invocation: ExternalAutomationInvocation): Resolved<String> {
        val plain = invocation.value(ExternalAutomationContract.EXTRA_PROMPT)
        val encoded = invocation.value(ExternalAutomationContract.EXTRA_PROMPT_B64)
        return when {
            plain != null && encoded != null ->
                Resolved.Failure(ExternalAutomationRejectionReason.PROMPT_AMBIGUOUS)

            plain != null -> Resolved.Success(plain)

            encoded != null -> {
                val decoded = decodeBase64(encoded)?.trim()
                when {
                    decoded == null -> Resolved.Failure(ExternalAutomationRejectionReason.PROMPT_UNDECODABLE)
                    decoded.isEmpty() -> Resolved.Failure(ExternalAutomationRejectionReason.PROMPT_MISSING)
                    else -> Resolved.Success(decoded)
                }
            }

            else -> Resolved.Failure(ExternalAutomationRejectionReason.PROMPT_MISSING)
        }
    }

    /**
     * Decodes a base64 prompt to text.
     *
     * @param encoded The base64 payload, already known to be non-blank.
     * @return The decoded UTF-8 text, or `null` when the payload is not valid
     *   base64 in the standard alphabet.
     */
    private fun decodeBase64(encoded: String): String? = try {
        String(BASE64.decode(encoded), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Wraps a refusal so a private helper can report a reason without the caller
     * unpacking a nullable.
     *
     * @param reason Why the request was refused.
     * @return The invalid parse result.
     */
    private fun invalid(reason: ExternalAutomationRejectionReason): ExternalAutomationParseResult =
        ExternalAutomationParseResult.Invalid(reason)

    /** Internal either-type for the private resolution helpers. */
    private sealed interface Resolved<out T> {
        /** A resolved value. */
        data class Success<T>(val value: T) : Resolved<T>

        /** A refusal carrying the reason to report. */
        data class Failure(val reason: ExternalAutomationRejectionReason) : Resolved<Nothing>
    }

    private companion object {
        /**
         * Standard RFC 4648 alphabet, padding optional on decode. Padding is the
         * one dimension where callers disagree harmlessly, so accepting both
         * spellings costs nothing and removes a whole class of "works in my
         * shell" reports.
         */
        val BASE64: Base64 = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
    }
}

/**
 * Outcome of parsing an external-automation call.
 *
 * A two-case result rather than a nullable request, because the reason a call
 * was refused is not diagnostic detail here: it is written to the request
 * journal and shown to the user in the settings surface.
 */
sealed interface ExternalAutomationParseResult {

    /**
     * The call was well-formed.
     *
     * @property request The validated request.
     */
    data class Parsed(val request: ExternalAutomationRequest) : ExternalAutomationParseResult

    /**
     * The call could not be interpreted.
     *
     * @property reason Why it was refused.
     */
    data class Invalid(val reason: ExternalAutomationRejectionReason) : ExternalAutomationParseResult
}
