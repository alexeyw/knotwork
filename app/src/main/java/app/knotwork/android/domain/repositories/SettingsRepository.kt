package app.knotwork.android.domain.repositories

import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.TestProbeResult
import app.knotwork.android.domain.models.ToolApprovalPolicy
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.models.UpdateMcpServerResult
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing application-wide settings and user
 * preferences. Provides abstraction over the underlying persistence
 * mechanism (DataStore + the Keystore-backed encrypted store for the
 * secret payloads).
 *
 * The interface is intentionally large; per-feature splits (Sampling /
 * Identity / Memory) are planned post-v0.1. The detekt suppression is
 * file-scoped because every method here serves a single coherent
 * "user-tunable preference" concern.
 */
@Suppress("TooManyFunctions")
interface SettingsRepository {

    /**
     * A [Flow] representing the current state of the first launch flag.
     * Emits `true` if it's the user's first time launching the app, `false` otherwise.
     *
     * Semantics (intentionally narrow): this flag gates one-shot seeding
     * inside `InitializeAppUseCase` (default prompts, the seeded
     * `Default System Pipeline`). It is cleared as part of that
     * initialization, so callers cannot rely on it to decide whether the
     * user has seen onboarding — use [hasCompletedOnboarding] for that.
     */
    val isFirstLaunch: Flow<Boolean>

    /**
     * Updates the first launch flag.
     *
     * @param isFirstLaunch The new value to set.
     */
    suspend fun setFirstLaunch(isFirstLaunch: Boolean)

    /**
     * A [Flow] indicating whether the user has finished (or skipped) the
     * onboarding flow at least once. Emits `false` until [setHasCompletedOnboarding]
     * is called.
     *
     * This is intentionally a separate flag from [isFirstLaunch]: cold-start
     * initialization (`InitializeAppUseCase`) clears `isFirstLaunch` before
     * the splash hands control to the nav-graph, so the onboarding gate
     * cannot key off it. The two flags evolve independently — seeding the
     * default pipeline runs exactly once per fresh install, while
     * onboarding can be re-shown later via Settings → Reset onboarding
     * without re-seeding.
     */
    val hasCompletedOnboarding: Flow<Boolean>

    /**
     * Updates the onboarding-completion flag.
     *
     * @param completed `true` after the user finishes or skips onboarding;
     *        `false` resets onboarding so it is shown again on the next
     *        launch (consumed by the Settings → Reset onboarding action).
     */
    suspend fun setHasCompletedOnboarding(completed: Boolean)

    /**
     * A [Flow] representing the saved HuggingFace authorization token.
     */
    val huggingFaceAuthToken: Flow<String?>

    /**
     * Updates the HuggingFace authorization token.
     *
     * @param token The new token to save, or null to clear it.
     */
    suspend fun setHuggingFaceAuthToken(token: String?)

    /**
     * A [Flow] representing the maximum allowed context length (e.g., in characters or tokens).
     */
    val maxContextLength: Flow<Int>

    /**
     * Updates the maximum allowed context length.
     *
     * @param length The new maximum length to set.
     */
    suspend fun setMaxContextLength(length: Int)

    /**
     * A [Flow] representing the sampling temperature for generation.
     */
    val temperature: Flow<Float>

    /**
     * Updates the sampling temperature.
     */
    suspend fun setTemperature(temperature: Float)

    /**
     * A [Flow] representing the top-k sampling parameter for generation.
     */
    val topK: Flow<Int>

    /**
     * Updates the top-k sampling parameter.
     */
    suspend fun setTopK(topK: Int)

    /**
     * A [Flow] representing the top-p sampling parameter for generation.
     */
    val topP: Flow<Float>

    /**
     * Updates the top-p sampling parameter.
     */
    suspend fun setTopP(topP: Float)

    /**
     * A [Flow] representing the system prompt prefix.
     */
    val systemPromptPrefix: Flow<String>

    /**
     * Updates the system prompt prefix.
     *
     * @param prompt The new prompt to set.
     */
    suspend fun setSystemPromptPrefix(prompt: String)

    /**
     * Configured MCP servers, ordered by user insertion order.
     *
     * Each entry carries the URL plus optional display name, transport
     * choice, and arbitrary request headers (typically `Authorization`).
     * Implementations migrate legacy URL-only persistence one-shot to
     * default [McpServerConfig]s on first read.
     */
    val mcpServers: Flow<List<McpServerConfig>>

    /**
     * Persists [config]. If a server with the same URL already exists,
     * it is replaced in place (preserving order).
     */
    suspend fun addMcpServer(config: McpServerConfig)

    /**
     * Replaces the server identified by [originalUrl] with [updated].
     * If [originalUrl] is not present, behaves the same as [addMcpServer].
     * The URL inside [updated] may differ from [originalUrl] (edit
     * scenario); persistence keeps the row at its old position.
     *
     * Refuses to persist with [UpdateMcpServerResult.UrlCollision] when
     * `updated.url` matches the URL of a different existing row. Without
     * this guard, replacing by index would silently produce a `[B, B]`
     * list and lose the original server's auth / headers / display name.
     */
    suspend fun updateMcpServer(originalUrl: String, updated: McpServerConfig): UpdateMcpServerResult

    /** Removes the server identified by [url]. No-op when missing. */
    suspend fun removeMcpServer(url: String)

    /**
     * A [Flow] representing the set of disabled local app function names.
     */
    val disabledAppFunctions: Flow<Set<String>>

    /**
     * Updates the set of disabled local app functions.
     */
    suspend fun setDisabledAppFunctions(functions: Set<String>)

    /**
     * A [Flow] of disabled MCP tool ids. Entries match the
     * `mcp:<sha8(serverUrl)>:<toolName>` ids produced by
     * `McpServerRepositoryImpl.mcpToolId`. Kept separate from
     * [disabledAppFunctions] because the two namespaces share no collision
     * guarantees — disabling `search_tool` (local) and an MCP tool named
     * `search_tool` are independent decisions.
     */
    val disabledMcpTools: Flow<Set<String>>

