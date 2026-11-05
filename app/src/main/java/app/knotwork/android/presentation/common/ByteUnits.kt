package app.knotwork.android.presentation.common

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import app.knotwork.android.R
import java.util.Locale

/**
 * The words and number style a byte size is written in, for one language:
 * "14.2 MB" in English, "14,2 МБ" in Russian.
 *
 * The unit names are string resources and the number follows the language's
 * decimal separator, so every size the app shows reads in the interface
 * language. Before, each screen wrote "KB" and "MB" into its own code, half of
 * them with `Locale.US` numerals.
 *
 * Built from the current resources — in a composable with [rememberByteUnits],
 * elsewhere with [of]. Text that leaves the app for someone else to read (a
 * shared benchmark, a report) uses [ENGLISH]: exports stay in English.
 *
 * @property bytes Format of a size in bytes; `%1$s` is the number.
 * @property kilobytes Format of a size in KiB.
 * @property megabytes Format of a size in MiB.
 * @property gigabytes Format of a size in GiB.
 * @property locale Writes the number: decimal separator and digits.
 */
data class ByteUnits(
    val bytes: String,
    val kilobytes: String,
    val megabytes: String,
    val gigabytes: String,
    val locale: Locale,
) {

    /**
     * A size in bytes, already written as a number.
     *
     * @param number The number as text.
     * @return The size with its unit.
     */
    fun inBytes(number: String): String = String.format(locale, bytes, number)

    /** A size in KiB; see [inBytes]. */
    fun inKilobytes(number: String): String = String.format(locale, kilobytes, number)

    /** A size in MiB; see [inBytes]. */
    fun inMegabytes(number: String): String = String.format(locale, megabytes, number)

    /** A size in GiB; see [inBytes]. */
    fun inGigabytes(number: String): String = String.format(locale, gigabytes, number)

    /**
     * [value] with [decimals] digits after the separator of [locale].
     *
     * @param value The number.
     * @param decimals Digits after the decimal separator.
     * @return The number as text.
     */
    fun number(value: Double, decimals: Int): String = String.format(locale, "%.${decimals}f", value)

    /** The two ways to build one, and the English one exports use. */
    companion object {
        /** English units and numerals, for text that leaves the app (a shared benchmark). */
        val ENGLISH: ByteUnits = ByteUnits(
            bytes = "%1\$s B",
            kilobytes = "%1\$s KB",
            megabytes = "%1\$s MB",
            gigabytes = "%1\$s GB",
            locale = Locale.US,
        )

        /**
         * The units of [resources]' language.
         *
         * @param resources Resources in the interface language.
         * @return The units and the language's number style.
         */
        fun of(resources: Resources): ByteUnits = ByteUnits(
            bytes = resources.getString(R.string.unit_size_bytes),
            kilobytes = resources.getString(R.string.unit_size_kilobytes),
            megabytes = resources.getString(R.string.unit_size_megabytes),
            gigabytes = resources.getString(R.string.unit_size_gigabytes),
            locale = resources.configuration.locales[0],
        )
    }
}

/**
 * The [ByteUnits] of the current interface language, for a composable.
 *
 * @return The units, resolved through `stringResource` so a language change
 *   re-resolves them.
 */
@Composable
fun rememberByteUnits(): ByteUnits = ByteUnits(
    bytes = stringResource(R.string.unit_size_bytes),
    kilobytes = stringResource(R.string.unit_size_kilobytes),
    megabytes = stringResource(R.string.unit_size_megabytes),
    gigabytes = stringResource(R.string.unit_size_gigabytes),
    locale = LocalConfiguration.current.locales[0],
)
