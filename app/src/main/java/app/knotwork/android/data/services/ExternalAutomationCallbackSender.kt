package app.knotwork.android.data.services

import android.content.Context
import android.content.Intent
import app.knotwork.android.domain.constants.ExternalAutomationContract
import app.knotwork.android.domain.models.ExternalAutomationRejectionReason
import app.knotwork.android.domain.models.ExternalAutomationStatus
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.ExternalAutomationCallbackNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends external-automation status callbacks as package-directed broadcasts.
 *
 * **Package-directed, not component-explicit.** The intent carries the caller's
 * action and `setPackage(...)`, never a `ComponentName`: the app cannot know the
 * caller's receiver class, and automation apps of the Tasker class register their
 * receivers at runtime, which a fully explicit component can never reach.
 *
 * **No `<queries>` entry is needed to deliver this.** Package-visibility filtering
 * governs `PackageManager` lookups and explicit interactions such as starting a
 * service; broadcast delivery resolves its receivers under the system identity, so
 * a package this app cannot otherwise see still receives the callback. Visibility
 * is only needed to *describe* such a package in the UI — which is why the
 * manifest declares visibility by capability rather than trying to enumerate every
 * automation app that might call.
 *
 * **The one place a callback leaves the app, so the place that says no.** Every
 * callback — the receiver's immediate answer and the run-termination hook's final
 * report — goes through [notifyOutcome], and it withholds two kinds:
 * - **anything while the contract is switched off.** The switch is read here, at
 *   send time, because the request is parsed (and may be refused, and answered)
 *   before the use case ever reads it: a rule keyed on the `CONTRACT_DISABLED`
 *   reason would still answer every *malformed* request sent to a user who never
 *   turned the feature on — a broadcast of the caller's choosing, from this app's
 *   uid, on demand. It also silences the final report of a run admitted before the
 *   user switched it off: off means nothing leaves.
 * - **any callback whose caller-chosen parts exceed the contract's ceilings**
 *   ([ExternalAutomationContract.MAX_REQUEST_ID_LENGTH],
 *   [ExternalAutomationContract.MAX_RETURN_ADDRESS_LENGTH]). The parser refuses such
 *   a request, but a refusal decided *before* those checks (an unknown action)
 *   still carries the raw values, and this seam is what bounds them all.
 *
 * `app/src/main` has no other `sendBroadcast`; `OutboundBroadcastCensusTest` keeps it
 * that way, so a second path cannot skip these two rules unnoticed.
 *
 * @property context Application context the broadcast is sent from.
 * @property settingsRepository Source of the contract's master switch.
 */
@Singleton
class ExternalAutomationCallbackSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) : ExternalAutomationCallbackNotifier {

    override suspend fun notifyOutcome(
        returnPackage: String,
        returnAction: String,
        requestId: String,
        status: ExternalAutomationStatus,
    ) {
        if (!settingsRepository.externalAutomationEnabled.first()) {
            Timber.tag(TAG).d("External automation is switched off; callback withheld")
            return
        }
        if (requestId.length > ExternalAutomationContract.MAX_REQUEST_ID_LENGTH ||
            returnPackage.length > ExternalAutomationContract.MAX_RETURN_ADDRESS_LENGTH ||
            returnAction.length > ExternalAutomationContract.MAX_RETURN_ADDRESS_LENGTH
        ) {
            // Nothing of the values themselves is logged: they are the caller's
            // text, and the point is not to repeat it anywhere.
            Timber.tag(TAG).w("External-automation callback withheld: a caller-chosen value is over its ceiling")
            return
        }
        val intent = Intent(returnAction)
            .setPackage(returnPackage)
            .putExtra(ExternalAutomationContract.EXTRA_REQUEST_ID, requestId)
            .putExtra(ExternalAutomationContract.EXTRA_STATUS, status.wireName())
        status.reasonOrNull()?.let {
            intent.putExtra(ExternalAutomationContract.EXTRA_STATUS_REASON, it.name)
        }
        try {
            context.sendBroadcast(intent)
        } catch (e: RuntimeException) {
            // Delivery is a courtesy to a third-party app; nothing about the run
            // depends on it. Absorbed so a caller that uninstalled itself, or named
            // a package that refuses the broadcast, cannot fail the run it started.
            Timber.tag(TAG).w(e, "External-automation callback to %s failed; ignored", returnPackage)
        }
    }

    private companion object {
        /** Timber tag for callback diagnostics. */
        const val TAG = "ExternalAutomation"

        /**
         * The published wire name of a status.
         *
         * These five strings are the third-party contract documented in
         * `docs/external-automation.md`; a caller branches on them, so they are
         * spelled out here rather than derived from a Kotlin name that a refactor
         * could silently change.
         *
         * @return The wire name to put on the callback.
         */
        fun ExternalAutomationStatus.wireName(): String = when (this) {
            ExternalAutomationStatus.Accepted -> "Accepted"
            ExternalAutomationStatus.Completed -> "Completed"
            ExternalAutomationStatus.Failed -> "Failed"
            is ExternalAutomationStatus.Rejected -> "Rejected"
            is ExternalAutomationStatus.Blocked -> "Blocked"
        }

        /**
         * The refusal reason a status carries, if any.
         *
         * @return The reason for a refusal, `null` for the other three statuses.
         */
        fun ExternalAutomationStatus.reasonOrNull(): ExternalAutomationRejectionReason? = when (this) {
            is ExternalAutomationStatus.Rejected -> reason
            is ExternalAutomationStatus.Blocked -> reason
            else -> null
        }
    }
}
