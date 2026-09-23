# API & Integration Conventions

This document collects the cross-cutting conventions that govern how the
codebase talks to its external dependencies — LiteRT-LM, AppFunctions
Jetpack, the Model Context Protocol (MCP), cloud LLM APIs, Room, and
DataStore — plus the error-handling and JSON-parsing patterns shared
across all of them.

For end-to-end recipes (adding a new tool, cloud provider, node type, or
prompt variable), see [`extending.md`](extending.md). For the broader
layering rationale, see [`architecture.md`](architecture.md).

---

## LiteRT-LM (on-device inference)

- Always load the model in a coroutine on `Dispatchers.IO`.
- Expose inference as a `Flow<String>` (token streaming) from the
  repository.
- Implement a `ModelSession` wrapper that holds the native handle and
  exposes `suspend fun generate(prompt: String): Flow<String>`.
- Call `session.close()` from the `ViewModel.onCleared()` and from the
  foreground service's `onDestroy()` to prevent OOM.
- Gate every inference call with a `Mutex` to prevent concurrent session
  access.
- Log memory usage before and after model load with `Timber.d`.

```kotlin
// Canonical pattern
interface LiteRtRepository {
    suspend fun loadModel(modelPath: String): Result<Unit>
    fun generate(prompt: String): Flow<String>
    suspend fun unloadModel()
    val isModelLoaded: StateFlow<Boolean>
}
```

---

## AppFunctions (tool calling)

- **Caller-side** discovery and dispatch lives in
  `data/tools/local/LocalAppFunctionManager`. AppFunctions are keyed by
  their qualified name (`"${packageName}/${id}"`) so identical ids
  from different packages can coexist. `ToolRepositoryImpl` merges the
  discovered set into the visible tool catalogue.
- **Callee-side** wrappers live in `data/tools/local/appfunctions/`
  annotated with `androidx.appfunctions.service.AppFunction`. The
  auto-merged `androidx.appfunctions.service.PlatformAppFunctionService`
  (from `appfunctions-service`) dispatches incoming requests through
  KSP-generated invokers. Do **not** subclass `AppFunctionService` or
  write a manual router; the recipe for a new wrapper lives in
  [`extending.md`](extending.md) §2.5.
- **`AppFunctionDataCodec` is the single point of serialization.** Every
  conversion between the LLM-emitted JSON argument string and the typed
  `AppFunctionData` consumed by `AppFunctionManager.executeAppFunction(...)`
  goes through
  [`AppFunctionDataCodec`](../app/src/main/java/app/knotwork/android/data/tools/local/AppFunctionDataCodec.kt).
  Likewise for the response: `ExecuteAppFunctionResponse` → flat JSON
  for the agent's observation log. Do not hand-roll `JSONObject`
  walking in callers — the codec is the source of truth for type
  coercion rules and `IllegalArgumentException` boundaries.
- **`ToolRepository.getRisk(name)` is the single source of truth for
  HITL.** The gate (`ToolInvocationGate`) consults it once per
  invocation. The risk resolves through three layers:
  built-in defaults (`search_tool` → `READ_ONLY`,
  `schedule_task` / `delegate_task` → `SENSITIVE`), per-tool overrides
  for discovered AppFunctions (keyed by tool name) and for MCP tools
  (keyed per server by the `mcp:<sha8(serverUrl)>:<toolName>` id, so the
  same tool name on two servers stays two decisions) via
  `SettingsRepository.setToolRiskOverride`, then `SENSITIVE` as the
  conservative fallback. The override is the user's voice, never the
  server's — MCP's `readOnlyHint` / `destructiveHint` annotations are
  deliberately not consulted, since a server able to declare its own
  tools read-only could walk straight past the gate.
- **Which risks ask is `ToolApprovalPolicy.requiresApproval(risk)` —
  written once, interpreted nowhere else.** `AllCalls` asks for every
  call, `SensitiveOrDestructive` (the default) for `SENSITIVE` and
  `DESTRUCTIVE`, `NeverPrompt` for `DESTRUCTIVE` only: **no policy quiets
  a `DESTRUCTIVE` call.** The one control that removes its prompt is
  *Block destructive tools*, and it refuses the call instead of running
  it. A node's `alwaysConfirm` (TOOL and SKILL — the node types that
  dispatch tools) is ORed on top and can only add a prompt;
  `ToolInvocationGate.dispatch` takes it without a default, so a new node
  type that dispatches tools has to pass its own. The legacy
  `requires_user_confirmation` DataStore key is a migration input only.
