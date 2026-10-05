package app.knotwork.android.data.engine

import app.knotwork.android.domain.services.RunSeedSource
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RunSeedSource] drawing each run seed from [SecureRandom].
 *
 * A strong source is not needed for sampling, but it makes the seed of one run
 * say nothing about the seed of the next — a run seed travels in an exported
 * trace, and a seeded `Random` would let a reader of one export predict the
 * others. The range matches the per-conversation seeds the engine drew before
 * runs had one: `1 until Int.MAX_VALUE`, never the runtime's default `0`.
 */
@Singleton
class SecureRandomRunSeedSource @Inject constructor() : RunSeedSource {

    private val random = SecureRandom()

    override fun nextSeed(): Int = random.nextInt(Int.MAX_VALUE - 1) + 1
}
