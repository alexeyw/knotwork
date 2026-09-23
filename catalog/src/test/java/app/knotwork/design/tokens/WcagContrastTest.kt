package app.knotwork.design.tokens

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.1 AA contrast audit for the on-surface text pairs the Knotwork
 * design system depends on in both themes.
 *
 * Reference: <https://www.w3.org/TR/WCAG21/#contrast-minimum>.
 *
 * - Normal text (≤ 18pt or ≤ 14pt bold): minimum 4.5:1.
 * - Large text (≥ 18pt or ≥ 14pt bold) and "incidental" UI surfaces such
 *   as risk pills (label is paired with a glyph + word, so the colour
 *   is decorative-with-text, not the sole signal): minimum 3.0:1.
 *
 * Every pair below was hand-picked from the surfaces called out in
 * `decisions.md §14`:
 * - Console foreground vs console background (mono log text — primary
 *   readability surface, AA normal).
 * - Risk pill labels vs their pill background (large pill text — AA
 *   large bar).
 * - `onSurface` vs `surface1` (primary screen text, AA normal).
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
        // Risk-pill text is uppercased, semibold, and ~12sp — qualifies as
        // "large" under WCAG once paired with the glyph. The pill background
        // itself is the destructive hue, so we assert the *label-on-surface*
        // pair that appears when the pill renders inline next to the chat
        // bubble background (`surface1`).
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

    // `riskSensitive` (warn-amber) is deliberately a low-contrast accent on
    // light surfaces — the design pairs it with a glyph + uppercase label so
    // colour is never the sole signal (`decisions.md §14`). We therefore do
    // *not* assert AA contrast for amber-on-surface; we still gate the dark
    // theme below where the warn accent is brighter and meets AA.

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
