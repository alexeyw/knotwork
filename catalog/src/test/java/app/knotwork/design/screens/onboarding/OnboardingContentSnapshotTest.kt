package app.knotwork.design.screens.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.assertNoAccentText
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi snapshot baseline for the scenario `OnboardingContent` across the
 * value-gallery, motivated-download, and ready states, in both themes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class OnboardingContentSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun onboarding_welcome_light() = snapshot(name = "welcome", dark = false) {
        OnboardingContent(state = OnboardingPreview.welcome())
    }

    @Test
    fun onboarding_welcome_dark() = snapshot(name = "welcome", dark = true) {
        OnboardingContent(state = OnboardingPreview.welcome())
    }

    @Test
    fun onboarding_scenario_light() = snapshot(name = "scenario", dark = false) {
        OnboardingContent(state = OnboardingPreview.chooseScenario())
    }

    @Test
    fun onboarding_scenario_dark() = snapshot(name = "scenario", dark = true) {
        OnboardingContent(state = OnboardingPreview.chooseScenario())
    }

    @Test
    fun onboarding_scenario_picked_light() = snapshot(name = "scenario_picked", dark = false) {
        OnboardingContent(state = OnboardingPreview.chooseScenarioPicked())
    }

    @Test
    fun onboarding_scenario_picked_dark() = snapshot(name = "scenario_picked", dark = true) {
        OnboardingContent(state = OnboardingPreview.chooseScenarioPicked())
    }

    @Test
    fun onboarding_download_light() = snapshot(name = "download", dark = false) {
        OnboardingContent(state = OnboardingPreview.download())
    }

    @Test
    fun onboarding_download_dark() = snapshot(name = "download", dark = true) {
        OnboardingContent(state = OnboardingPreview.download())
    }

    @Test
    fun onboarding_downloading_light() = snapshot(name = "downloading", dark = false) {
        OnboardingContent(state = OnboardingPreview.downloading())
    }

    @Test
    fun onboarding_downloading_dark() = snapshot(name = "downloading", dark = true) {
        OnboardingContent(state = OnboardingPreview.downloading())
    }

    @Test
    fun onboarding_download_error_light() = snapshot(name = "download_error", dark = false) {
        OnboardingContent(state = OnboardingPreview.downloadError())
    }

    @Test
    fun onboarding_download_error_dark() = snapshot(name = "download_error", dark = true) {
        OnboardingContent(state = OnboardingPreview.downloadError())
    }

    @Test
    fun onboarding_download_custom_url_light() = snapshot(name = "download_custom_url", dark = false) {
        OnboardingContent(state = OnboardingPreview.downloadCustomUrl())
    }

    @Test
    fun onboarding_download_custom_url_dark() = snapshot(name = "download_custom_url", dark = true) {
        OnboardingContent(state = OnboardingPreview.downloadCustomUrl())
    }

    @Test
    fun onboarding_download_installed_light() = snapshot(name = "download_installed", dark = false) {
        OnboardingContent(state = OnboardingPreview.downloadInstalled())
    }

    @Test
    fun onboarding_download_installed_dark() = snapshot(name = "download_installed", dark = true) {
        OnboardingContent(state = OnboardingPreview.downloadInstalled())
    }

    @Test
    fun onboarding_ready_warming_light() = snapshot(name = "ready_warming", dark = false) {
        OnboardingContent(state = OnboardingPreview.readyWarming())
    }

    @Test
    fun onboarding_ready_warming_dark() = snapshot(name = "ready_warming", dark = true) {
        OnboardingContent(state = OnboardingPreview.readyWarming())
    }

    @Test
    fun onboarding_ready_checking_light() = snapshot(name = "ready_checking_acceleration", dark = false) {
        OnboardingContent(state = OnboardingPreview.readyCheckingAcceleration())
    }

    @Test
    fun onboarding_ready_checking_dark() = snapshot(name = "ready_checking_acceleration", dark = true) {
        OnboardingContent(state = OnboardingPreview.readyCheckingAcceleration())
    }

    @Test
    fun onboarding_ready_styled_light() = snapshot(name = "ready_styled", dark = false) {
        OnboardingContent(state = OnboardingPreview.readyStyled())
    }

    @Test
    fun onboarding_ready_styled_dark() = snapshot(name = "ready_styled", dark = true) {
        OnboardingContent(state = OnboardingPreview.readyStyled())
    }

    @Test
    fun onboarding_ready_share_light() = snapshot(name = "ready_share", dark = false) {
        OnboardingContent(state = OnboardingPreview.readyShare())
    }

    @Test
    fun onboarding_ready_share_dark() = snapshot(name = "ready_share", dark = true) {
        OnboardingContent(state = OnboardingPreview.readyShare())
    }

    @Test
    fun onboarding_ready_warm_error_light() = snapshot(name = "ready_warm_error", dark = false) {
        OnboardingContent(state = OnboardingPreview.readyWarmError())
    }

    @Test
    fun onboarding_ready_warm_error_dark() = snapshot(name = "ready_warm_error", dark = true) {
        OnboardingContent(state = OnboardingPreview.readyWarmError())
    }

    /**
     * The scenario step at the "Largest" text preset. `StepHeadline` clamps the
     * headline's own scale off `KnotworkTheme.a11y.fontScale()`, so this is the
     * only capture that proves the clamp engages rather than the headline
     * simply riding `LocalDensity` like everything else.
     */
    @Test
    fun choose_scenario_font_scale_2x_light() =
        snapshot(name = "choose_scenario_font_scale_2x", dark = false, fontScale = LARGE_FONT_SCALE) {
            OnboardingContent(state = OnboardingPreview.chooseScenario())
        }

    private fun snapshot(name: String, dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            // Inside the theme on purpose: it is the placement that survives the
            // theme ever provisioning `LocalKnotworkA11y` again.
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true, fontScale = fontScale),
                    LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
                ) {
                    content()
                }
            }
        }
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/onboarding_${name}_$themeTag.png",
        )
    }

    private companion object {
        /** The "Largest" system text-size preset every layout must survive. */
        const val LARGE_FONT_SCALE = 2.0f
    }
}

