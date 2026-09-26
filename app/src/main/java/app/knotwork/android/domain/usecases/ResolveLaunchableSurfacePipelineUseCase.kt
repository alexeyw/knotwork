package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.EntrySurface
import app.knotwork.android.domain.repositories.PipelineRepository
import javax.inject.Inject

/**
 * Resolves the pipeline an [EntrySurface] would **run**: its binding, but only
 * while the bound pipeline still exists.
 *
 * A binding is an id kept in settings, and an id can outlive its pipeline — "Erase
 * data" wipes the database and keeps the settings, and an earlier release could
 * leave one behind. A launch path that trusted the raw id would hand the run
 * queue an id it cannot find, and the queue falls back to the default pipeline:
 * the share or the tile would run a graph the user never bound to it, while the
 * settings row reads "Not set". Every path that starts a run from a surface, or
 * shows the surface as ready, reads this; the raw [ResolveSurfacePipelineUseCase]
 * stays for the callers that need the id after its pipeline is gone (the delete
 * flow and the bindings census).
 *
 * @property resolveSurfacePipeline Reads the raw binding.
 * @property pipelineRepository Answers whether the bound pipeline exists.
 */
class ResolveLaunchableSurfacePipelineUseCase @Inject constructor(
    private val resolveSurfacePipeline: ResolveSurfacePipelineUseCase,
    private val pipelineRepository: PipelineRepository,
) {

    /**
     * Reads the bound pipeline of [surface], if it can run.
     *
     * @param surface The entry surface about to start a run, or to show its state.
     * @return The bound pipeline id, or `null` when the surface is unbound or its
     *   binding names a pipeline that no longer exists — the surface is then inert.
     */
    suspend operator fun invoke(surface: EntrySurface): String? {
        val pipelineId = resolveSurfacePipeline(surface) ?: return null
        return pipelineId.takeIf { pipelineRepository.getPipelineById(it) != null }
    }
}
