# Release engineering

This document captures how the on-device agent is built, signed, and shipped
for distribution. It is intentionally a single-page playbook — every step
should be runnable verbatim from a clean checkout.

## 1. Variants at a glance

The app has two build types (`debug` / `release`) and a **`distribution`
product-flavour dimension** with two flavours, so a full build variant is
`<flavour><BuildType>` (e.g. `fullRelease`, `fossDebug`):

| Flavour | Crash reporting          | Google/Firebase dependency | Intended channel        |
|---------|--------------------------|----------------------------|-------------------------|
| `full`  | Firebase Crashlytics (opt-in) | yes (`fullImplementation`) | Play Store / direct APK |
| `foss`  | none (no-op)             | **none** — provably absent | **F-Droid** (§8)        |

`full` is the default flavour (`isDefault = true`), so plain commands and
Android Studio's variant selector resolve to it.

| Build type | Minified | Resource-shrunk | Signing                             |
|------------|----------|------------------|-------------------------------------|
| `debug`    | no       | no               | Android debug key                   |
| `release`  | yes (R8) | yes              | Release keystore (debug fallback) * |

\* See §3 below. The `release` build type uses a dedicated `signingConfigs.release`
when its credentials are provisioned via `local.properties` or environment
variables; when they are absent it **falls back to the debug keystore** so a
clean checkout still builds. A debug-signed `release` artefact is suitable for
sideloading on a developer device but **not** acceptable for Play Store upload.

## 2. Building locally

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# Universal arm64-v8a APK (used for sideload smoke-tests).
./gradlew :app:assembleFullRelease   # Play / direct-APK build (Firebase)
./gradlew :app:assembleFossRelease   # F-Droid build (no Google/Firebase) — see §8

# Android App Bundle (the Play Store upload format; full flavour only).
./gradlew :app:bundleFullRelease
```

Outputs (the flavour name is part of the path):

- `app/build/outputs/apk/full/release/app-full-release.apk`
- `app/build/outputs/apk/foss/release/app-foss-release.apk`
- `app/build/outputs/bundle/fullRelease/app-full-release.aab`

The release variant strips every ABI except `arm64-v8a`
(`build.gradle.kts → buildTypes.release.ndk.abiFilters`). The reason is the
inference engine rather than the API floor: `litertlm-android` ships
`jni/arm64-v8a` and `jni/x86` only, with no `armeabi-v7a` binary, so a 32-bit
ARM device cannot run the app at any `minSdk`. Shipping
`armeabi-v7a` / `x86` / `x86_64` would add ~65 MB for zero benefit.
Emulator-driven smoke-tests should use the `debug` variant instead, which
keeps every ABI.

## 3. Signing

The `release` buildType is wired to a dedicated `signingConfigs.release` whose
credentials are resolved at configuration time from `local.properties` first
and environment variables second (`build.gradle.kts → resolveReleaseSigning()`).
The recognised keys are:

| Key                         | Meaning                                   |
|-----------------------------|-------------------------------------------|
| `RELEASE_KEYSTORE_PATH`     | Path to the keystore, relative to repo root. |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore (store) password.                |
| `RELEASE_KEY_ALIAS`         | Alias of the signing key in the keystore. |
| `RELEASE_KEY_PASSWORD`      | Password protecting the signing key.      |

If **any** key is missing/blank, or the resolved keystore file does not exist,
`resolveReleaseSigning()` returns `null` and the `release` buildType falls back
to the debug keystore:

```kotlin
signingConfig = signingConfigs.findByName("release")
    ?: signingConfigs.getByName("debug")
```

So a clean checkout without a provisioned key still produces a (debug-signed)
release artefact — configuration never fails for the lack of a key. The
credential values are never committed: `.gitignore` blocks every keystore
extension (`*.jks` / `*.keystore` / `*.p12` …) plus `local.properties`,
`keystore.properties`, and `secrets.properties`.

### Current distribution state

The debug fallback exists so that a clean checkout still builds; it is **not** a
path any published artefact can take. `release.yml` refuses to run without
release credentials and re-checks the signer of every artefact it produces
(§3 *Provisioning the keystore in CI*), so everything attached to a GitHub
Release from that workflow onwards is release-signed.

Artefacts published **before** that workflow existed were debug-signed via the
fallback. That was acceptable for the pre-release sideload channel because:

- the debug signing identity is well-known and not a secret;
- sideloading does not require a stable signing identity across releases
  (Android only enforces that the *signer* matches the previously-installed
  copy, and a clean install is acceptable until v1.0);
- a leaked debug-keystore signature cannot impersonate a Play Store
  upload — Play Store rejects debug-signed AABs.

The first release-signed build will use a different signer than the historical
debug-signed builds, so an in-place upgrade from a debug-signed install will be
rejected with a signature mismatch — see the *Pre-release notice* in
[README.md](../README.md). Plan for a clean install at that transition.

### Generating the release keystore

The keystore is created **outside VCS** on the maintainer's machine:

```bash
keytool \
    -genkeypair \
    -v \
    -keystore release.keystore \
    -keyalg RSA \
    -keysize 4096 \
    -validity 36500 \
    -alias agent-release
