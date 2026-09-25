package app.knotwork.android.domain.pipelineio

import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineImportOutcome
import app.knotwork.android.domain.models.PipelineSamplePrompt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [PipelineJsonSerializer].
 *
 * The host JVM provides a full `org.json` implementation via the
 * `org.json:json` testImplementation dependency, so no Robolectric is
 * needed — the serializer is pure Kotlin + org.json with no framework
 * touch points.
 */
class PipelineJsonSerializerTest {

    private val sampleGraph = PipelineGraph(
        id = "pipeline-uuid",
        name = "Demo pipeline",
        updatedAt = 1_700_000_000_000L,
        nodes = listOf(
            NodeModel(
                id = "node-1",
                type = NodeType.INPUT,
                x = 10f,
                y = 20f,
                label = "Start",
                systemPrompt = null,
                contextConfig = NodeContextConfig.defaultForType(NodeType.INPUT),
            ),
            NodeModel(
                id = "node-2",
                type = NodeType.CLOUD,
                x = 200f,
                y = 80f,
                label = "Brain",
                systemPrompt = "You are an agent.",
                cloudProvider = "anthropic",
                contextConfig = NodeContextConfig(
                    chatHistory = true,
                    originalTask = true,
                    nodeInput = true,
                    longTermMemory = false,
                    toolResults = false,
                ),
            ),
            NodeModel(
                id = "node-3",
                type = NodeType.IF_CONDITION,
                x = 400f,
                y = 80f,
                label = "Branch",
                conditionKeywords = "urgent, important",
                conditionPrompt = "Classify urgency",
                conditionComplexity = 5,
                conditionHasImage = true,
                contextConfig = NodeContextConfig.defaultForType(NodeType.IF_CONDITION),
            ),
            NodeModel(
                id = "node-4",
                type = NodeType.TOOL,
                x = 600f,
                y = 80f,
                label = "Search",
                toolName = "web_search",
                contextConfig = NodeContextConfig.defaultForType(NodeType.TOOL),
            ),
            NodeModel(
                id = "node-5",
                type = NodeType.OUTPUT,
                x = 800f,
                y = 80f,
                label = "Reply",
                systemPrompt = "Compose final answer.",
                contextConfig = NodeContextConfig.ALL_ENABLED,
            ),
            NodeModel(
                id = "node-6",
                type = NodeType.CLARIFICATION,
                x = 200f,
                y = 200f,
                label = "Ask",
                clarificationTimeoutMs = 30_000L,
                contextConfig = NodeContextConfig.defaultForType(NodeType.CLARIFICATION),
            ),
        ),
        connections = listOf(
            ConnectionModel(id = "c1", sourceNodeId = "node-1", targetNodeId = "node-2", label = null),
            ConnectionModel(id = "c2", sourceNodeId = "node-2", targetNodeId = "node-3", label = null),
            ConnectionModel(id = "c3", sourceNodeId = "node-3", targetNodeId = "node-4", label = "True"),
            ConnectionModel(id = "c4", sourceNodeId = "node-3", targetNodeId = "node-5", label = "False"),
            ConnectionModel(id = "c5", sourceNodeId = "node-4", targetNodeId = "node-5", label = null),
        ),
    )

    @Test
    fun `serialize then parse round-trips a full graph`() {
        val json = PipelineJsonSerializer.serialize(sampleGraph)

        val outcome = PipelineJsonSerializer.parse(json)

        assertTrue(outcome is PipelineImportOutcome.Success)
        val parsed = (outcome as PipelineImportOutcome.Success).graph
        assertEquals(sampleGraph.id, parsed.id)
        assertEquals(sampleGraph.name, parsed.name)
        assertEquals(sampleGraph.updatedAt, parsed.updatedAt)
        assertEquals(sampleGraph.nodes.size, parsed.nodes.size)
        assertEquals(sampleGraph.connections.size, parsed.connections.size)

        // Field-level checks for representative node types
        val cloud = parsed.nodes.first { it.type == NodeType.CLOUD }
        assertEquals("Brain", cloud.label)
        assertEquals("anthropic", cloud.cloudProvider)
        assertEquals("You are an agent.", cloud.systemPrompt)
        assertEquals(false, cloud.contextConfig.longTermMemory)

        val ifNode = parsed.nodes.first { it.type == NodeType.IF_CONDITION }
        assertEquals("urgent, important", ifNode.conditionKeywords)
        assertEquals(5, ifNode.conditionComplexity)
        assertEquals(true, ifNode.conditionHasImage)

        val tool = parsed.nodes.first { it.type == NodeType.TOOL }
        assertEquals("web_search", tool.toolName)

        val clarification = parsed.nodes.first { it.type == NodeType.CLARIFICATION }
        assertEquals(30_000L, clarification.clarificationTimeoutMs)

        // Connection labels survive
        val branchTrue = parsed.connections.first { it.id == "c3" }
        assertEquals("True", branchTrue.label)
        val branchFalse = parsed.connections.first { it.id == "c4" }
        assertEquals("False", branchFalse.label)
        // Empty labels round-trip as null (not "")
        assertNull(parsed.connections.first { it.id == "c1" }.label)
    }

