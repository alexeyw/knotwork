package app.knotwork.android.data.prompt

import app.knotwork.android.domain.prompt.ChatTranscript
import app.knotwork.android.domain.prompt.PromptVariableProvider
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the value for the `$MEMORY_SUMMARY` placeholder.
 *
 * Resolves to a numbered list of the most recent long-term memory chunks
 * the agent has stored. The number of chunks included is configurable through
 * [SettingsRepository.memorySummaryDefaultLimit] (defaults to 5) so users can
 * trade prompt size for recall.
 *
 * When the memory store is empty the placeholder resolves to an empty string
 * to avoid feeding "No memories" boilerplate into the LLM.
 *
 * @property memoryRepository Source of the long-term memory chunks.
 * @property settingsRepository Source of the user-configured chunk limit.
 */
@Singleton
class MemorySummaryVariableProvider @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val settingsRepository: SettingsRepository,
) : PromptVariableProvider {

    override fun key(): String = KEY

    /**
     * Builds the recent-memories list.
     *
     * The chunks are sorted by `timestamp` descending so the freshest entries
     * appear first, then truncated to the configured limit and rendered as
     * `1. text`, `2. text`, ... — a deterministic numbered list that helps the
     * LLM reference items unambiguously inside the response.
     *
     * @return Newline-joined numbered list of memory texts, or an empty string
     * when no memories exist.
     */
    override suspend fun resolve(): String {
        val limit = settingsRepository.memorySummaryDefaultLimit.first()
        if (limit <= 0) return ""
        // Fetch only the rows we render (newest-first, no embeddings) — loading
        // every memory just to take(N) would scan and deserialise the entire
        // history on every prompt render.
        val recent = memoryRepository.getRecentMemorySummaries(limit)
        if (recent.isEmpty()) return ""
        // Through ChatTranscript, as every other list the model reads: a memory's
        // text comes from chats and from imported files, and a line of its own
        // inside the system prompt reads as the prompt's own instructions.
        return recent
            .mapIndexed { index, chunk -> ChatTranscript.entry("${index + 1}. ", chunk.text) }
            .joinToString(separator = "\n")
    }

    private companion object {
        const val KEY = "MEMORY_SUMMARY"
    }
}
