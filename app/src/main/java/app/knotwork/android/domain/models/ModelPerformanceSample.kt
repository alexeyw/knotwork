package app.knotwork.android.domain.models

import app.knotwork.android.domain.constants.TimeAndIdConstants

/**
 * A single recorded inference measurement for one local model.
 *
 * Rows are produced both by ordinary pipeline runs (the LITE_RT node executor
 * records one per generation) and by the controlled benchmark
 * ([app.knotwork.android.domain.usecases.RunBenchmarkUseCase], [isBenchmark] =
 * `true`). The model's Performance card averages the most recent
 * [app.knotwork.android.domain.constants.PerformanceConstants.SAMPLE_WINDOW]
 * rows for the active model.
 *
 * Samples are keyed by [modelPath] (the concrete on-disk path of the model that
 * actually ran, read back from the engine after load) rather than a model row
 * id, so attribution never depends on resolving the "Active model" sentinel and
 * survives unchanged if the model row is edited.
 *
 * @property id Row id (`0` for an unsaved sample; assigned by Room on insert).
 *   Also the ordering key for the rolling window — higher id means more recent.
 * @property modelPath Absolute on-disk path of the model that produced the run.
 * @property ttftMs Time-to-first-token in milliseconds: the gap between the
 *   start of stream consumption (after the model is loaded) and the first
 *   emitted token. Model-load time is deliberately excluded. `0` when the run
 *   produced no tokens.
 * @property decodeTokensPerSec Decode throughput in tokens per second over the
 *   generation window (first token → last token). `0` when fewer than two
 *   tokens were produced or the window was zero-length.
 * @property totalMs Total inference wall-clock in milliseconds (start of stream
 *   consumption → last token), excluding model-load time.
 * @property tokenCount Number of tokens the run produced.
 * @property peakNativeHeapBytes Peak process-wide native-heap allocation
 *   observed across the inference window, in bytes. Approximate — see
 *   [app.knotwork.android.domain.services.NativeMemorySampler]. `0` when no
 *   sample was taken (e.g. a run that produced no tokens).
 * @property isBenchmark `true` when produced by the controlled benchmark,
 *   `false` for an ordinary pipeline run.
 * @property createdAt Epoch-millis the sample was recorded. Used only for
 *   display/share; the rolling window orders by [id].
 */
data class ModelPerformanceSample(
    val id: Long = 0,
    val modelPath: String,
    val ttftMs: Long,
    val decodeTokensPerSec: Float,
    val totalMs: Long,
    val tokenCount: Int,
    val peakNativeHeapBytes: Long,
    val isBenchmark: Boolean,
    val createdAt: Long,
) {
    /** Factory for deriving a sample from raw streamed-generation timings. */
    companion object {
        /**
         * Builds a sample from the raw timing primitives captured around a
         * streamed generation. Centralises the TTFT / decode-speed arithmetic so
         * the LITE_RT executor and the benchmark use case derive identical
         * figures from the same inputs.
         *
         * @param modelPath Concrete on-disk path of the model that ran.
         * @param inferenceStartMs `System.currentTimeMillis()` captured just
         *   before stream consumption began (after model load).
         * @param firstTokenAtMs `System.currentTimeMillis()` at the first emitted
         *   token, or `0` when no token was produced.
         * @param endMs `System.currentTimeMillis()` after the stream completed.
         * @param tokenCount Tokens produced by the run.
         * @param peakNativeHeapBytes Peak native-heap allocation observed in the
         *   window.
         * @param isBenchmark Whether this run was the controlled benchmark.
         * @param createdAt Epoch-millis to stamp the sample with.
         * @return The computed [ModelPerformanceSample].
         */
        fun fromTimings(
            modelPath: String,
            inferenceStartMs: Long,
            firstTokenAtMs: Long,
            endMs: Long,
            tokenCount: Int,
            peakNativeHeapBytes: Long,
            isBenchmark: Boolean,
            createdAt: Long,
        ): ModelPerformanceSample {
            val hasFirstToken = firstTokenAtMs > 0L
            val ttftMs = if (hasFirstToken) (firstTokenAtMs - inferenceStartMs).coerceAtLeast(0L) else 0L
            val totalMs = (endMs - inferenceStartMs).coerceAtLeast(0L)
            // Decode speed measures steady-state generation, so it spans the
            // first token to the last — the prefill latency already lives in
            // TTFT. Need at least two tokens (one decode interval) and a
            // non-zero window for the rate to be meaningful.
            val decodeWindowMs = endMs - firstTokenAtMs
            val decodeTokensPerSec = if (hasFirstToken && decodeWindowMs > 0L && tokenCount > 1) {
                (tokenCount - 1).toFloat() / (decodeWindowMs.toFloat() / TimeAndIdConstants.MS_PER_SECOND.toFloat())
            } else {
                0f
            }
            return ModelPerformanceSample(
                modelPath = modelPath,
                ttftMs = ttftMs,
                decodeTokensPerSec = decodeTokensPerSec,
                totalMs = totalMs,
                tokenCount = tokenCount,
                peakNativeHeapBytes = peakNativeHeapBytes,
                isBenchmark = isBenchmark,
                createdAt = createdAt,
            )
        }
    }
}

