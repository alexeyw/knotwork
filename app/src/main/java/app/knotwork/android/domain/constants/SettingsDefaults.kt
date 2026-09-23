package app.knotwork.android.domain.constants

import app.knotwork.android.domain.services.EmbeddingProvider

/**
 * Canonical default values for every user-tunable setting that lives behind
 * [app.knotwork.android.domain.repositories.SettingsRepository].
 *
 * Each `const val` is the **single source of truth** consumed by:
 *  - the DataStore-backed implementation
 *    ([app.knotwork.android.data.local.SettingsManager]) when a preference key has
 *    not yet been written;
 *  - the settings UI (`SettingsViewModel` / `SettingsScreen`) for slider bounds
 *    and reset-to-default actions;
 *  - the Ollama provider auto-detection path
 *    ([app.knotwork.android.data.local.ApiKeyManager]) when the user has not
 *    overridden the context-window size for a given model;
 *  - the visual orchestrator node-editor when seeding the timeout field of a
 *    fresh `CLARIFICATION` node.
 *
 * Centralising the defaults here keeps the production code, tests, and UI in
 * lock-step: bumping a default in one place automatically updates every caller.
 */
object SettingsDefaults {
    /** Maximum number of tokens to keep in the LLM context window by default. */
    const val MAX_CONTEXT_LENGTH_DEFAULT: Int = 4_096

    /** Default sampling temperature for local LLM generation. */
    const val TEMPERATURE_DEFAULT: Float = 0.7f

    /** Default `top-k` sampling parameter for local LLM generation. */
    const val TOP_K_DEFAULT: Int = 40

    /** Default `top-p` (nucleus sampling) parameter for local LLM generation. */
    const val TOP_P_DEFAULT: Float = 0.9f

    /**
     * Default length of the **live** phase of a tool-approval gate, in
     * milliseconds. `ToolInvocationGate` waits this long in-process for the
     * user's answer, then parks the run on a durable pending record.
     *
     * The name is historical and the DataStore key it backs cannot be renamed
     * without a migration for nothing; it does **not** bound the tool call
     * itself. The MCP round-trip has its own deadline in `KoogMcpClient`.
     */
    const val TOOL_CALL_TIMEOUT_MS_DEFAULT: Long = 60_000L

    /**
     * Lower bound of the live-approval slider, in milliseconds (5 s).
     *
     * Not a taste value: this window is how long the run holds an approval
     * request in-process before parking it durably, and a person answering from
     * a notification needs time to unlock the phone. Below roughly this, every
     * approval would park before it could be answered — which works, but turns
     * the fastest path into the slowest one.
     */
    const val TOOL_CALL_TIMEOUT_MS_MIN: Long = 5_000L

    /** Upper bound of the live-approval slider, in milliseconds (5 min). */
    const val TOOL_CALL_TIMEOUT_MS_MAX: Long = 300_000L

    /**
     * Default per-file size ceiling for the agent workspace, in bytes (5 MB).
     * Caps both the largest file a write will accept and the largest file a text
     * read will pull into memory wholesale.
     */
    const val WORKSPACE_MAX_FILE_SIZE_BYTES_DEFAULT: Long = 5L * 1024 * 1024

    /** Lower bound of the per-file workspace ceiling, in bytes (1 MB). */
    const val WORKSPACE_MAX_FILE_SIZE_BYTES_MIN: Long = 1L * 1024 * 1024

    /** Upper bound of the per-file workspace ceiling, in bytes (50 MB). */
    const val WORKSPACE_MAX_FILE_SIZE_BYTES_MAX: Long = 50L * 1024 * 1024

    /**
     * Default workspace-wide total-size ceiling, in bytes (100 MB). A write that
     * would push the workspace past this is refused, bounding how much device
     * storage a runaway pipeline can consume.
     */
    const val WORKSPACE_MAX_TOTAL_BYTES_DEFAULT: Long = 100L * 1024 * 1024

    /** Lower bound of the workspace-wide ceiling, in bytes (10 MB). */
    const val WORKSPACE_MAX_TOTAL_BYTES_MIN: Long = 10L * 1024 * 1024

    /** Upper bound of the workspace-wide ceiling, in bytes (1 GB). */
    const val WORKSPACE_MAX_TOTAL_BYTES_MAX: Long = 1024L * 1024 * 1024

