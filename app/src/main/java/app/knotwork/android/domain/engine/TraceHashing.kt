package app.knotwork.android.domain.engine

import java.security.MessageDigest

/**
 * The one hash function of the run trace: SHA-256 over a text's UTF-8 bytes, as
 * lowercase hex.
 *
 * Every hash a run records — a node's input and output, a model call's prompt
 * and output — goes through here, so a reader of an exported trace recomputes
 * each one with any SHA-256 tool and gets the same string.
 */
object TraceHashing {

    private const val HASH_ALGORITHM = "SHA-256"

    /**
     * Hashes [text].
     *
     * @param text The text to hash.
     * @return Lowercase hex SHA-256 of [text]'s UTF-8 bytes.
     */
    fun sha256Hex(text: String): String = MessageDigest.getInstance(HASH_ALGORITHM)
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
