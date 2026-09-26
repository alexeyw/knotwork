# Test-Coverage Baseline

Living record of the project's unit-test LINE coverage and the gate
configuration that backs it. Updated at the end of each phase that touches
coverage materially.

## Snapshot — 2026-05-26 (current)

| Field        | Value                                                          |
|--------------|----------------------------------------------------------------|
| Date         | 2026-05-26                                                     |
| Branch base  | integration branch at the 2026-05-26 refresh                   |
| Kover        | 0.9.8 (latest on the Gradle Plugin Portal)                     |
| Variant      | `debug` (Android default unit-test variant)                    |
| Test command | `./gradlew :app:testFullDebugUnitTest`                             |
| Build gate   | aggregate LINE ≥ **75 %** (raised 70 % → 75 % in this task)    |

`./gradlew :app:koverLog` headline figure: **`application line coverage: 77.62 %`**
(5 968 / 7 689 lines after exclusions).

> **Coverage refresh (2026-06-02):** aggregate LINE is now
> **79.05 %** (7 816 / 9 888 lines after exclusions) — the line total grew with
> later feature work, and the refresh closed the `presentation.ui.more`
> (→ 100 %) and `presentation.ui.settings.provider` (→ 100 %) gaps and raised
> `presentation.ui.prompts` to 67.9 %. The three rows below carry an
> `↑ 2026-06-02` note; the remaining per-package figures are still the
> earlier measurement and will be re-measured wholesale at the next coverage
> refresh.
>
> **Spot re-measurement (2026-09-20).** Three packages were re-measured while
> the issue tracker was being seeded, and two of their rows had gone wrong in
> opposite directions: `presentation.ui.pipeline.editor.config` had closed its
> gap unaided (52.40 % → 90.15 %), and `presentation.ui.prompts` had fallen
> (67.91 % → 43.67 %) by growing faster than its tests. `data.prompt` was
> unchanged at 49.25 %. The lesson for the next reader of this file is the
> second one: a per-package figure here is a claim about the tree on the day it
> was taken, and a package that is *growing* can fall while its covered line
> count rises. Re-measure before quoting a row — `./gradlew
> :app:koverXmlReportFullDebug` takes about two minutes.

### Historical snapshots

| Milestone               | Date       | Aggregate LINE | Gate  |
|-------------------------|------------|----------------|-------|
| Initial measurement     | 2026-05-10 | 45.3 % (raw, no exclusions yet) | — (measurement only) |
| First exclusions        | 2026-05-?? | ~80 % (with exclusions, claimed) | 70 % |
| Gate raised to 75 %     | 2026-05-26 | 77.6 % (after extending exclusions for nav/about/more/provider screens + AppFunctions glue) | **75 %** |
| ViewModel gaps closed   | 2026-06-02 | 79.05 % (closed `MoreViewModel` / `ProviderDetailViewModel` gaps) | **75 %** |

## Per-package LINE coverage (post-exclusions)

Numbers measured 2026-05-26. Sorted by layer.
Packages excluded from the gate (Composables, Android-runtime glue, DI,
generated code) do not appear in this table — they live in the
"Exclusions applied" section below.

### `domain/` — 90.0 % (1 764 / 1 960 lines)

| Package                       | Coverage | Covered / Total | Target  |
|-------------------------------|----------|-----------------|---------|
| `domain.constants`            | 100.00 % | 34 / 34         | ≥ 95 %  |
| `domain.engine`               | 93.56 %  | 407 / 435       | ≥ 90 %  |
| `domain.engine.executors`     | 78.42 %  | 378 / 482       | ≥ 75 %  |
| `domain.models`               | 94.50 %  | 378 / 400       | ≥ 90 %  |
| `domain.pipelineio`           | 92.91 %  | 118 / 127       | ≥ 90 %  |
| `domain.prompt`               | 100.00 % | 53 / 53         | ≥ 95 %  |
| `domain.repositories`         | 60.00 %  | 3 / 5           | n/a — interface declarations |
| `domain.usecases`             | 85.06 %  | 393 / 462       | ≥ 80 %  |

`domain` remains the strongest layer. The 60 % figure on `domain.repositories`
is a single `companion object` constant in an otherwise pure-interface
package — not a meaningful target (`n/a`).

### `data/` — 72.6 % (1 363 / 1 877 lines)

