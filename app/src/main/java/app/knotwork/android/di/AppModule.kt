package app.knotwork.android.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.work.WorkManager
import app.knotwork.android.data.local.AppDatabase
import app.knotwork.android.data.local.Converters
import app.knotwork.android.data.local.DeferredPassphraseOpenHelperFactory
import app.knotwork.android.data.local.EncryptedDbPassphraseProvider
import app.knotwork.android.data.local.crypto.AeadCipher
import app.knotwork.android.data.local.crypto.KeystoreBackedPrefsStore
import app.knotwork.android.data.local.crypto.SecretStore
import app.knotwork.android.data.local.dao.ChatDao
import app.knotwork.android.data.local.dao.ChatHistorySummaryDao
import app.knotwork.android.data.local.dao.ExternalAutomationJournalDao
import app.knotwork.android.data.local.dao.LocalModelDao
import app.knotwork.android.data.local.dao.MemoryDao
import app.knotwork.android.data.local.dao.ModelPerformanceDao
import app.knotwork.android.data.local.dao.PendingInteractionDao
import app.knotwork.android.data.local.dao.PipelineDao
import app.knotwork.android.data.local.dao.PipelinePresetDao
import app.knotwork.android.data.local.dao.PipelineRunDao
import app.knotwork.android.data.local.dao.PromptPresetDao
import app.knotwork.android.data.local.dao.PromptTemplateDao
import app.knotwork.android.data.local.dao.SkillDao
import app.knotwork.android.data.local.dao.TraceStepDao
import app.knotwork.android.data.local.dao.TriggerDao
import app.knotwork.android.data.local.dao.TriggerJournalDao
import app.knotwork.android.data.local.dao.UsageTelemetryDao
import app.knotwork.android.data.network.SharedHttpClient
import app.knotwork.android.data.services.ExternalAutomationCallbackSender
import app.knotwork.android.data.services.WorkManagerTaskScheduler
import app.knotwork.android.data.tools.local.AppFunctionDataCodec
import app.knotwork.android.data.tools.local.LocalAppFunctionManager
import app.knotwork.android.data.tools.local.SearchTool
import app.knotwork.android.domain.services.ApprovalNotifier
import app.knotwork.android.domain.services.CeilingNotifier
import app.knotwork.android.domain.services.ClarificationNotifier
import app.knotwork.android.domain.services.ExternalAutomationCallbackNotifier
import app.knotwork.android.domain.services.RunOutcomeAnnouncer
import app.knotwork.android.domain.services.ScheduledTaskNotifier
import app.knotwork.android.domain.services.TaskScheduler
import app.knotwork.android.presentation.notifications.ApprovalNotificationManager
import app.knotwork.android.presentation.notifications.CeilingNotificationManager
import app.knotwork.android.presentation.notifications.ClarificationNotificationManager
import app.knotwork.android.presentation.notifications.ScheduledTaskNotifierImpl
import app.knotwork.android.presentation.run.RunOutcomeAnnouncerImpl
import app.knotwork.android.presentation.state.ActiveSessionTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Global application-level dependency injection module.
 *
 * This module is installed in the SingletonComponent, meaning the dependencies
 * provided here will live as long as the application itself.
 * Use this module to provide system-wide singletons, such as application context
 * providers, database instances, network clients, etc.
 */
@Module
@InstallIn(SingletonComponent::class)
@Suppress("TooManyFunctions") // Provider-only module: one @Provides per database / DAO singleton.
object AppModule {

    private const val USER_PREFERENCES_NAME = "agent_preferences"

    /**
     * Backing file name of the settings-secrets store. Must stay byte-identical
     * to the value previously baked into `SettingsManager`, or existing users'
     * encrypted entries (API keys, Hugging Face token, MCP credentials) would be
     * orphaned under a new file.
     */
    private const val SETTINGS_SECRETS_PREFS_NAME = "secure_settings_secrets"

    /** Android Keystore alias of the AEAD key dedicated to the settings-secrets store. */
    private const val SETTINGS_SECRETS_KEY_ALIAS = "knotwork.settings_secrets"