# Distinguished Name prompt:
#   CN: Knotwork
#   OU: Releases
#   O: <your org or personal name>
#   L / ST / C: as appropriate
```

Move the resulting `release.keystore` into `app/` (covered by `.gitignore`)
and record the path + passwords + alias in `local.properties`:

```properties
RELEASE_KEYSTORE_PATH=app/release.keystore
RELEASE_KEYSTORE_PASSWORD=••••••
RELEASE_KEY_ALIAS=agent-release
RELEASE_KEY_PASSWORD=••••••
```

The Play Store also requires App Signing by Google Play — upload the keystore
once during the first release, then Play Store rotates the in-app signing
certificate on every subsequent release.

### Provisioning the keystore in CI

`.github/workflows/release.yml` resolves the same four signing keys as above
from the environment rather than from `local.properties`, so they — plus the
real Firebase config — are injected as **repository secrets** instead of
checked-in files. This is a one-time setup; §9 is the per-release procedure
that uses it.

Everything below is run **once**, on the machine that holds the keystore.

**1 — Base64-encode the keystore.** The secret has to be a single line, so
strip the newlines the encoder inserts:

```bash
base64 < app/release.keystore | tr -d '\n' | pbcopy   # macOS; `xclip -sel c` on Linux
```

**2 — Read the certificate fingerprint.** This is what every released artefact
will be checked against, so take it from the keystore itself rather than from
a previous build:

```bash
keytool -list -v -keystore app/release.keystore -alias agent-release \
    | grep 'SHA256:'
# SHA256: A1:B2:C3:…
#         ^^^^^^^^^^ copy the fingerprint only, WITHOUT the `SHA256:` label.
```

Colons and letter case are stripped before the comparison, so
`A1:B2:C3:…`, `a1b2c3…` and `A1B2C3…` are all accepted.

**3 — Base64-encode the real `google-services.json`.** The copy committed at
`app/google-services.json` is a **placeholder**: it keeps a clean checkout
building and keeps the real Firebase project id out of the repository, but a
`full` build made with it has crash reporting pointed at a project that does
not exist. Download the real file from the Firebase console (project settings →
your Android app → `google-services.json`) and encode it the same way:

```bash
base64 < ~/Downloads/google-services.json | tr -d '\n' | pbcopy
```

**4 — Store them.** Repository → *Settings* → *Secrets and variables* →
*Actions*:

| Name                          | Kind     | Value                                                    |
|-------------------------------|----------|----------------------------------------------------------|
| `RELEASE_KEYSTORE_BASE64`     | secret   | Output of step 1.                                        |
| `RELEASE_KEYSTORE_PASSWORD`   | secret   | Keystore (store) password.                               |
| `RELEASE_KEY_ALIAS`           | secret   | Key alias, e.g. `agent-release`.                         |
| `RELEASE_KEY_PASSWORD`        | secret   | Key password.                                            |
| `GOOGLE_SERVICES_JSON_BASE64` | secret   | Output of step 3.                                        |
| `RELEASE_CERT_SHA256`         | **variable** | Fingerprint from step 2, without the `SHA256:` label (colons and case are ignored). |

The fingerprint is a **variable**, not a secret, on purpose. It is public
information — it is embedded in every APK the project has ever shipped — and
a secret would be masked as `***` in the workflow log, turning the one check
that is supposed to be readable into an unreadable one.

**What the workflow does with them.** The keystore is decoded into
`$RUNNER_TEMP`, never into the checkout, so nothing under the workspace can
sweep it into an uploaded artefact, and it is deleted in a step that runs even
when the build fails. Passwords reach `keytool` on stdin rather than in argv.
The real `google-services.json` overwrites the placeholder for the `full`
build only and is restored from Git afterwards; the `foss` build is a separate
Gradle invocation that never loads the Google plugins at all (§8), and the
`debug` build type keeps its own committed placeholder so dogfooding never
reports into the real Firebase project.

**Why the setup is checked before the build, not after.** A missing or blank
credential does not fail the build — `resolveReleaseSigning()` returns `null`
and the release build silently falls back to the debug keystore (§3). So the
workflow fails loudly on an unset secret, and verifies the keystore's
fingerprint against `RELEASE_CERT_SHA256` *before* starting an hour-long build
rather than discovering the wrong signer at the end of it.

The `check.yml` gate does **not** build release artefacts and needs none of
these secrets.

### Verifying the signature

`release.yml` verifies every artefact it publishes and fails the release on a
mismatch, so this is the manual equivalent — useful for a locally built
artefact or for auditing something already downloaded.

For an APK, use `apksigner` from the Android SDK build-tools:

```bash
apksigner verify --print-certs --verbose \
    app/build/outputs/apk/full/release/app-full-release.apk
