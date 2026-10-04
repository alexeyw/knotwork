package app.knotwork.android.data.local

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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The transitional [SettingsRepository] composite: every section delegated, as is, to the instance
 * Hilt binds for it. It implements nothing itself — the sections live in their stores in
 * `data/local/settings/`, the resets in `SettingsResetStore` — and exists only for the few consumers
 * that still take every setting at once (`SettingsCompositeConsumersTest` lists them, and the list
 * only shrinks).
 *
 * A class-level `@Singleton`, like every class implementing a section (`SettingsSingletonScopeTest`).
 *
 * @param generation The generation and local-model section.
 * @param network The network and cloud-retry section.
 * @param memory The memory and chat-history section.
 * @param tool The tools and workspace section.
 * @param run The run ceilings and background-run section.
 * @param entryPoints The entry-point bindings section.
 * @param privacy The privacy section.
 * @param appState The app-state section.
 * @param reset The two resets that span several sections.
 */
@Singleton
class SettingsManager @Inject constructor(
    generation: GenerationSettings,
    network: NetworkSettings,
    memory: MemorySettings,
    tool: ToolSettings,
    run: RunSettings,
    entryPoints: EntryPointSettings,
    privacy: PrivacySettings,
    appState: AppStateSettings,
    reset: SettingsReset,
) : SettingsRepository,
    GenerationSettings by generation,
    NetworkSettings by network,
    MemorySettings by memory,
    ToolSettings by tool,
    RunSettings by run,
    EntryPointSettings by entryPoints,
    PrivacySettings by privacy,
    AppStateSettings by appState,
    SettingsReset by reset