    /**
     * Provides the singleton instance of the DataStore preferences.
     */
    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext appContext: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { appContext.preferencesDataStoreFile(USER_PREFERENCES_NAME) },
        )

    /**
     * Provides the deferred SQLCipher open-helper factory as its own singleton so the
     * user-confirmed data wipe ([app.knotwork.android.data.local.DatabaseResetServiceImpl])
     * can run inside [DeferredPassphraseOpenHelperFactory.runExclusive], serialized against
     * every concurrent database open.
     *
     * sqlcipher-android retains the passphrase array for the helper's lifetime (it re-keys
     * every pooled connection from it — unlike the legacy android-database-sqlcipher, it never
     * zeroes the array); the provider hands over a fresh copy, so the retained array never
     * aliases the stored value.
     */
    @Provides
    @Singleton
    fun provideDeferredPassphraseOpenHelperFactory(
        passphraseProvider: EncryptedDbPassphraseProvider,
    ): DeferredPassphraseOpenHelperFactory = DeferredPassphraseOpenHelperFactory(passphraseProvider) { passphrase ->
        SupportOpenHelperFactory(passphrase)
    }

    /**
     * Provides the singleton instance of the Room Database.
     *
     * The database is encrypted at rest via SQLCipher. A random 32-byte passphrase is stored
     * in a [app.knotwork.android.data.local.crypto.KeystoreBackedPrefsStore] (AES-GCM under a
     * dedicated Android Keystore key).
     *
     * **Migration policy.** Every schema-version bump is backed by an explicit
     * [androidx.room.migration.Migration] registered through [addMigrations]; the full chain is
     * declared on [AppDatabase]. Destructive recreation on **upgrade** has been removed, so a
     * version bump preserves all user data (chats, long-term memory, run traces, custom
     * pipelines, saved presets and prompt templates) instead of dropping the tables. A missing
     * upgrade path is therefore a hard failure surfaced in development rather than silent data
     * loss in the field.
     *
     * Destructive recreation is retained **only on downgrade**
     * ([fallbackToDestructiveMigrationOnDowngrade]) — forward migrations cannot reverse a schema,
     * so installing an older build over a newer database recreates it empty rather than crashing.
     *
     * Legacy plaintext databases from pre-SQLCipher development builds (which predate the public
     * release) are not supported: SQLCipher cannot open them and there is no downgrade path to
     * recreate them. This affects only such dev installs, never a released version.
     *
     * **Passphrase deferral.** The passphrase is intentionally NOT fetched here: this provider
     * runs synchronously during Hilt injection (often on the main thread while a ViewModel is
     * being constructed), so a passphrase failure here would crash the app before any UI error
     * handling exists. [DeferredPassphraseOpenHelperFactory] postpones the fetch to the first
     * real database open, where `AppInitializationUseCase` catches the failure and routes it to
     * the splash recovery screen.
     */
    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext appContext: Context,
        factory: DeferredPassphraseOpenHelperFactory,
    ): AppDatabase {
        // net.zetetic:sqlcipher-android does NOT auto-load its native library the way the
        // legacy android-database-sqlcipher did. Without this explicit load, the first call
        // into SupportOpenHelperFactory would crash with UnsatisfiedLinkError. loadLibrary is
        // idempotent, so calling it here (inside the @Singleton provider) is safe.
        System.loadLibrary("sqlcipher")

        return Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME,
        )
            .openHelperFactory(factory)
            .addMigrations(
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16,
                AppDatabase.MIGRATION_16_17,
                AppDatabase.MIGRATION_17_18,
                AppDatabase.MIGRATION_18_19,
                AppDatabase.MIGRATION_19_20,
                AppDatabase.MIGRATION_20_21,
                AppDatabase.MIGRATION_21_22,
                AppDatabase.MIGRATION_22_23,
                AppDatabase.MIGRATION_23_24,
                AppDatabase.MIGRATION_24_25,
                AppDatabase.MIGRATION_25_26,
                AppDatabase.MIGRATION_26_27,
                AppDatabase.MIGRATION_27_28,
                AppDatabase.MIGRATION_28_29,
                AppDatabase.MIGRATION_29_30,
                AppDatabase.MIGRATION_30_31,
                AppDatabase.MIGRATION_31_32,
                AppDatabase.MIGRATION_32_33,
                AppDatabase.MIGRATION_33_34,
                AppDatabase.MIGRATION_34_35,
                AppDatabase.MIGRATION_35_36,
                AppDatabase.MIGRATION_36_37,
                AppDatabase.MIGRATION_37_38,
                AppDatabase.MIGRATION_38_39,
                AppDatabase.MIGRATION_39_40,
                AppDatabase.MIGRATION_40_41,
                AppDatabase.MIGRATION_41_42,
                AppDatabase.MIGRATION_42_43,
                AppDatabase.MIGRATION_43_44,
                AppDatabase.MIGRATION_44_45,
                AppDatabase.MIGRATION_45_46,
                AppDatabase.MIGRATION_46_47,
                AppDatabase.MIGRATION_47_48,
                AppDatabase.MIGRATION_48_49,
                AppDatabase.MIGRATION_49_50,
                AppDatabase.MIGRATION_50_51,
                AppDatabase.MIGRATION_51_52,
                AppDatabase.MIGRATION_52_53,
                AppDatabase.MIGRATION_53_54,
                AppDatabase.MIGRATION_54_55,
                AppDatabase.MIGRATION_55_56,
                AppDatabase.MIGRATION_56_57,
                AppDatabase.MIGRATION_57_58,
                AppDatabase.MIGRATION_58_59,
                AppDatabase.MIGRATION_59_60,
                AppDatabase.MIGRATION_60_61,
                AppDatabase.MIGRATION_61_62,
                AppDatabase.MIGRATION_62_63,
            )
            // No destructive fallback on upgrade: every version bump must supply an explicit
            // migration above so user data survives. Destructive recreation is kept only for the
            // (rare) downgrade case, which forward migrations cannot handle.
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
    }

    /**
     * Provides the [LocalModelDao] from the database.
     */
    @Provides
    fun provideLocalModelDao(database: AppDatabase): LocalModelDao = database.localModelDao()

    /**
     * Provides the [ChatDao] from the database.
     */
    @Provides
    fun provideChatDao(database: AppDatabase): ChatDao = database.chatDao()

    /**
     * Provides the [MemoryDao] from the database.
     */
    @Provides
    fun provideMemoryDao(database: AppDatabase): MemoryDao = database.memoryDao()

    /**
     * Provides the [PipelineDao] from the database.
     */
    @Provides
    fun providePipelineDao(database: AppDatabase): PipelineDao = database.pipelineDao()

    /**
     * Provides the [PromptTemplateDao] from the database.
     */
    @Provides
    fun providePromptTemplateDao(database: AppDatabase): PromptTemplateDao = database.promptTemplateDao()

    /**
     * Provides the [TraceStepDao] from the database.
     */
    @Provides
    fun provideTraceStepDao(database: AppDatabase): TraceStepDao = database.traceStepDao()

    /**
     * Provides the [PipelinePresetDao] backing the user-saved
     * pipeline-preset catalogue.
     */
    @Provides
    fun providePipelinePresetDao(database: AppDatabase): PipelinePresetDao = database.pipelinePresetDao()

    /**
     * Provides the [PromptPresetDao] backing the user-saved
     * prompt-preset catalogue.
     */
    @Provides
    fun providePromptPresetDao(database: AppDatabase): PromptPresetDao = database.promptPresetDao()

    /**
     * Provides the [PipelineRunDao] backing the persistent pipeline-run records.
     */
    @Provides
    fun providePipelineRunDao(database: AppDatabase): PipelineRunDao = database.pipelineRunDao()

    /**
     * Provides the [PendingInteractionDao] backing the parked HITL
     * interaction records of the two-phase waiting protocol.
     */
    @Provides
    fun providePendingInteractionDao(database: AppDatabase): PendingInteractionDao = database.pendingInteractionDao()

    /**
     * Provides the [SkillDao] backing the skill catalogue (bundled + user
     * skills).
     */
    @Provides
    fun provideSkillDao(database: AppDatabase): SkillDao = database.skillDao()

    /**
     * Provides the [ChatHistorySummaryDao] backing per-session compressed-history
     * summaries.
     */
    @Provides
    fun provideChatHistorySummaryDao(database: AppDatabase): ChatHistorySummaryDao = database.chatHistorySummaryDao()

    /**
     * Provides the [ModelPerformanceDao] backing the per-model inference
     * performance samples shown on the model screen.
     */
    @Provides
    fun provideModelPerformanceDao(database: AppDatabase): ModelPerformanceDao = database.modelPerformanceDao()

    /**
     * Provides the [TriggerDao] backing user-defined automation triggers.
     */
    @Provides
    fun provideTriggerDao(database: AppDatabase): TriggerDao = database.triggerDao()

    /**
     * Provides the [TriggerJournalDao] backing the trigger-evaluation journal
     * (the `trigger_evaluations` table).
     */
    @Provides
    fun provideTriggerJournalDao(database: AppDatabase): TriggerJournalDao = database.triggerJournalDao()

    /**
     * Provides the [ExternalAutomationJournalDao] backing the external-automation
     * request journal.
     *
     * @param database The application database.
     * @return The [ExternalAutomationJournalDao] instance.
     */
    @Provides
    fun provideExternalAutomationJournalDao(database: AppDatabase): ExternalAutomationJournalDao =
        database.externalAutomationJournalDao()

    /**
     * Provides the [UsageTelemetryDao] backing the privacy-preserving local
     * usage statistics (the `usage_counter` / `usage_active_day` tables).
     */
    @Provides
    fun provideUsageTelemetryDao(database: AppDatabase): UsageTelemetryDao = database.usageTelemetryDao()

    /**
     * Provides the singleton instance of Converters for Room mapping.
     */
    @Provides
    @Singleton
    fun provideConverters(): Converters = Converters()

    /**
     * Provides the Keystore-backed secret store consumed by `SettingsManager`
     * (API keys, the Hugging Face token, per-server MCP credentials). Exposed via
     * the [SecretStore] seam so unit tests substitute an in-memory fake.
     */
    @Provides
    @Singleton
    fun provideSettingsSecretStore(@ApplicationContext context: Context, cipher: AeadCipher): SecretStore =
        KeystoreBackedPrefsStore(
            context = context,
            prefsName = SETTINGS_SECRETS_PREFS_NAME,
            keyAlias = SETTINGS_SECRETS_KEY_ALIAS,
            cipher = cipher,
        )

    /**
     * Provides the singleton instance of OkHttpClient.
     *
     * Connect/read/write timeouts are set (60s, per the project API conventions)
     * so model downloads and Hugging Face discovery cannot pin a coroutine
     * indefinitely on a stalled connection. No overall `callTimeout` is set on
     * purpose: a multi-GB model download legitimately runs far longer than any
     * single read, and the per-read timeout already trips a genuinely stalled
     * transfer.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = SharedHttpClient.build()

    /**
     * Provides how `search_tool` opens its connection: the platform's own, in the app.
     * The seam exists for the unit suite only (see [SearchTool.ConnectionOpener]).
     */
    @Provides
    fun provideSearchConnectionOpener(): SearchTool.ConnectionOpener = SearchTool.ConnectionOpener.SYSTEM

    /**
     * Provides the singleton instance of LocalAppFunctionManager.
     */
    @Provides
    @Singleton
    fun provideLocalAppFunctionManager(
        @ApplicationContext appContext: Context,
        codec: AppFunctionDataCodec,
    ): LocalAppFunctionManager = LocalAppFunctionManager(appContext, codec)

    /**
     * Provides the singleton instance of WorkManager.
     */
    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext appContext: Context): WorkManager = WorkManager.getInstance(appContext)

    /**
     * Binds the `WorkManager`-backed [WorkManagerTaskScheduler] to the
     * domain-level [TaskScheduler] port consumed by `ScheduleTaskUseCase`,
     * keeping the use case free of any `androidx.work` dependency.
     */
    @Provides
    @Singleton
    fun provideTaskScheduler(impl: WorkManagerTaskScheduler): TaskScheduler = impl

    // The Firebase Crashlytics singleton provider (and the
    // `CrashReportingRepository` binding) live in the `full`-flavour
    // `CrashReportingModule` (under `src/full`) so the shared `main` DI graph
    // never references the Firebase SDK. The `foss` flavour binds a no-op
    // implementation with no Firebase dependency.

    /**
     * Provides the singleton instance of ApprovalNotifier.
     */
    @Provides
    @Singleton
    fun provideApprovalNotifier(
        @ApplicationContext appContext: Context,
        activeSessionTracker: ActiveSessionTracker,
    ): ApprovalNotifier = ApprovalNotificationManager(appContext, activeSessionTracker)

    /**
     * Binds the presentation-layer [ScheduledTaskNotifierImpl] (deep-links into
     * `MainActivity`) to the domain-level [ScheduledTaskNotifier] consumed by
     * the background `AgentWorker`.
     */
    @Provides
    @Singleton
    fun provideScheduledTaskNotifier(impl: ScheduledTaskNotifierImpl): ScheduledTaskNotifier = impl

    /**
     * Binds the presentation-layer [RunOutcomeAnnouncerImpl] to the domain-level
     * [RunOutcomeAnnouncer] the task queue calls when a run settles.
     *
     * The implementation is in `presentation` because the sentence it writes
     * comes from `RunTerminationCopyMapper`, which owns the vocabulary every
     * other surface already uses for the same event.
     */
    @Provides
    @Singleton
    fun provideRunOutcomeAnnouncer(impl: RunOutcomeAnnouncerImpl): RunOutcomeAnnouncer = impl

    /**
     * Binds the presentation-layer [ClarificationNotificationManager]
     * (deep-links into `MainActivity`) to the domain-level
     * [ClarificationNotifier] consumed by the clarification node executor and
     * the parked-run submission path.
     */
    @Provides
    @Singleton
    fun provideClarificationNotifier(impl: ClarificationNotificationManager): ClarificationNotifier = impl

    /**
     * Binds the presentation-layer [CeilingNotificationManager] implementation
     * to the domain-facing [CeilingNotifier] consumed by the execution engine
     * (which raises the pause) and by [ParkedRunResumer] (which tears it down).
     */
    @Provides
    @Singleton
    fun provideCeilingNotifier(impl: CeilingNotificationManager): CeilingNotifier = impl

    /**
     * Binds the data-layer [ExternalAutomationCallbackSender] (a package-directed
     * broadcast) to the domain-level [ExternalAutomationCallbackNotifier] the run
     * termination hook reports an external request's outcome through.
     *
     * @param impl The broadcast-backed implementation.
     * @return The bound notifier interface.
     */
    @Provides
    @Singleton
    fun provideExternalAutomationCallbackNotifier(
        impl: ExternalAutomationCallbackSender,
    ): ExternalAutomationCallbackNotifier = impl
}
