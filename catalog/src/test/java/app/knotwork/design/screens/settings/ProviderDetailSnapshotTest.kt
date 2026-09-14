package app.knotwork.design.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baselines for the provider detail screen.
 *
 * The screen had **none**, because its composition lived entirely in `:app`.
 * That is how it grew a look of its own — its own title style, a grey
 * explanatory paragraph, bare Material sliders — without anything noticing:
 * there was nothing to compare it against. These captures are that comparison.
 *
 * The Ollama states are not padding. Ollama is the one provider whose shape
 * differs — no API key, a base URL, a context window — and the cleartext-consent
 * banner is a state a person only meets on a home network, which is exactly the
 * kind that goes unlooked-at until someone reports it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ProviderDetailSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun provider_detail_key_light() = snapshot("key", dark = false) {
        ProviderDetailContent(state = SettingsPreview.providerDetail())
    }

    @Test
    fun provider_detail_key_dark() = snapshot("key", dark = true) {
        ProviderDetailContent(state = SettingsPreview.providerDetail())
    }

    @Test
    fun provider_detail_ollama_light() = snapshot("ollama", dark = false, expandRow = "Ollama") {
        ProviderDetailContent(state = SettingsPreview.providerDetailOllama())
    }

    @Test
    fun provider_detail_ollama_dark() = snapshot("ollama", dark = true, expandRow = "Ollama") {
        ProviderDetailContent(state = SettingsPreview.providerDetailOllama())
    }

    @Test
    fun provider_detail_cleartext_light() = snapshot("cleartext", dark = false, expandRow = "Ollama") {
        ProviderDetailContent(state = SettingsPreview.providerDetailCleartext())
    }

    @Test
    fun provider_detail_cleartext_dark() = snapshot("cleartext", dark = true, expandRow = "Ollama") {
        ProviderDetailContent(state = SettingsPreview.providerDetailCleartext())
    }

    @Test
    fun provider_detail_invalid_url_light() = snapshot("invalid_url", dark = false, expandRow = "Ollama") {
        ProviderDetailContent(state = SettingsPreview.providerDetailInvalidUrl())
    }

    /**
     * At 200 % the provider row's label and its trailing controls compete for one
     * line, and the retry sliders' value labels sit beside titles free to wrap.
     * Recording this is what keeps a clipped label from shipping unseen.
     */
    @Test
    fun provider_detail_font_scale_200_light() = snapshot("fontscale200", dark = false, fontScale = FONT_SCALE_200) {
        ProviderDetailContent(state = SettingsPreview.providerDetail())
    }

    @Test
    fun provider_detail_font_scale_200_dark() = snapshot("fontscale200", dark = true, fontScale = FONT_SCALE_200) {
        ProviderDetailContent(state = SettingsPreview.providerDetail())
    }

    /** The retry section's hint open — the panel has to have a baseline too. */
    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h1400dp-xhdpi")
    fun provider_detail_retry_hint_light() = snapshot("retry_hint", dark = false, openHint = "CLOUD_RETRY_POLICY") {
        ProviderDetailContent(state = SettingsPreview.providerDetail())
    }

    /**
     * The retry policy sits below the fold on a 760 dp screen. Captured on a
     * taller device rather than by scrolling: the section is static, and without
     * this it has no baseline at all — which is precisely how it grew its own
     * visual language the first time.
     */
    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h1400dp-xhdpi")
    fun provider_detail_retry_light() = snapshot("retry", dark = false) {
        ProviderDetailContent(state = SettingsPreview.providerDetail())
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h1400dp-xhdpi")
    fun provider_detail_retry_dark() = snapshot("retry", dark = true) {
        ProviderDetailContent(state = SettingsPreview.providerDetail())
    }

    /**
     * The picker. One state, because it has one — a fixed list of five rows with
     * no loading, empty or error branch to reach.
     */
    @Test
    fun provider_picker_light() = snapshot("picker", dark = false) {
        ProviderPickerContent(state = SettingsPreview.providerPicker())
    }

    @Test
    fun provider_picker_dark() = snapshot("picker", dark = true) {
        ProviderPickerContent(state = SettingsPreview.providerPicker())
    }

    @Test
    fun provider_picker_font_scale_200_light() =
        snapshot("picker_fontscale200", dark = false, fontScale = FONT_SCALE_200) {
            ProviderPickerContent(state = SettingsPreview.providerPicker())
        }

    private fun snapshot(
        name: String,
        dark: Boolean,
        fontScale: Float = 1f,
        openHint: String? = null,
        expandRow: String? = null,
        content: @Composable () -> Unit,
    ) {
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            // Without a hint controller in scope no row renders its help glyph,
            // and every baseline here would certify a screen that looks finished
            // while the affordance the rest of Settings has is in none of them.
            val hints = remember { SettingsHintController { anchor -> SNAPSHOT_HINTS[anchor] } }
            LaunchedEffect(openHint) { if (openHint != null) hints.toggle(openHint) }
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true, fontScale = fontScale),
                    LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
                    LocalSettingsHints provides hints,
                ) { content() }
            }
        }
        // The provider row owns its expanded flag internally and starts closed,
        // so a capture taken as-is shows a chevron and nothing else — the base
        // URL, the context window and the validation error would all be outside
        // the frame while the file name promised they were in it.
        expandRow?.let { composeTestRule.onNodeWithText(it).performClick() }
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/provider_detail_${name}_$themeTag.png",
        )
    }

    private companion object {
        const val FONT_SCALE_200: Float = 2f

        /**
         * Help text for the retry rows, matching what `:app` supplies.
         *
         * Hand-maintained, like the settings-category fixtures: this module
         * cannot see `:app`'s strings, and the point of the fixture is that the
         * glyph and the panel have a baseline at all.
         */
        val SNAPSHOT_HINTS: Map<String, SettingsHint> = mapOf(
            "CLOUD_RETRY_POLICY" to SettingsHint(
                "A failed cloud call is tried again before the run gives up.",
            ),
            "CLOUD_RETRY_MAX_ATTEMPTS" to SettingsHint("One attempt means a failure stops the run outright."),
            "CLOUD_RETRY_BASE_DELAY_MS" to SettingsHint("The wait before the first retry; later ones back off."),
        )
    }
}
