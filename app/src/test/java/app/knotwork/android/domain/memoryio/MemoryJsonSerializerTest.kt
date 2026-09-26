package app.knotwork.android.domain.memoryio

import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MemoryImportOutcome
import app.knotwork.android.domain.models.MemorySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MemoryJsonSerializer] — the serialize → parse round-trip, the
 * provenance / tag fidelity, and the never-throwing failure paths.
 */
class MemoryJsonSerializerTest {

    private fun chunk(
        id: Long,
        text: String,
        embedding: FloatArray,
        source: MemorySource = MemorySource.Unknown,
        tags: List<String> = emptyList(),
        isPinned: Boolean = false,
    ) = MemoryChunk(
        id = id,
        text = text,
        embedding = embedding,
        timestamp = 1_000L + id,
        isPinned = isPinned,
        source = source,
        tags = tags,
    )

    @Test
    fun `serialize then parse round-trips every field a file may carry, pins reported but not applied`() {
        val chunks = listOf(
            chunk(1, "alpha", floatArrayOf(0.1f, 0.2f), MemorySource.Manual, listOf("preference"), isPinned = true),
            chunk(2, "beta", floatArrayOf(0.3f), MemorySource.ChatSession("sess-7")),
            chunk(3, "gamma", floatArrayOf(0.4f, 0.5f, 0.6f), MemorySource.Compaction(listOf(10L, 11L))),
            chunk(4, "delta", floatArrayOf(0.7f)),
        )

        val json = MemoryJsonSerializer.serialize(chunks, embeddingProviderId = "use", exportedAt = 99L)
        val outcome = MemoryJsonSerializer.parse(json)

        assertTrue(outcome is MemoryImportOutcome.Success)
        val document = (outcome as MemoryImportOutcome.Success).document
        assertEquals("use", document.embeddingProviderId)
        assertEquals(99L, document.exportedAt)
        // Changed on purpose (security audit 07/F3): this test once asserted the
        // pin came back, which is exactly what let a file pin its own text.
        assertEquals(chunks.map { it.copy(isPinned = false) }, document.chunks)
        assertEquals(1, document.pinnedInFile)
    }

    @Test
    fun `serialize emits the current schema version`() {
        val json = MemoryJsonSerializer.serialize(emptyList(), embeddingProviderId = "use", exportedAt = 0L)
        // An empty export still parses cleanly (Success with zero chunks).
        val outcome = MemoryJsonSerializer.parse(json)
        assertTrue(outcome is MemoryImportOutcome.Success)
        assertEquals(0, (outcome as MemoryImportOutcome.Success).document.chunks.size)
    }

    @Test
    fun `parse returns Failure on malformed JSON`() {
        val outcome = MemoryJsonSerializer.parse("{ not json")
        assertTrue(outcome is MemoryImportOutcome.Failure)
    }

    @Test
    fun `parse returns Failure when schemaVersion is missing`() {
        val outcome = MemoryJsonSerializer.parse("""{"embeddingProviderId":"use","chunks":[]}""")
        assertTrue(outcome is MemoryImportOutcome.Failure)
    }

    @Test
    fun `parse returns Failure when embeddingProviderId is missing`() {
        val outcome = MemoryJsonSerializer.parse("""{"schemaVersion":1,"chunks":[]}""")
        assertTrue(outcome is MemoryImportOutcome.Failure)
    }

    @Test
    fun `parse returns Failure when chunks array is missing`() {
        val outcome = MemoryJsonSerializer.parse("""{"schemaVersion":1,"embeddingProviderId":"use"}""")
        assertTrue(outcome is MemoryImportOutcome.Failure)
    }

    @Test
    fun `parse returns Failure when a chunk is missing a required field`() {
        val json = """
            {"schemaVersion":1,"embeddingProviderId":"use","exportedAt":0,
             "chunks":[{"id":1,"text":"x"}]}
        """.trimIndent()
        val outcome = MemoryJsonSerializer.parse(json)
        assertTrue(outcome is MemoryImportOutcome.Failure)
    }