    @Test
    fun `serialize then parse round-trips pipeline sample prompts including a null tools hint`() {
        val graph = sampleGraph.copy(
            samplePrompts = listOf(
                PipelineSamplePrompt(title = "Look up the latest benchmarks", toolsHint = "search_tool"),
                PipelineSamplePrompt(title = "Explain on-device inference"),
            ),
        )

        val outcome = PipelineJsonSerializer.parse(PipelineJsonSerializer.serialize(graph))

        assertTrue(outcome is PipelineImportOutcome.Success)
        val parsed = (outcome as PipelineImportOutcome.Success).graph
        assertEquals(graph.samplePrompts, parsed.samplePrompts)
        // The second card's absent hint round-trips as null (not "").
        assertNull(parsed.samplePrompts[1].toolsHint)
    }

    @Test
    fun `parse tolerates a document without a samplePrompts key by yielding an empty list`() {
        // Backward compatibility: documents from builds predating the field
        // simply lack the key, which must decode to no suggestions.
        val json = PipelineJsonSerializer.serialize(sampleGraph)
        val stripped = JSONObject(json).apply { remove("samplePrompts") }.toString()

        val outcome = PipelineJsonSerializer.parse(stripped)

        assertTrue(outcome is PipelineImportOutcome.Success)
        assertTrue((outcome as PipelineImportOutcome.Success).graph.samplePrompts.isEmpty())
    }

    @Test
    fun `serialize then parse round-trips the declared memory retrieval query`() {
        val graph = sampleGraph.copy(memoryRetrievalQuery = "evening journal entries around \$DATE")

        val outcome = PipelineJsonSerializer.parse(PipelineJsonSerializer.serialize(graph))

        assertTrue(outcome is PipelineImportOutcome.Success)
        assertEquals(
            "evening journal entries around \$DATE",
            (outcome as PipelineImportOutcome.Success).graph.memoryRetrievalQuery,
        )
    }

    @Test
    fun `serialize omits the memory retrieval query key when the pipeline declares none`() {
        // Absent rather than null: a pipeline that does not use the field must
        // produce the same document it produced before the field existed.
        val json = PipelineJsonSerializer.serialize(sampleGraph)

        assertFalse(JSONObject(json).has("memoryRetrievalQuery"))
    }

    @Test
    fun `serialize omits the memory retrieval query key when it is blank`() {
        // Blank is "not declared" everywhere else (resolver, validator), so the
        // document must not claim a declaration the runtime will ignore.
        val json = PipelineJsonSerializer.serialize(sampleGraph.copy(memoryRetrievalQuery = "   "))

        assertFalse(JSONObject(json).has("memoryRetrievalQuery"))
    }

    @Test
    fun `parse tolerates a document without a memory retrieval query key by yielding null`() {
        // Backward compatibility: pre-field documents decode to "declares
        // nothing", which is exactly their previous runtime behaviour.
        val json = PipelineJsonSerializer.serialize(sampleGraph.copy(memoryRetrievalQuery = "x"))
        val stripped = JSONObject(json).apply { remove("memoryRetrievalQuery") }.toString()

        val outcome = PipelineJsonSerializer.parse(stripped)

        assertTrue(outcome is PipelineImportOutcome.Success)
        assertNull((outcome as PipelineImportOutcome.Success).graph.memoryRetrievalQuery)
    }

