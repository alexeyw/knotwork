package app.knotwork.android.domain.services

/**
 * The sentences the app writes into a chat's history as `SYSTEM` rows when the
 * user's own decision changes what a run does: a tool call the user denied, a
 * clarification reply that arrived too late to be used.
 *
 * Each row is shown in the chat, written in the interface language at the moment
 * it is saved and then kept as written; a model reading the history reads it in
 * the language of the conversation (decisions §113.14). Rows saved earlier stay as
 * they were. The hidden rows the tool gate records for the model — its
 * observation, its own denial record, a blocked call — are tool results and stay
 * English.
 *
 * The implementation reads string resources, which the domain layer cannot.
 */
interface ChatSystemRows {

    /**
     * The row recording that the user denied a tool call.
     *
     * @param toolName The name of the tool the call was for, as the agent named it.
     * @return The sentence in the interface language.
     */
    fun toolDenied(toolName: String): String

    /**
     * The row recording that the user's clarification reply was not used: the
     * request had already been resolved with its default answer.
     *
     * @return The sentence in the interface language.
     */
    fun clarificationReplyNotDelivered(): String
}
