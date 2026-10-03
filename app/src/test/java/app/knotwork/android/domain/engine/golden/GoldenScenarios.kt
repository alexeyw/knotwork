package app.knotwork.android.domain.engine.golden

import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.ToolApprovalPolicy

/**
 * The catalogue of golden scenarios: every bundled preset, every published recipe and every
 * fixture, each with at least one scenario, plus the branch and suspension scenarios the
 * engine's control flow needs pinned.
 */
internal object GoldenScenarios {

    /** Every scenario, in catalogue order. */
    val all: List<GoldenScenario> = presets() + recipes() + fixtures()

    private fun presets(): List<GoldenScenario> = listOf(
        preset("clarify_then_act", "answered", "A clarification answered while the run waits.", BOOK_TABLE) {
            clarifications = listOf(ClarificationAction.Answer("The second one"))
        },
        preset(
            "clarify_then_act",
            "parked-then-answered",
            "Nobody answers in time, the run parks, the answer arrives later and the run resumes from its checkpoint.",
            BOOK_TABLE,
        ) {
            clarifications = listOf(ClarificationAction.TimeOut)
            parkResolutions = listOf(ParkResolution.Answer("The first one"))
        },
        preset("cloud_assist", "default", "A cloud answer that sees chat history and long-term memory.", TRIP),
        preset(
            "cloud_assist",
            "compressed-history",
            "A long conversation reaches the cloud node as a stored summary plus the most recent turns.",
            TRIP,
        ) {
            settings = GoldenSettings(compressedHistory = true)
        },
        preset("local_only_qa", "default", "The shortest shipped path: one on-device answer.", MOUNTAIN),
        preset(
            "local_only_qa",
            "token-ceiling-paused-then-continued",
            "The interactive token ceiling binds after the answer; the user continues and the run finishes.",
            MOUNTAIN,
        ) {
            settings = GoldenSettings(maxTokens = 3)
            parkResolutions = listOf(ParkResolution.Continue)
        },
        preset("multi_step_research", "default", "A two-item plan worked through a queue by a cloud node.", RESEARCH),
        preset(
            "multi_step_research",
            "step-ceiling-paused-then-continued",
            "The interactive step ceiling binds inside the queue; the user continues and the run resumes.",
            RESEARCH,
        ) {
            settings = GoldenSettings(maxSteps = 5)
            parkResolutions = listOf(ParkResolution.Continue)
        },
        preset(
            "multi_step_research",
            "background-step-ceiling",
            "A background run pauses on the background ceiling, not the interactive one; continuing resumes it.",
            RESEARCH,
        ) {
            origin = RunOrigin.TRIGGER
            settings = GoldenSettings(maxStepsBackground = 5)
            parkResolutions = listOf(ParkResolution.Continue)
        },
        preset("routed_local_cloud", "simple", "The router keeps a simple question on the device.", MOUNTAIN),
        preset("routed_local_cloud", "complex", "The router sends a complex question to the cloud.", RESEARCH) {
            answers { on("router", "Complex") }
        },
        preset(
            "routed_local_cloud",
            "router-gives-up",
            "Every routing answer fails the gate; after its repairs the run takes the declared fallback class.",
            MOUNTAIN,
        ) {
            answers { onCalls("router", "I would rather not say.") }
        },
        preset("share_handler", "approved", "Shared text is structured and appended to a file once approved.", SHARED) {
            origin = RunOrigin.SHARE
            approvals = listOf(ApprovalAction.APPROVE)
        },
        preset("share_handler", "denied", "The append is denied; the run continues with the refusal.", SHARED) {
            origin = RunOrigin.SHARE
            approvals = listOf(ApprovalAction.DENY)
        },
        preset("showcase_full_agent", "chat", "Small talk takes the Chat branch.", "Hi! How are you today?"),
        preset(
            "showcase_full_agent",
            "factual-simple",
            "A simple factual question: one lookup, one grounded answer.",
            MOUNTAIN,
        ) {
            answers {
                on("node-3", "Factual")
                on("node-5", "False")
            }
        },
        preset(
            "showcase_full_agent",
            "factual-complex",
            "A complex factual question: a plan, a lookup per topic in a queue, a distillation and a synthesis.",
            RESEARCH,
        ) {
            answers {
                on("node-3", "Factual")
                on("node-5", "True")
            }
        },
        preset(
            "showcase_full_agent",
            "task-every-subpipeline",
            "A task planned into four subtasks, each routed to a different sub-pipeline.",
            PLAN_EVENING,
        ) {
            answers {
                on("node-3", "Task")
                on(
                    "node-13",
                    "[\"Find the museum's opening hours\", \"Book a table for two\", " +
                        "\"Draft the evening plan\", \"Pick the restaurant\"]",
                )
                on("node-15", "Lookup", "Act", "Process", "Clarify")
            }
            clarifications = listOf(ClarificationAction.Answer("The first one"))
        },
        preset(
            "showcase_full_agent",
            "task-nested-clarification-parked",
            "A clarification inside a sub-pipeline parks the whole stack; the answer resumes it from the checkpoint.",
            PLAN_EVENING,
        ) {
            answers {
                on("node-3", "Task")
                on("node-15", "Clarify", "Process", "Lookup")
            }
            clarifications = listOf(ClarificationAction.TimeOut)
            parkResolutions = listOf(ParkResolution.Answer("The second one"))
        },
        preset(
            "showcase_research_to_file",
            "approved",
            "Research written to a file once the write is approved.",
            RESEARCH,
        ) {
            approvals = listOf(ApprovalAction.APPROVE)
        },
        preset("styled_translation", "default", "One on-device translation.", "Translate 'good morning' into French."),
        preset("subtask_act", "default", "An auto-selected read-only tool runs without asking.", BOOK_TABLE),
        preset(
            "subtask_act",
            "destructive-tool-blocked",
            "With destructive tools blocked, a destructive pick is refused without asking and the run stops.",
            BOOK_TABLE,
        ) {
            settings = GoldenSettings(blockDestructiveTools = true)
            outcome = GoldenOutcome.ERROR
            answers {
                on("node-2", "{\"tool\": \"delete_file\", \"arguments\": {\"path\": \"plans/old.md\"}}")
            }
        },
        preset(
            "subtask_act",
            "picks-a-sensitive-tool-denied",
            "The model picks a tool that needs approval, and the user denies it.",
            BOOK_TABLE,
        ) {
            answers {
                on(
                    "node-2",
                    "{\"tool\": \"write_file\", \"arguments\": " +
                        "{\"path\": \"plans/booking.md\", \"content\": \"Table for two at eight.\"}}",
                )
            }
            approvals = listOf(ApprovalAction.DENY)
        },
        preset("subtask_clarify", "answered", "The clarifying sub-pipeline on its own.", BOOK_TABLE) {
            clarifications = listOf(ClarificationAction.Answer("The first one"))
        },
        preset("subtask_lookup", "default", "The lookup sub-pipeline on its own.", MOUNTAIN),
        preset(
            "subtask_lookup",
            "tool-arguments-give-up",
            "Every argument answer fails the gate; after its repairs the tool runs on the last raw answer.",
            MOUNTAIN,
        ) {
            answers { onCalls("node-3", "Search for the mountain, please.") }
        },
        preset(
            "subtask_lookup",
            "every-call-asks",
            "With the ask-for-every-call policy even a read-only lookup waits for approval.",
            MOUNTAIN,
        ) {
            settings = GoldenSettings(approvalPolicy = ToolApprovalPolicy.AllCalls)
            approvals = listOf(ApprovalAction.APPROVE)
        },
        preset("subtask_process", "default", "The processing sub-pipeline on its own.", PLAN_EVENING),
        preset("tool_using_react", "needs-a-lookup", "The condition asks for a lookup: tool, then summary.", MOUNTAIN),
        preset("tool_using_react", "answers-directly", "The condition says no lookup is needed.", MOUNTAIN) {
            answers { on("needs_tool", "False") }
        },
        preset(
            "tool_using_react",
            "condition-gives-up",
            "Every condition answer fails the gate; after its repairs the run takes the False branch.",
            MOUNTAIN,
        ) {
            answers { onCalls("needs_tool", "It depends.") }
        },
        preset(
            "tool_using_react",
            "reads-a-long-file",
            "A tool result longer than the read budget reaches the summary cut to that budget.",
            MOUNTAIN,
        ) {
            answers {
                on("tool", "{\"tool\": \"read_file\", \"arguments\": {\"path\": \"notes/long.md\"}}")
            }
        },
        preset(
            "virtual_companion_mood_router",
            "casual",
            "The companion reads memory, triages the mood and answers casually.",
            "Hey, I'm back from work.",
        ),
        preset(
            "virtual_companion_mood_router",
            "supportive",
            "The triage picks the supportive branch.",
            "Today was really hard.",
        ) {
            answers { on("node-3", "Supportive") }
        },
    )

