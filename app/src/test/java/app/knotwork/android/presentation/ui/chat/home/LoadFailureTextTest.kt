package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.R
import app.knotwork.android.domain.models.AppError
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.usecases.ModelLoadError
import app.knotwork.android.presentation.ui.common.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chat banner's words for a failure: the two load failures a person can act
 * on are the app's sentences in the interface language, everything else is the
 * engine's own text, and a failure with no text reads as unknown.
 */
class LoadFailureTextTest {

    private object EngineError : AppError.System

    @Test
    fun `given no active model when worded then it points to the Models screen`() {
        val outcome = Result.Error<Unit, AppError>(ModelLoadError.NoActiveModel, message = "No active model found.")

        assertEquals(UiText(R.string.chat_error_no_active_model), loadFailureText(outcome))
    }

    @Test
    fun `given a missing model file when worded then the path goes into the sentence`() {
        val outcome = Result.Error<Unit, AppError>(ModelLoadError.FileMissing("/m.litertlm"), message = "gone")

        assertEquals(UiText.of(R.string.chat_error_model_file_missing, "/m.litertlm"), loadFailureText(outcome))
    }

    @Test
    fun `given an engine failure when worded then its own text is shown`() {
        val outcome = Result.Error<Unit, AppError>(EngineError, message = "GPU delegate refused the model")

        assertEquals(UiText.Dynamic("GPU delegate refused the model"), loadFailureText(outcome))
    }

    @Test
    fun `given an engine failure without text when worded then it reads as unknown`() {
        assertEquals(UiText(R.string.chat_error_unknown), loadFailureText(Result.Error<Unit, AppError>(EngineError)))
    }

    @Test
    fun `given a thrown error when worded then its message is shown, or unknown without one`() {
        assertEquals(UiText.Dynamic("boom"), failureText(IllegalStateException("boom")))
        assertEquals(UiText(R.string.chat_error_unknown), failureText(IllegalStateException()))
    }
}