    /**
     * Updates the set of disabled MCP tools.
     */
    suspend fun setDisabledMcpTools(toolIds: Set<String>)

    /**
     * A [Flow] of origins (`http://host[:port]`) the user has explicitly agreed
     * to talk to **unencrypted**.
     *
     * Android's `network_security_config.xml` cannot express "any private-LAN
     * address", so the cleartext rule lives in app code instead
     * (`CleartextPolicy`): unencrypted traffic is permitted only to a
     * loopback / private address present in this set, and never to a public
     * host. Entries are added when the user confirms the prompt shown while
     * saving a local Ollama or MCP address.
     *
     * An empty set means no unencrypted destination has been approved yet —
     * which is the state of a fresh install.
     */
    val approvedCleartextOrigins: Flow<Set<String>>

    /**
     * Records the user's agreement to send unencrypted traffic to [origin].
     *
     * @param origin canonical `scheme://host[:port]`, as produced by
     *   `CleartextPolicy.originOf`. Storing anything else would silently fail
     *   to match at request time.
     */
    suspend fun approveCleartextOrigin(origin: String)

    /**
     * A [Flow] of per-tool risk overrides. The map is the user's authoritative
     * voice on what risk class a discovered tool should be treated as; when an
     * entry is present it takes precedence over the conservative `SENSITIVE`
     * default that both discovery sources fall back to.
     *
     * Two key namespaces share this map, and they cannot collide because the
     * MCP form is prefixed:
     *  - **Discovered AppFunctions** — keyed by the AppFunction's tool name
     *    (the qualified `"${packageName}/${id}"` form).
     *  - **MCP tools** — keyed by the `mcp:<sha8(serverUrl)>:<toolName>` id
     *    produced by `McpServerRepositoryImpl.mcpToolId`, i.e. per server and
     *    not per bare name. Two servers advertising the same `create_issue`
     *    are independent decisions, exactly as they already are for
     *    [disabledMcpTools].
     *
     * Built-in tools carry hard-coded risk constants (see
     * `ToolRepositoryImpl.getBuiltinTools`) and are deliberately NOT affected
     * by entries in this map — their risk is part of the app's own contract.
     *
     * Missing entries (or an empty map) mean "no override, use the source
     * default" — the canonical resolution lives in `ToolRepository.getRisk`.
     */
    val toolRiskOverrides: Flow<Map<String, ToolRisk>>

    /**
     * Sets (or replaces) the risk override for a single discovered tool. To
     * remove an override, callers should pass the source-default value or wait
     * for the removal API to land in a follow-up task; this cut intentionally
     * keeps the surface minimal because there is no UI surface yet.
     *
     * @param toolKey Key of the tool to override — an AppFunction tool name or
     *   an `mcp:<sha8(serverUrl)>:<toolName>` id. See [toolRiskOverrides] for
     *   the namespace rules.
     * @param risk Effective risk class to associate with [toolKey].
     */
    suspend fun setToolRiskOverride(toolKey: String, risk: ToolRisk)

    /**
     * A [Flow] representing the current active chat session ID.
     */
    val currentChatSessionId: Flow<String?>

    /**
     * Updates the current active chat session ID.
     */
    suspend fun setCurrentChatSessionId(sessionId: String?)

    /**
     * A [Flow] emitting the user's last-selected console tab on the chat
     * home pane. Stored as a raw string (the enum name from
     * `app.knotwork.design.components.console.ConsoleTab`) so the domain
     * layer stays free of `:catalog` imports. Defaults to `"Logs"` for a
     * fresh install.
     */
    val consolePreferredConsoleTabName: Flow<String>

    /**
     * Persists the user's chosen console tab so it survives process death.
     *
     * @param name Enum name of the chosen tab (`Logs` / `Vars` / `Traces`).
     */
    suspend fun setConsolePreferredConsoleTabName(name: String)

    /**
     * A [Flow] emitting the epoch-millis of the most recent successful memory
     * compaction pass (manual or background), or `0L` when compaction has
     * never run. Powers the "compacted N ago" line on the Memory stats card.
     */
    val memoryLastCompactedAt: Flow<Long>

    /**
     * Records the time a compaction pass finished.
     *
     * @param millis Epoch-millis to store as the most-recent compaction time.
     */
    suspend fun setMemoryLastCompactedAt(millis: Long)

    /**
     * A [Flow] emitting the maximum number of long-term memory chunks a single
     * retrieval returns into a node's context (the "top-K" of the similarity
     * search). The search itself always scans the full stored pool; this caps
     * how many results survive ranking and reach the prompt. Defaults to
     * `SettingsDefaults.MEMORY_SEARCH_TOP_K_DEFAULT` (5) for a fresh install.
     */
    val memorySearchTopK: Flow<Int>

    /**
     * Updates the long-term memory retrieval top-K.
     *
     * @param topK The new top-K; callers should keep it within a sane range
     *   (validation of user-entered values lives in the Settings ViewModel).
     */
    suspend fun setMemorySearchTopK(topK: Int)

    /**
     * `true` when long chat sessions should be compressed: once the verbatim
     * history exceeds [chatHistoryCompressionThresholdTokens], the tail older
     * than the live window is summarised into the `--- Earlier conversation
     * (summarized) ---` context block. Defaults to
     * [app.knotwork.android.domain.constants.SettingsDefaults.CHAT_HISTORY_COMPRESSION_ENABLED_DEFAULT].
     */
    val chatHistoryCompressionEnabled: Flow<Boolean>

    /**
     * Persists the chat-history compression toggle.
     *
     * @param enabled `true` to enable history compression, `false` to always
     *   feed the full verbatim history.
     */
    suspend fun setChatHistoryCompressionEnabled(enabled: Boolean)