    private fun recipes(): List<GoldenScenario> = listOf(
        recipe(
            "composition-bundle",
            "default",
            "A parent pipeline calls a second pipeline of the same bundle as one step.",
            MOUNTAIN,
            entryPipelineId = "recipe-composition-parent",
        ),
        recipe("decompose-and-queue", "default", "A plan worked item by item, then put together.", RESEARCH),
        recipe("intent-routing", "answer", "The router answers directly.", MOUNTAIN),
        recipe("intent-routing", "search", "The router looks the answer up first.", RESEARCH) {
            answers { on("router", "Search") }
        },
        recipe("intent-routing", "ask", "The router asks one question before answering.", BOOK_TABLE) {
            answers { on("router", "Ask") }
            clarifications = listOf(ClarificationAction.Answer("The second one"))
        },
        recipe("memory-aware-run", "chat", "In a chat, memory is searched with the user's message.", TRIP),
        recipe(
            "memory-aware-run",
            "terse-memory-log",
            "With verbose memory logging off, the console names the hits without their text.",
            TRIP,
        ) {
            settings = GoldenSettings(verboseMemoryLogging = false)
        },
        recipe(
            "memory-aware-run",
            "background-token-ceiling",
            "A background run pauses on the background token ceiling; continuing resumes it.",
            TRIP,
        ) {
            origin = RunOrigin.TRIGGER
            settings = GoldenSettings(maxTokensBackground = 4)
            parkResolutions = listOf(ParkResolution.Continue)
        },
        recipe(
            "memory-aware-run",
            "trigger",
            "In a background run, memory is searched with the pipeline's declared query, \$DATE rendered.",
            TRIP,
        ) {
            origin = RunOrigin.TRIGGER
        },
        recipe("tool-with-approval", "approved", "The note is written once approved.", NOTE) {
            approvals = listOf(ApprovalAction.APPROVE)
        },
        recipe("tool-with-approval", "denied", "The write is denied.", NOTE) {
            approvals = listOf(ApprovalAction.DENY)
        },
        recipe(
            "tool-with-approval",
            "never-prompt",
            "With the never-prompt policy a sensitive write runs without asking.",
            NOTE,
        ) {
            settings = GoldenSettings(approvalPolicy = ToolApprovalPolicy.NeverPrompt)
        },
        recipe(
            "tool-with-approval",
            "parked-then-approved",
            "Nobody answers in time, the run parks, and an approval given later resumes it.",
            NOTE,
        ) {
            approvals = listOf(ApprovalAction.LET_IT_PARK)
            parkResolutions = listOf(ParkResolution.Approve)
        },
        recipe(
            "tool-with-approval",
            "parked-then-denied",
            "Nobody answers in time, the run parks, and a denial given later resumes it.",
            NOTE,
        ) {
            approvals = listOf(ApprovalAction.LET_IT_PARK)
            parkResolutions = listOf(ParkResolution.Deny)
        },
    )

