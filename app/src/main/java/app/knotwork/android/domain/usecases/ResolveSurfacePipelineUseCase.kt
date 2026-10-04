package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.EntrySurface
import app.knotwork.android.domain.repositories.EntryPointSettings
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Resolves the pipeline a given [EntrySurface] is bound to, or `null` when the
 * surface has no binding yet.
 *
 * A `null` result encodes the privacy-first default: an unbound surface is
 * inert and the caller must not launch anything. Centralising the
 * surface → setting mapping here keeps every entry point (share activity, Quick
 * Settings tile) reading the binding the same way and makes the resolution
 * trivially unit-testable against a fake [EntryPointSettings].
 */
class ResolveSurfacePipelineUseCase @Inject constructor(private val entryPointSettings: EntryPointSettings) {

    /**
     * Reads the current pipeline binding for [surface].
     *
     * @param surface The entry surface whose bound pipeline id is requested.
     * @return The bound pipeline id, or `null` when the surface is unbound.
     */
    suspend operator fun invoke(surface: EntrySurface): String? = when (surface) {
        EntrySurface.SHARE -> entryPointSettings.shareTargetPipelineId.first()
        EntrySurface.QUICK_TILE -> entryPointSettings.quickSettingsTilePipelineId.first()
        EntrySurface.EXTERNAL_AUTOMATION -> entryPointSettings.externalAutomationPipelineId.first()
    }
}
