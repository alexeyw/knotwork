package app.knotwork.android.di

import android.content.Context
import app.knotwork.android.data.repositories.FirebaseCrashReportingRepositoryImpl
import app.knotwork.android.domain.repositories.CrashReportingRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * `full`-flavour crash-reporting wiring.
 *
 * This module lives in the `full` source set only, so it is compiled into the
 * full-distribution build (Play / direct APK) and is entirely absent from the
 * `foss` (F-Droid) build, whose counterpart [app.knotwork.android.di.CrashReportingModule]
 * under `src/foss` binds a no-op implementation with no Firebase on the
 * classpath.
 *
 * The module binds the Firebase-backed [CrashReportingRepository] and provides
 * the [FirebaseCrashlytics] singleton it depends on. The Firebase providers were
 * previously in `AppModule`; they were moved here so the shared `main` DI graph
 * never references the Firebase SDK.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CrashReportingModule {

    /**
     * Binds the Firebase-backed [FirebaseCrashReportingRepositoryImpl] to the
     * domain-level [CrashReportingRepository]. The implementation gates every
     * method on [app.knotwork.android.domain.repositories.PrivacySettings.crashReportingEnabled],
     * so the binding is safe to provide unconditionally — no data leaves the
     * device until the user opts in.
     */
    @Binds
    @Singleton
    abstract fun bindCrashReportingRepository(
        repository: FirebaseCrashReportingRepositoryImpl,
    ): CrashReportingRepository

    companion object {

        /**
         * Provides the Firebase Crashlytics singleton.
         *
         * Wired through Hilt so unit tests can substitute a mock instead of
         * touching the static `getInstance()` entry point. Crashlytics
         * auto-collection is disabled by manifest meta-data (in the `full`
         * source set's `AndroidManifest.xml`) — actual upload only happens once
         * the user opts in via
         * [app.knotwork.android.domain.repositories.CrashReportingRepository.setEnabled].
         *
         * Defensively initialises [FirebaseApp] if it hasn't been by
         * `FirebaseInitProvider` yet. The `ProcessPhoenix.triggerRebirth`
         * restart spawns a transient `:phoenix` sub-process whose Hilt graph is
         * constructed before the Firebase ContentProvider has had a chance to
         * fire — without this guard the process crashes with
         * `IllegalStateException: Default FirebaseApp is not initialized` and the
         * restart silently fails.
         */
        @Provides
        @Singleton
        fun provideFirebaseCrashlytics(@ApplicationContext appContext: Context): FirebaseCrashlytics {
            ensureFirebaseInitialised(context = appContext)
            return FirebaseCrashlytics.getInstance()
        }

        /**
         * Idempotent helper that calls [FirebaseApp.initializeApp] when no
         * default app has been registered yet. `initializeApp(Context)` is a
         * no-op the second time around, so this is safe to call from every
         * Firebase provider without churn.
         */
        private fun ensureFirebaseInitialised(context: Context) {
            if (FirebaseApp.getApps(context).isEmpty()) {
                runCatching { FirebaseApp.initializeApp(context) }
            }
        }
    }
}
