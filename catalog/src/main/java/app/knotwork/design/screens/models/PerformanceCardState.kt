package app.knotwork.design.screens.models

/**
 * State of the model **Performance card** rendered below the Active-model card.
 *
 * The card shows, for the active model, the rolling-average TTFT / decode speed
 * / peak memory and hosts the controlled benchmark. All numeric values arrive
 * **pre-formatted** as display strings (e.g. `"420 ms"`, `"12.4 tok/s"`,
 * `"1.8 GB"`): formatting and localisation live in the host so the catalog stays
 * free of unit/number-format logic.
 *
 * The host collapses the model's performance history and the live benchmark
 * sub-state into exactly one of these variants.
 */
sealed interface PerformanceCardState {

    /** No model is active — a calm "select a model first" hint; card stays visible. */
    data object NoModel : PerformanceCardState

    /** A model is active but has no recorded runs yet — empty state + Run benchmark. */
    data object Empty : PerformanceCardState

    /**
     * A pipeline run currently holds the engine, so a benchmark can't start —
     * calm "busy with a task" message (mirrors the voice-input busy notice).
     */
    data object EngineBusy : PerformanceCardState

    /**
     * Rolling averages over the model's most recent runs.
     *
     * @property ttft formatted time-to-first-token (e.g. `"420 ms"` / `"1.2 s"`).
     * @property decode formatted decode speed (e.g. `"12.4 tok/s"`).
     * @property peakMemory formatted peak memory (e.g. `"1.8 GB"` / `"640 MB"`),
     *   or `null` when unavailable (renders `"—"`).
     * @property sampleCaption right-aligned caption (e.g. `"avg · last 8 runs"`).
     */
    data class Populated(val ttft: String, val decode: String, val peakMemory: String?, val sampleCaption: String) :
        PerformanceCardState

    /**
     * A benchmark is in progress.
     *
     * @property phase which of the two steps is running (warm-up vs measured).
     */
    data class Running(val phase: BenchmarkPhase) : PerformanceCardState

    /**
     * The inline one-shot benchmark report, shown after a run completes until
     * the user dismisses it with "Done".
     *
     * @property ttft formatted time-to-first-token of the measured run.
     * @property decode formatted decode speed of the measured run.
     * @property total formatted total inference time (e.g. `"2.6 s"`).
     * @property peakMemory formatted peak memory, or `null` when unavailable.
     */
    data class Result(val ttft: String, val decode: String, val total: String, val peakMemory: String?) :
        PerformanceCardState
}

/** The two timed steps of a benchmark run, surfaced in the running state. */
enum class BenchmarkPhase {
    /** Warm-up run — timings discarded; "Warming up…". */
    WarmingUp,

    /** Measured run — the timed pass; "Measuring…". */
    Measuring,
}

/**
 * Localised strings for the Performance card. English defaults keep catalog
 * previews resource-free; the host passes localised values.
 */
data class PerformanceStrings(
    val title: String = "Performance",
    val ttftLabel: String = "Time to first token",
    val decodeLabel: String = "Decode speed",
    val peakMemoryLabel: String = "Peak memory",
    val totalLabel: String = "Total",
    val unknownValue: String = "—",
    val memoryNote: String =
        "Peak memory is the approx. process-wide native heap — not the model's exact footprint.",
    val memoryReportNote: String = "native heap · approx.",
    val benchmarkBadge: String = "BENCHMARK",
    val runBenchmark: String = "Run benchmark",
    val cancel: String = "Cancel",
    val share: String = "Share",
    val done: String = "Done",
    val warmingUpTitle: String = "Warming up…",
    val warmingUpSub: String = "warm-up run · not counted",
    val measuringTitle: String = "Measuring…",
    val measuringSub: String = "measured run · fixed prompt",
    val emptyTitle: String = "No runs yet",
    val emptyBody: String =
        "Send a message or run a benchmark to see how this model performs on your device.",
    val busyTitle: String = "Busy with a task",
    val busyBody: String =
        "A benchmark runs once the current task finishes — the engine handles one run at a time.",
    val noModelBody: String = "Select a model first — performance is measured per model.",
)

/** One-shot callbacks for the Performance card. */
class PerformanceCallbacks(
    val onRunBenchmark: () -> Unit = {},
    val onCancelBenchmark: () -> Unit = {},
    val onShareReport: () -> Unit = {},
    val onDismissReport: () -> Unit = {},
)
