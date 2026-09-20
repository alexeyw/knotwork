# Security Policy

This document describes the security posture of **Knotwork**, an on-device AI
agent for Android: what data the app handles, how it is protected, what is sent
off-device when the user opts in to crash reporting, and how to report a
vulnerability you discover.

The project is currently a **pre-release (0.10.1)** and is published primarily
for review and experimentation. There are no stability guarantees for storage
formats, APIs, or persisted data across versions.

---

## Supported Versions

Only the latest release line is supported. As a solo pre-release project there
are no maintained back-release branches or long-term-support tags; fixes land on
the current `0.10.x` line and on the latest commit on `main`.

| Version            | Supported          |
|--------------------|--------------------|
| `0.10.x` (latest)  | :white_check_mark: |
| `< 0.10.1`         | :x:                |

---

## Threat Model

The agent is designed around the principle that **sensitive user data stays on
the device** unless the user has explicitly configured an outbound integration
(such as a cloud LLM provider). The following protections apply to local
storage and credentials:

### Local database (at-rest encryption)

- The Room database `agent_database.db` is encrypted with **SQLCipher**
  (`net.zetetic:sqlcipher-android`), wired through Room via
  `SupportOpenHelperFactory`.
- The encryption protects the contents of all tables that may hold material
  derived from user input or model output:
  - `chat_messages` — user messages and model replies.
  - `chat_sessions` — chat metadata and pipeline bindings.
  - `memory_chunks` — fragments of long-term agent memory extracted from
    prior conversations.
  - `trace_steps` — the persistent pipeline-run trace: per-node inputs and
    outputs, console events, and resolved long-term-memory snapshots recorded
    while a run executes (this is what console replay and checkpoint resume
    read back).
  - `pipeline_runs` — persistent run records, including the original user
    prompt of each run and per-node progress markers.
  - `pending_interactions` — parked human-in-the-loop requests for runs that
    wait in the background: each record stores the staged **tool name and the
    exact arguments** awaiting approval (or the clarification question), so
    they are protected at rest like the conversation that produced them.
- The SQLCipher passphrase is a **32-byte random value** persisted in a
  Keystore-backed encrypted store: each value is encrypted with AES-256-GCM
  under a dedicated, non-exportable key held in the Android Keystore, and
  every ciphertext is authenticated against its storage slot so blobs cannot
  be swapped between entries. (Earlier releases used the now-deprecated
  `EncryptedSharedPreferences`; it was replaced — together with the
  `androidx.security:security-crypto` dependency — by this direct Keystore
  wrapper, removing the intermediate wrapped-keyset file and its corruption
  modes. As permitted by the pre-release storage policy, there is **no data
  migration**: an install upgraded across this change boots into the startup
  recovery screen, where the only path forward for the old database is the
  explicit wipe, and previously saved API keys must be re-entered.)
- **The passphrase is generated only while no database file exists yet, and
  is never regenerated once one does.** While a database is present, any
  failure to read the stored passphrase — unopenable preferences, a missing
  or malformed entry, or a key/file mismatch after the database was restored
  from another install — raises a typed error that routes to a dedicated
  startup recovery screen. That screen offers **Retry** (keystore failures
  are often transient) and an explicit **Erase all data** action behind a
  typed confirmation; the app never wipes, re-keys, or silently recreates
  the passphrase store on its own while user data could be orphaned by it.
  The passphrase is read lazily at the first real database open — never
  during dependency injection — so a failure always surfaces where the UI
  can handle it.
- The store holding **cloud API keys** intentionally keeps the opposite,
  availability-first recovery: a key value that can no longer be decrypted is
  treated as unset and dropped. Unlike the database passphrase, keys can
  simply be re-entered by the user, so availability wins over preservation
  there.
