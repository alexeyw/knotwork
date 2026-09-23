package app.knotwork.android.domain.constants

import app.knotwork.android.domain.models.NodeType

/**
 * Canonical, code-level storage for every default LLM prompt and prompt template
 * used by the agent's pipeline executors.
 *
 * **Two distinct kinds of strings live here:**
 *
 *  - **`SYSTEM_FALLBACK`** — the default `systemPrompt` for a node when the user
 *    has not configured one (i.e. `node.systemPrompt == null`). They are emitted
 *    verbatim into the LLM input.
 *  - **`*_TEMPLATE`** — internal wrap-templates used by an executor to splice the
 *    upstream `inputText`, the resolved `systemPrompt`, and other per-node values
 *    into a final LLM prompt. Placeholders use the literal `${'$'}KEY` form
 *    (e.g. `${'$'}INPUT_TEXT`, `${'$'}ORIGINAL_TASK`) and are substituted via
 *    [renderTemplate] in a **single left-to-right pass** so a value that itself
 *    contains a `${'$'}KEY` token cannot trigger a follow-up replacement and
 *    corrupt downstream substitutions. They are **not** routed through
 *    [app.knotwork.android.domain.prompt.PromptTemplateEngine] — that engine is
 *    reserved for runtime-resolved variables (`${'$'}DATE`, `${'$'}TOOLS`, …) that
 *    apply to user-authored prompts.
 *
 * The historical flat constants (`SYSTEM_PROMPT_PREFIX`, `INTENT_ROUTER_PROMPT`,
 * etc.) are kept in place because they are still consumed by
 * `DefaultPipelineFactory`, `ClarificationNodeExecutor`, and the browser-side
 * `pipeline-editor.html` mirror. The per-node sub-objects expose the same texts
 * by reference to avoid drift.
 */
object DefaultPrompts {
    /**
     * Matches a literal `${'$'}KEY` placeholder where `KEY` follows the project-wide
     * variable convention `[A-Z_][A-Z0-9_]*`. Lowercase tokens or sequences
     * like `${'$'}50` are deliberately not matched.
     */
    private val PLACEHOLDER_REGEX = Regex("\\\$([A-Z_][A-Z0-9_]*)")

    /**
     * Substitutes `${'$'}KEY` placeholders inside [template] with values from
     * [values] in a **single left-to-right pass** that does not rescan replaced
     * text. This is critical when a substituted value (for example, a user-authored
     * `systemPrompt` or a runtime `originalPrompt`) itself contains a literal
     * `${'$'}KEY` token — a chained `String.replace` sequence would treat such a
     * token as a follow-up placeholder and corrupt the prompt.
     *
     * Unknown placeholders are left verbatim — same defensive behavior as
     * [app.knotwork.android.domain.prompt.PromptTemplateEngine] applied to runtime
     * variables.
     *
     * @param template Template string containing zero or more `${'$'}KEY` placeholders.
     * @param values Map from placeholder key (no leading `${'$'}`) to its replacement.
     * @return The template with every recognised placeholder substituted exactly once.
     */
    fun renderTemplate(template: String, values: Map<String, String>): String =
        PLACEHOLDER_REGEX.replace(template) { match ->
            // Regex.replace builds the result by appending matched-region replacements
            // to the un-matched prefix; the replacement string itself is not re-scanned,
            // so a value containing "$OTHER_KEY" stays literal in the output.
            val key = match.groupValues[1]
            values[key] ?: match.value
        }

    /**
     * Generic preamble injected at the top of every LiteRT/Cloud system prompt
     * (`${'$'}systemPromptPrefix\n${'$'}nodeSystemPrompt\n` — see
     * [LiteRtNodeExecutor]). Stored in `SettingsRepository.systemPromptPrefix`
     * with this value as its first-launch default.
     */
    const val SYSTEM_PROMPT_PREFIX = "You are a helpful AI assistant running on an Android device."

