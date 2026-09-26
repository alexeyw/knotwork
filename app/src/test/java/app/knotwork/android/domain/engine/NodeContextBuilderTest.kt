package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.models.ToolInvocationResult
import app.knotwork.android.domain.prompt.ForgedTurnFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exhaustive test for [NodeContextBuilder] — the single source of truth for how
 * pipeline context blocks are concatenated into a node's executor input.
 *
 * The suite covers four guarantees the builder must keep:
 *
 *  1. **All 32 flag combinations** — for every subset of the five booleans on
 *     [NodeContextConfig], the rendered string contains exactly the headers of
 *     enabled blocks and none of the disabled ones (Group A).
 *  2. **Block order is fixed** regardless of the flag combination (Group B).
 *  3. **Block separator** is exactly one blank line (Group C).
 *  4. **Empty data with an enabled flag drops the whole block** instead of
 *     emitting a header without content (Group D).
 *  5. **Per-block formatting** — chat history, memory and tool results follow
 *     the documented numbered-line shape (Group E).
 */
class NodeContextBuilderTest {

    private lateinit var builder: NodeContextBuilder

    private val originalTaskHeader = "--- Original Task ---"
    private val chatHistoryHeader = "--- Chat History ---"
    private val longTermMemoryHeader = "--- Long-Term Memory ---"
    private val toolResultsHeader = "--- Tool Results ---"
    private val previousNodeOutputHeader = "--- Previous Node Output ---"

    @Before
    fun setUp() {
        builder = NodeContextBuilder()
    }

    /**
     * Builds a [PipelineExecutionContext] in which every field carries
     * meaningful, non-empty data so a flag enabled by the test will actually
     * produce a visible block (otherwise the builder would correctly drop it,
     * which is verified separately in Group D).
     */
    private fun richContext(): PipelineExecutionContext = PipelineExecutionContext(
        originalUserMessage = "What's the weather in Madrid?",
        chatHistory = listOf(
            ChatMessage(
                id = 1L,
                sessionId = "s1",
                role = Role.USER,
                content = "Hi",
                timestamp = 0L,
            ),
            ChatMessage(
                id = 2L,
                sessionId = "s1",
                role = Role.AGENT,
                content = "Hello, how can I help?",
                timestamp = 1L,
            ),
        ),
        previousNodeOutput = "intermediate node payload",
        toolResults = listOf(
            ToolInvocationResult(toolName = "web.search", output = "23C, sunny"),
        ),
        memoryEntries = listOf(
            MemoryChunk(
                id = 10L,
                text = "User lives in Spain",
                embedding = FloatArray(0),
                timestamp = 0L,
            ),
        ),
    )

    /**
     * Builds a [NodeContextConfig] from the lower five bits of [mask]. Bit 0 is
     * `originalTask`, bit 1 — `chatHistory`, bit 2 — `longTermMemory`, bit 3 —
     * `toolResults`, bit 4 — `nodeInput`. The bit ordering is irrelevant to the
     * builder's contract (it consumes named flags) but documenting it here
     * keeps the per-mask debugging trail explicit.
     */
    private fun configFromMask(mask: Int): NodeContextConfig = NodeContextConfig(
        originalTask = mask and 0b00001 != 0,
        chatHistory = mask and 0b00010 != 0,
        longTermMemory = mask and 0b00100 != 0,
        toolResults = mask and 0b01000 != 0,
        nodeInput = mask and 0b10000 != 0,
    )

    @Test
    fun `given a stored agent message with a reasoning block when building then history omits it`() {
        // Given — a chat written before reasoning blocks were split at the
        // executor. Its stored message still carries the scratchpad, and without
        // this guard it is replayed into the prompt on every later turn for the
        // rest of that chat's life.
        val ctx = PipelineExecutionContext(
            originalUserMessage = "что ты помнишь?",
            chatHistory = listOf(
                ChatMessage(
                    id = 1L,
                    sessionId = "s1",
                    role = Role.AGENT,
                    content = "<think>\nOkay, the user just got home. Be warm.\n</think>\n\nПривет, наконец-то.",
                    timestamp = 0L,
                ),
            ),
            previousNodeOutput = "",
            toolResults = emptyList(),
            memoryEntries = emptyList(),
        )
        val config = NodeContextConfig(
            originalTask = false,
            chatHistory = true,
            longTermMemory = false,
            toolResults = false,
            nodeInput = false,
        )

        // When
        val rendered = builder.build(config, ctx)

        // Then
        assertTrue("the answer survives: $rendered", rendered.contains("Привет, наконец-то."))
        assertFalse("the scratchpad is gone: $rendered", rendered.contains("Okay, the user just got home"))
        assertFalse("no stray tags: $rendered", rendered.contains("<think>"))
    }