    /**
     * Approximate-token budget above which a session's verbatim chat history is
     * compressed. Coordinated with [maxContextLength] so the summarised history
     * plus memory and tool blocks fit the on-device context window. Defaults to
     * [app.knotwork.android.domain.constants.SettingsDefaults.CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS_DEFAULT].
     */
    val chatHistoryCompressionThresholdTokens: Flow<Int>

    /**
     * Persists the chat-history compression token threshold.
     *
     * @param tokens The new threshold; callers should keep it within a sane
     *   range (validation of user-entered values lives in the Settings ViewModel).
     */
    suspend fun setChatHistoryCompressionThresholdTokens(tokens: Int)

    /**
     * Number of most-recent messages kept verbatim under `--- Chat History ---`
     * when compression is active; everything older is represented by the
     * summary. Defaults to
     * [app.knotwork.android.domain.constants.SettingsDefaults.CHAT_HISTORY_LIVE_WINDOW_DEFAULT].
     */
    val chatHistoryLiveWindowSize: Flow<Int>

    /**
     * Persists the chat-history live-window size.
     *
     * @param size The new window size; callers should keep it within a sane
     *   range (validation of user-entered values lives in the Settings ViewModel).
     */
    suspend fun setChatHistoryLiveWindowSize(size: Int)

    /**
     * Maximum voice-recording length, in seconds, before the recorder
     * auto-stops and the clip is handed to transcription. Defaults to
     * [app.knotwork.android.domain.constants.SettingsDefaults.AUDIO_MAX_DURATION_SEC_DEFAULT].
     */
    val audioMaxDurationSec: Flow<Int>

    /**
     * Persists the maximum voice-recording length.
     *
     * @param seconds The new limit in seconds; callers should keep it within the
     *   `AUDIO_MAX_DURATION_SEC_MIN..AUDIO_MAX_DURATION_SEC_MAX` range (validation
     *   of user-entered values lives in the Settings ViewModel).
     */
    suspend fun setAudioMaxDurationSec(seconds: Int)

    /**
     * A [Flow] emitting the minimum cosine-similarity score (0.0–1.0) a memory
     * chunk must reach to be considered relevant during retrieval. Chunks below
     * this threshold are dropped before they reach a node's context. Defaults
     * to `SettingsDefaults.MEMORY_SEARCH_THRESHOLD_DEFAULT` (0.55) for a fresh
     * install.
     */
    val memorySearchThreshold: Flow<Float>

    /**
     * Updates the long-term memory retrieval similarity threshold.
     *
     * @param threshold The new threshold in the inclusive range 0.0–1.0;
     *   validation of user-entered values lives in the Settings ViewModel.
     */
    suspend fun setMemorySearchThreshold(threshold: Float)

    /**
     * A [Flow] emitting the recency half-life (in days) used by the long-term
     * memory re-ranker. A non-pinned chunk this old keeps half of its raw
     * cosine similarity; freshness wins ties below it and is penalised above
     * it. Defaults to `SettingsDefaults.MEMORY_RECENCY_HALF_LIFE_DAYS_DEFAULT`
     * (30) for a fresh install.
     */
    val memoryRecencyHalfLifeDays: Flow<Int>

    /**
     * Updates the long-term memory recency half-life.
     *
     * @param days The new half-life in days; callers should keep it within a
     *   sane range (validation of user-entered values lives in the Settings
     *   ViewModel).
     */
    suspend fun setMemoryRecencyHalfLifeDays(days: Int)

    /**
     * A [Flow] emitting the wire key of the selected local-model backend
     * ([app.knotwork.android.domain.models.LocalBackend.key]). Stored as a raw string for
     * backward compatibility with DataStore values written before the typed enum existed.
     */
    val localModelBackend: Flow<String>

    /**
     * The **raw** stored backend preference: the persisted wire key, or `null`
     * when the user (and the app) has never written one.
     *
     * [localModelBackend] folds the absent case into
     * [app.knotwork.android.domain.models.LocalBackend.CPU], which is exactly
     * what every inference call site wants — but it makes "never chosen"
     * indistinguishable from "deliberately CPU". The one-shot onboarding
     * acceleration decision needs that distinction: it may pick a default only
     * while nothing has been chosen, and must never override an explicit
     * selection.
     */
    val localModelBackendPreference: Flow<String?>

    /**
     * Updates the selected backend for the local model.
     *
     * @param backend The new backend wire key; use
     *        [app.knotwork.android.domain.models.LocalBackend.key] to obtain it.
     */
    suspend fun setLocalModelBackend(backend: String)

    /**
     * Sentinel emitted by [setLastInitBackendAttempt] right before a
     * non-CPU LiteRT backend init is attempted. Cleared on successful init.
     *
     * Used as a crash-recovery breadcrumb: if a cold-start observes this
     * key still set to a non-CPU backend, the previous LiteRT init
     * crashed mid-flight (typically a missing GPU/NPU dispatch library
     * killing the process via SIGABRT before Kotlin try/catch can fire).
     * `LiteRTLlmEngine.initialize` then forces CPU and clears the
     * persisted backend so subsequent restarts are stable.
     */
    val lastInitBackendAttempt: Flow<String?>

    /**
     * Updates the crash-recovery breadcrumb. Pass `null` to clear it on
     * successful init.
     */
    suspend fun setLastInitBackendAttempt(backendKey: String?)

    /**
     * Number of **consecutive** cold starts that found [lastInitBackendAttempt]
     * still set, i.e. the previous process died before init finished. Reset to
     * zero by the first successful init.
     *
     * The breadcrumb alone cannot tell a backend crash apart from any other way
     * the process can die inside that window — swiped from recents, reclaimed by
     * the low-memory killer, frozen by the OEM. Treating the first such death as
     * proof that the GPU is broken silently and permanently downgraded the user
     * to CPU; observed during a directed on-device test, where an unrelated
     * `lmkd` kill cost the device its GPU backend. Corroboration across two
     * consecutive starts is what separates "this backend really cannot
     * initialise" from "something else killed us".
     */
    val localBackendFailureStreak: Flow<Int>

