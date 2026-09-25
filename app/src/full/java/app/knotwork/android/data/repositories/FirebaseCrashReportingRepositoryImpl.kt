package app.knotwork.android.data.repositories

import app.knotwork.android.domain.repositories.CrashReportingRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase-backed implementation of [CrashReportingRepository].
 *
 * Every method reads the current value of
 * [SettingsRepository.crashReportingEnabled] before touching the Firebase
 * SDK; when the flag is `false` the call short-circuits to a no-op so no
 * data leaves the device. This matches the project's opt-in privacy
 * contract: the manifest disables auto-collection at boot, and the runtime
 * gate here guarantees we cannot accidentally upload anything from code
 * paths that were authored before the user opted in.
 *
 * Consent covers Crashlytics and nothing else. An earlier revision also flipped
 * `FirebaseAnalytics.setAnalyticsCollectionEnabled` here, on the premise that
 * Crashlytics requires Analytics — it does not (it depends on
 * `firebase-measurement-connector`, and falls back to a disabled breadcrumb
 * source when no Analytics implementation is present). The app has never logged
 * an analytics event, so that call collected automatic events the consent
 * dialog never mentioned. The Analytics SDK is no longer a dependency of this
 * build at all, which is what makes the guarantee structural rather than a
 * promise to remember.
 *
 * @property settingsRepository Single source of truth for the user's
 *                              opt-in flag.
 * @property crashlytics Firebase Crashlytics singleton (injected for tests).
 */
@Singleton
class FirebaseCrashReportingRepositoryImpl @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val crashlytics: FirebaseCrashlytics,
) : CrashReportingRepository {

    override suspend fun setEnabled(enabled: Boolean) {
        runCatching {
            crashlytics.isCrashlyticsCollectionEnabled = enabled
        }.onFailure { error ->
            Timber.e(error, "Failed to toggle Crashlytics collection to %s", enabled)
        }
    }

    override suspend fun recordException(throwable: Throwable, extras: Map<String, String>) {
        if (!settingsRepository.crashReportingEnabled.first()) return
        runCatching {
            // Extras are attached as Crashlytics log breadcrumbs (not custom keys) so they
            // appear in the report's log trail without persisting into the session-wide
            // custom-key namespace and leaking into every subsequent crash. Use
            // `setCustomKey` (via [setCustomKey] on this repository) when the value really
            // is session-global, e.g. the active pipeline / model.
            extras.forEach { (key, value) -> crashlytics.log("$key=$value") }
            crashlytics.recordException(throwable)
        }.onFailure { error ->
            Timber.e(error, "Failed to record exception to Crashlytics")
        }
    }

    override suspend fun setCustomKey(key: String, value: String) {
        if (!settingsRepository.crashReportingEnabled.first()) return
        runCatching {
            crashlytics.setCustomKey(key, value)
        }.onFailure { error ->
            Timber.e(error, "Failed to set Crashlytics custom key %s", key)
        }
    }
}
