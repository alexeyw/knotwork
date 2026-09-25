package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.engine.structured.EngineStructuredInferenceClient
import app.knotwork.android.domain.engine.structured.GateResult
import app.knotwork.android.domain.engine.structured.RepairListener
import app.knotwork.android.domain.engine.structured.StructuredOutputGate
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.MemorySource
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.prompt.ChatTranscript
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.prompt.PromptVariableProvider
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.MetricsRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.EmbeddingProviderResolver
import app.knotwork.android.domain.services.MemorySearchStatsTracker
import app.knotwork.android.domain.services.MemoryVectorSimilarity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import timber.log.Timber
import javax.inject.Inject

/**
 * Distils durable, long-term facts out of a finished conversation and persists
 * the novel ones into long-term memory.
 *
 * Lifecycle of one extraction pass:
 *  1. Keep only the conversational turns ([EXTRACTED_ROLES]) written on this
 *     device ([ChatMessage.writtenOnThisDevice]) and take the most recent slice of
 *     them (see [RECENT_MESSAGE_WINDOW]). A row imported from a chat file is not
 *     something this device's user said, whatever role the file gave it.
 *  2. Render the conservative extraction system prompt
 *     ([DefaultPrompts.MemoryExtraction.SYSTEM_FALLBACK]) — resolving `$DATE`
 *     for temporal grounding — and run it once through the local LiteRT model.
 *  3. Parse the model's reply as a JSON array of `{type, text}` facts.
 *  4. For each fact, compute its embedding with the **active** provider and
 *     reject near-duplicates (cosine similarity ≥
 *     [MemoryVectorSimilarity.NEAR_DUPLICATE_THRESHOLD])
 *     of either an already-stored chunk (checked against the **full** stored
 *     pool — an old fact must stay dedup-visible no matter its age) or a fact
 *     accepted earlier in the same pass.
 *  5. Save the survivors tagged with [MemorySource.ChatSession].
 *
 * **Which rows are read.** Only [Role.USER] and [Role.AGENT] — an allowlist, so
 * a role added later is excluded until someone decides otherwise. [Role.SYSTEM]
 * rows are never mined: they are tool observations, refusal notes and run
 * outcomes, none of which is a fact the user stated, and a tool observation is
 * untrusted content (a web page, an MCP server, a file). The filter runs before
 * the window, so a tool-heavy run cannot crowd the user's own turns out of it.
 * Agent turns stay in as context for the user's; the prompt tells the model to
 * ignore the assistant's own statements. Whether agent turns should be read at
 * all is an open design question (issue #426), not settled here. Each turn is
 * rendered through [ChatTranscript], so no message body can open a turn of its
 * own — the prompt's "only what the user stated" is read against real turns.
 *
 * The use case is intentionally free of the auto-extract feature toggle: the
 * trigger that calls it owns that gate, leaving this use case reusable by the
 * manual "Save to memory" path. It never throws for an
 * empty / malformed model reply or a model that cannot be loaded — it returns a
 * zero-result [MemoryExtractionOutcome] instead, so a best-effort background
 * pass can never break a conversation.
 *
 * @property llmInferenceEngine Local model used to run the extraction prompt.
 * @property loadModelUseCase Ensures the active model is loaded before inference.
 * @property promptTemplateEngine Substitutes runtime `$VARIABLE`s (here `$DATE`).
 * @property promptVariableProviders Registered providers backing the templating.
 * @property embeddingProviderResolver Resolves the active embedding backend per call.
 * @property memoryRepository Persistence + similarity search over stored chunks.
 * @property memorySearchStatsTracker Records the dedup-search scores so the
 *   Settings AVG SCORE stat cell keeps reflecting every similarity search.
 * @property structuredOutputGate Validate-and-repair gate the JSON-array reply
 *   is run through before parsing.
 * @property settingsRepository Source of the configured repair ceiling
 *   ([SettingsRepository.structuredOutputMaxRepairs]).
 * @property metricsRepository Sink for the per-pass repair-attempt counter.
 */
