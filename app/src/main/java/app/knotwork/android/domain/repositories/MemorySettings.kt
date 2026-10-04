package app.knotwork.android.domain.repositories

import kotlinx.coroutines.flow.Flow

/**
 * Long-term memory and chat-history compression: retrieval tuning, automatic extraction,
 * compaction, the embedding provider with its re-embed marker, verbose memory logging, and when a
 * long chat's history is summarised.
 *
 * One of the sections [SettingsRepository] is made of.
 */
interface MemorySettings {

    /**
     * A [Flow] emitting the epoch-millis of the most recent successful memory
     * compaction pass (manual or background), or `0L` when compaction has
     * never run. Powers the "compacted N ago" line on the Memory stats card.
     */
    val memoryLastCompactedAt: Flow<Long>

    /**
     * Records the time a compaction pass finished.
     *
     * @param millis Epoch-millis to store as the most-recent compaction time.
     */
    suspend fun setMemoryLastCompactedAt(millis: Long)

    /**
     * A [Flow] emitting the maximum number of long-term memory chunks a single
     * retrieval returns into a node's context (the "top-K" of the similarity
     * search). The search itself always scans the full stored pool; this caps
     * how many results survive ranking and reach the prompt. Defaults to
     * `SettingsDefaults.MEMORY_SEARCH_TOP_K_DEFAULT` (5) for a fresh install.
     */
    val memorySearchTopK: Flow<Int>

    /**
     * Updates the long-term memory retrieval top-K.
     *
     * @param topK The new top-K; callers should keep it within a sane range
     *   (validation of user-entered values lives in the Settings ViewModel).
     */
    suspend fun setMemorySearchTopK(topK: Int)

    /**
     * `true` when long chat sessions should be compressed: once the verbatim
     * history exceeds [chatHistoryCompressionThresholdTokens], the tail older
     * than the live window is summarised into the `--- Earlier conversation
     * (summarized) ---` context block. Defaults to
     * [app.knotwork.android.domain.constants.SettingsDefaults.CHAT_HISTORY_COMPRESSION_ENABLED_DEFAULT].
     */
    val chatHistoryCompressionEnabled: Flow<Boolean>

    /**
     * Persists the chat-history compression toggle.
     *
     * @param enabled `true` to enable history compression, `false` to always
     *   feed the full verbatim history.
     */
    suspend fun setChatHistoryCompressionEnabled(enabled: Boolean)

    /**
     * Approximate-token budget above which a session's verbatim chat history is
     * compressed. Coordinated with [GenerationSettings.maxContextLength] so the summarised history
     * plus memory and tool blocks fit the on-device context window. Defaults to
     * [app.knotwork.android.domain.constants.SettingsDefaults.CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS_DEFAULT].
     */
    val chatHistoryCompressionThresholdTokens: Flow<Int>

    /**
     * Persists the chat-history compression token threshold.
     *
     * @param tokens The new threshold; callers should keep it within a sane
     *   range (validation of user-entered values lives in the Settings ViewModel).
     */
    suspend fun setChatHistoryCompressionThresholdTokens(tokens: Int)

    /**
     * Number of most-recent messages kept verbatim under `--- Chat History ---`
     * when compression is active; everything older is represented by the
     * summary. Defaults to
     * [app.knotwork.android.domain.constants.SettingsDefaults.CHAT_HISTORY_LIVE_WINDOW_DEFAULT].
     */
    val chatHistoryLiveWindowSize: Flow<Int>

    /**
     * Persists the chat-history live-window size.
     *
     * @param size The new window size; callers should keep it within a sane
     *   range (validation of user-entered values lives in the Settings ViewModel).
     */
    suspend fun setChatHistoryLiveWindowSize(size: Int)

    /**
     * A [Flow] emitting the minimum cosine-similarity score (0.0–1.0) a memory
     * chunk must reach to be considered relevant during retrieval. Chunks below
     * this threshold are dropped before they reach a node's context. Defaults
     * to `SettingsDefaults.MEMORY_SEARCH_THRESHOLD_DEFAULT` (0.55) for a fresh
     * install.
     */
    val memorySearchThreshold: Flow<Float>

    /**
     * Updates the long-term memory retrieval similarity threshold.
     *
     * @param threshold The new threshold in the inclusive range 0.0–1.0;
     *   validation of user-entered values lives in the Settings ViewModel.
     */
    suspend fun setMemorySearchThreshold(threshold: Float)

    /**
     * A [Flow] emitting the recency half-life (in days) used by the long-term
     * memory re-ranker. A non-pinned chunk this old keeps half of its raw
     * cosine similarity; freshness wins ties below it and is penalised above
     * it. Defaults to `SettingsDefaults.MEMORY_RECENCY_HALF_LIFE_DAYS_DEFAULT`
     * (30) for a fresh install.
     */
    val memoryRecencyHalfLifeDays: Flow<Int>

    /**
     * Updates the long-term memory recency half-life.
     *
     * @param days The new half-life in days; callers should keep it within a
     *   sane range (validation of user-entered values lives in the Settings
     *   ViewModel).
     */
    suspend fun setMemoryRecencyHalfLifeDays(days: Int)

    /**
     * A [Flow] representing the default number of recent memory chunks rendered
     * by the `$MEMORY_SUMMARY` prompt variable. Defaults to 5.
     */
    val memorySummaryDefaultLimit: Flow<Int>

    /**
     * Updates the default number of recent memory chunks shown by `$MEMORY_SUMMARY`.
     *
     * @param limit The new chunk count. Values `<= 0` are valid and disable the
     * variable (it resolves to an empty string).
     */
    suspend fun setMemorySummaryDefaultLimit(limit: Int)

