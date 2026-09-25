package app.knotwork.android.data.logging

import android.util.Log
import app.knotwork.android.domain.engine.CloudErrorSanitizer
import app.knotwork.android.domain.repositories.CrashReportingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Timber tree that funnels `Log.WARN` / `Log.ERROR` entries into
 * [CrashReportingRepository] so they show up in Crashlytics alongside
 * fatal crashes. Lower priorities (`VERBOSE`, `DEBUG`, `INFO`) are
 * dropped to keep the upload budget tight and to avoid leaking routine
 * agent traces (prompts, tool inputs, etc.) into the cloud.
 *
 * The tree is only planted in release builds *after* the user opts in to
 * crash reporting (see [app.knotwork.android.App]). The repository itself
 * additionally short-circuits when the opt-in flag is `false`, providing
 * a belt-and-braces guarantee that nothing ever leaves the device while
 * collection is disabled.
 *
 * **A report carries where it happened, never what the user was doing.** PRIVACY
 * §3.5 promises the stack trace and nothing of the user's — and the text around a
 * stack is exactly where the user's data travels: a throwable's message quotes the
 * path it failed on, a provider's error quotes the request (Google authenticates by
 * query parameter), an Android `JSONException` ends with the whole document it
 * failed to parse, and a call site formats paths, names and tool arguments into its
 * message. So, one place for every `Timber.w` / `Timber.e` in the app, including the
 * ones written after this line:
 *  - a throwable is reported as a copy of its cause chain that keeps each link's type
 *    and stack frames and drops its message and its suppressed exceptions;
 *  - a message is reported as its call site's **template** — [formatMessage] never
 *    fills in the arguments, and `TimberMessageTemplateKonsistTest` keeps every
 *    `WARN`+ template a string literal, so nothing dynamic can be baked into it;
 *  - the template is still passed through [CloudErrorSanitizer.redactSecrets], a
 *    backstop for a credential written into the source itself.
 *
 * Crashlytics calls are dispatched via the supplied [CoroutineScope]
 * because the repository methods are `suspend` (they read the persisted
 * opt-in flag from DataStore). The scope is application-lifetime, so the
 * launched job survives the calling thread.
 *
 * @property crashReportingRepository Sink that forwards records to Crashlytics.
 * @property scope Application-scoped coroutine scope used to bridge the
 *                 synchronous Timber callback to the `suspend` repository API.
 */
class CrashlyticsTimberTree(
    private val crashReportingRepository: CrashReportingRepository,
    private val scope: CoroutineScope,
) : Timber.Tree() {

    /**
     * Allows `WARN` and `ERROR` records to pass through this tree.
     * The repository-level opt-in check still applies; this method only
     * filters out low-severity noise before it reaches [log].
     */
    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= Log.WARN

    /**
     * Returns the call site's template untouched: the values a call formats into its
     * message are the user's paths, names and tool arguments, and none of them may
     * reach a report. Timber calls this only when the call passed arguments.
     *
     * @param message The call site's template.
     * @param args The values the call site passed; deliberately unused.
     * @return [message] as written at the call site.
     */
    override fun formatMessage(message: String, args: Array<out Any?>): String = message

    /**
     * Forwards the record to Crashlytics.
     *
     * When the caller supplied a [Throwable] (`Timber.e(t, "context %s", arg)`), a
     * text-free copy of its cause chain is reported (see [typeOnlyChain]) with the
     * call site's template and tag attached as `extras`, so the context survives
     * without the arguments.
     *
     * Message-only records are wrapped in a synthetic exception whose message is the
     * tag and the template, so Crashlytics still has something to stack-trace and
     * group on.
     */
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (t != null) {
            val extras = buildMap {
                callSiteTemplate(message, t)?.let { put(EXTRA_MESSAGE, CloudErrorSanitizer.redactSecrets(it)) }
                if (!tag.isNullOrBlank()) put(EXTRA_TAG, tag)
            }
            val reported = typeOnlyChain(t)
            scope.launch {
                crashReportingRepository.recordException(reported, extras)
            }
            return
        }
        val synthetic = SyntheticLogException(
            CloudErrorSanitizer.redactSecrets(
                buildString {
                    if (!tag.isNullOrBlank()) {
                        append("[")
                        append(tag)
                        append("] ")
                    }
                    append(message)
                },
            ),
        )
        scope.launch {
            crashReportingRepository.recordException(synthetic)
        }
    }

    /**
     * The template the call site wrote, or `null` when it wrote none.
     *
     * Timber hands [log] the template followed by `\n` and the throwable's stack
     * trace — and, when the call passed no message at all, the stack trace alone,
     * whose first line is the throwable's own text. That text must not come back
     * through the extras after the chain has been stripped of it.
     *
     * @param message What Timber passed to [log].
     * @param error The throwable of the record.
     */
    private fun callSiteTemplate(message: String, error: Throwable): String? =
        message.takeUnless { it.startsWith(error.toString()) }
            ?.substringBefore('\n')
            ?.takeIf { it.isNotBlank() }

    /**
     * A copy of [error]'s cause chain that keeps what Crashlytics groups and triages on
     * — each link's type, named as its message, and its stack frames — and nothing
     * else: no link's message, no suppressed exception.
     *
     * @param error The throwable the Timber call site supplied.
     * @return The throwable to report.
     */
    private fun typeOnlyChain(error: Throwable): Throwable {
        val chain = mutableListOf(error)
        while (true) {
            val next = chain.last().cause ?: break
            // `initCause` rejects only a direct self-cause; a longer loop is possible.
            if (chain.any { it === next }) break
            chain += next
        }
        return chain.dropLast(1).foldRight<Throwable, Throwable>(TypeOnlyException(chain.last(), null)) { link, cause ->
            TypeOnlyException(link, cause)
        }
    }

    /**
     * Stand-in for a reported throwable: the original's type as its message, the
     * original's stack frames as its own.
     *
     * @param original The throwable being reported.
     * @param cause The stand-in for [original]'s cause, or `null`.
     */
    private class TypeOnlyException(original: Throwable, cause: Throwable?) :
        Exception(original::class.java.name, cause) {
        init {
            stackTrace = original.stackTrace
        }
    }

    /**
     * Synthetic exception used when a Timber message has no underlying
     * [Throwable]. Kept as a named subclass so Crashlytics groups
     * message-only events together rather than mixing them with real bugs.
     */
    private class SyntheticLogException(message: String) : Exception(message)

    private companion object {
        /** Extras key for the Timber call-site message attached to a throwable. */
        const val EXTRA_MESSAGE = "timber_message"

        /** Extras key for the Timber call-site tag attached to a throwable. */
        const val EXTRA_TAG = "timber_tag"
    }
}