    @Test
    fun `parse reports SchemaMismatch when the version differs but still parses`() {
        val older = """
            {"schemaVersion":99,"embeddingProviderId":"use","exportedAt":0,
             "chunks":[{"id":1,"text":"x","embedding":[0.1],"timestamp":5,"isPinned":false}]}
        """.trimIndent()
        val outcome = MemoryJsonSerializer.parse(older)
        assertTrue(outcome is MemoryImportOutcome.SchemaMismatch)
        val mismatch = outcome as MemoryImportOutcome.SchemaMismatch
        assertEquals(99, mismatch.foundVersion)
        assertEquals(MemoryJsonSerializer.CURRENT_SCHEMA_VERSION, mismatch.expectedVersion)
        assertEquals(1, mismatch.document.chunks.size)
    }

    @Test
    fun `parse rejects an empty embedding array`() {
        val json = """
            {"schemaVersion":1,"embeddingProviderId":"use","exportedAt":0,
             "chunks":[{"id":1,"text":"x","embedding":[],"timestamp":5}]}
        """.trimIndent()
        assertTrue(MemoryJsonSerializer.parse(json) is MemoryImportOutcome.Failure)
    }

    @Test
    fun `parse rejects a non-numeric embedding entry`() {
        val json = """
            {"schemaVersion":1,"embeddingProviderId":"use","exportedAt":0,
             "chunks":[{"id":1,"text":"x","embedding":[0.1,"oops",0.2],"timestamp":5}]}
        """.trimIndent()
        assertTrue(MemoryJsonSerializer.parse(json) is MemoryImportOutcome.Failure)
    }

    @Test
    fun `parse rejects a chunk with explicit null text`() {
        val json = """
            {"schemaVersion":1,"embeddingProviderId":"use","exportedAt":0,
             "chunks":[{"id":1,"text":null,"embedding":[0.1],"timestamp":5}]}
        """.trimIndent()
        assertTrue(MemoryJsonSerializer.parse(json) is MemoryImportOutcome.Failure)
    }

    @Test
    fun `parse rejects a chunk with blank text`() {
        val json = """
            {"schemaVersion":1,"embeddingProviderId":"use","exportedAt":0,
             "chunks":[{"id":1,"text":"  ","embedding":[0.1],"timestamp":5}]}
        """.trimIndent()
        assertTrue(MemoryJsonSerializer.parse(json) is MemoryImportOutcome.Failure)
    }

    @Test
    fun `parse rejects a non-numeric timestamp`() {
        val json = """
            {"schemaVersion":1,"embeddingProviderId":"use","exportedAt":0,
             "chunks":[{"id":1,"text":"x","embedding":[0.1],"timestamp":"oops"}]}
        """.trimIndent()
        assertTrue(MemoryJsonSerializer.parse(json) is MemoryImportOutcome.Failure)
    }

    @Test
    fun `parse rejects a non-positive timestamp`() {
        val json = """
            {"schemaVersion":1,"embeddingProviderId":"use","exportedAt":0,
             "chunks":[{"id":1,"text":"x","embedding":[0.1],"timestamp":0}]}
        """.trimIndent()
        assertTrue(MemoryJsonSerializer.parse(json) is MemoryImportOutcome.Failure)
    }

    @Test
    fun `parse does not throw on a malformed compaction ids entry`() {
        // A non-numeric ids element must not escape parse() as a JSONException;
        // it resolves to 0 and the chunk still parses.
        val json = """
            {"schemaVersion":1,"embeddingProviderId":"use","exportedAt":0,
             "chunks":[{"id":1,"text":"x","embedding":[0.1],"timestamp":5,
                        "source":{"type":"compaction","ids":["oops"]}}]}
        """.trimIndent()
        assertTrue(MemoryJsonSerializer.parse(json) is MemoryImportOutcome.Success)
    }

