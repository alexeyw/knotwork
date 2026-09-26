package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.EntrySurface
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.services.ScheduledTaskConstraints
import app.knotwork.android.domain.services.TaskScheduler
import javax.inject.Inject

/**
 * Launches the duty pipeline bound to the Quick Settings tile.
 *
 * The tile is tapped from the system shade, where the app may not be in the
 * foreground, so the run is dispatched through the background [TaskScheduler]
 * (WorkManager → `AgentWorker`) rather than the interactive queue: the worker
 * self-promotes to a foreground service for the duration of inference and the
 * result is announced through the scheduled-task notification with a deep-link
 * into the (freshly auto-named) session.
 *
 * When no pipeline is bound the tile is inert by design ([TileLaunchResult.NotConfigured]);
 * the caller then routes the user to the binding UI instead of running anything.
 */
class LaunchTilePipelineUseCase @Inject constructor(
    private val resolveSurfacePipeline: ResolveLaunchableSurfacePipelineUseCase,
    private val taskScheduler: TaskScheduler,
) {

    /**
     * Resolves the tile's bound pipeline and, when present, enqueues a one-shot
     * background run of it.
     *
     * @param prompt The text fed to the pipeline as the user message. The tile
     *   has no free-form input, so the caller supplies a localised stand-in.
     * @return [TileLaunchResult.Launched] when a run was enqueued, or
     *   [TileLaunchResult.NotConfigured] when the tile has no bound pipeline, or
     *   its binding names a pipeline that no longer exists.
     */
    suspend operator fun invoke(prompt: String): TileLaunchResult {
        val pipelineId = resolveSurfacePipeline(EntrySurface.QUICK_TILE) ?: return TileLaunchResult.NotConfigured
        taskScheduler.scheduleOneTime(
            prompt = prompt,
            delayMinutes = 0,
            sessionId = null,
            constraints = ScheduledTaskConstraints(requiresBatteryNotLow = false),
            pipelineId = pipelineId,
            origin = RunOrigin.QUICK_TILE,
        )
        return TileLaunchResult.Launched
    }
}

/** Outcome of a Quick Settings tile tap. */
sealed interface TileLaunchResult {
    /** A background run of the bound duty pipeline was enqueued. */
    data object Launched : TileLaunchResult

    /** No pipeline is bound to the tile; the caller should open the binding UI. */
    data object NotConfigured : TileLaunchResult
}