    /**
     * Records the current consecutive-failure count for the local backend.
     *
     * @param streak new streak value; `0` clears it after a successful init.
     */
    suspend fun setLocalBackendFailureStreak(streak: Int)

    /**
     * A [Flow] representing the timeout in milliseconds for tool approval requests.
     * After this duration without a user response, the approval is considered timed out.
     */
    val toolCallTimeoutMs: Flow<Long>

    /**
     * Updates the tool call approval timeout.
     *
     * @param timeoutMs The new timeout in milliseconds.
     */
    suspend fun setToolCallTimeoutMs(timeoutMs: Long)

    /**
     * A [Flow] of the maximum size, in bytes, of any single file the agent
     * workspace will accept. Writes whose content exceeds this are refused
     * ([app.knotwork.android.domain.models.WorkspaceError.TooLarge]); it also
     * caps how large a file the workspace will pull into memory for a text read.
     */
    val workspaceMaxFileSizeBytes: Flow<Long>

    /**
     * Updates the per-file size limit of the agent workspace.
     *
     * @param bytes The new per-file ceiling, in bytes.
     */
    suspend fun setWorkspaceMaxFileSizeBytes(bytes: Long)

    /**
     * A [Flow] of the maximum total size, in bytes, the agent workspace may
     * occupy across all files. A write that would push the workspace past this
     * is refused
     * ([app.knotwork.android.domain.models.WorkspaceError.QuotaExceeded]) so a
     * looping pipeline cannot exhaust device storage.
     */
    val workspaceMaxTotalBytes: Flow<Long>

    /**
     * Updates the workspace-wide total-size limit.
     *
     * @param bytes The new workspace-wide ceiling, in bytes.
     */
    suspend fun setWorkspaceMaxTotalBytes(bytes: Long)

    /**
     * A [Flow] of the budget, in **tokens**, of file content the `read_file`
     * tool returns in a single call. The tool converts this to an approximate
     * byte ceiling and truncates the served window to it (appending a
     * `[... truncated, N bytes remain — use offset to continue]` marker), so a
     * single read of a large file can never overflow the local model's context
     * window.
     */
    val workspaceReadTokenBudget: Flow<Int>

    /**
     * Updates the per-read token budget of the `read_file` tool.
     *
     * @param tokens The new budget, in tokens.
     */
    suspend fun setWorkspaceReadTokenBudget(tokens: Int)

    /**
     * A [Flow] of the domain allowlist for the `http_request` tool, in the order
     * the user added them. Each entry is a normalised host (e.g. `api.example.com`)
     * and is matched **exactly** — sub-domains are not implied, so the user must
     * add each host they intend to reach (see
     * [app.knotwork.android.domain.services.HttpRequestPolicy.isHostAllowed]).
     *
     * The allowlist is the tool's master switch: while it is **empty** the tool
     * is not published into [getAvailableTools] at all (the agent never sees it),
     * and any direct call is refused. A request whose target host matches no
     * entry is refused before it leaves the device — the conservative default
     * that keeps the read_file → http_request exfiltration channel closed until
     * the user explicitly opens a destination.
     */
    val allowedHttpDomains: Flow<List<String>>

    /**
     * Persists the full `http_request` domain allowlist, replacing any previous
     * value. Callers are expected to pass already-normalised, de-duplicated hosts
     * (see the Settings → Tools editor); persistence preserves their order.
     *
     * @param domains The new allowlist of normalised hosts.
     */
    suspend fun setAllowedHttpDomains(domains: List<String>)

    /**
     * A [Flow] of the maximum size, in bytes, of the response body the
     * `http_request` tool pulls into memory and returns to the agent. A larger
     * response is read up to this cap and a truncation marker is appended.
     * Bounds how much untrusted remote content one call can inject into context.
     */
    val httpToolMaxResponseBytes: Flow<Long>

    /**
     * Updates the `http_request` response-body byte ceiling.
     *
     * @param bytes The new ceiling, in bytes.
     */
    suspend fun setHttpToolMaxResponseBytes(bytes: Long)

    /**
     * A [Flow] representing the maximum number of pipeline execution steps.
     * Prevents infinite loops in pipeline graphs. Valid range: 5–100.
     */
    val pipelineMaxSteps: Flow<Int>

    /**
     * Updates the maximum number of pipeline execution steps.
     *
     * @param steps The new limit. Will be coerced to the range 5–100.
     */
    suspend fun setPipelineMaxSteps(steps: Int)

    /**
     * A [Flow] representing the maximum number of pipeline execution steps for
     * a run nobody is watching — the background origins (scheduler, quick tile,
     * trigger, external automation). Valid range: 5–100.
     *
     * Until this setting existed, [pipelineMaxSteps] governed every origin.
     * While it has never been set, it therefore reports whatever
     * [pipelineMaxSteps] is configured to, so an upgrade cannot quietly shrink
     * the budget of an automation the user had already widened.
     */
    val pipelineMaxStepsBackground: Flow<Int>

    /**
     * Whether the background step ceiling has ever been set independently.
     *
     * `false` means the value [pipelineMaxStepsBackground] reports is inherited
     * from [pipelineMaxSteps] rather than chosen — a real, reachable state that
     * a surface showing both numbers has to be able to explain, because
     * otherwise it renders two identical figures and gives no hint that moving
     * the first one also moves the second.
     *
     * Exposed rather than derived by comparing the two flows: they are equal by
     * default, so equality cannot distinguish "inherited" from "deliberately
     * set to the same number", and only the presence of the stored key can.
     */
    val pipelineMaxStepsBackgroundIsSet: Flow<Boolean>

    /**
     * Updates the background step ceiling.
     *
     * @param steps The new limit. Will be coerced to the range 5–100.
     */
    suspend fun setPipelineMaxStepsBackground(steps: Int)

