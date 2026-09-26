package app.knotwork.android.domain.services

/**
 * The rules a workspace path must satisfy before the workspace **creates** an
 * entry under it, and the one definition of which characters a name may not
 * carry.
 *
 * **Why names need rules at all.** The file tools render names into a
 * line-oriented, tab-separated observation (`list_files`, `find_files`), and on
 * the import path a name is whatever the source app's content provider says it
 * is. A line break or a tab inside a name would let that text forge an entry the
 * model reads as a fact about the filesystem. A control character therefore never
 * reaches a new name: [app.knotwork.android.domain.usecases.workspace.ImportFileToWorkspaceUseCase]
 * replaces it with [REPLACEMENT], and the workspace refuses a path that still
 * carries one with [app.knotwork.android.domain.models.WorkspaceError.InvalidPath].
 *
 * **Why lengths.** The filesystem refuses a name over 255 bytes, and the
 * workspace stages every write in a sibling scratch file whose name is 13 bytes
 * longer — so a name over [MAX_NAME_BYTES] could never be written, and the only
 * question is whether it fails with a typed refusal or an I/O exception.
 * [MAX_PATH_BYTES] keeps the whole absolute path far below every platform's
 * path limit (1024 bytes on the macOS test host, 4096 on Android).
 *
 * **Only new entries.** A name that already exists — created before these rules,
 * or by anything other than the workspace — stays readable and deletable; refusing
 * it would leave the user a file they cannot remove. Listings escape such a name
 * with [escapeForbidden] instead.
 */
object WorkspaceNamePolicy {

    /**
     * Longest single path segment, in UTF-8 bytes: the filesystem's 255-byte
     * `NAME_MAX` minus the 13-byte `.knotwork-tmp` scratch suffix every write is
     * staged under.
     */
    const val MAX_NAME_BYTES: Int = 242

    /** Longest workspace-relative path, in UTF-8 bytes. */
    const val MAX_PATH_BYTES: Int = 512

    /** Character substituted for a forbidden one when a name is sanitised. */
    const val REPLACEMENT: Char = '_'

    /** Why a path is refused by [violationOf]. */
    enum class Violation {
        /**
         * The path carries a character a name may not hold: one [isForbidden]
         * rejects, or half of a surrogate pair — which has no UTF-8 form, so the
         * JVM writes it as `?` and `java.nio` refuses the name outright.
         */
        CONTROL_CHARACTER,

        /** One segment is longer than [MAX_NAME_BYTES]. */
        NAME_TOO_LONG,

        /** The whole path is longer than [MAX_PATH_BYTES]. */
        PATH_TOO_LONG,
    }

    /**
     * Reports whether [c] may not appear in a new workspace name: the C0 and C1
     * control ranges (which include NUL, tab, line feed, carriage return and the
     * Unicode next-line character) and DEL, plus the Unicode line and paragraph
     * separators, which a reader may also treat as line breaks.
     *
     * @param c The character to test.
     * @return `true` when [c] is forbidden in a name.
     */
    fun isForbidden(c: Char): Boolean = c.isISOControl() || c.code == LINE_SEPARATOR || c.code == PARAGRAPH_SEPARATOR

    /**
     * Checks a workspace-relative path against the rules for a new entry.
     *
     * @param relativePath A `/`-separated path relative to the workspace root —
     *   the canonical form, so `..` segments are already collapsed.
     * @return The first rule [relativePath] breaks, or `null` when it may be created.
     */
    fun violationOf(relativePath: String): Violation? = when {
        hasForbiddenCharacter(relativePath) -> Violation.CONTROL_CHARACTER
        relativePath.utf8Size() > MAX_PATH_BYTES -> Violation.PATH_TOO_LONG
        relativePath.split('/').any { it.utf8Size() > MAX_NAME_BYTES } -> Violation.NAME_TOO_LONG
        else -> null
    }

    /**
     * Reports whether [text] holds a character no new name may carry: one
     * [isForbidden] rejects, or half of a surrogate pair.
     *
     * @param text A path or a name, as the caller wrote it.
     * @return `true` when some character of [text] is forbidden.
     */
    fun hasForbiddenCharacter(text: String): Boolean = text.indices.any { text.forbiddenAt(it) }

    /**
     * Replaces every forbidden character of [name] with [REPLACEMENT], one for
     * one, so an imported name keeps its shape but can no longer break a line —
     * and a lone surrogate, which no filesystem name can hold, becomes one too.
     *
     * @param name A candidate file name.
     * @return [name] with each forbidden character replaced.
     */
    fun replaceForbidden(name: String): String = buildString(name.length) {
        name.indices.forEach { append(if (name.forbiddenAt(it)) REPLACEMENT else name[it]) }
    }

    /**
     * Renders [text] with every forbidden character written out as a visible
     * escape (`\n`, `\r`, `\t`, otherwise `\uXXXX`), for a line-oriented output
     * that must show a name created before these rules without letting it break
     * the line. A backslash is not escaped, so the result is for reading, not for
     * parsing back.
     *
     * @param text The text to render.
     * @return [text] on one line, forbidden characters escaped.
     */
    fun escapeForbidden(text: String): String {
        if (text.indices.none { text.forbiddenAt(it) }) return text
        return buildString(text.length + ESCAPE_HEADROOM) {
            text.forEachIndexed { index, c ->
                when {
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    text.forbiddenAt(index) -> append(unicodeEscape(c))
                    else -> append(c)
                }
            }
        }
    }

    /** [c] as a `\uXXXX` escape, upper-case hex. */
    private fun unicodeEscape(c: Char): String =
        "\\u" + c.code.toString(HEX_RADIX).uppercase().padStart(HEX_DIGITS, '0')

    private fun String.utf8Size(): Int = encodeToByteArray().size

    /**
     * Whether the character at [index] may not appear in a name: [isForbidden],
     * or a surrogate that is not half of a well-formed pair.
     */
    private fun String.forbiddenAt(index: Int): Boolean {
        val c = this[index]
        return when {
            isForbidden(c) -> true
            c.isHighSurrogate() -> getOrNull(index + 1)?.isLowSurrogate() != true
            c.isLowSurrogate() -> getOrNull(index - 1)?.isHighSurrogate() != true
            else -> false
        }
    }

    /** U+2028 LINE SEPARATOR, written as a code so no source file carries the raw character. */
    private const val LINE_SEPARATOR = 0x2028

    /** U+2029 PARAGRAPH SEPARATOR, written as a code for the same reason. */
    private const val PARAGRAPH_SEPARATOR = 0x2029

    private const val HEX_RADIX = 16

    /** Digits of a `\uXXXX` escape. */
    private const val HEX_DIGITS = 4

    /** Extra capacity reserved for escapes when building an escaped string. */
    private const val ESCAPE_HEADROOM = 16
}
