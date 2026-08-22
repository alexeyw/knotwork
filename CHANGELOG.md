# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Until version `1.0.0`, this project is considered a pre-release: breaking
changes to the Kotlin public surface, pipeline JSON schema, settings layout
and on-device storage formats may ship in minor releases without a
migration path. See the *Pre-release notice* in [README.md](README.md) for
details.

## [Unreleased]

### Fixed

- **The `adb` examples for the external-automation contract could not work as
  written.** `adb shell` does not pass your arguments through — it joins them
  into one string and hands that to the phone's own shell, which splits it
  again, so the quotation marks you type are gone before they matter. Copying
  the documented command delivered only the first word of your message, and
  looked for a pipeline named after only the first word of its name, then
  reported it missing. Both looked like the app turning down a request that was
  in fact perfectly good.

  The examples now quote the whole command, and the reason is written down
  beside them rather than left to be rediscovered. A build check refuses any
  future example written the old way.

## [0.8.0] - 2026-08-21

### Added

- **Definition of the external-automation contract** — the vocabulary another
  app on the device (Tasker, MacroDroid, a shell script over `adb`) will use to
  ask Knotwork to run one of your pipelines. This change defines and documents
  the contract; the receiver that acts on it and the setting that switches it on
  land separately, so nothing is callable yet.
  [`docs/external-automation.md`](docs/external-automation.md) describes the
  request keys, the statuses and every refusal reason, and its reference tables
  are generated from the source declarations with a build check that fails on
  drift — a contract whose callers live in other apps cannot afford stale
  documentation. The entry point will be **off by default** and **inert until
  bound**, and the binding is an allowlist rather than a default: a request must
  name the pipeline it wants, and one naming any other pipeline is refused
  rather than quietly redirected.

  **The smallest call is two keys** — which pipeline, and what to say to it. The
  correlation id a caller can send is required only when that caller also asks
  to be answered, because its only job is to match the answer to the request
  that caused it. An automation app's built-in send-an-intent action gives you a
  small, fixed number of extra fields — three, in the version of Tasker this was
  checked against — so a key that is mandatory but then discarded is one of those
  fields spent on nothing.

- **Another app on your device can now ask Knotwork to run a pipeline.** The
  external-automation entry point defined in the entry above is live: a
  Tasker or MacroDroid profile, or a shell script over `adb`, can broadcast a
  request and Knotwork will run the pipeline you bound to it. The switch that
  turns this on is off by default; its settings screen ships in the entry
  below.

  The guarantees are the point, and each one is under test:
  human-in-the-loop confirmation, per-tool risk overrides and the
  destructive-tool block all apply exactly as they do to the app's own
  background runs — an external call asks for a run, it does not approve what
  the run then does. The binding is an allowlist, so a request naming any other
  pipeline is refused rather than redirected. Requests are rate-limited, and
  **every** request is recorded — admitted or refused — because an entry point
  that leaves no trace cannot be diagnosed. A caller that asks for one gets a
  callback when its run finishes, including hours later when the run had paused
  to ask you something.

  Android does not tell a broadcast receiver who sent the broadcast unless the
  sender opts in, which automation apps do not, so the callback address is taken
  at face value. That is why the callback carries only your request id and a
  status, and never what the run produced.
  [`docs/external-automation.md`](docs/external-automation.md) carries the whole
  contract plus worked Tasker, MacroDroid and `adb` examples you can copy.

- **External automation is now switchable, and every request is readable.**
  Settings → Background & triggers grows three rows: the master switch, the one
  pipeline outside apps may run, and the request journal. All three are in the
  settings search index — the app's most security-sensitive switch should not be
  the only one you cannot find by typing its name.

  Switching it **on** raises a consent dialog that names what you are agreeing
  to in plain language: any app on the device that can send a broadcast may ask,
  only the pipeline you pick can be run, your tool approvals still apply exactly
  as they do for the app's own background runs, a run can spend your cloud API
  key, and one tap turns it all off again. The switch does not move until you
  confirm. Switching it **off** is immediate and asks nothing — a confirmation
  on the way out would make that last promise false.

  Switched on with nothing picked is a real state, not an oversight: the surface
  accepts a request and refuses it. The binding row says so with a warning glyph
  and words, not only a colour.

  The **request journal** shows every inbound request, accepted or refused, with
  the reason as a sentence rather than a constant name, and keeps the two
  refusal kinds apart — *Refused* means sending the same request again gives the
  same answer, *Held back* means it can be accepted later. Because Android only
  reveals who sent a broadcast when the sender opts in (automation apps do not),
  a row shows the app the caller asked to be answered on and marks it
  *unverified*: a claim never reads as a confirmed identity. A caller looping
  against a switched-off contract collapses onto one row with a repeat count, so
  a misconfigured profile reads as one recurring problem rather than dozens of
  separate ones. The screen also carries a *How another app calls this* block —
  the action and the extra keys, read from the contract itself so it cannot
  describe a call the app would not answer.

- **Autonomous runs now have ceilings, and a run stopped by one says so.**
  A background run — a trigger firing overnight against your own cloud API key —
  is now bounded on two axes across the whole run tree: how many pipeline steps
  it may take, and how many tokens it may spend. Each axis has a soft threshold
  that warns and nudges the run to wrap up, and a hard one that stops it
  deterministically. Runs nobody is watching (scheduler, Quick Settings tile,
  triggers, external automation) get their own, tighter token limit.

  The step ceiling is not new, but it did not previously bind. It was counted
  per execution attempt, and answering a background approval resumes a run — so
  a nightly loop that asked a question each iteration received a full fresh
  allowance every time it was answered. The counters now live on the run record,
  survive being parked and resumed, and continue where the previous attempt
  stopped. Work already done is never charged twice.

  A **money limit is not offered, and the app says so rather than implying one.**
  Knotwork runs on your provider keys and never sees an invoice; turning tokens
  into currency would need a price table that goes stale between releases and
  would show a wrong number as money. The token limit is the honest proxy.

  Cloud token counts are now the provider's own figures — prompt *and*
  completion — where the provider reports them, instead of an estimate that only
  ever saw the answer. In a loop the prompt is what costs.

- **The run limits are now visible, adjustable, and honestly described.**
  Settings → Pipelines & structured output grows a **Run limits** row, showing
  the current step and token limits without opening anything, and leading to a
  screen with all four: steps and tokens, each with its own value for runs
  nobody is watching. The background step limit follows the interactive one
  until you set it separately, and says so on the row rather than showing two
  identical numbers with no hint that they are linked. The spending limit is
  there too, as a statement rather than a control: the app runs on your own key,
  never sees your bill, and says *Not measured* instead of showing a figure it
  would have to guess.

  Until now three of those four numbers had no screen at all — a run stopped at
  a limit you could not read, let alone change.

- **A run that goes in circles is now caught and stopped, and told apart from a
  run that has merely run out of allowance.** The agent watches each run for
  repetition — the same step, on the same input, producing the same result — and
  responds in two stages: first it tells the run, quietly in the chat and in the
  agent's own context, so it can change course by itself; then, if nothing
  changes, it ends the run and says *Stopped: the run was not getting anywhere*,
  offering **Open console**, whose log shows the same step running over and over
  and the line that ended it.

  This is a different protection from the run limits, not a second copy of one.
  A limit asks how much a run has spent; this asks whether it is getting
  anywhere. A run that keeps producing genuinely different results is not
  repeating itself however long it takes, and is left alone — and neither is a
  run still being handed something new each time, even when its answers look
  alike, which is what a long list of similar tasks looks like from the inside.
  Those cases are what the limits are for. Nothing is configurable here: how
  many identical steps count as a loop is not a preference, and a slider would
  only invite widening it until the protection stopped protecting.

  A run that stops responding altogether — a tool or a server that never answers
  — is now reported as its own thing (*Stopped: the run went quiet*) instead of
  borrowing the wording for repetition. It had been showing a message about work
  that "kept repeating", which was never what had happened to it, and offering
  to open a console to compare steps that had never repeated. It now offers to
  run it again, which is the thing that can actually help: a step that hung once
  often answers the next time.

- **A prompt can now travel as a file.** Any prompt in the Prompt library can be
  exported as a Markdown file and imported back — yours or one of the bundled
  ones, since exporting only reads it. Import is the new action in the library's
  top bar and the first button on the empty screen; export lives on each row.
  The file is a short settings block between two lines of three dashes followed
  by the prompt itself, so one can be written by hand in any editor and sent
  through a chat or kept in a repository.

  **A prompt file can only supply wording.** It cannot add tools, add steps, or
  carry scripts. If a file asks for any of those, the prompt is still imported
  and the app names exactly what it left out, because a refusal nobody sees is
  the same as no refusal at all. There is deliberately no "import from a URL":
  a prompt becomes part of the instructions the agent follows, and the file
  picker is what keeps a person's decision between a link and your agent.

  When a file cannot be read, nothing is imported and the app says which of the
  recognised causes it hit rather than calling the file invalid. When the prompt
  is already in your library and the file differs, you choose whether to replace
  it or keep both — the app never silently overwrites an edit you made in it.

### Changed

- **A run that stops explains itself in its own words.**
  Every way a run can end early — a limit, an unanswered approval, a pipeline
  edited mid-pause, the app being killed — now has a sentence written for a
  person, in one place, used identically by the chat, the notification and the
  foreground status. Six of them previously showed the internal constant name
  in the middle of a sentence, and the message a ceiling produced was assembled
  inside the engine, persisted, and quoted verbatim in the documentation, which
  is how it came to describe behaviour the engine does not have.

  **Retry is gone from these messages.** Re-running the identical turn reaches
  the identical limit; a run stopped by a ceiling now offers *Adjust limits*
  instead, and the ones a fresh attempt genuinely fixes offer to run again.
  Retry remains where it belongs, on an ordinary failure.

  A ceiling stop is also no longer *styled* as a failure: the chat, the
  background notification and the run console all give it the same warning tone
  and shield the trigger journal already used.

- **A run stopped by a safety limit no longer reads as a broken automation.**
  A trigger whose run hit a ceiling shows *Stopped by a safety limit* in its
  journal and does not count against the trigger's health indicator. Why a run
  ended is now recorded as a typed cause rather than recovered by matching the
  error text — which was how a timed-out background approval used to be told
  apart from a genuine failure, and would have broken the first time that
  message was reworded.

- **Prompt cards were rearranged to fit their new export action.** The name now
  has the whole first line to itself, and Preview, Duplicate, Edit and a **More**
  menu — holding Export and Delete — sit on the footer line beside `used by N
  pipelines`. Delete stops being a one-tap neighbour of Edit, and the row keeps
  the same four action slots it had, at the same reach, including at the largest
  font sizes. An empty Prompt library now offers **Import prompt** and **New
  prompt** directly instead of only a `+` button.

- **A settings reset no longer detaches the background step limit.**
  Resetting to recommended defaults used to write that limit explicitly, which
  is exactly what marks it as independently chosen — leaving you with a
  deliberate-looking decision you never made, silently stopping it from
  following the interactive limit. The reset now clears it, restoring the link.

- **Knotwork now runs on Android 14 and 15, not just Android 16.** The
  requirement had been Android 16 since the first release, which is a small
  slice of the devices in use — roughly a fifth. Nothing actually needed it.
  A measurement of what held the floor up came back empty: not a dependency,
  not the on-device inference engine, not one line of the app's own code. The
  floor had simply been set higher than anything asked for.

  Two things do still need Android 16, and both fail softly rather than
  breaking the app. Calling functions that other apps expose relies on a system
  service that is not present on every older device; where it is missing, that
  part of the tool catalogue is empty and everything else — local models, cloud
  providers, MCP servers, triggers, scheduled tasks — is unchanged. The strict
  intent-matching rules that harden the external-automation entry point are an
  Android 16 feature, so on Android 14 and 15 that receiver keeps its other
  defences (off by default, a single bound pipeline, a rate ceiling, a journal
  of every request, and confirmation for anything destructive) but not that
  one. Both are stated here rather than left to be discovered.

  The hardware requirement is unchanged and is the one more likely to bind in
  practice: a local model still needs roughly 2 GB of free RAM, and an older
  Android version often means a device that does not have it.

- **The instrumented test suite now runs in continuous integration.** Around ten
  thousand lines of Compose UI, Room migration and DAO tests lived in the
  repository without running anywhere automatic — so they were a claim of
  coverage rather than coverage, and once an entire instrumented source set
  stopped compiling without a single check going red. They now run on emulators
  on every pull request into the default branch, on every push to it, and
  nightly — covering both published build variants, including the Google-free
  one that nothing automatic had ever exercised on a device, and both ends of
  the supported Android range.

  Two failures are kept apart on purpose. A failing test is never retried —
  retrying a real regression until it passes is how one gets shipped. A failure
  that matches a known environment signature, such as a lost emulator or a
  dependency download that timed out, is retried once and then labelled as
  such, so a red build still answers the question "is the code wrong?" rather
  than leaving it open. Both still fail the run: a gate that turns green when
  its own environment misbehaves has stopped being a gate.

  A test that an emulator genuinely cannot judge is excluded by a named list
  with a written reason, checked by its own guard, instead of quietly reporting
  itself as skipped inside a green run. Exactly one test is on that list today.

  Whether the instrumented sources still *compile* is a different question with
  a different answer: it depends on nothing but the repository, so it is checked
  by the same required job as everything else, and therefore also on the path
  that builds a signed release.

  One job speaks for the whole emulator run, so that the list of checks a branch
  requires does not have to name each configuration and quietly fall out of step
  with them.

- **The build no longer fails because a dependency released a new version.**
  Four Android Lint checks — the three "a newer version of X is available"
  checks and the target-SDK expiry warning — answer from an external version
  index or from the calendar, not from the contents of this repository. That
  made the same commit green one day and red the next with nothing changed in
  between, and green on a contributor's machine while red in CI, because the two
  version indexes had been refreshed at different times. Twice this year the
  fix for a red build was a batch of version bumps unrelated to the work being
  reviewed.

  Those four checks now report instead of gating: they still run, still analyse
  every dependency, and their findings still appear in the lint report — they
  just no longer fail the build. The report is where the signal lives now. CI
  uploads it on every run rather than only on failures (a green run is precisely
  the run whose report carries the drift) and renders the findings into the job
  summary as a table, so the answer to "what is out of date?" needs no download.
  Dependencies are updated deliberately, when a task needs the newer version or
  in a single pass before a release.

  This is about determinism, not leniency: whether the build passes ought to be a
  function of what is in the repository. Checks that encode a store publishing
  blocker rather than a matter of hygiene deliberately keep failing the build —
  the expired-target-SDK check and the Google Play SDK Index checks — because
  being interrupted by those is the point. The design-system module, which
  mirrors the app's strict lint configuration and is covered by the same
  aggregate check, gets the same treatment; leaving it out would have kept the
  gate non-deterministic.

  A new build guard keeps the arrangement honest. Regenerating the lint baseline
  would re-absorb the now-informational findings and quietly empty the report
  they live in, with nothing failing to say so, so the aggregated check now fails
  if a baseline suppresses one of them.

- **Dependencies updated in one pass.** Nine were moved together rather than one
  at a time in the middle of unrelated work: the build tool, the HTTP client and
  its test server, the encrypted-database driver, the Markdown renderer used for
  chat messages, the crash-reporting libraries, and — the two that matter — the
  on-device inference engine and the text-embedding library behind long-term
  memory.

  Those last two had been deliberately held back, because the ways they break
  are invisible to a normal build: they fail only in a shipped, optimised
  release, and only when the app actually loads a model or writes a memory. Both
  are now checked by the two guards that exist for exactly those failures, and
  both pass — but the remaining verification is a real device, which is why they
  moved now, ahead of testing, rather than quietly on their own.

  One dependency could not be updated: the app-functions libraries share a
  version, and one of the three has not been published at the newer version, so
  moving the others would not resolve.

- **The reasoning behind a handful of decisions is now written down where
  contributors can find it.** A short catalogue explains the cases where the
  obvious change is the wrong one — why the manifest permits unencrypted local
  traffic while the app still refuses it, why the bundled agent library is used
  as a transport and a client rather than as the thing that runs the agent, why
  a few node controls were removed instead of connected. It is deliberately
  small: a topic already explained by the architecture, security, testing or API
  documents keeps its home there, and nine candidates were dropped for exactly
  that reason.

### Fixed

- **The *More* tab keeps its highlight inside settings sub-screens.**
  Opening Privacy → Usage statistics or Background & triggers → Request journal
  dropped the bottom-bar highlight, because the list of routes that count as
  "inside settings" was maintained by hand and had never included them.

- **A thinking model's private reasoning no longer arrives as the answer.**
  Models such as Qwen3 and DeepSeek R1 work through a problem out loud before
  replying, inside a block meant for the machine rather than the reader. That
  block was being treated as the answer: it filled the chat bubble, it was saved
  as the reply, and from there it was read back into every later message of the
  same conversation — so a single long deliberation kept crowding the model's
  own memory of the chat for the rest of it.

  It also broke things that have nothing to do with chat. When a model drafts a
  candidate tool call while thinking, the reply contains a stray fragment of
  machine-readable text, and the part of the app that looks for such text picked
  up the draft instead of the real thing.

  The reasoning is now separated where the answer is produced, so everything
  after it — the bubble, the saved reply, the conversation history, tool
  handling — sees the answer alone. Conversations that already contain a stored
  reasoning block stop feeding it back; what was written is left as it was
  written rather than rewritten after the fact.

  Two cases are handled the way the models actually behave rather than the way
  they are documented: a reply whose reasoning block is only ever closed and
  never opened (the usual shape for Qwen3), and one cut off mid-thought, where
  the whole reply is kept — an untidy answer beats a blank one.

- **Values in the run console can be copied instead of screenshotted.** The
  console's Variables tab shows a step's full input and output — for a local
  model that is the entire assembled prompt, which is exactly what you read when
  an answer comes out wrong. There was no way to get any of it off the screen,
  and the copy button in the header copied the log whichever tab was open, so on
  Variables and Timings it quietly put something else on the clipboard.
  Long-pressing a row now offers to copy it — the same gesture the log already
  used — and the header button copies the tab you are actually looking at.
  Values are copied whole: abbreviating them would recreate the problem the
  action exists to solve.

- **A test of the external-automation contract was checking the wrong signal.**
  Two tests assert that a third-party caller is told the outcome of a run exactly
  once, including the case where the run parked for hours on a confirmation
  prompt. They waited for the outcome to be written down and then checked that
  the caller had been told — but writing it down happens *first*, deliberately,
  because that record is what stops the same run being reported twice. In the
  gap between the two, the check could run and find nothing, which is what made
  it fail once on the build servers and pass everywhere else. The tests now wait
  for the notification itself. The behaviour they test was never wrong.

- **Four static-analysis rules were configured but had never run once.**
  The Kotlin analysis step splits its rules by what they need to answer: some
  read the code as text, others need the compiler's resolved types. A rule of
  the second kind, placed in the configuration used by the first, is skipped in
  complete silence — no error, no warning, nothing in the report. Four rules had
  been sitting there since they were added, each with a comment explaining the
  threshold chosen for it, and a deliberately absurd probe (a constructor with
  eighteen parameters) passed the check clean.

  They now run, in the analysis pass that can execute them, over both
  distribution flavours rather than only the shared sources. What they had been
  missing was waiting: nineteen unused imports, five constructor dependencies
  that were injected and never read — including one whose class documentation
  promised behaviour the dead dependency was supposed to provide — and four
  over-long parameter lists, now each carrying a written reason for being
  allowed to stay.

  The mistake cannot be repeated silently: the build now fails if a rule that
  needs resolved types is put back into the configuration that cannot run it,
  and it reads the list of such rules from the analysis tool itself rather than
  from a copy kept in this repository, so a future upgrade that moves a rule
  across the line is caught by the next build.

  One rule from the same group is deliberately still switched off. It does not
  count a dependency that is used while an object is being constructed, which on
  this code base is most of them — twenty-four of its twenty-nine findings were
  wrong. Turning it on would have meant annotating live code with two dozen
  assertions that are untrue. The five genuine findings it did surface were
  fixed by hand.

## [0.7.3] - 2026-08-16

### Fixed

- **Long-term memory works in a released build.** It did not, in any of them.
  Saving a message to memory failed with *Couldn't save to memory*, and anything
  else that needed to turn text into a vector failed the same way — semantic
  retrieval, automatic fact extraction, the memory-aware pipeline nodes. Only
  release builds were affected, which is why it survived so long: the app is
  developed and tested in debug builds, where the code that broke it does not
  run.

  What broke it: the release build's optimiser makes a class abstract when it
  can see no code creating an instance of it. The library that parses the
  on-device embedding model's configuration never creates instances the visible
  way — it allocates them directly, which the optimiser cannot see. So it
  removed the ability to instantiate exactly the classes that get instantiated,
  and the embedder failed on its first call. The failure was caught and shown as
  a small message rather than a crash, so it never appeared in a crash report,
  and nothing in the automated checks runs the optimised build.

  The build now verifies, on the packaged app itself, that those classes are
  still there and still instantiable — the property that was broken, rather than
  the one the previous check happened to look at. That check fails the build,
  and it has been confirmed to reject both previously published versions.

  If you had given up on memory: nothing was lost or corrupted, and nothing
  needs to be reset. Saving simply refused to happen. It happens now.

## [0.7.2] - 2026-08-16

### Changed

- **The FOSS build no longer declares any Google components.** MediaPipe brings
  Google's data-transport library along for its own logging, and three of that
  library's components — an alarm receiver, a job service and a
  backend-discovery service — ended up declared in the manifest of the build
  whose whole point is that it carries no Google dependency. Nothing could be
  sent through them: the transport implementation is stripped from the release
  build and no collection endpoint reaches the app. That made them worse than
  useless, because a manifest advertising a collector is exactly what someone
  who chose the FOSS build will look for. They are gone. Nothing about the app's
  behaviour changes; what changes is that the artefact now matches its own
  description.

- **The APK no longer carries Google's encrypted dependency blob.** The build
  tools stamp a description of the dependency graph into every artefact, in a
  form only Google can read. It is now omitted from the APK — the file you
  sideload and can inspect — and kept only in the bundle uploaded to Play, which
  uses it to warn about known-vulnerable dependencies.

## [0.7.1] - 2026-08-14

### Added

- **Report a response you think is wrong.** Long-pressing a message the model
  produced now offers **Report response**: pick a category (harmful or unsafe,
  sexually explicit, hate or harassment, misleading, something else), add a note
  in your own words, and the app assembles a report carrying your note, the
  reported text, and the app version, device and currently selected model. Nothing is
  transmitted — there is no reporting server behind this app and adding one
  would contradict everything else it claims. You hand the report over yourself:
  **Copy report** puts it on the clipboard, **Open issue** opens the public
  tracker with it prefilled. The dialog says so plainly, and warns that the
  tracker is public before you submit anything. Reporting is offered on what the agent
  produced — model replies and tool output — but not on your own messages, which
  have nobody to be reported to.

- **A standalone privacy policy.** `PRIVACY.md` now documents, in one place,
  what the app stores on the device, every path that can send data off it (a
  cloud node with your key, an MCP server you added, a model download, an
  outbound request from a tool you allowed, opt-in crash reports), what never
  leaves at all, and why each permission is requested. It replaces the README
  section as the canonical answer, and the About screen's privacy link now
  points at it — one document behind the app, the README and any store listing,
  rather than three descriptions drifting apart.

- **Store listing metadata lives in the repository.** Listing texts and release
  notes for both English and Russian, plus the screenshots, the 512 icon and the
  feature graphic, now sit under `fastlane/metadata/android/` — the layout
  Google Play and F-Droid both read, so the two listings are edited in one place
  instead of drifting apart in two consoles. The graphics are English-only and
  the Russian listing falls back to them, since the app's own interface is
  English. A test enforces the length limits, the presence of release notes for
  the shipping version, the screenshot geometry the stores accept, and the
  feature graphic's size and alpha-free format — Play refuses a PNG carrying an
  alpha channel even when every pixel in it is opaque.

### Changed

- **Turning on crash reports no longer turns on Firebase Analytics.** The
  consent toggle is described everywhere — in the setting itself, in the README
  and in the security policy — as stack traces only. Flipping it also switched
  on Firebase Analytics collection, quietly adding automatic events to what an
  opted-in install sent. The app has never logged an analytics event of its own,
  so the collection bought nothing and contradicted the toggle's own
  description. Consent now enables Crashlytics and only Crashlytics, and the
  Analytics SDK is no longer shipped at all — which also drops the
  advertising-ID and ad-services permissions it added to the full build. What a
  crash report can contain is unchanged. If you had opted in on an earlier
  build, updating stops the analytics collection you never asked for; you do not
  need to do anything.

- **Two builds of the same commit now carry the same build date.** The date
  shown in Settings was stamped from the wall clock while the build ran, which
  made every rebuild of an identical source tree a different binary. It now
  comes from `SOURCE_DATE_EPOCH` when set, otherwise from the commit itself —
  the groundwork for a release anyone can reproduce from source and check
  against the published artefact.

## [0.7.0] - 2026-08-13

### Added

- **Usage statistics now show whether the app actually stuck.** The on-device
  dashboard gained a **This week** section: active days out of the last seven
  with the previous week beside them for comparison, how many distinct pipelines
  you ran in that window, your current run of consecutive days, how often you
  came back after a break of three days or more, the longest such break, and how
  many days you used the app in your first week after installing. Everything is
  derived from counts the app already kept, plus one addition — which pipelines
  were used on which day — so the week can be told apart from the all-time
  totals. Nothing leaves the device: the same build-time guard that forbids any
  network call on this path covers the new code as well. The first-week figure
  stays blank until that week has actually elapsed rather than showing a number
  a two-day-old install has not earned, and the JSON export carries the window
  definition next to the figures so a saved snapshot still explains itself
  months later. Your day-by-day history was already being recorded, so the
  day-based figures are meaningful immediately after updating; **Pipelines
  used** is the one that starts at zero, because which pipeline ran on which day
  was never stored before and inventing that history would be worse than a week
  of honest zeros.

- **Archive chats instead of deleting them.** A chat list that only grows
  eventually stops being useful, and until now the only way to shorten it was to
  delete a conversation for good. Chats can now be archived: swipe a row in the
  chat drawer or use its new `⋮` menu, and the conversation leaves the list with
  every message, run and trace intact. A snackbar offers **Undo** for eight
  seconds and the drawer stays open, so putting several chats away in a row is
  one gesture each. Archived chats live on their own screen — always reachable
  from **More → Archived chats**, and from the drawer's footer once there is
  something in there — newest-archived first, each labelled with when you put it
  away. Restore is one tap (button, row menu, or swipe); **Delete forever** is
  available too, from the row menu only and behind a confirmation, never from a
  swipe. Opening an archived chat shows the full history **read-only**, with the
  message box replaced by a Restore bar: sending a message would silently
  un-archive the conversation, and that decision stays yours. A background
  trigger or scheduled run may still write into an archived chat without
  bringing it back, and the row says so when one does. Archived chats export to
  the share sheet exactly like active ones, so putting a chat away never puts it
  out of reach of your history.

- **Scheduled and triggered runs can say what they should remember.** A run you
  start yourself searches long-term memory with your own message. A run started
  by an automation trigger, a schedule, or the Quick Settings tile had no such
  message — it searched with the pipeline's built-in prompt ("write the evening
  journal entry"), which is identical on every firing and describes none of
  them, so the recalled entries were largely arbitrary. Such a run now uses, in
  order: a search key the pipeline declares (`memoryRetrievalQuery`, a new
  optional field in the pipeline JSON and in the browser editor's **Memory key**
  box, prompt variables included), otherwise the text arriving at the first step
  that uses memory, otherwise the prompt as before. Interactive runs are
  unchanged, the search still happens at most once per run, and the console's
  MEMORY line now names which rule supplied the key.

- **Model downloads survive leaving the app, and resume instead of restarting.**
  A download used to live and die with the screen that started it: switching
  away froze the transfer, and finishing setup cancelled it outright — on a slow
  connection that meant gigabytes fetched and thrown away. Downloads now run as
  background work with a progress notification (including a **Cancel** action),
  keep going while you use the rest of the app, and pick up where they stopped
  after a dropped connection. Unfinished bytes are held in a temporary file
  until the transfer completes, so a half-downloaded model can no longer look
  like an installed one, and the finished file registers itself even if you are
  no longer watching. Returning to the Models screen or to setup reconnects to
  the running transfer instead of showing an idle screen next to a ticking
  notification.
- **Setup now picks the fastest inference backend your device can actually
  run.** New installs used to start on the CPU backend, which made the very
  first answer — right after the model download — take the slowest path
  available. Setup now checks once whether the device supports GPU inference
  and, if it does, verifies it by really generating with the model
  (**Checking acceleration…** on the last setup step) before keeping it. On the
  reference device this is around five times faster on decode. Anything less
  than a clean success falls back to CPU silently, and the outcome is written
  into **Settings → Models → Inference backend**, so it is both visible and
  overridable. An explicitly chosen backend is never overridden.
- **`verifyDocsHygiene` build guard.** A new pure-JVM Gradle verification
  task (wired into `./gradlew check`, unit-tested in `buildSrc`) fails the
  build if any public-contour Markdown file reintroduces an LLM tool-call
  wrapper artifact or a reference to an internal-only planning document.
- **Releases are built, signed and published by the CI workflow, from a tag.**
  Pushing a `v*` tag now runs the full `check` gate and then assembles the three
  release artefacts — `full` APK, `full` AAB and `foss` APK — signs them, and
  attaches them to a GitHub Release generated from this file. The release
  build falls back to the *debug* signing identity when signing credentials are
  missing rather than failing, so the workflow verifies the signer twice: once
  against the keystore before the build starts, and once against the finished
  artefacts, refusing to publish anything not signed by the expected
  certificate. It also refuses a tag that disagrees with the declared
  `versionName`. `workflow_dispatch` runs the whole thing as a dry run without
  creating a Release. Procedure and one-time provisioning:
  [`docs/release.md`](docs/release.md).
- **Groundwork for a trigger-evaluation journal.** A new on-device,
  SQLCipher-encrypted store records why each automation trigger did or did
  not fire (fired / re-armed / a typed skip reason) and the eventual outcome
  of every background run it started — the durable data behind upcoming
  background-reliability diagnostics. Retained for 30 days (with a hard record
  cap) and cleaned up in the existing daily maintenance window; nothing it
  holds ever leaves the device.
- **The trigger-evaluation journal now records at every decision point.** Each
  time an automation trigger is evaluated in the background, exactly one entry
  is written the moment the decision is made — fired, re-armed, or a typed skip
  reason — tagged with which background surface woke it. When a trigger fires,
  the entry is later completed with the run's terminal fate: success, a typed
  failure, a platform kill (the hosting process was killed), a deliberate stop
  (the user cancelled the run), or a background-approval timeout — each kept
  distinct so a platform-reliability problem is never confused with a failure or
  an intended stop. Recording is a pure observer: a journal write failure is
  logged but never alters or aborts the run it describes.
