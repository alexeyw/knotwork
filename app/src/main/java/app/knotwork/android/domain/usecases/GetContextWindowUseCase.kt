package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.GenerationSettings
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Use case responsible for generating a prompt string from the recent chat history,
 * ensuring that the history kept fits within the configured context window.
 *
 * The window — [GenerationSettings.maxContextLength] — is expressed in **tokens**,
 * while messages are measured in **characters**, so the budget is converted with the
 * [CHARS_PER_TOKEN] heuristic before truncation. The resulting string powers the chat
 * token-usage indicator (`ChatHomeViewModel`), which divides the character length back
 * by the same factor to estimate tokens used.
 *
 * @property chatRepository The repository to fetch chat history.
 * @property generationSettings The repository to fetch context length limits.
 */
class GetContextWindowUseCase @Inject constructor(
    private val chatRepository: ChatRepository,
    private val generationSettings: GenerationSettings,
) {
    /**
     * Retrieves the chat history for the given session ID, truncates old messages
     * to fit within the configured maximum context length, and formats the remaining
     * messages into a single prompt string.
     *
     * @param sessionId The unique ID of the chat session.
     * @return A formatted string containing the most recent messages that fit into the context window.
     */
    suspend operator fun invoke(sessionId: String): String {
        val messages = chatRepository.getMessagesForSession(sessionId).first()
        // `maxContextLength` is a TOKEN budget, but this loop measures each
        // message in characters. Convert the budget to characters
        // (≈ CHARS_PER_TOKEN characters per token) before comparing — otherwise
        // a token count is compared against character lengths and the history
        // is truncated to roughly 1/CHARS_PER_TOKEN of the intended window.
        val maxTokens = generationSettings.maxContextLength.first()
        val maxChars = maxTokens * CHARS_PER_TOKEN

        val formattedMessages = mutableListOf<String>()
        var currentLength = 0

        // Iterate from newest to oldest
        for (message in messages.reversed()) {
            val formattedMessage = formatMessage(message)
            // Add +1 for a newline separator if it's not the first message being added
            val separatorLength = if (formattedMessages.isNotEmpty()) 1 else 0

            if (currentLength + formattedMessage.length + separatorLength <= maxChars) {
                formattedMessages.add(0, formattedMessage)
                currentLength += formattedMessage.length + separatorLength
            } else {
                // If adding this message exceeds the limit, stop adding older messages.
                break
            }
        }

        return formattedMessages.joinToString("\n")
    }

    private fun formatMessage(message: ChatMessage): String {
        val prefix = when (message.role) {
            Role.USER -> "USER: "
            Role.AGENT -> "AGENT: "
            Role.SYSTEM -> "SYSTEM: "
        }
        return "$prefix${message.content}"
    }

    private companion object {
        /**
         * Approximate characters per token, used to convert the
         * token-denominated [GenerationSettings.maxContextLength] budget into
         * the character budget this use case measures against. Mirrors the
         * presentation-layer estimators (`DisplayFormat.CHARS_PER_TOKEN`); kept
         * local so the `domain` layer stays free of presentation dependencies.
         */
        const val CHARS_PER_TOKEN: Int = 4
    }
}
