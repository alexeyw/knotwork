# Static Analysis & Coverage — Permanent Rules

This document is the source of truth for the project's quality gates. Every
PR must pass the gate below, which is also wired as the required CI job on
`pull_request → main`. Failing any sub-task blocks the merge.

> Test-coverage measurement and thresholds live alongside the rules here;
> the per-package baseline numbers used to seed the thresholds are kept in
> [`coverage-baseline.md`](coverage-baseline.md).

---

## `./gradlew check :buildSrc:test`

Two tasks, not one, and the second is not optional:

```bash
./gradlew check :buildSrc:test
```

`buildSrc` is a **separate build**. Its tasks are not reachable from this one,
so no amount of wiring inside `check` can pull them in — which is why CI spells
both out on the command line, and why running only `check` locally is a narrower
gate than the one that decides the merge.

That gap has bitten: three `CookbookDocsGeneratorTest` cases sat red on a phase
branch through two merges while every local `./gradlew check` reported success.
The build-logic module holds the generators and scanners behind the cookbook,
the file maps, the docs gates and the lint-baseline guard — a red test there
means a document is being generated from a rule nobody is checking.

`check` alone invokes (transitively):

| Sub-task                                      | Purpose                                                                 |
|-----------------------------------------------|-------------------------------------------------------------------------|
| `:app:detekt`                                 | Kotlin static analysis. Fails on any unsuppressed finding.              |
| `:app:detektFullDebug` + `:app:detektFossDebug` | Type-resolution gate (the rules the plain task cannot run, one per flavour). See below. |
| `:app:ktlintCheck`                            | Kotlin formatting & idiomatic style rules. Run `ktlintFormat` to fix.  |
| `:app:lintFullDebug` + `:app:lintFossDebug`   | Android Lint over both distribution flavours + library dependencies. `:catalog:lint` runs too. |
| `:app:testFullDebugUnitTest`                      | JVM unit tests for the debug variant.                                   |
| `:app:koverVerifyFullDebug`                       | Test-coverage threshold enforcement.                                    |
| `:app:verifyStoreListingLengths`                  | Play store-listing fields against Google's character limits. Rejection otherwise lands in the Play Console, after a signed release (see below). |
| `:app:verifyForbiddenVocabulary`              | Fails if any public text file carries the product's pre-rename name or internal planning numbering (see below). |
| `:app:verifyNoOrphanedKdoc` + `:catalog:verifyNoOrphanedKdoc` | Fails if a KDoc block documents no declaration — and so silently leaves the one below it undocumented (see below). |
| `:app:verifyDialogInventory`                  | Fails if a dialog or sheet is composed in `:app` without a recorded reason, putting it out of reach of the design-system baselines (see below). |
| `:app:checkNoInternalFqn`                     | Custom rule: forbid `app.knotwork.android.*` FQN references in code body.   |
| `:app:verifyBrowserEditorConstants`           | Fails if `pipeline-editor.html` `AUTO-GEN` blocks drift from the domain sources and the bundled presets / prompt templates, or if its node forms offer a control for a field no run reads. |
| `:app:verifyDocsHygiene`                      | Custom rule: guard the public docs against LLM tool-call artifacts and internal-document references (see below). |
| `:app:verifyExternalAutomationDocs`           | Fails if the `docs/external-automation.md` `AUTO-GEN` tables drift from the contract sources (see below). |
| `:app:verifySettingsHelpDocs`                 | Fails if the settings reference table in `docs/user-guide.md` drifts from the shipped help strings (see below). |
| `:app:verifyCookbookDocs`                     | Fails if the node reference in `docs/cookbook.md` drifts from the node sources (see below). |
| `:app:verifyFileMap`                          | Fails if a generated `FILE_MAP.md` drifts from the Kotlin sources, or an undocumented-file count grows past its ratchet (see below). |
| `:app:verifyDocLinks`                         | Fails if a relative link or an `#anchor` anywhere in the documentation leads nowhere (see below). External `http` links are reported, not gated. |
| `:app:verifyMermaidDiagrams`                  | Fails if an embedded Mermaid diagram is structurally broken (see below). |
| `:app:verifyBundledDocs`                      | Fails if a document bundled into the app drifted from `docs/`, carries something the in-app renderer cannot show, or holds a link that would not resolve offline (see below). |
| `:app:verifyVersionSources`                   | Fails if the README version badge or `CHANGELOG.md` disagrees with the declared `versionName` (see below). |
| `:app:testFullDebugUnitTest` (`CookbookRuntimeReachTest`, `CookbookRecipeValidationTest`) | Fails if the cookbook's run-time verdicts disagree with `NodeConfigCodec`, or a published recipe no longer imports (see below). |
| `:app:testFullDebugUnitTest` (`SettingsHelpCatalogTest`) | Fails if a registered setting has no help decision, or its text is blank, over-long, duplicated or in a forbidden register (see below). |
| `:app:verifyLintBaselineOverrides`            | Custom rule: fail if a lint baseline suppresses a check demoted to informational severity (see below). |
| `:app:verifyDetektAnalysisMode`               | Custom rule: fail if `detekt.yml` activates a rule that only runs under type resolution (see below). |
| `:app:testFullDebugUnitTest` (Konsist suite)      | Architecture guard: Clean-Architecture layer boundaries (see below).        |
| `:app:testFullDebugUnitTest` (`TopBarInsetGuardTest`) | Fails if a bar at the top of a screen neither applies the status-bar inset nor names the parent that does (see below). |
| `:app:testFullDebugUnitTest` (`BundledDocumentationRoutingGuardTest`) | Fails if a call that can open a document bundled into the app passes no in-app reader, and so sends it to the browser (see below). |
| `:catalog:verifyRoborazziDebug`               | Fails if a design-system screenshot differs from its committed baseline (see below). |

Pre-flight tip: run `./gradlew :app:ktlintFormat` first to auto-fix the
safely-correctable subset before invoking `check`.

---

## Detekt — Kotlin rules

Plugin: `dev.detekt`, 2.x line (required for Kotlin 2.4.x / AGP 9.x
compatibility). The exact version is pinned in
[`gradle/libs.versions.toml`](../gradle/libs.versions.toml) under the `detekt`
key — that catalog entry, not this page, is the source of truth. Configuration:
[`config/detekt/detekt.yml`](../config/detekt/detekt.yml), layered on top of
detekt's bundled defaults via `buildUponDefaultConfig = true` in
`app/build.gradle.kts`.

