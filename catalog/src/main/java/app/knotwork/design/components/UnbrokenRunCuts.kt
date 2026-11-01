package app.knotwork.design.components

import androidx.compose.ui.text.AnnotatedString

/** Most characters of one unbroken run a single text is given; a longer run continues in the next text. */
internal const val UNBROKEN_RUN_MAX = 2_000

/**
 * Where to cut [text] so that no piece holds more than [max] characters of one unbroken
 * run. A run is broken by whitespace — but not by a non-breaking space — and by a Han,
 * Hiragana or Katakana character; anything else continues it, line-break opportunities
 * such as `-`, `/` or U+200B included.
 *
 * Laying out a text costs time in the square of its longest unbroken run: measured on the
 * host, 200 000 characters of minified JSON take 4.9 s in one text and 0.2 s cut every
 * 2 000, while prose with spaces and Chinese or Japanese text stay linear, and hyphens,
 * slashes or zero-width spaces do not help (`project_docs/play-reports-2026-11-01-data`).
 * Text without such a run is never cut, so ordinary text renders exactly as before.
 *
 * @param text the text to cut.
 * @param max the most characters of one run a piece may hold; at least 2, so a cut never
 *   has to split a surrogate pair.
 * @return the offsets to cut at, ascending, each strictly between 0 and the length and on
 *   a code point boundary; empty when the text needs no cut.
 */
internal fun unbrokenRunCuts(text: CharSequence, max: Int = UNBROKEN_RUN_MAX): List<Int> {
    require(max >= 2) { "max must be at least 2" }
    val cuts = ArrayList<Int>()
    var run = 0
    var i = 0
    while (i < text.length) {
        val codePoint = Character.codePointAt(text, i)
        val size = Character.charCount(codePoint)
        if (breaksRun(codePoint)) {
            run = 0
        } else {
            if (run + size > max) {
                cuts += i
                run = 0
            }
            run += size
        }
        i += size
    }
    return cuts
}

/** [this] cut at [unbrokenRunCuts]; the text alone when it needs no cut. */
internal fun String.splitAtUnbrokenRuns(max: Int = UNBROKEN_RUN_MAX): List<String> =
    piecesAt(unbrokenRunCuts(this, max), length) { start, end -> substring(start, end) }

/** [this] cut at [unbrokenRunCuts], each piece keeping its part of every style and link. */
internal fun AnnotatedString.splitAtUnbrokenRuns(max: Int = UNBROKEN_RUN_MAX): List<AnnotatedString> =
    piecesAt(unbrokenRunCuts(text, max), length) { start, end -> subSequence(start, end) }

private inline fun <T> piecesAt(cuts: List<Int>, length: Int, piece: (Int, Int) -> T): List<T> {
    if (cuts.isEmpty()) return listOf(piece(0, length))
    val bounds = listOf(0) + cuts + length
    return bounds.zipWithNext { start, end -> piece(start, end) }
}

/** Whether [codePoint] ends an unbroken run: whitespace other than a non-breaking space, or a CJK ideograph or kana. */
private fun breaksRun(codePoint: Int): Boolean = Character.isWhitespace(codePoint) ||
    when (Character.UnicodeScript.of(codePoint)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
        else -> false
    }
