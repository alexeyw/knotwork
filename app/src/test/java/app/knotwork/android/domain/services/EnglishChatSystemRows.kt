package app.knotwork.android.domain.services

/**
 * [ChatSystemRows] with the English values of the resources, for tests that run
 * without resources.
 */
object EnglishChatSystemRows : ChatSystemRows {

    override fun toolDenied(toolName: String): String = "Tool '$toolName' denied by user."

    override fun clarificationReplyNotDelivered(): String = REPLY_NOT_DELIVERED

    /** The English row for a clarification reply that came too late. */
    const val REPLY_NOT_DELIVERED: String =
        "Reply was not delivered — the clarification had already been resolved with a default answer."
}
