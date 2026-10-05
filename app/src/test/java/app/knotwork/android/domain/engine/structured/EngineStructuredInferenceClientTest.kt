package app.knotwork.android.domain.engine.structured

import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.engine.NodeInference
import app.knotwork.android.domain.models.LocalSampling
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [EngineStructuredInferenceClient].
 */
class EngineStructuredInferenceClientTest {

    private val engine: LlmInferenceEngine = mockk()

    @Test
    fun `given streamed tokens when infer then returns the concatenation`() = runTest {
        every { engine.generateResponseStream(any(), any(), any()) } returns flowOf("Hel", "lo")

        val result = EngineStructuredInferenceClient(
            engine,
            NodeInference.Unrecorded,
        ).infer("prompt", temperature = null)

        assertEquals("Hello", result)
    }

    @Test
    fun `given a repair temperature when infer then the engine runs on the repair sampling`() = runTest {
        every { engine.generateResponseStream(any(), any(), any()) } returns flowOf("ok")

        EngineStructuredInferenceClient(engine, NodeInference.Unrecorded).infer("prompt", temperature = 0.1f)

        verify { engine.generateResponseStream("prompt", null, LocalSampling.repair(0.1f)) }
    }

    @Test
    fun `given no temperature outside a run when infer then the engine chooses its own sampling`() = runTest {
        every { engine.generateResponseStream(any(), any(), any()) } returns flowOf("ok")

        EngineStructuredInferenceClient(engine, NodeInference.Unrecorded).infer("prompt", temperature = null)

        verify { engine.generateResponseStream("prompt", null, null) }
    }

    @Test
    fun `given an onToken hook when infer then every token is forwarded before being appended`() = runTest {
        every { engine.generateResponseStream(any(), any(), any()) } returns flowOf("a", "b", "c")
        val seen = mutableListOf<String>()

        val result = EngineStructuredInferenceClient(engine, NodeInference.Unrecorded) { seen += it }
            .infer("prompt", temperature = null)

        assertEquals(listOf("a", "b", "c"), seen)
        assertEquals("abc", result)
    }
}
