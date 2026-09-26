package app.knotwork.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.knotwork.android.data.logging.CrashlyticsTimberTree
import app.knotwork.android.domain.repositories.CrashReportingRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.ScheduledTaskNotifier
import app.knotwork.android.presentation.theme.KnotworkFontsBootstrap
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
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
 * [SettingsRepository.crashReportingEnabled] and plants / uproots
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
    lateinit var settingsRepository: SettingsRepository

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
        super.onCreate()
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
    }

    /**
     * Subscribes to the persisted opt-in flag and keeps the
     * [CrashlyticsTimberTree] planted exactly when the flag is `true`. Toggling
     * both the Timber sink and the underlying Firebase collection flag from a
     * single observer keeps the user-visible state consistent across process
     * restarts.
     */
    private fun observeCrashReportingOptIn() {
        settingsRepository.crashReportingEnabled
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
