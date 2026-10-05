package app.knotwork.android.data.engine

import app.knotwork.android.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The data-layer sources of a run header: [SecureRandomRunSeedSource] draws seeds in
 * the range a run seed lives in, and [AndroidRunEnvironment] names the app, the
 * runtime and the device the way a header and an export show them.
 */
@RunWith(RobolectricTestRunner::class)
class RunEnvironmentSourcesTest {

    @Test
    fun `given many draws when seeds are drawn then each is a positive non-default seed`() {
        val source = SecureRandomRunSeedSource()

        val seeds = (0 until DRAWS).map { source.nextSeed() }

        assertTrue(seeds.all { it in 1 until Int.MAX_VALUE })
        assertTrue("draws repeat far too often", seeds.toSet().size > DRAWS - 2)
    }

    @Test
    fun `given the build when the environment is read then it names the app, the runtime and the device`() {
        val environment = AndroidRunEnvironment()

        assertEquals("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", environment.appVersion)
        assertEquals("LiteRT-LM ${BuildConfig.LITERT_LM_VERSION}", environment.runtimeVersion)
        assertTrue(environment.device.isNotBlank())
    }

    private companion object {
        const val DRAWS = 1_000
    }
}