- **See what your triggers are doing, without a cable.** Tapping a trigger now
  opens a detail screen with its **evaluation journal** — a day-by-day timeline
  of every time the trigger was checked in the background, written in plain
  language: whether it fired, was re-armed, or was skipped (and why — e.g. "the
  condition wasn't met at 07:15"), and for each run it started, how that run
  ended (completed, failed with the reason, stopped by the system, you stopped
  it, or timed out waiting for approval). Each trigger in the list also carries
  an at-a-glance **health badge** — Healthy, Overdue (the phone hasn't checked it
  in longer than expected — the tell-tale of aggressive battery savers), or Last
  run failed — each shown with an icon and a label, never colour alone. An
  overdue trigger's detail explains the likely cause in the header. This is the
  window onto the background so a trigger that "just didn't happen" is never a
  mystery.
- **Setup timing on the Usage statistics screen.** The on-device statistics now
  include how long the path from opening onboarding to your first successful run
  actually took, split into the model download and the time without it — the
  download is bound by your connection, not by the app, so the two are judged
  separately. The markers behind it are recorded once per install (a repeated
  pass through onboarding never moves them), are opt-in-gated like every other
  local count, and travel in the voluntary text/JSON export. Nothing here leaves
  the device.
- **Debug-only trigger-journal dump for background-reliability soak testing.** A
  developer diagnostic — present only in debug builds, never shipped — writes the
  full trigger-evaluation journal to a plain-JSON file that `adb pull` can
  retrieve, so a multi-day background soak can be analysed offline without opening
  the app or decrypting the on-device database. Nothing it produces leaves the
  device.
- **Groundwork for archiving chats.** Chat sessions can now carry an archived
  state on device — the storage and logic behind an upcoming archive surface for
  a chat list that grows with use. Archiving is non-destructive by construction:
  it changes only which list a conversation appears in, keeps every message,
  trace and run record intact, and is fully reversible. Existing chats upgrade as
  not archived, so the list looks exactly as it did before, and new activity in
  an archived chat (a trigger or scheduled run) deliberately does not pull it
  back — only you do.

### Fixed

- **A local Ollama or MCP server over plain HTTP now actually works.** The app
  carried a hard-coded list of fourteen private IP addresses as the only ones it
  would talk to unencrypted. That list ships inside the app, so you could not add
  your own, and it matched a real home network only by coincidence — meaning the
  self-hosted setup this app is built around simply did not connect for most
  people. Android's own network config cannot express "any address on my
  network", so the rule now lives in the app: any address on your local network
  works, once you approve that specific address. Saving a local `http://` address
  shows a notice naming it, explaining that anyone on the network can read what
  you send, and asking you to approve it; nothing is sent until you do, and the
  approval covers that address and port only. Unencrypted connections to public
  addresses are refused outright and cannot be approved, on every path including
  a redirect that tries to downgrade a secure connection mid-request.
- **Retry now retries.** After a failed message the **Retry** button only cleared
  the error tile — the run it was offering to repeat never happened. It now
  re-runs the message that failed, with its image if it had one. Text you typed
  while reading the error is left in the composer rather than being sent, and the
  failed message is not added to the conversation a second time.
- **Two tools with similar names were impossible to tell apart.** Tool names in
  an expanded MCP server were cut to a single line, so `get-resource-…` and
  `get-resource-…` looked identical while each row carried its own on/off switch
  — an easy way to disable the wrong tool. Long names now wrap to a second line.
  Rows whose names already fit are unchanged.
- **A failed turn is now part of an exported chat.** A run that fails never
  becomes a message, so exporting a conversation whose only turn failed produced
  a file with the question, no answer, and no sign anything had gone wrong — the
  worst possible file to attach to a bug report. The export now also lists the
  runs that failed, were cancelled or were interrupted, with the error and the
  timing.
- **Retry notices reached the console after the fact.** When a cloud provider
  blipped and the call was retried, the `Cloud retry 1/2` lines were held back
  until the whole answer finished. On a run where the retries happened in the
  first seconds and the request ultimately failed, that meant staring at nothing
  and then receiving everything at once. Each retry is now reported as it
  happens.
- **A pipeline step pointed at a local server could quietly switch to a cloud
  one.** The node editor offers a single *OpenAI-compatible* option covering both
  DeepSeek and a self-hosted server such as Ollama, and saving the step's settings
  resolved that option to DeepSeek regardless of which one the step actually used.
  Opening an Ollama step, changing nothing but the title, and saving was enough to
  repoint it at a third-party endpoint — with no warning, and no visible difference
  in the editor afterwards. For an app whose premise is that your data stays where
  you put it, that is the worst possible silent change, so a step now keeps the
  provider it already had unless you pick a different one yourself. The same fix
  covers the engine picker on Tool, Condition, Router, Decomposition and Evaluation
  steps.
- **The Tools screen could report a healthy server the agent could not reach.**
  Connection health and the connections the agent actually used were tracked
  separately, so a session that had already failed for one could still look fine to
  the other: the screen showed a tool count and an "ok" badge while the next tool
  call went to a dead connection. Both now share one set of connections, so the
  badge describes the session your tools will really use, and a failure seen on
  either side retires it for both.
- **Risk level of a tool from an external server could not be adjusted.** Every MCP
  tool was pinned to "ask me first", which sounds safe but cut both ways: a
  genuinely destructive remote tool could not be raised to the destructive tier
  (and so was never covered by *Block destructive tools*), and a plainly read-only
  one could not stop prompting. The per-tool risk setting now covers MCP tools as
  well, remembered per server so the same tool name on two servers stays two
  separate decisions. The server's own claim about a tool is deliberately ignored:
  only you can lower a confirmation prompt, never the server asking to skip it.
- **A mistyped tool name failed later and more obscurely than it needed to.** When
  a step let the model choose the tool, the chosen name was not checked against the
  list the model had been offered, so a mangled name surfaced further down as an
  internal-sounding "risk lookup failed". The name is now checked where the choice
  is made, and the message says plainly that the tool is not in the available list.
  A near-miss is not silently corrected to the closest match — running a different
  tool than the one named is exactly what the confirmation prompt exists to prevent.
- **Working in a language other than English broke tool calls and routing.** A
  model given a Russian question answers in Russian — and then keeps doing so at
  every step that follows. For an answer that is exactly right; for the machinery
  in between it is not. The step that splits a task into subtasks was writing
  those subtasks in the user's language, the step that picks a tool then matched
  them against an English tool catalogue, and the routing steps compare their
  answer against fixed English keywords. So the pipeline ran, took a wrong turn or
  called nothing at all, and said nothing about why. Every bundled pipeline now
  states which language each of its steps works in: steps whose output is read by
  the pipeline work in English, and the step that writes the answer translates it
  back into your language. The starter prompts used by steps you build yourself
  say the same. Two deliberate exceptions stay as they were — a Wikipedia search
  term is written in your language so it reaches the right edition of Wikipedia,
  and the translation pipeline keeps its own target language.
- **Notifications looked like they came from three different apps.** Every
  notification the agent posts — a trigger firing, a background task finishing or
  failing, a question, a request for approval — borrowed a stock Android icon
  picked for rough resemblance. A finished background task showed the **download
  complete** icon, so the status bar reported a download from an app that had
  downloaded nothing, sitting right next to a foreground notification carrying the
  real Knotwork mark. The approval buttons were worse than mismatched: **Approve**
  was a media *play* symbol and **Deny** was a *trash can*, so on a destructive
  confirmation the choice read as "run it" or "delete it" — the opposite of what
  the buttons do. Notifications now use one deliberate set: the Knotwork mark for
  anything that needs nothing from you, a plain tick or cross for an outcome, a
  shield for a decision you have to make, a speech bubble for a question, and a
  tick/cross pair on the buttons.
- **Importing a pipeline no longer loses settings without telling you.** When a
  pipeline file contained anything the app could not read, those settings were
  discarded in silence — the graph loaded, looked fine, and part of its node
  configuration was simply gone. The import now names exactly what it could not
  read, path by path, so you can judge whether the loss matters before keeping
  the result. This is reported even when the file's version stamp matches the
  app's: new fields are added to the format without changing that stamp, so a
  file written by a newer build can claim the same version and still carry
  settings this one cannot read. That case was previously invisible — a matching
  version produced no warning at all.
- **A cloud answer cut off in transit was shown as if it were complete.** When
  the connection to a cloud provider dropped part-way through a reply, some
  providers end the stream so quietly that it looks exactly like a finished
  answer — the only difference is that the provider never says *why* it stopped.
  The half-written reply was accepted, passed to the next step of the pipeline
  and shown as the result. A reply that never reported a completion is now
  treated as the failure it is: the partial text is discarded and the run says
  the response was cut off, so nothing incomplete is presented as final.
- **A resumed run could pay for the same cloud call twice.** When a run was
  interrupted, a cloud step that had already completed was supposed to be
  replayed from its record rather than re-sent. That record was written to a
  buffer that only reaches storage every half-second, so a process death inside
  that window lost it — and the resumed run called the provider again, at real
  cost. The record is now saved the moment a cloud step finishes, as was already
  done for tool calls.
- **A cloud provider that stopped responding could hold a run for 15 minutes.**
  Cloud requests inherited a 900-second network deadline that nothing in the app
  had chosen. Cloud calls now allow 60 seconds of *silence* from the provider
  before giving up — measured between pieces of the answer, so a long reply that
  is still arriving is never cut short, while a dead connection is no longer
  waited on for a quarter of an hour.
- **A Cloud step that could not run said so in the reply instead of failing.**
  When no provider was configured, or the selected one had no API key, the
  explanation was passed down the pipeline as though the model had written it —
  a run could end "successfully" with the words *"Error: … not configured"* as
  its answer. Such a step now fails properly and the run stops with the reason.
  Relatedly, when cloud access was switched off by the **Block network from
  local model** restriction, the message blamed a missing API key and sent you
  to the wrong screen; it now names the restriction that is actually in force.
- **The status line claimed your device was generating an answer the cloud was
  producing.** While a Cloud step streamed, the pill still named the on-device
  backend (`gpu` / `cpu`) — the one place a local-first app must not be vague
  about where a prompt is being processed. It now reads `cloud` for cloud steps
  and keeps naming the real device backend for on-device ones, which is what
  makes a silent fallback to CPU visible. The label also lost a redundant word
  so it fits on one line.
- **A dropped cloud connection could report itself as the word "null".** When the
  provider cut the connection mid-answer, the underlying failure carried no
  message of its own and the error card ended up reading *"Exception during
  streaming: null"* — a correct failure with an unreadable reason. The kind of
  failure is now named instead.
- **Cloud error messages opened with the same line twice.** A failed cloud call
  showed its client name on two consecutive lines before the actual diagnosis,
  costing a line in a card the user is meant to read quickly. The repeat is now
  collapsed.
- **A provider error could carry your API key into the logs.** One provider
  authenticates by putting the key in the request address, so an ordinary
  network error arrived with the key inside its text — and that text reached the
  run console, the saved run history and the device log. Credentials are now
  stripped from provider errors before they are shown or stored.
- **One external tool that never answered could freeze every chat in the app.**
  Messages are processed one at a time, so a tool call that hung took the whole
  queue with it: new messages in any chat were accepted, given a title, and
  then sat on "Generating…" forever with an empty console and no explanation —
  observed for an hour and a half against a server that simply stopped
  replying. Two independent limits now apply. A call to an MCP server gives up
  after 60 seconds and is reported as a failed tool call, like any other tool
  error; the previous limit was an accident of whichever HTTP engine happened
  to be bundled — 10 seconds, too short for tools that search or run a model of
  their own, and liable to change silently on any dependency upgrade. And
  independently of any one tool, a run that goes five minutes without a single
  sign of progress is ended with a message saying so, and the queue moves on.
  The five minutes count silence, not length: a long answer streams
  continuously, so slow-but-working runs are never cut short.

- **A tool could run twice after the app was killed mid-task.** When a task is
  interrupted — the app swiped away, the system reclaiming memory — resuming it
  replays the steps that already finished instead of repeating them, and that
  guarantee matters most for tools, which have already acted on the world. It
  held only once the record of the finished step reached storage, and records
  are written in batches up to half a second apart. A process death inside that
  window lost the record, and the resumed task called the same tool a second
  time: for a tool that asks permission you were asked again, but anything set
  to run without asking simply repeated its effect. A finished tool call is now
  recorded durably the moment it returns.

- **A tool list that would not stop loading.** A server that accepted the
  connection and then went quiet left its row spinning on "Connecting…" for as
  long as the app stayed open. The handshake now gives up after 30 seconds and
  says what happened.

- **Any tool whose input was not text was unusable.** Every parameter an MCP
  server declared was passed on to the model as free text, dropping both its
  real type and its description. Asked for a duration of 300 seconds, the model
  would faithfully send the *word* "300", and the server rejected the call —
  so a tool taking a number, a switch, a list or a choice from a fixed set
  could never be called successfully. Types and descriptions now reach the
  model as declared.

- **A server could stay stuck on "Connecting…" after you edited it.** Saving a
  change while its tool list was mid-load — or simply leaving the screen —
  cancelled the load without resolving the row, which then span indefinitely
  until refreshed by hand. Cancellation now settles the row honestly: back to
  connected if a usable tool list is already held, otherwise a plain
  "Connection attempt was interrupted".

- **A background approval you did not answer within the first minute could
  never be granted afterwards.** When nobody responds straight away the run
  parks and waits for you — for up to a day by default. Tapping **Approve** on
  the notification after that point did nothing useful: the run refused to
  continue, and in the chat it sat on "generating…" indefinitely, surviving
  restarts. The cause was bookkeeping: while the request waited, ordinary
  progress messages from the step doing the work were mistaken for the wait
  having ended, so the run stopped advertising itself as waiting and no answer
  could be applied to it any more. Any pipeline that runs another pipeline
  inside it was affected. Such a run now stays answerable for its whole window,
  and a run that genuinely cannot be continued is stopped with a reason instead
  of being left hanging.

- **An unrelated crash could quietly move you off GPU for good.** Before trying
  a GPU or NPU backend the app leaves a marker, because a missing driver can
  kill the process outright before any error handling runs; finding that marker
  on the next start meant the backend was blamed and switched to CPU
  permanently. But the marker only records that the app died while starting the
  model — not why. Being swiped away, or reclaimed by the system for memory, or
  frozen by the device's battery manager leaves exactly the same trace, so a
  single unrelated kill was enough to cost you GPU acceleration silently and
  for good. A first unexplained failure now only runs that one session on CPU
  and leaves your choice untouched; the saved setting changes only if a second
  start in a row fails the same way. The status line beside the answer also
  names the backend actually in use — `generating (GPU) · 65 tok` — so a
  fallback is visible while it is happening rather than discovered later by its
  slowness.

- **A background run could die the moment it started, right after you closed
  the app.** Swiping the app away tells Android to reclaim what it can, and the
  model is unloaded in response — correct when nothing is running, but the
  unload could not take effect immediately and instead landed a moment later,
  by which time a trigger-started run had already loaded the model for itself.
  The run then failed with "LLM Engine not initialized" seconds after firing.
  An unload now applies only to the model it was actually asked to release, so
  one that arrives late leaves a newly loaded model alone. Going to the
  background also no longer interrupts work already under way: a run that is
  generating keeps the model until it finishes. Genuine memory pressure still
  frees the model immediately, in-flight generation included — being killed
  outright is worse than losing one answer.

- **Refreshing an MCP server's tool list could break a tool call in progress —
  and told the agent the tool did not exist.** Tapping the refresh icon next to
  a server (or any tool-list fetch that had outlived its five-minute cache)
  re-established the connection from scratch instead of simply asking for the
  tools again. If the agent happened to be calling a tool from that server at
  the same moment, the connection was pulled out from under the call, which
  then failed as either "tool not found" or a session error — the first of
  which is actively misleading, since the agent would go on to plan around a
  capability it still had. Refreshing now re-lists the tools over the existing
  connection and reconnects only when there is no usable one, when the server's
  address, credentials or transport changed, or after a failure; and a call in
  progress can no longer observe a half-replaced connection. Disconnecting also
  ends the session on the server rather than just dropping the socket, so
  servers no longer accumulate abandoned sessions — one run of the reference
  server had collected four.

- **A task the agent scheduled for itself could not be found, let alone
  stopped.** Every scheduled task showed up under **More → Active tasks** as
  the same anonymous "Background Task", so cancelling a specific one was
  guesswork — and there was no way to stop them all. A task whose prompt told
  the agent to schedule its own successor was therefore unstoppable in
  practice: only one task is ever queued, so cancelling it changed nothing
  while the run that would enqueue the next one was still going, and clearing
  the app's data was the only way out. Scheduled tasks are now named — the row
  leads with the start of the task's own prompt, under it the repeat interval
  and the chat it reports into — and the screen offers **Stop all scheduled
  tasks**, which settles every one of them, running included. Automations and
  their triggers are untouched by it, and nothing is deleted. The agent is also
  now refused when too many scheduled runs have already started within the past
  hour, so a chain like that bounds itself instead of running until someone
  notices.
- **An automation that stopped to ask you something looked exactly like one
  that never asked.** A background run pausing for approval — the moment you
  are most likely to miss — left no trace in a trigger's evaluation journal
  once it was over: approve it from the notification and the run simply ended
  as **Completed**, indistinguishable from a run that needed nothing from you.
  Only an approval nobody answered was ever called out. A fired entry now also
  records the request itself: that the run asked, whether the answer had to
  come back from the notification shade, and how it was settled — approved,
  denied, answered, still waiting, timed out, or never delivered to you at
  all. Requests answered on the spot are recorded too, not just the ones that
  waited: the in-app wait lasts a full minute, so a promptly answered approval
  never reaches the shade, and recording only those would have missed the
  commonest case entirely. Diagnostic exports of the journal carry the same
  fields.
- **Bundled pipelines answered in the language of the question, not your
  device's.** Ask in English on a Russian phone and the reply came back in
  English. Some bundled pipelines had always been explicit about the language
  and others had never mentioned it, so behaviour depended on which one you
  picked. Every step whose text you actually read now works in your device
  language. Steps that emit a routing keyword or a true/false decision are
  deliberately untouched — translating those would break the branching — and
  styled translation still translates into the language its prompt names.
- **The tool-using agent template called a tool even when it plainly did not
  need one.** Asked for `17 times 23`, it worked out the answer, then ran a web
  search anyway and reported that the results did not contain the product. The
  template promised in its instructions to answer directly when no tool was
  needed, but its wiring had no way to skip the tool. It now decides **first**,
  on your actual request, and then does one thing per branch: a request needing
  a lookup becomes a search term, runs the tool and gets summarised; a request
  that does not is simply answered. This also stops the empty-input case where a
  blank step produced a search for an invented topic.
- **The preset gallery opened on a plumbing template.** Bundled presets were
  listed in filename order, so the first card was *Clarify, then act* and the
  scenarios from setup were scattered through the list. The order is now
  deliberate: setup scenarios, then the end-to-end showcases, then the
  build-your-own templates.
- **Cloud chat could echo its own context back at you.** The first reply in a
  conversation could restate the remembered entries and the model's own
  reasoning before getting to the answer. The cloud step is now told to reply
  with the answer only.
- **The bundled preset gallery showed four templates nobody was meant to pick.**
  The four *Subtask —* entries are building blocks the showcase agent runs
  internally, not starting points — their own descriptions said so. They no
  longer appear in **+ From preset**, in **More → Library**, or in the browser
  editor's preset list, while the showcase that composes them keeps working
  exactly as before.
- **Every bundled preset now opens with its own quick actions.** A pipeline
  spawned from a preset — including the one created on first launch and the
  three offered during setup — showed the generic starter cards, because no
  bundled preset declared any of its own. Each now suggests three prompts that
  fit what it actually does, naming the tools it really wires.
- **A router in a bundled preset could not be edited.** Opening the *Router*
  step of **Routed local + cloud** showed an empty list of categories and an
  error that blocked saving, because the preset shipped without the editor-side
  configuration and the fallback could not reconstruct the categories from the
  wiring. Every bundled preset now carries the full editor configuration on
  every step, so each one opens ready to edit.
- **The pass/fail judge template could never return a verdict.** The bundled
  *Pass/fail* evaluation prompt still asked for the JSON shape used before
  evaluation steps switched to routing on a `Pass` / `Retry` / `Fail` verdict,
  so the step always failed its check, retried, and fell through to its default
  branch. Both evaluation templates now lead with the verdict, and the scored
  one reports its score below it.
- **The browser editor's preset list was missing the scenarios from setup.**
  Styled translation, share handler and virtual companion ship with the app but
  had never been mirrored into `pipeline-editor.html`. The editor now carries
  the same catalogue as the app, scenarios first.
- **Memory compaction could quietly replace a fact with a wrong summary of it.**
  The background pass handed a group of stored facts to the on-device model,
  took whatever came back as their replacement, and deleted the originals — the
  only copy. Nothing checked that the summary actually said what the facts said,
  so a model that dropped one of them, or answered about something else, cost
  you that memory with no way to notice or recover. A summary now has to be
  shown to represent an entry before that entry can be removed: entries it
  skipped stay stored word-for-word, and a summary that represents fewer than
  two of them is discarded with the whole group left intact. The write and the
  deletions also became a single transaction, so a failure part-way can no
  longer leave a summary sitting next to the very entries it replaced. Where a
  summary and the original wording of one of its facts are both present — after
  importing an older backup, say — retrieval now shows the agent the original,
  which previously lost out because the summary was newer and counted as
  fresher. The compaction preview is correspondingly an upper bound: a run can
  free less than it estimated, never more.

- **Long-term memory stopped recalling anything more than a few weeks old.**
  A memory entry's relevance score was multiplied by a weight that fell to
  zero as it aged, and the resulting product was what the similarity threshold
  judged — so age worked as an expiry date rather than a preference. At the
  default settings a perfectly on-topic entry was already scored out after one
  half-life (30 days) and, past two, could not be recalled at any relevance,
  even though the entry was still listed on the Memory screen and still
  matched the query. Freshness is now a bonus added on top of relevance
  instead of a multiplier applied to it: the threshold judges how well an
  entry matches, nothing else, so an old but genuinely relevant entry stays
  recallable indefinitely, while a fresher entry still wins when two match
  equally well. The **Recency half-life** setting keeps its meaning — it is
  how quickly that freshness bonus fades — and pinned entries are unaffected.
- **Near-duplicate memory entries were detected by comparing their opening
  characters.** Retrieval collapsed entries that shared their first 80
  characters, which both merged and missed the wrong things: pipelines that
  write with a fixed preamble (an evening journal, a translation log)
  produced unrelated facts with identical openings and only one of them ever
  reached the model, while the same fact saved twice in different words kept
  two of the limited recall slots. Duplicates are now recognised by meaning,
  using the same measure that already stops a duplicate from being stored in
  the first place. A pinned entry always survives a merge, and an entry
  waiting to be re-embedded is never merged into another.

- **An approval tapped at the exact moment a background run gave up waiting
  could be ignored.** When nobody answers a tool-confirmation prompt in time,
  the run stops waiting live and parks so it can be approved later from the
  notification. For a brief moment both states existed at once, and an approval
  arriving in that window was applied to the wait that had already ended — the
  notification reported success while the run stayed parked, waiting for a
  decision that had in fact been made. The live wait is now retired before the
  run parks, so an approval always reaches whichever of the two is actually in
  effect.
- **Release builds crashed on the first message that touched long-term
  memory.** In a minified (release) build, initialising the on-device text
  embedder threw `ExceptionInInitializerError` and killed the app process, so a
  fresh install could send a message but never receive the reply. The cause was
  code shrinking renaming a logging library that identifies its own call frames
  by name; the affected package is now pinned, and a new release-build check
  fails the build if that protection is ever lost again. Debug builds were never
  affected, which is why this survived undetected — anyone running a release
  build of `0.6.0` should update.

- **"Privacy policy" in About opened a link to nowhere.** The button pointed at
  the `#privacy` section of the project README — a section that did not exist,
  so the link quietly dropped you at the top of the page instead. The README now
  has a real **Privacy** section (no account, on-device by default, local-only
  statistics, opt-in crash reporting, encryption at rest, confirmation before
  the agent acts), and the button lands on it. A new build-time guard resolves
  the link's anchor against the README's actual headings, so renaming the
  heading on either side now fails the build rather than shipping a dead link.

### Changed

- **The signing identity changed — updating from an earlier build means
  uninstalling it first.** Builds up to and including `0.6.0` were signed with
  the Android debug keystore; this is the first release signed with a real
  release key. Android refuses to update an installed app in place when the
  signer changes, so an update over `0.6.0` or earlier fails with a signature
  mismatch and the old build has to be uninstalled — which deletes its local
  data (chats, memory, custom pipelines). Export anything you want to keep
  before you do. This is a one-time break: releases from `0.7.0` onward share
  one signer and update normally.
- **The roadmap no longer describes shipped work as upcoming.** It still
  announced the previous release line as current, and its near-term section
  listed four directions — proven background execution, a repeatable
  time-to-first-value measurement, memory and preset quality, and a chat
  archive — that have all since shipped, alongside a section awaiting the first
  release-signed build that this release *is*. Those are now stated as things
  the product does, and the near-term section says what is actually next:
  getting the app into F-Droid and Play (including the open question of whether
  a prebuilt native inference library clears F-Droid's inclusion policy), a
  cookbook of recipes per node type, and whatever the first outside reports turn
  up.
- **The README now shows a real pipeline instead of a stack of node cards.**
  The *Pipeline editor* screenshot was rendered from the design-system
  regression baseline, which meant it showed one card per node type in a
  vertical list — an accurate picture of the catalogue and a misleading picture
  of the product. It is now a capture from a phone: a 22-node pipeline on the
  canvas, with routers, nested sub-pipelines, queues and tool steps wired
  together, in both themes. The other three screenshots are still rendered from
  baselines and say so; the canvas has no baseline to render from because it is
  an app screen rather than a design-system component.
- **The known limits of MCP servers and cloud providers are now written down.**
  A round of directed testing against real MCP servers and cloud providers
  turned up several behaviours that were true of the app but documented
  nowhere, and a few of them look like defects until you know what they are.
  The user guide now says so plainly: the tool count on an MCP server row is
  the list *that server published to this app*, so a healthy `13 tools · ok`
  can legitimately be fewer than the server's own catalogue (measured against
  the protocol's reference server, which offers 16); how long a server or a
  provider is given to answer, and what the resulting error messages mean; that
  a cloud answer cut off mid-stream is discarded rather than shown as a
  finished reply — and, honestly, that this protection covers OpenAI, DeepSeek
  and Google but **not** Ollama or Anthropic, with the reason for each; that
  a question can take a different route through a routed pipeline depending on
  what is already in the conversation, and how to make that repeatable; and
  that leaving the app can end a background run within seconds unless the app
  is excluded from battery optimisation, which the app never asks for and
  cannot grant itself. The contributor guide gained the step a new cloud
  provider must not skip — deciding, by measurement rather than by reading the
  provider's documentation, whether a missing completion signal means the
  answer was truncated. No behaviour change: this is documentation catching up
  with what was measured.
- **Controls that did nothing were removed from the step editor.** The Cloud
  step offered Temperature, Max tokens and Timeout, and the on-device step
  offered temperature, top-P and max new tokens. All of them were saved, and
  none of them were read: moving any of these sliders changed nothing about the
  answer. Timeout was the worst of them — the person reaching for it is the one
  whose provider has already hung, and it was guaranteed not to help. They are
  gone until per-step settings actually reach the engine. Existing pipelines are
  untouched: the saved values stay in the file and keep round-tripping, they are
  simply no longer presented as something you can change.
- **What the cloud retry policy does and does not do is now written down.** The
  user guide states plainly that a provider asking you to wait a specific time
  (a `Retry-After` header) is not honoured — the backoff is the same with or
  without it — so under a real rate limit the app knocks sooner than it was
  asked to. Measured, not assumed; the cause is in the upstream client library.
- **Build toolchain refreshed to clear the `NewerVersionAvailable` /
  `AndroidGradlePluginVersion` lint gate.** Gradle `9.6.1` → `9.7.0`, `dev.detekt`
  `2.0.0-alpha.5` → `2.0.0-alpha.6`, Roborazzi `1.70.0` → `1.72.0` (plugin
  plus the three test artefacts), and the Compose BOM `2026.06.01` →
  `2026.08.00`. These checks are hard errors in this project on
  purpose, and a local `check` cannot always see them — lint answers from a cached
  version index, so the failure can appear only on a clean CI run. No production
  code changed, no screenshot baseline moved, and the static-analysis guide now
  points at the version catalogue instead of restating a version number that
  goes stale the moment it is bumped. One dependency is deliberately held back:
  the on-device inference engine stays on its current version, because the newer
  release adds nothing for the Android path and no automated check on this
  project can exercise a native inference binary.
- **What long-term memory recalls is now written down.** The user guide gains a
  *"What the agent recalls, and when"* section: memory is searched once per run,
  at the first step that reads it; the similarity threshold is a gate nothing
  can be bonused through; age never disqualifies an entry and freshness only
  breaks ties; pinning always wins; the original wording beats a summary of it;
  and near-duplicates share one slot. The architecture guide's memory
  invariants now describe where the search key comes from, and its persistence
  section states that archiving a chat writes a flag and deletes nothing.
- **Cloud model line-up follows the upstream client.** With the Koog client
  updated to 1.1.1, Google's `gemini-3-pro-preview` is no longer offered — the
  upstream catalogue replaced it with `gemini-3.1-pro-preview`, which now takes
  its place in the model picker. If a provider was pinned to the removed model,
  pick the new entry: an unrecognised saved model falls back to the default
  Gemini flash model. Also bumps the Android Gradle plugin, `org.json` and
  Roborazzi to their current releases.
- **Trigger observability is documented end to end.** The user guide now
  explains the health badges and the evaluation journal in the terms the
  screens actually use, adds a *"A trigger didn't fire"* troubleshooting path
  that separates "the journal explains it" from "there is no entry, so the
  phone never woke the app", and states plainly the one gap that remains: an
  approval you grant in the background leaves no distinct trace, only a
  timeout does. The architecture guide gains the journal's write points,
  invariants and retention bounds alongside the trigger flow diagram.
- **Documentation hygiene pass across the public contour.** The product is
  now referred to consistently as **Knotwork** (an on-device AI agent for
  Android) throughout the docs, the near-term roadmap is realigned to lead
  with reliability and quality of what already ships, a stray artifact at
  the end of `README.md` was removed, several dangling references to
  internal-only documents were inlined or dropped, and duplicated version
  numbers were replaced with a single source of truth.

- **The repository is now `knotwork`.** It carried the old working title
  `PersonalAndroidAIAgent` while the app itself had long since become Knotwork.
  GitHub redirects the old URL — existing clones, links and `git` remotes keep
  working — but every link in the documentation, the CI badge, the clone
  command, the issue-template URLs and the in-app privacy link were rewritten to
  the new address rather than left to lean on that redirect, which disappears
  permanently if anyone ever creates a repository under the old name.

- **What you can expect when you share a pipeline file is now stated outright.**
  Exported pipelines and bundles carry a version stamp, and it was never
  explained what that stamp is worth. Before 1.0 it is a marker, not a promise:
  a file whose stamp does not match the build importing it is not rejected — it
  loads on a best-effort basis behind a warning, and any field the build does
  not recognise is dropped without saying which. The README pre-release notice
  now says so, and the user guide gains a *Sharing pipeline files* section with
  the practical consequence spelled out (keep the original — re-exporting after
  a lossy import overwrites your only complete copy). From 1.0 the format
  becomes a semantic-versioning contract with migration on import.

- **A pointer for people arriving from Tasker, n8n or Zapier.** The README and
  the user guide now say once, in plain terms, that a *pipeline* is what those
  tools call a *workflow*. The app's own wording is unchanged: it says
  "pipeline" on every screen, and the docs match it.

## [0.6.0] - 2026-07-16

### Added

- **Onboarding leads with value, not the machine — a scenario gallery.**
  First-launch onboarding now opens with *"what should your agent do for
  you?"* and a small gallery of ready-made scenarios — **Styled Translation**
  (an on-device translator that keeps a message's tone, register and dialect),
  **Share Handler** (share anything and it becomes a structured note in your
  inbox, confirming before it writes), and a featured **Virtual Companion**
  (a mood-aware chat). Picking one sets up everything it needs in a single tap:
  the matching pipeline is materialised as your default, its entry surface is
  wired where it uses one (Share Handler starts listening on the system Share
  sheet), and **exactly the model that scenario needs** is downloaded — framed
  with its size and live progress so the wait is a known one rather than a
  frozen screen. The final step opens straight into a working chat only once
  the model has warmed, so you never land in a cold session. A **Start from
  scratch** card keeps the build-it-yourself path one tap away. The three
  scenarios also join the bundled preset catalogue, so they are available from
  the pipeline library's **From preset** picker at any time.
- **Pipelines suggest their own starter prompts.** A pipeline can now carry a
  list of **sample prompts** shown as the quick-action cards on a new chat's
  empty state. Because the suggestions come from the pipeline that will actually
  run them, the `uses · …` tool hint on each card is honest — it reflects the
  tools that pipeline really wires, instead of a fixed promise that a simple
  chat pipeline could not keep. The built-in default pipeline ships three
  fitting starters (a web lookup, an on-device answer, and a multi-step plan);
  a pipeline that declares none falls back to a generic, tool-agnostic set. The
  field rides the pipeline JSON, so it travels with export / import and bundles.
- **Move whole compositions in one file — pipeline bundles.** A pipeline that
  calls sub-pipelines (through `PIPELINE` nodes) can now be exported as a single
  **bundle** that carries the root plus every sub-pipeline it depends on. In the
  library, a pipeline's overflow menu gains **Export bundle (with dependencies)**;
  the existing **Import JSON** affordance detects a bundle and imports the whole
  set at once. The browser editor gets matching **📦 Export bundle** / **📦 Import
  bundle** buttons. When an imported pipeline's id already exists, the app now
  asks whether to **Replace** it or **import as a copy** instead of silently
  overwriting — for both bundles and single-pipeline imports. Triggers, MCP
  servers, prompt presets and settings are intentionally out of scope; a bundle
  is pipelines only.
- **Shares can accumulate in one chat.** By default, everything you share into
  the app (text and images) now lands in a single running **Shared** chat — new
  shares are appended instead of spawning a fresh chat each time, which was noisy
  and hard to follow. A new **Settings → Background & triggers → Keep shares in
  one chat** toggle (on by default) controls it; turn it off to get a new,
  auto-named chat per share (the previous behaviour). Either way a share never
  disturbs the chat you were already in.
- **Wi-Fi triggers can target specific networks.** A Wi-Fi trigger can now be
  scoped to one or more network names (SSIDs) in the editor, so it fires only on,
  say, your home or office Wi-Fi instead of any connection. Leaving the list empty
  keeps the previous any-Wi-Fi behaviour. Because Android derives the connected
  SSID from location, scoping a trigger requests the location permission the first
  time you add a name; if it is not granted, the trigger stays saved but never
  fires (fail-safe). The network names are used only for the on-device match and
  never leave the device.
- **`append_file` workspace tool.** A new built-in file tool that adds text to the
  **end** of a workspace file, creating it on the first call and always keeping
  existing content (there is no overwrite). It makes "accumulate entries in a daily
  log / report" possible in a single tool call — previously only `write_file`
  (whole-file replace) and `edit_file` (anchored find-replace) existed, neither of
  which can append without first reading the file back. Risk **SENSITIVE** (asks for
  confirmation), routed through the same `AgentWorkspace` sandbox (containment +
  quotas) as the other file tools.
- **FOSS / F-Droid build.** A new `foss` product flavour ships with **no
  Firebase/Google dependency** anywhere in its graph, unblocking the F-Droid
  channel. Crash reporting is now behind a flavour-agnostic `CrashReporter`
  abstraction: the `full` flavour keeps Firebase Crashlytics, while `foss` binds
  a no-op implementation and hides the crash-reporting consent toggle entirely.
  The proprietary `google-services` / `firebase-crashlytics` Gradle plugins are
  applied only for `full` builds, so `./gradlew assembleFossRelease` never loads
  them. See [docs/release.md](docs/release.md) § *FOSS / F-Droid build*.
- **Entry surfaces.** The agent now reaches outside its own screen. A **share
  target** accepts text or an image shared from any app and runs your chosen
  pipeline over it, landing the result in a new chat you are taken straight to.
  **Launcher shortcuts** (long-press the app icon) offer "New chat" and
  "Pipelines", plus dynamic shortcuts to your most recent chats. A **Quick
  Settings tile** runs a "duty" pipeline in the background with one tap from the
  shade and notifies you when it finishes. Each surface is **off until you point
  it at a pipeline** (privacy-first default): bind one per surface in *Settings →
  Background & triggers*, or from a pipeline's row menu in the library ("Use for
  sharing" / "Use for Quick Settings tile").
- **Automation triggers — foundation.** Groundwork for running a bound pipeline
  in the background when a condition is met: a time schedule (every N, or daily
  at a set time), the device starting to charge, or gaining network / Wi-Fi
  connectivity. A trigger is inert until you bind a pipeline and is auto-disabled
  if that pipeline is later deleted; a fired trigger runs through the same
  background path as a scheduled task. The first wave is deliberately limited to
  low-sensitivity conditions (no notification-listener / location / SMS access).
  The management UI lands in a following change.
- **Manage automation triggers.** A new **Triggers** screen (under *More*) lists
  your triggers, each showing a plain-language condition ("Every day at 08:00",
  "When charging connected", "When Wi-Fi connects") and its bound pipeline, with
  an inline switch to enable or disable it. A full-screen editor creates and edits
  triggers: pick the condition type (interval presets `15m / 30m / 1h / 6h / 24h`
  or a custom value, a daily time, charging, or network with a Wi-Fi-only
  option), bind a pipeline, write the input prompt, and toggle enabled. An unbound
  trigger reads as inert ("No pipeline — tap to bind"), and a first-run empty
  state explains the "trigger → background run → result in chat" model. Changes
  take effect immediately — creating, editing, enabling or deleting a trigger
  re-syncs the background runtime without waiting for the next launch.
- **Charging triggers fire instantly.** A charging trigger now runs within
  seconds of plugging in — even when the app is closed — via a
  charging-constrained WorkManager job (`setRequiresCharging`), which the OS
  wakes through JobScheduler, instead of waiting for the next ~15-minute
  background poll. The poll remains as a backstop and re-arms the fast path on
  unplug; interval/daily/network triggers stay poll-driven.
- **Trigger results and notifications.** A fired trigger now lands its run in a
  dedicated chat session named after the trigger; recurring fires accumulate in
  that same conversation instead of spawning a new chat each time, and a deleted
  session is re-created on the next fire. A **"Trigger fired"** notification
  announces the start of a background run, alongside the existing **"Task
  completed" / "Task failed"** outcome notifications (same channel and toggle, no
  new surfaces) — each deep-links into the trigger's chat. Sensitive or
  destructive tools inside a trigger run surface the usual background
  **Approve / Deny** approval notification, so an unattended run can be settled
  from the shade without opening the app.
- **Local usage statistics (privacy-preserving).** A new **Usage statistics**
  screen (*Settings → Privacy*) shows fully on-device counts of how the app is
  used — runs per pipeline, run outcomes (completed / failed / cancelled /
  interrupted), trigger firings by kind, and active days. **Nothing on this path
  ever leaves the device** (enforced by a build-time guard that forbids any
  network import on the telemetry surface); the figures live in the existing
  encrypted database. Recording is on by default, clearly framed as local-only,
  and can be turned off or cleared at any time. A voluntary **Share as text** /
  **Export JSON** lets you take a snapshot for your own analysis — to a file or
  share sheet you choose, never automatically.
- **Search the settings.** The settings hub now has a search field. Typing
  filters every setting by name, description, owning category and synonyms (so
  `max` surfaces *Cap autonomous steps* via the synonym *max steps*), with the
  matched substring highlighted in each result. A result shows its category
  breadcrumb and Basic/Advanced tier; tapping it opens that category sub-screen,
  expands the Advanced section when needed, and scrolls to and briefly flashes
  the target row (a static accent under reduced motion). A calm "no settings
  match" empty state offers a one-tap clear. The index is built from the settings
  registry, so a setting added to the registry is searchable automatically.

- **More settings exposed in the redesigned Settings UI.** The tool-usage
  instruction and voice-input length (Generation), the PIPELINE-node max nesting
  depth and the structured-output repair budget (Pipelines), and the
  `$MEMORY_SUMMARY` default chunk limit (Memory) now have controls in Settings —
  previously persisted but not adjustable from the settings surface.

- **Discover models from Hugging Face.** A new **Discover** screen (top-bar
  action on the Models screen) browses and searches the curated
  `litert-community` organisation on the Hugging Face Hub. Each result card shows
  the repository's downloads, likes and licence; tapping it opens a detail screen
  listing the repo's engine-compatible `.litertlm` files with sizes. **Install**
  streams the chosen file through the existing download manager and registers it
  locally — gated behind an explicit **licence confirmation** dialog. Files
  already on disk render as **Installed**. Access-gated repositories show a lock
  badge and an inline Hugging Face token field (reusing the existing encrypted
  token store); a 401/403 download refusal surfaces a clear "accept the licence
  on Hugging Face and add a token" hint. The discovery client is read-only, sends
  no token for browsing (only the file download needs one), issues a request only
  in response to a user action, supports pull-to-refresh, and shows a graceful
  retry on network/parse errors. Built on raw OkHttp + `kotlinx.serialization`
  (no new dependency).

- **Model performance insights & benchmark.** The Models screen now shows a
  **Performance** card for the active model with its rolling-average
  **time-to-first-token**, **decode speed** (tokens/second) and **peak memory**,
  averaged over the last few runs — so choosing a model is an informed
  speed/quality/memory trade-off instead of a guess. Every on-device generation
  records a sample automatically; a model with no runs yet shows a calm "no runs
  yet" state. A **Run benchmark** action measures the model on demand with a
  fixed prompt (a warm-up run followed by a measured run) and produces a one-shot
  report you can **share as plain text**. The benchmark is foreground-only and
  waits its turn when a pipeline is using the engine. Peak memory is measured via
  the process native heap and is labelled **approximate** throughout — it is a
  process-wide figure, not the model's exact footprint.

- **Voice input (on-device transcription).** The composer mic now records a
  short voice clip — or you can pick an existing audio file — and a multimodal
  local model **transcribes it to text before anything runs**. The transcript
  lands in the input field as ordinary, editable text you review and send;
  the audio itself never travels the pipeline (a deliberate simplification that
  keeps the whole graph text-only). Recording captures canonical 16 kHz mono
  WAV, shows a live timer, and auto-stops at a configurable limit
  (`audioMaxDurationSec`, default 30 s, to match the model's audio window).
  Transcription requires the active model to be marked audio-capable via a new
  **"Audio support"** toggle on the Models screen (default off); a text-only
  model, a denied microphone permission, or a busy engine each surface a calm,
  non-blocking notice instead of failing. The clip is temporary and deleted
  after a successful transcription.

- **On-device image understanding (vision inference).** A vision-capable local
  model (e.g. Gemma 4) now actually reads an attached image. The image is
  delivered to the **first on-device step of the pipeline** alongside the user's
  prompt; the rest of the graph continues to operate on text, so the contract
  stays "the attachment belongs to the user message, the graph carries text".
  Images are **never sent to cloud models** (a privacy guarantee of this
  release). Because the LiteRT runtime exposes no capability probe, each model
  carries a manual **"Image support"** toggle on the Models screen (default off);
  the agent reads it in a **pre-flight check before a run starts** — sending an
  image to a text-only model, or to a pipeline that begins with a cloud step,
  surfaces a clear, non-blocking explanation instead of failing mid-inference.
  The agent console announces the multimodal input at the start of the run
  (`Image input: W×H, N KB`).

- **Image attachments in chat.** A message can now carry one image, attached
  from the system **Photo Picker** (gallery / screenshots, no storage
  permission) or the **camera**. The picked image is downscaled on-device
  (aspect ratio preserved, longest side ≤ 1536 px) and re-encoded to JPEG into
  private app storage — the original is never kept. A removable preview shows in
  the composer before sending; the sent message renders a thumbnail in its
  bubble that opens a full-screen viewer on tap. An image may be sent without a
  caption (an internal default instruction then accompanies it). Attachment
  files are deleted with their message or session, with a daily background sweep
  reclaiming any orphaned files. On-device multimodal inference over the image
  is a later change; for now the attachment travels with the user message while
  the pipeline graph continues to operate on text.

- **Chat-history compression for long sessions.** Long conversations no longer
  blow the on-device context window or crowd out memory and tool results. Once a
  session's verbatim history exceeds a configurable token budget, the messages
  older than a live window of recent turns are summarised by the local model in
  the background and rendered as an `--- Earlier conversation (summarized) ---`
  block ahead of the verbatim `--- Chat History ---` window. Summarisation is
  incremental (each pass folds the prior summary plus only the newly-aged-out
  messages, never re-summarising everything) and runs debounced and off the
  active-run path, sharing the same agent-busy gating as memory auto-extraction.
  If a summary is not ready when a run needs it, the history is gracefully
  truncated to the live window and the fact is reported on the agent console
  (`HistoryCompression` console events). Configurable under Settings → Memory:
  an on-by-default toggle, the token threshold (default 3500, coordinated with
  the context-length setting), and the live-window size (default 10 messages);
  verbose diagnostics ride the existing memory-logging flag.

- **Transient-failure retry for cloud calls.** Every cloud LLM call (chat
  completions and cloud embeddings) is now wrapped with an exponential-backoff
  retry policy: transient failures (HTTP 429 / 5xx / connection & read timeouts)
  are retried, while authentication errors are not and coroutine cancellation is
  always honoured. The attempt budget (1–5, default 3; `1` disables retries) and
  the base delay (100–10000 ms, default 1000) are configurable under
  Settings → Providers and apply to all cloud providers. Each retry of a CLOUD
  node is surfaced on the agent console as a muted warning line
  (`Cloud retry 1/2 for openai`).

- **Cloud-backed structured output for structured nodes.** The structured node
  types (`INTENT_ROUTER`, `DECOMPOSITION`, `EVALUATION`, `IF_CONDITION`, `TOOL`)
  can now run their validate-and-repair gate against a cloud provider instead of
  the on-device model. Each exposes an **Engine** selector (On-device by
  default, or a concrete cloud provider) in both the in-app and browser pipeline
  editors, persisted via the node's `cloudProvider`. When the chosen provider
  natively constrains output to JSON, the gate trusts it and validates once
  (`maxRepairs = 0`) for JSON-payload nodes; otherwise it falls back to the full
  repair budget. The gate remains the single source of structural validation.

- **Structured-output validate-and-repair gate.** A new domain component
  validates an LLM-driven node's output against the shape the node expects — a
  JSON object or array (deserialized with `kotlinx.serialization`) or one of a
  constrained set of tokens (e.g. `True`/`False`, `Pass`/`Retry`/`Fail`, router
  keys) — and, when the local model emits malformed output, hands it back its own
  invalid reply plus the validation error and asks it to correct itself, at a
  lowered sampling temperature. The number of repair attempts is configurable
  (default 2, range 0–4). JSON payload extraction (fenced / bare /
  embedded-in-prose, object or array) is consolidated in one place. Repairs are
  surfaced on the agent console as a muted warning line and counted per node in
  the run metrics.

- **Composed Showcase agent (bundled sub-pipelines).** The first-launch
  **Showcase — full agent** pipeline now demonstrates composition: its task
  loop's four subtask branches — *Clarify*, *Lookup*, *Act*, *Process* — are
  shipped as four standalone bundled sub-pipelines, and the Showcase routes
  each subtask to them through `PIPELINE` nodes instead of inlining the work.
  When the Showcase is seeded (first launch) or spawned from **+ From preset**,
  its bundled sub-pipelines are materialised under stable ids and persisted on
  demand (create-if-absent, so a sub-pipeline you have edited is never
  clobbered). A nested task run now shows each subtask as its own sub-pipeline
  span in the console — indented under the calling node and resumable across
  the boundary. The browser editor's bundled-preset catalogue carries the
  composed Showcase and the four sub-pipelines too.

- **Browser editor: `PIPELINE` and `SKILL` configuration.** The standalone
  browser pipeline editor can now configure composition nodes, not just place
  them. A `PIPELINE` node gets a target-pipeline picker populated from the
  bundled and per-browser preset catalogues, with a manual-id escape hatch; a
  `SKILL` node gets a skill-id field and an on-device/cloud engine selector.
  Both reference ids now travel in the **flat `config` block** of the exported
  JSON (alongside the rich `nodeConfig` envelope), so a pipeline exported from
  the app and imported into the browser — and back — preserves its references
  exactly and stays runnable. Importing a reference to an unknown pipeline no
  longer breaks silently: the node shows an "unresolved" badge and a validation
  warning instead of vanishing. The editor flags a direct self-reference and an
  unset reference as errors; full transitive cycle and nesting-depth validation
  remains the app's responsibility.

- **Skill execution (`SKILL` node).** A new pipeline node type runs a reusable
  skill as an inference step: the instruction, the visible-tool allowlist, and
  the default context all come from the skill rather than from the node. The
  node picks its inference engine (on-device or cloud); the skill's instruction
  is rendered with every built-in `$VARIABLE`, but `$TOOLS` expands only to the
  skill's allowlist. Tool restriction is enforced **at the executor level**, not
  just in the prompt: a tool call outside the allowlist is rejected with a typed
  error observation and is never executed, and any allowed call still passes
  through the unchanged tool risk / Human-in-the-Loop confirmation gate (a skill
  can never weaken it). The visual editor's SKILL node gains a skill picker, a
  read-only instruction preview, an allowlist indicator, an engine selector, and
  context toggles tagged *inherited* / *overridden* against the skill's default.
  The browser pipeline editor gains a matching **Skill** palette entry.
- **Skill entity & library.** A **skill** is a reusable bundle of *instruction
  + tool restriction + context configuration* — describe a capability once and
  reuse it instead of copying a system prompt between nodes. The new **Skill
  library** (More → Skill library) lists bundled and user skills in a
  Bundled / Mine 2-tab layout, with a full-screen editor: a monospace,
  `$VARIABLE`-aware instruction field; a tri-state tool allowlist master
  control (**All tools** = unrestricted · **Restrict** = an explicit subset
  with per-tool risk pills · **No tools** = an explicit empty allowlist, kept
  visually distinct from "unrestricted"); and per-context-block toggles. Three
  bundled skills ship and are seeded idempotently on every launch (Summarizer
  and Translator use no tools; Report Writer is allowed `write_file`). Bundled
  skills are read-only but can be duplicated into an editable copy; deleting a
  user skill warns about dependent pipelines. (Skill *execution* as a `SKILL`
  pipeline node lands in a follow-up change.)
- **Pipeline composition (`PIPELINE` node).** A new pipeline node type runs
  another pipeline as a sub-step — the building block for turning a reusable
  branch into a callable block. The node references its callee by id; at
  runtime the engine feeds the node's input to the sub-pipeline's `INPUT` node
  and the sub-pipeline's `OUTPUT` text becomes the node's output. Unbounded
  recursion (a pipeline referencing itself or forming a cycle) and runaway
  nesting are rejected **before a run starts** by a static cross-pipeline
  validator, with a configurable runtime depth ceiling as a safety net. A new
  `pipelineMaxNestingDepth` setting (default 3, range 1–5) bounds how deep a
  composition may nest. The browser pipeline editor gains a matching
  **Pipeline** palette entry. (Visual editor configuration of the node — target
  picker, on-card target name, validation deep-links — and nested-run
  observability land in follow-up changes.)
- **`PIPELINE` node in the visual editor.** The in-app pipeline editor now
  renders and configures `PIPELINE` nodes: a deep-indigo node card showing the
  target pipeline's name (or a "No target pipeline" / "Pipeline not found"
  note), and a config-sheet **target picker** listing every other saved
  pipeline. Choices that would create a cycle (self-reference or a pipeline
  that already runs this one) or exceed the nesting ceiling appear disabled
  with the reason — reusing the same cross-pipeline validator that gates a
  save. Deleting a pipeline that other pipelines reference now warns with the
  list of dependents (which become a normal, deep-linkable validation error —
  no silent cascade). Composition errors (missing target, cycle, depth) surface
  in the validation bar with a one-tap deep-link to the offending node.
- **Nested-pipeline observability, shared budgets and resume across the
  boundary.** A sub-pipeline now runs as a first-class **child run** linked to
  its parent (`pipeline_runs.parentRunId`), so its execution is no longer a
  black box:
  - **Nested console.** A sub-pipeline's log lines, variables and trace spans
    surface in the console indented by nesting depth and prefixed with the
    sub-pipeline name, both live and when replaying a finished run.
  - **Shared step budget.** The `MAX_STEPS` ceiling is now shared across the
    whole run tree — a sub-pipeline decrements the same allowance instead of
    getting a fresh one, and exhausting it at any depth fails the whole stack
    with a clear error.
  - **Human-in-the-loop across the boundary.** An approval or clarification
    raised *inside* a sub-pipeline now surfaces its card in chat and resumes
    correctly (previously the request was swallowed and the parent node timed
    out).
  - **Resume across the boundary.** A run interrupted (process death) or parked
    (background HITL) *inside* a sub-pipeline resumes by restoring the whole
    stack: the parent replays to its `PIPELINE` node and continues the child
    run from its checkpoint rather than restarting it; the recorded graph hash
    is validated for every graph in the stack.

### Fixed

- **Exporting a pipeline now confirms it saved, and there is a single Export
  action.** Tapping **Export** on a library pipeline opens the system "Save
  file" dialog, where you pick the destination — but on success the app said
  nothing, so it was unclear whether the file was written. A successful export
  now shows an **"Exported &lt;file name&gt;"** confirmation; write errors still
  surface as before, and dismissing the picker stays silent (nothing was saved).
  The library overflow menu also drops the separate, non-functional **Export
  JSON** entry: the remaining **Export** always carries the pipeline's
  sub-pipeline dependencies, so it covers every case (a pipeline with none
  simply exports as a single-pipeline bundle).
- **Importing a pipeline can no longer empty other pipelines.** Every exported
  pipeline numbers its nodes `node-1`, `node-2`, … independently, so importing a
  single-pipeline JSON whose node ids happened to match another saved pipeline's
  used to silently reassign those rows to the import — leaving the other pipeline
  with an empty graph. Single-pipeline imports now regenerate node and connection
  ids on save (the pipeline's own id is preserved), the same safeguard bundle
  imports already applied. Re-importing an affected pipeline's JSON restores it
  cleanly.
- **Each chat keeps its own unsent draft.** Text you have typed but not yet sent
  now stays with the chat it belongs to. Previously the input box was shared
  across the whole screen, so starting a message in one chat and switching to
  another lost the text; switching back showed an empty box. Every conversation
  now remembers its own half-written message until you send it (drafts are held
  in memory for the session, not persisted across an app restart).
- **The "agent working" notification no longer gets stuck.** The foreground
  status notification used to stay in the shade permanently — often frozen on
  "Answering…" long after the agent had finished, with a generic icon and no
  effect when tapped. It now appears only while the agent is actively working
  and is **removed automatically** once it settles, uses a proper Knotwork
  status-bar icon, and **opens the app** when tapped.
- **Sending with no model loaded now just works.** If you sent a message before
  the on-device model had loaded, you used to get an error with a **Retry** that
  only loaded the model — you then had to press send a second time, while a
  misleading "generating" status showed during the load. Sending now
  **loads the model and then delivers your message automatically** in one tap,
  showing an honest "loading model" status meanwhile; an error appears only if
  no model can be loaded at all (and Retry then loads-and-sends your still-intact
  message).
- **Clarification questions are no longer lost after they're answered.** A
  **Clarification** node used to pass only the user's answer to the next node,
  discarding the question it had asked. In pipelines that collect several
  answers — for example a looped journaling ritual that asks three reflection
  questions — the downstream node then saw a list of bare answers with no idea
  which question each belonged to, so the composed result mixed them up or
  dropped the questions entirely. A clarification node now forwards the full
  exchange (the question together with the answer) to the next node, so later
  steps can pair each answer with what was asked.
- **Chat titles are more informative, especially for shares.** Auto-generated
  chat names now use more of your first message (up to 40 characters, up from 20),
  and titles for chats created from the **share** target draw on the whole shared
  text — up to 60 characters — instead of just its first line, collapsing line
  breaks so the title reads as one clean line. In the chat drawer, titles now use
  a more compact type style so more of each name fits on a row.
- **Tool-approval prompt no longer double-shows and no longer lingers.** When a
  tool needed your confirmation, the request could appear **both** as the inline
  card in the chat **and** as a system notification at the same time, and
  answering it in the chat left the notification stranded in the shade. The
  active chat is now correctly tracked, so while you are looking at a chat its
  approval prompt stays inline only (no duplicate notification); a request raised
  while the app is in the background still notifies you. Answering from the chat
  now also dismisses any notification that was posted for it.
- **Output nodes no longer add filler to simple replies.** A fresh **Output**
  node now defaults to forwarding the previous node's text *verbatim* instead of
  running an extra "Formatter" model pass over it. That formatting pass could
  bolt on preambles and reword answers, which was especially noticeable in short,
  simple pipelines. Formatting is now opt-in: give the Output node a system prompt
  (a ready-made "Formatter" template is offered) to re-enable it. The Output
  node's default incoming context is now the **previous node's output only** —
  no chat history, original task, memory or tool results — so even when you do
  turn on formatting it re-formats just that upstream reply rather than the whole
  conversation. Existing saved pipelines already shipped a blank Output prompt, so
  only the default for newly-created nodes and the built-in starter pipeline changes.
- **Pipelines can branch on whether the message has an image.** Previously the
  Intent Router and If Condition steps only ever saw text, so a "did the user send
  a picture?" fork was impossible. An **If Condition** node now has a *Branch True
  when input has an image* toggle (a deterministic, no-model check), and an
  **Intent Router** is told when the message includes an image so it can route on
  it. Only the fact of an attachment is shared with these steps — the picture's
  contents still reach only the on-device vision model.
- **Pipeline editor: dragging a connection no longer pans the canvas at high zoom.**
  While you drag from a node's port to wire up an edge, the canvas now stays put
  instead of racing the pan gesture and scrolling away — previously, at maximum
  zoom, the drag often moved the view instead of creating the connection.
- **Pipeline editor: connecting nodes now tells you when a drag missed.** Releasing
  a connection drag in empty space (or back on the same node) shows a short hint on
  how to wire nodes, instead of silently doing nothing.
- **Deleting a just-created chat no longer removes the previous one.** Creating a
  new chat now switches the active thread immediately instead of after the
  background save settled. Previously a "delete" tapped right after creating an
  empty chat could remove the previously-active chat instead, because the active
  thread id still pointed at it during that brief window.
- **Agent messages keep the model that generated them.** Each agent answer now
  records the model it was produced with — the answering node's on-device model
  or cloud provider, not just whatever model happens to be active — so the name
  under a message no longer changes after you switch models. Older messages saved
  before this fall back to the active model name.
- **Routing handles ports labelled with symbols.** An `INTENT_ROUTER` edge whose
  label contains a non-word character (e.g. `C#`) is now matched correctly
  instead of falling through to the wrong branch.
- **MCP credentials survive edits and transient Keystore hiccups.** Editing or
  adding one server no longer rewrites other servers' stored credentials, a
  momentarily-unreadable secret is no longer deleted, and concurrent changes
  can't drop a server or orphan its secret.
- **Memory-pressure model unload is prompt.** Under low memory the engine now
  cancels the in-flight generation so the model is freed immediately instead of
  after the current answer finishes; the wake lock is also held while the final
  answer streams.
- **Token count stays visible in the chat top bar.** A long pipeline name no
  longer truncates the token counter in the chat's top app-bar subtitle — the
  count is pinned and the pipeline name ellipsizes instead.
- **Live progress shows on every message, not just the first.** A second message
  in the same chat no longer loses its generating indicator and console updates:
  the per-session state stream replayed the previous run's "completed" state to
  the new collector, which immediately settled the fresh run out of its
  generating state, so the answer appeared with no visible progress. The replayed
  stale terminal is now ignored when a new run starts.
- **New-chat picker is usable with many pipelines.** The "Start a new chat"
  sheet's pipeline list now scrolls with the Cancel/Create button pinned, so a
  large pipeline library no longer pushes the button off-screen; the model
  picker got the same treatment, and the row labels match the app's standard
  list style.
- **Nested pipelines no longer flood the chat.** A sub-pipeline's OUTPUT returns
  its result to the parent pipeline instead of writing a chat message — only the
  top-level pipeline's final answer appears in the conversation.
- **Hidden tools are now controllable.** Discovered on-device AppFunctions are
  listed in the Tools screen with an enable/disable toggle. A tool disabled
  there is removed from the agent everywhere, including pipelines — visibility
  and agent-availability stay consistent.
- **Showcase pipeline understands images.** A vision intake step turns an
  attached image into text before routing, so an image request no longer loses
  the photo when the pipeline decomposes it into subtasks.
- **On-device model could crash when freed mid-generation.** The LiteRT engine
  serialised concurrent generations but freed/rebuilt the native session
  (idle-timeout unload, low-battery, memory-trim, model switch) without that
  lock — a native use-after-free under a running decode loop. Engine load,
  unload and streaming now share one mutex, a cancelled generation closes its
  native session, and the CPU wake lock is released while the agent waits on the
  user instead of being pinned until the safety timeout.
- **MCP tool calls and the session-state stream could race.** The MCP client
  pool reconcile is now serialised (it could leak a connection under concurrent
  refreshes), and the active-sessions snapshot is read under its monitor
  (previously a possible `ConcurrentModificationException` on the enqueue path).
- **Pipeline routing fall-throughs.** An `IF_CONDITION` whose verdict had no
  wired branch no longer silently runs the opposite branch, and the
  `INTENT_ROUTER` fuzzy fallback matches a whole word instead of an incidental
  substring (so a key "…Cancel" no longer routes to a port named "can").
- **Faster chat loads.** `chat_messages` is now indexed by `sessionId`, so
  opening a chat and deleting a session no longer scan the whole message table.
- **MCP credentials are now encrypted at rest.** Bearer tokens, Basic passwords
  and API-key values for configured MCP servers move from the plain settings
  store into the Keystore-backed encrypted store (keyed per server by a hash of
  its URL); the plain entry keeps only non-secret metadata. Credentials saved by
  an earlier build are migrated automatically on first read and stripped from
  the plaintext entry.
- **Hardened model install & networking.** A downloaded model file name is
  sanitised against path traversal, the shared HTTP client has connect/read/write
  timeouts, and the plain settings store is excluded from cloud backup and device
  transfer. A failed audio import no longer leaves a truncated clip behind.
- **Recurring scheduled tasks no longer collapse across chats or stack
  duplicates.** A recurring task's de-duplication key now includes the bound
  chat session and trims the prompt, instead of keying on the verbatim prompt
  and interval alone. Previously, scheduling the same recurring prompt from a
  second chat was silently dropped (its results kept landing in the first
  chat), while a prompt differing only by a stray trailing space slipped past
  the de-dup and stacked a duplicate schedule. Schedules bound to different
  sessions now stay independent, and cosmetic whitespace differences collapse
  correctly; the agent still runs the verbatim prompt.

- **Scheduled-task confirmation message showed a literal placeholder.** The
  reply confirming a scheduled task ("Task successfully scheduled to run every
  …") rendered the raw `$intervalHours` / `$delayMinutes` text instead of the
  actual interval/delay numbers (an escaped string template). It now reports the
  real values.

- **Content slipping under the bottom navigation bar.** The Discover list,
  Discover detail and Pipeline library screens now zero their own
  `Scaffold` content insets (matching every other screen), so their bodies are
  positioned by the host shell's inner padding instead of double-handling the
  system bars — the last row / install button no longer scrolls under the
  in-app bottom-nav strip.

### Changed

- **README reworked as a value-first landing page.** The README now opens with
  what Knotwork is and who it is for (local-first, user-built pipelines, gated
  actions) ahead of an exhaustive feature list, leads with the Share Handler
  scenario as its one-glance demo, condenses the feature dump into grouped
  highlights (the full tour stays in the user guide), and replaces the outdated
  manual-model quick start with the scenario-onboarding first-run path.
- **Documentation refreshed for the current release line.** The roadmap's
  version line and "where the project is today" snapshot are brought up to the
  `0.5.x` surface (scenario-onboarding gallery, automation triggers and entry
  surfaces, local usage statistics, the FOSS build flavour); the user guide
  reconciles its first-run and default-pipeline description with scenario
  onboarding; and the contributor guide's "Add a Settings section" recipe is
  rewritten for the registry-driven settings hub, correcting stale source-file
  links. No code change.
- **On-device inference engine updated.** Bumped `com.google.ai.edge.litertlm`
  (LiteRT-LM) from `0.13.1` to `0.14.0`.
- **Encrypted-storage library updated.** Bumped `net.zetetic:sqlcipher-android`
  from `4.16.0` to `4.17.0`.
- **Settings internals reorganised ahead of the categorised settings redesign
  (no behaviour change).** `SettingsViewModel` is now a thin coordinator over
  eight per-category delegates (Generation, Models, Memory, Pipelines, Tools,
  Background, Privacy, About) that share its scope and state reducer; every
  setting keeps its current meaning, range, default, persistence, validation and
  restart/destructive gates. A new pure-domain `SettingsRegistry` records each
  user-facing setting's category, Basic/Advanced tier and search synonyms as the
  single source of truth for the upcoming settings hub, category sub-screens and
  settings search. No user-visible change yet.

- **Settings redesigned as a category hub with focused sub-screens.** The single
  long settings scroll is replaced by a **hub** — a short *Basic* block of the
  six most-touched controls (system instructions, approve tool calls, block
  destructive tools, local-model backend, long-running task alerts, crash
  reporting) over eight category rows (Generation, Models, Memory, Pipelines &
  structured output, Tools & workspace, Background & triggers, Privacy, About) —
  that opens a sub-screen per category. Each sub-screen shows its Basic settings
  immediately and tucks the rest behind an in-category **"Advanced — change
  deliberately"** disclosure. Re-organisation only: every setting keeps its
  meaning, range, default, persistence, validation, restart-gating and
  destructive-confirm gate; only location and Basic/Advanced prominence change.
  All existing entry points (drawer / More) still open settings.

- **"Reset all settings" now restores every tunable preference, behind a plain
  confirm dialog.** Settings → Privacy → *Reset all settings* previously reset
  only a subset of preferences (and used a typed-keyword gate). It now restores
  **every** tunable setting — sampling, context, workspace/HTTP limits,
  pipeline/retry/retention/resume windows, audio, memory tuning, notifications
  and security toggles — to its recommended default in a single atomic write,
  confirmed by a lightweight dialog since no user data is touched. The reset is
  strictly scoped: chats, long-term memory, pipelines, presets, skills,
  connections, the `http_request` allowlist, custom prompts, the active embedding
  provider and API keys are all left untouched (it no longer clears the
  system-instructions prefix or re-points the embedding provider). The remaining
  inline default values were centralised into `SettingsDefaults`, and the
  defaults surface is documented as sensible starting points refined through
  real-world use.

- **Chat-home ViewModel decomposed into domain delegates.** The large
  `ChatHomeViewModel` (~2.7k lines) is now a thin coordinator over eight
  delegates — console, voice, attachments, import/export, pipeline-binding,
  threads, HITL/clarification and reattach — each owning a slice of the single
  `ChatHomeScreenState` and sharing the ViewModel's scope and state flow. The
  coordinator keeps only the agent-execution core (send cycle, live run
  collector, thread switch, session/message/token plumbing). Behaviour and the
  rendered state contract are unchanged; this establishes the delegation pattern
  for thinning large presentation ViewModels.

- **Task scheduling extracted behind a domain port.** `ScheduleTaskUseCase` no
  longer depends on `androidx.work` or the `data` layer: it now delegates to a
  new `TaskScheduler` domain port (with a `ScheduledTaskConstraints` value type),
  implemented by `WorkManagerTaskScheduler` in the `data` layer. Behaviour is
  unchanged; this restores the Clean Architecture invariant that the `domain`
  layer carries no framework dependencies.

- **Cloud client and embedding factories are now retry-wrapped.** Cloud clients
  built by `KoogClientFactory` and the cloud/Ollama embedding clients are
  decorated with the configurable retry policy before use, using Koog's
  standalone `RetryingLLMClient` decorator over the existing cloud-LLM-client
  layer.

- **Structured-output gate wired into every LLM-driven consumer.** The engine's
  structured consumers now route their model output through the validate-and-repair
  gate instead of ad-hoc parsing, and each silent fallback becomes observable:
  - `IF_CONDITION` (a `True`/`False` token) and `INTENT_ROUTER` (a routing key
    constrained to the node's own outgoing edge labels) keep their default branch
    when the gate exhausts its repairs, but now emit a console error and count the
    repair attempts — there are no more silent forks.
  - `EVALUATION` (a `Pass`/`Retry`/`Fail` verdict) behaves the same: default port
    on failure, but observable.
  - `DECOMPOSITION` (a JSON array of sub-tasks) now **fails the run with a clear
    error** when no valid list can be produced — a corrupted sub-task list is worse
    than stopping.
  - `TOOL` argument generation validates the `{tool, arguments}` envelope (auto
    select) or the arguments object (fixed tool), repairing malformed JSON before
    falling back to the previous error-observation path.
  - Background memory extraction validates its `{type, text}` fact array, repairing
    once before honouring its best-effort (zero-result) contract.
  - Repairs are an internal node mechanic: they consume the node's repair budget,
    never pipeline steps. Repair re-inferences run at a lowered, deterministic-leaning
    sampling temperature. The duplicate per-consumer JSON extractors and regex parsers
    left over from the previous change have been removed.

- **Reliability features documented end-to-end.**
  [`docs/architecture.md`](docs/architecture.md) gains a *Structured-output
  reliability gate* section (with a gate-cycle diagram and the per-node
  failure-policy table) and a *Chat-history compression* section, and its
  *Cloud LLM providers* section now covers transient-failure retry and
  cloud-backed structured output. [`docs/user-guide.md`](docs/user-guide.md)
  documents the structured-output repair budget and per-node **Engine**
  selector, the cloud **Retry policy** sliders, the **Compress long chat
  history** settings, and how repair / cloud-retry / history-compression
  events read in the console. No behaviour change — documentation catching up
  to the shipped reliability contour.

- **Automation & background-execution surfaces documented and tested
  end-to-end.** [`SECURITY.md`](SECURITY.md) gains an *Automation triggers and
  entry surfaces* section (low-sensitivity conditions only; NotificationListener
  / location / SMS deliberately deferred; inert-until-bound, auto-disable on
  pipeline delete, and the unchanged human-in-the-loop gate as the mitigations
  for unattended background execution) and a *Local usage statistics* note (the
  on-device-only guarantee and its build-time network guard).
  [`docs/architecture.md`](docs/architecture.md) gains a *Triggers and entry
  surfaces → background runs* section with a flow diagram, and the background-work
  component table now lists the trigger watch / charging-sweep workers and the
  trigger scheduler. [`docs/user-guide.md`](docs/user-guide.md) notes that crash
  reporting is absent from the F-Droid / FOSS build. A new Robolectric
  integration test drives a charging trigger through firing → background run →
  notification → result in the bound chat, including the background
  HITL-approve-from-notification path. No behaviour change — documentation and
  test coverage catching up to the shipped automation surface.

- **Bump Hilt / Dagger `2.59.2` → `2.60`** to clear the `NewerVersionAvailable`
  lint gate (`hilt-android`, `hilt-android-compiler` and the Hilt Gradle plugin
  move together via the shared version). The existing `kotlin-metadata-jvm`
  force-pin already covers the unshaded-metadata dependency, so no further change
  was needed. No code change.

- **Bump `dev.detekt` `2.0.0-alpha.4` → `2.0.0-alpha.5`** to clear the
  `NewerVersionAvailable` lint gate. Build tooling only (not shipped in the
  APK); no rule-set changes affecting the codebase.

- **Dependency refresh to clear the `NewerVersionAvailable` / `GradleDependency`
  lint gate.** Compose BOM `2026.06.00` → `2026.06.01`, Hilt/Dagger `2.60` →
  `2.60.1`, the AndroidX Hilt extensions (`hilt-navigation-compose`, `hilt-work`,
  `hilt-compiler`) `1.3.0` → `1.4.0`, and Roborazzi `1.64.0` → `1.66.0`. No code
  change. `androidx.appfunctions` stays on `1.0.0-alpha09`: only `appfunctions`
  and `appfunctions-compiler` published `-alpha10`, while the `appfunctions-service`
  sibling (pinned to the same version) did not, so that finding is grandfathered
  in the lint baseline until the whole family moves together.

## [0.5.0] - 2026-06-14

### Added

- **"Research to file" bundled preset.** A new curated starter pipeline
  (`showcase_research_to_file`, in the **+ From preset** picker's Bundled
  tab and mirrored in the browser pipeline editor) demonstrates the
  end-to-end "research → file in your hands" loop out of the box: the
  local model turns the question into a Wikipedia lookup, distils the raw
  extract into clean facts, writes a short Markdown report into the agent
  workspace under `reports/`, and replies with the saved path. Plain-prose
  prompts keep it runnable on the on-device 4B model.

- **Files screen (agent workspace browser).** A new screen — reached from
  **More → Files** — gives the agent's previously-invisible file workspace a
  user-facing window. It lists the workspace's files (path-sorted, with size and
  modified time) behind a quota indicator that ramps neutral → amber → red as
  storage fills, and supports: a read-only monospace **preview** of text files
  (large files are shown truncated with a "save it out to read the whole file"
  banner); **export** of a file, either to the system share sheet (a per-share
  copy is staged so the workspace directory itself is never exposed) or to a
  chosen location via "Save as…"; **import** of an external file into the
  workspace, with a name-collision chooser (keep both / replace) and the same
  per-file and total quota enforcement the agent's own writes get; and
  **delete** with a confirmation dialog, including multi-select bulk delete.

- **Outbound HTTP tool (`http_request`).** A new built-in tool lets the
  agent call a remote HTTP(S) API (GET/POST/PUT/DELETE) — the most
  security-sensitive tool in the workspace set, designed conservatively
  because in combination with the file tools an unrestricted HTTP
  capability would be a data-exfiltration channel. It only reaches
  domains the user has explicitly added to an **allowlist** (Settings →
  Tools → Allowed domains, stored in DataStore); while the allowlist is
  empty the tool is hidden from the agent entirely and a direct call is
  refused. Risk is per-method — a `GET` is SENSITIVE, a
  `POST`/`PUT`/`DELETE` is DESTRUCTIVE — so each call passes the matching
  Human-in-the-Loop confirmation. Public hosts must use `https`
  (cleartext only for local addresses); redirects are followed manually
  and re-validated against the allowlist on every hop, aborting if one
  points outside it; a request that would carry a stored provider API key
  is refused; and the response body is capped (1 MB default) with a
  truncation marker so untrusted remote content can't overflow the local
  model's context. A standalone **Allowed domains** editor — reached from
  the http_request row on the Tools screen — lets you add (with live
  host-normalisation preview, invalid and duplicate feedback) and remove
  hosts; matching is exact, so sub-domains are not implied. While the list
  is empty the editor explains that the tool stays off until a host is
  added.

- **Workspace write tools.** Three new built-in tools let the agent change
  files in its workspace, each gated by its risk level: **write_file**
  (writes a UTF-8 text file; creating is the default and replacing an
  existing file needs an explicit `overwrite` flag, so content is never
  clobbered silently — the write is atomic, staged and renamed into place,
  and quota-checked before any bytes land), **edit_file** (replaces a single
  uniquely-matching `oldText` anchor with `newText`; the anchor must occur
  exactly once or the edit is refused with the occurrence count, and an
  empty replacement deletes the matched fragment) and **delete_file**
  (removes one file, irreversibly). write_file and edit_file are SENSITIVE
  and delete_file is DESTRUCTIVE, so every mutating call passes through the
  Human-in-the-Loop confirmation gate — interactive, notification, and the
  persistent background-approval path alike — before it runs.

- **Workspace read tools.** Three new built-in, read-only tools let the
  agent inspect its file workspace: **read_file** (reads a UTF-8 text file,
  truncated to a per-read token budget — default 2000 tokens — so a long
  file never overflows the local model's context window, with byte
  `offset`/`limit` paging that stitches consecutive pages back together
  without splitting multi-byte characters), **list_files** (a stable,
  path-sorted listing with size and modified time, optionally scoped to a
  sub-directory) and **find_files** (glob search over relative paths, e.g.
  `*.md` or `reports/**`). All three resolve through the jailed workspace
  gate, so a path that escapes the sandbox is refused with a readable
  error. A new **workspace read token budget** setting tunes the read
  truncation limit.

- **Agent file workspace (foundation).** Groundwork for letting the agent
  read and write files: a single, jailed directory (`files/agent_workspace/`
  in the app's private storage) with a strict containment boundary. Every
  path is canonicalised through one gate, so a relative path that tries to
  escape the workspace (`../` traversal, an absolute path, or a symlink
  pointing out) is refused before any file is touched. Two size quotas keep
  it bounded — a per-file limit (default 5 MB) and a workspace-wide limit
  (default 100 MB) — so a looping agent cannot exhaust device storage. This
  release lands the internal foundation only (text read/write/list with
  binary files listable but not text-readable); the file tools and the Files
  screen build on top of it.

- **Run-history retention.** Persistent pipeline runs and their traces
  no longer accumulate forever: a daily maintenance pass (WorkManager,
  charging + idle — the same window as memory compaction) deletes
  finished runs that fall outside the most-recent window of each chat
  or exceed the maximum age, together with their traces (and legacy
  pre-run trace rows). Two new **Settings → Privacy** sliders control
  the limits: **Keep run history per chat** (5–100, default 20) and
  **Run history max age** (7–180 days, default 30). Only settled runs
  are eligible — a run parked on a background approval or clarification
  is never removed while it waits; its lifetime stays bounded by the
  approval window. Documented in `SECURITY.md` as a mitigating control
  for the at-rest accumulation of user-derived content.

- **Background approvals and questions survive app death.** A run that
  hits a tool-approval gate or a clarifying question no longer fails
  when nobody answers within the live timeout: it parks in a persistent
  waiting state instead. The pending request (tool name, arguments and
  risk — or the generated question) is stored on-device, the engine and
  foreground service are released, and an ongoing notification becomes
  the way back to the run — swiping it away re-posts it from the stored
  request. Approve / Deny act directly from the notification for
  read-only and sensitive tools and work even after the process died:
  the decision is recorded one-shot and the run resumes from its
  checkpoint, re-validating the tool call so a stale approval can never
  authorise different arguments. Destructive tools keep their typed
  confirmation — the notification offers Deny plus a *Review in chat*
  deep link. Clarifications park the same way under an *Agent needs
  your input* notification, and the answer given in chat resumes the
  run without re-generating the question. Unanswered requests expire
  after the new **Settings → Approval window** period (default 24 h,
  1–168 h): a periodic maintenance pass fails the run with *Approval
  window expired*.

- **Scheduled tasks land their results in chat.** A task created with
  `schedule_task` now executes through the exact same task-queue →
  engine path as an interactive message and is bound to the
  conversation that scheduled it: when the task fires, the stored
  prompt is saved as a user message, intermediate node output goes to
  the console trace, and the final answer arrives as a regular agent
  reply — reopening the chat shows the run as if it had happened on
  screen, and a chat that is already open attaches to the run live. If
  the originating conversation was deleted before the task fired, the
  result is delivered to a fresh auto-named session
  (*Scheduled: &lt;task text&gt;*). Scheduled runs are recorded with
  `SCHEDULER` origin and run at normal queue priority so they never
  preempt an interactive request, and the executing worker promotes
  itself to a foreground service for the duration of inference
  (releasing the model afterwards when nothing else owns it).

- **"Task completed" / "Task failed" notifications.** Finishing a
  scheduled background run now posts a default-importance system
  notification — the completion variant carries the first line of the
  final answer, the failure variant the recorded reason — and tapping
  it deep-links straight into the conversation the result landed in.
  Announcements live on their own notification channel, separate from
  the high-importance approval prompts, and respect the new
  **Settings → Notifications → Scheduled task results** toggle
  (on by default). Cancelled and interrupted runs are deliberately not
  announced.

- **Checkpoint resume for interrupted runs.** The **Resume** button on the
  "Run interrupted" card now works: the run continues from its last
  completed node instead of starting over. Node results recorded in the
  persistent trace — including condition / router branch decisions and
  the long-term-memory context retrieved by the original run — replay
  without re-inference, so a background run killed mid-pipeline only pays
  for the nodes it never finished. Tool calls are the deliberate
  exception: a tool node the run died on is never replayed (whether its
  side effects happened is unknowable) and re-executes with a fresh
  human-in-the-loop approval, while a tool that demonstrably finished
  reuses its recorded observation. Resume is refused with an explicit
  message when the pipeline was edited or deleted since the run started
  (graph content-hash check) and when the interruption is older than the
  new **Resume window** setting (1–168 hours, default 48) — expired runs
  offer Discard only. Database schema v32 adds the recorded routing
  verdicts and the originating prompt to the encrypted run records.

- **Persistent pipeline-run records.** Every pipeline run is now a
  first-class row in the encrypted local database (`pipeline_runs`,
  schema v30): enqueueing creates a `QUEUED` record, the engine writes
  the node currently executing and the human-in-the-loop suspension
  statuses (`WAITING_APPROVAL` / `WAITING_CLARIFICATION`) as it walks the
  graph, and the task queue settles the record as `COMPLETED`, `FAILED`
  or `CANCELLED` (user stop is recorded distinctly from failure).
  Runs stranded by a process death — Doze, an OOM kill, a swipe from
  recents — are detected on the next launch and finalised as
  `INTERRUPTED` instead of silently vanishing; the detection is
  ownership-based (runs created by the live process are never touched),
  so a run still executing in the background — kept alive by the
  foreground service or a scheduled worker — survives reopening the app
  untouched. Each record also captures
  a content hash of the executing graph (cosmetic edits such as canvas
  moves excluded), laying the groundwork for resuming interrupted runs
  from their last completed node. Deleting a chat session removes its
  run records as well.

- **Persistent run trace with console replay.** The execution trace of a
  run — every console log line plus each node's input/output snapshot —
  is now written through to the encrypted database (`trace_steps`
  extended with run attribution, schema v31) instead of living only in
  ViewModel memory. Writes are buffered and land in batches (by size or
  a short timer, force-flushed whenever the run suspends on a
  human-in-the-loop request or ends), so trace persistence never
  competes with on-device inference for disk I/O on every streamed
  token. Opening a chat replays the trace of the active (or most
  recent) run into the console pane: the Logs tab shows the recorded
  events, and the Vars / Traces tabs are rebuilt from the persisted
  per-node records — so a run that executed in the background is no
  longer a black box. Live console events of the same run merge
  seamlessly on top of the replayed history without duplicates, and
  pre-existing trace rows survive the upgrade. Deleting a run (future
  retention cleanup) removes its trace atomically.

- **Chat reattach protocol.** Opening a chat now reconnects the UI to
  whatever its persistent run record says instead of assuming nothing
  happened while the screen was away. A run still executing in this
  process re-attaches to the live state stream without restarting the
  pipeline; a run suspended on a tool approval or a clarification
  question restores its card in the message stream from the
  authoritative pending snapshot (the in-memory stream's replay slot
  may have been overwritten by console events); a run that finished in
  the background renders normally on top of the replayed trace. A run
  that died with its process surfaces a **Run interrupted** status card
  naming the node it stopped at, with **Resume** and **Discard**
  actions — Discard settles the record as failed ("Discarded by user"),
  while Resume is wired end-to-end and reports that checkpoint-resume
  is not available yet (it ships with the resume mechanism). Threads
  that own a run in any active status show an in-progress indicator in
  the drawer list, so background work is visible at a glance.

- **Re-embed reminder banner.** *Settings → Memory* now shows a persistent
  warning under the embedding-provider dropdown whenever the stored memory
  vectors were created with a different provider than the active one —
  previously nothing surfaced the mismatch, even though affected chunks
  silently score ~0 against every query. The banner carries an inline
  button that runs the existing **Re-embed** action and disappears once a
  full re-embed (or a memory wipe) re-aligns the store. Switching back to
  the original provider also clears it.

- **Coroutine-cancellation static gate.** `./gradlew check` now also runs
  `detektDebug`, a type-resolution detekt pass with a dedicated config
  (`config/detekt/detekt-cancellation.yml`, since renamed to
  `detekt-type-resolution.yml`)
  that activates a single rule, `SuspendFunSwallowedCancellation`: suspend
  calls may not be wrapped in `runCatching`, and `try`/`catch` blocks
  around suspend calls must re-throw `CancellationException` before any
  generic handling. The contract is documented in
  [docs/code-style.md](docs/code-style.md) § Coroutines & Flow and
  [docs/static-analysis.md](docs/static-analysis.md).
- **Startup recovery screen for locked data.** When the encryption key of
  the local database cannot be read at startup, the splash screen now
  shows a dedicated recovery surface instead of failing generically: a
  plain-language explanation, a **Retry** action (keystore failures are
  often transient — e.g. right after a backup/restore or an OS update),
  and an explicit **Erase all data** action gated behind a typed
  confirmation. The app never wipes or re-keys data automatically. See
  *Troubleshooting* in [`docs/user-guide.md`](docs/user-guide.md).
- **Public roadmap.** New [`docs/roadmap.md`](docs/roadmap.md) describes
  post-release directions across near / mid / long horizons — agent
  tool-set expansion (including evaluating file-oriented tools), the first
  release-signed build, on-device verification beyond the JVM-only CI
  gate, pipeline-editor refinement, the path to `1.0.0`, and localization
  — plus how to get involved. Linked from the `README.md` documentation
  index and from `CONTRIBUTING.md`.

### Changed

- **⚠ BREAKING (local data): secret storage replaced —
  `EncryptedSharedPreferences` → direct Android Keystore wrapper.** The
  deprecated `androidx.security:security-crypto` library (no further
  upstream development past its final `1.1.0` line) has been removed
  entirely. The SQLCipher database passphrase and the cloud-provider API
  keys now live in a small in-house store: a plain preferences file whose
  values are encrypted with **AES-256-GCM under a dedicated,
  non-exportable Android Keystore key**, each ciphertext authenticated
  against its storage slot so blobs cannot be swapped between entries.
  With the data key residing directly in the Keystore there is no
  intermediate wrapped-keyset file left to corrupt — the failure surface
  shrinks to "Keystore key lost", which the startup recovery screen
  already handles. Recovery semantics are preserved: the passphrase is
  never regenerated while a database exists (failures route to the
  recovery screen), while an undecryptable API key is treated as unset
  and simply re-entered. **There is no data migration** (as permitted by
  the pre-release storage policy): an install upgraded across this change
  boots into the startup recovery screen, where continuing requires the
  explicit data wipe, and saved API keys must be re-entered. Export
  chats, memory, and pipelines through the in-app export actions
  *before* upgrading if you want to keep them.
- **Chat home screen state consolidated into a single immutable snapshot.**
  The chat-home ViewModel previously exposed ~25 independent `StateFlow`
  properties (composer text, console pane, HITL/clarification snapshots,
  thread metadata, model picker, token meter, …) and the screen subscribed
  to each one separately. They are now aggregated into one
  `ChatHomeScreenState` data class with logically grouped sub-structures,
  observed through a single subscription. One-shot events (export payloads,
  snackbars, the deleted-pipeline fallback signal) remain on dedicated
  event channels. No user-visible behaviour changes; the refactor reduces
  subscription overhead and makes state transitions atomic and easier to
  test.
- **Long-term-memory embeddings are now stored in binary form.** The
  `memory_chunks.embedding` column changes from a comma-separated TEXT
  encoding to a BLOB of little-endian IEEE-754 floats (4 bytes per
  component). An automatic database migration rewrites existing rows in
  place; a row whose legacy string cannot be parsed keeps its text with an
  empty-blob marker so the re-embedding repair path can still rebuild its
  vector instead of the row being deleted. Decoding the retrieval pool of
  5000 synthetic 512-dimension chunks drops from ~280 ms to ~1 ms, and the
  stored embedding payload shrinks ~2.8×. The memory export/import JSON
  format is unchanged (`schemaVersion: 1`, embeddings as number arrays);
  conversion happens at the storage boundary.
- **Bump `com.squareup.okhttp3:okhttp` `5.3.2` → `5.4.0`** to clear the
  `NewerVersionAvailable` lint gate.
- **Contributor onboarding refreshed.** `CONTRIBUTING.md` gains a *Where
  to start* section (roadmap, `good first issue` / `help wanted` labels,
  extension-point recipes) and its *Branch model* section now describes
  the actual workflow: changes are integrated on long-lived `phase/<N>`
  branches that merge into `main` as a batch, so pull requests target the
  open integration branch when one exists. The stale app-version
  placeholder in the bug-report issue form was bumped to the current
  version line.

- **`docs/api-conventions.md` MCP error-handling rule aligned with the
  cancellation gate.** The MCP section used to prescribe wrapping calls in
  `runCatching`, which contradicts the coroutine-cancellation contract (and
  the actual client code): the rule now requires `try`/`catch` with a
  dedicated `CancellationException` re-throw before mapping failures to
  `ToolResult.Error`. The architecture document also describes the
  passphrase lifecycle (generated only while no database exists; typed
  failures route to the startup recovery screen) and the binary embedding
  column introduced in this release.
- **`docs/code-style.md` restores the `*Preview.kt` file convention.** The
  Compose guidelines again state that preview-only Composables live in
  dedicated `*Preview.kt` files (as practised in the `:catalog` module);
  the detail had been dropped when the document was first published.
- **`docs/testing.md` now states explicitly what the automated gate does NOT
  cover.** A new section documents that the CI gate is entirely JVM-based
  (unit + Robolectric + Roborazzi, no emulator or device): instrumented
  tests are neither run nor compiled by `./gradlew check`, real TalkBack
  navigation, LiteRT-LM inference, the AppFunctions caller → callee
  round-trip, opening the SQLCipher-encrypted database, and Foreground
  Service / WorkManager behaviour are all verified only by a manual smoke
  test on the reference device (Samsung Galaxy S25 Ultra, Android 16). A
  green CI run is explicitly not a guarantee that the app works on
  hardware. The pre-release quality gate in `docs/release.md` now links to
  this section instead of an unpublished internal note.
- **Execution-model terminology aligned with the actual engine behaviour.** The
  core executes the user-authored pipeline graph node by node (graph-driven
  orchestration with `QUEUE_PROCESSOR`, `EVALUATION`-retry and `IF_CONDITION`
  control flow); it does not run an autonomous ReAct loop. The
  `AgentOrchestratorState` KDoc now says so explicitly, and the bundled
  pipeline preset formerly displayed as *"Tool-using ReAct agent"* is renamed
  to *"Tool-using agent"* (its single reason → tool → summarise pass is not a
  ReAct loop; the preset id `tool_using_react` is unchanged for stability).
  The same rename is mirrored in the browser pipeline editor's built-in
  preset catalogue and the user guide.
- **`SECURITY.md` supported-versions now tracks the `0.4.x` release line.** The
  policy table previously keyed support off an abstract `main` (latest) /
  older-commits split; it now states the supported line explicitly (`0.4.x`
  supported, `< 0.4.0` not), matching the published `versionName 0.4.0`. No
  behavioural change — a documentation-accuracy fix following the public
  release.
- **Bump `com.google.firebase:firebase-bom` `34.14.0` → `34.14.1`.** A
  patch-level BOM update that keeps the Firebase dependency on the current
  stable release and clears the `GradleDependency` lint warning. No new
  transitive licences.
- **Room no longer destroys data on upgrade.** The destructive-migration
  fallback (`fallbackToDestructiveMigration(true)`) has been removed from the
  database builder. Every schema-version bump is backed by an explicit
  `Migration` (the full chain is already registered via `addMigrations(...)`),
  so an in-place upgrade preserves all local data — chats, long-term memory,
  run traces, custom pipelines, and saved presets / prompt templates — instead
  of recreating the tables empty. Destructive recreation is retained only on
  **downgrade** (`fallbackToDestructiveMigrationOnDowngrade`), which forward
  migrations cannot handle. A `MigrationTestHelper` regression suite validates
  data preservation and the resulting schema across the exported-schema
  baseline range. `SECURITY.md`, `README.md`, and `docs/architecture.md` were
  updated to describe the new migration policy.
- **Release builds now use a dedicated signing config.** The `release`
  buildType signs with `signingConfigs.release` whose keystore path, store
  password, key alias, and key password are resolved from `local.properties`
  or environment variables (`RELEASE_KEYSTORE_PATH` /
  `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`).
  When no keystore is provisioned the build gracefully falls back to the debug
  keystore, so a clean checkout still produces a release artefact. Keystore
  material is never committed. See [docs/release.md](docs/release.md) for
  keystore generation, CI provisioning via repository secrets, and signature
  verification. **Note:** the first release-signed build uses a different
  signer than earlier debug-signed builds, so it cannot be installed over a
  debug-signed copy in place — see the *Pre-release notice* in the README.

### Fixed

- **Unbound chats no longer execute an arbitrary pipeline.** A chat
  without its own pipeline binding used to run "whatever pipeline the
  database returned first" — so after creating a second pipeline,
  unbound chats could silently switch to a different graph. Resolution
  is now deterministic: the chat's own binding, then the default marked
  in the pipeline library; when neither resolves, the run fails with an
  explicit "No default pipeline configured" error instead of a silent
  substitution. The chat subtitle follows the same chain, and switching
  to a thread whose bound pipeline has been deleted rebinds it to the
  default with the usual notification.

- **Old memories are no longer invisible to retrieval.** The similarity
  search behind long-term memory scanned only the most recent chunks (a
  hidden 1,000-row recency window), so an old but relevant fact stopped
  being findable — regardless of how well it matched — once enough newer
  entries accumulated. The search now scans the full memory base on every
  query; recency remains a *ranking* weight (the re-ranker's half-life
  decay), never a visibility filter. The same window also silently let
  near-duplicates of old facts back in through auto-extraction — the dedup
  check now runs against the full pool too. The pool stays bounded by the
  **Max stored chunks** compaction limit, and the now-redundant internal
  search-pool setting was removed. As part of the same change the AVG
  SCORE statistic moved out of the storage layer into a dedicated
  session-scoped tracker — its on-screen behaviour is unchanged.

- **Cancelling a generation no longer surfaces a false error.** Stopping a
  run (or any scope teardown mid-pipeline) used to be caught by the
  catch-all error mapping in the task queue and engine loop and shown as an
  execution error. `CancellationException` is now re-thrown at every
  boundary that wraps suspend calls — the task queue, the engine's per-node
  collector, workers, MCP/AppFunction/cloud-embedding calls, and the
  affected use cases and view models (53 call sites audited; `runCatching`
  around suspend calls replaced with explicit `try`/`catch`) — so
  cooperative cancellation propagates cleanly and the session state resets
  to idle instead of flashing an error banner. A cancelled predictive-back
  gesture on a modal sheet now keeps the sheet open instead of dismissing
  it.

### Security

- **The HuggingFace access token is now stored encrypted.** The token —
  a real credential granting access to gated HuggingFace repositories —
  was previously persisted in plain, unencrypted DataStore, contradicting
  the project's own storage policy (and the user guide, which incorrectly
  claimed it was encrypted). It now lives in the same kind of
  Keystore-backed encrypted store as the cloud API keys, with the same
  re-enterable-secret recovery policy. **A previously saved token is
  migrated automatically** on first read — the encrypted copy is written
  before the plaintext copy is removed from DataStore, so the value
  survives the upgrade (no re-entry needed, unlike the API keys above).
- **Prompt injection via tool content is now a documented, accepted risk.**
  `SECURITY.md` gains a threat-model section describing how content
  returned by tools (built-in search extracts, MCP-server results,
  AppFunction responses) flows into the context of subsequent pipeline
  nodes — including planning and routing nodes — and can influence the
  arguments of later tool calls. The stated backstop is the
  human-in-the-loop gate (tool name + exact arguments shown for every
  sensitive or destructive call; unknown-risk tools, MCP included, default
  to sensitive), while read-only calls are deliberately ungated. The
  section recommends switching the tool-approval policy to *approve every
  call* when connecting untrusted MCP servers. No behaviour change —
  a disclosure of an existing, deliberate trade-off.
- **Database passphrase can no longer be destroyed by a transient
  keystore failure.** The store holding the SQLCipher passphrase
  previously deleted and recreated itself whenever its encrypted
  preferences failed to open — the recovery path appropriate for
  re-enterable API keys, but fatal for the database key: a transient
  Android Keystore failure would silently discard the passphrase and
  leave the existing encrypted database permanently unreadable. The
  passphrase is now generated only when no database file exists yet;
  while a database is present, any failure to read the stored passphrase
  (preferences unopenable, entry missing or malformed) raises a typed
  error that routes to the new startup recovery screen instead of
  regenerating — while no database exists, the old self-heal (recreate a
  corrupt store) still applies, since nothing can be orphaned. A
  key/file mismatch (database restored from another install) is detected
  at open time and routed to the same recovery screen. The passphrase is
  also no longer fetched during dependency injection on the main thread —
  it is read lazily at the first real database open, where the failure
  can be handled by UI; best-effort background maintenance paths skip
  their work instead of crashing the process while the recovery screen
  is up. The user-confirmed wipe is serialized against concurrent
  database opens and deletes the passphrase only after the database file
  is verifiably gone. The API-key store intentionally keeps its
  recreate-on-corruption recovery (keys can be re-entered by the user).
- **The file-workspace and outbound-HTTP threat surface is now documented.**
  `SECURITY.md` extends *Prompt injection via tool content* to treat a file
  the agent reads (user-imported, or written from untrusted material) as
  untrusted model input, and adds two threat-model sections: *Agent file
  workspace (at-rest)* — the honest statement that workspace files are
  protected by the device's file-based encryption and the app sandbox but,
  unlike the database, are **not** SQLCipher-encrypted — and *Outbound HTTP
  and the exfiltration chain*, which describes the `read_file → http_request`
  data-exfiltration shape and the layered mitigations (empty allowlist by
  default with the tool hidden until opt-in, exact-host matching, per-method
  HITL, the stored-credential filter, redirect re-validation, and the
  transport floor). Workspace quotas are documented as an availability
  control. `docs/architecture.md` gains a storage-tier table
  (SQLCipher / Keystore / DataStore / FBE-only) and a *File and HTTP tools*
  section mapping the tools through `ToolRisk` → HITL; `docs/extending.md`
  gains an *Add a workspace tool* recipe. No behaviour change — documentation
  catching up to the shipped workspace + HTTP contour.

## [0.4.0] - 2026-06-07

### Added

- **Browser editor ↔ app full node-config parity**:
  the standalone `pipeline-editor.html` now mirrors every in-app
  `NodeConfigSheet` form field-for-field. The pipeline JSON interchange gained
  an optional, additive `nodeConfig` object per node — the exact
  `NodeConfigCodec` envelope (`{ "v":1, "type", "title", ...type fields... }`) —
  carried opaquely by `PipelineJsonSerializer` (the `domain` layer never
  interprets it; the flat `config` block stays authoritative for the runtime).
  The editor rewrote its 12 per-type forms onto the rich `NodeConfig` schema
  (temperature / topP / maxNewTokens / stop sequences, cloud model + maxTokens
  + timeout, intent-router classes with descriptions/examples, tool
  argument-mapping + confirm policy, summary format, decomposition/queue knobs,
  etc.) with the catalog `NodeConfigValidation` ranges, derives the flat runtime
  fields on export, and reads `nodeConfig` (falling back to deriving from the
  flat fields for older documents) on import. **EVALUATION nodes now expose
  three labelled output ports — Pass / Retry / Fail** (was a single generic
  output), matching `GraphExecutionEngine`'s verdict routing; the editor
  auto-labels the edges and round-trips them. The CLOUD provider gains a
  first-class **Auto** option (a new `CloudProvider.AUTO` catalog value that
  maps to the domain `"auto"` wire sentinel via `CloudProviderMapper.toWireId`
  / `fromWireId`) so an auto-routing node survives the editor→app round-trip
  and an in-app sheet save instead of silently decoding to OpenAI. Verified
  with a full export→import→export round-trip across all 12 node types in a
  headless browser.
- **CI on GitHub Actions**: the `.github/workflows/check.yml`
  gate is now tracked in the repository (previously kept locally and gitignored
  because the remote PAT lacked the `workflow` scope). The job runs
  `./gradlew check` — detekt, ktlintCheck, lintDebug, testDebugUnitTest,
  koverVerifyDebug, verifyBrowserEditorConstants and `checkNoInternalFqn` — on
  every `pull_request → main`, every `push` to `main`, and on manual
  `workflow_dispatch`. It sets up JDK 21 (temurin) and the Android SDK
  (the API 37 platform for `compileSdk 37` is fetched automatically by AGP),
  caches Gradle, and uploads detekt / ktlint / lint / unit-test / Kover /
  Roborazzi reports as artifacts on failure. A live build badge was added to
  `README.md`.
- **JetBrains Mono SemiBold (600) + Bold (700)**: the
  brand monospace family now ships its load-bearing heavier cuts as real
  Latin-subset font files (SIL OFL 1.1) instead of relying on synthetic bold —
  source / status tags, node-kind labels, badges and stat numbers render in
  Mono 700 at 9.5–11 px without smearing.
- **Custom `I.*` icon family**: the spec §0.7
  single-stroke icon set (73 glyphs — `menu`, `back`, `search`, `add`, `edit`,
  `trash`, `send`, `play`, `refresh`, `eye`, `terminal`, `undo`/`redo`, …, plus
  `pin`/`pin-on`) ships as custom `AppIcons.*` vectors built from the designer's
  SVG sources (stroke 1.6 default; solid for `more` / `play` / `pause` / `dot` /
  `pin-on` / `stop`; mixed for `theme`). **All Material Design icon call sites
  (240) migrated to the brand family** — the app UI now renders entirely on one
  stroke family (system Material components such as `RadioButton` keep their
  built-in glyph). The selected bottom-nav tab renders at the active 2.0 stroke.
  Stroke-weight and render-size icon tokens (`IconStroke`, `KnotworkIconSizes`)
  back the family.
- **`showcase_full_agent` bundled pipeline preset**: a
  seventh bundled preset under `assets/presets/pipelines/` — a 22-node
  on-device agent that triages each message (chat / factual / task) and runs a
  tailored branch: a direct `LITE_RT` reply for chat; an `IF_CONDITION`
  complexity-gated Wikipedia-lookup path for factual questions (single lookup
  or `DECOMPOSITION` → `QUEUE_PROCESSOR` research loop → `SUMMARY`); and a
  plan-and-loop flow for tasks (`DECOMPOSITION` → `QUEUE_PROCESSOR` → a second
  `INTENT_ROUTER` over clarify / lookup / act / process, with a human-in-the-loop
  `CLARIFICATION` node → `SUMMARY`). Carries rich per-node `nodeConfig`, passes
  `PipelineGraph.validate()` with zero errors, is mirrored into the browser
  editor's `BUILTIN_PIPELINE_PRESETS`, and is materialised as the first-launch
  seed.

### Changed

- **Rename the application to Knotwork**: the launcher/display name
  is now **Knotwork** (`app_name`), and the application id / package namespace
  moved from `ai.agent.android` to **`app.knotwork.android`** across the `:app`,
  `:tools-probe`, and `buildSrc` modules (package directories, Room exported
  schema path, `<queries>` package-visibility entries, the placeholder
  `google-services.json` client, the `checkNoInternalFqn` scan target, and all
  Kover exclusion FQNs were updated in lock-step). The application id is locked
  before the public release because Google Play treats it as the permanent,
  immutable app identifier. The internal codename (repository, branches, and the
  Gradle root project) stays `android-ai-agent`.
- **Bump LiteRT-LM `0.13.0` → `0.13.1`**: a bug-fix-only patch release of
  `com.google.ai.edge.litertlm:litertlm-android` (no API or behavior changes).
  Clears the `GradleDependency` lint error that flagged the newer version as
  available and was blocking `./gradlew check`.
- **`FILE_MAP.md` navigation reconciliation**: the
  `app/src/main/java/app/knotwork/android/FILE_MAP.md` agent/contributor navigation
  map was re-synced with the actual source tree — added the 11 missing pipeline
  `NodeExecutor` strategies (only `ClarificationNodeExecutor` had been listed),
  `PromptRepositoryImpl`, seven domain models (`ActiveModelMeta`, `Identity`,
  `MemoryStats`, `PromptTemplate`, `ProviderSummary`, `TestProbeResult`,
  `ToolApprovalPolicy`), the `IdentityRepository` / `PromptRepository`
  interfaces, `LongRunningTaskNotifier`, the three prompt-template use cases,
  and `ChatHomeFixtures`; corrected the misfiled `presentation/ui/common` +
  `presentation/ui/components` nesting; and removed the stale `data/remote/`
  entry. The `catalog/FILE_MAP.md` design-system map gained its entire
  previously-undocumented `screens/` tree (13 screen surfaces) plus the
  `components/{brand,pipelineeditor,topbar}` groups, missing tokens/components,
  and the new screen-level tests. No code changed.
- **Third-party license attribution audit**: added a
  repository `NOTICE` file inventorying every bundled runtime component, the
  required BSD-3-Clause notice for SQLCipher (`net.zetetic:sqlcipher-android`),
  the SIL OFL 1.1 copyright lines for the bundled Inter / JetBrains Mono fonts
  (with pointers to the full license texts under `app/src/main/assets/`), and
  the bundled Universal Sentence Encoder embedding model
  (`assets/universal_sentence_encoder.tflite`, Apache-2.0).
  The About screen's acknowledgments list was reconciled against the actual
  `libs.versions.toml` dependency set: dropped the phantom **Retrofit** /
  **Coil** entries (the network stack is OkHttp + Ktor) and the test-only
  MockK / Roborazzi credits, and added the previously-missing **AndroidX
  Jetpack**, **AppFunctions**, **Ktor**, **OkHttp**, **Gson**, **Multiplatform
  Markdown Renderer**, the bundled **Universal Sentence Encoder** model, and the
  **Inter** / **JetBrains Mono** fonts. A new
  `AboutAcknowledgmentsTest` guards the list against drift (no blanks, no
  duplicates, notice-required components present, stale non-dependencies
  absent). The README tech-stack "Network" row was corrected from
  "Retrofit, Coil" to "OkHttp + Ktor (via Koog)". All bundled licenses were
  verified Apache-2.0-compatible.
- **Context-window default**: the
  **Settings → LLM parameters → Max context** default is now **4096 tokens**
  (was 4000), landing exactly on a slider notch (range 512–8192, 512-token
  steps). A higher ceiling was trialled and reverted — large windows OOM-crash
  the on-device model on real hardware.
- **First-launch seed materialised from a preset**:
  `InitializeAppUseCase` now seeds the default pipeline by materialising the
  bundled `showcase_full_agent` preset through `LoadPipelineFromPresetUseCase`
  (fresh ids, validated, persisted) instead of hardcoding
  `DefaultPipelineFactory`. The factory remains a fallback if the preset asset
  cannot be loaded, so first launch never leaves the library empty.
- **`KnotworkChip` un-deprecated**: the general-purpose
  pill chip (`Default / Tonal / Outline` styles + decorative no-`onClick`
  variant) is reinstated as a supported design-system component rather than a
  removal target — it fills the decorative-tag / badge role the intent-specific
  `KnotworkFilterChip` / `KnotworkSuggestionChip` / `KnotworkInputChip` family
  does not cover. The `@Deprecated` annotation and the preview's stale
  `@file:Suppress("DEPRECATION")` were removed.
- **Design-token reconciliation against the Controls & Components spec**
 : aligned the Knotwork tokens and shared controls to the
  canonical `tokens.css` source of truth.
  - **Colour.** `primary` moved from accent-500 to **accent-600** (light) /
    **accent-300** (dark) so white `on-primary` clears the 3:1 UI-contrast floor
    on filled buttons. Added chat-bubble pairs (`chat-user/agent/tool` × bg/fg,
    with a distinct quieter tool bubble), the `console-tag` accent, and the
    memory source-tag provenance palette (`mem-auto/manual/compact` × bg/fg/rail
    — AUTO blue 220, MANUAL brand amber, COMPACT violet 285). All values derived
    deterministically from the `oklch` token source.
  - **Iconography.** Default stroke 1.5 → **1.6**; added named stroke
    (default / active / contextual) and render-size (22 / 20 / 18 / 26 / 14)
    tokens per spec §0.7.
  - **Controls.** Button labels now Inter 600 / 14 sp / +0.1; `RiskPill`
    JetBrains Mono 500 / +0.2; node-kind and `CURRENT` pills moved to
    JetBrains Mono 700 (with a real 5 px dot on `CURRENT`); icon-button glyph
    22 dp and badge 14 dp / Inter 700 / primary fill; bottom-nav on `surface-2`
    with a hairline top divider and Inter 11 sp labels.

### Removed

- **Dead string resources**: removed 54 unused string
  resources that lingered as legacy duplicates after screen bodies moved into
  the `:catalog` module — 52 from the app's `strings_*.xml` files (the fully
  orphaned `strings_tools.xml` was deleted outright) and 2 `knotwork_*` strings
  from the catalog. Their `UnusedResources` entries, plus 6 now-stale
  `PluralsCandidate` / `TypographyEllipsis` entries that pointed at the deleted
  strings, were pruned from `app/lint-baseline.xml`. detekt's
  `UnusedPrivate*` rules were already clean; no code symbols were removed.

### Fixed

- **Auto-Layout crashed (`StackOverflowError`) on pipelines with a loop**:
  the editor's `AutoLayout` longest-path layering recursed without a cycle
  guard, so any graph containing a legitimate back-edge — e.g. a
  `QUEUE_PROCESSOR` re-iteration loop, as in the `multi_step_research` and
  `showcase_full_agent` bundled presets — overflowed the stack the moment
  **Auto-Layout** was tapped. The depth resolver now tracks its recursion
  stack and ignores back-edges (and self-loops) for layering, so cyclic
  pipelines lay out without crashing.
- **Auto-Layout overlapped nodes on high-density screens**: the layout
  gaps were fixed canvas-px values, but a `NodeCard` is sized in dp and the
  canvas maps one unit to one screen-px, so on a 3×-density display each card
  rendered ~3× wider than the spacing assumed and the cards piled on top of
  each other. The editor now derives the sibling / layer gaps from the card
  footprint in dp, scaled through the screen `Density`, so nodes keep clear
  air between them at any density.
- **TOOL node with "Auto" tool failed at runtime**: a
  TOOL node left on the Auto option (persisted as a blank `toolName`) errored
  with "Tool node is missing toolName configuration" instead of letting the
  model pick a tool. `ToolNodeExecutor` now treats a blank/null `toolName` the
  same as the explicit `"auto"` sentinel (LLM-driven auto-select); only a
  configured-but-unknown tool name is an error.
- **Chat token-usage indicator counted a token budget as characters**
 : `GetContextWindowUseCase` compared message
  **character** lengths against `maxContextLength` (a **token** budget), so the
  history was truncated to ~¼ of the window and the TopAppBar "tokens used"
  bar capped at ~25%. It now converts the token budget to characters
  (`× CHARS_PER_TOKEN`) before truncating. Display-only — the actual prompt and
  the engine's `maxNumTokens` windowing were already correct.
- **Deleting a local model now removes its file**:
  `LocalModelRepository.deleteModelById` only dropped the Room record, leaving
  the (often multi-GB) weights file orphaned on disk. It now deletes the
  on-disk file at the model's path first (best-effort — a missing/unreadable
  file never blocks the record removal).
- **Dependency freshness**: bumped `androidx.core:core-ktx`
  1.18.0 → 1.19.0 and `com.google.ai.edge.litertlm:litertlm-android` 0.12.0 →
  0.13.0 (both Apache-2.0, no new transitive licences). The `GradleDependency`
  and `NewerVersionAvailable` lint checks are kept enabled so outdated
  dependencies are surfaced and updated rather than silenced; the deliberate
  Kotlin/Compose-plugin pin (2.3.21, a separate toolchain upgrade) and the
  mediapipe false positive (its version scheme sorts `0.20230731` as newer than
  the actual-latest `0.10.35`) are grandfathered individually in
  `lint-baseline.xml`.
- **Documentation ↔ code reconciliation**: a sweep
  aligning the public docs with current behaviour. `FILE_MAP.md` no longer
  claims MCP "Only SSE … through Koog 0.8; STREAMABLE_HTTP falls back to SSE" —
  both transports are end-to-end wired on Koog 1.0.0 (`KoogMcpClient` uses
  `mcpStreamableHttpTransport`). `docs/user-guide.md` now lists all nine prompt
  variables (was missing `$LANG` / `$LOCATION` / `$USER` / `$DEVICE`) and
  documents the first-launch showcase pipeline; `docs/extending.md` and
  `DESCRIPTION.md` reflect the seven bundled presets and the preset-backed seed.
- **UI functional verification — every control does something**:
  a screen-by-screen sweep wiring orphaned callbacks, fixing node-config
  forms, and removing dead / misleading affordances.
  - Pipeline editor: the empty-state **"From template"** CTA now fills the
    current pipeline from a chosen preset (was a "coming soon" Snackbar); the
    **EVALUATION** node now routes through its Pass / Retry / Fail output ports
    based on the model's verdict (was: always took the first edge).
  - Pipeline library: **Import JSON** now opens a real document picker and
    imports the pipeline (with a schema-mismatch confirm); the row subtitle
    lists node types in execution order (walked from INPUT) rather than
    storage order. Monitoring **Retry** reloads the system-log stream.
  - Node config: TOOL "Auto" no longer blocks Save; the QUEUE input-list
    expression is optional with a helper; CLARIFY gains a quick-replies helper
    and a 0–360 s timeout slider (0 = none).
  - Tools: discovered AppFunctions are hidden from the list (kept callable by
    the agent); the ToolDetail and Add-MCP-server top bars now match the app
    chrome.
  - Removed affordances that did nothing or misled: top-bar overflow menus
    (Library / Tools / Models), the More and Settings search icons (the latter
    opened an empty sheet), the Models active-model chevron, and the duplicate
    Settings "Change" link. Memory's search field can now be dismissed without
    first typing a query. Provider picker / detail screens aligned to the
    standard chrome.
  - Closed the `MoreViewModel` and `ProviderDetailViewModel` unit-test gaps;
    added a regression asserting the first-launch seeded pipeline passes
    `PipelineGraph.validate()` with zero errors.
  - Chat auto-scroll: opening a thread jumps to the latest message; a newly
    appended message (user or agent) is revealed automatically — top-aligned
    when it is taller than the screen, otherwise bottom-aligned — and nothing
    scrolls when the whole conversation already fits.

## [0.3.0] - 2026-05-30

Rolls up the post-`0.2.0` work that landed on `main`: the complete long-term
**memory lifecycle** (extraction, embedding-provider abstraction,
retrieval + re-rank, background compaction, export / import, memory-screen
redesign and tuning controls), **pipeline & prompt presets** end-to-end
(bundled catalogues, in-app pickers, browser-editor preset support
and gradle-driven constant-sync automation), and the **test / coverage
hardening** that raised the enforced Kover gate to 75 % (executor / DAO / Robolectric / Compose `androidTest` suites).

### Added

- **Long-term memory documentation + end-to-end test**:
  - `DESCRIPTION.md` §6 now documents the full memory subsystem (extraction,
    embedding providers, storage, retrieval/re-rank, context injection,
    compaction, import/export, and the message-to-retrieval lifecycle).
  - `docs/architecture.md` gains a §2.2 Mermaid *memory lifecycle* diagram;
    `docs/extending.md` gains an *Add a new `EmbeddingProvider`* recipe;
    `docs/user-guide.md` gains a *memory search isn't finding an entry*
    troubleshooting section.
  - New instrumented `MemoryLifecycleIntegrationTest` wires the real domain
    components (Room, `MemoryRepositoryImpl`, `MemoryReranker`,
    `NodeContextBuilder`, `KMeansClusterer`, extraction/retrieval/compaction
    use cases) over an in-memory database: a fact extracted in one session is
    retrieved into the next session's `--- Long-Term Memory ---` block and a
    pinned chunk survives a compaction pass.
- **Memory tuning controls** — *Settings → Memory*
  now exposes the long-term-memory parameters that previously only had code
  defaults:
  - **Sliders** for retrieval *Search results (top-K)* (1–20), *Similarity
    threshold* (0.30–0.90), *Recency half-life* (7–180 days), *Compaction age*
    (7–90 days), and *Max stored chunks* (1 000–20 000).
  - A **Background compaction** toggle and an **Embedding model** dropdown that
    lists every registered provider (on-device USE, OpenAI, Ollama) and persists
    the active selection.
  - Out-of-range or unknown-provider edits are rejected at the ViewModel layer
    with an inline validation message and are never persisted.
- **Memory export / import** — move an agent's
  long-term memory between devices:
  - **Export** — *Settings → Memory → Export* writes the table to a
    `schemaVersion: 1` JSON file via the Storage Access Framework, stamped with
    the active embedding provider id and an export timestamp (new
    `domain/memoryio/MemoryJsonSerializer`; the existing `ExportMemoryBaseUseCase`
    now emits the richer document, including per-chunk provenance and tags).
  - **Import** — *Settings → Memory → Import* parses a file and offers a
    **Merge** (keep existing, skip duplicate ids) or **Replace all** (wipe then
    load) strategy (new `MemoryImportUseCase`), preserving each chunk's id,
    provenance, pin state, and tags.
  - **Provider-mismatch handling** — when the file was exported under a
    different embedding provider, imported chunks are flagged `needsReembedding`
    and re-computed with the active provider by a background WorkManager job
    (new `MemoryReembedWorker` + `RecomputePendingEmbeddingsUseCase`, scheduled
    at import time), so transferred memories become findable off the hot path
    without stalling retrieval or needing a manual re-embed. The manual
    *Settings → Memory → Re-embed* action now also clears the flag.
- **Memory screen redesign** — a full rework of the
  long-term-memory surface:
  - **Save to memory from chat** — the message long-press menu gains a
    *Save to memory* action that embeds the message text with the active
    `EmbeddingProvider` and stores it as a `Manual` chunk (new
    `SaveMessageToMemoryUseCase`), confirming with a *Saved to memory* snackbar.
  - **Stats header** — total count, on-disk size, "compacted N ago", and a
    provenance breakdown bar (Auto / Compaction / Manual) with a one-tap
    **Compact** action gated behind a confirm dialog that previews an estimate
    (≈ removed / freed / runtime) via the new `EstimateCompactionUseCase`; the
    manual Compact now runs the real consolidation pass.
  - **Category chips + dropdowns** — single-select category chips (All / Pinned
    / Auto / Manual / Compaction) with live counts, plus Sort and date-range
    dropdowns.
  - **Semantic search** — the search field now embeds the query and ranks
    results by relevance, showing a per-row score.
  - **Time-grouped list** — entries grouped into Pinned / Today / This week /
    Earlier, each row carrying a provenance accent + badge and its tags.
  - **Rich detail sheet** — token estimate, source, "Learned from" chat,
    captured time, "Used in N replies", inline body + tag editing, and
    pin / delete / save actions.
  - **Add memory** — a FAB + dialog to store a memory by hand.
  - **Tags & usage tracking** — chunks now carry tags (auto-extraction persists
    each fact's type) and a retrieval use-count / last-used time recorded by the
    pipeline engine. Backed by an additive Room migration (26 → 27).

### Changed

- `MemoryRepository` gains `setMemoryTags` / `recordUsage` and a `tags`
  parameter on `saveMemory`; `ExportMemoryBaseUseCase` accepts an optional id
  subset; `SettingsRepository` records the last compaction time.

### Fixed

- **Long-term memory now embeds every read and write with the active provider**
 . Retrieval embedded the search query with the fixed
  on-device Universal Sentence Encoder (512-d) while auto-extraction
  stored chunks via the user-selected `EmbeddingProvider` — so with a non-`use`
  provider active (OpenAI 1536-d, Ollama 768-d) query and stored vectors lived
  in different dimensions, cosine similarity collapsed to `0`, and the
  `longTermMemory` node flag surfaced nothing. All memory paths now resolve the
  same active provider via `EmbeddingProviderResolver`:
  - `RetrieveRelevantMemoryUseCase` (read) — so enabling the flag actually
    injects relevant memories regardless of the chosen backend.
  - `DelegateTaskTool` (delegated-result write), `MemoryViewModel.editVectorMemory`
    (inline edit re-embed) and `ReembedAllMemoriesUseCase` (Settings → Memory →
    Re-embed) — previously these persisted 512-d USE vectors that a non-`use`
    query could never match. Re-embed remains the canonical way to migrate the
    whole corpus into a newly selected provider's space.
- **Memory retrieval is skipped when no executed node requests it**
 . `GraphExecutionEngine` previously embedded the user
  prompt at run start unconditionally. It now resolves long-term memory lazily —
  at most once, the first time an executed node actually opts into the
  `longTermMemory` context block — so graphs with memory disabled never embed
  the prompt, sparing avoidable cloud-embedding latency/cost and not shipping
  the prompt to an embedding backend the user did not enable memory for.

### Added

- **Memory observability in the agent console**. Every
  long-term-memory retrieval now surfaces in the chat console as a dedicated
  `MEMORY` source line (previously collapsed into the generic `RUNTIME`
  source), so the new `MEMORY` filter chip isolates memory activity from node
  and tool output. Each retrieval line echoes the truncated query, the hit
  count, and the per-hit similarity scores
  (`Memory: query='…' → 2 hits (0.83, 0.40)`) instead of the old bare count.
  A new opt-in **Settings → Privacy → Verbose memory logging** toggle (default
  off, `SettingsRepository.verboseMemoryLoggingEnabled`) expands each retrieval
  line with a per-hit snippet + score, and makes `MemoryCompactionUseCase` log
  the cluster membership (merged chunk ids) of every consolidation to logcat.
- **Background memory compaction**. A daily
  `MemoryCompactionWorker` (WorkManager, constrained to charging + device-idle)
  consolidates stale, redundant long-term memory so the `memory_chunks` table
  does not balloon with near-duplicate facts over weeks of use. The pass loads
  non-pinned chunks older than `memoryCompactionAgeDays` (default 30), clusters
  them by embedding similarity with a new deterministic `KMeansClusterer`
  (`k = max(1, floor(sqrt(N) / 2))`), and for every cluster of ≥ 3 chunks runs a
  single local-model consolidation prompt
  (`DefaultPrompts.MemoryCompaction`), embeds the summary with the active
  provider, saves it tagged `MemorySource.Compaction` (carrying the merged ids),
  and deletes the originals. Pinned chunks are never touched; a blank model
  reply or an embedding error skips only that cluster (its originals are kept).
  An out-of-schedule watch (`MemoryCompactionScheduler.startHardLimitWatch`)
  triggers an immediate, relaxed-constraint pass when the table grows past
  `maxMemoryChunks` (default 5000). New settings `memoryCompactionEnabled`
  (default on), `memoryCompactionAgeDays` and `maxMemoryChunks` back the feature
  (the Settings → Memory UI for them lands separately).
- **Memory retrieval re-ranking**. Raw cosine
  similarity is no longer the final word on what reaches the prompt. A new
  pure-domain `MemoryReranker` re-scores the full scored search pool before
  the top-K cut, applying four deterministic rules:
  - **Recency weighting** — a non-pinned chunk's score decays with age
    (`final = similarity * (1 - 0.5 * daysSince / halfLife)`, floored at 0),
    so a stale chunk no longer crowds out a fresher one. The half-life is
    configurable via the new `SettingsRepository.memoryRecencyHalfLifeDays`
    (default 30 days; Settings UI lands with the later Settings/tuning task).
  - **Pinned boost** — pinned chunks skip decay, gain a flat `+0.2`, sort
    ahead of every non-pinned chunk, and are exempt from the threshold filter,
    so a deliberately curated fact is always surfaced.
  - **Deduplication** — chunks sharing their first 80 characters collapse to
    the newest survivor, sparing the limited context budget.
  - **Threshold filter** — applied to the *final* (post-rerank) score.
  Re-ranking lives in `RetrieveRelevantMemoryUseCase` (the retrieval-only
  path), leaving `MemoryRepository.findSimilarMemories` raw for
  `MemoryExtractionUseCase`'s near-duplicate detection. The use case now pulls
  the full scored pool so a pinned or fresh chunk just outside the raw-cosine
  top-K can still be promoted.
- **Configurable memory retrieval tuning**. The
  retrieval top-K and relevance threshold are no longer hard-coded:
  `SettingsRepository.memorySearchTopK` (default 5) and
  `memorySearchThreshold` (default 0.55) back them via DataStore.
  `RetrieveRelevantMemoryUseCase` reads these by default (callers may still
  override per-call). The Settings UI for these controls lands with the later
  Settings/tuning task of this phase.
- **Automatic memory extraction**. After a pipeline run
  completes, the agent now mines the conversation for durable facts and writes
  the novel ones into long-term memory — making the "remembers past chats"
  capability actually populate memory instead of relying on manual saves.
  - A new `MemoryExtractionUseCase` (domain) runs the local model once with a
    conservative, no-hallucination prompt
    (`DefaultPrompts.MemoryExtraction.SYSTEM_FALLBACK`, `$DATE`-grounded) that
    returns a JSON array of `{type, text}` facts; all facts are embedded in a
    single batch `EmbeddingProvider.embed(List)` call (one cloud round-trip
    instead of N) and each is saved only if it is not a near-duplicate
    (cosine ≥ 0.92) of an existing chunk or another fact from the same pass.
  - A `MemoryAutoExtractionCoordinator` (domain, app-scoped) triggers the pass
    on pipeline completion with a 30 s per-session debounce, short-circuiting
    when the toggle is off and deferring while another pipeline is still
    generating.
  - The on-device inference engine (`LiteRTLlmEngine`) now serialises every
    generation behind a `Mutex`, since LiteRT-LM allows only one active
    conversation — this prevents the background extraction pass and a
    foreground response from concurrently tearing down each other's session
    (which could crash the native layer).
  - Memory chunks now carry a typed `MemorySource`
    (`ChatSession` / `Manual` / `Compaction` / `Unknown`), persisted via a new
    `memory_chunks.source` column (Room migration 25 → 26, legacy rows backfilled
    to `Unknown`).
  - New `Settings → Memory → Auto-extract from conversations` toggle
    (`SettingsRepository.autoExtractEnabled`, default on) describing exactly what
    is collected.
- **Embedding provider abstraction**. Long-term memory
  no longer hard-codes the on-device Universal Sentence Encoder. A new
  `EmbeddingProvider` domain abstraction (`embed` / batch `embed` /
  `dimension` / `id` / `displayName`) is implemented by three backends, Hilt-
  multibound into a `Map<String, EmbeddingProvider>` and selected at call time
  by `EmbeddingProviderResolver`:
  - `use` — on-device MediaPipe Universal Sentence Encoder (512-d), the
    default; needs no network or API key.
  - `openai_3_small` — OpenAI `text-embedding-3-small` (1536-d) via the
    existing Koog OpenAI client.
  - `ollama` — local-network Ollama `nomic-embed-text` (768-d) via the Koog
    Ollama client.

  The active backend is persisted in the new
  `SettingsRepository.activeEmbeddingProviderId` setting (default `"use"`).
  Each provider reports `isAvailable()` (cloud providers require a configured
  key / base URL); when the selected provider is unavailable the resolver
  substitutes the on-device default — keeping each provider's declared
  `dimension` honest rather than silently returning mis-dimensioned vectors.
  Backend failures surface as a typed `EmbeddingException`, and coroutine
  cancellation is propagated unwrapped. Existing embedding consumers are
  unchanged in this task; migrating them onto the resolver lands with the
  later memory tasks of this phase.
- **Browser-editor constant sync automation**. The
  `:app:generateBrowserEditorConstants` Gradle task regenerates the
  `NODE_TYPES`, `PROMPT_VARIABLES`, `AVAILABLE_TOOLS` and
  `DEFAULT_SYSTEM_PROMPTS` blocks of `pipeline-editor.html` straight from the
  Android domain sources (`NodeType.kt`, `DefaultPrompts.kt`,
  `PromptTemplateModule.kt`, `LocalToolsModule.kt`), injecting them between
  `AUTO-GEN` markers. `:app:verifyBrowserEditorConstants` — wired into
  `./gradlew check` — fails the build if the committed HTML has drifted,
  replacing the previous review-only "KEEP IN SYNC" rule that had let those
  mirrors diverge. The pure generation logic lives in `buildSrc`
  (`BrowserEditorConstantsGenerator`) with its own JUnit suite; editor-only
  metadata (palette order, colours, icons, tool labels) is cross-checked
  against the domain set so adding a `NodeType`/tool without metadata fails
  generation.
- **Pipeline presets — browser editor**. The
  standalone `pipeline-editor.html` gains a `📚 Presets` top-bar button
  opening a modal with **Bundled** and **Mine** tabs:
  - **Bundled** mirrors the 6 starter presets shipped in the APK under
    `assets/presets/pipelines/*.json`, inlined as the
    `BUILTIN_PIPELINE_PRESETS` JS constant (a later change will replace the
    hand-maintained block with a gradle-generated one).
  - **Mine** lists per-browser presets persisted in `localStorage`
    (degrades to an empty in-memory list in private-mode browsers).
  - Each card offers **Load** (materialises the preset onto the canvas
    via the existing `importFromJson` path, with a freshly-minted
    pipeline id) and **Export** (downloads `<id>.preset.json`); Mine
    cards also offer **Delete**. The modal footer adds **Import
    preset…** (parses a `.preset.json`, runs a schema-version mismatch
    confirm when needed, and stores it under Mine without touching the
    canvas) and **Save canvas as preset…** (a name / category / tags /
    description form, 60-char name cap matching
    `SavePipelineAsPresetUseCase`).
  - The `.preset.json` format is a strict superset of the pipeline JSON
    — same `schemaVersion` / `id` / `name` / `updatedAt` / `nodes` /
    `connections` plus `category` / `tags` / `description` — matching
    `PipelinePresetJsonSerializer.kt`, so files round-trip between the
    browser editor and the Android app.

- **Prompt presets — UI**. Wires the bundled and
  user-saved prompt-preset catalogue into the two production
  surfaces:
  - The pipeline editor's `NodeConfigSheet` gets a 📚 / 💾 pair on
    every prompt-bearing field. 📚 opens a new Knotwork-styled
    `PromptPresetPickerSheet` (modal bottom sheet) filtered by the
    active node's `NodeType`, with **Bundled** / **Mine** tabs, a
    leading `All N` chip + per-tag filter chips, 200 ms debounced
    search by name, radio-style row selection, a per-row magnifier
    preview, a `● CURRENT` pill on the row whose prompt matches the
    field's current value, and a sticky bottom `Cancel / ✓ Use prompt`
    bar. The Preview action surfaces the existing
    `PromptPreviewBottomSheet` with full `$VARIABLE` substitution. 💾
    captures the current draft as a user preset via a
    name / description / tags dialog routed through
    `SavePromptAsPresetUseCase`.
  - The standalone **Prompt library** screen (More → Library) now
    surfaces the same `PromptPreset` catalogue (bundled + user) in
    place of the legacy `PromptTemplate` source. Bundled rows are
    read-only (delete is silently a no-op); user rows can be edited
    via the existing bottom-sheet editor — saves go through
    `SavePromptAsPresetUseCase` with `existingId` (new upsert path)
    so an edit replaces the same preset in place. Duplicate writes a
    new user preset with a `(copy)` suffix; the source preset can be
    bundled, which is the canonical path for "start customising a
    bundled template".
  Replaces the legacy `PromptLibraryDialog` (Knotwork-styled
  `AlertDialog`) that was backed by the older `PromptTemplate` model.

- **Prompt presets — domain model & bundled catalogue**. New first-class entity for reusable system-prompt templates,
  attached to a single LLM-driven `NodeType` (LITE_RT, CLOUD, OUTPUT,
  SUMMARY, INTENT_ROUTER, DECOMPOSITION, EVALUATION, CLARIFICATION).
  Bundled presets ship inside the APK at `assets/presets/prompts/` (18
  starter prompts — 2–3 per LLM-driven type, including
  `litert_concise_assistant`, `output_markdown_with_sections`,
  `router_keyword_classifier`, `decomposition_json_subtasks`,
  `clarification_multiple_choice`, …) and are loaded read-only via
  `LocalPromptPresetRepositoryImpl`. User-saved presets land in a new
  `prompt_presets` Room table (schema v25), created and observed
  through `PromptPresetDao` / `PromptPresetRepository`. The new
  `SavePromptAsPresetUseCase` is the single entry point for the
  user-save flow — it validates name (1..60 chars), `systemPrompt`
  (≤ 8000 chars, `PromptPresetConstants.MAX_SYSTEM_PROMPT_LENGTH`), and
  the target `NodeType`. The catalogue ships behind a
  `PromptPresetCatalogValidationTest` (JVM unit) that pins the filename
  set, asserts every `nodeType` is LLM-driven, every `$VARIABLE`
  resolves against the registered provider whitelist, every
  `systemPrompt` fits within the soft limit, and every LLM-driven type
  has at least one bundled preset. The Prompt-Library UI rewiring that
  actually surfaces this catalogue lands separately.

- **Pipeline presets — UI**. Surfaces the
  preset catalogue end-to-end through three user-facing entry points:
  - **Speed-dial FAB** on the pipeline library — replaces the single
    "+ New pipeline" FAB with a two-action speed-dial (`+ New pipeline` /
    `+ From preset`). The "+ From preset" action opens a new modal
    `PresetPickerSheet` with Bundled / Mine tabs, `PresetCategory`
    filter chips, and a one-line `INPUT → LITE_RT → OUTPUT` graph
    preview on every card. Tapping "Use this preset" materialises a
    fresh pipeline via `LoadPipelineFromPresetUseCase` and routes the
    user straight into the editor.
  - **Save as preset** action — exposed in both the pipeline-library
    row overflow menu and the editor overflow. Opens a dialog
    capturing name / description / category / tags, then persists via
    `SavePipelineAsPresetUseCase`.
  - **More → Library** — new `PipelinePresetsManagerScreen` reachable
    from the More tab. Bundled presets render as read-only rows;
    user presets expose Rename / Export-JSON (via SAF) / Delete with
    a destructive-confirm dialog.

- **Bundled pipeline-preset catalogue**. Ships six
  curated starter presets under `assets/presets/pipelines/` covering the
  typical entry-point scenarios: `local_only_qa` (offline INPUT → LITE_RT →
  OUTPUT), `cloud_assist` (cloud with chat history + long-term memory),
  `tool_using_react` (reasoner → tool → summariser chain),
  `multi_step_research` (DECOMPOSITION → QUEUE_PROCESSOR with an explicit
  `Item` / `Done` fan-out plus a back-edge that loops each subtask through
  a cloud researcher), `clarify_then_act` (CLARIFICATION gate before a
  local reply) and `routed_local_cloud` (INTENT_ROUTER sending simple
  requests on-device and complex ones to the cloud). A new
  `PipelinePresetCatalogValidationTest` (JVM unit) parses every bundled
  file, runs `PipelineGraph.validate()` over each embedded graph, and
  verifies that every `$VARIABLE` token resolves against the registered
  `PromptVariableProvider` set in `di/PromptTemplateModule.kt` — so adding
  a broken preset, an unknown variable, or accidentally deleting one of
  the six fails the build.

- **Pipeline preset — domain model and storage**.
  Introduces `PipelinePreset` as a reusable pre-built pipeline template
  with two persistence tiers:
  - **Bundled** presets ship inside the APK under
    `assets/presets/pipelines/*.json` (the loader and the empty
    directory land first; the catalogue files are filled in afterwards).
  - **User** presets are persisted in a new `pipeline_presets` Room
    table (schema **v23 → v24** via `MIGRATION_23_24`).
  `LoadPipelineFromPresetUseCase` materialises a preset into a concrete
  pipeline with fresh ids (regenerated for the pipeline, every node, and
  every connection — orphan connections are dropped, mirroring
  `DuplicatePipelineUseCase`). `SavePipelineAsPresetUseCase` packages the
  current graph into a user preset after validating the name (1..60 chars)
  and running `PipelineGraph.validate()`. The new
  `PipelinePresetJsonSerializer` delegates the embedded graph half to
  `PipelineJsonSerializer`, keeping the preset and pipeline formats
  forever in sync.

### Changed

- **`MemoryAccess` console events carry query + scores**.
  The format moved from `Memory: N chunk(s) retrieved` to a richer line built
  by the new pure `MemoryAccessLogFormatter`. `RetrieveRelevantMemoryUseCase`
  gains a score-preserving `retrieveScored(...)` entry point (the score-free
  `invoke(...)` now delegates to it) so the engine can render scores without a
  second retrieval.
- **Browser pipeline editor — full sync sweep**.
  Re-synced `pipeline-editor.html` with the Android source of truth after
  the accumulated drift:
  - `BUILTIN_PROMPT_TEMPLATES` (the 📚 Prompts popover) now mirrors the
    21-entry bundled prompt-preset catalogue from
    `assets/presets/prompts/` verbatim — `systemPrompt`,
    name, and description copied byte-for-byte — replacing the 7 legacy
    generic per-type entries. The popover filters built-ins by the node's
    `NodeType`, matching the Android Prompt Library; each row carries the
    preset description as a hover tooltip.
  - `IF_CONDITION` presets are routed to the **condition-prompt** field, not
    `systemPrompt`: the IF picker lives on the "Classification prompt" field
    (which maps to `NodeModel.conditionPrompt` — the only field
    `EvaluateIfConditionUseCase` reads for branching), and the IF node no
    longer renders a `systemPrompt` field at all, mirroring
    `NodeConfigForms.IfConditionFormBody`. Previously an IF preset populated
    an ignored `systemPrompt` and left `conditionPrompt` empty, so the
    imported node fell through to `false`.
  - `AVAILABLE_TOOLS` (TOOL-node config) re-synced with the
    `LocalToolExecutor` registry: `web_search` → `search_tool`, plus the
    previously-missing `schedule_task`. The ids now equal the executors'
    `TOOL_NAME` constants so TOOL nodes built in the browser resolve
    on-device.
  - `CLOUD_PROVIDERS` gains `ollama`, matching the `CloudProvider` enum.
  - Verified in-sync (no change needed): `PROMPT_VARIABLES` (9 variables),
    `DEFAULT_SYSTEM_PROMPTS`, `NODE_TYPES`, `NODE_TYPE_TOOLTIPS`,
    `defaultContextConfig`, and the `schemaVersion: 1` JSON node contract.
- **Dependency currency**: bumped `firebase-bom` 34.13.0 → 34.14.0 to clear
  the `GradleDependency` lint error gating `./gradlew check`.

### Fixed

- **Preset pickers no longer apply a hidden selection after a filter
  change**. In `PresetPickerSheet` (pipeline presets) and
  `PromptPresetPickerDialog` (prompt presets), selecting a preset and then
  switching the tab / category / tag chip left the previous selection
  active: the footer CTA stayed enabled on the non-null id and applied the
  now-hidden preset instead of the row visible to the user. Both pickers now
  require the selected id to still be present in the filtered list before
  enabling and applying the action (`selectionVisible` /
  `visibleSelectedRowId`), so the CTA disables when the pick scrolls out of
  view and only ever instantiates a currently-visible preset.

### Build / coverage

- **Coverage gate raised 70 % → 75 % LINE aggregate**.
  `koverVerifyDebug` (run via `./gradlew check`) now fails the build if
  aggregate line coverage over the unit-testable surface drops below 75 %.
  Today's measurement after the new exclusions sits at ~77.6 %, leaving
  ~2.6 pp of headroom against silent regression. Per-package targets are
  documented as informational guidance in
  [`docs/coverage-baseline.md`](docs/coverage-baseline.md); they will be
  promoted to enforced rules once Kover 0.10 ships rule-level filters
  (0.9.8 is the latest available on the Gradle Plugin Portal). Several
  Compose-surface and Android-runtime-glue packages introduced earlier were also added to the Kover exclusion list to align with the
  existing `presentation.ui.*Screen*` convention:
  `presentation.ui.navigation.*`,
  `presentation.ui.about.{AboutScreen,AboutAcknowledgments}*`,
  `presentation.ui.more.MoreScreen*`,
  `presentation.ui.settings.provider.{ProviderPickerScreen,ProviderDetailScreen}*`,
  and `data.tools.local.appfunctions.*`. Without these exclusions the
  aggregate would have been 73.8 % — the previously-claimed "~80 %" in
  the baseline doc was stale because the new screens never made it into
  the filter when they shipped. Touched files:
  [`app/build.gradle.kts`](app/build.gradle.kts),
  [`docs/coverage-baseline.md`](docs/coverage-baseline.md),
  [`docs/static-analysis.md`](docs/static-analysis.md),
  [`docs/testing.md`](docs/testing.md).

### Documentation

- **Preset feature documented end-to-end**.
  `docs/extending.md` §5 is restructured into "Add a bundled preset"
  with a new pipeline-preset recipe (asset schema, `PresetCategory`
  keys, `validate()` / variable-whitelist rules, the
  `PipelinePresetCatalogValidationTest` registration step, and the
  hand-maintained `BUILTIN_PIPELINE_PRESETS` mirror in
  `pipeline-editor.html`) alongside the existing prompt-preset recipe,
  plus two new rows in the synchronization table. `docs/user-guide.md`
  gains a "Pipeline presets" section (bundled vs. Mine, cross-linked to
  the browser editor). `DESCRIPTION.md` adds §8.3 (preset domain model,
  repositories, bundled-vs-user lifecycle, Room migrations v23→v24 /
  v24→v25, build-time guarantees) and §10.6 (preset flow in the
  Library).

### Tests

- **Preset end-to-end integration tests**. Two
  pure-JVM suites that wire the real production classes together rather
  than the shipped artefacts alone (which the catalogue tests already
  pin): `PipelinePresetIntegrationTest` reads each bundled
  `assets/presets/pipelines/*.json`, materialises it through the real
  `LoadPipelineFromPresetUseCase` (asserting fresh ids + preserved
  connections + zero validation errors), then runs the `local_only_qa`
  preset through a real `GraphExecutionEngine` (only the
  `LlmInferenceEngine` token stream stubbed) and asserts it reaches
  `AgentOrchestratorState.Completed`. `PromptPresetIntegrationTest`
  parses every bundled `assets/presets/prompts/*.json`, applies the
  body to a `NodeModel.systemPrompt`, renders it through the real
  `PromptTemplateEngine`, and asserts every registered `$VARIABLE` is
  substituted (plus the escape / unknown-token contract).

- **Compose `androidTest` coverage for the remaining `presentation.ui`
  surfaces — Memory / Settings / Tools / Onboarding / Prompt Library**
 . Each screen gets a shared
  `mock<Screen>ViewModel` factory (relaxed MockK VM + mutable state-flow
  handles so tests drive transitions without re-stubbing) and 2–6 test
  classes under
  `app/src/androidTest/java/app/knotwork/android/presentation/ui/<screen>/`.
  Coverage: `MemoryScreenSearchTest` (200 ms debounce gated by
  `mainClock.advanceTimeBy`), `MemoryScreenInteractionsTest` (row pin /
  edit-commit which exercises the re-embedding call site / delete /
  pinned-glyph rendering / empty-state CTA);
  `SettingsScreenRestartRequiredTest` (banner test-tag visibility),
  `SettingsScreenDestructiveConfirmTest` (typed-confirm dialog,
  Confirm button gated on the resource keyword),
  `SettingsScreenTogglesTest` (LazyColumn scroll-to + Notifications
  toggle wiring); `ToolsScreenLocalToolsTest` (risk-pill rendering for
  all three `ToolRisk` levels, Switch tap invokes `toggleLocalTool`,
  row tap routes to `onOpenToolDetail`), `ToolsScreenMcpServersTest`
  (Connecting → Connected subtitle flip, expand-chevron toggle,
  overflow Refresh action); `OnboardingScreenPagerTest` (Welcome
  headline render, Continue CTA forwards to `next`, top-bar Skip
  invokes `skipOnboarding` + `onCompleted`, Ready step suppresses Skip
  and finishes via the primary CTA), `OnboardingScreenDownloadGateTest`
  (LiteRtModel CTA matrix across no-install / in-flight / installed,
  Ready step CTA gated on `isModelWarmed`); `PromptLibraryScreenListTest`
  (category-filtered list, tab-tap forwards to `selectCategory`, FAB
  opens a new draft, per-card Edit / Delete / Duplicate icons fire
  their VM hooks), `PromptLibraryScreenEditorTest` (bottom-sheet
  visibility on a non-null draft, prefilled fields, Save / Cancel
  dispatch). All factories follow the same
  pattern (`createComposeRule()` + relaxed MockK VM); no production
  code changes were required. Note: `SettingsUiState` does not expose
  the "pending change" or "ValidationError" surfaces mentioned in the
  task brief, so those branches are deliberately out-of-scope.

- **Compose `androidTest` coverage for `presentation.ui.pipeline.editor`**
 . Twelve test files mirroring the
  chat-home pattern: `createComposeRule()` + a shared
  `mockOrchestratorViewModel` factory (exposes mutable `StateFlow` /
  `SharedFlow` handles for `uiState`, `runState`, `focusNodeRequest`
  so tests drive state transitions without re-stubbing) plus a
  `PipelineEditorTestFixtures` graph palette. Most tests render
  internal canvas / bar / sheet composables directly with a real
  `EditorState`; a small minority drive the full `PipelineEditorScreen`
  to exercise overflow / sheet / dialog wiring. Coverage:
  `PipelineEditorContentRenderTest` (empty hero, populated cards,
  multi-select toolbar swap, clean-validation copy),
  `PipelineEditorOverflowMenuTest` (Save / Undo / Redo / Rename / Delete
  / Auto-layout / Mini-map / Grid / Find / Paste menu items render and
  dispatch; Save tap → `vm.saveCurrentPipeline`; Find opens FilterBar;
  Grid label flips Show ↔ Hide; Paste on empty clipboard does not
  invoke `vm.addNode`), `PipelineEditorMultiSelectTest` (toolbar count
  pluralisation, Cancel / Copy / Delete callbacks),
  `PipelineEditorRadialMenuTest` (all twelve `NodeType` labels render;
  tile tap dispatches the domain `NodeType`; close icon dispatches
  `onDismiss`), `PipelineEditorValidationBarTest` (clean-state copy;
  header banner plural; per-row labels; Auto-fix CTA invokes callback
  when at least one error is auto-fixable; `Go ↗` dispatches
  `onFocusNode` with the resolved node id),
  `PipelineEditorNodeConfigSheetTest` (per-type forms — Input / Output
  / LiteRt / Cloud — render their characteristic fields; editing the
  Input variable-name field fires `onChange` with the mutated
  `InputConfig`; Save / Cancel dispatch), `PipelineEditorSearchTest`
  (FilterBar placeholder, query change forwarding, match-count pill,
  Close button), `PipelineEditorMiniMapAndGridTest` (MiniMap renders
  OVERVIEW header + `formatScalePercent`; close button dispatches),
  `PipelineEditorCopyPasteTest` (multi-select Copy populates
  `editor.clipboard` from the live graph; overflow Paste on empty
  clipboard does not dispatch `vm.addNode` / `vm.updateNodeFromEditor`),
  `PipelineEditorRunStateTest` (Idle hides the run banner; flipping
  `runState` to Running shows the RUNNING badge and hides the toolbar
  Run button), `PipelineEditorGestureTest` (real `performTouchInput` —
  long-press on a node card enters multi-select; drag on a node card
  commits `vm.moveNode` with a non-zero delta). Pinch / two-finger zoom
  is deliberately out of scope — pure-Kotlin math is exhaustively
  covered by `CanvasTransformTest`, and `ZoomRail` exposes the same
  code path through deterministic buttons.
- **Compose `androidTest` coverage for `presentation.ui.chat.home`**
 . Extended the existing
  `createComposeRule()` + mocked `ChatHomeViewModel` pattern with a shared
  `mockChatHomeViewModel` factory (exposes mutable `StateFlow` handles so
  tests drive `Idle → Generating → Idle`, HITL approve, Clarification
  clear, etc. without re-stubbing). Added `ChatHomeSendFlowTest`
  (composer Send dispatches `sendMessage()`; Generating renders the
  loader bubble + Stop affordance; flip back to Idle restores Send;
  Error renders the retry tile and routes to `retryAfterError()`),
  `ChatHomeHitlScreenFlowTest` (Sensitive Allow / Reject → `approveTool`
  / `rejectTool`; Destructive typed-confirm gate stays disabled until
  the magic word lands; live `pendingTool` tool name renders end-to-end),
  `ChatHomeClarificationFlowTest` (live `pendingClarification` question
  + quick-reply chips render; chip tap dispatches
  `submitClarificationReply(label)`; clearing `pendingClarification` +
  flipping back to Idle removes the card), `ChatHomeConsolePaneTest`
  (Logs/Vars/Traces tab switching, source-filter chip toggle,
  search-bar input forwarding, header Search / Clear icons routing into
  the VM, Clear-confirm AlertDialog Confirm/Cancel, long-press
  "Copy line" round-trip into a fake `LocalClipboardManager`), and
  `ChatHomeDrawerTest` (DrawerOpen renders threads + footer rows,
  thread tap dispatches `selectThread` + `closeDrawer`, New chat opens
  the pipeline-picker `ModalBottomSheet`, picking + Create dispatches
  `createNewSessionWithPipeline(id)`, composer Send affordance remains
  present in HITL / Clarification / ConsoleExpanded states as a
  structural IME-overlap stand-in). Caveats spelled out in test KDoc:
  the 5 s Clarification timeout watchdog runs via `delay` inside the
  VM and is exercised by JVM unit tests; real soft-keyboard IME overlap
  needs screenshot coverage and is out of scope. Refactored the
  existing `ChatHomeOverflowMenuTest` onto the shared factory.
- **Robolectric coverage for `presentation.notifications` + `presentation.receivers`**
 . Added `ApprovalNotificationManagerTest` (risk-based
  channel routing: `AGENT_APPROVAL_DESTRUCTIVE` vs `AGENT_APPROVAL` with
  `READ_ONLY` sharing the `SENSITIVE` channel, `IMPORTANCE_HIGH` per channel,
  idempotent channel registration across repeat sends, active-session
  suppression vs. cross-session post, Approve / Deny `PendingIntent` extras
  + `FLAG_IMMUTABLE`, stable-vs-distinct notification ids on the
  `NOTIFICATION_ID + hash % NOTIFICATION_ID_RANGE` partition, `BigTextStyle`
  + auto-cancel + `PRIORITY_HIGH`) and `AgentApprovalReceiverTest`
  (`APPROVE` → `resumeWithApproval(true)`, `DENY` → `resumeWithApproval(false)`,
  unknown / null action skip the orchestrator, missing `sessionId` extra
  short-circuits before cancelling, notification cancellation at the
  partitioned slot, dedup-free repeat delivery). Used the same Hilt-bypass
  reflection trick as `AgentForegroundServiceTest` so `@AndroidEntryPoint`
  doesn't require a `HiltTestApplication` runner. The previous
  `app.knotwork.android.presentation.notifications.*` and
  `app.knotwork.android.presentation.receivers.*` Kover exclusions are gone —
  both packages now show **100 % LINE** in `koverHtmlReportDebug`.
- **Robolectric coverage for `data.services`**. Added
  `AgentForegroundServiceTest` (channel registration with `IMPORTANCE_LOW`,
  `startForeground` notification with `FLAG_ONGOING_EVENT`, wake-lock
  acquire/release across `Thinking` / `ExecutingTool` / `Idle` / `Error`
  states, `onDestroy` engine close-on-`isInitialized`, `onStartCommand`
  `START_STICKY` idempotency, `onBind` null contract), rewrote
  `AgentWorkerTest` on top of `TestListenableWorkerBuilder` + a manual
  `WorkerFactory` (covers null/blank input, happy path, stream-completes-
  with-`Error`-is-still-success, retry-on-exception, stage-only emissions,
  public key contract), extended `AgentIdleManagerTest` (no-`startObserving`
  silence, `Loading` no-op, `Error` triggers, 5-minute default timeout),
  extended `AgentPowerManagerTest` (low-battery → charging transition
  fires once, duplicate-`StateFlow`-emission dedup, never-initialized engine
  short-circuit), and added `LongRunningTaskNotifierImplTest`
  (`LONG_RUNNING_TASKS` channel registration, opt-out gate, `POST_NOTIFICATIONS`
  permission gate, channel-id and stable per-pipeline notification-id
  contract, empty-flow defaults-to-disabled). The previous
  `app.knotwork.android.data.services.*` Kover exclusion is gone — package
  coverage rose from **44.0 %** to **99.4 % LINE** (171 / 172). Pinned
  Robolectric runtime SDK to 36 via `app/src/test/resources/robolectric.properties`
  and enabled `unitTests.isIncludeAndroidResources = true` so `getString`
  on notifier strings resolves against the real merged resources.
- **Room DAO + migration regression suite**. Brought the
  in-memory `Room.inMemoryDatabaseBuilder` coverage on `data.local.dao` up to
  every public method on `ChatDao` (including `Upsert`, `renameSession`,
  `setSessionStarred`, single-column `UPDATE`s, `getDisplayMessagesBySessionId`
  `isFinal` filter, `getStarredMessages`, `getRecentMessagesByRole` ordering +
  limit), `LocalModelDao` (`observeActiveModel` Flow, `updateModel`,
  `deleteModelById`, `countByName`, `findByName` happy / miss /
  duplicate-name determinism), and `MemoryDao` (`insertMemory` rowId,
  `getRecentMemories` / `getRecentMemorySummaries` projection, `deleteMemoryById`,
  `deleteAllMemories` ignoring pin state, `observeChunkCount` /
  `observeTotalBytes` Flows). Added `PipelineDaoTest` (`@Transaction`-backed
  `getAllPipelines` / `getPipelineById`, `savePipelineTransaction` atomicity,
  scoped `deleteNodesForPipeline` / `deleteConnectionsForPipeline`,
  FK cascade on `deletePipelineById`, `NodeContextConfig` TypeConverter +
  nullable `config_json` round-trip), `PromptTemplateDaoTest` (`category, name
  ASC` ordering, `REPLACE` conflict, scoped delete, count), and
  `TraceStepDaoTest` (per-session `timestamp ASC` order, `durationMs` /
  `tokenCount` round-trip, scoped delete, FK cascade from `chat_sessions`).
  Added `AppDatabaseMigrationTest` — direct invocation of every
  `MIGRATION_17_18 … MIGRATION_22_23` against a real on-disk SQLite file via
  `FrameworkSQLiteOpenHelperFactory`, plus a chained `migrateAll_17_to_23`
  end-to-end run. `@Database(exportSchema = true)` + `room.schemaLocation` ksp
  arg + `app/schemas/` committed wire the v23 schema snapshot in for Room's
  runtime validation; future schema bumps must commit the new `N.json`
  alongside the migration. Added `androidx.room:room-testing` to
  `androidTestImplementation`.
- **`LocalAppFunctionManager` unit-test suite + extra codec edge cases**
 . 29 JVM tests for `LocalAppFunctionManager` cover the
  pure JSON-Schema generator (all supported and unsupported parameter types,
  description handling, required-array emission), discovery via
  `mockkObject(AppFunctionManager.Companion)` (empty manager → empty list +
  cache clear, `SENSITIVE` risk tagging, JSON-schema population per tool,
  description propagation), qualified-name dedup across packages (`pkg.a/foo`
  + `pkg.b/foo`), cache + re-discovery semantics for `isDiscovered` /
  `getParametersMetadata` / `getTargetPackageName`, `executeFunction`
  unavailable-manager error path, and the extracted `wrapInvocationError`
  helper (`AppFunctionException` → `IllegalStateException` with cause;
  others pass through). Two `AppFunctionDataCodecTest` gap-fillers also
  added: extra-field warning recurses through `SetObject` payloads, and a
  missing required field inside a `SetObjectList` item raises the same
  `IllegalArgumentException` as a top-level miss. Coverage of
  `data.tools.local` rises from **27.7 %** to **74.5 %**; the remaining gap
  is the `invokeByName` happy path that constructs `AppFunctionData`
  (Android-stub `Bundle` reference makes it unreachable from JVM tests —
  covered by `AppFunctionsEndToEndTest`).
- **`data.tools.local.executors` unit-test suite**.
  Added GWT-style JUnit + MockK coverage for `DelegateTaskExecutor`,
  `ScheduleTaskExecutor`, and `SearchToolExecutor` — happy path, JSON
  default branches (`targetModel` → `anthropic`, `intervalHours` /
  `delayMinutes` → `0`, `lang` → `en`), blank-query short-circuit,
  malformed / missing-field JSON, and downstream-exception propagation.
  The package was excluded from Kover reporting up to now; the
  exclusion was lifted in `app/build.gradle.kts` so the new tests
  surface as `100% line coverage` (21/21) and any future regression in
  the local-tool argument-parsing contract is caught by the build gate.

## [0.2.0] - 2026-05-25

Ships the Knotwork redesign end-to-end: every redesigned screen wired to its
real backend, MCP routing hardened against config-edit / disabled /
flaky-provider regressions, R8-minified release variant, and a fresh set
of README hero shots regenerated from new Roborazzi baselines.

### Fixed

- **MCP routing — three regressions in `ToolRepositoryImpl`** (follow-up).
  - `syncMcpClients` now keys the pool by the full `McpServerConfig`
    (not just the URL) and tears the connection down + reconnects when
    any of auth / transport / headers / display name change for an
    already-connected server. Previously a credentials edit in
    **Settings → External providers** for an existing endpoint was a
    silent no-op, and every subsequent `getAvailableTools` /
    `executeTool` kept using the stale auth until the process restarted.
  - `executeMcpTool` no longer throws on the first advertising provider
    whose per-server `mcpId` is in `disabledMcpTools`. Since the gate is
    scoped per server (id = `mcp:<sha8(serverUrl)>:<toolName>`), two
    servers can advertise the same tool name and only one's id can be
    disabled — the loop now flags `sawDisabled = true` and keeps
    probing, so the enabled sibling still gets a chance to execute.
  - `executeMcpTool` no longer `break`s out of the loop when an
    advertising provider's `executeTool` throws. The failure is
    remembered as `lastExecutionError` and the loop falls through to
    the remaining providers; if every advertising provider also fails,
    the **most recent failure is re-thrown** (preserves the actual
    network / 5xx / parse error instead of the generic "not found").
    Restores multi-provider resilience — one flaky server can no longer
    eclipse a healthy sibling.
  - Plus: the MCP dispatch loop in `executeMcpTool` /
    `getAvailableTools` / `getRisk` now walks the persisted
    **`settingsRepository.mcpServers`** order instead of the
    non-deterministic `ConcurrentHashMap.entries`, so multi-provider
    probe order is both predictable and matches the priority the user
    actually configured in Settings. The new helper
    `distinctMcpConfigs()` deduplicates the list by URL (keep-first)
    before iteration — defensive measure that pairs with the
    persistence-side fix below.
- **`SettingsManager.updateMcpServer` no longer silently produces
  `[B, B]` when the user edits server A's URL to match an existing
  server B's URL.** The method now reads the persisted list
  pre-write, delegates the check to the pure
  `McpServerCollisionCheck.detectCollision` helper, and returns a typed
  `UpdateMcpServerResult.UrlCollision(collidingUrl,
  collidingDisplayName)` instead of writing. `SettingsRepository`'s
  signature changes from `Unit` to `UpdateMcpServerResult` and
  `McpServerConfigViewModel.onSubmit` surfaces the collision as an
  inline `urlError` on the URL field ("A server with this URL already
  exists: \"<name>\"") — the form stays open with `submitting = false`
  so the user can fix the value and resubmit. Add-mode (`addMcpServer`)
  is unchanged. Integration tests in `SettingsManagerTest` use a real
  file-backed `PreferenceDataStore` (over a `TemporaryFolder`) to
  prove the `dataStore.edit { … }` write actually does not happen on
  collision and that the no-op rename (`newUrl == originalUrl`) still
  persists cleanly.

### Added

- **R8 minification + resource shrinking on the release build**. `buildTypes.release` now sets `isMinifyEnabled = true` /
  `isShrinkResources = true`, and `app/proguard-rules.pro` carries keep
  rules for every reflection-driven subsystem (kotlinx.serialization, Gson,
  MediaPipe / LiteRT JNI, SQLCipher, Koog + Ktor, AppFunctions KSP-generated
  inventories / invokers, Hilt, Room) plus `-dontwarn` blocks for the
  OpenTelemetry incubator + AutoValue symbols Koog pulls in transitively.
  Each section is documented inline with a one-line rationale.
- **`docs/release.md`** — single-page release playbook covering variant
  matrix, signing posture (current debug-keystore vs. the production block
  to land before Play Store submission), R8 keep-rules rationale,
  bundle / size measurement instructions, and the v0.2.0 APK-size
  breakdown.
- **README hero + screenshot grid regenerated at 1080 × 2400 from new
  Roborazzi baselines** (one `HeroSnapshotTest` per surface). Five
  surfaces × light + dark = 10 PNGs total, all wired through `<picture>`
  blocks that honour `prefers-color-scheme`:
  - `hero-chat-home{,-dark}.png` — README hero (Idle chat).
  - `hero-pipeline-editor{,-dark}.png` — vertical stack of NodeCards
    per node type, clipped from `PipelineEditorCatalogContent`.
  - `hero-pipeline-library{,-dark}.png` — populated pipeline library
    (`PipelineLibraryPreview.populated()`).
  - `hero-tools{,-dark}.png` — built-in AppFunctions + an expanded
    MCP server (`ToolsPreview.defaultExpanded()`).
  - `hero-settings{,-dark}.png` — populated Settings stack
    (`SettingsPreview.default()`).

### Changed

- **Public documentation actualised against the post-legacy code state**
  (follow-up). Removed references to deleted legacy
  classes (`ChatScreen` / `ChatViewModel` / `ChatUiState`), retired the
  "Saving and filtering messages" feature description, rewrote the
  **Console** section to describe the agent-status pill + independent
  `ModalBottomSheet` console pane that replaced the legacy mini-console /
  full-log split, dropped the orphaned `screenshots/TODO.png` placeholders
  in `docs/user-guide.md`, fixed the chat life-of-a-message sequence
  diagram in `docs/architecture.md` to use the `ChatHome*` names, updated
  the **Settings** section of `docs/extending.md` to list all nine cards
  (was: six), refreshed the `PROMPT_VARIABLES` example in
  `docs/extending.md` + `pipeline-editor.html` to include the four new
  variables (`LANG / LOCATION / USER / DEVICE`) that landed earlier,
  corrected the SDK install requirement in `CONTRIBUTING.md`
  (compileSdk is API 37, not API 36), wired `docs/release.md` into
  `README.md`, `CONTRIBUTING.md` (Further reading + Build & test), and
  `docs/architecture.md` (Further reading), and removed the stale
  duplicate `more/` block in `FILE_MAP.md`.

- **Jansi non-Android native binaries excluded from the release APK**
 . `android.packaging.resources` drops
  `org/fusesource/jansi/internal/native/{Windows,Mac,Linux,FreeBSD}/**` and
  `META-INF/native-image/jansi/**` — Jansi ships through Koog's logger and
  only its ANSI-escape rendering runs on JVM hosts. Saves ~430 KB on the
  release artefact.

### Removed

- **Legacy chat surface** (`presentation/ui/chat/legacy/`) — 13 production
  files + paired tests (2 unit tests, 4 instrumented tests). The
  redesigned `chat/home/` package has been the production surface since
  the redesign and absorbed every behaviour that mattered
  (orchestrator core, HITL, Clarification, console pane, chat export);
  the legacy package was kept around as a reference while wiring landed,
  and is now removed so future grep / FILE_MAP / file-tree audits stay
  honest. Stale docstring + comment references in `ChatHomeViewModel`,
  `ChatExportPayload`, `AppNavGraph`, `HitlIntegrationTest`, and
  `ClarificationIntegrationTest` are cleaned up alongside the deletion.

- **Global UI audit on Knotwork-converted screens**.
  Swept `app/src/main/` for design-system violations across hex colours,
  shape / typography / dp / motion tokens, theme single-source, and
  component reuse. One Block-grade and eight Major findings were
  closed in this PR; the full triage and the deferred TalkBack + dynamic-
  type release-smoke checklists are tracked separately.
  - `NodeContextConfigSection.kt` swaps the hint `Surface` shape from
    `RoundedCornerShape(8.dp)` to `KnotworkTheme.shapes.sm` and rebinds
    paddings to spacing tokens.
  - `PromptPreviewBottomSheet.kt`, `VariableChipsRow.kt`,
    `PromptLibraryDialog.kt`, `PipelineLibraryScreen.kt`, and
    `ChatHomeDebugStatePicker.kt` rebind every on-scale dp literal
    (`4 / 8 / 12 / 16 / 24`) to the matching `KnotworkTheme.spacing.*`
    token. Off-scale intentional values (`120 dp` loading container,
    `2 dp` checkbox-row vertical rhythm) are now private vals with a
    KDoc documenting the intent.
  - `AppShellScaffold.kt` ties the bottom-nav slide-in to
    `KnotworkTheme.motion.dur3` + `easeStd` and collapses to
    `EnterTransition.None / ExitTransition.None` under
    `KnotworkTheme.a11y.reducedMotion()`.
  - `ValidationBar.kt` Auto-fix header action migrated from raw M3
    `TextButton` to `KnotworkTextButton` for catalog typography +
    accent. The per-row `Go ↗` action stays on raw `TextButton`
    pending a `trailingIcon` slot on the catalog button (filed as
    Minor in the audit doc).

- **Post-merge fixes (F1–F10).** Ten review
  findings landed in the same PR by user request. Roborazzi
  baselines for `chat_home_*`, `settings_*`, and
  `prompts_editor_*` were re-recorded.
  - **F1** — `ChatHomeContent` TopAppBar subtitle drops the
    `Pipeline · ` prefix (`knotwork_chat_home_topbar_pipeline`) so
    the token counter fits on a single line at the default font
    scale.
  - **F2** — Markdown rendering restored. Catalog `ChatMessage` /
    `ChatHomeContent` gained an optional
    `markdownRenderer: @Composable (String) -> Unit` slot;
    `ChatHomeScreen` wires the `com.mikepenz.markdown.m3.Markdown`
    renderer. `ChatHomeViewModel.chatMessageToRow` now emits
    `ChatContent.Markdown` for agent / tool rows and keeps
    `ChatContent.Text` for user rows. Plain-text fallback is the
    default when no renderer is supplied. The new catalog
    `MarkdownTheme.kt` exposes Knotwork-themed
    `knotworkMarkdownTypography()` / `knotworkMarkdownColor()`
    factories that flow `KnotworkTextStyles` headings (collapsed
    from the M3 display tier into `TitleXl → BodySm`), `MonoBase`
    code, and `extended.surface{1,2,3}` / `divider` backgrounds
    through the m3 renderer. `:catalog` now declares `markdown-m3`
    as an `api` dependency.
  - **F3** — Drawer selected-thread contrast in dark theme. The
    selected row's background switched from the static
    `KnotworkPalette.Accent50` to `MaterialTheme.colorScheme.primaryContainer`
    paired with `onPrimaryContainer` text, mirroring the
    onboarding Step-2 fix; light theme retains WCAG-AA contrast.
  - **F4** — Eliminated the empty-chat hero flash on cold start.
    Added `ChatHomeUiState.Loading` / `ChatHomeVisualState.Loading`,
    the VM starts in `Loading`, and the catalog renders a centred
    `CircularProgressIndicator` until the first chat snapshot
    arrives. `rebalanceRestingState` settles `Loading → Empty / Idle`
    on the first emission.
  - **F5** — Settings secondary text (non-SemiBold subtitles on
    `onSurfaceMuted`) switched from `BodySm` / `LabelSm` to
    `MonoSm` to match the cloud-provider rows. Touches the
    Identity card, params helper, system-instructions counter,
    local-model backend label, Test backend probe line, and
    memory re-embedding progress.
  - **F6** — Test backend `Run` action shrunk to
    `KnotworkButtonSize.Sm`, matching the Memory section's Export /
    Clear / Reset buttons.
  - **F7** — Prompt library editor `EditorTextField` renders the
    multi-line prompt body in `KnotworkTextStyles.MonoSm`, matching
    the Settings → System instructions field. The Name field keeps
    the proportional `BodyBase` face.
  - **F8** — LITE_RT node "Active model" sentinel. Blank
    `LiteRtConfig.modelId` is now the explicit "use the currently-
    active model at execute time" choice — surfaced as a new
    `Active model` dropdown item, the codec persists `modelPath =
    null` on the domain row, and `LoadModelUseCase` centralises
    the blank-coerce so every executor (LITE_RT / Output / Summary
    / Clarification / Tool / System) honours the sentinel. The
    validator no longer flags blank `modelId` as REQUIRED. The
    earlier `LaunchedEffect` that eagerly froze the active id into
    the field on open was removed.
  - **F9** — Streaming token counter in the agent status pill. New
    `ChatHomeViewModel.streamingTokens` flow derives an approximate
    count from `AgentOrchestratorState.Thinking` / `Answering`
    `partialText.length`. The catalog status string composes as
    `generating · N tok` while non-zero, resets on each new send /
    Completed / Error.
  - **F10** — Restored `systemPrompt` field on the OUTPUT node form.
    Catalog `OutputConfig` gained the field; `OutputFormBody`
    renders the same `TextField` + variable chips pattern as
    LITE_RT / CLOUD; the codec encodes / decodes the field and
    syncs it onto `NodeModel.systemPrompt` (legacy rows surface
    their persisted prompt instead of silently clearing).

### Added

- **Knotwork conversion of the remaining screens**.
  Splash, Models, Prompt library, Task monitor, Live metrics, More, and
  About now share the catalog-driven Compose surface used by the §C1–C8
  screens. Each app-side screen is a slim mapper that folds its
  `UiState` into a catalog `*ViewState` and renders the matching
  `*Content` composable; design changes from now on live in the
  `:catalog` module. Specifically:
  - **Splash** — new `SplashContent` with `KnotworkLogo(Lg)` brand
    hero, `LinearProgressIndicator` on `KnotworkTheme.extended.surface2`
    track, status label and `KnotworkPrimaryButton` Retry on error.
  - **Models** — inline-section layout that matches the design mockup:
    accent-tinted Active card → HuggingFace token field with `+ Paste`
    clipboard action → Custom URL field with `Get` button → preset list
    with three per-row variants (Idle / Downloading / OnDisk).
    `ModelsViewModel` gained `cancelDownload()` (cancels the in-flight
    job) and `deleteModel(id)`; `ModelsUiState` adds the
    `activeDownloadFileName` field so the progress row binds to the
    matching preset.
  - **Prompt library** — `ScrollableTabRow` of category tabs +
    accent-stripe cards with inline `$VAR` highlighting + footer
    `used by N pipelines · Duplicate` action. Editor moved into a
    `ModalBottomSheet` (`PromptEditorSheetBody`) with Name / Category /
    Prompt text / `INSERT` chip row. New VM methods:
    `selectCategory`, `openEditor(id?)`, `closeEditor`, per-field
    setters, `saveEditor`, `duplicatePrompt`.
  - **Task monitor** — `KnotworkFilterChip` row + per-task rows with
    leading icon tile, `StatusPill` trailing, optional cancel button,
    and a `TaskMonitorDetailSheetBody` `ModalBottomSheet` opened on
    row tap. `TaskMonitorViewModel` adds `openDetails(id)` /
    `closeDetails()`.
  - **Live metrics** — `MonitoringContent` renders the power-saving
    banner, a 3-cell `KnotworkStatCell` grid (Inference time / tokens
    per second / total tokens), total execution line, per-node-type
    breakdown, and recent-logs lazy column. The original
    `MonitoringViewModel` is unchanged; the screen consumes its
    existing state through a pure-Kotlin mapper.
  - **More** — landing tab is now stateful: `MoreViewModel` aggregates
    live counters from `MemoryRepository.observeStats`,
    `LocalModelRepository.getAllModels`, `PromptRepository.getAllPrompts`,
    `TaskQueueManager.activeSessionsState`, and the new
    `NetworkActivityTracker.lastOutboundAt` flow. Renders seven rows on
    `KnotworkNavListRow` with subtitle counters and an active-tasks
    badge, plus a footer privacy pill (`on-device · no network calls
    in last N m`).
  - **About** — full body: `KnotworkLogo(Lg)` hero, version / build /
    commit card, license card with an `Open license text` CTA, hand-
    maintained acknowledgments list (15 key dependencies), and a
    privacy summary card with a `Read privacy policy` CTA.
- **`NetworkActivityTracker`** (`domain/repositories/` + `data/repositories/`).
  Singleton timestamp of the most recent outbound LLM / MCP call.
  Recorded by `CloudLlmNodeExecutor` immediately before each
  `executeStreaming` call and by `KoogMcpClient.connect` /
  `executeTool`. Drives the More tab privacy pill — distinct from
  `NetworkStateRepository`, which only reflects connectivity. The
  value resets on process recreation, which matches the indicator's
  "since you opened the app" semantics.
- **`KnotworkLogo`** (`components/brand/`) — purely vector brand mark
  (rounded square frame + inner diamond) in three sizes (`Sm` 32 dp,
  `Md` 64 dp, `Lg` 128 dp). Stroke width is a fixed fraction of the
  canvas side so all sizes look visually identical.
- **`KnotworkNavListRow`** (`components/lists/`) — 72 dp tall
  navigation row with a 48 dp leading icon tile, title (`TitleMd`),
  optional mono subtitle, optional trailing slot (badge / status
  pill), and a permanent chevron-right glyph. Backbone of the More
  tab and a candidate for the secondary surfaces that still use
  Material 3 `ListItem`.

- **Inputs & chips — design-system alignment** (follow-up). Brings every text input and chip on screen onto the
  canonical Knotwork atom family laid out in
  `inputs-and-chips.md`. Before this change the catalog had a single
  `KnotworkChip` (pill-shaped, three styles squeezed into one atom)
  plus a thin `KnotworkMonoTextArea`; everything else fell back to
  raw Material 3 `OutlinedTextField` / `FilterChip` / `AssistChip`
  scattered across 13 production call sites with no shared geometry,
  caps-label, or focus-state policy. Now:
  - New catalog atoms under `components/controls/`:
    - `KnotworkFieldDefaults` + `KnotworkFieldSize { Sm, Md, Lg,
      Composer }` — single source of truth for heights, paddings,
      border weights, icon sizes, label / helper / field gaps.
    - `KnotworkField` — caps-label + optional mono inline hint +
      helper / error row wrapper. M3 floating label stays off
      everywhere by design (dense rows + brand-signal cap label).
    - `KnotworkTextField` — single-line `BasicTextField` with the
      full 7-state visual table (default / hovered / focused /
      filled / disabled / readOnly / error), `monospace` flag for
      tokens / URLs / expressions, and a search-bar variant
      (pill shape, `surface2`, no border).
    - `KnotworkTextArea` — multi-line counterpart with
      length-preserving `VisualTransformation` that recolours
      `\$[A-Z_]+` tokens accent + underline inline, optional
      `insertChips` strip that splices the matching `$NAME` at the
      active cursor position, and `minLines` / `maxLines` controls.
    - `KnotworkPasswordField` — masks the value behind a `•`
      transformation by default and offers an eye-toggle trailing
      icon; flips to mono typography when revealed (long API keys
      read better in monospace).
  - New catalog atoms under `components/chips/`:
    - `KnotworkChipDefaults` + `KnotworkChipSize { Xs, Sm, Md }`.
    - `KnotworkFilterChip` — selected ↔ unselected toggle on the
      8 dp `sm` shape (Knotwork deliberately diverges from M3's
      pill-shaped filter chip), 180 ms cross-fade, optional
      `trailingCount` and `role` overrides for `Role.Tab` /
      `Role.Checkbox`.
    - `KnotworkSuggestionChip` — action-only chip with
      `surface1` + 1 dp outline so it reads on chat bubbles.
    - `KnotworkInputChip` + `KnotworkChipsInput` — removable
      chips inside a `FlowRow` plus an inline `BasicTextField`
      that commits on Enter / `,` and respects an optional
      `maxItems` cap (replaces the implicit "Max N items"
      behaviour that previously had to be reimplemented per
      caller).
    - `KnotworkVariableChip` — mono accent-coloured `$VAR`
      insert chip. Replaces the raw `AssistChip` row that used
      to back `VariableChipsRow`; the wrapper now forwards to
      this atom so prompt-variable affordances finally share the
      brand mono / accent / hollow-border treatment with the
      `KnotworkTextArea` highlight pass.
    - `KnotworkDateChip` — non-interactive section-divider pill
      for the chat stream (Today / Yesterday / locale date).
  - `RiskPill` and `StatusPill` brought onto the new spec: both
    drop the filled container for a transparent fill + 1 dp
    coloured border + leading 6 dp dot + `Mono13` label so they
    read on every surface (light card, console, chat bubble) and
    stop creating the contrast-collapse on inverted surfaces that
    the filled pills hit on the dark theme. `StatusPill` adds
    `Queued` and `Cancelled` states and pulses the dot on
    `Running` via an `infiniteRepeatable` tween that collapses to
    a constant alpha when
    `KnotworkTheme.a11y.reducedMotion()` is `true`. `Risk.Readonly`
    label switches from `"Read-only"` to `"Read only"` per spec.
  - Catalog `ChatComposer` trailing button now models the full
    4-state matrix from the spec: mic (Idle + empty + `onMic`
    callback) → send (Idle + non-empty) → stop (Generating) →
    retry (Error). Container and content colours swap per state
    (mic uses muted `surface3`, retry uses `riskDestructive`);
    morph stays at 200 ms and honours reduced motion.
  - 13 production usage sites migrated to the new family:
    `ChatHomeScreen` rename sheet, `ChatScreen` rename dialog,
    `ChatScreen` chat input bar (now wraps the catalog
    `ChatComposer`), `ClarificationCard` free-form input and the
    quick-reply chips (which were raw `OutlinedButton`s
    pretending to be chips), `ModelsScreen` HuggingFace token
    (now `KnotworkPasswordField` with mask + eye-toggle) and
    custom-URL field, `PipelineEditorScreen` rename dialog,
    `PipelineLibraryScreen` pipeline-name dialog,
    `PromptLibraryScreen` name + text fields (the text field
    folds in the `VariableChipsRow` companion via the new
    `insertChips` parameter), `FilterBar` search bar (`Md`
    pill, leading search icon, `Search` IME action),
    `ConsoleFullLogSheet` log filter row, `TaskMonitorScreen`
    task filter row, `VariableChipsRow` (`AssistChip` →
    `KnotworkVariableChip`), and the catalog
    `ChatHomeContent` sample-prompt row (`KnotworkChip` →
    `KnotworkSuggestionChip`).
  - The legacy `KnotworkChip` becomes `@Deprecated` (pointing at
    `KnotworkFilterChip`) and is scheduled for removal one
    iteration later — kept for now so any consumer outside this
    audit's reach does not break in the middle of the migration.

- **Koog 1.0.0 migration follow-up** — the prior dependency bump
  (`build: update koog, json, and roborazzi versions`) pointed every
  `koog-*` library at version `1.0.0`, but three Koog modules
  (`agents-mcp-server`, `prompt-executor-google-client`,
  `prompt-executor-deepseek-client`) have not promoted past
  `1.0.0-beta-preview7` on Maven Central yet — so the build failed
  to resolve. Plus the major bump renamed two DeepSeek model
  constants and the MCP client / prompt types moved between
  packages, breaking the source set. Fixes:
  - `koog-preview = "1.0.0-beta-preview7"` introduced as a separate
    version ref so the three unpromoted artifacts can pin to the
    latest beta without holding back the rest of the Koog stack
    (re-collapse onto `koog` once they ship 1.0.0 stable).
  - `koog-mcp` library entry switched from `agents-mcp-server`
    (server-only since 1.0.0; the misnaming used to work in 0.8.0
    via a transitive client dep) to `agents-mcp`, which is the
    actual client artifact carrying `McpToolRegistryProvider` /
    `mcpStreamableHttpTransport`.
  - `DeepSeekModels.DeepSeekChat` → `DeepSeekV4Flash`,
    `DeepSeekModels.DeepSeekReasoner` → `DeepSeekV4Pro` —
    `KoogModelMapper`, `KoogCloudLlmModelResolver`, and
    `DelegateTaskTool` updated to the new identifiers. Persisted
    user model ids fall back to `DeepSeekV4Flash` via
    `getDeepSeekModel`'s `else` branch.
  - Test source set migrated to the new Koog 1.0 prompt API:
    `ai.koog.prompt.dsl.Prompt` → `ai.koog.prompt.Prompt`,
    `Message.content` → `Message.textContent()` (the base
    `Message` interface now exposes `parts: List<MessagePart>` and
    a default `textContent()` extractor; `Message.Response` was
    renamed to `Message.Assistant`). `DelegateTaskToolTest`,
    `GraphExecutionEngineTest`, `CloudLlmNodeExecutorTest` were
    updated; the dead `Message.Response` mock that
    `DelegateTaskToolTest` carried was deleted (response flows
    through `StreamFrame.TextDelta` anyway).
  - Runtime `KoogHttpClient.Factory` lookup fixed. Koog 1.0.0
    declares the HTTP client as a JVM `ServiceLoader` SPI, but the
    `http-client-ktor-android-1.0.0` AAR published to Maven Central
    omits the `META-INF/services/ai.koog.http.client.KoogHttpClient$Factory`
    registration file (the `KtorKoogHttpClient$Factory` class itself
    is present, the SPI descriptor is not). The default `LLMClient`
    secondary constructors therefore threw
    `IllegalStateException: No KoogHttpClient.Factory provider
    found on the runtime classpath` on the first cloud call,
    crashing the app at startup. `KoogClientFactory` now constructs
    a single `KtorKoogHttpClient.Factory()` instance and passes it
    explicitly to every cloud / Ollama `LLMClient` constructor
    (`OpenAILLMClient`, `AnthropicLLMClient`, `GoogleLLMClient`,
    `DeepSeekLLMClient`, `OllamaClient`), bypassing the broken SPI
    lookup. `app/build.gradle.kts` already pulls in
    `libs.koog.http.client.ktor` so the factory class is on the
    runtime classpath; remove the explicit-factory plumbing once
    Koog ships an AAR with the services descriptor restored.
  - Lint baseline absorbs a Koog-version false-positive
    (`NewerVersionAvailable` claims `1.0.0-beta` is newer than
    `1.0.0-beta-preview7` for `agents-mcp`; Maven Central only
    publishes the `…-previewN` series and `-preview7` is the
    latest). Re-baseline together with the next genuine bump.

- **Pipeline editor — review follow-up** (second pass) — eight issues caught on a real device after the
  initial alignment landed: the `RunStatusBanner` now uses a single
  Material `Surface` with a `border` parameter so the inner border
  and the outer background tint always paint the same rectangle (the
  prior two-Surface layering left the border framing a smaller box
  than the background); `Stop` on the banner calls a new
  `OrchestratorViewModel.stopRunAndReset()` that wipes both
  `isRunning` and `activeNodeId`, and a screen-level `DisposableEffect`
  fires the same reset on screen leave so the banner can no longer
  follow the user back to the library or into another pipeline; the
  `ZoomRail` tiles dropped `IconButton` for `Surface + clickable +
  Icon` so the 40 dp tile size survives Material3's 48 dp
  minimum-interactive-component enforcement (tiles no longer overlap);
  `AutoLayout` post-translates the freshly-computed bbox so its
  centroid lands on the centroid of the previously occupied area —
  the re-laid graph stays where the user was looking instead of
  landing in the upper-left corner; a `FloatingActionButton` with `+`
  now sits in the bottom-right corner of the canvas (hidden behind
  the mini-map) and opens the radial quick-add menu at the viewport
  centre, honouring the empty-state's "Tap + to drop your first node"
  promise; the toolbar primary `Run` / `Re-run` button shrunk to the
  `Sm` size variant and dropped its leading icon so the title +
  subtitle stack has horizontal room to breathe (no more
  `Running · ste…` truncation); the empty-state CTAs wrap via
  `FlowRow` and use the `Sm` button size so `From template` no longer
  truncates to `Fro…`; the overflow `DropdownMenu` gained an
  explicit `Save pipeline` item that calls
  `OrchestratorViewModel.saveCurrentPipeline` and surfaces a snackbar
  on completion, so the user has a reliable "write to disk" lever
  instead of guessing whether each edit autosaved. Catalog snapshot
  baselines regenerated to reflect the new toolbar + banner layout.

- **Clarification node — default prompt + library seeds**
  (review fix) — two regressions caught on a
  fresh Clarification node:
  - `NodeConfigCodec.defaultFor(CLARIFICATION)` now seeds
    `questionTemplate` from `DefaultPrompts.getDefaultPromptForNodeType(CLARIFICATION)`.
    The previous `ClarificationConfig(title = title)` left the
    prompt blank on every new Clarification node — the user had to
    type from scratch instead of seeing the registered default.
  - `GetPromptTemplatesUseCase.seedMissingDefaults` extends the
    seed set to every prompt-bearing node type
    (`CLARIFICATION / EVALUATION / LITE_RT / CLOUD / OUTPUT`
    on top of the existing `INTENT_ROUTER / DECOMPOSITION /
    SUMMARY / TOOL`). Seed is now **additive** — it inserts only
    the categories that aren't already in the repository, so
    existing users who installed before a new category was
    registered pick up the new entry on the next library open
    instead of waiting for a DB wipe. Updated `GetPromptTemplatesUseCaseTest`
    to pin both the "all-present → no-op" and the
    "Clarification-missing → inserted" paths.

- **App-wide slider — single `KnotworkCompactSlider` atom**
  (review polish) — extracted the
  inline `CompactSlider` that the Settings screen had been rolling
  privately into a new catalog atom at
  `components/controls/KnotworkCompactSlider.kt`. Every slider in
  the app now goes through it: `KnotworkParamSlider` (Settings
  numeric params), `SettingsContent` (memory auto-summarize),
  `NodeConfigForms` (LITE_RT / CLOUD / Decomposition /
  Evaluation / Summary numeric fields). Visual contract — 4 dp
  pill-shaped thumb (4 × 18 dp) + 4 dp track, primary thumb /
  active track, `extended.surface3` inactive, no tick marks
  regardless of `steps`. Result: every slider reads identically
  regardless of which surface it sits in, instead of the previous
  mix of Material defaults vs the inline Settings variant vs the
  per-sheet `Modifier.height(28.dp)` hack. The dropped per-callsite
  customisation (settings-parity color helper, `COMPACT_SLIDER_*`
  constants, private Settings `CompactSlider`) is now `dead-code`
  removed.

- **Pipeline editor — polish round 3** (second real-device review pass) — six fixes on `NodeConfigSheet`:
  - Every field on the sheet now uses `KnotworkTextStyles.MonoBase`
    (Title + plain text fields + identifier fields). The mixed
    BodyBase / MonoBase stack read as accidentally inconsistent on
    the same dialog. The `monospace` parameter on `TextField` was
    removed (every call became `MonoBase`).
  - `VariableChipsRow` converts to a `LazyRow` with horizontal scroll
    so chips never wrap to a second row. The full prompt-variable
    set is now exposed: `$DATE / $TIME / $LANG / $LOCATION / $USER /
    $DEVICE / $MODEL / $TOOLS / $MEMORY_SUMMARY` (matches every
    registered `PromptVariableProvider` in the `:app` module).
  - `CloudFormBody` drops the per-node Model id field — cloud-model
    ids live once per provider in Settings → External providers and
    are shared across every Cloud node. `CloudConfig.model` stays on
    the data class for persisted-JSON backward-compat (executor falls
    back to the provider's configured model when the field is blank).
    `NodeConfigValidation.validateCloud` correspondingly stops
    flagging a blank `model` — the previous rule would have locked
    Save out forever now that the user can't reach the field.
  - Sheet sliders shrink to a compact 28 dp height — the M3 default
    48 dp interactive area was visibly inflating every slider row.
    Same primary thumb / surface3 inactive palette as
    `KnotworkParamSlider`; trade-off accepted: the form is a config
    surface, not a discoverability one.
  - `NodeContextConfigSection` ("Input Data" checkboxes — Original
    task / Chat history / Long-term memory / Tool results) is back
    on every NodeConfigSheet. The catalog `NodeConfigSheet` grew an
    `extraSection: @Composable (() -> Unit)?` slot (and the
    production `NodeConfigSheetHost` forwards it) so the
    domain-coupled section can render between the form body and the
    Save row without dragging `NodeContextConfig` into the catalog.
    `EditorState` gains a `workingContextConfig` mirror; Save now
    stitches it into the persisted `NodeModel.contextConfig`.
  - Default system prompts restored on every prompt-bearing node
    type. `NodeConfigCodec.deriveFromLegacy` falls back to
    `DefaultPrompts.getDefaultPromptForNodeType(node.type)` when
    `node.systemPrompt` is null/blank — older pipelines that
    persisted before defaults were wired into `NodeModel`
    construction now see the registered defaults on first open.
  - Catalog `pipeline_editor_*` snapshot baselines regenerated.

- **Pipeline editor — polish round 2** (real-device review pass) — eight more issues:
  - `NodeConfigSheet` Title field now uses `KnotworkTextStyles.BodyBase`
    instead of Material3's default `bodyLarge` so it no longer reads
    larger / different from every other field on the sheet.
  - `NodeConfigSheet` IntentRouter `Add class` auto-names the new
    class to a unique `class_N` placeholder so Save doesn't disable
    while the user is mid-rename (blank names used to immediately
    trigger the `REQUIRED` validator and lock Save out).
  - `NodeConfigSheet` prompt-library button moved back into a row
    above the field (sibling of the label, 32 dp tap-target). The
    earlier `trailingIcon` placement crowded the prompt and was hard
    to associate with the `VariableChipsRow` underneath.
  - `VariableChipsRow` extended to every prompt-bearing form
    (IntentRouter, Clarification, Decomposition, Evaluation, Summary
    custom-prompt) — previously only LITE_RT and CLOUD surfaced the
    `$DATE` / `$TIME` / `$TOOLS` / `$MODEL` / `$MEMORY_SUMMARY`
    chips.
  - `NodeConfigForms` `FloatSliderField` / `IntSliderField` now apply
    the same `SliderDefaults.colors` as `KnotworkParamSlider` on the
    Settings screen (primary thumb + active track, `surface3`
    inactive). Sheet sliders no longer fall back to M3's tonal
    palette.
  - Pipeline editor `BackHandler` learns the `searchOpen` state —
    system back closes the Find-node bar (and clears its query)
    instead of falling through to `onBack` and exiting the editor.
  - `Run` primary action now surfaces a `Preview only` snackbar
    explaining that the banner is the UI scaffold and that the real
    `GraphExecutionEngine` wiring lands in a follow-up. Avoids the
    "I tapped Run, nothing happened" confusion.
  - Catalog `pipeline_editor_*` snapshot baselines regenerated.

- **NodeConfigSheet — density tightening** (same review pass) — every per-field label now rides on
  `OutlinedTextField`'s floating-label slot instead of a separate row
  above (saves ~24 dp per field across 12 forms). The optional
  prompt-library button moves into the field's `trailingIcon` slot at
  a compact 32 dp tap target so the row no longer inflates to fit a
  48 dp `IconButton`. Sheet body padding drops `sp4 → sp3` horizontal
  + `sp3 → sp2` vertical; inter-field gap drops `sp3 → sp2`. The Save
  primary CTA shrinks to `KnotworkButtonSize.Sm` so the sticky action
  row matches the toolbar's `Run` button density. The catalog
  `pipeline_editor_*` snapshot baselines were regenerated to reflect
  the tighter sheet.

- **Pipeline editor — design alignment & feature backfill**
  — closes every divergence the diff document
  caught between the spec, the production code, and the new designer
  mockups. The catalog `EditorToolbar` is reshaped to
  `[← back] [title + subtitle] [primary action] [overflow]`
  (`EditorPrimaryAction.Run` / `Rerun` / `None` for the primary slot);
  Undo / Redo / Delete / Auto-layout move into the production-side
  overflow `DropdownMenu`. The new top-of-canvas `RunStatusBanner`
  surfaces `Running` (amber + Pause / Stop) and `Done` (green + Trace)
  variants — replaces the prior bottom `RunTraceBar`. An
  always-visible right-edge `ZoomRail` (`+ / − / ⤡`) anchors zoom +
  fit-to-view; a `MiniMap` overlay (270 × 290 dp, `OVERVIEW · 0.42×`
  header, per-type-hue node bricks + accent viewport rect) drops in
  from the overflow. A 24 dp `DotGridBackground` lights up under the
  canvas (toggle from overflow). The empty state becomes a
  full-hero `EmptyPipelineState` (brand-mark tile + `Start with
  INPUT` / `From template` CTAs + info pill). The `ValidationBar`
  gains a header `Auto-fix` action (six recipes registered in
  `ValidationAutoFix`) + per-row `Go ↗` jumps + severity glyphs.
  Non-active nodes dim to α 0.40 during a run; running edges adopt
  the source node's header hue. Copy / Paste node (multi-select
  Copy button + overflow Paste). Find-node bar (`FilterBar`, opened
  from overflow). Inline rename via the overflow `Rename node…`
  dialog. The toolbar subtitle now renders `Editing · nodes N ·
  edges M` / `N issues · can't run` / `Running · <label>` /
  `Overview · 0.42× · nodes N`. Two TODO candidates are explicitly
  deferred to follow-ups: pipeline-as-PNG export (requires
  FileProvider plumbing) and the sidebar drag-from-palette (overlaps
  with the radial quick-add menu — best landed alongside broader
  wide-screen layouts).

- **Onboarding — review follow-up** (second
  pass) — three issues caught on a real device after the initial
  audit landed: the Step-2 selected `LiteRtModelRow` background
  switched from the static `Accent50` palette to
  `MaterialTheme.colorScheme.primaryContainer` so the model name stays
  readable in dark theme; the per-step `StepHeadline` / `StepBody`
  dropped from `Display2xl` / `BodyLg` to `TitleXl` / `BodyBase` so
  the title + description block no longer eats half the viewport on
  small phones; Step 3's Configure tap now navigates to the same
  per-provider API-key editor that Settings uses (via the new
  `OnboardingScreen.onConfigureProvider` callback wired through
  `AppNavGraph`), and the `configuredCloudProviders` projection is
  computed reactively from `ApiKeyRepository` so the "Configured" pill
  flips on whatever is actually persisted — not on which row the user
  tapped.

- **Onboarding — design audit & alignment** —
  the four-step pager now honours every chrome rule called out in the
  task brief: the `StepHeadline` composable clamps the user's
  `fontScale` to 1.6× per `decisions.md §14` (via an overridden
  `LocalDensity` scoped to the headline subtree only, leaving every
  other text on the screen at the unclamped system value); the
  step-2 download progress indicator now branches on
  `KnotworkTheme.a11y.reducedMotion()` and collapses to a static
  primary-filled bar when the download reaches `≥ 0.99f`, matching
  the brief "под reduced-motion — статичный full bar"; the system
  predictive-back gesture is wired through a new
  `PredictiveBackHandler` in `OnboardingScreen` and rewinds the pager
  one step on steps 2–4, or raises a typed-confirm "Quit setup?"
  dialog on step 1; the activity-level `SnackbarHost` (which catches
  the skip-flow hint after onboarding is popped off the back-stack)
  now renders every message through `KnotworkSnackbar(variant =
  Default)` so the skip-hint sits on `extended.surface3` instead of
  the raw Material3 chrome. Roborazzi baseline grew from 8 → 16 PNGs
  with new fixtures for step-2 `Downloading`, step-2 `DownloadError`,
  step-2 `CustomUrlInput`, and step-4 `ModelReady`. Findings are tracked
  separately; `screens/README.md §C5` rewritten to match the second-pass JSX
  artboard family that `OnboardingViewState` was designed against,
  replacing the stale first-pass spec.

- **Onboarding — LiteRT model download wiring**
  — Step 2 of the onboarding pager now actually downloads the picked
  LiteRT model. The CTA stays disabled until the model is on disk or a
  download is in flight, so the user can no longer advance into chat
  with no active model (which previously produced a "LiteRT handle
  released by system" error on the first send). Re-entering onboarding
  after a previous install detects the existing file through the new
  `LocalModelRepository.isInstalled(fileName)` query and surfaces an
  "Installed" pill on the matching row. Picking *Custom URL…* reveals
  an inline text field, with on-the-fly filename derivation from the
  trailing path segment. As soon as a model becomes available the
  ViewModel runs `LoadModelUseCase` to warm the inference handle, so
  step 4's "Open chat" CTA becomes enabled the moment the model
  finishes loading. Skipping onboarding emits a snackbar — "You can
  install a model from Settings → Models" — so the user knows where to
  recover the flow later. The bundled preset URLs live in the new
  `domain/constants/OnboardingModelCatalog.kt` and are shared between
  the onboarding catalog and the data-layer downloader.

- **Tools — full MCP server configuration** —
  Each MCP server now carries a full [McpServerConfig]: optional
  display name, transport selection (SSE via Koog's
  `defaultSseTransport`; Streamable HTTP via the upstream MCP
  Kotlin SDK's `HttpClient.mcpStreamableHttpTransport` extension —
  both end-to-end wired against real servers), a typed
  authentication selector (None / Bearer / Basic / API Key) with
  per-scheme fields, and arbitrary request headers for advanced
  overrides. Adding and editing happen on a
  dedicated full-screen `McpServerConfigScreen` (route
  `tools/mcp-config?originalUrl={url}`) — the row's overflow ⋮
  menu (Refresh / Edit / Remove) opens it pre-filled, the
  `+ Add MCP` link opens it blank, and Save / Cancel pop back to
  the list. KoogMcpClient now configures the Ktor `defaultRequest`
  block with the user-supplied headers so they reach both the SSE
  handshake and every subsequent JSON-RPC call. Persistence
  switched from a `stringSet` of URLs to a JSON-encoded list of
  configs in the new `mcp_servers_json` key — the manager one-shot
  migrates the legacy key on the first read, and writes the new
  shape on the next mutation.
- **Tools — MCP per-tool detail and tool-list fetcher** — Tools surface now drives a real
  `tools/list` MCP round-trip through the new
  `McpServerRepository` (data impl: `McpServerRepositoryImpl`).
  Per-server snapshots in `ToolsUiState` carry the live
  `McpConnectionStatus` (`Connecting` / `Connected` /
  `Error(reason)`) and the discovered `McpTool` list — both
  rendered in the catalog under the expanded server row. Tool
  list responses are cached for 5 minutes; the trailing refresh
  icon on every server row force-bypasses the cache. Per-MCP-tool
  `ToolDetailScreen` now resolves a real
  `McpTool.inputSchemaJson` instead of the placeholder, and
  local AppFunction tools render their actual
  `AgentTool.parameters` (no more cosmetic `{ "...": ... }`
  stub). New `disabledMcpTools` set in `SettingsRepository`
  (keyed by `mcp:<sha8(serverUrl)>:<toolName>`) tracks the
  per-MCP-tool enabled state independently of
  `disabledAppFunctions`. Standalone `AddMcpServerScreen` route
  + file deleted — the inline add-form on `ToolsScreen` is the
  single entry point.

- **Settings — redesign + full backend wiring** —
  Settings was rewritten end-to-end to match the new mockup. New surface
  hosts nine cards: identity (device-id + Keystore probe), system
  instructions (with variable chip row and char/token counter),
  restrictions (segmented `Approve tool calls` + Block destructive /
  Block network from local model toggles + Cap autonomous steps),
  LLM parameters (Temperature / Top-K / Top-P / Repetition penalty /
  Max context / Max steps + "Reset to defaults"), local model
  (metadata card + Inference backend dropdown + Test backend with
  persisted `TestProbeResult`), external providers (collapsed nav-rows
  with key fingerprint + Add provider sheet → `ProviderDetailScreen`),
  memory (CHUNKS / SIZE / THREADS / AVG SCORE stat grid +
  Auto-summarize threshold slider + Embedding model row +
  Export / Re-embed / Clear actions), notifications (Long-running
  tasks toggle), and privacy (Crash reporting + Reset all settings).
  The legacy boolean `requiresUserConfirmation` flag is migrated
  one-shot into the new `ToolApprovalPolicy` enum
  (`true` → `SensitiveOrDestructive`, `false` → `NeverPrompt`). New
  domain: `IdentityRepository`, `MemoryRepository.observeStats()` +
  `deleteAllMemories()`, `LocalModelRepository.observeActiveModelMeta()`,
  `EmbeddingModelMetaProvider` (static), `LongRunningTaskNotifier`,
  `ToolApprovalPolicy`, `TestProbeResult`, `MemoryStats`,
  `ActiveModelMeta`, `ProviderSummary`, `Identity`, four new
  `$VARIABLE` providers (`$LANG`, `$LOCATION`, `$USER`, `$DEVICE`),
  and seven new use cases (`ResetSamplingDefaults`, `ClearAllMemory`,
  `ExportMemoryBase` over SAF, `ReembedAllMemories` with progress flow,
  `TestBackend` returning typed probe metrics,
  `GetSystemPromptVariableCatalog`). `ToolNodeExecutor` now gates by
  the new policy enum and hard-denies destructive tools when the
  `Block destructive tools` toggle is on; `KoogClientFactory` returns
  `null` for every cloud provider (Ollama still reachable) when the
  `Block network from local model` toggle is on. Restart-required
  banner detects backend / Ollama URL changes and reboots via
  `ProcessPhoenix.triggerRebirth`. Destructive actions
  (Clear memory / Reset settings) use a typed-confirm dialog (`yes`
  keyword, matching the HITL Destructive pattern). New routes:
  `settings/provider/{providerId}` (detail editor) +
  `settings/provider/add` (picker). About screen expanded with
  version / commit / license / acknowledgments / privacy policy
  sections.
- **Settings — port provider/sampling forms in the Knotwork style**
  — the Settings screen now drives the catalog
  `SettingsContent` as the single source of truth for chrome (TopAppBar,
  sections, scroll, visual states). Provider configuration moved to the
  catalog: `KnotworkProviderRow` renders a collapsible card with masked
  API-key input, model dropdown, and (for Ollama) base-URL +
  context-window fields with inline validation. Sampling sliders
  (temperature, top-K, top-P, max context, pipeline max steps, memory
  summary default limit) render through `KnotworkParamSlider`. The
  system-prompt prefix uses the new `KnotworkMonoTextArea`. A new
  `memorySummaryDefaultLimit` slider exposes the `$MEMORY_SUMMARY`
  limit (1–50) on the Memory section. About surfaces `app version`,
  short `git SHA` (via `BuildConfig.GIT_SHA`), and a license link
  routed to `AboutScreen`. MCP section navigates to the Tools screen
  for server management. `SettingsContent` now accepts an optional
  `rowContent` override so the app can replace the default
  title-subtitle-trailing row with the richer Knotwork variants without
  forking the catalog scaffolding, and an optional `onBack` callback so
  the embedded TopAppBar can render a navigation icon. Restart-required
  / destructive-confirm wiring stays deferred to a later audit pass.
- **Memory — design audit & alignment** — full 7-state
  Roborazzi baseline (Empty / Populated / Searching / LoadingMore /
  EntryExpanded / Editing / Error) in both themes plus a populated-pinned
  snapshot covering the pinned glyph. Detail-sheet tag chips switched from
  `ChipStyle.Outline` to `ChipStyle.Tonal` per `screens/README.md §C6`. New
  `MemoryAccessibilityTest` enumerates the TalkBack-reachable surfaces on
  happy path #5 (Search → expand → delete) and asserts the pinned-row
  glyph publishes a non-blank `contentDescription` so colour is never the
  only signal. Audit findings (closed / deferred) appended to
  `project_docs/ui-audit-phase22.md`.
- **Memory — edit + pin persistence** — the
  detail-sheet Edit and Pin affordances on the Memory screen now drive
  real persistence instead of "coming soon" snackbars.
  `MemoryRepository` gains `updateMemory(id, text, embedding)` and
  `setMemoryPinned(id, pinned)`; the editor regenerates the vector
  embedding for the new text through `TextEmbeddingEngine` so semantic
  search stays coherent with the visible body. The catalog
  `MemoryRow` / `MemoryEntryDetail` / `MemoryEntryRow` carry a new
  `isPinned` field — pinned rows render a leading star glyph and float
  to the top of the list ahead of the active sort partition, and the
  sheet pin button toggles between "Pin to top" and "Unpin" depending
  on the current state. Room migrates `v22 → v23` adding
  `memory_chunks.isPinned INTEGER NOT NULL DEFAULT 0`.
- **Chat home — design audit & alignment** — token
  sweep over the production chat scope, drawer slide-in motion gated
  through `respectReducedMotionTransitions`, HitlConfirm snapshot matrix
  expanded to the 3 risk variants (`Readonly` / `Sensitive` /
  `Destructive`), and 2 new font-scale 2.0× snapshots covering the worst
  cases. New `ChatHomeAccessibilityTest` asserts that the TopAppBar,
  drawer, HITL card, and console pane each publish the minimum number of
  TalkBack-reachable nodes. Audit findings (closed / deferred) live in
  `project_docs/ui-audit-phase22.md`.
- **Chat home — secondary affordances** — the
  drawer, top app bar, and composer-overflow callbacks that were left
  stubbed in Tasks 1–3 now drive real backend operations. Every entry
  point in `compose/screens/README.md §C1` is wired:
  - **New chat** opens a `ModalBottomSheet` pipeline picker pre-selected
    to the user's current binding; confirming persists a fresh
    `ChatSession` and switches to it.
  - **Rename thread** opens a rename sheet (`OutlinedTextField` +
    Save / Cancel) driving the new
    `ChatRepository.renameSession(sessionId, newName)`. The input is
    trimmed and a blank Save is a no-op.
  - **Favorite chat** persists a session-level `isStarred` flag via
    `ChatRepository.setSessionFavorite`. Favorited chats sort to the top
    of the drawer thread list and render a small leading star glyph next
    to the title (new `ChatHomeThreadRow.starred` field in the catalog).
  - **Import chat** launches `ActivityResultContracts.OpenDocument()`
    with mime `application/json`, reads the file on `Dispatchers.IO`,
    and forwards the payload to `ChatRepository.importChat(json)` (port
    of the legacy JSON parser; accepts both export-shaped objects and
    bare message arrays).
  - **Open Settings / Models** — `ChatHomeScreen` now takes
    `onOpenSettings` / `onOpenModels` constructor parameters wired by
    `AppNavGraph` to the existing `NavRoutes.SETTINGS` and
    `NavRoutes.MODELS` routes.
  - **Model picker** opens a `ModalBottomSheet` listing the locally
    installed LiteRT models (live from
    `LocalModelRepository.getAllModels()`). Picking a model calls
    `setActiveModel` + `LoadModelUseCase` to swap the inference handle;
    the empty case surfaces an "Open Models" pill deep-linking to the
    Models tab.
  - **Overflow menu** (anchored `DropdownMenu` on the TopAppBar `⋮`
    icon) drives Export chat, Delete chat (destructive `AlertDialog`),
    and Clear console. Export emits a `ChatExportPayload` via
    `viewModel.exportEvents`; the screen handles the
    `Intent.ACTION_SEND` share-sheet dispatch. Delete cascades into
    auto-selecting the next available thread, or creating a fresh
    unbound chat when none remains.
- **Room migration `v21 → v22`** — adds the `isStarred INTEGER NOT NULL
  DEFAULT 0` column to `chat_sessions`. Backfilled to `0` for every
  pre-existing row. Distinct from `MIGRATION_19_20` which introduced the
  message-level `isStarred` on `chat_messages`.
- **`ChatExportPayload`** relocated from `chat/legacy/` to `chat/home/`
  so the legacy package can be deleted without a
  dangling import.
- **Catalog: `ChatHomeThreadRow.starred: Boolean`** + leading star glyph
  in `ChatHomeDrawerThreadRow`.

### Changed

- `ChatHomeViewModel` constructor now injects `LocalModelRepository` and
  `LoadModelUseCase`. Both are used exclusively by the new model-picker
  sheet — every other flow is untouched.
- `ChatHomeStateMapping.toViewState` accepts a `threads:
  List<ChatHomeThreadRow>` parameter so the screen can pass the live VM
  projection. The fixtures fallback is preserved for the debug picker
  when the drawer is forced open before any session has been persisted.
- `ChatHomeViewModel._modelName` is now derived from the active
  `LocalModel.name` instead of the static `"Local model"` placeholder.

- **Chat home — Console pane real-time wiring** —
  replaces the `sampleConsoleLines()` / `sampleConsoleVars()` /
  `sampleConsoleTraces()` fixtures with live data streamed off the agent
  orchestrator. The console pane now reflects the real pipeline run on
  every step and survives Clear / Copy / tab-change interactions across
  process death.
  - New domain state `AgentOrchestratorState.NodeIO(nodeId, nodeType,
    input, output)`, emitted by `GraphExecutionEngine` after every
    non-`INPUT` / non-`OUTPUT` node completes (right after the existing
    `PipelineTrace` emission). Powers the Vars tab of the console pane —
    the VM aggregates emissions into a `LinkedHashMap<nodeId, NodeIO>`
    so repeated invocations of the same node id overwrite (rather than
    duplicate) Vars rows.
  - New pure-Kotlin `ChatHomeConsoleMapping.kt`: `ConsoleEvent →
    ConsoleLine` (timestamp `HH:mm:ss.SSS`, source resolution and severity
    mapping covering every `ConsoleEventType`), `TraceStep →
    ConsoleTraceSpan`, `NodeIO → List<ConsoleVarRow>` with
    `JSONObject.quote`-escaped values.
  - `ChatHomeViewModel` extensions: `consoleLines`, `consoleVars`,
    `consoleTraces`, `consoleTab`, `consoleClearConfirmRequested`,
    `consoleSnackbarEvents` flows plus public methods
    `onConsoleTabChange`, `requestConsoleClear`,
    `confirmConsoleClear`, `dismissConsoleClear`,
    `signalConsoleLineCopied`, `signalConsoleAllCopied`,
    `buildConsoleLineCopyPayload`, `buildConsoleAllCopyPayload`. The
    `consoleClearBaseline` logic from the legacy `ChatViewModel` is
    preserved verbatim so a mid-run `Clear` survives the next cumulative
    engine snapshot.
  - `SettingsRepository` gains
    `consolePreferredConsoleTabName: Flow<String>` +
    `setConsolePreferredConsoleTabName(name: String)`. The VM hydrates
    the active tab from this flow at init and writes through on
    `onConsoleTabChange`, so the user's chosen tab survives process
    death. Domain stays free of `:catalog` imports — the enum name is
    stored as a raw string and decoded at the presentation boundary.
  - `ChatHomeScreen` wires the four previously-stubbed callbacks:
    `onConsoleCopyLine` writes the plain-text payload via
    `LocalClipboardManager` and raises a Snackbar; `onConsoleCopyAll`
    pre-filters the buffer through the active `ConsoleFilter` +
    search-query so the clipboard mirrors exactly what the user sees;
    `onConsoleClear` opens a destructive `AlertDialog` and only advances
    the baseline once the user confirms; `onConsoleTabChange` persists
    through the VM. The Clear and Line-copied snackbars use new
    `chat_console_clear_dialog_confirm` / `chat_console_clear_dialog_cancel`
    / `chat_snackbar_console_line_copied` strings.
  - `ChatHomeStateMapping.toViewState` now accepts `consoleLogs`,
    `consoleVars`, `consoleTraces`, `consoleTab` and forwards them to
    `ChatHomeConsoleState`. The old `sampleConsoleLines()` /
    `sampleConsoleVars()` / `sampleConsoleTraces()` fixtures are
    deleted.
  - **Console pane is now an independent overlay** —
    `ChatHomeConsoleState.snap` becomes nullable (`null` = closed);
    catalog `ChatHomeContent` renders the overlay whenever
    `state.console.snap != null` instead of gating on
    `visualState == ConsoleExpanded`. `ChatHomeUiState.ConsoleExpanded`
    is removed from the sealed hierarchy. The VM exposes a dedicated
    `consoleSnap: StateFlow<ConsoleSnap?>` that is orthogonal to the
    chat state machine, so the pane survives `Generating →
    HitlConfirm → Clarification → Completed / Error` transitions
    instead of being closed by every terminal emission. The debug
    state picker now routes its `CONSOLE_*` entries through
    `debugConsoleSnapForId` + `openConsole(snap)`.
  - Console `Close` icon in the Partial / Full header now actually
    dismisses the overlay (catalog `ConsolePane` gains a dedicated
    `onCloseConsole` parameter); previously it only snapped the pane
    down to `Peek`.
  - `FullTabStrip` columns widened from 72 dp → 88 dp + `maxLines = 1`
    so the longest tab label (`TRACES`) no longer wraps onto two lines.
  - Pill-tap affordance: tapping the agent-status pill above the
    composer opens the console pane at the Partial snap (catalog
    `ChatHomeCallbacks.onAgentStatusClick`, screen routes to
    `viewModel.openConsole()`).
  - **Console hosted in M3 `ModalBottomSheet`** — the hand-rolled
    overlay (custom scrim + `Modifier.height(snap.height)` +
    self-implemented `detectVerticalDragGestures`) is replaced with
    Material 3's anchored-draggable bottom sheet. We get smooth
    snap-transition animations, fling-to-snap physics, drag-from-body
    (not just the handle), tap-outside-to-dismiss, swipe-down-to-close,
    and a `BottomSheetDefaults.DragHandle` that announces correctly
    via TalkBack — all for free. The console keeps its "always dark"
    identity via overridden `containerColor` / `contentColor`. The
    sheet's `SheetState` is bidirectionally synced with the host's
    `consoleSnap` flow: programmatic `partialExpand()` /
    `expand()` calls follow VM-driven changes, and user-driven snap
    moves are mirrored back through `onConsoleSnapChange`.
  - **Engine emits no longer conflated.** The per-session orchestrator
    flow in `TaskQueueManagerImpl` switched from `MutableStateFlow` to
    `MutableSharedFlow(replay = 1, extraBufferCapacity = 256)`. The
    `StateFlow` was conflated — when `GraphExecutionEngine` emitted
    `PipelineTrace` immediately followed by `NodeIO` (two `emit`
    calls back-to-back), the second `.value =` overwrote the first
    before the chat-home collector resumed on the main dispatcher, so
    the Traces tab stayed empty even when the Vars tab populated
    correctly. `SharedFlow` with `replay = 1` preserves the legacy
    "subscriber sees latest state on attach" behaviour while the 256-
    event buffer guarantees no engine emit is silently dropped. The
    `enqueueTask` / `processTask` / `updateActiveSessionsState` /
    `evictOldestTerminalSession` paths are migrated to
    `emit(...)` + `replayCache.lastOrNull()`.
  - **`ConsoleSnap.Peek` retired.** The 44 dp ticker strip duplicated
    the agent-status pill above the composer and proved a dead-end UX
    in user testing. The enum is now `Partial` ↔ `PartiallyExpanded` +
    `Full` ↔ `Expanded`, matching M3's native `SheetValue`. The
    debug-picker `CONSOLE_PEEK` entry, the `PeekHeader` /
    `PeekTabStrip` / `PeekTickerRow` / `DragHandleStrip` composables,
    and the custom `resolveDragOutcome` snap-cycle helper are deleted.

- **Chat home — HITL and Clarification real-time wiring** — replaces the `forceState(...)` stubs in
  `ChatHomeScreen.onHitlAllowOnce` / `onHitlReject` / `onClarificationReply`
  with live orchestrator round-trips.
  - `ChatHomeViewModel` now injects `ClarificationRepository` and exposes
    two new `StateFlow`s: `pendingTool: StateFlow<HitlPending?>`
    (snapshot of the tool the orchestrator is paused on, with risk +
    raw arguments) and `pendingClarification: StateFlow<ClarificationRequest?>`.
  - `handleOrchestratorState` maps `WaitingForApproval(name, args, risk)`
    onto `ChatHomeUiState.HitlConfirm(Risk)` (via `ToolRisk.toCatalogRisk`)
    and `AwaitingClarification(request)` onto `ChatHomeUiState.Clarification`.
  - New VM callbacks: `approveTool()` resumes the pipeline with `true`
    (refused defensively for `Destructive` tools until the typed-confirm
    matches `"yes"`); `rejectTool()` resumes with `false` and persists a
    `SYSTEM` chat row recording the denial; `submitClarificationReply(text)`
    forwards the reply through `ClarificationRepository.submitClarification`
    and flips back to `Generating`.
  - Clarification watchdog: when `AwaitingClarification` arrives with a
    positive `timeoutMs`, the VM arms a `delay(timeoutMs)` job that — on
    elapse — submits the default answer (first option, or empty for
    free-form), appends a `SYSTEM` "clarification timed out" chat row,
    and settles back on `Idle` / `Empty`. The repository's own
    `withTimeout` remains the authoritative gate; the watchdog is a
    UI safety-net.
  - `ChatHomeStateMapping.toViewState` now accepts `pendingTool` /
    `pendingClarification` and renders the trailing HITL /
    Clarification rows from live data — tool name, risk, JSON-decoded
    `Map<String, String>` of argument fragments, question, and
    quick-reply options — falling back to the existing fixtures only
    when no real pending snapshot is available (preserves the debug
    state picker).

- **Chat home — orchestrator core wiring** —
  replaces the earlier stub `ChatHomeViewModel` (canned delay + reply)
  with a fully wired Hilt ViewModel that runs the agent orchestrator on
  the redesigned chat surface.
  - `ChatHomeViewModel` now injects `AgentOrchestratorUseCase`,
    `ChatRepository`, `PipelineRepository`, `SettingsRepository`,
    `LlmInferenceEngine`, and `GetContextWindowUseCase`. `sendMessage()`
    drives the `Idle → Generating → Idle / Error` cycle through
    `agentOrchestratorUseCase(sessionId, prompt, pipelineId).collect`,
    forwarding the active session's `pipelineId`; intermediate states
    keep `Generating` until HITL / Clarification / Console handlers land
    in tasks 2/17 and 3/17.
  - **Pipeline binding** wired end-to-end: TopAppBar subtitle resolves
    to the bound pipeline name (or the user-marked default, or the
    first available pipeline), and the deleted-pipeline fallback
    silently rebinds the session to the default and surfaces a
    `KnotworkSnackbar` via a new `pipelineFallbackEvents` one-shot
    `SharedFlow`.
  - **Session lifecycle**: initial session is restored from
    `SettingsRepository.currentChatSessionId` (or freshly generated
    when none exists) and persisted via `ChatRepository.saveSession`;
    `selectThread` re-subscribes the message stream and rebalances the
    resting state.
  - **Token counter**: the TopAppBar shows a rough
    `getContextWindowUseCase(sessionId).length / 4` estimate against
    `SettingsRepository.maxContextLength`; a precise tokenizer is
    queued as a separate follow-up.
  - Display-message flow now goes through
    `ChatRepository.getDisplayMessagesForSession`; the legacy in-VM
    `MutableStateFlow` of pretend messages and the canned reply
    constants are gone.
  - Auto-renames a new chat to the first user message (truncated to 20
    characters) on send, matching legacy parity.
- **Accessibility + release-candidate gate** —
  finalises the v0.1 surface against `decisions.md §14`:
  - Localised the only hard-coded English `contentDescription` left in
    the catalog (`PipelineListRow` overflow icon now resolves through
    `knotwork_library_row_overflow_cd`) and dropped the duplicated
    semantics override on `QuickAddRadialMenu`'s close button so
    TalkBack reads the localised resource instead of `"close"`.
  - `QuickAddTile` now merges its glyph + label into a single
    `mergeDescendants` semantics node with a 48 dp `minimumInteractiveComponentSize`
    floor, so the radial menu tiles are TalkBack-reachable in one focus
    stop instead of two.
  - Memory FLIP rank-shuffle (`animateItem(placementSpec)`) now gates
    on `KnotworkTheme.a11y.reducedMotion()` — the 320 ms slide
    collapses to an 80 ms crossfade when the user has the system
    "Remove animations" toggle on.
  - **`A11yMatrixSnapshotTest`** — new `:catalog` Roborazzi suite
    locking baselines at `fontScale = 2.0` ("Largest" preset) for Chat
    home / Pipeline library / Tools / Memory, plus a generating-state
    snapshot under reduced-motion. Provides visual proof that no
    dynamic-type layout breaks at 200 %.
  - **`WcagContrastTest`** — pure-JVM ratifies WCAG 2.1 AA contrast for
    the console foreground/background pair and the risk-destructive
    label on `surface1` in both themes. Codifies the §14 contrast
    contract so a future token regression fails CI.
  - **`TalkBackHappyPathsTest`** — Compose-test scaffolds covering the
    five happy paths from `decisions.md §14`. Asserts each entry
    surface (chat home, pipeline library, tools, settings, memory)
    publishes at least one TalkBack-reachable node (non-blank
    `contentDescription`, `text`, or `OnClick` action). The live
    TalkBack walkthrough on physical hardware is owned by QA via the
    v0.1 release checklist.
  - **Roborazzi baseline-lock** — every new screen's full state matrix
    is committed under `:catalog/src/test/snapshots/` so
    `:catalog:verifyRoborazziDebug` runs green without `*-actual.png`
    leftovers.
  - **Internal review retrospective** — walks through the component
    review checklist for every sub-task, with the gaps closed in a
    follow-up explicitly cross-linked.
  - **Release APK build** — `:app:assembleRelease` now produces an
    installable APK signed with the debug keystore (until a release
    keystore is provisioned), targeting `arm64-v8a` only (every
    `minSdk = 36` device is 64-bit). v0.1 APK weighs in well above the
    initial 30 MB target — the on-device LiteRT-LM runtime, the
    bundled cloud-provider SDKs (OpenAI / Anthropic / Google /
    DeepSeek / Ollama via Koog), and the un-shrunk DEX classes
    dominate. Switching to Android App Bundle + R8 minification is
    tracked as the v0.2 size-reduction work; the APK size is documented
    here so reviewers can plan accordingly.
- **Hero screenshot in `README.md`** — pipeline editor (light theme)
  rendered into `docs/images/hero-pipeline-editor.png` from the
  catalog Roborazzi baseline. Replaces the missing visual entry the
  README has carried since the rewrite started.

- Redesigned **remaining screens C3 – C8** — six
  user-facing surfaces now driven by stateless catalog content
  composables, each backed by a sealed `*ViewState` matrix mirroring
  `compose/screens/README.md`:
  - **C3 Pipeline library** (`PipelineLibraryContent`) — search field,
    `All / Recent / Shared / Mine` filter chips (Shared rendered
    disabled until the sync backend lands), swipe Duplicate / Archive /
    Delete reusing the catalog `PipelineListRow`, FAB → "+ New
    pipeline" dialog, multi-select chrome, Empty / Loading / Filtering
    (no-matches) / SwipeOpen / MultiSelect / Error states. App-side
    `PipelineLibraryScreen` rewritten as a thin VM ↔ catalog adapter.
  - **C4 Tools / MCP** (`ToolsContent`, `ToolDetailContent`,
    `AddMcpServerContent`) — collapsible sections per MCP server with
    `StatusPill`s; per-tool enable toggle; URL validation in
    `AddMcpServerScreen`; new `ToolDetailScreen` shows the JSON-Schema
    preview + enable/disable toggle. Routes `TOOL_DETAIL` and
    `ADD_MCP_SERVER` now resolve to real screens.
  - **C5 Onboarding pager** (`OnboardingContent`) — replaces the
    single-screen stub with a 4-step flow (Welcome → Model
    source → Permissions → Sample pipelines) plus a custom
    `OnboardingScaffold` (progress dots, skip, back / next, finish).
    `OnboardingViewModel` records the user's model-source pick, API-key
    field with inline format validation, permission grants, and
    sample-pipeline selections.
  - **C6 Memory** (`MemoryContent`) — single recall list driven by the
    catalog `MemoryEntryRow`; 200 ms-debounced search; sort chips
    (Recent / Relevance / A→Z); detail bottom sheet with Edit / Pin /
    Delete; FLIP rank-shuffle animation on every `LazyColumn` row
    (`animateItem` with `tween(320 ms, emphasized)`). The legacy
    "Chat history" tab is dropped.
  - **C7 Settings** (`SettingsContent`) — sectioned LazyColumn
    (Appearance / Models / Privacy / Memory / MCP / About) with the
    documented state matrix (Loading / Default / PendingChange /
    ValidationError / RestartRequired / DestructiveAction / Error). The
    legacy app-side `SettingsScreen.kt` keeps its rich provider /
    sampling forms until a follow-up ports them row by row.
  - **C8 Console pane** (extension of `ConsolePane`) — inline
    search-by-text field above the Logs body, long-press menu on every
    log row (`Copy line` / `Only show this source`), localised "no
    matches" placeholder. Wired through `ChatHomeViewModel` so the
    affordance survives state transitions.
- **Roborazzi snapshot suites** for every new catalog screen (light +
  dark across the documented state matrix): pipeline library,
  onboarding, memory, settings, tools / tool detail / add-MCP-server.

- Redesigned **Pipeline editor screen** —
  `PipelineEditorScreen` under `presentation/ui/pipeline/editor/*`
  becomes the production canvas surface, replacing the legacy
  `VisualOrchestratorScreen`. Composes the catalog atoms
  (`NodeCard`, `EditorToolbar`, `NodeConfigSheet` + all 12 per-type
  `NodeConfigForms`) into a working editor:
  - **Infinite canvas** with pan + 2-finger pinch zoom clamped to
    `0.4×–2.0×`; node positions in canvas-space px, projected through a
    pure-Kotlin `CanvasTransform` so the gesture stream never mutates
    `OrchestratorViewModel`.
  - **Drag-and-drop** of nodes with the spec'd pickup
    (1.0 → 1.04 over 100 ms `easeOut`) and release-settle
    (`spring(.7f, 15f)`) animations, snapping to a 24 dp canvas grid on
    commit.
  - **Connection mode** — drag from an output port renders a preview
    Bezier; release on a target's inbound hit-zone routes through
    `OrchestratorViewModel.addConnection` (which keeps the DAG invariants).
  - **Long-press radial quick-add menu** with one tile per node type;
    selecting a tile spawns the node and opens its `NodeConfigSheet`
    pre-filled with sensible defaults from `NodeConfigCodec.defaultFor`.
  - **Multi-select** via long-press on a node card; the editor toolbar
    swaps for `MultiSelectToolbar` with bulk Cancel / Delete actions.
  - **Sugiyama-style auto-layout** — longest-path layering + median
    crossing reduction + grid-aligned coordinates (`AutoLayout.compute`)
    triggered from `EditorToolbar.onAutoLayout`.
  - **Validation bar** lists every `PipelineValidationError` returned by
    `PipelineGraph.validate()`; tapping a row drives `requestFocusNode`
    on the VM, which the screen consumes via a `SharedFlow` to centre
    the canvas on the offending node.
  - **Run-trace bar** + per-node run pulse (catalog `NodeCard.running`)
    + traveling-dot accent on active edges (arc-length-derived cycle so
    motion stays at the spec's 40 dp/s).
  - **Undo / redo** stack (`EditorUndoRedo`, capacity 50) wired to every
    graph mutation; clears the redo branch on a new push.
  - Full per-type **`NodeConfigSheet`** integration — all 12 forms from
    `node-specs.md` (Input / Output / LiteRt / Cloud / IntentRouter /
    IfCondition / Clarification / Tool / Decomposition / QueueProcessor /
    Evaluation / Summary) shipped by the catalog and now editable from
    the production editor; configs persisted as a JSON blob via
    `NodeConfigCodec` into the new nullable `pipeline_nodes.config_json`
    column (Room migration `MIGRATION_20_21`, DB version 21).
- Phase-21 hooks on `OrchestratorViewModel`: `updateNodeFromEditor`,
  `replaceCurrentPipeline`, `requestFocusNode` /
  `focusNodeRequest: SharedFlow<String>`, `setRunning`,
  `setActiveRunningNode`, `runState: StateFlow<PipelineRunState>`,
  `labelFor(error)` — exposed so the editor screen can drive validation
  focus, undo / redo replays, and the run-trace bar without
  re-implementing the wording or the VM state map.
- Pure-Kotlin editor core (JVM-testable, no Compose dependency):
  `CanvasTransform` (pan / zoom / grid snap), `BezierEdge`
  (control-point math + hit-test + arc length), `AutoLayout`
  (Sugiyama-style), `EditorUndoRedo` (bounded snapshot stack),
  `EditorState` (`@Stable` selection / drafts / sheet holder).
- Unit-test additions for the editor core
  (`CanvasTransformTest`, `BezierEdgeTest`, `AutoLayoutTest`,
  `EditorUndoRedoTest`, `NodeConfigCodecTest`, `NodeTypeMapperTest`) and
  five `OrchestratorViewModelTest` cases covering the Phase-21 hook
  surface.

### Changed

- The legacy `VisualOrchestratorScreen` and its `DraggableNode` helper
  are deleted; `AppNavGraph` routes `NavRoutes.PIPELINE_EDITOR` and
  `PIPELINE_EDIT_WITH_ID` to the new `PipelineEditorScreen` instead.
  The parameterised alias now loads the requested pipeline through
  `OrchestratorViewModel.loadPipeline(id)` instead of being a stub.
- `NodeModel` / `NodeEntity` gain a nullable `configJson: String?` field
  (Room DB v20 → v21, `MIGRATION_20_21`) — additive, defaulted to
  `NULL`, derived from the legacy flat fields on first edit so
  pre-Phase-21 rows open in the new editor without a data migration.

- Redesigned **Chat home screen** — the new primary
  surface of the app, built on the Knotwork design system. `ChatHomeScreen`
  covers the eight documented visual states from `compose/screens/README.md
  §C1` (Empty, Idle, Generating, HITL Confirm, Clarification, Error, Drawer
  open, Console expanded) plus the cross-cutting dark theme. Highlights:
  - Stateless `ChatHomeContent` lives in `:catalog` under
    `app.knotwork.design.screens.chat.*` and is exercised by Roborazzi
    snapshots (8 states × 2 themes = 16 PNG baselines).
  - Stub `ChatHomeViewModel` in `:app` exposes a deterministic
    `StateFlow<ChatHomeUiState>`; the real orchestrator wiring (legacy
    `chat.legacy.ChatViewModel`) will reattach in a follow-up task after
    v0.1 — until then `sendMessage` simulates a 1.2 s
    `Idle → Generating → Idle` round-trip so the composer morph is
    testable.
  - **Debug state picker** (`ChatHomeDebugStatePicker`) reachable by
    triple-tapping the TopAppBar title in debug builds — flips through
    all 12 picker rows (8 visual states + 3 HITL risk tiers).
  - Reduced-motion + a11y rules go through `KnotworkTheme.a11y` per
    `decisions.md §14`; loader dots collapse to a steady `"•••"` glyph.

### Changed

- The legacy chat surface (`ChatScreen`, `ChatViewModel`,
  `ConsolePanelCollapsed`, `ConsoleFullLogSheet`, `ApprovalBanner`,
  `ClarificationCard`, `PipelineSummary`, `PipelineTraceCard`, and the
  paired tests) moved to `presentation.ui.chat.legacy/*` and is no longer
  wired into the navigation graph. It remains in the source tree as the
  reference implementation for the post-v0.1 orchestrator integration
  task that will reattach the real backend to `ChatHomeScreen`.

- Knotwork pipeline-editor base components, shipped
  under `:catalog/app.knotwork.design.components.pipelineeditor.*`:
  - **NodeCard** — 168 dp × 64..96 dp card covering idle / selected /
    multi-selected / running / runtime-error / validation-error states in
    one stateless composable. Header strip tinted from
    `KnotworkTheme.extended.node*` (12 hues), foreground tone resolved by
    a luminance-band rule (white / `onPrimary` / `onSurface`), and a 1.2 s
    running pulse gated through `KnotworkTheme.a11y.reducedMotion()`.
    Ports rendered with 12 dp visual / 24 dp hit-target dots; the inbound
    dot is pulled 6 dp into the header strip per spec.
  - **NodePorts.forType** — single source for the canonical port layout
    per node type (`IfCondition` → `True/False`, `QueueProcessor` →
    `Item/Done`, `Evaluation` → `Pass/(Retry)/Fail`, `IntentRouter` → one
    `Custom` per class, others → 1 in / 1 unlabelled out).
  - **EdgeLabel** — floating chip on `surface1` with 1 dp `outline`
    border, `LabelSm` text, plus an overload that derives the label from
    an `OutboundPort`.
  - **EditorToolbar** — inline-editable pipeline name on the left,
    Undo / Redo / Delete / Auto-layout cluster in the centre,
    `Run` (`KnotworkPrimaryButton`) + overflow on the right.
  - **NodeConfigSheet** + **NodeConfigForms** — `ModalBottomSheet` shell
    with a fixed type-pill header and sticky Cancel / Save row, plus a
    per-type form body for each of the 12 `NodeConfig` variants
    (`Input`, `Output`, `LiteRt`, `Cloud`, `IntentRouter`, `IfCondition`,
    `Clarification`, `Tool`, `Decomposition`, `QueueProcessor`,
    `Evaluation`, `Summary`). Save is gated on
    `NodeConfigValidation.validate(...)`, which enumerates every rule
    from `node-specs.md` §Validation rules.
  - **PipelineEditorCatalogContent** — scrollable harness rendering all
    12 NodeCard hues + the state matrix (selected / runtime-error /
    validation-error) + every EdgeLabel kind + the EditorToolbar + an
    idle LiteRT NodeConfigSheet body + an invalid IntentRouter body
    (surfacing inline `TITLE_DUPLICATE` / `INTENT_CLASS_COUNT` /
    `REQUIRED` errors). Wired into `ComponentsCatalogPage` under
    "Pipeline editor" and snapshot-tested via Roborazzi in both themes
    with `FixedKnotworkA11y(reducedMotion = true)` for determinism.
- Knotwork chat-surface components, shipped under
  `:catalog/app.knotwork.design.components.chat.*` and
  `:catalog/app.knotwork.design.components.console.*`:
  - **ChatMessage** with sealed `ChatContent` (`Text`, `Markdown`,
    `Confirmation`, `Clarification`, `Error`, `ToolCall`) — dispatches
    per content type, applies asymmetric bubble shapes (`ChatBubbleShapes`,
    16/16/4/16 mirrored for user vs assistant), and surfaces a long-press
    context menu (Copy / Rerun / Rate) with haptic feedback and a 60 ms
    scale-to-0.98 press animation. Reduced motion (per `decisions.md §14`)
    skips the scale.
  - **HitlConfirmationCard** — full HITL prompt with risk pill, tool
    name, summary, collapsible JSON args block, destructive "type yes"
    typed-confirm row, and a `Reject / Always allow / Allow once` action
    row gated by `HitlConfirmationState.isAllowOnceEnabled(...)`
    (pure-Kotlin helper, JVM-tested).
  - **ClarificationCard** — quick-reply chips (`KnotworkChip(Tonal)`) +
    free-form `OutlinedTextField` with `ImeAction.Send`; collapses to a
    `Replied: …` summary when the model carries a `replied` value.
  - **ChatComposer** — multiline 1..6 line composer with leading
    attach / voice icons, trailing send button that morphs to a stop
    button via `AnimatedContent` (200 ms crossfade; reduced motion
    swaps instantly), and an inline `signalError` banner for the
    `Error(message)` state.
  - **ConsolePane** — bottom-sheet container with three snap heights
    (`Peek` 44 dp / `Partial` 360 dp / `Full` 720 dp) and three tabs
    (`Logs` / `Vars` / `Traces`). Always-dark surface
    (`extended.consoleBg` / `consoleFg`) regardless of system theme.
    Source-filter chips drive the `ConsoleFilter.matches` predicate
    (JVM-tested); traces tab renders a thin per-row duration bar
    relative to the longest span.
  - **Catalog surface** — `ChatCatalogContent` and
    `ConsoleCatalogContent` harnesses, wired into `ComponentsCatalogPage`
    under new `Chat` and `Console` sections.
  - **Roborazzi snapshot baselines** — five new PNGs under
    `:catalog/src/test/snapshots/`: `chat_light.png`, `chat_dark.png`,
    `chat_reduced_motion.png` (pinned through `FixedKnotworkA11y`),
    `console_light.png`, `console_dark.png`. Behavioural coverage:
    `HitlConfirmationStateTest`, `ChatBubbleShapesTest`,
    `ConsoleFilterTest`, `ConsoleSnapTest`.
- Knotwork base component library, shipped under
  `:catalog/app.knotwork.design.components.*`:
  - **Buttons** — `KnotworkPrimaryButton`, `KnotworkSecondaryButton`
    (with `destructive` flag for HITL `Reject`), `KnotworkTextButton`,
    `KnotworkIconButton` (optional `badge: Int?`, `9+` overflow). All
    four follow the `KnotworkTheme.shapes.md` shape, expose a 48 dp
    minimum touch target, and honour the spec'd `loading` /
    `disabled` palettes (label fades to alpha 0.3, container shifts
    to `extended.surface3` / `onSurfaceDim`).
  - **Chips & pills** — `KnotworkChip` with `Default / Tonal /
    Outline` styles + `selected` palette, decorative no-`onClick`
    variant for tag rows; `RiskPill` (`Read-only / Sensitive /
    Destructive`) paired with `Visibility / WarningAmber / GppMaybe`
    glyphs from `icons/icon-mapping.md`; `StatusPill` (`Idle /
    Running / Success / Warning / Error`). All three pills expose
    `Risk level: …` / `Status: …` `contentDescription`s so colour is
    never the only signal.
  - **List rows** — `PipelineListRow` (72 dp, swipe-from-right reveals
    `Duplicate / Archive / Delete` via `Modifier.draggable` +
    `Animatable<Float>`; `revealed: Boolean?` parameter drives
    deterministic snapshot rendering); `ToolListRow` (64 dp, trailing
    connection-status pill via a `ConnectionStatus → Status`
    mapping); `MemoryEntryRow` (variable height, 3-line `BodyBase`
    clamp, tag chips + relevance score footer).
  - **Misc** — `EmptyState` (centred illustration slot defaulting to
    `StripedPlaceholder`, title + subtitle + optional CTA);
    `KnotworkSnackbar` (`Default / Error / Success` variants);
    `KnotworkLoader` (three pulsing dots `Accent300 → Accent400 →
    Accent500`, 1.2 s loop, 200 ms stagger; collapses to a static
    `•••` glyph under reduced motion); `StripedPlaceholder` (40 %
    diagonal stripes, optional mono caption).
  - **Catalog surface** — `ComponentsCatalogPage` mirrors
    `FoundationsCatalogPage` / `IconCatalogPage`, surfacing every
    base component under one scrollable column.
  - **Roborazzi snapshot baselines** — eight new PNGs under
    `:catalog/src/test/snapshots/`: `buttons_*.png`, `chips_*.png`,
    `lists_*.png`, `misc_*.png`, `components_*.png` (one light + one
    dark per category, plus the aggregated catalog page). Behavioural
    coverage: `KnotworkA11yTest`, `RiskPillTest`, `StatusPillTest`,
    `KnotworkChipTest`, `PipelineListRowTest`, `ConnectionStatusTest`.
- Accessibility primitives in `:catalog/app.knotwork.design.a11y` —
  `KnotworkA11y` interface (`reducedMotion()` backed by
  `Settings.Global.TRANSITION_ANIMATION_SCALE` /
  `ANIMATOR_DURATION_SCALE` with a `ContentObserver`-driven recompose;
  `fontScale()` mirroring `LocalConfiguration`), the
  `DefaultKnotworkA11y` production singleton, the test-friendly
  `FixedKnotworkA11y(reducedMotion, fontScale)`, the
  `LocalKnotworkA11y` composition local, and the
  `respectReducedMotionTransitions(enter, exit)` helper that swaps any
  enter/exit transitions for an 80 ms alpha-only crossfade when
  reduced motion is enabled. Wired into `KnotworkTheme` via the new
  `KnotworkTheme.a11y` accessor — components MUST consume the gate
  through this accessor instead of touching `LocalConfiguration` /
  `Settings.Global` directly.
- App shell with bottom navigation, single unified nav-graph, and a
  first-launch onboarding gate:
  - Four-tab `NavigationBar` — **Chat** (start) / **Pipelines** /
    **Tools** / **More** — replaces the legacy hub-style `HomeScreen`.
    Tab state (back-stack, scroll position) is preserved across
    switches and rotations via the canonical
    `popUpTo(startDestination) { saveState = true } + restoreState = true`
    pattern. While on a tab's start destination, the system Back
    gesture finishes the activity (Back on root tabs exits the app,
    not switches tab).
  - `AppNavGraph` consolidates every route into one `NavHost`: splash,
    onboarding, all four tabs, the pipelines nested-graph (library +
    editor with the parameterised `pipeline/{id}/edit` alias), tools
    detail + add-MCP placeholders, and the secondary screens under
    More (Memory / Models / Prompts / Task monitor / Live metrics /
    Settings / About). Modal bottom-sheet routes
    (`sheet/node-config`, `sheet/console`) are registered as
    placeholders backed by a shared `KnotworkModalRoute` wrapper
    (Material3 `ModalBottomSheet` + `PredictiveBackHandler`); the
    sheet bodies arrive in Tasks 6/7/10.
  - Chat deep-link: `knotwork://chat/{threadId}` resolves to the Chat
    tab and forwards the thread id to `ChatViewModel.switchSession`.
  - Onboarding stub: a single-screen welcome with a Get-started CTA.
    Gating is keyed off a new dedicated
    `SettingsRepository.hasCompletedOnboarding` flag (separate from
    `isFirstLaunch`, which `InitializeAppUseCase` clears during
    cold-start seeding and therefore cannot drive the UI gate). The
    full 4-step `HorizontalPager` (Welcome → Models → Permissions →
    Sample pipelines) ships separately.
- Bundled brand fonts in `:app/src/main/res/font/`:
  Inter Regular / Medium / SemiBold / Bold and JetBrains Mono Regular /
  Medium. Sources are the SIL OFL 1.1 upstreams (Inter v4.0,
  JetBrains Mono v2.304) subset to Latin-1 plus a handful of typographic
  punctuation glyphs. Total APK delta ≈ 152 KB (well under the 350 KB
  ceiling). `App.onCreate()` calls
  `KnotworkFontsBootstrap.install()` so `KnotworkTextStyles` resolves
  against the bundled families on the first frame instead of system
  fallbacks. License notices ship in `app/src/main/assets/THIRD_PARTY_LICENSES.txt`.
- 17 custom `ImageVector` icons hand-ported from
  `project_docs/design/icons-src/` into
  `:catalog/.../icons/imagevector/` — brand mark + wordmark glyph, the
  Pipelines-tab `Flow`, editor `AutoLayout`, memory `Brain`, and the 12
  pipeline-node glyphs (`NodeInput`, `NodeIntentRouter`, `NodeBranch`,
  `NodeClarify`, `NodeLite`, `NodeCloud`, `NodeTool`, `NodeDecompose`,
  `NodeQueue`, `NodeEval`, `NodeSummary`, `NodeOutput`). The `AppIcons`
  facade now resolves to real vectors instead of the prior `error(...)`
  stubs; a JVM unit test (`AppIconsTest`) guards 24×24-dp/viewport and
  non-empty path invariants for each entry.
- `IconCatalogPage` composable in `:catalog` rendering every custom icon
  plus a curated set of Material Icons Extended at 24/32/48 dp on light
  and dark swatches. Light + dark Roborazzi baselines committed under
  `:catalog/src/test/snapshots/icon_catalog_{light,dark}.png` so future
  icon edits surface in code review as a snapshot diff.
- Knotwork adaptive launcher icon in
  `:app/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml`,
  derived from `project_docs/design/compose/brand/ic_launcher_*.svg`.
  Background = `knotwork_accent_500`, foreground = the three-vertex
  Knotwork mark in `knotwork_surface_0`, monochrome = the mark in
  pure black for the Android 13+ themed-icon mode. Adaptive XMLs in
  `mipmap-anydpi-v26/` wire all three layers; no `mipmap-anydpi-v33/`
  folder is created (dead path under `minSdk 36`, see `decisions.md §15`).
- Platform splash window via `androidx.core.splashscreen.installSplashScreen()`.
  `Theme.App.Splash` (parent `Theme.SplashScreen`) pins
  `windowSplashScreenBackground = @color/knotwork_accent_500` and
  `windowSplashScreenAnimatedIcon = @drawable/ic_launcher_foreground`;
  `postSplashScreenTheme` swaps back to the regular app theme when the
  platform splash dismisses on the first Compose frame, handing off to
  the existing in-app `SplashScreen` composable without a blank-frame
  flash.
- Knotwork design tokens ported into `:catalog` under
  `app.knotwork.design.tokens`. Six token files —
  `Color.kt`, `ExtendedColors.kt`, `Type.kt`, `Spacing.kt`, `Shape.kt`,
  `Elevation.kt`, `Motion.kt` — establish the canonical Compose-side
  source of truth for colour, typography, spacing, shape, elevation and
  motion. Every token is exposed both via the upstream `KnotworkPalette`
  / `KnotworkLight` / `KnotworkDark` objects and through a Material3
  mapping (`knotworkLightColorScheme()` / `knotworkDarkColorScheme()` /
  `knotworkTypography()` / `MaterialKnotworkShapes`).
- `KnotworkTheme` composable in `:catalog` now wires the tokens into a
  real `MaterialTheme` and installs `KnotworkExtendedColors`,
  `KnotworkSpacing`, `KnotworkShapes`, `KnotworkElevation` and
  `KnotworkMotion` into composition locals. A sibling `object KnotworkTheme`
  exposes them through `KnotworkTheme.extended` / `.spacing` / `.shapes`
  / `.elevation` / `.motion` accessors, mirroring the shape of
  `MaterialTheme.colorScheme`. Material You / dynamic colour stays
  intentionally unexposed.
- `FoundationsCatalogPage` composable plus light + dark `@Preview`s
  rendering the palette, type scale and spacing tokens as a single
  scrollable surface for design review and snapshot baselines.
- `knotwork_*` colour and `knotwork_sp_*` dimen mirrors in
  `:app/src/main/res/values/colors.xml`,
  `:app/src/main/res/values-night/colors.xml` and
  `:app/src/main/res/values/dimens.xml` so non-Compose surfaces
  (notifications, app widgets, splash window theme) can reach the
  Knotwork tokens through the standard Android resource pipeline.
  Compose code keeps reading from `:catalog` `KnotworkTheme.*`. Resources
  are pre-published — `tools:ignore="UnusedResources"` is applied at the
  file level until the consuming surfaces land in later changes.
- Snapshot-testing infrastructure: Roborazzi `1.60.0` + Robolectric
  `4.16.1` wired into `:catalog`, with the first baseline (light + dark
  PNGs of `FoundationsCatalogPage`) committed under
  `:catalog/src/test/snapshots/`. `./gradlew :catalog:recordRoborazziDebug`
  refreshes the baselines; `./gradlew :catalog:verifyRoborazziDebug`
  is the CI gate. Aggregated `./gradlew check` already triggers
  `verifyRoborazziDebug` via the `testDebugUnitTest` chain.
- `:catalog` Android library module hosting the Knotwork design system.
  The project scaffold shipped first: namespace
  `app.knotwork.design`, `minSdk 36` / `compileSdk 37`, Compose BOM,
  ktlint and detekt mirrored from `:app`. A follow-up replaced the
  pass-through `KnotworkTheme` with the real token-wired implementation
  (see entries above).
- `androidx.core.splashscreen 1.0.1` dependency wired into `:app`. The
  platform-side `installSplashScreen(...)` call lands in a follow-up once
  the brand mark and accent ramp are available; declaring the artefact
  here unblocks downstream tasks without touching the existing Compose
  `SplashScreen` route.
- `android:enableOnBackInvokedCallback="true"` declared on the agent
  `<application>` element to opt the app in to Android's predictive-back
  gesture stack. Surface-level `PredictiveBackHandler` wiring on modal
  sheets follows later.

### Changed

- `MainActivity.enableEdgeToEdge(...)` now passes explicit transparent
  `SystemBarStyle.auto(...)` parameters for both status and navigation
  bars so the design system can paint to the device edges deterministically
  in both light and dark themes. Visible behaviour is unchanged on the
  current screens.
- `CONTRIBUTING.md` now lists JDK 21 as the required toolchain for
  running unit tests. Roborazzi's Robolectric
  backend requires JDK 21 to render against `minSdk 36`. Production
  code still compiles to `JavaVersion.VERSION_17` / `JvmTarget.JVM_17`.
  Note for repo owner: the Action runner in `.github/workflows/check.yml`
  (gitignored — see `.gitignore`) must be bumped from `java-version: '17'`
  to `'21'` for the gate to keep passing once this branch lands; the
  bump cannot be made from this PR because the Git PAT lacks the
  `workflow` scope.

### Changed

- Callee-side AppFunctions surface now relies on the auto-merged
  `androidx.appfunctions.service.PlatformAppFunctionService` (from
  `appfunctions-service`) for dispatch. `SearchAppFunction.invoke` is annotated with
  `@AppFunction`, so its KSP-generated entry in `app_functions_v2.xml` advertises the
  function to external callers. **The wire id contains literal backticks around the
  `data` package segment** —
  `` app.knotwork.android.`data`.tools.local.appfunctions.SearchAppFunction#invoke `` —
  because the AppFunctions compiler bakes Kotlin source-level escaping for soft
  keywords into the id string. External callers must include the backticks verbatim,
  exactly as `AppFunctionsEndToEndTest.SEARCH_TOOL_ID` and the `:tools-probe`
  `MainActivity` constant do. The Hilt-managed instance is supplied to the
  AppFunctions runtime through a new `AppFunctionConfiguration.Provider`
  implementation on `App`.

### Removed

- Hand-rolled callee dispatch: `AgentAppFunctionService`, `AppFunctionRouter`,
  `AppFunctionDispatchEntryPoint`, the matching manifest `<service>` entry, and
  `AppFunctionRouterTest`. The merged platform service plus KSP-generated invokers cover
  the same surface end-to-end and remove the parallel routing path that previously had to
  be kept in sync with `SearchAppFunction`.

### Added

- `:tools-probe` debug-only Android module shipping a single
  `@AppFunction echo(message)` so the end-to-end instrumented test
  (`AppFunctionsEndToEndTest` in `:app/src/androidTest`) has a deterministic
  remote target. The probe APK is installed on the device before the
  agent's instrumented tests via a Gradle task hook that adds
  `:tools-probe:installDebug` as a prerequisite of
  `installDebugAndroidTest` — works for both CLI
  (`./gradlew :app:connectedDebugAndroidTest`) and Android Studio's Run
  Test flows. Its `MainActivity` doubles as a one-tap manual smoke for the
  agent's callee-side surface (`search_tool` query "Knotwork") on the
  reference device.
- `AppFunctionsEndToEndTest` covering four end-to-end scenarios on Android
  16+: caller-side `ToolRepository.executeTool` round-trip, HITL gate
  emission of `WaitingForApproval` for SENSITIVE-by-default AppFunctions
  followed by `ToolNodeExecutor.resumeWithApproval`, callee-side invocation
  of `search_tool` through the system `AppFunctionManager`, and risk
  override resolution via `SettingsRepository.setAppFunctionRiskOverride`.
  Note: on stock Android 16 builds the `EXECUTE_APP_FUNCTIONS` permission
  is declared at signature / module / preinstalled protection level —
  verified on both the Pixel 9 Pro emulator
  (`system-images;android-36;default;x86_64`) and a Samsung Galaxy S25
  Ultra (Android 16). `pm grant` rejects the permission with "not a
  changeable permission type" and `appops set` reports "Unknown operation".
  Third-party agents can therefore neither observe nor invoke cross-package
  AppFunctions on those builds, regardless of whether the host is an
  emulator or real device. The test detects that platform state from the
  captured grant-attempt stderr and skips itself via `Assume.assumeTrue`
  with a detailed transcript. The probe's manual MainActivity button hits
  the same platform restriction (plus a second pre-requisite: the agent's
  `search_tool` will only show up in `app_functions_v2.xml` once it gains
  an `@AppFunction` annotation-7). Both gates re-open
  automatically on future Android builds that relax the permission's
  protection level for debuggable apps.
- `AppFunctionsE2ETestEntryPoint` (Hilt `EntryPoint`) under
  `data/testing/` exposing the singletons the new instrumented test
  consumes (`ToolRepository`, `SettingsRepository`, `ChatRepository`),
  avoiding a parallel Hilt test component.
- Callee-side AppFunctions surface: `AgentAppFunctionService` now routes
  incoming `ExecuteAppFunctionRequest`s through a pure-Kotlin
  `AppFunctionRouter` that resolves Hilt-managed wrappers via an
  application-scoped `AppFunctionDispatchEntryPoint`. The first wrapper,
  `SearchAppFunction`, exposes the read-only `search_tool` built-in to
  external callers using the same Wikipedia code path the agent invokes
  internally. Side-effect built-ins (`schedule_task`, `delegate_task`)
  remain intentionally excluded from the callee surface. Cancellation,
  invalid-argument, function-not-found and unexpected-error paths are
  translated into the matching `AppFunctionException` codes.
- `ToolRisk` domain model (`READ_ONLY` / `SENSITIVE` / `DESTRUCTIVE`) with
  per-tool defaults: `search_tool` → `READ_ONLY`, `schedule_task` /
  `delegate_task` → `SENSITIVE`, discovered AppFunctions → `SENSITIVE` (with
  per-tool override via `SettingsRepository.appFunctionRiskOverrides`),
  MCP tools → blanket `SENSITIVE`. Resolved through the new
  `ToolRepository.getRisk(name)` seam. HITL gate consumption lands in a
  follow-up task; this change ships the data model only.
- `CONTRIBUTING.md` at the repository root covering dev setup, build & test
  commands, branch model, Conventional Commits, the pull-request checklist,
  and the English-only language policy.
- `CODE_OF_CONDUCT.md` at the repository root adopting Contributor
  Covenant 2.1; reporting routes through the same private GitHub Security
  Advisories channel as `SECURITY.md`.
- Public contributor-facing documentation under `docs/`:
  `docs/code-style.md`, `docs/testing.md`, `docs/api-conventions.md`.
- Caller-side end-to-end execution of discovered AppFunctions. The local
  tool catalogue now includes AppFunctions surfaced by
  `LocalAppFunctionManager` alongside built-ins (built-ins still win on
  name collisions, with a warning log). AppFunctions are addressed by
  their qualified name (`"${packageName}/${id}"`) so identical ids exposed
  by different packages can coexist without overwriting each other in the
  caller-side cache. `ToolRepository.executeTool` routes AppFunction calls
  through `LocalAppFunctionManager.invokeByName`, which encodes arguments
  via `AppFunctionDataCodec`, dispatches through the system
  `AppFunctionManager`, and renders the response back to a flat JSON
  string. Disabling an AppFunction via
  `SettingsRepository.disabledAppFunctions` now also gates execution.

### Changed

- Coverage policy wording in `docs/testing.md` aligned with the actual
  enforcement: target 100% logic coverage for new code in `domain` /
  `data`, build gate at 70% LINE aggregate (per-package decomposition in
  `docs/coverage-baseline.md`, full policy in `docs/static-analysis.md`).
- Prominent inline approval prompt (`ApprovalBanner`) rendered directly
  above the chat input whenever the orchestrator is in `WaitingForApproval`.
  Replaces the easy-to-miss 16dp console-line affordance as the primary
  surface for the HITL decision; the console line still appears as a
  short status echo. Full-width Approve / Deny buttons meet the 48dp tap
  target and the banner is unaffected by the compact-console layout.
- Risk-based Human-in-the-Loop (HITL) gate. `ToolNodeExecutor` now consults
  `ToolRepository.getRisk(name)` instead of a global flag: `SENSITIVE` and
  `DESTRUCTIVE` tools always prompt; `READ_ONLY` tools run silently unless
  the user has globally opted into "ask on every tool call" via
  `SettingsRepository.requiresUserConfirmation` (which is now an override,
  not the primary trigger). `AgentOrchestratorState.WaitingForApproval`
  carries the resolved `risk` and the inline approval row in the chat
  console renders a coloured risk chip (`READ` / `SENS` / `DEST`) next to
  the tool name. `DESTRUCTIVE` approvals route through a dedicated
  `IMPORTANCE_HIGH` notification channel with a warning glyph; `SENSITIVE`
  / opt-in `READ_ONLY` continue on the existing approval channel.

### Deprecated

### Removed

### Fixed

- `ApprovalBannerTest` and `ChatScreenTest` no longer fail to compile on the
  current Compose / `UiText` surfaces. The fixes (a stale `assertDoesNotExist`
  import and a raw `String` passed where a `UiText?` is expected) had drifted
  silently because CI runs JVM-only `./gradlew check`; the new
  instrumented-test work made the breakage observable.

### Security

## [0.1.0] - 2026-05-11

First public pre-release. This entry retrospectively summarises the work
that produced the initial 0.1.0 snapshot.

### Added

- On-device LLM inference engine built on **LiteRT-LM** (Google Edge AI,
  formerly TensorFlow Lite) with optional NPU/GPU acceleration and a
  streaming token API.
- **AppFunctions Jetpack** integration for on-device tool calling.
- **Model Context Protocol (MCP)** client for connecting external tool
  servers.
- Optional cloud LLM providers — OpenAI, Anthropic, Google (Gemini),
  DeepSeek and Ollama — all opt-in and bring-your-own-key.
- Visual pipeline orchestrator inside the app for editing typed
  node graphs (input, local LLM, cloud LLM, tool, routing,
  decomposition, evaluation, clarification, output).
- Standalone browser-based `pipeline-editor.html` for authoring and
  exporting pipelines without launching the app.
- Long-term memory with semantic retrieval (RAG) over past conversations.
- Multi-session chats backed by a priority task queue.
- Per-chat pipeline binding with rename / duplicate / delete operations
  on the pipeline library.
- Prompt variables substituted fresh on every render: `$DATE`, `$TIME`,
  `$TOOLS`, `$MODEL`, `$MEMORY_SUMMARY`.
- Agent-initiated clarifications — a pipeline node can ask the user a
  question mid-execution and suspend until a reply arrives
  (human-in-the-loop).
- Live mini-console and an expanded execution-log view inside the app
  for observing pipeline runs.
- Opt-in Firebase Crashlytics integration for anonymous crash reporting,
  disabled by default and gated by an explicit in-app consent dialog.

### Changed

- Decomposed `GraphExecutionEngine` into per-node `NodeExecutor`
  strategies so node-specific execution logic lives next to the node
  type instead of in a single monolithic engine.
- Unified the cloud-provider pipeline nodes into a single `CLOUD` node
  with a `provider` parameter, replacing the earlier per-provider node
  types.
- Extracted all hardcoded user-visible strings, prompt templates and
  magic numbers into Android resources and Kotlin constants.
- Promoted detekt, ktlint and Android lint to strict mode in the build:
  new warnings fail the local and CI quality gate.

### Fixed

- Restored prompt-variable interpolation in `ToolRepositoryImpl` — tool
  arguments were previously forwarded literally instead of being
  rendered through the prompt template engine.
- Implemented `AgentAppFunctionService.onExecuteFunction`, which was
  previously a no-op stub and silently dropped invocations.
- Surfaced JSON Schema metadata from `KoogMcpClient.getTools` so MCP
  tools correctly advertise their argument schema to the agent.
- Made `AgentIdleManager` and `TaskQueueManagerImpl` thread-safe by
  replacing ad-hoc state mutations with synchronised primitives.
- Eliminated the N+1 query pattern in `ChatRepositoryImpl.saveMessage`
  by batching session updates alongside the message insert.

### Security

- **At-rest encryption**: the Room database (`agent_database.db`) is
  encrypted with SQLCipher via `net.zetetic:sqlcipher-android`.
- **API keys**: cloud provider API keys are stored in
  `EncryptedSharedPreferences` and never written to plain
  `DataStore`.
- **SQLCipher passphrase**: a 32-byte random passphrase is generated on
  first launch and stored in `EncryptedSharedPreferences`.
- **Master key**: `EncryptedSharedPreferences` is rooted in the Android
  Keystore, so the master key is hardware-backed where available.

[Unreleased]: https://github.com/alexeyw/knotwork/compare/v0.8.0...HEAD
[0.8.0]: https://github.com/alexeyw/knotwork/compare/v0.7.3...v0.8.0
[0.7.3]: https://github.com/alexeyw/knotwork/compare/v0.7.2...v0.7.3
[0.7.2]: https://github.com/alexeyw/knotwork/compare/v0.7.1...v0.7.2
[0.7.1]: https://github.com/alexeyw/knotwork/compare/v0.7.0...v0.7.1
[0.7.0]: https://github.com/alexeyw/knotwork/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/alexeyw/knotwork/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/alexeyw/knotwork/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/alexeyw/knotwork/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/alexeyw/knotwork/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/alexeyw/knotwork/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/alexeyw/knotwork/releases/tag/v0.1.0