    // ─── Group A: every one of the 32 flag combinations ─────────────────────

    @Test
    fun `all 32 flag combinations include exactly the enabled block headers`() {
        val ctx = richContext()

        for (mask in 0 until 32) {
            val config = configFromMask(mask)
            val rendered = builder.build(config, ctx)

            assertHeaderPresence(
                rendered = rendered,
                mask = mask,
                header = originalTaskHeader,
                expected = config.originalTask,
            )
            assertHeaderPresence(
                rendered = rendered,
                mask = mask,
                header = chatHistoryHeader,
                expected = config.chatHistory,
            )
            assertHeaderPresence(
                rendered = rendered,
                mask = mask,
                header = longTermMemoryHeader,
                expected = config.longTermMemory,
            )
            assertHeaderPresence(
                rendered = rendered,
                mask = mask,
                header = toolResultsHeader,
                expected = config.toolResults,
            )
            assertHeaderPresence(
                rendered = rendered,
                mask = mask,
                header = previousNodeOutputHeader,
                expected = config.nodeInput,
            )
        }
    }

    @Test
    fun `all-flags-false combination produces empty string`() {
        // The validation layer refuses to save such a config on
        // a context-aware node, but the builder itself must remain a pure
        // function and degrade gracefully — empty in, empty out.
        val rendered = builder.build(
            NodeContextConfig(
                chatHistory = false,
                originalTask = false,
                nodeInput = false,
                longTermMemory = false,
                toolResults = false,
            ),
            richContext(),
        )

        assertEquals("", rendered)
    }

    // ─── Group B: deterministic block order ─────────────────────────────────

    @Test
    fun `block order is Original then Chat then Memory then Tools then PreviousOutput when all enabled`() {
        val rendered = builder.build(NodeContextConfig.ALL_ENABLED, richContext())

        val orderedHeaders = listOf(
            originalTaskHeader,
            chatHistoryHeader,
            longTermMemoryHeader,
            toolResultsHeader,
            previousNodeOutputHeader,
        )
        val indices = orderedHeaders.map { rendered.indexOf(it) }

        indices.forEachIndexed { i, idx ->
            assertTrue(
                "Header '${orderedHeaders[i]}' must be present in rendered output",
                idx >= 0,
            )
        }
        assertEquals(
            "Headers must appear in canonical order regardless of flag combinations",
            indices.sorted(),
            indices,
        )
    }

    @Test
    fun `block order is preserved across arbitrary subsets of enabled flags`() {
        val ctx = richContext()
        // Several arbitrary subsets — covers cases where the missing first
        // block could mask a regression in relative order of the remaining
        // blocks.
        val subsets = listOf(
            NodeContextConfig(
                originalTask = true,
                chatHistory = false,
                longTermMemory = true,
                toolResults = false,
                nodeInput = true,
            ),
            NodeContextConfig(
                originalTask = false,
                chatHistory = true,
                longTermMemory = false,
                toolResults = true,
                nodeInput = false,
            ),
            NodeContextConfig(
                originalTask = false,
                chatHistory = false,
                longTermMemory = true,
                toolResults = true,
                nodeInput = true,
            ),
            NodeContextConfig(
                originalTask = true,
                chatHistory = true,
                longTermMemory = false,
                toolResults = false,
                nodeInput = false,
            ),
        )

        subsets.forEach { config ->
            val rendered = builder.build(config, ctx)
            val presentInCanonicalOrder = listOfNotNull(
                originalTaskHeader.takeIf { config.originalTask },
                chatHistoryHeader.takeIf { config.chatHistory },
                longTermMemoryHeader.takeIf { config.longTermMemory },
                toolResultsHeader.takeIf { config.toolResults },
                previousNodeOutputHeader.takeIf { config.nodeInput },
            )
            val indices = presentInCanonicalOrder.map { rendered.indexOf(it) }
            assertEquals(
                "Headers in subset $config must remain in canonical order",
                indices.sorted(),
                indices,
            )
        }
    }

