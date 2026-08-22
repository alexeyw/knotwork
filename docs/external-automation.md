# External automation contract

Knotwork can be asked to run one of your pipelines by another app on the same
device — a Tasker or MacroDroid profile, a shell script over `adb`, anything that
can send a broadcast. The intent is to **complement** an automation app rather
than replace it: the other app decides *when* something should happen, using its
own permissions and its own condition model, and Knotwork does the language-model
part of *what* happens.

> **Status: the contract is live and switchable.** Requests are received, judged
> and journalled exactly as described below, and **Settings → Background &
> triggers → External automation** switches it on, picks the one pipeline outside
> apps may run, and shows every inbound request. Worked Tasker, MacroDroid and
> `adb` examples are in [Worked examples](#worked-examples).

## Switching it on

The contract is off out of the box. To open it:

1. **Settings → Background & triggers → External automation** — the switch raises
   a consent dialog naming what you are agreeing to; it only moves once you
   confirm. Switching it back off is immediate and asks nothing.
2. **Pipeline other apps may run** — pick the one pipeline. Until you do, the
   surface is on but inert: every request is refused, and the row says so.
3. **Request journal** — every inbound request, accepted or refused, with the
   reason in plain language. The row on the settings screen summarises the most
   recent one, which is usually what you came to check.

The journal also carries a **How another app calls this** block with the action
and the extra keys, so a profile can be written without leaving the app.

## The safety model in one paragraph

The entry point is **off by default**, and even switched on it is **inert until
bound**: you pick exactly one pipeline that outside apps may run. That binding is
an **allowlist, not a default** — a request must name the pipeline it wants, and
a request naming anything else is refused rather than quietly redirected to the
bound one. An external call is not a form of approval: human-in-the-loop
confirmation, per-tool risk overrides, and the destructive-tool block all apply
exactly as they do to the app's own background runs.

## Requesting a run

The caller broadcasts `ACTION_RUN_PIPELINE` with the extras below. The target is
named **either** by id **or** by name, never both; the prompt is supplied
**either** as plain text **or** base64-encoded, never both. Anything ambiguous,
missing, or undecodable is refused with a reason rather than repaired to the
nearest plausible reading.

**The smallest call is two keys: a target and a prompt.** The correlation id is
required only of a caller that asked to be answered — it exists to match an
answer to the request that caused it, and a call that wants no answer has
nothing to match. This is not a detail: an automation app's built-in
send-an-intent action has a small, fixed number of extra fields, and a third
mandatory key would put the simplest useful call out of reach of the apps this
contract exists to complement. Omitting the id costs only the correlation chip
on the request's journal row.

**An empty value counts as an absent one.** Every key is trimmed, and a key
whose value is blank is treated as if it had not been sent — because the callers
are templates and shell scripts, where an unfilled variable arrives as an empty
string far more often than anyone means to pass one. So an empty `prompt` is
refused as `PROMPT_MISSING` rather than run as an empty message.

**The base64 form uses the standard alphabet** (RFC 4648 §4: `A`–`Z`, `a`–`z`,
`0`–`9`, `+`, `/`). Padding is optional — `aGk=` and `aGk` both decode to `hi`. The
URL-safe alphabet (`-` and `_`) is **not** accepted: the same characters mean
different bytes in the two alphabets, so decoding one as the other would hand the
pipeline text you never wrote. A payload in the wrong alphabet is refused as
`PROMPT_UNDECODABLE`.

Targeting by id is the stable form. A pipeline's **name is targeted exactly**,
so renaming the pipeline breaks every profile that named it — if you expect to
rename it, use the id, which you can copy from the pipeline's entry in the
library.

<!-- AUTO-GEN:CONTRACT_KEYS -->

| Constant | Wire key | Meaning |
| --- | --- | --- |
| `ACTION_RUN_PIPELINE` | `app.knotwork.android.action.RUN_PIPELINE` | Broadcast action a caller sends to ask the app to run a pipeline. |
| `ACTION_RUN_RESULT` | `app.knotwork.android.action.RUN_RESULT` | Default broadcast action of the terminal callback. Used when a caller asks for a callback without naming an action of its own. |
| `EXTRA_PIPELINE_ID` | `pipeline_id` | Request key: id of the pipeline to run. Mutually exclusive with `EXTRA_PIPELINE_NAME`. |
| `EXTRA_PIPELINE_NAME` | `pipeline_name` | Request key: user-visible name of the pipeline to run. Mutually exclusive with `EXTRA_PIPELINE_ID`. |
| `EXTRA_PROMPT` | `prompt` | Request key: the prompt to run the pipeline on, as plain text. Mutually exclusive with `EXTRA_PROMPT_B64`. |
| `EXTRA_PROMPT_B64` | `prompt_b64` | Request key: the prompt as a base64-encoded UTF-8 string, for callers whose shell quoting cannot carry the text intact. Mutually exclusive with `EXTRA_PROMPT`. |
| `EXTRA_REQUEST_ID` | `request_id` | Request key: caller-minted correlation id. Required only of a caller that asked to be answered (`EXTRA_RETURN_PACKAGE`); a fire-and-forget call may omit it. The callback carries it back under this same key, so a caller reads back the id it wrote. |
| `EXTRA_RETURN_ACTION` | `return_action` | Request key: broadcast action to send the callback with. Optional; defaults to `ACTION_RUN_RESULT`. |
| `EXTRA_RETURN_PACKAGE` | `return_package` | Request key: package to deliver the callback to as an explicit intent. Optional — omitting it is a valid fire-and-forget call. |
| `EXTRA_STATUS` | `status` | Callback key: the status discriminator (`Accepted` / `Completed` / `Failed` / `Rejected` / `Blocked`). |
| `EXTRA_STATUS_REASON` | `reason` | Callback key: the refusal reason, present only for the `Rejected` and `Blocked` statuses. |

<!-- /AUTO-GEN:CONTRACT_KEYS -->

## Receiving the callback

The callback is an ordinary broadcast carrying your `return_action` (or
`ACTION_RUN_RESULT` if you named none), addressed to the package in
`return_package`. Two consequences worth knowing before you write the profile:

- **Register a receiver for that action.** Automation apps that watch for
  arbitrary intents (Tasker's *Intent Received*, MacroDroid's equivalent) already
  do this for you; a receiver declared in an app's manifest works too. A
  fully-qualified component is deliberately never used, because the app cannot
  know your receiver's class and a runtime-registered receiver could not be
  reached that way.
- **Omitting `return_package` is normal.** A shell script over `adb` has nowhere
  to be called back, and asking for no callback is a supported call rather than a
  degraded one.

**The app cannot verify who sent a request.** Android tells a broadcast receiver
the sender's identity only when the *sender* opts in, which automation apps and
`adb` do not, so `return_package` is taken at face value. This is why the payload
is deliberately thin — the request id you chose, the status, and a reason where
there is one — and why it never carries what the run produced. If you want the
run's answer, have the pipeline put it somewhere you can read.

## Worked examples

Three callers, one broadcast. Two things are true of all of them:

- **Name the package.** An intent that names no package is an *implicit*
  broadcast, and since Android 8 a receiver declared in an app's manifest is not
  woken by one. Every example below sets the package for that reason, not for
  tidiness.
- **The package is also which build you are calling.** A debug build installs
  alongside the release one as `app.knotwork.android.debug`, and the action
  string is identical in both. If you have both installed, the package field is
  the only thing deciding which of them runs your pipeline.

### Tasker

A task action of **System → Send Intent**:

| Field | Value |
| --- | --- |
| Action | `app.knotwork.android.action.RUN_PIPELINE` |
| Extra | `pipeline_name:Morning brief` |
| Extra | `prompt:%SOMEVARIABLE` |
| Package | `app.knotwork.android` |
| Target | **Broadcast Receiver** |

Leave *Cat*, *Mime Type*, *Data* and *Class* empty.

**Tasker's *Send Intent* offers three extra fields** in the build this was
checked against — room for the smallest call with one field to spare, or for the
smallest call plus a correlation id.

**A callback needs four**: a target, a prompt, the id, and the package to answer
to. That is one more than the action has. Two ways round it, and the second is
usually the easier one:

- Build the intent yourself in Tasker's *Java Function* action, which has no
  field limit.
- Or let Tasker only **receive**. An *Intent Received* event does not care how
  the request was sent, so the request can come from a shell over `adb` — which
  has no field limit either — while Tasker handles the answer.

To receive the callback, use an **Intent Received** event with *Action* set to
whatever you passed as `return_action` (or `app.knotwork.android.action.RUN_RESULT`
if you passed none). Tasker hands each extra to the task as a local variable
named after its key — lowercased, with anything non-alphanumeric turned into an
underscore — so the callback's three keys arrive as `%request_id`, `%status` and
`%reason`. If a name ever reads differently in your Tasker version, that
transformation is the rule to apply, not these three spellings.

### MacroDroid

An action of **Send Intent** with *Intent target* set to **Broadcast**:

| Field | Value |
| --- | --- |
| Action | `app.knotwork.android.action.RUN_PIPELINE` |
| Package | `app.knotwork.android` |
| Extra 1 | `pipeline_name` = `Morning brief` (String) |
| Extra 2 | `prompt` = `Summarise today's calendar` (String) |
| Extra 3 | `request_id` = `md-0001` (String) |
| Extra 4 | `return_package` = `com.arlosoft.macrodroid` (String) |

MacroDroid's *Send Intent* takes several extras, so the callback keys fit
comfortably alongside the two the call needs. Any value can be magic text
instead of a literal, which is how you pass a variable through. Leaving the
package empty broadcasts to every app on the device rather than to this one —
fill it in.

The callback arrives on the **Intent Received** trigger, configured with the
same action you sent as `return_action`.

### adb

**Quote the whole command.** `adb shell` does not pass your arguments through:
it joins them with spaces into one string and hands that to the device's shell,
which splits it again. Quotes you write on your own machine are consumed before
`adb` ever sees them, so a value containing a space arrives as several
arguments — `--es prompt 'What is on my calendar today?'` reaches the app as the
prompt `What`, and a pipeline named `Morning brief` is looked up as `Morning`
and reported missing. Wrapping the entire command in double quotes, as below,
keeps the inner quotes intact all the way to the device.

The minimum call, from a shell:

```bash
adb shell "am broadcast -a app.knotwork.android.action.RUN_PIPELINE -p app.knotwork.android --es pipeline_name 'Morning brief' --es prompt 'What is on my calendar today?'"
```

When the text fights your shell's quoting, send it base64-encoded instead:

```bash
adb shell "am broadcast -a app.knotwork.android.action.RUN_PIPELINE -p app.knotwork.android --es pipeline_name 'Morning brief' --es prompt_b64 '$(printf %s 'Anything with "quotes", $signs and newlines' | base64 | tr -d '\n')'"
```

The `$(...)` still runs on your own machine — the device never sees it — so the
encoded text is substituted before the command is sent.

**`tr -d '\n'` is not decoration.** GNU `base64` wraps its output at 76
characters, and the decoder here accepts the RFC 4648 alphabet without embedded
whitespace — a wrapped payload is refused as `PROMPT_UNDECODABLE`, which reads
like a mangled prompt rather than a wrapped one.

A shell script has nowhere to be called back, so both examples omit
`request_id` and `return_package`. That is a complete call, not a degraded one.

## Statuses

A request is answered with one of the statuses below. `Rejected` and `Blocked`
carry a reason; the other three do not. The callback is delivered only when the
request asked for one, and it never carries the content of the run — only the
request id (under the same `request_id` key the request used), the status, and
the reason where there is one.

<!-- AUTO-GEN:STATUSES -->

| Status | Meaning |
| --- | --- |
| `Accepted` | The request was admitted and a background run was enqueued. |
| `Completed` | The run started by the request finished successfully. |
| `Failed` | The run started by the request failed, was cancelled, or was interrupted. |
| `Rejected` | The request was refused before anything was started, because of what the request said or how the app is configured. Retrying the identical request against the identical configuration yields the identical refusal. |
| `Blocked` | The request was well-formed and permitted, but a safety ceiling refused it at this moment. Unlike `Rejected` this is a statement about the moment, not about the request: the same request may be admitted later. It is deliberately not a silent enqueue — a caller whose request was dropped on the floor cannot tell that from one that ran. |

<!-- /AUTO-GEN:STATUSES -->

## Refusal reasons

<!-- AUTO-GEN:REJECTION_REASONS -->

| Reason | Meaning |
| --- | --- |
| `CONTRACT_DISABLED` | The external-automation contract is switched off (its default state). |
| `SURFACE_NOT_BOUND` | No pipeline is bound to the external-automation surface (inert until bound). |
| `TARGET_NOT_ALLOWED` | The request named a pipeline other than the one bound to the surface. |
| `TARGET_MISSING` | The request carried no target pipeline at all. |
| `TARGET_AMBIGUOUS` | The request named the target twice, by id and by name, and the two cannot be reconciled. |
| `UNKNOWN_ACTION` | The request carried an action this contract does not define. |
| `PROMPT_MISSING` | The request carried no prompt for the pipeline to run on. |
| `PROMPT_AMBIGUOUS` | The request carried the prompt twice, in plain and base64 form. |
| `PROMPT_UNDECODABLE` | The base64 prompt could not be decoded. |
| `REQUEST_ID_MISSING` | The request asked to be answered but carried no request id to correlate the answer with. A call that asks for no callback may omit the id. |
| `RATE_LIMITED` | Too many external requests were accepted within the rate window. |
| `RETURN_PACKAGE_MISMATCH` | The request asked for its callback to be delivered to a package other than the one the system reported as the sender. |

<!-- /AUTO-GEN:REJECTION_REASONS -->

## What an accepted request does

An accepted request is enqueued as an ordinary background run — the same path the
app's own scheduled tasks and automation triggers take. In practice that means:

- **The gates still apply.** A tool the run wants to use is confirmed with you if
  its risk calls for it, per-tool risk overrides are honoured, and the
  destructive-tool block is not bypassed. An external call asks for a run; it does
  not approve what the run then wants to do. If a confirmation goes unanswered
  while nobody is at the device, the run parks and waits — and the callback
  arrives whenever it finally settles, which may be hours later.
- **Results accumulate in one chat.** Every external run lands in the same
  conversation rather than starting a new one per request.
- **Requests are rate-limited.** Beyond a fixed number of accepted requests per
  hour, further ones are answered `Blocked` until the window moves. Refused
  requests do not count toward it.
- **Every request is recorded**, admitted or refused, so a profile that silently
  does nothing can be diagnosed from inside the app.

## Stability

The action strings, extra keys, status names, and reason names above are frozen
once released. A profile you write against them keeps working; renaming any of
them would break every profile already written, with no way for the author to
see why. The tables on this page are generated from the source declarations and
`./gradlew check` fails if they drift, so what you read here is what the app
actually accepts.
