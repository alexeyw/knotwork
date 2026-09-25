# User Guide

This guide is for people who installed **Knotwork**, an on-device AI
agent for Android, and want to learn how to use it. It walks through every
screen the app provides and the user-facing flows that connect them.

It is not a developer document. If you are looking for the internal
architecture, see [`architecture.md`](architecture.md); for recipes on
extending the agent with new node types, tools, or cloud providers,
see [`extending.md`](extending.md).

Marketing-style hero shots of the main surfaces (chat home, pipeline
editor, pipeline library, tools, settings) live under
[`docs/images/`](images/) and are rendered in the project README.
Inline per-flow screenshots in this guide are placeholders pending a
device-capture pass — the agent's surfaces are evolving fast enough
between releases that hand-curated guide screenshots tend to go stale
before the next release ships.

---

## Table of contents

1. [Getting started](#getting-started)
2. [Chats](#chats)
3. [Console](#console)
4. [Background tasks](#background-tasks)
5. [Entry surfaces](#entry-surfaces)
6. [Triggers](#triggers)
7. [Pipelines](#pipelines)
8. [Browser pipeline editor](#browser-pipeline-editor)
9. [Tools and MCP](#tools-and-mcp)
10. [Files](#files)
11. [Memory](#memory)
12. [Settings](#settings)
13. [Getting around](#getting-around)
14. [More tab](#more-tab)
15. [Managing local models](#managing-local-models)
16. [Prompt library](#prompt-library)
17. [Skill library](#skill-library)
18. [Active tasks](#active-tasks)
19. [Live metrics](#live-metrics)
20. [About](#about)
21. [Finding the documentation from inside the app](#finding-the-documentation-from-inside-the-app)

Two companion pages sit beside this one: [`faq.md`](faq.md) for whether a
thing is supported and where it lives, and
[`troubleshooting.md`](troubleshooting.md) for when something is broken.

---

## Getting started

The first time you launch the app you go through a short onboarding
flow, then land on the Chat tab. Instead of asking you to assemble a
pipeline up front, onboarding leads with **what the agent should do for
you**: you pick one of a few ready-made scenarios — a styled translator,
a share-to-capture handler, or a mood-aware companion — and the app sets
up everything that scenario needs. It materialises the matching pipeline
as your default, wires any surface it uses (the share handler starts
listening on the system Share sheet), and downloads exactly the model
that scenario requires, showing the size and live progress so the wait
is a known one. When the model has finished warming, the final step
opens your scenario straight into a working chat. Prefer to build your
own? The gallery's **Start from scratch** card skips the scenario and
drops you into the app to wire a pipeline node by node.

The bottom of the screen always shows the four navigation tabs:

- **Chat** — talk to the agent (the default tab the app opens on).
- **Pipelines** — browse and edit the agent's reasoning pipelines.
- **Tools** — manage AppFunctions and connected MCP servers.
- **More** — every other screen: Triggers, the pipeline preset library
  and Tasks; Memory, Files and archived chats; the prompt and skill
  libraries and Models; Settings, Help, Live metrics and About. See
  [More tab](#more-tab).

The Back gesture returns you up the inner stack of the current tab; from
Chat, the tab the app opens on, it closes the app. The full contract —
what the highlighted tab means, and where each entry point lands — is in
[Getting around](#getting-around).

If you picked a scenario during onboarding, the model it needs is
already downloading (or ready) and you can skip straight to chatting.
This section covers the Models screen directly — use it when you chose
**Start from scratch**, or any time you want to add, switch, or manage
models. Talking to the agent always needs at least one model on the
device.

### 1. Open the Models screen

Open the **More** tab and tap **Models**. The screen has two areas:

- A list of **presets** — curated LiteRT models that are known to work
  with the agent. Each preset shows the model name and a **Download**
  button (or a **Downloaded** label if it is already on the device).
- A **Custom Model URL** field for paste-in downloads (for example, a
  direct file URL from Hugging Face).

If the source you are downloading from requires authentication, paste
your token into the **HuggingFace Auth Token** field above the URL
input. The field is masked and is used only for the download request.

<!-- TODO: device capture of the Models screen. The hero shots under
     `docs/images/` cover chat / pipeline-editor / pipeline-library /
     tools / settings; Models / Memory / Onboarding still need a
     dedicated pass. -->


### 2. Download a model

- To use a preset, tap **Download** next to it.
- To download a custom file, paste the URL into **Custom Model URL**
  and tap **Download Custom Model**.

A progress bar appears with a percentage, and a notification shows the
same progress with a **Cancel** action. The transfer is a real background
job: you can leave the screen, leave the app, or finish setup, and it
keeps going — the model registers itself when it lands.

Coming back to the Models screen (or to setup) while a download is
running reconnects to it — the progress you see is the live one, not a
new transfer.

If the connection drops (or you cancel and start the same file again),
the download **resumes from where it stopped** rather than starting over.
Until it completes, the bytes live in a temporary `.part` file, so an
unfinished download never masquerades as an installed model.

### 3. Activate the model

After the download finishes, the model shows up in the **Downloaded**
list below the presets. Tap **Make Active** on the row you want to
use. The active model is highlighted and labelled **Active**. Only
one model can be active at a time.

### Discovering models from Hugging Face

You don't have to know a URL. Tap the **Discover** action in the Models
screen top bar to browse the curated
[`litert-community`](https://huggingface.co/litert-community) collection
on Hugging Face — ready-to-run on-device models — without leaving the
app:

- **Browse and search.** Each card shows the model name, its downloads
  and likes, and its licence. Type in the search box to filter, or pull
  down to refresh. The app only contacts Hugging Face when you open the
  screen, search, or open a card — never in the background.
- **Open a model.** Tapping a card shows the repository's installable
  `.litertlm` files with their sizes, the licence, and a **View on
  Hugging Face** link to the full model card. Files you already have on
  disk are marked **Installed**.
- **Install.** Tap **Install** next to a file. You'll be asked to
  **review and accept the licence** before the download starts; it then
  streams through the same downloader as the rest of the screen and the
  model appears in your **Downloaded** list, ready to activate.
- **Gated models.** Some repositories require you to accept their
  licence on the Hugging Face website and use a personal access token.
  These show a **Gated** badge and an inline token field — paste your
  token there (it is stored in the same encrypted slot as the download
  token). If a download is refused (HTTP 401/403), the app tells you to
  accept the licence on Hugging Face and add a token.
- **Offline / errors.** If the network is unavailable, the screen shows
  a clear message with a **Retry** button rather than failing silently.
  In chat, **Retry** re-runs the message that failed — not whatever you
  have typed since. Anything in the composer is left alone, and the failed
  message is not added to the conversation a second time.

### 4. Send a first message

Open the **Chat** screen and type a message. The agent should reply
within a few seconds on a device with hardware acceleration, and
within a longer wait on CPU-only devices. If the reply never arrives,
jump to [Troubleshooting](troubleshooting.md).

---

## Chats

Every conversation lives in its own chat session. You can keep many
sessions in parallel — each one has its own message history and can
be bound to its own pipeline.

### The chat home screen

The chat home is the first surface that opens after onboarding. It is
now built on the Knotwork design system and shows, top to bottom:

- A **top app bar** with the current thread title, the active model
  name beneath it (e.g. *Gemma 2 · 2B*), a menu icon on the left that
  opens the thread drawer, and an overflow menu on the right. The model
  name is a status line, not a control — switch models under
  **More → Models**. The bar also names the chat's pipeline: the one it
  is bound to if you picked one, and otherwise the pipeline that
  actually produced the conversation you are reading — which is what a
  chat opened by a trigger, by a scheduled task or by external
  automation shows, since those carry no binding of their own.
- A **message list** with user and assistant bubbles, inline tool
  invocations, and any clarification or HITL confirmation card the
  agent surfaced for the current run. A run that ends **without an
  answer** — it failed, you stopped it, an approval window elapsed, or
  the app was killed mid-run — leaves a line in the conversation saying
  so. The line is part of the history, so it is still there when you
  come back to a chat whose run ended while the app was in the
  background; you will not find a message of yours sitting there with
  nothing after it.
- A pinned **composer** at the bottom — type a message or dictate with
  the microphone. The send button morphs into a **stop** button while
  the agent is generating, and into a **retry** button after an error.
  **Stop ends the run**, not just the screen's view of it: the work
  stops and the conversation gets a line saying the run was stopped
  before it could answer. Only that button ends a run — switching to
  another chat leaves its run going, which is why you can come back
  to it.
  Anything you type but do not send is **kept per chat**: switch to
  another conversation and back and your half-written message is still
  there (drafts live for the session and are not kept across an app
  restart). If you send before the on-device model has loaded, the app
  **loads it and then sends your message automatically** — you no longer
  have to load the model and press send separately.

The surface adapts to eight deterministic visual states, all reachable
from the same screen:

| State            | When it appears                                                 |
|------------------|-----------------------------------------------------------------|
| Empty            | A brand-new thread with no messages — shows the active pipeline's starter prompts. |
| Idle             | History present, no in-flight request. The default.             |
| Generating       | Your message is on its way to an answer. The message appears at once, marked **Sending** until it is stored; the bubble under it reads **Loading model…** while the model loads, **Waiting…** while the run is queued behind another one (a trigger, a share, another chat), and streams the answer once the run starts. |
| HITL Confirm     | A tool call awaits your approval (read-only / sensitive /       |
|                  | destructive — each tier surfaces a different confirmation UI).  |
| Clarification    | The assistant asks you for more details before continuing.      |
| Error            | The model or network failed; an inline tile + retry appears.    |
| Drawer Open      | The thread list slides in as an alt-nav drawer over the chat.   |
| Dark             | A cross-cutting variant — every state respects the system theme. |

The **console pane** (see the [Console](#console) section) is rendered
as a bottom-sheet *overlay* on top of any of the above states — it is
not a chat state in its own right and stays mounted across state
transitions while it is open.

**Debug builds** expose a state picker reachable by triple-tapping the
title row, which flips between every documented state for visual QA.
The picker is not present in release builds.

### Switching, creating, and renaming chats

Open the side drawer from the chat screen (tap the menu icon, or
swipe from the left edge). The drawer lists every chat under
**SESSIONS**. The currently active session is highlighted; favorited
chats are sorted to the top with a small leading star glyph.

- **New chat** — tap **New chat** at the top of the drawer. A bottom
  sheet opens with the available pipelines (pre-selected to the one
  currently bound to your active chat); confirm to create a fresh
  session. If no pipelines exist, the new chat inherits the default
  pipeline automatically.
- **Switch chat** — tap any row in the list. The chat screen updates
  immediately.
- **Row actions** — tap the `⋮` next to a session row for **Rename**,
  **Archive**, and **Delete chat**. Rename opens a bottom sheet with the
  current name pre-filled; Delete asks for confirmation first.
- **Favorite chat** — tap the star icon in the chat top bar to favorite
  the active chat. Favorited chats persist across restarts and sort to
  the top of the drawer.

### Switching models from the chat top bar

Tap the model label under the chat title to open a model-picker bottom
sheet listing every locally installed LiteRT model. Picking a model
activates it and reloads the inference engine. If no models are
installed, the sheet shows **Open Models** that takes you directly to
the Models screen.

### Archiving chats

A chat list that only grows eventually stops being useful. Archiving
takes a conversation out of the drawer **without deleting anything** —
every message, run, and trace stays exactly where it was.

- **Archive** — swipe a drawer row from the right and tap **Archive**,
  or pick **Archive** from the row's `⋮` menu. A snackbar confirms with
  an **Undo** action; the drawer stays open, so you can put several
  chats away in a row. Archiving the chat you are reading moves you to
  the next one.
- **Find archived chats** — **More → Archived chats**, which is always
  there and shows the count. Once at least one chat is archived, the
  chat drawer's footer carries the same entry.
- **The archive screen** lists your archived chats newest-archived
  first, each labelled with when you put it away ("Archived 2 h ago").
  A chat whose background run finished *after* you archived it says so
  on the row, so nothing changes behind your back silently.
- **Restore** — the row's `⋮` menu, or swipe the row and tap **Restore**.
  The chat returns to the drawer unchanged.
- **Open an archived chat** — tap the row. It opens **read-only**: the
  history is fully readable, the top bar reads `Archived · read-only`,
  and the message box is replaced by a bar offering **Restore**. This is
  deliberate — sending a message would silently un-archive the chat, and
  only you decide when a chat comes back.
- **Delete forever** — the row's `⋮` menu, behind a confirmation. This
  is the only irreversible action on the screen; a swipe can never
  trigger it.

A background trigger or scheduled run is still allowed to write into an
archived chat, and doing so does **not** bring it back to the drawer.
Archived chats stay until you delete them; nothing expires on its own.

### Settings shortcut

The drawer footer carries a **Settings** entry that deep-links to the
Settings screen. Use it whenever the chat surface needs a quick jump
to API keys, sampling parameters, or appearance toggles.

### Exporting and importing chat history

The app uses standard Android share and file-picker intents, so chat
history is portable to any app that handles JSON or plain text.

- **Export** — from the chat screen's top-bar overflow menu (`⋮`)
  choose **Export chat**. The Android **Share Sheet** opens with a
  JSON payload containing the full message history. Choose any
  destination that accepts JSON (Files, email, a messenger, a
  cloud-drive app, and so on). Archived chats export the same way, from
  their own row menu on the archive screen — putting a chat away never
  puts it out of reach.

  The file also carries an `unfinishedRuns` list: any run of that chat
  that failed, was cancelled or was interrupted, with the error text and
  the timing. A failure never becomes a chat message, so without this a
  conversation whose only turn failed exported as a lone question with no
  answer and no explanation — which is exactly the file someone attaches
  to a bug report. Successful runs are not listed; the answer they
  produced is already there.
- **Import** — open the drawer and tap **Import chat**. The system
  file picker opens, filtered to `application/json`. Selecting a
  previously exported file creates a new chat session with the
  imported messages and switches to it immediately. A chat file can
  come from anyone, so the import keeps only the conversation — your
  turns and the assistant's — and leaves out the app notices and tool
  observations of the device it came from. Imported messages are never
  read for long-term memory, never re-run by **Retry**, and never dated
  later than the moment you imported them. A file the app cannot read is
  refused whole, with a short reason and nothing imported.
- **Delete chat** — from the same overflow menu choose **Delete chat**.
  A destructive confirmation dialog appears; once confirmed the
  conversation (and every message in it) is removed. The next
  available chat is auto-selected, or a fresh unbound chat is created
  if none remain.

### Copying a message

Long-press any message bubble to open its context menu. From there you
can **Copy** the text to the clipboard, **Re-run** it (drop the text back
into the composer), or **Save to memory** — which stores the message
verbatim in long-term memory as a manual entry and confirms with a
*Saved to memory* snackbar. Saved entries show up under
**More → Memory** with the **Manual** source.

### Reporting a response

The same menu on a message the model produced also offers **Report
response**. Pick a category (harmful or unsafe, sexually explicit, hate or
harassment, misleading, something else), add a note in your own words, and
the app assembles a report: your note, the category, the reported text, and
the app version, device and currently selected model. That last one is the model
selected now, which is the one that answered unless you have switched since.

The app sends nothing on its own — there is no reporting server behind it,
and adding one would contradict everything else on this page. You hand the
report over yourself, one of two ways: **Copy report** puts it on the
clipboard, and **Open issue** opens the public issue tracker in your browser
with the report prefilled. The prefilled text rides in the link, so GitHub
receives it as soon as the page opens, even if you never submit; the issue
becomes public only when you do. The reported response is quoted in full (up
to a length cap, which the report states when it applies) — if it holds
something private, copy the report and edit it before you post.

### Attaching an image

Tap the **image** button in the composer to attach a picture to your next
message. A small sheet offers two sources:

- **Photo library** — the system photo picker (your gallery and
  screenshots). No storage permission is requested; the picker runs in its
  own process and hands back only the image you choose.
- **Camera** — take a photo now.

The chosen image is **downscaled on the device** (its aspect ratio is kept —
never cropped to a square) and re-encoded to JPEG before it is stored; the
original is never kept — a photo you take is deleted from the app's cache as
soon as its smaller copy is made. A removable preview appears above
the input row while you finish typing — tap the **✕** to drop it. You can send
an image on its own, without any caption.

Once sent, the message bubble shows a thumbnail; tap it to view the image
full-screen, and use back, the close button, or a tap outside the image to
dismiss. If an image was later cleared to save space (see *Run history and
retention*), the thumbnail and viewer say so plainly — the message text stays
in the chat.

### Image understanding (vision)

For the agent to actually *read* the picture, the active local model must be
**vision-capable** (for example a Gemma 4 model). Because the on-device runtime
does not advertise this, you tell the app which models can see: open
**Models**, and on the active model's card flip the **Image support** toggle on.
The setting is per model and off by default.

When you send an image, the picture is handed to the **first on-device step of
the pipeline** together with your message; from there the rest of the pipeline
works on text as usual. The agent console shows an `Image input: W×H, N KB`
line at the start of the run so you can see the image was taken in.

Safety checks run **before** a run starts, so you get a clear message instead of
a failure mid-answer (your draft and the attached image are always kept):

- **Cloud-first pipeline.** Images **never leave your device** — they are not
  sent to cloud models. If the pipeline bound to the chat begins with a cloud
  step, sending an image is blocked with a note to use an on-device pipeline.
- **Text-only model.** If the active model is not marked vision-capable, the app
  explains that it can't read images and points you to the Models toggle (or to
  switch models).
- **No on-device image step.** If the bound pipeline has no on-device model step
  that could read the picture, the send is blocked rather than quietly ignoring
  the image.

For a pipeline that branches, the image goes to the first on-device model step
on the path the run actually takes. If a branch is taken that has no such step,
the agent says so on the console (`Image not used …`) instead of pretending the
model saw the picture.

**Branching on whether a picture was sent.** Two node types can react to the
presence of an image even though the picture itself only ever reaches the vision
step:

- An **If Condition** node has a *“Branch True when input has an image”* toggle.
  Turn it on and the node takes the **True** branch whenever the message carries
  an image and **False** otherwise — a plain check with no model call, evaluated
  before the text expression (which is ignored while the toggle is on). This is
  the simplest way to fork a pipeline into an “image” path and a “text” path.
- An **Intent Router** is told when the message includes an image, so you can add
  a route (an outgoing edge labelled e.g. `image`) and the router can pick it.

Only the *fact* that an image is attached is shared with these steps — never the
picture's contents, which stay with the on-device vision model.

### Voice input

Tap the **microphone** button in the composer to add voice. A small sheet
offers two ways in:

- **Record voice** — the app asks for microphone permission the first time,
  then records. While recording, the input row becomes a bar with a live timer
  (`0:07 / 0:30`), a discard ✕, and a Stop button. Recording **auto-stops** at
  the limit (set by *Audio recording length*, default 30 seconds) — the timer
  turns amber in the last few seconds to warn you.
- **Choose audio file** — pick an existing clip from your device.

Either way, the clip is **transcribed to text by your active model before
anything runs** — the audio never travels the pipeline. You'll see
*Transcribing…* in the composer; when it finishes, the transcript drops into
the input field as **ordinary editable text**. Read it, fix anything, and send
it like any other message.

Transcription needs an **audio-capable** model:

- **Text-only model.** A calm note ("This model can't transcribe audio") points
  you to the **Audio support** toggle on the Models screen (next to *Image
  support*) — turn it on for a multimodal model such as Gemma 4.
- **Microphone off.** If you declined the permission, a note offers to open
  system settings so you can grant it.
- **A task is running.** Transcription shares the single on-device engine with
  the agent, so it waits until the current run finishes rather than interrupting
  it; a note tells you to try again in a moment.

The recorded or picked clip is **temporary** — it is deleted as soon as it has
been transcribed (only the text remains).

---

## Console

The console shows what the agent is actually doing while it processes
your request — which pipeline node is running, which tools were
called, which memory chunks were retrieved, and any errors that
occurred.

### The console strip

Just above the message composer sits a dark one-line strip that says
what it is and what the agent is doing:

```
console │ [NODE]  idle · ready                              ^
```

The left side is the strip's name, the middle is the live status, and
the chevron on the right points at where tapping goes. Status lines:

| Status                             | When you see it                     |
|------------------------------------|-------------------------------------|
| `[NODE]  idle · ready`             | The agent is waiting for input.     |
| `[NODE]  generating`               | A response is being produced.       |
| `[NODE]  loading model · please wait` | The model is being loaded.       |
| `[NODE]  waiting behind another run · queued` | Your message is queued behind another run (a trigger, a share, another chat); the queue runs one at a time. Stop cancels it. |
| `[TOOL]  awaiting approval`        | The HITL card is on screen.         |
| `[NODE]  waiting on clarification` | A clarification card is on screen.  |
| `[NODE]  error · see message`      | The latest run failed (see banner). |

Tapping the strip opens the **console pane** — a bottom sheet that
covers most of the screen with the full chronological event log. The
strip does not disappear when you do: it becomes the pane's header, with
the chevron flipped, so tapping it again closes the console.

### Console pane

The console pane is independent of the chat state — it stays open
across `Generating → HitlConfirm → Clarification` transitions, so you
can watch the agent while it works without losing your place.

The pane has three tabs:

- **Console log** — every `[TAG] message` event from the current
  session in chronological order with millisecond timestamps. A row
  of source filter chips at the top lets you narrow by origin —
  **NODE / TOOL / MEMORY / RUNTIME / USER** — each toggling
  independently. The **MEMORY** chip isolates long-term-memory
  retrievals: each one is logged as
  `Memory: query='…' → N hits (score, …)`, echoing the query, how many
  chunks were surfaced, and their similarity scores. Turn on
  **Settings → Memory → (Advanced) Verbose memory logging** to expand each line
  with a per-hit text snippet and score.
- **Pipeline trace** — a structured view of the pipeline run as a tree
  of node spans (name, duration, status). Useful for understanding
  *why* a particular branch fired or a node was skipped.
- **Node I/O** — per-node input / output text plus the values of every
  `$VARIABLE` resolved during the run, so you can diff what the agent
  actually saw. In a long conversation the input may open with an
  **`--- Earlier conversation (summarized) ---`** block: that is the
  model-written stand-in for older turns that no longer fit verbatim (see
  **Settings → Memory → Compress long chat history**). The recent turns
  still appear underneath it under `--- Chat History ---`.

A few events are worth recognising while you read the **Console log**:

- **Reliability warnings (RUNTIME chip).** When the agent has to nudge a
  node into producing well-formed output, you'll see a muted yellow line
  like `Output repair 1/2 for node Intent Router`. When a cloud call hits a
  transient failure and is retried, you'll see `Cloud retry 1/2 for openai`.
  Both are normal self-healing, not errors — they only mean the agent had to
  try again. A genuine give-up is logged as a red **Error** line instead
  (for example a router that exhausted its repairs and fell back to its
  default branch).
- **History compression (MEMORY chip).** Once per run, if older turns were
  summarised to fit the context window, a line such as `Chat history
  compressed: summarized older turns, kept the last 10 messages` appears.
  If the summary was not ready in time, you'll see `Chat history over
  budget; summary not ready, kept the last 10 messages` instead.

Three actions are available at the pane footer:

- **Copy line** — long-press a single console row.
- **Copy all** — copies the full plain-text dump of the active tab to
  the clipboard, regardless of which filter chip is active.
- **Clear console** — wipes the on-screen log baseline for the current
  session after a destructive confirmation dialog. The underlying chat
  messages and the saved pipeline trace are not affected. It is reachable
  from the console header only; the chat overflow menu no longer carries a
  second copy of it.
- **Search** — appears on the **Logs** tab only, because the inline search
  field it opens filters log lines. Vars and Traces have no search field,
  so they show no magnifier.

The active tab is persisted between runs, so the pane re-opens to the
tab you used last. The pane auto-scrolls to the latest event as long as
you are pinned to the bottom; if you scroll up to read older events,
auto-scroll pauses so you do not lose your place.

### Trace replay after reopening a chat

The console is no longer wiped when the app restarts. The execution
trace of every run — the console log plus each node's input/output —
is saved to the encrypted local database as the run executes, and
opening a chat replays the trace of its active (or most recent) run
into all three tabs. A run that executed while the app was closed is
therefore fully inspectable afterwards: what the agent did, which
tools it called, and what every node received and produced. If the run
is still executing, live events continue seamlessly below the replayed
history. **Clear console** keeps working on top of a replayed trace —
cleared rows stay hidden until you switch sessions or send a new
message.

### Sub-pipelines in the console

When a pipeline calls another pipeline (a **Pipeline** node), the
sub-pipeline's work is no longer hidden inside a single "Pipeline: 40 s"
line. Its console log, variables and trace spans appear **indented**
under the calling node and prefixed with the sub-pipeline's name (for
example `[Translator] ▶ LITE_RT`), so you can read a nested run as a
hierarchy — both live and when replaying a finished run. Each level of
nesting adds one step of indentation.

Three run-wide limits span the whole call tree:

- **Step limit.** The step ceiling is shared across the parent and every
  sub-pipeline it calls, so a composition cannot loop forever by nesting.
  If it runs out deep inside a sub-pipeline, the whole stack pauses and the
  question appears in the chat like any other — continuing resumes the nested
  run in place.
- **Token limit.** Charged against the same shared allowance, on the same
  whole-tree basis.
- **Approvals and questions.** A tool approval or clarification raised
  *inside* a sub-pipeline surfaces its card in the chat exactly like a
  top-level one, and answering it continues the nested run in place.

If the app is killed (or you step away from a background approval) while
a sub-pipeline is mid-run, **Resume** restores the entire stack: the
parent pipeline fast-forwards through the work it already finished, then
continues the sub-pipeline from where it stopped — neither the parent
nor the child re-runs a node that already completed.

### Reopening a chat while a run is in flight

Closing the chat — or the whole app UI — no longer disconnects you from
a run that is still working. When you open a session, the app checks
the persistent run record and reattaches accordingly:

- **Run still executing** — the chat reconnects to the live run
  without restarting it: the generating indicator returns, streaming
  continues, and the console picks up where the replayed trace ends.
- **Run waiting on you** — a pending tool approval or clarification
  question is restored as its card in the message stream, so a request
  raised while you were away is never lost behind a spinner.
- **Run finished in the background** — the final answer is already in
  the conversation and the console holds the full trace; nothing
  restarts.
- **Run interrupted** — if the process died mid-run (battery
  optimisation, memory pressure, swiping the app away), the chat shows
  a **Run interrupted** card naming the node the run stopped at, with
  **Resume** and **Discard** buttons. *Discard* dismisses the run for
  good. *Resume* continues from the checkpoint: nodes that already
  finished replay their recorded results instantly (no re-inference),
  and execution restarts at the first unfinished node. A tool call the
  run died on is never replayed — the tool may or may not have acted —
  so it runs again from scratch, asking for your approval as usual.
  Resume is offered while the interruption is younger than the
  **Resume window** setting (default 48 hours) and the pipeline has not
  been edited since the run started; an edited or deleted pipeline
  means the task can only be restarted from the beginning.

Conversations with a run still working in the background are easy to
spot: their row in the thread drawer shows a small in-progress
indicator next to the title.

### Approving a tool call

If the agent needs your approval to run a sensitive or destructive
tool, the chat shifts into the **HitlConfirm** state — a card appears
inline in the message stream with the tool name, the typed arguments,
a colour-coded risk pill (`READ` / `SENS` / `DEST`), and **Approve** /
**Deny** buttons. Destructive tools also require typing **yes** into a
confirmation field before the **Approve** button is enabled.

If that chat is not on screen when the request comes in, it also
arrives as a notification. For read-only and sensitive tools it offers
**Approve** and **Deny**. Destructive tools never execute from a
notification — it offers **Deny** and a **Review in chat** link to the
regular typed-confirm card.

Each notification and each card answers only the request it shows. If two
runs are waiting in the same chat, each has its own notification, and a
button for a request that has already been answered or stopped does nothing.

An unanswered request does not fail the run. When the live waiting
window elapses — say a scheduled run hits a sensitive tool at 6 a.m. —
the run parks in a persistent waiting state and an ongoing notification
becomes your way back to it; swiping the notification away simply
re-posts it. Its buttons work even if the app process has since been
killed: the run resumes from its checkpoint and the tool call is
re-validated before your stored decision is applied. A **Deny** stays
a deny even if you change **Approve tool calls** while the run waits.
Clarifying questions park the same way under an **Agent needs
your input** notification that deep-links back to the chat. Parked
requests expire after the **Settings → Background & triggers → Approval window**
period (default 24 hours); an expired run fails with *Approval window
expired*.

---

## Background tasks

A pipeline run does not need the screen. This section is the
one-stop summary of what happens when the app goes away mid-run; the
chat-level details live in [Chats](#chats).

### What happens when you close the app during a run

- **Backgrounding the app** keeps the run alive: a foreground service
  (or, for scheduled tasks, the worker itself) holds the process while
  inference continues, with a status notification. That notification is
  shown **only while the agent is actively working** and is removed on
  its own once the run settles — it does not linger in the shade after
  the agent has finished. Tap it to jump back into the app.
- **Process death** (battery optimisation, memory pressure, swiping
  the app away) cannot be survived in place — but every run writes its
  progress to the encrypted database as it executes. On the next app
  start the run is detected and marked **interrupted**, and its chat
  shows a **Run interrupted** card with **Resume** / **Discard**.
  *Resume* continues from the checkpoint — finished nodes replay their
  recorded results instantly, only unfinished work re-executes (see
  [Reopening a chat while a run is in flight](#reopening-a-chat-while-a-run-is-in-flight)).
- **A run that finishes in the background** lands its answer in the
  conversation as usual; open the chat and the full console trace is
  there to replay.

#### Battery settings decide whether any of this happens

The first bullet above describes what happens when the system lets the
app keep running. Whether it does is not the app's decision. On a device
with the default battery setting, leaving the app can end a run within
seconds — measured at roughly **ten seconds** on the reference device,
after which the process is gone and the run comes back as *interrupted*
on the next start. This is the platform doing what it is designed to do,
not a defect, but the effect is the same for you: background work does
not finish.

To let it finish, exclude the app from battery optimisation. On stock
Android that is **system Settings → Apps → Knotwork → Battery →
Unrestricted**; the exact path differs by manufacturer, and phones with
an extra vendor layer (Samsung, Xiaomi, OnePlus and others) usually need
the app taken out of a separate "sleeping apps" list as well. The app
never asks you for this and cannot grant it to itself — if long
background runs, scheduled tasks or triggers matter to you, it is worth
setting once by hand.

### Notifications

| Notification | When | Actions |
|---|---|---|
| *Agent is working* (only while working) | While a run executes in the background; auto-removed when it finishes | Opens the app |
| *Approval required* | A sensitive/destructive tool call awaits your decision | **Approve** / **Deny** (destructive: **Deny** / **Review in chat**) |
| *Agent needs your input* | A clarification question is waiting | Deep-links into the chat |
| *Task completed* / *Task failed* | A scheduled task finished | Opens the conversation the result landed in |
| *Still running* (optional ping) | A long backgrounded run is still going | Opens the app |

Approval and clarification requests survive process death: the staged
request is stored persistently, and acting on the notification resumes
the run from its checkpoint even if the app was killed in between. An
unanswered request waits for the **Approval window** setting (default
24 hours, Settings → Background & triggers → Advanced) and then fails the run with
*Approval window expired*.

### Run history and retention

Every run's record and trace are kept in the encrypted local database
so chats can reattach, traces can replay, and interrupted runs can
resume. To keep that history from growing forever, a daily maintenance
pass (it runs while the device is charging and idle) deletes old
finished runs and their traces:

- **Keep run history per chat** (Settings → Privacy, default 20) — each
  conversation keeps its most recent runs; older finished ones are
  removed.
- **Run history max age** (Settings → Privacy, default 30 days) —
  finished runs older than this are removed regardless of count.

Runs still waiting on an approval or clarification are never removed by
retention — they stay until you respond or their approval window
expires. Deleting a conversation removes its runs and traces
immediately.

---

## Entry surfaces

Beyond opening the app, four OS surfaces let you reach the agent from
elsewhere on the device. Every surface is **off by default**: it does
nothing until you point it at a pipeline, so the agent never acts on
shared content or a tap until you have opted in.

### Sharing into the agent

The app appears in the Android share sheet for **text and images**.
Share something and pick the app: it runs your chosen *share pipeline*
over the shared content and opens the chat so you can watch the run.
A shared image is attached exactly like a composer attachment (the local
model reads it; it never leaves the device), and it gets the same checks
first: if the share pipeline starts with a cloud step, the active model
can't read images, or no on-device step would see the picture, the app
says so and runs nothing. If you have not bound a share pipeline yet, the
app opens with a reminder instead of running anything.

**Other apps can hand content to it directly**, not only through the share
sheet. So pick a share pipeline you are comfortable running on text you did
not write. At most **30 shares an hour** start a run; past that the app says
so and runs nothing until the hour moves on. The limit is one for every app,
since the app cannot tell who sent a share. Details are in
[SECURITY.md](../SECURITY.md#automation-triggers-and-entry-surfaces-background-execution).

By default every share lands in one running **Shared** chat, so
everything you send accumulates in one place — new shares are appended to
it rather than starting a fresh chat each time. Turn this off with
**Settings → Background & triggers → Keep shares in one chat** to get a
new, auto-named chat per share instead. Either way, shares never touch
the chat you were already in.

### Launcher shortcuts

Long-press the app icon for quick actions:

- **New chat** — opens a fresh conversation.
- **Pipelines** — opens the pipeline library.
- **Recent chats** — the app also keeps a couple of dynamic shortcuts to
  your most recently used conversations, so you can jump straight back in.

### Quick Settings tile

Add the **Run pipeline** tile to your Quick Settings (notification
shade). One tap runs your chosen *tile pipeline* in the background — even
when the app is closed — and posts a notification when it finishes, with
a tap-through into the resulting chat. While no pipeline is bound the tile
is inactive and a tap opens the app so you can set one up. Right after you
bind a tile pipeline in Settings, Android offers to add the tile for you.

### External automation (Tasker, MacroDroid, adb)

Another app on the device can ask Knotwork to run a pipeline for it — a
Tasker or MacroDroid profile, or a shell script over `adb`. The other app
decides *when*; Knotwork does the language-model part of *what*.

This one is different from the three above: a request needs no screen, so it
can arrive from an app in the background and run with nothing shown. So it is
switched on deliberately, in two steps:

1. **Settings → Background & triggers → External automation** — the switch
   raises a dialog spelling out what you are agreeing to: any app on the
   device that can send a broadcast may ask, only the pipeline you pick can
   be run, your tool approvals still apply exactly as they do for the app's
   own background runs, a run can spend your cloud API key, and one tap
   turns it all off again. The switch only moves once you confirm; turning
   it back off is immediate and asks nothing.
2. **Pipeline other apps may run** — pick the one pipeline. This is an
   allowlist, not a default: a request naming anything else is refused
   rather than redirected to your pick. Until you pick one, the surface is
   on but inert — every request is refused, and the row says so in amber.

**Request journal.** Every inbound request is recorded, accepted or
refused, with the reason in plain language — "It asked for a pipeline other
than the one you picked", "Too many requests in the last hour". A refusal is
usually a profile that needs fixing rather than a fault in the app, and the
journal is how you tell which. It also distinguishes the two refusal kinds:
**Refused** means sending the same request again gives the same answer,
**Held back** means it can be accepted later.

Two details worth knowing:

- **The sender is not verified.** Android only tells a receiving app who
  sent a broadcast when the *sender* opts in, which automation apps do not.
  So a row shows the app the caller asked to be answered on, marked
  *unverified* — it is a claim, not a confirmed identity.
- **A repeated refusal collapses onto one row** with a count (`×43`), so a
  misconfigured profile looping every minute reads as one recurring problem
  instead of forty-three separate ones.

The journal screen also carries a **How another app calls this** block with
the action string and the extra keys, so you can write the profile without
leaving the app. Full details, including the callback your profile can
receive back, are in
[external-automation.md](external-automation.md).

**Taking the journal with you.** Two actions in the screen's top bar hand you
the whole request journal as a JSON file: **share** it (the system share
sheet — mail, a chat, a notes app) or **save** it to a folder you pick. Use
either when you are reporting a problem with a profile: the file says what
actually reached the app and what it decided, which is the one thing the
automation app on the other side cannot tell you.

The file holds the journal and nothing else — the same rows the screen shows,
with the request id, the action, the pipeline the caller named, the packages
involved, the decision and its reason. Nothing about the *run* a request
started travels with it: not the message it was given, not the answer it
produced. Neither action touches the network; the file goes where you send
it, and nowhere else.

### Choosing a pipeline per surface

Bind a pipeline to a surface in either place:

- **Settings → Background & triggers** — the *Pipeline for sharing*,
  *Quick Settings pipeline* and *Pipeline other apps may run* rows open a
  picker (choose a pipeline, or **None** to switch the surface back off).
- **Pipeline library** — a pipeline's row menu (⋮) has **Use for sharing**
  and **Use for Quick Settings tile**. The bound pipelines are easy to spot
  at a glance: the library row carries an outlined **SHARE** pill when it is
  the share-target pipeline and an outlined **TILE** pill when it is the
  Quick Settings tile pipeline (next to the filled **DEFAULT** pill).

If you delete a pipeline that a surface was using, that surface simply
turns off again until you bind another.

---

## Triggers

A **trigger** runs a pipeline on its own when a condition is met — a time
of day, a repeating interval, the device starting to charge, or a network
connection — and drops the result into a chat in the background. Open
**More → Triggers** to manage them.

The list shows each trigger with a plain-language condition line ("Every
day at 08:00", "When charging connected", "When Wi-Fi connects"), its
bound pipeline, and a switch to enable or disable it on the spot. The
first time you open the screen, an empty state explains the model —
*trigger → background run → result in chat* — with a button to create your
first one.

### Creating or editing a trigger

Tap **New trigger** (or a row to edit one). The editor has:

- **Name** — a label for the list.
- **When** — the condition type:
  - **Interval** — repeat every 15 min, 30 min, 1 h, 6 h or 24 h, or a
    **Custom** value in minutes or hours. The minimum is 15 minutes:
    background runs are batched, so the system may defer a run by a few
    minutes.
  - **Daily** — a time of day (24-hour).
  - **Charging** — fires once **the moment you plug in** (and re-arms when
    you unplug). This one is event-driven, so it runs right away rather
    than waiting for a poll.
  - **Network** — fires on connecting; flip **Wi-Fi only** to ignore
    mobile data. Under **Only on these Wi-Fi networks** you can add one or
    more network names (SSIDs) so the trigger fires *only* on those — e.g.
    just your home or office Wi-Fi. Leave it empty to fire on any Wi-Fi.
    Adding a name asks for **location permission** the first time (Android
    ties the Wi-Fi name to location); if you decline, the trigger stays
    saved but won't fire until you grant it. The names are used only for
    the match on your device — they are never uploaded.
- **Run this pipeline** — bind the pipeline to run. Choosing **None**
  leaves the trigger inert (saved but it fires nothing).
- **Input prompt** — the message handed to the pipeline each time it
  fires.
- **Enabled** — on/off. Off means saved but it won't fire; you can also
  toggle this from the list.

An **unbound** trigger (no pipeline) is always inert and shows "No
pipeline — tap to bind" with a disabled switch — a trigger fires nothing
without a pipeline. Saving, enabling, disabling or deleting a trigger
takes effect immediately, without waiting for the next app launch. If you
delete the bound pipeline, the trigger is disabled automatically.

### How soon a trigger fires

**Charging** triggers are event-driven — plugging in fires the run within
seconds, even if the app is closed. Every other trigger is checked on a
background schedule of its own, so it fires at the next check after its
condition is met, not the instant it changes:

- **Interval** triggers are checked on their own interval (never more often
  than every **15 minutes** — the platform minimum for background work).
- **Daily** triggers wake once a day, timed to the hour you picked.
- **Network** triggers are checked on a **15-minute** poll, so a connection
  that comes and goes between two checks can pass unnoticed.

When the device is idle or under aggressive battery optimisation the system
may defer any of these further; keeping the app excluded from battery
optimisation makes background runs more punctual. The evaluation journal on
each trigger records every check, so a late or missing run is diagnosable
after the fact — see below.

### Results and notifications

Each trigger owns its **own chat session**, named after the trigger. The
first time it fires it creates that chat, and every later run lands in the
**same** conversation, so a recurring trigger keeps one running log instead
of scattering a new chat each time. (If you delete that chat, the next fire
quietly starts a fresh one.) The run streams its work and final answer into
the session exactly as if you had typed the prompt yourself.

While a run happens in the background you get up to three notifications,
all on the **"Scheduled task results"** channel and gated by the same
**Settings → Background & triggers** notifications toggle:

- **Trigger fired** — when an automation starts a background run.
- **Task completed** — with a preview of the final answer.
- **Task failed** — with the reason.

Tapping any of them deep-links straight into the trigger's chat. If a run
pauses for approval of a sensitive or destructive tool, you get the usual
**approval notification** with **Approve / Deny** actions, so you can let a
background trigger run proceed (or stop it) without opening the app. A
destructive tool is the exception: its notification offers **Deny** and
**Review in chat**, and approving it takes the typed confirmation in the chat.

### Checking what a trigger has been doing

Background automation is invisible by nature: it either happens or it
doesn't, and when it doesn't there is normally nothing to look at. Two
surfaces make it legible.

**Health at a glance.** In the trigger list, every trigger that is enabled
*and* bound to a pipeline carries a badge:

- **Healthy** — nothing is wrong: it is being checked on schedule and the last
  run it started finished cleanly. A trigger you have just created also reads
  Healthy, because nothing is overdue until its first check falls due.
- **Overdue** — the phone has not checked this trigger for noticeably longer
  than its own schedule implies (more than twice the cadence it should be
  checked at). This is the tell-tale of an aggressive battery saver or a deep
  idle state — the trigger is fine, the phone simply isn't waking the app.
- **Last run failed** — the most recent run this trigger started ended as
  anything other than a clean success: a failure, a run the system killed, a
  run you stopped, or one that timed out waiting for your approval. The
  journal entry says which.

Each badge is an icon plus a word, never colour alone, and at very large font
sizes it collapses to the icon while screen readers still announce the full
label. Disabled and unbound triggers carry no badge — they are not supposed
to be doing anything.

**The evaluation journal.** Tapping a trigger opens its detail screen: the
identity header (**When** / **Runs** / **State** with the enable switch),
**Edit** and **Delete**, and the **Evaluation journal** — every occasion this
trigger was evaluated in the background, newest first, grouped by day
(*Today*, *Yesterday*, then the date). Each entry names the surface that woke
it (**Scheduled check**, **Device event** or **Charging check**), the time,
and the verdict:

- **Fired** — a run started. The entry is completed later with how that run
  ended: **Completed**, **Failed**, **Stopped by the system** (the app's
  process was killed mid-run), **You stopped it**, **Timed out waiting for
  approval**, or **Stopped by a safety limit** — a working guard, so it does
  not count against the trigger's health indicator. That last one is not the
  moment the ceiling was reached: a background run that reaches one **pauses and
  asks** first (see [When a run reaches a limit](#when-a-run-reaches-a-limit)),
  and settles here only when you answer *Stop the run* or the response window
  closes with the question unanswered. Until the run settles it reads
  **Running…**. If the run stopped to ask you something, a second line says
  what became of the request —
  **You approved it**, **You denied it**, **You answered it**, **Waiting for
  your response**, **No response before the window closed**, or **The request
  never reached you** — and adds *from the notification* (or *in the
  notification shade*, while it is still waiting) when the answer had to come
  back from the shade rather than from a screen you already had open.
- **Didn't run** — with the reason in plain language: *"The condition wasn't
  met at 07:15."*, *"It had already fired for this window."*, *"The trigger
  was turned off."*, or *"No pipeline is bound."*
- **Re-armed** — an event condition (charging, network) fell away again, so
  the one-shot latch reset and the trigger is ready for the next edge. No run.

If the trigger looks overdue, the detail screen leads with a banner naming
the moment it was last checked and the likely cause.

Entries are kept for **30 days** (with a ceiling on the total number) and age
out in the same nightly maintenance pass as run history. Like everything else
on this screen, they are stored in the encrypted on-device database and never
leave the phone unless you send them somewhere yourself.

**Taking the journal with you.** The Triggers list — not a single trigger's
detail — carries two actions in its top bar: **share** the evaluation journal
as a JSON file, or **save** it to a folder you pick. The file covers *every*
trigger, because the question a journal answers is usually about the gaps
("was there a day nothing was checked at all?"), and a per-trigger file could
not answer it.

It is the right thing to attach to a bug report about background reliability,
and it is also how you get the journal off a phone that has been running a
scenario for a week without you opening the app. The file carries the journal
rows and nothing else: which trigger, when, what woke the check, the verdict,
and how the run it started ended. Neither action touches the network.

**Why this is worth trusting.** Every evaluation writes exactly one entry at
the moment the decision is taken, before anything else happens — so a trigger
that didn't run is either explained by an entry, or there is no entry at all,
and that gap is itself the answer: the system never woke the app to check.
Distinguishing the two is the whole point, and **Overdue** exists to flag the
second case. Journal writing can never disturb the automation it describes: a
failure to record is logged and dropped, never allowed to abort a run.

That reasoning extends to approvals. A run that paused for your approval and
got it ends as a plain **Completed**, which on its own is indistinguishable
from a run that never needed asking — so the entry records the request too:
that it happened, whether it had to wait in the notification shade, and how it
was settled. An approval nobody answered before the window closed is
distinguished from one that never reached you at all.

This first wave covers only **low-sensitivity** conditions (time,
charging, network) that need no dangerous permission; notification,
location and SMS triggers are intentionally deferred.

---

## Pipelines

A **pipeline** is the recipe the agent follows when it processes a
message. It is a graph of typed nodes (for example, a local-LLM call,
a cloud-LLM call, a tool invocation, an output formatter) connected
by arrows that describe the flow of data. If you come from Tasker, n8n,
or Zapier, this is the thing those tools call a *workflow* — the app
says "pipeline" everywhere, including in the menus.

You do not need to design a pipeline yourself — the app ships with a
sensible default — but the orchestrator lets you tweak how the agent
thinks, what tools it can use, and what the final answer looks like.

### Pipeline library

Open the **Pipelines** screen to see every pipeline saved on the
device. Sending a message uses whichever pipeline is bound to the
current chat, or the pipeline marked **DEFAULT** if the chat has no
explicit binding; **SHARE** and **TILE** mark the pipelines bound to the
share sheet and the Quick Settings tile. No row is marked as the one
open in the editor — which pipeline the editor last showed is not a
setting, and it changes nothing about what runs.

On the very first launch the app seeds a **showcase** graph into your
library, materialised from the bundled `showcase_full_agent` preset. It
is the default pipeline unless you pick a scenario during onboarding — in
which case that scenario's pipeline is added and becomes the default
instead, while the showcase stays in your library to explore. It triages
each message into
**chat**, **factual** or **task** and runs a tailored branch: a quick
on-device reply for chat; a Wikipedia-grounded lookup for factual
questions (with a complexity gate that can break hard questions into a
small research loop); and a plan → subtask-loop → synthesis flow for
actionable tasks, including a human-in-the-loop clarification step when
a subtask needs your input. The task loop is **composed**: each subtask
runs as one of four bundled sub-pipelines (Clarify / Lookup / Act /
Process) called through Pipeline nodes, so the showcase seeds a parent
pipeline plus those four sub-pipelines into your library. It runs
entirely on-device. Everything is an ordinary pipeline: edit, duplicate,
rename or delete any of them like any other (see
[Composing pipelines](#composing-pipelines-the-pipeline-node)).

Tap the `⋮` button on a row to see the per-pipeline menu:

- **Load in editor** — open this pipeline in the visual editor.
- **Rename** — open a dialog titled **Rename pipeline** with a
  **Name** field.
- **Duplicate** — create a copy with `(copy)` appended to the name.
- **Delete** — remove the pipeline after a confirmation dialog. Any
  pipeline can be deleted, including the last one and the one the editor
  last showed; the editor then opens the next pipeline in the library, or
  an empty one when none is left.
- **Set as default** — make this pipeline the fallback for any chat
  that has no explicit binding.
- **Save as preset** — package the pipeline as a reusable template
  with a name, description, category and tags. Saved presets show up
  under **More → Library** and in the **+ From preset** picker.

Tap the **+** button (floating action button) to expand the two-way
speed-dial:

- **+ New pipeline** — create a blank pipeline. New pipelines start as
  a minimal `INPUT → OUTPUT` graph so that they are valid immediately.
- **+ From preset** — opens the **Pipeline presets** picker with a
  **Bundled** tab (curated starter presets that ship with the app) and
  a **Mine** tab (presets you have saved yourself). Use the category
  chips to narrow the list, tap a card to see its graph preview, then
  hit **Use this preset** to spawn a fresh pipeline from the template
  and jump into the editor.

### Pipeline presets

A **pipeline preset** is a reusable template of a whole graph. Two kinds
exist:

- **Bundled** — a curated starter set that ships with the app, read-only.
  Three of them are the scenarios offered during onboarding — **styled
  translation** (keeps register and dialect, fully on-device), **share
  handler** (anything you share becomes a structured note in your
  workspace inbox) and **virtual companion** (a private companion that
  adapts its tone to your mood). Alongside them ship two end-to-end
  showcases — the **full agent** and **research to file** (a question
  turned into a Wikipedia lookup, distilled, written out as a Markdown
  report under `reports/` in the agent workspace, with the saved path
  returned) — and the building-block templates you would start your own
  pipeline from: local-only Q&A, cloud assist, routed local/cloud,
  clarify-then-act, tool-using agent and multi-step research.

  Each bundled preset brings its own starter prompts, so a pipeline
  spawned from one opens with quick actions that actually fit it.
- **Mine** — presets you create yourself with **Save as preset** (from a
  pipeline's `⋮` menu). These live in the app's local database.

Spawn a pipeline from a preset with the FAB's **+ From preset** option
(see above), or — when you open the editor on an empty pipeline — tap the
canvas's **From template** button to pick a preset without leaving the
editor. Either way, loading a preset always creates a *fresh* pipeline
with new ids, so the template is never modified. Presets are interchangeable with
the [browser pipeline editor](#browser-pipeline-editor): a bundled preset
can be exported as a `*.preset.json` file (see below) and imported into
the editor, and the editor can export its own `*.preset.json` for import
back into the app.

### Managing presets

Open **More → Library** to manage every pipeline preset. Bundled
presets are read-only — they can be exported as JSON (for example to
import them in the browser pipeline editor) but not renamed or
deleted. Your own presets expose a `⋮` overflow with **Rename**,
**Export JSON** (writes a `*.preset.json` file via the system file
picker), and **Delete** (asks to confirm).

For a marketing-style preview of this screen see
[`docs/images/hero-pipeline-library.png`](images/hero-pipeline-library.png)
(dark variant: [`hero-pipeline-library-dark.png`](images/hero-pipeline-library-dark.png)).

### Binding a pipeline to a chat

When you create a new chat from the drawer, you can attach a specific
pipeline to it. Chats without an explicit binding fall back to the
default pipeline marked in the library.

If no pipeline is marked as default (for example after deleting the one
that was), an unbound chat refuses to run and shows an explicit error
instead of silently picking an arbitrary pipeline from the library. To
fix it, mark a default via the pipeline card's `⋮` menu in the library,
or bind a pipeline to the chat directly. If a chat's bound pipeline has
been deleted, the chat is rebound to the default and a brief
notification tells you the selection moved.

### Visual editor

Loading a pipeline opens the **Pipeline editor**, framed so the whole graph
is on screen — zoomed out as far as that takes, but never past 100 %, so a
small pipeline opens at its natural size. The editor surface is an infinite
pan / zoom canvas with the following gestures:

- **One-finger drag on empty canvas** — pan the viewport.
- **Two-finger pinch** — zoom (`0.4×–2.0×`).
- **Drag a node card** — move the node; it snaps to a 24 dp grid on
  release with a soft spring settle.
- **Add a connection** — press and hold one of the node's **bottom
  port dots**, then drag toward another node and release when the
  finger is over its **top port dot**. For multi-output nodes
  (`If` → True / False, `Queue` → Item / Done, `Eval` → Pass / Retry /
  Fail, `Router` → one port per declared class) the dot you grabbed
  determines which branch the edge represents.
- **Delete a connection** — two paths:
  1. **Single-tap** the edge → it highlights in accent colour; then
     choose **Delete selection** in the overflow menu. Or
  2. **Long-press** the edge → confirmation dialog "Remove
     connection?" opens; tap Remove.
  Both paths are undoable with **Undo** in the overflow menu.
- **Tap a node** — select it (single-select mode).
- **Tap a selected node** — opens its **configuration sheet**
  (`NodeConfigSheet`) so you can edit the per-type properties.
  Equivalent to "double-tap the node".
- **Long-press a node** — enter multi-select. Subsequent taps toggle
  membership; the top bar swaps for a count + Cancel / Delete cluster.
- **Long-press the empty canvas** — opens a **radial quick-add menu**
  with one labelled tile per node type. Picking a tile spawns the node
  at the long-press point and immediately opens its configuration sheet.
- **Toolbar** — back arrow, the inline-editable pipeline name with a
  one-line status under it (`Editing · nodes N · edges N`, or the issue
  count when saving is blocked), and an overflow menu on the right. Every
  editing command lives in that menu: **Save pipeline**, **Undo**,
  **Redo**, **Rename node…**, **Delete selection** (the selected edge if
  there is one, otherwise the selected nodes), **Save as preset…**,
  **Auto-layout**, **Mini-map**, **Show grid** / **Hide grid**,
  **Pipeline cookbook**, **Find node…** and **Paste**.
  Auto-layout re-arranges nodes via a Sugiyama-style hierarchy
  (longest-path layering + median crossing reduction) so the graph
  reads top-to-bottom. **Save pipeline** is the action that writes your
  edits to disk.
- **Zoom rail** — on the right edge of the canvas: zoom in, zoom out, and
  **Fit pipeline to view**, which frames the whole graph. The editor does
  the same framing when it opens a pipeline, never zooming past 100 %.

  Beside the node count sits an **Unsaved** marker — a coloured dot and the
  word — shown from the moment the editor holds anything that is not in
  storage. Leaving while it shows, by the back gesture or the back arrow, asks
  **Leave without saving?** and offers **Save and leave** or **Discard**.

  Two details decide whether that marker is worth trusting. A save refused by
  validation leaves the work marked unsaved — because it is: nothing was
  written. And an edit made while a save is in flight stays unsaved rather than
  being counted as stored. What the marker compares is the whole pipeline,
  including its name, its sample prompts and its memory query, not just the
  nodes and the edges.

  The editor has no Run button: it composes pipelines, it does not
  execute them. You run a pipeline by binding it to a chat and sending a
  message, or from a trigger / scheduled task — those paths report
  progress in the chat console, which the editor cannot show.

At the bottom of the screen sits the **validation bar** — it lists
pipeline errors (missing input, dangling output, cycles, empty context,
…). Tapping a row centres the canvas on the offending node and selects
it. The bar collapses to a single-line "Pipeline is valid" when there are
no errors. Outstanding errors block saving, and the toolbar subtitle
says so.

A pipeline exported from the standalone browser editor is loaded with
**Import JSON**, in the footer of the pipeline library rather than in the
editor (see [Importing into the app](#importing-into-the-app)).

### Node configuration sheets

Reach a node's per-type configuration by either:

- **Tapping a node you've already selected** (single-tap → select,
  tap again → open the sheet); or
- **Picking the node from the radial quick-add menu** — newly added
  nodes open the sheet immediately.

The sheet is a modal bottom-sheet. Every node type — Input, Output,
LiteRt, Cloud, IntentRouter, IfCondition, Clarification, Tool,
Decomposition, QueueProcessor, Evaluation, Summary, Pipeline, Skill —
has its own form, with inline validation that disables Save until every
required field is filled. Each form's fields, their defaults, and
**which of them actually change a run** are listed per node type in the
[pipeline cookbook](cookbook.md) — worth reading before you spend time
on a field, because some are stored with the pipeline and never
consulted while it runs.

By default an **Output** node has **no system prompt** and simply forwards
the previous node's text to you *verbatim* — what the last model or tool
produced is exactly what you see, with no extra processing. This keeps simple
pipelines clean. If you *do* want the Output node to re-format the reply, give
it a system prompt (there is a ready-made "Formatter" template you can pick in
its config sheet): it then runs one more model pass over the incoming text and
returns the formatted result instead. Even then, its default context is just the
**previous node's output** — not the chat history, the original task, memory or
tool results — so it re-formats only that reply. Enable the other context blocks
in the node's config if a formatter genuinely needs them.

For the **IntentRouter** node, the Classes section in its config
sheet lets you grow / shrink the class list: each row has a small
**−** button to remove it (disabled below the 2-class minimum), and a
**+ Add class** button under the list creates a new empty class row
(disabled above the 6-class maximum). The new class shows up as an
additional outbound port on the node card immediately on Save.

#### Why the same question can take a different route

In a pipeline that routes with an **IntentRouter**, asking the same thing
twice can send it down two different branches — kept on the device one
time, handed to a cloud provider the next. That looks like the app being
unpredictable, and it is worth knowing that it is not random.

The router is a model deciding which class your message belongs to, and
by default it is shown the **chat history** as well as the message
itself, because a router judging "is this a follow-up?" needs to see what
came before. So the same sentence genuinely is a different question in an
empty chat than it is after ten turns about something else, and the
router can reasonably classify it differently. Cloud nodes are set up the
same way for the same reason.

If you would rather a router decided on the message alone, open its
configuration sheet and turn **Chat history** off under **Input Data**.
The classification becomes repeatable, at the cost of the router no
longer understanding follow-up questions. Starting a fresh chat has much
the same effect without changing the pipeline.

The nodes that produce a structured result — **IntentRouter**,
**IfCondition**, **Evaluation**, **Decomposition** and **Tool** — run their
output through a validate-and-repair step: if the model's first reply is
malformed, the agent hands it back the bad output and asks it to fix itself
(up to a small repair budget, 2 attempts by default) before falling back.
Each repair attempt shows on the console as `Output repair 1/2 for node …`,
and a node that exhausts its budget logs an Error and takes its default
path (or, for Decomposition, fails the run rather than continue on a
corrupt subtask list).

Each of these nodes also carries an **Engine** selector — choose whether it
runs **On-device** (the default) or against a configured cloud provider. A
cloud provider that natively guarantees JSON output lets a JSON-producing
node skip the repair loop; if the chosen provider is unavailable at run time
the node falls back to on-device and notes it on the console.

### Composing pipelines (the Pipeline node)

A pipeline can call **another pipeline** as a single step. Add a
**Pipeline** node, open its configuration sheet, and pick the
sub-pipeline to run from the **Target pipeline** picker — the list is
every other saved pipeline. When the node runs, its input becomes the
sub-pipeline's message, and the sub-pipeline's final answer becomes the
node's output, so a composition reads like a function call between
pipelines. This is the building block for reuse: describe a reusable
sub-flow once and call it from several pipelines instead of copying
nodes around. The bundled **Showcase — full agent** is the worked
example — its task loop routes each subtask to one of four bundled
sub-pipelines (Clarify / Lookup / Act / Process) through Pipeline nodes.

A few rules keep compositions sound, all enforced before a pipeline can
be saved:

- **No target, no save.** A Pipeline node with no target selected is a
  validation error, exactly like a dangling connection.
- **No cycles.** A pipeline cannot call itself, directly or through a
  chain that loops back to it. The target picker greys out any choice
  that would close a cycle and tells you which pipeline causes it.
- **Depth limit.** Compositions can only nest so deep (the picker greys
  out a target that would exceed the limit). The same ceiling is
  re-checked while the pipeline runs, so a graph edited after it was
  validated can never recurse without bound.
- **Editing a sub-pipeline.** A sub-pipeline is an ordinary pipeline —
  edit, rename or duplicate it like any other. If you delete one that
  another pipeline still calls, the deletion dialog lists the dependent
  pipelines so you can repoint or remove the reference first.

While a composed run executes, each sub-pipeline appears as its own
**indented span** in the console — see
[Sub-pipelines in the console](#sub-pipelines-in-the-console) for how
nested traces, the shared run limits, approvals raised inside a
sub-pipeline, and resuming across a sub-pipeline boundary all behave.

### Variables in system prompts

Nodes that drive a language model (local or cloud) carry their own
system prompt. Instead of baking dynamic values into the prompt
text, the prompt can reference **variables** with a `$NAME` syntax —
the app substitutes the current value every time the node runs.

Built-in variables:

| Placeholder        | Resolves to                                              |
|--------------------|----------------------------------------------------------|
| `$DATE`            | Current device-local date (`dd MMMM yyyy`).              |
| `$TIME`            | Current device-local time (`HH:mm`, 24-hour).            |
| `$TOOLS`           | The active tools list, one `name — description` per line.|
| `$MODEL`           | The display name of the currently active local model.    |
| `$MEMORY_SUMMARY`  | A numbered list of recent long-term memory entries. The default upper bound is configurable from **Settings → Memory → Memory summary default limit** (1–50). |
| `$LANG`            | The device locale as a BCP-47 language tag (e.g. `en-US`). |
| `$LOCATION`        | The device's coarse region — the locale country code (e.g. `US`). |
| `$USER`            | The display name from your identity card (currently the literal `Anonymous`). |
| `$DEVICE`          | A short `manufacturer model · Android <version>` descriptor. |

When you edit a system prompt in a node's configuration dialog, a
row of chips beneath the prompt field shows every available variable.
Tap a chip to insert the token at the cursor. Unknown placeholders
are kept verbatim and reported in the console as a warning, so a
typo never crashes the run.

Example system prompt:

```
You are a helpful assistant running on $MODEL.
Today is $DATE, local time $TIME.
You have access to the following tools:
$TOOLS

Recent memory:
$MEMORY_SUMMARY
```

To emit a literal `$KEY` (for example, when you want to write
documentation inside the prompt), escape the dollar sign as `\$KEY`.

### Prompt presets

Every prompt-bearing field in a node's configuration sheet has two
small icons next to its label:

- **📚 Library** — opens a picker scoped to the current node's type.
  The picker has two tabs:
  - **Bundled** — curated, read-only prompt templates that ship with
    the app (e.g. *Concise assistant*, *Step-by-step reasoner*,
    *JSON structured output*, *Keyword classifier*, *Dependency-aware
    decomposition*).
  - **Mine** — prompt templates you've saved yourself (see 💾 below).
  Use the search box to filter by name, or tap the tag chips to narrow
  the list further. Every row exposes two actions:
  - **Preview** — renders the prompt with `$VARIABLE` placeholders
    substituted at the current moment so you can see the final text
    before applying.
  - **Apply** — replaces the field's current value with the preset's
    body and closes the picker.
- **💾 Save as preset** — captures the current draft as a new entry in
  the **Mine** tab. You enter a name (max 60 chars), an optional
  description, and optional comma-separated tags. The preset's node
  type is inferred from the field you saved from, so it'll only show
  up in the picker when you open it on a matching node type later.

User presets live in the app's local database; bundled presets ship
with the APK and are never modified.

---

## Browser pipeline editor

The repository ships a standalone HTML editor at
`pipeline-editor.html`. It is a regular single-file web page — no
build step, no server, no extension required.

### Running the editor

1. Clone or download the repository.
2. Open `pipeline-editor.html` in any modern desktop browser
   (Chrome, Firefox, Safari, or Edge).

The page mirrors the in-app visual orchestrator. You can drop nodes
onto a canvas, draw connections, edit each node's parameters, and
review the prompt-variable list — all locally in your browser.

### Exporting to JSON

The editor's top bar has an **Export JSON** action. It produces a
single file describing the entire graph (nodes, connections,
configuration, and the schema version). The same format is used by
the app's import flow, so there is no manual conversion step.

### Importing into the app

1. Move the exported JSON file onto the Android device (USB, cloud
   drive, email — anything that puts it in a place the system file
   picker can reach).
2. Open the app's **Pipelines** screen — the **Import JSON** affordance
   sits in the *From browser editor* footer at the bottom of the
   library list.
3. Tap **Import JSON**. The system file picker opens; pick the file
   you exported from the browser editor.
4. The imported pipeline is saved into the library and becomes the
   one currently open in the editor.

A pipeline file can come from anyone, so a few things are checked on the way
in rather than taken as written:

- **The node settings you see are the ones that run.** A file keeps each node's
  settings twice — once for the run, once for the editor. Opening a node, in the
  app or in the browser editor, shows the copy the run uses, whatever the editor
  copy says. The browser editor reads the file the way the app does, including
  a node's *Input data* switches and provider names in any letter case; a tool
  it has no entry for is shown by name and kept when you save.
- **Unclear settings are refused, not guessed.** An *Input data* switch a file
  leaves out takes that node type's usual setting; one set to anything but on
  or off (`true` / `false`) refuses the file, and so does a provider name the
  app does not know.
  Files saved by an earlier browser editor kept four settings only in the
  editor copy; the browser editor still reads those from there and saves them
  into both.
- **Names stay short.** The pipeline name and each node's label are kept to one
  line of at most 60 characters; anything longer is cut.
- **Starter prompts only name tools the pipeline calls.** The `uses · …` line
  under a starter prompt keeps a tool only if one of the pipeline's Tool nodes
  is set to call it, and a pipeline keeps at most six starter prompts.
- **Files over 8 MB are not read.** That is about three times a bundle of fifty
  of the largest bundled pipelines.

### Moving a whole composition — bundles

A single **Export JSON** / **Import JSON** moves *one* pipeline. If your
pipeline calls other pipelines (through **Pipeline** nodes), those
sub-pipelines are separate files — moving the parent alone would leave
dangling references. A **bundle** solves this: it packs the pipeline
*and* every sub-pipeline it depends on into one file.

- **Export a bundle.** In the pipeline library, open a pipeline's
  overflow menu and choose **Export bundle (with dependencies)**. The
  app walks the pipeline's dependencies, gathers the whole set, and
  writes a single `knotwork-bundle-YYYY-MM-DD.json`. If a dependency
  can't be found the export stops and tells you which one — a bundle is
  only useful if it's complete. In the browser editor the matching
  button is **📦 Export bundle**.
- **Import a bundle.** Use the same **Import JSON** affordance — it
  recognises a bundle automatically and imports every pipeline in it at
  once, reporting how many landed. In the browser editor, **📦 Import
  bundle** adds them to your *Mine* presets.
- **When something already exists.** If a pipeline you're importing has
  the same identity as one already in your library, the app asks what to
  do: **Replace** it (update in place, keeping anything bound to it) or
  **import as a copy** (leave the existing one untouched and add a fresh
  duplicate). This choice appears for ordinary single-pipeline imports too,
  so an import never silently overwrites your work.

  The question names the pipeline **already in your library**, not the name
  written in the file, and lists what runs it: the default pipeline for
  chats, the share target, the Quick Settings tile, requests from other apps,
  automation triggers, chats, and other pipelines that call it. Replace keeps
  all of these pointed at it, so from then on they run the file's steps. When
  in doubt, import as a copy — it changes nothing that already works.

Bundles carry pipelines only — not triggers, tool/MCP settings, prompt
presets, or chat history. Those stay on the device they were set up on.

### Sharing pipeline files: what compatibility you can count on

Every exported file carries a version stamp — `schemaVersion` for a
pipeline or preset, `bundleVersion` for a bundle. Before version 1.0 that
stamp is a **marker, not a promise**: the format may change without a
major bump, and no import-time migration is provided.

What that means when you hand a file to someone else, or open your own
file in a later build:

- **A stamp mismatch does not block the import.** Both the app and the
  browser editor warn you that the file came from a different version
  and let you continue.
- **Continuing is a best-effort import.** The graph loads, but any field
  the importing build does not recognise is dropped — the pipeline can
  come back with part of its node configuration missing.
- **The import now names what it dropped.** The warning lists the exact
  settings it could not read (`nodes[1].config.samplingTopK`, and so on),
  so you can judge whether the loss matters instead of guessing.
- **A matching stamp is not a guarantee that nothing was lost.** The
  format adds new fields without bumping the version, so a file written
  by a newer build can claim the same `schemaVersion` and still contain
  settings this build cannot read. That case used to be completely
  invisible; it is now reported the same way, as a notice after the
  import.
- **So keep the original file.** Naming the loss is not preventing it,
  and re-exporting after a lossy import overwrites the only complete copy
  you had.

From 1.0 onwards the format is a semantic-versioning contract: a breaking
change means a major `schemaVersion` and a migration applied on import.
Until then, treat shared pipelines the way you would treat a config file
from a pre-release tool.

---

## Tools and MCP

Tools are how the agent takes real action — looking something up,
scheduling future work, or delegating a hard subtask to a more
capable model. They are managed from the **Tools** screen.

The screen is two collapsible groups — **Built-in tools** and **MCP
servers** — each headed by the number of rows it holds. Folding a group
away never hides a problem inside it: a collapsed **MCP servers** header
still reports `⚠ 1 disconnected`. The `+` in the top bar adds an MCP
server from anywhere on the screen; when you have none yet, the empty
**MCP servers** group carries a labelled **Add server** button instead.

### Built-in tools

The app ships with the following tools:

| Tool             | What it does                                                                          |
|------------------|---------------------------------------------------------------------------------------|
| **search_tool**    | Looks up a topic on Wikipedia and returns a concise summary. **This tool leaves the device**, and it is the only built-in that does so without asking: the search term the model wrote is sent to `https://<language>.wikipedia.org`, and the tool is read-only, so no confirmation card appears. It is on from the first launch and the seeded pipeline calls it — turn it off with its switch here, or with *Block network from local model*, which withholds it. |
| **schedule_task**  | Schedules a task to run later in the background (one-off or recurring).             |
| **delegate_task**  | Hands a hard subtask to a configured cloud LLM and stores the result in memory. Only appears when at least one cloud provider has an API key configured. |
| **read_file**      | Reads a text file from the agent's private workspace, truncated to a token budget so a long file never overflows the model's context; supports byte `offset`/`limit` paging. |
| **list_files**     | Lists files in the workspace (optionally under a sub-directory) with their size and last-modified time. |
| **find_files**     | Finds workspace files whose path matches a glob pattern (`*.md`, `reports/**`).      |
| **write_file**     | Writes a text file to the workspace. Creating a new file is the default; replacing an existing one needs an explicit overwrite flag, so content is never clobbered by accident. Asks for confirmation before running. |
| **edit_file**      | Makes a targeted change to an existing workspace file by replacing a unique snippet of text. The snippet must match exactly once, so an edit never lands in the wrong place. Asks for confirmation before running. |
| **append_file**    | Adds text to the **end** of a workspace file, creating it on the first call. Existing content is always kept (there is no overwrite), so it is the natural fit for accumulating entries in a daily log or report. Asks for confirmation before running. |
| **delete_file**    | Deletes a file from the workspace. This is irreversible and always asks you to confirm before it runs. |
| **http_request**   | Calls a remote HTTP(S) API (GET/POST/PUT/DELETE). It can only reach domains you have explicitly added to the **Allowed domains** list — until you add one, the tool is hidden from the agent entirely. A GET asks for confirmation unless **Approve tool calls** is set to `Never`; a POST/PUT/DELETE always asks for the stronger destructive-action confirmation. See the warning below before adding a domain. |

Each tool has a switch on the Tools screen. Turn a tool off to hide
it from the agent for the next run; turn it on to make it available
again.

#### Allowed domains for http_request

The **http_request** tool is off by default: it stays invisible to the
agent until you opt a specific destination in. Because a file the agent
reads could contain instructions planted by someone else (a *prompt
injection*), an HTTP tool that could reach any address would be a way to
quietly send your data off the device. The allowlist is the safeguard:

- The agent can only contact a host you have added. Matching is exact —
  sub-domains are not implied, so adding `example.com` does not allow
  `api.example.com`; add each host you need. Any other host — including
  one reached through a redirect — is refused before the request leaves
  the device.
- Public domains must use `https`. Plain `http` is allowed only for
  local addresses (for example a home Ollama server), and only after you
  have approved that specific address — see *Adding an MCP server*.
- A request is refused outright if it would carry one of your stored
  provider API keys, so a saved key can't be leaked to a remote host.

To manage the list, open the **Tools** screen and tap **Allowed domains**
under the http_request row. The editor shows the current hosts, lets you
remove any of them, and previews how a typed entry will be stored before
you add it (it normalises `HTTPS://Api.Example.com/v1` to
`api.example.com`, and warns when an entry is invalid or already on the
list). Only add a domain you trust. Adding one lets the agent send data to
it, so treat the list the way you would treat granting an app network
access to a specific site.

For a marketing-style preview of this screen see
[`docs/images/hero-tools.png`](images/hero-tools.png)
(dark variant: [`hero-tools-dark.png`](images/hero-tools-dark.png)).

### Where scheduled task results land

A task created with **schedule_task** executes through the same
pipeline as an interactive message, and its result lands in the
conversation that scheduled it: when the task fires, the prompt
appears as a user message, intermediate steps go to the console, and
the final answer arrives as a regular agent reply. Opening the chat
later shows the exchange as if the run had happened on screen — and if
the chat is already open when the task fires, the run attaches live.

If the original conversation was deleted before the task fired (or the
task predates session binding), the result is delivered to a fresh
conversation named after the task, e.g. *Scheduled: check the news*.

### A task that keeps scheduling itself

An agent asked to "do this and set up the next one" can end up
scheduling its own successor every time it runs. Only ever one task is
queued, so nothing looks wrong in the list, while the agent works
continuously in the background.

Two things bound this. The app refuses to schedule anything more once
too many scheduled runs have already started within the last hour, and
tells the agent so instead of failing silently — a legitimate schedule
(hourly or slower, which is all background work honours anyway) never
comes close to that. And **More → Active tasks → Stop all scheduled
tasks** ends the chain outright, which cancelling the one queued item by
hand cannot do while the run that will enqueue the next one is still
going.

When a scheduled run finishes, the app posts a **Task completed**
notification with the first line of the answer — or **Task failed**
with the reason — and tapping it opens the conversation. The
announcement can be turned off with **Settings → Background & triggers →
Scheduled task results** (on by default).

### Risk levels and human-in-the-loop

Every tool declares a **risk level** that controls whether the agent
can run it on its own:

- **READ_ONLY** — runs without prompting (for example, looking up a
  fact).
- **SENSITIVE** — surfaces an **Approve / Deny** prompt in the chat
  before the call happens.
- **DESTRUCTIVE** — same approval gate as **SENSITIVE**, plus a typed
  confirmation, used for actions that cannot be undone (for example,
  sending a message or deleting data).

When approval is required, the mini-console shows inline
**Approve** and **Deny** buttons. The agent waits for your response
before continuing the pipeline; deny cleanly stops the call without
killing the run.

The **Approve tool calls** control in **Settings → Tools & workspace** lets
you require approval for **every** tool call (`All`), regardless of its risk
level. Choose it if you want to confirm even read-only lookups. `Never` goes the
other way: read-only and sensitive tools run without asking, but a destructive
tool still stops for your approval under every setting. To keep destructive
tools from running at all, turn on **Block destructive tools** — they are then
refused instead of offered for approval.

#### Changing a tool's risk level

Open a tool from the **Tools** screen and its detail page shows a **Risk level**
control. Which form it takes depends on where the tool came from, and that is
the honest distinction rather than a cosmetic one:

- **A tool built into the app** states its level and does not offer a choice.
  Its risk is decided in the code, and the gate reads that before it looks at
  anything you could set — a control here would not act.
- **A tool from an MCP server**, or one discovered on the device, offers the
  three levels. It starts at **Sensitive**, because a tool the app cannot
  inspect is treated conservatively until you say otherwise. Only you know what
  a given server's tool actually does: setting it to **Read only** stops the
  approval prompt for it, and **Destructive** brings it under **Block
  destructive tools** as well as the prompt.

The choice is remembered per tool, and for MCP tools per *server* — the same
tool name on two servers stays two separate decisions, because they are two
different tools. A server's own hints about its tools are deliberately not
consulted: a server that could declare itself read-only could walk straight past
the gate.

#### When two tools share a name

The agent calls a tool by its name, so each name reaches exactly one tool:

- **A tool on your device wins.** If a server offers a tool named like a
  built-in one — `read_file`, say — the agent only ever gets the built-in. The
  server's tool is not offered to it at all.
- **Otherwise the first server wins.** If two servers offer the same name, the
  one listed first on the Tools screen serves it, as long as the tool is
  switched on there. Switch it off there and the next server takes over.

The Tools screen says so under a server tool the agent is not offered: *Not
offered to the agent*, and why. Switching that tool on does not help; rename or
switch off the tool that takes the name instead.

The approval prompt, the risk level and the call itself all come from that one
server. If the call fails there, it is not retried on another server — a call
that timed out may still be running where it was sent. And if a server comes
back between the approval check and the call and takes the name over, the call
is stopped and you are told to run it again, rather than run under a decision
you made for a different server.

### Adding an MCP server

The **Tools** screen groups your own **MCP Servers** — external **Model
Context Protocol** endpoints that publish their own tools — separately
from the built-in catalogue. To add one:

1. Tap **+** in the screen's top bar. (It is always there; the empty MCP
   group also offers the same action.)
2. Paste the endpoint into **Add New MCP Server URL** — the address your
   server prints when it starts, path included, as in
   `https://mcp.example.com/sse`. There is no separate port field: a
   non-default port belongs in the URL.
3. Pick the **transport** to match that endpoint, then tap **Add server**.

The server's tools become available to the agent on the next run.
Remove a server by tapping the trash icon next to its row.

**Unencrypted addresses need your approval.** If the URL starts with
`http://` rather than `https://` and points at a machine on your own
network, the form shows a notice naming the exact address and a single
**Approve unencrypted connection** button. Nothing is sent until you press
it — the app refuses to open the connection, and saving the server is
blocked while the notice is showing. This is not a formality: on an
unencrypted connection anyone else on the network can read what you send,
including any token you set in the Authentication section below.

`http://` to a **public** address is refused outright and cannot be
approved. Approval is remembered per address *and port* — approving
`http://192.168.1.42:8080` does not approve port 3000 on the same machine,
because that is a different server.

MCP connections open lazily — the app only contacts the server when
a tool from it is needed — and they are wrapped in error-handling
so an unreachable server does not crash the chat. If a tool that
relied on an MCP server stops responding, you will see an error
event in the console rather than a silent failure.

#### What the tool count on a server row means

A connected server shows something like `13 tools · ok`. That number is
the list **the server published to this app**, which is not always
everything the server can do.

Some tools only work if the client on the other end supports an extra
conversation the tool needs — asking the client's own model to generate
something mid-call, requesting access to a folder on the client, or
popping a follow-up question at you while the call is in flight. This
app supports none of those yet, and a server that plays by the rules
simply leaves such tools out of the list it hands over. Nothing is
broken, nothing is misconfigured, and the row correctly says `ok`.
Measured against the protocol's own reference server, the app sees
**13 of the 16** tools that server can offer.

So if a tool you know a server has never turns up in the agent's
catalogue while the server itself is healthy, that is the most likely
explanation — the tool needs a client feature the app does not have.
Tools that just take arguments and return a result are unaffected, and
that is the large majority of what MCP servers publish.

The app also sets limits of its own on what a server can publish, because
every tool's name and description goes into the model's instructions on every
run. A tool whose name is not a plain identifier (letters, digits, `_`, `-`
and `.`, up to 128 characters, as the MCP specification asks) is left out; a
description longer than 4 096 characters is cut, with a note saying so; and one
server publishes at most 256 tools and about 256 KB of tool definitions —
anything past that is left out. Ordinary servers are nowhere near these limits:
GitHub's MCP server, one of the largest, publishes 125 tools in about half the
size.

A tool's **result** is limited too, and so is an error message the server
sends instead: past the **Largest tool response** setting (*Settings → Tools &
workspace*, default 1 024 KB) it is cut, with a marker saying so.

#### How long a server is given to answer

Two deadlines apply, and neither is adjustable:

- **Connecting** — 30 seconds for the handshake. A server that accepts
  the connection and then goes quiet fails outright, instead of leaving
  its row stuck on *connecting…* forever.
- **A tool call** — 60 seconds. Past that the call is abandoned.

Either breach is reported as a **failed tool call**, not as a failed
run: the console shows the error, the agent is told the tool did not
answer, and the pipeline continues and can try something else. One
unresponsive server also cannot hold up work in your other chats — a
call that goes quiet for too long is ended rather than left to block the
queue behind it.

#### What the common MCP errors mean

| What you see | What it means |
|---|---|
| *…did not complete the handshake within 30s* | The address answered but never finished the MCP handshake. Usually a wrong URL path, or a server expecting the other transport. |
| *MCP tool … did not respond within 60s* | The call hit the deadline above. The server may still be working on it; nothing was cancelled on its side. |
| *Tool … not found across active providers* | No connected server publishes a tool by that name. Read the tool-count note above before concluding the server is broken. |
| *MCP client is not connected; cannot execute …* | The connection dropped between planning the call and making it. This is deliberately worded differently from *not found*, because the tool does exist — trying again normally reconnects. |
| *Tool … is disabled* | The tool exists but its switch is off on the Tools screen — on every server that offers it. |
| *MCP tool … now resolves to risk level …, not the … its approval check used* | Between the approval check and the call, a server came back and took the tool's name over (see *When two tools share a name*), or the tool's risk level was changed on the Tools screen. The call was not made. Run it again: the check is repeated for the server that serves it now. |

---

## Files

The agent has a small private **workspace** — a sandboxed directory it
can read from and write to via the file tools (`read_file`,
`write_file`, `list_files`, …). The **Files** screen, reached from
**More → Files**, is your window into it: it is where the reports and
exports the agent produces show up, and where you can hand it a file to
read.

### The listing and quota

Files are shown in a flat, path-sorted list — a file saved under
`reports/` reads as `reports/name.md`, with the directory dimmed and the
name emphasised, so the layout is legible without a folder tree. Each
row shows the file's size and when it was last modified; text files and
binary files carry different icons.

The header shows a **quota indicator** — how much of the workspace
budget is used out of its limit, with a fill bar. As the workspace fills
it ramps from neutral to amber (near the limit) to red. If it is full,
a banner explains that the agent's writes are being refused until space
is freed; delete files or raise the limit (Settings → Tools & workspace →
Advanced → Workspace size limit) to recover.

The workspace also holds at most **10,000 files and folders**, however small.
Past that, new files are refused even while the indicator shows room; deleting
files frees the count again. Folders are never shown on their own and never
kept empty: deleting the last file in a folder removes the folder too.

Pull down to refresh the listing.

### Previewing a file

Tap a text file to open a read-only, monospace **preview** in a bottom
sheet. Very large files are shown truncated (the first part only) with a
banner telling you so — use **Save as…** to get the whole file. Binary
files are not previewable; use the row's overflow menu to share, save,
or delete them.

### Taking a file out

From the preview sheet (or a row's overflow menu) you can:

- **Share** — opens the system share sheet. The app stages a temporary
  copy for sharing, so the workspace directory itself is never exposed
  to other apps; the receiving app gets read access to that one copy
  only. The copy is deleted when you delete the file; otherwise, once it
  is more than an hour old, by your next share or the daily clean-up.
- **Save as…** — opens the system "create document" picker so you can
  write the file out to a location of your choice (Downloads, Drive,
  etc.).

In multi-select mode you can share several files at once.

### Putting a file in

Tap **Import** (the button in the quota header, the floating action
button, or the empty-state call to action) to pick a file with the
system file picker and copy it into the workspace for the agent to read.
Imports are subject to the same per-file and total-size limits as the
agent's own writes. A name carrying line breaks, tabs or other control
characters is imported with `_` in their place, and a name longer than 242
bytes (about 240 Latin letters, or half as many Cyrillic ones) is refused. If a file with the same name already exists, you are
asked whether to **keep both** (the import is saved under a numbered
name like `report (1).md`) or **replace** the existing file.

### Cleaning up

Delete a single file from its preview or overflow menu, or long-press a
row to enter multi-select and delete several at once. Deletion asks for
confirmation and is **permanent** — files removed here cannot be
recovered.

### When something does not work

An import refused for its size or the workspace quota, a **Save as…**
that could not be written, a file with no readable preview, and a
multi-select delete that removed only some of its files each say so in a
message at the bottom of the screen. If an action appears to do nothing,
read that message — it names what happened.

**Share** reports the same way. A file that cannot be prepared is left
out of the share, so if only some of a selection can be sent you are told
how many of how many went; if none can, no share sheet opens and the
message says why.

---

## Memory

The agent has two kinds of memory:

- **Short-term memory** — the rolling context window of the current
  conversation. This is what the model "remembers" from one message
  to the next.
- **Long-term memory** — a vector store of past conversations the
  agent can search semantically when a new question is similar to
  something you talked about before.

### Watching the context window

The chat screen's top bar shows the **current / maximum** token
count for the active conversation:

- Grey — normal usage.
- Amber — context window is more than 70% full.
- Red — context window is more than 90% full; older messages will
  start to fall out of scope on the next reply.

If you are seeing red and want to keep the conversation going,
either start a new chat (the old one keeps its history) or clear
the rolling context (described below).

### Browsing long-term memory

Open the **More** tab, tap **Memory**. A stats header sits at the top:
the total number of stored memories, the on-disk size, when memory was
last compacted, and a coloured bar breaking entries down by provenance
(**Auto** / **Compaction** / **Manual**). Below it, a chip row narrows
the list — **All**, **Pinned**, or one provenance at a time, each chip
showing its count — and a **Sort** + date-range pair of dropdowns
re-orders and time-bounds the list. Entries are grouped into **Pinned**,
**Today**, **This week**, and **Earlier**, each row carrying a coloured
provenance accent, a source badge, and its tags.

### Auto-extract from conversations

When **Settings → Memory → Auto-extract from conversations** is on
(the default), the agent automatically tops up long-term memory for
you. Shortly after a reply finishes (a ~30-second quiet period, so a
fast back-and-forth is processed only once), it re-reads the recent
conversation and distils the durable facts you stated — preferences,
events, and relationships — into new memory chunks. Small talk, the
assistant's own wording, and anything not explicitly stated are
ignored, and a fact that closely matches one you already have is
skipped rather than duplicated.

It reads your messages and the assistant's replies — never tool results
(a web lookup, a file the agent read, an MCP server's answer), and never
the messages of an imported chat, which are someone else's words whatever
the file calls them — and no message can pass for a turn of yours, so text
a tool fetched is not put before the extractor as something you said. Text
you share into the app from another app is recorded as your message,
though, so it is read as yours.

Each new chunk is tagged with the fact type it represents (`fact`,
`preference`, `project`, …) and the chat it came from, so you can tell
auto-saved memories apart from ones you saved by hand. You can watch
this happen in the **Console** pane (the **Memory** filter) and review
or delete the results on the Memory screen.

Turn the toggle off if you would rather curate memory entirely by
hand; extraction then stops and existing memories are left untouched.

### Saving a memory by hand

You don't have to wait for auto-extract. Two ways to add a **Manual**
entry: long-press any chat message and choose **Save to memory** (see
[Copying a message](#copying-a-message)), or tap the **Add memory** FAB
on the Memory screen and type the text. Manual entries are embedded with
whichever embedding provider is active, so they are searchable straight
away.

### Searching memory

Tap the search icon to open semantic search. Your query is embedded and
the list is re-ranked by relevance, so a search for "berlin" surfaces the
timezone note even though it never contains that word. Each result shows
its ranking score: how well the entry matches, plus the small bonuses a
recent or pinned entry earns — which is why a strong match can read
slightly above 1.00.

### What the agent recalls, and when

Memory is not read on every step of a reply. A run searches long-term memory
**once**, when it reaches the first step that has the *Long-term memory* input
switched on, and the entries it finds are the ones every later step in that run
sees. Which entries those are is decided in this order:

- **Meaning, not words.** Your question is compared against the meaning of each
  stored entry, so an entry can be recalled without sharing any wording with
  the question — and an entry that repeats your words can still be skipped if it
  is about something else.
- **A relevance gate.** An entry has to clear **Settings → Memory → Similarity
  threshold** to be eligible at all. Nothing below the gate is recalled, and no
  bonus can push an entry through it.
- **Age never disqualifies anything.** An entry that clears the gate stays
  recallable however old it is. Freshness is only a tie-breaker: recent entries
  get a small bonus that fades with **Recency half-life**, so when more entries
  qualify than **Search results (top-K)** allows, newer ones take the slots.
- **Pinned entries are always in.** Pinning skips the gate, adds the largest
  bonus, and sorts the entry first — it is the one way to guarantee a fact is
  recalled.
- **The original wins over a summary of it.** If compaction has merged some
  facts into a summary and one of those facts is still stored word-for-word, the
  original is what the agent is shown. A summary is a paraphrase written by the
  on-device model; when both are available, the exact wording is the safer one.
- **Duplicates don't take two slots.** When two qualifying entries say nearly
  the same thing, only the better-ranked one is recalled (the pinned one if
  there is one), so a reworded copy cannot crowd out a different fact.

You can see the outcome for any run: the console's **Memory** filter carries one
`Memory:` line per run, giving the search key, where that key came from, and the
relevance score of every hit (turn on **Verbose memory logging** in Settings →
Memory for a snippet per hit as well).
Each entry's detail sheet also counts how often it has been recalled. If
something you expected is missing, work down
[Memory search isn't finding an obvious
entry](troubleshooting.md#memory-search-isnt-finding-an-obvious-entry).

### What the agent recalls in a background run

When you talk to the agent, it searches memory with your own message. A
run started by an automation trigger, a schedule, or the Quick Settings
tile has no such message — it runs the prompt the pipeline was built
with, which is the same every time and rarely describes what today's run
is about. Such a run therefore searches with, in order: the search key
the pipeline declares for background runs, otherwise the text arriving at
the first step that uses long-term memory, otherwise the pipeline's
prompt.

You can see which one was used: open the run's console and read the
**MEMORY** line — it names the key and tags it `[pipeline-declared]`,
`[node input]`, or `[user prompt]`. If a scheduled pipeline keeps
recalling the wrong things, declaring a search key for it is the fix;
that key is part of the pipeline document (see the pipeline JSON schema
in the extension guide) and is set in the browser editor's **Memory key**
field or in the file you import.

### Viewing and editing an entry

Tap a row to open its detail sheet. It shows the full text, an
approximate token count, the source ("Auto-extracted" / "Saved
manually" / "Compacted"), which chat it was learned from, when it was
captured, and how often it has been used in replies. From here you can
**pin**, **edit** the text and tags, or **delete** the entry. Pinned
entries float to the top and are never touched by compaction.

### Compact Memory

Tap **Compact** in the stats header to consolidate memory. A dialog
previews the estimated number of chunks removed, bytes freed, and
runtime before you confirm. Compaction merges near-duplicate chunks and
re-summarises the oldest entries; pinned memories are never touched. The
"compacted N ago" line in the header reflects the last run.

The preview is an upper bound, and deliberately so. An entry is deleted only
once the on-device model's summary has been checked to actually represent it,
so facts a summary skipped stay in memory word-for-word, and a summary that
turned out not to describe its group is discarded with the whole group left
intact. A run can therefore free less than it predicted — never more, and never
at the cost of a fact whose only copy it was about to remove. If a merged
summary and the original wording of one of its facts are both in memory (say
after importing an older backup), the original is what the agent is shown.

### Exporting memory

The overflow menu (⋮) on the Memory screen offers **Export memory**,
which writes every chunk — text, embedding, tags, and metadata — to a
JSON file via the system file picker, for backup or migration. The same
**Export** action lives under **Settings → Memory**, and the Memory
screen's multi-select mode can export only the chosen entries.

### Moving memory to another device (export / import)

To carry an agent's memory to a new phone, export it on the old device
and import the file on the new one:

1. On the source device, open **Settings → Memory → Export** and pick a
   location. The file is a self-describing JSON document (it records
   which embedding provider produced the vectors and when it was
   written).
2. Copy the file to the new device (any transfer works — cloud drive,
   cable, messaging app).
3. On the new device, open **Settings → Memory → Import**, select the
   file, then choose a strategy:
   - **Merge** — add the imported chunks to whatever is already there,
     skipping any with an id that already exists. Nothing is deleted.
   - **Replace all** — wipe the current memory (pinned entries included)
     and load the file's chunks. Use this for a clean transfer.

Two things are not taken from the file, whichever strategy you choose:

- **Pins.** Every imported entry arrives unpinned. A pinned entry is
  recalled whenever memory is read, whatever the question, so pinning stays something
  you do entry by entry — the import dialog tells you how many entries the
  file had pinned, and you can pin them again on the Memory screen.
- **Dates in the future.** An entry dated later than the moment you import
  it is dated to the import instead, so it ages like any new entry.

A file holding more than 20,000 entries — the most memory can be set to keep —
is refused whole. So is a file too large for the device to read in one go: the
limit is a quarter of the memory the app may use, and the message names it.

If the file was exported with a **different embedding provider** than the
one the new device is using, the app shows a notice and re-computes the
affected embeddings automatically in the background after import — so the
transferred entries become findable without any manual step (give it a
moment to finish before searching). You can also force an immediate pass
with **Settings → Memory → Re-embed**.

### Clearing context

Starting a new chat from the drawer is the simplest way to drop the
short-term context — old sessions stay where they are, and the new
one begins with an empty window. Clearing the mini-console with
**Console → Clear** wipes the visible event log but does not touch
chat history or memory.

---

## Settings

Settings open on a **hub**: a search field, a short **Basic** block of the
handful of knobs most people touch, and a list of eight categories. Tapping a
category opens a focused sub-screen that shows its Basic settings immediately
and tucks the rest behind an in-category **"Advanced — change deliberately"**
disclosure. The redesign changed only *where* each setting lives, not *what* it
does — every existing control survives, just grouped by topic instead of one
long scroll. The top bar carries the app version, channel and build date.

For a marketing-style preview of the hub see
[`docs/images/hero-settings.png`](images/hero-settings.png)
(dark variant: [`hero-settings-dark.png`](images/hero-settings-dark.png)).

### Basic vs Advanced

Every category leads with its **Basic** settings — the ones that change everyday
behaviour and are safe to adjust. The **Advanced** disclosure holds tuning
parameters (sampling internals, retrieval thresholds, workspace and HTTP limits,
retention windows) that have sensible defaults and rarely need changing; the
"change deliberately" label is a reminder, not a lock. Five cross-category Basic
controls are also surfaced inline on the hub so you never have to open a
sub-screen for them: **System instructions**, **Inference backend**, **Approve
tool calls**, **Block destructive tools** and **Send anonymous crash reports**.

### Search the settings

The magnifying-glass field at the top of the hub searches every setting by name,
description, owning category and a set of synonyms — so typing `limit` surfaces
*Run limits* (via its synonyms), and `max` surfaces *Max context length*,
*Max memory chunks* and more. The matched text is highlighted in each result, and
a result row shows its category **breadcrumb** and **Basic/Advanced** tier (plus
a `≈ "synonym"` chip when a synonym is what matched). Tapping a result opens the
owning category, expands its Advanced section when the target lives there, and
scrolls to and briefly flashes the row (a static accent under reduced motion). A
calm *"no settings match"* state offers a one-tap **Clear**. The index is built
from the settings registry, so any setting added to the app becomes searchable
automatically.

See [`docs/images/settings-search.png`](images/settings-search.png)
(dark variant: [`settings-search-dark.png`](images/settings-search-dark.png))
for the search results in action.

### What every setting means

Most rows in Settings carry a small **ⓘ** next to the label. Tapping it opens a
one-sentence explanation in place, under the row, and closes whichever
explanation was already open — so the screen never grows by more than one panel,
and nothing is left open when you come back.

A row carries one when its meaning does not follow from its name: it has a
number, it borrows a word (*Top-K*, *threshold*, *half-life*, *embedding*), or
changing it has an effect somewhere other than that row. Links to another
screen, the identity and version rows, and plain actions like *Export memories*
carry none, and the table below says which and why.

No explanation begins with *"Not wired up yet"* any more. Seven once did — they
were rows that stored a value nothing read — and each was closed the only two
honest ways there are. **Temperature**, **Top-K** and **Top-P** now reach the
on-device model; they had to arrive together, because the engine's sampler is
set as a whole or not at all, which is why all three sat unread for so long.
**Repetition penalty**, **Tool-usage instruction**, **Auto-summarize threshold**
and **Long-running task alerts** were removed: the first has no equivalent in
the on-device engine, and the other three had no reader at all. A setting that
does nothing is worse than a missing one, because it invites you to tune it.

The **tool-call timeout** and the four **workspace and HTTP ceilings** used to be
in the same table with no screen to open them on — findable by search, drawn by
nothing. They are now five sliders under **Tools & workspace → Advanced**.

The table is **generated from the very strings the app shows**, so it cannot
drift from them: where a row has a ⓘ, what you read here is word-for-word what
it opens. What each
setting *does* when you change it — the measured timeouts, the retry behaviour,
the ordering rules — is described in the prose of the sections that follow, not
repeated here.

<!-- AUTO-GEN:SETTINGS_HELP -->
#### Generation

| Setting | What it means |
|---|---|
| **System instructions** | Goes in front of every Local LLM, Cloud and Skill step, wherever it runs — chats, triggers, background. Other node types skip it. |
| **Temperature** | Higher values make the on-device model's answers more varied and surprising; lower values keep them predictable. |
| **Top-K** | Caps how many candidate words the on-device model may choose between at each step. Smaller keeps it on the obvious choice. |
| **Top-P** | Narrows each step to the likeliest words that together reach this share of the probability. Lower is safer, higher roams. |
| **Max context length** | The working window of the on-device model. Larger holds more at once and runs slower; cloud models use their own window. |
| **Voice input length** | Recording stops on its own at this point and sends what it has. |

#### Models

| Setting | What it means |
|---|---|
| **Local model backend** | Which chip runs the on-device model. NPU is fastest where it works; the app falls back on its own if it does not. |
| **External providers** | *(no explanation — opens a screen that explains itself)* |
| **Default pipeline** | *(no explanation — opens a screen that explains itself)* |

#### Memory

| Setting | What it means |
|---|---|
| **Auto-extract memories** | On, durable facts from your chats — names, preferences, project details — are saved and reused later. |
| **Memory compaction** | Old memories get merged into shorter summaries once there are many. Frees room; loses the exact wording. |
| **Compress chat history** | Long threads are summarised past the live window, so the agent keeps the gist instead of forgetting the start. |
| **Memory search top-K** | How many memories are pulled into a reply at most. More context, slower start, and more room for noise. |
| **Memory search threshold** | Higher recalls only close matches, so less is pulled in; lower recalls more, including memories that miss the point. |
| **Recency half-life** | How fast old memories lose to new ones when both match. Short favours this week; long treats everything as current. |
| **Compaction age** | How old a memory must be before compaction may merge it. Fresher facts keep their exact wording until they pass it. |
| **Max memory chunks** | The ceiling on stored pieces. Reaching it starts a compaction pass, so it does nothing at all while compaction is switched off. |
| **History compression threshold** | How large a thread grows before everything past the live window is summarised. Lower compresses sooner and keeps less wording. |
| **Live window size** | How many recent messages stay word-for-word after a thread is summarised. Anything older survives only as the summary. |
| **Memory summary limit** | How many recent memories the $MEMORY_SUMMARY prompt variable lists. Pipelines that do not use that variable are unaffected. |
| **Embedding provider** | The model that turns text into the numbers memory search compares. Change it and recall stays poor until you re-embed by hand. |
| **Verbose memory logging** | Adds each recalled memory, text included, to the console — useful when answers cite the wrong thing, and revealing on a shared screen. |
| **Export · Import · Re-embed · Clear** | Re-embed rebuilds every vector for the current model. Stay on this screen while it runs, and expect poor recall until it finishes. |

#### Run limits & structured output

| Setting | What it means |
|---|---|
| **Run limits** | *(no explanation — opens a screen that explains itself)* |
| **Max nesting depth** | How many levels of sub-pipeline a run may descend into before it is refused. Deeper composes more; shallow fails a runaway sooner. |
| **Structured-output repairs** | When a model answers in the wrong shape, how many times it is shown its own output and asked again before the node gives up. |
| **Cloud retry attempts / base delay** | *(no explanation — opens a screen that explains itself)* |

#### Tools & workspace

| Setting | What it means |
|---|---|
| **Approve tool calls** | Which tool calls stop and wait for your approval. Never lets read-only and sensitive ones through; destructive ones still ask. |
| **Block destructive tools** | On, a destructive tool call is refused outright rather than offered for approval, and the run sees it as a failed call. |
| **Block network from local model** | On, no cloud model and no Wikipedia lookup runs, memory search included. Ollama answers only at localhost or a private IP, never by name. |
| **Manage tools / MCP servers** | *(no explanation — opens a screen that explains itself)* |
| **Approval wait** | How long a run waits for you to approve a tool call before it parks and asks again later. It does not bound the call itself. |
| **Largest file** | The largest single file the workspace accepts, for both writing one and reading one whole. |
| **Workspace size limit** | How much device storage the whole workspace may hold. A write that would push past it is refused rather than trimmed. |
| **Single read budget** | How much of a file one read may put in front of the model. The rest is cut, leaving room for the prompt and the thread. |
| **Largest tool response** | How much of a web response or MCP tool result reaches the model. Past it the text is cut and marked, so it cannot flood the context. |
| **Allowed HTTP domains** | *(no explanation — opens a screen that explains itself)* |

#### Background & triggers

| Setting | What it means |
|---|---|
| **Scheduled task alerts** | A notification arrives when a scheduled task finishes or fails, so a background result does not wait for you to open the app. |
| **Pipeline for sharing** | Which pipeline runs when you share text or a link into the app from somewhere else. |
| **Keep shares in one chat** | On, every share lands in one Shared chat. Off, each share opens its own, so the chat list grows with each one. |
| **Quick Settings pipeline** | Which pipeline the Quick Settings tile runs when you tap it from the notification shade. |
| **External automation** | Other apps on this device can start a run — Tasker, MacroDroid, adb. Off, those requests are refused and nothing is sent back. |
| **Pipeline other apps may run** | The only pipeline an outside app may start. Nothing else can be named in the request, whatever it asks for. |
| **External request journal** | *(no explanation — opens a screen that explains itself)* |
| **Resume max age** | How stale a parked run may be and still resume. Past it the run is dropped, because its gathered context no longer holds. |
| **Background approval window** | How long a background run waits for you to approve a tool call. Unanswered past it, the run ends rather than waiting forever. |

#### Privacy

| Setting | What it means |
|---|---|
| **Crash reporting** | Off, nothing leaves the device. On, crashes plus warning and error log lines go out — never your messages, prompts or keys. |
| **Trace retention · runs** | How many past runs keep their step-by-step trace in a chat. Older traces are dropped; the messages themselves stay. |
| **Trace retention · age** | How long a trace is kept before it is dropped, whatever the per-chat count. Bounds what accumulates on disk. |

#### About

| Setting | What it means |
|---|---|
| **Identity** | *(no explanation — shows a value, decides nothing)* |
| **App version & licenses** | *(no explanation — opens a screen that explains itself)* |
| **Reset all settings** | *(no explanation — does what its name says)* |
<!-- /AUTO-GEN:SETTINGS_HELP -->

### Generation

System-prompt and sampling controls.

- **System instructions** *(Basic)* — a monospaced multi-line field whose content
  is prepended to every system prompt the agent sends. Tap a chip to insert one
  of the built-in variables (`$DATE`, `$TIME`, `$LANG`, `$LOCATION`, `$USER`,
  `$DEVICE`) — they expand fresh on every prompt render. The counter shows live
  character usage against the 4 000-character limit.
- **Temperature** (0.0 – 2.0) — higher values produce more varied output.
- **Top-K** (1 – 100) — keeps only the K most likely tokens.
- **Top-P** (0.0 – 1.0) — nucleus sampling threshold.
- **Max context length** (512 – 8 192) — working window in tokens.

  The three sampling sliders apply to the **on-device** model. They are sent as
  one sampler configuration, so all three take effect together; cloud providers
  use their own defaults and ignore these. The structured-output repair pass
  overrides them deliberately, because that pass exists to get well-formed
  output back rather than interesting output.
- **Voice-input length** (seconds, default 30) — the auto-stop limit for voice
  capture before transcription.

### Models

The active on-device model, its backend, and external cloud providers.

- **Active-model card** — name, file size, context window, quantization and
  download date, with an **Active** badge.
- **Inference backend** *(Basic)* — drop-down picking the engine (NPU preferred,
  falling back to GPU then CPU). Changing it surfaces a restart banner — tap
  **Restart** to apply immediately. On a fresh install the app picks this for
  you once, during setup: if the device looks capable of GPU inference it briefly
  shows **Checking acceleration…** while verifying that the GPU really runs the
  model, and quietly settles on CPU if it does not. The result is written into
  this row, so you can always see — and override — what was chosen. Once you pick
  a backend yourself, the app never changes it again.
- **Test backend** — runs a fixed prompt-probe and persists the measurement
  (`Last probe · N tok in T s · K tok/s`) so the row keeps the metric across
  navigation.
- **Manage** — opens the full Models browser to discover and install on-device
  models.
- **External providers** *(Basic link)* — each provider (**OpenAI**,
  **Anthropic**, **Google**, **DeepSeek**, **Ollama**) collapses to a row showing
  the masked key fingerprint and selected model; tap to open the provider editor.
  The Ollama row carries a **LAN** pill and base URL. **+ Add provider** surfaces
  an unconfigured provider without scrolling. Leaving every cloud row blank keeps
  the agent fully offline.
- **Default pipeline** *(Advanced link)* — picks which pipeline new chats use by
  default.

The provider editor's **Retry policy** applies to every cloud provider (chat and
cloud embeddings alike). Transient failures — rate-limits (HTTP 429), server
errors (5xx) and connection/read timeouts — are retried with exponential
backoff; authentication errors are not retried, and stopping a run cancels
cleanly. **Max attempts** (1–5, default 3; set to **1** to disable retries) and
**Base delay** (100–10 000 ms, default 1 000) tune it. Each retry shows on the
agent console as a muted line such as `Cloud retry 1/2 for openai`, at the
moment the retry happens rather than after the answer finishes.

One limitation worth knowing, measured rather than assumed: when a provider
answers a rate-limit with a `Retry-After` header asking you to wait a specific
time, that request is **not** honoured — the backoff curve is the same whether
the header is present or not. The cause is in the upstream client library, and
working around it would mean building a second retry layer of our own. In
practice it means that under a real rate limit the app knocks sooner than it was
asked to.

#### How long a cloud provider is given, and how many tries it gets

These figures were measured against real and stalled providers, not assumed
from documentation:

- **60 seconds of silence.** The limit applies to each *read*, not to the
  answer as a whole: a long reply that keeps streaming is never cut for being
  long, while a provider that accepts the request and then says nothing for a
  minute is dropped. Before this was set explicitly, the app waited **fifteen
  minutes** on a stalled provider.
- **30 seconds to connect.**
- **3 attempts, waiting 1 then 2 seconds** — the default retry budget above,
  which also covers timeouts.

A timeout ends that *attempt*, not the run: it counts as a transient failure
and is retried. Only once the retry budget is spent does the error reach the
console and the run stop, rather than carrying on without an answer. In the
worst case a dead provider therefore costs about three minutes — three silent
minutes plus the backoff — where before these limits were set a single attempt
alone could hold the run for fifteen.

#### An answer that was cut off is not shown as an answer

If the connection dies halfway through a reply, most providers do not raise an
error. The stream simply ends, and the only difference from a healthy one is a
missing "I have finished" marker — so a half-written answer can look exactly
like a complete one. The app checks for that marker and, when it is absent,
discards the partial text and tells you the reply was cut off, rather than
handing you an answer that stops mid-sentence as though the model meant it.

Which providers this protection covers was decided by measurement only, because
the opposite mistake — treating a healthy reply as truncated — would break
working setups:

| Provider | Truncated answers caught? |
|---|---|
| **OpenAI**, **DeepSeek** | Yes — measured. |
| **Google** | Yes — its client reports a broken stream as an error on its own. |
| **Ollama** | **No.** Its client never sends the finishing marker at all, so its absence proves nothing; checking for it would fail every healthy run. |
| **Anthropic** | **No.** We could not produce a trustworthy test either way, and guessing is exactly the failure this check exists to prevent. |

For the bottom two rows a connection that drops mid-answer can still leave you
with a reply that is shorter than it should be and looks finished. If an answer
from one of those ends abruptly for no obvious reason, ask again before
concluding the model had nothing more to say.

### Memory

Long-term memory extraction, chat-history compression, retrieval tuning and data
actions (behaviour unchanged — see [Memory](#memory) for the
full feature).

Basic:

- **Auto-extract from conversations** (default on) — distils durable facts from
  finished chats into memory.
- **Background compaction** (default on) — the daily charging-and-idle worker
  consolidates stale clusters.
- **Compress long chat history** (default on) — older turns of a long session are
  summarised by the local model in the background and shown to the agent as an
  *"Earlier conversation (summarized)"* block ahead of the recent, verbatim
  turns.

Advanced:

- **Search results (top-K)** (1–20, default 5), **Similarity threshold**
  (0.30–0.90, default 0.55), **Recency half-life** (7–180 days, default 30),
  **Compaction age** (7–90 days, default 30) and **Max stored chunks**
  (1 000–20 000, default 5 000) — the retrieval and housekeeping parameters.
- **Compression threshold** (500–32 000 tokens, default 3 500) and **Live
  window** (2–50 messages, default 10) — tune chat-history compression. Keep the
  threshold comfortably below **Max context** so the summary plus the live window
  still fit.
- **Memory summary default limit** (1–50) — how many recent chunks the
  `$MEMORY_SUMMARY` prompt variable injects.
- **Embedding model** — on-device Universal Sentence Encoder, OpenAI or Ollama.
  OpenAI is not used, and Ollama only at a local address, while **Block network
  from local model** is on — memory falls back to the on-device model instead.
  Switching applies on the next embed/retrieval; a persistent
  *"re-embed recommended"* banner appears (with an inline **Re-embed** button)
  until a full re-embed or wipe re-aligns the store, or you switch back.
- **Verbose memory logging** (off by default) — expands every memory-retrieval
  console line with a per-hit snippet and similarity score, and logs which chunks
  background compaction merged. A local diagnostic only — nothing leaves the
  device.
- **Data actions** — **Export base** (SAF picker → JSON blob), **Re-embed**
  (re-runs the embedder over every chunk, with a progress bar), and **Clear** (a
  typed-confirm dialog that wipes every chunk, pinned included).

Each slider only offers in-range values; a rejected value shows an inline message
and is discarded rather than saved.

### Run limits & structured output

The category holds the *limits and knobs* an autonomous run obeys. The pipelines
themselves live on the **Pipelines** tab (library and editor) and under
**More → Library**; this screen never lists them. Searching Settings for
`pipelines` still lands here.

- **Run limits** *(Basic link)* — opens the [Run limits](#run-limits) screen,
  and shows the current step and token limits on the row itself.
- **Max nesting depth** *(Advanced)* — how deep `PIPELINE` nodes may recurse.
- **Structured-output repairs** *(Advanced)* — how many times the
  structured-output gate re-asks the model to fix malformed JSON before falling
  back to the per-node failure policy.
- **Retry policy** *(Advanced link)* — opens the provider detail screen (the same
  retry sliders described under Models).

#### Run limits

A run that reaches one of these limits pauses and asks whether it may carry on.
Everything a run starts counts towards them: pipelines it calls, and every time
it resumes after a pause. There are four numbers and one statement:

- **Steps per run** (5 – 100) — how many steps a run may take before it stops.
  One step is one node execution.
- **Steps per background run** — the same limit for runs you did not start
  yourself: a trigger, a schedule, the Quick Settings tile, or another app.
  Until you set it, it is marked *Same as above* and follows the number above
  it, so raising your interactive limit raises theirs too. Setting it separately
  ends that, and it keeps its own value from then on.
- **Tokens per run** (10 000 – 10 000 000) — how many tokens a run may send and
  receive in total. The track is logarithmic, so a proportional change costs the
  same drag anywhere along it.
- **Tokens per background run** — lower by default, because nobody is watching a
  background run finish.
- **Spending limit — *Not measured*.** Knotwork runs on your own API key, so it
  never sees your bill and cannot measure or cap what a run costs. Rather than
  show you a figure it would have to guess, it says so. The token limit above is
  the closest control.

A run you are watching warns you when it passes 75 % of a limit, with an
unobtrusive note above the composer while there is still room to finish. That
warning point is fixed and not adjustable. Two honest caveats: the note lives in
the chat, so a run started by a trigger at 3 am has nowhere to show it; and a
run that pauses and resumes may warn again.

#### When a run goes in circles

Separately from the limits above, and with nothing for you to configure, the app
watches a run for **repetition**: the same step, on the same input, producing the
same result over and over. The two are not the same protection. A limit asks how
much a run has spent; this asks whether it is getting anywhere.

It works in two stages. First the run is told — quietly, in the same short strip
above the composer that the limit warning uses, and in the run's own context, so
the agent gets a chance to change course by itself. That is usually the end of
it. If the repetition carries on regardless, the run is ended and the chat says
**Stopped: the run was not getting anywhere**, with **Open console**. The
console's **Logs** tab is where the repetition shows: the same step starts and
finishes over and over, and the last lines say what was noticed and that the run
was ended for it. The **Vars** tab is the companion — it shows what that step was
given and what it produced, though only the most recent pass, since it keeps one
entry per step rather than one per visit.

A run that keeps producing *different* results is not repeating itself, however
long it takes, and this never touches it. That case is what the limits above are
for.

#### When a run reaches a limit

The run **pauses and asks**. The chat shows **Paused at a safety limit**, which
allowance ran out, how much of it was used, and two actions: **Continue (+N)**
and **Stop the run**.

Continuing buys the run **one more portion of the same allowance** — the number
on the button, which is the limit it just reached. It does not lift the limit:
when that portion runs out the run asks again, so a run that has been waved
through five times is one you have said yes to five times. Stopping ends it
exactly as reaching a limit used to: **Stopped by a safety limit**, with an
**Adjust limits** action.

The pause survives everything. It is written down the moment it is raised, so a
run that reaches its limit while you are elsewhere — a trigger firing overnight,
or the app being killed while you were away — is still waiting when you come
back, with the same numbers it stopped at. A run that pauses while you are not
looking at its chat posts a notification; tapping it opens the conversation where
the question is. The notification carries no buttons on purpose: what you are
deciding depends on how much the run has already spent, and the shade has nowhere
to show you that.

Two things bound the wait. An unanswered pause expires with the same
**background response window** that governs approvals (Settings → Tools &
workspace), after which the run is ended at its limit; and a run whose pipeline
you edit while it waits cannot be continued, because its checkpoint no longer
matches the graph.

A background run that ends at a limit — whether you stopped it or the window
closed — is announced in its notification and recorded in the trigger's journal,
where it deliberately does **not** count against the trigger's health: a limit
you configured doing its job is not a fault.

### Tools & workspace

Tool approval, safety guardrails, and the agent workspace / HTTP limits.

Basic:

- **Approve tool calls** — segmented control: `All` (prompt for every call),
  `Sensitive +` (only sensitive/destructive — recommended), `Never` (only
  destructive tools still ask; for known-safe pipelines).
- **Block destructive tools** — when on, destructive tools are refused outright
  rather than going through the HITL prompt. Useful when the agent runs
  unattended.
- **Block network from local model** — when on, no model request leaves this
  device or your own network:
  - **Cloud providers** (OpenAI, Anthropic, Google, DeepSeek) are refused even
    with a key saved — for Cloud steps, `delegate_task` and memory alike.
    If OpenAI is your **Embedding model**, memory switches to the on-device
    model while the restriction is on. Memories embedded by OpenAI are recalled
    poorly until you turn it off, and memories saved meanwhile are recalled
    poorly after you do — run **Re-embed** once you have settled on one.
  - **Ollama** answers only when its address is `localhost` or a private IP
    address (`127.x.x.x`, `10.x.x.x`, `172.16.x.x`–`172.31.x.x`,
    `192.168.x.x`), over `http` or `https`. A server on the internet is refused
    even over `https`, and so is **any host name** — `ollama.lan`, `nas.local` —
    because a name says nothing about where it resolves; type the IP address
    instead. Tailscale (`100.x.x.x`) and IPv6 addresses are refused too. A Cloud
    step that hits the restriction fails and names the refused address.
  - **One tool is affected: `search_tool`.** The built-in Wikipedia lookup is
    withheld from the model while the restriction is on, and a pipeline step
    bound to it by name reports the refusal instead of a result. It is the one
    tool the restriction covers, because it is the one that reaches the network
    with neither a destination you named nor a confirmation.
  - **The other tools are not affected.** An MCP server or an `http_request`
    domain you added is still reachable — those have their own controls (tool
    approval, the allowed-domains list), described under
    [Tools and MCP](#tools-and-mcp).
- **Manage tools / MCP servers** *(link)* — enable tools, set per-tool risk
  overrides, add MCP servers. It opens the same surface as the **Tools** tab but
  *inside* Settings: the bottom-nav highlight stays on the tab you came from and
  Back returns to this category, not to the Tools tab.

Advanced:

- **Approval wait** (5 – 300 s, default 60) — how long a run holds an approval
  request open in front of you before parking it and asking again later. It does
  not bound the tool call itself; that deadline is not a setting.
- **Largest file** (1 – 50 MB, default 5) and **Workspace size limit**
  (10 – 1024 MB, default 100) — the per-file and total ceilings on the agent
  workspace. A write that would cross either is refused.
- **Single read budget** (200 – 8 000 tokens, default 2 000) — how much of a file
  one `read_file` call may return. Anything past it is cut, with a marker, so one
  read cannot fill the model's whole context.
- **Largest tool response** (64 – 8 192 KB, default 1 024) — how much of an
  `http_request` response or an MCP tool result is read into the answer. Bounds
  how much untrusted remote text a single call can put in front of the model.
- **Files / allowed domains** *(link)* — the `http_request` domain allowlist and
  the workspace file browser.

### Background & triggers

Notifications and the windows that govern parked / resumable runs.

Basic:

- **Scheduled task results** — when on, finishing a scheduled background task
  posts a **Task completed** notification with the first line of the answer (or
  **Task failed** with the reason); tapping it opens the conversation the result
  landed in. On by default.

Advanced:

- **Resume window** (1 – 168 hours, default 48) — how long an interrupted run
  stays resumable from its checkpoint. Older interrupted runs only offer
  **Discard** — their recorded context grows stale with time.
- **Approval window** (1 – 168 hours, default 24) — how long a run parked on an
  unanswered tool approval or clarifying question waits before failing with
  *Approval window expired*.

### Privacy

- **Send anonymous crash reports** *(Basic)* — forwards stack traces + device
  meta + active pipeline / model identifiers to Firebase Crashlytics. Off by
  default; debug builds never report. Full policy in
  [SECURITY.md](../SECURITY.md). **This toggle exists only on the standard
  build.** The **F-Droid / FOSS build** ships with no crash-reporting
  dependency at all — it collects and transmits nothing, and the toggle is
  absent — so which build you installed decides whether this control is even
  present. The rest of the app is identical between builds.
- **Keep run history per chat** *(Advanced)* (5–100, default 20) — how many
  most-recent pipeline runs each conversation keeps. Older finished runs and
  their traces are deleted by the daily maintenance pass (see
  [Background tasks](#background-tasks)).
- **Run history max age** *(Advanced)* (7–180 days, default 30) — finished runs
  older than this are deleted regardless of the per-chat count. Runs still
  waiting on an approval or clarification are never removed by retention.
- **Usage statistics** *(link)* — opens a fully on-device usage dashboard (see
  below).

#### Usage statistics

A privacy-preserving picture of how you use the app, computed entirely on the
device. **Nothing on this screen is ever transmitted** — the counts live in the
same encrypted database as the rest of your data, and there is no network call
anywhere on this path (a build-time guard enforces that). The screen shows:

- **Runs** — total finished runs and the share that **completed**, **failed**,
  were **cancelled**, or were **interrupted** (a run cut short by the app being
  killed). Nested sub-pipeline runs are not double-counted.
- **Runs by pipeline** — how many runs each pipeline accounted for.
- **Trigger firings** — how many times each kind of automation trigger
  (schedule / daily / charging / network) fired.
- **Active days** — the number of distinct days with any activity, plus the
  first and last.
- **This week** — whether the app is actually sticking. Active days out of the
  last seven (with the week before it for comparison), how many distinct
  pipelines you ran in that window, your current run of consecutive days, how
  many times you came back after a break of three days or more, the longest such
  break, and how many days you used the app in your first week after installing.
  The first-week figure stays blank (`—`) until that week has actually passed, so
  a fresh install is never labelled with a number it has not earned yet. The
  day-based figures use the day history the app was already keeping, so they are
  meaningful right after updating; **Pipelines used** starts at zero after an
  update, because which pipeline ran on which day was not recorded before and is
  not back-filled.
- **Setup** — how long your first run took to arrive: the time from opening
  onboarding to your first successful run, how much of that was the model
  download, and the two subtracted (`mm:ss`). The download depends on your
  connection rather than on the app, so separating it is what makes the figure
  comparable between installs. The block appears once that first run has
  happened; the timing is measured a single time per install, from the first time
  onboarding opens, and a later pass through onboarding never overwrites it.
  Journeys where the model was already installed show no download time.

Controls:

- **Record usage on this device** — the opt-in toggle. On by default and
  local-only; turn it off to stop the counters advancing (already-recorded
  figures are untouched).
- **Share as text** / **Export JSON** — take a voluntary snapshot for your own
  analysis. The text goes through the system share sheet; the JSON is written to
  a file you pick. Neither happens automatically.
- **Reset statistics** — permanently clears every recorded count (it does not
  change the recording toggle).

### About

- **Identity card** — an avatar + label confirm the device identity is anonymous
  and local. The meta line shows the truncated device id and whether your API
  keys live in the Android Keystore (hardware-backed) or — on constrained
  devices — in encrypted preferences.
- **App version & licenses** *(link)* — build info and the open-source license
  list.
- **Reset all settings** *(Advanced)* — a confirm dialog that restores every
  tunable setting to its recommended default in one step. It touches *settings
  only*: your chats, long-term memory, pipelines, presets, skills, MCP/cloud
  connections, the `http_request` allowlist, custom prompts, the active embedding
  provider, and API keys are all left untouched. The defaults are sensible
  starting points rather than tuned optimums — they are refined as the app sees
  real-world use, so resetting is a safe way to get back to a known-good
  baseline.

---

## Getting around

Four tabs sit at the bottom: **Chat**, **Pipelines**, **Tools**, **More**.
Everything else is a screen pushed on top of one of them.

- **The highlighted tab is the one you are inside.** It follows the screens you
  actually opened, not the screen you are looking at — open the tool list from
  **Settings** and the highlight stays where you were, because that is where
  Back will take you.
- **Back returns to where you came from**, one screen at a time — never to a
  tab you did not open.
- **A tab is only ever entered by tapping it** (or by a launcher shortcut /
  notification, which behaves the same way). No link inside a screen drops you
  onto a different tab: a surface reachable from two places opens in the place
  you opened it from. `Settings → Tools & workspace → Manage tools` is the
  example — same screen as the Tools tab, but it stays inside Settings.
- **Notifications open the chat they belong to**, replacing whatever chat was on
  screen rather than stacking a second one, so Back closes the app exactly as it
  would after a normal launch.

## More tab

The **More** tab is the landing page for every secondary surface. Its
thirteen rows sit in four named sections:

| Section | Rows |
|---|---|
| **Automation** | Triggers · Pipeline preset library · Tasks |
| **Your content** | Memory · Files · Archived chats |
| **Building blocks** | Prompt library · Skill library · Models |
| **App** | Settings · Help · Live metrics · About |

Automation comes first because it holds the reasons to open More at all,
and App comes last because that is where Settings is looked for. The
sections are labels: nothing here is a separate screen, and no row goes
anywhere different than it did before.

Most rows carry a live subtitle — memory chunks, the active model name,
prompt categories and prompts, the presets you saved, the files in the
workspace and their size, the app version and build. **Tasks** reads
`N running · N queued`, where *queued* counts only chats waiting behind
another run, and it is the only row with a numeric badge: the running
count. That is what keeps a badge meaning "something is running right
now" — a stored quantity like the archived-chat count lives in the row's
subtitle instead. A footer pill summarises the privacy state. It counts
every connection the app opens itself — a cloud model or `delegate_task`,
a network embedding model, an MCP server, `search_tool`, `http_request`,
and Hugging Face browsing and model downloads. While one is running, and
for a minute after, it reads `online · network call just now`; after that,
`on-device · no network calls in last N m`, and before the first one,
`on-device · no network calls yet`. Crash reports (the standard build,
after you opt in) are not counted: the crash-reporting SDK sends them on
its own, out of the app's sight. The count resets when the process is
recreated.

## Managing local models

Open **More → Models** to install, activate, or remove on-device
LLMs.

- The top **Active** card highlights the model currently loaded into
  inference memory. Its mono subtitle shows size, accelerator
  backend, and execution backend.
- The **HuggingFace** section lets you paste a personal access token
  (stored encrypted, in the same Keystore-backed store as cloud API
  keys) so gated repositories can be downloaded. The `+ Paste` button
  reads the system clipboard. The token is sent only to
  `huggingface.co`.
- The **Custom model URL** field accepts a direct link to a
  `.litertlm` file — the only format the on-device engine loads.
  Tap `Get` to start downloading. A link to any other host is downloaded without your
  token.
- The **Available presets** list shows curated models, each row in
  one of three states: `Get` (not downloaded), progress bar with
  cancel-X (downloading), or `✓ ON DISK` (ready to activate).

### Model performance & benchmark

Below the Active card, the **Performance** card shows how the active
model actually performs *on your device* — picking a model is a
speed/quality/memory trade-off, and the numbers make it concrete instead
of a guess:

- **Time to first token** — how long after a run starts the model emits
  its first token (model-load time excluded). Shown in milliseconds, or
  seconds once it passes one second.
- **Decode speed** — sustained generation throughput in tokens per second.
- **Peak memory** — the highest process-wide native-heap usage seen during
  a run. This is deliberately labelled **approximate**: it covers the whole
  app process (not just the model), excludes the model file's
  memory-mapped pages, and can read low on devices that compress memory. It
  is a useful relative indicator, **not** the model's exact footprint.

These figures are **averaged over the model's most recent runs** and update
automatically — every on-device generation you trigger (chatting, running a
pipeline) records a sample. A freshly installed model that hasn't run yet
shows a calm **"No runs yet"** state.

Tap **Run benchmark** to measure the model on demand. It runs a fixed
prompt twice — a **warm-up** run (not counted) followed by a **measured**
run — then shows a one-shot report (TTFT, decode speed, total time, peak
memory) with a **BENCHMARK** badge. **Share** hands a plain-text summary to
any app (messages, notes, a bug report); **Done** returns to the rolling
averages. You can **Cancel** a benchmark while it runs. The benchmark only
runs in the foreground and **waits its turn** if a pipeline is currently
using the engine — it never interrupts an active run (you'll see a calm
"Busy with a task" notice in that case).

## Prompt library

**More → Prompt library** stores reusable system prompts grouped by
node type. The screen opens on the first category tab; tap any tab
to switch. Each card has:

- A category chip on the left and the prompt name in bold.
- A multi-line preview with `$VARIABLE` tokens highlighted inline so
  you can see at a glance which runtime values the prompt depends
  on.
- A footer line with `used by N pipelines` and the row's actions:
  **Preview**, **Duplicate**, **Edit**, and a **More** menu holding
  **Export** and **Delete**. Bundled prompts are read-only, so their
  row shows **Preview**, **Duplicate** and **Export** directly.

The FAB at the bottom-right opens the editor sheet. Inside, you can
edit the name and category and tap any chip in the `INSERT` row to
append the matching `$VARIABLE` to the prompt body. Save persists
the change immediately; the next pipeline run picks it up.

### Importing and exporting a prompt

A prompt can travel as a **Markdown file**, so you can send one to
somebody or keep it in a repository beside your other notes.

- **Export** — from a row's **More** menu (or directly on a bundled
  row). Pick where to save; the file is named after the prompt.
  Exporting a bundled prompt is fine — it only reads it.
- **Import** — the tray icon in the top bar, and the first button on
  the empty screen. Pick a `.md` file; the prompt lands in the category
  the file names. When there is nothing to report, a message names the
  prompt and the category and offers to take you there; when there is,
  you get the details instead.

The file looks like this — a settings block between two lines of three
dashes, then the prompt itself:

```markdown
---
schemaVersion: 1
id: concise-assistant
name: Concise assistant
description: Single-paragraph answers, no preamble.
nodeType: LITE_RT
tags: [concise, starter]
---
You are a helpful assistant. Answer in one paragraph, and today's
date is $DATE.
```

Only `name`, `nodeType` and the prompt text are required, so a file
you write by hand can be shorter than this one.

**A prompt file can only supply wording.** It cannot add tools, add
steps, or carry scripts — if a file asks for any of those, the prompt
is still imported and the app tells you exactly what it left out. This
is deliberate: a prompt goes into the instructions the agent follows,
and a file you did not write should not be able to widen what the
agent is allowed to do. For the same reason there is no "import from a
URL" — the file picker keeps a person's decision between a link and
your agent.

If something is wrong with the file, nothing is imported and the app
names the cause — a missing settings block, no prompt text, or a step
type it does not have. If the prompt is already in your library and
the file differs, you choose whether to replace it or keep both.

## Skill library

**More → Skill library** stores **skills** — reusable bundles of
*instruction + tool restriction + context configuration*. Instead of
copying a system prompt between nodes and pipelines, you describe a
capability once and reuse it.

The screen has two tabs:

- **Bundled** — read-only skills that ship with the app (Summarizer,
  Translator, Report Writer). Their row menu offers only **Duplicate**,
  which drops an editable copy named `… (copy)` into your **Mine** tab.
- **Mine** — your own skills, with full **Edit / Duplicate / Delete**.

Each row shows the skill name, a one-line description, and a
**tool-restriction** pill that is always one of three visually distinct
states: **All tools** (unrestricted — every tool, including ones added
later), **N tools** (an explicit subset), or **No tools** (an explicit
empty allowlist — *not* the same as unrestricted).

Tap the FAB (**New skill**) or **Edit** to open the full-screen editor:

- **Name** and **Description**.
- **Instruction** — a monospace field. Use `$VARIABLE` placeholders
  (e.g. `$DATE`, `$LANG`) for runtime values; they're substituted when
  the skill runs. Tap a chip in the **INSERT** row to append one.
- **Tools** — a master control selects **All tools**, **Restrict**, or
  **No tools**. In **Restrict** mode a checklist of the available tools
  appears, each with its risk pill, so you can pick exactly which tools
  the skill may use.
- **Context the skill sees** — five toggles (chat history, original
  task, node input, long-term memory, tool results) that become the
  skill's default context.

Deleting a user skill asks for confirmation; if any pipelines reference
it the dialog lists them so you can repoint or remove the reference
first.

### Using a skill in a pipeline (the SKILL node)

In the pipeline editor, add a **Skill** node to run a skill as one step
of a pipeline. The node configuration sheet offers:

- **Skill** — a picker over your whole library (bundled + your own).
  Until you choose one, the node is flagged invalid and the pipeline
  won't save.
- **Instruction (read-only)** — a preview of the chosen skill's
  instruction. It's edited in the Skill library, not here.
- **Tool allowlist** — an indicator showing the skill's restriction
  (All tools / N tools / No tools).
- **Inference engine** — run the skill **on-device** or in the **cloud**.
- **Always ask before this call** — every tool call the skill makes stops for
  your approval, whatever the tool's risk. It can only add a confirmation,
  never remove one.
- **Context toggles** — these start **inherited** from the skill's own
  default context; change any one and it's marked **overridden** so you
  can see at a glance where the node diverges from the skill.

The tool allowlist is a real boundary, not a hint: when the skill runs,
`$TOOLS` in its instruction lists only the allowed tools, and if the
model still tries to call a tool outside the allowlist the node refuses
it and reports the refusal instead of running it. Allowed tool calls go
through the same confirmation prompts as any other tool — a skill never
lowers a tool's risk or skips a confirmation.

## Active tasks

**More → Active tasks** lists everything the agent is running right
now plus completed history. Filter chips at the top scope the list
to `All`, `Active`, `Background`, or `Completed`. Each row shows
the task title, a mono subtitle with the pipeline stage, a status
pill (Queued / Running / Success / Failed / Cancelled), and an
inline cancel button on running background work. Tap any row to
open a bottom sheet with the task details and an `Open chat` shortcut
for session-bound tasks.

**Tasks the agent scheduled for itself** are named: the row leads with
the beginning of the task's own prompt, and the line under it reads
*Scheduled task*, its repeat interval if it has one, and the chat it
reports into. That is what makes cancelling a specific one possible —
without it every scheduled task looks the same.

When at least one such task is queued or running, the top bar offers
**Stop all scheduled tasks**. It settles every task the agent scheduled
for itself, including any running at that moment, and nothing else:
automations and their triggers keep working, and nothing is deleted —
a task can simply be scheduled again. It exists for
[a task that keeps scheduling itself](#a-task-that-keeps-scheduling-itself),
which cancelling the one queued row by hand cannot end.

## Live metrics

**More → Live metrics** surfaces the orchestrator's performance
counters and the most recent system log lines. The header three-cell
grid shows last inference time (ms), tokens-per-second, and the
total tokens processed since process start. Under it sit the
session-wide totals and a per-node-type breakdown. When the device
enters power-saving mode, a warning banner appears above the grid
to flag that the agent has paused background work.

## About

**More → About** shows the app's brand mark, version / build / commit, a
**Documentation** card that opens [Help](#the-help-screen), the open-source
license name (Apache 2.0), a hand-curated acknowledgments list of the libraries
that ship inside the app, and a short privacy summary. Tap `Open license text`
to load the license verbatim in your browser, or `Read privacy policy` for the
detailed privacy stance.

---

## Finding the documentation from inside the app

The five documents written for people using the app open from the app
itself, so you never have to go looking for a URL — and the two you are
most likely to want when something is wrong open with **no network at
all**.

### The Help screen

**More → Help** lists every document, in the order you are likely to
need one while something is broken: [troubleshooting](troubleshooting.md),
the [FAQ](faq.md), this guide, the [pipeline cookbook](cookbook.md) and
the [external-automation contract](external-automation.md).

Each row says what is inside it and, in small type, **where it is read
from and how big it is** — `on this device · 11 problems`, or
`opens in browser · 3,000 lines`. A row that leaves the app also carries
a small outward arrow after its name, so you know before you tap
whether you are staying put.

**About** points at the same screen rather than listing the documents a
second time.

### Reading a document inside the app

Troubleshooting and the FAQ ship inside the app. Opening one gives you a
reader, not a browser:

- **Links between sections work.** Tapping an entry in a document's own
  contents list scrolls to that section and briefly marks where you
  landed, so arriving mid-document does not look like a broken load.
- **Links between the two documents work**, anchor and all. Back returns
  to the document you left, at the place you left it.
- **Headings are navigable by a screen reader**, so TalkBack's heading
  rotor moves through a document the way it would on the web.
- **A link to a document that is still on the web** hands off to your
  browser. With no network it refuses in place instead, and offers you
  the address to copy — the reader keeps your scroll position either
  way.

The **Open in browser** action in the top bar is always available, for
when you want the web version of what you are reading.

### The documents that stay on the web

This guide, the cookbook and the automation contract open in a browser.
That is deliberate rather than unfinished: this guide runs to about
three thousand lines and is genuinely better with a browser's search,
and the cookbook's generated tables use
formatting the in-app reader cannot show — it would drop parts of them
silently, which is worse than sending you to the web.

Tapping one of those rows with no network refuses under the row and
tells you which documents *are* on your device.

### Links that go straight to the point

Four screens link to the document that explains them — two of them to
the exact section:

| Where | What it opens |
|---|---|
| **Tools** — the book icon in the top bar | This guide, at *Adding an MCP server* |
| **Triggers** — the book icon in the top bar | This guide, at *Triggers* |
| **Pipeline editor** — overflow menu → *Pipeline cookbook* | The cookbook, from the top |
| **Settings → Background & triggers → External automation**, the hint's *Read the contract* | The external-automation contract, from the top |

The last step of onboarding carries the same pointer to the FAQ, and
opens it in the built-in reader — so it works before you have set up a
network or a model, and back returns you to that step.

**The links match the build you are running.** A release build opens the
documentation at the tag of the version you installed, so what you read
describes the app on your phone rather than whatever has landed since.
Debug builds link at `main`. Two consequences worth knowing: the
privacy policy is deliberately **not** pinned this way — it always opens
the current edition, because that is the one that binds — and the
documents bundled into the app are the ones that shipped with your
version, which is the same promise stated a different way.

---

## See also

- [`faq.md`](faq.md) — short answers to the questions that come up most,
  each one pointing back into this guide.
- [`troubleshooting.md`](troubleshooting.md) — what to do when something
  does not work.
- [`architecture.md`](architecture.md) — internal design of the
  agent for contributors and reviewers.
- [`extending.md`](extending.md) — recipes for adding new node
  types, tools, cloud providers, and prompt variables.
- [`../SECURITY.md`](../SECURITY.md) — security policy, threat
  model, and what crash reporting collects.
- [`../README.md`](../README.md) — project overview and quick start.
