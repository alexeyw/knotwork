package app.knotwork.design.tokens

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.1 AA contrast audit for the text colours the Knotwork design system
 * hands out, in both themes.
 *
 * Reference: <https://www.w3.org/TR/WCAG21/#contrast-minimum>.
 *
 * - Normal text: minimum 4.5:1. "Large" text — 18pt, or 14pt bold, i.e. about
 *   24 sp / 18.7 sp bold — may drop to 3:1. Nothing in the catalog that draws
 *   words in a risk or signal colour is that large: the pills are 11 sp.
 * - A glyph or a dot beside words is not the text: colour is then not the sole
 *   signal (1.4.1), but the words themselves still owe 4.5:1 (1.4.3). An earlier
 *   version of this file let the light risk accents pass as "large text paired
 *   with a glyph"; neither half held, and the light pills read at 2.3–3.9:1.
 *
 * So the light risk and signal accents are accents only, and every word drawn in
 * one of those hues uses its `…Text` token — pinned here on every surface step of
 * its theme. `assertNoAccentText()` (test sources) checks the rendered screens use them.
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
    fun `every risk and signal text tone meets AA normal on every light surface`() {
        val light = knotworkExtendedColorsLight()
        assertEveryTextToneReadsOn(
            colors = light,
            surfaces = listOf(KnotworkLight.Surface0, light.surface1, light.surface2, light.surface3, light.surface4),
            theme = "light",
        )
    }

    @Test
    fun `every risk and signal text tone meets AA normal on every dark surface`() {
        val dark = knotworkExtendedColorsDark()
        assertEveryTextToneReadsOn(
            colors = dark,
            surfaces = listOf(KnotworkDark.Surface0, dark.surface1, dark.surface2, dark.surface3, dark.surface4),
            theme = "dark",
        )
    }

    @Test
    fun `the M3 error role is a text colour on every light surface`() {
        // Material draws field labels, supporting text and destructive menu items in
        // `colorScheme.error`, so the role has to be readable as text.
        val light = knotworkExtendedColorsLight()
        listOf(KnotworkLight.Surface0, light.surface1, light.surface2, light.surface3, light.surface4)
            .forEachIndexed { step, surface ->
                assertContrastAtLeast(
                    foreground = knotworkLightColorScheme().error,
                    background = surface,
                    minimumRatio = AA_NORMAL_TEXT,
                    label = "colorScheme.error / surface$step (light)",
                )
            }
    }

    @Test
    fun `the light risk and signal accents are not text colours`() {
        // The reason the …Text tones exist. If an accent is ever re-tuned to read
        // as text on its own, this fails and the split can be revisited.
        val light = knotworkExtendedColorsLight()
        listOf(
            "signalWarn" to light.signalWarn,
            "signalError" to light.signalError,
            "signalSuccess" to light.signalSuccess,
            "riskReadonly" to light.riskReadonly,
        ).forEach { (name, accent) ->
            val ratio = contrastRatio(accent, light.surface2)
            assertTrue("$name / surface2 (light) is $ratio — no longer below AA", ratio < AA_NORMAL_TEXT)
        }
    }

    private fun assertEveryTextToneReadsOn(colors: KnotworkExtendedColors, surfaces: List<Color>, theme: String) {
        val tones = listOf(
            "riskReadonlyText" to colors.riskReadonlyText,
            "riskSensitiveText" to colors.riskSensitiveText,
            "riskDestructiveText" to colors.riskDestructiveText,
            "signalErrorText" to colors.signalErrorText,
            "signalSuccessText" to colors.signalSuccessText,
        )
        tones.forEach { (name, tone) ->
            surfaces.forEachIndexed { step, surface ->
                assertContrastAtLeast(
                    foreground = tone,
                    background = surface,
                    minimumRatio = AA_NORMAL_TEXT,
                    label = "$name / surface$step ($theme)",
                )
            }
        }
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
