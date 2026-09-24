package app.knotwork.android.presentation.ui.splash

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.R
import app.knotwork.android.domain.models.InitFailureKind
import app.knotwork.android.domain.models.InitProgress
import app.knotwork.android.domain.models.InitStage
import app.knotwork.android.domain.usecases.AppInitializationUseCase
import app.knotwork.android.domain.usecases.ResetLockedDatabaseUseCase
import app.knotwork.design.components.dialogs.typedConfirmMatches
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for [SplashScreen]. Subscribes to [AppInitializationUseCase] on
 * construction, maps each [InitProgress] into [SplashUiState], and exposes a
 * [retry] entry-point that re-runs the entire initialization pipeline after
 * a fatal failure.
 *
 * For the `DB_PASSPHRASE_UNAVAILABLE` failure kind it additionally drives the
 * data-locked recovery surface: a typed-confirm dialog gating
 * [ResetLockedDatabaseUseCase] (the recovery wipe), followed by an automatic restart
 * of the initialization pipeline against the now-empty data set.
 */
@HiltViewModel
class SplashViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val appInitializationUseCase: AppInitializationUseCase,
    private val resetLockedDatabaseUseCase: ResetLockedDatabaseUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SplashUiState())
    val uiState: StateFlow<SplashUiState> = _uiState.asStateFlow()

    private var initJob: Job? = null

    init {
        startInitialization()
    }

    /**
     * Starts (or restarts) the initialization sequence. Cancels any in-flight
     * job so [retry] cannot stack overlapping runs if the user taps the
     * button while a previous attempt is still emitting.
     */
    private fun startInitialization() {
        initJob?.cancel()
        _uiState.update { SplashUiState() }
        initJob = viewModelScope.launch {
            appInitializationUseCase().collect { progress ->
                _uiState.update { current -> current.merge(progress) }
            }
        }
    }

    /**
     * Re-runs initialization after a [InitStage.Failed] terminal state. No-op
     * while the splash is mid-flight or already done — the UI hides the
     * retry button in those cases anyway — and while a wipe is in flight:
     * `errorMessage` is still set during the wipe, so without the
     * [SplashUiState.isResetting] check a racing Retry tap would start an
     * initialization run concurrently with the wipe's file deletions.
     */
    fun retry() {
        val state = _uiState.value
        if (state.isResetting) return
        if (state.errorMessage == null && !state.isDone) return
        startInitialization()
    }

    /** Opens the typed-confirm reset dialog on the data-locked surface. */
    fun requestReset() {
        if (!_uiState.value.isDataLocked) return
        _uiState.update { it.copy(showResetDialog = true, resetTypedInput = "") }
    }

    /** Mirrors the live text of the typed-confirm field into the state. */
    fun updateResetTypedInput(value: String) {
        _uiState.update { it.copy(resetTypedInput = value) }
    }

    /** Closes the reset dialog without any action. */
    fun dismissResetDialog() {
        _uiState.update { it.copy(showResetDialog = false, resetTypedInput = "") }
    }

    /**
     * Executes the full data wipe and restarts initialization. Guarded twice:
     * the catalog dialog disables its confirm button until the typed keyword
     * matches, and this method re-validates the input so a programmatic call
     * can never bypass the confirmation contract.
     */
    fun confirmReset() {
        val state = _uiState.value
        if (!state.isDataLocked || state.isResetting) return
        val keyword = appContext.getString(R.string.destructive_typed_keyword)
        if (!typedConfirmMatches(input = state.resetTypedInput, keyword = keyword)) return

        initJob?.cancel()
        _uiState.update { it.copy(isResetting = true, showResetDialog = false) }
        viewModelScope.launch {
            try {
                resetLockedDatabaseUseCase()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Even a failed wipe should fall through to a fresh init attempt:
                // the next failure (if any) re-surfaces on the same screen.
                Timber.e(e, "Data wipe failed; re-running initialization anyway.")
            }
            startInitialization()
        }
    }
}

/**
 * Folds an [InitProgress] emission into a new [SplashUiState], translating
 * `InitStage` to UI-shaped flags. Pulled out as an extension so the
 * `SplashViewModel.startInitialization` body stays focused on lifecycle.
 */
private fun SplashUiState.merge(progress: InitProgress): SplashUiState {
    val fraction = if (progress.totalSteps > 0) {
        progress.completedSteps.toFloat() / progress.totalSteps.toFloat()
    } else {
        0f
    }
    return when (val stage = progress.stage) {
        is InitStage.Done -> SplashUiState(
            message = progress.message,
            progressFraction = 1f,
            isDone = true,
            errorMessage = null,
        )
        is InitStage.Failed -> SplashUiState(
            message = progress.message,
            progressFraction = fraction,
            isDone = false,
            errorMessage = stage.cause,
            isDataLocked = stage.failureKind == InitFailureKind.DB_PASSPHRASE_UNAVAILABLE,
        )
        else -> SplashUiState(
            message = progress.message,
            progressFraction = fraction,
            isDone = false,
            errorMessage = null,
        )
    }
}