| Package                       | Coverage | Covered / Total | Target  |
|-------------------------------|----------|-----------------|---------|
| `data.engine`                 | 55.39 %  | 190 / 343       | ≥ 50 %  |
| `data.local`                  | 48.30 %  | 327 / 677       | ≥ 45 %  |
| `data.local.models`           | 93.02 %  | 80 / 86         | ≥ 90 %  |
| `data.mappers`                | 100.00 % | 43 / 43         | ≥ 95 %  |
| `data.mcp`                    | 79.45 %  | 58 / 73         | ≥ 75 %  |
| `data.network`                | 92.68 %  | 38 / 41         | ≥ 90 %  |
| `data.prompt`                 | 49.25 %  | 33 / 67         | ≥ 45 %  |
| `data.repositories`           | 82.20 %  | 494 / 601       | ≥ 80 %  |
| `data.services`               | 99.42 %  | 171 / 172       | ≥ 95 %  |
| `data.tools.local`            | 74.53 %  | 199 / 267       | ≥ 70 %  |
| `data.tools.local.executors`  | 100.00 % | 21 / 21         | ≥ 95 %  |

`data.services` jumped from 44 % to 99 % when
Robolectric coverage of `AgentForegroundService`, `AgentWorker`,
`AgentIdleManager`, `AgentPowerManager`, and `LongRunningTaskNotifierImpl`
landed; the previous `data.services.*` exclusion was lifted in that task
and remains lifted.

### `presentation/` — 74.7 % (2 837 / 3 800 lines)

| Package                                       | Coverage | Covered / Total | Target  |
|-----------------------------------------------|----------|-----------------|---------|
| `presentation.notifications`                  | 100.00 % | 63 / 63         | ≥ 95 %  |
| `presentation.receivers`                      | 100.00 % | 14 / 14         | ≥ 95 %  |
| `presentation.ui.chat.home`                   | 84.29 %  | 692 / 821       | ≥ 80 %  |
| `presentation.ui.common`                      | 37.50 %  | 6 / 16          | ≥ 35 %  |
| `presentation.ui.memory`                      | 92.86 %  | 52 / 56         | ≥ 85 %  |
| `presentation.ui.models`                      | 82.14 %  | 92 / 112        | ≥ 75 %  |
| `presentation.ui.monitoring`                  | 100.00 % | 30 / 30         | ≥ 95 %  |
| `presentation.ui.more`                        | 100.00 % | 80 / 80         | ↑ `MoreViewModelTest` closes the gap |
| `presentation.ui.onboarding`                  | 86.61 %  | 110 / 127       | ≥ 80 %  |
| `presentation.ui.orchestrator`                | 85.03 %  | 335 / 394       | ≥ 80 %  |
| `presentation.ui.pipeline.editor.config`      | 90.15 %  | 366 / 406       | ↑ the gap closed: `NodeConfigCodecTest` grew from 10 tests to 37 while the codec grew to 759 lines. Re-measured 20.09.2026 |
| `presentation.ui.pipeline.editor.core`        | 85.16 %  | 310 / 364       | ≥ 80 %  |
| `presentation.ui.prompts`                     | 43.67 %  | 145 / 332       | ↓ **and the direction is the point**: the covered count rose (91 → 145) while the package grew 134 → 332 lines, so the share fell. The `toViewState` mapper is still the densest uncovered piece. Re-measured 20.09.2026; tracked as issue #424 |
| `presentation.ui.settings`                    | 88.89 %  | 256 / 288       | ≥ 80 %  |
| `presentation.ui.settings.provider`           | 100.00 % | 83 / 83         | ↑ `ProviderDetailViewModelTest` closes the gap |
| `presentation.ui.splash`                      | 97.30 %  | 36 / 37         | ≥ 90 %  |
| `presentation.ui.taskmonitor`                 | 90.91 %  | 100 / 110       | ≥ 85 %  |
| `presentation.ui.tools`                       | 92.34 %  | 205 / 222       | ≥ 85 %  |

The 0 % / sub-50 % rows above are **testable** code (ViewModel + UiState
classes) that simply lacks coverage today. They are not excluded from the
gate — they pull the aggregate down — and are tracked as follow-up
items rather than being silently filtered. The "Target" column is
**informational**: with Kover 0.9.x the build only enforces the global 75 %
aggregate; per-package floors will be promoted to enforced rules when Kover
0.10 ships (per-rule `filters { ... }` is a 0.10+ feature).

### Other

| Package                            | Coverage | Covered / Total |
|------------------------------------|----------|-----------------|
| `app.knotwork.android` (root, `App.kt`)| 23.53 %  | 8 / 34          |
| `appfunctions_aggregated_deps`     | 0.00 %   | 0 / 5           |

`App.kt` is the Hilt `Application` subclass; the lit lines are the
Crashlytics opt-in gate. `appfunctions_aggregated_deps` is KSP-generated
boilerplate and could be added to the exclusion list later; at 5 lines it
does not move the aggregate.

## Exclusions applied

The following classes are removed from the metric in
`app/build.gradle.kts` → `kover { reports { filters { excludes { … } } } }`.
Each is justified below.