    /**
     * Prompt text used as the effective user message when an image is sent with
     * no caption. Image-only messages are allowed, but the pipeline graph only
     * carries text, so this internal default becomes the prompt that travels the
     * graph (the chat bubble still shows just the thumbnail). Wiring the image
     * itself into the first inference node is a later, multimodal-inference
     * concern; this constant only ensures the run has a sensible instruction.
     */
    const val IMAGE_ONLY_DEFAULT_INSTRUCTION = "Describe what you see in this image."

    /**
     * Instruction sent alongside an audio clip when transcribing voice input.
     * Transcription is a **preprocessing** step run by the active multimodal
     * model before any pipeline: the audio never travels the execution graph —
     * only this transcript text does, landing in the composer as an editable
     * message the user reviews before sending. The instruction asks for a
     * verbatim transcript and nothing else (no commentary, no translation) so
     * the model's output can be used directly as the message text.
     */
    const val AUDIO_TRANSCRIPTION_INSTRUCTION =
        "Transcribe the spoken words in this audio exactly as said. " +
            "Output only the transcript text, with no added commentary, labels, or translation."

    /**
     * Fixed prompt used by the model benchmark
     * ([app.knotwork.android.domain.usecases.RunBenchmarkUseCase]). It is held
     * constant so successive benchmark runs — and runs across different models —
     * stay comparable: the only variables are the device and the model, never the
     * input. It asks for a short, self-contained generation so the measured run
     * exercises real decoding without dragging on, while still producing enough
     * tokens to derive a stable decode-speed figure. The benchmark never shows
     * this text or the model's answer to the user; only the timing numbers
     * surface.
     */
    const val BENCHMARK_PROMPT =
        "Write a short, friendly paragraph (about four sentences) explaining what " +
            "an AI assistant running entirely on a phone can do for its user."

    /**
     * Tool-usage instruction surfaced to user-authored prompts when the agent
     * wants to advertise the available tools. The literal `[TOOL_LIST]`
     * placeholder is substituted by the caller (currently consumed by
     * `pipeline-editor.html` as a copy-pasteable template snippet).
     */
    val TOOL_USAGE_INSTRUCTION = """
        You have access to the following tools:
        [TOOL_LIST]

        To use a tool, output a JSON block like this:
        ```json
        {
          "tool": "tool_name",
          "arguments": "{ \"param\": \"value\" }"
        }
        ```
        If you don't need to use a tool, just answer the user directly.
    """.trimIndent()

    /**
     * The language rule for a node whose output is read by the pipeline rather
     * than by the user.
     *
     * A model handed a Russian question answers in Russian, and keeps doing so
     * for every step downstream — which is correct for prose and wrong for
     * everything else. A routing keyword, a subtask list, a tool name, a tool
     * argument or a file path in the user's language stops matching what the
     * code compares it against, so tool calls fail and routers fall through to
     * their default branch. The failure is silent: the pipeline runs, it just
     * takes the wrong path or calls nothing.
     *
     * Appended to every prompt whose output is machine-facing. The step that
     * writes the user-visible answer carries [USER_LANGUAGE_RULE] instead, so
     * the user still reads their own language.
     */
    const val INTERNAL_ENGLISH_RULE = "Write your entire output in English, whatever language the user " +
        "writes in. This step's output is read by later steps, not by the user — the step that writes " +
        "the final answer translates it back into the user's language."

    /**
     * The language rule for a node whose output the user actually reads — the
     * final answer, a clarifying question, a persona's reply.
     *
     * The counterpart to [INTERNAL_ENGLISH_RULE]: internal steps run in English,
     * and the language comes back here. Stated explicitly rather than left to
     * the model, because by this point the input it is working from is usually
     * English, so "continue in the input's language" would give the user an
     * English answer to a Russian question.
     */
    const val USER_LANGUAGE_RULE = "Reply in the user's language (\$LANG), even when the material you " +
        "were given is in English."

