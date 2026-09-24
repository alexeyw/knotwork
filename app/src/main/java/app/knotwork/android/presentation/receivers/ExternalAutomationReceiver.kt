package app.knotwork.android.presentation.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import app.knotwork.android.data.services.RunRetentionScheduler
import app.knotwork.android.domain.constants.ExternalAutomationContract
import app.knotwork.android.domain.models.ExternalAutomationInvocation
import app.knotwork.android.domain.models.ExternalAutomationRejectionReason
import app.knotwork.android.domain.models.ExternalAutomationStatus
import app.knotwork.android.domain.services.ExternalAutomationCallbackNotifier
import app.knotwork.android.domain.usecases.automation.HandleExternalAutomationRequestUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * The app's external-automation entry point: the exported receiver another app on
 * the device broadcasts to when it wants a pipeline run.
 *
 * **This class decides nothing.** It copies the intent's action and the contract's
 * extras into an [ExternalAutomationInvocation] and hands them to
 * [HandleExternalAutomationRequestUseCase]; the master switch, the binding
 * allowlist, the rate ceiling and the journal all live behind that call. Keeping
 * the framework boundary this thin is what lets the entire security model be
 * tested off-device, which matters more here than anywhere else in the app — this
 * is the one input path whose callers the project does not control.
 *
 * **Only the contract's own keys are read**, by name, rather than iterating the
 * intent's extras: a receiver exported to every installed app should not unpack an
 * arbitrary caller-supplied bundle, and the contract is a closed vocabulary anyway.
 *
 * **The sender is not identified, and the class does not pretend otherwise.**
 * `getSentFromPackage()` returns a package only when the *sender* opted in with
 * `BroadcastOptions.setShareIdentityEnabled(true)`, which defaults to `false` and
 * which neither automation apps nor `adb` set. `Binder.getCallingUid()` is worse
 * than useless here — inside `onReceive` it returns this app's own uid. So the
 * attested package is passed along as an opportunistic extra, and the trust
 * decision rests entirely on the user's switch and their binding.
 */
@AndroidEntryPoint
class ExternalAutomationReceiver : BroadcastReceiver() {

    /** Decides the request's fate and starts the run when it is admitted. */
    @Inject
    lateinit var handleRequest: HandleExternalAutomationRequestUseCase

    /** Delivers the admission status back to a caller that asked for one. */
    @Inject
    lateinit var callbackNotifier: ExternalAutomationCallbackNotifier

    /**
     * Enqueues the daily retention pass that bounds the request journal.
     *
     * Scheduled from here as well as from the main activity, and that is not
     * belt-and-braces: a broadcast can wake this process without any activity ever
     * being created, so a user who drives the app purely from an automation app
     * would otherwise never enqueue retention at all — on precisely the install
     * whose journal a third-party app is writing to.
     */
    @Inject
    lateinit var runRetentionScheduler: RunRetentionScheduler

