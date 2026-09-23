package app.knotwork.android.domain.engine.executors

import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.NodeExecutionResult
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeOutput
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.Skill
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.prompt.PromptVariableProvider
import app.knotwork.android.domain.repositories.SkillRepository
import app.knotwork.android.domain.repositories.ToolRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SkillNodeExecutor].
 *
 * The real inference is delegated to [LiteRtNodeExecutor] / [CloudLlmNodeExecutor];
 * those are mocked here and stubbed to return a controlled output text so the tests
 * focus on the SKILL-specific behaviour: instruction rendering with a scoped `$TOOLS`,
 * executor-level allowlist enforcement, engine selection, and missing-skill failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SkillNodeExecutorTest {

    private lateinit var skillRepository: SkillRepository
    private lateinit var toolRepository: ToolRepository
    private lateinit var liteRtNodeExecutor: LiteRtNodeExecutor
    private lateinit var cloudLlmNodeExecutor: CloudLlmNodeExecutor
    private lateinit var toolInvocationGate: ToolInvocationGate
    private lateinit var executor: SkillNodeExecutor

    private val dateProvider = object : PromptVariableProvider {
        override fun key() = "DATE"
        override suspend fun resolve() = "01 May 2026"
    }

    // A global $TOOLS provider that must be SHADOWED by the skill-scoped one.
    private val globalToolsProvider = object : PromptVariableProvider {
        override fun key() = "TOOLS"
        override suspend fun resolve() = "GLOBAL-ALL-TOOLS"
    }

    @Before
    fun setup() {
        skillRepository = mockk()
        toolRepository = mockk(relaxed = true)
        liteRtNodeExecutor = mockk()
        cloudLlmNodeExecutor = mockk()
        toolInvocationGate = mockk(relaxed = true)
        executor = SkillNodeExecutor(
            skillRepository = skillRepository,
            promptTemplateEngine = PromptTemplateEngine(),
            promptVariableProviders = setOf(dateProvider, globalToolsProvider),
            toolRepository = toolRepository,
            liteRtNodeExecutor = liteRtNodeExecutor,
            cloudLlmNodeExecutor = cloudLlmNodeExecutor,
            toolInvocationGate = toolInvocationGate,
        )
        coEvery { toolRepository.getAvailableTools() } returns listOf(
            AgentTool(name = "write_file", description = "Writes a file", parameters = "{}"),
            AgentTool(name = "read_file", description = "Reads a file", parameters = "{}"),
        )
    }

    private fun skillNode(
        skillId: String? = "skill-1",
        cloudProvider: String? = null,
        alwaysConfirm: Boolean? = null,
    ) = NodeModel(
        id = "skill_node",
        type = NodeType.SKILL,
        x = 0f,
        y = 0f,
        skillId = skillId,
        cloudProvider = cloudProvider,
        alwaysConfirm = alwaysConfirm,
    )

    private fun skill(instruction: String = "Translate to English.", allowlist: List<String>? = null) = Skill(
        id = "skill-1",
        name = "Translator",
        description = "Translates text",
        instruction = instruction,
        toolAllowlist = allowlist,
        contextConfig = NodeContextConfig.ALL_ENABLED,
        isBundled = false,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun stubLiteRtOutput(text: String, nodeSlot: io.mockk.CapturingSlot<NodeModel>? = null) {
        every {
            liteRtNodeExecutor.execute(
                node = if (nodeSlot != null) capture(nodeSlot) else any(),
                inputText = any(),
                sessionId = any(),
                originalPrompt = any(),
                runId = any(),
                scope = any(),
            )
        } returns flowOf(NodeOutput.Result(NodeExecutionResult(outputText = text, tokenCount = 7)))
    }

    @Test
    fun `given missing skill id when execute then emits error result`() = runTest {
        val outputs = executor.execute(skillNode(skillId = null), "hi", "s1", "hi").toList()
        val result = outputs.filterIsInstance<NodeOutput.Result>().single()
        assertTrue(result.result.error!!.contains("no skill selected"))
    }

    @Test
    fun `given unresolved skill when execute then emits not-found error`() = runTest {
        coEvery { skillRepository.getSkillById("skill-1") } returns null
        val outputs = executor.execute(skillNode(), "hi", "s1", "hi").toList()
        val result = outputs.filterIsInstance<NodeOutput.Result>().single()
        assertTrue(result.result.error!!.contains("not found"))
    }

    @Test
    fun `given instruction with variables when execute then renders DATE and scopes TOOLS to allowlist`() = runTest {
        coEvery { skillRepository.getSkillById("skill-1") } returns
            skill(instruction = "Date: \$DATE\nTools:\n\$TOOLS", allowlist = listOf("write_file"))
        val nodeSlot = slot<NodeModel>()
        stubLiteRtOutput("done", nodeSlot)

        executor.execute(skillNode(), "translate this", "s1", "translate this").toList()

        val rendered = nodeSlot.captured.systemPrompt!!
        assertTrue("DATE resolved", rendered.contains("Date: 01 May 2026"))
        assertTrue("allowlisted tool listed", rendered.contains("write_file — Writes a file"))
        assertTrue("non-allowlisted tool hidden", !rendered.contains("read_file"))
        assertTrue("global TOOLS shadowed", !rendered.contains("GLOBAL-ALL-TOOLS"))
    }

    @Test
    fun `given empty allowlist when execute then TOOLS renders empty`() = runTest {
        coEvery { skillRepository.getSkillById("skill-1") } returns
            skill(instruction = "Tools:[\$TOOLS]", allowlist = emptyList())
        val nodeSlot = slot<NodeModel>()
        stubLiteRtOutput("done", nodeSlot)

        executor.execute(skillNode(), "x", "s1", "x").toList()

        assertEquals("Tools:[]", nodeSlot.captured.systemPrompt)
    }

    @Test
    fun `given pure inference output when execute then output text is the node result`() = runTest {
        coEvery { skillRepository.getSkillById("skill-1") } returns skill()
        stubLiteRtOutput("Hello world")

        val outputs = executor.execute(skillNode(), "Привет мир", "s1", "Привет мир").toList()

        val result = outputs.filterIsInstance<NodeOutput.Result>().single()
        assertEquals("Hello world", result.result.outputText)
        assertNull(result.result.error)
        coVerify(exactly = 0) { toolInvocationGate.dispatch(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `given tool call outside allowlist when execute then rejects at executor level without dispatch`() = runTest {
        coEvery { skillRepository.getSkillById("skill-1") } returns skill(allowlist = listOf("write_file"))
        // The model emits a call to a tool the skill does not allow.
        stubLiteRtOutput("""{"tool": "delete_file", "arguments": {"path": "/x"}}""")

        val outputs = executor.execute(skillNode(), "x", "s1", "x").toList()

        val result = outputs.filterIsInstance<NodeOutput.Result>().single()
        assertEquals("delete_file", result.result.resolvedToolName)
        assertTrue(result.result.outputText!!.contains("not in skill"))
        // Hard guarantee: the out-of-allowlist tool is never dispatched.
        coVerify(exactly = 0) { toolInvocationGate.dispatch(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { toolRepository.executeTool(any(), any(), any()) }
    }

    @Test
    fun `given tool call inside allowlist when execute then dispatches through the gate`() = runTest {
        coEvery { skillRepository.getSkillById("skill-1") } returns skill(allowlist = listOf("write_file"))
        stubLiteRtOutput("""{"tool": "write_file", "arguments": {"path": "/x", "content": "hi"}}""")

        executor.execute(skillNode(), "x", "s1", "x", runId = "run-1").toList()

        coVerify(exactly = 1) {
            toolInvocationGate.dispatch(
                collector = any(),
                nodeType = "SKILL",
                nodeId = "skill_node",
                sessionId = "s1",
                runId = "run-1",
                resolvedToolName = "write_file",
                resolvedToolArgs = any(),
                alwaysConfirm = false,
            )
        }
    }

    @Test
    fun `given a SKILL node that always confirms when it dispatches a tool then the gate is told to ask`() = runTest {
        // The switch a shared pipeline can set on a SKILL node must reach the
        // gate exactly as it does from a TOOL node — otherwise the author armed
        // a confirmation the run never raises.
        coEvery { skillRepository.getSkillById("skill-1") } returns skill(allowlist = null)
        stubLiteRtOutput("""{"tool": "read_file", "arguments": {"path": "/x"}}""")

        executor.execute(skillNode(alwaysConfirm = true), "x", "s1", "x", runId = "run-1").toList()

        coVerify(exactly = 1) {
            toolInvocationGate.dispatch(any(), any(), any(), any(), any(), "read_file", any(), alwaysConfirm = true)
        }
    }

    @Test
    fun `given null allowlist when execute then any tool call is dispatched`() = runTest {
        coEvery { skillRepository.getSkillById("skill-1") } returns skill(allowlist = null)
        stubLiteRtOutput("""{"tool": "read_file", "arguments": {"path": "/x"}}""")

        executor.execute(skillNode(), "x", "s1", "x", runId = "run-1").toList()

        coVerify(exactly = 1) {
            toolInvocationGate.dispatch(any(), any(), any(), any(), any(), "read_file", any(), alwaysConfirm = false)
        }
    }

    @Test
    fun `given cloud provider set when execute then routes to the cloud executor`() = runTest {
        coEvery { skillRepository.getSkillById("skill-1") } returns skill()
        every {
            cloudLlmNodeExecutor.execute(any(), any(), any(), any(), any(), any())
        } returns flowOf(NodeOutput.Result(NodeExecutionResult(outputText = "cloud")))

        val outputs = executor.execute(skillNode(cloudProvider = "auto"), "x", "s1", "x").toList()

        assertEquals("cloud", outputs.filterIsInstance<NodeOutput.Result>().single().result.outputText)
        coVerify(exactly = 0) { liteRtNodeExecutor.execute(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `given inference error when execute then propagates the error`() = runTest {
        coEvery { skillRepository.getSkillById("skill-1") } returns skill()
        every {
            liteRtNodeExecutor.execute(any(), any(), any(), any(), any(), any())
        } returns flowOf(NodeOutput.Result(NodeExecutionResult(error = "model failed")))

        val outputs = executor.execute(skillNode(), "x", "s1", "x").toList()

        assertEquals("model failed", outputs.filterIsInstance<NodeOutput.Result>().single().result.error)
    }
}
