package app.knotwork.android.domain.services

/**
 * [DefaultNames] with the English values of the resources, for tests that run
 * without resources. A new chat's name is recognised in English only.
 */
object EnglishDefaultNames : DefaultNames {

    /** The English new-chat name, also the name earlier releases stored. */
    const val NEW_CHAT: String = "New Chat"

    override fun newChat(): String = NEW_CHAT

    override fun isNewChat(name: String): Boolean = name == NEW_CHAT

    override fun importedChat(): String = "Imported Chat"

    override fun chatWithId(shortId: String): String = "Chat $shortId"

    override fun triggerChat(): String = "Trigger"

    override fun newPipeline(): String = "New Pipeline"

    override fun inputNode(): String = "Input"

    override fun outputNode(): String = "Output"

    override fun copyOf(name: String): String = "$name (copy)"
}
