package app.knotwork.android.domain.models

/**
 * Why a chat file could not be imported, in words this app wrote.
 *
 * The message is shown to the user as is, so it never quotes the file: on Android a
 * JSON parser's own message ends with the whole input, and a chat file is someone
 * else's text — an import error once showed a sentence the file supplied as if the
 * app had said it. The parser's exception, when there is one, is kept as the
 * [cause] for diagnostics and is not shown.
 *
 * @param message The user-facing reason, written by the app. It completes the sentence
 *   "Could not import chat: …", so it starts in lower case and has no final full stop.
 * @param cause The underlying parse failure, if any.
 */
class ChatImportException(message: String, cause: Throwable? = null) : Exception(message, cause)
