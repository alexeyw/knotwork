package app.knotwork.android.domain.engine.golden

import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.ToolApprovalPolicy

/**
 * One scripted run of one shipped pipeline through the real `GraphExecutionEngine`, and the
 * name of the golden file its trace is compared with.
 *
 * A scenario says only what differs from the harness defaults: which model answers to give a
 * node where the default would not take the branch under test, and how to settle each
 * suspension the run raises. A suspension the scenario does not settle fails the run loudly
 * instead of being answered by a guess, so a pipeline that starts asking for approval shows
 * up as a failing scenario, not as a silently approved call.
 *
 * @property source The pipeline the run starts from.
 * @property name Scenario slug; the golden file is `<source>/<name>.trace`.
 * @property description One sentence on what the scenario pins, copied into the golden file.
 * @property prompt The user message that starts the run.
 * @property origin What started the run; selects the ceilings and the memory-retrieval key.
 * @property script Model answers that override the per-node-type defaults.
 * @property approvals How to settle each live approval request, in the order they are raised.
 * @property clarifications How to answer each clarification request, in order.
 * @property parkResolutions What the user does about each parked run before it is resumed,
 *   in order. A run that parks with no resolution left ends the scenario parked.
 * @property outcome How the scenario must end; checked before the trace is compared, so a
 *   run that fails for a reason nobody intended can never be recorded as golden output.
 * @property settings The settings values that differ from the harness's own.
 */
internal data class GoldenScenario(
    val source: GoldenPipelineSource,
    val name: String,
    val description: String,
    val prompt: String,
    val origin: RunOrigin = RunOrigin.CHAT,
    val script: GoldenScript = GoldenScript(),
    val approvals: List<ApprovalAction> = emptyList(),
    val clarifications: List<ClarificationAction> = emptyList(),
    val parkResolutions: List<ParkResolution> = emptyList(),
    val outcome: GoldenOutcome = GoldenOutcome.COMPLETED,
    val settings: GoldenSettings = GoldenSettings(),
) {
    /** Path of the golden file relative to the traces directory. */
    val goldenPath: String get() = "${source.kind.directory}/${source.fileStem}/$name.trace"

    /** Human-readable id used as the parameterised test name. */
    override fun toString(): String = "${source.id}/$name"
}

/**
 * Where a scenario's pipeline comes from.
 *
 * @property kind Which shipped collection the file belongs to.
 * @property fileStem The file name without `.json`.
 * @property entryPipelineId For a multi-pipeline bundle, the id of the pipeline the run
 *   starts from; `null` for a single-pipeline file.
 */
internal data class GoldenPipelineSource(
    val kind: GoldenSourceKind,
    val fileStem: String,
    val entryPipelineId: String? = null,
) {
    /** Stable id printed in the golden file header. */
    val id: String get() = "${kind.directory}/$fileStem"
}

/**
 * The three collections the harness runs.
 *
 * @property directory The traces sub-directory of the collection.
 */
internal enum class GoldenSourceKind(val directory: String) {
    /** The bundled presets under `app/src/main/assets/presets/pipelines/`. */
    PRESET("presets"),

    /** The published recipes under `docs/recipes/`. */
    RECIPE("recipes"),

    /**
     * Test-only pipelines under `app/src/test/golden/fixtures/`, for the node types no
     * shipped pipeline uses, so every `NodeType` appears in some golden trace.
     */
    FIXTURE("fixtures"),
}

/** How a golden scenario must end. */
internal enum class GoldenOutcome {
    /** The last attempt ends in `Completed`. */
    COMPLETED,

    /** The last attempt ends in `Error`. */
    ERROR,

    /** The last attempt ends parked, with no resolution left. */
    PARKED,
}

