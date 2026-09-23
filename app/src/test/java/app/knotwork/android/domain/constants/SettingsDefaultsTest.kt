package app.knotwork.android.domain.constants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the numeric values exposed by [SettingsDefaults] so a silent edit to a
 * default value is caught at test time rather than at runtime by an end user.
 *
 * If a default is intentionally changed, update the asserted value in lock-step
 * with the constant: every change here is a behavioural change to the app.
 */
class SettingsDefaultsTest {

    @Test
    fun `given LLM defaults when read then match documented values`() {
        assertEquals(4_096, SettingsDefaults.MAX_CONTEXT_LENGTH_DEFAULT)
        assertEquals(0.7f, SettingsDefaults.TEMPERATURE_DEFAULT)
        assertEquals(40, SettingsDefaults.TOP_K_DEFAULT)
        assertEquals(0.9f, SettingsDefaults.TOP_P_DEFAULT)
    }

    @Test
    fun `given timeout defaults when read then match documented values`() {
        assertEquals(60_000L, SettingsDefaults.TOOL_CALL_TIMEOUT_MS_DEFAULT)
        assertEquals(60_000L, SettingsDefaults.CLARIFICATION_TIMEOUT_MS_DEFAULT)
    }

    @Test
    fun `given pipeline-steps defaults when read then values bracket the default`() {
        assertEquals(15, SettingsDefaults.PIPELINE_MAX_STEPS_DEFAULT)
        assertEquals(5, SettingsDefaults.PIPELINE_MAX_STEPS_MIN)
        assertEquals(100, SettingsDefaults.PIPELINE_MAX_STEPS_MAX)
        // Sanity: the default value must lie inside the min/max window the UI
        // surfaces. Drift here would let the slider start out clamped on first
        // launch, which would silently overwrite the persisted default.
        assertTrue(
            SettingsDefaults.PIPELINE_MAX_STEPS_DEFAULT in
                SettingsDefaults.PIPELINE_MAX_STEPS_MIN..SettingsDefaults.PIPELINE_MAX_STEPS_MAX,
        )
    }

    @Test
    fun `given memory and ollama defaults when read then match documented values`() {
        assertEquals(4_096, SettingsDefaults.OLLAMA_CONTEXT_WINDOW_DEFAULT)
    }

    @Test
    fun `given centralised toggle and limit defaults when read then match documented values`() {
        assertEquals(false, SettingsDefaults.CRASH_REPORTING_ENABLED_DEFAULT)
        assertEquals(true, SettingsDefaults.USAGE_TELEMETRY_ENABLED_DEFAULT)
        assertEquals(true, SettingsDefaults.SCHEDULED_TASK_NOTIFICATIONS_ENABLED_DEFAULT)
        assertEquals(false, SettingsDefaults.BLOCK_DESTRUCTIVE_TOOLS_DEFAULT)
        assertEquals(false, SettingsDefaults.BLOCK_NETWORK_FROM_LOCAL_MODEL_DEFAULT)
        assertEquals(5, SettingsDefaults.MEMORY_SUMMARY_DEFAULT_LIMIT_DEFAULT)
    }

