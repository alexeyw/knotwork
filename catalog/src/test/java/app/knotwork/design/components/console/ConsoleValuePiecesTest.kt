package app.knotwork.design.components.console

import org.junit.Assert.assertEquals
import org.junit.Test

/** How [ConsoleValuePieces] cuts the value viewer's lines into lazy items. */
class ConsoleValuePiecesTest {

    @Test
    fun `given 42 characters a row when a long line is cut then each piece is a whole number of rows`() {
        // 2000 / 42 = 47 rows of 42 = 1 974 characters, so a seam falls at a row's end.
        val line = ConsoleValueLine(indent = 0, text = "x".repeat(5_000))
        val pieces = ConsoleValuePieces(listOf(line)) { 42 }

        assertEquals(3, pieces.count)
        val ranges = (0 until 3).map { pieces.piece(it).let { piece -> piece.start to piece.end } }
        assertEquals(listOf(0 to 1_974, 1_974 to 3_948, 3_948 to 5_000), ranges)
    }

    @Test
    fun `given short, empty and long lines when cut then each piece maps back to its line`() {
        val lines = listOf(
            ConsoleValueLine(indent = 0, text = "short"),
            ConsoleValueLine(indent = 0, text = ""),
            ConsoleValueLine(indent = 1, text = "y".repeat(4_000)),
            ConsoleValueLine(indent = 0, text = "end"),
        )
        val pieces = ConsoleValuePieces(lines) { 40 }

        // 2000 / 40 × 40 = 2000: the long line is exactly two pieces; an empty line is one item.
        assertEquals(5, pieces.count)
        assertEquals(listOf(0, 1, 2, 2, 3), (0 until pieces.count).map { pieces.piece(it).lineIndex })
        assertEquals(2_000 to 4_000, pieces.piece(3).let { it.start to it.end })
        assertEquals(0 to 0, pieces.piece(1).let { it.start to it.end })
    }

    @Test
    fun `given a row wider than the piece size when cut then a piece is one row`() {
        val pieces = ConsoleValuePieces(listOf(ConsoleValueLine(indent = 0, text = "z".repeat(6_000)))) { 2_500 }

        assertEquals(3, pieces.count)
    }

    @Test
    fun `given no width for a row when cut then each piece still holds at least one character a row`() {
        val pieces = ConsoleValuePieces(listOf(ConsoleValueLine(indent = 9, text = "w".repeat(2_001)))) { 0 }

        assertEquals(2, pieces.count)
    }
}
