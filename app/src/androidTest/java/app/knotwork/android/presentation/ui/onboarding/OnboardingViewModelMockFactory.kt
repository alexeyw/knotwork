@file:Suppress("ktlint:standard:filename", "MatchingDeclarationName")
// File hosts both the `mockOnboardingViewModel` factory function (primary
// export) and its sibling `OnboardingMockHandles` data class.

package app.knotwork.android.presentation.ui.onboarding

import app.knotwork.design.screens.onboarding.OnboardingViewState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Mutable mirror of [OnboardingViewModel.state] used by androidTest
 * scenarios to walk the screen through pager / download / warm-up phases
 * without re-stubbing the mock.
 */
internal class OnboardingMockHandles(val stateFlow: MutableStateFlow<OnboardingViewState>)

/**
 * Builds a relaxed [OnboardingViewModel] mock with [state] stubbed to a
 * deterministic starting value (defaults to `OnboardingViewState()`,
 * which lands on the Welcome step).
 */
internal fun mockOnboardingViewModel(
    initialState: OnboardingViewState = OnboardingViewState(),
): Pair<OnboardingViewModel, OnboardingMockHandles> {
    val stateFlow = MutableStateFlow(initialState)
    val vm = mockk<OnboardingViewModel>(relaxed = true)
    every { vm.state } returns stateFlow
    // The screen collects it; left to the relaxed mock, its value is a mock of Any that the screen
    // casts to an interruption and crashes on.
    every { vm.pendingInterruption } returns MutableStateFlow(null)
    every { vm.downloadError } returns MutableStateFlow(null)
    val handles = OnboardingMockHandles(stateFlow = stateFlow)
    return vm to handles
}
