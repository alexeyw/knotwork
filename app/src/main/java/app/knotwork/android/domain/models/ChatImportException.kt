package app.knotwork.android.domain.models

/**
 * Why a chat file could not be imported, as a reason the screen words in the
 * interface language.
 *
 * The reason never quotes the file: on Android a JSON parser's own message ends with
 * the whole input, and a chat file is someone else's text — an import error once
 * showed a sentence the file supplied as if the app had said it. The parser's
 * exception, when there is one, is kept as the [cause] for diagnostics and is not
 * shown.
 *
 * @property reason What was wrong with the file.
 * @param cause The underlying parse failure, if any.
 */
class ChatImportException(val reason: ChatImportReason, cause: Throwable? = null) :
    Exception(reason.toString(), cause)

/** What was wrong with a chat file; each completes the sentence "Could not import chat: …". */
sealed interface ChatImportReason {

    /** The file is not JSON. */
    data object NotJson : ChatImportReason

    /** The file is JSON, but neither a chat export nor a list of messages. */
    data object NotAChatFile : ChatImportReason

    /** The export carries no list of messages. */
    data object NoMessages : ChatImportReason

    /**
     * One entry of the list is not a message.
     *
     * @property position The entry's position, from 1.
     */
    data class NotAMessage(val position: Int) : ChatImportReason
}
