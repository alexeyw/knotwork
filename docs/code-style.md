# Code Style

This document describes the Kotlin code style enforced in this project. It
applies to every file under `app/src/main/java/` and the corresponding test
source sets. Automated checks (detekt, ktlint, Android lint) are wired into
`./gradlew check`; see [`docs/static-analysis.md`](static-analysis.md) for the
exact configuration and severity policy.

## Kotlin style

- Follow the official [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html).
- Use **ktlint** for automatic formatting. Run `./gradlew ktlintFormat` before
  every commit; the same checks run on CI as part of `./gradlew check`.
- Max line length: **120 characters**.
- Use **trailing commas** in multi-line collections and function signatures.
- Prefer **expression bodies** for single-expression functions.
- Use **named arguments** when calling functions with three or more parameters
  of the same type.

## Naming conventions

| Entity                 | Convention                          | Example                        |
|------------------------|-------------------------------------|--------------------------------|
| Classes / interfaces   | PascalCase                          | `AgentOrchestrator`            |
| Functions / variables  | camelCase                           | `executeToolCall()`            |
| Constants              | SCREAMING_SNAKE_CASE                | `MAX_CONTEXT_TOKENS`           |
| Composables            | PascalCase, verb-free noun          | `ChatScreen`, `MessageBubble`  |
| ViewModels             | `<Feature>ViewModel`                | `ChatViewModel`                |
| Use cases              | `<Verb><Noun>UseCase`               | `SendMessageUseCase`           |
| Repositories (i-face)  | `<Noun>Repository`                  | `ModelRepository`              |
| Repositories (impl)    | `<Noun>RepositoryImpl`              | `ModelRepositoryImpl`          |
| DI modules             | `<Feature>Module`                   | `LiteRtModule`                 |

## Architecture constraints

- **`domain` layer** — zero Android SDK imports (`android.*`, `androidx.*`).
  Pure Kotlin plus Coroutines only. Dependencies flow inward: `data` and
  `presentation` depend on `domain`; `domain` depends on nothing else in the
  project.
- **`presentation` layer** — Composables observe `StateFlow` / `UiState` from
  a `ViewModel`. No direct repository or use-case calls from inside a
  `@Composable` function.
- **`data` layer** — implements repository interfaces declared in `domain`.
  Heavy I/O runs on `Dispatchers.IO`.

For the broader layering rationale, see
[`docs/architecture.md`](architecture.md).

## Compose guidelines

- Every screen-level Composable receives only a `ViewModel` (or a `UiState` +
  callback lambdas) — never raw data types from `data` or `domain`.
- Use `remember { }` and `derivedStateOf { }` to avoid unnecessary
  recompositions.
- Use `LazyColumn` / `LazyRow` for all scrollable lists.
- Separate stateful (screen-level) and stateless (component-level)
  Composables.
- Mark preview-only Composables with `@Preview` and keep them in dedicated
  `*Preview.kt` files, separate from the production Composables they render.
- Snackbars render through the design system's `KnotworkSnackbarHost` only:
  in a `Scaffold`'s `snackbarHost` slot, which lifts it above the bottom bar
  and the floating action button, or bottom-centre of a container already
  clear of the system bars. A `SnackbarHostState` a screen shows messages
  into is rendered by a host in the same file. `SnackbarHostGuardTest` fails
  a raw Material3 host, an unrendered state and an unplaced host.
- Text is drawn through the design system's `Text`
  (`app.knotwork.design.components.text.Text`), never Material's: laying out
  one text costs time in the square of its longest run without whitespace,
  whatever its line limit, and that `Text` bounds what any text lays out. A
  screen whose point is to show a long text whole uses `WholeText` and is
  named, with the reason, in `BoundedTextGuardTest` — as is every file that
  composes an input field, which can be neither cut nor drawn in pieces.

## Coroutines & Flow

- Launch coroutines from `viewModelScope` (inside a `ViewModel`) or from a
  `CoroutineScope` injected via Hilt.
- **Never** use `GlobalScope`.
- Use `StateFlow` for UI state and `SharedFlow` for one-shot events.
- Handle exceptions via `CoroutineExceptionHandler` or `try`/`catch` —
  raw exceptions must not propagate to the presentation layer.