    @Test
    fun `serialize then parse round-trips PIPELINE and SKILL reference ids in the flat config block`() {
        // PIPELINE / SKILL nodes carry their references in the flat `config`
        // block (read by the runtime executors). A bare graph proves the ids
        // survive a serialize → parse cycle without relying on the rich
        // nodeConfig envelope.
        val graph = PipelineGraph(
            id = "composed-pipeline",
            name = "Composed",
            updatedAt = 1_700_000_000_000L,
            nodes = listOf(
                NodeModel(
                    id = "node-input",
                    type = NodeType.INPUT,
                    x = 0f,
                    y = 0f,
                    label = "Start",
                    systemPrompt = null,
                    contextConfig = NodeContextConfig.defaultForType(NodeType.INPUT),
                ),
                NodeModel(
                    id = "node-sub",
                    type = NodeType.PIPELINE,
                    x = 100f,
                    y = 0f,
                    label = "Sub-pipeline",
                    targetPipelineId = "target-pipeline-uuid",
                    contextConfig = NodeContextConfig.defaultForType(NodeType.PIPELINE),
                ),
                NodeModel(
                    id = "node-skill",
                    type = NodeType.SKILL,
                    x = 200f,
                    y = 0f,
                    label = "Report Writer",
                    skillId = "report-writer-skill",
                    cloudProvider = "auto",
                    contextConfig = NodeContextConfig.defaultForType(NodeType.SKILL),
                ),
                NodeModel(
                    id = "node-output",
                    type = NodeType.OUTPUT,
                    x = 300f,
                    y = 0f,
                    label = "Reply",
                    contextConfig = NodeContextConfig.ALL_ENABLED,
                ),
            ),
            connections = listOf(
                ConnectionModel(id = "c1", sourceNodeId = "node-input", targetNodeId = "node-sub", label = null),
                ConnectionModel(id = "c2", sourceNodeId = "node-sub", targetNodeId = "node-skill", label = null),
                ConnectionModel(id = "c3", sourceNodeId = "node-skill", targetNodeId = "node-output", label = null),
            ),
        )

        val outcome = PipelineJsonSerializer.parse(PipelineJsonSerializer.serialize(graph))

        assertTrue(outcome is PipelineImportOutcome.Success)
        val parsed = (outcome as PipelineImportOutcome.Success).graph
        val pipelineNode = parsed.nodes.first { it.type == NodeType.PIPELINE }
        assertEquals("target-pipeline-uuid", pipelineNode.targetPipelineId)
        assertNull(pipelineNode.skillId)
        val skillNode = parsed.nodes.first { it.type == NodeType.SKILL }
        assertEquals("report-writer-skill", skillNode.skillId)
        assertEquals("auto", skillNode.cloudProvider)
        assertNull(skillNode.targetPipelineId)
    }

    @Test
    fun `serialize emits the flat reference ids inside the config block`() {
        val graph = PipelineGraph(
            id = "p",
            name = "n",
            updatedAt = 1L,
            nodes = listOf(
                NodeModel(
                    id = "node-sub",
                    type = NodeType.PIPELINE,
                    x = 0f,
                    y = 0f,
                    label = "Sub",
                    targetPipelineId = "target-uuid",
                    contextConfig = NodeContextConfig.defaultForType(NodeType.PIPELINE),
                ),
            ),
            connections = emptyList(),
        )

        val config = JSONObject(PipelineJsonSerializer.serialize(graph))
            .getJSONArray("nodes")
            .getJSONObject(0)
            .getJSONObject("config")

        assertEquals("target-uuid", config.getString("targetPipelineId"))
        assertTrue(config.isNull("skillId"))
    }

    @Test
    fun `parse reports SchemaMismatch when schemaVersion differs`() {
        val json = """
            {
              "schemaVersion": 99,
              "id": "p",
              "name": "Future",
              "updatedAt": 0,
              "nodes": [
                {"id":"n1","type":"INPUT","position":{"x":0,"y":0},"label":"In",
                 "config":{},"contextConfig":{}}
              ],
              "connections": []
            }
        """.trimIndent()

        val outcome = PipelineJsonSerializer.parse(json)

        assertTrue("Expected SchemaMismatch but was $outcome", outcome is PipelineImportOutcome.SchemaMismatch)
        val mismatch = outcome as PipelineImportOutcome.SchemaMismatch
        assertEquals(99, mismatch.foundVersion)
        assertEquals(PipelineJsonSerializer.CURRENT_SCHEMA_VERSION, mismatch.expectedVersion)
        // Best-effort graph still produced
        assertEquals(1, mismatch.graph.nodes.size)
        assertEquals(NodeType.INPUT, mismatch.graph.nodes.single().type)
    }

