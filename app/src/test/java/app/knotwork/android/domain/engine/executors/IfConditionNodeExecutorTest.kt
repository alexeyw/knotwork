package app.knotwork.android.domain.engine.executors

import app.knotwork.android.domain.engine.structured.RepairListener
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.ExecutionScope
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.RunTreeContext
import app.knotwork.android.domain.usecases.EvaluateIfConditionUseCase
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IfConditionNodeExecutorTest {

    private val evaluateIfConditionUseCase: EvaluateIfConditionUseCase = mockk()
    private val executor = IfConditionNodeExecutor(evaluateIfConditionUseCase)
    private val node = NodeModel("1", NodeType.IF_CONDITION, 0f, 0f)

    @Test
    fun `execute evaluates condition and returns result`() = runTest {
        coEvery { evaluateIfConditionUseCase(node, "test input", any(), any()) } returns
            EvaluateIfConditionUseCase.Outcome(value = true)

        val outputs = executor.execute(node, "test input", "session-1", "prompt").toList()

        val result = outputs.lastResult()
        assertEquals(true, result.conditionResult)
        assertEquals("test input", result.outputText)
        assertTrue("No console events expected on the happy path", outputs.consoleEvents().isEmpty())
    }

    @Test
    fun `execute surfaces each repair attempt as a console event`() = runTest {
        coEvery { evaluateIfConditionUseCase(node, "test input", any(), any()) } answers {
            val listener = arg<RepairListener>(3)
            listener.onRepairAttempt(node.label, 1, 2)
            EvaluateIfConditionUseCase.Outcome(value = true)
        }

        val outputs = executor.execute(node, "test input", "session-1", "prompt").toList()

        val repairs = outputs.consoleEvents().filter { it.type == ConsoleEventType.StructuredOutputRepair }
        assertEquals(1, repairs.size)
        assertEquals(true, outputs.lastResult().conditionResult)
    }

    @Test
    fun `execute forwards image presence to the use case when the run carries an image`() = runTest {
        val hasImageSlot = slot<Boolean>()
        coEvery {
            evaluateIfConditionUseCase(node, "test input", capture(hasImageSlot), any())
        } returns EvaluateIfConditionUseCase.Outcome(value = true)
        val scope = ExecutionScope(run = RunTreeContext.standalone().copy(imagePresent = true))

        executor.execute(node, "test input", "session-1", "prompt", null, scope).toList()

        assertTrue("IF executor must report image presence to the use case", hasImageSlot.captured)
    }

    @Test
    fun `execute reports no image when the run carries none`() = runTest {
        val hasImageSlot = slot<Boolean>()
        coEvery {
            evaluateIfConditionUseCase(node, "test input", capture(hasImageSlot), any())
        } returns EvaluateIfConditionUseCase.Outcome(value = false)

        executor.execute(node, "test input", "session-1", "prompt").toList()

        assertFalse(hasImageSlot.captured)
    }

    @Test
    fun `execute on gate failure keeps default branch and emits an error console event`() = runTest {
        coEvery { evaluateIfConditionUseCase(node, "test input", any(), any()) } returns
            EvaluateIfConditionUseCase.Outcome(value = false, gateFailed = true)

        val outputs = executor.execute(node, "test input", "session-1", "prompt").toList()

        assertEquals(false, outputs.lastResult().conditionResult)
        val errors = outputs.consoleEvents().filter { it.type == ConsoleEventType.Error }
        assertEquals(1, errors.size)
    }

    @Test
    fun `execute on a provider that could not be used names it on the console`() = runTest {
        coEvery { evaluateIfConditionUseCase(node, "test input", any(), any()) } returns
            EvaluateIfConditionUseCase.Outcome(value = true, unavailableProvider = "groq")

        val outputs = executor.execute(node, "test input", "session-1", "prompt").toList()

        assertEquals(true, outputs.lastResult().conditionResult)
        val errors = outputs.consoleEvents().filter { it.type == ConsoleEventType.Error }
        assertEquals(1, errors.size)
        assertTrue(errors.single().message, errors.single().message.contains("'groq'"))
        assertTrue(errors.single().message, errors.single().message.contains("falling back to the local model"))
    }
}
