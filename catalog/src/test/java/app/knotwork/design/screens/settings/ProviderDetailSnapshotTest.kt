package app.knotwork.design.screens.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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
 * Roborazzi baselines for the external providers: the list, the *Add provider* picker, each
 * provider's form in its states, the *Test connection* row and the model list.
 *
 * The screen had **none** while its composition lived in `:app`, which is how it grew a look of
 * its own unnoticed. The states here are the ones the design hand-off names: every field shape (a
 * hosted key with a built-in list, a server the user runs, a fixed address), every way an address
 * is refused while it is typed, every state of the test row, and 200 % font scale where the test
 * row and *Choose* move under their labels.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ProviderDetailSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // ─── A · the list and the picker ────────────────────────────────────────

    @Test
    fun settings_models_providers_eight_light() = snapshot("settings_models_providers_eight", dark = false) {
        ModelsSettingsContent(state = ProviderPreview.modelsProvidersEight())
    }

    @Test
    fun settings_models_providers_eight_dark() = snapshot("settings_models_providers_eight", dark = true) {
        ModelsSettingsContent(state = ProviderPreview.modelsProvidersEight())
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h1400dp-xhdpi")
    fun settings_models_providers_fontscale200_light() =
        snapshot("settings_models_providers_fontscale200", dark = false, fontScale = FONT_SCALE_200) {
            ModelsSettingsContent(state = ProviderPreview.modelsProvidersEight())
        }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h1400dp-xhdpi")
    fun settings_models_providers_fontscale200_dark() =
        snapshot("settings_models_providers_fontscale200", dark = true, fontScale = FONT_SCALE_200) {
            ModelsSettingsContent(state = ProviderPreview.modelsProvidersEight())
        }

    @Test
    fun provider_picker_eight_light() = snapshot("provider_picker_eight", dark = false) {
        ProviderPickerContent(state = ProviderPreview.providerPicker())
    }

    @Test
    fun provider_picker_eight_dark() = snapshot("provider_picker_eight", dark = true) {
        ProviderPickerContent(state = ProviderPreview.providerPicker())
    }

    @Test
    fun provider_picker_fontscale200_light() =
        snapshot("provider_picker_fontscale200", dark = false, fontScale = FONT_SCALE_200) {
            ProviderPickerContent(state = ProviderPreview.providerPicker())
        }

    // ─── B · provider forms ─────────────────────────────────────────────────

    @Test
    fun provider_detail_key_open_light() = detail("key_open", dark = false, ProviderPreview.providerDetail())

    @Test
    fun provider_detail_key_open_dark() = detail("key_open", dark = true, ProviderPreview.providerDetail())

    @Test
    fun provider_detail_key_refused_light() =
        detail("key_refused", dark = false, ProviderPreview.providerDetailKeyRefused())

    @Test
    fun provider_detail_key_refused_dark() =
        detail("key_refused", dark = true, ProviderPreview.providerDetailKeyRefused())

    @Test
    fun provider_detail_ollama_light() = detail("ollama", dark = false, ProviderPreview.providerDetailOllama())

    @Test
    fun provider_detail_ollama_dark() = detail("ollama", dark = true, ProviderPreview.providerDetailOllama())

    @Test
    fun provider_detail_invalid_url_light() =
        detail("invalid_url", dark = false, ProviderPreview.providerDetailInvalidUrl())

    @Test
    fun provider_detail_compat_empty_light() =
        detail("compat_empty", dark = false, ProviderPreview.providerDetailCompatEmpty())

    @Test
    fun provider_detail_compat_empty_dark() =
        detail("compat_empty", dark = true, ProviderPreview.providerDetailCompatEmpty())

    /**
     * At 200 % the field labels wrap, the markers follow them, and the test row's status and
     * button move under its label.
     */
    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h1400dp-xhdpi")
    fun provider_detail_compat_fontscale200_light() =
        detail("compat_fontscale200", dark = false, ProviderPreview.providerDetailCompatEmpty(), FONT_SCALE_200)

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h1400dp-xhdpi")
    fun provider_detail_compat_fontscale200_dark() =
        detail("compat_fontscale200", dark = true, ProviderPreview.providerDetailCompatEmpty(), FONT_SCALE_200)

    @Test
    fun provider_detail_compat_cleartext_light() =
        detail("compat_cleartext", dark = false, ProviderPreview.providerDetailCompatCleartext())

    @Test
    fun provider_detail_compat_cleartext_dark() =
        detail("compat_cleartext", dark = true, ProviderPreview.providerDetailCompatCleartext())

    @Test
    fun provider_detail_compat_refused_http_light() =
        detail("compat_refused_http", dark = false, ProviderPreview.providerDetailCompatRefusedHttp())

    @Test
    fun provider_detail_compat_refused_http_dark() =
        detail("compat_refused_http", dark = true, ProviderPreview.providerDetailCompatRefusedHttp())

    @Test
    fun provider_detail_compat_refused_block_light() =
        detail("compat_refused_block", dark = false, ProviderPreview.providerDetailCompatRefusedBlock())

    @Test
    fun provider_detail_compat_refused_block_dark() =
        detail("compat_refused_block", dark = true, ProviderPreview.providerDetailCompatRefusedBlock())

    @Test
    fun provider_detail_openrouter_empty_light() =
        detail("openrouter_empty", dark = false, ProviderPreview.providerDetailOpenRouterEmpty())

    @Test
    fun provider_detail_openrouter_empty_dark() =
        detail("openrouter_empty", dark = true, ProviderPreview.providerDetailOpenRouterEmpty())

    @Test
    fun provider_detail_openrouter_configured_light() =
        detail("openrouter_configured", dark = false, ProviderPreview.providerDetailOpenRouterConfigured())

    @Test
    fun provider_detail_openrouter_configured_dark() =
        detail("openrouter_configured", dark = true, ProviderPreview.providerDetailOpenRouterConfigured())

    /** At 200 % *Choose* moves under the model field, and a long id wraps rather than being cut. */
    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h1400dp-xhdpi")
    fun provider_detail_openrouter_fontscale200_light() = detail(
        "openrouter_fontscale200",
        dark = false,
        ProviderPreview.providerDetailOpenRouterConfigured(),
        FONT_SCALE_200,
    )

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h1400dp-xhdpi")
    fun provider_detail_openrouter_fontscale200_dark() = detail(
        "openrouter_fontscale200",
        dark = true,
        ProviderPreview.providerDetailOpenRouterConfigured(),
        FONT_SCALE_200,
    )

    @Test
    fun provider_detail_model_nolist_light() =
        detail("model_nolist", dark = false, ProviderPreview.providerDetailModelNoList())

    @Test
    fun provider_detail_model_nolist_dark() =
        detail("model_nolist", dark = true, ProviderPreview.providerDetailModelNoList())

    // ─── The test row ──────────────────────────────────────────────────────

    @Test
    fun provider_detail_test_running_light() =
        detail("test_running", dark = false, ProviderPreview.providerDetailTestRunning())

    @Test
    fun provider_detail_test_running_dark() =
        detail("test_running", dark = true, ProviderPreview.providerDetailTestRunning())

    @Test
    fun provider_detail_test_reachable_light() =
        detail("test_reachable", dark = false, ProviderPreview.providerDetailTestReachable())

    @Test
    fun provider_detail_test_reachable_dark() =
        detail("test_reachable", dark = true, ProviderPreview.providerDetailTestReachable())

    @Test
    fun provider_detail_test_failed_light() =
        detail("test_failed", dark = false, ProviderPreview.providerDetailTestFailed())

    @Test
    fun provider_detail_test_failed_dark() =
        detail("test_failed", dark = true, ProviderPreview.providerDetailTestFailed())

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h1400dp-xhdpi")
    fun provider_detail_test_failed_fontscale200_light() = detail(
        "test_failed_fontscale200",
        dark = false,
        ProviderPreview.providerDetailTestFailed(),
        FONT_SCALE_200,
    )

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h1400dp-xhdpi")
    fun provider_detail_test_failed_fontscale200_dark() = detail(
        "test_failed_fontscale200",
        dark = true,
        ProviderPreview.providerDetailTestFailed(),
        FONT_SCALE_200,
    )

    // ─── The model list ────────────────────────────────────────────────────

    @Test
    fun provider_detail_model_sheet_light() = snapshot("provider_detail_model_sheet", dark = false) {
        ModelSheet()
    }

    @Test
    fun provider_detail_model_sheet_dark() = snapshot("provider_detail_model_sheet", dark = true) {
        ModelSheet()
    }

    @Test
    fun provider_detail_model_sheet_fontscale200_light() =
        snapshot("provider_detail_model_sheet_fontscale200", dark = false, fontScale = FONT_SCALE_200) {
            ModelSheet()
        }

    @Test
    fun provider_detail_model_sheet_fontscale200_dark() =
        snapshot("provider_detail_model_sheet_fontscale200", dark = true, fontScale = FONT_SCALE_200) {
            ModelSheet()
        }

    // ─── Retry policy ──────────────────────────────────────────────────────

    /** The retry section's hint open — the panel has to have a baseline too. */
    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h1400dp-xhdpi")
    fun provider_detail_retry_hint_light() =
        snapshot("provider_detail_retry_hint", dark = false, openHint = "CLOUD_RETRY_POLICY") {
            ProviderDetailContent(state = ProviderPreview.providerDetail())
        }

    /**
     * The retry policy sits below the fold on a 760 dp screen. Captured on a taller device rather
     * than by scrolling: the section is static, and without this it has no baseline at all.
     */
    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h1400dp-xhdpi")
    fun provider_detail_retry_light() = detail("retry", dark = false, ProviderPreview.providerDetail())

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h1400dp-xhdpi")
    fun provider_detail_retry_dark() = detail("retry", dark = true, ProviderPreview.providerDetail())

    /** The model list as it opens over a form, searched for "llama-3.3". */
    @Composable
    private fun ModelSheet() {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            ModelPickerSheetContent(
                state = ProviderPreview.modelSheet(),
                onPick = {},
                initialQuery = "llama-3.3",
                focusSearch = false,
            )
        }
    }

    private fun detail(name: String, dark: Boolean, state: ProviderDetailViewState, fontScale: Float = 1f) =
        snapshot("provider_detail_$name", dark = dark, fontScale = fontScale) {
            ProviderDetailContent(state = state)
        }

    private fun snapshot(
        file: String,
        dark: Boolean,
        fontScale: Float = 1f,
        openHint: String? = null,
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
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/${file}_$themeTag.png",
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
