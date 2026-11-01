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
 * @param lines the formatted lines.
 * @param charsPerRow characters one row of a line holds, given the line — its indent takes
 *   part of the width; at least 1.
 */
internal class ConsoleValuePieces(private val lines: List<ConsoleValueLine>, charsPerRow: (ConsoleValueLine) -> Int) {

    /** Characters per piece, line by line. */
    private val pieceLengths = IntArray(lines.size) { pieceLength(charsPerRow(lines[it])) }

    /** `starts[l]` is the index of line `l`'s first piece; the last entry is the count. */
    private val starts = IntArray(lines.size + 1).also { starts ->
        for (l in lines.indices) {
            val length = lines[l].text.length
            val pieces = if (length == 0) 1 else (length + pieceLengths[l] - 1) / pieceLengths[l]
            starts[l + 1] = starts[l] + pieces
        }
    }

    /** How many pieces — lazy items — there are. */
    val count: Int get() = starts[lines.size]

    /**
     * The piece at [index].
     *
     * @param index from 0 to [count] − 1.
     * @return the line the piece belongs to, the index of that line, and the piece's range
     *   in the line's text.
     */
    fun piece(index: Int): ConsoleValuePiece {
        var low = 0
        var high = lines.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (starts[mid] <= index) low = mid else high = mid - 1
        }
        val line = lines[low]
        val start = (index - starts[low]) * pieceLengths[low]
        return ConsoleValuePiece(
            lineIndex = low,
            line = line,
            start = start,
            end = minOf(
                line.text.length,
                start + pieceLengths[low],
            ),
        )
    }

    private companion object {
        /** A whole number of rows, as many as fit in [VALUE_PIECE_MAX_CHARS]; one row when a row is longer. */
        fun pieceLength(perRow: Int): Int {
            val row = perRow.coerceAtLeast(1)
            return if (row >= VALUE_PIECE_MAX_CHARS) row else VALUE_PIECE_MAX_CHARS / row * row
        }
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