    /**
     * Default `systemPrompt` for an [NodeType.INTENT_ROUTER] node. The model is
     * expected to emit one of the four keywords (`Simple`, `Data`, `Complex`,
     * `Task`) as its full reply — the router uses the response as a routing key.
     */
    const val INTENT_ROUTER_PROMPT = "You are an Intent Router. Analyze the user input and determine its category. " +
        "Output strictly ONE of the following keywords:\n" +
        "- Simple (if it's a simple chat message or greeting)\n" +
        "- Data (if it requires searching the web or current data)\n" +
        "- Complex (if it requires complex coding, math, or deep reasoning)\n" +
        "- Task (if it's a multi-step task or requires executing an action/tool)\n" +
        "The keywords above are English literals: output the keyword exactly as written, in English, " +
        "whatever language the user writes in — the router matches on the literal text."

    /**
     * Default `systemPrompt` for an [NodeType.DECOMPOSITION] node. The model is
     * expected to return a JSON array of strings — each string is one subtask
     * that downstream `QUEUE_PROCESSOR` iterates over.
     */
    const val DECOMPOSITION_PROMPT = "You are a Task Decomposer. Break down the given complex task into a list " +
        "of simpler, actionable subtasks. Output the result as a JSON array of strings. " +
        INTERNAL_ENGLISH_RULE

    /**
     * Default `systemPrompt` for an [NodeType.EVALUATION] node. The model is
     * expected to inspect a subtask's result and report success / what went wrong.
     */
    const val EVALUATION_PROMPT = "You are a Task Evaluator. Analyze the result of the executed subtask and " +
        "determine if it was successful. Begin your reply with a single verdict token on its own first line — " +
        "exactly one of PASS, RETRY, or FAIL — then explain your reasoning. Use PASS when the result satisfies " +
        "the task, RETRY when another attempt could plausibly fix it, and FAIL when the task cannot be completed. " +
        "The verdict routes the pipeline through the node's matching output port. " +
        INTERNAL_ENGLISH_RULE

    /**
     * Default `systemPrompt` for an [NodeType.SUMMARY] node. The model is
     * expected to synthesise multiple subtask results into a single coherent
     * answer. See [Summary.SYNTHESIS_TEMPLATE] for the wrap-template that
     * surrounds this prompt at execution time.
     */
    const val SUMMARY_PROMPT = "You are a Summarizer. Given the results of multiple executed subtasks, provide " +
        "a concise and comprehensive summary of the overall outcome. " + USER_LANGUAGE_RULE

    /**
     * Opt-in formatter `systemPrompt` for an [NodeType.OUTPUT] node.
     *
     * This is **not** the default for a fresh OUTPUT node — a node without a
     * `systemPrompt` runs in pass-through mode and forwards the upstream text
     * verbatim (see [getDefaultPromptForNodeType]). This constant is offered as a
     * selectable prompt template so a user can explicitly turn an OUTPUT node into
     * a formatting pass. See [Output.FORMATTING_TEMPLATE] for the wrap-template
     * that embeds this prompt before sending to the LLM.
     */
    const val OUTPUT_FORMAT_PROMPT = "You are a Formatter. Please format the provided input text into a clear, " +
        "readable markdown response for the user. " + USER_LANGUAGE_RULE

    /**
     * Default instruction for a [NodeType.CLARIFICATION] node.
     *
     * Tells the LLM to inspect the upstream context and produce a clarification
     * question — together with an optional list of answer options — as strict JSON.
     * The executor parses the JSON and forwards it to the user; an empty `options`
     * array means "free-form input expected".
     */
    const val CLARIFICATION_PROMPT = "You are a Clarification Generator. Inspect the user's " +
        "request and the upstream context, then craft ONE concise clarifying question that " +
        "would help the agent proceed. If a small set of likely answers is obvious, list them " +
        "as options; otherwise return an empty array to ask for free-form input. " +
        "Output STRICTLY valid JSON with this shape and nothing else:\n" +
        "{\n  \"question\": \"<the question to ask the user>\",\n  \"options\": [\"<option 1>\", \"<option 2>\"]\n}\n" +
        "The JSON keys are English literals; the question and the options are what the user reads, so " +
        "write those in the user's language (\$LANG)."

