package app.knotwork.android.domain.verification

import app.knotwork.android.domain.engine.TraceHashing
import app.knotwork.android.domain.models.LocalSampling
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunSampler
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.models.RunTreeIds
import app.knotwork.android.domain.verification.VerificationFixtures.ROOT
import app.knotwork.android.domain.verification.VerificationFixtures.cloudCall
import app.knotwork.android.domain.verification.VerificationFixtures.console
import app.knotwork.android.domain.verification.VerificationFixtures.localCall
import app.knotwork.android.domain.verification.VerificationFixtures.nodeIo
import app.knotwork.android.domain.verification.VerificationFixtures.run
import app.knotwork.android.domain.verification.VerificationFixtures.tree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [RunDigest]: one hash for what a finished run produced — the same for a repeat
 * with the same content, whatever its ids, times, seeds or console lines, and
 * different as soon as one node's or one call's text differs, sub-pipelines included.
 */
class RunDigestTest {

    private val child = RunTreeIds.child(ROOT, "pipe", 0)

    /** A run: a model call, its node, a cloud node, a sub-pipeline with one call, and console lines. */
    private fun recorded(
        rootId: String = ROOT,
        answer: String = "answer",
        innerAnswer: String = "inner answer",
        shift: Long = 0L,
    ): RecordedRunTree {
        val childId = RunTreeIds.child(rootId, "pipe", 0)
        fun RunTraceRecord.LocalModelCall.moved() = copy(
            timestamp = timestamp + shift,
            durationMs = (durationMs ?: 0L) + shift,
            sampling = LocalSampling(RunSampler(0.1 + shift, 1, 0.5), seed = sampling.seed + shift.toInt()),
        )
        return tree(
            run(id = rootId),
            run(id = childId, parentRunId = rootId),
            traces = mapOf(
                rootId to listOf(
                    console(0, "started at $shift", runId = rootId),
                    localCall(1, "llm", output = answer, runId = rootId).moved(),
                    nodeIo(2, "llm", "LITE_RT", runId = rootId, output = answer).copy(timestamp = 99 + shift),
                    cloudCall(3, "cloud").copy(runId = rootId),
                    nodeIo(4, "cloud", "CLOUD", runId = rootId),
                    nodeIo(5, "pipe", "PIPELINE", runId = rootId),
                ),
                childId to listOf(localCall(0, "inner", output = innerAnswer, runId = childId).moved()),
            ),
        )
    }

    @Test
    fun `given two runs with the same content when digested then the digests match`() {
        val first = recorded()
        val repeat = recorded(rootId = "run-2", shift = 7L)

        assertEquals(RunDigest.of(first), RunDigest.of(repeat))
    }

    @Test
    fun `given one answer differs at the root or one level down when digested then the digests differ`() {
        val digest = RunDigest.of(recorded())

        assertNotEquals(digest, RunDigest.of(recorded(answer = "another answer")))
        assertNotEquals(digest, RunDigest.of(recorded(innerAnswer = "another inner answer")))
    }

    @Test
    fun `given a PIPELINE node that failed when digested then its sub-pipeline still counts`() {
        fun failedRun(innerAnswer: String) = tree(
            run(status = PipelineRunStatus.FAILED),
            run(id = child, parentRunId = ROOT),
            traces = mapOf(
                ROOT to listOf(localCall(0, "llm"), nodeIo(1, "llm", "LITE_RT")),
                child to listOf(localCall(0, "inner", output = innerAnswer, runId = child)),
            ),
        )

        assertNotEquals(RunDigest.of(failedRun("one")), RunDigest.of(failedRun("two")))
    }

    @Test
    fun `given a record without hashes when digested then its texts are hashed instead`() {
        val hashed = tree(run(), traces = mapOf(ROOT to listOf(nodeIo(0, "llm", "LITE_RT"))))
        val unhashed = tree(run(), traces = mapOf(ROOT to listOf(nodeIo(0, "llm", "LITE_RT", hashed = false))))

        assertEquals(RunDigest.of(hashed), RunDigest.of(unhashed))
    }

    @Test
    fun `given a run still going or one recorded before headers when digested then there is none`() {
        val records = mapOf(ROOT to listOf<RunTraceRecord>(localCall(0, "llm")))

        assertNull(RunDigest.of(tree(run(status = PipelineRunStatus.RUNNING), traces = records)))
        assertNull(RunDigest.of(tree(run(header = null), traces = records)))
    }

    @Test
    fun `given one on-device call when digested then the chain is the documented one`() {
        // Pins the scheme a reader of an export recomputes: a change here needs a new SCHEME.
        val call = localCall(0, "llm")
        val start = link("knotwork-digest-v1")
        val expected = link(start, "local", "llm", "LITE_RT", "0", "0", call.promptSha256, call.outputSha256)

        assertEquals(expected, RunDigest.of(tree(run(), traces = mapOf(ROOT to listOf(call)))))
    }

    /** One link, written out as the canon describes it: each field as `<length>:<field>`, then SHA-256. */
    private fun link(vararg fields: String): String =
        TraceHashing.sha256Hex(fields.joinToString(separator = "") { "${it.length}:$it" })
}
