package app.knotwork.android.presentation.common

/**
 * Shared display formatters used by multiple screens, so byte sizes and token
 * estimates render identically across the app instead of drifting between
 * per-screen copies.
 */
object DisplayFormat {

    /** Bytes in one KiB; the divisor for the human byte-size ladder. */
    private const val BYTES_PER_KB: Double = 1_024.0

    /**
     * Approximate characters per token, for rough on-device token estimates.
     * A single source of truth so the same text reports the same token count
     * wherever it is shown.
     */
    const val CHARS_PER_TOKEN: Int = 4

    /**
     * Formats a byte count as a human-readable size (`"512 B"` / `"1.4 KB"` /
     * `"14.2 MB"` / `"2.1 GB"` in English) in the language of [units].
     *
     * @param bytes Non-negative byte count.
     * @param units Unit names and number style of the interface language.
     * @return The largest sensible unit with one decimal place.
     */
    fun formatBytes(bytes: Long, units: ByteUnits): String {
        if (bytes < BYTES_PER_KB) return units.inBytes(bytes.toString())
        val kb = bytes / BYTES_PER_KB
        if (kb < BYTES_PER_KB) return units.inKilobytes(units.number(kb, decimals = 1))
        val mb = kb / BYTES_PER_KB
        if (mb < BYTES_PER_KB) return units.inMegabytes(units.number(mb, decimals = 1))
        val gb = mb / BYTES_PER_KB
        return units.inGigabytes(units.number(gb, decimals = 1))
    }

    /**
     * Rough token-count estimate for [text] using [CHARS_PER_TOKEN].
     *
     * @return `text.length / CHARS_PER_TOKEN`, never negative.
     */
    fun approxTokenCount(text: String): Int = (text.length / CHARS_PER_TOKEN).coerceAtLeast(0)
}
