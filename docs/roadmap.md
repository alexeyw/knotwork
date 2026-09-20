# Roadmap

This document describes where the project is headed after its initial
public release. It lists **directions, not commitments**: items are
ordered by rough priority within each horizon, carry no dates, and may be
reshaped or dropped as reality intervenes. The roadmap is updated as work
lands — if something here has already shipped, check
[CHANGELOG.md](../CHANGELOG.md) for the authoritative record.

Concrete, actionable work items live in the
[issue tracker](https://github.com/alexeyw/knotwork/issues);
this document provides the connective tissue between them. GitHub
Milestones may be introduced later to group issues per release once the
release cadence settles.

## Where the project is today

The current pre-release line (`0.10.x`) already covers the core loop
end-to-end:

- On-device LLM inference through LiteRT-LM, with optional
  bring-your-own-key cloud providers (OpenAI, Anthropic, Google,
  DeepSeek, Ollama).
- Graph-driven pipeline execution with a full in-app editor, a standalone
  browser editor, and a pipeline library with per-chat binding.
- Tool calling through AppFunctions (local) and MCP (external servers),
  with a human-in-the-loop gate for sensitive and destructive actions.
- Long-term memory with semantic retrieval, extraction, compaction, and
  export / import.
- At-rest encryption (SQLCipher + Android Keystore) and explicit Room
  migrations that preserve local data across upgrades.
- Value-first onboarding: a scenario gallery that sets up a ready-made
  agent — the matching pipeline, its entry surface, and exactly the
  on-device model it needs — in a single tap.
- Automation triggers and entry surfaces: run a bound pipeline in the
  background on a schedule, on charging, or on connectivity, and reach the
  agent from the system Share sheet, launcher shortcuts, and a Quick
  Settings tile.
- Local, on-device usage statistics — including a weekly view of whether
  the app is actually being used — that never leave the device, and a
  Firebase-free FOSS build flavour for the F-Droid channel.
- A chat archive, so a chat list that grows with daily use stays usable.
- Safety limits on an unattended run: reaching the step or token allowance
  pauses the run and asks whether to grant one more portion, rather than
  ending it — and the journals of what ran in the background can be
  exported from the app for inspection.
- Releases built, signed and published by CI from a tag, with the signing
  identity verified before and after the build (see
  [release.md](release.md)).
- File-oriented tools in the built-in catalogue: the agent reads, lists,
  finds, writes, edits, appends to and deletes documents in a private
  workspace, with the mutating ones behind the confirmation gate.
- An instrumented suite that actually runs: the emulator matrix covers both
  distribution flavours at the current API level and the whole suite again at
  the `minSdk` floor, on every pull request into `main` and nightly (see
  [testing.md](testing.md#the-instrumented-gate)).

See [README.md](../README.md) for the full feature list and
[architecture.md](architecture.md) for how the pieces fit together.

## Near term

The previous near-term entry here was reliability and quality of what
already exists — proven background execution, a repeatable time-to-first-
value measurement, memory and preset quality, and a chat archive. All four
have shipped, along with the first release-signed build; they are listed
above as things the product does, and [CHANGELOG.md](../CHANGELOG.md) is
the authoritative record of what each turned into. What follows is what is
near-term now.

### Getting the app into the places people look for it

Knotwork is on **Google Play**, alongside the APKs on GitHub Releases.
That settles the first half of this item: a store listing exists, and so
does a data-safety declaration matching the privacy model the app
actually implements. What is left is the second channel.

- **F-Droid.** The `foss` flavour exists precisely so the app can be
  built without proprietary telemetry, and the audience this project is
  written for largely installs from there. The open question is not the
  build but the inclusion policy: the on-device inference engine ships as
  a prebuilt native library, and whether that is acceptable has to be
  settled before an RFP is worth filing. A reproducible build (a pinned
  `SOURCE_DATE_EPOCH`) is the other piece. If the policy answer is no,
  the fallback under consideration is a project-run F-Droid repository.

Note the one-time migration cost described in the *Pre-release notice* of
[README.md](../README.md): `0.7.0` is the first release-signed build, so
updating a debug-signed install to it requires a reinstall. Releases from
`0.7.0` onward update in place.

### More recipes in the cookbook

[cookbook.md](cookbook.md) now covers this ground: every node type, what
it does with its input, which of its configuration fields change a run,
and five importable recipes. What is still thin is the recipe half —
five worked examples is a starting library, not a cookbook. The examples
worth adding next are the ones people actually get stuck on, which is a
question the first outside reports answer better than guessing does.

Writing the reference turned up a class of defect worth naming here, and
then closed it: twenty-five configuration fields were shown, accepted and
stored while nothing read them during a run. Nine were wired up and twelve
were removed, because the engine has no matching concept for them; a
further eight were reclassified, having already lost their controls and
being kept only so an older pipeline file round-trips. The cookbook's field
table is generated from the code that defines a node, so this is a
statement the build keeps true rather than one anybody maintains: a control
that stops reaching the run grows its row back.

### Whatever the first users run into

The product has not yet met an audience. The first reports from people
who did not write it will reshape this list, and that is the point of
publishing it. Bug reports with reproduction steps and "I tried to build
X and got stuck at Y" are both useful; see
[How to get involved](#how-to-get-involved).

## Mid term

### Agent tool-set expansion

Once background-run reliability is locked in, the biggest lever on how
useful the agent feels is the breadth of its built-in tool catalogue. A
dedicated workstream will grow the set of local tools the agent can call
out of the box. The file-oriented tools listed here as a candidate have
since shipped; what remains is further system integration. The design space (which tools, which permission and HITL
surfaces, which backing APIs) is intentionally **not** fixed yet;
proposals and use-case reports in the issue tracker are welcome input
while this is being scoped.

### On-device verification beyond the emulator

`./gradlew check` is JVM-only by design, and the instrumented suite now runs
beside it on an emulator matrix (above). What that still does not reach is
real hardware: native inference on an actual NPU or GPU, a background run
under a real vendor's battery management, and the AppFunctions runtime —
whose only end-to-end test is device-only and therefore runs on no emulator
leg at all. Those are verified by a manual smoke test today (see
[testing.md](testing.md#what-the-automated-gate-does-not-cover)). Closing
the remaining gap means a device farm, not another emulator.

### Pipeline editor refinement

The in-app editor and the standalone browser editor
(`pipeline-editor.html`) share the same pipeline JSON but evolve at
different speeds. Keeping the two surfaces at feature parity, smoothing
rough edges reported by early users, and improving validation feedback
are ongoing concerns rather than a single feature.

## Longer term

### Path to 1.0

`1.0.0` is the point where the project starts guaranteeing stability for
its public surfaces: the pipeline JSON schema, exported data formats
(pipelines, memory, presets), the settings layout, and upgrade paths.
Getting there is mostly a hardening exercise — schema versioning for
exports, deprecation policies, and a longer track record of explicit Room
migrations.

### Localization

All user-visible strings are currently English-only. Once the UI surface
stabilises, externalising strings for translation and accepting
community-contributed locales is a natural, well-bounded direction — and
a good area for first-time contributors.

## How to get involved

- The [issue tracker](https://github.com/alexeyw/knotwork/issues) is the
  list of concrete work, and it is short — the project is young and every
  issue in it was written by the maintainer. Issues labelled
  [`good first issue`](https://github.com/alexeyw/knotwork/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22)
  are scoped to be approachable without deep knowledge of the codebase;
  [`help wanted`](https://github.com/alexeyw/knotwork/issues?q=is%3Aissue+is%3Aopen+label%3A%22help+wanted%22)
  marks items where outside contributions are especially welcome. Each one
  names what to change, where, and what "done" means, because an issue that
  costs an evening to decode is not an invitation.
- [CONTRIBUTING.md](../CONTRIBUTING.md) covers dev setup, the branch
  model, and the PR checklist.
- [extending.md](extending.md) has step-by-step recipes for the
  cheapest high-value contributions: new node types, tools, cloud
  providers, and prompt variables.
- For new capabilities, open a
  [feature request](https://github.com/alexeyw/knotwork/issues/new/choose)
  first — many ideas fit an existing extension point and can land without
  core changes.

## How this roadmap is maintained

The roadmap is revised when a direction ships, gets re-scoped, or stops
making sense — typically alongside the release that affects it. Major
changes go through a PR like any other documentation change, so the
history of this file *is* the history of the project's intent.
