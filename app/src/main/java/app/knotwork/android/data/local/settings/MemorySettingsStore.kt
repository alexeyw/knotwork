package app.knotwork.android.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.repositories.MemorySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MemorySettings] over the app's preferences DataStore: retrieval tuning, automatic extraction,
 * compaction, the embedding provider with its re-embed marker, verbose memory logging, and chat-history
 * compression.
 *
 * The embedding provider, its re-embed marker and the last compaction time are not tunables and are
 * never written by a reset; everything else here is, through [writeRecommendedDefaults].
 *
 * A class-level `@Singleton`, like every settings section (`SettingsSingletonScopeTest`).
 *
 * @property dataStore The app's one preferences DataStore.
 */
@Singleton
class MemorySettingsStore @Inject constructor(private val dataStore: DataStore<Preferences>) : MemorySettings {

    private object Keys {
        val MEMORY_LAST_COMPACTED_AT = longPreferencesKey("memory_last_compacted_at")
        val MEMORY_SEARCH_TOP_K = intPreferencesKey("memory_search_top_k")
        val MEMORY_SEARCH_THRESHOLD = floatPreferencesKey("memory_search_threshold")
        val MEMORY_RECENCY_HALF_LIFE_DAYS = intPreferencesKey("memory_recency_half_life_days")
        val MEMORY_SUMMARY_DEFAULT_LIMIT = intPreferencesKey("memory_summary_default_limit")
        val ACTIVE_EMBEDDING_PROVIDER_ID = stringPreferencesKey("active_embedding_provider_id")
        val LAST_REEMBED_PROVIDER_ID = stringPreferencesKey("last_reembed_provider_id")
        val AUTO_EXTRACT_ENABLED = booleanPreferencesKey("auto_extract_enabled")
        val MEMORY_COMPACTION_ENABLED = booleanPreferencesKey("memory_compaction_enabled")
        val MEMORY_COMPACTION_AGE_DAYS = intPreferencesKey("memory_compaction_age_days")
        val MAX_MEMORY_CHUNKS = intPreferencesKey("max_memory_chunks")
        val VERBOSE_MEMORY_LOGGING_ENABLED = booleanPreferencesKey("verbose_memory_logging_enabled")
        val CHAT_HISTORY_COMPRESSION_ENABLED = booleanPreferencesKey("chat_history_compression_enabled")
        val CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS = intPreferencesKey("chat_history_compression_threshold_tokens")
        val CHAT_HISTORY_LIVE_WINDOW_SIZE = intPreferencesKey("chat_history_live_window_size")
    }

