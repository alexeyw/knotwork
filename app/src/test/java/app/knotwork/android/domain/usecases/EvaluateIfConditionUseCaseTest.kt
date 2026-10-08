package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.engine.structured.CloudStructuredInferenceClientFactory
import app.knotwork.android.domain.engine.structured.CollectingRepairListener
import app.knotwork.android.domain.engine.structured.StructuredInferenceClient
import app.knotwork.android.domain.engine.structured.StructuredOutputGate
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Tests for [EvaluateIfConditionUseCase], including the structured-output gate path
 * (constrained `{"True","False"}` token with repair).
 */
class EvaluateIfConditionUseCaseTest {

    private lateinit var llmInferenceEngine: LlmInferenceEngine
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var evaluateIfConditionUseCase: EvaluateIfConditionUseCase

    @Before
    fun setup() {
        llmInferenceEngine = mockk()
        settingsRepository = mockk()
        every { settingsRepository.structuredOutputMaxRepairs } returns flowOf(DEFAULT_MAX_REPAIRS)
        evaluateIfConditionUseCase = EvaluateIfConditionUseCase(
            llmInferenceEngine,
            StructuredOutputGate(),
            settingsRepository,
            CloudStructuredInferenceClientFactory { _, _ -> null },
        )
    }

    private fun ifNode(
        keywords: String? = null,
        complexity: Int? = null,
        prompt: String? = null,
        hasImageBranch: Boolean? = null,
    ): NodeModel = NodeModel(
        id = UUID.randomUUID().toString(),
        type = NodeType.IF_CONDITION,
        x = 0f,
        y = 0f,
        conditionKeywords = keywords,
        conditionComplexity = complexity,
        conditionPrompt = prompt,
        conditionHasImage = hasImageBranch,
    )

    @Test
    fun `given image branch and an image present when invoke then returns true`() = runTest {
        val outcome = evaluateIfConditionUseCase(
            ifNode(hasImageBranch = true),
            "Look at this",
            hasImage = true,
        )

        assertTrue(outcome.value)
        assertFalse(outcome.gateFailed)
    }

    @Test
    fun `given image branch and no image when invoke then returns false`() = runTest {
        val outcome = evaluateIfConditionUseCase(
            ifNode(hasImageBranch = true),
            "Just text",
            hasImage = false,
        )

        assertFalse(outcome.value)
    }

    @Test
    fun `given image branch with image-only blank text when invoke then still returns true`() = runTest {
        val outcome = evaluateIfConditionUseCase(
            ifNode(hasImageBranch = true),
            "",
            hasImage = true,
        )

        assertTrue(outcome.value)
    }

    @Test
    fun `given image branch then image presence wins over matching keywords`() = runTest {
        // Keywords would match, but the deterministic image check runs first and the
        // run carries no image, so the branch is False regardless of the keyword hit.
        val outcome = evaluateIfConditionUseCase(
            ifNode(keywords = "urgent", hasImageBranch = true),
            "This is URGENT",
            hasImage = false,
        )

        assertFalse(outcome.value)
    }

    @Test
    fun `given matching keywords when invoke then returns true without gate`() = runTest {
        val outcome = evaluateIfConditionUseCase(ifNode(keywords = "urgent, important"), "This is an URGENT message.")

        assertTrue(outcome.value)
        assertFalse(outcome.gateFailed)
    }

    @Test
    fun `given non-matching keywords when invoke then returns false`() = runTest {
        val outcome = evaluateIfConditionUseCase(ifNode(keywords = "urgent, important"), "This is a normal message.")

        assertFalse(outcome.value)
        assertFalse(outcome.gateFailed)
    }

    @Test
    fun `given text over complexity threshold when invoke then returns true`() = runTest {
        val outcome = evaluateIfConditionUseCase(
            ifNode(complexity = 10),
            "This message is definitely longer than 10 characters.",
        )

        assertTrue(outcome.value)
    }

    @Test
    fun `given text under complexity threshold when invoke then returns false`() = runTest {
        val outcome = evaluateIfConditionUseCase(ifNode(complexity = 50), "Short message.")

        assertFalse(outcome.value)
    }

    @Test
    fun `given LLM emits True when invoke then returns true`() = runTest {
        every { llmInferenceEngine.generateResponseStream(any(), any(), any()) } returns flowOf("T", "r", "u", "e")

        val outcome = evaluateIfConditionUseCase(ifNode(prompt = "Is this a question?"), "How are you?")

        assertTrue(outcome.value)
        assertFalse(outcome.gateFailed)
    }

