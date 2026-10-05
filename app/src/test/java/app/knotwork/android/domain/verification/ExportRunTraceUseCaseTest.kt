package app.knotwork.android.domain.verification

import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.models.RunTreeIds
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.verification.VerificationFixtures.HEADER
import app.knotwork.android.domain.verification.VerificationFixtures.ROOT
import app.knotwork.android.domain.verification.VerificationFixtures.SESSION
import app.knotwork.android.domain.verification.VerificationFixtures.cloudCall
import app.knotwork.android.domain.verification.VerificationFixtures.console
import app.knotwork.android.domain.verification.VerificationFixtures.localCall
import app.knotwork.android.domain.verification.VerificationFixtures.nodeIo
import app.knotwork.android.domain.verification.VerificationFixtures.run
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run trace export — [ExportRunTraceUseCase] over [BuildRunTraceExportUseCase]:
 * the header, every record of every run in tree order with its hashes and the full
 * prompts, and the digest; a run recorded before headers as records only; nothing
 * for a run still going.
 */
class ExportRunTraceUseCaseTest {

    private val child = RunTreeIds.child(ROOT, "pipe", 0)
    private var root = run()
    private val traces = mutableMapOf<String, List<RunTraceRecord>>(
        ROOT to listOf(
            console(0, "Pipeline started"),
            RunTraceRecord.MemorySnapshot(
                runId = ROOT,
                sessionId = SESSION,
                seq = 1,
                timestamp = 1,
                entries = listOf(MemoryChunk(id = 7, text = "likes tea", embedding = FloatArray(0), timestamp = 0)),
            ),
            localCall(2, "llm", prompt = "Résumé — «tea»"),
            nodeIo(3, "llm", "LITE_RT"),
            cloudCall(4, "cloud"),
            nodeIo(5, "pipe", "PIPELINE"),
        ),
        child to listOf(localCall(0, "inner", runId = child)),
    )
    private val runs: PipelineRunRepository = mockk {
        coEvery { getRun(ROOT) } answers { root }
        coEvery { getRun("missing") } returns null
        coEvery { getDescendantRuns(ROOT) } returns listOf(run(id = child, parentRunId = ROOT, pipelineId = "gone"))
    }
    private val trace: RunTraceRepository = mockk {
        coEvery { getTraceForRun(any()) } answers { traces[firstArg()].orEmpty() }
    }
    private val pipelines: PipelineRepository = mockk {
        coEvery { getPipelineById("p") } returns PipelineGraph(id = "p", name = "Daily brief")
        coEvery { getPipelineById("gone") } returns null
    }
    private val readTree = ReadRecordedRunTreeUseCase(runs, trace)
    private val export = ExportRunTraceUseCase(readTree, pipelines, BuildRunTraceExportUseCase())

    private fun JsonObject.string(key: String): String? =
        this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    @Test
    fun `given a finished run when exported then the document holds the header, every record and the digest`() =
        runTest {
            val document = export(ROOT, "05 Oct 2026 18:30")!!
            val json = Json.parseToJsonElement(document.json).jsonObject

            assertEquals(1, json.getValue("schemaVersion").jsonPrimitive.int)
            assertEquals("true", json.string("localOnly"))
            assertEquals(HEADER.seed, json.getValue("header").jsonObject.getValue("seed").jsonPrimitive.int)
            assertEquals("0.7", json.getValue("header").jsonObject.string("temperature"))
            assertEquals(RunDigest.of(readTree(ROOT)!!), json.string("digest"))
            assertEquals(document.digest, json.string("digest"))
            assertEquals(RunDigest.SCHEME, json.string("digestScheme"))
            assertEquals(7, document.recordCount)

            val exportedRuns = json.getValue("runs").jsonArray.map { it.jsonObject }
            assertEquals(listOf(ROOT, child), exportedRuns.map { it.string("runId") })
            assertEquals(listOf("Daily brief", null), exportedRuns.map { it.string("pipelineName") })
            val records = exportedRuns.first().getValue("records").jsonArray.map { it.jsonObject }
            assertEquals(
                listOf("console", "memory", "localModelCall", "node", "cloudModelCall", "node"),
                records.map { it.string("kind") },
            )
            assertEquals("SYSTEM_MESSAGE", records[0].string("type"))
            assertEquals("likes tea", records[1].getValue("entries").jsonArray[0].jsonObject.string("text"))
            val call = records[2]
            assertEquals("Résumé — «tea»", call.string("prompt"))
            assertEquals("gemma.litertlm", call.string("modelFile"))
            assertEquals(localCall(2, "llm", prompt = "Résumé — «tea»").promptSha256, call.string("promptSha256"))
            assertEquals("CPU", call.string("backend"))
            assertEquals("102", call.string("seed"))
            assertEquals(nodeIo(3, "llm", "LITE_RT").outputSha256, records[3].string("outputSha256"))
        }

    @Test
    fun `given a document with text beyond ASCII when sized then the size counts UTF-8 bytes`() = runTest {
        val document = export(ROOT, "now")!!

        assertEquals(document.json.toByteArray(Charsets.UTF_8).size, document.sizeBytes)
        assertTrue("the prompt has multi-byte characters", document.sizeBytes > document.json.length)
    }

    @Test
    fun `given a run recorded before headers when exported then it is records only`() = runTest {
        root = run(header = null)
        traces[ROOT] = listOf(nodeIo(0, "llm", "LITE_RT", visit = null, hashed = false))

        val document = export(ROOT, "now")!!
        val json = Json.parseToJsonElement(document.json).jsonObject

        assertEquals(JsonNull, json["header"])
        assertNull(json.string("digest"))
        assertNull(document.digest)
        val node = json.getValue("runs").jsonArray[0].jsonObject.getValue("records").jsonArray[0].jsonObject
        assertNull(node.string("inputSha256"))
        assertEquals("in", node.string("input"))
    }

    @Test
    fun `given a run still going or no run when exported then there is nothing`() = runTest {
        root = run(status = PipelineRunStatus.WAITING_APPROVAL)

        assertNull(export(ROOT, "now"))
        assertNull(export("missing", "now"))
    }
}