    /** Prompts for [NodeType.LITE_RT] (on-device inference) nodes. */
    object LiteRt {
        /**
         * Used as the node's `systemPrompt` when the user has not configured one.
         * Intentionally generic because the heavy-lifting domain-specific prompt
         * comes from [SYSTEM_PROMPT_PREFIX], which the executor concatenates ahead
         * of this fallback.
         */
        const val SYSTEM_FALLBACK = "You are a helpful AI assistant."
    }

    /** Prompts for [NodeType.CLOUD] (remote LLM provider) nodes. */
    object Cloud {
        /** @see LiteRt.SYSTEM_FALLBACK — same contract on the cloud path. */
        const val SYSTEM_FALLBACK = "You are a helpful AI assistant."
    }

    /**
     * Prompts for the legacy "system" node executor (used by
     * `INTENT_ROUTER`/`DECOMPOSITION`/`EVALUATION` paths in
     * [app.knotwork.android.domain.engine.executors.SystemNodeExecutor]).
     */
    object System {
        /** Used when no `systemPrompt` is set on a system-style node. */
        const val SYSTEM_FALLBACK = "You are an AI assistant."

        /**
         * One-line note appended to a system node's prompt by
         * [app.knotwork.android.domain.engine.executors.SystemNodeExecutor] when the run input
         * carries an image attachment. Only the boolean fact of an attachment travels the graph
         * (never the pixels), so a routing node can branch on "the user sent a picture" without
         * the image itself ever reaching it.
         */
        const val IMAGE_PRESENT_NOTE = "Note: the user's message includes an image attachment."
    }

    /** Prompts for [NodeType.OUTPUT] nodes. */
    object Output {
        /** @see DefaultPrompts.OUTPUT_FORMAT_PROMPT — re-exported for symmetry with other sub-objects. */
        const val SYSTEM_FALLBACK = OUTPUT_FORMAT_PROMPT

        /**
         * Wrap-template that the OUTPUT executor sends to the LLM.
         *
         * Placeholders (literal `${'$'}KEY` substituted via [String.replace]):
         *  - `${'$'}NODE_SYSTEM_PROMPT` — the resolved (user or fallback) system prompt for the node.
         *  - `${'$'}INPUT_TEXT` — the upstream node output to format.
         *
         * Expected response: the formatted answer with no conversational filler;
         * any "Here is the formatted output:" prefix is stripped by the executor
         * as a defensive heuristic.
         */
        const val FORMATTING_TEMPLATE = "\$NODE_SYSTEM_PROMPT\n\n" +
            "CRITICAL INSTRUCTION: Output ONLY the requested format. Do NOT include any conversational filler, " +
            "explanations, or preambles (e.g., \"Here is the formatted output:\").\n\n" +
            "INPUT: \$INPUT_TEXT\nFORMATTED OUTPUT: "
    }

    /** Prompts for [NodeType.SUMMARY] nodes. */
    object Summary {
        /**
         * Used when no `systemPrompt` is set on a SUMMARY node. Differs from
         * [DefaultPrompts.SUMMARY_PROMPT] (which is the user-facing default in
         * `DefaultPipelineFactory`) — this fallback fires only inside the executor
         * when the persisted node has no explicit prompt.
         */
        const val SYSTEM_FALLBACK = "You are an AI assistant responsible for summarizing the results of subtasks."

        /**
         * Wrap-template that the SUMMARY executor sends to the LLM.
         *
         * Placeholders (literal `${'$'}KEY` substituted via [String.replace]):
         *  - `${'$'}NODE_SYSTEM_PROMPT` — the resolved (user or fallback) system prompt.
         *  - `${'$'}ORIGINAL_TASK` — the original user message that started the run.
         *  - `${'$'}RESULTS_OF_SUBTASKS` — concatenated subtask outputs (the executor's `inputText`).
         *
         * Expected response: a single coherent answer to the original task that
         * actually uses the data from the subtask results (not just a list of
         * what each subtask did).
         */
        const val SYNTHESIS_TEMPLATE = "\$NODE_SYSTEM_PROMPT\n\n" +
            "CRITICAL INSTRUCTION: You must synthesize the provided results into a coherent final answer for the " +
            "original task. Do not just list what each task did. Answer the original task using the data from the " +
            "results.\n\n" +
            "ORIGINAL TASK: \$ORIGINAL_TASK\n\n" +
            "RESULTS OF SUBTASKS:\n\$RESULTS_OF_SUBTASKS\n\n" +
            "FINAL ANSWER: "
    }