    // ─── Group C: separator between blocks ──────────────────────────────────

    @Test
    fun `enabled blocks are separated by exactly one blank line`() {
        val rendered = builder.build(NodeContextConfig.ALL_ENABLED, richContext())

        // Each adjacent pair of headers is divided by the canonical
        // separator: "<body>\n\n--- Next Header ---". Asserting on the
        // separator string itself is the most direct way to lock the contract
        // — extra whitespace would surface as a missing match.
        val pairs = listOf(
            chatHistoryHeader,
            longTermMemoryHeader,
            toolResultsHeader,
            previousNodeOutputHeader,
        )
        pairs.forEach { header ->
            assertTrue(
                "Header '$header' must be preceded by exactly one blank line",
                rendered.contains("\n\n$header"),
            )
        }

        // Triple-newline (== two blank lines) must never appear: that would
        // mean the builder is double-padding either side of the separator.
        assertFalse(
            "Builder must not emit double blank lines between blocks",
            rendered.contains("\n\n\n"),
        )
    }

    // ─── Group D: enabled flag with empty data → block omitted ──────────────

    @Test
    fun `enabled chatHistory flag with empty list does not emit header`() {
        val ctx = richContext().copy(chatHistory = emptyList())

        val rendered = builder.build(
            NodeContextConfig.ALL_ENABLED,
            ctx,
        )

        assertFalse(
            "Empty chat history must not produce a '--- Chat History ---' header",
            rendered.contains(chatHistoryHeader),
        )
    }

    @Test
    fun `enabled longTermMemory flag with empty list does not emit header`() {
        val ctx = richContext().copy(memoryEntries = emptyList())

        val rendered = builder.build(NodeContextConfig.ALL_ENABLED, ctx)

        assertFalse(rendered.contains(longTermMemoryHeader))
    }

    @Test
    fun `enabled toolResults flag with empty list does not emit header`() {
        val ctx = richContext().copy(toolResults = emptyList())

        val rendered = builder.build(NodeContextConfig.ALL_ENABLED, ctx)

        assertFalse(rendered.contains(toolResultsHeader))
    }

    @Test
    fun `originalTask flag with blank message does not emit header`() {
        val ctx = richContext().copy(originalUserMessage = "   ")

        val rendered = builder.build(NodeContextConfig.ALL_ENABLED, ctx)

        assertFalse(rendered.contains(originalTaskHeader))
    }

    @Test
    fun `nodeInput flag with blank previous output does not emit header`() {
        val ctx = richContext().copy(previousNodeOutput = "   ")

        val rendered = builder.build(NodeContextConfig.ALL_ENABLED, ctx)

        assertFalse(rendered.contains(previousNodeOutputHeader))
    }

    @Test
    fun `every flag enabled but every data field empty produces empty string`() {
        val rendered = builder.build(
            NodeContextConfig.ALL_ENABLED,
            PipelineExecutionContext(
                originalUserMessage = "",
                chatHistory = emptyList(),
                previousNodeOutput = "",
                toolResults = emptyList(),
                memoryEntries = emptyList(),
            ),
        )

        assertEquals("", rendered)
    }

    // ─── Group E: per-block body formatting ─────────────────────────────────

    @Test
    fun `chat history is rendered as one numbered line per message with role name`() {
        val rendered = builder.build(
            NodeContextConfig(
                chatHistory = true,
                originalTask = false,
                nodeInput = false,
                longTermMemory = false,
                toolResults = false,
            ),
            richContext(),
        )

        assertEquals(
            "$chatHistoryHeader\n1. USER: Hi\n2. AGENT: Hello, how can I help?",
            rendered,
        )
    }