    /**
     * Default budget, in **tokens**, of file content the `read_file` tool will
     * return in a single call. The tool converts this to a byte ceiling (≈ 4
     * bytes per token) and truncates the served window to it, so a single read
     * of a large file can never blow the local model's context window. Set to
     * roughly half of [MAX_CONTEXT_LENGTH_DEFAULT] so one read leaves room for
     * the prompt, chat history and other context blocks.
     */
    const val WORKSPACE_READ_TOKEN_BUDGET_DEFAULT: Int = 2_000

    /** Lower bound of the single-read token budget. */
    const val WORKSPACE_READ_TOKEN_BUDGET_MIN: Int = 200

    /** Upper bound of the single-read token budget. */
    const val WORKSPACE_READ_TOKEN_BUDGET_MAX: Int = 8_000

    /**
     * Default wall-clock timeout for a `CLARIFICATION` node's outstanding question,
     * in milliseconds. Mirrors [TOOL_CALL_TIMEOUT_MS_DEFAULT] today but is exposed
     * as a separate constant so the two can diverge without code-wide impact.
     */
    const val CLARIFICATION_TIMEOUT_MS_DEFAULT: Long = 60_000L

    /**
     * Default window, in hours, during which an interrupted pipeline run can
     * be resumed from its checkpoint. Older interrupted runs only offer the
     * regular discard path — their recorded context (chat history, memory,
     * tool observations) is increasingly stale, and replaying it as if no
     * time had passed gets less defensible the older the run is.
     */
    const val RESUME_MAX_AGE_HOURS_DEFAULT: Int = 48

    /** Lower bound enforced when the user edits the resume-window setting. */
    const val RESUME_MAX_AGE_HOURS_MIN: Int = 1

    /** Upper bound enforced when the user edits the resume-window setting. */
    const val RESUME_MAX_AGE_HOURS_MAX: Int = 168

    /**
     * Default window, in hours, during which a run parked on a persistent
     * HITL request (background approval or clarification) waits for the
     * user's response. The window counts from the moment the live in-process
     * waiting phase timed out; once it elapses, the maintenance pass fails
     * the run with an "Approval window expired" message — an unanswered
     * request must not keep a run (and its notification) alive forever.
     */
    const val BACKGROUND_APPROVAL_WINDOW_HOURS_DEFAULT: Int = 24

    /** Lower bound enforced when the user edits the background-approval-window setting. */
    const val BACKGROUND_APPROVAL_WINDOW_HOURS_MIN: Int = 1

    /** Upper bound enforced when the user edits the background-approval-window setting. */
    const val BACKGROUND_APPROVAL_WINDOW_HOURS_MAX: Int = 168

    /**
     * Default number of most-recent pipeline runs preserved per chat session
     * by the retention pass. Terminal runs (and their persisted traces, via
     * the `trace_steps` foreign-key cascade) beyond this count are deleted
     * during the daily maintenance window. Non-terminal runs — including runs
     * parked on a background approval or clarification — are never counted
     * against, nor removed by, retention.
     */
    const val TRACE_RETENTION_RUNS_PER_SESSION_DEFAULT: Int = 20

    /** Lower bound enforced when the user edits the runs-per-session retention setting. */
    const val TRACE_RETENTION_RUNS_PER_SESSION_MIN: Int = 5

    /** Upper bound enforced when the user edits the runs-per-session retention setting. */
    const val TRACE_RETENTION_RUNS_PER_SESSION_MAX: Int = 100

    /**
     * Default maximum age, in days, a terminal pipeline run (and its trace)
     * is kept before the retention pass deletes it regardless of the
     * per-session count. Bounds how long derived user content (per-node
     * inputs/outputs, console events) accumulates at rest.
     */
    const val TRACE_RETENTION_MAX_AGE_DAYS_DEFAULT: Int = 30

    /** Lower bound enforced when the user edits the max-age retention setting. */
    const val TRACE_RETENTION_MAX_AGE_DAYS_MIN: Int = 7

    /** Upper bound enforced when the user edits the max-age retention setting. */
    const val TRACE_RETENTION_MAX_AGE_DAYS_MAX: Int = 180

    /** Default maximum number of pipeline steps allowed per user request. */
    const val PIPELINE_MAX_STEPS_DEFAULT: Int = 15

    /** Lower bound enforced when the user edits the pipeline-max-steps setting. */
    const val PIPELINE_MAX_STEPS_MIN: Int = 5

