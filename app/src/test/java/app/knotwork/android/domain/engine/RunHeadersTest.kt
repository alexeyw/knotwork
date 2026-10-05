package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.RunHeader
import app.knotwork.android.domain.models.RunSampler
import app.knotwork.android.domain.repositories.GenerationSettings
import app.knotwork.android.domain.services.RunEnvironment
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [RunHeaders]: a fresh header takes the seed source's seed, the settings' sampler
 * widened through its decimal form, and the environment's versions and device.
 */
class RunHeadersTest {

    private val settings: GenerationSettings = mockk {
        every { temperature } returns flowOf(0.7f)
        every { topK } returns flowOf(40)
        every { topP } returns flowOf(0.9f)
    }
    private val environment = object : RunEnvironment {
        override val appVersion = "0.12.0 (17)"
        override val runtimeVersion = "LiteRT-LM 0.17.1"
        override val device = "Samsung SM-S938B · Android 16"
    }

    @Test
    fun `given the settings and a seed when a run starts then its header holds both and the environment`() = runTest {
        val header = RunHeaders(settings, { 42 }, environment).fresh()

        assertEquals(
            RunHeader(
                seed = 42,
                // The slider's 0.7, not the 0.699999988079071 a plain toDouble() exposes.
                sampler = RunSampler(temperature = 0.7, topK = 40, topP = 0.9),
                appVersion = "0.12.0 (17)",
                runtimeVersion = "LiteRT-LM 0.17.1",
                device = "Samsung SM-S938B · Android 16",
            ),
            header,
        )
    }

    @Test
    fun `given two runs when they start then each draws its own seed`() = runTest {
        var next = 0
        val headers = RunHeaders(settings, { ++next }, environment)

        assertEquals(listOf(1, 2), listOf(headers.fresh().seed, headers.fresh().seed))
    }
}
