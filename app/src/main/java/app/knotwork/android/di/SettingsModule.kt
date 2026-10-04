package app.knotwork.android.di

import app.knotwork.android.data.local.SettingsManager
import app.knotwork.android.domain.repositories.AppStateSettings
import app.knotwork.android.domain.repositories.EntryPointSettings
import app.knotwork.android.domain.repositories.GenerationSettings
import app.knotwork.android.domain.repositories.MemorySettings
import app.knotwork.android.domain.repositories.NetworkSettings
import app.knotwork.android.domain.repositories.PrivacySettings
import app.knotwork.android.domain.repositories.RunSettings
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.SettingsReset
import app.knotwork.android.domain.repositories.ToolSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings of the settings sections and of the transitional [SettingsRepository] composite.
 *
 * Every binding resolves to the one [SettingsManager] instance. That holds because the class itself
 * is `@Singleton`: a scope on a `@Binds` method caches per binding, so ten scoped bindings of an
 * unscoped class would build ten managers, each with its own copy of the in-memory MCP credential
 * cache, the mutex serialising server edits, and the Hugging Face token flow — an edit made through
 * one section would then be invisible to a read through another. `SettingsSingletonScopeTest` keeps
 * the scope on every class that implements a section.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {

    /** Binds the [SettingsManager] implementation to the transitional [SettingsRepository] composite. */
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(settingsManager: SettingsManager): SettingsRepository

    /** Binds the generation and local-model section. */
    @Binds
    @Singleton
    abstract fun bindGenerationSettings(settingsManager: SettingsManager): GenerationSettings

    /** Binds the network and cloud-retry section. */
    @Binds
    @Singleton
    abstract fun bindNetworkSettings(settingsManager: SettingsManager): NetworkSettings

    /** Binds the memory and chat-history section. */
    @Binds
    @Singleton
    abstract fun bindMemorySettings(settingsManager: SettingsManager): MemorySettings

    /** Binds the tools and workspace section. */
    @Binds
    @Singleton
    abstract fun bindToolSettings(settingsManager: SettingsManager): ToolSettings

    /** Binds the run ceilings and background-run section. */
    @Binds
    @Singleton
    abstract fun bindRunSettings(settingsManager: SettingsManager): RunSettings

    /** Binds the entry-point bindings section. */
    @Binds
    @Singleton
    abstract fun bindEntryPointSettings(settingsManager: SettingsManager): EntryPointSettings

    /** Binds the privacy section. */
    @Binds
    @Singleton
    abstract fun bindPrivacySettings(settingsManager: SettingsManager): PrivacySettings

    /** Binds the app-state section. */
    @Binds
    @Singleton
    abstract fun bindAppStateSettings(settingsManager: SettingsManager): AppStateSettings

    /** Binds the cross-section resets. */
    @Binds
    @Singleton
    abstract fun bindSettingsReset(settingsManager: SettingsManager): SettingsReset
}
