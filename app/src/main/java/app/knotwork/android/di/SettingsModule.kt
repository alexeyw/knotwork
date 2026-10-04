package app.knotwork.android.di

import app.knotwork.android.data.local.SettingsManager
import app.knotwork.android.data.local.settings.AppStateSettingsStore
import app.knotwork.android.data.local.settings.EntryPointSettingsStore
import app.knotwork.android.data.local.settings.GenerationSettingsStore
import app.knotwork.android.data.local.settings.MemorySettingsStore
import app.knotwork.android.data.local.settings.NetworkSettingsStore
import app.knotwork.android.data.local.settings.PrivacySettingsStore
import app.knotwork.android.data.local.settings.RunSettingsStore
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
 * A section split out into its own store is bound to that store; the rest are bound to the one
 * [SettingsManager], which delegates to the same store instances. Every class here is a class-level
 * `@Singleton`: a scope on a `@Binds` method caches per binding, so several scoped bindings of an
 * unscoped class would each build their own instance, each with its own copy of the in-memory MCP
 * credential cache, the mutex serialising server edits, and the Hugging Face token flow — an edit
 * made through one section would then be invisible to a read through another.
 * `SettingsSingletonScopeTest` keeps the scope on every class that implements a section.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {

    /** Binds the [SettingsManager] implementation to the transitional [SettingsRepository] composite. */
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(settingsManager: SettingsManager): SettingsRepository

    /** Binds the generation and local-model section to its store. */
    @Binds
    @Singleton
    abstract fun bindGenerationSettings(store: GenerationSettingsStore): GenerationSettings

    /** Binds the network and cloud-retry section to its store. */
    @Binds
    @Singleton
    abstract fun bindNetworkSettings(store: NetworkSettingsStore): NetworkSettings

    /** Binds the memory and chat-history section to its store. */
    @Binds
    @Singleton
    abstract fun bindMemorySettings(store: MemorySettingsStore): MemorySettings

    /** Binds the tools and workspace section. */
    @Binds
    @Singleton
    abstract fun bindToolSettings(settingsManager: SettingsManager): ToolSettings

    /** Binds the run ceilings and background-run section to its store. */
    @Binds
    @Singleton
    abstract fun bindRunSettings(store: RunSettingsStore): RunSettings

    /** Binds the entry-point bindings section to its store. */
    @Binds
    @Singleton
    abstract fun bindEntryPointSettings(store: EntryPointSettingsStore): EntryPointSettings

    /** Binds the privacy section to its store. */
    @Binds
    @Singleton
    abstract fun bindPrivacySettings(store: PrivacySettingsStore): PrivacySettings

    /** Binds the app-state section to its store. */
    @Binds
    @Singleton
    abstract fun bindAppStateSettings(store: AppStateSettingsStore): AppStateSettings

    /** Binds the cross-section resets. */
    @Binds
    @Singleton
    abstract fun bindSettingsReset(settingsManager: SettingsManager): SettingsReset
}
