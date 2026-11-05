package app.knotwork.android.presentation.common

/**
 * The arguments of a tool call waiting for approval, as each approval surface shows them.
 *
 * What the user approves is what runs (`§76`, `api-conventions.md` § AppFunctions): both
 * surfaces lay the argument string out with [JsonLayout], which changes whitespace and
 * nothing the call receives, and show text that is not valid JSON exactly as it came. The
 * one liberty is at the edges: whitespace around the string is dropped, and an empty
 * string — a call with no arguments — is shown as `{}`.
 */
internal object ApprovalArguments {

    /** What an empty argument string is shown as. */
    private const val NO_ARGUMENTS = "{}"

    /**
     * The arguments as the chat card draws them: one key or item per line, nested values
     * indented.
     *
     * @param arguments the argument string the request would run with.
     * @return the text the card shows.
     */
    fun forCard(arguments: String): String = laidOut(arguments, JsonLayout::indented)

    /**
     * The arguments as the notification shows them: on one line, so the line with what
     * the run was asked stays in view below them.
     *
     * @param arguments the argument string the request would run with.
     * @return the text the notification shows, before its length budget applies.
     */
    fun forNotification(arguments: String): String = laidOut(arguments, JsonLayout::singleLine)

    private inline fun laidOut(arguments: String, layout: (String) -> String): String {
        val trimmed = arguments.trim()
        return if (trimmed.isEmpty()) NO_ARGUMENTS else layout(trimmed)
    }
}
