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
 * **Every record is redacted on the way out.** A provider error can quote the
 * failing request, and Google authenticates by query parameter, so an ordinary
 * transport failure logged anywhere in the app would carry the API key into the
 * crash report. The call-site message, the extras and the message of every
 * throwable in the cause chain pass [CloudErrorSanitizer.redactSecrets] here —
 * one place for every `Timber.w` / `Timber.e` in the app, including the ones
 * written after this line.
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
     * Forwards the record to Crashlytics.
     *
     * When the caller supplied a [Throwable] (`Timber.e(t, "context %s", arg)`),
     * the exception is reported as-is — or as a redacted copy when a message in
     * its cause chain carries a credential — and the formatted [message] / [tag]
     * are attached as `extras` so the call-site context survives — otherwise
     * Crashlytics would only see the bare stack trace.
     *
     * Message-only records (no throwable) are wrapped in a synthetic exception
     * whose message preserves the original tag + body so Crashlytics still has
     * something to stack-trace and group on. Every message is redacted first.
     */
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (t != null) {
            // Timber's base class appends `\n` + stack trace to `message` when a
            // throwable is present (Timber.Tree.prepareLog). The stack already lives on
            // the throwable itself — extract only the original call-site message before
            // the newline so the breadcrumb stays readable.
            val callSiteMessage = CloudErrorSanitizer.redactSecrets(message.substringBefore('\n'))
            val extras = buildMap {
                put(EXTRA_MESSAGE, callSiteMessage)
                if (!tag.isNullOrBlank()) put(EXTRA_TAG, tag)
            }
            val reported = redactedChain(t)
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
     * Returns [error] itself when no throwable reachable from it — through causes or
     * suppressed exceptions — carries a credential, and otherwise a redacted copy of
     * its cause chain.
     *
     * The copy keeps what Crashlytics groups and triages on — each link's stack
     * frames, and its original type, named at the head of its message — and drops
     * the secret and the suppressed exceptions. An untouched chain is passed through
     * as the same instance, so the ordinary report is exactly what it was before
     * redaction existed.
     *
     * @param error The throwable the Timber call site supplied.
     * @return The throwable to report.
     */
    private fun redactedChain(error: Throwable): Throwable {
        if (reachableFrom(error).none(::carriesSecret)) return error
        val chain = mutableListOf(error)
        while (true) {
            val next = chain.last().cause ?: break
            if (chain.any { it === next }) break
            chain += next
        }
        return chain.dropLast(1).foldRight<Throwable, Throwable>(RedactedException(chain.last(), null)) { link, cause ->
            RedactedException(link, cause)
        }
    }

    /**
     * Every throwable reachable from [error] through causes and suppressed
     * exceptions, each once. `initCause` rejects only a direct self-cause, so a
     * longer loop is possible and the walk must not follow one forever.
     */
    private fun reachableFrom(error: Throwable): List<Throwable> {
        val seen = mutableListOf<Throwable>()
        val pending = ArrayDeque(listOf(error))
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (seen.any { it === next }) continue
            seen += next
            next.cause?.let(pending::addLast)
            next.suppressed.forEach(pending::addLast)
        }
        return seen
    }

    /** Whether [error]'s own message contains something [CloudErrorSanitizer] would mask. */
    private fun carriesSecret(error: Throwable): Boolean =
        error.message?.let { text -> CloudErrorSanitizer.redactSecrets(text) != text } == true

    /**
     * Stand-in for a throwable whose message carried a credential: the original's
     * type and redacted message as its message, the original's stack frames as its
     * own.
     *
     * @param original The throwable being reported.
     * @param cause The already-redacted stand-in for [original]'s cause, or `null`.
     */
    private class RedactedException(original: Throwable, cause: Throwable?) :
        Exception(
            original.message
                ?.let { "${original::class.java.name}: ${CloudErrorSanitizer.redactSecrets(it)}" }
                ?: original::class.java.name,
            cause,
        ) {
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
