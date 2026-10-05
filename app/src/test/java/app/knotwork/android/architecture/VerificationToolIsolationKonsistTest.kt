package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The check of a past run cannot run a tool.
 *
 * **What it pins.** A run's check repeats its on-device model calls from their
 * recorded prompts and compares the answers; a tool's recorded output stands and
 * the tool is never run again (`decisions.md §75.2` R1). That rests on what the
 * check's classes can reach, and nothing else: no tool repository, gate or
 * executor, no node executor, not the graph engine that would dispatch one, no
 * MCP or AppFunctions client. So the classes of `domain/verification/` may depend
 * only on the listed types — a new dependency fails here, by name, before it can
 * become a path to a side effect happening twice.
 *
 * **Two checks.** Every constructor parameter of a class in the package must be
 * an allowed type, and no file in the package may name a forbidden one at all
 * (a tool reached through a parameter type that is allowed — a factory, a
 * provider — is still named somewhere in the file). The forbidden list is the
 * second line of defence; the allowlist is the first.
 */
class VerificationToolIsolationKonsistTest {

    @Test
    fun `the check's classes depend only on the record, the model registry and the on-device engine`() {
        val dependencies = ArchitectureScope.production.classes()
            .filter { it.resideInPackage(PACKAGE) }
            .flatMap { cls -> cls.primaryConstructor?.parameters.orEmpty().map { "${cls.name}(${it.type.name})" } }
            .toSortedSet()
        val unlisted = dependencies.filterNot { dependency -> ALLOWED.any { dependency.endsWith("($it)") } }

        assertTrue("the scan found no class in $PACKAGE — the package moved", dependencies.isNotEmpty())
        assertEquals(
            "a class of the run check takes a dependency outside the allowlist: $unlisted. The check repeats " +
                "recorded model calls and nothing else; it must not be able to reach a tool, an executor or the " +
                "graph engine. If the dependency cannot run anything, add it here with the reason.",
            emptyList<String>(),
            unlisted,
        )
    }

    @Test
    fun `no file of the check names a tool, an executor or the graph engine`() {
        val token = Regex("""\b(${FORBIDDEN.joinToString("|")})\b""")
        val named = ProductionSources.code
            .filterKeys { it.contains(PACKAGE_PATH) }
            .mapValues { (_, code) -> token.findAll(code).map { it.value }.toSortedSet() }
            .filterValues { it.isNotEmpty() }

        assertTrue(
            "the scan found no file under $PACKAGE_PATH",
            ProductionSources.code.keys.any {
                it.contains(PACKAGE_PATH)
            },
        )
        assertEquals("the run check names what could run a tool: $named", emptyMap<String, Set<String>>(), named)
    }

    private companion object {
        const val PACKAGE = "..domain.verification.."
        const val PACKAGE_PATH = "/domain/verification/"

        /**
         * What a class of the check may take: the run record and its trace, the
         * model registry and pipelines (for names and checksums), the generation
         * settings (the backend and window a load would use), the model loader and
         * the on-device engine — the one thing the check runs — and the package's
         * own readers and renderer, which read the record and format it.
         */
        val ALLOWED = setOf(
            "PipelineRunRepository",
            "RunTraceRepository",
            "LocalModelRepository",
            "PipelineRepository",
            "GenerationSettings",
            "LoadModelUseCase",
            "LlmInferenceEngine",
            "ReadRecordedRunTreeUseCase",
            "BuildRunTraceExportUseCase",
            "PlanRunVerificationUseCase",
            "PlanRunAgainWithSeedUseCase",
            // Value types of the check's own models.
            "String",
            "Int",
            "Long",
            "Boolean",
            "RunHeader",
            "LocalBackend",
            "NotVerifiableReason",
            "NotPromisedReason",
            "VisitKind",
            "VerifyMismatch",
            "VerificationPlan",
            "VerificationSummary",
            "NodeVerdict",
            "List<PlannedVisit>",
            "List<RunTraceRecord.LocalModelCall>",
            "Set<LocalBackend>",
            "PipelineRun",
            "Map<String, PipelineRun>",
            "Map<String, List<RunTraceRecord>>",
            "LocalSampling",
            "RunAgainRequest",
            "RunAgainNotOfferedReason",
            "List<RunModelUse>",
            "Reproducibility",
            "VerifyAvailability",
            "RunAgainAvailability",
        )

        /** What could run a tool, or dispatch a node that does. */
        val FORBIDDEN = listOf(
            "ToolRepository",
            "ToolInvocationGate",
            "LocalToolExecutor",
            "ToolNodeExecutor",
            "SkillNodeExecutor",
            "NodeExecutor",
            "NodeExecutorFactory",
            "GraphExecutionEngine",
            "TaskQueueManager",
            "McpConnectionPool",
            "LocalAppFunctionManager",
            "CloudLlmClientFactory",
        )
    }
}
