# Knotwork — on-device AI agent for Android

[![Check](https://github.com/alexeyw/knotwork/actions/workflows/check.yml/badge.svg)](https://github.com/alexeyw/knotwork/actions/workflows/check.yml)
[![Instrumented](https://github.com/alexeyw/knotwork/actions/workflows/instrumented.yml/badge.svg?branch=main)](https://github.com/alexeyw/knotwork/actions/workflows/instrumented.yml)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
![Version](https://img.shields.io/badge/version-0.10.0-orange.svg)
![Android API](https://img.shields.io/badge/Android-API%2034%2B-3DDC84.svg?logo=android)
[![Google Play](https://img.shields.io/badge/Google%20Play-available-3DDC84.svg?logo=googleplay&logoColor=white)](https://play.google.com/store/apps/details?id=app.knotwork.android)

> **An AI agent you build, not just prompt.** A local-first Android agent whose
> behaviour you assemble from explicit, verifiable blocks — and every risky
> action waits for your confirmation. It plans and acts across your phone, and a
> typical conversation never leaves the device.

<!--
  Hero visual: an on-device Share Handler demo loop (light/dark). Keep the
  <picture> element so prefers-color-scheme swaps the theme on GitHub.
-->
<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/hero-share-handler-dark.gif">
    <img alt="Sharing a page into Knotwork: the Share Handler pipeline runs on-device and captures it to the workspace inbox" src="docs/images/hero-share-handler.gif" width="300">
  </picture>
</p>

## What it is

Knotwork is an autonomous assistant that takes a request in plain language,
decides what to do, and carries the work out across your Android device.
Inference runs **on-device** via [LiteRT-LM](https://ai.google.dev/edge)
(Google Edge AI, the successor to TensorFlow Lite), so planning, tool calls,
and replies stay on the phone unless you deliberately reach for a cloud model.

What makes it Knotwork rather than another chat box is that **you build the
behaviour**. Every conversation is processed by a pipeline — what Tasker, n8n,
or Zapier would call a *workflow*: a graph of typed nodes (input, on-device LLM,
optional cloud LLM, tool calls, routing, decomposition, evaluation,
clarifications, output) that you can edit inside the app or in a standalone
browser editor. Automations that can act on their own still can't act
*unsupervised*: destructive or sensitive tool calls pass through a
human-in-the-loop gate, so the agent never sends a message or writes a file
without showing you the request first.

## Who it's for

Knotwork is built for the technically literate flagship-Android owner who
values privacy as a practice rather than a slogan, enjoys *constructing* the way
their tools behave, and is already at home in the world of Tasker, Obsidian,
Home Assistant, or r/LocalLLaMA. If you want to know — and control — exactly
what your agent does with your data, this is for you.

It is deliberately **not** a mass-market "ask a question, get an answer"
assistant. For that, Gemini is free and built into the OS, and competing there
is a losing bet regardless of code quality. Knotwork trades that convenience for
control, transparency, and local-first data handling.

## See it work

The **Share Handler** scenario is a good one-glance picture of the whole idea:
share an article, a message, or a screenshot to Knotwork from any app, and an
on-device model turns it into a clean, structured note in your inbox — pausing
to ask before it writes the file. On-device inference, a real tool call, and the
human-in-the-loop gate, all in one flow.

First launch leads with *what the agent should do for you* rather than an empty
canvas: pick a ready-made scenario and Knotwork materialises its pipeline as
your default, wires any surface it uses, and downloads exactly the model that
scenario needs — with size and live progress — before dropping you into a
working chat. A **Start from scratch** path stays one tap away.

https://github.com/user-attachments/assets/2ea06de5-6832-4e0c-ad48-430f375d8b72

> The hero above is a real on-device capture — no mockups.

## Highlights

- **Runs on your device.** On-device LLM inference through LiteRT-LM, with
  optional NPU/GPU acceleration; CPU-only works too, just slower. Cloud
  providers (OpenAI, Anthropic, Google Gemini, DeepSeek, Ollama) are optional,
  opt-in, and bring-your-own-key — nothing leaves the device unless you
  configure it to.
- **You build the pipeline.** A drag-and-drop editor with pan / pinch-zoom,
  snap-to-grid, a radial node picker, auto-layout, inline validation, and
  per-type configuration for all 14 node types — plus a standalone
  [browser editor](pipeline-editor.html) for authoring pipelines without
  launching the app: one HTML file with its graph library inlined, so it opens
  from disk with no network at all. Prompt variables (`$DATE`, `$TIME`,
  `$TOOLS`, `$MODEL`, `$MEMORY_SUMMARY`, `$LANG`, `$LOCATION`, `$USER`,
  `$DEVICE`) render fresh on
  every run. Prompts import and export as **Markdown files**, so one can be
  written in any editor and passed around — and a prompt file supplies wording
  only: it cannot add tools or steps, and a file that asks is imported as text
  with the request named.
- **Tools, gated by you.** Local actions through AppFunctions Jetpack and
  external servers through the Model Context Protocol (MCP), with reusable
  **skills** (instruction + tool allowlist + context) as pipeline steps. Every
  sensitive or destructive call stops for explicit confirmation — the allowlist
  is enforced at the executor level, not merely suggested.
- **Reaches you from outside the app.** A share target, launcher shortcuts, and
  a Quick Settings tile run your chosen pipeline over shared text/images or in
  the background. **Triggers** (time, charging, Wi-Fi, network) fire a pipeline
  on their own and report back with a notification. Every entry surface stays
  inert until you bind a pipeline to it — a privacy-first default.
- **Complements your automation app.** Tasker, MacroDroid or a shell script over
  `adb` can ask the agent to run one pipeline you nominate: they decide *when*,
  the agent does the language-model part of *what*. Off by default and behind an
  explicit consent dialog; the binding is an allowlist, not a fallback, so a
  request naming anything else is refused rather than redirected. Every inbound
  request — accepted or refused — lands in a readable journal you can export as
  a file when a profile misbehaves.
- **Local-first by construction.** The Room database is SQLCipher-encrypted and
  API keys are sealed with AES-GCM under a dedicated Android Keystore key.
  On-device usage statistics and journal exports (build-time guards forbid any
  network on either path), attachment images that never reach cloud models, and
  voice input transcribed on-device before the pipeline runs. A **FOSS build** ships with
  zero proprietary dependencies for F-Droid.
- **Reliable over long, autonomous runs.** A validate-and-repair gate keeps
  structured nodes producing well-formed output, exponential-backoff retry
  smooths transient cloud failures, and background history compression keeps a
  long session from overflowing the context window. Interrupted runs resume from
  their last completed node; reopening a chat reconnects to a run still
  executing in the background. A run nobody is watching is bounded by step and
  token ceilings counted across the whole run tree — so an automation that
  starts looping cannot quietly spend your cloud key — and a run stopped that
  way says so instead of looking broken.
- **Remembers what matters.** Long-term memory with semantic retrieval (RAG)
  over past conversations, automatic fact extraction, manual "Save to memory,"
  and a memory manager with search, provenance, compaction, and JSON
  export / import.
- **A chat list that stays a working list.** Archive a conversation to take it
  out of the drawer without deleting a single message: it waits, whole and
  exportable, on its own screen until you restore it — and opens read-only in
  the meantime, so nothing brings it back except you.
- **Settings that say what they do.** Options that carry a number, a borrowed
  term, or a consequence beyond their own row explain themselves in one
  sentence, opened in place from the **ⓘ** beside the label — so *Similarity
  threshold*, *Recency half-life* and the retention windows stop being words you
  have to look up elsewhere. There is exactly one copy of each explanation: the
  reference table in the user guide is generated from the strings the app itself
  shows. A setting whose behaviour is not wired up yet carries no explanation at
  all rather than a plausible one — the reference table records which, and why.
- **The documentation is reachable from inside the app — two of it offline.**
  A **Help** screen lists the user guide, FAQ, cookbook, troubleshooting page
  and automation contract, and marks each with where it is read from; Tools,
  Triggers, the pipeline editor and the external-automation settings link
  to the document that explains them — Tools and Triggers to the exact
  section. The FAQ and the troubleshooting
  guide **ship inside the app** and read with no network — which is when you
  need them — with working links between them and headings a screen reader can
  navigate. The rest open in a browser, at the tag of the version you installed,
  so you read about the app you actually have. A build gate resolves every link
  against the real document and refuses to bundle one the in-app renderer cannot
  show, so a renamed heading or a dropped paragraph fails the build rather than
  the reader.

The full feature tour lives in the [user guide](docs/user-guide.md).

## Screenshots

The pipeline editor below is a capture from a phone running the app. The other
three are rendered at 1080 × 2400 from a Roborazzi baseline
(`./gradlew :catalog:recordRoborazziDebug --tests "*HeroSnapshotTest*"`), which
keeps them and the design-system regression suite in sync — the editor canvas is
an app screen rather than a design-system component, so it has no baseline to
render from. Hover over (or tap) an image to see the dark variant via your
browser's `prefers-color-scheme`.

<table>
  <tr>
    <td align="center">
      <picture>
        <source media="(prefers-color-scheme: dark)" srcset="docs/images/hero-pipeline-canvas-dark.jpg">
        <img alt="Pipeline editor on a phone: a 22-node pipeline on the canvas, with routers, nested sub-pipelines, queues and tool steps wired together" src="docs/images/hero-pipeline-canvas.jpg" width="270">
      </picture>
      <br><sub><b>Pipeline editor</b> — a 22-node pipeline on the canvas: routers, nested sub-pipelines, queues, tools, on-device and cloud steps</sub>
    </td>
    <td align="center">
      <picture>
        <source media="(prefers-color-scheme: dark)" srcset="docs/images/hero-pipeline-library-dark.png">
        <img alt="Pipeline library — saved pipelines" src="docs/images/hero-pipeline-library.png" width="270">
      </picture>
      <br><sub><b>Pipeline library</b> — saved pipelines + import-from-browser-editor JSON</sub>
    </td>
  </tr>
  <tr>
    <td align="center">
      <picture>
        <source media="(prefers-color-scheme: dark)" srcset="docs/images/hero-tools-dark.png">
        <img alt="Tools — built-in AppFunctions and expanded MCP server" src="docs/images/hero-tools.png" width="270">
      </picture>
      <br><sub><b>Tools</b> — built-in AppFunctions + MCP servers with per-tool risk + toggle</sub>
    </td>
    <td align="center">
      <picture>
        <source media="(prefers-color-scheme: dark)" srcset="docs/images/hero-settings-dark.png">
        <img alt="Settings — searchable hub with Basic controls and eight categories" src="docs/images/hero-settings.png" width="270">
      </picture>
      <br><sub><b>Settings</b> — searchable hub with inline <i>Basic</i> controls over eight category sub-screens</sub>
    </td>
  </tr>
</table>

## Requirements

- **Android 14 or newer** (API level 34+). One OS-integration feature needs
  Android 16: the strict intent-matching rules that harden the
  external-automation entry point. On Android 14 and 15 everything else — local
  models, cloud providers, MCP servers, triggers, scheduled tasks — works
  unchanged. (Calling AppFunctions that *other* apps expose is not on this list
  at any API level: it needs a permission Android grants to privileged system
  apps only, so an ordinary install cannot do it. Publishing them, which this
  app does, is open to any app.)
- Approximately **2 GB of free RAM** available for the LLM at runtime.
- Optional: hardware acceleration via **NPU or GPU** for noticeably faster
  inference. CPU-only operation works but is slower.
- Optional: **location permission**, requested only if you scope a Wi-Fi
  trigger to specific network names (Android ties the Wi-Fi name to location).
  Nothing else uses it, and the name never leaves the device.
- To build from source: **JDK 21** and the Android SDK (the Android Studio
  bundled JBR already ships JDK 21). See [CONTRIBUTING.md](CONTRIBUTING.md) for
  the full dev setup.

## Install

### From Google Play

[**Knotwork on Google Play**](https://play.google.com/store/apps/details?id=app.knotwork.android)
— the simplest route, and the one that updates itself. Requires Android 14+.

The Play build is the `full` flavour described below. It shares a signing key
with the APKs on the Releases page, so you can move between those two channels
without reinstalling.

### From a release build

Grab the latest APK from the
[**Releases**](https://github.com/alexeyw/knotwork/releases) page and install it
on an Android 14+ device. Two flavours are published:

- **`full`** — the standard build, with opt-in Firebase Crashlytics for
  anonymous crash reporting (off by default, never collects message content).
- **`foss`** — zero proprietary dependencies and no crash reporting, suitable
  for F-Droid. See [docs/release.md](docs/release.md) § *FOSS / F-Droid build*.

> **Upgrading from `0.6.0` or earlier requires a clean install.** Those builds
> were signed with the Android debug keystore; `0.7.0` is the first release
> signed with a real release key. Android refuses to update an install whose
> signer changed, so you must uninstall the old build — which clears its local
> data — before installing `0.7.0`. Export anything you want to keep first.
> This is a one-time break; later releases update in place. See the full
> pre-release notice below.

### Build from source

```bash
git clone https://github.com/alexeyw/knotwork.git
cd knotwork
./gradlew assembleFullDebug
adb install app/build/outputs/apk/full/debug/app-full-debug.apk
```

### First run

Launch the app and let **scenario onboarding** guide you: pick a scenario and
Knotwork sets up its pipeline and downloads exactly the model it needs, then
opens into a working chat once the model has warmed. Prefer to wire things up
yourself? Choose **Start from scratch**, then open **Models**, download a
LiteRT model through the built-in manager (or paste a custom URL), and send your
first message once it loads.

Stuck on which buttons to press, or wondering whether something is supported
at all? [docs/faq.md](docs/faq.md) answers both, briefly.

## Tech stack

| Layer            | Technology                                              |
|------------------|---------------------------------------------------------|
| Language         | Kotlin                                                  |
| UI               | Jetpack Compose + Material Design 3                     |
| Design system    | Knotwork (in-tree `:catalog` library)                   |
| Brand fonts      | Inter + JetBrains Mono (bundled TTF, SIL OFL 1.1)       |
| LLM engine       | LiteRT-LM (Google Edge AI / ex-TensorFlow Lite)         |
| Tool calling     | AppFunctions Jetpack                                    |
| MCP & cloud LLM  | Koog (MCP transport + cloud-LLM client only)            |
| Architecture     | Clean Architecture + MVVM                               |
| DI               | Hilt                                                    |
| Async            | Coroutines / Flow                                       |
| Serialization    | kotlinx.serialization (structured-output validation)    |
| Network          | OkHttp + Ktor (via Koog)                                |
| Image loading    | Coil 3 (attachment thumbnails / viewer)                 |
| Local storage    | Room + DataStore                                        |
| Crash reporting  | Firebase Crashlytics (`full` flavour) / none (`foss`)   |
| Distribution     | `full` (Play / direct APK) + `foss` (F-Droid, no Google)|
| Testing          | JUnit + MockK; instrumented suite on an emulator matrix in CI |
| Architecture tests | Konsist (Clean-Architecture layer guard, in `check`)  |

## Privacy

Knotwork has no account, no sign-in, and no server of its own. There is nothing
to log into, and nothing is uploaded for the app to work.

- **Conversations stay on the device by default.** Inference runs locally
  through LiteRT-LM. Data leaves the phone only along the paths listed in
  [PRIVACY.md § 3](PRIVACY.md#3-what-can-leave-your-device): a cloud LLM node
  or memory embedding with your own API key, `delegate_task`, an MCP server you
  added, a model download, an `http_request` call to a host you allowed — all
  opt-in — and the built-in `search_tool`, which is **on from the first launch**
  and looks topics up on Wikipedia for the pipeline the app starts you with.
  Its switch is on the Tools screen, and *Block network from local model*
  withholds it too.
- **Usage statistics are local-only.** The in-app statistics are computed and
  stored on the device and are never transmitted; a build-time architecture
  guard fails the build if any network dependency reaches that code. Exporting
  them is a manual action you take.
- **Crash reporting is opt-in and off by default.** In the `full` flavour you
  can enable anonymous crash reports (stack traces, device model, Android and
  app version, the active pipeline and model identifiers). Message content,
  prompts, memory, and API keys are never included. The `foss` flavour has no
  crash-reporting dependency at all and hides the setting.
- **Sensitive data is encrypted at rest.** The local database — chats, long-term
  memory, run traces — is SQLCipher-encrypted, and API keys, the Hugging Face
  token, and MCP credentials are sealed with AES-GCM under a dedicated Android
  Keystore key.
- **The agent asks before it acts.** Destructive and sensitive tool calls stop
  at a human-in-the-loop confirmation, including when a pipeline runs in the
  background from a trigger.

The policy in full — what is stored on the device, every path that can send
data off it, and how to switch each one off — is [PRIVACY.md](PRIVACY.md). The
threat model behind it, including what is explicitly *out* of scope, is in
[SECURITY.md](SECURITY.md); the per-feature behaviour is in the
[user guide](docs/user-guide.md).

## Documentation

The FAQ and the troubleshooting guide also ship **inside the app** and read with
no network: open **More → Help**. The other documents are listed there too and
open in a browser.

- Architecture overview — [docs/architecture.md](docs/architecture.md).
- User guide — [docs/user-guide.md](docs/user-guide.md).
- Pipeline cookbook — what every node type does, which of its settings
  actually change a run, and importable recipes —
  [docs/cookbook.md](docs/cookbook.md).
- Frequently asked questions, and the standing list of what the app
  deliberately does not do — [docs/faq.md](docs/faq.md).
- Troubleshooting — [docs/troubleshooting.md](docs/troubleshooting.md).
- External-automation contract (calling the agent from Tasker, MacroDroid or
  `adb`) — [docs/external-automation.md](docs/external-automation.md).
- Extending the agent (new node types, tools, providers, prompt
  variables) — [docs/extending.md](docs/extending.md).
- Code style — [docs/code-style.md](docs/code-style.md).
- Testing strategy and coverage — [docs/testing.md](docs/testing.md).
- API & integration conventions — [docs/api-conventions.md](docs/api-conventions.md).
- Release-build playbook (R8, signing, AAB, APK size) — [docs/release.md](docs/release.md).
- Decisions that constrain contributions — [docs/decisions/](docs/decisions/README.md).
- Roadmap — [docs/roadmap.md](docs/roadmap.md).
- Contributing guide — [CONTRIBUTING.md](CONTRIBUTING.md).
- Code of Conduct — [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
- Privacy policy — [PRIVACY.md](PRIVACY.md).
- Security policy and threat model — [SECURITY.md](SECURITY.md).
- Release notes and version history — [CHANGELOG.md](CHANGELOG.md).

## Pre-release notice

This project is currently at **version 0.10.0** and is published for review and
experimentation. Expect rough edges:

- There are no stability guarantees for the public surface (Kotlin APIs,
  pipeline JSON schema, settings layout) between versions.
- On-device storage formats (encrypted preferences, exported pipeline JSON)
  may still change between versions.
- **Shared pipeline files are not yet a compatibility contract.** Exported
  pipelines and bundles carry a version stamp (`schemaVersion: 1`,
  `bundleVersion: 1`), but before 1.0 that stamp is a marker, not a promise:
  the schema may change without a major bump, and no import-time migration is
  provided. A file whose stamp does not match the build importing it is not
  rejected — it is imported on a **best-effort** basis behind an explicit
  warning. What the import *does* now tell you is exactly which settings it
  could not read, by name, whether or not the stamp matches — because the
  format adds fields without bumping the stamp, so a matching version is no
  guarantee that nothing was lost. Keep the original file anyway: naming the
  loss is not the same as preventing it. At 1.0 at
  the latest the format becomes a semantic-versioning contract: a breaking
  change then means a major `schemaVersion` plus a migration applied on import.
- **Upgrades preserve local data.** Every Room schema-version bump ships with
  an explicit migration, so an in-place update keeps your chat history,
  long-term memory, run traces, custom pipelines, and saved presets / prompt
  templates. (Note: *downgrading* to an older build recreates the database
  empty — forward migrations cannot be reversed — so export anything you want
  to keep before installing an older version.)
- **The signing identity changed at `0.7.0` — a one-time upgrade break.**
  Builds up to and including `0.6.0` were signed with the Android debug
  keystore; `0.7.0` is the first release signed with a real release key.
  Android **refuses to update an install in place when the signer changes**
  (signature mismatch), so upgrading from `0.6.0` or earlier means uninstalling
  the old build first — which clears its local data. Export anything you want
  to keep before you do. Releases from `0.7.0` onward share one signer and
  update in place normally.

## License

Released under the Apache License 2.0. See [LICENSE](LICENSE) for the full
text.
