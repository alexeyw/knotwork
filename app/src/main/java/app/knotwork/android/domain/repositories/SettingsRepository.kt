package app.knotwork.android.domain.repositories

/**
 * Every application setting, as the union of its sections: [GenerationSettings],
 * [NetworkSettings], [MemorySettings], [ToolSettings], [RunSettings], [EntryPointSettings],
 * [PrivacySettings], [AppStateSettings] and [SettingsReset]. Backed by DataStore, with the secret
 * payloads in the Keystore-backed encrypted store.
 *
 * **Transitional.** This composite declares nothing of its own and exists while consumers move to
 * the sections. Production code depends on the section it reads, never on the whole: a consumer
 * that sees all of it can read and write anything, and a test of it has to fake all of it.
 * `SettingsCompositeConsumersTest` refuses a production file that names this interface unless it is
 * on the list of existing consumers, and that list only shrinks. Tests may still mock the
 * composite: a mock of it is a mock of every section.
 */
interface SettingsRepository :
    GenerationSettings,
    NetworkSettings,
    MemorySettings,
    ToolSettings,
    RunSettings,
    EntryPointSettings,
    PrivacySettings,
    AppStateSettings,
    SettingsReset
