package app.knotwork.android.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.knotwork.android.data.local.crypto.FakeAeadCipher
import app.knotwork.android.data.local.crypto.InMemorySharedPreferences
import app.knotwork.android.data.local.crypto.KeystoreBackedPrefsStore
import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.McpAuth
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.McpTransport
import app.knotwork.android.domain.models.ToolApprovalPolicy
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.models.UpdateMcpServerResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * Unit tests for [SettingsManager].
 */
class SettingsManagerTest {

    private val dataStore = mockk<DataStore<Preferences>>()

    /**
     * Fakes backing the Keystore-backed secrets store (HuggingFace token):
     * an in-memory prefs file plus a deterministic AEAD cipher, wired
     * through a relaxed context mock shared by every construction below.
     */
    private val securePrefs = InMemorySharedPreferences()
    private val cipher = FakeAeadCipher()
    private val context = mockk<Context>(relaxed = true) {
        every { getSharedPreferences("secure_settings_secrets", Context.MODE_PRIVATE) } returns securePrefs
    }

    /**
     * The injected secret store under test, backed by the in-memory secure prefs
     * + deterministic cipher above — the production [KeystoreBackedPrefsStore]
     * with its Keystore/IO replaced so secrets actually round-trip in unit tests.
     */
    private val secretStore = KeystoreBackedPrefsStore(
        context = context,
        prefsName = "secure_settings_secrets",
        keyAlias = "knotwork.settings_secrets",
        cipher = cipher,
    )

    /**
     * Backs the `updateMcpServer` write-path integration tests below with a
     * **real** file-backed `PreferenceDataStore` so the assertions can
     * round-trip through `dataStore.edit { … }` and observe the persisted
     * state. The existing read-only tests (above) keep their lighter mock-
     * based pattern; mocking the `edit { … }` extension is hairy in mockk
     * and a temp-file DataStore is the most faithful integration target.
     */
    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    // private val settingsManager = SettingsManager(dataStore, secretStore)
    private val isFirstLaunchKey = booleanPreferencesKey("is_first_launch")
    private val temperatureKey = androidx.datastore.preferences.core.floatPreferencesKey("temperature")
    private val topKKey = androidx.datastore.preferences.core.intPreferencesKey("top_k")
    private val topPKey = androidx.datastore.preferences.core.floatPreferencesKey("top_p")
    private val requiresUserConfirmationKey = booleanPreferencesKey("requires_user_confirmation")
    private val toolApprovalPolicyKey = stringPreferencesKey("tool_approval_policy")
    private val lastReembedProviderIdKey = stringPreferencesKey("last_reembed_provider_id")
    private val pipelineMaxStepsKey = androidx.datastore.preferences.core.intPreferencesKey("pipeline_max_steps")
    private val pipelineMaxStepsBackgroundKey =
        androidx.datastore.preferences.core.intPreferencesKey("pipeline_max_steps_background")
    private val runMaxTokensKey = androidx.datastore.preferences.core.intPreferencesKey("run_max_tokens")
    private val runMaxTokensBackgroundKey =
        androidx.datastore.preferences.core.intPreferencesKey("run_max_tokens_background")
    private val pipelineMaxNestingDepthKey =
        androidx.datastore.preferences.core.intPreferencesKey("pipeline_max_nesting_depth")
    private val audioMaxDurationSecKey =
        androidx.datastore.preferences.core.intPreferencesKey("audio_max_duration_sec")
    private val cloudRetryMaxAttemptsKey =
        androidx.datastore.preferences.core.intPreferencesKey("cloud_retry_max_attempts")
    private val cloudRetryBaseDelayMsKey =
        androidx.datastore.preferences.core.longPreferencesKey("cloud_retry_base_delay_ms")
    private val structuredOutputMaxRepairsKey =
        androidx.datastore.preferences.core.intPreferencesKey("structured_output_max_repairs")
    private val resumeMaxAgeHoursKey = androidx.datastore.preferences.core.intPreferencesKey("resume_max_age_hours")
    private val traceRetentionRunsPerSessionKey =
        androidx.datastore.preferences.core.intPreferencesKey("trace_retention_runs_per_session")
    private val traceRetentionMaxAgeDaysKey =
        androidx.datastore.preferences.core.intPreferencesKey("trace_retention_max_age_days")
    private val crashReportingEnabledKey = booleanPreferencesKey("crash_reporting_enabled")
    private val toolRiskOverridesKey = stringPreferencesKey("app_function_risk_overrides")
    private val hasCompletedOnboardingKey = booleanPreferencesKey("has_completed_onboarding")
    private val activeEmbeddingProviderIdKey = stringPreferencesKey("active_embedding_provider_id")
    private val memorySearchTopKKey = androidx.datastore.preferences.core.intPreferencesKey("memory_search_top_k")
    private val memorySearchThresholdKey =
        androidx.datastore.preferences.core.floatPreferencesKey("memory_search_threshold")
    private val memoryCompactionEnabledKey = booleanPreferencesKey("memory_compaction_enabled")
    private val verboseMemoryLoggingEnabledKey = booleanPreferencesKey("verbose_memory_logging_enabled")
    private val memoryCompactionAgeDaysKey =
        androidx.datastore.preferences.core.intPreferencesKey("memory_compaction_age_days")
    private val maxMemoryChunksKey = androidx.datastore.preferences.core.intPreferencesKey("max_memory_chunks")
    private val allowedHttpDomainsKey = stringPreferencesKey("allowed_http_domains")
    private val httpToolMaxResponseBytesKey =
        androidx.datastore.preferences.core.longPreferencesKey("http_tool_max_response_bytes")

    @Test
    fun `isFirstLaunch returns true by default`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[isFirstLaunchKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.isFirstLaunch.first()
        assertTrue(result)
    }

