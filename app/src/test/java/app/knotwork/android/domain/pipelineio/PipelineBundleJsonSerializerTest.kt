package app.knotwork.android.domain.pipelineio

import app.knotwork.android.domain.models.PipelineBundleImportOutcome
import app.knotwork.android.domain.pipelineio.PipelineBundleTestFixtures.linearGraph
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [PipelineBundleJsonSerializer] — the envelope round-trip and every
 * parse-time invariant (referential integrity, duplicate ids, limits, schema
 * aggregation). Graph-shape mapping is delegated to [PipelineJsonSerializer]
 * and covered by its own test, so these fixtures use the serializer's own
 * output as the parse input where possible.
 */
class PipelineBundleJsonSerializerTest {

    @Test
    fun `given closure when serialize then parse round-trips every pipeline`() {
        val root = linearGraph("root", targets = listOf("sub"))
        val sub = linearGraph("sub")

        val json = PipelineBundleJsonSerializer.serialize(listOf(root, sub), exportedAt = 42L)
        val outcome = PipelineBundleJsonSerializer.parse(json)

        assertTrue(outcome is PipelineBundleImportOutcome.Success)
        val pipelines = (outcome as PipelineBundleImportOutcome.Success).pipelines
        assertEquals(listOf("root", "sub"), pipelines.map { it.id })
        assertEquals(42L, JSONObject(json).getLong("exportedAt"))
        assertEquals(1, JSONObject(json).getInt("bundleVersion"))
    }

    @Test
    fun `given single-pipeline bundle when parse then it succeeds`() {
        val json = PipelineBundleJsonSerializer.serialize(listOf(linearGraph("solo")), exportedAt = 0L)

        val outcome = PipelineBundleJsonSerializer.parse(json)

        assertTrue(outcome is PipelineBundleImportOutcome.Success)
        assertEquals(1, (outcome as PipelineBundleImportOutcome.Success).pipelines.size)
    }

    @Test
    fun `given missing bundleVersion when parse then Failure`() {
        val outcome = PipelineBundleJsonSerializer.parse("""{"pipelines":[]}""")
        assertTrue(outcome is PipelineBundleImportOutcome.Failure)
    }

    @Test
    fun `given empty pipelines array when parse then Failure`() {
        val outcome = PipelineBundleJsonSerializer.parse("""{"bundleVersion":1,"pipelines":[]}""")
        assertTrue(outcome is PipelineBundleImportOutcome.Failure)
    }

    @Test
    fun `given malformed JSON when parse then Failure`() {
        assertTrue(PipelineBundleJsonSerializer.parse("{ not json") is PipelineBundleImportOutcome.Failure)
    }

    @Test
    fun `given dangling targetPipelineId when parse then Failure`() {
        // root references "missing" but the bundle does not contain it.
        val json = PipelineBundleJsonSerializer.serialize(
            listOf(linearGraph("root", targets = listOf("missing"))),
            exportedAt = 0L,
        )

        val outcome = PipelineBundleJsonSerializer.parse(json)

        assertTrue(outcome is PipelineBundleImportOutcome.Failure)
        assertTrue((outcome as PipelineBundleImportOutcome.Failure).problem.toString().contains("missing"))
    }

    @Test
    fun `given duplicate ids when parse then Failure`() {
        val json = PipelineBundleJsonSerializer.serialize(
            listOf(linearGraph("dup"), linearGraph("dup")),
            exportedAt = 0L,
        )

        assertTrue(PipelineBundleJsonSerializer.parse(json) is PipelineBundleImportOutcome.Failure)
    }

    @Test
    fun `given closure exceeding the limit when parse then Failure`() {
        val many = (0..PipelineBundleJsonSerializer.MAX_BUNDLE_PIPELINES).map { linearGraph("p$it") }
        val json = PipelineBundleJsonSerializer.serialize(many, exportedAt = 0L)

        assertTrue(PipelineBundleJsonSerializer.parse(json) is PipelineBundleImportOutcome.Failure)
    }

    @Test
    fun `given a future schemaVersion element when parse then PartialSchemaMismatch aggregates it`() {
        val root = linearGraph("root", targets = listOf("sub"))
        val sub = linearGraph("sub")
        // Bump the embedded schemaVersion of the "sub" element to a future value.
        val bundle = JSONObject(PipelineBundleJsonSerializer.serialize(listOf(root, sub), exportedAt = 0L))
        val pipelines = bundle.getJSONArray("pipelines")
        pipelines.getJSONObject(1).put("schemaVersion", 99)

        val outcome = PipelineBundleJsonSerializer.parse(bundle.toString())

        assertTrue(outcome is PipelineBundleImportOutcome.PartialSchemaMismatch)
        val mismatch = outcome as PipelineBundleImportOutcome.PartialSchemaMismatch
        assertEquals(2, mismatch.pipelines.size)
        assertEquals(1, mismatch.mismatches.size)
        assertEquals("sub", mismatch.mismatches.first().pipelineId)
        assertEquals(99, mismatch.mismatches.first().foundVersion)
    }

    @Test
    fun `given a structurally broken element when parse then Failure`() {
        val bundle = JSONObject(
            PipelineBundleJsonSerializer.serialize(listOf(linearGraph("root")), exportedAt = 0L),
        )
        // Drop the required id from the sole element.
        bundle.getJSONArray("pipelines").getJSONObject(0).remove("id")

        assertTrue(PipelineBundleJsonSerializer.parse(bundle.toString()) is PipelineBundleImportOutcome.Failure)
    }

    @Test
    fun `looksLikeBundle distinguishes envelope from single document`() {
        val bundle = PipelineBundleJsonSerializer.serialize(listOf(linearGraph("root")), exportedAt = 0L)
        val single = PipelineJsonSerializer.serialize(linearGraph("root"))

        assertTrue(PipelineBundleJsonSerializer.looksLikeBundle(bundle))
        assertFalse(PipelineBundleJsonSerializer.looksLikeBundle(single))
        assertFalse(PipelineBundleJsonSerializer.looksLikeBundle("{ not json"))
    }
}
