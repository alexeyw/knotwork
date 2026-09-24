package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.RunOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RunRateCeiling] — the shared runaway-guard arithmetic.
 *
 * The boundary is asserted explicitly because the guard is an inequality that is
 * easy to write one off: at exactly the limit the next run must already be
 * refused, since the limit counts runs that have *started*.
 */
class RunRateCeilingTest {

    private val ceiling = RunRateCeiling(origin = RunOrigin.EXTERNAL, limitPerWindow = 3, windowMillis = 1_000L)

    @Test
    fun `given a count below the limit when checking then the run is allowed`() {
        assertFalse(ceiling.isExceededBy(0))
        assertFalse(ceiling.isExceededBy(2))
    }

    @Test
    fun `given a count at the limit when checking then the run is refused`() {
        assertTrue(ceiling.isExceededBy(3))
    }

    @Test
    fun `given a count above the limit when checking then the run is refused`() {
        assertTrue(ceiling.isExceededBy(4))
    }

    @Test
    fun `given a moment when asked for the window start then it is one window earlier`() {
        assertEquals(9_000L, ceiling.windowStart(10_000L))
    }

    @Test
    fun `given the shipped ceilings then external work gets no larger allowance than the app's own`() {
        // An external caller must not be able to start runs faster than the
        // agent's own scheduling tool is allowed to.
        assertTrue(RunRateCeiling.EXTERNAL.limitPerWindow <= RunRateCeiling.SCHEDULED.limitPerWindow)
        assertEquals(RunRateCeiling.ONE_HOUR_MILLIS, RunRateCeiling.EXTERNAL.windowMillis)
        assertEquals(RunRateCeiling.ONE_HOUR_MILLIS, RunRateCeiling.SCHEDULED.windowMillis)
    }

    @Test
    fun `given the shipped ceilings then each governs its own origin`() {
        assertEquals(RunOrigin.SCHEDULER, RunRateCeiling.SCHEDULED.origin)
        assertEquals(RunOrigin.EXTERNAL, RunRateCeiling.EXTERNAL.origin)
        assertEquals(RunOrigin.SHARE, RunRateCeiling.SHARE.origin)
        assertEquals(RunRateCeiling.ONE_HOUR_MILLIS, RunRateCeiling.SHARE.windowMillis)
    }

    // --- Census: every way a run starts is bounded, or says why not ---------

    /**
     * What bounds how fast each origin can start runs: its ceiling, or the written
     * reason it needs none.
     *
     * Exhaustive on purpose. The share target shipped exported without a
     * permission and with no ceiling, while the threat model said only the
     * external contract was reachable by code the user did not write; a new origin
     * does not compile here until someone decides which of the two it is.
     */
    private sealed interface RateBound {
        data class Ceiling(val ceiling: RunRateCeiling) : RateBound
        data class Exempt(val reason: String) : RateBound
    }

    private fun rateBoundOf(origin: RunOrigin): RateBound = when (origin) {
        RunOrigin.CHAT -> RateBound.Exempt("typed into the app's own composer; no other app can reach it")
        RunOrigin.SCHEDULER -> RateBound.Ceiling(RunRateCeiling.SCHEDULED)
        RunOrigin.SHARE -> RateBound.Ceiling(RunRateCeiling.SHARE)
        RunOrigin.QUICK_TILE -> RateBound.Exempt(
            "only the system can bind the tile (BIND_QUICK_SETTINGS_TILE); each run is the user's tap",
        )
        RunOrigin.TRIGGER -> RateBound.Exempt(
            "fired by device conditions the user configured, each at most once per poll (15 min floor)",
        )
        RunOrigin.EXTERNAL -> RateBound.Ceiling(RunRateCeiling.EXTERNAL)
    }

    @Test
    fun `given every run origin then it is bounded by its own ceiling or exempt for a stated reason`() {
        RunOrigin.entries.forEach { origin ->
            when (val bound = rateBoundOf(origin)) {
                is RateBound.Ceiling -> assertEquals(origin, bound.ceiling.origin)
                is RateBound.Exempt -> assertTrue("$origin needs a reason", bound.reason.isNotBlank())
            }
        }
    }

    @Test
    fun `given the origins another app can start without a permission then each has a ceiling`() {
        // The two exported, permission-less surfaces that start runs (see
        // ExportedComponentInventoryTest): the share target and the external contract.
        listOf(RunOrigin.SHARE, RunOrigin.EXTERNAL).forEach { origin ->
            assertNotNull(origin.name, rateBoundOf(origin) as? RateBound.Ceiling)
        }
    }
}
