package app.knotwork.android.domain.services

import app.knotwork.android.domain.services.WorkspaceNamePolicy.Violation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [WorkspaceNamePolicy]: which characters a new name may not carry,
 * the byte limits at their exact boundaries (in UTF-8, not characters), and the
 * two renderings of a forbidden character — replaced on import, escaped in a listing.
 */
class WorkspaceNamePolicyTest {

    @Test
    fun `given the C0 range DEL and the C1 range when isForbidden then every one is forbidden`() {
        val controls = (0x00..0x1F) + 0x7F + (0x80..0x9F)

        controls.forEach { assertTrue("U+%04X".format(it), WorkspaceNamePolicy.isForbidden(Char(it))) }
    }

    @Test
    fun `given the Unicode line and paragraph separators when isForbidden then both are forbidden`() {
        assertTrue(WorkspaceNamePolicy.isForbidden(Char(LINE_SEPARATOR)))
        assertTrue(WorkspaceNamePolicy.isForbidden(Char(PARAGRAPH_SEPARATOR)))
    }

    @Test
    fun `given ordinary name characters when isForbidden then none is forbidden`() {
        "report (1).md-_~ Привет #%&'".forEach { assertFalse("'$it'", WorkspaceNamePolicy.isForbidden(it)) }
    }

    @Test
    fun `given a plain nested path when violationOf then there is none`() {
        assertNull(WorkspaceNamePolicy.violationOf("reports/2026/q3 summary.md"))
    }

    @Test
    fun `given a line break anywhere in the path when violationOf then CONTROL_CHARACTER`() {
        assertEquals(Violation.CONTROL_CHARACTER, WorkspaceNamePolicy.violationOf("notes.md\nforged.txt"))
        assertEquals(Violation.CONTROL_CHARACTER, WorkspaceNamePolicy.violationOf("dir\t/f.txt"))
        assertEquals(Violation.CONTROL_CHARACTER, WorkspaceNamePolicy.violationOf("a${Char(LINE_SEPARATOR)}b"))
    }

    @Test
    fun `given half of a surrogate pair anywhere in the path when violationOf then CONTROL_CHARACTER`() {
        // A lone surrogate has no UTF-8 encoding: the JVM writes it as '?', and
        // java.nio refuses to turn the name into a Path at all — which the
        // workspace walk does for every entry. It must never become a name.
        for (path in listOf("\uD800.txt", "notes/a\uDC00b.md", "x\uD83D")) {
            assertEquals(path, WorkspaceNamePolicy.Violation.CONTROL_CHARACTER, WorkspaceNamePolicy.violationOf(path))
        }
    }

    @Test
    fun `given text when hasForbiddenCharacter then only control characters and broken pairs count`() {
        assertTrue(WorkspaceNamePolicy.hasForbiddenCharacter("a\uD800b"))
        assertTrue(WorkspaceNamePolicy.hasForbiddenCharacter("line\nbreak"))
        assertFalse(WorkspaceNamePolicy.hasForbiddenCharacter("notes/\uD83D\uDE00.md"))
        assertFalse(WorkspaceNamePolicy.hasForbiddenCharacter("../x.md"))
    }

    @Test
    fun `given a whole surrogate pair when violationOf then there is none`() {
        assertNull(WorkspaceNamePolicy.violationOf("notes/\uD83D\uDE00 smile.md"))
    }

    @Test
    fun `given a lone surrogate when replaceForbidden then it is replaced and a whole pair is kept`() {
        assertEquals("_.txt \uD83D\uDE00_", WorkspaceNamePolicy.replaceForbidden("\uD800.txt \uD83D\uDE00\uDC00"))
    }

    @Test
    fun `given a lone surrogate when escapeForbidden then it is shown as an escape`() {
        assertEquals("a\\uD800b", WorkspaceNamePolicy.escapeForbidden("a\uD800b"))
    }

    @Test
    fun `given a name at and one over the byte limit when violationOf then only the longer is refused`() {
        assertNull(WorkspaceNamePolicy.violationOf("n".repeat(WorkspaceNamePolicy.MAX_NAME_BYTES)))
        assertEquals(
            Violation.NAME_TOO_LONG,
            WorkspaceNamePolicy.violationOf("dir/" + "n".repeat(WorkspaceNamePolicy.MAX_NAME_BYTES + 1)),
        )
    }

    @Test
    fun `given a Cyrillic name when violationOf then the limit counts UTF-8 bytes not characters`() {
        // Two bytes per letter: 121 letters are 242 bytes, 122 are 244.
        val atLimit = "ж".repeat(WorkspaceNamePolicy.MAX_NAME_BYTES / 2)
        assertNull(WorkspaceNamePolicy.violationOf(atLimit))
        assertEquals(Violation.NAME_TOO_LONG, WorkspaceNamePolicy.violationOf(atLimit + "ж"))
    }

    @Test
    fun `given a path at and one over the byte limit when violationOf then only the longer is refused`() {
        val segment = "s".repeat(SEGMENT)
        val atLimit = List(PATH_SEGMENTS) { segment }.joinToString("/")
            .let { it + "/" + "f".repeat(WorkspaceNamePolicy.MAX_PATH_BYTES - it.length - 1) }
        assertEquals(WorkspaceNamePolicy.MAX_PATH_BYTES, atLimit.length)

        assertNull(WorkspaceNamePolicy.violationOf(atLimit))
        assertEquals(Violation.PATH_TOO_LONG, WorkspaceNamePolicy.violationOf(atLimit + "f"))
    }

    @Test
    fun `given forbidden characters when replaceForbidden then each is replaced one for one`() {
        val name = "a\nb\tc${Char(0)}d${Char(PARAGRAPH_SEPARATOR)}e"

        assertEquals("a_b_c_d_e", WorkspaceNamePolicy.replaceForbidden(name))
    }

    @Test
    fun `given forbidden characters when escapeForbidden then the text stays on one line and shows them`() {
        val escaped = WorkspaceNamePolicy.escapeForbidden("a\nb\rc\td${Char(0x1B)}e${Char(LINE_SEPARATOR)}f")

        assertEquals("a\\nb\\rc\\td\\u001Be\\u2028f", escaped)
        assertEquals(1, escaped.lines().size)
    }

    @Test
    fun `given text without forbidden characters when escapeForbidden then it is returned unchanged`() {
        val text = "reports/q3\\summary.md"

        assertTrue(text === WorkspaceNamePolicy.escapeForbidden(text))
    }

    private companion object {
        const val LINE_SEPARATOR = 0x2028
        const val PARAGRAPH_SEPARATOR = 0x2029
        const val SEGMENT = 100
        const val PATH_SEGMENTS = 5
    }
}