    @Test
    fun `parse rejects document missing schemaVersion`() {
        val json = """{ "id":"p","name":"x","nodes":[],"connections":[] }"""

        val outcome = PipelineJsonSerializer.parse(json)

        assertTrue(outcome is PipelineImportOutcome.Failure)
        val msg = (outcome as PipelineImportOutcome.Failure).message
        assertTrue("Message should mention schemaVersion: $msg", msg.contains("schemaVersion"))
    }

    @Test
    fun `parse rejects malformed JSON`() {
        val outcome = PipelineJsonSerializer.parse("{ not json")
        assertTrue(outcome is PipelineImportOutcome.Failure)
    }

    @Test
    fun `parse rejects unknown node type`() {
        val json = """
            {
              "schemaVersion": 1, "id":"p", "name":"x",
              "nodes":[{"id":"n1","type":"BOGUS","position":{"x":0,"y":0},
                       "label":"x","config":{},"contextConfig":{}}],
              "connections":[]
            }
        """.trimIndent()

        val outcome = PipelineJsonSerializer.parse(json)

        assertTrue(outcome is PipelineImportOutcome.Failure)
        assertTrue(
            "Failure should mention BOGUS",
            (outcome as PipelineImportOutcome.Failure).message.contains("BOGUS"),
        )
    }

    @Test
    fun `parse rejects connection referencing unknown node`() {
        val json = """
            {
              "schemaVersion": 1, "id":"p", "name":"x",
              "nodes":[{"id":"n1","type":"INPUT","position":{"x":0,"y":0},
                       "label":"x","config":{},"contextConfig":{}}],
              "connections":[{"id":"c1","fromNodeId":"n1","toNodeId":"ghost","label":null}]
            }
        """.trimIndent()

        val outcome = PipelineJsonSerializer.parse(json)

        assertTrue(outcome is PipelineImportOutcome.Failure)
        assertTrue((outcome as PipelineImportOutcome.Failure).message.contains("ghost"))
    }

    /** A one-node document: a CLOUD node with the given `contextConfig` block and provider id. */
    private fun cloudNodeDocument(contextConfig: String, cloudProvider: String = "anthropic") = """
        {
          "schemaVersion": 1, "id":"p", "name":"x", "updatedAt": 0,
          "nodes":[{"id":"n1","type":"CLOUD","position":{"x":0,"y":0},
                   "label":"Answer","config":{"cloudProvider":"$cloudProvider"},
                   "contextConfig":$contextConfig}],
          "connections":[]
        }
    """.trimIndent()

    private fun parsedNode(json: String): NodeModel {
        val outcome = PipelineJsonSerializer.parse(json)
        assertTrue("Expected Success but was $outcome", outcome is PipelineImportOutcome.Success)
        return (outcome as PipelineImportOutcome.Success).graph.nodes.single()
    }

    @Test
    fun `given a contextConfig block that leaves flags out when parse then they take the node type's defaults`() {
        // A cloud node whose file names only three of the five flags. The browser editor
        // shows the missing ones from the node type's defaults (memory and tool results
        // off for CLOUD); the run must read them the same way, not as "on".
        val node = parsedNode(cloudNodeDocument("""{"chatHistory":true,"originalTask":true,"nodeInput":true}"""))

        val defaults = NodeContextConfig.defaultForType(NodeType.CLOUD)
        assertEquals(defaults.longTermMemory, node.contextConfig.longTermMemory)
        assertEquals(defaults.toolResults, node.contextConfig.toolResults)
    }

    @Test
    fun `given a contextConfig flag that is not a boolean when parse then the import is refused`() {
        val outcome = PipelineJsonSerializer.parse(
            cloudNodeDocument("""{"chatHistory":true,"originalTask":true,"nodeInput":true,"longTermMemory":0}"""),
        )

        assertTrue("Expected Failure but was $outcome", outcome is PipelineImportOutcome.Failure)
        assertTrue((outcome as PipelineImportOutcome.Failure).message.contains("longTermMemory"))
    }