    @Test
    fun `parse refuses a file holding more chunks than the memory can keep`() {
        val tooMany = SettingsDefaults.MAX_MEMORY_CHUNKS_MAX + 1
        val chunks = (1..tooMany).joinToString(",") { """{"text":"m$it","embedding":[0.1],"timestamp":1}""" }
        val json = """{"schemaVersion":1,"embeddingProviderId":"use","chunks":[$chunks]}"""

        val outcome = MemoryJsonSerializer.parse(json)

        assertEquals(
            MemoryImportOutcome.Failure(
                "File contains $tooMany memory chunks, more than the " +
                    "${SettingsDefaults.MAX_MEMORY_CHUNKS_MAX} the memory can hold",
            ),
            outcome,
        )
    }

    @Test
    fun `parse accepts a file at exactly the chunk ceiling`() {
        val chunks = (1..SettingsDefaults.MAX_MEMORY_CHUNKS_MAX)
            .joinToString(",") { """{"text":"m$it","embedding":[0.1],"timestamp":1}""" }
        val json = """{"schemaVersion":1,"embeddingProviderId":"use","chunks":[$chunks]}"""

        val outcome = MemoryJsonSerializer.parse(json) as MemoryImportOutcome.Success

        assertEquals(SettingsDefaults.MAX_MEMORY_CHUNKS_MAX, outcome.document.chunks.size)
    }

    @Test
    fun `parse defaults a missing id to zero so Room assigns a fresh key`() {
        val json = """
            {"schemaVersion":1,"embeddingProviderId":"use","exportedAt":0,
             "chunks":[{"text":"x","embedding":[0.1],"timestamp":5}]}
        """.trimIndent()
        val outcome = MemoryJsonSerializer.parse(json)
        assertTrue(outcome is MemoryImportOutcome.Success)
        assertEquals(0L, (outcome as MemoryImportOutcome.Success).document.chunks.single().id)
    }

    @Test
    fun `parse defaults optional fields when absent`() {
        val json = """
            {"schemaVersion":1,"embeddingProviderId":"use","exportedAt":0,
             "chunks":[{"text":"x","embedding":[0.1],"timestamp":5}]}
        """.trimIndent()
        val outcome = MemoryJsonSerializer.parse(json)
        assertTrue(outcome is MemoryImportOutcome.Success)
        val chunk = (outcome as MemoryImportOutcome.Success).document.chunks.single()
        assertEquals(false, chunk.isPinned)
        assertEquals(emptyList<String>(), chunk.tags)
        assertEquals(MemorySource.Unknown, chunk.source)
    }

    // --- What a file may claim (security audit 07/F3) ---

    @Test
    fun `parse does not honour isPinned from the document`() {
        // A pinned chunk skips the similarity threshold, sorts first in every
        // retrieval and is exempt from compaction; a file must not grant that.
        val json = """
            {"schemaVersion":1,"embeddingProviderId":"use","exportedAt":0,
             "chunks":[{"text":"x","embedding":[0.1],"timestamp":5,"isPinned":true}]}
        """.trimIndent()

        val outcome = MemoryJsonSerializer.parse(json)

        assertTrue(outcome is MemoryImportOutcome.Success)
        val document = (outcome as MemoryImportOutcome.Success).document
        assertFalse(document.chunks.single().isPinned)
        assertEquals(1, document.pinnedInFile)
    }

    @Test
    fun `parse re-dates a timestamp in the future to the moment of import`() {
        // Year 2100: would hold the head of $MEMORY_SUMMARY and stay out of the
        // compaction window for as long as the device lives.
        val json = """
            {"schemaVersion":1,"embeddingProviderId":"use","exportedAt":0,
             "chunks":[{"text":"x","embedding":[0.1],"timestamp":4102444800000}]}
        """.trimIndent()

        val outcome = MemoryJsonSerializer.parse(json, nowMillis = NOW)

        assertTrue(outcome is MemoryImportOutcome.Success)
        assertEquals(NOW, (outcome as MemoryImportOutcome.Success).document.chunks.single().timestamp)
    }