- The app does not retain any plaintext copy of the passphrase.
- **Schema migrations preserve data on upgrade.** Every schema-version bump is
  backed by an explicit Room `Migration` registered through `addMigrations(...)`;
  the destructive-recreation fallback on upgrade has been removed. An in-place
  upgrade therefore keeps all local data — chats and metadata (`chat_messages`,
  `chat_sessions`), long-term memory (`memory_chunks`), pipeline run traces
  (`trace_steps`), **custom pipelines** (`pipelines`, `pipeline_nodes`,
  `pipeline_connections`), and **saved presets and prompt templates**
  (`pipeline_presets`, `prompt_presets`, `prompt_templates`). The migrations
  across the exported-schema baseline range are covered by a `MigrationTestHelper`
  regression suite that validates the resulting schema and data preservation.
- **Residual pre-1.0 caveats.**
  - *Downgrade.* Installing an **older** build over a newer database recreates
    it empty (`fallbackToDestructiveMigrationOnDowngrade`), since forward
    migrations cannot reverse a schema. Avoid downgrading if you want to keep
    local data.
  - *Legacy plaintext dev databases.* Unencrypted databases from pre-SQLCipher
    development builds (which predate the public release) are not supported and
    cannot be opened. This affects only such dev installs, never a released
    version.

  Both are data-loss / availability concerns, not confidentiality ones —
  discarded rows are destroyed, never exposed. If you must downgrade, export
  anything you want to keep first: chats and long-term memory through their
  in-app export actions, and any custom pipelines / saved presets via the
  pipeline-library and preset JSON-export actions.

### Agent file workspace (at-rest)

