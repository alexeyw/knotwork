package app.knotwork.android.domain.constants

/**
 * The wire vocabulary of the external-automation entry point: the action a
 * third-party automation app broadcasts to run a pipeline, the keys it puts its
 * parameters under, and the action and keys of the callback it may receive back.
 *
 * **This object is the single source of truth for the public contract.** The
 * reference tables in `docs/external-automation.md` are generated from it by
 * `./gradlew :app:generateExternalAutomationDocs`, and `check` fails when the
 * committed documentation has drifted. Documentation that disagrees with the
 * code is worse than no documentation here: a third-party profile written
 * against the wrong key fails silently — the request simply looks malformed to
 * the app and the caller sees a refusal with no obvious cause.
 *
 * Every value below is therefore **frozen once released**. Renaming a key does
 * not migrate anything; it breaks every profile already written against it.
 *
 * The object lives in `domain` and holds nothing but strings: the framework-side
 * receiver maps an `Intent` onto these keys, and the pure parser interprets the
 * result, so neither the contract nor its tests need Android.
 */
object ExternalAutomationContract {

    /** Broadcast action a caller sends to ask the app to run a pipeline. */
    const val ACTION_RUN_PIPELINE: String = "app.knotwork.android.action.RUN_PIPELINE"

    /**
     * Default broadcast action of the terminal callback. Used when a caller asks
     * for a callback without naming an action of its own.
     */
    const val ACTION_RUN_RESULT: String = "app.knotwork.android.action.RUN_RESULT"

    /** Request key: id of the pipeline to run. Mutually exclusive with [EXTRA_PIPELINE_NAME]. */
    const val EXTRA_PIPELINE_ID: String = "pipeline_id"

    /** Request key: user-visible name of the pipeline to run. Mutually exclusive with [EXTRA_PIPELINE_ID]. */
    const val EXTRA_PIPELINE_NAME: String = "pipeline_name"

    /** Request key: the prompt to run the pipeline on, as plain text. Mutually exclusive with [EXTRA_PROMPT_B64]. */
    const val EXTRA_PROMPT: String = "prompt"

    /**
     * Request key: the prompt as a base64-encoded UTF-8 string, for callers whose
     * shell quoting cannot carry the text intact. Mutually exclusive with [EXTRA_PROMPT].
     */
    const val EXTRA_PROMPT_B64: String = "prompt_b64"

    /**
     * Request key: caller-minted correlation id. Required only of a caller that
     * asked to be answered ([EXTRA_RETURN_PACKAGE]); a fire-and-forget call may
     * omit it. The callback carries it back under this same key, so a caller
     * reads back the id it wrote.
     *
     * There is deliberately no separate callback-side constant holding the same
     * value: two constants for one wire key are two things to rename out of
     * step, on a key that is frozen once callers depend on it.
     */
    const val EXTRA_REQUEST_ID: String = "request_id"

    /** Request key: broadcast action to send the callback with. Optional; defaults to [ACTION_RUN_RESULT]. */
    const val EXTRA_RETURN_ACTION: String = "return_action"

    /**
     * Request key: package to deliver the callback to, as a broadcast directed at
     * that package (not at a component of it). Optional — omitting it is a valid
     * fire-and-forget call.
     */
    const val EXTRA_RETURN_PACKAGE: String = "return_package"

    /** Callback key: the status discriminator (`Accepted` / `Completed` / `Failed` / `Rejected` / `Blocked`). */
    const val EXTRA_STATUS: String = "status"

    /** Callback key: the refusal reason, present only for the `Rejected` and `Blocked` statuses. */
    const val EXTRA_STATUS_REASON: String = "reason"

    /**
     * Longest [EXTRA_REQUEST_ID] the contract accepts, in characters.
     *
     * The id is the one piece of caller text the callback carries back, so its
     * length is how much of a caller's choosing this app will broadcast on the
     * caller's behalf. A correlation id needs a few dozen characters at most (a
     * UUID is 36); anything past this is refused rather than cut, because a cut id
     * no longer correlates with anything.
     */
    const val MAX_REQUEST_ID_LENGTH: Int = 128

    /**
     * Longest [EXTRA_RETURN_ACTION] and [EXTRA_RETURN_PACKAGE] the contract accepts,
     * in characters — the callback's address, which is also caller text the app
     * puts on a broadcast. Generous for any real action string or package name.
     */
    const val MAX_RETURN_ADDRESS_LENGTH: Int = 256
}
