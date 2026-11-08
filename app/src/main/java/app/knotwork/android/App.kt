package app.knotwork.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.knotwork.android.data.backup.PendingRestoreApplier
import app.knotwork.android.data.backup.RestoreLayout
import app.knotwork.android.data.logging.CrashlyticsTimberTree
import app.knotwork.android.domain.repositories.CrashReportingRepository
import app.knotwork.android.domain.repositories.PrivacySettings
import app.knotwork.android.domain.services.ScheduledTaskNotifier
import app.knotwork.android.presentation.theme.KnotworkFontsBootstrap
import com.jakewharton.processphoenix.ProcessPhoenix
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

/**
 * Base Application class for the Knotwork project.
 *
 * This class is annotated with @HiltAndroidApp to trigger Hilt's code generation,
 * including a base class for the application that serves as the application-level
 * dependency container.
 *
 * It implements Configuration.Provider to configure WorkManager with HiltWorkerFactory,
 * allowing dependencies to be injected into CoroutineWorkers.
 *
 * It acts as the primary entry point for setting up global application state,
 * integrating Dagger-Hilt for dependency injection across the presentation,
 * domain, and data layers of our Clean Architecture.
 *
 * Also owns the Crashlytics opt-in lifecycle: in release builds the app observes
 * [PrivacySettings.crashReportingEnabled] and plants / uproots
 * [CrashlyticsTimberTree] in response to the user's choice. Debug builds plant
 * only [Timber.DebugTree] and never touch Crashlytics, so local development
 * never accidentally uploads logs.
 */
@HiltAndroidApp
class App :
    Application(),
    Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var privacySettings: PrivacySettings

    @Inject
    lateinit var crashReportingRepository: CrashReportingRepository

    @Inject
    lateinit var scheduledTaskNotifier: ScheduledTaskNotifier

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var crashlyticsTree: CrashlyticsTimberTree? = null

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        // A restore the user confirmed replaces the database and the files before anything can open them:
        // the dependencies are built in super.onCreate().
        val restoreFailure = applyCommittedRestore()
        super.onCreate()
        // A restart passes through a short-lived process that runs this class too; it must not read the
        // settings or the database while the old process is still alive.
        if (ProcessPhoenix.isPhoenixProcess(this)) return
        // Install the bundled Inter / JetBrains Mono families into the
        // design-system typography sheet before the first Compose composition
        // so screens render against the brand fonts on the very first frame.
        KnotworkFontsBootstrap.install()
        scheduledTaskNotifier.registerChannel()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else if (BuildConfig.CRASH_REPORTING_AVAILABLE) {
            // Only the `full` distribution has a live crash collector behind the
            // opt-in. The `foss` build binds a no-op `CrashReportingRepository`,
            // so wiring the observer there would needlessly plant a Timber tree
            // that dispatches every WARN/ERROR log into a no-op for the whole
            // process lifetime — skip it entirely (a release build plants no
            // tree until the user opts in anyway).
            observeCrashReportingOptIn()
        }
        restoreFailure?.let {
            Timber.e(it, "A confirmed restore's files could not all be moved; the next start retries")
        }
    }

    /**
     * Re-registers the task-results notification channel after a configuration
     * change, so its name and description follow a new interface language.
     *
     * Registering an existing channel again updates only its name and
     * description; the user's own choices for it stay. The other channels are
     * registered right before each notification they post, so they follow by
     * themselves; this one is registered once at start, and without this a
     * language picked for the app would leave its name in the old language until
     * the next process start.
     *
     * @param newConfig The configuration now in force.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (ProcessPhoenix.isPhoenixProcess(this)) return
        scheduledTaskNotifier.registerChannel()
    }

    private fun isMainProcess(): Boolean = Application.getProcessName() == packageName

    /**
     * Puts a committed restore's database and files in place, in the app's main process only — a
     * restart passes through a short-lived process of its own, which runs this class too.
     *
     * @return What stopped it, to be logged once logging is set up; `null` when it finished or there was
     *   nothing to apply.
     */
    private fun applyCommittedRestore(): IOException? {
        if (!isMainProcess()) return null
        return try {
            PendingRestoreApplier.applyIfCommitted(RestoreLayout.of(this))
            null
        } catch (e: IOException) {
            e
        }
    }

    /**
     * Subscribes to the persisted opt-in flag and keeps the
     * [CrashlyticsTimberTree] planted exactly when the flag is `true`. Toggling
     * both the Timber sink and the underlying Firebase collection flag from a
     * single observer keeps the user-visible state consistent across process
     * restarts.
     */
    private fun observeCrashReportingOptIn() {
        privacySettings.crashReportingEnabled
            .distinctUntilChanged()
            .onEach { enabled ->
                crashReportingRepository.setEnabled(enabled)
                if (enabled) {
                    if (crashlyticsTree == null) {
                        val tree = CrashlyticsTimberTree(crashReportingRepository, applicationScope)
                        crashlyticsTree = tree
                        Timber.plant(tree)
                    }
                } else {
                    crashlyticsTree?.let { tree ->
                        Timber.uproot(tree)
                        crashlyticsTree = null
                    }
                }
            }
            .launchIn(applicationScope)
    }
}