    /** Upper bound enforced when the user edits the pipeline-max-steps setting. */
    const val PIPELINE_MAX_STEPS_MAX: Int = 100

    /**
     * Default step ceiling for runs nobody is watching — the background
     * origins (scheduler, quick tile, trigger, external automation).
     *
     * Deliberately equal to [PIPELINE_MAX_STEPS_DEFAULT] rather than lower.
     * The origin distinction that matters for an unattended run is what it
     * *spends*, and that is carried by the token ceiling below; shipping an
     * upgrade that silently shrinks the step budget of automations already
     * running in the field would break working pipelines to buy protection the
     * token axis already provides. The value is separately configurable so a
     * user who wants a tighter background bound has one.
     *
     * This constant is reached only when the user has configured **neither**
     * cap: until this key existed one setting governed every origin, so an
     * unset background value falls back to the configured interactive one
     * rather than here. Otherwise a user who had raised the cap would have seen
     * their background runs quietly reset to 15 by the upgrade.
     *
     * Shares the [PIPELINE_MAX_STEPS_MIN] / [PIPELINE_MAX_STEPS_MAX] clamps.
     */
    const val PIPELINE_MAX_STEPS_BACKGROUND_DEFAULT: Int = 15

    /**
     * Default token ceiling for an interactive run, across the whole run tree.
     *
     * Set high enough that it never fires by accident on a person's own
     * request — someone is watching an interactive run and can stop it — while
     * still bounding a pathological loop.
     */
    const val RUN_MAX_TOKENS_DEFAULT: Int = 1_000_000

    /**
     * Default token ceiling for a background run, across the whole run tree.
     *
     * An order of magnitude below the interactive default, because this is the
     * concrete failure the ceilings exist for: a trigger fires overnight, a
     * CLOUD node runs on the user's own provider key inside a loop, and the
     * bill arrives in the morning. Sized against the step ceiling — with 15
     * nodes it leaves well over six thousand tokens per node, comfortable for
     * a real background pipeline and tight for a runaway one.
     */
    const val RUN_MAX_TOKENS_BACKGROUND_DEFAULT: Int = 100_000

    /** Lower bound enforced when the user edits either run-token ceiling. */
    const val RUN_MAX_TOKENS_MIN: Int = 10_000

    /** Upper bound enforced when the user edits either run-token ceiling. */
    const val RUN_MAX_TOKENS_MAX: Int = 10_000_000

    /**
     * Default maximum nesting depth for PIPELINE-node composition: how many
     * levels of sub-pipeline a run may descend into before the recursion is
     * refused. Keeps composed pipelines comprehensible and bounds recursion.
     */
    const val PIPELINE_MAX_NESTING_DEPTH_DEFAULT: Int = 3

    /** Lower bound enforced when the user edits the pipeline-max-nesting-depth setting. */
    const val PIPELINE_MAX_NESTING_DEPTH_MIN: Int = 1

    /** Upper bound enforced when the user edits the pipeline-max-nesting-depth setting. */
    const val PIPELINE_MAX_NESTING_DEPTH_MAX: Int = 5

    /**
     * Default top-K for long-term memory retrieval: how many ranked chunks a
     * single search returns into a node's context block. The semantic search
     * itself always scans the full stored pool before ranking.
     */
    const val MEMORY_SEARCH_TOP_K_DEFAULT: Int = 5

    /** Lower bound enforced when the user edits the memory search top-K. */
    const val MEMORY_SEARCH_TOP_K_MIN: Int = 1

    /** Upper bound enforced when the user edits the memory search top-K. */
    const val MEMORY_SEARCH_TOP_K_MAX: Int = 20

    /**
     * Default for the chat-history compression toggle. `true` so long sessions
     * stay within the on-device context window out of the box — compression only
     * ever activates once the verbatim history crosses
     * [CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS_DEFAULT], and graceful
     * truncation bounds the budget even before a summary is ready.
     */
    const val CHAT_HISTORY_COMPRESSION_ENABLED_DEFAULT: Boolean = true

    /**
     * Default approximate-token budget above which chat history is compressed.
     * Set below [MAX_CONTEXT_LENGTH_DEFAULT] (4096) so the summarised history
     * leaves room for the long-term-memory, tool-result, and output blocks
     * within the on-device context window.
     */
    const val CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS_DEFAULT: Int = 3_500

