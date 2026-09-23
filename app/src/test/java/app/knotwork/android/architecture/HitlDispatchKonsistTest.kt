package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Census of the seams through which a tool call can take effect, and of the one
 * channel through which a human answer can reach the gate in front of them.
 *
 * **What it pins.** The human-in-the-loop gate (`ToolInvocationGate`) is only a
 * gate if nothing walks around it. That rests on four properties of the tree
 * that no compiler checks and a grep established once:
 *
 *  1. `executeTool` — the repository's, and the MCP client's behind it — is
 *     called from exactly two places: the gate, and the repository dispatching
 *     to an MCP server. A third caller would be a path to a tool's side effect
 *     that never asked.
 *  2. `invokeByName`, the AppFunctions dispatch, is called only by the
 *     repository.
 *  3. The built-in tool executors (every `LocalToolExecutor`) are named only by
 *     their own files, the DI module that binds them and the repository that
 *     dispatches to them. Anything else holding one could call `execute`.
 *  4. `resumeWithApproval` is called only along the delegation chain that
 *     starts at `SubmitApprovalDecisionUseCase` — the one place that addresses
 *     an answer to the request it was given for. A second caller would be a
 *     second decision channel with no such rule.
 *
 * **Why text, not imports or types.** A call needs no import (the receiver's
 * type arrives through a constructor, an entry point, a `with` block), and
 * Konsist does not resolve call targets. So every occurrence of each name is
 * counted — call, method reference, receiver-less call inside `with` — after
 * comments are removed (Kotlin block comments nest; KDoc names these methods
 * freely). The census is exact: an occurrence nobody listed fails, and so does
 * a listed one that disappeared, so the list cannot rot into an allowance for
 * code that no longer exists. A string literal is left in place: a name inside
 * one fails loudly, which is the safe direction.
 *
 * **Which files.** Every production source set of the module (`main`, the
 * flavours, the build types) — a guard over `src/main` alone is blind to the
 * flavour code that ships. Each flavour's unit-test task recompiles its own
 * flavour directory, so an edit there re-runs this test in that flavour's suite
 * rather than answering from a cached run.
 */
class HitlDispatchKonsistTest {

    @Test
    fun `every executeTool call site is one of the two known ones`() {
        assertEquals(
            "executeTool is called from an unlisted place. Every tool call must pass the HITL gate: route it " +
                "through ToolInvocationGate.dispatch, or — if this is a new transport behind the repository — add " +
                "the call site here with the reason it cannot bypass the gate.",
            EXECUTE_TOOL_CALLS,
            callSites("executeTool"),
        )
    }

    @Test
    fun `the AppFunctions dispatch is called only by the tool repository`() {
        assertEquals(
            "invokeByName runs an AppFunction; only ToolRepositoryImpl.executeTool (itself called only by the " +
                "gate) may call it.",
            INVOKE_BY_NAME_CALLS,
            callSites("invokeByName"),
        )
    }

    @Test
    fun `built-in tool executors are named only by the tool layer`() {
        val executors = localToolExecutors()
        val violations = executors.flatMap { (name, declaringFile) ->
            val allowed = EXECUTOR_REFERRERS + declaringFile + LOCAL_TOOL_EXECUTOR_FILE
            filesMentioning(name) - allowed
        } + (filesMentioning(LOCAL_TOOL_EXECUTOR) - EXECUTOR_REFERRERS - executors.values - LOCAL_TOOL_EXECUTOR_FILE)

        assertTrue(
            "these files reference a built-in tool executor outside the tool layer: ${violations.sorted()}. " +
                "Holding an executor is holding its execute(), which runs the tool without the HITL gate; " +
                "reach tools through ToolRepository.executeTool via the gate.",
            violations.isEmpty(),
        )
    }

    @Test
    fun `the executor census finds every class the type checker knows implements LocalToolExecutor`() {
        // Keeps the text scan of the rule above honest: a class header it failed
        // to parse would be an executor the rule never looks for.
        val byKonsist = ArchitectureScope.production.classes()
            .filter { it.hasParentWithName(LOCAL_TOOL_EXECUTOR) }
            .map { it.name }
            .toSet()

        assertTrue("Konsist found no executors at all — the scope is broken", byKonsist.isNotEmpty())
        assertTrue(
            "the text scan missed these executors: ${byKonsist - localToolExecutors().keys}",
            localToolExecutors().keys.containsAll(byKonsist),
        )
    }

