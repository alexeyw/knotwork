package app.knotwork.android.presentation.ui.onboarding

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.knotwork.design.screens.onboarding.OnboardingLiteRtModel
import app.knotwork.design.screens.onboarding.OnboardingStep
import app.knotwork.design.screens.onboarding.OnboardingViewState
import io.mockk.verify
import org.junit.Rule
import org.junit.Test
import app.knotwork.design.R as KnotworkR

/**
 * Pins the LiteRT model step CTA gate. The CTA label
 * and enablement flip across three sub-states:
 *
 *  * No install + no in-flight download → enabled `Download Gemma 4 E2B`
 *    tap fires [OnboardingViewModel.startDownload].
 *  * Download in flight → disabled `Downloading…`.
 *  * Installed → enabled `Continue` tap fires [OnboardingViewModel.next].
 *
 * The Ready step's `Open chat` CTA is also gated on `isModelWarmed`; a
 * sanity assertion covers that branch.
 */
class OnboardingScreenDownloadGateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun liteRtStep_noInstall_noDownload_downloadCtaEnabled_andStartsDownload() {
        val (vm, _) = mockOnboardingViewModel(
            initialState = OnboardingViewState(
                step = OnboardingStep.Download,
                liteRtModel = OnboardingLiteRtModel.Gemma4E2B,
            ),
        )

        composeTestRule.setContent {
            MaterialTheme {
                OnboardingScreen(
                    onOpenDocument = {},
                    onCompleted = {},
                    viewModel = vm,
                )
            }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val downloadCta = ctx.getString(
            KnotworkR.string.knotwork_onboarding_models_download_cta,
            OnboardingLiteRtModel.Gemma4E2B.displayName,
        )

        composeTestRule.onNodeWithText(text = downloadCta).assertIsEnabled()
        composeTestRule.onNodeWithText(text = downloadCta).performClick()

        verify(exactly = 1) { vm.startDownload() }
    }

    @Test
    fun liteRtStep_downloadInProgress_ctaDisabledAndLabelChanges() {
        val (vm, _) = mockOnboardingViewModel(
            initialState = OnboardingViewState(
                step = OnboardingStep.Download,
                liteRtModel = OnboardingLiteRtModel.Gemma4E2B,
                downloadProgress = HALF_PROGRESS,
            ),
        )

        composeTestRule.setContent {
            MaterialTheme {
                OnboardingScreen(
                    onOpenDocument = {},
                    onCompleted = {},
                    viewModel = vm,
                )
            }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val downloadingCta = ctx.getString(KnotworkR.string.knotwork_onboarding_models_downloading_cta)

        composeTestRule.onNodeWithText(text = downloadingCta).assertIsDisplayed()
        composeTestRule.onNodeWithText(text = downloadingCta).assertIsNotEnabled()
    }

    @Test
    fun liteRtStep_modelInstalled_continueCtaEnabled_andAdvances() {
        val (vm, _) = mockOnboardingViewModel(
            initialState = OnboardingViewState(
                step = OnboardingStep.Download,
                liteRtModel = OnboardingLiteRtModel.Gemma4E2B,
                installedModelId = OnboardingLiteRtModel.Gemma4E2B.id,
            ),
        )

        composeTestRule.setContent {
            MaterialTheme {
                OnboardingScreen(
                    onOpenDocument = {},
                    onCompleted = {},
                    viewModel = vm,
                )
            }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val continueCta = ctx.getString(KnotworkR.string.knotwork_onboarding_models_continue_cta)

        composeTestRule.onNodeWithText(text = continueCta).assertIsEnabled()
        composeTestRule.onNodeWithText(text = continueCta).performClick()

        verify(exactly = 1) { vm.next() }
    }

    @Test
    fun readyStep_modelNotWarmed_openChatDisabled() {
        val (vm, _) = mockOnboardingViewModel(
            initialState = OnboardingViewState(
                step = OnboardingStep.Ready,
                installedModelId = OnboardingLiteRtModel.Gemma4E2B.id,
                isModelWarmed = false,
            ),
        )

        composeTestRule.setContent {
            MaterialTheme {
                OnboardingScreen(
                    onOpenDocument = {},
                    onCompleted = {},
                    viewModel = vm,
                )
            }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val openChat = ctx.getString(KnotworkR.string.knotwork_onboarding_ready_preparing_cta)

        composeTestRule.onNodeWithText(text = openChat).assertIsNotEnabled()
    }

    private companion object {
        const val HALF_PROGRESS: Float = 0.5f
    }
}
