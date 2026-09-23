# Extending the Agent

This guide is for contributors who want to add new functionality to
**Knotwork**, an on-device AI agent for Android — a new node type for the
pipeline engine, a new tool the agent can call, a new cloud LLM provider, or a
new prompt variable. Each section is a step-by-step recipe with the
exact files to touch and the order in which to touch them.

It assumes you have already read [`docs/architecture.md`](architecture.md)
(layers, pipeline engine, integrations). For end-user documentation,
see [`docs/user-guide.md`](user-guide.md).

---

## Table of contents

1. [Add a new `NodeType`](#1-add-a-new-nodetype)
2. [Add a new `Tool`](#2-add-a-new-tool) (includes §2.5 — exposing a
   built-in as a callee-side AppFunction; §2.6 — adding a workspace tool)
3. [Add a new cloud provider](#3-add-a-new-cloud-provider)
4. [Add a new prompt variable](#4-add-a-new-prompt-variable)
5. [Add a bundled preset](#5-add-a-bundled-preset) — pipeline (§5.1),
   prompt (§5.2) and skill (§5.3); §5.4 documents the portable
   prompt-pack file format
6. [Add a new `EmbeddingProvider`](#6-add-a-new-embeddingprovider)
7. [Use input atoms and chip atoms](#7-use-input-atoms-and-chip-atoms)
8. [Synchronization table — "if you change X, also touch Y"](#8-synchronization-table)
9. [Quality gate](#9-quality-gate)

> Extending the agent is not the same job as *using* it. For what each
> node type does, what it does with its input, and recipes that wire them
> together, see [`docs/cookbook.md`](cookbook.md) — and note that adding a
> `NodeType` means regenerating its node reference (§1).

---

## 1. Add a new `NodeType`

A `NodeType` is one entry in the pipeline graph's vocabulary. Adding
one means: defining what the enum value means, writing a strategy that
executes it, wiring it into the dispatch factory, and mirroring the new
type into the browser-based editor so users can place it on the canvas.

### 1.1. Extend the `NodeType` enum

Add a new constant to
[`domain/models/NodeType.kt`](../app/src/main/java/app/knotwork/android/domain/models/NodeType.kt).
Keep the name SCREAMING_SNAKE_CASE and group it logically with similar
existing types (e.g. control-flow next to `IF_CONDITION`,
LLM-driven next to `LITE_RT` / `CLOUD`).

### 1.2. Implement `NodeExecutor`

Create a new class under
`app/src/main/java/app/knotwork/android/domain/engine/executors/` that
implements
[`NodeExecutor`](../app/src/main/java/app/knotwork/android/domain/engine/executors/NodeExecutor.kt):

```kotlin
class MyNewNodeExecutor @Inject constructor(
    // dependencies (repositories, the prompt engine, …) go here
) : NodeExecutor {
    override fun execute(
        node: NodeModel,
        inputText: String,
        sessionId: String,
        originalPrompt: String,
    ): Flow<NodeOutput> = flow {
        // 1. emit NodeOutput.State events for progress
        // 2. emit exactly one terminal NodeOutput.Result at the end
    }
}
```

Contract reminders:

- Long-running work goes on `Dispatchers.IO` (or `Dispatchers.Default`
  for pure compute). Never block the main thread.
- The flow must terminate with **exactly one** `NodeOutput.Result`
  carrying a `NodeExecutionResult`. `NodeOutput.State` events in
  between are forwarded to the inline mini-console.
- If your executor calls into the LLM, run the assembled prompt
  through `PromptTemplateEngine.render(...)` first so `$DATE`,
  `$TOOLS` and friends get substituted.
- If your executor calls into the LLM, wrap reads of `node.systemPrompt`
  with the matching `DefaultPrompts.<Node>.SYSTEM_FALLBACK` so an
  unset prompt does not produce an empty system message:
  `val sp = node.systemPrompt ?: DefaultPrompts.MyNode.SYSTEM_FALLBACK`.

### 1.3. Register the executor in the factory

[`NodeExecutorFactory`](../app/src/main/java/app/knotwork/android/domain/engine/executors/NodeExecutorFactory.kt)
is the single dispatch point used by `GraphExecutionEngine`. Inject
the new executor and add a branch to the `when`:

```kotlin
class NodeExecutorFactory @Inject constructor(
    // existing executors …
    private val myNewNodeExecutor: MyNewNodeExecutor,
) {
    fun getExecutor(type: NodeType): NodeExecutor = when (type) {
        // existing branches …
        NodeType.MY_NEW_TYPE -> myNewNodeExecutor
    }
}
```

The `when` is exhaustive, so the Kotlin compiler will refuse to build
until every `NodeType` is routed. Several types may share the same
executor — `INTENT_ROUTER`, `DECOMPOSITION`, and `EVALUATION` all
delegate to `SystemNodeExecutor` because they have the same
"LLM-with-system-prompt, no streaming side effects" shape. Reuse an
existing executor when the new type fits that shape; otherwise write a
fresh class.

**Detecting an image attachment.** Only text travels the graph — the picture
itself reaches a single on-device vision step. If your executor needs to know
*whether* the run carries an image (e.g. to branch on it), read
`scope.imageDelivery != null` from the `ExecutionScope` passed to `execute`. The
holder is non-null for the whole run tree whenever the input has an image,
regardless of whether the vision step has consumed it yet, so it is a stable
"the user sent a picture" signal that never leaks the pixels. `IF_CONDITION`
(via its `Branch True when input has an image` toggle) and `INTENT_ROUTER`
(which appends `DefaultPrompts.System.IMAGE_PRESENT_NOTE` to its prompt) both
use it.

### 1.4. Provide default context flags

`NodeContextConfig.defaultForType(type)` in
[`domain/models/NodeContextConfig.kt`](../app/src/main/java/app/knotwork/android/domain/models/NodeContextConfig.kt)
returns the recommended starting context for a freshly-created node of
each type. Add a `when` branch for your new type that selects the
minimum set of blocks the executor actually needs:

```kotlin
NodeType.MY_NEW_TYPE -> NodeContextConfig(
    chatHistory = false,
    originalTask = true,
    nodeInput = true,
    longTermMemory = false,
    toolResults = false,
)
```

Defaults are not validation. Users can still toggle any flag in the
visual editor. The goal is "right out of the box" — a small LiteRT
model should not default to receiving the entire chat history, and a
tool node should not default to seeing long-term memory.

### 1.5. Update graph validation if needed

[`PipelineGraph.validate()`](../app/src/main/java/app/knotwork/android/domain/models/PipelineGraph.kt)
already enforces the universal invariants (exactly one `INPUT`, at
least one `OUTPUT`, DAG, no dangling connections, non-empty
`contextConfig` for executor-driven types). You only need to touch
`validate()` if the new node type has special structural rules — for
example, "must be a singleton in the graph" or "must always feed into
an `OUTPUT`".

`validate()` is a pure function on a single graph, so it cannot enforce
rules that depend on *other* graphs or on settings. When a node type
references another entity by id (as `PIPELINE` references a target
pipeline and `SKILL` references a skill), put the single-graph check in
`validate()` (e.g. `MissingTargetPipeline` / `MissingSkill` for an unset
reference) and the cross-entity checks in a dedicated repository-backed
validator invoked from the relevant use case. [`PipelineCompositionValidator`](../app/src/main/java/app/knotwork/android/domain/services/PipelineCompositionValidator.kt)
is the reference example: `SavePipelineUseCase` runs it alongside
`validate()` to reject reference cycles, dangling targets, and chains
nested deeper than `SettingsRepository.pipelineMaxNestingDepth` before a
graph can be persisted.

### 1.6. Mirror the new type into the browser editor

[`pipeline-editor.html`](../pipeline-editor.html) is a standalone
single-file HTML app that mirrors the Android pipeline schema, and
standalone is meant literally: the graph library (Drawflow, MIT) is inlined
into the file rather than fetched from a CDN, so the editor works from disk
with no network. Keep it that way — a `<script src="https://…">` would
quietly undo it, and a local-first product whose editor needs a CDN is a
contradiction a reader will find.

The editor is also the most common place where changes drift out of sync —
every change to the `NodeType` set must be reflected here in **three**
places:

1. `NODE_TYPES` array (around line 827) — add a row with `id`,
   `label`, `color`, `icon`, `inputs`, `outputs`. Match the
   inputs/outputs to your executor's connector shape (most types are
   `1/1`; `INPUT` is `0/1`; `OUTPUT` is `1/0`; branching types like
   `IF_CONDITION` and `QUEUE_PROCESSOR` are `1/2`).
2. `defaultContextConfig(typeId)` (around line 972) — add a `case`
   that returns the same flags as your Kotlin `defaultForType` branch.
3. `NODE_TYPE_TOOLTIPS` (around line 1059) — add a one-line tooltip
   for the palette item.

If your node has a custom default system prompt, also add it to
`DEFAULT_SYSTEM_PROMPTS` (around line 855) so the editor seeds new
nodes with the same baseline as the Android app.

If your node carries **typed config** (anything beyond name + system
prompt), mirror the Android `NodeConfig` into the JS rich-config block:
add a `case` to `defaultRichConfig`, `richToFlat`, `encodeRichEnvelope`,
`decodeRichEnvelope`, `deriveRichFromFlat`, `renderFormFields`, and
`validateRichConfig`. Keep the envelope keys byte-identical to the
Kotlin `NodeConfigCodec` encoder so a document round-trips through both
editors unchanged.

If your node **references another entity by id** (like `PIPELINE` →
target pipeline, `SKILL` → skill), there are two extra obligations:

1. Carry the id in the **flat `config` block** of the export, not only in
   the rich `nodeConfig` envelope — the runtime executors read the flat
   `NodeModel` field (`targetPipelineId` / `skillId`), so
   [`PipelineJsonSerializer`](../app/src/main/java/app/knotwork/android/domain/pipelineio/PipelineJsonSerializer.kt)
   emits and reads it there, and the editor mirrors that in `exportToJson`
   / `importFromJson`.
2. The browser holds a **single document**, so it can only flag a *direct*
   self-reference and an unset/unknown id (a node badge plus a validation
   entry). The full transitive cycle and depth-limit checks stay in the
   app's `PipelineCompositionValidator` — document that limitation in a
   comment next to the editor check rather than pretending to replicate it.

The public node reference in [`docs/cookbook.md`](cookbook.md) is the
other mirror, and unlike the HTML it is generated: add the type to
`CookbookDocsGenerator.NODE_DOC_META` with a reader-facing sentence, give
every field of its `NodeConfig` a verdict in `FIELD_REACH`, then run
`./gradlew :app:generateCookbookDocs` and commit the result.
`verifyCookbookDocs` fails the build until you do, and generation itself
fails if either table is missing an entry — the reference cannot silently
lose a node type or a field.

### 1.7. Tests

- A unit test for the executor that covers the happy path, at least
  one error branch, and (if relevant) the empty-input case.
- A new case in
  [`GraphExecutionEngineTest`](../app/src/test/java/app/knotwork/android/domain/engine/GraphExecutionEngineTest.kt)
  that walks a minimal graph including the new node type.
- If `defaultForType` or `validate()` changed, add a unit test in the
  matching test file.

---

## 2. Add a new `Tool`

The agent calls tools through a Hilt multibinding keyed by tool name.
Adding a built-in tool is therefore a small, mechanical change: one
class plus one `@Binds` line.

### 2.1. Implement `LocalToolExecutor`

Create a class under
`app/src/main/java/app/knotwork/android/data/tools/local/executors/` that
implements
[`LocalToolExecutor`](../app/src/main/java/app/knotwork/android/domain/repositories/LocalToolExecutor.kt):

```kotlin
class MyToolExecutor @Inject constructor(
    // dependencies you need
) : LocalToolExecutor {

    override val toolName: String = TOOL_NAME

    // `context` carries trusted engine-supplied values (e.g. the invoking chat
    // session id); ignore it if your tool needs none. The interface gives it a
    // default, but an override may not repeat the default — list both params.
    override suspend fun execute(arguments: String, context: ToolExecutionContext): String {
        // 1. parse `arguments` as JSON (kotlinx.serialization or JSONObject —
        //    never manual string splitting)
        // 2. perform the action
        // 3. return a short textual result for the agent observation log
    }

    companion object {
        const val TOOL_NAME = "my_tool"
    }
}
```

Existing implementations to crib from:

- [`SearchToolExecutor`](../app/src/main/java/app/knotwork/android/data/tools/local/executors/SearchToolExecutor.kt)
  — calls a public HTTP API.
- [`DelegateTaskExecutor`](../app/src/main/java/app/knotwork/android/data/tools/local/executors/DelegateTaskExecutor.kt)
  — delegates to a different LLM provider via the cloud client factory.
- [`ScheduleTaskExecutor`](../app/src/main/java/app/knotwork/android/data/tools/local/executors/ScheduleTaskExecutor.kt)
  — bridges to a domain use case (`ScheduleTaskUseCase`).

### 2.2. Register the executor

Add one `@Binds @IntoMap @StringKey(...)` entry to
[`di/LocalToolsModule.kt`](../app/src/main/java/app/knotwork/android/di/LocalToolsModule.kt):

```kotlin
@Binds
@IntoMap
@StringKey(MyToolExecutor.TOOL_NAME)
abstract fun bindMyToolExecutor(executor: MyToolExecutor): LocalToolExecutor
```

That is the only DI touch-point.
[`ToolRepositoryImpl`](../app/src/main/java/app/knotwork/android/data/repositories/ToolRepositoryImpl.kt)
already consumes the multibinding map and dispatches by name; you do
not edit it.

### 2.3. Declare risk and handle the human-in-the-loop gate

Every tool has a `ToolRisk`:

| Risk          | Behaviour                                                                                          |
|---------------|----------------------------------------------------------------------------------------------------|
| `READ_ONLY`   | Runs immediately, unless the user's *Approve tool calls* policy is `All`.                          |
| `SENSITIVE`   | The gate emits `WaitingForApproval` and suspends until approval — unless the policy is `Never`.   |
| `DESTRUCTIVE` | Asks under every policy, with a typed confirmation. Use whenever data can be irreversibly modified. |

If your tool sends an email, deletes a file, makes a purchase, or
mutates any system state the user would care to undo, set the risk to
`DESTRUCTIVE` — it is the only tier no approval policy quiets. If it
reads private data (location, contacts, calendar) without modifying
anything, set it to `SENSITIVE`, knowing that a user who chose `Never`
lets it run unasked. Which tiers ask is decided in one place,
`ToolApprovalPolicy.requiresApproval`, and every tool call goes through
`ToolInvocationGate` — there is no code path that bypasses the gate.

For **discovered AppFunctions** (tools surfaced by `LocalAppFunctionManager`
from other packages), the default is `SENSITIVE` — the platform
`AppFunctionManager` metadata gives no trustworthy signal about side
effects. The user can downgrade a specific tool to `READ_ONLY` (or
upgrade it to `DESTRUCTIVE`) via
[`SettingsRepository.setToolRiskOverride(toolKey, risk)`](../app/src/main/java/app/knotwork/android/domain/repositories/SettingsRepository.kt),
which writes into the `toolRiskOverrides` flow persisted under
DataStore key `app_function_risk_overrides`. `ToolRepository.getRisk(name)`
consults the override map first and falls back to the conservative
default.

**MCP tools** work the same way and share that map, with one difference:
they are keyed per server, by the tool's `mcp:<sha8(serverUrl)>:<toolName>`
id rather than its bare name. A long shared prefix is normal in MCP
catalogues, and two servers advertising `create_issue` must stay
independent decisions — the same rule `disabledMcpTools` already follows.
The override is the **user's** voice and never the server's: MCP's
`readOnlyHint` / `destructiveHint` tool annotations are deliberately not
consulted, because a remote server able to declare its own tools
read-only could walk straight past the confirmation gate.

There is no settings screen for risk overrides yet — the API is reachable
programmatically only.

Most tools have a single, static risk. A tool whose risk depends on the
*call* rather than its name (the built-in `http_request`, whose `GET` is
`SENSITIVE` but whose `POST`/`PUT`/`DELETE` are `DESTRUCTIVE`) resolves it
through the argument-aware overload
[`ToolRepository.getRisk(name, arguments)`](../app/src/main/java/app/knotwork/android/domain/repositories/ToolRepository.kt):
the HITL gate passes the resolved argument string so the confirmation
strength matches the concrete request. Keep the per-call decision in one pure
helper that both the risk lookup and the executor's own enforcement read from
(`http_request` uses `HttpRequestPolicy`), so the gate and the actual refusal
can never diverge. An unparsable call must fall back to the strictest risk.

### 2.4. Tests

- Unit test the executor with mocked dependencies. Cover the happy
  path, an invalid-arguments branch, and the failure branch
  (`runCatching` mapped to `ToolResult.Error`).
- If the tool surfaces new UI (e.g. a custom confirmation dialog), add
  an instrumented Compose test under `androidTest/`.

### 2.5. Expose a built-in to other apps (callee-side AppFunction)

If you want a third-party app to be able to call your tool through the
system [`AppFunctionManager`](https://developer.android.com/reference/android/app/appfunctions/AppFunctionManager),
add an `@AppFunction`-annotated wrapper next to the existing
[`SearchAppFunction`](../app/src/main/java/app/knotwork/android/data/tools/local/appfunctions/SearchAppFunction.kt).
The library's `PlatformAppFunctionService` (from
[`androidx.appfunctions`](https://developer.android.com/reference/androidx/appfunctions/package-summary),
still alpha — the service class itself has no published reference page yet)
is auto-merged from `appfunctions-service` and dispatches incoming
requests through KSP-generated invokers — you do **not** subclass
`AppFunctionService` or write a manual router.

Publishing is open to any app; **being called is not**. Discovering and
executing another app's AppFunctions requires `EXECUTE_APP_FUNCTIONS`,
declared `internal|privileged` on Android 16 and granted to privileged
system apps only — so in practice the caller that reaches your wrapper
is a system assistant, not an arbitrary app from the store. Publish the
wrapper anyway if the function belongs in that catalogue, but do not
expect a peer app to invoke it.

Only expose tools that are safe to run on behalf of an unknown caller —
typically `READ_ONLY` operations. `schedule_task` and `delegate_task`
are intentionally not exposed (scheduling background work or burning
the user's cloud API quota at a third party's request would violate the
user's expectation of agency).

`schedule_task` additionally refuses to schedule anything once more
than `ScheduleTaskUseCase.MAX_SCHEDULED_RUNS_PER_HOUR` scheduled runs
have started within the last hour, and returns that refusal as the tool
result. The guard keys on the *rate of scheduled runs*, not on the depth
of the queue: a task whose prompt tells the agent to schedule its own
successor keeps exactly one item queued at all times, so queue depth
never reveals it. Every task the tool schedules is tagged
(`ScheduledTaskTag`) so the Active-tasks screen can name it and stop all
of them at once without touching trigger or Quick-Settings work.

1. **Create the wrapper.** Add a `@Singleton` class under
   `data/tools/local/appfunctions/`. The first parameter must be
   `androidx.appfunctions.AppFunctionContext` — the KSP compiler
   rejects `@AppFunction` declarations whose first parameter is
   anything else. Kotlin defaults on subsequent parameters are not
   honoured, so normalise blank inputs inside the body if you want a
   fallback:
   ```kotlin
   @Singleton
   class MyAppFunction @Inject constructor(
       private val backingTool: BackingTool,
   ) {
       @AppFunction
       @Suppress("UnusedParameter")
       suspend fun invoke(context: AppFunctionContext, arg: String): String {
           require(arg.isNotBlank()) { "arg must be non-blank" }
           return backingTool.run(arg)
       }
   }
   ```
2. **Register the Hilt-managed factory.** The AppFunctions runtime
   calls a reflective no-arg constructor by default, which is
   incompatible with `@Inject constructor(...)`. Add an entry to
   `App.appFunctionConfiguration` so the runtime asks Hilt for an
   instance:
   ```kotlin
   @Inject
   lateinit var myAppFunctionProvider: Provider<MyAppFunction>

   override val appFunctionConfiguration: AppFunctionConfiguration
       get() = AppFunctionConfiguration.Builder()
           .addEnclosingClassFactory(SearchAppFunction::class.java) {
               searchAppFunctionProvider.get()
           }
           .addEnclosingClassFactory(MyAppFunction::class.java) {
               myAppFunctionProvider.get()
           }
           .build()
   ```
3. **KSP auto-generates the rest.** The
   `androidx.appfunctions:appfunctions-compiler` KSP processor — already
   wired up in `app/build.gradle.kts` with
   `appfunctions:aggregateAppFunctions=true` — emits the per-class
   `*_AppFunctionInventory.kt` / `*_AppFunctionInvoker.kt` Kotlin
   artefacts plus the leaf-app `app_functions.xml` and
   `app_functions_v2.xml` under `assets/`. The platform indexer reads
   the XML to advertise the function to other apps.
4. **Wire id is `<ClassFQN>#<methodName>`.** Reference the KSP-generated
   `MyAppFunctionIds` object for the canonical wire string. Caveat:
   when any package segment is a Kotlin soft keyword (`data`, `value`,
   …) the compiler bakes Kotlin source-level escaping into the literal
   — see `SearchAppFunctionIds.INVOKE_ID`, whose value embeds literal
   backticks around `data`. External callers must include the
   backticks verbatim. Pick a package without soft-keyword collisions
   if you can.
5. **Tests.**
   - Unit-test the wrapper directly with a mocked `AppFunctionContext`
     (`mockk(relaxed = true)`). Cover happy path, invalid arguments,
     and the blank-fallback if applicable.
   - Add a scenario to
     [`AppFunctionsEndToEndTest`](../app/src/androidTest/java/app/knotwork/android/AppFunctionsEndToEndTest.kt)
     that resolves the metadata via `observeAppFunctions` and invokes
     the function through `AppFunctionManager.executeAppFunction(...)`.
     The test currently skips on stock Android 16 because
     `EXECUTE_APP_FUNCTIONS` is declared `internal|privileged`, which a
     developer-installed caller cannot hold; keep the
     `Assume.assumeTrue` gate.

The `:tools-probe` debug module is a deterministic peer for end-to-end
checks: install it alongside the agent's instrumented tests and a
single tap of its `MainActivity` button exercises the same callee path
externally.

### 2.6. Add a workspace tool

A **workspace tool** is a `LocalToolExecutor` that reads or writes the agent's
private file sandbox instead of calling the network. The seven built-ins
(`read_file`, `write_file`, `edit_file`, `append_file`, `delete_file`,
`list_files`, `find_files`) all share one rule: **they never touch `java.io.File`
directly** — every byte goes through the
[`AgentWorkspace`](../app/src/main/java/app/knotwork/android/domain/services/AgentWorkspace.kt)
interface, which is the single trust boundary for agent-driven file I/O.
Reuse that boundary and the sandbox guarantees (containment, quotas, the
typed error surface) come for free; bypass it and you reopen the path-traversal
and storage-exhaustion holes it exists to close. The structural map of this
contour is in [`architecture.md`](architecture.md) §4.5; the threat-model
framing is in [`SECURITY.md`](../SECURITY.md).

**Step 1 — inject `AgentWorkspace`, not a `File`.** Crib from an existing
executor (`ReadFileExecutor`, `WriteFileExecutor`, `DeleteFileExecutor` in
`data/tools/local/executors/`):

```kotlin
class CountLinesExecutor @Inject constructor(
    private val workspace: AgentWorkspace,
) : LocalToolExecutor {

    override val toolName: String = TOOL_NAME

    // The interface declares execute(arguments, context = ToolExecutionContext.EMPTY);
    // an override may not repeat the default, so both parameters are listed. Ignore
    // `context` if your tool needs no trusted environment identity (e.g. the session id).
    override suspend fun execute(arguments: String, context: ToolExecutionContext): String {
        val path = JSONObject(arguments).optString("path", "")   // never split strings by hand
        if (path.isBlank()) return "Error: missing 'path' argument."
        return when (val result = workspace.readText(path)) {
            is WorkspaceResult.Success -> "${result.value.lines().size} lines"
            is WorkspaceResult.Failure -> errorMessage(path, result.error)  // map the typed error, never throw
        }
    }

    // WorkspaceError is a sealed class of data objects (+ AnchorNotUnique(count)),
    // not an enum — there is no `.name`; map each case explicitly, as the real
    // file executors do.
    private fun errorMessage(path: String, error: WorkspaceError): String = when (error) {
        WorkspaceError.PathOutsideWorkspace -> "Error: path '$path' is outside the workspace."
        WorkspaceError.NotFound -> "Error: file '$path' not found."
        WorkspaceError.NotAText -> "Error: '$path' is not a UTF-8 text file."
        else -> "Error: '$path' could not be read."
    }

    companion object { const val TOOL_NAME = "count_lines" }
}
```

Contract reminders specific to the workspace:

- **Funnel every path through the gate.** `AgentWorkspace.resolve` is the one
  canonicalisation point; `readText` / `writeText` / `editText` / `delete` /
  `list` / `importBytes` / `exportTo` all enforce it internally. Pass the
  caller's relative path straight in — do not pre-resolve, pre-join, or
  canonicalise it yourself, or you risk re-introducing the escape the gate
  blocks.
- **Map the typed error, never throw for a refusable condition.** Workspace
  calls return `WorkspaceResult.Failure` with a `WorkspaceError`
  (`PathOutsideWorkspace`, `NotFound`, `NotAText`, `AlreadyExists`,
  `IsDirectory`, `TooLarge`, `QuotaExceeded`, `AnchorNotFound`,
  `AnchorNotUnique`). Turn each into a short, model-readable observation
  string. A refused call must surface as a `ToolResult.Error` so the run
  continues — it must not crash the pipeline.
- **Respect the quotas; don't recompute them.** The per-file and workspace-wide
  limits are enforced inside `writeText` / `importBytes`. Never stage bytes
  outside the workspace to dodge them.

**Step 2 — register it like any other tool.** Add the `@Binds @IntoMap
@StringKey(CountLinesExecutor.TOOL_NAME)` entry to
[`di/LocalToolsModule.kt`](../app/src/main/java/app/knotwork/android/di/LocalToolsModule.kt)
(see §2.2). No other DI edit is needed.

**Step 3 — pick the risk tier and declare it on the built-in.** Use the same
read / mutate / destructive mapping as the existing workspace tools:

| Your tool…                                   | `ToolRisk`     |
|----------------------------------------------|----------------|
| only reads or lists (`read_file`-shaped)     | `READ_ONLY`    |
| creates or modifies a file (`write_file`/`edit_file`-shaped) | `SENSITIVE`    |
| irreversibly removes data (`delete_file`-shaped) | `DESTRUCTIVE`  |

Built-in workspace tools carry their risk in the built-in list inside
[`ToolRepositoryImpl`](../app/src/main/java/app/knotwork/android/data/repositories/ToolRepositoryImpl.kt)
(the `getRisk(name)` source of truth), alongside `search_tool` and friends —
add your tool there with the tier from the table. If the risk depends on the
*call* rather than the name (as it does for `http_request`), resolve it through
the argument-aware `getRisk(name, arguments)` overload from one pure policy
helper that the executor's own enforcement reads from too (§2.3), so the gate
and the refusal cannot diverge.

**Step 4 — surface it to the user (docs).** A new workspace tool is a
user-visible capability: add a row to the built-in-tools table in
[`docs/user-guide.md`](user-guide.md) (the *Tools and MCP* section). The
**Files** screen already renders whatever files the tool produces — no UI work
is needed unless the tool needs a bespoke surface.

**Step 5 — tests.** Unit-test the executor against a **real** `AgentWorkspace`
backed by a JUnit `@TempDir` (the cheapest faithful sandbox), not a mocked
interface — the point is to prove the gate behaves. Cover the happy path, a
`../` traversal (assert `PathOutsideWorkspace` is mapped, not thrown), and the
relevant quota / not-found / anchor branch. Mirror the structure of the
existing `*ExecutorTest` files.

---

## 3. Add a new cloud provider

Cloud providers are dispatched by the single unified `CLOUD` node. You
do **not** create a new node type for a new provider — you teach the
existing factory and resolver about it. This keeps `pipeline-editor.html`
and the engine untouched.

### 3.1. Extend the `CloudProvider` enum

Add a constant to
[`domain/models/CloudProvider.kt`](../app/src/main/java/app/knotwork/android/domain/models/CloudProvider.kt)
with a stable wire-id (the lowercase string used in pipeline JSON,
e.g. `"mistral"`). Existing values are
`OPENAI`, `ANTHROPIC`, `GOOGLE`, `DEEPSEEK`, `OLLAMA`.

### 3.2. Implement client construction

Add a branch in
[`KoogClientFactory.createClient(...)`](../app/src/main/java/app/knotwork/android/data/engine/KoogClientFactory.kt)
that constructs the Koog executor for the new provider. If the
provider's SDK needs extra configuration (base URL, organization id),
keep all of that inside the helper method — `CloudLlmNodeExecutor`
should remain provider-agnostic.

### 3.3. Teach the model resolver

[`KoogCloudLlmModelResolver`](../app/src/main/java/app/knotwork/android/data/engine/KoogCloudLlmModelResolver.kt)
owns the per-provider default model id and (for Ollama-shaped
providers) the context-window lookup. Add an entry so the resolver
can map a free-text model id to a concrete Koog `LLModel`.

### 3.4. Store the API key securely

API keys live in the Keystore-backed encrypted store only — never in
DataStore, never in `local.properties`, never committed to git.

- Add a new key constant in
  [`ApiKeyManager`](../app/src/main/java/app/knotwork/android/data/local/ApiKeyManager.kt)
  (e.g. `MISTRAL_KEY`).
- Add reader/writer methods for the new key (or extend the generic
  ones if your provider follows the standard shape).

### 3.5. Add a Settings section

Settings is a **hub with per-category sub-screens**, driven by a
pure-domain registry. Every user-facing setting is declared once as a
`SettingEntry` in
[`SettingsRegistry`](../app/src/main/java/app/knotwork/android/domain/settings/SettingsRegistry.kt)
— that single declaration stamps its category (Generation, Models,
Memory, Pipelines, Tools, Background, Privacy, About), its Basic/Advanced
tier, its control type and its search synonyms, so the control lands in
the right sub-screen and becomes searchable automatically. Each category
renders through a catalog `*SettingsContent` composable
(`catalog/src/main/java/app/knotwork/design/screens/settings/`) fed by a
per-category delegate
(`app/src/main/java/app/knotwork/android/presentation/ui/settings/*SettingsDelegate.kt`)
under the coordinating `SettingsViewModel`.

To add a new external LLM provider:

1. Add a `ProviderId` entry in
   [`ProviderSummary.kt`](../app/src/main/java/app/knotwork/android/domain/models/ProviderSummary.kt)
   with its `cloudProvider` mapping (the wire id used for navigation and
   key storage).
2. Add a `providerSummary(ProviderId.<Name>, "<Name>", …)` row to the
   **External providers** block in
   [`ModelsSettingsDelegate`](../app/src/main/java/app/knotwork/android/presentation/ui/settings/ModelsSettingsDelegate.kt)
   — this renders the provider row inside the Models category and links it
   to its editor.
3. For a standard cloud provider there is nothing to wire in the editor:
   [`ProviderDetailScreen`](../app/src/main/java/app/knotwork/android/presentation/ui/settings/provider/ProviderDetailScreen.kt)
   dispatches on the wire id and renders the shared catalog
   [`KnotworkProviderRow`](../catalog/src/main/java/app/knotwork/design/screens/settings/KnotworkProviderRow.kt).
   A network-local provider (Ollama-style) supplies an
   `OllamaProviderInputs` bundle for the extra base-URL and context-window
   fields.
4. Persist the key and model through `ApiKeyRepository` (Keystore-backed)
   and `SettingsRepository`, exactly as `ProviderDetailViewModel` already
   does for the built-in providers.

To add a **non-provider setting**: declare a `SettingEntry` in the right
category list of `SettingsRegistry`, add its persisted key to
`SettingsRepository` with a default in
[`SettingsDefaults`](../app/src/main/java/app/knotwork/android/domain/constants/SettingsDefaults.kt),
and render the control in that category's `*SettingsContent` (plus its
delegate). The hub, sub-screen placement and search index pick it up from
the registry entry.

The catalog composables that power these screens:

- `KnotworkProviderRow` — collapsible provider card.
- `KnotworkParamSlider` — branded labelled slider.
- `KnotworkMonoTextArea` — multi-line mono text input.

### 3.6. Decide whether an absent finish reason means truncation

[`CloudLlmNodeExecutor.providerReportsFinishReason(...)`](../app/src/main/java/app/knotwork/android/domain/engine/executors/CloudLlmNodeExecutor.kt)
is exhaustive over `CloudProvider`, so a new constant will not compile
until you answer this. Answer it by **measuring, not by reading the
provider's docs** — this is the one step in the recipe where a plausible
guess causes a user-visible defect either way.

The question is what the client does when the connection dies in the
middle of a streamed answer. Some clients raise; the OpenAI-compatible
ones end the flow *normally*, emitting frames byte-identical to a healthy
stream except that `End.finishReason` is `null` instead of `"stop"`. For
those, a missing finish reason is the only evidence that the answer was
cut off, and returning `true` makes the executor reject the partial text
instead of passing half an answer down the graph.

Return `false` when you have no evidence, and say why in the KDoc:

- **A client that never emits a finish reason** (Koog's Ollama client) —
  returning `true` fails *every healthy run*, because absence carries no
  information.
- **A client you could not test** (Anthropic, whose parser rejected the
  crafted SSE fixture) — the existing entries record "excluded pending
  measurement" rather than a guess, and so should yours.

To measure it, point the client's `baseUrl` at a local socket stub from a
JVM test and cut the socket mid-stream: the Koog clients accept `baseUrl`
in their settings, so this exercises the real SSE branch on the same
engine the device uses. Compare the terminal frame of a cut stream
against a complete one. Whatever you conclude, write the evidence into
the KDoc entry — the table is only trustworthy while every row says what
it is based on.

### 3.7. Tests

- Unit test the new branch in `KoogClientFactory` with a fake key.
- Unit test the resolver branch — both the "known model id" and the
  "fallback to default model" paths.
- Cover the finish-reason decision from §3.6 in
  `CloudLlmNodeExecutorTest`: a stream that ends without a finish reason
  must fail the node when the provider is `true`, and must succeed when
  it is `false`.
- If the Settings UI gained a new field, add a Compose test that
  verifies the field round-trips through the ViewModel.

---

## 4. Add a new prompt variable

A prompt variable is a `$KEY` placeholder substituted by
`PromptTemplateEngine` right before a system prompt is sent to an LLM.
The five built-in variables (`$DATE`, `$TIME`, `$TOOLS`, `$MODEL`,
`$MEMORY_SUMMARY`) all follow the same pattern.

### 4.1. Implement `PromptVariableProvider`

Create a class under
`app/src/main/java/app/knotwork/android/data/prompt/` that implements
[`PromptVariableProvider`](../app/src/main/java/app/knotwork/android/domain/prompt/PromptVariableProvider.kt):

```kotlin
class WeatherVariableProvider @Inject constructor(
    private val weatherRepository: WeatherRepository,
) : PromptVariableProvider {

    override fun key(): String = "WEATHER"

    override suspend fun resolve(): String =
        weatherRepository.currentSummary().orEmpty()
}
```

Rules:

- The key must match `[A-Z_][A-Z0-9_]*`. Lowercase keys and `$50`-style
  sequences are not recognised by the renderer.
- `resolve()` is allowed to suspend and perform I/O. If it throws,
  `PromptTemplateEngine` catches the exception, logs a warning, and
  substitutes an empty string — a broken provider can never break the
  whole render.
- Two providers must not share the same key. Resolution order is
  unspecified in that case.

### 4.2. Register the provider via Hilt

Add a `@Binds @IntoSet` method to
[`di/PromptTemplateModule.kt`](../app/src/main/java/app/knotwork/android/di/PromptTemplateModule.kt):

```kotlin
@Binds
@IntoSet
abstract fun bindWeatherVariableProvider(
    impl: WeatherVariableProvider,
): PromptVariableProvider
```

`PromptTemplateEngine` consumes the resulting `Set<PromptVariableProvider>`
directly — no further wiring is needed.

### 4.3. Mirror the variable into the browser editor

Add the key to the `PROMPT_VARIABLES` array in
[`pipeline-editor.html`](../pipeline-editor.html). The current set is:

```js
// Current set (9 variables) — keep in sync with di/PromptTemplateModule.kt:
const PROMPT_VARIABLES = ['DATE', 'TIME', 'TOOLS', 'MODEL', 'MEMORY_SUMMARY', 'LANG', 'LOCATION', 'USER', 'DEVICE'];

// After registering a new provider (e.g. WEATHER), append its key:
const PROMPT_VARIABLES = ['DATE', 'TIME', 'TOOLS', 'MODEL', 'MEMORY_SUMMARY', 'LANG', 'LOCATION', 'USER', 'DEVICE', 'WEATHER'];
//                                                                                                                  ^^^^^^^ your new key
```

This drives the clickable chips above the `systemPrompt` textarea. If
you skip this step the runtime still resolves the variable (so prompts
work), but users will not see it in the autocomplete chips.

### 4.4. Document the variable

Add a row to the "Variables in system prompts" table in
[`docs/user-guide.md`](user-guide.md) so end users can discover the
new placeholder.

### 4.5. Tests

- Unit test the provider's `resolve()` with mocked dependencies.
  Include the failure path — verify that throwing inside `resolve()`
  results in an empty substitution after `PromptTemplateEngine` runs.
- Add a `PromptTemplateEngine` round-trip test that renders a template
  containing `$YOUR_KEY` and asserts on the substituted output.

---

## 5. Add a bundled preset

A **preset** is a curated starting point that ships inside the APK so the
user gets something useful before they have built anything themselves.
There are two kinds, and they follow the same three-step shape:

| Kind                | What it captures                          | Asset directory                  | Use case that loads it          |
|---------------------|-------------------------------------------|----------------------------------|---------------------------------|
| **Pipeline preset** | A whole graph (`PipelineGraph`)           | `assets/presets/pipelines/`      | `LoadPipelineFromPresetUseCase` |
| **Prompt preset**   | One node's `systemPrompt`                 | `assets/presets/prompts/`        | applied in `NodeConfigSheet`    |

Both are read-only once shipped; the user can also save their own
(`SavePipelineAsPresetUseCase` / `SavePromptAsPresetUseCase`), which land
in the `pipeline_presets` / `prompt_presets` Room tables. Adding a new
bundled preset is a single JSON file, one line in the catalogue validation
test, and — for pipeline presets — a mirror entry in the browser editor.

### 5.1. Add a bundled pipeline preset

A pipeline preset wraps the same pipeline-graph JSON the app exports, plus
three preset-only fields (`category`, `tags`, `description`). The canonical
contract is `domain/pipelineio/PipelinePresetJsonSerializer.kt` (schema
version 1); the shipped files under `assets/presets/pipelines/` are the
reference examples. The broadest of them, `showcase_full_agent.json`, is the
on-device agent: an `INTENT_ROUTER` triages every message into
**chat / factual / task**, and each intent runs a tailored branch —

- *chat* → a direct `LITE_RT` reply;
- *factual* → an `IF_CONDITION` complexity gate that either does a single
  Wikipedia lookup (`LITE_RT` query-builder → `TOOL` → `LITE_RT` grounded
  answer) or decomposes the question (`DECOMPOSITION` → `QUEUE_PROCESSOR`
  per-topic `TOOL` loop → `SUMMARY`);
- *task* → a plan-and-loop flow (`DECOMPOSITION` → `QUEUE_PROCESSOR` → a
  second `INTENT_ROUTER` routing each subtask over clarify / lookup / act /
  process — but each branch now runs as a composed **`PIPELINE` node**
  calling one of four bundled sub-pipelines (`subtask_clarify`,
  `subtask_lookup`, `subtask_act`, `subtask_process`), looping back to the
  `QUEUE_PROCESSOR` → `SUMMARY`).

It exercises intent routing, IF-condition branching, decomposition,
queue-processor loops (via QUEUE_PROCESSOR back-edges), tool calls, HITL
clarification (inside `subtask_clarify`), pipeline composition and summary
synthesis, with a broad mix of context flags and `$VARIABLE` placeholders. It
is also the pipeline `InitializeAppUseCase` materialises as the first-launch
seed, so treat it as the canonical "kitchen-sink" template.

> **Composed presets.** A preset whose `PIPELINE` nodes target *other*
> bundled presets (as the showcase targets the four `subtask_*` sub-pipelines)
> needs those sub-pipelines persisted under the **stable id** the node's flat
> `targetPipelineId` references — the runtime resolves a `PIPELINE` target by
> *pipeline id*, but materialising a preset mints a fresh random pipeline id.
> `LoadPipelineFromPresetUseCase` bridges the gap: when it materialises a
> preset, it walks the `PIPELINE` targets and, for any that are not already a
> saved pipeline but *are* a bundled preset id, materialises that preset under
> its own stable id and persists it (create-if-absent, recursively). So a
> sub-pipeline preset simply ships as another JSON file whose **filename stem
> equals the id the parent references**; first-launch seeding and a later
> **+ From preset** spawn both leave a runnable composition.
>
> Such a sub-pipeline is a building block, not something the user picks from
> the gallery, so it declares `"internal": true` (see the schema below). The
> flag hides it from the picker and the library — `getBundledPresets()`
> filters it out — while `getPresetById()` still resolves it, which is
> exactly the lookup the seeding above performs. The four `subtask_*`
> presets are the shipped example.

**Step 1 — drop a JSON file under `assets/presets/pipelines/`.** The
filename stem becomes the preset `id`, so it must be unique across the
directory (e.g. `local_only_qa.json` → id `local_only_qa`). Schema:

```json
{
  "schemaVersion": 1,
  "id": "local_only_qa",
  "name": "Local-only Q&A",
  "description": "Single-turn answers handled entirely by the on-device model.",
  "category": "local",
  "tags": ["offline", "qa", "starter"],
  "updatedAt": 1748304000000,
  "nodes": [
    {
      "id": "input",
      "type": "INPUT",
      "position": { "x": 80.0, "y": 200.0 },
      "label": "Input",
      "config": { "systemPrompt": null, "cloudProvider": null, "modelPath": null,
                  "toolName": null, "targetPipelineId": null, "skillId": null,
                  "clarificationTimeoutMs": null, "conditionPrompt": null,
                  "conditionKeywords": null, "conditionComplexity": null,
                  "conditionHasImage": null },
      "contextConfig": { "chatHistory": false, "originalTask": false, "nodeInput": true,
                         "longTermMemory": false, "toolResults": false },
      "nodeConfig": { "v": 1, "type": "INPUT", "title": "Input" }
    }
    // … LITE_RT, OUTPUT, …
  ],
  "connections": [
    { "id": "c1", "fromNodeId": "input", "toNodeId": "lite_rt", "label": null }
  ]
}
```

Rules:

- `category` must be one of the `domain/models/PresetCategory` keys:
  `local`, `cloud`, `hybrid`, `tool`, `research`, `other`.
- The embedded graph must pass `PipelineGraph.validate()` with **zero
  errors** — exactly one `INPUT` and one `OUTPUT`, no cycles, no
  disconnected or dead-end nodes, and no empty `contextConfig` on a node
  that consumes context. The catalogue test fails the build otherwise.
- Any `$VARIABLE` token in a node's `systemPrompt` must be a registered
  provider key (`$DATE`, `$TIME`, `$TOOLS`, `$MODEL`, `$MEMORY_SUMMARY`,
  `$LANG`, `$LOCATION`, `$USER`, `$DEVICE`) — same whitelist as §5.2.
- `name` must not exceed 60 characters (the cross-feature
  `MAX_NAME_LENGTH`).
- `position` is in **dp**, the unit the editor lays node cards out in — a card
  is 168 dp wide and up to 96 dp tall, so the example's 80 is 80 dp from the
  canvas origin on every screen. Leave at least one 24 dp grid step between
  cards (a left-to-right row of 240 dp steps is a safe default).
  `ShippedPipelineLayoutTest` fails the build when two cards in a bundled
  preset or a cookbook recipe overlap.
- `internal` is optional and defaults to `false`. Set it to `true` **only**
  for a sub-pipeline another preset composes (see the composed-presets note
  above): the preset then disappears from the picker and the library while
  staying resolvable by id. It is honoured only for bundled files — an
  imported document that sets it is loaded as a normal user preset, so a
  hand-edited import cannot hide itself from its own owner.

A pipeline may also declare an optional top-level `samplePrompts` array —
the starter ("quick action") cards shown on a new chat's empty state when
this pipeline is the active one. Each entry is `{ "title": "…",
"toolsHint": "…" }`; `toolsHint` is optional and, when omitted, the card
renders without its `uses · …` subtitle. Keep the hints honest — only name
tools the pipeline actually wires:

```json
"samplePrompts": [
  { "title": "Look up the latest on-device LLM benchmarks", "toolsHint": "search_tool" },
  { "title": "Explain how on-device inference keeps my data private" }
]
```

The field is additive and display-only: it is excluded from
`PipelineGraph.contentHash()` (editing the suggestions never invalidates a
resumable run), documents without it import fine (they decode to no
suggestions), and a pipeline that declares none falls back to a generic,
tool-agnostic card set on the empty state. It is optional for a pipeline
document in general, but **every user-facing bundled preset must declare
at least one** — a preset the user spawns from the gallery should open with
its own quick actions rather than the generic set. `PipelinePresetCatalogValidationTest`
enforces this for non-`internal` presets.

A pipeline may also declare an optional top-level `memoryRetrievalQuery`
string — the long-term-memory search key used by its **background** runs
(automation trigger, schedule, Quick Settings tile):

```json
"memoryRetrievalQuery": "evening journal entries, mood and highlights around $DATE"
```

An interactive run searches memory with the user's own message, which is
also the best possible search key. A background run has no such message:
its prompt was written once by the pipeline author ("write the evening
journal entry") and describes no particular firing, so searching for it
returns whatever happens to sit near that generic sentence. The declared
query replaces it. Write it as the *topic to recall*, not as an
instruction; it is rendered through the prompt-variable engine, so `$DATE`
and the other tokens from §5.2 work.

Resolution order for a background run: declared query → the input of the
first node that opts into long-term memory → the run's prompt. Interactive
runs ignore the field entirely. Retrieval still happens **at most once per
run**, so declaring a query costs nothing extra, and the console's
`MEMORY` line names the rule that was applied (`[pipeline-declared]`,
`[node input]`, `[user prompt]`). The field is additive: documents without
it import fine, and it is excluded from `PipelineGraph.contentHash()`
because a resumed run replays its persisted memory snapshot instead of
searching again. Omit it for interactive-only pipelines and for pipelines
whose nodes all have `longTermMemory: false`.

Each node may also carry an optional `nodeConfig` object alongside `config`
and `contextConfig` — the rich `NodeConfig` payload (the
`NodeConfigCodec` envelope: `{ "v": 1, "type", "title", ...type-specific
fields... }`) that the in-app `NodeConfigSheet` and the browser editor edit
field-for-field. It is additive: `PipelineJsonSerializer` round-trips it as
an opaque blob into `NodeModel.configJson`, the runtime engine ignores it
(it reads only the flat `config` fields), and documents without it import
fine — the app derives a default rich config from the flat fields on first
edit. The browser editor emits it automatically on export.

**Bundled presets must carry it on every node** (enforced by
`PipelinePresetCatalogValidationTest`), because the legacy derivation is
lossy for editor-only fields. The sharpest case is `INTENT_ROUTER`: the
derived config has **no classes at all**, and the form requires 2..6, so a
router without a `nodeConfig` opens with an empty class list and a
validation error blocking Save. Declare the classes to match the node's
outgoing edge labels, in the same order, and set `fallbackClass` to the
**first** of them — the runtime routes on those labels and falls through to
the first outgoing edge when the model emits nothing recognised, so any
other declaration would be a lie the editor shows the user:

```json
"nodeConfig": {
  "v": 1, "type": "INTENT_ROUTER", "title": "Router",
  "classes": [
    { "name": "Simple",  "description": "Single-step questions the local model can answer.", "examples": [] },
    { "name": "Complex", "description": "Multi-step reasoning that benefits from a larger model.", "examples": [] }
  ],
  "classifierPrompt": "…",
  "fallbackClass": "Simple"
}
```

**Step 2 — register the filename in `PipelinePresetCatalogValidationTest`.**
`expectedFileNames` in
`app/src/test/java/app/knotwork/android/domain/pipelineio/PipelinePresetCatalogValidationTest.kt`
is a hard whitelist of the shipped catalogue. Add the new filename in the
same PR; otherwise the test refuses the new file (or misses a deletion).
A preset marked `"internal": true` also belongs in the same file's
`expectedInternalIds` set, which pins *which* presets are hidden.

**Step 3 — regenerate the browser editor.** Run
`./gradlew :app:generateBrowserEditorConstants` and commit
`pipeline-editor.html`. The editor's `BUILTIN_PIPELINE_PRESETS` block (the
**📚 Presets → Bundled** tab, plus `PIPELINE` target resolution and the
bundle-export closure, which is why `internal` presets are included) is
generated from `assets/presets/pipelines/`, in the order of
`BundledPresetCatalog.DISPLAY_ORDER` with internal presets after it. The same
applies to prompt templates under `assets/presets/prompts/` and the
`BUILTIN_PROMPT_TEMPLATES` block. `verifyBrowserEditorConstants` fails `check`
when either block has drifted, so an edited preset cannot reach the app while
the editor keeps the old one — which is exactly how the editor once went on
shipping prompts six presets had been changed to remove.

Two constraints the generator enforces: a preset file must be canonical
2-space JSON (as `JSON.stringify(doc, null, 2)` writes it), and a public
preset must be listed in `DISPLAY_ORDER`. Leave out the per-node sampling,
token and timeout fields (`temperature`, `topP`, `maxNewTokens`,
`stopTokens`; on `CLOUD` also `model`, `maxTokens`, `timeoutMs`): no run
reads them — see the [cookbook](cookbook.md) — and a preset is the example
other authors copy.

**Tests.** No per-file test is needed:
`PipelinePresetCatalogValidationTest` runs once over the whole directory
(filename set, parse success, `validate()` cleanliness, variable
whitelist), and `PipelinePresetIntegrationTest` proves any bundled preset
materialises into a runnable pipeline (`LoadPipelineFromPresetUseCase` →
`validate()` → `GraphExecutionEngine` → `Completed`).

### 5.2. Add a bundled prompt preset

A **prompt preset** is a reusable system-prompt template that ships
inside the APK and surfaces in the Prompt Library so the user can apply
it to a compatible LLM-driven node with one tap. Bundled presets are
read-only; the user can also save their own (`SavePromptAsPresetUseCase`),
which land in the `prompt_presets` Room table instead.

Adding a new bundled preset is a single-file change plus one line in
the catalogue validation test.

**Step 1 — drop a JSON file under `assets/presets/prompts/`.**

Filename convention: `<nodetype_in_lowercase>_<short_slug>.json`
(e.g. `litert_concise_assistant.json`, `output_json_structured.json`).
The filename stem becomes the preset `id`, so it must be unique across
the directory.

Schema (`PromptPresetJsonSerializer`, version 1):

```json
{
  "schemaVersion": 1,
  "id": "litert_concise_assistant",
  "name": "Concise assistant",
  "description": "Single-paragraph answers, no preamble.",
  "nodeType": "LITE_RT",
  "systemPrompt": "You are a concise on-device assistant running on $MODEL. Today is $DATE. ...",
  "tags": ["concise", "starter"]
}
```

Rules:

- `nodeType` must be one of the LLM-driven types listed in
  `PromptPresetConstants.LLM_DRIVEN_NODE_TYPES` (LITE_RT, CLOUD, OUTPUT,
  SUMMARY, INTENT_ROUTER, DECOMPOSITION, EVALUATION, CLARIFICATION).
  Non-LLM types (`INPUT`, `TOOL`, `IF_CONDITION`, `QUEUE_PROCESSOR`)
  never run a system prompt and are rejected by the serializer.
- `systemPrompt` may only reference `$VARIABLE` tokens registered in
  `di/PromptTemplateModule.kt` (`$DATE`, `$TIME`, `$TOOLS`, `$MODEL`,
  `$MEMORY_SUMMARY`, `$LANG`, `$LOCATION`, `$USER`, `$DEVICE`).
  Misspellings remain in the rendered output as literal `$KEY` and the
  catalogue test fails the build.
- `systemPrompt` length must not exceed
  `PromptPresetConstants.MAX_SYSTEM_PROMPT_LENGTH` (8000 chars).
- `name` must not exceed `PromptPresetConstants.MAX_NAME_LENGTH` (60
  chars).
- `tags` are lower-case, kebab-case labels.

**Step 2 — register the filename in `PromptPresetCatalogValidationTest`.**

`expectedFileNames` in
`app/src/test/java/app/knotwork/android/domain/promptio/PromptPresetCatalogValidationTest.kt`
is a hard whitelist of the shipped catalogue. Add the new filename in
the same commit; otherwise the catalogue test will refuse the new file
(or, if you removed one, miss the deletion). The test also asserts that
every LLM-driven NodeType has at least one bundled preset — if you
introduce a new LLM-driven type elsewhere, ship at least one bundled
preset for it.

**Tests.**

The catalogue test (`PromptPresetCatalogValidationTest`) already covers:
- the filename set matches `expectedFileNames`;
- every file parses to `Success` with `isBundled = true` and `id` equal
  to the filename stem;
- every `nodeType` is LLM-driven;
- every `systemPrompt` fits within `MAX_SYSTEM_PROMPT_LENGTH`;
- every `name` fits within `MAX_NAME_LENGTH`;
- every `$VARIABLE` token is in the registered whitelist;
- every LLM-driven NodeType has at least one bundled preset.

You don't need to add a per-file test — the catalogue test runs once
over the whole directory, and `PromptPresetIntegrationTest` proves every
bundled prompt renders cleanly through `PromptTemplateEngine` with all
registered variables substituted.

### 5.3. Add a bundled skill

A **skill** is a reusable bundle of *instruction + tool restriction +
context configuration* — described once and reused from a `SKILL` node
instead of copying a system prompt between pipelines. Bundled skills ship
inside the APK as JSON, are seeded into the `skills` table idempotently on
**every** launch (`SeedBundledSkillsUseCase` → `SkillRepository.seedBundledSkills`,
upsert-by-stable-id so users upgrading from a build without a given skill
still receive it), and are read-only in the **Skill library** (the user can
duplicate one into an editable copy).

**Step 1 — drop a JSON file under `assets/presets/skills/`.** The filename
stem becomes the skill `id`, so it must be unique across the directory
(e.g. `report_writer.json` → id `report_writer`). The contract is
`domain/skillio/SkillJsonSerializer.kt` (schema version 1); the three shipped
files (`summarizer.json`, `translator.json`, `report_writer.json`) are the
reference examples.

```json
{
  "schemaVersion": 1,
  "id": "report_writer",
  "name": "Report Writer",
  "description": "Writes a short Markdown report to a workspace file.",
  "instruction": "You are a report writer. Today is $DATE. Write the report, then save it with the write_file tool ...",
  "toolAllowlist": ["write_file"],
  "contextConfig": {
    "chatHistory": false,
    "originalTask": true,
    "nodeInput": true,
    "longTermMemory": false,
    "toolResults": false
  },
  "createdAt": 1748304000000,
  "updatedAt": 1748304000000
}
```

Rules:

- **`toolAllowlist` is tri-state** — the same null / empty / subset contract
  the domain `Skill.toolAllowlist` carries, and it is a real boundary, not a
  hint (the `SKILL` executor refuses an out-of-allowlist call):
  - **field absent or JSON `null`** → unrestricted (every tool, including
    tools added later);
  - **`[]`** → an explicit *empty* allowlist (no tools — instruction-only);
  - **`["a", "b"]`** → only those tools. List a tool by the same id the agent
    uses (`write_file`, `search_tool`, …).
- `instruction` may reference any registered `$VARIABLE` placeholder; `$TOOLS`
  expands only to the skill's allowlist when the skill runs (not the global
  tool set).
- `name` must not exceed `MAX_NAME_LENGTH` (60 chars).
- `contextConfig` is the skill's **default** context; a `SKILL` node starts
  *inherited* from it and can override per-node.

**Step 2 — use it from a `SKILL` node.** No registration step is needed — the
seed picks the file up by directory scan. In a pipeline, add a **Skill** node,
choose the skill in its picker, and (optionally) the on-device / cloud engine.
The instruction, the visible-tool allowlist, and the default context all come
from the skill rather than the node (see the *Skill library* and *SKILL node*
sections of [`docs/user-guide.md`](user-guide.md)).

**Tests.** `SkillJsonSerializerTest` covers the round-trip and the null/empty
allowlist distinction; `SeedBundledSkillsUseCaseTest` and `SkillRepositoryImplTest`
cover idempotent seeding. A new bundled skill needs no per-file test, but if it
allows tools, add a `SkillNodeExecutorTest` case asserting an allowed call
dispatches and an out-of-allowlist call is refused.

---

### 5.4. The prompt-pack file format

A prompt preset is the one preset kind a user can **hand to somebody as a
file**: markdown with a YAML frontmatter block, imported and exported from
the Prompt Library. This is a second serialization of the *same*
`PromptPreset` — JSON stays the internal asset format that the build
controls, markdown is the portable one that nobody controls. There is no
third entity and no third library.

```markdown
---
schemaVersion: 1
id: concise-assistant
name: Concise assistant
description: Single-paragraph answers, no preamble.
nodeType: LITE_RT
tags: [concise, starter]
---
You are a helpful assistant. Answer in one paragraph. Today is $DATE.
```

Required: `name`, `nodeType`, and a non-empty body. Optional: `id` (falls
back to the file-name stem, so re-importing the same file updates rather
than duplicates), `description`, `tags`, and `schemaVersion` — absent means
current, because a file meant to be written by hand should not need a
version stamp to be readable.

**The capability ceiling.** A prompt pack carries text and nothing else. It
cannot add nodes, add tools, or widen a tool allowlist. The keys that ask
for one (`allowed-tools`, `allowedTools`, `tools`, `mcp`, `permissions`,
`nodes`, `steps`, `pipeline`, `scripts`) are recognised **precisely so they
can be named** in the import result: a refusal the user never sees is
indistinguishable from a refusal that did not happen. Two tests hold this
and both fail when the guard is removed —
`PromptPackMarkdownSerializerTest` asserts that such a file imports as text
with the request reported, and that nothing in the produced model has a
field a capability could travel in. Values read out of a file are sanitised
before display (control characters and `U+2028`/`U+2029` stripped, length
and count clamped) so a crafted document cannot forge dialog copy.

Applicability is `PromptPresetConstants.LLM_DRIVEN_NODE_TYPES`. Three
exclusions are load-bearing rather than incidental: `SKILL` is refused
because `SkillNodeExecutor` **overwrites** `systemPrompt` with the rendered
skill instruction, so a preset applied there would vanish silently; a blank
body is refused because an empty `systemPrompt` on an `OUTPUT` node *means*
pass-through; and a body over
`PromptPresetConstants.MAX_SYSTEM_PROMPT_LENGTH` is refused rather than
truncated, because a prompt cut off mid-instruction still looks like a
prompt.

**Relationship to `SKILL.md`.** The shape is borrowed from the Agent Skills
specification — `---`-delimited frontmatter followed by a markdown body that
*is* the instruction. The semantics are not: a skill there is a directory
that may carry `scripts/`, its `name` must be a kebab-case identifier
matching that directory, and it may declare `allowed-tools`. Conformance is
therefore **not** claimed. That spec's three purely descriptive optional
keys (`license`, `compatibility`, `metadata`) are accepted and ignored so a
file written for another runtime imports without a warning about keys its
author had every reason to include.

**The frontmatter grammar** is a documented subset of YAML implemented in
`domain/promptpack/PromptPackFrontmatterParser` — there is no YAML
dependency in this project, and adding one to read six scalar keys would buy
a LICENSE audit and a supply-chain surface for nothing. Accepted: scalars
(with one optional layer of quotes; `\\` and `\"` unescape inside double
quotes), inline lists (`[a, b]`), block lists (`- item`), nested maps
(child key names recorded, values not interpreted), and whole-line comments.
Not accepted, each producing a typed error rather than a guess: anchors and
aliases, multi-document streams, block scalars (`|`, `>`), flow maps, tags,
and multi-line quoted values. A `#` inside a value stays part of the value,
so a prompt that mentions hashtags survives.

Best-effort parsing is deliberately absent here. The file ends up in the
system prompt of an agent holding tools; a guess about what the author meant
is a guess about the contents of an instruction.

## 6. Add a new `EmbeddingProvider`

An embedding provider turns text into a dense vector for long-term-memory
similarity search. The memory pipeline never names a backend directly —
it asks `EmbeddingProviderResolver` for the active one on every call — so
adding a backend is purely additive: implement the interface, bind it into
the Hilt map, and it shows up in the Settings picker automatically. The
full memory lifecycle these vectors flow through is documented in
[`architecture.md`](architecture.md) §2.2.

> **Dimension warning.** Vectors from different providers are **not**
> comparable — switching the active provider strands every chunk embedded
> under the old one in a foreign space (cosine collapses to ~0) until they
> are re-embedded. The app handles this with the `needsReembedding` flag
> and the background re-embed worker (see §2.1 in `architecture.md`); your
> provider just needs to report an honest `dimension`.

### 6.1. Implement the `EmbeddingProvider` interface

Add a class in
[`data/services/embedding/`](../app/src/main/java/app/knotwork/android/data/services/embedding/)
implementing
[`EmbeddingProvider`](../app/src/main/java/app/knotwork/android/domain/services/EmbeddingProvider.kt):

- `id` — a stable, lowercase wire key (persisted in settings; **never**
  change it once shipped).
- `displayName` — the label shown in the Settings → Memory picker.
- `dimension` — the exact vector length the backend produces.
- `isAvailable()` — return `false` when required configuration is missing
  (no API key, no server URL). The resolver then falls back to the
  always-present on-device default instead of returning a provider that
  would throw or, worse, emit mis-dimensioned vectors.
- `embed(text)` / `embed(texts)` — the single and batch calls. Override
  the batch form to use the backend's native batch endpoint where one
  exists; do all heavy work off the main thread.

Cloud-shaped providers can reuse the `KoogEmbedderFactory` seam (see
`CloudEmbeddingProvider` / `OllamaEmbeddingProvider`), which already maps
`List<Double> → FloatArray` and falls back to on-device USE when
unconfigured.

### 6.2. Add the id constant

Add the new id as a `const val` in the `EmbeddingProvider` companion
object (alongside `ID_USE` / `ID_OPENAI_3_SMALL` / `ID_OLLAMA`). This is
the single source of truth shared by the DI binding, the resolver
fallback, and `SettingsDefaults`.

### 6.3. Bind it into the Hilt map

Append one binding to
[`EmbeddingModule`](../app/src/main/java/app/knotwork/android/di/EmbeddingModule.kt):

```kotlin
@Binds
@IntoMap
@StringKey(EmbeddingProvider.ID_MY_PROVIDER)
abstract fun bindMyEmbeddingProvider(provider: MyEmbeddingProvider): EmbeddingProvider
```

That is the only wiring step. `EmbeddingProviderResolver` reads the whole
`Map<String, EmbeddingProvider>` and the persisted
`activeEmbeddingProviderId` at call time — **no resolver edit is needed.**

### 6.4. The Settings dropdown is automatic

`SettingsViewModel` builds the **Settings → Memory → Embedding model**
dropdown from `embeddingProviders.values` (the same Hilt map), so the new
provider appears as soon as it is bound — sorted by `displayName`. The
ViewModel also rejects selecting an id absent from the map, so the binding
above is what makes the option both visible and selectable. If the new
backend needs credentials (API key / base URL), add that field under
**External Providers** the same way a cloud provider does (§3.4–§3.5).

### 6.5. Tests

- A unit test for the provider's own logic (config gating in
  `isAvailable()`, batch/single parity, error mapping to
  `EmbeddingException`). Mock the transport / Koog client; do not hit a
  real network.
- A `EmbeddingProviderResolver` test asserting your id resolves to your
  provider when active and available, and falls back to `ID_USE` when it
  is not (the resolver's two fallback branches).

---

## 7. Use input atoms and chip atoms

Every text input and chip on screen lives in the Knotwork catalog under
`catalog/src/main/java/app/knotwork/design/components/controls/` and
`…/chips/`. The full specification is `inputs-and-chips.md` (sizing,
spacing, state tables, motion); this section is the quick "which atom
do I reach for" lookup.

### 5.1 Pick an input atom

| You want to render…                                          | Atom                                                                                  |
|--------------------------------------------------------------|---------------------------------------------------------------------------------------|
| Single-line sans text (titles, names, IDs)                   | `KnotworkField` + `KnotworkTextField(size = Sm)`                                       |
| Single-line monospace (condition / token / URL / JSON)       | `KnotworkField` + `KnotworkTextField(monospace = true)`                                |
| Multi-line prompt / classes / question template              | `KnotworkField` + `KnotworkTextArea(monospace = true, insertChips = […])`              |
| Numeric value                                                | `KnotworkTextField(keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))` |
| Slider 0..1 / 0..2 (Temperature, Top-p)                      | `KnotworkCompactSlider`                                                                |
| List of strings (Stop tokens, Quick replies, tag inputs)     | `KnotworkChipsInput`                                                                   |
| Choose from ≤ 8 mutually exclusive values                    | segmented `KnotworkFilterChip(size = Sm)` row                                          |
| Choose from > 8 values                                       | catalog dropdown (out of scope for this guide)                                         |
| Search bar                                                   | `KnotworkTextField(size = Md, search = true)`                                          |
| Password / API key / token                                   | `KnotworkPasswordField`                                                                |
| Chat input                                                   | catalog `ChatComposer` (`components/chat/ChatComposer.kt`)                             |
| Inline rename (toolbar title)                                | `KnotworkTextField(size = Sm)` without external `KnotworkField` wrapper                |

Every atom in the table above already lives behind `KnotworkField` for
the caps-label + helper row. If you skip the wrapper, **set
`contentDescription` on the inner `KnotworkTextField`** so TalkBack still
announces the field.

### 5.2 Pick a chip atom

| You want to render…                                                   | Atom                                                            |
|-----------------------------------------------------------------------|------------------------------------------------------------------|
| Single-choice segmented row (Format, Style, Risk gate, yes/no)        | `KnotworkFilterChip(size = Sm)`                                  |
| Filter bar with counts (All · 24 / Recent · 5 / Mine)                 | `KnotworkFilterChip(size = Sm, trailingCount = …)`               |
| Quick-reply under a `CLARIFICATION` card or empty-state suggestion    | `KnotworkSuggestionChip(size = Md)`                              |
| Removable list value (Stop tokens, Quick replies)                     | `KnotworkInputChip` inside `KnotworkChipsInput`                  |
| `$DATE` / `$TIME` / `$GOAL` insert-token chip                         | `KnotworkVariableChip` (or the `insertChips` strip on `KnotworkTextArea`) |
| Section header in the chat stream (Today / date)                      | `KnotworkDateChip`                                               |
| Risk tier badge in HITL prompt or Tools row                           | `RiskPill`                                                       |
| Run-state badge in pipeline library / console                         | `StatusPill`                                                     |

The chip family uses the 8 dp `sm` shape by default (the spec
deliberately diverges from Material 3's pill-shaped filter chip).
`RiskPill` / `StatusPill` / `KnotworkDateChip` are the three pill-shaped
exceptions; everything else stays rectangular.

### 5.3 Adding a new variable to the textarea highlight pass

`KnotworkTextArea` highlights any token matching `\$[A-Z_][A-Z0-9_]*`
out of the box, so a new prompt variable added through the
`PromptVariableProvider` recipe in §4 is highlighted automatically.
No extra wiring on the atom side.

### 5.4 Adding a new atom

Catalog atoms live next to their existing siblings in
`components/controls/` (text inputs) or `components/chips/` (chips and
pills). The conventions a new atom must follow:

- Read sizes / padding / borders from `KnotworkFieldDefaults` /
  `KnotworkChipDefaults`; never inline a literal `dp` at the call site.
- Read colours from `KnotworkTheme.extended` and
  `MaterialTheme.colorScheme`; never inline a hex value.
- Touch target ≥ 48 dp via `Modifier.minimumInteractiveComponentSize()`
  or `Modifier.size(48.dp)` even when the visual is smaller.
- Pair colour with another signal (icon, label, dot) — never use colour
  alone, so the state stays legible for colour-blind users.
- Ship a snapshot test (`Roborazzi`) that exercises the visual states
  most likely to regress (default / focused / disabled / error for
  inputs; off / on / disabled for chips).

---

## 8. Synchronization table

The same change can require updates in multiple places. The table
below lists each extension point and every file that must move
together. **`pipeline-editor.html` is the most frequent drift point —
double-check it for every recipe in this guide.**

| You changed …                | Files you must also update                                                                                                                                                                                                                                          |
|------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| A new `NodeType`             | `domain/models/NodeType.kt` · a new `NodeExecutor` implementation · `domain/engine/executors/NodeExecutorFactory.kt` · `domain/models/NodeContextConfig.kt` (`defaultForType`) · `domain/models/PipelineGraph.kt` (`validate`, if special invariants) · `buildtools/BrowserEditorConstantsGenerator.kt` (`NODE_TYPE_META`) + run `./gradlew :app:generateBrowserEditorConstants` · `buildtools/CookbookDocsGenerator.kt` (`NODE_DOC_META` + a `FIELD_REACH` entry per config field) + run `./gradlew :app:generateCookbookDocs` · **`pipeline-editor.html`** (`defaultContextConfig`, `NODE_TYPE_TOOLTIPS`, optional `DEFAULT_SYSTEM_PROMPTS`; for typed config also `defaultRichConfig` / `richToFlat` / `encodeRichEnvelope` / `decodeRichEnvelope` / `deriveRichFromFlat` / `renderFormFields` / `validateRichConfig`) · executor unit test · `GraphExecutionEngineTest` |
| A node type that **references another entity by id** (`PIPELINE` / `SKILL`) | the flat `NodeModel` field (`targetPipelineId` / `skillId`) · `domain/pipelineio/PipelineJsonSerializer.kt` (emit + read the id in the flat `config` block) · `domain/models/PipelineGraph.kt` (`validate` → `MissingTargetPipeline` / `MissingSkill`) · `domain/services/PipelineCompositionValidator.kt` (transitive cycle / depth) · **`pipeline-editor.html`** (flat `config` key in `exportToJson`/`importFromJson`, reference form, self-ref + unresolved-id validation, node badge) · `PipelineJsonSerializerTest` round-trip |
| A new field on a `NodeConfig` (catalog) | `catalog/.../pipelineeditor/NodeConfig.kt` · `NodeConfigForms.kt` + `NodeConfigValidation.kt` if it is edited · `presentation/ui/pipeline/editor/config/NodeConfigCodec.kt` (encode/decode, and `apply` if it must reach the runtime) · `buildtools/CookbookDocsGenerator.kt` (`FIELD_REACH` — generation fails without it) + run `./gradlew :app:generateCookbookDocs` · `CookbookRuntimeReachTest` checks the published verdict against the codec · **`pipeline-editor.html`** envelope encode/decode so the field round-trips — and, if its verdict is `RoundTripOnly`, **no** control in `renderFormFields`: `verifyBrowserEditorConstants` fails on a form control for a field no run reads |
| A new `Tool`                 | a new `LocalToolExecutor` implementation · `di/LocalToolsModule.kt` (`@Binds @IntoMap @StringKey`) · declare `ToolRisk` correctly · executor unit test · optional Compose test if new UI                                                                            |
| A new **workspace tool**     | a new `LocalToolExecutor` that goes through `AgentWorkspace` (never raw `File`) · `di/LocalToolsModule.kt` (`@Binds @IntoMap @StringKey`) · risk tier in `ToolRepositoryImpl` built-in list · `docs/user-guide.md` (built-in-tools table) · executor unit test against a `@TempDir`-backed `AgentWorkspace` (happy path + `../` traversal + quota/not-found) |
| A new callee-side AppFunction | a new `@AppFunction`-annotated wrapper under `data/tools/local/appfunctions/` (first param `AppFunctionContext`) · `App.appFunctionConfiguration` (`addEnclosingClassFactory(...)`) · wrapper unit test with a mocked `AppFunctionContext` · scenario in `AppFunctionsEndToEndTest` |
| A new cloud provider         | `domain/models/CloudProvider.kt` · `data/engine/KoogClientFactory.kt` · `data/engine/KoogCloudLlmModelResolver.kt` · `data/local/ApiKeyManager.kt` · `presentation/ui/settings/SettingsScreen.kt` · **`domain/engine/executors/CloudLlmNodeExecutor.kt`** (`providerReportsFinishReason` — exhaustive `when`, decide it by measurement per §3.6) · `docs/user-guide.md` (the truncated-answer table under Settings → Models) · factory / resolver / executor unit tests |
| A new prompt variable        | a new `PromptVariableProvider` implementation · `di/PromptTemplateModule.kt` (`@Binds @IntoSet`) · **`pipeline-editor.html`** (`PROMPT_VARIABLES`) · `docs/user-guide.md` (variables table) · provider unit test · `PromptTemplateEngine` round-trip test           |
| A new bundled pipeline preset | a JSON file under `assets/presets/pipelines/` · `PipelinePresetCatalogValidationTest.expectedFileNames` · `BundledPresetCatalog.DISPLAY_ORDER` (unless `internal`) · run `./gradlew :app:generateBrowserEditorConstants` (`BUILTIN_PIPELINE_PRESETS` is generated) · catalogue + `PipelinePresetIntegrationTest` already cover the directory |
| A new bundled prompt preset  | a JSON file under `assets/presets/prompts/` · `PromptPresetCatalogValidationTest.expectedFileNames` · run `./gradlew :app:generateBrowserEditorConstants` (`BUILTIN_PROMPT_TEMPLATES` is generated) · catalogue + `PromptPresetIntegrationTest` already cover the directory |
| A new **composed** bundled pipeline preset (its `PIPELINE` nodes target other bundled presets) | the parent JSON + one JSON per sub-pipeline under `assets/presets/pipelines/` (each sub-pipeline's **filename stem = the `targetPipelineId` the parent references**) · `PipelinePresetCatalogValidationTest.expectedFileNames` (every file) · run `./gradlew :app:generateBrowserEditorConstants` (the parent *and* every sub-pipeline are generated into `BUILTIN_PIPELINE_PRESETS`) · `LoadPipelineFromPresetUseCase` seeds the sub-pipelines under stable ids on demand (no edit needed unless the resolution policy changes) |
| The **pipeline bundle** envelope (`{ bundleVersion, exportedAt, pipelines: [...] }`) | `domain/pipelineio/PipelineBundleJsonSerializer.kt` (envelope + `looksLikeBundle` + limits) · **`pipeline-editor.html`** (`CURRENT_BUNDLE_VERSION`, `MAX_BUNDLE_PIPELINES`, `collectBundleClosure` / `parseBundleDoc`, the 📦 Export/Import bundle buttons — all hand-maintained, NOT auto-generated) · `PipelineBundleJsonSerializerTest`. The per-pipeline element format is owned by `PipelineJsonSerializer` (delegation), so a single-pipeline schema change flows through automatically |
| A new bundled skill          | a JSON file under `assets/presets/skills/` (filename stem = id) · no registration (seed is a directory scan, idempotent upsert) · `SkillJsonSerializerTest` round-trip already covers the format; add a `SkillNodeExecutorTest` allowlist case if the skill allows tools |
| A new `EmbeddingProvider`    | a new `EmbeddingProvider` implementation under `data/services/embedding/` · `EmbeddingProvider.kt` (`ID_*` constant) · `di/EmbeddingModule.kt` (`@Binds @IntoMap @StringKey`) · provider unit test · `EmbeddingProviderResolver` resolve/fallback test (Settings dropdown is automatic) |

When in doubt, search the repository for the exact identifier you
changed (`grep -R 'MY_NEW_TYPE'`) — anything that already mentions one
of the existing constants is a candidate for the same edit.

---

## 9. Quality gate

Before pushing any change from the recipes above, run the full quality
gate locally:

```bash
./gradlew check :buildSrc:test
```

The aggregated `check` task runs, among others:

- `detekt` and the type-resolution detekt tasks — static analysis.
- `ktlintCheck` — Kotlin formatting.
- `lintFullDebug` + `lintFossDebug` — Android Lint.
- `testFullDebugUnitTest` + `testFossDebugUnitTest` — JVM unit tests, including
  the architecture and source guards.
- `:catalog:verifyRoborazziDebug` — design-system screenshots against their
  baselines.
- the generated-document gates this guide's recipes touch —
  `verifyBrowserEditorConstants`, `verifyCookbookDocs`, `verifyFileMap`,
  `verifyDocLinks` and the rest.
- `koverVerifyFullDebug` — line-coverage verification.

`:buildSrc:test` runs the tests of the generators behind those gates; `buildSrc`
is a separate build, so `check` cannot reach it. The full list of gates is in
[`static-analysis.md`](static-analysis.md). The same two tasks gate every pull
request in CI, so running it locally
just trades local feedback for slower CI feedback. The coverage
baseline, per-package thresholds, and the rationale behind every
exclusion are documented in
[`docs/coverage-baseline.md`](coverage-baseline.md); the policy itself
lives in [`docs/static-analysis.md`](static-analysis.md).

---

## Further reading

- [`docs/architecture.md`](architecture.md) — the layered model, the
  pipeline engine, and the integration surface this guide extends.
- [`docs/user-guide.md`](user-guide.md) — how the features you ship
  appear to end users.
- [`docs/cookbook.md`](cookbook.md) — the per-node reference generated
  from the sources you are editing, plus the recipes that use them.
- [`docs/coverage-baseline.md`](coverage-baseline.md) — current
  coverage numbers and what is excluded from measurement.
- [`docs/static-analysis.md`](static-analysis.md) — detekt / ktlint /
  Android Lint policy.
- [`SECURITY.md`](../SECURITY.md) — threat model and how to report a
  vulnerability before shipping a risky tool or provider.