    /** Lower bound enforced when the user edits the compression token threshold. */
    const val CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS_MIN: Int = 500

    /** Upper bound enforced when the user edits the compression token threshold. */
    const val CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS_MAX: Int = 32_000

    /**
     * Default number of most-recent messages kept verbatim when compression is
     * active. The older tail is folded into the summary.
     */
    const val CHAT_HISTORY_LIVE_WINDOW_DEFAULT: Int = 10

    /** Lower bound enforced when the user edits the live-window size. */
    const val CHAT_HISTORY_LIVE_WINDOW_MIN: Int = 2

    /** Upper bound enforced when the user edits the live-window size. */
    const val CHAT_HISTORY_LIVE_WINDOW_MAX: Int = 50

    /**
     * Default maximum voice-recording length, in seconds, before the recorder
     * auto-stops and hands the clip to transcription. Set to 30 s to match the
     * effective audio window of the multimodal on-device models (longer clips
     * risk silent truncation by the model's audio frontend); user-adjustable.
     */
    const val AUDIO_MAX_DURATION_SEC_DEFAULT: Int = 30

    /** Lower bound enforced when the user edits the recording length. */
    const val AUDIO_MAX_DURATION_SEC_MIN: Int = 5

    /** Upper bound enforced when the user edits the recording length. */
    const val AUDIO_MAX_DURATION_SEC_MAX: Int = 120

    /**
     * Default minimum cosine-similarity score a memory chunk must reach to be
     * surfaced during retrieval. Chunks below this are filtered out before
     * reaching the prompt.
     */
    const val MEMORY_SEARCH_THRESHOLD_DEFAULT: Float = 0.55f

    /** Lower bound enforced when the user edits the memory search threshold. */
    const val MEMORY_SEARCH_THRESHOLD_MIN: Float = 0.3f

    /** Upper bound enforced when the user edits the memory search threshold. */
    const val MEMORY_SEARCH_THRESHOLD_MAX: Float = 0.9f

    /**
     * Default recency half-life, in days, used by the memory re-ranker: a
     * non-pinned chunk this old keeps half of its raw cosine similarity. Lower
     * values bias retrieval harder towards fresh facts.
     */
    const val MEMORY_RECENCY_HALF_LIFE_DAYS_DEFAULT: Int = 30

    /** Lower bound enforced when the user edits the recency half-life. */
    const val MEMORY_RECENCY_HALF_LIFE_DAYS_MIN: Int = 7

    /** Upper bound enforced when the user edits the recency half-life. */
    const val MEMORY_RECENCY_HALF_LIFE_DAYS_MAX: Int = 180

    /**
     * Default Ollama-side context-window size (in tokens) assumed by
     * [app.knotwork.android.data.local.ApiKeyManager] for any model whose
     * per-model override has not been configured.
     */
    const val OLLAMA_CONTEXT_WINDOW_DEFAULT: Int = 4_096

    /** Lower bound enforced when the user edits the memory-summary default limit. */
    const val MEMORY_SUMMARY_LIMIT_MIN: Int = 1

    /** Upper bound enforced when the user edits the memory-summary default limit. */
    const val MEMORY_SUMMARY_LIMIT_MAX: Int = 50

    /**
     * Maximum length (in characters) of the user-editable system instructions
     * block. Mirrors the `218 / 4 000 chars` counter shown in the System
     * instructions card. The bound exists so a runaway paste cannot inflate
     * the prompt past what an on-device model can fit in context.
     */
    const val SYSTEM_INSTRUCTIONS_CHAR_LIMIT: Int = 4_000

    /**
     * Default active embedding-provider id for the long-term memory subsystem.
     *
     * Mirrors [EmbeddingProvider.ID_USE] (the on-device Universal Sentence
     * Encoder) — referenced rather than re-typed so the default and the
     * provider's own id can never drift apart.
     */
    const val ACTIVE_EMBEDDING_PROVIDER_ID_DEFAULT: String = EmbeddingProvider.ID_USE

    /**
     * Default for the "Auto-extract from conversations" memory toggle. `true`
     * so the long-term memory fills itself out of the box: after a pipeline run
     * completes, [app.knotwork.android.domain.usecases.MemoryExtractionUseCase]
     * distils durable facts from the dialogue. Users who prefer to curate
     * memory by hand can turn it off in Settings → Memory.
     */
    const val AUTO_EXTRACT_ENABLED_DEFAULT: Boolean = true

