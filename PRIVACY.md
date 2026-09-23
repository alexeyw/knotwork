# Privacy Policy — Knotwork

**Effective date:** 20 September 2026
**Applies to:** the Knotwork Android application (package `app.knotwork.android`),
both published distributions — `full` and `foss` — from version `0.7.1` onward.

---

## Summary

Knotwork is an on-device AI agent. There is **no account, no sign-in, and no
server operated by the developer**. Nothing is uploaded for the app to work,
and there is no back end that could hold your data.

The app processes your conversations **on your phone**, using a language model
you download to the device. Data leaves the device only along these paths — a
cloud model carrying your own API key (in a pipeline step, or as the embedding
model for memory), an MCP server you added, a model download, an outbound
request from one of the two built-in tools that reach the network, or (in the
`full` build only) crash reports you opted in to. All of them are off until you
configure them except one: the built-in Wikipedia lookup is on from the first
launch, and section 3.4 says so and says how to turn it off. Each path is
listed below with what it sends, where, and how to stop it.

This document is the privacy policy. The engineering-level threat model, the
attack surfaces the app defends against, and what is explicitly *out* of scope
live in [SECURITY.md](SECURITY.md); per-feature behaviour is described in the
[user guide](docs/user-guide.md).

---

## 1. Who is responsible

Knotwork is an independent open-source project maintained by a single
developer. There is no company, no data-processing infrastructure, and no
analytics back end behind it.

