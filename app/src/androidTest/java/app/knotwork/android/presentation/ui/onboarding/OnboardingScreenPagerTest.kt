package app.knotwork.android.presentation.ui.onboarding

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.knotwork.design.screens.onboarding.OnboardingScenario
import app.knotwork.design.screens.onboarding.OnboardingStep
import app.knotwork.design.screens.onboarding.OnboardingViewState
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import app.knotwork.design.R as KnotworkR

/**
 * Covers the 4-step pager wiring on the Onboarding
 * surface: the Welcome step renders the brand title + Continue CTA, the
 * primary CTA on each step forwards to the matching ViewModel call, and
 * the top-bar Skip CTA invokes [OnboardingViewModel.skipOnboarding] plus
 * the screen's `onCompleted` lambda.
 */
class OnboardingScreenPagerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun initialState_rendersWelcomeStepHeadline() {
        val (vm, _) = mockOnboardingViewModel(initialState = OnboardingViewState())

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
        val welcomeHeadline = ctx.getString(KnotworkR.string.knotwork_onboarding_welcome_headline)

        composeTestRule.onNodeWithText(text = welcomeHeadline).assertIsDisplayed()
    }

    @Test
    fun welcomeContinueCta_tap_invokesNext() {
        val (vm, _) = mockOnboardingViewModel(initialState = OnboardingViewState())

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
        val continueLabel = ctx.getString(KnotworkR.string.knotwork_onboarding_continue)

        composeTestRule.onNodeWithText(text = continueLabel).performClick()

        verify(exactly = 1) { vm.next() }
    }

    @Test
    fun skipCta_tap_invokesSkipOnboarding_andCallsCompleted() {
        val (vm, _) = mockOnboardingViewModel(initialState = OnboardingViewState())
        var completed = 0

        composeTestRule.setContent {
            MaterialTheme {
                OnboardingScreen(
                    onOpenDocument = {},

                    onCompleted = { completed += 1 },
                    viewModel = vm,
                )
            }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val skipLabel = ctx.getString(KnotworkR.string.knotwork_onboarding_skip)

        composeTestRule.onNodeWithText(text = skipLabel).performClick()

        verify(exactly = 1) { vm.skipOnboarding() }
        // JUnit assertion — Kotlin `assert` is a no-op without `-ea`,
        // which Android instrumentation runs do not enable.
        assertEquals("onCompleted should fire exactly once on skip", 1, completed)
    }

    @Test
    fun readyStep_skipCtaHidden_finishCtaInvokesFinishOnboarding() {
        val (vm, _) = mockOnboardingViewModel(
            initialState = OnboardingViewState(
                step = OnboardingStep.Ready,
                installedModelId = "gemma_4_e2b",
                isModelWarmed = true,
            ),
        )
        var completed = 0

        composeTestRule.setContent {
            MaterialTheme {
                OnboardingScreen(
                    onOpenDocument = {},

                    onCompleted = { completed += 1 },
                    viewModel = vm,
                )
            }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val skipLabel = ctx.getString(KnotworkR.string.knotwork_onboarding_skip)
        val openChatLabel = ctx.getString(KnotworkR.string.knotwork_onboarding_ready_open_generic_cta)

        // Skip is suppressed on the final step — committing happens via the
        // primary CTA only.
        composeTestRule.onNodeWithText(text = skipLabel).assertDoesNotExist()

        composeTestRule.onNodeWithText(text = openChatLabel).performClick()

        verify(exactly = 1) { vm.finishOnboarding() }
        assertEquals("onCompleted should fire exactly once on finish", 1, completed)
    }

    @Test
    fun scenarioStep_tapCard_picksScenario_andSetUpCtaMaterialises() {
        val (vm, _) = mockOnboardingViewModel(
            initialState = OnboardingViewState(
                step = OnboardingStep.ChooseScenario,
                selectedScenario = OnboardingScenario.StyledTranslation,
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
        val companionTitle = ctx.getString(KnotworkR.string.knotwork_onboarding_scenario_companion_title)
        val setUpCta = ctx.getString(
            KnotworkR.string.knotwork_onboarding_scenario_cta_setup,
            ctx.getString(KnotworkR.string.knotwork_onboarding_scenario_translate_title),
        )

        // Tapping a scenario card records the pick.
        composeTestRule.onNodeWithText(text = companionTitle).performClick()
        verify(exactly = 1) { vm.pickScenario(OnboardingScenario.VirtualCompanion) }

        // The "Set up {scenario}" CTA materialises the picked scenario.
        composeTestRule.onNodeWithText(text = setUpCta).performClick()
        verify(exactly = 1) { vm.setUpScenario() }
    }
}
