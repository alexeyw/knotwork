package app.knotwork.android.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RunSeeds]: one run seed reproduces every call's seed, and every coordinate of a
 * call — tree path, node, visit, call — changes it.
 */
class RunSeedsTest {

    private fun seed(runSeed: Int = 1234, path: String = "", nodeId: String = "llm", visit: Int = 0, call: Int = 0) =
        RunSeeds.forCall(runSeed, path, nodeId, visit, call)

    @Test
    fun `given the same coordinates when derived twice then the seed is the same`() {
        assertEquals(seed(), seed())
    }

    @Test
    fun `given the scheme when derived then the seed is pinned`() {
        // A change to the derivation silently changes what every recorded run would
        // repeat with; pinning one value makes such a change a deliberate one.
        assertEquals(PINNED_SEED, seed())
    }

    @Test
    fun `given any one coordinate changed when derived then the seed changes`() {
        val base = seed()

        assertNotEquals(base, seed(runSeed = 1235))
        assertNotEquals(base, seed(path = "/p#0"))
        assertNotEquals(base, seed(nodeId = "llm2"))
        assertNotEquals(base, seed(visit = 1))
        assertNotEquals(base, seed(call = 1))
    }

    @Test
    fun `given ids whose plain concatenation collides when derived then the seeds still differ`() {
        // Without length prefixes "ab" + "c" and "a" + "bc" would hash the same bytes.
        assertNotEquals(seed(path = "/ab", nodeId = "c"), seed(path = "/a", nodeId = "bc"))
    }

    @Test
    fun `given many nodes and visits of one run when derived then no two share a seed`() {
        val seeds = (0 until NODES).flatMap { node ->
            (0 until VISITS).map { visit -> seed(nodeId = "n$node", visit = visit) }
        }

        assertEquals(seeds.size, seeds.toSet().size)
    }

    @Test
    fun `given any coordinates when derived then the seed is in the range a fresh run seed is drawn from`() {
        val seeds = (0 until RANGE_SAMPLE).map { seed(runSeed = it, nodeId = "n$it") }

        assertTrue(seeds.all { it in 1 until Int.MAX_VALUE })
    }

    private companion object {
        const val NODES = 40
        const val VISITS = 25
        const val RANGE_SAMPLE = 2_000

        /** `forCall(1234, "", "llm", 0, 0)` under scheme `knotwork-seed-v1`. */
        const val PINNED_SEED = 345210169
    }
}