- **Never swallow `CancellationException`.** A `try`/`catch` around suspend
  calls (or a `collect`) must re-throw it from a dedicated first catch
  clause, *before* the generic handler maps the failure to an error state:

  ```kotlin
  try {
      repository.doWork()
  } catch (e: CancellationException) {
      throw e // cooperative cancellation — never map to an error
  } catch (e: Exception) {
      Result.failure(e)
  }
  ```

  `runCatching` must **never wrap suspend calls** — it traps
  `CancellationException` and breaks cooperative cancellation (a cancelled
  coroutine keeps running, or surfaces a false error to the user). Use the
  `try`/`catch` shape above instead. Both rules are enforced by the
  type-resolution detekt gate — see
  [`static-analysis.md`](static-analysis.md#type-resolution-gate-detektfulldebug--detektfossdebug);
  note the gate's blind spot for `try`/`catch` inside non-suspend inline
  lambdas (e.g. `forEach`), which reviewers must still check manually.
  Cleanup that must also run on the cancellation path belongs in `finally`
  (with `withContext(NonCancellable)` if it suspends).

## Regular expressions

- A regex pattern is a **string literal** in the source. Runtime text goes into
  it only through `Regex.escape`, which adds characters, never quantifiers.
  `java.util.regex` backtracks: a pattern shaped by input can take time
  exponential in its length, and the match cannot be stopped — it never
  suspends, so a coroutine timeout does not fire.
- To match input against a pattern that itself comes from the model or the user
  (a glob, a filter), use a matcher that cannot backtrack, as `WorkspaceGlob`
  does. `RegexConstructionKonsistTest` fails a `Regex(…)`, `Pattern.compile(…)`
  or `.toRegex()` built any other way.

## Logging

- A `WARN`-or-above Timber message is a **string literal without templates**,
  and values go in as format arguments:
  `Timber.w(e, "import failed for %s", name)`. With crash reporting on, those
  records are sent as non-fatal reports, and a report carries the literal, the
  error's type and its stack frames — never the arguments and never an error's
  own message, which is where paths, file contents and tool arguments travel.
  A value baked into the literal (`"… $path"`), or a message held in a
  variable, would be sent. `TimberMessageTemplateKonsistTest` fails any other
  shape.

## Interface text

- Every text the interface shows is a **string resource**, translated into each
  language the app ships (`localeFilters` in `app/build.gradle.kts`). A sentence
  written into Kotlin shows in English in every language.
  `DisplayedLiteralGuardTest` fails a literal that reads like words in the code
  that draws interface; `CatalogStringsWiringTest` fails a design-system English
  default the app never replaces with a resource.
- A ViewModel outlives the screen's re-creation in a new language, so text it
  resolves once and keeps stays in the old one. Hand the screen a resource id or
  a typed value and resolve it there; text a ViewModel must keep is rebuilt when
  the configuration's locales change (`KeyedCache`).
- A new string gets its translation in the same change. Lint's
  `MissingTranslation`, an error in both modules, fails a string missing from a
  shipped language; `TranslationCoverageTest` fails a translation that only
  copies its English unless `config/l10n/identical-<lang>.txt` names it.
- What a model reads is not translated: system prompts, tool descriptions and
  tool results stay in English, as do the tokens a model's output is matched
  against (route labels, intent classes). The system rows a chat shows (a tool
  call you denied, how a run ended) are written in the interface language when
  saved, through `ChatSystemRows` or the run's own copy, and kept as written:
  the model reads them in the language of the conversation. The hidden rows the
  tool gate records for the model (an observation, a denial, a blocked call)
  are tool results and stay English.
- A failure the app words itself travels as a type, not a sentence: `domain` and
  `data` hand over what happened (`ModelLoadError`, `ConnectionFailure`,
  `TestProbeFailure`), and the screen names it. Text an engine, a server or the
  platform wrote is shown as it came.
- A name the app gives what it creates — a new chat, a pipeline, its first
  nodes, a copy — comes from `DefaultNames` and is stored as written, in the
  language of the moment. Never compare a stored name with a literal: a new
  chat is recognised by `DefaultNames.isNewChat`, in every shipped language.

## Dependency injection (Hilt)

- Use `@HiltViewModel` for every `ViewModel`.
- Provide external dependencies (Retrofit, Room, LiteRT, Koog) in dedicated
  `@Module` objects, grouped by feature.
- Prefer constructor injection over field injection.

## Enforcement

Style is enforced by the build, not by reviewer discretion. Before pushing a
branch, run:

```bash
./gradlew check
```

This aggregates detekt (both the strict run and the type-resolution
gate), ktlint, Android lint, unit tests and Kover. The same
task gates every pull request in CI. See
[`docs/static-analysis.md`](static-analysis.md) for the rule set, current
baselines, and how to handle a new finding.
