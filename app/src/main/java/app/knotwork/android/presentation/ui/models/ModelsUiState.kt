package app.knotwork.android.presentation.ui.models

import app.knotwork.android.domain.models.AppError
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.models.ModelPerformanceSummary
import app.knotwork.android.domain.usecases.BenchmarkReport
import app.knotwork.android.domain.usecases.BenchmarkRunPhase

/**
 * Represents the UI state for the Models management screen.
 *
 * @property availablePresets A list of pre-defined model URLs (e.g., Gemma 2B, 7B).
 * @property downloadedModels A list of models currently saved in the local database.
 * @property activeModel The currently active (selected) model, if any.
 * @property downloadProgress The progress percentage of the current download (0..100), or null if not downloading.
 * @property isDownloading True if a download is currently in progress.
 * @property downloadError Any error that occurred during the download process, or null.
 * @property customUrlInput The current text in the custom URL input field.
 * @property authTokenInput The current authorization token for HuggingFace downloads.
 */
data class ModelsUiState(
    val availablePresets: List<ModelPreset> = listOf(
        ModelPreset(
            "Gemma-4-E4B-it",
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        ),
        ModelPreset(
            "Gemma-4-E2B-it",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        ),
        // ModelPreset("Qwen3.5-0.8B", "https://huggingface.co/g-ntovas/Qwen3.5-0.8B-LiteRT/resolve/main/qwen35_mm_q8_ekv2048.litertlm"),
        // ModelPreset("Qwen3.5-2B", "https://huggingface.co/g-ntovas/Qwen3.5-2B-LiteRT/resolve/main/qwen35_2b_mm_q4_block32_ekv4096.litertlm")
    ),
    val downloadedModels: List<LocalModel> = emptyList(),
    val activeModel: LocalModel? = null,
    val downloadProgress: Int? = null,
    val isDownloading: Boolean = false,
    val downloadError: AppError? = null,
    val customUrlInput: String = "",
    val authTokenInput: String = "",
    /**
     * Filename of the in-flight download (e.g. `gemma-4-E4B-it.litertlm`), or
     * `null` if no download is active. Lets the UI render the per-preset
     * progress on the *right* row instead of attaching the bar to the field
     * section.
     */
    val activeDownloadFileName: String? = null,
    /**
     * Wire key of the active local-model backend
     * ([app.knotwork.android.domain.models.LocalBackend.key]) — `cpu` /
     * `gpu` / `npu`. Read from `GenerationSettings.localModelBackend`
     * and rendered as part of the per-model meta line. Defaults to
     * `cpu` until the first observation lands.
     */
    val localBackendKey: String = "cpu",
    /**
     * Rolling-average performance summary for the active model, or `null` when
     * the active model has no recorded runs yet (the card shows "no runs yet").
     */
    val performanceSummary: ModelPerformanceSummary? = null,
    /**
     * Live state of the controlled benchmark. [BenchmarkUiState.Idle] when no
     * benchmark is running or its result has been dismissed.
     */
    val benchmark: BenchmarkUiState = BenchmarkUiState.Idle,
    /**
     * `true` while a pipeline run holds the single inference engine. The
     * Performance card then shows a calm "busy with a task" notice instead of
     * the Run-benchmark action (a benchmark can't seize the engine mid-run).
     * Observed live from the task queue's global state.
     */
    val engineBusy: Boolean = false,
)

/**
 * Presentation state of the model benchmark, layered over the rolling-average
 * Performance card.
 */
sealed interface BenchmarkUiState {
    /** No benchmark running; the card shows the rolling average (or empty state). */
    data object Idle : BenchmarkUiState

    /**
     * A benchmark is in progress.
     *
     * @property phase Which timed phase is currently running.
     */
    data class Running(val phase: BenchmarkRunPhase) : BenchmarkUiState

    /**
     * The benchmark finished; the inline one-shot report is shown until dismissed.
     *
     * @property report The measured figures.
     */
    data class Result(val report: BenchmarkReport) : BenchmarkUiState
}

/**
 * Represents a predefined model download option.
 *
 * @property name The human-readable name of the preset.
 * @property url The direct download URL for the preset model.
 */
data class ModelPreset(val name: String, val url: String)