    @Test
    fun `an approval reaches the gate only through SubmitApprovalDecisionUseCase`() {
        assertEquals(
            "resumeWithApproval is called from an unlisted place. An answer must be submitted through " +
                "SubmitApprovalDecisionUseCase, which settles only the request the answer was given for.",
            RESUME_WITH_APPROVAL_CALLS,
            callSites("resumeWithApproval"),
        )
    }

    /**
     * Every non-declaring occurrence of the identifier [name] across production
     * code, as `file -> receiver`, sorted. The receiver is the identifier before
     * `.` / `?.` / `::`, or empty for a receiver-less occurrence.
     */
    private fun callSites(name: String): List<CallSite> {
        val token = Regex("""\b${Regex.escape(name)}\b""")
        val declaration = Regex("""\bfun\s+(<[^>]*>\s*)?$""")
        val receiver = Regex("""(\w+)\s*(\?\.|\.|::)\s*$""")
        return productionSources().flatMap { (path, code) ->
            token.findAll(code).mapNotNull { match ->
                val before = code.substring(maxOf(0, match.range.first - LOOKBEHIND), match.range.first)
                if (declaration.containsMatchIn(before)) {
                    null
                } else {
                    CallSite(path, receiver.find(before)?.groupValues?.get(1).orEmpty())
                }
            }.toList()
        }.sortedWith(compareBy(CallSite::file, CallSite::receiver))
    }

    /** Paths of the production files whose code (comments removed) mentions the identifier [name]. */
    private fun filesMentioning(name: String): Set<String> {
        val token = Regex("""\b${Regex.escape(name)}\b""")
        return productionSources().filter { (_, code) -> token.containsMatchIn(code) }.keys
    }

    /** Every class declaring `LocalToolExecutor` among its supertypes, mapped to the file declaring it. */
    private fun localToolExecutors(): Map<String, String> {
        val header = Regex("""\bclass\s+(\w+)[^{]*?[:,]\s*$LOCAL_TOOL_EXECUTOR\b""")
        return productionSources().flatMap { (path, code) ->
            header.findAll(code).map { it.groupValues[1] to path }.toList()
        }.toMap()
    }

    /**
     * One occurrence of a guarded name.
     *
     * @property file Path under `app/src`, e.g. `main/java/.../ToolInvocationGate.kt`.
     * @property receiver Identifier the name is called on; empty when there is none.
     */
    private data class CallSite(val file: String, val receiver: String)

    private companion object {

        /** How far before an occurrence to look for its receiver or a `fun` keyword. */
        const val LOOKBEHIND = 80

        const val LOCAL_TOOL_EXECUTOR = "LocalToolExecutor"

        const val JAVA = "main/java/app/knotwork/android"

        const val GATE = "$JAVA/domain/engine/executors/ToolInvocationGate.kt"
        const val TOOL_REPOSITORY_IMPL = "$JAVA/data/repositories/ToolRepositoryImpl.kt"
        const val LOCAL_TOOL_EXECUTOR_FILE = "$JAVA/domain/repositories/LocalToolExecutor.kt"

        /** The gate's one call, and the repository's dispatch to a connected MCP server. */
        val EXECUTE_TOOL_CALLS = listOf(
            CallSite(TOOL_REPOSITORY_IMPL, "client"),
            CallSite(GATE, "toolRepository"),
        ).sortedWith(compareBy(CallSite::file, CallSite::receiver))

        val INVOKE_BY_NAME_CALLS = listOf(CallSite(TOOL_REPOSITORY_IMPL, "localAppFunctionManager"))

        /** The delegation chain from the decision use case down to the gate. */
        val RESUME_WITH_APPROVAL_CALLS = listOf(
            CallSite("$JAVA/data/engine/TaskQueueManagerImpl.kt", "graphExecutionEngine"),
            CallSite("$JAVA/domain/engine/GraphExecutionEngine.kt", "toolNodeExecutor"),
            CallSite("$JAVA/domain/engine/executors/ToolNodeExecutor.kt", "toolInvocationGate"),
            CallSite("$JAVA/domain/usecases/SubmitApprovalDecisionUseCase.kt", "taskQueueManager"),
        ).sortedWith(compareBy(CallSite::file, CallSite::receiver))

        /** Files besides an executor's own that may name it: the binding module and the dispatcher. */
        val EXECUTOR_REFERRERS = setOf("$JAVA/di/LocalToolsModule.kt", TOOL_REPOSITORY_IMPL)

        fun productionSources(): Map<String, String> = ProductionSources.code.also {
            check(GATE in it) { "production sources not found under ${ProductionSources.moduleDirectory()}/src" }
        }
    }
}