    /**
     * Every recommended default that ships with editable bounds must fall inside
     * those bounds. A default outside its slider range would be unreachable from
     * the UI or be silently coerced on the first persist — both are bugs the
     * "sensible defaults" audit must keep out.
     */
    @Test
    fun `given every bounded default when read then it lies within its inclusive range`() {
        with(SettingsDefaults) {
            assertInRange(
                "RESUME_MAX_AGE_HOURS",
                RESUME_MAX_AGE_HOURS_DEFAULT.toDouble(),
                RESUME_MAX_AGE_HOURS_MIN.toDouble(),
                RESUME_MAX_AGE_HOURS_MAX.toDouble(),
            )
            assertInRange(
                "BACKGROUND_APPROVAL_WINDOW_HOURS",
                BACKGROUND_APPROVAL_WINDOW_HOURS_DEFAULT.toDouble(),
                BACKGROUND_APPROVAL_WINDOW_HOURS_MIN.toDouble(),
                BACKGROUND_APPROVAL_WINDOW_HOURS_MAX.toDouble(),
            )
            assertInRange(
                "TRACE_RETENTION_RUNS_PER_SESSION",
                TRACE_RETENTION_RUNS_PER_SESSION_DEFAULT.toDouble(),
                TRACE_RETENTION_RUNS_PER_SESSION_MIN.toDouble(),
                TRACE_RETENTION_RUNS_PER_SESSION_MAX.toDouble(),
            )
            assertInRange(
                "TRACE_RETENTION_MAX_AGE_DAYS",
                TRACE_RETENTION_MAX_AGE_DAYS_DEFAULT.toDouble(),
                TRACE_RETENTION_MAX_AGE_DAYS_MIN.toDouble(),
                TRACE_RETENTION_MAX_AGE_DAYS_MAX.toDouble(),
            )
            assertInRange(
                "PIPELINE_MAX_NESTING_DEPTH",
                PIPELINE_MAX_NESTING_DEPTH_DEFAULT.toDouble(),
                PIPELINE_MAX_NESTING_DEPTH_MIN.toDouble(),
                PIPELINE_MAX_NESTING_DEPTH_MAX.toDouble(),
            )
            assertInRange(
                "MEMORY_SEARCH_TOP_K",
                MEMORY_SEARCH_TOP_K_DEFAULT.toDouble(),
                MEMORY_SEARCH_TOP_K_MIN.toDouble(),
                MEMORY_SEARCH_TOP_K_MAX.toDouble(),
            )
            assertInRange(
                "CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS",
                CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS_DEFAULT.toDouble(),
                CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS_MIN.toDouble(),
                CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS_MAX.toDouble(),
            )
            assertInRange(
                "CHAT_HISTORY_LIVE_WINDOW",
                CHAT_HISTORY_LIVE_WINDOW_DEFAULT.toDouble(),
                CHAT_HISTORY_LIVE_WINDOW_MIN.toDouble(),
                CHAT_HISTORY_LIVE_WINDOW_MAX.toDouble(),
            )
            assertInRange(
                "AUDIO_MAX_DURATION_SEC",
                AUDIO_MAX_DURATION_SEC_DEFAULT.toDouble(),
                AUDIO_MAX_DURATION_SEC_MIN.toDouble(),
                AUDIO_MAX_DURATION_SEC_MAX.toDouble(),
            )
            assertInRange(
                "MEMORY_SEARCH_THRESHOLD",
                MEMORY_SEARCH_THRESHOLD_DEFAULT.toDouble(),
                MEMORY_SEARCH_THRESHOLD_MIN.toDouble(),
                MEMORY_SEARCH_THRESHOLD_MAX.toDouble(),
            )
            assertInRange(
                "MEMORY_RECENCY_HALF_LIFE_DAYS",
                MEMORY_RECENCY_HALF_LIFE_DAYS_DEFAULT.toDouble(),
                MEMORY_RECENCY_HALF_LIFE_DAYS_MIN.toDouble(),
                MEMORY_RECENCY_HALF_LIFE_DAYS_MAX.toDouble(),
            )
            assertInRange(
                "MEMORY_SUMMARY_DEFAULT_LIMIT",
                MEMORY_SUMMARY_DEFAULT_LIMIT_DEFAULT.toDouble(),
                MEMORY_SUMMARY_LIMIT_MIN.toDouble(),
                MEMORY_SUMMARY_LIMIT_MAX.toDouble(),
            )
            assertInRange(
                "MEMORY_COMPACTION_AGE_DAYS",
                MEMORY_COMPACTION_AGE_DAYS_DEFAULT.toDouble(),
                MEMORY_COMPACTION_AGE_DAYS_MIN.toDouble(),
                MEMORY_COMPACTION_AGE_DAYS_MAX.toDouble(),
            )
            assertInRange(
                "MAX_MEMORY_CHUNKS",
                MAX_MEMORY_CHUNKS_DEFAULT.toDouble(),
                MAX_MEMORY_CHUNKS_MIN.toDouble(),
                MAX_MEMORY_CHUNKS_MAX.toDouble(),
            )
            assertInRange(
                "STRUCTURED_OUTPUT_MAX_REPAIRS",
                STRUCTURED_OUTPUT_MAX_REPAIRS_DEFAULT.toDouble(),
                STRUCTURED_OUTPUT_MAX_REPAIRS_MIN.toDouble(),
                STRUCTURED_OUTPUT_MAX_REPAIRS_MAX.toDouble(),
            )
            assertInRange(
                "CLOUD_RETRY_MAX_ATTEMPTS",
                CLOUD_RETRY_MAX_ATTEMPTS_DEFAULT.toDouble(),
                CLOUD_RETRY_MAX_ATTEMPTS_MIN.toDouble(),
                CLOUD_RETRY_MAX_ATTEMPTS_MAX.toDouble(),
            )
            assertInRange(
                "CLOUD_RETRY_BASE_DELAY_MS",
                CLOUD_RETRY_BASE_DELAY_MS_DEFAULT.toDouble(),
                CLOUD_RETRY_BASE_DELAY_MS_MIN.toDouble(),
                CLOUD_RETRY_BASE_DELAY_MS_MAX.toDouble(),
            )
            assertInRange(
                "TOOL_CALL_TIMEOUT_MS",
                TOOL_CALL_TIMEOUT_MS_DEFAULT.toDouble(),
                TOOL_CALL_TIMEOUT_MS_MIN.toDouble(),
                TOOL_CALL_TIMEOUT_MS_MAX.toDouble(),
            )
            assertInRange(
                "WORKSPACE_MAX_FILE_SIZE_BYTES",
                WORKSPACE_MAX_FILE_SIZE_BYTES_DEFAULT.toDouble(),
                WORKSPACE_MAX_FILE_SIZE_BYTES_MIN.toDouble(),
                WORKSPACE_MAX_FILE_SIZE_BYTES_MAX.toDouble(),
            )
            assertInRange(
                "WORKSPACE_MAX_TOTAL_BYTES",
                WORKSPACE_MAX_TOTAL_BYTES_DEFAULT.toDouble(),
                WORKSPACE_MAX_TOTAL_BYTES_MIN.toDouble(),
                WORKSPACE_MAX_TOTAL_BYTES_MAX.toDouble(),
            )
            assertInRange(
                "WORKSPACE_READ_TOKEN_BUDGET",
                WORKSPACE_READ_TOKEN_BUDGET_DEFAULT.toDouble(),
                WORKSPACE_READ_TOKEN_BUDGET_MIN.toDouble(),
                WORKSPACE_READ_TOKEN_BUDGET_MAX.toDouble(),
            )
            assertInRange(
                "HTTP_TOOL_MAX_RESPONSE_BYTES",
                HTTP_TOOL_MAX_RESPONSE_BYTES_DEFAULT.toDouble(),
                HTTP_TOOL_MAX_RESPONSE_BYTES_MIN.toDouble(),
                HTTP_TOOL_MAX_RESPONSE_BYTES_MAX.toDouble(),
            )
        }
    }

    /** Asserts `min <= default <= max`, naming the setting in the failure message. */
    private fun assertInRange(name: String, default: Double, min: Double, max: Double) {
        assertTrue("$name default ($default) must be >= its minimum ($min)", default >= min)
        assertTrue("$name default ($default) must be <= its maximum ($max)", default <= max)
    }
}
