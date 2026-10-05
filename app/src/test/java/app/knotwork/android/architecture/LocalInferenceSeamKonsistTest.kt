package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Census of the places that call the on-device model directly.
 *
 * **What it pins.** A pipeline run is checkable only if every on-device call it
 * makes runs on the run's sampler and a seed derived from the run seed, and is
 * recorded. Both happen in one place: the node's `NodeInference`. A node executor
 * that called `LlmInferenceEngine.generateResponseStream` itself would run on a
 * random seed and leave no record — the run would look checkable and not be.
 * So the direct callers are an exact list:
 *
 *  - the two `NodeInference` implementations — the recording one a run hands
 *    each node, and the unrecorded one for work outside a run;
 *  - work that is never part of a run's answer: chat-history compression and
 *    memory compaction (after a run), the backend probe and the benchmark
 *    (Settings), and `search_tool` condensing a page — a tool's own model use is
 *    part of the tool, whose recorded output a check never re-runs.
 *
 * An unlisted caller fails, and so does a listed one that disappeared, so the
 * list cannot rot into an allowance for code that no longer exists.
 *
 * **Why text.** As in [HitlDispatchKonsistTest]: a call needs no import and
 * Konsist does not resolve call targets, so every occurrence of the name in the
 * production sources (comments removed) is counted, declarations excepted.
 */
class LocalInferenceSeamKonsistTest {

    @Test
    fun `every direct call of the on-device model is one of the known ones`() {
        assertEquals(
            "generateResponseStream is called from an unlisted place. Inside a pipeline run a node reaches the " +
                "on-device model only through scope.inference (NodeInference), which seeds the call from the run " +
                "seed and records it; outside a run, add the call site here with the reason it is never part of " +
                "a run's answer.",
            DIRECT_CALLERS,
            callingFiles(),
        )
    }

    @Test
    fun `no node executor calls the on-device model directly`() {
        val executors = callingFiles().filter { it.startsWith(EXECUTORS) || it.startsWith(STRUCTURED) }

        assertTrue("these executors bypass NodeInference: $executors", executors.isEmpty())
    }

    /** Files with a non-declaring occurrence of `generateResponseStream`, one entry per occurrence, sorted. */
    private fun callingFiles(): List<String> {
        val token = Regex("""\bgenerateResponseStream\b""")
        val declaration = Regex("""\bfun\s+$""")
        val sources = ProductionSources.code
        check(sources.keys.any { it.endsWith("/NodeInference.kt") }) { "production sources not found" }
        return sources.flatMap { (path, code) ->
            token.findAll(code).mapNotNull { match ->
                val before = code.substring(maxOf(0, match.range.first - LOOKBEHIND), match.range.first)
                path.takeUnless { declaration.containsMatchIn(before) }
            }.toList()
        }.sorted()
    }

    private companion object {

        /** How far before an occurrence to look for a `fun` keyword. */
        const val LOOKBEHIND = 40

        const val JAVA = "main/java/app/knotwork/android"
        const val EXECUTORS = "$JAVA/domain/engine/executors/"
        const val STRUCTURED = "$JAVA/domain/engine/structured/"

        /** One entry per call; the benchmark calls twice (warm-up and measured run). */
        val DIRECT_CALLERS = listOf(
            "$JAVA/data/tools/local/SearchTool.kt",
            "$JAVA/domain/engine/NodeInference.kt",
            "$JAVA/domain/engine/RecordingNodeInference.kt",
            "$JAVA/domain/usecases/CompressChatHistoryUseCase.kt",
            "$JAVA/domain/usecases/MemoryCompactionUseCase.kt",
            "$JAVA/domain/usecases/RunBenchmarkUseCase.kt",
            "$JAVA/domain/usecases/RunBenchmarkUseCase.kt",
            "$JAVA/domain/usecases/TestBackendUseCase.kt",
        ).sorted()
    }
}
