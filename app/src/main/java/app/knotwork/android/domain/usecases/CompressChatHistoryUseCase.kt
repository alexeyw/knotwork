package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.engine.ChatHistoryWindowPlanner
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.ChatHistorySummary
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.prompt.ChatTranscript
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.prompt.PromptVariableProvider
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Background pass that keeps a long chat session's verbatim history bounded by
 * summarising the messages older than the live window into a single dense
 * [ChatHistorySummary].
 *
 * Triggered (debounced, never during an active run) by
 * [app.knotwork.android.domain.services.ChatHistoryCompressionCoordinator] after
 * a pipeline completes. One pass:
 *  1. Short-circuits when compression is disabled.
 *  2. Loads the session's full unfiltered history (the same source the engine
 *     reads for context, so the cursor stays consistent with rendering).
 *  3. Skips when the history is within budget, or when there is nothing older
 *     than the live window to summarise.
 *  4. **Incrementally** folds the previous summary plus only the messages that
 *     have aged out since it was last computed into a new summary — never
 *     re-summarising the whole history, and never building an unbounded
 *     summary-of-summary chain (each pass produces exactly one summary covering
 *     the whole older tail).
 *  5. Runs one local-model inference with
 *     [DefaultPrompts.HistoryCompression.SYSTEM_FALLBACK] (plain prose) and
 *     persists the result with the new coverage cursor.
 *
 * Best-effort: a blank reply, an inference error, an unavailable model, or a
 * persistence failure all yield [CompressionOutcome.SKIPPED] rather than
 * throwing, so background maintenance can never break a conversation. Only
 * [CancellationException] propagates.
 *
 * @property chatRepository Source of session messages and summary persistence.
 * @property llmInferenceEngine Local model used to run the compression prompt.
 * @property loadModelUseCase Ensures the active model is loaded before inference.
 * @property promptTemplateEngine Substitutes runtime `$VARIABLE`s (here `$DATE`).
 * @property promptVariableProviders Registered providers backing the templating.
 * @property settingsRepository Source of the compression toggle, token budget,
 *   and live-window size.
 */
class CompressChatHistoryUseCase @Inject constructor(
    private val chatRepository: ChatRepository,
    private val llmInferenceEngine: LlmInferenceEngine,
    private val loadModelUseCase: LoadModelUseCase,
    private val promptTemplateEngine: PromptTemplateEngine,
    private val promptVariableProviders: Set<@JvmSuppressWildcards PromptVariableProvider>,
    private val settingsRepository: SettingsRepository,
) {

    /** What a single compression pass did. */
    enum class CompressionOutcome {
        /** Disabled, under budget, nothing new to summarise, or a best-effort failure. */
        SKIPPED,

        /** A fresh summary was computed and persisted. */
        COMPRESSED,
    }

    /**
     * Runs one compression pass for [sessionId].
     *
     * @param sessionId The chat session to (re)summarise.
     * @param nowMillis Wall-clock "now" stamped on the saved summary. Defaults to
     *   the system clock; tests pass a fixed value for determinism.
     * @return Whether a fresh summary was written.
     */
    suspend operator fun invoke(sessionId: String, nowMillis: Long = System.currentTimeMillis()): CompressionOutcome =
        withContext(Dispatchers.Default) {
            if (sessionId.isBlank()) return@withContext CompressionOutcome.SKIPPED
            if (!settingsRepository.chatHistoryCompressionEnabled.first()) return@withContext CompressionOutcome.SKIPPED

            val window = settingsRepository.chatHistoryLiveWindowSize.first()
            val thresholdTokens = settingsRepository.chatHistoryCompressionThresholdTokens.first()

            val messages = chatRepository.getMessagesForSession(sessionId).first()
            if (messages.size <= window) return@withContext CompressionOutcome.SKIPPED
            if (ChatHistoryWindowPlanner.approxTokenCount(messages) <= thresholdTokens) {
                return@withContext CompressionOutcome.SKIPPED
            }

            // The tail that should be represented by the summary: everything older
            // than the live window. coveredMessageCount tracks how much of this tail
            // a prior summary already covers, so a pass only digests the delta.
            val tail = messages.dropLast(window)
            val existing = chatRepository.getHistorySummary(sessionId)
            val alreadyCovered = (existing?.coveredMessageCount ?: 0).coerceIn(0, tail.size)
            if (alreadyCovered >= tail.size) {
                // The summary already covers the whole older tail; the messages that
                // have aged out since are still within reach of the next pass once
                // more arrive. Nothing to do now.
                return@withContext CompressionOutcome.SKIPPED
            }
            val newPortion = tail.subList(alreadyCovered, tail.size)

            if (loadModelUseCase() is Result.Error) {
                Timber.tag(TAG).w("Active model unavailable; skipping chat-history compression")
                return@withContext CompressionOutcome.SKIPPED
            }

            val summaryText = runInference(existing?.summary, newPortion).trim()
            if (summaryText.isEmpty()) return@withContext CompressionOutcome.SKIPPED

            try {
                chatRepository.saveHistorySummary(
                    ChatHistorySummary(
                        sessionId = sessionId,
                        summary = summaryText,
                        coveredMessageCount = tail.size,
                        updatedAt = nowMillis,
                    ),
                )
                CompressionOutcome.COMPRESSED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to persist chat-history summary for session %s", sessionId)
                CompressionOutcome.SKIPPED
            }
        }

    /**
     * Builds the compression prompt from the rendered system prompt, the prior
     * summary (when present), and the new messages, then runs it once through the
     * local model and joins the streamed tokens. Never throws for an inference
     * error — returns an empty string so the caller skips the pass.
     *
     * @param priorSummary The existing running summary to fold in, or `null` on
     *   the first pass for this session.
     * @param newMessages The messages that have aged out since the prior summary.
     * @return The model's raw reply (expected to be a single plain-text summary).
     */
    private suspend fun runInference(priorSummary: String?, newMessages: List<ChatMessage>): String {
        val systemPrompt = promptTemplateEngine.render(
            DefaultPrompts.HistoryCompression.SYSTEM_FALLBACK,
            promptVariableProviders.toList(),
        )
        // Every role is kept — the summary stands in for the history the engine
        // shows a node verbatim — but no message can open a turn of its own.
        val dialogue = newMessages.joinToString(separator = "\n") { message ->
            ChatTranscript.turn(label = message.role.name, content = message.content)
        }
        val priorBlock = priorSummary
            ?.takeIf { it.isNotBlank() }
            ?.let { "PREVIOUS SUMMARY:\n$it\n\n" }
            .orEmpty()
        val fullPrompt = "$systemPrompt\n\n${priorBlock}NEW MESSAGES:\n$dialogue\n\nUPDATED SUMMARY: "

        return try {
            llmInferenceEngine.generateResponseStream(fullPrompt).toList().joinToString(separator = "")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Chat-history compression inference failed")
            ""
        }
    }

    private companion object {
        const val TAG = "HistoryCompression"
    }
}