    @Test
    fun `given a cloud provider node when invoke then routes the gate to the cloud client`() = runTest {
        // A cloud-backed structured client that classifies the condition as True,
        // proving the use case routes to the cloud seam (not the local engine)
        // when the node carries a cloudProvider.
        val cloudFactory = CloudStructuredInferenceClientFactory { _, _ ->
            StructuredInferenceClient { _, _ -> "True" }
        }
        val useCase = EvaluateIfConditionUseCase(
            llmInferenceEngine,
            StructuredOutputGate(),
            settingsRepository,
            cloudFactory,
        )
        val node = ifNode(prompt = "Is this a question?").copy(cloudProvider = "openai")

        val outcome = useCase(node, "How are you?")

        assertTrue(outcome.value)
        assertFalse(outcome.gateFailed)
    }

    @Test
    fun `given a cloud provider that cannot be used when invoke then the local engine answers and it is named`() =
        runTest {
            // The factory has no client (no key, restriction, unknown id): the local engine
            // decides, and the outcome carries the provider so the executor can say so — it
            // used to fall back in silence.
            every { llmInferenceEngine.generateResponseStream(any(), any(), any()) } returns flowOf("True")
            val useCase = EvaluateIfConditionUseCase(
                llmInferenceEngine,
                StructuredOutputGate(),
                settingsRepository,
                CloudStructuredInferenceClientFactory { _, _ -> null },
            )

            val outcome = useCase(ifNode(prompt = "Is this a question?").copy(cloudProvider = "groq"), "How are you?")

            assertTrue(outcome.value)
            assertEquals("groq", outcome.unavailableProvider)
        }

    @Test
    fun `given a node without a cloud provider when invoke then no fallback is reported`() = runTest {
        every { llmInferenceEngine.generateResponseStream(any(), any(), any()) } returns flowOf("True")

        val outcome = evaluateIfConditionUseCase(ifNode(prompt = "Is this a question?"), "How are you?")

        assertEquals(null, outcome.unavailableProvider)
    }

    @Test
    fun `given LLM emits False when invoke then returns false`() = runTest {
        every { llmInferenceEngine.generateResponseStream(any(), any(), any()) } returns flowOf("False")

        val outcome = evaluateIfConditionUseCase(ifNode(prompt = "Is this a question?"), "I am fine.")

        assertFalse(outcome.value)
        assertFalse(outcome.gateFailed)
    }

    @Test
    fun `given malformed verdict then a valid one when invoke then repairs and returns true`() = runTest {
        every { llmInferenceEngine.generateResponseStream(any(), any(), any()) } returnsMany listOf(
            flowOf("hmm, maybe"), // no recognised token → triggers repair
            flowOf("True"),
        )
        val listener = CollectingRepairListener()

        val outcome = evaluateIfConditionUseCase(
            ifNode(prompt = "Is this a question?"),
            "How are you?",
            repairListener = listener,
        )

        assertTrue(outcome.value)
        assertFalse(outcome.gateFailed)
        assertEquals(1, listener.attempts.size)
    }

    @Test
    fun `given verdict never recognised when invoke then defaults to false and flags gate failure`() = runTest {
        every { settingsRepository.structuredOutputMaxRepairs } returns flowOf(1)
        every { llmInferenceEngine.generateResponseStream(any(), any(), any()) } returns flowOf("inconclusive")
        val listener = CollectingRepairListener()

        val outcome = evaluateIfConditionUseCase(
            ifNode(prompt = "Is this a question?"),
            "How are you?",
            repairListener = listener,
        )

        assertFalse(outcome.value)
        assertTrue(outcome.gateFailed)
        assertEquals(1, listener.attempts.size)
    }

    @Test
    fun `given inference error when invoke then defaults to false and flags gate failure`() = runTest {
        every { llmInferenceEngine.generateResponseStream(any(), any(), any()) } returns
            kotlinx.coroutines.flow.flow { throw IllegalStateException("engine down") }

        val outcome = evaluateIfConditionUseCase(ifNode(prompt = "Is this a question?"), "How are you?")

        assertFalse(outcome.value)
        assertTrue(outcome.gateFailed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given wrong node type when invoke then throws`() = runTest {
        val node = NodeModel(id = UUID.randomUUID().toString(), type = NodeType.TOOL, x = 0f, y = 0f)
        evaluateIfConditionUseCase(node, "Text")
    }

    @Test
    fun `given blank input when invoke then returns false`() = runTest {
        val outcome = evaluateIfConditionUseCase(ifNode(keywords = "urgent"), "   ")

        assertFalse(outcome.value)
    }

    @Test
    fun `given no conditions when invoke then returns false`() = runTest {
        val outcome = evaluateIfConditionUseCase(ifNode(), "Some text")

        assertFalse(outcome.value)
    }

    private companion object {
        const val DEFAULT_MAX_REPAIRS = 2
    }
}
