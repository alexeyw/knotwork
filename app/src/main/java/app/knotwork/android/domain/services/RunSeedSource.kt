package app.knotwork.android.domain.services

/**
 * Picks the seed of a new run.
 *
 * A seam so the golden-trace harness can fix the seed and the trace stays
 * byte-identical between runs; the app draws it at random.
 */
fun interface RunSeedSource {

    /**
     * Returns a fresh run seed.
     *
     * @return A seed in `1 until Int.MAX_VALUE`.
     */
    fun nextSeed(): Int
}