/**
 * The settings a scenario runs with. The harness's numeric values are deliberately **not** the
 * app's defaults (`SettingsDefaults`), so a refactoring that replaced a settings read with the
 * shipped default changes the trace of every scenario the value reaches. The two switches
 * ([approvalPolicy], [blockDestructiveTools]) do take the app's defaults; dedicated scenarios
 * run the other values.
 *
 * @property approvalPolicy Which tool risks ask for approval.
 * @property blockDestructiveTools Whether destructive tools are refused outright.
 * @property maxSteps Step ceiling of an interactive run.
 * @property maxStepsBackground Step ceiling of a background run; different from [maxSteps] so
 *   a run that picks the wrong ceiling for its origin shows.
 * @property maxTokens Token ceiling of an interactive run. A token-ceiling scenario sets it far
 *   below the floor the settings store enforces on write (10 000): scripted answers are a few
 *   tokens long, and the engine applies whatever value the store returns. A floor moved into the
 *   ceiling resolver would turn those scenarios red, which is a change worth explaining.
 * @property maxTokensBackground Token ceiling of a background run, different from [maxTokens].
 * @property verboseMemoryLogging Whether the memory console line lists every hit.
 * @property compressedHistory Enables chat-history compression and seeds a 40-message
 *   conversation with a stored summary of its older turns, so the window planner compresses.
 */
internal data class GoldenSettings(
    val approvalPolicy: ToolApprovalPolicy = ToolApprovalPolicy.SensitiveOrDestructive,
    val blockDestructiveTools: Boolean = false,
    val maxSteps: Int = 60,
    val maxStepsBackground: Int = 40,
    val maxTokens: Int = 900_000,
    val maxTokensBackground: Int = 150_000,
    val verboseMemoryLogging: Boolean = true,
    val compressedHistory: Boolean = false,
) {
    /**
     * The values that differ from the harness defaults, for the golden file header.
     *
     * @return `harness defaults`, or the differing values.
     */
    fun describe(): String {
        val defaults = GoldenSettings()
        val changed = listOfNotNull(
            "approvalPolicy=$approvalPolicy".takeIf { approvalPolicy != defaults.approvalPolicy },
            "blockDestructiveTools=$blockDestructiveTools".takeIf {
                blockDestructiveTools != defaults.blockDestructiveTools
            },
            "maxSteps=$maxSteps".takeIf { maxSteps != defaults.maxSteps },
            "maxStepsBackground=$maxStepsBackground".takeIf { maxStepsBackground != defaults.maxStepsBackground },
            "maxTokens=$maxTokens".takeIf { maxTokens != defaults.maxTokens },
            "maxTokensBackground=$maxTokensBackground".takeIf { maxTokensBackground != defaults.maxTokensBackground },
            "verboseMemoryLogging=$verboseMemoryLogging".takeIf {
                verboseMemoryLogging != defaults.verboseMemoryLogging
            },
            "compressedHistory=$compressedHistory".takeIf { compressedHistory != defaults.compressedHistory },
        )
        return changed.joinToString(", ").ifEmpty { "harness defaults" }
    }
}

/** How the scenario settles a live approval request. */
internal enum class ApprovalAction {
    /** The user approves while the run is still waiting. */
    APPROVE,

    /** The user denies while the run is still waiting. */
    DENY,

    /** Nobody answers: the live window expires and the run parks. */
    LET_IT_PARK,
}

/** How the scenario answers a clarification request. */
internal sealed interface ClarificationAction {
    /**
     * The user answers while the run is still waiting.
     *
     * @property text The answer.
     */
    data class Answer(val text: String) : ClarificationAction

    /** Nobody answers in time: the run parks, or falls back to its default option. */
    data object TimeOut : ClarificationAction
}

/**
 * What the user does about a parked run. Each answer goes through the app's own use case
 * (`SubmitApprovalDecisionUseCase`, `SubmitClarificationAnswerUseCase`,
 * `SubmitCeilingDecisionUseCase`), which records it and asks `ResumePipelineRunUseCase` to
 * resume — so a resume the app would refuse is refused in the trace too.
 */