    /**
     * Id of the currently active embedding provider, matching one of the
     * `EmbeddingProvider.ID_*` constants (e.g. `"use"`, `"openai_3_small"`,
     * `"ollama"`). Drives which backend the long-term memory subsystem uses to
     * turn text into vectors. Defaults to
     * [SettingsDefaults.ACTIVE_EMBEDDING_PROVIDER_ID_DEFAULT] (on-device USE).
     *
     * If the persisted value no longer matches a registered provider,
     * `EmbeddingProviderResolver` falls back to the on-device default at
     * resolution time — the stored value is left untouched.
     */
    val activeEmbeddingProviderId: Flow<String>

    /**
     * Persists the active embedding provider id.
     *
     * Implementations also capture the *previous* active id into
     * [lastReembedProviderId] when that value has never been set, so the
     * provider the stored embeddings were actually created with is known from
     * the first provider switch onward (powers the re-embed reminder banner).
     *
     * @param id One of the `EmbeddingProvider.ID_*` constants.
     */
    suspend fun setActiveEmbeddingProviderId(id: String)

    /**
     * Id of the embedding provider the stored memory vectors were last
     * (re-)embedded with, or `null` when unknown (no provider switch and no
     * re-embed has happened yet — the store is then in sync by definition).
     *
     * Settings → Memory compares this against [activeEmbeddingProviderId]:
     * a mismatch means existing vectors live in a different embedding space
     * than new queries, and a persistent "re-embed recommended" banner is
     * shown until the user runs a successful re-embed (or wipes the store).
     */
    val lastReembedProviderId: Flow<String?>

    /**
     * Records the provider id the memory store is now consistent with —
     * called after a successful full re-embed and after a full memory wipe
     * (an empty store has no stale vectors).
     *
     * @param id One of the `EmbeddingProvider.ID_*` constants.
     */
    suspend fun setLastReembedProviderId(id: String)

    /**
     * `true` when the agent should automatically extract durable facts from a
     * conversation into long-term memory after a pipeline run completes. Drives
     * the "Auto-extract from conversations" toggle in Settings → Memory and is
     * the short-circuit gate consulted by the auto-extraction trigger. Defaults
     * to [app.knotwork.android.domain.constants.SettingsDefaults.AUTO_EXTRACT_ENABLED_DEFAULT]
     * (`true`).
     */
    val autoExtractEnabled: Flow<Boolean>

    /**
     * Persists the auto-extract memory toggle.
     *
     * @param enabled `true` to enable automatic memory extraction, `false` to
     *   disable it (the trigger then short-circuits to a no-op).
     */
    suspend fun setAutoExtractEnabled(enabled: Boolean)

    /**
     * `true` when the background memory-compaction worker is allowed to run.
     * Drives the "Background compaction" toggle in Settings → Memory and is the
     * short-circuit gate consulted by `MemoryCompactionWorker` at run time (a
     * user flipping it off while a run is queued still cancels the work).
     * Defaults to
     * [app.knotwork.android.domain.constants.SettingsDefaults.MEMORY_COMPACTION_ENABLED_DEFAULT]
     * (`true`).
     */
    val memoryCompactionEnabled: Flow<Boolean>

    /**
     * Persists the background memory-compaction toggle.
     *
     * @param enabled `true` to enable background compaction, `false` to disable
     *   it (the worker then short-circuits to a no-op).
     */
    suspend fun setMemoryCompactionEnabled(enabled: Boolean)

    /**
     * `true` when long-term memory operations should emit verbose diagnostics.
     * Drives the "Verbose memory logging" toggle in Settings → Privacy.
     *
     * When enabled, [app.knotwork.android.domain.engine.GraphExecutionEngine] expands
     * each `MemoryAccess` console event with a per-hit snippet and similarity
     * score, and [app.knotwork.android.domain.usecases.MemoryCompactionUseCase] logs
     * the cluster membership of every consolidation. Off by default to keep the
     * console and logcat quiet for users who do not need the detail. Defaults to
     * [app.knotwork.android.domain.constants.SettingsDefaults.VERBOSE_MEMORY_LOGGING_ENABLED_DEFAULT]
     * (`false`).
     */
    val verboseMemoryLoggingEnabled: Flow<Boolean>

    /**
     * Persists the verbose memory logging toggle.
     *
     * @param enabled `true` to enable verbose memory diagnostics, `false` to fall
     *   back to the terse one-line summaries.
     */
    suspend fun setVerboseMemoryLoggingEnabled(enabled: Boolean)

    /**
     * Age threshold, in days, beyond which a non-pinned chunk becomes eligible
     * for compaction. Chunks younger than this keep their exact wording; only
     * older ones are clustered and consolidated. Defaults to
     * [app.knotwork.android.domain.constants.SettingsDefaults.MEMORY_COMPACTION_AGE_DAYS_DEFAULT]
     * (30).
     */
    val memoryCompactionAgeDays: Flow<Int>

    /**
     * Persists the compaction age window.
     *
     * @param days The new age threshold in days; callers should keep it within
     *   a sane range (validation of user-entered values lives in the Settings
     *   ViewModel).
     */
    suspend fun setMemoryCompactionAgeDays(days: Int)

    /**
     * Hard ceiling on the total number of stored memory chunks. When the table
     * grows past this, compaction is triggered out-of-schedule to keep the
     * database bounded. Defaults to
     * [app.knotwork.android.domain.constants.SettingsDefaults.MAX_MEMORY_CHUNKS_DEFAULT]
     * (5000).
     */
    val maxMemoryChunks: Flow<Int>

    /**
     * Persists the max-chunks hard limit.
     *
     * @param limit The new hard limit; callers should keep it within a sane
     *   range (validation of user-entered values lives in the Settings
     *   ViewModel).
     */
    suspend fun setMaxMemoryChunks(limit: Int)
}
