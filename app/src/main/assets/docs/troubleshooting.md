# Troubleshooting

Something is not behaving the way it should. This page collects the failures
that have actually come up, with the reason behind each one and what to change.

Three sibling documents, and it is worth knowing which one you want:

- **This page** answers *"it is broken"* — a model that will not load, a run
  that stopped, a trigger that never fired.
- [`faq.md`](faq.md) answers *"can it, does it, where is it"* — whether a
  thing is supported at all, and where to find it.
- [`user-guide.md`](user-guide.md) describes how each screen and feature
  works, and is the canonical description of behaviour. Both of the other two
  link into it rather than repeating it.

## Contents

- [The model fails to load with "out of memory"](#the-model-fails-to-load-with-out-of-memory)
- [Inference is very slow](#inference-is-very-slow)
- [A tool says it is unavailable](#a-tool-says-it-is-unavailable)
- [An MCP server is connected but a tool I expect isn't there](#an-mcp-server-is-connected-but-a-tool-i-expect-isnt-there)
- [The same question goes to the cloud one time and stays local the next](#the-same-question-goes-to-the-cloud-one-time-and-stays-local-the-next)
- [A background run dies the moment I leave the app](#a-background-run-dies-the-moment-i-leave-the-app)
- [A pipeline went missing](#a-pipeline-went-missing)
- [The agent stopped mid-run](#the-agent-stopped-mid-run)
- [A trigger didn't fire](#a-trigger-didnt-fire)
- [Memory search isn't finding an obvious entry](#memory-search-isnt-finding-an-obvious-entry)
- ["Your data can't be unlocked" appears at startup](#your-data-cant-be-unlocked-appears-at-startup)

---


## The model fails to load with "out of memory"

Local LLMs need a large block of contiguous memory. If loading a
model fails:

- Open **Models** and tap **Make Active** on a smaller model. Quad-
  or 4-bit-quantised variants tend to fit where full-precision ones
  do not.
- If you have multiple models on the device, the previously active
  one stays loaded until a new one is activated. Switching back and
  forth a few times can leave the device fragmented — closing the
  app entirely (swipe it away from the recents list) and reopening
  it frees the native handle reliably.
- Make sure other heavy apps are not running in the background.

## Inference is very slow

Without an NPU or a usable GPU, the local model runs on CPU only,
which is noticeably slower (especially for the first few tokens):

- Open **Settings → Models** and tap **Test backend** to confirm
  which backend the model is actually using.
- Try a smaller model from the **Models** screen — even a 1B-2B
  parameter model can be substantially faster than a 7B+ one on
  CPU.
- Lower **Max context length** in **Settings → Generation → Advanced**.
  Shorter contexts mean less work per token.

## A tool says it is unavailable

Two common causes:

- A built-in tool that delegates to a cloud provider (for example,
  **delegate_task**) requires at least one cloud API key in
  **Settings → Models → External providers**. Without a key it is hidden
  from the agent.
- An MCP-server tool requires the server itself to be reachable.
  Open the **Tools** screen and confirm the server is still listed
  under **MCP Servers**; if the URL changed or the server is down,
  the tool will fail with an error event in the console.

## An MCP server is connected but a tool I expect isn't there

If the server row says `ok` and the tool still never appears, the server
is probably not publishing it to this app. Some tools only work with
clients that support extra features Knotwork does not have yet, and a
well-behaved server leaves those out of the list rather than offering
something that would fail. See [What the tool count on a server row
means](user-guide.md#what-the-tool-count-on-a-server-row-means) — it is a real
limitation, not a misconfiguration you can fix from the Tools screen.

## The same question goes to the cloud one time and stays local the next

That is usually the router reading the conversation, not a bug. See [Why
the same question can take a different
route](user-guide.md#why-the-same-question-can-take-a-different-route) for what to
change if you want the decision to be repeatable.

## A background run dies the moment I leave the app

Almost always battery restrictions rather than the app: with the default
setting, the system can reclaim the process within seconds of the app
going away. See [Battery settings decide whether any of this
happens](user-guide.md#battery-settings-decide-whether-any-of-this-happens). The same
cause is behind most triggers and scheduled tasks that never produce a
result.

## A pipeline went missing

If you delete or rename a pipeline that a chat was bound to, the
chat falls back to the **default** pipeline marked in the library
on its next message. There is no broken state — replies keep
working — but the conversation will start using whichever pipeline
is currently flagged as the default. Pick a new pipeline for the
chat by reopening the **Pipelines** screen and using **Set as
default**, or rebind the chat by creating a new one.

## The agent stopped mid-run

A long run can reach one of its **run limits**. It pauses and asks: the chat
says **Paused at a safety limit**, names which allowance ran out and how much of
it was used, and offers **Continue (+N)** or **Stop the run**. Continuing gives
it one more portion of the same allowance and picks up where it left off; it
asks again when that runs out.

If the run stopped instead of pausing, the chat says **Stopped by a safety
limit** — you chose Stop, the waiting window closed before you answered, or the
run had no record to wait on (a test run from the editor). **Adjust limits** on
that message opens the screen where you can raise the limit for next time.

Two things worth knowing before you raise anything. The limit involved may be
the **token** one rather than the step one, so read the message rather than
assuming; and a run you did not start yourself is governed by the **background**
limits, which are set separately on the same screen.

But read the message first, because not every mid-run stop is a limit. **Stopped:
the run was not getting anywhere** means the run was repeating itself and was
ended for that — raising a limit would only buy it more circles, so open the
console and look at which step keeps coming back. **Stopped: the run went quiet**
is a third thing again: a step stopped responding, most often a tool or a server
that never answered, and the run was ended so other messages could run. That one
may well work on a second try.

## A trigger didn't fire

Open the trigger and read its **Evaluation journal** (see
[Triggers](user-guide.md#checking-what-a-trigger-has-been-doing)) — the answer is almost
always there, and which of two shapes it takes decides what to do next:

- **There is an entry for the moment you expected, saying it didn't run.**
  The reason is stated: the condition wasn't met, it had already fired for
  that window, the trigger was off, or no pipeline is bound. Nothing is
  broken — either the condition is not what you thought, or the trigger needs
  binding or enabling.
- **There is an entry, and it fired but ended badly.** *Failed* points at the
  pipeline (open the run in the trigger's chat and read the console). Two
  failures name themselves: *the run was not getting anywhere* means it was
  repeating itself, and the console's log shows the same step running again and
  again before the line that ends it;
  *the run went quiet* means a step stopped responding — the console's log ends
  with the step it started and never finished, and that step is the thing to
  look at, though the **Vars** tab will have nothing for it because it never
  produced anything;
  *Stopped by the system* means the process was killed mid-run, which is a
  battery / memory-pressure problem, not a pipeline one; *Timed out waiting
  for approval* means the run parked on a sensitive tool and the approval
  window expired before you answered — either respond sooner, widen
  **Settings → Background & triggers → Approval window**, or use a pipeline
  whose tools don't need approval. If the entry says *The request never
  reached you*, the opposite happened: the approval could not be handed over
  at all, so no notification was worth waiting for.
- **There is no entry at all around that time,** and the list shows
  **Overdue**. The app was never woken to check. This is a platform-side
  problem: exclude the app from battery optimisation, and on phones with an
  extra vendor layer (Samsung, Xiaomi, OnePlus and others) also take it out
  of any "sleeping"/"deep sleeping" app list. Note that every non-charging
  trigger runs on a deferrable background schedule (see [How soon a trigger
  fires](user-guide.md#how-soon-a-trigger-fires)) — a check arriving late is normal; a
  gap of many times the trigger's own cadence is not.

A trigger that has never been evaluated at all shows *"No evaluations yet"* —
expected right after you create one, since the first check is up to the next
poll.

## Memory search isn't finding an obvious entry

If a memory you know exists never shows up in a reply (or in the Memory
screen's search), work down this list:

- **The node isn't reading memory.** Only nodes with the **Long-term
  memory** input flag pull from the store. Open the pipeline, check the
  node's *Input Data* section, and watch the **Console → Memory** filter:
  if there is no `Memory: query=… → N hits` line for the run, the active
  node never queried memory.
- **The similarity threshold is too high.** A high **Settings → Memory →
  Similarity threshold** drops loosely-related chunks before they reach
  the prompt. Lower it (or pin the entry — pinned chunks bypass the
  threshold entirely and always sort to the top).
- **Fresher entries won the slots.** Age never disqualifies an entry —
  a chunk that clears the similarity threshold stays retrievable however
  old it is — but freshness is a tie-breaker, so when more entries clear
  the threshold than **Search results (top-K)** allows, newer ones win
  the slots. Raise top-K, raise **Recency half-life** (it widens the
  window in which freshness still counts for much), or pin the entry.
- **Another entry said the same thing.** Entries whose meaning nearly
  matches a better-ranked one are collapsed into it, so a reworded
  duplicate does not spend a second slot. The surviving copy is the
  pinned one if there is one, otherwise the best-ranked.
- **The entry is queued for re-embedding.** A chunk imported under a
  different embedding provider can't be matched until the background
  re-embed finishes (it scores ~0 in the meantime). Give it a moment, or
  force it with **Settings → Memory → Re-embed**.
- **The provider changed.** Switching the **Embedding model** leaves
  existing chunks in the old vector space; run **Re-embed** so the whole
  store shares the active provider's space again. The Memory card shows
  a persistent *re-embed recommended* banner while this mismatch holds.
- **It was never extracted.** Auto-extract only keeps durable facts
  (preferences, events, relationships) and skips small talk and
  near-duplicates. If a fact didn't make the cut, add it by hand with
  **Save to memory** or the **Add memory** FAB.

## "Your data can't be unlocked" appears at startup

All local data is stored in an encrypted database whose key lives in
Android's hardware keystore. In rare situations — typically right after
restoring the app from a backup, after an OS update, or due to a
transient keystore glitch — that key can become temporarily unreadable,
and the app shows a dedicated recovery screen instead of starting:

- **Tap Retry first — possibly more than once.** Keystore failures are
  often transient; if the key becomes readable again, the app opens your
  existing data untouched. Rebooting the device before another retry
  helps in some cases.
- **Erase all data is the last resort.** If retrying never gets past the
  screen, the key is gone for good and the encrypted database can no
  longer be opened by anyone — including the app itself. **Erase all
  data** deletes the database and generates a fresh key so you can start
  over. The action is irreversible and guarded by a typed confirmation.

The app never deletes or re-keys your data automatically in this state:
without the original key the database contents cannot be recovered, so
the decision to wipe is always yours.


---

## See also

- [`faq.md`](faq.md) — what is supported, what is not, and where things live.
- [`user-guide.md`](user-guide.md) — how every screen and feature works.
- [`../SECURITY.md`](../SECURITY.md) — security policy, threat model, and what
  crash reporting collects.