    /** Prompts for [NodeType.INTENT_ROUTER] nodes. */
    object IntentRouter {
        /** @see DefaultPrompts.INTENT_ROUTER_PROMPT */
        const val SYSTEM_FALLBACK = INTENT_ROUTER_PROMPT
    }

    /** Prompts for [NodeType.DECOMPOSITION] nodes. */
    object Decomposition {
        /** @see DefaultPrompts.DECOMPOSITION_PROMPT */
        const val SYSTEM_FALLBACK = DECOMPOSITION_PROMPT
    }

    /** Prompts for [NodeType.EVALUATION] nodes. */
    object Evaluation {
        /** @see DefaultPrompts.EVALUATION_PROMPT */
        const val SYSTEM_FALLBACK = EVALUATION_PROMPT
    }

    /** Prompts for [NodeType.CLARIFICATION] nodes. */
    object Clarification {
        /** @see DefaultPrompts.CLARIFICATION_PROMPT */
        const val SYSTEM_FALLBACK = CLARIFICATION_PROMPT
    }

    /** Prompts for [NodeType.TOOL] nodes — internal (not user-customisable) wrap-templates. */
    object Tool {
        /**
         * Sent to the local LLM when the TOOL node is configured with `auto`
         * tool selection.
         *
         * Placeholders (literal `${'$'}KEY` substituted via [String.replace]):
         *  - `${'$'}AVAILABLE_TOOLS` — multi-line list of tools (`Tool: …\nDescription: …\nParameters: …`).
         *  - `${'$'}INPUT_TEXT` — the upstream task description.
         *
         * Expected response: strict JSON `{"tool": "...", "arguments": ...}`.
         */
        const val AUTO_SELECT_TEMPLATE =
            "You are an AI assistant that selects the best tool for a given task and generates arguments.\n" +
                "\n" +
                "AVAILABLE TOOLS:\n" +
                "\$AVAILABLE_TOOLS\n" +
                "\n" +
                "TASK:\n" +
                "\$INPUT_TEXT\n" +
                "\n" +
                "INSTRUCTIONS:\n" +
                "Choose the most appropriate tool to solve the task. \n" +
                "Generate strictly valid JSON with two fields: \"tool\" and \"arguments\".\n" +
                "\"tool\" should be the exact name of the selected tool.\n" +
                "\"arguments\" should contain the parameters matching the tool's schema.\n" +
                "\n" +
                "JSON OUTPUT: "

        /**
         * Sent to the local LLM when the TOOL node has a fixed `toolName` and we
         * only need the model to produce arguments.
         *
         * Placeholders (literal `${'$'}KEY` substituted via [String.replace]):
         *  - `${'$'}TOOL_NAME` — the tool's stable identifier.
         *  - `${'$'}TOOL_DESCRIPTION` — human description.
         *  - `${'$'}TOOL_PARAMETERS` — schema string.
         *  - `${'$'}INPUT_TEXT` — the upstream task description.
         *
         * Expected response: strict JSON for the tool's arguments (the executor
         * also accepts a `{"tool": "...", "arguments": ...}` envelope).
         */
        const val ARGUMENT_GENERATION_TEMPLATE =
            "You are an AI assistant that generates arguments for a specific tool.\n" +
                "\n" +
                "TOOL: \$TOOL_NAME\n" +
                "DESCRIPTION: \$TOOL_DESCRIPTION\n" +
                "PARAMETERS SCHEMA: \$TOOL_PARAMETERS\n" +
                "\n" +
                "TASK:\n" +
                "\$INPUT_TEXT\n" +
                "\n" +
                "INSTRUCTIONS:\n" +
                "Based on the task description, generate strictly valid JSON for the tool's " +
                "\"arguments\" according to its schema.\n" +
                "Do not wrap in anything else, just the JSON for the arguments. " +
                "If it's a primitive, output {\"tool\": \"\$TOOL_NAME\", \"arguments\": <value>}.\n" +
                "\n" +
                "JSON OUTPUT: "
    }