    /**
     * A [Flow] representing the token ceiling for an interactive run, counted
     * across the whole run tree. Valid range: 10 000–10 000 000.
     */
    val runMaxTokens: Flow<Int>

    /**
     * Updates the interactive token ceiling.
     *
     * @param tokens The new limit. Will be coerced to the range 10 000–10 000 000.
     */
    suspend fun setRunMaxTokens(tokens: Int)

    /**
     * A [Flow] representing the token ceiling for a background run, counted
     * across the whole run tree. Valid range: 10 000–10 000 000.
     */
    val runMaxTokensBackground: Flow<Int>

    /**
     * Updates the background token ceiling.
     *
     * @param tokens The new limit. Will be coerced to the range 10 000–10 000 000.
     */
    suspend fun setRunMaxTokensBackground(tokens: Int)

    /**
     * A [Flow] representing the maximum nesting depth allowed for PIPELINE-node
     * composition (how many levels of sub-pipeline a run may descend into).
     * Enforced statically by `PipelineCompositionValidator` and at runtime by
     * `PipelineNodeExecutor`. Valid range: 1–5.
     */
    val pipelineMaxNestingDepth: Flow<Int>

    /**
     * Updates the maximum PIPELINE-node nesting depth.
     *
     * @param depth The new limit. Will be coerced to the range 1–5.
     */
    suspend fun setPipelineMaxNestingDepth(depth: Int)

    /**
     * A [Flow] representing the number of corrective re-inferences the
     * structured-output gate may spend on a single node's malformed output
     * before giving up. Consumed by the gate's engine-side integration; the gate
     * treats `0` as "validate once, never repair". Valid range: 0–4.
     */
    val structuredOutputMaxRepairs: Flow<Int>

    /**
     * Updates the structured-output repair budget.
     *
     * @param count The new repair ceiling. Will be coerced to the range 0–4.
     */
    suspend fun setStructuredOutputMaxRepairs(count: Int)

    /**
     * A [Flow] representing the maximum number of attempts (the initial call
     * plus retries) a transient cloud-call failure is given before the error
     * propagates. Consumed by the cloud client/embedding factories to build
     * Koog's retry policy; `1` means "no retries". Valid range: 1–5.
     */
    val cloudRetryMaxAttempts: Flow<Int>

    /**
     * Updates the cloud-retry attempt budget.
     *
     * @param attempts The new attempt ceiling. Will be coerced to the range 1–5.
     */
    suspend fun setCloudRetryMaxAttempts(attempts: Int)

    /**
     * A [Flow] representing the base delay, in milliseconds, before the first
     * cloud retry. Subsequent retries grow it by the fixed exponential-backoff
     * multiplier with jitter. Valid range: 100–10000.
     */
    val cloudRetryBaseDelayMs: Flow<Long>

    /**
     * Updates the cloud-retry base delay.
     *
     * @param delayMs The new base delay in milliseconds. Will be coerced to the
     *   range 100–10000.
     */
    suspend fun setCloudRetryBaseDelayMs(delayMs: Long)

    /**
     * A [Flow] representing the window, in hours, during which an interrupted
     * pipeline run can still be resumed from its checkpoint. Interrupted runs
     * older than this only offer the regular discard path — their recorded
     * context grows stale with time. Valid range: 1–168.
     */
    val resumeMaxAgeHours: Flow<Int>

    /**
     * Updates the checkpoint-resume window.
     *
     * @param hours The new window in hours. Will be coerced to the range 1–168.
     */
    suspend fun setResumeMaxAgeHours(hours: Int)

    /**
     * A [Flow] representing the window, in hours, during which a run parked
     * on a persistent HITL request (background approval or clarification)
     * waits for the user's response. Counted from the moment the live
     * in-process waiting phase timed out; once elapsed, the maintenance pass
     * fails the run with an "Approval window expired" message. Valid range:
     * 1–168.
     */
    val backgroundApprovalWindowHours: Flow<Int>

    /**
     * Updates the background-approval window.
     *
     * @param hours The new window in hours. Will be coerced to the range 1–168.
     */
    suspend fun setBackgroundApprovalWindowHours(hours: Int)

    /**
     * A [Flow] representing how many most-recent pipeline runs the retention
     * pass preserves per chat session. Terminal runs beyond this count are
     * deleted (together with their persisted traces) during the daily
     * maintenance window; non-terminal runs — including runs parked on a
     * background approval or clarification — are never removed by retention.
     * Valid range: 5–100.
     */
    val traceRetentionRunsPerSession: Flow<Int>

    /**
     * Updates the per-session run-retention count.
     *
     * @param runs The new count. Callers should keep it within the range
     *   5–100 (validation of user-entered values lives in the Settings
     *   ViewModel).
     */
    suspend fun setTraceRetentionRunsPerSession(runs: Int)

    /**
     * A [Flow] representing the maximum age, in days, a terminal pipeline run
     * (and its trace) is kept before the retention pass deletes it regardless
     * of the per-session count. Valid range: 7–180.
     */
    val traceRetentionMaxAgeDays: Flow<Int>

    /**
     * Updates the max-age run-retention window.
     *
     * @param days The new age limit in days. Callers should keep it within
     *   the range 7–180 (validation of user-entered values lives in the
     *   Settings ViewModel).
     */
    suspend fun setTraceRetentionMaxAgeDays(days: Int)

    /**
     * A [Flow] representing the id of the pipeline the user has marked as
     * default. `null` means no explicit choice — chats without their own
     * binding then have no pipeline to execute against and the task queue
     * fails such runs with an explicit error (it never silently picks an
     * arbitrary pipeline from the library).
     *
     * Set on first launch by `InitializeAppUseCase` to the seeded
     * `Default System Pipeline` so the default is unambiguous from the
     * start. Cleared automatically when the marked pipeline is deleted.
     */
    val defaultPipelineId: Flow<String?>