    /**
     * Default for the background memory-compaction toggle. `true` so the
     * long-term memory keeps itself tidy out of the box: a daily worker
     * (`MemoryCompactionWorker`, charging + idle only) clusters old non-pinned
     * chunks and consolidates each dense cluster into a single summary chunk.
     * Users who prefer their raw facts untouched can turn it off in
     * Settings → Memory.
     */
    const val MEMORY_COMPACTION_ENABLED_DEFAULT: Boolean = true

    /**
     * Default for the "Verbose memory logging" privacy toggle. `false` so the
     * agent console and logcat stay terse out of the box. When the user opts in
     * (Settings → Privacy), memory retrieval console events expand with per-hit
     * snippets and similarity scores, and the compaction pass logs the cluster
     * membership of every consolidation.
     */
    const val VERBOSE_MEMORY_LOGGING_ENABLED_DEFAULT: Boolean = false

    /**
     * Default age, in days, after which a non-pinned chunk becomes a candidate
     * for compaction. Fresh chunks are left alone so recently-learned facts
     * keep their exact wording; only stale ones are eligible for clustering.
     */
    const val MEMORY_COMPACTION_AGE_DAYS_DEFAULT: Int = 30

    /** Lower bound enforced when the user edits the compaction age window. */
    const val MEMORY_COMPACTION_AGE_DAYS_MIN: Int = 7

    /** Upper bound enforced when the user edits the compaction age window. */
    const val MEMORY_COMPACTION_AGE_DAYS_MAX: Int = 90

    /**
     * Hard ceiling on the total number of stored memory chunks. When the table
     * grows past this, compaction is triggered out-of-schedule (without waiting
     * for the daily charging-and-idle window) to keep the database bounded.
     */
    const val MAX_MEMORY_CHUNKS_DEFAULT: Int = 5_000

    /** Lower bound enforced when the user edits the max-chunks hard limit. */
    const val MAX_MEMORY_CHUNKS_MIN: Int = 1_000

    /** Upper bound enforced when the user edits the max-chunks hard limit. */
    const val MAX_MEMORY_CHUNKS_MAX: Int = 20_000

    /**
     * Default ceiling, in bytes, on the response body the `http_request` tool
     * pulls into memory and surfaces to the agent (1 MB). A larger response is
     * read up to this cap and a truncation marker is appended so the model knows
     * the body was cut. Bounds how much untrusted remote content a single call
     * can inject into the local model's context.
     */
    const val HTTP_TOOL_MAX_RESPONSE_BYTES_DEFAULT: Long = 1L * 1024 * 1024

    /** Lower bound of the `http_request` response ceiling, in bytes (64 KB). */
    const val HTTP_TOOL_MAX_RESPONSE_BYTES_MIN: Long = 64L * 1024

    /** Upper bound of the `http_request` response ceiling, in bytes (8 MB). */
    const val HTTP_TOOL_MAX_RESPONSE_BYTES_MAX: Long = 8L * 1024 * 1024

    /**
     * Wall-clock connect / read timeout, in milliseconds, applied to every
     * `http_request` call (60 s). Matches the cloud-LLM OkHttp timeout mandated
     * by the API conventions so an unresponsive remote host cannot stall a
     * pipeline run indefinitely.
     */
    const val HTTP_TOOL_TIMEOUT_MS_DEFAULT: Long = 60_000L

    /**
     * Maximum number of HTTP redirects the `http_request` tool follows manually.
     * Each hop is re-validated against the domain allowlist before it is taken;
     * the cap stops a redirect loop from spinning forever.
     */
    const val HTTP_TOOL_MAX_REDIRECTS: Int = 5

    /**
     * Default number of corrective re-inferences the structured-output gate
     * ([app.knotwork.android.domain.engine.structured.StructuredOutputGate])
     * may spend on a single node before giving up and returning a
     * [app.knotwork.android.domain.engine.structured.GateResult.Failed]. `2`
     * recovers the common single-malformed-reply case without letting a
     * persistently confused model stall a run.
     */
    const val STRUCTURED_OUTPUT_MAX_REPAIRS_DEFAULT: Int = 2

    /** Lower bound (no repairs — fail fast) for the structured-output repair budget. */
    const val STRUCTURED_OUTPUT_MAX_REPAIRS_MIN: Int = 0