| Pattern                                           | Why excluded                                                             |
|---------------------------------------------------|--------------------------------------------------------------------------|
| `*_HiltModules*`, `*_HiltModules_*`, `Hilt_*`     | Hilt-generated component / module classes — wiring code, no logic.       |
| `*_Factory`, `*_Factory$*`, `*_MembersInjector`   | Hilt-generated provider factories.                                       |
| `*_Provide*Factory`, `*_Provide*Factory$*`        | Hilt module-provider factories.                                          |
| `dagger.hilt.internal.*`, `hilt_aggregated_deps.*`| Hilt internals.                                                          |
| `*_Impl`, `*_Impl$*`                              | Room-generated DAO implementations.                                      |
| `app.knotwork.android.data.local.AppDatabase`        | Room database class (declarations + migration shells).                    |
| `app.knotwork.android.data.local.AppDatabase_Impl*`  | Room-generated database implementation.                                  |
| `*_AutoMigration_*`                               | Room auto-migration generated bridges.                                   |
| `app.knotwork.android.data.local.dao.*`              | Room DAO **interfaces** (only `companion object` constants in source).   |
| `*ComposableSingletons*`, `ComposableSingletons$*`| Compose-compiler-emitted lambda singletons.                              |
| `*Preview`, `*PreviewKt`                          | Compose preview files (project convention: `*Preview.kt`).               |
| `app.knotwork.android.di.*`                           | Hilt DI modules — only `@Provides` wiring, no business behaviour.        |
| `app.knotwork.android.BuildConfig`                    | Android-Gradle-generated build constants.                                |
| `*.databinding.*`, `*.BR`                         | DataBinding generated code (defensive — project does not use DataBinding today). |
| `@androidx.compose.ui.tooling.preview.Preview`    | Belt-and-braces annotation filter for preview functions outside `*Preview.kt`. |
| `app.knotwork.android.App`                            | Hilt `Application` subclass; needs Android runtime to instantiate.       |
| `app.knotwork.android.presentation.ui.MainActivity*`  | Compose host `Activity`; covered by androidTest, not JVM unit tests.      |
| `app.knotwork.android.presentation.ui.*Screen*`       | Top-level Compose screen files (project convention: `*Screen.kt`).        |
| `app.knotwork.android.presentation.ui.components.*`   | Reusable Compose components used by multiple screens.                     |
| `app.knotwork.android.presentation.ui.orchestrator.components.*` | Sub-package of orchestrator Compose components.                |
| `app.knotwork.android.presentation.ui.chat.legacy.*` (selected) | Legacy chat surface kept until orchestrator rewire.|
| `app.knotwork.android.presentation.ui.chat.home.ChatHomeScreen*`, `ChatHomeDebugStatePicker*`, `DebugStateRows*` | Compose surfaces of the redesigned chat home. |
| `app.knotwork.android.presentation.ui.pipeline.editor.canvas.*` | Gesture / animation / Bezier draw layer.       |
| `app.knotwork.android.presentation.ui.pipeline.editor.bars.*`   | Editor top / bottom bars (Compose).                                  |
| `app.knotwork.android.presentation.ui.pipeline.editor.sheet.*`  | Editor bottom sheets (Compose).                                      |
| `app.knotwork.android.presentation.ui.pipeline.editor.PipelineEditorContent*`, `PipelineEditorScreen*` | Pipeline editor host Composables.        |
| `app.knotwork.android.presentation.ui.splash.SplashScreen*`     | Splash Composable.                                                   |
| `app.knotwork.android.presentation.theme.*`           | Material 3 colour / typography constants. Pure declarative data.         |
| `app.knotwork.android.presentation.state.*`           | Tiny constant-only state types historically used by Compose code.        |
| `app.knotwork.android.presentation.ui.navigation.*`   | **New.** `AppShellScaffold`, `AppNavGraph`, `TabDestination`, `BottomNavVisibility`, `KnotworkModalRoute`, `NavRoutes` — bottom-nav shell + nav-graph wiring. Pure UI / nav glue; route constants unreachable in JVM tests. |
| `app.knotwork.android.presentation.ui.about.AboutScreen*`, `AboutAcknowledgments*` | **New.** Single-file Compose About surface plus its private declarative acknowledgments list. |
| `app.knotwork.android.presentation.ui.more.MoreScreen*` | **New.** Bottom-nav More hub Composable. The sibling `MoreViewModel` / `MoreUiState` remain inside the gate. |
| `app.knotwork.android.presentation.ui.settings.provider.ProviderPickerScreen*`, `ProviderDetailScreen*` | **New.** Provider picker and per-provider configuration screens — covered by the catalog Roborazzi snapshots, not JVM unit tests. |
| `app.knotwork.android.data.tools.local.appfunctions.*` | **New.** AppFunctions callee side: the `AgentAppFunctionService` entry point, the service and inventory the compiler generates beside it, and the `SearchAppFunction` body (unit-tested, but in the same package); the service needs the Android 16 AppFunctions host to dispatch. |
| `app.knotwork.android.data.tools.local.LocalAppFunctionManager`, `SearchTool*`, `DelegateTaskTool*` | Tool-execution Android glue (live HTTP / LLM bridge).                    |
| `app.knotwork.android.data.logging.CrashlyticsTimberTree*` | Firebase Crashlytics Timber bridge; `getInstance()` paths need Google Play services on Android. |

