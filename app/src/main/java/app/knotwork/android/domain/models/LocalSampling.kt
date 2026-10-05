package app.knotwork.android.domain.models

import app.knotwork.android.domain.models.RunSampler.Companion.toDecimalDouble

/**
 * Everything that decides which tokens an on-device generation picks, apart from
 * the model file and its input: the sampler and the seed.
 *
 * A run passes one of these to every on-device model call it makes, and records
 * it with the call. With the same model file, backend (and, on GPU, context
 * window), input and these values, the runtime produces the same text again on
 * the same device — measured, not assumed (`decisions.md §70.6`).
 *
 * @property sampler Temperature, top-k and top-p.
 * @property seed The sampler's random seed.
 */
data class LocalSampling(val sampler: RunSampler, val seed: Int) {

    /** The fixed sampling of a structured-output repair. */
    companion object {
        /**
         * Top-k of a repair generation: the conventional Gemma-family value, never
         * the user's own sampler.
         */
        const val REPAIR_TOP_K: Int = 64

        /** Top-p of a repair generation. */
        const val REPAIR_TOP_P: Double = 0.95

        /**
         * Seed of a repair generation. Fixed, so a corrective re-inference is the
         * same for the same prompt — the runtime treats `0` as an ordinary seed
         * (`decisions.md §70.6`), not as "random".
         */
        const val REPAIR_SEED: Int = 0

        /**
         * The sampling of a structured-output repair at [temperature]: low
         * temperature over conventional top-k / top-p, so a stumbling model is
         * nudged towards the requested shape rather than re-exploring. The user's
         * own sampler deliberately does not apply.
         *
         * @param temperature The repair temperature.
         * @return The repair sampling.
         */
        fun repair(temperature: Float): LocalSampling = LocalSampling(
            sampler = RunSampler(temperature = temperature.toDecimalDouble(), topK = REPAIR_TOP_K, topP = REPAIR_TOP_P),
            seed = REPAIR_SEED,
        )
    }
}