    @Test
    fun `parse keeps a past timestamp as written`() {
        val json = """
            {"schemaVersion":1,"embeddingProviderId":"use","exportedAt":0,
             "chunks":[{"text":"x","embedding":[0.1],"timestamp":${NOW - 1}}]}
        """.trimIndent()

        val outcome = MemoryJsonSerializer.parse(json, nowMillis = NOW)

        assertEquals(NOW - 1, (outcome as MemoryImportOutcome.Success).document.chunks.single().timestamp)
    }

    @Test
    fun `a file without pins reports none`() {
        val json = MemoryJsonSerializer.serialize(listOf(chunk(1, "x", floatArrayOf(0.1f))), "use", exportedAt = 0L)

        assertEquals(0, (MemoryJsonSerializer.parse(json) as MemoryImportOutcome.Success).document.pinnedInFile)
    }

    @Test
    fun `every MemoryChunk field has a declared import policy`() {
        // Guard for the class of 07/F3: a field that decides whether a chunk is
        // retrieved (as isPinned and timestamp do) must not be taken from a file
        // by default. A new field fails here until someone decides what an
        // import does with it — and pins that decision below.
        val fields = MemoryChunk::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

        assertEquals(IMPORT_POLICY.keys, fields)
    }

    @Test
    fun `a document claiming every privilege yields a chunk holding none`() {
        val json = """
            {"schemaVersion":1,"embeddingProviderId":"use","exportedAt":0,
             "chunks":[{"id":7,"text":"x","embedding":[0.1],"timestamp":4102444800000,"isPinned":true,
                        "useCount":999,"lastUsedAt":4102444800000,"needsReembedding":false}]}
        """.trimIndent()

        val chunk = (MemoryJsonSerializer.parse(json, nowMillis = NOW) as MemoryImportOutcome.Success)
            .document.chunks.single()

        // One assertion per non-CARRIED policy in IMPORT_POLICY.
        assertEquals(NOW, chunk.timestamp)
        assertFalse(chunk.isPinned)
        assertEquals(0, chunk.useCount)
        assertEquals(null, chunk.lastUsedAt)
    }

    @Test
    fun `parse does not carry an id no export of this app could hold`() {
        // An id near Long.MAX_VALUE, inserted as is, moves the table's AUTOINCREMENT
        // counter to the end of its range: every later memory write fails, and
        // deleting the row does not reset the counter. Ids an export can carry
        // (positive, within Int range) still de-duplicate a Merge.
        fun idOf(id: String): Long = (
            MemoryJsonSerializer.parse(
                """{"schemaVersion":1,"embeddingProviderId":"use","exportedAt":0,
                   "chunks":[{"id":$id,"text":"x","embedding":[0.1],"timestamp":5}]}""",
            ) as MemoryImportOutcome.Success
            ).document.chunks.single().id

        assertEquals(0L, idOf(Long.MAX_VALUE.toString()))
        assertEquals(0L, idOf((Int.MAX_VALUE.toLong() + 1).toString()))
        assertEquals(0L, idOf("-5"))
        assertEquals(42L, idOf("42"))
        assertEquals(Int.MAX_VALUE.toLong(), idOf(Int.MAX_VALUE.toString()))
    }

    /** What an import does with each [MemoryChunk] field. */
    private enum class Policy {
        /** Taken from the file as written. */
        CARRIED,

        /** Taken from the file, bounded (a timestamp no later than the import). */
        CAPPED,

        /** Never taken from the file; always the fresh default. */
        RESET,
    }

    private companion object {
        const val NOW = 1_758_000_000_000L

        val IMPORT_POLICY = mapOf(
            "id" to Policy.CAPPED,
            "text" to Policy.CARRIED,
            "embedding" to Policy.CARRIED,
            "timestamp" to Policy.CAPPED,
            "isPinned" to Policy.RESET,
            "source" to Policy.CARRIED,
            "tags" to Policy.CARRIED,
            "useCount" to Policy.RESET,
            "lastUsedAt" to Policy.RESET,
        )
    }
}