    @Test
    fun `given every node type, flag and value shape when parse then the flag follows the app's reading rule`() {
        // One rule for every node type: a flag left out or null takes the type's default,
        // a boolean or "true"/"false" string is read as such, anything else refuses the
        // file. The browser editor's readContextConfig mirrors this rule.
        val flags = listOf("chatHistory", "originalTask", "nodeInput", "longTermMemory", "toolResults")
        val read = mapOf("true" to true, "false" to false, "\"TRUE\"" to true, "\"false\"" to false)
        val refused = listOf("0", "1", "\"yes\"", "[]")
        NodeType.entries.forEach { type ->
            val defaults = NodeContextConfig.defaultForType(type)
            val defaultOf = mapOf(
                "chatHistory" to defaults.chatHistory,
                "originalTask" to defaults.originalTask,
                "nodeInput" to defaults.nodeInput,
                "longTermMemory" to defaults.longTermMemory,
                "toolResults" to defaults.toolResults,
            )
            flags.forEach { flag ->
                fun document(value: String?) = """
                    {"schemaVersion": 1, "id":"p", "name":"x", "updatedAt": 0,
                     "nodes":[{"id":"n1","type":"${type.name}","position":{"x":0,"y":0},"label":"n","config":{},
                               "contextConfig":{${value?.let { "\"$flag\":$it" }.orEmpty()}}}],
                     "connections":[]}
                """.trimIndent()
                fun flagOf(node: NodeModel) = mapOf(
                    "chatHistory" to node.contextConfig.chatHistory,
                    "originalTask" to node.contextConfig.originalTask,
                    "nodeInput" to node.contextConfig.nodeInput,
                    "longTermMemory" to node.contextConfig.longTermMemory,
                    "toolResults" to node.contextConfig.toolResults,
                ).getValue(flag)

                assertEquals("$type.$flag absent", defaultOf.getValue(flag), flagOf(parsedNode(document(null))))
                assertEquals("$type.$flag null", defaultOf.getValue(flag), flagOf(parsedNode(document("null"))))
                read.forEach { (value, expected) ->
                    assertEquals("$type.$flag = $value", expected, flagOf(parsedNode(document(value))))
                }
                refused.forEach { value ->
                    val outcome = PipelineJsonSerializer.parse(document(value))
                    assertTrue("$type.$flag = $value must be refused", outcome is PipelineImportOutcome.Failure)
                    assertTrue((outcome as PipelineImportOutcome.Failure).message.contains(flag))
                }
            }
        }
    }

    @Test
    fun `given a provider id the app knows no provider by when parse then the import is refused`() {
        val outcome = PipelineJsonSerializer.parse(cloudNodeDocument("{}", cloudProvider = "mistral"))

        assertTrue("Expected Failure but was $outcome", outcome is PipelineImportOutcome.Failure)
        assertTrue((outcome as PipelineImportOutcome.Failure).message.contains("mistral"))
    }

    @Test
    fun `given known provider ids in any letter case when parse then the import succeeds and keeps them`() {
        listOf("Anthropic", "GEMINI", "auto", "AUTO", "ollama").forEach { id ->
            assertEquals(id, parsedNode(cloudNodeDocument("{}", cloudProvider = id)).cloudProvider)
        }
    }

    @Test
    fun `parse falls back to per-type defaults when contextConfig is missing`() {
        val json = """
            {
              "schemaVersion": 1, "id":"p", "name":"x", "updatedAt": 0,
              "nodes":[{"id":"n1","type":"OUTPUT","position":{"x":0,"y":0},
                       "label":"Out","config":{}}],
              "connections":[]
            }
        """.trimIndent()

        val outcome = PipelineJsonSerializer.parse(json)

        assertTrue(outcome is PipelineImportOutcome.Success)
        val node = (outcome as PipelineImportOutcome.Success).graph.nodes.single()
        // Missing contextConfig falls back to the OUTPUT per-type default
        // (nodeInput-only), not a blanket ALL_ENABLED.
        assertEquals(NodeContextConfig.defaultForType(NodeType.OUTPUT), node.contextConfig)
    }

