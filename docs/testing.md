# Testing

This document describes the test strategy and tooling used across
Knotwork, an on-device AI agent for Android — what kinds of tests live
where, how to run them, and what the
quality bar is. It covers both unit tests (`app/src/test/`) and Compose /
instrumented tests (`app/src/androidTest/`).

## Coverage policy

Target **100% logic coverage** in new or modified code of the `domain` and
`data` layers; the build-side gate is **75% LINE aggregate** across the
module (raised from 70 % — see
[`coverage-baseline.md`](coverage-baseline.md) § *Enforced threshold*).
Per-package decomposition and informational per-package targets are also
documented in [`coverage-baseline.md`](coverage-baseline.md); the full
policy — what is counted, what is excluded, and how regressions are
handled — lives in [`static-analysis.md`](static-analysis.md).

Every public method in `domain` and `data` should have at least one unit
test.

## Coverage measurement (Kover)

Test coverage is measured by
[Kover](https://kotlin.github.io/kotlinx-kover/gradle-plugin/) (Gradle plugin
`org.jetbrains.kotlinx.kover`, version pinned in
`gradle/libs.versions.toml`). Generated code (Hilt factories and modules,
Room `*_Impl` DAOs, Compose previews and synthetic singletons, DI modules,
`BuildConfig`) is excluded by the filter in `app/build.gradle.kts`.

> The app has a `distribution` product-flavour dimension (`full` / `foss`), so
> per-variant Gradle tasks carry the flavour segment (e.g. `koverVerifyFullDebug`,
> `lintFullDebug`). Coverage is measured on the representative `full` variant;
> both flavours share every measured source.

Run locally:

```bash
./gradlew :app:koverHtmlReportFullDebug   # HTML report — drill-down per package/file
./gradlew :app:koverXmlReportFullDebug    # XML report — for CI parsers
./gradlew :app:koverLogFullDebug          # console one-liner: line coverage %
```

Report locations:

- `app/build/reports/kover/htmlFullDebug/index.html` — primary visual entry
  point.
- `app/build/reports/kover/reportFullDebug.xml` — JaCoCo-compatible XML.

The current baseline numbers and the rationale behind every exclusion are in
[`coverage-baseline.md`](coverage-baseline.md).

## Unit tests (JUnit + MockK)

### File layout

Mirror the source-file path under the `test` source set:

```
src/main/.../domain/usecase/SendMessageUseCase.kt
src/test/.../domain/usecase/SendMessageUseCaseTest.kt
```

### Naming pattern

```kotlin
@Test
fun `given <precondition> when <action> then <expected result>`() { ... }
```

### Standard structure (Given–When–Then)

```kotlin
@Test
fun `given valid input when execute then emits success state`() {
    // Given
    val input = "Hello"
    every { mockRepository.findChat(any()) } returns flowOf(fakeChat)

    // When
    val result = useCase.execute(input).first()

    // Then
    assertThat(result).isInstanceOf(Result.Success::class.java)
}
```

### Mocking

- Use **MockK** for all mocking: `mockk<T>()`, `coEvery { }`, `coVerify { }`.
- Use `relaxed = true` only when the mock's return values are irrelevant to
  the test.
- Use `spyk()` only for testing real class behaviour with partial overrides.

### ViewModel tests

- Use `kotlinx-coroutines-test` with a `StandardTestDispatcher`.
- Always call `advanceUntilIdle()` after triggering state changes.
- Use the **Turbine** library when asserting on a sequence of `Flow`
  emissions.

### Use-case tests

- Cover every branch: happy path, error / exception, empty state, boundary
  values.
- Mock **all** external dependencies (repositories, dispatchers, clocks).

### Tests that read files from the repository

A test that opens a file by path — a guard over published documents, the
browser editor, the store listing, screenshot baselines — is answered from
Gradle's up-to-date check unless that file is an input of the test task. Edit
the file, and the test does not run again: `check` stays green on the very
change the test exists to catch.

- **Files under `src/main` need nothing.** Editing a source recompiles the
  module and editing an asset repackages the resources the Robolectric tests
  load; both re-run the task (measured).
- **Everything else must be declared** on the test task, next to the reason, in
  the module's build file — `tasks.withType<Test>().configureEach { inputs.file(…) }`
  in `app/build.gradle.kts` (repository-root files, `docs/`, `fastlane/`, the
  instrumented sources and workflow), `catalog/build.gradle.kts` (screenshot
  baselines) and `buildSrc/build.gradle.kts` (the `:app` and `:catalog` sources the
  cookbook generator test reads).
- **Prove it once:** run the test twice (the second run is `UP-TO-DATE`), change
  the file, run again — the task must execute. Without the declaration it does
  not: the privacy policy, the browser editor, the cookbook generator's sources
  and the screenshot baselines were each found that way.