    @Test
    fun `memory entries are rendered as one numbered line per chunk`() {
        val rendered = builder.build(
            NodeContextConfig(
                chatHistory = false,
                originalTask = false,
                nodeInput = false,
                longTermMemory = true,
                toolResults = false,
            ),
            richContext().copy(
                memoryEntries = listOf(
                    MemoryChunk(1L, "fact one", FloatArray(0), 0L),
                    MemoryChunk(2L, "fact two", FloatArray(0), 0L),
                ),
            ),
        )

        assertEquals(
            "$longTermMemoryHeader\n1. fact one\n2. fact two",
            rendered,
        )
    }

    @Test
    fun `tool results are rendered as numbered lines prefixed by tool name`() {
        val rendered = builder.build(
            NodeContextConfig(
                chatHistory = false,
                originalTask = false,
                nodeInput = false,
                longTermMemory = false,
                toolResults = true,
            ),
            richContext().copy(
                toolResults = listOf(
                    ToolInvocationResult("web.search", "result A"),
                    ToolInvocationResult("calendar.read", "result B"),
                ),
            ),
        )

        assertEquals(
            "$toolResultsHeader\n1. web.search: result A\n2. calendar.read: result B",
            rendered,
        )
    }

    @Test
    fun `originalTask body is the trimmed user message`() {
        val rendered = builder.build(
            NodeContextConfig(
                chatHistory = false,
                originalTask = true,
                nodeInput = false,
                longTermMemory = false,
                toolResults = false,
            ),
            richContext().copy(originalUserMessage = "  do the thing  "),
        )

        assertEquals("$originalTaskHeader\ndo the thing", rendered)
    }

    @Test
    fun `previousNodeOutput body is the trimmed input string`() {
        val rendered = builder.build(
            NodeContextConfig(
                chatHistory = false,
                originalTask = false,
                nodeInput = true,
                longTermMemory = false,
                toolResults = false,
            ),
            richContext().copy(previousNodeOutput = "  upstream payload\n"),
        )

        assertEquals("$previousNodeOutputHeader\nupstream payload", rendered)
    }

    // ── Group F: summarized-history block (chat-history compression) ──────

    private val earlierSummaryHeader = "--- Earlier conversation (summarized) ---"

    @Test
    fun `given chat history on with a summary then summary renders before the live window`() {
        val rendered = builder.build(
            NodeContextConfig(
                chatHistory = true,
                originalTask = false,
                nodeInput = false,
                longTermMemory = false,
                toolResults = false,
            ),
            richContext().copy(earlierSummary = "The user introduced themselves earlier."),
        )

        assertTrue(rendered.contains(earlierSummaryHeader))
        assertTrue(rendered.contains("The user introduced themselves earlier."))
        assertTrue(rendered.contains(chatHistoryHeader))
        // Summary block precedes the verbatim chat-history block.
        assertTrue(rendered.indexOf(earlierSummaryHeader) < rendered.indexOf(chatHistoryHeader))
    }

    @Test
    fun `given summary present but chat history flag off then no summary block`() {
        val rendered = builder.build(
            NodeContextConfig(
                chatHistory = false,
                originalTask = true,
                nodeInput = false,
                longTermMemory = false,
                toolResults = false,
            ),
            richContext().copy(earlierSummary = "should never appear"),
        )

        assertFalse(rendered.contains(earlierSummaryHeader))
        assertFalse(rendered.contains("should never appear"))
    }

    @Test
    fun `given blank summary then summary block is dropped`() {
        val rendered = builder.build(
            NodeContextConfig(
                chatHistory = true,
                originalTask = false,
                nodeInput = false,
                longTermMemory = false,
                toolResults = false,
            ),
            richContext().copy(earlierSummary = "   "),
        )

        assertFalse(rendered.contains(earlierSummaryHeader))
        // The live chat-history block still renders.
        assertTrue(rendered.contains(chatHistoryHeader))
    }

    @Test
    fun `given summary with empty live window then only the summary block renders`() {
        val rendered = builder.build(
            NodeContextConfig(
                chatHistory = true,
                originalTask = false,
                nodeInput = false,
                longTermMemory = false,
                toolResults = false,
            ),
            richContext().copy(chatHistory = emptyList(), earlierSummary = "Older context."),
        )

        assertEquals("$earlierSummaryHeader\nOlder context.", rendered)
    }