    /**
     * Host scope of the suspending decision work bridged through [goAsync].
     * Visible for tests so they can substitute a test scope and await completion
     * deterministically.
     */
    @VisibleForTesting
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Receives one external-automation broadcast.
     *
     * An action this contract does not define is still handed to the use case
     * rather than dropped here, so it is refused with `UNKNOWN_ACTION` **and
     * journalled** — a caller with a typo in its profile needs to see that the
     * broadcast arrived and what it said, which a silent return would hide.
     *
     * @param context The context the receiver is running in.
     * @param intent The received broadcast.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val invocation = ExternalAutomationInvocation(
            action = intent.action.orEmpty(),
            extras = readContractExtras(intent),
        )
        // Read BEFORE goAsync(). `getSentFromPackage()` reads through the pending
        // result, and `goAsync()` clears that field — so bridging first would turn
        // every attested sender into a silent `null`, with nothing failing to show
        // it. Ordering is the whole correctness argument here; do not move this
        // below `launchAsync`.
        val attestedSender = sentFromPackage
        launchAsync {
            val status = handleRequest(invocation = invocation, attestedSenderPackage = attestedSender)
            replyIfRequested(invocation, status)
            ensureRetentionScheduled()
        }
    }

    /**
     * Enqueues the journal retention pass once per process.
     *
     * Deliberately after the decision — the request a user is waiting on must never
     * queue behind housekeeping — and deliberately once: `schedulePeriodic` is
     * idempotent in effect but not free, and the call rate here belongs to a
     * third-party app, so an unguarded call would put a WorkManager transaction on
     * the path of every broadcast a loop can send.
     */
    private fun ensureRetentionScheduled() {
        if (retentionScheduled.compareAndSet(false, true)) {
            runRetentionScheduler.schedulePeriodic()
        }
    }

    /**
     * Copies the contract's request keys out of a caller-supplied intent.
     *
     * Wrapped defensively: unmarshalling extras from an arbitrary installed app can
     * throw on a malformed or hostile parcel, and an exception escaping `onReceive`
     * would crash the app on demand from any app on the device. An unreadable
     * bundle degrades to no extras, which the parser then refuses with a typed
     * reason and the journal records — the same path as any other malformed call.
     *
     * @param intent The received broadcast.
     * @return The contract's request keys and their values, absent ones included as
     *   `null`.
     */
    private fun readContractExtras(intent: Intent): Map<String, String?> = try {
        REQUEST_KEYS.associateWith(intent::getStringExtra)
    } catch (e: RuntimeException) {
        Timber.tag(TAG).w(e, "Unreadable external-automation extras; treating the call as empty")
        REQUEST_KEYS.associateWith { null }
    }

    /**
     * Sends the admission status to the caller when it asked for a callback.
     *
     * Only the immediate decision is reported here. A terminal outcome arrives much
     * later, from the run-termination hook, because the run may park on a
     * human-in-the-loop gate for hours and this receiver is long gone by then.
     *
     * Whether anything is actually sent is decided by the notifier, not here:
     * nothing while the contract is switched off (this reply runs for a request
     * refused before the switch is read, too) and nothing over the contract's
     * length ceilings.
     *
     * @param invocation The raw call, the source of the callback address.
     * @param status The decision to report.
     */
    private suspend fun replyIfRequested(invocation: ExternalAutomationInvocation, status: ExternalAutomationStatus) {
        // The one refusal that must not be answered. Its whole purpose is that the
        // caller named someone else's package for the callback, so sending the
        // refusal to that address would perform the exact broadcast the refusal
        // exists to prevent — and the journal row would then assert the opposite of
        // what happened.
        if (status is ExternalAutomationStatus.Rejected &&
            status.reason == ExternalAutomationRejectionReason.RETURN_PACKAGE_MISMATCH
        ) {
            return
        }
        val returnPackage = invocation.value(ExternalAutomationContract.EXTRA_RETURN_PACKAGE) ?: return
        val requestId = invocation.value(ExternalAutomationContract.EXTRA_REQUEST_ID) ?: return
        callbackNotifier.notifyOutcome(
            returnPackage = returnPackage,
            returnAction = invocation.value(ExternalAutomationContract.EXTRA_RETURN_ACTION)
                ?: ExternalAutomationContract.ACTION_RUN_RESULT,
            requestId = requestId,
            status = status,
        )
    }

    /**
     * Bridges suspending work out of `onReceive` via [goAsync]: the pending result
     * keeps the (possibly freshly launched) process in the foreground priority band
     * until `finish()` is called. Nullable because a directly invoked receiver
     * (unit tests) has no framework-provided pending result.
     *
     * @param block The suspending work to run.
     */
    private fun launchAsync(block: suspend () -> Unit) {
        val pendingResult: PendingResult? = goAsync()
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "External-automation request handling failed")
            } finally {
                pendingResult?.finish()
            }
        }
    }

    /** Process-wide state of the entry point, and its constants. */
    companion object {
        /** Timber tag for entry-point diagnostics. */
        private const val TAG = "ExternalAutomation"

        /**
         * Whether this process has already enqueued the retention pass. Process-wide
         * rather than per-instance: the framework builds a fresh receiver per
         * broadcast, so an instance field would guard nothing.
         *
         * Visible for tests, which share a JVM across test classes and must reset it
         * — a leaked `true` would make a later test silently assert nothing.
         */
        @VisibleForTesting
        internal val retentionScheduled = AtomicBoolean(false)

        /**
         * The request-side keys of the contract, and the only extras this receiver
         * reads. Callback-side keys are absent by design: they travel outward.
         */
        private val REQUEST_KEYS: List<String> = listOf(
            ExternalAutomationContract.EXTRA_PIPELINE_ID,
            ExternalAutomationContract.EXTRA_PIPELINE_NAME,
            ExternalAutomationContract.EXTRA_PROMPT,
            ExternalAutomationContract.EXTRA_PROMPT_B64,
            ExternalAutomationContract.EXTRA_REQUEST_ID,
            ExternalAutomationContract.EXTRA_RETURN_ACTION,
            ExternalAutomationContract.EXTRA_RETURN_PACKAGE,
        )
    }
}
