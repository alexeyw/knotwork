package app.knotwork.android.domain.models

/**
 * One step in the application's cold-start initialization sequence, surfaced
 * by `AppInitializationUseCase` to the splash screen so the user can see
 * which heavy resource is being loaded right now.
 *
 * Stages are ordered: each non-terminal stage represents work currently in
 * progress; [Done] is emitted exactly once after every step succeeds; [Failed]
 * carries the underlying cause and the stage that broke so the UI can offer a
 * retry button instead of silently freezing.
 */
sealed interface InitStage {
    /** Application-level setup — first-launch defaults, prompt seeding. */
    data object Initializing : InitStage

    /**
     * Downloaded model files the registry does not list are being registered
     * again. The model itself is not loaded here: the first run that needs it
     * loads it.
     */
    data object FindingModels : InitStage

    /** Pipelines are being prefetched from Room into the in-memory cache. */
    data object LoadingPipelines : InitStage

    /** Chat sessions are being prefetched from Room. */
    data object LoadingChats : InitStage

    /** Long-term memory summaries are being prefetched. */
    data object LoadingMemory : InitStage

    /** All steps succeeded; the splash screen can hand off to the chat UI. */
    data object Done : InitStage

    /**
     * A non-recoverable error stopped initialization. The UI shows [cause] and
     * a retry button that re-runs the entire pipeline.
     *
     * @property cause Human-readable failure message.
     * @property failedStage The stage that failed, useful for diagnostics.
     * @property failureKind Classification of the failure so the UI can pick
     *   between the generic retry surface and a dedicated recovery screen.
     */
    data class Failed(
        val cause: String,
        val failedStage: InitStage,
        val failureKind: InitFailureKind = InitFailureKind.GENERIC,
    ) : InitStage
}

/**
 * Coarse classification of a fatal initialization failure, used by the splash
 * UI to decide which terminal surface to render.
 */
enum class InitFailureKind {
    /** Any failure without special handling — rendered as message + Retry. */
    GENERIC,

    /**
     * The SQLCipher passphrase is unavailable while an encrypted database
     * exists ([DbPassphraseUnavailableException] somewhere in the cause
     * chain). Rendered as a dedicated recovery screen with Retry and an
     * explicit typed-confirm "reset all data" action.
     */
    DB_PASSPHRASE_UNAVAILABLE,
}

/**
 * Snapshot of initialization progress emitted by `AppInitializationUseCase`
 * on every stage transition. The pair `(completedSteps, totalSteps)` lets
 * the UI render a determinate `LinearProgressIndicator`.
 *
 * @property stage Current [InitStage].
 * @property message Human-readable label rendered under the progress bar
 *   (e.g. "Reading pipelines…").
 * @property completedSteps Number of stages already finished — `0` when
 *   the very first stage is in flight, `totalSteps` once [InitStage.Done]
 *   is emitted.
 * @property totalSteps Total number of stages, fixed for the duration of a
 *   single initialization attempt. Renderer uses this as the denominator.
 */
data class InitProgress(val stage: InitStage, val message: String, val completedSteps: Int, val totalSteps: Int)
