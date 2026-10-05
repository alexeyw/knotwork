package app.knotwork.android.domain.engine

import java.security.MessageDigest

/**
 * Derives the seed of each on-device model call from its run's seed.
 *
 * A run picks one seed when it starts ([app.knotwork.android.domain.models.RunHeader.seed]).
 * Every call a node makes gets a seed derived from it, the node's place in the
 * run tree, the node, the visit and the call's position in the visit — so:
 *
 *  - the same run seed reproduces every call's seed, before and after a resume
 *    (visits are counted on replayed steps too);
 *  - two nodes, two visits of one node, or one node id in two sub-pipelines get
 *    different seeds by construction (they coincide only if a 31-bit SHA-256
 *    prefix collides);
 *  - a new run seed — a retry — changes every call's seed at once.
 *
 * Derivation hashes the fields length-prefixed, so no choice of ids can make two
 * different tuples encode to the same bytes.
 */
object RunSeeds {

    private const val HASH_ALGORITHM = "SHA-256"

    /** Version tag hashed in first: changing the scheme changes every seed. */
    private const val SCHEME = "knotwork-seed-v1"

    /** Bytes of the digest folded into the seed. */
    private const val SEED_BYTES = 4

    /** Bits per byte, for folding the digest prefix into an int. */
    private const val BITS_PER_BYTE = 8

    /** Mask of one byte. */
    private const val BYTE_MASK = 0xFF

    /**
     * The seed of one model call.
     *
     * @param runSeed The run's seed.
     * @param seedPath Where the call's invocation sits in the run tree
     *   ([app.knotwork.android.domain.models.RunTreeContext.seedPath]).
     * @param nodeId The calling node.
     * @param visit The node's zero-based visit index in its invocation.
     * @param call The call's zero-based index among the visit's model calls.
     * @return A seed in `1 until Int.MAX_VALUE` — the range a fresh run seed is
     *   drawn from, and never the runtime's default `0`.
     */
    fun forCall(runSeed: Int, seedPath: String, nodeId: String, visit: Int, call: Int): Int {
        val canonical = buildString {
            for (field in listOf(SCHEME, runSeed.toString(), seedPath, nodeId, visit.toString(), call.toString())) {
                append(field.length).append(':').append(field)
            }
        }
        val digest = MessageDigest.getInstance(HASH_ALGORITHM).digest(canonical.toByteArray(Charsets.UTF_8))
        var prefix = 0
        for (index in 0 until SEED_BYTES) {
            prefix = (prefix shl BITS_PER_BYTE) or (digest[index].toInt() and BYTE_MASK)
        }
        return (prefix and Int.MAX_VALUE) % (Int.MAX_VALUE - 1) + 1
    }
}