class MemoryExtractionUseCase @Inject constructor(
    private val llmInferenceEngine: LlmInferenceEngine,
    private val loadModelUseCase: LoadModelUseCase,
    private val promptTemplateEngine: PromptTemplateEngine,
    private val promptVariableProviders: Set<@JvmSuppressWildcards PromptVariableProvider>,
    private val embeddingProviderResolver: EmbeddingProviderResolver,
    private val memoryRepository: MemoryRepository,
    private val memorySearchStatsTracker: MemorySearchStatsTracker,
    private val structuredOutputGate: StructuredOutputGate,
    private val settingsRepository: SettingsRepository,
    private val metricsRepository: MetricsRepository,
) {

    /**
     * Runs one extraction pass over [messages] and persists the novel facts.
     *
     * @param sessionId Id of the chat session the [messages] belong to; recorded
     *   as [MemorySource.ChatSession] on every saved chunk.
     * @param messages The conversation to mine. Only its [EXTRACTED_ROLES] rows
     *   written on this device are read (never an imported row), and of those only
     *   the trailing [RECENT_MESSAGE_WINDOW]; passes
     *   with fewer than [MIN_MESSAGES_TO_EXTRACT] such rows are skipped (too
     *   little signal).
     * @return A summary of how many facts were parsed, saved, and skipped as
     *   duplicates.
     */
    suspend operator fun invoke(sessionId: String, messages: List<ChatMessage>): MemoryExtractionOutcome =
        withContext(Dispatchers.Default) {
            val recent = messages.filter { it.writtenOnThisDevice && it.role in EXTRACTED_ROLES }
                .takeLast(RECENT_MESSAGE_WINDOW)
            if (recent.size < MIN_MESSAGES_TO_EXTRACT) {
                return@withContext MemoryExtractionOutcome.EMPTY
            }

            // Ensure the active model is loaded. The model was almost certainly
            // just used by the completing pipeline, so this is usually a no-op;
            // if it cannot be loaded we skip rather than fail the background pass.
            if (loadModelUseCase() is Result.Error) {
                Timber.tag(TAG).w("Active model unavailable; skipping memory extraction")
                return@withContext MemoryExtractionOutcome.EMPTY
            }

            val facts = extractFacts(recent)
            if (facts.isEmpty()) {
                return@withContext MemoryExtractionOutcome(parsed = 0, saved = 0, skippedDuplicates = 0)
            }

            persistNovelFacts(sessionId, facts)
        }

    /**
     * Builds the extraction prompt, runs it through the structured-output gate,
     * and maps the validated JSON array into the recognised facts.
     *
     * The gate validates the reply as a JSON array of `{type, text}` objects and,
     * on a malformed reply, hands the model its own output back with the parse
     * error for up to [SettingsRepository.structuredOutputMaxRepairs] corrective
     * re-inferences. Each repair attempt bumps the off-graph `MEMORY_EXTRACTION`
     * repair counter so the cost of a stumbling model is observable.
     *
     * This pass is **best-effort**: a gate failure (repairs exhausted), a
     * non-cancellation inference error, or a reply with no recognised facts all
     * yield an empty list rather than throwing, so a background extraction can
     * never break a conversation. Only elements with a non-blank `text` and a
     * recognised `type` ([VALID_FACT_TYPES]) survive.
     *
     * @param messages Trailing slice of the conversation to embed in the prompt.
     * @return The recognised facts (possibly empty).
     */
    private suspend fun extractFacts(messages: List<ChatMessage>): List<ExtractedFact> {
        val systemPrompt = promptTemplateEngine.render(
            DefaultPrompts.MemoryExtraction.SYSTEM_FALLBACK,
            promptVariableProviders.toList(),
        )
        val dialogue = messages.joinToString(separator = "\n") { message ->
            ChatTranscript.turn(label = message.role.label(), content = message.content)
        }
        val fullPrompt = "$systemPrompt\n\nCONVERSATION:\n$dialogue\n\nJSON OUTPUT: "
        val maxRepairs = settingsRepository.structuredOutputMaxRepairs.first()

        val result = try {
            structuredOutputGate.runJson(
                inference = EngineStructuredInferenceClient(llmInferenceEngine),
                prompt = fullPrompt,
                serializer = ListSerializer(ExtractedFactDto.serializer()),
                nodeName = METRICS_KEY,
                maxRepairs = maxRepairs,
                listener = RepairListener { _, _, _ -> metricsRepository.recordStructuredOutputRepair(METRICS_KEY) },
            )
        } catch (e: CancellationException) {
            // Preserve structured-concurrency cancellation: a broad catch would
            // otherwise let the calling scope believe the pass finished cleanly.
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Memory extraction inference failed")
            return emptyList()
        }

        return when (result) {
            is GateResult.Success -> result.value.mapNotNull { dto ->
                val type = dto.type.trim().lowercase()
                val text = dto.text.trim()
                if (text.isNotEmpty() && type in VALID_FACT_TYPES) ExtractedFact(type = type, text = text) else null
            }
            is GateResult.Failed -> {
                // Repairs exhausted — honour the best-effort contract and drop the
                // pass silently (repair attempts were already counted by the listener).
                Timber.tag(
                    TAG,
                ).w("Memory extraction gate failed after %d repairs: %s", result.repairs, result.lastError)
                emptyList()
            }
        }
    }

    /**
     * Embeds and stores every fact that is not a near-duplicate of an existing
     * chunk or of a fact already accepted in this pass.
     *
     * @param sessionId Session recorded as the provenance of each saved chunk.
     * @param facts Parsed candidate facts.
     * @return Outcome counters for the pass.
     */
    private suspend fun persistNovelFacts(sessionId: String, facts: List<ExtractedFact>): MemoryExtractionOutcome {
        val provider = embeddingProviderResolver.resolve()

        // Embed every fact in a single batch call. Cloud providers turn this
        // into one network round-trip instead of N (one per fact); on-device
        // providers map over their single-text path. The result is
        // index-aligned with [facts].
        val embeddings = try {
            provider.embed(facts.map { it.text })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to embed extracted facts; skipping the pass")
            return MemoryExtractionOutcome(parsed = facts.size, saved = 0, skippedDuplicates = 0)
        }
        if (embeddings.size != facts.size) {
            // Defensive: a provider that breaks the index-alignment contract
            // would mis-attribute embeddings to facts — drop the pass rather
            // than persist scrambled vectors.
            Timber.tag(TAG).w(
                "Embedding count %d != fact count %d; skipping the pass",
                embeddings.size,
                facts.size,
            )
            return MemoryExtractionOutcome(parsed = facts.size, saved = 0, skippedDuplicates = 0)
        }

        val source = MemorySource.ChatSession(sessionId)
        val acceptedEmbeddings = mutableListOf<FloatArray>()
        var saved = 0
        var skipped = 0

        for (i in facts.indices) {
            val embedding = embeddings[i]
            if (isDuplicate(embedding, acceptedEmbeddings)) {
                skipped++
                continue
            }

            memoryRepository.saveMemory(
                text = facts[i].text,
                embedding = embedding,
                source = source,
                // Persist the fact type as a tag so the Memory surface can
                // render it (e.g. `fact` / `preference` / `project`).
                tags = listOf(facts[i].type),
            )
            acceptedEmbeddings += embedding
            saved++
        }

        return MemoryExtractionOutcome(parsed = facts.size, saved = saved, skippedDuplicates = skipped)
    }

    /**
     * Decides whether [embedding] is a near-duplicate. A fact is a duplicate
     * when its cosine similarity to either a stored chunk or a fact already
     * accepted in this pass is at least
     * [MemoryVectorSimilarity.NEAR_DUPLICATE_THRESHOLD]. The
     * stored-chunk check runs against the **full** stored pool — an old fact
     * must keep rejecting its re-extracted duplicates no matter its age.
     *
     * @param embedding Embedding of the candidate fact.
     * @param acceptedEmbeddings Embeddings accepted earlier in the same pass.
     * @return `true` if the candidate should be skipped as a duplicate.
     */
    private suspend fun isDuplicate(embedding: FloatArray, acceptedEmbeddings: List<FloatArray>): Boolean {
        val hits = memoryRepository.findSimilarMemories(embedding, limit = 1)
        memorySearchStatsTracker.record(hits.map { it.second })
        val topStored = hits.firstOrNull()?.second ?: 0f
        if (topStored >= MemoryVectorSimilarity.NEAR_DUPLICATE_THRESHOLD) return true
        return acceptedEmbeddings.any {
            MemoryVectorSimilarity.cosine(embedding, it) >= MemoryVectorSimilarity.NEAR_DUPLICATE_THRESHOLD
        }
    }

    /**
     * One parsed extraction fact: its [type] (a [VALID_FACT_TYPES] member,
     * persisted as a tag) and its durable [text].
     */
    private data class ExtractedFact(val type: String, val text: String)

    /**
     * Wire shape of one fact in the model's JSON-array reply, deserialized by the
     * structured-output gate. Both fields default to empty so a partial object
     * (missing `type` or `text`) deserializes successfully and is then dropped by
     * the [VALID_FACT_TYPES] / non-blank filter in [extractFacts] rather than
     * failing the whole array.
     *
     * @property type Raw fact category as emitted by the model.
     * @property text Raw fact text as emitted by the model.
     */
    @Serializable
    private data class ExtractedFactDto(val type: String = "", val text: String = "")

    /** Maps a [Role] to the speaker label used inside the extraction prompt. */
    private fun Role.label(): String = when (this) {
        Role.USER -> "User"
        Role.AGENT -> "Assistant"
        Role.SYSTEM -> "System"
    }

    private companion object {
        const val TAG = "MemoryExtraction"

        /** Maximum number of trailing conversational turns fed to the extractor. */
        const val RECENT_MESSAGE_WINDOW = 20

        /** Minimum conversational turns required before a pass is worthwhile. */
        const val MIN_MESSAGES_TO_EXTRACT = 2

        /**
         * The roles whose rows reach the extraction prompt — an allowlist, so a
         * role added later stays out until it is decided on. [Role.SYSTEM] rows
         * (tool observations, refusal notes, run outcomes) never do.
         */
        val EXTRACTED_ROLES = setOf(Role.USER, Role.AGENT)

        /**
         * Synthetic node key under which this off-graph consumer records its
         * structured-output repair attempts in
         * [app.knotwork.android.domain.models.AgentMetrics.repairAttemptsPerNode]
         * (memory extraction is not a pipeline node).
         */
        const val METRICS_KEY = "MEMORY_EXTRACTION"

        /** Recognised fact categories emitted by the extraction prompt. */
        val VALID_FACT_TYPES = setOf("preference", "event", "relation")
    }

    /**
     * Summary of a single extraction pass.
     *
     * @property parsed Number of well-formed facts parsed from the model reply.
     * @property saved Number of novel facts persisted to memory.
     * @property skippedDuplicates Number of facts dropped as near-duplicates.
     */
    data class MemoryExtractionOutcome(val parsed: Int, val saved: Int, val skippedDuplicates: Int) {
        /** Shared constants for [MemoryExtractionOutcome]. */
        companion object {
            /** Result of a pass that did nothing (skipped, empty, or model unavailable). */
            val EMPTY = MemoryExtractionOutcome(parsed = 0, saved = 0, skippedDuplicates = 0)
        }
    }
}