    @Test
    fun `serialize emits null for missing optional fields`() {
        val minimal = PipelineGraph(
            id = "p",
            name = "x",
            nodes = listOf(
                NodeModel(
                    id = "n1",
                    type = NodeType.INPUT,
                    x = 0f,
                    y = 0f,
                    systemPrompt = null,
                ),
            ),
            connections = emptyList(),
        )
        val json = PipelineJsonSerializer.serialize(minimal)
        // Spot-check that JSON null is used for absent strings rather than ""
        // — we look at the raw text instead of re-parsing, since re-parse is
        // covered by the round-trip test.
        assertNotNull(json)
        assertTrue(json.contains("\"systemPrompt\":null"))
        assertTrue(json.contains("\"toolName\":null"))
    }

    @Test
    fun `serialize then parse round-trips the rich nodeConfig payload`() {
        val payload = """
            {"v":1,"type":"CLOUD","title":"Brain","provider":"ANTHROPIC",
             "model":"claude","systemPrompt":"You are an agent.",
             "temperature":0.7,"maxTokens":1024,"timeoutMs":30000}
        """.trimIndent()
        val graph = graphWithCloudConfigJson(payload)

        val outcome = PipelineJsonSerializer.parse(PipelineJsonSerializer.serialize(graph))

        assertTrue(outcome is PipelineImportOutcome.Success)
        val cloud = (outcome as PipelineImportOutcome.Success).graph.nodes.first { it.type == NodeType.CLOUD }
        // The opaque blob round-trips: the rich fields survive verbatim even
        // though the domain serializer never interprets them.
        assertNotNull(cloud.configJson)
        val parsed = JSONObject(cloud.configJson!!)
        assertEquals(1, parsed.getInt("v"))
        assertEquals("ANTHROPIC", parsed.getString("provider"))
        assertEquals(0.7, parsed.getDouble("temperature"), 0.0001)
        assertEquals(1024, parsed.getInt("maxTokens"))
        // The flat config remains authoritative for the runtime.
        assertEquals("anthropic", cloud.cloudProvider)
    }

    @Test
    fun `serialize omits nodeConfig when configJson is absent`() {
        val json = PipelineJsonSerializer.serialize(graphWithCloudConfigJson(null))

        assertFalse(json.contains("\"nodeConfig\""))
    }

    @Test
    fun `serialize drops malformed configJson instead of emitting nodeConfig`() {
        val json = PipelineJsonSerializer.serialize(graphWithCloudConfigJson("{ not json"))

        assertFalse("Malformed configJson must not leak into the document", json.contains("\"nodeConfig\""))
        val outcome = PipelineJsonSerializer.parse(json)
        assertTrue(outcome is PipelineImportOutcome.Success)
        val cloud = (outcome as PipelineImportOutcome.Success).graph.nodes.first { it.type == NodeType.CLOUD }
        assertNull(cloud.configJson)
    }

    @Test
    fun `parse tolerates a non-object nodeConfig as null`() {
        val json = """
            {
              "schemaVersion": 1,
              "id": "p",
              "name": "x",
              "nodes": [
                { "id": "n1", "type": "INPUT", "position": { "x": 0, "y": 0 }, "label": "In",
                  "nodeConfig": "oops-not-an-object" }
              ],
              "connections": []
            }
        """.trimIndent()

        val outcome = PipelineJsonSerializer.parse(json)

        assertTrue(outcome is PipelineImportOutcome.Success)
        assertNull((outcome as PipelineImportOutcome.Success).graph.nodes.single().configJson)
    }

    /**
     * Builds a minimal INPUT → CLOUD graph whose CLOUD node carries the given
     * [configJson] blob, used to exercise the opaque `nodeConfig` passthrough.
     */
    private fun graphWithCloudConfigJson(configJson: String?): PipelineGraph = PipelineGraph(
        id = "p",
        name = "x",
        nodes = listOf(
            NodeModel(
                id = "n1",
                type = NodeType.INPUT,
                x = 0f,
                y = 0f,
                systemPrompt = null,
                contextConfig = NodeContextConfig.defaultForType(NodeType.INPUT),
            ),
            NodeModel(
                id = "n2",
                type = NodeType.CLOUD,
                x = 1f,
                y = 0f,
                systemPrompt = "You are an agent.",
                cloudProvider = "anthropic",
                contextConfig = NodeContextConfig.defaultForType(NodeType.CLOUD),
                configJson = configJson,
            ),
        ),
        connections = listOf(
            ConnectionModel(id = "c1", sourceNodeId = "n1", targetNodeId = "n2", label = null),
        ),
    )

