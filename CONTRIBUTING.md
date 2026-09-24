# Contributing

Thank you for taking an interest in **Knotwork**, an on-device AI agent for
Android. This document covers everything you need to know to set up the
project, make a change, and open a pull request.

## Pre-release notice

The project is currently in pre-release and is published primarily
for review and experimentation. Expect breaking changes between minor
versions: the Kotlin public surface, pipeline JSON schema, on-device
storage formats, and settings layout are **not** stability-guaranteed
until `1.0.0`. See the *Pre-release notice* in [`README.md`](README.md) for
the current version and details, and [`SECURITY.md`](SECURITY.md) for the
security posture.

## Code of Conduct

By participating in this project you agree to abide by our
[Code of Conduct](CODE_OF_CONDUCT.md), adapted from Contributor Covenant
2.1.

## Where to start

- The [roadmap](docs/roadmap.md) describes where the project is headed
  and which directions welcome outside help.
- Issues labelled
  [`good first issue`](https://github.com/alexeyw/knotwork/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22)
  are scoped to be approachable without deep knowledge of the codebase:
  each one states the motivation, the files involved, and concrete
  acceptance criteria. Issues labelled
  [`help wanted`](https://github.com/alexeyw/knotwork/issues?q=is%3Aissue+is%3Aopen+label%3A%22help+wanted%22)
  mark work where contributions are especially welcome.
- [`docs/extending.md`](docs/extending.md) has step-by-step recipes for
  the most self-contained contributions — new node types, tools, cloud
  providers, and prompt variables.
- [`docs/decisions/`](docs/decisions/README.md) records the handful of
  decisions where the obvious change is the wrong one — why the manifest
  permits cleartext, why Koog is not the agent runtime, why some node
  controls were removed rather than wired up. Worth a glance before
  proposing a change that looks like an oversight; it may be a decision.
- Leave a comment on an issue before starting non-trivial work, so effort
  is not duplicated and the approach can be sanity-checked early.

## Dev setup

Required toolchain:

- **JDK 21** — required to run the unit-test suite. Roborazzi's
  Robolectric backend in `:catalog` renders the snapshot suites against
  SDK 36 — the newest release the project supports, pinned as a fixed
  reference so committed baselines survive a `minSdk` change — and
  Robolectric 4.16 needs JDK 21 for that. Production code still compiles to
  `JavaVersion.VERSION_17` / `JvmTarget.JVM_17` — building the APK works
  on JDK 17 — but `./gradlew check` (the merge gate) needs JDK 21. The
  Gradle daemon asks for JDK 21 of any vendor
  (`gradle/gradle-daemon-jvm.properties`): Gradle uses one it finds on the
  machine and downloads one on first use otherwise. CI installs Temurin 21,
  so no JDK is downloaded there — Gradle has no checksum for that download,
  which is why the build no longer names a vendor that only a download could
  supply.
- **Android Studio** — current stable channel (or any IDE that supports
  AGP 9.3.x and Kotlin 2.4.x).
- **Android SDK** — install platform **API 37** (`compileSdk` +
  `targetSdk`). Minimum runtime is API 34 (Android 14).
- **NDK** is not required.

Debug and release install side by side:

- The debug build uses the applicationId `app.knotwork.android.debug`
  and the launcher label **Knotwork Debug**, so it never replaces (or
  shares data with) a release build already on the device. Its Firebase
  config is the placeholder in `app/src/debug/google-services.json`,
  which keeps a debug build off any real project even when the
  module-root file is swapped for a real one.
- Both apps answer the same deep links and share targets, so Android
  will ask which one to use. That is expected; pick by the label.

Local configuration:

- Create `local.properties` in the repository root with at least
  `sdk.dir=<absolute path to your Android SDK>`. The file is gitignored
  and will never be committed.
- All other configuration is checked in (`gradle.properties`, the version
  catalog under `gradle/libs.versions.toml`, and the build scripts under
  `app/`).

## Build & test

Common commands:

```bash
./gradlew assembleDebug   # build the debug APK
./gradlew test            # run JVM unit tests
./gradlew check           # full local quality gate (see below)
./gradlew bundleFullRelease   # build the Play-Store-shaped AAB (R8-minified)
```

The `release` variant runs through R8 + resource shrinking + Jansi-native
stripping; the keep rules live in [`app/proguard-rules.pro`](app/proguard-rules.pro)
and the full release playbook (signing, AAB build, APK size breakdown) lives in
[`docs/release.md`](docs/release.md). Published releases are built by
`.github/workflows/release.yml` from a `v*` tag rather than by hand — see
[`docs/release.md`](docs/release.md) §9.

`./gradlew check :buildSrc:test` is the same gate CI runs on every pull
request. `buildSrc` is a separate build, so its tests — the generators and
scanners behind the documentation gates — cannot be reached from `check`, and
running `check` alone is a narrower gate than the one that decides the merge.
`check` aggregates:

- **detekt** — Kotlin static analysis (configured in
  [`config/detekt/detekt.yml`](config/detekt/detekt.yml)).
- **ktlint** — formatting.
- **Android lint** (`lintFullDebug` + `lintFossDebug`, plus `:catalog:lint`).
- **Unit tests** (`testFullDebugUnitTest` + `testFossDebugUnitTest`) — including
  the **Konsist** architecture guard (Clean-Architecture layer boundaries; see
  [`docs/static-analysis.md`](docs/static-analysis.md)).
- **Screenshot tests** — the design-system renders compared with their
  committed baselines (`:catalog:verifyRoborazziDebug`).
- **Kover** coverage verification (`koverVerifyFullDebug`).
- **Documentation gates** — dead internal links and `#anchors`
  (`verifyDocLinks`), Mermaid diagram structure (`verifyMermaidDiagrams`), the
  version number agreeing with every hand-written copy of it in `README.md`,
  `CHANGELOG.md`, `SECURITY.md` and `docs/roadmap.md` (`verifyVersionSources`),
  the in-app documentation links and the copies of documents bundled into the
  app (`verifyDocumentationLinks`, `verifyBundledDocs`). External `http` links
  are reported weekly by a separate workflow and never gate a merge. The full
  roster of gates lives in [`docs/static-analysis.md`](docs/static-analysis.md).

Run `./gradlew check :buildSrc:test` **locally before pushing**. Pushing
without running it just trades local feedback for slower CI feedback.

Beyond those two tasks, CI adds two things. It compiles the instrumented
source set — as a separate Gradle invocation, since bundling it into
`check` makes unrelated Robolectric tests fail:

```bash
./gradlew :app:compileFullDebugAndroidTestKotlin :app:compileFossDebugAndroidTestKotlin
```

And it *runs* the instrumented suite on emulators, in a separate
`Instrumented` workflow — three legs, all of them on every pull request
into `main`, on every push to `main` and nightly: API 36 for both
flavours (`full` and `foss`), plus `full` at API 34, the `minSdk` floor.
If you have a device or emulator attached,
`./gradlew connectedFullDebugAndroidTest` is the local equivalent. See
[`docs/testing.md`](docs/testing.md) § *The instrumented gate* for the
matrix, the exclusion list, and how a failure is classified as a test
failure or as infrastructure trouble.

## Dependencies, pins and the shipped manifest

Four gates look at what the build pulls in, what it ships and what a commit
carries out, rather than at the code; each is described in [`docs/static-analysis.md`](docs/static-analysis.md)
§ *Supply chain*. What they ask of a change:

- **Adding or bumping a dependency.** Every artifact is verified against
  `gradle/verification-metadata.xml`. A bump signed by a publisher already
  trusted there needs nothing; otherwise the build names what it cannot verify.
  Record it with
  `./gradlew --write-verification-metadata pgp,sha256 --export-keys <the failing tasks>`
  and **read the diff**: a new trusted key is a decision to trust a publisher,
  and a new checksum is trusted on first download. Gradle may widen a key's
  trust to a whole namespace (`regex="true"`); unless the key is that
  organisation's own release key, narrow it to the groups it signs. Never regenerate to silence a
  checksum *mismatch* on an existing entry — that is the substitution the file
  exists to catch.
- **Changing what the release manifest declares** — a permission, an exported
  component, a `queries` entry, including one a library brings in — fails
  `verify<Variant>MergedManifest` until you edit `config/merged-manifest/` by
  hand. A new permission also needs its row in `PRIVACY.md` §5.
- **Adding a GitHub Action** means pinning it to a full commit SHA with the
  version in a trailing comment: `uses: owner/action@<sha> # v1.2.3`. Dependabot
  keeps existing pins current.
- **Upgrading Gradle** needs the distribution checksum:
  `./gradlew wrapper --gradle-version <v> --gradle-distribution-sha256-sum <sum>`,
  with the sum from [gradle.org/release-checksums](https://gradle.org/release-checksums/).
- **Secrets.** Every commit under review is scanned. A test fixture that must
  look like a real key carries `gitleaks:allow` on its line; better, make it
  obviously fake. A false positive already pushed is waived by its fingerprint
  in `.gitleaksignore`, since a later inline waiver cannot reach the earlier
  commit.

## Branch model

Development proceeds in thematic batches, each integrated on a
long-lived branch before reaching `main`:

- `main` is the default branch and always reflects shipped, reviewed
  work. Every commit on `main` is expected to pass `./gradlew check`.
- Active development happens on integration branches named `phase/<N>`.
  Individual changes branch off the current integration branch as
  `feature/<kebab-name>`, and their pull requests **target the open
  `phase/<N>` branch**, not `main`. When the batch is complete, the
  integration branch is merged into `main` with a merge commit and
  deleted.
- If no `phase/<N>` branch is currently open (check the
  [branch list](https://github.com/alexeyw/knotwork/branches)),
  target `main` directly. When in doubt, open the PR against `main` —
  maintainers will retarget it if an integration branch is active.
- Direct pushes to `main` are not permitted; every change lands through a
  reviewed PR.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/). The
following prefixes are accepted:

| Prefix       | Meaning                                                    |
|--------------|------------------------------------------------------------|
| `feat:`      | A new user-facing feature.                                 |
| `fix:`       | A bug fix.                                                 |
| `chore:`     | Maintenance work that is neither a feature nor a fix.      |
| `docs:`      | Documentation-only changes.                                |
| `test:`      | Adding or correcting tests, no production change.          |
| `refactor:`  | Internal restructuring with no behaviour change.           |
| `build:`     | Build system, CI config, dependency or toolchain change.   |

Guidelines:

- Keep the subject line in imperative mood (`add`, `fix`, `remove`) and
  under 72 characters.
- Reference an issue in the body or footer with `Closes #N` (auto-close
  on merge) or `Refs #N`.
- Squash trivial fixups before opening the PR.

## Pull requests

Before requesting review, please confirm:

- [ ] Tests added or updated for the change.
- [ ] `./gradlew check :buildSrc:test` passes locally.
- [ ] If the change touches `app/src/androidTest/`, the instrumented
      sources compile
      (`./gradlew :app:compileFullDebugAndroidTestKotlin`) and the suite
      was run on a device or emulator.
- [ ] Public documentation is updated where the change affects user-
      facing behaviour, the public API surface, or the build / dev setup.
- [ ] If the change edits a document the app links to (`docs/user-guide.md`,
      `faq.md`, `troubleshooting.md`, `cookbook.md`,
      `external-automation.md`), `./gradlew :app:generateDocumentationLinks`
      was run — the Help screen shows each document's size, so a changed line
      count is drift — and, when `docs/faq.md` or `docs/troubleshooting.md`
      changed, `./gradlew :app:syncBundledDocs`, with both results committed.
- [ ] `./gradlew :app:generateFileMap` was run and its result committed
      when Kotlin files or directories were added, moved, or removed. The
      task also owns the `catalog/` and test maps; the root `FILE_MAP.md`
      is still hand-written and covers top-level changes.
- [ ] The PR description summarises **what** changed, **why** it changed,
      and lists the linked issue.

Maintainers will review on a best-effort basis; please be patient and
willing to iterate. Small, focused PRs are easier to review and land than
large omnibus ones.

## Language policy

All public artifacts in this repository are in **English**:

- Documentation (`README.md`, `CHANGELOG.md`, files under `docs/`, this
  document, and any future contributor-facing material).
- Code comments and KDoc.
- Commit messages and pull request descriptions.
- User-visible strings inside the application.

This applies to new content as well as to edits of existing content. If
you encounter non-English text in any of the locations above, treat it as
a bug.

## Licensing of contributions

Contributions are accepted under the [Apache License 2.0](LICENSE), the licence
this project ships under. That is not a separate agreement — clause 5 of the
licence already says that anything you deliberately submit for inclusion is
submitted under its terms, so inbound and outbound are the same licence. There
is no CLA to sign and no DCO sign-off to add; opening a pull request is enough.

## Adding a decision record

Write one when a decision would otherwise be re-litigated in review — not once
per change and not once per release. A record earns its place only if it still
binds, if it constrains what a contributor may do rather than merely describing
what the project does, and if no existing document already owns the topic. The
[index](docs/decisions/README.md) states the criteria in full; most candidates
fail the third one, which is the point.

## Further reading

- [`docs/roadmap.md`](docs/roadmap.md) — where the project is headed and
  which directions welcome help.
- [`docs/decisions/`](docs/decisions/README.md) — decisions that constrain
  what a change may do, and the reasoning behind them.
- [`docs/code-style.md`](docs/code-style.md) — Kotlin style and naming
  conventions.
- [`docs/testing.md`](docs/testing.md) — test strategy, coverage policy,
  Kover commands.
- [`docs/api-conventions.md`](docs/api-conventions.md) — conventions for
  LiteRT-LM, AppFunctions, MCP, cloud LLM APIs, Room, DataStore.
- [`docs/extending.md`](docs/extending.md) — recipes for adding a new
  node type, tool, cloud provider, or prompt variable.
- [`docs/architecture.md`](docs/architecture.md) — Clean Architecture
  overview, pipeline engine, integrations.
- [`docs/user-guide.md`](docs/user-guide.md) — end-user documentation.
- [`docs/static-analysis.md`](docs/static-analysis.md) — detekt / ktlint
  / lint configuration and severity policy.
- [`docs/release.md`](docs/release.md) — release-build playbook (R8
  keep rules, signing, AAB build, APK size breakdown, how a release is cut).
- [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) — community standards.
- [`SECURITY.md`](SECURITY.md) — threat model and how to report a
  vulnerability.
