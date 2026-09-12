# Frequently asked questions

Short answers to the questions that actually come up, each one pointing at the
page that covers it properly. Nothing here is the full story on purpose — this
page is a router, not a third copy of the documentation.

Which page do you want?

- **This one** answers *"can it, does it, where is it, what happens if"*.
- [`troubleshooting.md`](troubleshooting.md) answers *"it is broken"*.
- [`user-guide.md`](user-guide.md) describes how every screen and feature
  works, and is the canonical description of behaviour.

If you are looking for the shortest path from a fresh install to something
useful, that is [the five-step answer below](#im-installed-now-what-do-i-actually-press).

## Contents

- [Installing and choosing a model](#installing-and-choosing-a-model)
- [What leaves the device, and what does not](#what-leaves-the-device-and-what-does-not)
- [Pipelines and the editor](#pipelines-and-the-editor)
- [Triggers and background work](#triggers-and-background-work)
- [Tools, MCP, and confirmations](#tools-mcp-and-confirmations)
- [External automation (Tasker, MacroDroid, adb)](#external-automation-tasker-macrodroid-adb)
- [Run limits and stopping a run](#run-limits-and-stopping-a-run)
- [Memory](#memory)
- [Known limitations](#known-limitations)

---

## Installing and choosing a model

### What do I need to run this?

Android 14 or newer and roughly 2 GB of free RAM for the model. Memory is the
real constraint, not the CPU — a phone without an NPU or usable GPU still works,
just slower. Full list in [README § Requirements](../README.md#requirements).

### Do I have to download a model at all?

For on-device answers, yes — the model is a file that lives on your phone. The
alternative is a cloud provider with your own API key, which needs no download.
You need one or the other before anything will answer; a fresh install has
neither. See [Getting started](user-guide.md#getting-started).

### Which model should I start with?

Onboarding picks one for you — each scenario names the model it needs and
downloads exactly that. Choosing by hand, the two presets are **Gemma 4 E2B** and
**Gemma 4 E4B**; take E2B when storage or RAM is tight. Either way this is a tap,
not a decision about file formats. See
[Getting started](user-guide.md#getting-started).

### Which cloud providers are supported?

Five: OpenAI, Anthropic, Google Gemini, DeepSeek, and a self-hosted **Ollama**
server. All of them are opt-in and bring-your-own-key. They are offered during
onboarding and live afterwards under **Settings → Models → External providers**.

### Can I point it at my own OpenAI-compatible endpoint?

Not unless it speaks the Ollama API. The provider list is a closed set of five,
so a base URL is configurable **only** for Ollama — Groq, vLLM, LM Studio's
OpenAI-compatible mode and a plain OpenAI-shaped endpoint on your own VPS have
nowhere to go. See [Known limitations](#known-limitations).

### My Ollama server is not on my home network. Will it work?

Over `https`, yes — the Ollama row under **Settings → Models → External
providers** carries its base URL. Over plain
`http` it is refused unless the address is on a private network or you have
approved that specific origin, because unencrypted traffic to the open internet
is not something the app will do quietly on your behalf.

### What is "Custom Model URL", and can it use authentication?

It downloads a `.litertlm` **file** onto the phone from a direct link — it is not
a way to point the app at a remote model that stays on a server. It can
authenticate, but only with a bearer token: fill the Hugging Face access-token
field on the Models screen and the download carries it as an `Authorization`
header. There is no OAuth flow, and no other auth scheme. See
[Download a model](user-guide.md#2-download-a-model).

### Can I browse and install models from Hugging Face?

Yes, including gated repositories once you have stored an access token. See
[Discovering models from Hugging Face](user-guide.md#discovering-models-from-hugging-face).

### What is the FOSS build and how does it differ?

A second flavour with zero proprietary dependencies, built for F-Droid: no
Firebase, no crash reporting, and no consent toggle for it. Everything else —
local models, cloud providers, MCP, triggers — is identical. See
[README § Install](../README.md#install).

---

## What leaves the device, and what does not

### Does a normal conversation leave my phone?

No. On-device inference means the message, the answer, and the run in between
stay local. There is no account, no sign-in, and no server run by the
maintainer. See [PRIVACY § What never leaves your
device](../PRIVACY.md#4-what-never-leaves-your-device).

### So what *can* go out?

Five paths, and every one of them is something you switched on: a **cloud node**
using your own key, an **MCP server** you added, a **model download**, an
**`http_request` tool call** to a host you put on the allowed list, and **crash
reports** if you consented to them. Nothing else has a way out. See [PRIVACY §
What can leave your
device](../PRIVACY.md#3-what-can-leave-your-device--and-only-if-you-set-it-up),
and [SECURITY § Outbound HTTP and the exfiltration
chain](../SECURITY.md#outbound-http-and-the-exfiltration-chain) for the layered
restrictions on the tool path.

### Where are my API keys stored?

In an encrypted store sealed under a non-exportable Android Keystore key — never
in plain settings, never in exported pipelines, never in logs. Same for MCP
credentials and the Hugging Face token. See [SECURITY § API keys for cloud
providers](../SECURITY.md#api-keys-for-cloud-providers).

### Is there telemetry?

Usage statistics exist, they stay on the device, and they are never transmitted —
a build-time check fails the build if any network dependency reaches that code.
Crash reporting is separate, off by default, `full`-build only, and asks before
it collects anything. See [SECURITY § What is
collected](../SECURITY.md#what-is-collected-crash-reporting).

### Is my chat history encrypted on the phone?

Yes — chats, long-term memory, and run traces live in a SQLCipher-encrypted
database. See [SECURITY § Local
database](../SECURITY.md#local-database-at-rest-encryption).

---

## Pipelines and the editor

### Why is everything a pipeline instead of just a chat box?

Because the point of the app is that you can see and change what happens between
your message and the answer — which model, which tools, what gets confirmed. A
ready-made pipeline is active from the first launch, so you can ignore all of it
until you want to change something. See
[Pipelines](user-guide.md#library-and-active-pipeline).

### I'm installed. Now what do I actually press?

Five steps, start to finish:

1. **Pick a scenario in onboarding** — it installs a working pipeline *and*
   downloads the model that scenario needs.
2. **Chat → type a message** once the model has landed. That is the whole loop;
   everything below is optional. (Chose *Start from scratch* instead? Open
   **Models**, download a preset and tap **Make Active** first —
   [how](user-guide.md#2-download-a-model).)
3. **Pipelines → open the active one** to see the graph that produced that
   answer ([how](user-guide.md#visual-editor)).
4. **Tools** to switch a tool on, or add an MCP server
   ([how](user-guide.md#adding-an-mcp-server)).
5. **More → Automation → Triggers** to have a pipeline run on its own
   ([how](user-guide.md#creating-or-editing-a-trigger)).

### Settings has a "Run limits & structured output" section — where are the actual pipelines?

Under **Pipelines**, in the library. The settings category holds the limits and
guardrails that apply to runs, not the pipelines themselves; it used to be named
in a way that promised otherwise. See
[Library and active pipeline](user-guide.md#library-and-active-pipeline).

### What does this node actually do?

Every node type is described in the [pipeline cookbook](cookbook.md), together
with what it does with its input, which of its configuration fields change a run
and which are stored but ignored, and five importable recipes.

### Can I edit a pipeline on a computer?

Yes — there is a standalone browser editor that reads and writes the same JSON,
so you can build on a real keyboard and import the file. See [Browser pipeline
editor](user-guide.md#browser-pipeline-editor).

### Can I share a pipeline with someone else?

You can export one (or a whole composition as a bundle) and they can import it.
Be aware the file format is **not a compatibility contract before 1.0**: an
import tells you by name what it could not read, which is not the same as not
losing it. Keep the original. See [Sharing pipeline
files](user-guide.md#sharing-pipeline-files-what-compatibility-you-can-count-on).

---

## Triggers and background work

### Where are the triggers?

**More → Automation → Triggers** — the first row of the first section. See
[Triggers](user-guide.md#triggers) and the [More tab](user-guide.md#more-tab).

### Does the agent run when the app is closed?

Yes — triggers, scheduled tasks and runs already in flight continue with the app
away, and a confirmation you owe arrives as a notification. This depends entirely
on the battery setting below, which is not on by default. See [What happens when
you close the app during a
run](user-guide.md#what-happens-when-you-close-the-app-during-a-run).

### Why does nothing happen in the background on my phone?

Almost always the battery setting, which is not something the app can grant
itself. [Battery settings decide whether any of this
happens](user-guide.md#battery-settings-decide-whether-any-of-this-happens) is
the canonical explanation; [A background run dies the moment I leave the
app](troubleshooting.md#a-background-run-dies-the-moment-i-leave-the-app) and [A
trigger didn't fire](troubleshooting.md#a-trigger-didnt-fire) are the two
specific failures.

### How soon after the condition does a trigger actually fire?

Not instantly. Every non-charging trigger runs on a deferrable background
schedule, so late is normal and a gap of many times the trigger's own cadence is
not. See [How soon a trigger fires](user-guide.md#how-soon-a-trigger-fires).

### Does the model stay in memory between runs?

No — it is unloaded after five minutes idle, and also when the system asks for
memory back (unless a generation is in flight, which is never interrupted for
it). So it neither holds RAM nor drains the battery while nothing is running.

### How do I get the background journal off my phone?

Two actions in the Triggers top bar: **share** the evaluation journal as a JSON
file, or **save** it to a folder you pick. It covers every trigger, not just the
one you were looking at, because the useful question is normally about a gap
rather than a row. The request journal on Settings → Background & triggers →
External automation carries the same pair for inbound requests.

Attach either to a bug report about background reliability — it says what the
app actually did while you were not watching, which nothing else can. The file
holds the journal rows only: no message a run was given, no answer it produced.
Neither action touches the network. See [Checking what a trigger has been
doing](user-guide.md#checking-what-a-trigger-has-been-doing).

---

## Tools, MCP, and confirmations

### What address does my MCP server need?

The endpoint your server prints when it starts, path included — the form's own
example is `https://mcp.example.com/sse`. There is no separate port field: a
non-default port belongs in the URL, and the transport selector below the field
has to match the endpoint. See [Adding an MCP
server](user-guide.md#adding-an-mcp-server).

### Which authentication methods do MCP servers support?

Bearer token, HTTP Basic, or a custom API-key header — whichever you pick is
stored encrypted, per server. **OAuth is not supported.** See
[Known limitations](#known-limitations).

### Is there a "test connection" button?

No. What you get instead is the server row itself: after you add a server it
shows a health state and a tool count, and a server that cannot be reached says
so there. See [What the common MCP
errors mean](user-guide.md#what-the-common-mcp-errors-mean).

### My server publishes 16 tools but the app shows 13.

The server is deciding that, not the app — see [An MCP server is connected but a
tool I expect isn't
there](troubleshooting.md#an-mcp-server-is-connected-but-a-tool-i-expect-isnt-there),
which explains why and what it means for the missing tool.

### Why does it ask me before every tool call?

Because the tool is classed as sensitive or destructive, and that gate is
enforced where the tool executes rather than suggested to the model. Two
settings widen or tighten it for everything at once — **Approve tool calls** can
be set to ask about *every* call, and **Block destructive tools** refuses
destructive ones outright instead of offering approval — and both live in
**Settings → Tools & workspace**.

To stop the prompt for **one** tool, open it from the **Tools** screen and set
its **Risk level** to *Read only*. That works for MCP tools and for tools
discovered on the device, which start at *Sensitive* precisely because the app
cannot tell what they do; a tool built into the app states its level instead,
since the gate resolves that from the code. See [Risk levels and
human-in-the-loop](user-guide.md#risk-levels-and-human-in-the-loop).

### Can other apps on my phone expose tools to the agent?

No. Android only lets the device maker's system apps and Google publish
AppFunctions, so a third-party app cannot offer one however much it would like
to. What you get is the built-in catalogue plus whatever you connect over MCP.
See [Built-in tools](user-guide.md#built-in-tools).

---

## External automation (Tasker, MacroDroid, adb)

### Can Tasker start a run?

Yes — that is a supported contract with worked examples for Tasker, MacroDroid
and `adb`. See [external-automation.md](external-automation.md).

### Does it work out of the box?

No, and deliberately. The entry point is **off by default**, and even switched on
it stays inert until you bind exactly one pipeline that outside apps may run.
A request naming anything else is refused rather than redirected. See [Switching
it on](external-automation.md#switching-it-on).

### How do I get the result back?

The request can name a callback broadcast, which carries the run's status and
output. Statuses and refusal reasons are enumerated. See [Receiving the
callback](external-automation.md#receiving-the-callback).

### Does calling from outside skip the confirmations?

No. An external call is not a form of approval: human-in-the-loop confirmation
and the destructive-tool block apply exactly as they do to the app's own
background runs, and the approval arrives as a notification. See [The safety
model](external-automation.md#the-safety-model-in-one-paragraph).

---

## Run limits and stopping a run

### What stops a runaway pipeline?

Four numbers and a repetition detector. Steps per run and tokens per run, each
with a separate allowance for runs you did not start yourself; and, separately,
a watch for a run repeating the same step on the same input, which first nudges
the run and then ends it. See [Run limits](user-guide.md#run-limits) and [When a
run goes in circles](user-guide.md#when-a-run-goes-in-circles).

### A run hit its limit. Is the work lost?

No — it is waiting for you. Reaching a limit pauses the run rather than ending
it, and the chat offers **Continue (+N)** or **Stop the run**. Continuing gives
it one more portion of the same allowance and resumes from where it stopped, so
nothing is re-run. The pause is written down, so it survives the app closing and
is still there when you come back. See [When a run reaches a
limit](user-guide.md#when-a-run-reaches-a-limit).

### Does continuing past a limit turn the limit off?

No. It buys **one more portion** — the same allowance again, which is the number
on the button — and the run asks again when that runs out. A run you have waved
through five times is one you have said yes to five times.

### Can I cap what a run costs me?

No. The app runs on your own API key, never sees your bill, and will not show
you a figure it would have to guess. The token limit is the closest control.

### How do I stop a run that is already going?

You mostly cannot, and it is worth knowing exactly what the **stop** button in
the composer does: it detaches the screen from the run. The run keeps executing,
and its answer still lands in the conversation. What actually ends a run is
**Stop the run** on a limit pause, the repetition detector, or — for a chain of
scheduled tasks — **More → Active tasks → Stop all scheduled tasks**, which also
cancels one that is executing. The limit pause is the one reliable moment a run
offers to stop: it waits for an answer rather than pressing on. A per-run cancel
is not built yet; see [Known limitations](#known-limitations).

### My run stopped by itself. Which limit was it?

The message names it, and not every mid-run stop is a limit — three different
endings exist, with different fixes. See [The agent stopped
mid-run](troubleshooting.md#the-agent-stopped-mid-run).

---

## Memory

### What does the agent remember, and when?

Durable facts it extracted from conversations — preferences, events,
relationships — retrieved by meaning when a node is set to read memory. Small
talk and near-duplicates are skipped. See [What the agent recalls, and
when](user-guide.md#what-the-agent-recalls-and-when).

### Can I add or delete a memory myself?

Yes, both, by hand — plus **Compact Memory** to fold redundant entries together,
which verifies before it deletes. See [Browsing long-term
memory](user-guide.md#browsing-long-term-memory).

### Does memory move to a new phone?

Export and import move it, and the export is a file you hold. See [Moving memory
to another
device](user-guide.md#moving-memory-to-another-device-export--import).

### Why doesn't a memory I know exists come back?

Several distinct reasons, and which one it is decides the fix. See [Memory
search isn't finding an obvious
entry](troubleshooting.md#memory-search-isnt-finding-an-obvious-entry).

---

## Known limitations

These are current, deliberate, and stated without softening. Each one says what
would change it.

- **No arbitrary OpenAI-compatible endpoint.** The provider list is a closed set
  of five, and a base URL is configurable only for Ollama. Revisited on the
  first request filed publicly as an issue. One person has asked for it in
  private testing, which is a sample of one and does not reorder the work.
- **No OAuth anywhere.** Not for MCP servers, not for model downloads. Bearer,
  Basic and API-key headers are what exist. Revisited with the first external
  report of a real server that cannot be reached any other way.
- **No connection test for an MCP server.** You add it and read the resulting
  health row. Revisited alongside the MCP screen's next rework.
- **A built-in tool's risk level cannot be changed.** MCP tools and tools
  discovered on the device now have a **Risk level** control on their detail
  page; a tool written into the app does not, because its level is resolved from
  the code before any setting of yours is read. Revisited if a built-in's
  classification turns out to be wrong for a real pipeline rather than in
  principle.
- **One image per message.** Attaching a second replaces the first, and the app
  says so when it happens. Collages and multi-image comparisons are out.
- **MCP client features are parked** — `roots`, `sampling` and `elicitation` are
  not implemented, which is why a server may publish fewer tools to this app than
  it has. Each is a new surface with its own risk model rather than a quick
  addition. Revisited on the first external report of a real server's missing
  tool; `elicitation` would come first, being the only one that reuses the
  existing confirmation path.
- **Third-party apps cannot expose tools.** A platform restriction, not a
  decision, and nothing on the roadmap changes it.
- **The node picker in the visual editor is hard to read** at the current number
  of node types, where labels overlap. Replacing it is accepted as needed, with
  no date attached; the browser editor is the workaround in the meantime.
- **Pipeline files are not a compatibility contract before 1.0.** The version
  stamp is a marker, not a promise, and no import-time migration exists. See
  [README § Pre-release notice](../README.md#pre-release-notice).
- **The reliability numbers are one device and one operator.** A scheduled
  pipeline ran 7.14 days unopened and completed 55 of 55 firings with no
  unexplained misses. That is a real measurement, and it is not external
  validation.
- **There is no casual mode, and there will not be one.** The app is built for
  someone who wants to see and change the machinery. A phone assistant that
  answers questions already ships with the OS, and competing with it is a losing
  bet rather than a missing feature. See [README § Who it's
  for](../README.md#who-its-for).
- **One maintainer, no company.** Response times follow from that.

---

## See also

- [`user-guide.md`](user-guide.md) — how every screen and feature works.
- [`cookbook.md`](cookbook.md) — what each node type does, and recipes to import.
- [`troubleshooting.md`](troubleshooting.md) — what to do when something breaks.
- [`external-automation.md`](external-automation.md) — the contract other apps
  call.
- [`../PRIVACY.md`](../PRIVACY.md) — what is stored and what can leave.
- [`../SECURITY.md`](../SECURITY.md) — threat model and vulnerability reporting.
