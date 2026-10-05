package app.knotwork.android.domain.engine.structured

import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.engine.NodeInference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [CloudCallNotingClient]: each call a cloud-backed structured node makes is
 * noted with the node's inference, then made unchanged.
 */
class CloudCallNotingClientTest {

    private val notes = mutableListOf<Pair<String, String?>>()
    private val inference = object : NodeInference {
        override fun local(
            engine: LlmInferenceEngine,
            prompt: String,
            imagePath: String?,
            repairTemperature: Float?,
        ): Flow<String> = error("a cloud client never reaches the on-device model")

        override fun cloudCall(provider: String, model: String?) {
            notes += provider to model
        }
    }

    @Test
    fun `given a first attempt and a repair when inferred then both are noted and answered by the provider`() =
        runTest {
            val asked = mutableListOf<Pair<String, Float?>>()
            val client = CloudCallNotingClient(
                provider = "anthropic",
                inference = inference,
                client = StructuredInferenceClient { prompt, temperature ->
                    asked += prompt to temperature
                    "answer"
                },
            )

            val first = client.infer("route this", temperature = null)
            client.infer("route this again", temperature = 0.1f)

            assertEquals("answer", first)
            assertEquals(listOf("route this" to null, "route this again" to 0.1f), asked)
            assertEquals(listOf("anthropic" to null, "anthropic" to null), notes)
        }
}