    /** Upper bound for the structured-output repair budget. */
    const val STRUCTURED_OUTPUT_MAX_REPAIRS_MAX: Int = 4

    /**
     * Default maximum number of attempts (the initial call plus retries) a
     * transient cloud failure is given before the error propagates. Maps onto
     * Koog's `RetryConfig.maxAttempts`. `3` mirrors Koog's own `PRODUCTION`
     * preset: it absorbs a single 429 / 5xx / timeout blip without letting a
     * persistently failing provider stall a run.
     */
    const val CLOUD_RETRY_MAX_ATTEMPTS_DEFAULT: Int = 3

    /** Lower bound for the cloud-retry attempt budget. `1` disables retries (initial call only). */
    const val CLOUD_RETRY_MAX_ATTEMPTS_MIN: Int = 1

    /** Upper bound for the cloud-retry attempt budget. */
    const val CLOUD_RETRY_MAX_ATTEMPTS_MAX: Int = 5

    /**
     * Default base delay, in milliseconds, before the first cloud retry. Maps
     * onto Koog's `RetryConfig.initialDelay`; subsequent retries grow it by the
     * fixed exponential backoff multiplier with jitter. `1000` ms matches Koog's
     * `PRODUCTION` preset.
     */
    const val CLOUD_RETRY_BASE_DELAY_MS_DEFAULT: Long = 1000L

    /** Lower bound for the cloud-retry base delay. */
    const val CLOUD_RETRY_BASE_DELAY_MS_MIN: Long = 100L

    /** Upper bound for the cloud-retry base delay. */
    const val CLOUD_RETRY_BASE_DELAY_MS_MAX: Long = 10_000L

    /**
     * Default for the anonymous crash-reporting opt-in. `false` to honour the
     * project's on-device privacy positioning: no telemetry leaves the device
     * until the user explicitly opts in (Settings → Privacy).
     */
    const val CRASH_REPORTING_ENABLED_DEFAULT: Boolean = false

    /**
     * Default for the on-device usage-statistics opt-in. `true` so the
     * dogfooding window gathers usage signal out of the box — but the data is
     * **local-only**: no figure ever leaves the device regardless of this flag,
     * which merely controls whether the local counters are recorded at all. The
     * user can turn recording off and clear the statistics from
     * Settings → Privacy → Usage statistics at any time.
     */
    const val USAGE_TELEMETRY_ENABLED_DEFAULT: Boolean = true

    /**
     * Default for the "Scheduled task results" notification toggle. `true` so a
     * scheduled background run announces its outcome (and deep-links into its
     * session) without the user having to enable anything first.
     */
    const val SCHEDULED_TASK_NOTIFICATIONS_ENABLED_DEFAULT: Boolean = true

    /**
     * Default for the "keep shares in one chat" flag. `true` so every item shared
     * into the app accumulates in a single reusable **Shared** chat instead of
     * spawning a fresh chat per share — the friendlier, more legible default the
     * user asked for. Opt out to get one new chat per share.
     */
    const val SHARE_REUSE_SESSION_DEFAULT: Boolean = true

    /**
     * Default for the external-automation master switch. `false` — the contract
     * that lets another app on the device start a pipeline stays off until the
     * user turns it on, because a broadcast carries no attested sender identity
     * and the switch is therefore the whole of the consent.
     */
    const val EXTERNAL_AUTOMATION_ENABLED_DEFAULT: Boolean = false

    /**
     * Default for the hard-block-every-destructive-tool flag. `false` so
     * destructive tools are gated by the standard Human-in-the-loop prompt
     * rather than refused outright — a blanket block is an opt-in safety stance.
     */
    const val BLOCK_DESTRUCTIVE_TOOLS_DEFAULT: Boolean = false

    /**
     * Default for local-only mode. `false` so configured cloud providers remain
     * reachable; the user opts in to gate every cloud call (on-device + Ollama
     * only) when they want a strict no-egress posture.
     */
    const val BLOCK_NETWORK_FROM_LOCAL_MODEL_DEFAULT: Boolean = false

    /**
     * Default number of recent memory chunks rendered by the `$MEMORY_SUMMARY`
     * prompt variable. `5` keeps the injected block compact; the user can widen
     * it up to [MEMORY_SUMMARY_LIMIT_MAX] in Settings → Memory.
     */
    const val MEMORY_SUMMARY_DEFAULT_LIMIT_DEFAULT: Int = 5
}
