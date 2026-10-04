package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ratchet on the production files that depend on the whole `SettingsRepository` composite: a new
 * file may not, and the list of files that still do can only shrink.
 *
 * **Why.** The composite is every setting at once. A consumer that holds it can read and write any
 * of them, and a test of that consumer has to fake all of them. The settings are split into
 * sections (`GenerationSettings`, `MemorySettings`, …); production code depends on the section it
 * reads. The composite stays while the existing consumers move, and tests may keep mocking it — a
 * mock of the composite is a mock of every section.
 *
 * **What it reads.** The production sources with comments removed ([ProductionSources]), so a KDoc
 * link to the composite does not count and a use in code does. A listed file that no longer names
 * the composite fails too: delete its line, which is how the list shrinks.
 */
class SettingsCompositeConsumersTest {

    @Test
    fun `no production file depends on the settings composite unless it is listed`() {
        val consumers = ProductionSources.code
            .filter { (path, code) -> COMPOSITE.containsMatchIn(code) && !path.endsWith(DECLARATION) }
            .keys.mapTo(sortedSetOf()) { it.replace(PACKAGE_PATH, "") }

        assertEquals(
            "a new dependency on SettingsRepository — depend on the section the code reads instead " +
                "(GenerationSettings, NetworkSettings, MemorySettings, ToolSettings, RunSettings, " +
                "EntryPointSettings, PrivacySettings, AppStateSettings, SettingsReset)",
            sortedSetOf<String>(),
            consumers - LISTED_CONSUMERS,
        )
        assertEquals(
            "these files no longer depend on SettingsRepository — delete their lines; the list only shrinks",
            sortedSetOf<String>(),
            LISTED_CONSUMERS - consumers,
        )
    }

    @Test
    fun `the census recognises a use and ignores a mention`() {
        assertEquals(true, COMPOSITE.containsMatchIn("private val settings: SettingsRepository,"))
        assertEquals(false, COMPOSITE.containsMatchIn("private val settings: SettingsRepositoryImpl,"))
        assertEquals(
            false,
            COMPOSITE.containsMatchIn(ProductionSources.stripComments("/** Reads [SettingsRepository]. */")),
        )
    }

    private companion object {
        /** The composite's name as a whole word. */
        val COMPOSITE = Regex("""\bSettingsRepository\b""")

        /** The composite's own declaration, which is not a consumer. */
        const val DECLARATION = "/domain/repositories/SettingsRepository.kt"

        /** Dropped from each path to keep the list readable: `main/data/local/…`. */
        const val PACKAGE_PATH = "java/app/knotwork/android/"

        /**
         * The production files that still depend on the composite, by source set and package path:
         * 87 when the sections were introduced. What remains spans every section (the composite, its
         * Hilt module, the settings screen's view model, the end-to-end test entry point) or hands it
         * to several sections' delegates through a constructor already over the parameter limit
         * (the chat home and orchestrator view models), until those view models are split up.
         */
        val LISTED_CONSUMERS = sortedSetOf(
            "main/data/local/SettingsManager.kt",
            "main/data/testing/AppFunctionsE2ETestEntryPoint.kt",
            "main/di/SettingsModule.kt",
            "main/presentation/ui/chat/home/ChatHomeViewModel.kt",
            "main/presentation/ui/orchestrator/OrchestratorViewModel.kt",
            "main/presentation/ui/settings/SettingsViewModel.kt",
        )
    }
}