    override val memoryLastCompactedAt: Flow<Long> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.MEMORY_LAST_COMPACTED_AT] ?: 0L
        }

    override suspend fun setMemoryLastCompactedAt(millis: Long) {
        dataStore.edit { preferences ->
            preferences[Keys.MEMORY_LAST_COMPACTED_AT] = millis
        }
    }

    override val memorySearchTopK: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.MEMORY_SEARCH_TOP_K]
                ?: SettingsDefaults.MEMORY_SEARCH_TOP_K_DEFAULT
        }

    override suspend fun setMemorySearchTopK(topK: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.MEMORY_SEARCH_TOP_K] = topK
        }
    }

    override val memorySearchThreshold: Flow<Float> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.MEMORY_SEARCH_THRESHOLD]
                ?: SettingsDefaults.MEMORY_SEARCH_THRESHOLD_DEFAULT
        }

    override suspend fun setMemorySearchThreshold(threshold: Float) {
        dataStore.edit { preferences ->
            preferences[Keys.MEMORY_SEARCH_THRESHOLD] = threshold
        }
    }

    override val memoryRecencyHalfLifeDays: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.MEMORY_RECENCY_HALF_LIFE_DAYS]
                ?: SettingsDefaults.MEMORY_RECENCY_HALF_LIFE_DAYS_DEFAULT
        }

    override suspend fun setMemoryRecencyHalfLifeDays(days: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.MEMORY_RECENCY_HALF_LIFE_DAYS] = days
        }
    }

    override val memorySummaryDefaultLimit: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.MEMORY_SUMMARY_DEFAULT_LIMIT]
                ?: SettingsDefaults.MEMORY_SUMMARY_DEFAULT_LIMIT_DEFAULT
        }

    override suspend fun setMemorySummaryDefaultLimit(limit: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.MEMORY_SUMMARY_DEFAULT_LIMIT] = limit
        }
    }

    override val activeEmbeddingProviderId: Flow<String> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.ACTIVE_EMBEDDING_PROVIDER_ID]
                ?: SettingsDefaults.ACTIVE_EMBEDDING_PROVIDER_ID_DEFAULT
        }

    override suspend fun setActiveEmbeddingProviderId(id: String) {
        dataStore.edit { preferences ->
            // First provider switch ever: capture the provider the stored
            // vectors were created with, so the re-embed reminder banner can
            // compare it against the new active id. Done inside the same edit
            // so the capture and the switch land atomically.
            if (preferences[Keys.LAST_REEMBED_PROVIDER_ID] == null) {
                preferences[Keys.LAST_REEMBED_PROVIDER_ID] =
                    preferences[Keys.ACTIVE_EMBEDDING_PROVIDER_ID]
                        ?: SettingsDefaults.ACTIVE_EMBEDDING_PROVIDER_ID_DEFAULT
            }
            preferences[Keys.ACTIVE_EMBEDDING_PROVIDER_ID] = id
        }
    }

    override val lastReembedProviderId: Flow<String?> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.LAST_REEMBED_PROVIDER_ID]
        }

    override suspend fun setLastReembedProviderId(id: String) {
        dataStore.edit { preferences ->
            preferences[Keys.LAST_REEMBED_PROVIDER_ID] = id
        }
    }

    override val autoExtractEnabled: Flow<Boolean> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.AUTO_EXTRACT_ENABLED] ?: SettingsDefaults.AUTO_EXTRACT_ENABLED_DEFAULT
        }

    override suspend fun setAutoExtractEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.AUTO_EXTRACT_ENABLED] = enabled
        }
    }

    override val memoryCompactionEnabled: Flow<Boolean> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.MEMORY_COMPACTION_ENABLED]
                ?: SettingsDefaults.MEMORY_COMPACTION_ENABLED_DEFAULT
        }

    override suspend fun setMemoryCompactionEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.MEMORY_COMPACTION_ENABLED] = enabled
        }
    }

    override val memoryCompactionAgeDays: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.MEMORY_COMPACTION_AGE_DAYS]
                ?: SettingsDefaults.MEMORY_COMPACTION_AGE_DAYS_DEFAULT
        }

    override suspend fun setMemoryCompactionAgeDays(days: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.MEMORY_COMPACTION_AGE_DAYS] = days
        }
    }

    override val maxMemoryChunks: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.MAX_MEMORY_CHUNKS]
                ?: SettingsDefaults.MAX_MEMORY_CHUNKS_DEFAULT
        }

    override suspend fun setMaxMemoryChunks(limit: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.MAX_MEMORY_CHUNKS] = limit
        }
    }

    override val verboseMemoryLoggingEnabled: Flow<Boolean> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.VERBOSE_MEMORY_LOGGING_ENABLED]
                ?: SettingsDefaults.VERBOSE_MEMORY_LOGGING_ENABLED_DEFAULT
        }

    override suspend fun setVerboseMemoryLoggingEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.VERBOSE_MEMORY_LOGGING_ENABLED] = enabled
        }
    }

    override val chatHistoryCompressionEnabled: Flow<Boolean> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.CHAT_HISTORY_COMPRESSION_ENABLED]
                ?: SettingsDefaults.CHAT_HISTORY_COMPRESSION_ENABLED_DEFAULT
        }

    override suspend fun setChatHistoryCompressionEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.CHAT_HISTORY_COMPRESSION_ENABLED] = enabled
        }
    }

    override val chatHistoryCompressionThresholdTokens: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS]
                ?: SettingsDefaults.CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS_DEFAULT
        }

    override suspend fun setChatHistoryCompressionThresholdTokens(tokens: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS] = tokens
        }
    }

    override val chatHistoryLiveWindowSize: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.CHAT_HISTORY_LIVE_WINDOW_SIZE]
                ?: SettingsDefaults.CHAT_HISTORY_LIVE_WINDOW_DEFAULT
        }

    override suspend fun setChatHistoryLiveWindowSize(size: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.CHAT_HISTORY_LIVE_WINDOW_SIZE] = size
        }
    }

    /**
     * Writes every recommended default of this section into [preferences], as part of the one atomic
     * edit of "Reset all settings": memory tuning, extraction, compaction, verbose logging and
     * chat-history compression.
     *
     * @param preferences The preferences being edited by the reset.
     */
    internal fun writeRecommendedDefaults(preferences: MutablePreferences) {
        preferences[Keys.MEMORY_SUMMARY_DEFAULT_LIMIT] = SettingsDefaults.MEMORY_SUMMARY_DEFAULT_LIMIT_DEFAULT
        preferences[Keys.MEMORY_SEARCH_TOP_K] = SettingsDefaults.MEMORY_SEARCH_TOP_K_DEFAULT
        preferences[Keys.MEMORY_SEARCH_THRESHOLD] = SettingsDefaults.MEMORY_SEARCH_THRESHOLD_DEFAULT
        preferences[Keys.MEMORY_RECENCY_HALF_LIFE_DAYS] = SettingsDefaults.MEMORY_RECENCY_HALF_LIFE_DAYS_DEFAULT
        preferences[Keys.MEMORY_COMPACTION_ENABLED] = SettingsDefaults.MEMORY_COMPACTION_ENABLED_DEFAULT
        preferences[Keys.MEMORY_COMPACTION_AGE_DAYS] = SettingsDefaults.MEMORY_COMPACTION_AGE_DAYS_DEFAULT
        preferences[Keys.MAX_MEMORY_CHUNKS] = SettingsDefaults.MAX_MEMORY_CHUNKS_DEFAULT
        preferences[Keys.AUTO_EXTRACT_ENABLED] = SettingsDefaults.AUTO_EXTRACT_ENABLED_DEFAULT
        preferences[Keys.VERBOSE_MEMORY_LOGGING_ENABLED] = SettingsDefaults.VERBOSE_MEMORY_LOGGING_ENABLED_DEFAULT
        preferences[Keys.CHAT_HISTORY_COMPRESSION_ENABLED] = SettingsDefaults.CHAT_HISTORY_COMPRESSION_ENABLED_DEFAULT
        preferences[Keys.CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS] =
            SettingsDefaults.CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS_DEFAULT
        preferences[Keys.CHAT_HISTORY_LIVE_WINDOW_SIZE] = SettingsDefaults.CHAT_HISTORY_LIVE_WINDOW_DEFAULT
    }
}