    /**
     * Updates the user-marked default pipeline id. Pass `null` to clear
     * the marker (unbound chats then refuse to run until a new default
     * is marked or the chat is bound explicitly).
     *
     * @param pipelineId Pipeline id to mark as default, or `null` to clear.
     */
    suspend fun setDefaultPipelineId(pipelineId: String?)

    /**
     * A [Flow] of the pipeline id bound to the **share target** entry surface.
     * `null` means the surface is unbound: sharing text/an image into the app
     * does nothing until the user picks a pipeline (privacy-first default).
     *
     * Like [defaultPipelineId] this is a user binding, not a tunable preference,
     * so it is **never** touched by `resetToRecommendedDefaults`. It is cleared
     * automatically when the bound pipeline is deleted.
     */
    val shareTargetPipelineId: Flow<String?>

    /**
     * Updates the pipeline bound to the share target. Pass `null` to unbind
     * (sharing then becomes inert again).
     *
     * @param pipelineId Pipeline id to bind, or `null` to clear the binding.
     */
    suspend fun setShareTargetPipelineId(pipelineId: String?)

    /**
     * `true` when content shared into the app should accumulate in a single
     * reusable **Shared** chat instead of opening a fresh chat per share.
     *
     * Unlike [shareTargetPipelineId] this is a tunable behaviour preference (not
     * a user binding), so `resetToRecommendedDefaults` restores it to
     * [SettingsDefaults.SHARE_REUSE_SESSION_DEFAULT] (`true`).
     */
    val shareReuseSession: Flow<Boolean>

    /**
     * Updates the "keep shares in one chat" preference.
     *
     * @param reuse `true` to append every share to the single Shared chat,
     *   `false` to start a new chat per share.
     */
    suspend fun setShareReuseSession(reuse: Boolean)

    /**
     * A [Flow] of the pipeline id bound to the **Quick Settings tile** ("duty"
     * pipeline). `null` means the tile is unbound: tapping it opens the app's
     * Background settings instead of running anything (privacy-first default).
     *
     * Like [defaultPipelineId] this is a user binding, not a tunable preference,
     * so it is **never** touched by `resetToRecommendedDefaults`. It is cleared
     * automatically when the bound pipeline is deleted.
     */
    val quickSettingsTilePipelineId: Flow<String?>

    /**
     * Updates the pipeline bound to the Quick Settings tile. Pass `null` to
     * unbind (the tile then routes to settings instead of running).
     *
     * @param pipelineId Pipeline id to bind, or `null` to clear the binding.
     */
    suspend fun setQuickSettingsTilePipelineId(pipelineId: String?)

    /**
     * A [Flow] of the pipeline id bound to the **external-automation** entry
     * surface — the one a third-party automation app may ask the app to run.
     * `null` means the surface is unbound and every external request is refused.
     *
     * The binding is an **allowlist**, not a default: an external request must
     * name this exact pipeline, and one naming any other pipeline is refused
     * rather than redirected here. That is what makes the user's choice in
     * settings the complete statement of what another app is permitted to run.
     *
     * Like [defaultPipelineId] this is a user binding, not a tunable preference,
     * so it is **never** touched by `resetToRecommendedDefaults`. It is cleared
     * automatically when the bound pipeline is deleted.
     */
    val externalAutomationPipelineId: Flow<String?>

    /**
     * Updates the pipeline bound to the external-automation surface. Pass `null`
     * to unbind (external requests are then refused outright).
     *
     * @param pipelineId Pipeline id to bind, or `null` to clear the binding.
     */
    suspend fun setExternalAutomationPipelineId(pipelineId: String?)

    /**
     * A [Flow] of the master switch for the external-automation contract —
     * whether another app on the device may ask this one to run a pipeline at
     * all. Defaults to `false`.
     *
     * Off by default is not caution for its own sake: switching it on widens the
     * app's attack surface to every installed app, because a broadcast carries no
     * attested sender identity. The switch is therefore the user's consent, and
     * the only thing standing between an inbound broadcast and the parser.
     *
     * Being off does not merely ignore requests — it refuses them with
     * [app.knotwork.android.domain.models.ExternalAutomationRejectionReason.CONTRACT_DISABLED]
     * and journals the refusal, so a caller can tell "switched off" from "never
     * arrived".
     *
     * This is a security-relevant preference rather than a tunable, so
     * `resetToRecommendedDefaults` returns it to `false` — the safe direction.
     */
    val externalAutomationEnabled: Flow<Boolean>

    /**
     * Switches the external-automation contract on or off.
     *
     * @param enabled `true` to let permitted external requests through, `false`
     *   to refuse every one of them.
     */
    suspend fun setExternalAutomationEnabled(enabled: Boolean)

    /**
     * A [Flow] indicating whether the user has opted in to anonymous crash
     * reporting via Firebase Crashlytics. Defaults to `false` — the project's
     * on-device privacy positioning forbids any automatic data egress.
     *
     * When `false`, the implementation must short-circuit every
     * `CrashReportingRepository` call to a no-op so no payload ever leaves
     * the device. When `true`, Firebase Crashlytics collection is enabled
     * and exceptions / custom keys are forwarded.
     */
    val crashReportingEnabled: Flow<Boolean>

    /**
     * Updates the user's opt-in for anonymous crash reporting.
     *
     * @param enabled `true` to enable Crashlytics collection,
     *                `false` to disable and force all reporting calls into a no-op.
     */
    suspend fun setCrashReportingEnabled(enabled: Boolean)

    /**
     * A [Flow] indicating whether fully on-device usage statistics are recorded.
     *
     * Gates the privacy-preserving local telemetry feature: when `true`, terminal
     * run outcomes and background trigger firings advance local counters
     * ([app.knotwork.android.domain.repositories.UsageTelemetryRepository]); when
     * `false`, no counter advances. **Nothing on this path ever leaves the
     * device regardless of the flag** — the toggle only controls whether the
     * local figures are gathered at all. Defaults to `true`.
     */
    val usageTelemetryEnabled: Flow<Boolean>

