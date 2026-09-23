package app.knotwork.android.domain.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Unit tests for [WorkspaceGlob], pinning the documented glob semantics:
 * `*` stays within a path segment, `**` crosses directories, `?` matches a
 * single non-separator character, and everything else is literal.
 */
class WorkspaceGlobTest {

    @Test
    fun `given single-star glob when matching then stays within one segment`() {
        assertTrue(WorkspaceGlob.matches("*.md", "notes.md"))
        assertFalse(WorkspaceGlob.matches("*.md", "reports/notes.md"))
    }

    @Test
    fun `given single-star glob when extension differs then no match`() {
        assertFalse(WorkspaceGlob.matches("*.md", "notes.txt"))
    }

    @Test
    fun `given double-star suffix when matching then crosses directories`() {
        assertTrue(WorkspaceGlob.matches("reports/**", "reports/a.md"))
        assertTrue(WorkspaceGlob.matches("reports/**", "reports/2026/q1/b.md"))
        assertFalse(WorkspaceGlob.matches("reports/**", "notes.md"))
    }

    @Test
    fun `given double-star suffix when path is the bare prefix then no match`() {
        // `reports/**` requires the separator, so the directory name alone does not match.
        assertFalse(WorkspaceGlob.matches("reports/**", "reports"))
    }

    @Test
    fun `given leading double-star slash when matching then optional leading segments`() {
        assertTrue(WorkspaceGlob.matches("**/*.md", "a.md"))
        assertTrue(WorkspaceGlob.matches("**/*.md", "sub/a.md"))
        assertTrue(WorkspaceGlob.matches("**/*.md", "sub/deeper/a.md"))
        assertFalse(WorkspaceGlob.matches("**/*.md", "a.txt"))
    }

    @Test
    fun `given question mark when matching then single non-separator char`() {
        assertTrue(WorkspaceGlob.matches("file?.md", "file1.md"))
        assertFalse(WorkspaceGlob.matches("file?.md", "file.md"))
        assertFalse(WorkspaceGlob.matches("a?b", "a/b"))
    }

    @Test
    fun `given regex metacharacters when matching then treated as literals`() {
        assertTrue(WorkspaceGlob.matches("report.(final).md", "report.(final).md"))
        // The literal dot must not behave like a regex wildcard.
        assertFalse(WorkspaceGlob.matches("a.b", "axb"))
    }

    @Test
    fun `given full path glob when matching then anchored end to end`() {
        assertTrue(WorkspaceGlob.matches("reports/q1.md", "reports/q1.md"))
        assertFalse(WorkspaceGlob.matches("reports/q1.md", "reports/q1.md.bak"))
        assertFalse(WorkspaceGlob.matches("q1.md", "reports/q1.md"))
    }

    @Test
    fun `given bare double-star when matching then matches everything`() {
        assertTrue(WorkspaceGlob.matches("**", "a.md"))
        assertTrue(WorkspaceGlob.matches("**", "deep/nested/path/file.json"))
    }

    @Test
    fun `given a question mark and a character outside the basic plane when matching then it is one character`() {
        // `[^/]` in java.util.regex consumes a whole code point, and the matcher keeps that.
        // The literal case is a fix: the former translation quoted each UTF-16 half on its
        // own, so a glob holding such a character never matched anything (checked on the JVM).
        val emoji = String(Character.toChars(0x1F600))
        assertTrue(WorkspaceGlob.matches("?.md", "$emoji.md"))
        assertTrue(WorkspaceGlob.matches("$emoji*", "${emoji}notes.md"))
    }

    @Test(timeout = 2_000)
    fun `given a star-repeated glob that fails against a long name when matching then it answers promptly`() {
        // A backtracking translation explores every split of the subject between the stars:
        // measured on the JVM at about four times longer per added star (nine stars: 11 s).
        // Ten stars sit roughly twenty times over this timeout, and a regression leaves the
        // runaway match to finish within about a minute rather than hold the test JVM.
        val glob = "**a".repeat(PATHOLOGICAL_STARS) + "**b"
        val path = "a".repeat(PATHOLOGICAL_SUBJECT_A) + ".txt"

        assertFalse(WorkspaceGlob.matches(glob, path))
    }

    @Test(timeout = 2_000)
    fun `given the longest accepted glob and path when matching then the cost stays linear in their product`() {
        val glob = "*a".repeat(WorkspaceGlob.MAX_GLOB_LENGTH / 2)
        val path = "a".repeat(LONG_SUBJECT) + "b"

        assertFalse(WorkspaceGlob.matches(glob, path))
    }

    @Test
    fun `given random globs and paths when matching then the result equals the former regex translation`() {
        // The matcher replaced a regex translation; this pins that the language each glob
        // accepts did not move. Small alphabets make every token meet every other.
        val random = Random(EQUIVALENCE_SEED)
        repeat(EQUIVALENCE_CASES) {
            val glob = randomString(random, GLOB_ALPHABET, MAX_RANDOM_GLOB)
            val path = randomString(random, PATH_ALPHABET, MAX_RANDOM_PATH)
            assertEquals(
                "glob '$glob' against '$path'",
                formerRegex(glob).matches(path),
                WorkspaceGlob.matches(glob, path),
            )
        }
    }

    private fun randomString(random: Random, alphabet: String, maxLength: Int): String =
        String(CharArray(random.nextInt(maxLength + 1)) { alphabet[random.nextInt(alphabet.length)] })

    /** The translation `WorkspaceGlob.compile` used before the matcher, kept as the reference. */
    private fun formerRegex(glob: String): Regex {
        val pattern = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            when (val c = glob[i]) {
                '*' -> if (i + 1 < glob.length && glob[i + 1] == '*') {
                    if (i + 2 < glob.length && glob[i + 2] == '/') {
                        pattern.append("(?:.*/)?")
                        i += 3
                    } else {
                        pattern.append(".*")
                        i += 2
                    }
                } else {
                    pattern.append("[^/]*")
                    i += 1
                }
                '?' -> {
                    pattern.append("[^/]")
                    i += 1
                }
                else -> {
                    pattern.append(Regex.escape(c.toString()))
                    i += 1
                }
            }
        }
        return Regex(pattern.append('$').toString())
    }

    private companion object {
        const val PATHOLOGICAL_STARS = 10
        const val PATHOLOGICAL_SUBJECT_A = 40
        const val LONG_SUBJECT = 4_096
        const val EQUIVALENCE_SEED = 44_08
        const val EQUIVALENCE_CASES = 20_000
        const val MAX_RANDOM_GLOB = 8
        const val MAX_RANDOM_PATH = 10
        const val GLOB_ALPHABET = "ab/*?."
        const val PATH_ALPHABET = "ab/."
    }
}