    private fun fixtures(): List<GoldenScenario> = listOf(
        fixture(
            "nesting_limit",
            "stops-at-the-ceiling",
            "A pipeline that runs itself goes as deep as the nesting ceiling allows, then fails.",
            MOUNTAIN,
        ) {
            outcome = GoldenOutcome.ERROR
        },
        fixture("evaluation_review", "pass", "The reviewer passes the draft.", MOUNTAIN),
        fixture("evaluation_review", "retry", "The reviewer asks for another go; the draft is revised.", MOUNTAIN) {
            answers { on("review", "Retry") }
        },
        fixture("evaluation_review", "fail", "The reviewer fails the draft.", MOUNTAIN) {
            answers { on("review", "Fail") }
        },
        fixture("skill_report", "text-answer", "The skill answers in text and calls no tool.", RESEARCH),
        fixture(
            "skill_report",
            "writes-the-report-approved",
            "The skill calls write_file, which its allowlist permits, and the user approves.",
            RESEARCH,
        ) {
            answers {
                on(
                    "report",
                    "{\"tool\": \"write_file\", \"arguments\": " +
                        "{\"path\": \"reports/golden.md\", \"content\": \"# Report\\nThe findings.\"}}",
                )
            }
            approvals = listOf(ApprovalAction.APPROVE)
        },
        fixture(
            "skill_report",
            "tool-outside-allowlist",
            "The skill calls a tool outside its allowlist; nothing executes.",
            RESEARCH,
        ) {
            answers {
                on("report", "{\"tool\": \"delete_file\", \"arguments\": {\"path\": \"reports/old.md\"}}")
            }
        },
    )

