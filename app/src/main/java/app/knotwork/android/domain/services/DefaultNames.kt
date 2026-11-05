package app.knotwork.android.domain.services

/**
 * The names the app gives what it creates before a person names it: a chat, a
 * pipeline, the first nodes of a pipeline, a copy.
 *
 * Each name is written in the interface language at the moment the thing is
 * created and then stored like any name a person typed — changing the app's
 * language later leaves it as it was (decisions §113 R1): from the moment it
 * exists, the name is the user's data, like one they typed.
 *
 * The implementation reads string resources, which the domain layer cannot.
 */
interface DefaultNames {

    /**
     * The name of a chat nobody has named yet.
     *
     * @return The name in the interface language.
     */
    fun newChat(): String

    /**
     * Whether [name] is still a new chat's name, in any language the app ships or
     * as an earlier release stored it — the chat's first message then renames it.
     *
     * @param name The chat's stored name.
     * @return `true` when nobody has named the chat yet.
     */
    fun isNewChat(name: String): Boolean

    /**
     * The name of a chat imported from a file that names none.
     *
     * @return The name in the interface language.
     */
    fun importedChat(): String

    /**
     * The name of a chat a message created by arriving first, named after its id.
     *
     * @param shortId The beginning of the chat's id.
     * @return The name in the interface language.
     */
    fun chatWithId(shortId: String): String

    /**
     * The name of a trigger's chat when the trigger has neither a name nor a prompt.
     *
     * @return The name in the interface language.
     */
    fun triggerChat(): String

    /**
     * The name of a pipeline nobody has named yet.
     *
     * @return The name in the interface language.
     */
    fun newPipeline(): String

    /**
     * The label of the input node a new pipeline starts with.
     *
     * @return The label in the interface language.
     */
    fun inputNode(): String

    /**
     * The label of the output node a new pipeline starts with.
     *
     * @return The label in the interface language.
     */
    fun outputNode(): String

    /**
     * The name of a copy of something named [name].
     *
     * @param name The original's name.
     * @return The copy's name in the interface language.
     */
    fun copyOf(name: String): String
}
