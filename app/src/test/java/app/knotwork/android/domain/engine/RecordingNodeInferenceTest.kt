package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.LocalSampling
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.RunTreeContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RecordingNodeInference]: every on-device call of a visit runs on the run's
 * sampler and a seed derived from the run seed, and is kept — with what the
 * engine ran on — until the engine drains it into the trace.
 */
class RecordingNodeInferenceTest {

    private val engine: LlmInferenceEngine = mockk {
        every { currentModelPath } returns "/m/gemma.litertlm"
        every { activeBackend } returns LocalBackend.GPU
        every { activeContextLength } returns 4096
    }
    private val tree = RunTreeContext.standalone().copy(header = TEST_RUN_HEADER, seedPath = "/p#1")
    private val node = NodeModel(id = "llm", type = NodeType.LITE_RT, x = 0f, y = 0f)

    @Test
    fun `given an ordinary call when collected then it runs on the run's sampler and the derived seed`() = runTest {
        val sampling = slot<LocalSampling>()
        every { engine.generateResponseStream(any(), any(), capture(sampling)) } returns flowOf("an", "swer")

        val chunks = RecordingNodeInference(tree, node, visit = 2).local(engine, "the prompt").toList()

        assertEquals(listOf("an", "swer"), chunks)
        assertEquals(TEST_RUN_HEADER.sampler, sampling.captured.sampler)
        assertEquals(RunSeeds.forCall(TEST_RUN_HEADER.seed, "/p#1", "llm", 2, 0), sampling.captured.seed)
    }

    @Test
    fun `given a repair call when collected then it runs on the fixed repair sampling`() = runTest {
        every { engine.generateResponseStream(any(), any(), any()) } returns flowOf("{}")

        RecordingNodeInference(tree, node, visit = 0).local(engine, "fix it", repairTemperature = 0.1f).toList()

        verify { engine.generateResponseStream("fix it", null, LocalSampling.repair(0.1f)) }
    }

    @Test
    fun `given two calls in one visit when collected then each gets its own seed`() = runTest {
        val seeds = mutableListOf<Int>()
        every { engine.generateResponseStream(any(), any(), any()) } answers {
            seeds += thirdArg<LocalSampling>().seed
            flowOf("x")
        }
        val inference = RecordingNodeInference(tree, node, visit = 0)

        inference.local(engine, "first").toList()
        inference.local(engine, "second").toList()

        assertEquals(2, seeds.toSet().size)
    }

    @Test
    fun `given a finished call when drained then it carries prompt, output and what the engine ran on`() = runTest {
        every { engine.generateResponseStream(any(), any(), any()) } returns flowOf("an", "swer")
        val inference = RecordingNodeInference(tree, node, visit = 2)

        inference.local(engine, "the prompt", imagePath = "/img.jpg").toList()

        val call = inference.drain().single() as PendingModelCall.Local
        assertEquals("llm", call.nodeId)
        assertEquals("LITE_RT", call.nodeType)
        assertEquals(2, call.visit)
        assertEquals(0, call.call)
        assertEquals("the prompt", call.prompt)
        assertEquals("answer", call.output)
        assertEquals("/m/gemma.litertlm", call.modelPath)
        assertEquals(LocalBackend.GPU, call.backend)
        assertEquals(4096, call.contextWindow)
        assertTrue(call.hadImage)
        assertTrue("a drained call is gone", inference.drain().isEmpty())
    }

    @Test
    fun `given a stream that fails when collected then no call is kept`() = runTest {
        every { engine.generateResponseStream(any(), any(), any()) } returns flow {
            emit("partial")
            error("native failure")
        }
        val inference = RecordingNodeInference(tree, node, visit = 0)

        runCatching { inference.local(engine, "p").toList() }

        assertTrue(inference.drain().isEmpty())
    }

    @Test
    fun `given local and cloud calls when drained then they come out in the order they were made`() = runTest {
        every { engine.generateResponseStream(any(), any(), any()) } returns flowOf("x")
        val inference = RecordingNodeInference(tree, node, visit = 0)

        inference.cloudCall("anthropic", "claude")
        inference.local(engine, "p").toList()

        val drained = inference.drain()
        assertEquals(listOf(0, 1), drained.map { it.call })
        assertEquals(PendingModelCall.Cloud("llm", "LITE_RT", 0, 0, "anthropic", "claude"), drained.first())
    }

    @Test
    fun `given inference outside a run when called then the engine chooses its sampling and nothing is kept`() =
        runTest {
            every { engine.generateResponseStream(any(), any(), any()) } returns flowOf("x")

            NodeInference.Unrecorded.local(engine, "p").toList()
            NodeInference.Unrecorded.local(engine, "p", repairTemperature = 0.1f).toList()

            verify { engine.generateResponseStream("p", null, null) }
            verify { engine.generateResponseStream("p", null, LocalSampling.repair(0.1f)) }
        }
}