    private const val MOUNTAIN = "What is the tallest mountain on Earth?"
    private const val RESEARCH = "Compare how solar and wind power grew over the last decade."
    private const val TRIP = "What should I pack for my trip?"
    private const val BOOK_TABLE = "Book a table for dinner."
    private const val PLAN_EVENING = "Plan my Saturday evening in the city."
    private const val SHARED = "https://example.org/article — Ten habits of calm people"
    private const val NOTE = "Note that the boiler service is due in March."

    private fun preset(
        stem: String,
        name: String,
        description: String,
        prompt: String,
        configure: Builder.() -> Unit = {},
    ): GoldenScenario =
        scenario(GoldenPipelineSource(GoldenSourceKind.PRESET, stem), name, description, prompt, configure)

    private fun recipe(
        stem: String,
        name: String,
        description: String,
        prompt: String,
        entryPipelineId: String? = null,
        configure: Builder.() -> Unit = {},
    ): GoldenScenario = scenario(
        GoldenPipelineSource(GoldenSourceKind.RECIPE, stem, entryPipelineId),
        name,
        description,
        prompt,
        configure,
    )

    private fun fixture(
        stem: String,
        name: String,
        description: String,
        prompt: String,
        configure: Builder.() -> Unit = {},
    ): GoldenScenario =
        scenario(GoldenPipelineSource(GoldenSourceKind.FIXTURE, stem), name, description, prompt, configure)

    private fun scenario(
        source: GoldenPipelineSource,
        name: String,
        description: String,
        prompt: String,
        configure: Builder.() -> Unit,
    ): GoldenScenario = Builder(source, name, description, prompt).apply(configure).build()

    /**
     * Collects the optional parts of one scenario.
     *
     * @param source The pipeline the scenario runs.
     * @param name The scenario slug.
     * @param description What the scenario pins.
     * @param prompt The user message.
     */
    class Builder(
        private val source: GoldenPipelineSource,
        private val name: String,
        private val description: String,
        private val prompt: String,
    ) {
        /** Overrides of the default model answers. */
        var script: GoldenScript = GoldenScript()

        /** What started the run. */
        var origin: RunOrigin = RunOrigin.CHAT

        /** Live approval decisions, in order. */
        var approvals: List<ApprovalAction> = emptyList()

        /** Clarification answers, in order. */
        var clarifications: List<ClarificationAction> = emptyList()

        /** Resolutions of parked runs, in order. */
        var parkResolutions: List<ParkResolution> = emptyList()

        /** Settings that differ from the harness's own. */
        var settings: GoldenSettings = GoldenSettings()

        /** How the scenario must end. */
        var outcome: GoldenOutcome = GoldenOutcome.COMPLETED

        /**
         * Scripts model answers with the [GoldenScript.Builder] DSL.
         *
         * @param block Builder calls.
         */
        fun answers(block: GoldenScript.Builder.() -> Unit) {
            script = GoldenScript.of(block)
        }

        /** Builds the scenario. */
        fun build(): GoldenScenario = GoldenScenario(
            source = source,
            name = name,
            description = description,
            prompt = prompt,
            origin = origin,
            script = script,
            approvals = approvals,
            clarifications = clarifications,
            parkResolutions = parkResolutions,
            outcome = outcome,
            settings = settings,
        )
    }
}