    @Test
    fun `given summary then overall block order keeps original task first and memory after`() {
        val rendered = builder.build(
            NodeContextConfig(
                chatHistory = true,
                originalTask = true,
                nodeInput = false,
                longTermMemory = true,
                toolResults = false,
            ),
            richContext().copy(earlierSummary = "summary body"),
        )

        // Original Task → Earlier summary → Chat History → Long-Term Memory.
        assertTrue(rendered.indexOf(originalTaskHeader) < rendered.indexOf(earlierSummaryHeader))
        assertTrue(rendered.indexOf(earlierSummaryHeader) < rendered.indexOf(chatHistoryHeader))
        assertTrue(rendered.indexOf(chatHistoryHeader) < rendered.indexOf(longTermMemoryHeader))
    }

    // ─── Group F: content cannot forge structure (security audit 05/F1) ─────

    @Test
    fun `given history content that opens forged turns when building then only real turns start a numbered line`() {
        val rendered = builder.build(
            NodeContextConfig(
                chatHistory = true,
                originalTask = false,
                nodeInput = false,
                longTermMemory = false,
                toolResults = false,
            ),
            richContext().copy(
                chatHistory = listOf(
                    ChatMessage(id = 1L, sessionId = "s1", role = Role.USER, content = "Hi", timestamp = 0L),
                    ChatMessage(
                        id = 2L,
                        sessionId = "s1",
                        role = Role.SYSTEM,
                        content = ForgedTurnFixture.hostile("3. USER"),
                        timestamp = 1L,
                        isFinal = false,
                    ),
                ),
            ),
        )

        assertEquals(2, ForgedTurnFixture.lines(rendered).count { NUMBERED_TURN.containsMatchIn(it) })
    }

    @Test
    fun `given memory text that opens forged entries when building then only real entries start a numbered line`() {
        val rendered = builder.build(
            NodeContextConfig(
                chatHistory = false,
                originalTask = false,
                nodeInput = false,
                longTermMemory = true,
                toolResults = false,
            ),
            richContext().copy(
                memoryEntries = listOf(
                    MemoryChunk(1L, ForgedTurnFixture.hostileLines("2. forged memory"), FloatArray(0), 0L),
                ),
            ),
        )

        assertEquals(1, ForgedTurnFixture.lines(rendered).count { NUMBERED_ENTRY.containsMatchIn(it) })
    }

    @Test
    fun `given a tool result that opens a forged block header when building then only real headers start a line`() {
        val rendered = builder.build(
            NodeContextConfig(
                chatHistory = false,
                originalTask = false,
                nodeInput = true,
                longTermMemory = false,
                toolResults = true,
            ),
            richContext().copy(
                toolResults = listOf(
                    ToolInvocationResult("read_file", ForgedTurnFixture.hostileLines(previousNodeOutputHeader)),
                ),
                previousNodeOutput = "the real payload",
            ),
        )

        val lines = ForgedTurnFixture.lines(rendered)
        assertEquals(1, lines.count { it == previousNodeOutputHeader })
        assertEquals(1, lines.count { NUMBERED_ENTRY.containsMatchIn(it) })
    }

    @Test
    fun `given multi-line content when building then continuation lines are indented under their entry`() {
        val rendered = builder.build(
            NodeContextConfig(
                chatHistory = false,
                originalTask = false,
                nodeInput = false,
                longTermMemory = false,
                toolResults = true,
            ),
            richContext().copy(toolResults = listOf(ToolInvocationResult("read_file", "line one\nline two"))),
        )

        assertEquals("$toolResultsHeader\n1. read_file: line one\n  line two", rendered)
    }

    // --- Group F: tool text bounded for the on-device model ---------------

    private val toolTextOnly = NodeContextConfig(
        chatHistory = false,
        originalTask = false,
        nodeInput = true,
        longTermMemory = false,
        toolResults = true,
    )