```

An AAB carries only a v1 (JAR) signature, which `apksigner` does not read;
`keytool` prints the signer certificate of a signed JAR directly:

```bash
keytool -printcert -jarfile app/build/outputs/bundle/fullRelease/app-full-release.aab
```

In both cases check that the printed SHA-256 fingerprint matches
`RELEASE_CERT_SHA256` (and the one Play Console registered for the app). The
debug keystore prints `CN=Android Debug`, so the DN is the quickest way to spot
an accidental fallback to debug signing.

## 4. R8 minification + resource shrinking

Enabled on `release` (`isMinifyEnabled = true`, `isShrinkResources = true`).
Keep-rules live in `app/proguard-rules.pro`, organised by subsystem with a
KDoc-style banner per section explaining *why* the rule is needed. The
short version:

| Subsystem                | Why it needs explicit keeps                                  |
|--------------------------|---------------------------------------------------------------|
| `kotlin.Metadata`        | Used by every reflection-driven library (Koog, serialization). |
| `kotlinx.serialization`  | `$$serializer` synthetic + `serializer(...)` lookup.          |
| Gson                     | Round-trip of `app_functions_*.xml` + chat-export payloads.   |
| MediaPipe / LiteRT       | JNI bindings reach Java classes by name — R8 has no AST view. |
| SQLCipher                | `net.zetetic:sqlcipher-android` loads its `.so` by reflection. |
| Koog                     | Heavy reflection over node / tool / pipeline graph definitions.|
| Ktor                     | Transitive HTTP layer underneath every Koog cloud client.     |
| AppFunctions             | `*_AppFunctionInventory` / `*_AppFunctionInvoker` KSP outputs are loaded by `androidx.appfunctions` via reflection. |
| Hilt                     | Aggregated component classes occasionally over-shrunk on full mode. |
| Room                     | `*_Impl` DAOs / database instantiated reflectively.           |
| OpenTelemetry incubator  | Optional symbols referenced from Koog's OTel logging plumbing — kept under `-dontwarn` since the runtime path is never hit. |

If R8 starts stripping something at runtime, drop a new section into
`proguard-rules.pro` rather than scattering rules across the file, and
include a one-line comment on the symptom that triggered the keep.

## 5. APK size breakdown

> A **point-in-time snapshot**, measured on 14 September 2026 from a local
> `./gradlew :app:assembleFullRelease` of the 0.10.0 development line. Byte
> counts move with every dependency bump; the published release assets are the
> numbers to trust for a given version.

`app-full-release.apk` measures **60.7 MiB on disk** (63,613,649 bytes). The
published full APKs, for comparison:

| Release | Full APK (bytes) | What changed |
|---------|------------------|--------------|
| 0.7.3   | 62,184,669 | — |
| 0.8.0   | 77,902,632 | **+15.7 MB, unnoticed at the time.** MediaPipe `tasks-text` 1.0.0 began shipping `libmediapipe_tasks_textgenai_jni.so` (14.4 MB), the native half of its new `TextSummarizer` / `TextProofreader`. |
| 0.9.0   | 77,930,200 | — |
| 0.10.0  | 63,613,649 (local build, before release) | That library is excluded from packaging (below). |

The 30 MB target from the original plan is **not achievable** with the current
dependency set: the native libraries and the bundled embedding model account for
~41 MB before a single line of agent code.

Top contributors (uncompressed bytes inside the APK, arm64-v8a only):

| Entry                                          | Size    | Notes                                     |
|------------------------------------------------|---------|-------------------------------------------|
| `lib/arm64-v8a/liblitertlm_jni.so`             | 21.5 MB | LiteRT-LM runtime and tokenizer JNI (the separate `libLiteRt.so` and GPU accelerator of earlier versions are folded into it). |
| `classes.dex`                                  | 12.3 MB | App + Koog agents (post-R8).              |
| `lib/arm64-v8a/libmediapipe_tasks_jni.so`      | 11.0 MB | MediaPipe Tasks (text embedding host).    |
| `classes2.dex`                                 |  8.1 MB | App + Koog agents (overflow DEX).         |
| `assets/universal_sentence_encoder.tflite`     |  6.1 MB | Bundled embedding model (long-term memory). |
| `lib/arm64-v8a/libsqlcipher.so`                |  2.1 MB | SQLCipher engine.                         |
| `resources.arsc`                               |  1.0 MB | Compiled resources.                       |
| `classes3.dex`                                 |  0.9 MB | Overflow DEX.                             |
| Everything else combined                       | ~1.0 MB | Presets, bundled documentation, DataStore native, baseline profiles, manifest. |

What we already did to keep this in check:

- **arm64-v8a only.** Other ABIs would more than double the artefact.
- **R8 full mode + resource shrinking.** Saves ~2 MB on DEX vs. unminified.
- **Strip Jansi non-Android natives.** `org/fusesource/jansi/internal/native/{Windows,Mac,Linux,FreeBSD}/**` and `META-INF/native-image/jansi/**` are dropped via the `android.packaging.resources` exclude list — Jansi ships through Koog's logger and only its ANSI-escape rendering runs on JVM hosts.
- **Drop MediaPipe's text-generation library.** `**/libmediapipe_tasks_textgenai_jni.so` is excluded via `android.packaging.jniLibs`. Only `TextSummarizer` and `TextProofreader` load it — the app uses `TextEmbedder`, which loads `libmediapipe_tasks_jni.so` — and text generation belongs to LiteRT-LM. An exclude is a file-name pattern that stops matching silently if the library is renamed, so the release workflow asserts it on the artefact (§9): the library absent, and the LiteRT-LM and MediaPipe Tasks libraries present.

Future wins (left out of scope for now):

- **Move the universal-sentence-encoder model to a first-run download.** Wins ~6 MB; complicates first-run UX. Tracked separately.
- **Promote Koog clients to optional dynamic features.** Cloud LLM clients are bundled today; ~1 MB per provider could move out for users who only use the local model.

## 6. App Bundle build (Play Store upload)

```bash
./gradlew :app:bundleFullRelease
# Output: app/build/outputs/bundle/fullRelease/app-full-release.aab
```

The AAB is the format Play Store wants; per-device APKs delivered through
Play Store are smaller than `app-full-release.apk` because they only ship the
ABI + density resources the target device needs. Only the `full` flavour has an
AAB build — the `foss` flavour goes to F-Droid as an APK (§8).

To inspect what Play Store would deliver to a specific device:

```bash
# bundletool is in the Android SDK cmdline-tools.
bundletool build-apks \
    --bundle=app-full-release.aab \
    --output=app.apks \
    --mode=universal