- **An approval answers a request, never a session.** The gate mints a
  `requestId` for every request it raises; the chat card, both
  notifications and the parked record carry it, and every answer goes
  through `SubmitApprovalDecisionUseCase`, which settles only the request
  it names. A parked request and a live one coexist in one session, so
  "whatever the session is waiting on" is not an address. A new answering
  surface carries the id; a new caller of `resumeWithApproval`,
  `executeTool` or `invokeByName` fails `HitlDispatchKonsistTest`.

```kotlin
enum class ToolRisk { READ_ONLY, SENSITIVE, DESTRUCTIVE }

interface Tool {
    val id: String
    val risk: ToolRisk
    val description: String
    suspend fun execute(args: Map<String, String>): ToolResult
}
```

---

## Model Context Protocol (MCP)

- The MCP client lives in the `data/mcp` package.
- Live connections have exactly one owner: the `@Singleton`
  `McpConnectionPool` (`data/mcp/`), keyed by server URL and holding the
  config each client was connected with. `McpServerRepositoryImpl` (Tools
  screen health + TTL tool-list cache) and `ToolRepositoryImpl` (the
  agent's calls) both go through it. Never add a second pool: when these
  two each kept their own, the health indicator described one session
  while the agent used another. The pool's per-URL lock is held only
  across connect / invalidate — never while a caller uses the client, or
  concurrent tool calls to one server would serialise behind it.
- Connections are lazy: they open on first use and close when the agent
  session ends.
- Every MCP call is wrapped in `try`/`catch` that **re-throws
  `CancellationException` from a dedicated first catch clause** and maps all
  other failures to `ToolResult.Error`. `runCatching` is never used around
  these (suspending) calls — it would swallow cancellation; see
  [code-style.md](code-style.md) § Coroutines & Flow.
- Every network round-trip carries an **explicit deadline applied in our own
  code**: `withTimeoutOrNull` around the call inside `KoogMcpClient` — 60 s for
  a tool call (matching the cloud-LLM budget below), 30 s for the connect
  handshake. Ktor's `HttpTimeout` plugin is deliberately **not** used: it does
  not apply to MCP's SSE-framed response path, so installing it removes the
  engine's own socket timeout without supplying a replacement and leaves the
  call unbounded. `withTimeoutOrNull` rather than `withTimeout`, because a
  timeout surfacing as a `CancellationException` would propagate past the
  tool-error mapping and cancel the entire run.
- **One server per tool name.** Which server answers a name is decided by one
  rule, `McpToolRouting` in the domain layer, and read everywhere — the agent
  catalogue, `ToolRepository.getRisk`, `executeTool` and the Tools screen. A
  local tool (built-in or AppFunction, disabled included) owns its name;
  otherwise the first server in the user's order with the name switched on
  serves it, and every other entry is left out of the catalogue. There is no
  failover to another server after an error. The gate passes the risk it
  decided on (`ToolExecutionContext.gatedRisk`) and the dispatch refuses a call
  whose serving server's risk no longer matches. Never pick an MCP server for a
  name any other way: `McpRoutingMatrixTest` runs every routing state of two
  servers and fails a second resolver.
- **Everything a server sends is bounded in `KoogMcpClient`**, the one place
  that covers both the agent and the Tools screen: a name outside the MCP
  naming rule is not published (nor callable), descriptions are clamped, the
  catalogue is capped by tool count and rendered size, and a result is cut at
  the user's `httpToolMaxResponseBytes` budget with a marker. Read MCP content
  through the client, never around it.
- **MCP credentials** (Bearer tokens, Basic passwords, API-key values) are
  stored in the **Keystore-backed encrypted store**, keyed per server by a hash
  of its URL — never in the plain `mcp_servers_json` DataStore entry, which
  holds only non-secret metadata (URL, transport, name, custom headers). Auth
  embedded inline by earlier releases is migrated into the encrypted store on
  first read and stripped from the JSON.

---

## Cloud LLM APIs (OpenAI / Anthropic / Google / DeepSeek / Ollama)

- All cloud providers implement the domain interface
  `CloudLlmClientFactory` (data-layer impl: `KoogClientFactory`).
- **Every client that carries model traffic asks `ModelNetworkGate` before it is
  built** — the chat clients in `KoogClientFactory` and the network embedding
  providers alike. The gate applies the *Block network from local model*
  restriction (hosted providers refused; Ollama only at `localhost` or a private
  IPv4 literal, whatever the scheme — see [architecture.md](architecture.md) §4.4)
  and, for Ollama, the cleartext rule. A model client that skips it breaks the
  restriction silently: the Ollama chat client and both embedding providers once
  did. Name the cause of a `null` client with
  `CloudLlmClientFactory.unavailabilityOf`, never by re-reading settings.
- **The gate also owns one tool.** `search_tool` asks
  `ModelNetworkGate.networkToolRefusal` before it opens its connection, and
  `ToolRepositoryImpl` withholds it from the catalogue while the restriction is
  on. MCP and `http_request` stay outside — they are opt-in and each has its own
  control, which `search_tool` does not. A new built-in tool that reaches the
  network on its own belongs behind the same call, and behind an entry in
  `NetworkEgressInventoryKonsistTest`.
- API keys are stored in **the Keystore-backed encrypted store only**
  (`KeystoreBackedPrefsStore` — AES-GCM under a dedicated Android Keystore
  key; see [architecture.md](architecture.md) §5.2) — never in plain
  DataStore, log files, exported pipelines, or anything committed to the
  repository.
- The **Hugging Face access token** used to install gated models from the
  Discover screen lives in the **same Keystore-backed store** (keyed
  `hugging_face_token`), never in plain DataStore. It is attached by the
  downloader from the request's own URL — `https` and host `huggingface.co`, by
  equality — never from "a token is saved": the Hub's CDN redirect needs no token
  (OkHttp drops `Authorization` on a host change), and a pasted URL for any other
  host never gets it. Discovery browsing and metadata calls are anonymous.
- **Every cloud client carries an explicit `ConnectionTimeoutConfig`**, applied in
  `KoogClientFactory`: 60 s socket, 30 s connect, 900 s request. The socket value
  is the load-bearing one because Ktor applies it *per read* — it bounds how long
  the provider may stay **silent**, not how long a healthy answer may take, so a
  long streaming reply is never cut short for being long. Do not "simplify" this
  to a single overall timeout. Leaving the config off is not neutral: Koog's own
  default is 900 s for both request and socket, measured to hold a node for
  900 033 ms against a stalled provider.
- **A stream that ends without a finish reason is a failure, not an answer.** The
  OpenAI-compatible clients end a dropped stream normally and simply omit the
  finish reason, so the only signal that a reply was truncated is the absent
  `StreamFrame.End.finishReason`. `CloudLlmNodeExecutor` rejects such a response
  instead of forwarding a half-written answer. The check is per provider and
  enabled only where the behaviour was measured — Koog's Ollama client never
  emits a finish reason at all, so enabling it there would fail healthy runs.
- **Provider error text is scrubbed before it is shown, logged or stored**
  (`CloudErrorSanitizer`). Google authenticates by query parameter, so its
  transport errors arrive carrying the API key; credentials must never reach the
  run console, the run trace or logcat. An executor that calls a provider scrubs
  its own error (`sanitize(e)`) and logs the scrubbed message, not the
  throwable. The engine and the crash-reporting tree redact again as a backstop
  (`redactSecrets`) — a backstop, not a licence to skip the first step.
- Use the unified `CLOUD` pipeline node with a `provider` parameter — do
  not add per-provider node types to the pipeline graph.

---

## Room database

- Every DAO method that returns a `Flow` must be annotated with `@Query` —
  no magic.
- Use `@Transaction` for operations that touch multiple tables.
- Database migrations must be explicit
  (`Migration(oldVersion, newVersion) { ... }`). Auto-migrations are
  allowed for additive changes only.
- Always inject a `CoroutineDispatcher` into `DataSource` classes so they
  remain testable.

---

## DataStore (settings)

- Define a single `PreferencesDataStore` instance per feature module, not
  per class.
- All preference keys are `object`s in a `PreferenceKeys` companion
  object.
- Wrap `DataStore.data` collection in a `catch` operator to handle
  `IOException`.

---

## JSON parsing

- Use `org.json.JSONObject` or `kotlinx.serialization` — **never** manual
  string parsing.
- Always handle `JSONException` and map it to a typed error result.
- When parsing tool arguments from LLM output, use the canonical parser in
  `domain/parser/ToolArgumentParser.kt`.

---

## Error handling

- All repository methods return `Result<T>` (`kotlin.Result`) or emit a
  `sealed class` state hierarchy.
- **Never** propagate raw exceptions to the presentation layer.
- Log every error with `Timber.e(throwable, "Context message")`.
