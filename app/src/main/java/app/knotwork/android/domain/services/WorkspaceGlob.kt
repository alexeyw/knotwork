package app.knotwork.android.domain.services

/**
 * Pure glob matcher for agent-workspace relative paths.
 *
 * The `find_files` tool lets the agent (and pipeline authors) locate workspace
 * files by a shell-style glob (for example a `dot-md` extension glob, or a
 * recursive prefix under a directory). Matching is kept as a small,
 * dependency-free matcher so it lives in the `domain` layer (no Android, no
 * `java.nio.file.PathMatcher` API-level concern) and is exhaustively
 * unit-testable.
 *
 * Supported syntax (paths always use the forward slash as the separator,
 * matching [app.knotwork.android.domain.models.WorkspaceFile.relativePath]):
 *
 *  - A single star matches any run of characters **except** the path separator,
 *    so an extension glob matches a top-level file but not one in a
 *    sub-directory.
 *  - A double star matches any run of characters **including** the separator,
 *    spanning directories — a recursive prefix matches everything beneath it.
 *  - A double star immediately followed by a separator (a leading recursive
 *    prefix) matches zero or more leading directory segments, so such a pattern
 *    matches both a top-level file and a nested one.
 *  - A question mark matches exactly one character other than the separator.
 *  - Every other character (including regex metacharacters such as the dot and
 *    parentheses) is treated as a literal.
 *
 * "Character" means a Unicode code point, so a question mark consumes a whole
 * character outside the basic plane, and such a character written in the glob
 * matches itself.
 *
 * The match is anchored: the whole relative path must match the whole glob. See
 * [WorkspaceGlobTest] for the worked examples.
 *
 * **Why not a regex.** The glob comes from the model, and a backtracking
 * `java.util.regex` translation of a star-repeated glob takes time exponential in
 * the number of stars (measured on the JVM at about four times longer per star) —
 * a match nothing can interrupt. [Matcher] instead advances every possible
 * position in the glob together, one path character at a time, so a match costs
 * at most the glob's length times the path's length, whatever the glob.
 */
object WorkspaceGlob {

    /** Longest glob, in characters, the `find_files` tool accepts. */
    const val MAX_GLOB_LENGTH: Int = 256

    /**
     * Reports whether [relativePath] matches [glob] under the syntax documented
     * on [WorkspaceGlob].
     *
     * @param glob A shell-style glob over workspace-relative paths.
     * @param relativePath A workspace-relative path (`/`-separated, no leading
     *   slash), as produced by the workspace listing.
     * @return `true` when the entire path matches the entire glob.
     */
    fun matches(glob: String, relativePath: String): Boolean = compile(glob).matches(relativePath)

