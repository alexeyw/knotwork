package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.R
import app.knotwork.android.domain.models.AppError
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.usecases.ModelLoadError
import app.knotwork.android.presentation.ui.common.UiText

/**
 * The chat's words for a failure the app did not word itself: the throwable's own
 * text, as the engine or provider wrote it, or a generic sentence when it has none.
 *
 * @param error What stopped the turn.
 * @return The banner text.
 */
internal fun failureText(error: Throwable): UiText =
    error.message?.let(UiText::Dynamic) ?: UiText(R.string.chat_error_unknown)

/**
 * The chat's words for a model that would not load before a send or a retry.
 *
 * The two failures a person can act on are worded in the interface language: no
 * active model, and a model file gone from the device. Any other failure is the
 * engine's own text.
 *
 * @param outcome The failed load.
 * @return The banner text.
 */
internal fun loadFailureText(outcome: Result.Error<*, AppError>): UiText = when (val error = outcome.error) {
    ModelLoadError.NoActiveModel -> UiText(R.string.chat_error_no_active_model)
    is ModelLoadError.FileMissing -> UiText.of(R.string.chat_error_model_file_missing, error.path)
    else -> outcome.message?.let(UiText::Dynamic) ?: UiText(R.string.chat_error_unknown)
}