    /**
     * Updates the opt-in for on-device usage statistics recording.
     *
     * @param enabled `true` to record local usage counters, `false` to stop
     *   recording (already-stored statistics are untouched — use the Usage
     *   statistics screen's reset action to clear them).
     */
    suspend fun setUsageTelemetryEnabled(enabled: Boolean)

    /**
     * A [Flow] representing the default number of recent memory chunks rendered
     * by the `$MEMORY_SUMMARY` prompt variable. Defaults to 5.
     */
    val memorySummaryDefaultLimit: Flow<Int>

    /**
     * Updates the default number of recent memory chunks shown by `$MEMORY_SUMMARY`.
     *
     * @param limit The new chunk count. Values `<= 0` are valid and disable the
     * variable (it resolves to an empty string).
     */
    suspend fun setMemorySummaryDefaultLimit(limit: Int)

    /**
     * Policy that drives the Human-in-the-Loop approval gate
     * (`ToolInvocationGate`); which risks it stops for is
     * [ToolApprovalPolicy.requiresApproval]. Supersedes the legacy boolean
     * `requires_user_confirmation` key, which nothing else reads.
     *
     * While the policy key is absent, every read maps that legacy key onto a
     * policy (nothing is written): `true` → [ToolApprovalPolicy.SensitiveOrDestructive],
     * `false` → [ToolApprovalPolicy.NeverPrompt], absent →
     * [ToolApprovalPolicy.DEFAULT].
     */
    val toolApprovalPolicy: Flow<ToolApprovalPolicy>

    /**
     * Persists the user's tool-approval policy choice.
     *
     * @param policy The new policy.
     */
    suspend fun setToolApprovalPolicy(policy: ToolApprovalPolicy)

    /**
     * `true` when the user has opted to hard-block every `DESTRUCTIVE`
     * tool — `ToolNodeExecutor` refuses to call the tool and returns a
     * structured "blocked by policy" observation. Defaults to `false`.
     */
    val blockDestructiveTools: Flow<Boolean>

    /**
     * Updates the hard-deny destructive-tool flag.
     *
     * @param blocked `true` to refuse destructive tools outright.
     */
    suspend fun setBlockDestructiveTools(blocked: Boolean)

    /**
     * Local-only mode flag ("Block network from local model"). When `true`, no
     * model request leaves the device or the user's network: `ModelNetworkGate`
     * refuses every hosted cloud provider (OpenAI / Anthropic / Google / DeepSeek)
     * for chat, `delegate_task` and memory embeddings alike, and admits Ollama only
     * when its host is `localhost` or a loopback / private IPv4 literal, whatever
     * the scheme ([app.knotwork.android.domain.services.LocalOnlyPolicy]). Tools
     * (MCP servers, `http_request`) are not affected. Defaults to `false`.
     */
    val blockNetworkFromLocalModel: Flow<Boolean>

    /**
     * Updates the local-only mode flag.
     *
     * @param blocked `true` to gate every cloud provider.
     */
    suspend fun setBlockNetworkFromLocalModel(blocked: Boolean)

    /**
     * `true` when the user wants a system notification announcing the outcome
     * of a scheduled background run ("Task completed" / "Task failed").
     * Drives the "Scheduled task results" toggle in the Notifications card —
     * gates `ScheduledTaskNotifier`, which posts to the dedicated
     * default-importance channel with a deep-link into the session the run
     * landed in. Defaults to `true`.
     */
    val scheduledTaskNotificationsEnabled: Flow<Boolean>

    /**
     * Persists the scheduled-task result notifications toggle.
     *
     * @param enabled `true` to announce scheduled run outcomes.
     */
    suspend fun setScheduledTaskNotificationsEnabled(enabled: Boolean)

    /**
     * Last persisted result of a `Test backend` run inside Settings.
     * Emits `null` until the user has run the probe at least once.
     */
    val lastTestProbeResult: Flow<TestProbeResult?>

    /**
     * Persists the latest test-backend probe result so the row's
     * subtitle survives navigation.
     *
     * @param result Probe outcome to persist; pass `null` to clear.
     */
    suspend fun setLastTestProbeResult(result: TestProbeResult?)

    /**
     * Id of the currently active embedding provider, matching one of the
     * `EmbeddingProvider.ID_*` constants (e.g. `"use"`, `"openai_3_small"`,
     * `"ollama"`). Drives which backend the long-term memory subsystem uses to
     * turn text into vectors. Defaults to
     * [SettingsDefaults.ACTIVE_EMBEDDING_PROVIDER_ID_DEFAULT] (on-device USE).
     *
     * If the persisted value no longer matches a registered provider,
     * `EmbeddingProviderResolver` falls back to the on-device default at
     * resolution time — the stored value is left untouched.
     */
    val activeEmbeddingProviderId: Flow<String>

    /**
     * Persists the active embedding provider id.
     *
     * Implementations also capture the *previous* active id into
     * [lastReembedProviderId] when that value has never been set, so the
     * provider the stored embeddings were actually created with is known from
     * the first provider switch onward (powers the re-embed reminder banner).
     *
     * @param id One of the `EmbeddingProvider.ID_*` constants.
     */
    suspend fun setActiveEmbeddingProviderId(id: String)

    /**
     * Id of the embedding provider the stored memory vectors were last
     * (re-)embedded with, or `null` when unknown (no provider switch and no
     * re-embed has happened yet — the store is then in sync by definition).
     *
     * Settings → Memory compares this against [activeEmbeddingProviderId]:
     * a mismatch means existing vectors live in a different embedding space
     * than new queries, and a persistent "re-embed recommended" banner is
     * shown until the user runs a successful re-embed (or wipes the store).
     */
    val lastReembedProviderId: Flow<String?>