internal sealed interface ParkResolution {
    /** Approves the parked tool call. */
    data object Approve : ParkResolution

    /** Denies the parked tool call. */
    data object Deny : ParkResolution

    /**
     * Answers the parked clarification.
     *
     * @property text The answer.
     */
    data class Answer(val text: String) : ParkResolution

    /** Continues past the ceiling the run paused on: one more portion of that ceiling. */
    data object Continue : ParkResolution
}

/**
 * Model answers that override the harness defaults, keyed by pipeline, node, visit and call.
 *
 * A visit is one execution of a node within one run of one engine invocation (a queue loop
 * visits its item node several times; each run of a sub-pipeline counts its own visits); a
 * call is one model request within a visit (a structured-output repair is a second call). When
 * a visit has fewer scripted calls than it makes, the last scripted answer repeats.
 *
 * @property answers `"<pipelineId>/<nodeId>"` to visits to calls; a `null` pipeline id in the
 *   key, written as [ROOT], means the scenario's root pipeline.
 */
internal class GoldenScript(private val answers: Map<String, List<List<String>>> = emptyMap()) {

    /**
     * The scripted answer for one model call, or `null` to use the default.
     *
     * @param rootPipelineId Id of the scenario's root pipeline.
     * @param pipelineId Id of the pipeline the calling node belongs to.
     * @param nodeId The calling node.
     * @param visit One-based visit number of the node in this engine invocation.
     * @param call One-based model-call number within the visit.
     * @return The answer, or `null` when the scenario does not script this call.
     */
    fun answerFor(rootPipelineId: String, pipelineId: String, nodeId: String, visit: Int, call: Int): String? {
        val rootKey = "$ROOT/$nodeId".takeIf { pipelineId == rootPipelineId }
        val visits = answers["$pipelineId/$nodeId"] ?: rootKey?.let { answers[it] } ?: return null
        val calls = visits.getOrNull(visit - 1)?.takeIf { it.isNotEmpty() } ?: return null
        return calls.getOrNull(call - 1) ?: calls.last()
    }

    /**
     * Builder for a [GoldenScript].
     */
    class Builder {
        private val answers = mutableMapOf<String, MutableList<List<String>>>()

        /**
         * Scripts one answer per consecutive visit of [nodeId], starting with the first.
         *
         * @param nodeId The node.
         * @param visits One answer per visit.
         * @param pipelineId The pipeline the node belongs to; the root pipeline by default.
         */
        fun on(nodeId: String, vararg visits: String, pipelineId: String = ROOT) {
            val key = "$pipelineId/$nodeId"
            answers.getOrPut(key) { mutableListOf() } += visits.map { listOf(it) }
        }

        /**
         * Scripts the consecutive model calls of one visit of [nodeId] — a structured-output
         * repair sequence.
         *
         * @param nodeId The node.
         * @param calls One answer per call; the last one repeats.
         * @param visit One-based visit the calls belong to.
         * @param pipelineId The pipeline the node belongs to; the root pipeline by default.
         */
        fun onCalls(nodeId: String, vararg calls: String, visit: Int = 1, pipelineId: String = ROOT) {
            val visits = answers.getOrPut("$pipelineId/$nodeId") { mutableListOf() }
            while (visits.size < visit) visits += emptyList<String>()
            visits[visit - 1] = calls.toList()
        }

        /** Builds the script. */
        fun build(): GoldenScript = GoldenScript(answers.mapValues { it.value.toList() })
    }

    companion object {
        /** Key prefix meaning "the scenario's root pipeline", whatever its id. */
        const val ROOT: String = "<root>"

        /**
         * Builds a script with the [Builder] DSL.
         *
         * @param block Builder calls.
         * @return The script.
         */
        fun of(block: Builder.() -> Unit): GoldenScript = Builder().apply(block).build()
    }
}
