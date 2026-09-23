package app.knotwork.design.tokens

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrast checks for a few colour pairs of the Knotwork design system, in both
 * themes, measured with the WCAG 2.1 formula.
 *
 * Reference: <https://www.w3.org/TR/WCAG21/#contrast-minimum>. AA asks 4.5:1 of
 * normal text and 3:1 of large text — at least 18 pt, or 14 pt bold (about
 * 24 sp, or 18.7 sp bold).
 *
 * What the checks below claim, and what they do not:
 * - The console foreground on the console background is held to 4.5:1, the AA
 *   bar for normal text, which the monospace log is.
 * - The risk pill labels are 11 sp, so under WCAG they are normal text, and AA
 *   would ask 4.5:1 of them. In the light theme the risk hues do not reach that
 *   (destructive 3.7:1, sensitive 2.2:1 on `surface1`); they are kept as they
 *   are as a design choice. The 3:1 used below is a floor that stops them from
 *   getting any fainter, not a claim that they pass AA.
 * - Each pill shows its risk as a word beside a coloured dot, so colour is not
 *   the only thing that tells the risks apart (WCAG 1.4.1). That is a separate
 *   requirement from the label's own contrast (1.4.3), and it does not lower it.
 */
class WcagContrastTest {
    @Test
    fun `console foreground meets AA normal on console background — light theme`() {
        val light = knotworkExtendedColorsLight()
        assertContrastAtLeast(
            foreground = light.consoleFg,
            background = light.consoleBg,
            minimumRatio = AA_NORMAL_TEXT,
            label = "consoleFg / consoleBg (light)",
        )
    }

    @Test
    fun `console foreground meets AA normal on console background — dark theme`() {
        val dark = knotworkExtendedColorsDark()
        assertContrastAtLeast(
            foreground = dark.consoleFg,
            background = dark.consoleBg,
            minimumRatio = AA_NORMAL_TEXT,
            label = "consoleFg / consoleBg (dark)",
        )
    }

    @Test
    fun `risk destructive label meets AA large on surface1 — light theme`() {
        val light = knotworkExtendedColorsLight()
        // Measured against `surface1`, the card background the pill sits on in
        // chat. 3:1 is a floor for the accent, not an AA pass: an 11 sp label is
        // normal text and would need 4.5:1 (see the class KDoc).
        assertContrastAtLeast(
            foreground = light.riskDestructive,
            background = light.surface1,
            minimumRatio = AA_LARGE_TEXT,
            label = "riskDestructive / surface1 (light)",
        )
    }

    @Test
    fun `risk destructive label meets AA large on surface1 — dark theme`() {
        val dark = knotworkExtendedColorsDark()
        assertContrastAtLeast(
            foreground = dark.riskDestructive,
            background = dark.surface1,
            minimumRatio = AA_LARGE_TEXT,
            label = "riskDestructive / surface1 (dark)",
        )
    }

    // No light-theme check for `riskSensitive`: the amber is 2.2:1 on
    // `surface1`, below even the 3:1 floor used here, and is kept as it is by
    // design. The dark theme's amber is brighter and is gated below.

    @Test
    fun `risk sensitive label meets AA large on surface1 — dark theme`() {
        val dark = knotworkExtendedColorsDark()
        assertContrastAtLeast(
            foreground = dark.riskSensitive,
            background = dark.surface1,
            minimumRatio = AA_LARGE_TEXT,
            label = "riskSensitive / surface1 (dark)",
        )
    }

    private fun assertContrastAtLeast(foreground: Color, background: Color, minimumRatio: Double, label: String) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            "$label contrast $ratio < required $minimumRatio",
            ratio >= minimumRatio,
        )
    }

    /**
     * Computes the WCAG 2.1 contrast ratio between [foreground] and
     * [background], using the relative-luminance formula from
     * <https://www.w3.org/TR/WCAG21/#dfn-relative-luminance>.
     *
     * Both colours are assumed opaque. Alpha-blended colours should be
     * resolved against their target background before being passed in —
     * the relative-luminance formula is defined only on opaque sRGB
     * triples.
     */
    private fun contrastRatio(foreground: Color, background: Color): Double {
        val lf = relativeLuminance(foreground)
        val lb = relativeLuminance(background)
        val lighter = maxOf(lf, lb)
        val darker = minOf(lf, lb)
        return (lighter + LUMINANCE_OFFSET) / (darker + LUMINANCE_OFFSET)
    }

    /** sRGB relative luminance — Y component of CIE 1931 XYZ at D65. */
    private fun relativeLuminance(color: Color): Double {
        val r = linearise(color.red.toDouble())
        val g = linearise(color.green.toDouble())
        val b = linearise(color.blue.toDouble())
        return RED_COEFFICIENT * r + GREEN_COEFFICIENT * g + BLUE_COEFFICIENT * b
    }

    /** Inverse sRGB companding — converts a 0–1 sRGB channel to linear light. */
    private fun linearise(channel: Double): Double = if (channel <= SRGB_LINEAR_THRESHOLD) {
        channel / SRGB_LINEAR_DIVISOR
    } else {
        Math.pow((channel + SRGB_GAMMA_OFFSET) / SRGB_GAMMA_DIVISOR, SRGB_GAMMA_EXPONENT)
    }

    private companion object {
        /** WCAG 2.1 AA threshold for "normal" body text. */
        const val AA_NORMAL_TEXT = 4.5

        /** WCAG 2.1 AA threshold for large text / non-decorative UI. */
        const val AA_LARGE_TEXT = 3.0

        /** Contrast-ratio offset preventing division-by-zero on pure black. */
        const val LUMINANCE_OFFSET = 0.05

        /** Per CIE 1931 — Y(red) coefficient for D65 sRGB. */
        const val RED_COEFFICIENT = 0.2126

        /** Per CIE 1931 — Y(green) coefficient for D65 sRGB. */
        const val GREEN_COEFFICIENT = 0.7152

        /** Per CIE 1931 — Y(blue) coefficient for D65 sRGB. */
        const val BLUE_COEFFICIENT = 0.0722

        /** sRGB low-channel cutoff used by the WCAG linearisation formula. */
        const val SRGB_LINEAR_THRESHOLD = 0.03928

        /** Divisor for the linear branch of inverse sRGB companding. */
        const val SRGB_LINEAR_DIVISOR = 12.92

        /** Offset added before the gamma branch of inverse sRGB companding. */
        const val SRGB_GAMMA_OFFSET = 0.055

        /** Divisor for the gamma branch of inverse sRGB companding. */
        const val SRGB_GAMMA_DIVISOR = 1.055

        /** Exponent of the gamma branch of inverse sRGB companding. */
        const val SRGB_GAMMA_EXPONENT = 2.4
    }
}
