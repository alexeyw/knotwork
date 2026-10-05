package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.LocalSampling
import app.knotwork.android.domain.models.RunHeader
import app.knotwork.android.domain.models.RunSampler
import app.knotwork.android.domain.repositories.GenerationSettings
import app.knotwork.android.domain.services.RunEnvironment
import app.knotwork.android.domain.services.RunSeedSource
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Makes the header of a root run starting for the first time.
 *
 * The header fixes what every on-device call of the run uses: a fresh seed, and
 * the sampler as the settings say *now* — widened to `Double` once, here
 * ([RunSampler.fromSettings]), so the recorded values are exactly the ones the
 * runtime gets. A run resumed later reads its recorded header back instead of
 * coming here, so a change to the sampler in the meantime does not reach a run
 * that already started. A run started again with a recorded run's seed takes
 * that run's seed and sampler instead of fresh ones.
 *
 * @property generationSettings The user's sampler (Settings → Generation).
 * @property seedSource Draws the run seed.
 * @property environment The versions and device the run executes on.
 */
class RunHeaders @Inject constructor(
    private val generationSettings: GenerationSettings,
    private val seedSource: RunSeedSource,
    private val environment: RunEnvironment,
) {

    /**
     * A header for a run starting now.
     *
     * @param samplingOverride The seed and sampler of a recorded run, for a run
     *   started again with them; `null` for an ordinary run.
     * @return A header with [samplingOverride]'s seed and sampler, or a fresh seed
     *   and the current sampler — with the versions and device of now either way.
     */
    suspend fun fresh(samplingOverride: LocalSampling? = null): RunHeader = RunHeader(
        seed = samplingOverride?.seed ?: seedSource.nextSeed(),
        sampler = samplingOverride?.sampler ?: RunSampler.fromSettings(
            temperature = generationSettings.temperature.first(),
            topK = generationSettings.topK.first(),
            topP = generationSettings.topP.first(),
        ),
        appVersion = environment.appVersion,
        runtimeVersion = environment.runtimeVersion,
        device = environment.device,
    )
}
