package app.knotwork.android.presentation.ui.splash

import app.knotwork.android.domain.models.InitStage

/**
 * Render state of [SplashScreen].
 *
 * Mirrors the latest `InitProgress` snapshot from `AppInitializationUseCase`,
 * normalised for direct UI consumption (progress fraction, error/done flags).
 *
 * @property stage The step in flight, named by the screen in the interface
 *   language; `null` before the first emission lands.
 * @property progressFraction Determinate progress in `0f..1f` derived from
 *   `completedSteps / totalSteps`. `1f` once the run reaches [InitStage.Done].
 * @property isDone `true` after the use case successfully reaches
 *   [InitStage.Done]; the activity navigates to the home graph.
 * @property errorMessage Non-null while the use case is in the [InitStage.Failed]
 *   terminal state. Drives the inline error text + retry button.
 * @property isDataLocked `true` when the failure was classified as
 *   `InitFailureKind.DB_PASSPHRASE_UNAVAILABLE` — the screen then renders the
 *   dedicated recovery surface (Retry + typed-confirm full reset) instead of
 *   the generic error layout.
 * @property showResetDialog `true` while the typed-confirm reset dialog is
 *   open on the recovery surface.
 * @property resetTypedInput Live text of the typed-confirm field inside the
 *   reset dialog.
 * @property isResetting `true` while the wipe + restart round-trip is in
 *   flight; gates re-entrant confirm taps.
 */
data class SplashUiState(
    val stage: InitStage? = null,
    val progressFraction: Float = 0f,
    val isDone: Boolean = false,
    val errorMessage: String? = null,
    val isDataLocked: Boolean = false,
    val showResetDialog: Boolean = false,
    val resetTypedInput: String = "",
    val isResetting: Boolean = false,
)