    /** Prompts for [NodeType.IF_CONDITION] nodes — internal wrap-template. */
    object IfCondition {
        /**
         * Sent to the local LLM when the IF_CONDITION node uses a free-form
         * `conditionPrompt` (as opposed to keyword/complexity branches).
         *
         * Placeholders (literal `${'$'}KEY` substituted via [String.replace]):
         *  - `${'$'}CONDITION_PROMPT` — the user-authored condition text.
         *  - `${'$'}INPUT_TEXT` — the upstream content to evaluate.
         *
         * Expected response: contains either `true` or `false` (case-insensitive
         * substring match on the trimmed reply).
         */
        const val EVALUATION_TEMPLATE =
            "Evaluate the following text against the condition: \"\$CONDITION_PROMPT\".\n" +
                "Text: \"\$INPUT_TEXT\"\n" +
                "Reply strictly with 'true' or 'false'."
    }

    /**
     * Prompts for the long-term memory **auto-extraction** pass
     * ([app.knotwork.android.domain.usecases.MemoryExtractionUseCase]).
     *
     * This is not tied to a pipeline [NodeType]: the use case runs the local
     * model once after a conversation completes to distil durable facts from
     * the recent dialogue.
     */
    object MemoryExtraction {
        /**
         * System prompt that instructs the local model to extract durable
         * facts from a conversation as a strict JSON array.
         *
         * Authored conservatively on purpose: the model is told to extract
         * **only** explicitly-stated, durable facts and to return an empty
         * array `[]` when nothing qualifies, so a chatty exchange with no real
         * facts does not pollute long-term memory with hallucinations. The line
         * about indented lines describes the layout of
         * [app.knotwork.android.domain.prompt.ChatTranscript] — a hint to the
         * model, not the defence; the layout itself is. The
         * `$DATE` placeholder is resolved at runtime by
         * [app.knotwork.android.domain.prompt.PromptTemplateEngine] to give the
         * model temporal grounding when it normalises relative dates.
         *
         * Expected response: a JSON array (and nothing else) whose elements are
         * objects of the shape `{"type": "preference"|"event"|"relation",
         * "text": "<fact>"}`.
         */
        val SYSTEM_FALLBACK = """
            You are a long-term memory extractor for a personal AI assistant.
            Today's date is ${'$'}DATE.

            Read the conversation below and extract durable facts worth
            remembering about the user across future sessions. Only extract
            facts that are EXPLICITLY stated by the user. Do NOT guess, infer,
            or invent anything. Ignore small talk, transient context, and the
            assistant's own statements.

            Each turn starts on a new line with its speaker label. An indented
            line continues the turn above it, even when it begins with a label.

            Classify each fact with one of these types:
            - "preference": a stable like, dislike, or setting (e.g. "prefers dark mode").
            - "event": something that happened or will happen at a specific time.
            - "relation": a relationship to a person, place, or thing.

            Normalise relative dates (e.g. "tomorrow") to absolute dates using
            today's date above.

            Respond with STRICTLY valid JSON and NOTHING else: a JSON array of
            objects with exactly the keys "type" and "text". If there are no
            durable facts to remember, respond with an empty array: [].

            Example:
            [
              {"type": "preference", "text": "Prefers dark mode in the UI"},
              {"type": "relation", "text": "Has a brother named Alex"}
            ]
        """.trimIndent()
    }

