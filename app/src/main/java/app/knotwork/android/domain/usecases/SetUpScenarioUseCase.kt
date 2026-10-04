package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.constants.OnboardingScenarioCatalog
import app.knotwork.android.domain.repositories.EntryPointSettings
import app.knotwork.android.domain.repositories.PipelineRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * One-tap "set up this scenario" action behind the onboarding value gallery.
 *
 * Materialises the bundled preset a picked scenario maps to, marks it as the
 * user's default pipeline, and — for scenarios that own an OS entry point —
 * binds that surface to the freshly materialised pipeline. It is a thin
 * orchestration over existing use cases so the wiring stays testable and the
 * onboarding ViewModel does not reach into repositories directly:
 *
 *  1. [LoadPipelineFromPresetUseCase] deep-copies the preset (fresh ids,
 *     validated, persisted) and seeds any composition sub-pipelines. The
 *     curated scenarios are single graphs, so sub-pipeline seeding is a no-op
 *     today — but routing through the same use case keeps a future composed
 *     scenario runnable for free.
 *  2. [EntryPointSettings.setDefaultPipelineId] points the first chat at the
 *     new pipeline (`ChatHomePipelineBindingDelegate` resolves this default).
 *  3. [SetSurfacePipelineUseCase] binds the scenario's [EntrySurface] when it
 *     declares one (Share Handler → the system Share sheet), leaving inert
 *     surfaces untouched.
 *
 * The materialised graph is read back once to project the compact node/edge
 * recap the "Ready" step renders, so the caller never has to re-open the
 * pipeline just to draw its preview.
 *
 * @property loadPipelineFromPresetUseCase Materialises + persists the preset.
 * @property setSurfacePipelineUseCase Binds the scenario's entry surface.
 * @property entryPointSettings Persists the default-pipeline pointer.
 * @property pipelineRepository Read back the saved graph for the recap projection.
 */
class SetUpScenarioUseCase @Inject constructor(
    private val loadPipelineFromPresetUseCase: LoadPipelineFromPresetUseCase,
    private val setSurfacePipelineUseCase: SetSurfacePipelineUseCase,
    private val entryPointSettings: EntryPointSettings,
    private val pipelineRepository: PipelineRepository,
) {
    /**
     * Sets up the scenario identified by [scenarioId].
     *
     * @param scenarioId Stable scenario id (see [OnboardingScenarioCatalog]).
     * @return [Result.success] with the materialised [ScenarioSetup] (pipeline
     *   id + recap projection), or [Result.failure] when the scenario id is
     *   unknown or the preset cannot be materialised / persisted.
     */
    suspend operator fun invoke(scenarioId: String): Result<ScenarioSetup> = try {
        val spec = OnboardingScenarioCatalog.byId(scenarioId)
            ?: return Result.failure(IllegalArgumentException("Unknown onboarding scenario: $scenarioId"))

        val pipelineId = loadPipelineFromPresetUseCase(spec.presetId)
            .getOrElse { return Result.failure(it) }

        entryPointSettings.setDefaultPipelineId(pipelineId)
        spec.entrySurface?.let { surface -> setSurfacePipelineUseCase(surface, pipelineId) }

        val graph = pipelineRepository.getPipelineById(pipelineId)
        Result.success(
            ScenarioSetup(
                scenarioId = scenarioId,
                pipelineId = pipelineId,
                nodeTypeNames = graph?.nodes.orEmpty().map { it.type.name },
                nodeCount = graph?.nodes?.size ?: 0,
                edgeCount = graph?.connections?.size ?: 0,
            ),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Outcome of a successful scenario set-up.
     *
     * @property scenarioId The scenario that was set up.
     * @property pipelineId Id of the materialised, now-default pipeline.
     * @property nodeTypeNames Ordered node-type names (e.g. `INPUT`, `LITE_RT`)
     *   for the "Ready" recap chips.
     * @property nodeCount Total nodes in the materialised graph.
     * @property edgeCount Total connections in the materialised graph.
     */
    data class ScenarioSetup(
        val scenarioId: String,
        val pipelineId: String,
        val nodeTypeNames: List<String>,
        val nodeCount: Int,
        val edgeCount: Int,
    )
}