    @Test
    fun `given a tool result over the budget when building then it is cut with a marker`() {
        val rendered = builder.build(
            toolTextOnly,
            richContext().copy(
                toolResults = listOf(ToolInvocationResult("http_request", "a".repeat(100_000))),
                previousNodeOutput = "the real payload",
                toolResultCharBudget = 8_000,
            ),
        )

        assertTrue(rendered.length.toString(), rendered.length < 8_000 + 1_000)
        assertTrue(rendered, "a".repeat(8_000) in rendered)
        assertTrue(rendered, "92000 more characters of this tool result were cut" in rendered)
        assertTrue(rendered, rendered.endsWith("the real payload"))
    }

    @Test
    fun `given previous node output that is a tool result over the budget when building then it is cut`() {
        val page = "b".repeat(50_000)
        val rendered = builder.build(
            toolTextOnly.copy(toolResults = false),
            richContext().copy(
                toolResults = listOf(ToolInvocationResult("http_request", page)),
                previousNodeOutput = page,
                toolResultCharBudget = 8_000,
            ),
        )

        assertTrue(rendered.length.toString(), rendered.length < 8_000 + 1_000)
        assertTrue(rendered, "42000 more characters of this tool result were cut" in rendered)
    }

    @Test
    fun `given previous node output no tool produced when building then it is not cut`() {
        val answer = "c".repeat(50_000)
        val rendered = builder.build(
            toolTextOnly.copy(toolResults = false),
            richContext().copy(previousNodeOutput = answer, toolResultCharBudget = 8_000),
        )

        assertTrue(rendered.endsWith(answer))
    }

    @Test
    fun `given a tool result already cut to the budget by its tool when building then its own note survives`() {
        // read_file serves at most the budget and appends where to continue;
        // cutting that note off again would lose the offset the model needs.
        val served = "d".repeat(8_000) + "\n[... truncated, 120000 bytes remain — use offset 8000 to continue]"
        val rendered = builder.build(
            toolTextOnly,
            richContext().copy(
                toolResults = listOf(ToolInvocationResult("read_file", served)),
                previousNodeOutput = "p",
                toolResultCharBudget = 8_000,
            ),
        )

        assertTrue(rendered, "use offset 8000 to continue]" in rendered)
        assertFalse(rendered, "were cut" in rendered)
    }

    @Test
    fun `given a tool observation in chat history over the budget when building then only it is cut`() {
        val question = "q".repeat(20_000)
        val rendered = builder.build(
            toolTextOnly.copy(chatHistory = true, toolResults = false, nodeInput = false),
            richContext().copy(
                chatHistory = listOf(
                    ChatMessage(sessionId = "s1", role = Role.USER, content = question, timestamp = 0L),
                    ChatMessage(
                        sessionId = "s1",
                        role = Role.SYSTEM,
                        content = "Observation from http_request: " + "f".repeat(50_000),
                        timestamp = 1L,
                        isFinal = false,
                    ),
                ),
                toolResultCharBudget = 8_000,
            ),
        )

        assertTrue("A user's own message is not a tool result", question in rendered)
        assertFalse(rendered, "f".repeat(50_000) in rendered)
        assertTrue(rendered, "more characters of this tool result were cut" in rendered)
    }

    @Test
    fun `given no budget when building then a tool result is passed whole`() {
        val page = "e".repeat(100_000)
        val rendered = builder.build(
            toolTextOnly,
            richContext().copy(
                toolResults = listOf(ToolInvocationResult("http_request", page)),
                previousNodeOutput = page,
            ),
        )

        assertEquals(2, Regex("e{100000}").findAll(rendered).count())
    }

    private fun assertHeaderPresence(rendered: String, mask: Int, header: String, expected: Boolean) {
        val actual = rendered.contains(header)
        if (expected != actual) {
            val maskBin = mask.toString(2).padStart(5, '0')
            val verb = if (expected) "must contain" else "must NOT contain"
            throw AssertionError(
                "mask=0b$maskBin output $verb header '$header'.\nRendered output:\n$rendered",
            )
        }
    }

    private companion object {
        /** A chat-history line opening a turn: `N. ROLE: `. */
        val NUMBERED_TURN = Regex("^\\d+\\. (USER|AGENT|SYSTEM): ")

        /** Any numbered entry of a list block: `N. `. */
        val NUMBERED_ENTRY = Regex("^\\d+\\. ")
    }
}