**That file may only hold rules that run without type resolution.** Detekt 2.x
splits its rules by what they need to answer, and a rule implementing
`dev.detekt.api.RequiresAnalysisApi` is skipped by the plain `:app:detekt` task
*in silence* — no error, no warning, no report entry. The rules that need it
live in the type-resolution gate below, and
[`:app:verifyDetektAnalysisMode`](#detekt-analysis-mode-guard) fails the build
if one is put back here.

**Strict mode**: `detekt { ignoreFailures = false }`. The default
`failOnSeverity = Error` means any rule emitting at `severity: error`
(everything we enable) fails the build. The legacy 1.x top-level
`failFast: true` switch was removed in detekt 1.22 and is intentionally not
used.

### Tuned thresholds & disabled rules

| Rule                              | Setting                           | Why                                                                             |
|-----------------------------------|-----------------------------------|---------------------------------------------------------------------------------|
| `complexity.LongMethod`           | `allowedLines: 120`               | Graph-execution loops, JSON serialisers, pipeline factories genuinely run long. |
| `complexity.LongMethod`           | `ignoreAnnotated: ['Composable']` | `@Composable` trees are declarative; ktlint handles formatting.                 |
| `complexity.CyclomaticComplexMethod` | `allowedComplexity: 25`        | Orchestrator branches over node/provider/error states.                          |
| `complexity.CyclomaticComplexMethod` | `ignoreAnnotated: ['Composable']` | Composables aggregate conditional rendering.                                    |
| `complexity.LargeClass`           | `excludes: presentation/ui/**`    | `*Screen` files host many top-level composables.                                |
| `complexity.TooManyFunctions`     | `allowedFunctionsPer*: 25`        | DAOs, Repository contracts, Hilt modules naturally expose >11 functions.        |
| `complexity.LongParameterList`    | `allowedFunctionParameters: 10`, `allowedConstructorParameters: 14` | Composable slot APIs commonly take many lambdas; Hilt assembles long constructor lists. Lives in the type-resolution gate. |
| `style.MagicNumber`               | tuned excludes + named-arg ignore | See `MagicNumber` block in YAML for rationale; `AppDatabase.kt` is excluded.    |
| `style.MaxLineLength`             | `maxLineLength: 120`, comments excluded | Code lines ≤ 120 are enforced by ktlint too; KDoc references unavoidably overshoot.|
| `style.ReturnCount`               | `max: 5`                          | Multi-return early-exit is idiomatic Kotlin.                                    |
| `style.ThrowsCount`               | `max: 4`                          | Per-field schema validators want specific error messages per case.              |
| `style.LoopWithTooManyJumpStatements` | disabled                      | Pipeline / graph traversal legitimately uses multiple `break/continue`.         |
| `exceptions.TooGenericExceptionCaught` | disabled                     | LLM SDK / native / Android-IO call sites have an open exception surface.        |
| `exceptions.SwallowedException`   | disabled                          | Catching → mapping to `Result.failure(domainError)` is the boundary contract.   |
| `naming.FunctionNaming`           | `ignoreAnnotated: ['Composable']` | Compose convention is PascalCase.                                               |
| `comments.UndocumentedPublic*`    | scoped to `domain/` and `data/repositories/` | Enforces the project rule that public API in `domain/` and `data/repositories/` carries KDoc. |

### Adding an intentional suppression

When a finding is genuinely intentional, suppress it at the **narrowest
scope** with a reason:

```kotlin
// Reason: the validate() function accumulates a fixed set of structural
// checks into a single list. Each branch is one independent rule;
// extracting helpers would mostly rename, not decompose, the cyclomatic count.
@Suppress("CyclomaticComplexMethod")
fun validate(): List<PipelineValidationError> { … }
```

Bare `@Suppress("X")` without a reason comment is rejected in code review.

**Reports**:
- `app/build/reports/detekt/detekt.html` — visual checklist.
- `app/build/reports/detekt/detekt.xml` — checkstyle-compatible for CI parsers.

### Type-resolution gate (`detektFullDebug` + `detektFossDebug`)

Detekt 2.x splits its rules by what they need in order to answer. A rule that
only walks the syntax tree runs anywhere; a rule that needs resolved types
implements `dev.detekt.api.RequiresAnalysisApi` and runs only in `full`
analysis mode. **A rule of the second kind, configured into a `light`-mode run,
is skipped without a word** — no error, no warning, no entry in the report. Of
detekt 2.x's rules, 93 are of the second kind.

That is not a hypothetical. Four rules sat in `detekt.yml` — `LongParameterList`,
`UnusedImport`, `UnusedPrivateFunction`, `UnusedPrivateProperty` — each with a
comment explaining its threshold, and none of them had ever run: an
18-parameter constructor passed `:app:detekt` clean. When they were finally
executed they had 52 findings waiting.

So the plugin-generated type-resolution tasks for each flavour's debug variant
(`:app:detektFullDebug` / `:app:detektFossDebug`) are rewired in
`app/build.gradle.kts` to
[`config/detekt/detekt-type-resolution.yml`](../config/detekt/detekt-type-resolution.yml)
and added to `check`. Both flavours run so the gate also covers the
flavour-specific sources (`src/full` / `src/foss`), which the plain task's
`source` never reached either.

| Rule                                    | What it catches                                                        |
|-----------------------------------------|------------------------------------------------------------------------|
| `coroutines.SuspendFunSwallowedCancellation` | `runCatching` wrapping suspend calls, and `try`/`catch` in suspend functions that catches `CancellationException` (or a superclass such as `Exception` / `Throwable`) without immediately re-throwing. |
| `complexity.LongParameterList`          | >10 function parameters, >14 constructor parameters; `@Composable` exempt. |
| `style.UnusedImport`                    | Imports no longer referenced — ktlint's own check cannot see through extension-property imports. |
| `style.UnusedPrivateFunction`           | Dead private/internal functions.                                        |

**Why not simply run the strict config under type resolution.** Measured on
this repository, that surfaces 296 findings, 263 of them from rules the project
never declared it wants (`InjectDispatcher` alone accounts for 131). Adopting
them is a deliberate piece of work, not a side effect of making four declared
rules run. Full-config type-resolution analysis remains available for that
triage via `:app:detektMain` / `:app:detektRelease` (not part of the gate).

**One rule is deliberately parked.** `style.UnusedPrivateProperty` belongs to
the same family but is not enabled: in the pinned detekt version it does not
count a reference made from a *property initializer*, so

```kotlin
class C(private val dep: Dep) { val holder = Holder(dep = dep) }  // `dep` reported unused
```

On this repository that is 24 of its 29 findings — every dependency a ViewModel
forwards into a delegate built as a property initializer. Enabling it would mean
24 `@Suppress` annotations asserting something untrue about live code. The five
genuine findings it did surface were fixed by hand.

**Compliant cancellation patterns**: a dedicated first catch clause
`catch (e: CancellationException) { throw e }` before the generic catch, or a
`try`/`finally` without a catch. `runCatching` must never wrap suspend calls.

**Known blind spot**: `SuspendFunSwallowedCancellation` does not analyse
`try`/`catch` blocks nested inside non-suspend inline lambdas (e.g.
`forEach { ... }`), because the enclosing function literal is not itself
suspend-typed. Reviewers must still check those sites manually.

### Detekt analysis-mode guard

`:app:verifyDetektAnalysisMode` (wired into `check`) is what keeps the split
above from silently rotting. It reads the rules
[`config/detekt/detekt.yml`](../config/detekt/detekt.yml) explicitly activates,
resolves which rule classes on detekt's own classpath implement
`RequiresAnalysisApi`, and fails when the two sets intersect — i.e. when a rule
has been added to the light-mode config that the `detekt` task would skip
without saying so.

It reads the rule set from the `detekt` configuration's jars rather than from a
list pinned in the repository, so an upgrade that moves a rule across the
boundary is caught by the next build. It also fails when that scan comes back
empty, because a guard with nothing to compare against passes everything —
which is precisely the failure mode it exists to prevent.

Scope is stated rather than implied: the guard judges the rules this repository
*declares*, not the ones detekt's bundled defaults switch on underneath them.
Those defaults do include rules requiring the Analysis API, and the light-mode
task skips them in the same silence; adopting them is the 296-finding triage
described above, and a guard should not force it by failing the build. The
enforceable promise is the narrower one: a rule the project went to the trouble
of naming and tuning actually runs.

Implementation: `buildSrc/.../DetektAnalysisModeGuard.kt`, unit-tested via
`./gradlew -p buildSrc test`.

---

## ktlint — formatting & idiomatic style

Plugin: `org.jlleitschuh.gradle.ktlint` `14.2.0`, bundled engine `1.5.0`.
Strict mode: `ktlint { ignoreFailures.set(false) }`. Rule overrides live in
[`.editorconfig`](../.editorconfig):

- `ktlint_function_naming_ignore_when_annotated_with = Composable` — Compose
  PascalCase is allowed.
- `ktlint_standard_backing-property-naming = disabled` — the `_uiState`
  /`uiState` ViewModel pattern is project-wide.

Run `./gradlew :app:ktlintFormat` for the auto-fixable subset; remaining
issues are reported by `:app:ktlintCheck`.

**Reports**:
- `app/build/reports/ktlint/ktlintMainSourceSetCheck/*` (HTML + plain).

---

## Android Lint

Provided by the Android Gradle Plugin (version pinned in
[`gradle/libs.versions.toml`](../gradle/libs.versions.toml)). Strict mode is
configured identically in `app/build.gradle.kts` and `catalog/build.gradle.kts`:

```kotlin
android {
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = true
    }
}
```

`lint-baseline.xml` grandfathers existing issues so only **newly introduced**
warnings or errors fail the build. Regenerate the baseline only after a
deliberate batch of fixes:

```bash
./gradlew :app:updateLintBaseline    # rewrites the baseline; commit it.
```

> **Regenerating also re-absorbs the informational checks below.** Lint records
> informational findings into a baseline exactly as it records errors, and a
> baselined finding disappears from the report — which would silently empty the
> dependency-drift signal while the build stays green. `verifyLintBaselineOverrides`
> fails the build if that happens, naming the lines to delete. Prefer editing the
> baseline by hand over regenerating it wholesale.

### Version-freshness checks report, they do not gate

Four checks are demoted to **informational** severity in both modules:

| Check | Answers from |
|-------|--------------|
| `GradleDependency`          | an external version index |
| `AndroidGradlePluginVersion`| an external version index |
| `NewerVersionAvailable`     | an external version index (a network round-trip per dependency) |
| `ExpiringTargetSdkVersion`  | the calendar |

The reason is determinism, not leniency. Whether the build passes ought to be a
function of the contents of the repository: these four are not, so the same
commit can be green today and red tomorrow with no edit in between, and green on
a laptop while red in CI, because the two version indexes were refreshed at
different times. A check whose verdict moves without the checked object moving is
a report.

**Deliberate exceptions.** Seven checks stay build-failing even though their
verdict is not purely a function of this repository, because each encodes a
store publishing blocker rather than a matter of hygiene — being interrupted by
them is the point:

- `ExpiredTargetSdkVersion` (fatal), which is calendar-driven;
- `PlaySdkIndexNonCompliant`, `PlaySdkIndexVulnerability`,
  `PlaySdkIndexGenericIssues`, `PlaySdkIndexDeprecated`, `RiskyLibrary` and
  `OutdatedLibrary`, which are decided by the Google Play SDK Index — a
  network-refreshed dataset with a bundled offline snapshot as fallback.

Anything outside that list is expected to hold the rule: a check that reads
network reachability, a third-party service or the clock belongs in a report,
not in the gate.

Demoted is not disabled. The checks still run, still respect the baseline, and
still appear in the reports — `disable` would drop the findings before any
reporter sees them. `warningsAsErrors = true` does not undo the demotion: lint
promotes `WARNING` to `ERROR`, and informational findings are exempt. Do **not**
add `ignoreWarnings = true` next to it — that flag suppresses informational
findings as well, which would delete the report.

Dependencies are therefore updated deliberately — when a task needs the newer
version, or in one pass before a release — rather than because a check went red
in the middle of unrelated work.

### Where the drift report lands

- Locally: the lint reports below, produced by every `./gradlew check`.
- In CI: the **Informational lint findings** table in the job summary, plus the
  `lint-report` artifact, which is uploaded on every run — including green ones,
  since a green run is precisely the run whose report carries the drift.

**Reports**:
- `app/build/reports/lint-results-fullDebug.{html,xml}`
- `app/build/reports/lint-results-fossDebug.{html,xml}`
- `catalog/build/reports/lint-results-debug.{html,xml}`

---

## Lint baseline guard (`verifyLintBaselineOverrides`)

The checks demoted above report at informational severity, which makes the lint
report their only signal — and makes the baseline a way to delete that signal
without failing anything. Lint records informational findings into a regenerated
baseline exactly as it records errors (the write path filters by issue id, never
by severity) and then filters baselined findings out of the reports, so a single
routine `updateLintBaseline` run would empty the drift report while `check`
stayed green.

`verifyLintBaselineOverrides` closes that path: a demoted id may not appear in a
committed baseline. Unlike the checks it protects, the guard is a legitimate gate
— its verdict is a function of the committed baselines and nothing else.

The pure scanner lives in `buildSrc`
([`LintBaselineGuard`](../buildSrc/src/main/kotlin/app/knotwork/android/buildtools/LintBaselineGuard.kt))
and holds the demoted-id list as its single declaration site: both lint blocks
read it as `informational += LintBaselineGuard.DEMOTED_ISSUE_IDS`, so the set
cannot drift between the modules and the guard. Its unit tests are not reachable
from the root `check` graph (`buildSrc` is a separate build):

```bash
./gradlew -p buildSrc test
```

---

## Konsist — architecture guard

[Konsist](https://docs.konsist.lemonappdev.com/) (Apache-2.0) is a Kotlin
static analyzer whose checks are written as ordinary JUnit tests. The project
uses it for two things: mechanically enforcing the Clean Architecture layer
boundaries that were previously guarded by item 1 of the manual review
checklist, and pinning a small number of **invariants that would otherwise be
held by prose alone** — a decision recorded in a document lasts exactly until
the first convenient occasion. Because the suite lives in the `test` source
set, it runs as part of `:app:testFullDebugUnitTest` — no extra `check` wiring
is needed.

The suite lives in
[`app/src/test/java/app/knotwork/android/architecture/`](../app/src/test/java/app/knotwork/android/architecture)
and is pinned to the `app` module's **production** source set
(`app/src/main`) via the shared `ArchitectureScope`. The pin is deliberate:
a whole-project Konsist scope would also parse test sources (which
legitimately cross layers) and any unrelated copies of the tree under the
project root — notably stale git worktrees under `.claude/worktrees/` — and
flag them with spurious, environment-dependent violations.

| Rule (test class)                  | What it enforces                                                                                  |
|------------------------------------|---------------------------------------------------------------------------------------------------|
| `LayerDependencyKonsistTest`       | `data -> domain <- presentation`; `domain.dependsOnNothing()` (no `domain -> data`/`presentation`). |
| `DomainPurityKonsistTest`          | `domain` imports no `android.*` / `androidx.*` — the only exception is annotation-only `androidx.annotation.*`. |
| `RepositoryPlacementKonsistTest`   | `*Repository` interfaces reside in `domain`; `*RepositoryImpl` classes reside in `data`.          |
| `ComposableUseCaseKonsistTest`     | No `@Composable` takes a `*UseCase` parameter (see scope note below).                             |
| `FirebaseIsolationKonsistTest`     | No file in the shared `main` source set imports the Firebase SDK, so the `foss` build is provably free of it. |
| `UsageTelemetryNoNetworkKonsistTest` | No file on the local usage-telemetry path imports a network client. The statistics stay on-device. |
| `PromptPackNoNetworkKonsistTest`   | No file on the prompt-pack path imports a network client — a pack is imported from a file the user picked, never fetched (see below). |
| `JournalExportNoNetworkKonsistTest` | No file on the journal-export path imports a network client — the trigger and external-request journals leave the device only through the share sheet or a file the user picked (see below). |
| `TabRootEntryGuardTest`            | A bottom-nav tab root is entered as a tab switch, never pushed onto another subtree's back stack. |
| `InstrumentedTestExclusionGuardTest` | The roster of device-only instrumented tests, and the annotation the emulator workflow excludes by, stay in step. |

**Why these rules and not more.** Konsist analyses *declarations*, not the
data flow inside a function body, so it cannot prove a `@Composable` never
*invokes* a use-case transitively. `ComposableUseCaseKonsistTest` therefore
guards only the structural smell it can express precisely — a use-case handed
to a Composable as a parameter. The deeper "presentation talks to ViewModels
only" intent stays a **manual** review item; the layer-direction and
domain-purity rules above are fully mechanical and supersede the manual
"no Android imports in `domain`" / "repository placement" checks.

The guard is regression-tested against the exact leak class it exists to
catch: reintroducing a `domain -> data` import (or an `android.*` import in
`domain`) turns both `LayerDependencyKonsistTest` and
`DomainPurityKonsistTest` red — verified during the suite's introduction.

**The three no-network rules, and the trap they share.**
`UsageTelemetryNoNetworkKonsistTest`, `PromptPackNoNetworkKonsistTest` and
`JournalExportNoNetworkKonsistTest`
select their files by name and path, and a filter of that shape goes stale in
silence: a new file on the same logical path, named something the filter does
not match, is simply unguarded and nothing says so. The prompt-pack rule closes
that with a **second instrument** — it also treats a file as in-scope when it
*imports* the `promptpack` packages (whose names it derives from real
declarations, so a package rename moves the guard with the code), and a
companion test measures coverage from the file's own text, failing when a file
that names a prompt pack falls outside the guarded set. The rule to follow when
adding to either surface is still the cheap one: name the new file **into** the
guard rather than widening the filter until it matches everything.

The journal-export rule takes the same two instruments and adds a third, after
the coverage test earned its keep on its first run: a name-only filter left the
two `Export…JournalUseCase`s and both journal screens unguarded, because none of
them carries the export token in its own name. The filter now also matches on
*imports* containing that token, and a third test pins the token to the real
declarations' names — so a rename fails loudly instead of quietly emptying the
guarded set.

`android.net` is deliberately not on the forbidden list of the prompt-pack or
journal-export rules. The file picker legitimately hands both paths an
`android.net.Uri`, and a rule that has to be suppressed on its first day is not
a rule.

All three rules are pinned to `app/src/main`, so a file added under a `full` or
`foss` flavour source set is outside their scope. No surface has one today. The
same bound puts `TriggerJournalDumpReceiver` (in `src/debug`) outside the
journal-export rule — acceptably, since it exists only in debuggable builds and
renders its document through the guarded seam the in-app export uses.

---

## External-automation contract documentation guard (`verifyExternalAutomationDocs`)

`:app:verifyExternalAutomationDocs` is a custom Gradle verification task, wired
into `check`, that fails the build when the reference tables in
[`docs/external-automation.md`](external-automation.md) no longer match the
Kotlin declarations they document — the action strings and extra keys of
`ExternalAutomationContract`, the members of `ExternalAutomationStatus`, and the
constants of `ExternalAutomationRejectionReason`.

The guard exists because this contract's callers live in **other apps**. A
Tasker profile or `adb` one-liner written against a documented key keeps using
it forever, and a key that has quietly changed does not fail loudly on the
caller's side: the request simply looks malformed to the app, and the person who
wrote the profile has no way to see why. Documentation drift here is therefore a
compatibility defect, not a tidiness issue.

Regenerate the tables with:

```bash
./gradlew :app:generateExternalAutomationDocs
```

Only the content between the `<!-- AUTO-GEN:… -->` markers is generated. The
prose, the trust model and the worked examples around them are hand-written and
never touched.

Each row's description is the declaration's **own KDoc first paragraph**, so —
unlike the browser-editor generator — there is no second, hand-maintained table
that can be forgotten. What replaces that cross-check is a stricter demand on
the source: a contract member with no KDoc fails generation outright, because an
undocumented member would otherwise publish an empty cell.

The generation logic is a pure string transform in `buildSrc`
([`ExternalAutomationDocsGenerator`](../buildSrc/src/main/kotlin/app/knotwork/android/buildtools/ExternalAutomationDocsGenerator.kt))
and is unit-tested there (`./gradlew -p buildSrc test`), including idempotence,
per-block drift detection, and the two failure modes above. The gate itself was
verified by renaming a wire key and watching `check` fail with the drifted block
named.

---

## Node-cookbook guards (`verifyCookbookDocs`, `CookbookRuntimeReachTest`, `CookbookRecipeValidationTest`)

Three guards over [`docs/cookbook.md`](cookbook.md) and the recipes it
publishes. They are separate on purpose: each answers a question the others
cannot.

### `verifyCookbookDocs` — the reference is derived, not written

A build-time verification task, wired into `check`, that regenerates the three
`AUTO-GEN` blocks of the cookbook from the sources that define a node — the
`NodeType` enum, the `:catalog` mirror of it, `NodePorts.forType`,
`NodeContextConfig.defaultForType`, the `NodeConfig` hierarchy and
`DefaultPrompts` — and fails when the committed document differs.

Regenerate with:

```bash
./gradlew :app:generateCookbookDocs
```

Three things the generator cannot read out of the sources are hand-maintained in
[`CookbookDocsGenerator`](../buildSrc/src/main/kotlin/app/knotwork/android/buildtools/CookbookDocsGenerator.kt):
the reader-facing sentence per node type (the domain KDoc is written for whoever
implements an executor, not for whoever wires a pipeline), the per-field verdict
of whether a configuration field reaches the run, and — per node type — the list
of what the node actually runs on.

That third table is what the document leads with, and it exists because the
first draft led with the wrong thing. Enumerating the editor's form fields is
not the same as describing the node's inputs, and for four node types the two
barely overlap: the form shows a prompt box that goes nowhere while the prompt
the node really runs on appeared in no table at all. A reader who knew the
system found the result confusing, which settled it.

None of the three is unguarded. Generation fails when a node type has no
sentence, when a field has no verdict, when a sheet field writes a property no
input row explains, or when an input row promises a control that no field
writes. So the two tables cannot drift apart, in either direction.

The count guard matters more here than the drift check, because a generator
paired with its own drift check agrees with itself exactly when it is wrong. So
the node set is derived **five times over four files in two modules** — the
`:app` enum, the `:catalog` enum, the ports factory, the context-defaults
`when`, and the config hierarchy — and generation stops unless all five agree.

### `CookbookRuntimeReachTest` — the verdicts are checked against the codec

The column saying whether a configuration field changes anything at run time is
the reason the document is worth reading, and it is the one claim a build-time
parser cannot settle: no static reading of a declaration reveals whether the
engine later reads the value.

So the check runs from the other side. The test mutates every field of every
node configuration, pushes it through the real `NodeConfigCodec`, observes which
properties of the stored node actually moved, and compares that against the
committed Markdown — a different file, produced by different code, read by a
different parser. A verdict claiming a field works when it does not fails the
unit suite; fixing the codec so an ignored field starts working fails it too,
until the document is regenerated, so the fix and its documentation land
together.

### `CookbookRecipeValidationTest` — the recipes still import

The five documents under [`docs/recipes/`](recipes/) are what the cookbook tells
a reader to download and import. They go through the **same parser the app's
import uses** and the graphs through the same `PipelineGraph.validate()` the
editor's validation bar runs — not a validator written alongside them. The test
also asserts nothing is dropped on parse, that every prompt variable is one the
app registers (read out of the Hilt module rather than a list in the test), that
every router's declared classes match its outgoing edge labels, and that each
recipe is linked from the cookbook so one cannot rot unread.

Both tests read files that are on no classpath — the published Markdown and the
recipe documents — so `app/build.gradle.kts` declares them as inputs of every
`Test` task, alongside the instrumented sources and the store metadata that have
the same problem. This is not a precaution: without those two lines a broken
recipe **passed `check` from a cached run**, and nothing else would have caught
it, since `verifyCookbookDocs` does not read the recipes at all. The cost is a
unit-test re-run when the documents change; the alternative is a guard that
answers from cache about the very edit it polices.

The generation logic is a pure string transform in `buildSrc` and is unit-tested
there (`./gradlew -p buildSrc test`). Every gate was observed failing before
being trusted: a removed `FIELD_REACH` entry, a node type dropped from the meta,
an edited verdict, and a broken recipe each fail with the cause named — the last
one twice, before and after the caching hole above was closed.

---

## Top-bar inset guard (`TopBarInsetGuardTest`)

The app draws edge to edge, and both the shell scaffold and every screen's own
`Scaffold` zero their window insets, so each screen owns its status-bar inset.
Material's `TopAppBar` applies it by itself; a bar laid out by hand does not.
Twice a hand-built bar drew its title under the clock — the Help screen's two
bars, then the Files selection bar — and **no rendering test can see it**:
Robolectric renders with no system insets, so the snapshot of the broken bar is
identical to the snapshot of the fixed one.

`TopBarInsetGuardTest` reads the production sources of `:app` and `:catalog` and
enforces three rules:

1. **`Scaffold` slots.** Every composable of the project called inside a
   `topBar = { … }` slot either shows inset evidence — a Material top app bar, or
   a `windowInsetsPadding` / `statusBarsPadding` / `safeDrawingPadding` /
   `systemBarsPadding` call — or is a container that only lays out a `content`
   slot. Checking *every* callee matters: the Files slot switched between a
   `TopAppBar` and a hand-built row, and only the first carried the inset.
2. **Bars outside a slot.** A bar placed at the top of a column by hand is
   invisible to rule 1, so each is listed with the owner of its inset — the bar
   itself, or the parent file that applies it. A renamed or deleted entry fails
   the test instead of checking nothing.
3. **Census.** Any composable named like a top bar (`…TopBar`, `…AppBar`,
   `…Toolbar`, `…SelectionBar`) must be covered by rule 1 or listed under rule 2.

**What it cannot catch:** a hand-built top bar outside a `Scaffold` slot whose
name does not look like a bar, and an inset applied to the wrong element — the
rules read evidence, not geometry.

### Observed failing

With the Files selection bar's inset removed, rule 1 reports exactly
`FilesSelectionBar`; with the Help list bar's inset removed, rule 2 reports
`HelpListBar`. Its first run also found the image viewer's top bar covered by
no rule — it insets itself, and is now listed.

---

## Screenshot baseline guard (`verifyRoborazziDebug`)

The `:catalog` unit tests render every design-system screen and component with
Roborazzi, and `check` always ran them — but it never *compared* a render with its
committed baseline under `catalog/src/test/snapshots/`. A screen could change and
every gate stayed green; the baselines were only as current as the last time
someone ran `verifyRoborazziDebug` by hand. `check` now depends on it, so the unit
tests run in verify mode and any pixel difference fails the build.

Two things make it a gate rather than a ritual:

- **The baselines are declared as test inputs.** They sit on no classpath, so
  without that declaration replacing a baseline left `testDebugUnitTest`
  `UP-TO-DATE` and the verification green — measured before the wiring.
- **It was measured on both platforms before it was made blocking.** On the
  development machine (macOS): 707 tests, 503 baselines, no difference, about a
  minute. On CI (Linux): 706 matched byte for byte; the settings hub's loading
  state — tiles drawn at half alpha — differed in 975 of 1,094,400 pixels, by at
  most 2/255 per channel, anti-aliasing no one can see.
- **Every capture uses one colour tolerance for that reason.**
  `KnotworkRoborazziOptions` (`catalog/src/test/…/KnotworkRoborazziOptions.kt`) raises
  Roborazzi's per-pixel colour distance from 0.007 to **0.02** — twice the measured
  noise (0.0096), two orders of magnitude below a visible change (a light baseline
  swapped for its dark twin: ~800,000 pixels up to 1.63 apart). No pixel shift and
  no share of differing pixels is allowed. Roborazzi takes the option per call, so
  `SnapshotComparisonOptionsGuardTest` fails a `captureRoboImage` that omits it.
  Re-recording the baselines on Linux was rejected: it would move the same noise to
  every local run on macOS.

To accept an intended change, re-record the affected snapshots with
`./gradlew :catalog:recordRoborazziDebug --tests '<test>'`, look at the new PNGs,
and commit them with the change that caused them. On a CI failure the diffs are
uploaded as the `roborazzi-report` artefact.

**What it cannot see:** anything Robolectric does not render — system insets
above all (see the top-bar inset guard) — and screens outside `:catalog`.

### Observed failing

With `foundations_light.png` replaced by the dark baseline, the run reports
exactly `FoundationsCatalogPageSnapshotTest > foundations_light` and fails; with
the file restored it passes. Before the baselines were declared as inputs, the
same replacement passed from the up-to-date check. With CI's Linux render of the
settings hub put in place of its baseline, the run passes at 0.02 and fails at
Roborazzi's default 0.007 — the tolerance absorbs exactly the observed noise. A
capture call with its options removed fails the guard.

---

## Bundled-document routing guard (`BundledDocumentationRoutingGuardTest`)

`openDocumentation` routes by the registry's `delivery`: a document bundled into
the app opens in the in-app reader, one on the web in the browser — **but only
when the caller passes a reader.** Without one, a bundled document falls back to
the browser. That fallback is legitimate for a caller with nowhere to navigate,
and it is how the onboarding Ready step sent its FAQ link — a bundled document,
on the step where the network may not be set up yet — to the browser while every
screen around it looked correct.

The guard reads every `openDocumentation(…)` call in `:app`'s production sources
and fails when a call without a reader can reach a bundled document:

- a call naming a `DocumentationLinks.ID_…` constant is resolved against the
  registry itself, so moving a document into the APK makes its readerless callers
  fail here rather than on a device;
- a call whose id is a variable must name, in the test's `DYNAMIC_ID_SOURCES`, the
  file every such id comes from (today the settings help catalogue), whose
  constants are resolved the same way; a variable-id caller missing from that list,
  or a listed one that no longer exists, fails too.

**What it cannot see:** a reader argument that is passed but navigates nowhere
useful — it checks that a reader is handed over, not where it goes.

### Observed failing

With the onboarding call's reader argument removed, the first test reports
`OnboardingScreen.kt` opening `[faq]` without a reader. With the settings help
catalogue pointed at the troubleshooting document, it reports `SettingsScreens.kt`.

---

## Settings help-text guards (`SettingsHelpCatalogTest`, `verifySettingsHelpDocs`)

Two guards, one rule: **a setting's meaning is written once**, in
`app/src/main/res/values/strings_settings_help.xml`, and everything else quotes
it.

The rule exists because the alternative was measured. Before it, one setting's
explanation lived in four places — the catalog row subtitle, the search-index
description, a second copy of the subtitle in the app resources, and the user
guide's prose — and closed testing found them already disagreeing. The
long-running-tasks row said "runs > 8 s", the search index said "runs long", the
guide said "exceeds the long-running threshold", and **no constant anywhere in
the code held any such number**. Prose kept in sync by review alone drifts, and a
reader cannot tell which copy is true.

### `SettingsHelpCatalogTest` — completeness and register

A Robolectric unit test, so it can resolve real string resources. It fails when:

- a row in `SettingsRegistry` has no decision in `SettingsHelpCatalog` (or a
  decision exists for a row that does not);
- an explained row's string resource is missing or resolves to blank text;
- an explanation exceeds **140 characters** — the ceiling measured against the
  200 % font-scale frame, past which the panel pushes the row it explains off
  the top of the screen;
- two rows are explained by the same sentence;
- an explanation uses a phrasing the copy standard rules out (`enables …`,
  `allows you to …`, `this setting …`, `when enabled …`) — each describes the
  control rather than what the reader will notice, which is the register that
  went unread.

Note what this fixes about its predecessor. `SettingsSearchCatalogTest` reads
like a completeness guard and is one on **names only**: `descRes` is nullable and
no assertion looks at it, so a description could be deleted and the build stayed
green. Here the decision is an explicit `SettingHelp.Text | SettingHelp.None`
with a recorded reason, which is what makes "every row decided" assertable at
all.

- an explanation exists for a row that no screen renders (the set of such rows is
  pinned by name, so it can only shrink — a *new* unreachable explanation fails
  the build).

Each failure mode was observed failing before the gate was wired in, on its own
assertion. Note the one thing this gate cannot see: it knows an anchor is
*declared*, not that the control emits both the glyph and the panel. A header
that shipped a glyph opening nothing passed it, and was caught by
`SettingsHintBehaviourTest` — which opens each bespoke control and demands the
panel — instead.

### `verifySettingsHelpDocs` — the guide quotes rather than restates

`:app:verifySettingsHelpDocs` fails `check` when the `AUTO-GEN:SETTINGS_HELP`
block of [`docs/user-guide.md`](user-guide.md) no longer matches the shipped
strings. Regenerate with:

```bash
./gradlew :app:generateSettingsHelpDocs
```

The generated table answers *"what does this option mean"*. The hand-written
prose around it answers *"what happens when you change it"* — the measured
timeouts, the retry behaviour, the ordering rules — and is never touched.

**One thing to know before editing this generator.** A doc generator paired with
its own drift check can delete a real row and stay green: if the parser stops
seeing a declaration, the block loses that row and the verify task then confirms
the shortened block matches. Guarding against it needs a count taken from a
**different file read by a different parser** — here, the help catalogue, whose
keys `SettingsHelpCatalogTest` independently pins to the registry. Comparing the
registry parse against itself does not work, and this generator shipped two
drafts that did exactly that: the first emitted **five rows for fifty-six
settings** with its own check green.

The logic is a pure string transform in `buildSrc`
([`SettingsHelpDocsGenerator`](../buildSrc/src/main/kotlin/app/knotwork/android/buildtools/SettingsHelpDocsGenerator.kt)),
unit-tested there including idempotence, drift detection, a missing string
resource, a display name that lives in another values file, and a crippled
parser. The gate was verified by editing a help string without regenerating, by
hand-editing the generated table, and by breaking the registry parser — each
failed with the cause named.

---

## File-map guard (`verifyFileMap`)

`:app:verifyFileMap` is wired into `check`. It regenerates the `AUTO-GEN` blocks
of four `FILE_MAP.md` files from the Kotlin source tree and fails when the
committed file differs, or when a documentation gap has grown.

### Why generated

The maps were kept in step by a post-write hook, a PR-checklist item and a
review step — all three of which name the map under `app/src/main`, while the
repository holds several. Measured immediately before this guard was added:

| Map | Files it named | Files that existed |
|---|---|---|
| `app/src/main/…/FILE_MAP.md` | 704 | 701 (3 unlisted, 1 entry pointing at a moved file) |
| `catalog/FILE_MAP.md` | 164 | 292 |
| `app/src/test/…/FILE_MAP.md` | 44 | 375 |
| `app/src/androidTest/…/FILE_MAP.md` | 16 | 60 |

A map covering an eighth of its directory does not read as stale — it reads as
complete, which is the more expensive failure. Deriving the structure turns
"the map is current" into a function of the repository, which is the standard a
verdict has to meet before it may block a build.

### What is generated, and what is not

The **structure** — which paths exist, how they nest, what order they are in —
is derived. Ordering is alphabetical with directories and files interleaved,
because the previous hand-chosen order could not be reproduced from the
repository and therefore could not be verified.

The **descriptions** are not derived. They carry design rationale no KDoc holds,
so each is carried across regenerations by path key. A path that has none is
seeded from the KDoc of the declaration the file is named for, and a path with
neither gets an explicit marker.

`./gradlew :app:generateFileMap` rewrites the blocks. Only Kotlin files appear
inside them; manifests, build scripts and the maps themselves stay in the
hand-written prose around the markers.

### The three failure modes it is built around

1. **A silently shorter map.** A generator that drops what it cannot parse
   produces a shorter file and a green build. So the generator reconciles rather
   than trusts: a description whose path still exists but which did not reach
   the output is a hard error, and a block that matches zero Kotlin files fails
   instead of rendering empty.
2. **A rename eating a paragraph.** A moved file's description would otherwise
   vanish inside a diff that reads as a routine map update. `generateFileMap`
   refuses to write, prints every description it would lose, and accepts
   `-PacceptFileMapDrops` once the author has confirmed the loss is intended.
3. **A plausible but wrong description.** Seeding from "the first KDoc in the
   file" produced, for one catalog composable, the KDoc of a private layout
   constant declared above it — a real sentence about the wrong thing, which
   nothing downstream can detect. The extractor therefore answers only from the
   declaration the file is named for, or from the file's single documented
   declaration, and otherwise returns nothing.

### The ratchet

`config/file-map/baseline.properties` records, per block, how many entries carry
the "no description" marker and how many Kotlin files offer no KDoc sentence to
seed one from.
`verifyFileMap` fails when a measured count exceeds its record.

`generateFileMap` **lowers** a number and never raises one, so an improvement is
recorded by re-running the task while a regression leaves the record where it
was and fails verification. Raising a number is a deliberate edit to the
committed file, reviewed like any other change.

Every `.undescribed` count is currently `0`: a new Kotlin file or package
without a description fails `check` until one is written.

### Why a typed task rather than an ad-hoc block

The four generate/verify pairs above are ad-hoc `doLast` blocks. Two consequences
this pair avoids, both measured rather than assumed:

- **Configuration cache.** An ad-hoc action that reads a build-script `val`
  captures the whole build script. On its own task graph,
  `./gradlew :app:verifyCookbookDocs --configuration-cache` fails with *"cannot
  serialize Gradle script object references"*; `:app:verifyFileMap` and
  `:app:generateFileMap` store and reuse an entry. (This says nothing about the
  whole build's compatibility — a task's problems only surface when that task is
  in the graph.)
- **Up-to-date checking.** A task with no declared output is never up to date, so
  an ad-hoc verification task re-runs on every `check`. `VerifyFileMapTask`
  declares a stamp output: observed `UP-TO-DATE` on a second consecutive run,
  re-running on a content change, and failing on an added file.

The action reads the tree **through** its declared `@InputFiles` property rather
than walking the filesystem, so Gradle cannot fingerprint one set of files while
the code reads another.

### Observed failing

Both gates were watched red before being trusted: a line deleted from the
unit-test map (drift, named the file and the fix), and a new undocumented Kotlin
file (ratchet, `app-main.undescribed: 1, recorded 0` and
`app-main.no-kdoc-seed: 28, recorded 27`). The pure logic is unit-tested in
`buildSrc` — `FileMapGeneratorTest`, `KdocSentenceExtractorTest`,
`FileMapBaselineTest` (`./gradlew -p buildSrc test`).

---

## Public documentation hygiene guard (`verifyDocsHygiene`)

`:app:verifyDocsHygiene` is a custom Gradle verification task, wired into
`check`, that scans the public-documentation contour for two defect classes
that are cheap to introduce and expensive once the repository is public.

**Scope.** It shares the `documentationFiles` collection with the link and
Mermaid gates: every top-level `*.md`, plus everything under `docs/`,
`.github/`, `app/`, `catalog/` and `gradle/`, plus `NOTICE`. Each scope is a
**glob**, not a hand-maintained allowlist, so a newly added public document is
guarded automatically.

It used to resolve a narrower set of its own — the repository root, `NOTICE`
and `docs/` — which left the generated `FILE_MAP.md` files outside every rule
it enforces. Those are exactly the documents most likely to acquire an internal
reference, because their descriptions are seeded from KDoc written for
maintainers, and by the time the gate was widened they had accumulated nine
violations across three files. The three maps are additionally pinned by exact
path in `requiredPrefixes`, so a glob that quietly stops matching them fails
the build instead of passing over nothing.

The defect classes:

1. **LLM tool-call wrapper artifacts** — stray fragments of an assistant's
   tool-call envelope (closing wrapper tags, or the opening of a markup /
   function-results block) that leak into a document when generated prose is
   pasted verbatim. These are never valid Markdown.
2. **References to internal-only documents** — links or mentions of the
   project's private planning files (the roadmap plan, the full description,
   the decision log, the phase backlog, the vision doc, the agent manifest)
   or of the internal `project_docs` tree. External readers cannot see
   them, so such a reference is always dangling.

**What this gate still does not catch**, recorded so the question is not
reopened: an internal file named by its **bare basename** with no directory
(`node-specs.md`), and any reference that lives in a KDoc comment which never
reaches a generated map. Scanning all KDoc rather than the first sentence, and
scanning `CHANGELOG.md`, are both deliberately out of scope — the first because
the gate would then police prose that no public reader ever sees, the second
because its past entries legitimately name internal documents as they were
called at the time.

Two families are deliberately **excluded** from the glob: `CHANGELOG.md`
(a historical journal whose past entries legitimately name internal documents
as they were called at the time — rewriting history to satisfy a lint rule
would be worse than the dangling reference) and the untracked, internal
`CLAUDE` agent-manifest family, which deliberately references the private
planning docs. Internal-document filenames are matched only at a path/word
boundary, so a longer name that merely contains one (for example an archive
copy) does not trip the guard.

The scanning logic is a pure `Map<path, content> -> List<Violation>`
transform in `buildSrc`
([`DocsHygieneChecker`](../buildSrc/src/main/kotlin/app/knotwork/android/buildtools/DocsHygieneChecker.kt)),
so it is unit-tested there (`./gradlew -p buildSrc test`) with a fixture for
every forbidden token and a clean-input case. The forbidden tokens are
assembled from fragments at runtime so that neither the scanner's own source
nor this document — which describes the tokens in prose — matches itself.

---

## Documentation link guards (`verifyDocLinks`, `reportExternalDocLinks`)

Two tasks over the same links, with deliberately different powers.

**`:app:verifyDocLinks` is a gate.** A relative path or an `#anchor` is a claim
about *this repository*: its verdict is a function of the commit under review
and nothing else, so a dead one is a defect the build can refuse. It resolves
every internal link across the whole Markdown set — the top-level documents,
`docs/`, `.github/`, the `FILE_MAP.md` family and `gradle/` — and fails naming
the file, the line and the target. Today that is more than 400 internal links
across 35 Markdown files.

**`:app:reportExternalDocLinks` is a report, and is not part of `check`.** An
`http` link is a claim about somebody else's server; it can turn red while the
repository does not change at all. Gating on it would make merges depend on
third-party uptime — the same reasoning that keeps the dependency
version-freshness checks at informational severity. The task probes every
distinct URL (`HEAD`, falling back to `GET`), writes
`app/build/reports/docs-links/external-links.md`, and never fails the build.
[`.github/workflows/docs-links.yml`](../.github/workflows/docs-links.yml) runs
it weekly and on demand, and publishes the report to the job summary; it must
never be added to branch protection's required checks.

Both read the links through one extractor
([`MarkdownLinks`](../buildSrc/src/main/kotlin/app/knotwork/android/buildtools/MarkdownLinks.kt)),
so the gate and the report cannot end up disagreeing about what a link is.

### The two ways a link checker reports zero and is wrong

Both were met while writing one for this repository, and both are closed here:

1. **Choosing inputs from the Git index.** A file set read from `git ls-files`
   cannot see the files the branch under review is *adding*. Such a checker
   validated 32 files and reported `0 broken` while the roughly 60 links in a
   brand-new FAQ had never been read. The Gradle task takes its file set from
   declared source trees instead, and the falsification below was repeated
   inside a brand-new untracked file for exactly this reason.
2. **Getting GitHub's anchor rule wrong.** GitHub strips punctuation *in place*
   and then replaces **each remaining space** with a hyphen — so a heading whose
   words are separated by punctuation keeps both surrounding spaces and
   slugifies with a **double** hyphen. Collapsing runs of whitespace instead
   reports a perfectly valid link as broken.

The mirror-image failure — a scan that passes by covering nothing — is closed
by `requiredPrefixes`: each declared documentation root must contribute at
least one document, which is self-maintaining where a pinned file count would
rot. The untracked `CLAUDE` manifest family is excluded so the gate reads the
same corpus locally and in CI.

### What it does not check

Shortcut and collapsed reference usages (`[label]`, `[label][]`) are not
resolved — `CHANGELOG.md` is full of them, and they are covered only in as much
as their *definitions* are checked. A code span opened on one line and closed on
the next keeps its links visible to the scanner. Anchors into a Markdown file
outside the scanned set are reported rather than skipped: that would mean the
scan has a hole, which is a finding about the gate itself.

### Why the task is untracked

`verifyDocLinks` declares no output and runs on every `check`. Its verdict
depends on files it cannot declare as inputs — a link may point at any path in
the repository, and the defect it exists to catch is precisely that such a path
stopped existing. Declaring the whole working tree would hash 138 MB to answer
a question that costs milliseconds; declaring only the documents would let the
task report a **cached pass** over a target deleted after it last ran. Running
every time is the honest option, and it costs a fraction of a second.

### Observed failing

Watched red before being trusted, in three shapes: a link to a file that does
not exist, a link to an anchor that does not exist, and both together **inside a
brand-new untracked file** — the case the Git-index failure mode above would
have missed. The pure logic is unit-tested in `buildSrc` (`MarkdownLinksTest`,
`DocLinkCheckerTest`, `ExternalLinkReportTest`).

---

## Bundled documentation guard (`verifyBundledDocs`)

`:app:verifyBundledDocs` is wired into `check`. It guards the documents that
ship **inside** the APK — today `docs/faq.md` and `docs/troubleshooting.md`,
which the registry marks `BUNDLED`.

`./gradlew :app:syncBundledDocs` writes the copies into
`app/src/main/assets/docs/` together with a generated `index.json`, and the
verification task fails on drift. Both are committed, like every other generated
artefact here.

### Why a document has to earn being bundled

The in-app renderer is not GitHub. It drops what it does not implement
**silently** — no placeholder, no error — so a document that looks right in a
browser can reach a reader with paragraphs missing and nothing on screen to
suggest anything is absent. The gate turns that into rules:

1. **No Mermaid blocks.** There is no diagram support, so a diagram arrives as a
   fenced block of its own source.
2. **No images.** Nothing resolves a relative image path inside the APK.
3. **No HTML.** Unsupported upstream by design; an HTML node falls through the
   renderer and is dropped. This is the rule that keeps `docs/cookbook.md` on
   the web — its generated node-reference tables carry 14 `<br>` tags, each one
   welding a caption onto a code sample when dropped.
4. **Every relative link resolves** — to a heading in the same document, to
   another bundled document, or to a repository file that exists. Offline there
   is no address bar to recover with.
5. **Every anchor a link targets is unique**, for the reason spelled out under
   `verifyDocumentationLinks`: a heading written twice yields two anchors that
   both keep working.

The input set is the Markdown under `docs/` **plus the repository's root
Markdown**, because a bundled document links to `../SECURITY.md` and
`../PRIVACY.md`. Declaring only the `docs` tree would resolve those against
files the task never declared, and stay green after one was deleted.

### Why `verifyDocLinks` skips the copies

`app/src/main/assets/docs/` is excluded from `verifyDocLinks`. That gate
resolves a relative link against the file's own directory, and the copies are
deliberately a *subset* — `user-guide.md` is not beside them and never will be.
Checking them there as well would not be stricter, it would be wrong. Their
links are checked here instead, against the real repository.

### What the index is for, and why the app resolves nothing

The renderer has no heading anchors and no path resolution, and both answers are
needed at run time. Implementing them in `app` would mean a second GitHub slug
algorithm and a second path resolver — with the build's copy being the one a
gate verifies and the app's being the one a user gets. So `syncBundledDocs`
ships the answers instead: `index.json` maps each anchor to the **character
offset** of its heading, and each raw link target to what the reader should do
with it. The app performs a lookup, never a resolution, and a lookup that misses
is a defect in the build rather than a case for the app to guess at.

One trap worth recording: the offsets are counted over the **original** lines
while headings are recognised in the **masked** ones. Masking blanks a fenced
block's lines, which preserves their count — that is what makes the two readings
line up — but not their lengths, so an offset taken from masked text would drift
by the width of every code block above it.

## In-app documentation link guard (`verifyDocumentationLinks`)

`:app:verifyDocumentationLinks` is wired into `check`. It guards the registry of
documents the **app** links out to — the About screen's Documentation card, the
onboarding Ready step, the Tools and Triggers top bars, the editor's overflow,
and the external-automation settings hint.

It fails on two things: the committed registry drifting from the build-side
list, and any entry naming a document or heading that no longer resolves.

### Why the list lives in `buildSrc` and the app's copy is generated

Three readers need the same list: the app (which builds the URLs), this gate
(which resolves them), and the bundled-documentation sync. `buildSrc` cannot see
`app` code, so only one of the three can hold the list natively. The build holds
it (`DocumentationLinkRegistry`) and generates the app's copy
(`domain/constants/DocumentationLinks.kt`), which is committed like any other
generated artefact in this repository.

That is the only arrangement in which the list **and** the GitHub slug algorithm
(`MarkdownLinks.slug`) each have exactly one owner. The alternative — parsing the
Kotlin object back out of `app` with regular expressions — was rejected on the
precedent of `BrowserEditorConstantsGenerator`, which skips a record of
unexpected shape *silently*: a typo there produces a missing gate rather than a
failure.

`./gradlew :app:generateDocumentationLinks` rewrites the copy. It refuses to
write while any entry fails to resolve, so a broken registry cannot reach the
app and be reported afterwards against a file already committed.

Note the consequence of the generated entries carrying per-document counts (the
Help list shows them): **editing any document under `docs/` can make the
committed registry stale**, and the gate will say so. Regenerating is the same
one command, and it is cheap; the alternative was a hand-maintained number
beside a document that grows, which is the defect this repository keeps
removing.

### Why this one is typed and cacheable while `verifyDocLinks` is not

`verifyDocLinks` is deliberately untracked: a documentation link may address any
path in the repository, so its inputs cannot be declared, and a cached pass
could hide a target deleted since the last run.

This gate constrains every target to a Markdown file under `docs/` — a rule it
enforces rather than assumes. That makes the declared input set *complete*:
deleting a target changes the task's fingerprint. So the task carries typed
inputs and a stamp output, and `check` skips it while nothing it reads has
changed, which is what keeps a no-op build from growing by another always-run
task.

### The rule that "the anchor exists" cannot express

A heading written twice yields two anchors, `slug` and `slug-1`, and **both
keep resolving forever**. Reordering those two sections therefore moves what
each anchor points at without breaking either — a link that is green and wrong.
`docs/user-guide.md` carries a live instance: "Background & triggers" appears
twice.

So the rule is uniqueness, not existence: a registry anchor must be produced by
exactly one heading, and an ordinal `-N` anchor is refused outright. The count
comes from `MarkdownLinks.headingSlugCounts`, not from inspecting the anchor's
shape — `step-1` from a heading "Step 1" is a legitimate anchor and
indistinguishable from an ordinal suffix by spelling alone.

### Release builds link at the tag, legal documents do not

`BuildConfig.DOCS_REF` is `v<versionName>` for a release build and `main`
otherwise, decided by build **type** in `app/build.gradle.kts` (the rule itself
is `DocumentationRef`, unit-tested in `buildSrc` where both branches are
ordinary arguments — an app-side test only ever observes the debug variant).
Reading `versionName` for its suffix instead would make an unrelated string
decide where the links point.

`PRIVACY.md` is exempt and stays on `main`: what binds a user is the current
edition, and that URL is also the one filed with the app stores. The exemption
is asserted in `AboutLinksTest` so it cannot be tidied into the version-pinned
rule.

The tag is cut *after* the build, so no repository gate can confirm the link
resolves on GitHub. `docs/release.md` carries that as a post-publication
checklist item.

### Observed failing

Watched red before being trusted, in four shapes: a renamed heading
(`triggers` → no heading produces it), an anchor whose heading text appears
twice (reported with the count), an ordinal `-1` anchor, and a committed copy
left stale after the build-side list changed. The pure logic is unit-tested in
`buildSrc` (`DocumentationLinkRegistryTest`, `DocumentationRefTest`,
`MarkdownLinksTest`), each rule against a document written to break it.

---

## Mermaid diagram guard (`verifyMermaidDiagrams`)

`:app:verifyMermaidDiagrams` fails the build when an embedded Mermaid diagram
is structurally broken. A broken diagram does not degrade politely — GitHub
renders a red error box where the architecture picture should be — and nothing
in review reliably catches it, because a diagram is read as prose.

**It is not a Mermaid parser, and the difference matters.** A real parse needs
Mermaid's own grammar, which means a Node toolchain and a network install on the
critical path of every build. What runs instead is a set of structural rules:

1. The block is not empty, and its first significant line declares a known
   diagram type.
2. A `flowchart` / `graph` direction, when written, is one of `TB` `TD` `BT`
   `RL` `LR`.
3. Block openers balance their `end` — `subgraph` in flowcharts, and `loop` /
   `alt` / `opt` / `par` / `critical` / `break` / `rect` / `box` in sequence
   diagrams.
4. In a flowchart, brackets and quotes balance on each line.
5. In a flowchart, an unquoted node label holds no parenthesis.
6. In a flowchart, every arrow is a real arrow — a bare `->` is not one.

**Every rule was written against the real parser rather than from memory**, and
that step changed the rule set: three plausible rules were *dropped* because
Mermaid accepts what they would have rejected. A `flowchart` with no direction
is valid; so are unbalanced brackets and quotes in the free text of a
`sequenceDiagram` message or a `stateDiagram` note, which is why rules 4–6 are
scoped to flowcharts; and the asymmetric `id>text]` node shape has brackets
that deliberately do not pair. A gate that fails valid documents is worse than
no gate, because it teaches everyone to distrust it.

The list of known diagram types is a deliberate allowlist: adding a diagram of
a type nobody has used here yet means adding it to
[`MermaidBlockChecker`](../buildSrc/src/main/kotlin/app/knotwork/android/buildtools/MermaidBlockChecker.kt),
and the failure message says so.

### Observed failing

Four mutations of the real diagrams in `docs/architecture.md` — an unknown
diagram type, a `subgraph` missing its `end`, an unquoted parenthesis in a node
label, and a bare `->` arrow. Each turned the gate red, and each was
independently confirmed to be rejected by the actual Mermaid parser, while
every committed diagram passes both. That cross-check is the point: a structural
checker is only worth having while its rules agree with the renderer, and it is
the one claim this guard cannot make about itself.

---

## Version-number agreement guard (`verifyVersionSources`)

`versionName` in [`app/build.gradle.kts`](../app/build.gradle.kts) is the single
source of truth for the build — the F-Droid recipe builds a tag with no Gradle
properties injected and must still get the right number. The problem is that the
number is *repeated*, for humans, in places no build step reads:

- the version badge in `README.md`;
- the *Pre-release notice* sentence in `README.md` ("currently at **version X**");
- the topmost released heading of `CHANGELOG.md`;
- the `[Unreleased]` compare link at the foot of `CHANGELOG.md`;
- the link definition of that topmost release, which names the tag it shipped as;
- every version in `SECURITY.md` — its prose, the line it says fixes land on,
  and both cells of the supported-versions table;
- the pre-release **line** named at the top of `docs/roadmap.md`, which says
  which release the "where the project is today" list describes.

Each is edited by hand at release time and nothing noticed when one was missed.
The release checklist did not even mention the badge — which is the version
number a bug reporter quotes. `:app:verifyVersionSources` compares them all
against the declared `versionName` and fails naming both values and the file to
edit. A source it cannot *find* is a failure too: a checker that silently finds
nothing to compare passes everything.

**The last three were added a release after the guard shipped, and the reason is
the point.** The first version held four of the seven hand-written copies a
survey of the repository had already counted — and the very next release cut duly
left `SECURITY.md` at the previous version, in its prose *and* in its
supported-versions table, where a stale number tells a reporter their release is
unsupported. A rule covering four of seven does not merely miss three: it reads
as a closed subject, which is worse than no rule, because nobody greps a
question that looks answered.

`SECURITY.md` is checked **wholesale** — every version-like token in it must be
the shipping version or its minor line (`X.Y.Z` or `X.Y.x`). That is a stronger
claim than one pattern per sentence, and it is true of this document. It is not
true of `README.md`, which legitimately discusses older releases (the one-time
signing change at `0.7.0`), so the README is matched by named patterns only.
Naming a historic version in `SECURITY.md` therefore means revisiting this rule
on purpose, which is the intended cost.

Two documents name the *line* rather than the release — the supported-versions
table and the roadmap's opening sentence — so those are compared against
`X.Y.x`, which is what makes a patch release pass without touching either.

Deliberately outside the rule: `PRIVACY.md` says the policy applies "from
version 0.7.1 onward", which is a dated statement about the past and must not
follow the build; and `README.md`'s account of the one-time signing change
names the releases it happened at. Both are versions that are *supposed* to go
out of date.

`versionCode` is deliberately out of scope. Its agreement with the store
changelog file is already held by `StoreMetadataTest`, and a second, separately
written opinion about the same fact is how two guards start disagreeing.

### Observed failing

Five mutations, one per source plus the whole-release case: a stale README
badge, a changelog heading ahead of the build, a stale `[Unreleased]` compare
link, a release heading whose link definition was deleted, and a `versionName`
bumped with nothing else — which reported all three remaining sources at once.

The sources added later were observed failing on **a real drift rather than a
mutation**: with `versionName` cut to the shipping version and `SECURITY.md`
untouched, the extended gate failed naming both stale copies, which is exactly
the drift the previous release had shipped unnoticed. It was then fixed and the
gate went green. The roadmap line was found the same way one step later — in the
diff of the change that added the other two — and is the reason this list is
worth keeping honest rather than declaring finished: a rule that has just been
widened is exactly when its author is most sure it is complete.

The pure logic is unit-tested in `buildSrc` (`VersionSourcesCheckerTest`), with
a case per source, one asserting that the supported *line* (`X.Y.x`) is accepted
rather than read as a stale version, and one asserting that a `SECURITY.md`
naming no version at all fails.

---

## Store-listing length guard (`verifyStoreListingLengths`)

The Play Console **rejects** an upload whose listing fields are over Google's
character limits. Without a gate that rejection lands in a web form, after the
merge, after the release workflow has built and signed an artefact — the one
moment at which it is most expensive and least expected.

`:app:verifyStoreListingLengths` measures every file under
[`fastlane/metadata`](../fastlane/metadata) against the limit for its field:
30 code points for the app title, 80 for the short description, 4 000 for the
full description, and 500 for a release's "What's new" text. Counting is in
**Unicode code points**, matching how the Console counts, and a single trailing
newline is ignored because every file in the tree ends with one and Play does
not.

The changelog ceiling is held apart from the other three because those files are
named after the `versionCode` rather than after the field, so they are matched
by their parent directory instead of their file name.

**The margins are why this is a gate rather than a line in the release
checklist.** When it was written, the English full description sat **5**
characters under its ceiling, the English title **4**, and one release note
**2**. One added word breaks any of the three, and nothing in the repository
could see it.

Not to be confused with `StoreMetadataTest`, which checks that a shipping
`versionCode` *has* release notes at all, in every locale. Length and existence
are two questions and are answered in two places on purpose.

### Observed failing

The pure logic is unit-tested in `buildSrc` (`StoreListingLengthCheckerTest`)
with a case per behaviour that decides a verdict: a field exactly **at** its
limit passes and one character **over** fails — the boundary is where an
off-by-one in a length rule actually lives — plus the trailing newline, counting
outside the basic plane, a changelog matched by its directory, every field over
at once, and a non-listing file ignored. The committed listing passes.

---

## Forbidden-vocabulary guard (`verifyForbiddenVocabulary`)

`:app:verifyForbiddenVocabulary` fails the build when a public text file
carries either of two families of words that must never reach an outside
reader or a user of the app.

1. **The product's pre-rename name**, in every form it was written in: the
   three-word prose name (any capitalisation, including one wrapped across a
   KDoc line), the run-together identifier used for theme and tag names, the
   title-case descriptor the project once used, and the old root package.
2. **Internal planning numbering**: a phase number of two or more digits
   (written with a space, a hyphen or nothing before the digits), a task
   fraction, and a phase branch path. That vocabulary belongs to private
   planning documents; a public reader can look none of it up.

The gate does not spell the forbidden forms out here, because this document is
inside its own scope — the scanner assembles its patterns from fragments for the
same reason.

### Why a gate

The product was renamed, and the old name went on greeting every user from the
onboarding title for three months — through a pre-launch clean-up, a store
listing that promised the new name, and a Google Play release. No gate looked
for it; it was found by inspecting a frame of a published demo video. The first
run of this gate then found the same name in the GitHub issue-template chooser,
in the app theme's resource and composable names, and in a wake-lock tag —
which Android vitals lists by name in the Play Console.

Planning numbering had the same history in a different shape. It was removed
once by a search that matched a single spelling, and a later search with the
same pattern reported zero — while the hyphenated, lower-case and run-together
spellings it could not see went on accumulating in KDoc and test comments.

### Scope

Every public **text** file, selected by glob rather than by a list, so a newly
added file is guarded without anyone remembering to add it: source, resources
and bundled assets of `:app` and `:catalog`, build logic, the probe app,
documentation, store metadata, CI configuration, and the repository root. Each
root must contribute at least one file, so a directory that moves or a glob that
stops matching fails the build instead of quietly shrinking what is guarded. The
file set is a declared input, never a Git query, so a file the branch under
review is adding is scanned too.

Deliberate exclusions:

- **`CHANGELOG.md`** — a historical journal whose past entries name the old
  identifiers as they were at the time. Rewriting history to satisfy a gate
  would be the wrong repair.
- **`buildSrc/src/test`** — the scanner's fixtures must spell the forbidden forms.
- **`app/src/main/assets/docs`** — a generated copy of `docs/`, which is scanned
  at its source rather than twice.

**Not forbidden, on purpose:** the lower-case hyphenated project codename that
names the Gradle root project and the Firebase project. Neither is visible to a
user, and a Firebase project id cannot be renamed.

**Not caught, on purpose:** a single-digit phase. Integration tests label the
steps of a multi-stage scenario "Phase 1" to "Phase 4", which is not planning
vocabulary. Every real planning phase has two digits, so requiring two digits
separates the two cleanly; a historical single-digit reference would slip
through, and none exists.

**Caught although legitimate, knowingly:** a literal progress label written as
the word "task" followed by digits and a slash — say, in a preview fixture for a
decomposition queue. Nothing in the tree has that shape; the production strings
build such a label from placeholders, which the rule does not match. If a
fixture needs one, write "step" or use the placeholder form rather than
weakening the rule.

The task is typed and cacheable, so a no-op `check` skips it. It reads files
that five generators rewrite (the file maps, the browser editor, the settings
reference, the automation reference and the cookbook), and declares
`mustRunAfter` on each of them — the ordering the combined
`generate… check` invocation needs.

### Observed failing

The first run over the tree reported **52** hits: **10** of the pre-rename name
across eight files, and **42** planning numbers across 27 files — two generated
file maps, KDoc in the engine, the MCP client and the task queue, test comments,
a string-resource comment, one test name, and two comments in the browser
editor. All were rewritten to say what the code does or what was observed,
without the number. The gate was then observed failing again with the
onboarding title string reverted to its pre-rename value. The pure logic is
unit-tested in `buildSrc` (`ForbiddenVocabularyCheckerTest`).

---

## Orphaned-KDoc guard (`verifyNoOrphanedKdoc`)

Kotlin attaches a KDoc block to the declaration that follows it, and only to
that one. When two blocks end up back to back with no declaration between them,
the first documents nothing: invisible to Dokka, invisible to the IDE, and —
worse — it *reads* as documentation of the declaration below, which already has
its own.

`:app:verifyNoOrphanedKdoc` and `:catalog:verifyNoOrphanedKdoc` fail the build
on such a block, naming the file, the line and the block's first content line.

**A file-level block is not a violation.** A file whose opening block explains
the file as a whole, followed by the first declaration's own block, is a
deliberate shape that several files here use. It is told apart structurally
rather than by heuristics: a file-level block is one with nothing but `package`,
`import`, file annotations, comments and blank lines before it.

`:app` scans `androidTest` too, deliberately — `check` neither runs nor compiles
that source set, so a block orphaned there would go unseen until the separate
instrumented job, and this check only reads text. `:catalog` is covered because
it is a design system: its declarations carry the longest KDoc in the
repository, and components are routinely inserted next to their neighbours,
which is exactly the edit that produces an orphan.

### Observed failing

**Four** real instances existed in the tree when this check was written, all the
same editing accident and none of which had looked like one in review: a new
function inserted **between** an existing KDoc and the function it described.
The old doc stayed where it was, the new function kept its own, and the original
function silently lost its documentation — in one case the entire migration
policy of the Room database. Every individual line of those diffs was correct.
The pure logic is unit-tested in `buildSrc` (`OrphanedKdocCheckerTest`).

---

## Dialog inventory guard (`verifyDialogInventory`)

Dialog and sheet **bodies** belong in `:catalog`, where the Roborazzi baselines
can reach them; `:app` *hosts* them, because the host owns scrim and IME
behaviour. `:app:verifyDialogInventory` fails the build when a file in `:app`
calls `AlertDialog`, `BasicAlertDialog` or `ModalBottomSheet` without an entry
accounting for it.

The check cannot tell a sanctioned host from a dialog composed in place by
parsing alone, so it does not try: it reports every call site, and each one is
answered once in an explicit allowlist in
[`app/build.gradle.kts`](../app/build.gradle.kts) that records **why** — host of
a named catalog body, deliberate deviation, or known remaining work. That list
is the deliverable. A file that has to say what it is doing beats a rule that
quietly decides for itself.

The allowlist is audited in both directions: an entry that no longer matches any
file fails just as loudly as an unaccounted dialog. An allowlist that outlives
what it excused is how a gate rots into decoration, and it is the half such a
check usually forgets.

A call inside a comment is ignored — this codebase discusses these composables
in prose constantly, and a check that fired on its own documentation would be
switched off within the day.

### Observed failing

The inventory this gate replaced was wrong **twice**, which is the argument for
deriving the list from the sources instead of writing it. The first hand pass
listed seven screens with no catalog twin; three already had one, under a file
named after the feature rather than after the screen. The second pass missed the
dialogs entirely, because it matched `*Screen(` composables — so
`SaveAsPresetDialog` was never counted, never covered, and shipped with its
selected category chip visually indistinguishable from the unselected ones until
somebody ran the app by hand. On its own first run the gate named two sheets a
hand inventory had already declared complete. The pure logic is unit-tested in
`buildSrc` (`DialogInventoryCheckerTest`).

---

## R8 keep-rule guard (`verify<Variant>KeepRules`)

Some keep rules protect code whose failure mode **no test in this gate can
see**, because the gate is JVM-only and debug builds are not minified. The
motivating case: MediaPipe's `tasks-text` pulls in
`com.google.common.flogger`, which resolves a log site by *walking the call
stack* for a frame belonging to flogger itself. When R8 renames or inlines
those frames away, the first `TextEmbedder.createFromOptions` call fails with
`IllegalStateException: no caller found on the stack for: …` — that is the
on-device embedding path, so in a minified build every message that touches
long-term memory kills the process, while every debug build stays green.

The one durable artefact that *can* see it is the mapping R8 emits: a live
`-keep class … { *; }` rule leaves the package **identity-mapped**
(`a.b.C -> a.b.C`). `:app:verify<Variant>KeepRules` asserts exactly that for
every protected package after each `release` assemble (it is wired with
`finalizedBy`, so it runs as part of the release build, not of `check` —
`check` never produces a mapping). A package that comes back renamed **or
missing entirely** fails the build; the missing case is treated as a failure
on purpose, since "all zero classes are identity-mapped" would be a vacuous
pass hiding a dropped dependency.

The parsing is a pure `String -> List<Violation>` transform in `buildSrc`
([`R8MappingChecker`](../buildSrc/src/main/kotlin/app/knotwork/android/buildtools/R8MappingChecker.kt)),
unit-tested there (`./gradlew -p buildSrc test`) against an identity-mapped
fixture, an obfuscated one, an absent-package one, and a member-line shape
that must not be misparsed as a class. Protected packages are listed in
`r8ProtectedPackages` in `app/build.gradle.kts`; add to that list whenever a
new keep rule exists to satisfy a stack-walking or name-reflecting library.

---

## Kover — coverage measurement & threshold

Plugin: `org.jetbrains.kotlinx.kover` `0.9.8`. Strict mode: a single
aggregate rule enforces **≥ 75 % LINE coverage** over the unit-testable
surface (raised from 70 %).

Kover 0.9.x does not support per-rule filters (that landed in 0.10+), so
filtering is done globally via `reports.filters.excludes`. The excluded
class set covers:

- Generated code (Hilt factories, Room `*_Impl`, AppDatabase, AutoMigrations,
  ComposableSingletons, `BuildConfig`, BR, DataBinding).
- All `*Preview.kt` files and `@Preview`-annotated functions.
- Hilt DI modules (`app.knotwork.android.di.*`).
- `App.kt` and `MainActivity` — Android-runtime-bound bootstrap.
- All `*Screen` Composables and `presentation.ui.*.components.*`, plus the
  sub-packages `presentation.ui.navigation.*`,
  `presentation.ui.about.AboutScreen*` / `AboutAcknowledgments*`,
  `presentation.ui.more.MoreScreen*`, and
  `presentation.ui.settings.provider.{ProviderPickerScreen, ProviderDetailScreen}*`.
- `presentation.theme/state.*` — declarative Compose constants.
- `data.tools.local.*` Android-runtime glue (AppFunctions service, search HTTP,
  delegate-task), including the sub-package
  `data.tools.local.appfunctions.*`.
- `data.local.dao.*` interfaces (impls are auto-excluded via the `*_Impl` pattern).
- `data.logging.CrashlyticsTimberTree*` — Firebase Crashlytics Timber bridge.

After these exclusions the aggregate runs at ~77.6 %;
[`coverage-baseline.md`](coverage-baseline.md) keeps the per-package
breakdown and the (informational) per-package targets. The 75 % floor
protects against regression with ~2.6 pp of headroom for in-flight
refactors.

Verification is wired into the `check` lifecycle:

```kotlin
tasks.named("check") { dependsOn("koverVerifyFullDebug") }
```

**Local commands**:

```bash
./gradlew :app:koverVerifyFullDebug      # threshold check
./gradlew :app:koverHtmlReportFullDebug  # drill-down per package/file
./gradlew :app:koverLog              # one-liner aggregate %
```

**Reports**:
- `app/build/reports/kover/htmlFullDebug/index.html`
- `app/build/reports/kover/reportFullDebug.xml`

---

## CI

The required job is defined in `.github/workflows/check.yml`. The workflow runs
`./gradlew check` on every `pull_request → main` and every `push` to `main`
(plus a manual `workflow_dispatch` trigger), uploads each report set —
detekt / ktlint / unit-test / Kover / Roborazzi diffs — as a downloadable
artifact on failure, and is configured with `concurrency.cancel-in-progress` so
a new push supersedes any older run on the same branch. The **lint** report is
the exception: it is uploaded on every run, green ones included, because it
carries the informational dependency-drift findings described above. The same
findings are also rendered into the job summary as an *Informational lint
findings* table, so the answer to "what is out of date?" needs no artifact
download.

The same job also compiles the instrumented source set for both flavours, in a
Gradle invocation of its own. `check` does not compile `androidTest`, so
without that step an instrumented test could stop building while every gate
stayed green. Whether those tests *pass* is decided elsewhere — by
`.github/workflows/instrumented.yml`, which runs them on an emulator matrix and
is deliberately kept out of the required `check` workflow (and therefore out of
the release path) because an emulator's verdict is not purely a function of the
repository. See [`testing.md`](testing.md) § *The instrumented gate*.

One workflow is deliberately **not** a gate.
[`.github/workflows/docs-links.yml`](../.github/workflows/docs-links.yml) probes
the external `http` links of the documentation weekly and on demand, publishes
the result to the job summary, and never fails: an external link's verdict
belongs to somebody else's server, so it must not sit among the conditions for
merging. It must never be added to branch protection's required checks — and
note that a scheduled workflow only runs from the default branch, and only once
its definition has reached it.

The same workflow is also exposed as a reusable one (`workflow_call`) and is
called as the first job of `.github/workflows/release.yml`, so a release cannot
be built against a definition of "green" that has drifted from the one pull
requests are measured by. `release.yml` adds the checks that only make sense on
a release build — the tag ↔ `versionName` agreement, `verify<Variant>KeepRules`
on both flavours, and a signature check of every published artefact against the
expected certificate fingerprint. The release procedure itself is documented in
[`release.md`](release.md) §9.

---

## What this gate does **not** do

- It does not collect instrumented (androidTest / Compose UI test)
  **coverage** — `*Screen.kt` Composables remain outside the Kover scope. The
  instrumented tests themselves do run in CI, in their own workflow; their
  results simply do not feed the coverage number.
- It does not run the `release` variant — lint and tests target `debug`. R8
  regressions are therefore invisible here; the release-only guard above
  (`verify<Variant>KeepRules`) runs as part of the release assemble instead,
  which on CI means `release.yml` rather than this workflow.
- It does not perform dependency-vulnerability scanning — that is a
  separate workstream.