    /**
     * Records the provider id the memory store is now consistent with —
     * called after a successful full re-embed and after a full memory wipe
     * (an empty store has no stale vectors).
     *
     * @param id One of the `EmbeddingProvider.ID_*` constants.
     */
    suspend fun setLastReembedProviderId(id: String)

    /**
     * `true` when the agent should automatically extract durable facts from a
     * conversation into long-term memory after a pipeline run completes. Drives
     * the "Auto-extract from conversations" toggle in Settings → Memory and is
     * the short-circuit gate consulted by the auto-extraction trigger. Defaults
     * to [app.knotwork.android.domain.constants.SettingsDefaults.AUTO_EXTRACT_ENABLED_DEFAULT]
     * (`true`).
     */
    val autoExtractEnabled: Flow<Boolean>

    /**
     * Persists the auto-extract memory toggle.
     *
     * @param enabled `true` to enable automatic memory extraction, `false` to
     *   disable it (the trigger then short-circuits to a no-op).
     */
    suspend fun setAutoExtractEnabled(enabled: Boolean)

    /**
     * `true` when the background memory-compaction worker is allowed to run.
     * Drives the "Background compaction" toggle in Settings → Memory and is the
     * short-circuit gate consulted by `MemoryCompactionWorker` at run time (a
     * user flipping it off while a run is queued still cancels the work).
     * Defaults to
     * [app.knotwork.android.domain.constants.SettingsDefaults.MEMORY_COMPACTION_ENABLED_DEFAULT]
     * (`true`).
     */
    val memoryCompactionEnabled: Flow<Boolean>

    /**
     * Persists the background memory-compaction toggle.
     *
     * @param enabled `true` to enable background compaction, `false` to disable
     *   it (the worker then short-circuits to a no-op).
     */
    suspend fun setMemoryCompactionEnabled(enabled: Boolean)

    /**
     * `true` when long-term memory operations should emit verbose diagnostics.
     * Drives the "Verbose memory logging" toggle in Settings → Privacy.
     *
     * When enabled, [app.knotwork.android.domain.engine.GraphExecutionEngine] expands
     * each `MemoryAccess` console event with a per-hit snippet and similarity
     * score, and [app.knotwork.android.domain.usecases.MemoryCompactionUseCase] logs
     * the cluster membership of every consolidation. Off by default to keep the
     * console and logcat quiet for users who do not need the detail. Defaults to
     * [app.knotwork.android.domain.constants.SettingsDefaults.VERBOSE_MEMORY_LOGGING_ENABLED_DEFAULT]
     * (`false`).
     */
    val verboseMemoryLoggingEnabled: Flow<Boolean>

    /**
     * Persists the verbose memory logging toggle.
     *
     * @param enabled `true` to enable verbose memory diagnostics, `false` to fall
     *   back to the terse one-line summaries.
     */
    suspend fun setVerboseMemoryLoggingEnabled(enabled: Boolean)

    /**
     * Age threshold, in days, beyond which a non-pinned chunk becomes eligible
     * for compaction. Chunks younger than this keep their exact wording; only
     * older ones are clustered and consolidated. Defaults to
     * [app.knotwork.android.domain.constants.SettingsDefaults.MEMORY_COMPACTION_AGE_DAYS_DEFAULT]
     * (30).
     */
    val memoryCompactionAgeDays: Flow<Int>

    /**
     * Persists the compaction age window.
     *
     * @param days The new age threshold in days; callers should keep it within
     *   a sane range (validation of user-entered values lives in the Settings
     *   ViewModel).
     */
    suspend fun setMemoryCompactionAgeDays(days: Int)

    /**
     * Hard ceiling on the total number of stored memory chunks. When the table
     * grows past this, compaction is triggered out-of-schedule to keep the
     * database bounded. Defaults to
     * [app.knotwork.android.domain.constants.SettingsDefaults.MAX_MEMORY_CHUNKS_DEFAULT]
     * (5000).
     */
    val maxMemoryChunks: Flow<Int>

    /**
     * Persists the max-chunks hard limit.
     *
     * @param limit The new hard limit; callers should keep it within a sane
     *   range (validation of user-entered values lives in the Settings
     *   ViewModel).
     */
    suspend fun setMaxMemoryChunks(limit: Int)

    /**
     * Resets the local-generation sampling parameters back to the
     * documented defaults ([SettingsDefaults.TEMPERATURE_DEFAULT],
     * [SettingsDefaults.TOP_K_DEFAULT], [SettingsDefaults.TOP_P_DEFAULT],
     * [SettingsDefaults.REPETITION_PENALTY_DEFAULT],
     * [SettingsDefaults.MAX_CONTEXT_LENGTH_DEFAULT],
     * [SettingsDefaults.PIPELINE_MAX_STEPS_DEFAULT]) — and, alongside them, the
     * autonomous-run ceilings, which share this reset because they are the same
     * family of runtime bound: the background step cap and both token caps. Used
     * by the "Reset to defaults" action in Settings → LLM parameters.
     */
    suspend fun resetSamplingDefaults()

    /**
     * Restores **every tunable preference** to its recommended default from
     * [app.knotwork.android.domain.constants.SettingsDefaults], in a single
     * atomic write. Backs the "Reset all settings" action in Settings → Privacy.
     *
     * Strictly scoped to *tunable values* — it deliberately does **not** touch
     * user-entered content or configuration: long-term memory, chats,
     * pipelines, presets, skills, secrets (API keys / HuggingFace token),
     * MCP servers, the `http_request` domain allowlist, per-tool enable/disable
     * and risk overrides, the user-authored system-instructions prefix, the
     * active embedding provider (and its re-embed
     * marker), the default-pipeline binding, the per-surface entry-point
     * pipeline bindings (share target, Quick Settings tile), the selected local
     * backend, and onboarding / transient state. Everything it resets has a documented
     * recommended default; everything it leaves alone is the user's own data.
     */
    suspend fun resetToRecommendedDefaults()
}