bundletool get-size total --apks=app.apks
```

## 7. Quality gate before release

```bash
./gradlew check :buildSrc:test :app:lintFullRelease :app:bundleFullRelease
```

- `check` aggregates `detekt` and the type-resolution detekt tasks,
  `ktlintCheck`, lint, the unit-test suite for **both** flavours
  (`testFullDebugUnitTest` + `testFossDebugUnitTest`), the design-system
  screenshot comparison (`:catalog:verifyRoborazziDebug`),
  `koverVerifyFullDebug` (coverage is measured on the representative `full`
  variant; the flavours share every measured source), and the documentation,
  version and store-listing gates. The full list, with what each one guards, is
  in [`static-analysis.md`](static-analysis.md).
- `:buildSrc:test` runs the tests of the build logic behind those gates. It is a
  separate build, so `check` cannot reach it.
- `lintFullRelease` re-runs lint on the release configuration (catches issues
  hidden by debug-only resources).
- `bundleFullRelease` confirms R8 + resource shrinking still produce a valid AAB.

CI gates every pull request into `main` on `check :buildSrc:test` only; the two
release-configuration tasks run nowhere but here and in `release.yml`. The
integration PR adds the instrumented emulator run
([`testing.md`](testing.md) § *The instrumented gate*) and the manual smoke test
on the reference device described in [`testing.md`](testing.md) § *What the
automated gate does NOT cover*.

`release.yml` (§9) runs `check` as its first job and gets the release-config
lint through `lintVital<Variant>Release`, which AGP wires into every release
assemble. That is the fatal-severity subset of `lintFullRelease`, so running the
command above before tagging still buys something the release pipeline does not.

### Two guards on the minified artefact

`./gradlew check` never runs R8's output, so a release-only defect has exactly
one place left to be caught: the artefact itself. Two tasks run after release
packaging, and they check different properties — the second exists because the
first was green while the app was broken.

- **`verify<Variant>KeepRules`** reads the R8 mapping and asserts that protected
  packages stayed identity-mapped. It catches a keep rule that stopped pinning
  *names* — the failure behind the flogger crash, where a renamed frame broke a
  stack walk.
- **`verify<Variant>Instantiable`** opens the packaged APK, parses the dex
  `class_defs` table and asserts that classes the app instantiates reflectively
  are present and carry neither `ACC_ABSTRACT` nor `ACC_INTERFACE`.

The second was added after long-term memory turned out to have never worked in
any released build. R8 in full mode left `com.google.protobuf.Any` with its own
name — so the mapping check passed — and made the class **abstract**, because
protobuf-javalite instantiates through `Unsafe.allocateInstance`, which R8
cannot see. MediaPipe parses its task graph as a protobuf, so every
`TextEmbedder.createFromOptions` threw `InstantiationException`. The exception
was caught and shown as a snackbar, so nothing reached logcat or Crashlytics.

The lists live in `app/build.gradle.kts` (`r8ProtectedPackages`,
`r8RequiredInstantiableClasses`); the checkers are unit-tested in `buildSrc`
(`./gradlew -p buildSrc test`). Both published versions, `0.7.1` and `0.7.2`,
fail the instantiability check — it was verified against them before being
trusted.

**Diagnosing a release-only failure.** A release build plants no Timber tree, so
`adb logcat` shows nothing from the app. Build a diagnostic APK instead: plant
`Timber.DebugTree()` unconditionally and give the release build type an
`applicationIdSuffix`, so it installs beside the real one and leaves its data
alone. That is how the protobuf failure was found.

## 8. FOSS / F-Droid build

F-Droid requires a build that is **free of proprietary dependencies and
build-time tooling**. The `foss` flavour is exactly that build, and the
`full` flavour is everything else:

```bash
# F-Droid-compatible release APK — no Google/Firebase anywhere in the graph.
./gradlew :app:assembleFossRelease
```

How the freedom is guaranteed:

- **No crash-reporting / telemetry SDK.** Firebase Crashlytics is declared as
  `fullImplementation(...)` in `app/build.gradle.kts`, so the Crashlytics SDK
  (and its `firebase-common` / `firebase-installations` transitive graph) is on
  the `full` classpath only. Analytics is not declared in either flavour. Verify the `foss` classpath carries none of them:

  ```bash
  ./gradlew :app:dependencies --configuration fossReleaseRuntimeClasspath \
      | grep -iE 'firebase-crashlytics|firebase-analytics|firebase-common|firebase-installations|play-services'
  # expected: no output
  ```

- **No proprietary build plugin.** The `com.google.gms.google-services` and
  `com.google.firebase.crashlytics` Gradle plugins are applied *conditionally* —
  skipped whenever **every** requested Gradle task is `Foss`-scoped (the
  `fossOnlyBuild` guard in `app/build.gradle.kts`). An `assembleFossRelease`
  invocation therefore never loads them and never reads `google-services.json`.
  (Mixed invocations such as `./gradlew check`, which also build/test the `full`
  variant, do apply the plugins — the `full` variant needs the generated
  `google_app_id` for its Robolectric tests. The `foss` artifact stays clean
  because the F-Droid build is `foss`-only.)

- **No crash collector in the app.** The `foss` flavour binds a no-op
  `CrashReportingRepository` (`app/src/foss/.../NoOpCrashReportingRepository.kt`)
  that records and transmits nothing, and the in-app crash-reporting consent
  toggle is hidden (driven by `BuildConfig.CRASH_REPORTING_AVAILABLE = false`).
  The `full` flavour keeps the Firebase-backed implementation under
  `app/src/full/`.

The `foss` and `full` flavours share **all** `main` sources; only the
crash-reporting implementation, its Hilt module, and the Crashlytics/Analytics
manifest meta-data differ between the two source sets. This keeps the two builds
behaviourally identical apart from crash reporting.

### Known residuals (out of scope for the crash-reporting split)

Removing Crashlytics/Analytics is the blocker this flavour split targets. Two
larger items remain before the `foss` build is fully F-Droid-*eligible* — they
are inherent to the product, not to crash reporting, and are tracked separately:

- **`com.google.firebase:firebase-encoders{,-json,-proto}`** still appear on the
  `foss` classpath. They are **not** the telemetry SDK — they are Apache-2.0
  serialization utilities pulled transitively by MediaPipe
  (`com.google.mediapipe:tasks-text` → `com.google.android.datatransport` →
  `firebase-encoders`), present in **both** flavours.

  The same chain used to put three of Google's data-transport **components**
  into the FOSS manifest — an alarm receiver, a job service and a
  backend-discovery service, the only `com.google.*` entries in a manifest whose
  build is described as carrying no Google dependency. Nothing could be sent
  through them (R8 strips the transport implementation; no CCT endpoint survives
  into the dex), but a manifest that advertises a collector invites exactly the
  question the FOSS build exists to answer. They are removed in
  `app/src/foss/AndroidManifest.xml` with `tools:node="remove"`.

  **Removing the components rather than the dependency is deliberate.**
  Excluding `com.google.android.datatransport` from the `foss` configurations
  builds — after two extra `-dontwarn` rules — but leaves MediaPipe's own code
  referencing classes that are no longer in the APK. The path that would touch
  them is its logging path, reached from `TextEmbedder.createFromOptions`: the
  same on-device embedding path that produced a release-only crash once before
  (see the flogger section of `proguard-rules.pro`). A `NoClassDefFoundError`
  there is invisible to the JVM gate and fatal to long-term memory. The manifest
  route reaches the same observable end state with no runtime surface.
- **Prebuilt on-device inference binaries.** `com.google.ai.edge.litertlm` and
  `com.google.mediapipe:tasks-core` ship large prebuilt native libraries that
  F-Droid's build server does not compile from source. They are **freely
  licensed** — the LiteRT-LM POM declares Apache-2.0 and the AAR carries both
  the licence text and a third-party notice listing only free licences, with
  sources at `github.com/google-ai-edge/LiteRT-LM`; MediaPipe is Apache-2.0 as
  well. (An earlier revision of this document called them "non-free". That was
  wrong, and the distinction matters: the open question is not the licence but
  whether a reviewer accepts a prebuilt binary under F-Droid's allowance for
  freely-licensed artefacts from trusted Maven repositories, Google Maven
  included.) The `foss` release APK contains exactly five native libraries —
  `liblitertlm_jni.so`, `libmediapipe_tasks_jni.so`, `libsqlcipher.so`,
  `libandroidx.graphics.path.so` and `libdatastore_shared_counter.so` — all
  from freely-licensed upstreams.

### The Google dependency-metadata block

AGP stamps a Google-encrypted description of the dependency graph into every
artefact it packages. `dependenciesInfo` in `app/build.gradle.kts` turns it off
for the **APK** and leaves it on for the **bundle**:

```kotlin
dependenciesInfo {
    includeInApk = false
    includeInBundle = true
}
```

The asymmetry is the point. The APK is what a user sideloads and what an
F-Droid-compatible index unpacks and inspects; an opaque blob only Google can
read has no business in it. The bundle is consumed by Play, which uses that same
block to warn about known-vulnerable dependencies — a real signal, and one no
user ever receives a copy of.

### Reproducible builds

F-Droid prefers builds it can reproduce bit-for-bit from source. Both
`BuildConfig` stamps are now deterministic for a given checkout:

- `BuildConfig.GIT_COMMIT_DATE_EPOCH_MS` resolves, in order, from the
  `SOURCE_DATE_EPOCH` environment variable (the cross-ecosystem convention,
  in seconds), then the `HEAD` commit date, and only then the wall clock —
  see `resolveBuildTimestampMs()` in `app/build.gradle.kts`. Two builds of one
  commit therefore agree; before this, every rebuild differed by construction.

  ```bash
  SOURCE_DATE_EPOCH=$(git log -1 --pretty=%ct) ./gradlew :app:assembleFossRelease
  ```

- `BuildConfig.GIT_SHA` resolves the short commit SHA, which is deterministic
  for a given checkout.

What this does *not* establish is that the whole artefact reproduces
bit-for-bit: that can only be confirmed against a build produced by F-Droid's
own server, which does not exist yet. The claim here is narrow and true — the
one identified source of non-determinism is gone.

The `foss` release is otherwise a standard R8-minified arm64-v8a build (§4); the
F-Droid build recipe should disable any signing config so F-Droid applies its
own signature.

## 9. Cutting a release

Releases are built by `.github/workflows/release.yml`, not by hand. The
maintainer's part is the version bump, the tag, and the decision to publish.

**Before tagging**

1. Bump `versionCode` **and** `versionName` in `app/build.gradle.kts`. The
   workflow refuses to build a tag that disagrees with `versionName`
   (`./gradlew :app:verifyReleaseVersion -PreleaseTag=v0.7.0` is the same check,
   runnable locally), so this is the step that decides what the release is
   called. `versionCode` must increase monotonically — Play rejects a re-used
   one, and Android refuses the in-place upgrade.
2. Move the `[Unreleased]` section of [`CHANGELOG.md`](../CHANGELOG.md) under
   the new version heading, and **set that heading's date to the day the tag is
   pushed** — not the day the section was first cut. A heading written ahead of
   the tag goes stale as soon as anything else lands, and anything that lands
   between the cut and the tag ships in *this* release, so it belongs above the
   heading, not under `[Unreleased]`.
3. Add `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` for
   the new `versionCode`, in every locale (§10). This is not optional bookkeeping:
   `StoreMetadataTest` fails `./gradlew check` without it, because a version that
   reaches a store with no release notes is a version nobody can tell apart from
   the last one.
4. Update the link definitions at the foot of `CHANGELOG.md` — point
   `[Unreleased]` at the new tag and add a `[<version>]` line for it. Every
   version heading in that file is a reference link, so a heading without its
   definition renders as literal brackets, in the repository and in the release
   notes pasted from it.
5. Update the version badge near the top of [`README.md`](../README.md) — the
   `img.shields.io/badge/version-…` line. It is the first version number a
   visitor sees and the one a bug reporter quotes, and it used to be the only
   copy this checklist never mentioned.
6. Update the two prose copies the badge does not cover: the *Pre-release
   notice* sentence in `README.md` ("currently at **version X**"), and
   [`SECURITY.md`](../SECURITY.md) — which names the version **three** ways: its
   opening sentence, the line saying which line fixes land on, and both cells of
   the supported-versions table. A stale table there tells a reporter their
   release is unsupported, which is the most expensive of these to get wrong.
7. On a **minor** bump only, update the pre-release line at the top of
   [`docs/roadmap.md`](roadmap.md) ("The current pre-release line (`X.Y.x`)").
   A patch release leaves it correct, and the gate compares against the line
   rather than the full version for exactly that reason.
8. Land all of it on `main` through the normal review path.

Steps 1, 2, 4, 5, 6 and 7 are held together by `./gradlew check`:
`:app:verifyVersionSources` fails the build when the README badge, the README
prose, the topmost changelog heading, either compare link, any version in
`SECURITY.md`, or the roadmap line disagrees with the declared `versionName`. Do them in one commit
and the gate is invisible; skip one and it names the file and both values. See
[`static-analysis.md`](static-analysis.md#version-number-agreement-guard-verifyversionsources).

**Tagging**

```bash
git checkout main && git pull
git tag -a v0.7.0 -m "Knotwork 0.7.0"
git push origin v0.7.0
```

The tag push starts the workflow. It reuses the `check` gate, verifies the tag
against `versionName`, materialises the keystore and the real
`google-services.json`, builds `fullRelease` (APK + AAB) and — in a separate
Gradle invocation, so the Google plugins stay out of it (§8) — `fossRelease`
(APK), checks that both APKs ship the LiteRT-LM and MediaPipe Tasks libraries and
not MediaPipe's excluded text-generation one (§5), re-verifies the signer of all
three artefacts against `RELEASE_CERT_SHA256`, and attaches them plus a `SHA256SUMS.txt` to a **draft**
GitHub Release. The R8 mapping files are uploaded as a workflow artefact (90-day
retention) rather than as a public asset — they belong with the maintainer, for
deobfuscating crash reports.

Note that the release build only *injects* the Crashlytics mapping-file id
(`injectCrashlyticsMappingFileIdFullRelease`); it does not upload the mapping.
Until `./gradlew :app:uploadCrashlyticsMappingFileFullRelease` is run against
the same build, Crashlytics stack traces for that version stay obfuscated —
which is what the archived workflow artefact is for.

**Publishing**

The release is left as a draft on purpose: publishing is a deliberate act, the
same way merging a pull request is. Before pressing publish:

1. Replace the auto-generated notes with the CHANGELOG section for this version.
2. Download the APK and install it on the reference device — the automated gate
   is JVM-only and never runs the artefact (see
   [`testing.md`](testing.md) § *What the automated gate does NOT cover*).
3. **Open every in-app documentation link from that installed build.**
   - **More → Help**: every row. The two bundled documents (troubleshooting,
     FAQ) open in the in-app reader; the three others open in the browser, at
     the tag. In each reader, *Open in browser* opens the same document at the
     tag, and a link inside the document to a repository file does too — except
     the privacy policy, which opens on `main`.
   - The Tools and Triggers top-bar book icons, which land on their sections of
     the user guide, the editor overflow's *Pipeline cookbook*, and the *Read
     the contract* link in the external-automation settings hint.
   - The same Help screen **in airplane mode**: both bundled documents still
     read, links between them still scroll, and a web row refuses in place — offering
     the address to copy and naming the documents that are on the device —
     instead of opening an error page.

   The onboarding FAQ link cannot be reached on an existing install — onboarding
   does not re-open. It opens the bundled copy rather than a URL, and
   `BundledDocumentationRoutingGuardTest` fails the build if it stops doing so.

   This is a checklist item and not a `check` task on purpose. A release build
   links at `v<versionName>`, and that tag does not exist until the release is
   cut — so no gate running inside the repository can confirm the URL resolves
   on GitHub. `verifyDocumentationLinks` proves the *document and heading*
   exist in the commit being released (see
   [`static-analysis.md`](static-analysis.md) § *In-app documentation link
   guard*); only this step proves the tag does.

   A link that 404s here means the tag was not pushed, not that the registry is
   wrong.
4. Publish.

**Dry-running the workflow.** *Actions* → *Release* → *Run workflow* takes a tag
as input and performs every step except creating the release, so the pipeline
(and freshly rotated secrets) can be exercised without minting a throwaway tag.
It still requires the tag to match the declared `versionName`.

**A tag with a pre-release suffix** (`v0.7.0-rc1`) is marked as a prerelease on
GitHub and does not become the "Latest" download. A plain `0.x` tag does not —
pre-1.0 is still the version users are meant to install.

## 10. Store listing metadata

The listing texts, screenshots and per-version release notes live in the
repository, in the `fastlane supply` layout F-Droid reads:

```
fastlane/metadata/android/
├── en-US/
│   ├── title.txt                   # ≤ 30 characters (Play's limit; F-Droid allows 50)
│   ├── short_description.txt       # ≤ 80
│   ├── full_description.txt        # ≤ 4000
│   ├── changelogs/<versionCode>.txt  # ≤ 500
│   └── images/
│       ├── icon.png                # 512 × 512
│       ├── featureGraphic.jpg      # 1024 × 500 — required by Play
│       └── phoneScreenshots/       # 1.png, 2.jpg, …
└── ru-RU/                          # texts only; falls back to en-US graphics
```

**Nothing uploads this directory to Google Play.** No workflow runs `supply`; the
Play listing is filled in by hand in Play Console, from these files. Keeping the
Play texts here anyway gives them the same length gate and the same review as
the code, and one place to copy from — but a merged edit reaches Play only when
someone pastes it there. `StoreMetadataTest` (in the `:app` unit-test suite, wired into `check`) enforces
the limits, the presence of a changelog for the **current** `versionCode`, and
the screenshot rules below. A version bump without a matching
`changelogs/<versionCode>.txt` fails the build rather than shipping a release
with no notes.

**Screenshot geometry is a real gate, not a guideline.** Play rejects any
screenshot whose longer side exceeds twice its shorter side, and the README hero
baselines are 1080 × 2400 — over the line. The store captures are therefore
rendered separately, at 1080 × 2160:

```bash
./gradlew :catalog:recordRoborazziDebug --tests "*StoreScreenshotTest*"
for f in catalog/src/test/snapshots/store_phone_*.png; do
    n=$(basename "$f" | cut -d_ -f3)          # store_phone_4_pipelines.png -> 4
    cp "$f" "fastlane/metadata/android/en-US/images/phoneScreenshots/$n.png"
done
```

The rename is the point: both stores order the carousel by file name, so the
baselines land as `<n>.png` rather than under their own names. The step is
manual and nothing verifies it, so re-record and re-copy in the same change. Slot 2 is the phone capture of the editor
canvas (`docs/images/hero-pipeline-canvas.jpg`), which has no design-system
counterpart to render from.

**The feature graphic is a JPEG on purpose.** Play accepts JPEG or a *24-bit*
PNG and refuses a PNG that carries an alpha channel — even one whose every pixel
is opaque, which no image viewer will show you. `StoreMetadataTest` reads the
PNG colour-type byte and fails the build rather than letting that reach a
submission.

The privacy policy Play requires as a URL is [`PRIVACY.md`](../PRIVACY.md) at
the repository root; the About screen links to the same file, so the store, the
README and the app cannot describe three different policies.
