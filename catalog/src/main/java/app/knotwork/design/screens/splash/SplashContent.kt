package app.knotwork.design.screens.splash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.brand.KnotworkLogo
import app.knotwork.design.components.brand.KnotworkLogoSize
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.dialogs.TypedConfirmDialog
import app.knotwork.design.components.dialogs.TypedConfirmDialogState
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Maximum content width of the splash column — keeps the layout readable on tablets. */
private val SplashMaxWidth = 360.dp

/**
 * Stateless Knotwork splash surface. Renders the brand mark + app name +
 * determinate progress bar + status label; on error, swaps the indicator
 * for an inline error message and Retry CTA.
 *
 * @param appName brand text rendered under the logo.
 * @param state render state — drives loading vs. error layout.
 * @param modifier optional layout modifier applied to the root box.
 * @param callbacks one-shot callback bundle (Retry).
 */
@Composable
fun SplashContent(
    appName: String,
    state: SplashViewState,
    modifier: Modifier = Modifier,
    retryLabel: String = SPLASH_RETRY_LABEL,
    callbacks: SplashCallbacks = noopSplashCallbacks(),
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = SplashMaxWidth)
                .padding(horizontal = KnotworkTheme.spacing.sp8),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp6),
        ) {
            KnotworkLogo(size = KnotworkLogoSize.Lg)
            Text(
                text = appName,
                style = KnotworkTextStyles.Display2xl,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            when (state) {
                SplashViewState.Initializing -> SplashProgress(progress = 0f, message = "")
                is SplashViewState.Loading -> SplashProgress(progress = state.progress, message = state.message)
                is SplashViewState.Error -> SplashError(
                    message = state.message,
                    retryLabel = retryLabel,
                    onRetry = callbacks.onRetry,
                )
                is SplashViewState.DataLocked -> SplashDataLocked(
                    state = state,
                    retryLabel = retryLabel,
                    callbacks = callbacks,
                )
            }
        }
    }
    if (state is SplashViewState.DataLocked && state.resetDialog != null) {
        SplashResetConfirmDialog(
            payload = state.resetDialog,
            onInputChange = callbacks.onResetInputChange,
            onConfirm = callbacks.onResetConfirm,
            onDismiss = callbacks.onResetDismiss,
        )
    }
}

@Composable
private fun SplashProgress(progress: Float, message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier.fillMaxWidth(),
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = KnotworkTheme.extended.surface2,
            strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
        )
        if (message.isNotEmpty()) {
            Text(
                text = message,
                style = KnotworkTextStyles.BodyBase,
                color = KnotworkTheme.extended.onSurfaceMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SplashError(message: String, retryLabel: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp4),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = KnotworkTextStyles.BodyBase,
            color = KnotworkTheme.extended.signalError,
            textAlign = TextAlign.Center,
        )
        KnotworkPrimaryButton(text = retryLabel, onClick = onRetry)
    }
}

/**
 * Recovery surface for [SplashViewState.DataLocked]: headline, plain-language
 * explanation, a primary Retry CTA and a destructive secondary reset action.
 * The reset itself is armed only through [SplashResetConfirmDialog].
 */
@Composable
private fun SplashDataLocked(state: SplashViewState.DataLocked, retryLabel: String, callbacks: SplashCallbacks) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp4),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = state.title,
            style = KnotworkTextStyles.TitleMd,
            color = KnotworkTheme.extended.signalError,
            textAlign = TextAlign.Center,
        )
        Text(
            text = state.body,
            style = KnotworkTextStyles.BodyBase,
            color = KnotworkTheme.extended.onSurfaceMuted,
            textAlign = TextAlign.Center,
        )
        KnotworkPrimaryButton(
            text = retryLabel,
            onClick = callbacks.onRetry,
            modifier = Modifier.testTag(SPLASH_DATA_LOCKED_RETRY_TEST_TAG),
        )
        KnotworkSecondaryButton(
            text = state.resetLabel,
            onClick = callbacks.onResetRequest,
            destructive = true,
            modifier = Modifier.testTag(SPLASH_DATA_LOCKED_RESET_TEST_TAG),
        )
    }
}

/**
 * Typed-confirm dialog gating the irreversible full data reset. Thin wrapper
 * over the shared [TypedConfirmDialog], so the splash and Settings destructive
 * confirmations share one interaction contract.
 */
@Composable
private fun SplashResetConfirmDialog(
    payload: SplashResetDialogState,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    TypedConfirmDialog(
        state = TypedConfirmDialogState(
            title = payload.title,
            body = payload.body,
            keyword = payload.keyword,
            hint = payload.hint,
            pendingInput = payload.pendingInput,
        ),
        confirmLabel = payload.confirmLabel,
        cancelLabel = payload.cancelLabel,
        onInputChange = onInputChange,
        onConfirm = onConfirm,
        onCancel = onDismiss,
        fieldTestTag = SPLASH_RESET_TYPED_FIELD_TEST_TAG,
        confirmTestTag = SPLASH_RESET_CONFIRM_BUTTON_TEST_TAG,
    )
}

/** Default Retry button label used by catalog previews / snapshots. */
private const val SPLASH_RETRY_LABEL = "Retry"

/** Test tag of the Retry button on the data-locked recovery surface. */
const val SPLASH_DATA_LOCKED_RETRY_TEST_TAG: String = "splash_data_locked_retry"

/** Test tag of the destructive reset button on the data-locked recovery surface. */
const val SPLASH_DATA_LOCKED_RESET_TEST_TAG: String = "splash_data_locked_reset"

/** Test tag of the typed-confirm input inside the reset dialog. */
const val SPLASH_RESET_TYPED_FIELD_TEST_TAG: String = "splash_reset_typed_field"

/** Test tag of the confirm button inside the reset dialog. */
const val SPLASH_RESET_CONFIRM_BUTTON_TEST_TAG: String = "splash_reset_confirm_button"