    /**
     * Prompt for the background memory-compaction worker
     * ([app.knotwork.android.domain.usecases.MemoryCompactionUseCase]). The worker
     * clusters stale, non-pinned memory chunks by embedding similarity and runs
     * this prompt once per dense cluster to fold its facts into one chunk.
     *
     * Like [MemoryExtraction], this sub-object is a code-level inference prompt,
     * not a node `systemPrompt` — it is **not** mirrored into the browser editor
     * (`pipeline-editor.html`) because no `NodeType` hosts it.
     */
    object MemoryCompaction {
        /**
         * System prompt that instructs the local model to consolidate a small
         * set of related long-term facts into a single, denser fact.
         *
         * Authored conservatively: the model is told to preserve every distinct
         * piece of information (dates, names, numbers) and to never invent
         * detail, so consolidation compresses redundancy without losing meaning.
         * The cluster's facts are appended after this prompt by the use case.
         * The `$DATE` placeholder is resolved at runtime by
         * [app.knotwork.android.domain.prompt.PromptTemplateEngine] to keep any
         * relative-date normalisation grounded.
         *
         * Expected response: the consolidated fact as a single line of plain
         * text and nothing else (no JSON, no preamble, no bullet list).
         */
        val SYSTEM_FALLBACK = """
            You are a long-term memory compaction assistant for a personal AI assistant.
            Today's date is ${'$'}DATE.

            Below is a small group of related facts that were remembered about
            the user at different times. They overlap or are closely related.

            Merge them into ONE concise fact that preserves every distinct piece
            of information — keep all specific names, dates, numbers, and
            preferences. Do NOT invent, guess, or add anything that is not
            present in the facts below. Do NOT drop a detail just to make the
            sentence shorter. If the facts genuinely contradict each other,
            prefer the most recent wording.

            Respond with the single consolidated fact as plain text and NOTHING
            else: no JSON, no quotes, no bullet points, no explanation.
        """.trimIndent()
    }

    /**
     * Prompt for the background chat-history compressor
     * ([app.knotwork.android.domain.usecases.CompressChatHistoryUseCase]). When a
     * session's verbatim history outgrows the configured token budget, the
     * compressor summarises the tail older than the live window so the
     * `--- Earlier conversation (summarized) ---` context block can stand in for
     * those messages.
     *
     * Like [MemoryExtraction] / [MemoryCompaction], this sub-object is a
     * code-level inference prompt, not a node `systemPrompt` — it is **not**
     * mirrored into the browser editor (`pipeline-editor.html`) because no
     * `NodeType` hosts it.
     */
    object HistoryCompression {
        /**
         * System prompt that instructs the local model to fold an older slice of
         * a conversation (optionally on top of an existing running summary) into
         * one dense prose summary.
         *
         * Authored conservatively: the model is told to preserve concrete facts,
         * decisions, names, numbers, and any unresolved questions, and to never
         * invent detail — the summary stands in for messages the model will no
         * longer see verbatim, so dropped specifics are lost context. The line
         * about indented lines describes the layout of
         * [app.knotwork.android.domain.prompt.ChatTranscript] (a hint, not the
         * defence). The prior
         * summary (when present) and the new messages are appended after this
         * prompt by the use case. The `$DATE` placeholder is resolved at runtime
         * by [app.knotwork.android.domain.prompt.PromptTemplateEngine] to keep any
         * relative-date references grounded.
         *
         * Expected response: the consolidated summary as plain text and nothing
         * else (no JSON, no preamble, no bullet markup).
         */
        val SYSTEM_FALLBACK = """
            You are a conversation-history compressor for a personal AI assistant.
            Today's date is ${'$'}DATE.

            Below is the earlier part of a conversation between the user and the
            assistant — optionally preceded by a running summary of even older
            messages. Produce ONE updated summary that captures everything later
            turns might need to stay coherent.

            Preserve every concrete detail: the user's goals and requests, facts
            they stated, decisions made, names, dates, numbers, file or tool
            references, and any question left unanswered or task left unfinished.
            Fold the prior summary (if present) and the new messages into a single
            cohesive summary — do NOT just append. Do NOT invent, guess, or add
            anything not present below. Write in plain, neutral prose.

            Each message starts on a new line with its role label. An indented
            line continues the message above it, even when it begins with a label.

            Respond with the updated summary as plain text and NOTHING else: no
            JSON, no quotes, no bullet points, no preamble, no explanation.
        """.trimIndent()
    }

