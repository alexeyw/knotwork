package app.knotwork.android.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SettingsRegistry] — the single source of truth for the
 * settings information architecture.
 *
 * The completeness test is a **drift guard**: the explicit expected key set
 * below must be edited deliberately when a setting is added/removed, so a new
 * preference can never silently miss the registry (and therefore miss the
 * search index built on top of it). It compares against an explicit set rather
 * than reflecting over `PreferencesKeys`, which mixes user-facing settings with
 * internal/runtime state.
 */
class SettingsRegistryTest {

    @Test
    fun `categories cover the eight ratified ids in display order`() {
        assertEquals(
            listOf(
                SettingsCategoryId.GENERATION,
                SettingsCategoryId.MODELS,
                SettingsCategoryId.MEMORY,
                SettingsCategoryId.PIPELINES,
                SettingsCategoryId.TOOLS,
                SettingsCategoryId.BACKGROUND,
                SettingsCategoryId.PRIVACY,
                SettingsCategoryId.ABOUT,
            ),
            SettingsRegistry.categories.map { it.id },
        )
    }

    @Test
    fun `category order field is contiguous and matches list position`() {
        SettingsRegistry.categories.forEachIndexed { index, category ->
            assertEquals(index, category.order)
        }
    }

    @Test
    fun `every category exposes a non-blank icon key and at least one entry`() {
        SettingsRegistry.categories.forEach { category ->
            assertTrue("icon key blank for ${category.id}", category.iconKey.isNotBlank())
            assertTrue("no entries for ${category.id}", category.entries.isNotEmpty())
        }
    }

    @Test
    fun `each entry reports the category it is filed under`() {
        SettingsRegistry.categories.forEach { category ->
            category.entries.forEach { entry ->
                assertEquals(category.id, entry.categoryId)
            }
        }
    }

    @Test
    fun `setting keys are unique`() {
        val keys = SettingsRegistry.allEntries().mapNotNull { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `registry covers exactly the expected user-facing setting keys`() {
        val expected = setOf(
            // Generation
            "SYSTEM_PROMPT_PREFIX", "TEMPERATURE", "TOP_K", "TOP_P",
            "MAX_CONTEXT_LENGTH", "AUDIO_MAX_DURATION_SEC",
            // Models
            "LOCAL_MODEL_BACKEND", "DEFAULT_PIPELINE_ID",
            // Memory
            "AUTO_EXTRACT_ENABLED", "MEMORY_COMPACTION_ENABLED", "CHAT_HISTORY_COMPRESSION_ENABLED",
            "MEMORY_SEARCH_TOP_K", "MEMORY_SEARCH_THRESHOLD",
            "MEMORY_RECENCY_HALF_LIFE_DAYS", "MEMORY_COMPACTION_AGE_DAYS", "MAX_MEMORY_CHUNKS",
            "CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS", "CHAT_HISTORY_LIVE_WINDOW_SIZE",
            "MEMORY_SUMMARY_DEFAULT_LIMIT", "ACTIVE_EMBEDDING_PROVIDER_ID", "VERBOSE_MEMORY_LOGGING_ENABLED",
            // Pipelines & structured output
            // PIPELINE_MAX_STEPS is deliberately absent: the step ceiling is no
            // longer a keyed row here but part of the run-limits screen, reached
            // through a keyless LINK row (anchor LINK_RUN_LIMITS). It moved with
            // its three siblings, which had never had rows at all.
            "PIPELINE_MAX_NESTING_DEPTH", "STRUCTURED_OUTPUT_MAX_REPAIRS",
            // Tools & workspace
            "TOOL_APPROVAL_POLICY", "BLOCK_DESTRUCTIVE_TOOLS", "BLOCK_NETWORK_FROM_LOCAL_MODEL",
            "TOOL_CALL_TIMEOUT_MS", "WORKSPACE_MAX_FILE_SIZE_BYTES", "WORKSPACE_MAX_TOTAL_BYTES",
            "WORKSPACE_READ_TOKEN_BUDGET", "HTTP_TOOL_MAX_RESPONSE_BYTES",
            // Background & triggers
            "SCHEDULED_TASK_NOTIFICATIONS",
            "SHARE_TARGET_PIPELINE_ID", "SHARE_REUSE_SESSION", "QUICK_SETTINGS_TILE_PIPELINE_ID",
            "EXTERNAL_AUTOMATION_ENABLED", "EXTERNAL_AUTOMATION_PIPELINE_ID",
            "RESUME_MAX_AGE_HOURS", "BACKGROUND_APPROVAL_WINDOW_HOURS",
            // Privacy
            "CRASH_REPORTING_ENABLED", "TRACE_RETENTION_RUNS_PER_SESSION", "TRACE_RETENTION_MAX_AGE_DAYS",
        )
        assertEquals(expected, SettingsRegistry.allEntries().mapNotNull { it.key }.toSet())
    }

    @Test
    fun `hub-basic set is exactly the ratified five and all are basic-tier`() {
        val hubBasic = SettingsRegistry.hubBasicEntries()
        assertEquals(
            listOf(
                "SYSTEM_PROMPT_PREFIX",
                "LOCAL_MODEL_BACKEND",
                "TOOL_APPROVAL_POLICY",
                "BLOCK_DESTRUCTIVE_TOOLS",
                "CRASH_REPORTING_ENABLED",
            ),
            hubBasic.map { it.key },
        )
        assertTrue(hubBasic.all { it.tier == SettingTier.BASIC })
    }

    @Test
    fun `link rows carry a destination and non-link rows do not`() {
        SettingsRegistry.allEntries().forEach { entry ->
            if (entry.controlType == SettingControlType.LINK) {
                assertTrue("LINK without destination: ${entry.key ?: entry.tier}", entry.linkDestination != null)
            } else {
                assertNull("non-LINK with destination: ${entry.key}", entry.linkDestination)
            }
        }
    }

    @Test
    fun `categoryOf resolves a known key and returns null for an unknown one`() {
        assertEquals(SettingsCategoryId.GENERATION, SettingsRegistry.categoryOf("TEMPERATURE"))
        assertEquals(SettingsCategoryId.PRIVACY, SettingsRegistry.categoryOf("CRASH_REPORTING_ENABLED"))
        assertNull(SettingsRegistry.categoryOf("NOT_A_REAL_KEY"))
    }

    @Test
    fun `allEntries equals the concatenation of category entries`() {
        assertEquals(
            SettingsRegistry.categories.flatMap { it.entries },
            SettingsRegistry.allEntries(),
        )
    }
}