    /**
     * Parses [glob] once into a reusable [Matcher] implementing the documented
     * semantics, so a caller that tests many paths against one glob does the
     * parsing once.
     *
     * @param glob A shell-style glob over workspace-relative paths.
     * @return A [Matcher] for [glob].
     */
    fun compile(glob: String): Matcher {
        val kinds = ArrayList<Int>(glob.length)
        val literals = ArrayList<Int>(glob.length)
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            val isDoubleStar = c == '*' && i + 1 < glob.length && glob[i + 1] == '*'
            when {
                // A double-star-then-separator collapses any number of leading segments
                // (including none); a bare double-star matches across directory boundaries.
                isDoubleStar && i + 2 < glob.length && glob[i + 2] == '/' -> {
                    kinds += DIRECTORY_PREFIX
                    literals += NO_LITERAL
                    i += DOUBLE_STAR_SLASH_LEN
                }
                isDoubleStar -> {
                    kinds += ANY_RUN
                    literals += NO_LITERAL
                    i += 2
                }
                c == '*' -> {
                    kinds += SEGMENT_RUN
                    literals += NO_LITERAL
                    i += 1
                }
                c == '?' -> {
                    kinds += ONE_IN_SEGMENT
                    literals += NO_LITERAL
                    i += 1
                }
                else -> {
                    val codePoint = glob.codePointAt(i)
                    kinds += LITERAL
                    literals += codePoint
                    i += Character.charCount(codePoint)
                }
            }
        }
        return Matcher(kinds.toIntArray(), literals.toIntArray())
    }

    /**
     * A parsed glob. Safe to share between threads: [matches] keeps its state in
     * locals.
     *
     * The glob is simulated as a nondeterministic automaton. State `t` (for `t` in
     * `0..n`, `n` = token count) means "the next token to match is token `t`";
     * state `n` accepts. A leading-directory token at `t` owns one more state,
     * `n + 1 + t`, for "inside the skipped directories, waiting for a separator".
     */
    class Matcher internal constructor(private val kinds: IntArray, private val literals: IntArray) {
        private val tokenCount = kinds.size

        /**
         * Reports whether the whole of [relativePath] matches the whole glob.
         *
         * @param relativePath A workspace-relative path, `/`-separated.
         * @return `true` when the entire path matches.
         */
        fun matches(relativePath: String): Boolean {
            var active = BooleanArray(2 * tokenCount + 1)
            var next = BooleanArray(active.size)
            active[0] = true
            close(active)
            var i = 0
            while (i < relativePath.length) {
                val codePoint = relativePath.codePointAt(i)
                i += Character.charCount(codePoint)
                next.fill(false)
                if (!step(active, codePoint, next)) return false
                close(next)
                active = next.also { next = active }
            }
            return active[tokenCount]
        }

        /**
         * Consumes one [codePoint] from every state in [active] into [next].
         *
         * @return `false` when no state survives, so the path cannot match.
         */
        private fun step(active: BooleanArray, codePoint: Int, next: BooleanArray): Boolean {
            var survived = false
            val inSegment = codePoint != SEPARATOR
            for (t in 0 until tokenCount) {
                val target = if (!active[t]) {
                    NONE
                } else {
                    when (kinds[t]) {
                        LITERAL -> if (codePoint == literals[t]) t + 1 else NONE
                        ONE_IN_SEGMENT -> if (inSegment) t + 1 else NONE
                        SEGMENT_RUN -> if (inSegment) t else NONE
                        ANY_RUN -> t
                        else -> NONE // DIRECTORY_PREFIX consumes only through its loop state.
                    }
                }
                if (target != NONE) {
                    next[target] = true
                    survived = true
                }
                // The loop state is checked apart from `t` itself: once a character has been
                // consumed inside the skipped directories, only the loop state is active.
                val loop = tokenCount + 1 + t
                if (kinds[t] == DIRECTORY_PREFIX && active[loop]) {
                    next[loop] = true
                    if (!inSegment) next[t + 1] = true
                    survived = true
                }
            }
            return survived
        }

        /**
         * Adds to [states] every state reachable without consuming a character.
         * Such moves only go forward (`t` → `t + 1`, or into `t`'s loop state), so
         * one ascending pass reaches them all.
         */
        private fun close(states: BooleanArray) {
            for (t in 0 until tokenCount) {
                if (!states[t]) continue
                when (kinds[t]) {
                    SEGMENT_RUN, ANY_RUN -> states[t + 1] = true
                    DIRECTORY_PREFIX -> {
                        states[t + 1] = true
                        states[tokenCount + 1 + t] = true
                    }
                }
            }
        }
    }

    /** One character equal to the token's literal. */
    private const val LITERAL = 0

    /** `?`: one character other than the separator. */
    private const val ONE_IN_SEGMENT = 1

    /** `*`: any run of characters other than the separator. */
    private const val SEGMENT_RUN = 2

    /** `**`: any run of characters. */
    private const val ANY_RUN = 3

    /** `**` + `/`: zero or more leading directory segments. */
    private const val DIRECTORY_PREFIX = 4

    /** Placeholder literal for a token that is not [LITERAL]. */
    private const val NO_LITERAL = -1

    /** No target state. */
    private const val NONE = -1

    private const val SEPARATOR = '/'.code

    /** Number of glob characters consumed by a double-star-then-slash token (two `*` and one `/`). */
    private const val DOUBLE_STAR_SLASH_LEN = 3
}
