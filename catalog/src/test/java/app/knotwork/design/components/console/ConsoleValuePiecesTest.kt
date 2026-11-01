package app.knotwork.design.components.console

import org.junit.Assert.assertEquals
import org.junit.Test

/** How [ConsoleValuePieces] cuts the value viewer's lines into lazy items and counts their rows. */
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

    @Test
    fun `given a long line at 42 characters a row when its rows are counted then each piece holds 47 of them`() {
        val pieces = ConsoleValuePieces(listOf(ConsoleValueLine(indent = 0, text = "x".repeat(5_000)))) { 42 }

        // ⌈5000 / 42⌉ = 120 rows: 47, 47 and the last 26.
        assertEquals(120, pieces.rowCount)
        assertEquals(listOf(0, 47, 94), (0 until 3).map { pieces.firstRowOf(it) })
        assertEquals(ConsoleValueRowPosition(index = 0, rowInPiece = 46), pieces.pieceAt(46))
        assertEquals(ConsoleValueRowPosition(index = 1, rowInPiece = 0), pieces.pieceAt(47))
        assertEquals(ConsoleValueRowPosition(index = 2, rowInPiece = 25), pieces.pieceAt(119))
    }

    @Test
    fun `given a row before the first or past the last when looked up then the nearest end is taken`() {
        val pieces = ConsoleValuePieces(listOf(ConsoleValueLine(indent = 0, text = "x".repeat(5_000)))) { 42 }

        assertEquals(ConsoleValueRowPosition(index = 0, rowInPiece = 0), pieces.pieceAt(-3))
        assertEquals(ConsoleValueRowPosition(index = 2, rowInPiece = 25), pieces.pieceAt(10_000))
    }

    @Test
    fun `given short, empty and long lines when rows are counted then every line takes at least one`() {
        val lines = listOf(
            ConsoleValueLine(indent = 0, text = "short"),
            ConsoleValueLine(indent = 0, text = ""),
            ConsoleValueLine(indent = 1, text = "y".repeat(4_000)),
            ConsoleValueLine(indent = 0, text = "end"),
        )
        val pieces = ConsoleValuePieces(lines) { 40 }

        // 1 + 1 + 100 (two pieces of 50) + 1.
        assertEquals(103, pieces.rowCount)
        assertEquals(listOf(0, 1, 2, 52, 102), (0 until pieces.count).map { pieces.firstRowOf(it) })
        assertEquals(ConsoleValueRowPosition(index = 1, rowInPiece = 0), pieces.pieceAt(1))
        assertEquals(ConsoleValueRowPosition(index = 3, rowInPiece = 0), pieces.pieceAt(52))
        assertEquals(ConsoleValueRowPosition(index = 3, rowInPiece = 49), pieces.pieceAt(101))
        assertEquals(ConsoleValueRowPosition(index = 4, rowInPiece = 0), pieces.pieceAt(102))
    }

    @Test
    fun `given a row wider than the piece size when rows are counted then each piece is one row`() {
        val pieces = ConsoleValuePieces(listOf(ConsoleValueLine(indent = 0, text = "z".repeat(6_000)))) { 2_500 }

        assertEquals(3, pieces.rowCount)
        assertEquals(ConsoleValueRowPosition(index = 2, rowInPiece = 0), pieces.pieceAt(2))
    }
}
