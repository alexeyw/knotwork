package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.EntrySurface
import app.knotwork.android.domain.repositories.EntryPointSettings
import javax.inject.Inject

/**
 * Writes (or clears, with `null`) the pipeline bound to an [EntrySurface].
 *
 * The symmetric counterpart of [ResolveSurfacePipelineUseCase]: both dispatch
 * over `EntrySurface` with an exhaustive `when`, so adding a surface is a
 * compile error here and in the resolver until both are updated — no entry
 * point can silently end up bindable-but-unresolvable (or vice versa).
 */
class SetSurfacePipelineUseCase @Inject constructor(private val entryPointSettings: EntryPointSettings) {

    /**
     * Persists the binding for [surface].
     *
     * @param surface The entry surface to bind.
     * @param pipelineId Pipeline id to bind, or `null` to clear the binding
     *   (returning the surface to its inert default).
     */
    suspend operator fun invoke(surface: EntrySurface, pipelineId: String?) {
        when (surface) {
            EntrySurface.SHARE -> entryPointSettings.setShareTargetPipelineId(pipelineId)
            EntrySurface.QUICK_TILE -> entryPointSettings.setQuickSettingsTilePipelineId(pipelineId)
            EntrySurface.EXTERNAL_AUTOMATION -> entryPointSettings.setExternalAutomationPipelineId(pipelineId)
        }
    }
}