The agent has a small private **workspace** — a single jailed directory
(`files/agent_workspace/` inside the app's private `filesDir`) that the file
tools (`read_file`, `write_file`, `edit_file`, `delete_file`, `list_files`,
`find_files`) read from and write to, and that the **Files** screen surfaces
to the user. Its at-rest posture is deliberately **weaker than the
database's**, and this is the honest statement of that trade-off:

- The workspace lives in app-private internal storage, so it is protected by
  the device's **file-based encryption (FBE)** — the OS-level encryption that
  covers every app's private data while the device is locked and the user has
  not yet authenticated after boot. It is sandboxed from other apps by the
  standard Android app-data permission boundary.
- It is **not** additionally encrypted with SQLCipher the way the Room
  database is. The database holds structured rows behind a single open
  helper, which makes a transparent cipher layer cheap and natural; the
  workspace holds arbitrary user/agent files streamed through file tools and
  exported to other apps, where a second application-level cipher would add
  cost and friction (every import/export/share would have to encrypt and
  decrypt) for protection that overlaps what FBE already provides. So
  workspace contents are protected by FBE and the app sandbox, **but not by
  the app's own SQLCipher key** — a meaningful difference if your threat model
  assumes an attacker who can read app-private storage on an unlocked,
  post-authentication device (which is already out of scope below, but is
  called out here so the asymmetry is not a surprise).
- The single canonicalisation gate (`AgentWorkspace.resolve`, which every
  other workspace operation funnels through) is the integrity boundary: every
  relative path a tool supplies is resolved and checked for containment, so a
  `../` traversal, an absolute path, or a symlink pointing out of the
  directory is refused with a typed `WorkspaceError.PathOutsideWorkspace`
  before any file is touched. A tool can therefore only ever read or write
  **inside** the workspace, never the rest of the app's private storage.

### Workspace quotas (availability control)

Two size quotas and a per-read budget bound how much an autonomous — possibly
injected or looping — agent can consume; they protect **availability**, not
confidentiality:

- A **per-file limit** (default 5 MB, `WorkspaceError.TooLarge`) and a
  **workspace-wide total limit** (default 100 MB, `WorkspaceError.QuotaExceeded`,
  pre-checked before any bytes are committed by the atomic stage-and-rename
  write) keep a runaway `write_file` loop from exhausting device storage. User
  imports through the Files screen are charged against the same limits.
- A **per-read token budget** (default 2000 tokens) truncates `read_file`
  output so a single large file cannot blow out the local model's context
  window, and the `http_request` response is capped (1 MB default) so untrusted
  remote content cannot do the same. Both limits are user-tunable.

### Run-history retention (mitigating control)

Pipeline runs and their traces accumulate content **derived from user
input** — prompts, per-node inputs/outputs, tool observations — for as long
as the rows exist. Encryption protects them at rest; retention bounds how
much of that derived content exists at all:

- A daily maintenance pass (WorkManager, charging + idle) deletes finished
  runs that fall outside the **last N runs per chat** window or exceed the
  **maximum age**, together with their traces. Both limits are user-tunable
  in **Settings → Privacy** (defaults: 20 runs per chat, 30 days).
- Only runs in a settled, terminal state are eligible. A run parked on a
  background approval or clarification is never removed by retention while
  it waits; its lifetime is bounded separately by the **approval window**
  (default 24 hours), after which it is failed and becomes a regular
  retention candidate.
- Deleting a chat session removes its runs and traces immediately,
  independent of the retention schedule.

### Automation triggers and entry surfaces (background execution)

The agent can now start a pipeline run **without an interactive prompt**: an
**automation trigger** fires a bound pipeline when a device condition is met, the
OS **entry surfaces** (a share target and a Quick Settings tile) start one from
outside the app, and the **external-automation contract** lets another app on the
device ask for one by broadcast. Autonomous, possibly-unattended execution is a
new risk surface, and the design constrains it deliberately:

- **Low-sensitivity conditions by default.** A trigger fires on a **time
  schedule** (every N, or daily at a set time), the device **starting to
  charge**, or **gaining network / Wi-Fi connectivity**. In their default form
  none of these requires a runtime permission, and none reads user content or
  location to make its decision — the trigger evaluator sees only a charging
  flag, a connectivity flag, and the clock. They reveal nothing about the user
  beyond the fact that a schedule elapsed or a hardware state changed.
- **Wi-Fi SSID scoping is opt-in and gated on location.** A Wi-Fi trigger can
  optionally be narrowed to specific network names (SSIDs) so it fires only on,
  say, home or office Wi-Fi. Because Android treats the connected SSID as
  location-derived, this is the one trigger feature that needs a runtime
  permission (`ACCESS_FINE_LOCATION`), and it is requested **only** when the user
  adds an SSID in the editor — never for the default network condition. The SSID
  is used solely for the on-device match: it is never persisted beyond the
  trigger's own condition, never logged, and never leaves the device. If the
  permission is not granted the SSID reads back as unknown and an SSID-scoped
  trigger simply never fires (fail-safe, never fail-open).
- **High-sensitivity conditions are deliberately deferred.** Conditions that
  would require privileged, content-bearing access — a
  **NotificationListenerService** (reads every notification on the device),
  **geofencing / location**, and **SMS** — are intentionally **out of scope for
  this release**. They are higher-value automation but a much larger privacy and
  attack surface (each is a standing read into sensitive user data), so they are
  held back rather than shipped behind a checkbox. This is a design boundary, not
  an oversight; if they are added later they will get their own threat-model
  entry and an explicit, revocable consent.
- **Inert until the user binds a pipeline.** A trigger, the share target and the
  tile all do **nothing** until the user explicitly points them at a pipeline —
  the privacy-first default. An unbound trigger never fires, and a bound trigger
  is **auto-disabled** if its pipeline is later deleted, so a dangling automation
  can never wake and run an unintended graph.
- **The external-automation contract is off by default and cannot be opened by
  accident.** It is the only entry surface reachable by code the user did not
  write, so it carries more than the shared defaults above:
  - The switch raises a **consent dialog** naming what is being agreed to, and
    only moves once the user confirms. Turning it back off is immediate.
  - Even switched on it stays **inert until bound** to exactly one pipeline, and
    that binding is an **allowlist, not a fallback**: a request naming any other
    pipeline is refused, never redirected to the bound one.
  - **The sender is not attested, and the app does not pretend otherwise.**
    Android reports a broadcast's sender only when the *sender* opts in, which
    automation apps and `adb` do not. So the callback address a request supplies
    is an unverified claim — shown as such in the journal — and the trust
    decision rests entirely on the user's switch and their binding. This is why
    the callback payload is deliberately thin (the caller's own correlation id, a
    status, and a reason where there is one) and **never carries what the run
    produced**: an unauthenticated address must not be able to ask this app to
    read anything back to it.
  - **Accepted requests are rate-limited** per hour, and **every request is
    journalled** — admitted or refused, with its typed reason — so a profile that
    silently does nothing can be diagnosed, and a looping one is visible.
  - The receiver declares `intentMatchingFlags="enforceIntentFilter"`, which
    **Android 16 and above enforce** and Android 14–15 ignore. The gap is
    narrower than it looks, and deliberately so: that flag is not what validates
    the action. The receiver copies the action verbatim and the use case refuses
    anything the contract does not define, journalling the refusal — so an
    explicit intent carrying a foreign action is refused identically on 14 as on
    16. Every other defence above lives in app code and is unaffected by the
    platform version.
- **No new execution path, no relaxed gate.** A fired trigger (or an entry
  surface, or an admitted external request) runs through the **exact same
  background path** as a scheduled task — the same persisted-run lifecycle, the
  same foreground-service promotion, and the same engine — attributed with a
  distinct run origin (`TRIGGER` / `SHARE` / `QUICK_TILE` / `EXTERNAL`) only for
  accounting. **An external call asks for a run; it does not approve what the run
  then wants to do.** Crucially, the
  **human-in-the-loop gate stays fully in force**: before any `SENSITIVE` or
  `DESTRUCTIVE` tool executes inside any of these unattended runs, the run
  **parks**
  on a persistent approval notification and waits — it does **not** auto-approve
  because no UI is attached. An unattended automation can therefore *propose* a
  sensitive action but never *execute* one unreviewed; an unanswered park is
  failed once the approval window elapses (see *Run-history retention* above and
  *Two-phase HITL* in [docs/architecture.md](docs/architecture.md)). The
  background-execution arc — trigger fires → background run → notification →
  result in the bound chat, including the park-and-approve path — is covered
  end-to-end by an integration test.
- **An unattended run is bounded, and the bound survives being answered.** A run
  started without anyone watching can loop, and with a cloud provider configured
  a loop spends the user's own API key. Two ceilings — a number of steps and a
  number of tokens — are counted on the **root run record** across the whole run
  tree, so a nested sub-pipeline cannot start a fresh allowance and, crucially,
  neither can a resume: answering a background approval hours later continues the
  same budget instead of restarting it. Background runs get their own, tighter
  token ceiling than interactive ones. Breaching a ceiling ends the run with a
  typed reason the surfaces render in plain language; it is a **defensive stop**,
  reported as such, not a silent truncation. A separate stuck-detector ends a run
  that is repeating work without progressing.
- **The journals can be exported, by you, over no network.** Both the trigger
  journal and the external-request journal can be written to a file through the
  system share sheet on an explicit action. There is no network on that path —
  a build-time architecture check fails the build if a network dependency
  reaches the export code — and the file carries the journal rows, not the
  content of the runs they describe. Once the share sheet hands the file to
  another app, that app's handling is outside this threat model.
- **Each trigger owns one bound chat.** A trigger's runs land in a single chat
  session named after it (recurring fires accumulate there), so the results of an
  autonomous run are visible and auditable in the same encrypted store as the
  rest of the conversation — never hidden.

### Local usage statistics (on-device only)

The optional **Usage statistics** screen records coarse counts of how the app is
used — runs per pipeline, run outcomes, trigger firings by kind, and active
days. **Nothing on this path ever leaves the device:** the counters live in the
same SQLCipher-encrypted database as the rest of the user's data, the figures
are deliberately coarse (a firing *kind*, never a schedule time or a Wi-Fi-only
flag), and a **build-time architecture guard** forbids any network import on the
telemetry surface so a regression cannot quietly add an upload. Recording is a
local-only opt-in the user can disable or clear at any time; the *Share as text*
/ *Export JSON* actions are voluntary, one-shot, and routed only to a
destination the user picks (a share sheet or a file). This is **separate from
crash reporting** below, which is the only path that can transmit anything
off-device, and only after an explicit opt-in.

### Message attachments — images and audio (on-device guarantee)

A chat message can now carry **multimodal** input: one image attachment
(gallery / screenshot / camera) and a voice clip that is transcribed before a
run starts. Both are the most sensitive content a user can hand the agent, so
their handling is constrained more tightly than text — and the constraints are
**structural**, not advisory.

- **Processed strictly on-device.** Image understanding and audio transcription
  run only through the on-device LiteRT-LM engine. No attachment, and nothing
  derived from one, is transmitted off-device as part of normal operation.
- **Attachments never reach a cloud node (release guarantee).** This is the
  central invariant of the multimodal feature: an image is delivered to **at
  most one on-device `LITE_RT` node** and `CloudLlmNodeExecutor` *structurally*
  ignores the image-delivery channel, so no code path can hand an attachment to
  a cloud provider. A pre-flight check (`ResolveEntryInferenceUseCase`) runs
  **before** the run is enqueued and blocks an image message whenever the bound
  pipeline would start on — or only reach — a cloud step, with a clear message
  that the draft and attachment are preserved. Audio never travels the graph at
  all (see below). The honest framing: this is a guarantee of the **current
  release**, enforced by the delivery code and the pre-flight gate, not a
  property the user has to configure.
- **Image storage is FBE-protected, not SQLCipher-encrypted.** The picked or
  captured image is decoded, EXIF-rotated, downscaled (aspect ratio preserved,
  longest side ≤ 1536 px) and re-encoded to JPEG into the app-private
  `files/attachments/` directory; the **original is never copied in**. That
  directory has the **same weaker-than-the-database at-rest posture as the agent
  workspace** (*Agent file workspace*, above): it is covered by the device's
  **file-based encryption (FBE)** and the app sandbox, but **not** additionally
  wrapped with the app's SQLCipher key. The same asymmetry caveat applies — an
  attacker who can already read app-private storage on an unlocked,
  post-authentication device is out of scope (see *Out of scope*), but the
  difference is called out here so it is not a surprise.
- **Image retention and cleanup.** A stored image is deleted together with its
  owning message and session. Independently, a daily
  `AttachmentOrphanCleanupWorker` (the same charging + idle maintenance window
  as run retention) reclaims any attachment file that no message references,
  with a **24-hour grace window** so a freshly-picked image that is still in the
  composer is never swept out from under the user.
- **Audio clips are ephemeral and deleted after transcription.** A recorded or
  picked clip is written as a temporary file in the app cache
  (`cacheDir/audio/`, FBE + sandbox, and subject to OS cache eviction). It is
  transcribed to text **before any pipeline runs**, and the clip is **deleted as
  soon as transcription succeeds** — only the resulting text survives, as an
  ordinary editable message the user reviews and sends. The audio bytes
  therefore never enter the pipeline graph, are never persisted in the database,
  and never leave the device.
- **Capability flags do not weaken the privacy boundary.** Because the LiteRT
  runtime exposes no capability probe, vision and audio support are **manual
  per-model toggles** (*Image support* / *Audio support* on the Models screen,
  both off by default). They gate whether the agent *attempts* multimodal
  inference; they have **no bearing** on the cloud-exclusion guarantee, which is
  enforced separately and unconditionally.

### Hugging Face access token

Model discovery can browse the curated `litert-community` organisation and
install a chosen file. A user-supplied **Hugging Face access token** (needed
only for gated repositories) is handled exactly like a cloud-provider key:

- Stored exclusively in the **Keystore-backed encrypted store** (AES-256-GCM
  under a dedicated Android Keystore key) — never in plain DataStore, log files,
  exported archives, or anything committed to the repository. An earlier
  development build kept it in plain DataStore; a **one-time migration moves any
  legacy value into the Keystore store and removes the plaintext entry**.
- **Sent only on the file download** that needs it. Browsing and metadata calls
  are public and carry **no token**, so the token is never put on the wire for
  ordinary discovery traffic.

### API keys for cloud providers

- Keys for optional cloud LLM providers (OpenAI, Anthropic, Google, DeepSeek,
  Ollama) are stored exclusively in the same kind of Keystore-backed
  encrypted store as the database passphrase (AES-256-GCM under its own
  dedicated Android Keystore key).
- Keys are never written to plain `SharedPreferences`, DataStore, log files,
  exported chat archives, or any artifact checked into the repository.

### MCP server credentials

- Credentials for a configured MCP server (a Bearer token, Basic password, or
  API-key value) are stored in the **same Keystore-backed encrypted store**,
  keyed per server by a hash of its URL.
- The plain `mcp_servers_json` DataStore entry holds only **non-secret**
  metadata (URL, transport, display name, custom headers) — never the auth
  payload. An earlier build embedded auth inline in that entry; a **one-time
  migration moves any inline auth into the encrypted store and strips it from
  the JSON**.

### On-device processing by default

- All inference performed through the on-device LiteRT-LM engine is local.
  No prompt, model output, memory chunk, tool input, or tool output leaves
  the device as part of normal operation.
- The app reaches the network for these actions, and no others. All but the
  last are user-initiated; the last one is not, and is listed as such:
  - Sending a request to a cloud LLM provider that the user has configured
    with their own API key — a `CLOUD` node, the structured-output path, the
    `delegate_task` tool, or a **memory embedding** when the user has selected
    a network embedding provider (OpenAI or Ollama) instead of the on-device
    default.
  - Connecting to an **MCP server** the user added, to list its tools and to
    invoke them.
  - Browsing or searching the curated `litert-community` organisation on the
    Hugging Face Hub from the **Discover** screen, and downloading a model
    file the user selects there or supplies by URL. Browsing is read-only and
    anonymous; only a gated-file download carries the user's token.
  - The `http_request` tool reaching a host the user has explicitly added
    to the **allowed-domains allowlist** (empty by default; see *Outbound
    HTTP and the exfiltration chain* below).
  - Anonymous crash reporting **after** the user has opted in (see below).
  - The built-in `search_tool` reaching `https://<language>.wikipedia.org` with
    a model-composed search term. **This one is on by default** — the pipeline
    seeded on first launch calls it for questions its router judges factual —
    and being classified `READ_ONLY` it passes no confirmation. Its controls
    are the tool's own switch on the **Tools** screen and the *Block network
    from local model* restriction, which withholds it; it has no allowlist and
    no per-call gate. See [PRIVACY.md § 3.4](PRIVACY.md#34-outbound-requests-from-tools).

### Prompt injection via tool content (accepted risk)

Content returned by tools is **untrusted model input**, and the agent does
not attempt to sanitize it. This is a deliberate, accepted trade-off — not an
oversight — and it works as follows:

- Text returned by any tool — Wikipedia extracts from the built-in
  `search_tool`, results from user-configured **MCP servers**, the body of an
  `http_request` response, and **the contents of a file the agent reads from
  its workspace** — is fed back into the context of subsequent pipeline
  nodes. A file the user imported through the Files screen (or that an
  earlier `write_file` produced from untrusted material) is therefore
  **untrusted model input**, exactly like a network tool result: it may
  contain text that reads as instructions to the model. That content reaches
  planning and routing nodes (`DECOMPOSITION`, `INTENT_ROUTER`), so a crafted
  tool result or file can steer which branch a pipeline takes and
  **influence the arguments of later tool calls** in the same run.
- **AppFunctions exposed by other installed apps are not on that list**, and
  the omission is deliberate rather than an oversight. Calling another app's
  AppFunction needs `EXECUTE_APP_FUNCTIONS`, which Android 16 grants to
  privileged system apps only, so an ordinary install never reaches that path
  and no untrusted input arrives through it. The reverse direction is open —
  publishing AppFunctions is available to any app, this one included, so the
  functions it publishes are an inbound entry surface rather than a source of
  tool content; they are covered by *Automation triggers and entry surfaces*
  above.
- The backstop is the **human-in-the-loop gate**: before any `SENSITIVE` or
  `DESTRUCTIVE` tool executes, the chat surfaces a confirmation card showing
  the **tool name and the exact arguments** the model produced, and the run
  suspends until the user approves or denies. An injected instruction can
  therefore *propose* a harmful call, but cannot *execute* it unreviewed.
- `READ_ONLY` tools are **not gated by design** — prompting on every lookup
  would make the agent unusable. The residual exposure is that injected
  content can shape further read-only queries and the text of the final
  answer.
- Tools without a known risk level (all MCP-provided tools included) default
  to `SENSITIVE`, the conservative fallback, so they always hit the gate.

**Recommendation:** when connecting an MCP server you do not fully trust —
or one that serves content from the open web — set the tool-approval policy
in **Settings → Restrictions** to require approval for **every** tool call,
regardless of risk level. That closes the ungated read-only path for the
price of one extra tap per call.

### Outbound HTTP and the exfiltration chain

The file tools and the `http_request` tool together create a concrete
**data-exfiltration** shape that did not exist when the agent could only read
the web and talk to a local model: an injected instruction (planted in a file
the agent reads, or in any tool result — see above) tells the model to
`read_file` something private and then `http_request` it to an
attacker-controlled URL. `http_request` is the most security-sensitive tool in
the workspace set and is designed conservatively around exactly this chain.
The defences are layered so that no single one has to be perfect:

- **Empty allowlist by default, tool hidden until opt-in.** `http_request`
  can only reach a host the user has explicitly added to the allowed-domains
  list (Settings → Tools → Allowed domains, persisted in DataStore). While the
  list is empty the tool is **not published to the agent at all** — it never
  appears in the tool catalogue — and a direct invocation is refused. There is
  no default destination an injection could reach.
- **Exact-host matching, no implied sub-domains.** Matching is exact and
  case-insensitive: adding `example.com` does not authorise `api.example.com`.
  An injection cannot widen the user's grant by guessing a neighbouring host.
- **Human-in-the-loop on every call, by method.** Risk is resolved per
  request through `HttpRequestPolicy`: a `GET` is `SENSITIVE` and a
  `POST`/`PUT`/`DELETE` is `DESTRUCTIVE`, so every `http_request` passes the
  HITL gate. The confirmation card shows the model-produced **URL and
  arguments**, so a user who is paying attention sees the destination before
  the data leaves the device. An unparsable call falls back to the strictest
  risk.
- **Stored-credential filter.** Before a request is sent, its URL, headers,
  and body are scanned for any saved cloud-provider API key (OpenAI,
  Anthropic, Google, DeepSeek). If a request would carry one, it is refused
  outright — a saved key can never be exfiltrated through this tool, even with
  user approval.
- **Redirect re-validation.** Automatic redirects are disabled; each hop is
  re-validated against the same allowlist (a redirect that points outside it
  aborts the request), the chain is capped, and credential headers are
  stripped when a redirect crosses to a different host. A redirect cannot be
  used to slip past the allowlist.
- **Transport floor.** Public hosts must use `https`; cleartext `http` is
  permitted only for loopback / private-LAN addresses written as plain decimal
  IPv4 literals (the same rule the app applies to a local Ollama or MCP server;
  the platform network-security config permits cleartext app-wide, because it
  cannot express "any private address").

The residual risk is the honest one: a user who has **deliberately added a
host to the allowlist** and then **approves** a `SENSITIVE`/`DESTRUCTIVE`
`http_request` to it can still send workspace data to that host — the tool is
doing exactly what the user authorised. The allowlist and the HITL gate make
that an explicit, reviewable decision rather than a silent capability, which
is the design goal; they do not (and cannot) override a user who chooses to
trust a destination. The Files screen warns about this when adding a domain.

### Out of scope

The threat model does not attempt to defend against:

- A device that is rooted, jailbroken, or otherwise compromised at the OS
  level.
- An attacker with physical access to an unlocked device.
- Screen capture, accessibility services, or other apps with elevated
  privileges granted by the user.
- Prompt-injection attacks delivered through content the user feeds into the
  model. The agent confirms destructive or sensitive tool invocations with
  the user (human-in-the-loop), but it cannot prevent the model from
  producing untrusted output. Injection through **tool-returned** content and
  through **files the agent reads** is documented separately above
  (*Prompt injection via tool content*), as is the read-then-exfiltrate chain
  it can drive (*Outbound HTTP and the exfiltration chain*) — same
  conclusion, same backstop: the human-in-the-loop gate and the conservative
  `http_request` allowlist bound what an injection can *do*, not what the
  model can *be told*.
- Vulnerabilities in third-party dependencies. Those should be reported to
  the respective upstream projects.

---

## What Is Collected (Crash Reporting)

Crash reporting is **opt-in and disabled by default** — and entirely absent
from the FOSS build:

- **The `foss` (F-Droid) build has no crash reporting at all.** It ships no
  Firebase/Google dependency, binds a no-op crash reporter that records and
  transmits nothing, and hides the consent toggle. The controls below apply to
  the `full` distribution only. See [docs/release.md](docs/release.md) §
  *FOSS / F-Droid build*.
- The `full` flavour's `AndroidManifest.xml` overlay sets both
  `firebase_crashlytics_collection_enabled` and
  `firebase_analytics_collection_enabled` to `false`, which disables Firebase
  auto-collection at process start.
- A runtime gate in `CrashReportingRepository` short-circuits every reporting
  call to a no-op until the user toggles
  **Settings → Privacy → Send anonymous crash reports** to on. The toggle is
  accompanied by an in-app description of what is collected.
- **Debug builds never enable crash reporting.** The opt-in observer that
  forwards events to Firebase is only installed in release builds; debug
  builds use a local Timber tree and do not touch Crashlytics regardless of
  the persisted preference.

When (and only when) a user has explicitly opted in on a release build, the
following information may be transmitted to Firebase Crashlytics:

- Stack traces for fatal crashes and non-fatal `Log.WARN` / `Log.ERROR`
  records captured by Timber.
- Device model and Android OS version.
- App version and build identifier.
- Two custom keys set by the pipeline engine: `active_pipeline_id` and
  `active_model` (the identifier of the pipeline and the model in use when
  the event occurred).

The following are **never** transmitted off-device, even with crash reporting
enabled:

- The contents of chat messages, model prompts, or model replies.
- Long-term memory chunks or any user-authored text.
- Tool inputs, tool outputs, or arguments produced by the agent.
- API keys, passphrases, or any value stored in the Keystore-backed
  encrypted stores.
- Personally identifying information beyond the device/app metadata listed
  above.

Firebase Analytics collection is never enabled, and the Analytics SDK is not
shipped in either flavour: the consent toggle enables Crashlytics alone, and no
analytics events — automatic or custom — are collected. An earlier build did
flip Analytics collection alongside Crashlytics; that call, and the dependency
behind it, are gone.

The user can revoke consent at any time from the same settings entry; the
runtime gate then returns every reporting call to a no-op.

---

## Reporting a Vulnerability

Please report suspected vulnerabilities **privately** through GitHub Security
Advisories, using the **Security** tab of this repository and the
"Report a vulnerability" action. This opens a private channel between you and
the maintainers; public issues should not be used for security reports.

When reporting, please include:

- The affected version (commit SHA or build identifier).
- A clear description of the issue and its security impact.
- Reproduction steps, proof-of-concept code, or sample data, if available.
- Expected versus actual behavior.
- Any suggested mitigation, if you have one.

Response expectations:

- **Acknowledgement:** best effort within 7 days.
- **Fix timeline:** no fixed SLA at the current pre-release stage. We will
  communicate a target timeline with you after triage.
- Please do not publicly disclose the issue until a fix has been released or
  we have agreed on a coordinated disclosure date.

Reports about vulnerabilities in third-party dependencies (LiteRT-LM, Koog,
Room, SQLCipher, Firebase, and so on) should be filed with the respective
upstream projects. We are happy to receive a courtesy heads-up if such an
issue materially affects this project.

---

## Scope

In scope for this policy: source code in this repository, build configuration,
and the runtime behavior of the resulting Android application.

Out of scope: vulnerabilities in third-party dependencies, model-quality
issues (hallucinations, refusals, biased output), and reports that require an
attacker to already control the device or its operating system.
