package app.knotwork.android.presentation.ui.splash

import app.knotwork.android.R
import app.knotwork.android.domain.models.InitStage
import app.knotwork.design.screens.splash.SplashViewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the `SplashUiState.toViewState(...)` mapper that bridges the
 * app-side cold-start projection to the catalog `SplashViewState` consumed
 * by `SplashContent`.
 */
class SplashStateMappingTest {

    private val defaultMessage = "Starting…"

    private val dataLockedTexts = DataLockedTexts(
        title = "Your data can’t be unlocked",
        body = "Explanation",
        resetLabel = "Erase all data",
        dialogTitle = "Erase all data?",
        dialogBody = "Type RESET to confirm.",
        dialogHint = "Type RESET to confirm",
        dialogConfirm = "Erase everything",
        dialogCancel = "Cancel",
        keyword = "RESET",
    )

    private fun map(state: SplashUiState): SplashViewState = state.toViewState(
        defaultMessage = defaultMessage,
        stageText = { stage -> "stage:${stage::class.simpleName}" },
        dataLockedTexts = dataLockedTexts,
    )

    @Test
    fun `given pristine ui state when mapped then Initializing`() {
        val state = SplashUiState()

        val result = map(state)

        assertEquals(SplashViewState.Initializing, result)
    }

    @Test
    fun `given in-flight progress when mapped then Loading names the stage`() {
        val state = SplashUiState(
            stage = InitStage.LoadingChats,
            progressFraction = 0.42f,
        )

        val result = map(state)

        assertTrue(result is SplashViewState.Loading)
        result as SplashViewState.Loading
        assertEquals("stage:LoadingChats", result.message)
        assertEquals(0.42f, result.progress, 0.001f)
    }

    @Test
    fun `given default message but non-zero progress when mapped then Loading with localised default`() {
        val state = SplashUiState(progressFraction = 0.10f)

        val result = map(state)

        assertTrue(result is SplashViewState.Loading)
        assertEquals(defaultMessage, (result as SplashViewState.Loading).message)
    }

    @Test
    fun `given out-of-range progress when mapped then coerced into 0_1f`() {
        val state = SplashUiState(stage = InitStage.LoadingMemory, progressFraction = 2f)

        val result = map(state)

        assertEquals(1f, (result as SplashViewState.Loading).progress, 0.001f)
    }

    @Test
    fun `given error message when mapped then Error wins over loading`() {
        val state = SplashUiState(
            stage = InitStage.LoadingMemory,
            progressFraction = 0.8f,
            errorMessage = "model file missing",
        )

        val result = map(state)

        assertTrue(result is SplashViewState.Error)
        assertEquals("model file missing", (result as SplashViewState.Error).message)
    }

    @Test
    fun `given data-locked state when mapped then DataLocked wins over generic error`() {
        val state = SplashUiState(
            errorMessage = "Database passphrase unavailable",
            isDataLocked = true,
        )

        val result = map(state)

        assertTrue(result is SplashViewState.DataLocked)
        result as SplashViewState.DataLocked
        assertEquals(dataLockedTexts.title, result.title)
        assertEquals(dataLockedTexts.body, result.body)
        assertEquals(dataLockedTexts.resetLabel, result.resetLabel)
        assertNull(result.resetDialog)
    }

    @Test
    fun `given data-locked state with open dialog when mapped then dialog payload carries typed input`() {
        val state = SplashUiState(
            errorMessage = "Database passphrase unavailable",
            isDataLocked = true,
            showResetDialog = true,
            resetTypedInput = "RES",
        )

        val result = map(state)

        result as SplashViewState.DataLocked
        assertNotNull(result.resetDialog)
        assertEquals("RES", result.resetDialog?.pendingInput)
        assertEquals("RESET", result.resetDialog?.keyword)
        assertEquals(dataLockedTexts.dialogTitle, result.resetDialog?.title)
    }

    @Test
    fun `given resetting state when mapped then Loading instead of DataLocked`() {
        val state = SplashUiState(
            errorMessage = "Database passphrase unavailable",
            isDataLocked = true,
            isResetting = true,
        )

        val result = map(state)

        assertTrue(result is SplashViewState.Loading)
    }

    @Test
    fun `given every stage when labelled then each reads its own resource`() {
        val stages = listOf(
            InitStage.Initializing,
            InitStage.FindingModels,
            InitStage.LoadingPipelines,
            InitStage.LoadingChats,
            InitStage.LoadingMemory,
            InitStage.Done,
        )

        val labels = stages.map(::stageLabel)

        assertEquals(stages.size, labels.toSet().size)
        assertEquals(R.string.splash_stage_pipelines, stageLabel(InitStage.Failed("x", InitStage.LoadingPipelines)))
    }
}
