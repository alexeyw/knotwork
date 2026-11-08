package app.knotwork.android.presentation.ui.models

import app.knotwork.android.presentation.common.ByteUnits

/** Bytes per MiB. */
private const val MEGABYTE = 1024.0 * 1024.0

/** Bytes per GiB. */
private const val GIGABYTE = MEGABYTE * 1024.0

/**
 * MiB value at or above which the label switches to GiB. Set to the rounding
 * boundary of the `"%.0f MB"` format (1023.5 MiB rounds to "1024 MB") so the
 * label never prints "1024 MB" — anything that would round to 1024 MiB is shown
 * as "1.0 GB" instead.
 */
private const val GB_DISPLAY_THRESHOLD_MB = 1023.5

/**
 * Formats a positive byte count as `"1.8 GB"` (one decimal) at or above ~1 GiB,
 * else `"640 MB"` (whole MiB). The single source for the Models surface's
 * GiB/MiB ladder, shared by model-size labels and the Performance card's peak
 * memory so the two cannot drift. Units and numerals are those of [units].
 *
 * Callers handle the non-positive case themselves (the model-size label renders
 * "0 GB"; the performance label renders nothing), so this function assumes
 * `bytes > 0`.
 *
 * @param bytes A positive byte count.
 * @param units Unit names and number style of the interface language.
 * @return The formatted GiB or MiB label.
 */
internal fun gigabyteOrMegabyteLabel(bytes: Long, units: ByteUnits): String {
    val megabytes = bytes / MEGABYTE
    return if (megabytes >= GB_DISPLAY_THRESHOLD_MB) {
        units.inGigabytes(units.number(bytes / GIGABYTE, decimals = 1))
    } else {
        units.inMegabytes(units.number(megabytes, decimals = 0))
    }
}