    // ─── Supported versions, migration seam, and what an import discards ───

    @Test
    fun `every supported schema version parses to Success`() {
        // The criterion behind this work asks for a test per supported version.
        // Today the supported set has exactly one member — the stamp has never
        // held another value — so this loop asserts one case. It is written as a
        // loop on purpose: adding `2` to SUPPORTED_SCHEMA_VERSIONS without also
        // making a v2 document parse fails here rather than in the field.
        PipelineJsonSerializer.SUPPORTED_SCHEMA_VERSIONS.forEach { version ->
            val json = JSONObject(PipelineJsonSerializer.serialize(sampleGraph))
                .put("schemaVersion", version)
                .toString()

            val outcome = PipelineJsonSerializer.parse(json)

            assertTrue("v$version should be supported, got $outcome", outcome is PipelineImportOutcome.Success)
        }
    }

    @Test
    fun `the app's own export reports nothing dropped`() {
        // The guard on the recognised-key sets: a field added to `serialize`
        // but forgotten in ROOT_KEYS / NODE_KEYS / CONFIG_KEYS would show up
        // here as a false "we lost this" on a file the app just wrote.
        val outcome = PipelineJsonSerializer.parse(PipelineJsonSerializer.serialize(sampleGraph))

        assertEquals(emptyList<String>(), (outcome as PipelineImportOutcome.Success).droppedFields)
    }

    @Test
    fun `given unknown fields at a matching schema version then they are reported not silently dropped`() {
        // The actual defect. The format's rule is that additive fields do NOT
        // bump `schemaVersion` (`samplePrompts` and `memoryRetrievalQuery` were
        // both added that way), so a file from a newer build carries settings
        // this build cannot represent while claiming the very same version — and
        // a version check could never have caught it.
        val root = JSONObject(PipelineJsonSerializer.serialize(sampleGraph))
        root.put("retryPolicy", "aggressive")
        root.getJSONArray("nodes").getJSONObject(1).getJSONObject("config").put("samplingTopK", 40)
        root.getJSONArray("nodes").getJSONObject(1).getJSONObject("contextConfig").put("toolSchemas", true)
        root.getJSONArray("connections").getJSONObject(0).put("weight", 0.5)

        val outcome = PipelineJsonSerializer.parse(root.toString()) as PipelineImportOutcome.Success

        assertEquals(
            listOf(
                "retryPolicy",
                "nodes[1].config.samplingTopK",
                "nodes[1].contextConfig.toolSchemas",
                "connections[0].weight",
            ),
            outcome.droppedFields,
        )
    }

    @Test
    fun `given an unsupported future version then the mismatch names the lost fields`() {
        val root = JSONObject(PipelineJsonSerializer.serialize(sampleGraph))
            .put("schemaVersion", 99)
            .put("parallelism", 4)

        val outcome = PipelineJsonSerializer.parse(root.toString()) as PipelineImportOutcome.SchemaMismatch

        assertEquals(99, outcome.foundVersion)
        assertEquals(PipelineJsonSerializer.CURRENT_SCHEMA_VERSION, outcome.expectedVersion)
        assertEquals(listOf("parallelism"), outcome.droppedFields)
    }

    @Test
    fun `given the opaque nodeConfig blob then its contents are never reported as dropped`() {
        // `nodeConfig` round-trips verbatim into NodeModel.configJson, so nothing
        // inside it is lost — descending into it would produce a wall of false
        // warnings on every import.
        val root = JSONObject(PipelineJsonSerializer.serialize(sampleGraph))
        root.getJSONArray("nodes").getJSONObject(1).put(
            "nodeConfig",
            JSONObject().put("v", 1).put("type", "CLOUD").put("somethingBrandNew", "x"),
        )

        val outcome = PipelineJsonSerializer.parse(root.toString()) as PipelineImportOutcome.Success

        assertEquals(emptyList<String>(), outcome.droppedFields)
    }
}
