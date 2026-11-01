package app.knotwork.design.components.console

/** Most characters one lazy item of the value viewer holds; above it a line is cut into pieces. */
internal const val VALUE_PIECE_MAX_CHARS = 2_000

/**
 * The value viewer's lines cut into pieces of at most [VALUE_PIECE_MAX_CHARS] characters,
 * one lazy item each, so the viewer never lays out more than one piece at a time. Laying
 * out one string costs time in its whole length — quadratic when it has no spaces — and a
 * value's line can be a megabyte.
 *
 * A piece of a cut line is a whole number of rows long, `⌊2000 / perRow⌋ × perRow`, so in
 * a monospaced font the seam between two pieces falls at a row's end and cannot be seen.
 * Only the counts are kept: a piece's text is cut out when its item is drawn.
 *
 * The pieces also count rows, for the viewer's scroll thumb: a line takes
 * `⌈length / perRow⌉` rows, at least one. That is exact for a line without spaces; a line
 * that wraps at words can take more, so for text the count is a lower bound.
 *
 * @param lines the formatted lines.
 * @param charsPerRow characters one row of a line holds, given the line — its indent takes
 *   part of the width; at least 1.
 */
internal class ConsoleValuePieces(private val lines: List<ConsoleValueLine>, charsPerRow: (ConsoleValueLine) -> Int) {

    /** Characters per row, line by line, at least 1. */
    private val perRow = IntArray(lines.size) { charsPerRow(lines[it]).coerceAtLeast(1) }

    /** Characters per piece, line by line. */
    private val pieceLengths = IntArray(lines.size) { pieceLength(perRow[it]) }

    /** `starts[l]` is the index of line `l`'s first piece; the last entry is the count. */
    private val starts = IntArray(lines.size + 1).also { starts ->
        for (l in lines.indices) {
            val length = lines[l].text.length
            val pieces = if (length == 0) 1 else (length + pieceLengths[l] - 1) / pieceLengths[l]
            starts[l + 1] = starts[l] + pieces
        }
    }

    /** `rowStarts[l]` is the first row of line `l`; the last entry is the number of rows. */
    private val rowStarts = IntArray(lines.size + 1).also { rowStarts ->
        for (l in lines.indices) rowStarts[l + 1] = rowStarts[l] + rows(lines[l].text.length, perRow[l])
    }

    /** How many pieces — lazy items — there are. */
    val count: Int get() = starts[lines.size]

    /** How many rows the lines take. */
    val rowCount: Int get() = rowStarts[lines.size]

    /**
     * The piece at [index].
     *
     * @param index from 0 to [count] − 1.
     * @return the line the piece belongs to, the index of that line, and the piece's range
     *   in the line's text.
     */
    fun piece(index: Int): ConsoleValuePiece {
        val l = lineOf(starts, index)
        val line = lines[l]
        val start = (index - starts[l]) * pieceLengths[l]
        return ConsoleValuePiece(
            lineIndex = l,
            line = line,
            start = start,
            end = minOf(
                line.text.length,
                start + pieceLengths[l],
            ),
        )
    }

    /**
     * The first row of the piece at [index].
     *
     * @param index from 0 to [count] − 1.
     */
    fun firstRowOf(index: Int): Int {
        val l = lineOf(starts, index)
        return rowStarts[l] + (index - starts[l]) * (pieceLengths[l] / perRow[l])
    }

    /**
     * The piece that holds [row], and how many of its rows come before it.
     *
     * @param row any row; one before the first or past the last is taken as that end.
     * @return the piece's index and the row's place in it.
     */
    fun pieceAt(row: Int): ConsoleValueRowPosition {
        val clamped = row.coerceIn(0, rowCount - 1)
        val l = lineOf(rowStarts, clamped)
        val perPiece = pieceLengths[l] / perRow[l]
        val inLine = clamped - rowStarts[l]
        return ConsoleValueRowPosition(index = starts[l] + inLine / perPiece, rowInPiece = inLine % perPiece)
    }

    /** The line whose range in [bounds] holds [value]: the last `l` with `bounds[l] <= value`. */
    private fun lineOf(bounds: IntArray, value: Int): Int {
        var low = 0
        var high = lines.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (bounds[mid] <= value) low = mid else high = mid - 1
        }
        return low
    }

    private companion object {
        /** A whole number of rows, as many as fit in [VALUE_PIECE_MAX_CHARS]; one row when a row is longer. */
        fun pieceLength(perRow: Int): Int =
            if (perRow >= VALUE_PIECE_MAX_CHARS) perRow else VALUE_PIECE_MAX_CHARS / perRow * perRow

        /** Rows a text of [length] characters takes at [perRow] a row; an empty line is one row. */
        fun rows(length: Int, perRow: Int): Int = if (length == 0) 1 else (length + perRow - 1) / perRow
    }
}

/**
 * One lazy item of the value viewer.
 *
 * @property lineIndex index of [line] among the value's lines.
 * @property line the line the piece is cut from.
 * @property start first character of the piece in the line's text, inclusive.
 * @property end character after the piece's last, exclusive.
 */
internal data class ConsoleValuePiece(val lineIndex: Int, val line: ConsoleValueLine, val start: Int, val end: Int)

/**
 * Where a row of the value viewer falls among its lazy items.
 *
 * @property index the piece — lazy item — that holds the row.
 * @property rowInPiece how many of that piece's rows come before the row.
 */
internal data class ConsoleValueRowPosition(val index: Int, val rowInPiece: Int)