/**
 * Rolling-average performance figures for one model, derived on the fly from
 * its most recent [ModelPerformanceSample] rows. Surfaced by
 * [app.knotwork.android.domain.usecases.GetModelPerformanceUseCase] and rendered
 * in the model's Performance card.
 *
 * @property sampleCount Number of runs averaged (1..window size). Always `>= 1`
 *   — an empty history yields `null` rather than a zero-count summary.
 * @property avgTtftMs Mean time-to-first-token in milliseconds across the window.
 * @property avgDecodeTokensPerSec Mean decode throughput in tokens per second.
 * @property peakNativeHeapBytes Largest peak-native-heap value seen in the
 *   window, in bytes (the worst case, not an average of peaks). `0` when no
 *   sample in the window recorded a memory reading.
 */
data class ModelPerformanceSummary(
    val sampleCount: Int,
    val avgTtftMs: Long,
    val avgDecodeTokensPerSec: Float,
    val peakNativeHeapBytes: Long,
) {
    /** Factory for folding a window of samples into a single summary. */
    companion object {
        /**
         * Folds a window of samples into a [ModelPerformanceSummary], or returns
         * `null` when [samples] is empty (the card then shows its "no runs yet"
         * empty state).
         *
         * @param samples The most-recent samples for one model (already limited
         *   to the rolling window by the caller). Order does not matter.
         * @return The averaged summary, or `null` when [samples] is empty.
         */
        fun from(samples: List<ModelPerformanceSample>): ModelPerformanceSummary? {
            if (samples.isEmpty()) return null
            // Exclude degenerate runs from the metric means: a blank generation
            // (no tokens) has ttft = 0, and a single-token generation has
            // decode = 0. Folding those zeros in would systematically drag the
            // displayed averages below the model's real performance. Each metric
            // averages only over the samples that actually measured it; the peak
            // is the worst case across every sample.
            val ttftSamples = samples.filter { it.ttftMs > 0L }
            val decodeSamples = samples.filter { it.decodeTokensPerSec > 0f }
            return ModelPerformanceSummary(
                sampleCount = samples.size,
                avgTtftMs = if (ttftSamples.isEmpty()) 0L else ttftSamples.sumOf { it.ttftMs } / ttftSamples.size,
                avgDecodeTokensPerSec = if (decodeSamples.isEmpty()) {
                    0f
                } else {
                    decodeSamples.map { it.decodeTokensPerSec }.average().toFloat()
                },
                peakNativeHeapBytes = samples.maxOf { it.peakNativeHeapBytes },
            )
        }
    }
}