- Source code and issue tracker: <https://github.com/alexeyw/knotwork>
- Privacy point of contact: <alexeyw+knotwork@gmail.com>
- Security reports: the private advisory channel described in
  [SECURITY.md](SECURITY.md#reporting-a-vulnerability) — not the address above,
  so that a vulnerability report stays private until a fix ships

Because the developer operates no server, the developer receives **none** of
the data described in section 3 except crash reports (section 3.5), and those
only if you switch them on.

---

## 2. What the app stores on your device

All of the following is created by your use of the app and stays in the app's
private storage on your device:

- Chat sessions and messages, including image and audio attachments you add.
- Long-term memory entries derived from your conversations.
- Pipelines, presets, prompt templates, and their run traces.
- Triggers, scheduled tasks, and the trigger journal.
- Settings, including the list of cloud providers, MCP servers, and allowed
  domains you configured.
- Local usage statistics (section 4).
- API keys, the Hugging Face access token, and MCP credentials you entered.

**Encryption at rest.** The local database — chats, memory, run traces — is
encrypted with SQLCipher. API keys, the Hugging Face token, and MCP credentials
are sealed with AES-GCM under a dedicated Android Keystore key. Details and
limits are in [SECURITY.md](SECURITY.md#threat-model).

**Deletion.** Uninstalling the app removes all of it. Individual chats,
memories, pipelines, and run history can be deleted from inside the app, and
run history is also pruned automatically according to the retention setting.

---

## 3. What can leave your device

None of this is required for the app to work, and none of it sends data to the
developer. Every item here is **off until you configure it** with one exception,
called out where it belongs: the built-in Wikipedia lookup in section 3.4 is on
from the first launch.

### 3.1 Cloud model providers (opt-in, your own key)

If you place a cloud node in a pipeline and enter your own API key, the text
that node processes — the prompt, the relevant conversation context, and any
attachments that node consumes — is sent to the provider you selected
(OpenAI, Anthropic, Google Gemini, DeepSeek, or an Ollama endpoint you name).

The request goes **directly from your device to that provider**. It does not
pass through any infrastructure of the developer. Once it arrives, the
provider's own privacy policy and data-retention terms govern it — including
whether they retain or train on it. Review the policy of whichever provider
you choose.

Two other paths reach the same providers, both also opt-in:

- **Memory embeddings.** If you choose OpenAI or Ollama as the *Embedding model*
  in the memory settings, the text being embedded is sent to that service: a
  memory as it is saved, and the query used to search memory during a run. This
  happens whether or not the pipeline has a cloud node. The default embedding
  model runs on the device.
- **The `delegate_task` tool** hands a subtask to a provider you have a key for.
  It counts as a sensitive tool call, so it waits for your approval unless you
  set *Approve tool calls* to *Never*.

Apart from those two, a pipeline without a cloud node never contacts a cloud
provider. Cloud nodes are visible in the pipeline editor, so you can see whether
one is present.

**Block network from local model** (Settings → Tools & workspace) keeps every
path in this section on your own network: no cloud provider is contacted — memory uses the
on-device embedding model instead — and an Ollama endpoint is used only at
`localhost` or a private IP address on your own network. It also withholds the
built-in `search_tool` (3.4), which is the one tool it covers. It does not
affect MCP servers (3.2), model downloads (3.3) or the `http_request` tool
(3.4) — each of those is something you set up, and each has its own control.

### 3.2 MCP servers (opt-in)

If you add a Model Context Protocol server, the app connects to that server's
URL to list its tools and to invoke them. Tool arguments produced during a run
— which may contain content from your conversation — are sent to that server,
along with any credentials you stored for it. The operator of that server
determines what happens to that data.

What the server sends back stays on your device, like the rest of a
conversation: the tool results are saved in the chat history, and the tool list
and results become part of what the model reads next. That includes a cloud
model, if a later step in the run uses one (see 3.1). The app keeps only a
bounded amount of each: a result is cut at the *Largest tool response* setting,
and a tool list is limited in size.

### 3.3 Model downloads

Downloading an on-device model contacts the host serving it: Hugging Face for
the models offered in the app, or any URL you paste yourself. These are
ordinary file downloads. If you saved a Hugging Face access token, it goes only
to Hugging Face (`huggingface.co`), with its model downloads — never to a link
you paste for another host. Browsing the catalogue is anonymous.

### 3.4 Outbound requests from tools

Two built-in tools reach a destination of their own, and this section is about
those two. A third tool also leaves the device — `delegate_task` — but it goes
to the cloud provider whose key you entered, so it is described in section 3.1
with the other paths to that provider. Every remaining built-in works inside the
device: the workspace tools read and write files here, and `schedule_task` only
asks the device to run something later.

**`search_tool` — a Wikipedia lookup, and the one path in this document that is
on by default.** When a pipeline step calls it, the app requests
`https://<language>.wikipedia.org/w/api.php` with a search term. The language
code is checked first, so the request can only go to a Wikipedia address. The model
writes that term from what it is working on, so it can carry wording from your
conversation. Nothing else of yours goes with it — no account, no device
identifier, no credentials — though, like any web request, it shows your IP
address to the Wikimedia Foundation, whose servers answer it.

Being on by default is the part worth stating plainly: the pipeline the app
creates for you on first launch calls this tool for questions it judges
factual, so the lookup can happen before you have configured anything. The tool
is classified read-only, so it does not stop for a confirmation. Either of two
switches ends it — **Settings → Tools & workspace → Block network from local
model**, which withholds this tool along with the cloud paths, or the tool's
own switch on the **Tools** screen.

**`http_request` — off until you name a destination.** It can reach only hosts
you have added to the allowed-domains list; while that list is empty the tool
is not offered to the model at all. A call waits for your approval, showing
the destination and the arguments before anything is sent, unless you set
*Approve tool calls* to *Never* — then a `GET` goes without asking, while a
`POST`, `PUT` or `DELETE` still asks. The layered
restrictions on this path are documented in
[SECURITY.md](SECURITY.md#outbound-http-and-the-exfiltration-chain).

### 3.5 Crash reporting (`full` build only, opt-in, off by default)

The `full` distribution can send anonymous crash reports to Firebase
Crashlytics (Google) — **only** after you enable
*Settings → Privacy → Send anonymous crash reports*, which is off by default.

When enabled, a report may contain: the stack trace, device model, Android and
app version, and two identifiers describing which pipeline and model were
active. It **never** contains message content, prompts, model replies, memory
entries, tool inputs or outputs, API keys, or anything stored in the encrypted
stores. Full detail is in
[SECURITY.md](SECURITY.md#what-is-collected-crash-reporting).

You can revoke consent at any time from the same setting. The `foss`
distribution contains no crash-reporting dependency at all and hides the
setting.

### 3.6 External automation (another app on this device)

Another app on the same device — a Tasker or MacroDroid profile, a shell script
over `adb` — can ask Knotwork to run one of your pipelines. This is **off by
default**; it does nothing until you switch it on in
*Settings → Background & triggers → External automation* and bind exactly one
pipeline that outside callers may reach.

While it is on, data moves in two directions and neither leaves the device:

- **Inbound.** The caller's broadcast carries the prompt text into Knotwork.
  That text is another app's to send, so what it contains is governed by that
  app, not by this one; here it is treated like any other input.
- **Outbound (optional).** If the request asked to be answered, Knotwork sends
  one broadcast to **the package that request named** — an explicit intent, never
  a general broadcast — carrying the request id, the admission status and, when
  refused, the reason. It never carries the prompt, the model's reply, or
  anything the run produced. Note the precision: Android does not tell the app
  who sent a broadcast unless the sender opts in, so the address is a claim made
  *in* the request rather than a verified identity. That is exactly why the
  payload is this thin — an unverified address must not be able to have anything
  of yours read back to it. A request that names no package gets no callback at
  all, which is the normal shape for a shell script.

Every inbound request, accepted or refused, is written to a local journal on
the device. The vocabulary of the contract is documented in
[docs/external-automation.md](docs/external-automation.md).

### 3.7 Journal export (you share the file)

The trigger journal and the external-request journal can be exported to a file
through the system share sheet, by an explicit action you take in the app.
There is no network on that path — a build-time architecture check fails the
build if any network dependency reaches the export code — and the exported file
does not contain the content of your runs.

Where the file goes after the share sheet is decided by the app you pick, and
that app's own policy applies from that point on.

---

## 4. What never leaves your device

- **Usage statistics.** The in-app statistics — how many pipelines you ran,
  which days you were active, and so on — are computed and stored on the
  device and are never transmitted. This is enforced at build time: an
  architecture test fails the build if any network dependency ever reaches
  that code. Exporting the statistics as a file is a manual action you take,
  and the resulting file goes wherever you send it.
- **Your keys and credentials.** API keys, the Hugging Face token, and MCP
  credentials are used only to authenticate to the service you entered them
  for. They are never sent anywhere else, and a saved provider key found in an
  outgoing `http_request` causes that request to be refused outright.
- **Attachments.** Images and audio you attach are processed by the on-device
  model unless a cloud node in your pipeline consumes them (section 3.1).
- **Nothing is sent to the developer on its own.** The only two paths that can
  reach the developer at all are the optional crash reports of section 3.5 and
  a report you compose yourself: flagging a model response opens a prefilled
  message you can edit, and it travels only if you send it. Neither happens in
  the background.

---

## 5. Permissions and why they are requested

| Permission | Why | When |
|---|---|---|
| Internet, network state | Model downloads, and the opt-in cloud/MCP/tool paths above | Always declared; used only for the paths you configure |
| Notifications | Run progress, background-run results, confirmation prompts | Asked on first run |
| Microphone | Voice input you record for a message; processed on-device | Asked when you first record audio |
| Approximate and precise location | Only to match a Wi-Fi trigger against specific network names — Android ties Wi-Fi identity to location | Asked only if you scope a Wi-Fi trigger to named networks |
| Foreground service, wake lock | Keeping a model download or a running pipeline alive while the screen is off | Used only while such work is running |
| Run at boot | Re-arming your scheduled triggers after a restart, so an automation you set up does not silently stop | Declared by the scheduling library; used only if you created a trigger |
| Execute app functions | Calling tool functions exposed by apps on the device | Used only when a pipeline invokes such a tool |

The Wi-Fi network name obtained under the location permission is used on the
device to decide whether a trigger fires, and never leaves it. Background
location is **not** requested.

---

## 6. Children

Knotwork is not directed to children. The intended audience is adults
(18 and over). The app does not knowingly process data from children, and it
collects no age information because it collects no personal profile at all.

---

## 7. Legal bases and your rights

Since the developer operates no server and receives no personal data, there is
no data controller holding a copy of your information to grant access to,
export, or erase on request — the data is in your possession, on your device,
and you can inspect, export, or delete it there at any time.

Where crash reporting is enabled (section 3.5), the legal basis is your
explicit consent, which you may withdraw at any time in the app. Data you send
to a cloud provider or an MCP server is governed by your relationship with that
operator; exercise any rights over it with them directly.

---

## 8. Third parties

The app contacts a third party only along the paths in section 3. Depending on
what you configure — and, for the first entry below, on nothing at all — those
may be:

- The cloud model provider whose key you entered (OpenAI, Anthropic, Google,
  DeepSeek, or an Ollama endpoint you name), for pipeline steps, memory
  embeddings or `delegate_task`.
- Hugging Face, or any host you paste a model URL for.
- MCP servers you add.
- The Wikimedia Foundation, for the built-in `search_tool` lookup described in
  section 3.4 — the one entry here that does not wait for you to configure
  something.
- A host you added to the allowed-domains list for the `http_request` tool.
- Google (Firebase Crashlytics), in the `full` build, if you opted in to crash
  reporting.

Each is governed by its own privacy policy. The developer has no agreement with
them on your behalf and receives nothing from them.

---

## 9. Changes to this policy

Material changes are recorded in [CHANGELOG.md](CHANGELOG.md) alongside the
release that introduces them, and the effective date at the top of this
document is updated. The version history of this file is public in the
repository, so any change can be diffed.

---

## 10. Contact

Questions about this policy, or a request about your data: write to
<alexeyw+knotwork@gmail.com>, or open an issue at
<https://github.com/alexeyw/knotwork/issues> if the question is not private.
Suspected vulnerabilities should go through the private channel described in
[SECURITY.md](SECURITY.md#reporting-a-vulnerability) instead.