## Instrumented / Compose UI tests

> **Note:** instrumented tests run in CI on emulators — see
> [The instrumented gate](#the-instrumented-gate) for the matrix, the
> exclusion list and how to reproduce a CI run locally. They stay outside
> `./gradlew check`, which remains JVM-only.

### Setup

```kotlin
@get:Rule
val composeTestRule = createAndroidComposeRule<MainActivity>()
```

### Naming

`<ScreenName>ScreenTest.kt` under the `androidTest` source set.

### Must-test scenarios per screen

- The initial loading state renders correctly.
- The success state renders the expected content.
- The error state shows the error UI with a retry action.
- User interactions (click, swipe, input) dispatch the correct ViewModel
  events.

## What not to test

- Generated code (Room DAO auto-generated implementations, Hilt factories).
- Trivial data classes with no logic.
- `@Preview` Composables.

## CI gate

All tests, static analysis and coverage must pass via:

```bash
./gradlew check :buildSrc:test
```

Two tasks, and the second is not optional: `buildSrc` is a separate build, so
its tests cannot be reached from `check` — which is why CI spells both out, and
why running only `check` locally is a narrower gate than the one that decides
the merge. The build-logic module holds the generators behind the cookbook, the
file maps and the docs gates; a red test there means a published document is
being generated from a rule nobody checked.

`check` is the task CI runs on every pull request. It executes
detekt (including the type-resolution gate, `detektFullDebug` +
`detektFossDebug`), ktlint, Android lint, the unit-test suite for both
flavours (`testFullDebugUnitTest` + `testFossDebugUnitTest`), the `:catalog`
screenshot tests **in verify mode** (`verifyRoborazziDebug` — a render that
differs from its committed baseline beyond anti-aliasing fails the build; capture
with `KnotworkRoborazziOptions`), and
`koverVerifyFullDebug`. Lint must pass with no new warnings.

`check` does not compile the instrumented source set, so CI compiles it in
a separate step of the same job:

```bash
./gradlew :app:compileFullDebugAndroidTestKotlin :app:compileFossDebugAndroidTestKotlin
```

Run it as its own Gradle invocation, never appended to `check`: bundling
the two into one invocation makes dozens of unrelated Robolectric unit
tests fail with *Default FirebaseApp is not initialized in this process*.
Run separately, both are green.

## The instrumented gate

The `app/src/androidTest/` suite runs on emulators in its own workflow,
`Instrumented`, separate from `check`:

It runs three legs on `google_apis` x86_64 emulators — both distribution
flavours at API 36 (`full` and `foss`, the two things the project actually
ships) plus `full` at API 34, the `minSdk` floor — on every pull request
into `main`, every push to `main`, and nightly.

### Why the third leg is the API floor

The first two legs sit at the API *ceiling* the project supports. When
`minSdk` moved from 36 to 34, the floor had no automated coverage at all —
roughly ten thousand lines of Compose, Room and DAO tests had only ever
run at 36, so anything breaking specifically at the floor had nothing to
surface it. The leg uses `full` because the flavour axis is already
covered at API 36 and both flavours share every measured source.

**What the floor leg does not cover, said plainly:** it does not exercise
the AppFunctions extension path. Below API 36 the library selects the
`com.android.extensions.appfunctions` sidecar instead of the platform
service, and that branch genuinely cannot execute on an API 36 emulator —
but the only test that would reach it, `AppFunctionsEndToEndTest`, is on
the device-only exclusion list, because `EXECUTE_APP_FUNCTIONS` is
signature-level and an emulator cannot grant it. The AppFunctions runtime
is covered by nothing automated at any API level; it belongs to the manual
reference-device pass, alongside `targetSdk` 37.

### Why the second axis is the flavour, not a second API level

The intended shape was floor plus `targetSdk` — API 36 and API 37, back when
36 was the floor. Neither alternative API level survives contact with this
app, and both were measured rather than assumed:

- **API 37 never reaches the suite.** The image boots, and then the
  emulator action's own post-boot `adb shell input keyevent 82` — which no
  input can disable — dies with *Failure calling service input: Broken
  pipe*. 0 of 2 runs.
- **API 36.1 failed 0 of 3 runs**, at a different layer each time: the AVD
  disk, the keyguard, then `install-write`. The first two produced fixes
  that hardened the API 36 leg too. The third has none in sight, and 36.1
  is still Android 16, so what it adds over API 36 is a patch level rather
  than an API surface.

API 36 has been green 3 of 3 with the full suite. So the second
configuration is the one the project ships twice over: `foss`, which is
what F-Droid builds, shares every `main` source with `full`, and had never
been exercised on a device by anything automated.

**The cost is real and is not hidden: the app targets API 37 and no
emulator run covers it.** That belongs to the manual reference-device pass
until the action makes its unlock step optional.

### Two settings that are load-bearing, not decoration

The AVD is given an explicit **10 GB disk**. `abiFilters` strips non-arm64
only in `release`, so the **debug** APK carries all four ABIs at ~210 MB,
and the default partition runs out while adb is still pushing it — *write
failed: No space left on device*.

The script **dismisses the keyguard** before starting the suite. The
action's post-boot `input keyevent 82` retires a swipe lock on some images
and not others, and when it does not, the failure looks nothing like a lock
screen: instrumentation launches the app while the user is still locked and
it dies with *SharedPreferences in credential encrypted storage are not
available until after user (id 0) is unlocked*, running zero tests.

Both gaps were invisible on API 36 — which happened to fit in the default
disk and happens to boot unlocked. A single-configuration matrix would have
shipped both as future "the emulator is flaky" noise.

### Making it blocking

The workflow runs on every pull request into `main`; whether it *blocks* is a
branch-protection setting, which lives in GitHub rather than in this
repository. Require exactly two checks on `main`:

- `./gradlew check`
- `Instrumented gate`

Not the individual legs. The required-check list is a hand-maintained copy of
job names with nothing to keep the two in step: naming the legs means a new
leg is silently not required, and a renamed one leaves a stale entry that
blocks every pull request forever, waiting for a status that can no longer be
reported. The gate job aggregates them, so the matrix stays free to change.

Two details of that job are load-bearing rather than decorative. It runs under
`if: always()`, because **a job skipped by a conditional is reported to branch
protection as a success** — without it, a failing matrix would skip the gate
and the required check would go green exactly when the tests were red. And it
demands `success` from both the classifier and the matrix, so a failed
classifier (which leaves the matrix `skipped`) fails the gate rather than
passing as an absence of news. A cancelled run leaves the gate red for the
same reason: a cancelled run is not a passed one.

Leave *Require branches to be up to date before merging* off. It would force a
rebase and a full re-run — around 35 minutes of `check` plus 16 of emulators —
every time anything else lands on `main`.

### Why it is not part of `./gradlew check`

The release workflow reuses the `check` workflow verbatim as its first
job, so anything added there also gates a signed release built from a tag.
An emulator downloads system images, boots a virtual machine and talks to
it over adb — it can fail for reasons the repository did not cause, and a
release must not be blocked by that. The project's rule is that a build's
verdict has to be a function of the repository's contents, so the two
halves are split along exactly that line: **whether the instrumented
sources compile** is deterministic and lives in `check`; **whether they
pass** lives in the separate workflow.

That does not make the emulator job optional. It runs on every pull
request into `main` and goes red for both failure classes — the split
labels them, it does not excuse either. (Whether the check is *blocking*
is a branch-protection setting — see [Making it
blocking](#making-it-blocking).) What the script does is tell them apart.
`.github/scripts/run-instrumented.sh` matches the output against a tight
list of environment signatures — a lost device, a failed install, a
dependency download that timed out — and:

- an **infrastructure** failure is retried at most once, then reported as
  such in the job summary;
- everything else is attributed to the **repository** and never retried,
  because retrying a real regression until it passes is how one gets
  shipped.

The second class is named for the repository rather than for tests on
purpose: it also catches a build error in the instrumented sources, where
no test ran at all, and calling that a test failure sends the next reader
hunting for a failing test that does not exist.

Anything ambiguous falls to the repository. `Process crashed.` from the
instrumentation runner, for instance, is exactly what an app-side crash
regression looks like, so it is not on the environment list.

That classifier only ever executes on a run that is already failing, so
nothing else would notice it regressing.
`.github/scripts/run-instrumented-selftest.sh` drives it against a stub
`gradlew` — asserting the exit code, the recorded class *and* the number
of attempts — and the workflow runs it before booting any emulator. Run
it by hand in a second:

```bash
bash .github/scripts/run-instrumented-selftest.sh
```

### Tests excluded from the emulator runs

Excluded tests are named by a list, not dropped silently. A class carrying
`@DeviceOnlyInstrumentedTest(reason = …)` is filtered out of every
automated run by annotation name, and `InstrumentedTestExclusionGuardTest`
pins both the roster and the reason, so the list can only grow through a
deliberate edit that a reviewer reads.

| Test | Why an emulator cannot reach a verdict |
|---|---|
| `AppFunctionsEndToEndTest` | `EXECUTE_APP_FUNCTIONS` is declared `appop\|preinstalled\|module` by the platform. No stock emulator image grants it to a third-party caller, so cross-package discovery never returns the probe and all five scenarios degrade to skips — while still paying the discovery poll (78 s of a 453 s suite). The round-trip is exercised in the manual pass on the reference device, where the permission gate can apply. |

An excluded test is not dead code: it still has to compile under
`./gradlew check`, and it is still run by hand on a device.

### Running the instrumented suite locally

Against any connected device or emulator:

```bash
./gradlew :app:connectedFullDebugAndroidTest
```

To reproduce what CI does — same task, same exclusion filter, same
failure classification — run the CI script itself against a booted
emulator:

```bash
GRADLE_TASK=:app:connectedFullDebugAndroidTest EXCLUDE_ANNOTATION=app.knotwork.android.testing.DeviceOnlyInstrumentedTest LOG_DIR=build/instrumented-logs bash .github/scripts/run-instrumented.sh
```

## What the automated gate does NOT cover

`./gradlew check` is **JVM-based**: plain JUnit + MockK unit tests,
Robolectric-driven Compose tests, and Roborazzi screenshot tests (in the
`:catalog` module). It runs on `ubuntu-latest` with **no emulator and no
physical device attached** — deliberately, so that its verdict depends on
nothing but the repository. The instrumented suite covers part of the
remaining gap on emulators (see [The instrumented
gate](#the-instrumented-gate)), but an emulator is not a phone, and one
consequence should not be overestimated:

> **A green `./gradlew check` is NOT a guarantee that the app works on a
> physical device.** It guarantees that the JVM-testable logic behaves as
> specified. Everything that needs real Android system services, native
> libraries, or hardware is outside the gate.

The areas below are **not** exercised by CI, and why:

- **Real hardware.** The instrumented suite runs on emulators, which
  reproduce the Android framework but not a phone: no real GPU or NPU, no
  vendor OEM layer, no thermal throttling, no true Doze, no cellular
  radio. A regression that needs any of those to appear is still found
  only by the manual pass below.
- **Pull requests into a phase branch.** CI fires on pull requests into
  `main` and pushes to `main`, so a pull request that targets an
  integration branch runs neither `check` nor the instrumented suite.
  Run both locally, and dispatch the workflows manually on the
  integration branch before it is merged into `main`.
- **Real TalkBack navigation.** `TalkBackHappyPathsTest` (in the
  `:catalog` test source set) only asserts a structural pre-condition:
  every surface on the ratified happy paths publishes focusable
  interactive nodes with non-blank content descriptions. It does **not**
  drive the actual screen reader — the AccessibilityService bridge cannot
  be toggled from a Compose test. Whether TalkBack focus order, custom
  actions, and announcements actually work is verified only by a manual
  walkthrough with TalkBack enabled.
- **LiteRT-LM inference.** The native inference engine and real model
  weights never run in CI. Unit tests mock the engine boundary; model
  loading, token streaming, delegate selection (CPU/GPU), and memory
  behaviour under real weights are device-only concerns.
- **AppFunctions caller/callee.** The end-to-end test that resolves
  function metadata and invokes a function through
  `AppFunctionManager.executeAppFunction(...)` is the single entry on the
  [emulator exclusion list](#tests-excluded-from-the-emulator-runs): no
  stock image grants a third-party caller the appop-protected
  `EXECUTE_APP_FUNCTIONS`, so the full caller → callee round-trip is
  verifiable only on a device build where the gate applies.
- **Opening the SQLCipher-encrypted database.** Robolectric cannot load
  the SQLCipher native library, so JVM tests never open the real
  encrypted database. That the passphrase provisioning, keystore-backed
  storage, and encrypted open actually succeed is observable only on a
  device.
- **Foreground Service and WorkManager.** Unit tests mock the lifecycle
  and scheduling boundaries. Real service start/stop semantics,
  notification behaviour, Doze interactions, and worker execution under
  OS constraints are not reproduced on the JVM.

### Compensating control: manual smoke on the reference device

These gaps are covered by a **manual smoke test on the reference
device — Samsung Galaxy S25 Ultra (Android 16)** — performed before every
integration merge into `main`, plus a manual TalkBack walkthrough of the
ratified happy paths. The emulator suite narrowed what that pass has to
carry — Room migrations, DAO round-trips and the Compose flows are now
answered automatically — but it did not replace it: the remaining items
above are the ones only real hardware can decide. The pre-release quality gate in
[`release.md`](release.md) § *Quality gate before release* builds on the
same rule: automated checks first, manual on-device verification as the
final word.

This compromise is reasonable for a small-team project without a device
farm, but it is a compromise. If a change touches any of the areas listed
above, do not rely on CI alone — state in the pull request what was
verified on-device and how.
