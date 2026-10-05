package app.knotwork.android.domain.models

/**
 * The three sampling values an on-device generation runs with.
 *
 * Stored as `Double` because that is what the runtime's sampler takes: a value
 * that started as a settings `Float` is widened once, when the run header is
 * made ([fromSettings]), and every later read — the generation, the recorded
 * call, a repeat — uses the same number.
 *
 * @property temperature Sampling temperature.
 * @property topK How many of the most likely tokens are sampled from.
 * @property topP Nucleus probability mass sampled from.
 */
data class RunSampler(val temperature: Double, val topK: Int, val topP: Double) {

    /** Widening from the settings' `Float`s. */
    companion object {
        /**
         * The sampler for the values the settings hold.
         *
         * Each `Float` is widened through its decimal form, so the slider's `0.7`
         * becomes `0.7` — not `0.699999988079071`, the binary value a plain
         * `toDouble()` exposes. The header and an exported trace show the number
         * the user chose, and the runtime samples at it.
         *
         * @param temperature The temperature setting.
         * @param topK The top-k setting.
         * @param topP The top-p setting.
         * @return The sampler.
         */
        fun fromSettings(temperature: Float, topK: Int, topP: Float): RunSampler =
            RunSampler(temperature = temperature.toDecimalDouble(), topK = topK, topP = topP.toDecimalDouble())

        /**
         * This `Float` as the `Double` its shortest decimal form names.
         *
         * @return The widened value.
         */
        fun Float.toDecimalDouble(): Double = toString().toDouble()
    }
}