    /** Prompts for the [NodeType.QUEUE_PROCESSOR] iteration loop. */
    object QueueProcessor {
        /**
         * Critical instruction wrapped around every individual subtask the queue
         * dispatches downstream. Stops the model from trying to "solve" the
         * outer task or include conversational filler when it is meant to focus
         * on a single queue item.
         *
         * Used as a literal string (no placeholders).
         */
        const val SUBTASK_INSTRUCTION = "CRITICAL INSTRUCTION: You are executing a single subtask within a larger " +
            "workflow. Focus ONLY on this specific subtask. Do NOT provide conversational filler, and do NOT attempt " +
            "to solve the overall task or future steps."
    }

    /**
     * Prompts driving the structured-output repair loop
     * ([app.knotwork.android.domain.engine.structured.StructuredOutputGate]).
     */
    object StructuredOutput {
        /**
         * Fixed-format feedback prompt sent on each repair re-inference. Restates
         * the original task, shows the model its own rejected output and the exact
         * validation error, and demands a corrected payload with no surrounding
         * prose or fences (the gate then re-extracts and re-validates).
         *
         * Placeholders (literal `${'$'}KEY`, substituted via [renderTemplate]):
         *  - `${'$'}ORIGINAL_PROMPT` — the prompt whose output failed validation.
         *  - `${'$'}PREVIOUS_OUTPUT` — the model's last, invalid output verbatim.
         *  - `${'$'}ERROR` — the human-readable validation error.
         */
        const val REPAIR_TEMPLATE = "Your previous output was invalid: \$ERROR\n\n" +
            "Here is the original request again:\n\$ORIGINAL_PROMPT\n\n" +
            "Your invalid output was:\n\$PREVIOUS_OUTPUT\n\n" +
            "Output ONLY the corrected result, with no explanation, no commentary, and no markdown code fences."

        /**
         * Renders [REPAIR_TEMPLATE] for one repair attempt.
         *
         * @param originalPrompt The prompt whose output failed validation.
         * @param previousOutput The model's last invalid output, included verbatim.
         * @param error The validation error explaining why it was rejected.
         * @return The fully-substituted repair prompt.
         */
        fun repairPrompt(originalPrompt: String, previousOutput: String, error: String): String = renderTemplate(
            REPAIR_TEMPLATE,
            mapOf(
                "ORIGINAL_PROMPT" to originalPrompt,
                "PREVIOUS_OUTPUT" to previousOutput,
                "ERROR" to error,
            ),
        )
    }

    /**
     * Returns the default user-facing system prompt for a specific node type — the
     * value pre-seeded into `node.systemPrompt` by `DefaultPipelineFactory`.
     *
     * `null` for nodes that do not carry a `systemPrompt` field
     * (`INPUT`, `IF_CONDITION`, `TOOL`, `QUEUE_PROCESSOR`, `PIPELINE`, `SKILL`)
     * and for `OUTPUT`, which starts in pass-through mode by design.
     */
    fun getDefaultPromptForNodeType(type: NodeType): String? = when (type) {
        NodeType.INTENT_ROUTER -> INTENT_ROUTER_PROMPT
        NodeType.DECOMPOSITION -> DECOMPOSITION_PROMPT
        NodeType.EVALUATION -> EVALUATION_PROMPT
        NodeType.SUMMARY -> SUMMARY_PROMPT
        // OUTPUT intentionally has NO default systemPrompt: a fresh OUTPUT node runs
        // in pass-through (echo) mode, forwarding the upstream node's text verbatim
        // without an extra formatting LLM pass. This keeps simple pipelines clean —
        // the formatter behaviour is opt-in via the OUTPUT_FORMAT_PROMPT template.
        NodeType.OUTPUT -> null
        NodeType.CLARIFICATION -> CLARIFICATION_PROMPT
        NodeType.LITE_RT, NodeType.CLOUD -> SYSTEM_PROMPT_PREFIX
        else -> null
    }
}