    @Test
    fun `hasCompletedOnboarding returns false by default`() = runTest {
        // The flag must default to `false` so the onboarding gate trips on
        // fresh installs — the canonical "user has not seen onboarding yet"
        // signal. Confusion with `isFirstLaunch` (which defaults to `true`
        // and is cleared by `InitializeAppUseCase`) is exactly what this
        // separate flag exists to prevent.
        val prefs = mockk<Preferences>()
        every { prefs[hasCompletedOnboardingKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.hasCompletedOnboarding.first()
        org.junit.Assert.assertFalse(result)
    }

    @Test
    fun `temperature returns default value`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[temperatureKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.temperature.first()
        assertEquals(SettingsDefaults.TEMPERATURE_DEFAULT, result)
    }

    @Test
    fun `topK returns default value`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[topKKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.topK.first()
        assertEquals(SettingsDefaults.TOP_K_DEFAULT, result)
    }

    @Test
    fun `topP returns default value`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[topPKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.topP.first()
        assertEquals(SettingsDefaults.TOP_P_DEFAULT, result)
    }

    @Test
    fun `audioMaxDurationSec returns default value`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[audioMaxDurationSecKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.audioMaxDurationSec.first()
        assertEquals(SettingsDefaults.AUDIO_MAX_DURATION_SEC_DEFAULT, result)
    }

    @Test
    fun `setAudioMaxDurationSec persists and is read back`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            manager.setAudioMaxDurationSec(45)
            assertEquals(45, manager.audioMaxDurationSec.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `given only the legacy approval flag when the policy is read then it maps onto a policy`() = runTest {
        // The legacy boolean is read by the policy migration and nothing else;
        // this is its whole contract now.
        val expected = mapOf(
            false to ToolApprovalPolicy.NeverPrompt,
            true to ToolApprovalPolicy.SensitiveOrDestructive,
            null to ToolApprovalPolicy.DEFAULT,
        )
        for ((legacy, policy) in expected) {
            val prefs = mockk<Preferences>()
            every { prefs[toolApprovalPolicyKey] } returns null
            every { prefs[requiresUserConfirmationKey] } returns legacy
            every { dataStore.data } returns flowOf(prefs)

            assertEquals("legacy=$legacy", policy, SettingsManager(dataStore, secretStore).toolApprovalPolicy.first())
        }
    }

    @Test
    fun `given a stored policy and a legacy flag when the policy is read then the stored policy wins`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[toolApprovalPolicyKey] } returns ToolApprovalPolicy.AllCalls.key
        every { prefs[requiresUserConfirmationKey] } returns false
        every { dataStore.data } returns flowOf(prefs)

        assertEquals(ToolApprovalPolicy.AllCalls, SettingsManager(dataStore, secretStore).toolApprovalPolicy.first())
    }

    @Test
    fun `localModelBackendPreference distinguishes never-chosen from an explicit CPU choice`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            // Nothing stored: the folded reader still answers CPU (every
            // inference call site needs a backend), but the raw preference must
            // report "never chosen" so the onboarding probe may pick a default.
            assertNull(manager.localModelBackendPreference.first())
            assertEquals(LocalBackend.CPU.key, manager.localModelBackend.first())

            manager.setLocalModelBackend(LocalBackend.CPU.key)

            // Same folded value, different meaning: the choice has been made.
            assertEquals(LocalBackend.CPU.key, manager.localModelBackendPreference.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `lastReembedProviderId returns null by default`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[lastReembedProviderIdKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        assertNull(settingsManager.lastReembedProviderId.first())
    }

    @Test
    fun `setLastReembedProviderId persists and is read back`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            manager.setLastReembedProviderId("ollama")
            assertEquals("ollama", manager.lastReembedProviderId.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `setActiveEmbeddingProviderId captures the previous provider as baseline on first switch only`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            // Given — a fresh install: the default provider is active and no
            // baseline has ever been captured.
            assertNull(manager.lastReembedProviderId.first())

            // When — the user switches providers for the first time.
            manager.setActiveEmbeddingProviderId("ollama")

            // Then — the provider the stored vectors were created with (the
            // default) is captured as the baseline.
            assertEquals("ollama", manager.activeEmbeddingProviderId.first())
            assertEquals(
                SettingsDefaults.ACTIVE_EMBEDDING_PROVIDER_ID_DEFAULT,
                manager.lastReembedProviderId.first(),
            )

            // When — a second switch happens without a re-embed in between.
            manager.setActiveEmbeddingProviderId("openai_3_small")

            // Then — the baseline is NOT overwritten (the vectors are still in
            // the original provider's space).
            assertEquals(
                SettingsDefaults.ACTIVE_EMBEDDING_PROVIDER_ID_DEFAULT,
                manager.lastReembedProviderId.first(),
            )

            // When — the user switches back to the baseline provider.
            manager.setActiveEmbeddingProviderId(SettingsDefaults.ACTIVE_EMBEDDING_PROVIDER_ID_DEFAULT)

            // Then — active equals baseline again, so the mismatch banner
            // condition no longer holds.
            assertEquals(manager.lastReembedProviderId.first(), manager.activeEmbeddingProviderId.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `memorySearchTopK returns default value`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[memorySearchTopKKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.memorySearchTopK.first()
        assertEquals(SettingsDefaults.MEMORY_SEARCH_TOP_K_DEFAULT, result)
    }

    @Test
    fun `memorySearchThreshold returns default value`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[memorySearchThresholdKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.memorySearchThreshold.first()
        assertEquals(SettingsDefaults.MEMORY_SEARCH_THRESHOLD_DEFAULT, result)
    }

    @Test
    fun `setMemorySearchTopK persists and is read back`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            manager.setMemorySearchTopK(12)
            assertEquals(12, manager.memorySearchTopK.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `setMemorySearchThreshold persists and is read back`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            manager.setMemorySearchThreshold(0.72f)
            assertEquals(0.72f, manager.memorySearchThreshold.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `allowedHttpDomains returns empty list by default`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[allowedHttpDomainsKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        assertEquals(emptyList<String>(), settingsManager.allowedHttpDomains.first())
    }

    @Test
    fun `allowedHttpDomains decodes newline-delimited entries preserving order and dropping blanks`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[allowedHttpDomainsKey] } returns "api.example.com\n\nb.test\n"
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        assertEquals(listOf("api.example.com", "b.test"), settingsManager.allowedHttpDomains.first())
    }

    @Test
    fun `setAllowedHttpDomains persists order and is read back`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            manager.setAllowedHttpDomains(listOf("z.example.com", "a.example.com"))
            assertEquals(listOf("z.example.com", "a.example.com"), manager.allowedHttpDomains.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `setAllowedHttpDomains trims and drops blank entries before persisting`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            manager.setAllowedHttpDomains(listOf("  a.example.com  ", "", "   "))
            assertEquals(listOf("a.example.com"), manager.allowedHttpDomains.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `httpToolMaxResponseBytes returns default value`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[httpToolMaxResponseBytesKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        assertEquals(
            SettingsDefaults.HTTP_TOOL_MAX_RESPONSE_BYTES_DEFAULT,
            settingsManager.httpToolMaxResponseBytes.first(),
        )
    }

    @Test
    fun `setHttpToolMaxResponseBytes persists and is read back`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            // 2 KB before this setting had bounds; now below the 64 KB floor,
            // which the coercion test covers separately.
            manager.setHttpToolMaxResponseBytes(2L * 1024 * 1024)
            assertEquals(2L * 1024 * 1024, manager.httpToolMaxResponseBytes.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `memoryCompactionEnabled returns default value`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[memoryCompactionEnabledKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.memoryCompactionEnabled.first()
        assertEquals(SettingsDefaults.MEMORY_COMPACTION_ENABLED_DEFAULT, result)
    }

    @Test
    fun `setMemoryCompactionEnabled persists and is read back`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            manager.setMemoryCompactionEnabled(false)
            assertEquals(false, manager.memoryCompactionEnabled.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `verboseMemoryLoggingEnabled returns default value`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[verboseMemoryLoggingEnabledKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.verboseMemoryLoggingEnabled.first()
        assertEquals(SettingsDefaults.VERBOSE_MEMORY_LOGGING_ENABLED_DEFAULT, result)
    }

    @Test
    fun `setVerboseMemoryLoggingEnabled persists and is read back`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            manager.setVerboseMemoryLoggingEnabled(true)
            assertEquals(true, manager.verboseMemoryLoggingEnabled.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `memoryCompactionAgeDays returns default value`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[memoryCompactionAgeDaysKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.memoryCompactionAgeDays.first()
        assertEquals(SettingsDefaults.MEMORY_COMPACTION_AGE_DAYS_DEFAULT, result)
    }

    @Test
    fun `setMemoryCompactionAgeDays persists and is read back`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            manager.setMemoryCompactionAgeDays(45)
            assertEquals(45, manager.memoryCompactionAgeDays.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `maxMemoryChunks returns default value`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[maxMemoryChunksKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.maxMemoryChunks.first()
        assertEquals(SettingsDefaults.MAX_MEMORY_CHUNKS_DEFAULT, result)
    }

    @Test
    fun `setMaxMemoryChunks persists and is read back`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            manager.setMaxMemoryChunks(8000)
            assertEquals(8000, manager.maxMemoryChunks.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `isFirstLaunch returns stored value`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[isFirstLaunchKey] } returns false
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.isFirstLaunch.first()
        assertEquals(false, result)
    }

    @Test
    fun `isFirstLaunch handles IOException and returns default`() = runTest {
        every { dataStore.data } returns flow { throw IOException("Test") }

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.isFirstLaunch.first()
        assertTrue(result)
    }

    @Test
    fun `pipelineMaxSteps returns default value of 15`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[pipelineMaxStepsKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.pipelineMaxSteps.first()
        assertEquals(15, result)
    }

    @Test
    fun `pipelineMaxSteps returns stored value`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[pipelineMaxStepsKey] } returns 30
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.pipelineMaxSteps.first()
        assertEquals(30, result)
    }

    @Test
    fun `pipelineMaxStepsBackground falls back to the configured interactive cap`() = runTest {
        // The upgrade case, and the reason this is not a plain constant default:
        // one setting used to govern every origin, so a user who had widened the
        // cap to 40 must not find their triggers quietly reset to 15.
        val prefs = mockk<Preferences>()
        every { prefs[pipelineMaxStepsBackgroundKey] } returns null
        every { prefs[pipelineMaxStepsKey] } returns 40
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        assertEquals(40, settingsManager.pipelineMaxStepsBackground.first())
    }

    @Test
    fun `pipelineMaxStepsBackground prefers its own stored value over the interactive one`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[pipelineMaxStepsBackgroundKey] } returns 8
        every { prefs[pipelineMaxStepsKey] } returns 40
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        assertEquals(8, settingsManager.pipelineMaxStepsBackground.first())
    }

    @Test
    fun `pipelineMaxStepsBackground falls back to the constant only when neither is set`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[pipelineMaxStepsBackgroundKey] } returns null
        every { prefs[pipelineMaxStepsKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        assertEquals(
            SettingsDefaults.PIPELINE_MAX_STEPS_BACKGROUND_DEFAULT,
            settingsManager.pipelineMaxStepsBackground.first(),
        )
    }

    @Test
    fun `runMaxTokens returns its default and then its stored value`() = runTest {
        val unset = mockk<Preferences>()
        every { unset[runMaxTokensKey] } returns null
        every { dataStore.data } returns flowOf(unset)
        assertEquals(
            SettingsDefaults.RUN_MAX_TOKENS_DEFAULT,
            SettingsManager(dataStore, secretStore).runMaxTokens.first(),
        )

        val stored = mockk<Preferences>()
        every { stored[runMaxTokensKey] } returns 250_000
        every { dataStore.data } returns flowOf(stored)
        assertEquals(250_000, SettingsManager(dataStore, secretStore).runMaxTokens.first())
    }

    @Test
    fun `runMaxTokensBackground returns its own, tighter default`() = runTest {
        // The background number is a real default rather than an inheritance,
        // because the token axis is new — there is no older setting a user could
        // have configured for it.
        val prefs = mockk<Preferences>()
        every { prefs[runMaxTokensBackgroundKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        assertEquals(
            SettingsDefaults.RUN_MAX_TOKENS_BACKGROUND_DEFAULT,
            settingsManager.runMaxTokensBackground.first(),
        )
        assertTrue(
            "the unattended ceiling must be the tighter one",
            SettingsDefaults.RUN_MAX_TOKENS_BACKGROUND_DEFAULT < SettingsDefaults.RUN_MAX_TOKENS_DEFAULT,
        )
    }

    @Test
    fun `pipelineMaxNestingDepth returns default value of 3`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[pipelineMaxNestingDepthKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        assertEquals(3, settingsManager.pipelineMaxNestingDepth.first())
    }

    @Test
    fun `pipelineMaxNestingDepth returns stored value`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[pipelineMaxNestingDepthKey] } returns 5
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        assertEquals(5, settingsManager.pipelineMaxNestingDepth.first())
    }

    @Test
    fun `setPipelineMaxNestingDepth coerces into the sanctioned 1-5 range`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            manager.setPipelineMaxNestingDepth(0)
            assertEquals(1, manager.pipelineMaxNestingDepth.first())

            manager.setPipelineMaxNestingDepth(99)
            assertEquals(5, manager.pipelineMaxNestingDepth.first())

            manager.setPipelineMaxNestingDepth(4)
            assertEquals(4, manager.pipelineMaxNestingDepth.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `every tool ceiling coerces into its sanctioned range`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            // These five gained sliders before they gained bounds. A zero
            // tool-call deadline would time out every MCP round-trip and a zero
            // workspace ceiling would refuse every write, so the setter clamps
            // rather than trusting whatever the caller hands it.
            manager.setToolCallTimeoutMs(0L)
            assertEquals(SettingsDefaults.TOOL_CALL_TIMEOUT_MS_MIN, manager.toolCallTimeoutMs.first())
            manager.setToolCallTimeoutMs(Long.MAX_VALUE)
            assertEquals(SettingsDefaults.TOOL_CALL_TIMEOUT_MS_MAX, manager.toolCallTimeoutMs.first())

            manager.setWorkspaceMaxFileSizeBytes(0L)
            assertEquals(
                SettingsDefaults.WORKSPACE_MAX_FILE_SIZE_BYTES_MIN,
                manager.workspaceMaxFileSizeBytes.first(),
            )
            manager.setWorkspaceMaxTotalBytes(0L)
            assertEquals(SettingsDefaults.WORKSPACE_MAX_TOTAL_BYTES_MIN, manager.workspaceMaxTotalBytes.first())
            manager.setWorkspaceReadTokenBudget(0)
            assertEquals(
                SettingsDefaults.WORKSPACE_READ_TOKEN_BUDGET_MIN,
                manager.workspaceReadTokenBudget.first(),
            )
            manager.setHttpToolMaxResponseBytes(Long.MAX_VALUE)
            assertEquals(
                SettingsDefaults.HTTP_TOOL_MAX_RESPONSE_BYTES_MAX,
                manager.httpToolMaxResponseBytes.first(),
            )

            // An in-range value is stored verbatim — the clamp must not round.
            manager.setToolCallTimeoutMs(30_000L)
            assertEquals(30_000L, manager.toolCallTimeoutMs.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `structuredOutputMaxRepairs returns default value of 2`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[structuredOutputMaxRepairsKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        assertEquals(2, settingsManager.structuredOutputMaxRepairs.first())
    }

    @Test
    fun `structuredOutputMaxRepairs returns stored value`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[structuredOutputMaxRepairsKey] } returns 4
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        assertEquals(4, settingsManager.structuredOutputMaxRepairs.first())
    }

    @Test
    fun `setStructuredOutputMaxRepairs coerces into the sanctioned 0-4 range`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            manager.setStructuredOutputMaxRepairs(-1)
            assertEquals(0, manager.structuredOutputMaxRepairs.first())

            manager.setStructuredOutputMaxRepairs(99)
            assertEquals(4, manager.structuredOutputMaxRepairs.first())

            manager.setStructuredOutputMaxRepairs(3)
            assertEquals(3, manager.structuredOutputMaxRepairs.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cloudRetryMaxAttempts returns default of 3 then stored value`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[cloudRetryMaxAttemptsKey] } returns null andThen 5
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        assertEquals(3, settingsManager.cloudRetryMaxAttempts.first())
        assertEquals(5, settingsManager.cloudRetryMaxAttempts.first())
    }

    @Test
    fun `setCloudRetryMaxAttempts coerces into the sanctioned 1-5 range`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            manager.setCloudRetryMaxAttempts(0)
            assertEquals(1, manager.cloudRetryMaxAttempts.first())

            manager.setCloudRetryMaxAttempts(99)
            assertEquals(5, manager.cloudRetryMaxAttempts.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cloudRetryBaseDelayMs returns default of 1000 then stored value`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[cloudRetryBaseDelayMsKey] } returns null andThen 2500L
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        assertEquals(1000L, settingsManager.cloudRetryBaseDelayMs.first())
        assertEquals(2500L, settingsManager.cloudRetryBaseDelayMs.first())
    }

    @Test
    fun `setCloudRetryBaseDelayMs coerces into the sanctioned 100-10000 range`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            manager.setCloudRetryBaseDelayMs(10)
            assertEquals(100L, manager.cloudRetryBaseDelayMs.first())

            manager.setCloudRetryBaseDelayMs(999_999)
            assertEquals(10_000L, manager.cloudRetryBaseDelayMs.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `resumeMaxAgeHours returns default value of 48`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[resumeMaxAgeHoursKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        assertEquals(48, settingsManager.resumeMaxAgeHours.first())
    }

    @Test
    fun `resumeMaxAgeHours returns stored value`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[resumeMaxAgeHoursKey] } returns 72
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        assertEquals(72, settingsManager.resumeMaxAgeHours.first())
    }

    @Test
    fun `setResumeMaxAgeHours coerces into the sanctioned 1-168 range`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            manager.setResumeMaxAgeHours(0)
            assertEquals(1, manager.resumeMaxAgeHours.first())

            manager.setResumeMaxAgeHours(9_000)
            assertEquals(168, manager.resumeMaxAgeHours.first())

            manager.setResumeMaxAgeHours(24)
            assertEquals(24, manager.resumeMaxAgeHours.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `traceRetentionRunsPerSession returns default value of 20`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[traceRetentionRunsPerSessionKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        assertEquals(20, settingsManager.traceRetentionRunsPerSession.first())
    }

    @Test
    fun `traceRetentionMaxAgeDays returns default value of 30`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[traceRetentionMaxAgeDaysKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        assertEquals(30, settingsManager.traceRetentionMaxAgeDays.first())
    }

    @Test
    fun `setTraceRetentionRunsPerSession coerces into the sanctioned 5-100 range`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            manager.setTraceRetentionRunsPerSession(1)
            assertEquals(5, manager.traceRetentionRunsPerSession.first())

            manager.setTraceRetentionRunsPerSession(9_000)
            assertEquals(100, manager.traceRetentionRunsPerSession.first())

            manager.setTraceRetentionRunsPerSession(40)
            assertEquals(40, manager.traceRetentionRunsPerSession.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `setTraceRetentionMaxAgeDays coerces into the sanctioned 7-180 range`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            manager.setTraceRetentionMaxAgeDays(1)
            assertEquals(7, manager.traceRetentionMaxAgeDays.first())

            manager.setTraceRetentionMaxAgeDays(9_000)
            assertEquals(180, manager.traceRetentionMaxAgeDays.first())

            manager.setTraceRetentionMaxAgeDays(60)
            assertEquals(60, manager.traceRetentionMaxAgeDays.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `crashReportingEnabled returns false by default`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[crashReportingEnabledKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.crashReportingEnabled.first()
        assertEquals(false, result)
    }

    @Test
    fun `crashReportingEnabled returns stored true value`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[crashReportingEnabledKey] } returns true
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.crashReportingEnabled.first()
        assertTrue(result)
    }

    @Test
    fun `crashReportingEnabled handles IOException and falls back to false`() = runTest {
        every { dataStore.data } returns flow { throw IOException("Test") }

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.crashReportingEnabled.first()
        assertEquals(false, result)
    }

    @Test
    fun `toolRiskOverrides returns empty map when nothing is stored`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[toolRiskOverridesKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.toolRiskOverrides.first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `toolRiskOverrides parses stored JSON map into typed risks`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[toolRiskOverridesKey] } returns
            "{\"echo\":\"READ_ONLY\",\"send_email\":\"DESTRUCTIVE\"}"
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.toolRiskOverrides.first()

        assertEquals(2, result.size)
        assertEquals(ToolRisk.READ_ONLY, result["echo"])
        assertEquals(ToolRisk.DESTRUCTIVE, result["send_email"])
    }

    @Test
    fun `toolRiskOverrides drops entries with unknown risk values`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[toolRiskOverridesKey] } returns
            "{\"echo\":\"READ_ONLY\",\"bogus\":\"NOT_A_REAL_RISK\"}"
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.toolRiskOverrides.first()

        assertEquals(1, result.size)
        assertEquals(ToolRisk.READ_ONLY, result["echo"])
        assertTrue(!result.containsKey("bogus"))
    }

    @Test
    fun `toolRiskOverrides returns empty map on malformed JSON`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[toolRiskOverridesKey] } returns "this is not json"
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.toolRiskOverrides.first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `toolRiskOverrides handles IOException and falls back to empty map`() = runTest {
        every { dataStore.data } returns flow { throw IOException("Test") }

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.toolRiskOverrides.first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `pipelineMaxSteps coerceIn is enforced in stored range`() = runTest {
        // Coercion is verified via ViewModel; here we just confirm the key name and default
        val prefs = mockk<Preferences>()
        every { prefs[pipelineMaxStepsKey] } returns 50
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.pipelineMaxSteps.first()
        assertEquals(50, result)
    }

    @Test
    fun `mcpServers migrates legacy MCP_SERVER_URLS stringSet to default configs`() = runTest {
        // The MCP persistence expanded from a stringSet of URLs to a
        // JSON-encoded List<McpServerConfig>. Existing installs hold the old key — the
        // manager must surface them as default configs (no headers, SSE transport)
        // until the first write replaces the storage shape.
        val legacyKey = stringSetPreferencesKey("mcp_server_urls")
        val newJsonKey = stringPreferencesKey("mcp_servers_json")
        val prefs = mockk<Preferences>()
        every { prefs[legacyKey] } returns setOf("https://legacy.example/mcp")
        every { prefs[newJsonKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val result = SettingsManager(dataStore, secretStore).mcpServers.first()

        assertEquals(1, result.size)
        assertEquals("https://legacy.example/mcp", result[0].url)
        assertEquals(null, result[0].name)
        assertEquals(McpTransport.SSE, result[0].transport)
        assertTrue(result[0].headers.isEmpty())
    }

    @Test
    fun `mcpServers decodes JSON-encoded list with headers and transport`() = runTest {
        val legacyKey = stringSetPreferencesKey("mcp_server_urls")
        val newJsonKey = stringPreferencesKey("mcp_servers_json")
        val prefs = mockk<Preferences>()
        every { prefs[legacyKey] } returns null
        every {
            prefs[newJsonKey]
        } returns """
            [
              {
                "url":"https://hf.example/mcp",
                "name":"HuggingFace",
                "transport":"streamable_http",
                "headers":{"Authorization":"Bearer secret"}
              }
            ]
        """.trimIndent()
        every { dataStore.data } returns flowOf(prefs)

        val result = SettingsManager(dataStore, secretStore).mcpServers.first()

        assertEquals(1, result.size)
        assertEquals("HuggingFace", result[0].name)
        assertEquals(McpTransport.STREAMABLE_HTTP, result[0].transport)
        assertEquals("Bearer secret", result[0].headers["Authorization"])
    }

    @Test
    fun `mcpServers decodes typed Bearer auth payload`() = runTest {
        val newJsonKey = stringPreferencesKey("mcp_servers_json")
        val prefs = mockk<Preferences>()
        every { prefs[stringSetPreferencesKey("mcp_server_urls")] } returns null
        every {
            prefs[newJsonKey]
        } returns """
            [
              {
                "url":"https://hf.example/mcp",
                "auth":{"type":"bearer","token":"abc"}
              }
            ]
        """.trimIndent()
        every { dataStore.data } returns flowOf(prefs)
        // The auth-migration pass rewrites the JSON (strips inline auth); the mock
        // DataStore just needs to accept the edit. Decode still reads the inline
        // auth from the original (un-mutated) mock prefs — the migration-window
        // back-compat path.
        coEvery { dataStore.updateData(any()) } returns prefs

        val result = SettingsManager(dataStore, secretStore).mcpServers.first()

        assertEquals(McpAuth.Bearer(token = "abc"), result.single().auth)
    }

    @Test
    fun `mcpServers decodes typed ApiKey auth payload`() = runTest {
        val newJsonKey = stringPreferencesKey("mcp_servers_json")
        val prefs = mockk<Preferences>()
        every { prefs[stringSetPreferencesKey("mcp_server_urls")] } returns null
        every {
            prefs[newJsonKey]
        } returns """
            [
              {
                "url":"https://api.example/mcp",
                "auth":{"type":"apiKey","headerName":"X-API-Key","value":"v1"}
              }
            ]
        """.trimIndent()
        every { dataStore.data } returns flowOf(prefs)
        coEvery { dataStore.updateData(any()) } returns prefs

        val result = SettingsManager(dataStore, secretStore).mcpServers.first()

        assertEquals(McpAuth.ApiKey(headerName = "X-API-Key", value = "v1"), result.single().auth)
    }

    // ───────────────────────────────────────────────────────────────────
    // HuggingFace token: encrypted storage + legacy-DataStore migration
    // (real PreferenceDataStore, same pattern as the MCP tests below).
    // ───────────────────────────────────────────────────────────────────

    private val huggingFaceTokenKey = stringPreferencesKey("hugging_face_token")

    /** Like [freshManagerWithRealDataStore] but also exposes the backing DataStore. */
    @Test
    fun `given a raised interactive cap when reset then the background ceiling inherits again`() = runTest {
        val (manager, _, scope) = freshManagerWithExposedDataStore()
        try {
            // The user widens both, deliberately detaching the background one.
            manager.setPipelineMaxSteps(40)
            manager.setPipelineMaxStepsBackground(30)
            assertTrue(manager.pipelineMaxStepsBackgroundIsSet.first())

            manager.resetToRecommendedDefaults()

            // The inheritance is the default state, so a reset has to restore
            // it. Writing the constant instead would leave the user holding a
            // deliberate-looking decision they never made — and from then on
            // raising the interactive cap would silently stop moving their
            // triggers.
            assertFalse(
                "a reset must not leave the background ceiling independently set",
                manager.pipelineMaxStepsBackgroundIsSet.first(),
            )
            manager.setPipelineMaxSteps(40)
            assertEquals(
                "after a reset the background ceiling follows the interactive one again",
                40,
                manager.pipelineMaxStepsBackground.first(),
            )
        } finally {
            scope.cancel()
        }
    }

    private fun freshManagerWithExposedDataStore(): Triple<SettingsManager, DataStore<Preferences>, CoroutineScope> {
        val file = tempFolder.newFile("settings-manager-hf-${System.nanoTime()}.preferences_pb")
        file.delete()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val ds = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        return Triple(SettingsManager(ds, secretStore), ds, scope)
    }

    @Test
    fun `huggingFace token round-trips through the encrypted store across instances`() = runTest {
        val (manager, ds, scope) = freshManagerWithExposedDataStore()
        try {
            manager.setHuggingFaceAuthToken("hf_secret_token")

            val fresh = SettingsManager(ds, secretStore)
            assertEquals("hf_secret_token", fresh.huggingFaceAuthToken.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `huggingFace token is not stored as plaintext`() = runTest {
        val (manager, _, scope) = freshManagerWithExposedDataStore()
        try {
            manager.setHuggingFaceAuthToken("hf_secret_token")

            val raw = securePrefs.values["hugging_face_token"] as String
            assertTrue(!raw.contains("hf_secret_token"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `legacy DataStore token migrates to the encrypted store on first read`() = runTest {
        val (manager, ds, scope) = freshManagerWithExposedDataStore()
        try {
            ds.edit { it[huggingFaceTokenKey] = "hf_legacy_token" }

            val token = manager.huggingFaceAuthToken.first()

            assertEquals("hf_legacy_token", token)
            // The plaintext copy is gone from DataStore…
            assertNull(ds.data.first()[huggingFaceTokenKey])
            // …and the encrypted store now holds it (not in plaintext).
            val raw = securePrefs.values["hugging_face_token"] as String
            assertTrue(!raw.contains("hf_legacy_token"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `migration does not overwrite an already-encrypted token`() = runTest {
        val (manager, ds, scope) = freshManagerWithExposedDataStore()
        try {
            manager.setHuggingFaceAuthToken("hf_current_token")
            // Simulate a stale plaintext leftover from a crashed earlier migration.
            ds.edit { it[huggingFaceTokenKey] = "hf_stale_legacy" }
            val fresh = SettingsManager(ds, secretStore)

            val token = fresh.huggingFaceAuthToken.first()

            assertEquals("hf_current_token", token)
            assertNull(ds.data.first()[huggingFaceTokenKey])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `setting token to null removes the encrypted entry`() = runTest {
        val (manager, _, scope) = freshManagerWithExposedDataStore()
        try {
            manager.setHuggingFaceAuthToken("hf_secret_token")

            manager.setHuggingFaceAuthToken(null)

            assertNull(manager.huggingFaceAuthToken.first())
            assertTrue(!securePrefs.values.containsKey("hugging_face_token"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `undecryptable stored token is treated as unset and dropped`() = runTest {
        val (manager, ds, scope) = freshManagerWithExposedDataStore()
        try {
            manager.setHuggingFaceAuthToken("hf_secret_token")
            cipher.failDecrypt = true
            val fresh = SettingsManager(ds, secretStore)

            // A lost Keystore key must surface as "no token configured":
            // the token is user re-enterable, same policy as API keys.
            assertNull(fresh.huggingFaceAuthToken.first())
            assertTrue(!securePrefs.values.containsKey("hugging_face_token"))
        } finally {
            scope.cancel()
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // updateMcpServer integration tests (real PreferenceDataStore).
    //
    // The collision-detection logic itself is pure-tested in
    // McpServerCollisionCheckTest; the cases below verify that the
    // SettingsManager method actually dispatches to it AND skips the
    // dataStore.edit { … } write when a collision is detected — the part
    // that matters for "persists nothing" semantics.
    // ───────────────────────────────────────────────────────────────────

    private fun freshManagerWithRealDataStore(): Pair<SettingsManager, CoroutineScope> {
        val file = tempFolder.newFile("settings-manager-mcp-${System.nanoTime()}.preferences_pb")
        // Files created by JUnit's TemporaryFolder start as empty 0-byte files,
        // which DataStore would treat as a corrupt preferences blob and throw on
        // first read. Delete the file so DataStore can create it from scratch
        // on the first write.
        file.delete()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val ds = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        return SettingsManager(ds, secretStore) to scope
    }

    @Test
    fun `updateMcpServer returns UrlCollision when new url matches another existing row`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            manager.addMcpServer(McpServerConfig(url = "http://a", name = "Server A"))
            manager.addMcpServer(McpServerConfig(url = "http://b", name = "Server B"))

            val result = manager.updateMcpServer(
                originalUrl = "http://a",
                updated = McpServerConfig(url = "http://b", name = "About to overwrite B"),
            )

            // Typed result carries the colliding row's identity for the UI.
            assertTrue(
                "Expected UrlCollision, got $result",
                result is UpdateMcpServerResult.UrlCollision,
            )
            val collision = result as UpdateMcpServerResult.UrlCollision
            assertEquals("http://b", collision.collidingUrl)
            assertEquals("Server B", collision.collidingDisplayName)

            // Persistence MUST be untouched — A still has its original name,
            // B still has its original name, no duplicate row sneaked in.
            val onDisk = manager.mcpServers.first()
            assertEquals(2, onDisk.size)
            assertEquals("http://a", onDisk[0].url)
            assertEquals("Server A", onDisk[0].name)
            assertEquals("http://b", onDisk[1].url)
            assertEquals("Server B", onDisk[1].name)
            assertNotEquals("About to overwrite B", onDisk[1].name)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `updateMcpServer succeeds when new url matches its own current url (no-op edit)`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            manager.addMcpServer(McpServerConfig(url = "http://a", name = "Original"))

            // Same URL, renamed display label — the canonical "rename only" edit
            // path. Must not trip the collision guard.
            val result = manager.updateMcpServer(
                originalUrl = "http://a",
                updated = McpServerConfig(url = "http://a", name = "Renamed"),
            )

            assertEquals(UpdateMcpServerResult.Success, result)
            val onDisk = manager.mcpServers.first()
            assertEquals(1, onDisk.size)
            assertEquals("http://a", onDisk[0].url)
            assertEquals("Renamed", onDisk[0].name)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `updateMcpServer succeeds and persists when the new url is unique`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            manager.addMcpServer(McpServerConfig(url = "http://a", name = "Server A"))

            val result = manager.updateMcpServer(
                originalUrl = "http://a",
                updated = McpServerConfig(url = "http://c", name = "Server C"),
            )

            assertEquals(UpdateMcpServerResult.Success, result)
            val onDisk = manager.mcpServers.first()
            assertEquals(1, onDisk.size)
            assertEquals("http://c", onDisk[0].url)
            assertEquals("Server C", onDisk[0].name)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `addMcpServer stores auth in the encrypted store, not in plain DataStore JSON`() = runTest {
        val (manager, ds, scope) = freshManagerWithExposedDataStore()
        try {
            manager.addMcpServer(
                McpServerConfig(url = "https://mcp.example", name = "S", auth = McpAuth.Bearer(token = "tok_secret")),
            )

            // Read-back surfaces the auth…
            assertEquals(McpAuth.Bearer(token = "tok_secret"), manager.mcpServers.first().single().auth)
            // …but the plain DataStore JSON carries neither the token nor an auth object.
            val json = ds.data.first()[stringPreferencesKey("mcp_servers_json")] ?: ""
            assertTrue("JSON must not contain the secret: $json", !json.contains("tok_secret"))
            assertTrue("JSON must carry no auth object: $json", !json.contains("\"auth\""))
            // The encrypted store holds it, and not as plaintext.
            val raw = securePrefs.values[mcpAuthSecretKey("https://mcp.example")] as String
            assertTrue("encrypted entry must not be plaintext", !raw.contains("tok_secret"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `removeMcpServer clears the server's encrypted auth entry`() = runTest {
        val (manager, _, scope) = freshManagerWithExposedDataStore()
        try {
            manager.addMcpServer(
                McpServerConfig(url = "https://mcp.example", auth = McpAuth.ApiKey(headerName = "X", value = "v")),
            )
            assertTrue(securePrefs.values.containsKey(mcpAuthSecretKey("https://mcp.example")))

            manager.removeMcpServer("https://mcp.example")

            assertTrue(
                "secret must be cleared on remove",
                !securePrefs.values.containsKey(mcpAuthSecretKey("https://mcp.example")),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `updateMcpServer moves the auth secret when the url changes`() = runTest {
        val (manager, _, scope) = freshManagerWithExposedDataStore()
        try {
            manager.addMcpServer(
                McpServerConfig(url = "https://old.example", auth = McpAuth.Bearer(token = "tok")),
            )

            manager.updateMcpServer(
                originalUrl = "https://old.example",
                updated = McpServerConfig(url = "https://new.example", auth = McpAuth.Bearer(token = "tok")),
            )

            assertTrue(
                "old URL's secret must be removed",
                !securePrefs.values.containsKey(mcpAuthSecretKey("https://old.example")),
            )
            assertTrue(
                "new URL's secret must be present",
                securePrefs.values.containsKey(mcpAuthSecretKey("https://new.example")),
            )
            assertEquals(McpAuth.Bearer(token = "tok"), manager.mcpServers.first().single().auth)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `legacy inline MCP auth migrates to the encrypted store and is stripped from JSON`() = runTest {
        val (manager, ds, scope) = freshManagerWithExposedDataStore()
        try {
            val url = "https://legacy.example/mcp"
            ds.edit {
                it[stringPreferencesKey("mcp_servers_json")] =
                    """[{"url":"$url","transport":"sse","auth":{"type":"bearer","token":"legacy_tok"}}]"""
            }

            // First read triggers the migration and still surfaces the auth.
            assertEquals(McpAuth.Bearer(token = "legacy_tok"), manager.mcpServers.first().single().auth)

            // Inline auth is gone from the persisted JSON…
            val json = ds.data.first()[stringPreferencesKey("mcp_servers_json")] ?: ""
            assertTrue("inline auth must be stripped: $json", !json.contains("legacy_tok"))
            assertTrue("auth object must be stripped: $json", !json.contains("\"auth\""))
            // …and now lives encrypted in the secret store.
            val raw = securePrefs.values[mcpAuthSecretKey(url)] as String
            assertTrue("migrated entry must not be plaintext", !raw.contains("legacy_tok"))
        } finally {
            scope.cancel()
        }
    }

    /** Mirrors `SettingsManager.SecretKeys.mcpAuthKey` for asserting the encrypted-store entry name. */
    private fun mcpAuthSecretKey(url: String): String {
        val hex = java.security.MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "mcp_auth_$hex"
    }

    @Test
    fun `activeEmbeddingProviderId returns on-device default when nothing stored`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[activeEmbeddingProviderIdKey] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.activeEmbeddingProviderId.first()

        assertEquals(SettingsDefaults.ACTIVE_EMBEDDING_PROVIDER_ID_DEFAULT, result)
        assertEquals("use", result)
    }

    @Test
    fun `activeEmbeddingProviderId returns stored value`() = runTest {
        val prefs = mockk<Preferences>()
        every { prefs[activeEmbeddingProviderIdKey] } returns "openai_3_small"
        every { dataStore.data } returns flowOf(prefs)

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.activeEmbeddingProviderId.first()

        assertEquals("openai_3_small", result)
    }

    @Test
    fun `activeEmbeddingProviderId handles IOException and returns default`() = runTest {
        every { dataStore.data } returns flow { throw IOException("Test") }

        val settingsManager = SettingsManager(dataStore, secretStore)
        val result = settingsManager.activeEmbeddingProviderId.first()

        assertEquals(SettingsDefaults.ACTIVE_EMBEDDING_PROVIDER_ID_DEFAULT, result)
    }

    @Test
    fun `setActiveEmbeddingProviderId persists and round-trips through DataStore`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            // Default before any write.
            assertEquals("use", manager.activeEmbeddingProviderId.first())

            manager.setActiveEmbeddingProviderId("ollama")

            assertEquals("ollama", manager.activeEmbeddingProviderId.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `resetToRecommendedDefaults restores every tunable preference to its default`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            // Drive a representative spread of tunable preferences off their defaults.
            manager.setTemperature(0.1f)
            manager.setTopK(7)
            manager.setMaxContextLength(1234)
            manager.setPipelineMaxSteps(SettingsDefaults.PIPELINE_MAX_STEPS_MAX)
            manager.setPipelineMaxNestingDepth(SettingsDefaults.PIPELINE_MAX_NESTING_DEPTH_MAX)
            manager.setAudioMaxDurationSec(99)
            manager.setMaxMemoryChunks(9_999)
            manager.setMemorySummaryDefaultLimit(42)
            manager.setBlockDestructiveTools(true)
            manager.setBlockNetworkFromLocalModel(true)
            manager.setCrashReportingEnabled(true)
            manager.setVerboseMemoryLoggingEnabled(true)
            manager.setScheduledTaskNotificationsEnabled(false)
            manager.setChatHistoryCompressionEnabled(false)

            manager.resetToRecommendedDefaults()

            assertEquals(SettingsDefaults.TEMPERATURE_DEFAULT, manager.temperature.first())
            assertEquals(SettingsDefaults.TOP_K_DEFAULT, manager.topK.first())
            assertEquals(SettingsDefaults.MAX_CONTEXT_LENGTH_DEFAULT, manager.maxContextLength.first())
            assertEquals(SettingsDefaults.PIPELINE_MAX_STEPS_DEFAULT, manager.pipelineMaxSteps.first())
            assertEquals(
                SettingsDefaults.PIPELINE_MAX_NESTING_DEPTH_DEFAULT,
                manager.pipelineMaxNestingDepth.first(),
            )
            assertEquals(SettingsDefaults.AUDIO_MAX_DURATION_SEC_DEFAULT, manager.audioMaxDurationSec.first())
            assertEquals(SettingsDefaults.MAX_MEMORY_CHUNKS_DEFAULT, manager.maxMemoryChunks.first())
            assertEquals(
                SettingsDefaults.MEMORY_SUMMARY_DEFAULT_LIMIT_DEFAULT,
                manager.memorySummaryDefaultLimit.first(),
            )
            assertEquals(SettingsDefaults.BLOCK_DESTRUCTIVE_TOOLS_DEFAULT, manager.blockDestructiveTools.first())
            assertEquals(
                SettingsDefaults.BLOCK_NETWORK_FROM_LOCAL_MODEL_DEFAULT,
                manager.blockNetworkFromLocalModel.first(),
            )
            assertEquals(SettingsDefaults.CRASH_REPORTING_ENABLED_DEFAULT, manager.crashReportingEnabled.first())
            assertEquals(
                SettingsDefaults.VERBOSE_MEMORY_LOGGING_ENABLED_DEFAULT,
                manager.verboseMemoryLoggingEnabled.first(),
            )
            assertEquals(
                SettingsDefaults.SCHEDULED_TASK_NOTIFICATIONS_ENABLED_DEFAULT,
                manager.scheduledTaskNotificationsEnabled.first(),
            )
            assertEquals(
                SettingsDefaults.CHAT_HISTORY_COMPRESSION_ENABLED_DEFAULT,
                manager.chatHistoryCompressionEnabled.first(),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `resetToRecommendedDefaults leaves user data and configuration untouched`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            // Seed user-owned content / configuration that the reset must never touch.
            manager.setSystemPromptPrefix("my custom prefix")
            manager.setAllowedHttpDomains(listOf("api.example.com"))
            manager.addMcpServer(McpServerConfig(url = "http://mcp", name = "My MCP"))
            manager.setActiveEmbeddingProviderId("ollama")
            manager.setDefaultPipelineId("pipeline-123")
            manager.setShareTargetPipelineId("share-pipe")
            manager.setQuickSettingsTilePipelineId("tile-pipe")
            manager.setLocalModelBackend("gpu")

            manager.resetToRecommendedDefaults()

            assertEquals("my custom prefix", manager.systemPromptPrefix.first())
            assertEquals(listOf("api.example.com"), manager.allowedHttpDomains.first())
            assertEquals(listOf("http://mcp"), manager.mcpServers.first().map { it.url })
            assertEquals("ollama", manager.activeEmbeddingProviderId.first())
            assertEquals("pipeline-123", manager.defaultPipelineId.first())
            assertEquals("share-pipe", manager.shareTargetPipelineId.first())
            assertEquals("tile-pipe", manager.quickSettingsTilePipelineId.first())
            assertEquals("gpu", manager.localModelBackend.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `given surface pipeline bindings when set and cleared then round-trip correctly`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            assertEquals(null, manager.shareTargetPipelineId.first())
            assertEquals(null, manager.quickSettingsTilePipelineId.first())

            manager.setShareTargetPipelineId("share-pipe")
            manager.setQuickSettingsTilePipelineId("tile-pipe")
            assertEquals("share-pipe", manager.shareTargetPipelineId.first())
            assertEquals("tile-pipe", manager.quickSettingsTilePipelineId.first())

            manager.setShareTargetPipelineId(null)
            manager.setQuickSettingsTilePipelineId(null)
            assertEquals(null, manager.shareTargetPipelineId.first())
            assertEquals(null, manager.quickSettingsTilePipelineId.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `resetToRecommendedDefaults restores security toggles to a fresh-install state`() = runTest {
        val (manager, scope) = freshManagerWithRealDataStore()
        try {
            manager.setToolApprovalPolicy(ToolApprovalPolicy.NeverPrompt)

            manager.resetToRecommendedDefaults()

            assertEquals(ToolApprovalPolicy.DEFAULT, manager.toolApprovalPolicy.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `resetToRecommendedDefaults covers every persistable key except documented exclusions`() = runTest {
        val (manager, ds, scope) = freshManagerWithExposedDataStore()
        try {
            manager.resetToRecommendedDefaults()
            // Keys the reset actually wrote = those present after a reset on a fresh store.
            val written = ds.data.first().asMap().keys.map { it.name }.toSet()
            val allKeys = manager.knownPreferenceKeyNames()

            // User-owned content / configuration / transient state the reset must
            // never touch (mirrors the SettingsRepository.resetToRecommendedDefaults
            // contract). A new tunable key forgotten in the reset, or a user-data key
            // wrongly added to it, fails one of the assertions below.
            val excluded = setOf(
                "is_first_launch", "has_completed_onboarding", "hugging_face_token",
                "system_prompt_prefix", "tool_usage_instruction",
                "mcp_server_urls", "mcp_servers_json",
                "disabled_app_functions", "disabled_mcp_tools", "app_function_risk_overrides",
                "current_chat_session_id", "memory_last_compacted_at",
                // Backend choice plus the two crash-recovery breadcrumbs that
                // qualify it. The streak belongs with the attempt sentinel: both
                // describe how the last inits went on *this* device, and a
                // settings reset is not evidence that a backend which failed
                // twice now works.
                "local_model_backend", "last_init_backend_attempt", "local_backend_failure_streak",
                "default_pipeline_id", "console_preferred_tab", "last_test_probe_result",
                "active_embedding_provider_id", "last_reembed_provider_id",
                // User-granted network permissions, in the same category as
                // `allowed_http_domains`: a "reset to recommended defaults" that
                // silently revoked the approval for a LAN Ollama address would
                // break a working local setup with no explanation, and one that
                // silently kept re-granting it would be worse.
                "allowed_http_domains", "approved_cleartext_origins",
                // Entry-surface bindings are user choices, not tunables. The
                // external-automation one carries the most weight of the three:
                // it is the allowlist of what another app may run, so a reset
                // silently re-granting (or revoking) it would change the app's
                // exposure without the user asking for it.
                "share_target_pipeline_id", "quick_settings_tile_pipeline_id",
                "external_automation_pipeline_id",
                // Excluded because the reset REMOVES it rather than writing it,
                // which is the only way to restore its default. Its default is
                // not a number: while the key is absent the background step
                // ceiling *follows* the interactive one, and writing any value
                // — including the constant — is precisely what marks it as an
                // independent choice. A reset that wrote it would hand the user
                // a deliberate-looking decision they never made.
                "pipeline_max_steps_background",
                // Superseded legacy boolean, read only by the approval-policy
                // migration while the policy key is absent. The reset writes the
                // policy key, after which nothing reads this one — writing it
                // would only store a value no code consults.
                "requires_user_confirmation",
            )

            val uncovered = allKeys - written - excluded
            assertTrue(
                "Persistable keys neither reset nor explicitly excluded " +
                    "(wire them into resetToRecommendedDefaults or add to the exclusion list): $uncovered",
                uncovered.isEmpty(),
            )
            val wronglyReset = written intersect excluded
            assertTrue(
                "Excluded user-data keys must never be written by the reset: $wronglyReset",
                wronglyReset.isEmpty(),
            )
        } finally {
            scope.cancel()
        }
    }
}