## Justified zero-coverage packages

These appear as 0 % (or near-zero) but are intentional / out-of-scope for
unit-test coverage. They are documented here so a future reader does not
mistake them for missing work.

- **`presentation.theme`** — Material 3 colour / typography constants and a
  thin `Theme` composable. Pure declarative composition; no branching
  business logic to test. Excluded via the
  `app.knotwork.android.presentation.theme.*` pattern above.
- **`presentation.ui`** (root entry-points) — `MainActivity` plus the
  `*ScreenKt` host shells under the top-level `presentation/ui/` directory.
  These are Android-runtime-bound Compose roots; their coverage is the
  domain of `connectedFullDebugAndroidTest`, not the JVM Kover pipeline.
- **`data.local.dao`** — Room DAO interfaces. Their generated `*_Impl`
  implementations are covered, the interface lines themselves are
  declarative.
- **`appfunctions_aggregated_deps`** — KSP-generated boilerplate; 5 lines.

## How to regenerate locally

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# HTML report (visual drill-down):
./gradlew :app:koverHtmlReportFullDebug
open app/build/reports/kover/htmlFullDebug/index.html

# XML report (machine-readable, used by CI parsers):
./gradlew :app:koverXmlReportFullDebug
# → app/build/reports/kover/reportFullDebug.xml

# Console summary (single number, useful in scripts):
./gradlew :app:koverLog
```

The Android-variant suffix (`Debug`) is mandatory — Kover exposes per-variant
tasks for projects that use the `com.android.application` plugin. Tasks like
`koverHtmlReport` (no suffix) exist but cover all variants merged together,
which is unnecessary for this single-variant project.

## CI integration — deferred

There is still no coverage job under `.github/workflows/` publishing the HTML
report as a PR artefact. The original reason — the Git remote's PAT lacking the
`workflow` scope — no longer applies (`.github/workflows/` now holds both
`check.yml` and `release.yml`); what remains is that the build-failing threshold
is already enforced by `koverVerifyFullDebug` inside `./gradlew check`, so the
published report would add convenience, not a gate.

The eventual workflow should:

1. Run `./gradlew :app:koverHtmlReportFullDebug :app:koverXmlReportFullDebug` on each
   PR to publish the report (the build-failing threshold itself is already
   enforced locally and in `./gradlew check` via `koverVerifyFullDebug` — see
   *Enforced threshold* below).
2. Upload `app/build/reports/kover/htmlFullDebug/` and
   `app/build/reports/kover/reportFullDebug.xml` via `actions/upload-artifact`.
3. Optionally print the `koverLog` headline figure as a PR comment.

This artefact-publishing job is still pending; the coverage **gate**
(`koverVerifyFullDebug`) is already wired into `./gradlew check` and is not
blocked on it.

## What this baseline does **not** do

- It does **not** add coverage for `*Screen.kt` Composables — those need
  androidTest / Compose UI tests, which is a separate workstream.
- It does **not** cover the `release` variant — Kover's per-variant tasks are
  invoked with the `Debug` suffix throughout. `release` is irrelevant for
  unit-test coverage.

## Enforced threshold (current)

`koverVerifyFullDebug` fails the build when aggregate LINE coverage over the
unit-testable surface drops below **75 %** (raised from 70 %).
Today's measurement is **77.6 %**, giving the gate ~2.6 pp of
headroom against silent regression.

Kover 0.9.x has no rule-level `filters { ... }` block — that DSL element is
gated behind a future 0.10 release — so the gate is a single
application-wide rule operating over the globally-filtered class set in
`app/build.gradle.kts` → `kover { reports { filters { excludes { ... } } } }`.
The per-package floors in the tables above are **informational targets**;
promote them to enforced rules once Kover 0.10 ships.

Several Compose-surface / Android-runtime exclusions were also added that
fell through the existing wildcards because they live in newly introduced
sub-packages:
`presentation.ui.navigation.*`, `presentation.ui.about.AboutScreen*` (+ its
private `AboutAcknowledgments` data list), `presentation.ui.more.MoreScreen*`,
`presentation.ui.settings.provider.{ProviderPickerScreen, ProviderDetailScreen}*`,
and `data.tools.local.appfunctions.*`. Without these, the aggregate would
sit at 73.8 % — i.e. the previously-claimed "~80 %" was already incorrect
because the new screens never made it into the filter when they shipped.

Raise the threshold deliberately as coverage grows; do **not** lower it
without team agreement.