/** Internal preview fixtures backing the onboarding snapshot suite. */
internal object OnboardingPreview {

    fun welcome(): OnboardingViewState = OnboardingViewState(step = OnboardingStep.Welcome)

    fun chooseScenario(): OnboardingViewState = OnboardingViewState(step = OnboardingStep.ChooseScenario)

    fun chooseScenarioPicked(): OnboardingViewState = OnboardingViewState(
        step = OnboardingStep.ChooseScenario,
        selectedScenario = OnboardingScenario.StyledTranslation,
        liteRtModel = OnboardingLiteRtModel.Gemma4E4B,
    )

    fun download(): OnboardingViewState = OnboardingViewState(
        step = OnboardingStep.Download,
        selectedScenario = OnboardingScenario.StyledTranslation,
        liteRtModel = OnboardingLiteRtModel.Gemma4E4B,
    )

    fun downloading(): OnboardingViewState = download().copy(downloadProgress = DOWNLOAD_PROGRESS_FIXTURE)

    fun downloadError(): OnboardingViewState = download().copy(downloadError = DOWNLOAD_ERROR_FIXTURE)

    fun downloadCustomUrl(): OnboardingViewState = download().copy(
        liteRtModel = OnboardingLiteRtModel.CustomUrl,
        customDownloadUrl = CUSTOM_URL_FIXTURE,
    )

    fun downloadInstalled(): OnboardingViewState = download().copy(
        installedModelId = OnboardingLiteRtModel.Gemma4E4B.id,
    )

    /** Ready — Virtual Companion, model still warming. */
    fun readyWarming(): OnboardingViewState = OnboardingViewState(
        step = OnboardingStep.Ready,
        selectedScenario = OnboardingScenario.VirtualCompanion,
        liteRtModel = OnboardingLiteRtModel.Gemma4E4B,
        scenarioPreview = companionPreview(),
        installedModelId = OnboardingLiteRtModel.Gemma4E4B.id,
        isModelWarmed = false,
    )

    /** Ready — the host is verifying this device's hardware acceleration. */
    fun readyCheckingAcceleration(): OnboardingViewState = readyWarming().copy(
        isCheckingAcceleration = true,
    )

    /** Ready — Styled Translation, warmed. */
    fun readyStyled(): OnboardingViewState = OnboardingViewState(
        step = OnboardingStep.Ready,
        selectedScenario = OnboardingScenario.StyledTranslation,
        liteRtModel = OnboardingLiteRtModel.Gemma4E4B,
        scenarioPreview = styledPreview(),
        installedModelId = OnboardingLiteRtModel.Gemma4E4B.id,
        isModelWarmed = true,
    )

    /** Ready — Share Handler, warmed (shows the entry-surface + safety rows). */
    fun readyShare(): OnboardingViewState = OnboardingViewState(
        step = OnboardingStep.Ready,
        selectedScenario = OnboardingScenario.ShareHandler,
        liteRtModel = OnboardingLiteRtModel.Gemma4E4B,
        scenarioPreview = sharePreview(),
        installedModelId = OnboardingLiteRtModel.Gemma4E4B.id,
        isModelWarmed = true,
    )

    /** Ready — warm-up failed: error banner + retry CTA, not a silent dead-end. */
    fun readyWarmError(): OnboardingViewState = OnboardingViewState(
        step = OnboardingStep.Ready,
        selectedScenario = OnboardingScenario.StyledTranslation,
        liteRtModel = OnboardingLiteRtModel.Gemma4E4B,
        scenarioPreview = styledPreview(),
        installedModelId = OnboardingLiteRtModel.Gemma4E4B.id,
        isModelWarmed = false,
        downloadError = "Couldn't load the model. Check storage and retry.",
    )

    private const val DOWNLOAD_PROGRESS_FIXTURE: Float = 0.42f
    private const val DOWNLOAD_ERROR_FIXTURE: String = "Connection lost. Retry?"
    private const val CUSTOM_URL_FIXTURE: String = "https://huggingface.co/example/model.litertlm"

    private fun styledPreview(): OnboardingDefaultPipelinePreview = OnboardingDefaultPipelinePreview(
        nodes = listOf("INPUT", "LITE_RT", "OUTPUT"),
        nodeCount = 3,
        edgeCount = 2,
    )

    private fun sharePreview(): OnboardingDefaultPipelinePreview = OnboardingDefaultPipelinePreview(
        nodes = listOf("INPUT", "LITE_RT", "TOOL", "OUTPUT"),
        nodeCount = 4,
        edgeCount = 3,
    )

    private fun companionPreview(): OnboardingDefaultPipelinePreview = OnboardingDefaultPipelinePreview(
        nodes = listOf(
            "INPUT", "LITE_RT", "INTENT_ROUTER",
            "LITE_RT", "LITE_RT", "LITE_RT", "LITE_RT", "LITE_RT", "OUTPUT",
        ),
        nodeCount = 9,
        edgeCount = 12,
        accentNodeName = "INTENT_ROUTER",
    )
}
