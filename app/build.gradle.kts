import app.knotwork.android.buildtools.BrowserEditorConstantsGenerator
import app.knotwork.android.buildtools.BrowserEditorFlatExportGuard
import app.knotwork.android.buildtools.BrowserEditorImportParityGuard
import app.knotwork.android.buildtools.BrowserEditorInertControlGuard
import app.knotwork.android.buildtools.BrowserEditorRuntimeFieldGuard
import app.knotwork.android.buildtools.CookbookDocsGenerator
import app.knotwork.android.buildtools.DetektAnalysisModeGuard
import app.knotwork.android.buildtools.DexInstantiabilityChecker
import app.knotwork.android.buildtools.DexInvocationChecker
import app.knotwork.android.buildtools.DocumentationRef
import app.knotwork.android.buildtools.ExternalAutomationDocsGenerator
import app.knotwork.android.buildtools.FileMapSpec
import app.knotwork.android.buildtools.GenerateDocumentationLinksTask
import app.knotwork.android.buildtools.GenerateFileMapTask
import app.knotwork.android.buildtools.GitCommitId
import app.knotwork.android.buildtools.LintBaselineGuard
import app.knotwork.android.buildtools.PinnedNdk
import app.knotwork.android.buildtools.R8MappingChecker
import app.knotwork.android.buildtools.ReleaseVersionChecker
import app.knotwork.android.buildtools.ReportExternalDocLinksTask
import app.knotwork.android.buildtools.SettingsHelpDocsGenerator
import app.knotwork.android.buildtools.StoreListingLengthChecker
import app.knotwork.android.buildtools.SyncBundledDocsTask
import app.knotwork.android.buildtools.VerifyBundledDocsTask
import app.knotwork.android.buildtools.VerifyDialogInventoryTask
import app.knotwork.android.buildtools.VerifyDocLinksTask
import app.knotwork.android.buildtools.VerifyDocsHygieneTask
import app.knotwork.android.buildtools.VerifyDocumentationLinksTask
import app.knotwork.android.buildtools.VerifyFileMapTask
import app.knotwork.android.buildtools.VerifyForbiddenVocabularyTask
import app.knotwork.android.buildtools.VerifyKeepRuleTargetsTask
import app.knotwork.android.buildtools.VerifyMergedManifestTask
import app.knotwork.android.buildtools.VerifyMermaidDiagramsTask
import app.knotwork.android.buildtools.VerifyNoOrphanedKdocTask
import app.knotwork.android.buildtools.VerifyNoSlf4jProviderTask
import app.knotwork.android.buildtools.VerifySupplyChainPinsTask
import app.knotwork.android.buildtools.VerifyVersionSourcesTask
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ScopedArtifacts
import dev.detekt.gradle.Detekt
import java.util.Properties
import java.util.zip.ZipFile

/**
 * Resolves the commit identifier baked into `BuildConfig.GIT_SHA`: the first
 * eight characters of the full hash of `HEAD` (e.g. `19b9c8f0`), via
 * [GitCommitId]. Not `git rev-parse --short`, whose length follows the clone's
 * depth and the host's `core.abbrev` — one commit then compiled to two different
 * dex files on CI and on a full clone. Returns `"unknown"` when git is absent,
 * the working tree is not a repository, or the command otherwise fails (e.g. a
 * tarball-based build with no git history).
 */
fun Project.resolveGitSha(): String = runCatching {
    val output = providers.exec {
        commandLine(GitCommitId.COMMAND)
        isIgnoreExitValue = true
    }
    val exitCode = output.result.get().exitValue
    if (exitCode == 0) {
        GitCommitId.of(output.standardOutput.asText.get()) ?: "unknown"
    } else {
        "unknown"
    }
}.getOrDefault("unknown")

/**
 * Resolves the build timestamp (epoch milliseconds) baked into `BuildConfig`.
 *
 * Resolution order, most authoritative first:
 *
 * 1. **`SOURCE_DATE_EPOCH`** (seconds since the epoch) — the cross-ecosystem
 *    convention for reproducible builds. A rebuilder sets it to the commit
 *    timestamp and expects a bit-identical artefact; reading the wall clock
 *    instead is precisely what makes a build unreproducible.
 * 2. **The `HEAD` commit date** — deterministic for a given checkout, and the
 *    honest answer to "when was this source built from".
 * 3. **The wall clock** — last resort for a build with neither (a tarball with
 *    no git history and no environment override).
 *
 * The value is surfaced in the Settings top-app-bar subtitle, so it must stay a
 * real date in all three cases rather than a sentinel.
 *
 * @return Epoch milliseconds for the build stamp.
 */
fun Project.resolveBuildTimestampMs(): Long {
    val millisPerSecond = 1_000L
    val sourceDateEpoch = providers.environmentVariable("SOURCE_DATE_EPOCH").orNull
        ?.trim()
        ?.toLongOrNull()
    if (sourceDateEpoch != null) return sourceDateEpoch * millisPerSecond

    val commitEpochSeconds = runCatching {
        val output = providers.exec {
            commandLine("git", "log", "-1", "--pretty=%ct")
            isIgnoreExitValue = true
        }
        if (output.result.get().exitValue == 0) {
            output.standardOutput.asText.get().trim().toLongOrNull()
        } else {
            null
        }
    }.getOrNull()

    return commitEpochSeconds?.times(millisPerSecond) ?: System.currentTimeMillis()
}

/**
 * Resolved release-signing credentials sourced from `local.properties` or
 * environment variables. Carries the validated keystore file plus its
 * passwords and key alias so the `signingConfigs.release` block can be
 * populated without re-reading any properties.
 *
 * @property storeFile The keystore file (already verified to exist on disk).
 * @property storePassword The keystore (store) password.
 * @property keyAlias The alias of the signing key inside the keystore.
 * @property keyPassword The password protecting the signing key.
 */
private data class ReleaseSigningCredentials(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

/**
 * Resolves release-signing credentials, preferring `local.properties` (the
 * developer machine) and falling back to environment variables (CI). The
 * recognised keys are `RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`,
 * `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD`.
 *
 * Returns `null` — which the build interprets as "fall back to the debug
 * signing identity" — whenever any credential is missing/blank or the
 * resolved keystore file does not exist. This keeps a clean checkout without
 * a provisioned key building release artefacts instead of failing
 * configuration.
 *
 * @return The complete, validated set of credentials, or `null` when release
 *   signing is not provisioned in this environment.
 */
private fun Project.resolveReleaseSigning(): ReleaseSigningCredentials? {
    val localProps = Properties().apply {
        rootProject.file("local.properties")
            .takeIf { it.exists() }
            ?.inputStream()
            ?.use { load(it) }
    }
    fun value(key: String): String? = (localProps.getProperty(key) ?: System.getenv(key))?.trim()?.ifEmpty { null }

    val storePath = value("RELEASE_KEYSTORE_PATH") ?: return null
    val storePassword = value("RELEASE_KEYSTORE_PASSWORD") ?: return null
    val keyAlias = value("RELEASE_KEY_ALIAS") ?: return null
    val keyPassword = value("RELEASE_KEY_PASSWORD") ?: return null

    val storeFile = rootProject.file(storePath).takeIf { it.exists() } ?: return null
    return ReleaseSigningCredentials(storeFile, storePassword, keyAlias, keyPassword)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
    // The `google-services` and `firebase-crashlytics` Gradle plugins are NOT
    // applied here unconditionally. They are proprietary and pull Google's
    // build-time tooling into the graph, which the F-Droid channel rejects.
    // Instead they are applied further down for every build EXCEPT a foss-only
    // one (see the `fossOnlyBuild` guard after the `android {}` block), so an
    // `assembleFossRelease` build never loads them.
}

ksp {
    // Export the Room schema for every version so that
    // `MigrationTestHelper` can validate migrations against frozen JSON
    // snapshots in `app/schemas/`. The corresponding `exportSchema = true`
    // flag is set on the `@Database` annotation. Every future schema bump
    // must commit the newly generated `N.json` alongside the migration.
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "app.knotwork.android"
    compileSdk {
        version = release(37)
    }
    // The NDK whose `llvm-strip` strips the prebuilt native libraries. The app
    // compiles no native code, but left to its default AGP silently packages the
    // libraries unstripped on a host that lacks its default NDK — the same commit
    // then shipped different `.so` bytes from CI and from a laptop. Pinned in the
    // version catalog; `verify<Variant>PinnedNdk` below fails a release build
    // without it.
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "app.knotwork.android"
        minSdk = 34
        targetSdk = 37
        versionCode = 14
        versionName = "0.10.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // License metadata exposed as Android string
        // resources so a future "About" dialog can render the license name and
        // link to the canonical text without hardcoding the values in UI code.
        resValue("string", "license_name", "Apache License 2.0")
        resValue("string", "license_url", "https://www.apache.org/licenses/LICENSE-2.0")

        // Surface the build's short git SHA inside the
        // About row of the Knotwork settings screen so users can paste a
        // precise build identifier into bug reports without needing the APK
        // hash. Falls back to "unknown" when `git` is unavailable (e.g. a
        // tarball build).
        buildConfigField("String", "GIT_SHA", "\"${resolveGitSha()}\"")

        // Build date surfaced in the Settings top-app-bar
        // subtitle (`v0.9.2 · alpha · 2026.05.18`). Captured at configuration
        // time as an epoch-millis Long so the formatter on the screen owns
        // the locale-specific rendering. Derived from `SOURCE_DATE_EPOCH` or
        // the HEAD commit date (see `resolveBuildTimestampMs`), so two builds
        // of the same commit agree — reading the wall clock here used to make
        // every rebuild a different binary, which is the one thing a
        // reproducible build may not do.
        buildConfigField(
            "long",
            "GIT_COMMIT_DATE_EPOCH_MS",
            "${resolveBuildTimestampMs()}L",
        )
    }

    // Real release-signing identity. The credentials are resolved from
    // `local.properties` (developer machine) or environment variables (CI
    // repository secrets); `resolveReleaseSigning()` returns null when none are
    // provisioned, in which case the `release` buildType below gracefully falls
    // back to the debug keystore so a clean checkout still builds. Keystore
    // material is never committed (guarded by `.gitignore`).
    val releaseSigning = resolveReleaseSigning()
    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = releaseSigning.storeFile
                storePassword = releaseSigning.storePassword
                keyAlias = releaseSigning.keyAlias
                keyPassword = releaseSigning.keyPassword
                // APK Signature Scheme v3 next to v2 (v1 is off at this minSdk).
                // v3 is the scheme a signing-key rotation is expressed in; with a
                // single key it changes nothing a device can observe, and it does
                // not make a LOST key recoverable — a rotation is signed by the
                // old key. v2 has to be asked for explicitly: at minSdk >= 28 AGP
                // drops it as soon as v3 is on (measured: a v3-only APK), while
                // every artefact published so far is v2 — keeping it makes the
                // change purely additive. `release.yml` checks each published APK
                // carries both.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // A debug build installs next to a release build instead of
            // replacing it: separate applicationId means a separate data
            // directory, so dogfooding a work-in-progress build can no longer
            // migrate, corrupt or wipe the database of the build actually in
            // daily use. The launcher label is overridden in `src/debug/res`
            // and the version name is suffixed so About and bug reports say
            // which of the two is talking.
            //
            // `src/debug/google-services.json` carries the same placeholder
            // values as the module-root file with the suffixed package name.
            // The variant-specific file wins for this build type, so the debug
            // build never resolves to a real Firebase project even when the
            // root file is swapped for the real one at release-build time.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"

            // Where this build's in-app documentation links resolve. A debug
            // build has no tag of its own, so it reads `main`. Decided by build
            // TYPE, never by inspecting `versionName` for the suffix above: the
            // suffix exists to tell two installs apart, and letting it also
            // decide where links point would move them the next time a build
            // type sets one for an unrelated reason.
            buildConfigField(
                "String",
                "DOCS_REF",
                "\"${DocumentationRef.of(release = false, versionName = defaultConfig.versionName.orEmpty())}\"",
            )
        }
        release {
            // Where this build's in-app documentation links resolve: the
            // annotated tag of the version being shipped. A store user reading
            // `main` would be reading about a build they do not have — the same
            // class of drift as a guide pointing at a file that does not exist
            // for them. The tag is cut at release time, so this link is live
            // only after the tag is pushed; `docs/release.md` carries the
            // post-publication check, which no repository gate can perform.
            buildConfigField(
                "String",
                "DOCS_REF",
                "\"${DocumentationRef.of(release = true, versionName = defaultConfig.versionName.orEmpty())}\"",
            )

            // R8 in full mode + resource shrinking.
            // Keep rules for reflection-heavy code paths (Koog, Ktor,
            // kotlinx.serialization, MediaPipe / LiteRT JNI, SQLCipher,
            // AppFunctions KSP-generated wrappers) live in
            // `proguard-rules.pro`; AGP appends the standard
            // `proguard-android-optimize.txt` from the Android SDK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Use the dedicated `release` signing config when its credentials
            // are provisioned (see `signingConfigs` above); otherwise fall back
            // to the debug keystore so a clean checkout without a key still
            // produces a (debug-signed) release artefact. Provisioning details
            // and signature verification are documented in `docs/release.md`.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            // Strip non-arm64 ABIs from the release APK. The reason is the
            // inference engine, not the API floor: `litertlm-android` ships
            // `jni/arm64-v8a` and `jni/x86` only — it has no `armeabi-v7a`
            // binary at all — so a 32-bit ARM device cannot run this app at any
            // `minSdk`. Shipping `armeabi-v7a` + `x86` + `x86_64` would inflate
            // the artefact by ~65 MB for zero benefit. (The earlier wording
            // justified this by "every `minSdk = 36` device is 64-bit", which
            // was true but incidental; it would have gone stale when the floor
            // moved to 34, where 32-bit devices do exist. The engine-level
            // reason does not.) Emulator-based smoke tests should use the debug
            // variant which keeps every ABI.
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    // Distribution flavours. `full` ships Firebase Crashlytics and is
    // the default for day-to-day development and the Play/direct-APK channel.
    // `foss` is the F-Droid-compatible build: it carries no Firebase/Google
    // dependency (see the flavour-scoped `fullImplementation(...)` block and
    // the conditional plugin application below) and binds a no-op
    // `CrashReportingRepository`. The two flavours share all `main` sources;
    // only the crash-reporting impl + DI module differ (under `src/full` /
    // `src/foss`).
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            // Pre-selected variant in Android Studio and the default target for
            // ambiguous instrumentation/test tasks.
            isDefault = true
            // Surfaces to the presentation layer whether the in-app
            // crash-reporting consent toggle has a live collector behind it.
            // `full` does; `foss` hides the toggle entirely.
            buildConfigField("boolean", "CRASH_REPORTING_AVAILABLE", "true")
        }
        create("foss") {
            dimension = "distribution"
            buildConfigField("boolean", "CRASH_REPORTING_AVAILABLE", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    // AGP stamps a Google-encrypted blob of the dependency list into every
    // artefact. It is opaque by construction — only Google can read it — which
    // is a poor fit for an APK distributed as the FOSS build and inspected by
    // people who chose it precisely to see what is inside.
    //
    // The asymmetry is deliberate: the bundle keeps its copy, because that is
    // consumed by Play, which uses the block to warn about known-vulnerable
    // dependencies. Dropping it there would trade a real security signal for
    // nothing, since no user ever receives the .aab.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric needs the merged Android resources
        // and assets on the JVM unit-test classpath (string lookups in
        // `LongRunningTaskNotifierImpl`, drawables resolved by NotificationCompat
        // builders). Without this flag Robolectric falls back to its own minimal
        // resource table and `context.getString(R.string.…)` returns a placeholder.
        unitTests.isIncludeAndroidResources = true

        // Gradle's default test-worker heap is 512 MB, and this suite (≈3800
        // tests, Robolectric loading a merged resource table per class) began
        // exhausting it — as an `OutOfMemoryError` in whichever unrelated class
        // happened to run when the heap ran out, which reads like a flake and
        // is not one. The floor is raised once here rather than chased per
        // class; `forkEvery` is deliberately not used, since restarting the
        // worker every N classes costs far more wall-clock than the heap costs
        // memory.
        unitTests.all { test -> test.maxHeapSize = "2g" }
    }

    // Expose the exported Room schemas to the
    // androidTest classpath so `MigrationTestHelper` (which reads them from
    // `assets/`) can validate every migration step against the frozen
    // snapshots in `app/schemas/`.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    packaging {
        resources {
            excludes += "META-INF/*"
            // Jansi (transitive via Koog → log adapter)
            // ships pre-built native binaries for Windows / Mac / Linux that
            // are dead weight on Android. Stripping them shaves ~430 KB off
            // the release APK without touching runtime behaviour (the
            // ANSI-escape rendering only runs on JVM hosts).
            excludes += "org/fusesource/jansi/internal/native/Windows/**"
            excludes += "org/fusesource/jansi/internal/native/Mac/**"
            excludes += "org/fusesource/jansi/internal/native/Linux/**"
            excludes += "org/fusesource/jansi/internal/native/FreeBSD/**"
            excludes += "META-INF/native-image/jansi/**"
        }
        jniLibs {
            // MediaPipe `tasks-text` 1.0.0 added `TextSummarizer` and `TextProofreader`,
            // on-device text generators, and ships their native half in this library —
            // 14.4 MB of the arm64 APK, most of the ~15 MiB it grew between 0.7.3 and
            // 0.8.0 without anyone noticing. The app uses only `TextEmbedder`, whose
            // bytecode loads `mediapipe_tasks_jni` and never this one (checked in the
            // AAR: only the two generator classes name it). Text generation belongs to LiteRT-LM, the one
            // on-device LLM engine; a second one would run outside the pipeline graph,
            // its HITL gate and its ceilings. The release workflow asserts the library
            // stays out and `mediapipe_tasks_jni` stays in.
            excludes += "**/libmediapipe_tasks_textgenai_jni.so"
        }
    }

    lint {
        // Strict mode. `abortOnError` + `warningsAsErrors`
        // turn every lint finding into a build failure; `checkDependencies`
        // extends the analysis to library modules so issues in shared code
        // surface on the PR that introduced them. The baseline file
        // grandfathers existing findings — only newly introduced issues
        // surface as failures.
        baseline = file("lint-baseline.xml")
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = true
        htmlReport = true
        xmlReport = true
        // The release variant ships `arm64-v8a` only (the inference engine has
        // no `armeabi-v7a` binary — see the `abiFilters` block above for why
        // this does not depend on the API floor). ChromeOS support is not in
        // scope for v0.1 — disable the lint check that demands an x86 binary.
        disable += "ChromeOsAbiSupport"
        // Version-freshness and deadline checks stay ENABLED, but are demoted to
        // INFORMATIONAL so they report instead of gating. Their verdict is a
        // function of an external version index (or of the calendar), not of the
        // contents of this repository: the same commit is green today and red
        // tomorrow without a single edit, and green locally while red in CI,
        // because the two version indexes refresh at different times. A check
        // whose verdict changes without the checked object changing is a report,
        // not a gate — `decisions.md` §35, which generalises the rule to any
        // future check that depends on external state.
        //
        // Demoted, NOT disabled. `disable` maps to `Severity.IGNORE`, which drops
        // the incident before any reporter sees it; the signal has to survive.
        // INFORMATIONAL findings still run, still match the baseline and still
        // appear in the HTML/XML reports, which CI uploads on every run (see
        // `.github/workflows/check.yml`). `warningsAsErrors` cannot undo the
        // demotion: lint promotes `Severity.WARNING` only, and INFORMATIONAL is
        // documented as exempt.
        //
        // Deliberate exclusions — checks that stay gates although their verdict is
        // not purely a function of this repository, because each encodes a store
        // publishing blocker rather than a matter of hygiene:
        // - `ExpiredTargetSdkVersion` (FATAL), which is calendar-driven;
        // - `PlaySdkIndexNonCompliant`, `PlaySdkIndexVulnerability`,
        //   `PlaySdkIndexGenericIssues`, `PlaySdkIndexDeprecated`, `RiskyLibrary`
        //   and `OutdatedLibrary`, decided by the Google Play SDK Index — a
        //   network-refreshed dataset with a bundled offline snapshot fallback.
        // Being interrupted by those is the point. Anything outside that list is
        // expected to hold the rule.
        //
        // - Do NOT add `ignoreWarnings = true` alongside this. Unlike
        //   `warningsAsErrors` it tests `<= WARNING`, so it would swallow
        //   INFORMATIONAL as well and silently delete the drift report.
        //
        // The baseline is the third way to delete the report, and the easiest to
        // trip over: lint records informational findings into a regenerated
        // baseline just like errors, and then filters them out of the reports.
        // `verifyLintBaselineOverrides` (below, wired into `check`) fails the
        // build if a baseline ever suppresses one of these ids.
        informational += LintBaselineGuard.DEMOTED_ISSUE_IDS
    }
}

// Apply the proprietary Google build plugins for every build EXCEPT one that
// builds the `foss` flavour and no `full` flavour. `google-services` (and the
// `firebase-crashlytics` plugin it backs) is what makes the Firebase SDK
// initialise cleanly — it generates the `google_app_id` resource from
// `google-services.json`. The `foss` flavour ships no Firebase SDK, so an
// F-Droid build (`./gradlew clean assembleFossRelease`) skips the plugins
// entirely and produces an APK with zero Google build-time tooling in its graph.
//
// The condition deliberately keys on the presence of a `Foss` task AND the
// absence of any `Full` task, NOT "every requested task is foss". The naive
// "all foss" form fails OPEN on the canonical F-Droid recipe
// `clean assembleFossRelease`: `clean` is not a `Foss` task, so "all foss" is
// false and the proprietary plugins would be (wrongly) applied to the foss
// build. With `any-foss && no-full`, neutral tasks like `clean` no longer flip
// the decision, while any invocation that also builds `full` (e.g. `check`,
// `assemble`, `assembleFullRelease`) keeps the plugins so Firebase's startup
// provider finds `google_app_id` and does not throw under Robolectric.
//
// The `foss` flavour's freedom from the `com.google.firebase` runtime classpath
// is guaranteed independently by the flavour-scoped `fullImplementation(...)`
// dependencies, not by this guard — this guard only keeps the proprietary
// *build-time* plugins out of a pure F-Droid build.
val requestedTaskNames = gradle.startParameter.taskNames
val fossOnlyBuild = requestedTaskNames.any { it.contains("Foss", ignoreCase = true) } &&
    requestedTaskNames.none { it.contains("Full", ignoreCase = true) }
if (!fossOnlyBuild) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
    apply(plugin = libs.plugins.firebase.crashlytics.get().pluginId)
}

detekt {
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    // Strict mode. Any finding emitted at
    // `severity: error` (default `failOnSeverity`) now fails the build.
    // The legacy 1.x `failFast` switch was removed in detekt 1.22 and is
    // intentionally not used here.
    ignoreFailures = false
    basePath.set(rootDir)
    source.setFrom("src/main/java", "src/main/kotlin")
}

// Type-resolution gate. A detekt rule that implements
// `dev.detekt.api.RequiresAnalysisApi` cannot run in the `light` analysis mode
// of the plain `detekt` task above — and is skipped by it *silently*, so a rule
// listed in `detekt.yml` may be checking nothing at all. Those rules live in a
// second, deliberately narrow run: the plugin-generated type-resolution tasks
// for the debug variant of each distribution flavour (`detektFullDebug` /
// `detektFossDebug`) are rewired to `detekt-type-resolution.yml`, a config that
// activates exactly the four rules this project declared it wants and can only
// get here (`SuspendFunSwallowedCancellation`, `LongParameterList`,
// `UnusedImport`, `UnusedPrivateFunction`). Both flavours are wired so the gate
// also covers the flavour-specific crash-reporting sources (`src/full` /
// `src/foss`) — which the plain task's `source` never reached either.
//
// Running the full strict config under type resolution instead surfaces 296
// findings (measured), 263 of them from rules that have never been part of the
// gate — adopting them is a separate effort, not a side effect of this wiring.
// Full-config type-resolution analysis remains available via
// `detektMain`/`detektRelease`.
val typeResolutionGateDetektTasks = setOf("detektFullDebug", "detektFossDebug")
tasks.matching { it.name in typeResolutionGateDetektTasks }.configureEach {
    this as Detekt
    config.setFrom(files("$rootDir/config/detekt/detekt-type-resolution.yml"))
    buildUponDefaultConfig.set(false)
}
tasks.named("check") { dependsOn(typeResolutionGateDetektTasks) }

// The guard behind the split above: it reads every rule the light-mode config
// activates, resolves which rule classes on detekt's own classpath implement
// `RequiresAnalysisApi`, and fails when the two sets intersect — i.e. when a
// rule has been added to `detekt.yml` that the `detekt` task would skip without
// saying so. Wired into `check` because the failure it prevents is invisible by
// construction: the build stays green, the report stays empty, and the rule
// checks nothing. It reads the rule set from the `detekt` configuration's own
// jars rather than a pinned list, so a detekt upgrade that moves a rule across
// the boundary is caught by the next build instead of by nobody.
val verifyDetektAnalysisMode by tasks.registering {
    group = "verification"
    description = "Fails if config/detekt/detekt.yml activates a rule that needs type resolution."
    val lightConfig: File = rootProject.file("config/detekt/detekt.yml")
    val lightConfigPath: String = lightConfig.relativeTo(rootDir).path
    val detektClasspath = configurations.named("detekt")
    inputs.file(lightConfig)
    inputs.files(detektClasspath)
    doLast {
        val analysisApiRules = DetektAnalysisModeGuard.rulesRequiringAnalysisApi(detektClasspath.get().files)
        // A guard that finds no rules to compare against passes everything,
        // which is this task's own failure mode. Detekt 2.x ships 93 such rules,
        // so an empty set means the scan stopped finding them — a renamed marker
        // interface or a relocated rule package after an upgrade — not that the
        // distinction went away.
        if (analysisApiRules.isEmpty()) {
            throw GradleException(
                "verifyDetektAnalysisMode found no detekt rule requiring the Analysis API on the " +
                    "`detekt` classpath. The marker interface or the rule package has moved; the " +
                    "guard is scanning nothing and would pass any configuration. Update " +
                    "DetektAnalysisModeGuard before trusting this build.",
            )
        }
        val violations = DetektAnalysisModeGuard.scan(
            lightModeRules = DetektAnalysisModeGuard.activeRuleIds(lightConfig.readText()),
            analysisApiRules = analysisApiRules,
            configPath = lightConfigPath,
        )
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Detekt rules configured where they cannot run (${violations.size} rule(s)):\n" +
                    violations.joinToString(separator = "\n") { it.format() } +
                    "\n\nThe `detekt` task runs in `light` analysis mode and skips these rules in " +
                    "silence — green build, empty report, nothing checked. Move them to " +
                    "config/detekt/detekt-type-resolution.yml, which runs under type resolution.",
            )
        }
    }
}
tasks.named("check") { dependsOn(verifyDetektAnalysisMode) }

tasks.withType<Detekt>().configureEach {
    reports {
        html.required.set(true)
        checkstyle.required.set(true)
        sarif.required.set(false)
        markdown.required.set(false)
    }
    exclude("**/build/**", "**/generated/**")
}

ktlint {
    version.set("1.5.0")
    android.set(true)
    // Strict mode. Any ktlint violation that survives
    // `ktlintFormat` (i.e. cannot be auto-corrected, or the source set
    // was not formatted) fails the build. Compose-specific rule overrides
    // live in `.editorconfig`.
    ignoreFailures.set(false)
    filter {
        exclude { entry -> entry.file.toString().contains("/build/") }
        exclude { entry -> entry.file.toString().contains("/generated/") }
    }
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.HTML)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Kover — test-coverage measurement & enforcement.
// Aggregate threshold enforced via `koverVerifyDebug`; the aggregate floor was
// raised 70 % → 75 %.
//
// Per-rule filters are a Kover 0.10+ feature; on the 0.9.x line the only
// way to scope verification is via the (global) `reports.filters` block,
// which is also what determines what appears in HTML / XML reports. To
// avoid the verify rule failing on Composables and Android-runtime-bound
// classes that this gate cannot cover (they need androidTest / instrumented
// runs), those classes are removed from the **whole** coverage picture
// here. The trade-off is documented in docs/coverage-baseline.md:
// instrumented coverage for `*Screen.kt` etc. is a separate workstream.
//
// The single rule then enforces a 75 % aggregate floor over the
// remaining "unit-testable" surface — domain + data.repositories +
// presentation ViewModels / UiStates. Today's measurement is ~77.6 %, so
// the floor leaves ~2.6 pp of headroom against silent regression. Per-
// package targets that the build cannot yet enforce (no rule-level filters
// in 0.9.x) live in docs/coverage-baseline.md.
kover {
    reports {
        filters {
            excludes {
                classes(
                    // Hilt-generated factories, modules, and member injectors.
                    "*_HiltModules*",
                    "*_HiltModules_*",
                    "*.Hilt_*",
                    "*_Factory",
                    "*_Factory$*",
                    "*_MembersInjector",
                    "*_Provide*Factory",
                    "*_Provide*Factory$*",
                    "dagger.hilt.internal.*",
                    "hilt_aggregated_deps.*",
                    // Room-generated DAO implementations and the database impl
                    // (the schema migrations themselves are bundled inside it).
                    "*_Impl",
                    "*_Impl$*",
                    "app.knotwork.android.data.local.AppDatabase",
                    "app.knotwork.android.data.local.AppDatabase_Impl*",
                    "*_AutoMigration_*",
                    "app.knotwork.android.data.local.dao.*",
                    // Mic capture is a thin platform-`AudioRecord` wrapper that
                    // cannot run on the JVM; its testable WAV-header logic lives
                    // in `data.audio.WavHeader`, which stays in coverage.
                    "app.knotwork.android.data.audio.AudioRecorderImpl",
                    "app.knotwork.android.data.audio.AudioRecorderImpl$*",
                    // Compose synthetic singletons + project-convention preview files
                    // (every Compose `@Preview` lives in a `*Preview.kt` file).
                    "*ComposableSingletons*",
                    "ComposableSingletons$*",
                    "*Preview",
                    "*PreviewKt",
                    // Hilt DI modules — wiring code, no business logic to cover.
                    "app.knotwork.android.di.*",
                    // Generated build artefacts.
                    "app.knotwork.android.BuildConfig",
                    "*.databinding.*",
                    "*.BR",
                    // Android-runtime-bound presentation classes that need
                    // instrumented (androidTest / Compose UI test) coverage,
                    // out of scope for the JVM-only Kover pipeline.
                    "app.knotwork.android.App",
                    "app.knotwork.android.presentation.ui.MainActivity",
                    "app.knotwork.android.presentation.ui.MainActivity$*",
                    "app.knotwork.android.presentation.ui.*Screen",
                    "app.knotwork.android.presentation.ui.*ScreenKt",
                    "app.knotwork.android.presentation.ui.*Screen$*",
                    // Legacy chat surface (moved under chat/legacy/ pending the
                    // post-v0.1 orchestrator-rewiring task).
                    "app.knotwork.android.presentation.ui.chat.legacy.ConsoleFullLogSheet*",
                    "app.knotwork.android.presentation.ui.chat.legacy.ConsolePanelCollapsed*",
                    "app.knotwork.android.presentation.ui.chat.legacy.AgentThoughtIndicator*",
                    "app.knotwork.android.presentation.ui.chat.legacy.ClarificationCard*",
                    "app.knotwork.android.presentation.ui.chat.legacy.PipelineTraceCard*",
                    "app.knotwork.android.presentation.ui.chat.legacy.ApprovalBanner*",
                    "app.knotwork.android.presentation.ui.chat.legacy.ChatScreen*",
                    // Redesigned chat-home Composables — Compose surfaces are
                    // covered by `:catalog` Roborazzi snapshots; the
                    // testable VM / state-mapping live next to them and are
                    // included in coverage.
                    "app.knotwork.android.presentation.ui.chat.home.ChatHomeScreen*",
                    "app.knotwork.android.presentation.ui.chat.home.ChatHomeDebugStatePicker*",
                    "app.knotwork.android.presentation.ui.chat.home.DebugStateRows*",
                    "app.knotwork.android.presentation.ui.components.*",
                    "app.knotwork.android.presentation.ui.orchestrator.components.*",
                    // Pipeline editor Compose layer. Gestures, animations, and
                    // Bezier draw paths are intentionally outside the JVM Kover
                    // scope; the pure-Kotlin core (CanvasTransform, AutoLayout,
                    // EditorUndoRedo, BezierEdge, NodeConfigCodec) plus the VM
                    // hooks ARE covered. Screen-level visual coverage rides on the
                    // catalog's PipelineEditorCatalogPageSnapshotTest and the
                    // a11y + release-candidate gate.
                    "app.knotwork.android.presentation.ui.pipeline.editor.canvas.*",
                    "app.knotwork.android.presentation.ui.pipeline.editor.bars.*",
                    "app.knotwork.android.presentation.ui.pipeline.editor.sheet.*",
                    "app.knotwork.android.presentation.ui.pipeline.editor.PipelineEditorContent*",
                    "app.knotwork.android.presentation.ui.pipeline.editor.PipelineEditorScreen*",
                    "app.knotwork.android.presentation.ui.splash.SplashScreen*",
                    "app.knotwork.android.presentation.theme.*",
                    "app.knotwork.android.presentation.state.*",
                    // Additional Compose-surface / nav-glue packages. Same
                    // rationale as the
                    // existing presentation.ui.*Screen exclusions: rendering and
                    // navigation code needs Compose UI tests, not JVM unit tests.
                    // The redesigned bottom-nav shell (AppShellScaffold,
                    // AppNavGraph, TabDestination, BottomNavVisibility,
                    // KnotworkModalRoute, NavRoutes) is pure UI wiring; route
                    // constants in NavRoutes are unreachable in JVM tests.
                    "app.knotwork.android.presentation.ui.navigation.*",
                    // Single static About surface (AboutScreen.kt). The
                    // `AboutAcknowledgments` private object lives in the same
                    // file and is pure declarative data feeding the Composable.
                    "app.knotwork.android.presentation.ui.about.AboutScreen*",
                    "app.knotwork.android.presentation.ui.about.AboutAcknowledgments*",
                    // Bottom-nav "More" hub Composable. The MoreViewModel /
                    // MoreUiState in the same package remain inside the gate.
                    "app.knotwork.android.presentation.ui.more.MoreScreen*",
                    // Provider picker and detail Compose screens under
                    // presentation/ui/settings/provider — covered by the
                    // catalog snapshot suite, not JVM unit tests.
                    "app.knotwork.android.presentation.ui.settings.provider.ProviderPickerScreen*",
                    "app.knotwork.android.presentation.ui.settings.provider.ProviderDetailScreen*",
                    // AppFunctions callee side: the `AgentAppFunctionService`
                    // entry point, the service and inventory the compiler
                    // generates next to it, and the `SearchAppFunction` body
                    // (unit-tested, but sharing the package). The service needs
                    // the Android 16 AppFunctions host to run.
                    "app.knotwork.android.data.tools.local.appfunctions.*",
                    // `data.services.*` is now covered by
                    // Robolectric tests (`AgentForegroundServiceTest`,
                    // `AgentWorkerTest`, `AgentIdleManagerTest`,
                    // `AgentPowerManagerTest`, `LongRunningTaskNotifierImplTest`).
                    // The exclusion that was here while the package waited for
                    // Robolectric coverage has been lifted.
                    // `presentation.notifications.*` and
                    // `presentation.receivers.*` are now covered by Robolectric
                    // tests (`ApprovalNotificationManagerTest`,
                    // `AgentApprovalReceiverTest`). The exclusions that lived
                    // here while those packages waited for ShadowNotificationManager
                    // / BroadcastReceiver coverage have been lifted.
                    // Tool-execution Android glue (AppFunctions caller, search
                    // tool HTTP client, delegate-task LLM bridge) needs either
                    // an Android runtime or live LLM/HTTP fixtures.
                    "app.knotwork.android.data.tools.local.LocalAppFunctionManager",
                    "app.knotwork.android.data.tools.local.SearchTool*",
                    "app.knotwork.android.data.tools.local.DelegateTaskTool*",
                    // NOTE: `data.tools.local.executors.*` are pure JSON-arg
                    // parsers that delegate to the (excluded) Android-runtime
                    // tools above — they are JVM-unit-testable and covered by
                    // `*ExecutorTest`s under `data/tools/local/executors/`.
                    // Firebase Crashlytics glue: the `CrashlyticsTimberTree`
                    // (in `src/main`) and the `full`-flavour
                    // `FirebaseCrashReportingRepositoryImpl` (in `src/full`)
                    // thinly wrap `FirebaseCrashlytics` / `FirebaseAnalytics`
                    // singletons which need the Android runtime and Google Play
                    // services to initialise. Unit-test coverage for the
                    // no-op-when-disabled and dispatch branches lives under
                    // `FirebaseCrashReportingRepositoryImplTest` (`src/testFull`)
                    // / `CrashlyticsTimberTreeTest`, but the production
                    // `getInstance()` paths are not exercised on the JVM. The
                    // `foss` no-op impl carries no logic and is covered by
                    // `NoOpCrashReportingRepositoryTest` (`src/testFoss`).
                    "app.knotwork.android.data.logging.CrashlyticsTimberTree*",
                )
                // Belt-and-braces: also skip any @Preview-annotated function that
                // happens to live outside a *Preview.kt file.
                annotatedBy("androidx.compose.ui.tooling.preview.Preview")
            }
        }

        verify {
            // Aggregate gate — protects against silent regression across the
            // whole unit-testable surface. The current measured value
            // (May 2026) is ~77.5 %; the 75 % floor leaves a
            // ~2.5 pp buffer for in-flight refactors.
            //
            // Per-package thresholds were considered for this task but cannot
            // be expressed on Kover 0.9.x — the rule-level `filters { ... }`
            // block is a 0.10+ feature (not yet released; 0.9.8 is the latest
            // on the Gradle Plugin Portal as of May 2026). Per-package
            // *targets* are documented in docs/coverage-baseline.md as
            // guidance; promote them to enforced rules once Kover 0.10 ships.
            rule("Aggregate unit-testable coverage must stay ≥75%") {
                groupBy = kotlinx.kover.gradle.plugin.dsl.GroupingEntityType.APPLICATION
                bound {
                    minValue = 75
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                    aggregationForGroup =
                        kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE
                }
            }
        }
    }
}

// Wire `koverVerifyFullDebug` into the `check` lifecycle so a single
// `./gradlew check` invocation runs detekt + ktlintCheck + lint + unit tests +
// coverage verification. With the `distribution` flavour dimension the
// per-variant Kover task gains the flavour segment (`koverVerifyDebug` →
// `koverVerifyFullDebug`); the `full` variant is the representative coverage
// target because both flavours share every measured source — they differ only
// in the crash-reporting impl, and the `foss` no-op carries no logic to cover.
tasks.named("check") { dependsOn("koverVerifyFullDebug") }

// AGP wires only the *default* variant's lint (`lintFullDebug`) into `check`.
// With the `distribution` flavour dimension that leaves the `foss`-only sources
// (the no-op crash reporter + its DI module + the absence of the Firebase
// manifest overlay) un-linted. Add `lintFossDebug` so `check` lints BOTH
// flavours — matching the CI artifact upload and the "both flavours gated" claim.
tasks.named("check") { dependsOn("lintFossDebug") }

// Enforces the "no internal FQN" rule for the
// `app.knotwork.android.*` package. References like
// `app.knotwork.android.domain.models.NodeType` outside `import`/`package`
// statements must be replaced with a top-level import + short name. KDoc
// lines (whitespace-then-`*`) and the `.editorconfig` pinned ktlint rules
// catch the rest (wildcard imports, unused imports, import ordering).
val checkNoInternalFqn by tasks.registering {
    group = "verification"
    description =
        "Fails the build if any internal `app.knotwork.android.*` FQN reference appears in source code " +
        "outside of `import`/`package` statements or KDoc comments."
    val sourceRoots = listOf(
        "src/main/java",
        "src/main/kotlin",
        "src/test/java",
        "src/test/kotlin",
        "src/androidTest/java",
        "src/androidTest/kotlin",
    )
    val ktFiles = sourceRoots.flatMap { root ->
        fileTree("$projectDir/$root") { include("**/*.kt") }.files
    }
    inputs.files(ktFiles)
    doLast {
        val fqnPattern = Regex("""\bapp\.knotwork\.android\.[a-z_]+\.[A-Za-z]""")
        // Intent action strings are namespaced by the application id by Android
        // convention (`<applicationId>.action.NAME`), so they read like an internal
        // FQN while being wire data rather than a Kotlin reference — nothing an
        // import could replace, and frozen once third-party callers use them.
        // They are scrubbed from the line rather than exempting the whole line, so a
        // real FQN sitting beside an action string is still caught.
        val intentActionPattern = Regex("""\bapp\.knotwork\.android\.action\.""")
        val violations = mutableListOf<String>()
        ktFiles.forEach { file ->
            file.useLines { lines ->
                lines.forEachIndexed { index, line ->
                    val trimmed = line.trimStart()
                    // Skip `import …` / `package …` statements and `*`-style KDoc lines.
                    if (trimmed.startsWith("import ") ||
                        trimmed.startsWith("package ") ||
                        trimmed.startsWith("*") ||
                        trimmed.startsWith("//")
                    ) {
                        return@forEachIndexed
                    }
                    if (fqnPattern.containsMatchIn(intentActionPattern.replace(line, ""))) {
                        violations += "${file.relativeTo(rootDir)}:${index + 1}: ${line.trim()}"
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Internal FQN references found (use imports instead):\n" +
                    violations.joinToString(separator = "\n"),
            )
        }
    }
}
tasks.named("check") { dependsOn(checkNoInternalFqn) }

// Orphaned KDoc gate.
//
// Kotlin attaches a doc block to the declaration that follows it, and to no
// other. Nine blocks in this codebase documented nothing, and every one of them
// was the same accident: a function had been inserted *between* an existing
// doc block and the function it described, so the doc stayed put, the new
// function kept its own, and the original silently lost its documentation — in
// one case the entire migration policy of the Room database. Every individual
// line of those diffs was correct, which is why review never caught them.
//
// Only four were found by hand. Three more turned up the first time this gate
// ran — the hand sweep had looked for `*/` on a line of its own, and a
// single-line block closes on the line carrying its text — and two more the
// first time it ran against `:catalog`. That progression is the argument for a
// gate rather than a one-off cleanup.
//
// Typed rather than ad-hoc for the reasons the file-map pair records below:
// configuration-cache compatibility, and a declared output so `check` can skip
// it while no source has changed.
val verifyNoOrphanedKdoc by tasks.registering(VerifyNoOrphanedKdocTask::class) {
    group = "verification"
    description = "Fails the build if a KDoc block documents no declaration."
    repositoryRoot.set(rootProject.layout.projectDirectory)
    sources.from(
        // androidTest included deliberately: `check` neither runs nor compiles
        // that source set, so a doc block orphaned there would go unseen until
        // the separate instrumented job — and this check only reads text.
        listOf(
            "src/main/java",
            "src/main/kotlin",
            "src/test/java",
            "src/test/kotlin",
            "src/androidTest/java",
            "src/androidTest/kotlin",
        ).map { root ->
            fileTree("$projectDir/$root") { include("**/*.kt") }
        },
    )
    stampFile.set(layout.buildDirectory.file("reports/kdoc/no-orphans.txt"))
}
tasks.named("check") { dependsOn(verifyNoOrphanedKdoc) }

// Forbidden-vocabulary gate.
//
// The product was renamed, and the old name kept greeting every user from the
// onboarding title for three months — through a pre-launch clean-up and a Play
// release — because nothing looked for it. It was found by inspecting a frame
// of a published demo video. The same inspection found it in the GitHub
// issue-template chooser and in a wake-lock tag Android vitals shows in the Play
// Console. Internal planning numbering had the same shape: removed once by a
// search that matched one spelling, then quietly re-accumulated in every
// spelling that search did not match.
//
// Scope is every public TEXT file, by glob — source, resources, bundled assets,
// build logic, documentation, store metadata, CI configuration — so a new file is
// guarded without anyone remembering to list it. Two exclusions, both deliberate:
// `CHANGELOG.md`, whose past entries name old identifiers as they were at the
// time, and `buildSrc/src/test`, whose fixtures must spell the forbidden forms.
// The bundled documentation under `assets/docs` is a generated copy of `docs/`,
// which is scanned at its source instead of twice.
val publicTextExtensions = listOf(
    "kt", "kts", "java", "xml", "json", "md", "txt", "html", "js", "mjs",
    "yml", "yaml", "toml", "properties", "pro", "sh",
)

fun publicTextTree(directory: String, vararg excludes: String): ConfigurableFileTree = fileTree(directory) {
    publicTextExtensions.forEach { include("**/*.$it") }
    exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**", "**/.cxx/**", *excludes)
}

val verifyForbiddenVocabulary by tasks.registering(VerifyForbiddenVocabularyTask::class) {
    group = "verification"
    description = "Fails the build if public text carries the retired product name or internal planning numbering."
    repositoryRoot.set(rootProject.layout.projectDirectory)
    sources.from(
        fileTree(rootDir) {
            include("*.md", "*.kts", "*.html", "*.properties", "NOTICE")
            exclude("CHANGELOG.md", "CLAUDE.md", "CLAUDE.local.md", "local.properties")
        },
        publicTextTree("$rootDir/app", "src/main/assets/docs/**"),
        publicTextTree("$rootDir/catalog"),
        publicTextTree("$rootDir/buildSrc", "src/test/**"),
        publicTextTree("$rootDir/tools-probe"),
        publicTextTree("$rootDir/docs"),
        publicTextTree("$rootDir/.github"),
        publicTextTree("$rootDir/fastlane"),
        publicTextTree("$rootDir/config"),
        publicTextTree("$rootDir/gradle"),
    )
    requiredPrefixes.set(
        listOf(
            "",
            "app/",
            "catalog/",
            "buildSrc/",
            "tools-probe/",
            "docs/",
            ".github/",
            "fastlane/",
            "config/",
            "gradle/",
        ),
    )
    stampFile.set(layout.buildDirectory.file("reports/vocabulary/verified.txt"))
}
tasks.named("check") { dependsOn(verifyForbiddenVocabulary) }

// Dialog inventory gate.
//
// A dialog composed in `:app` cannot be photographed: Roborazzi runs in
// `:catalog`. That is not theory — `SaveAsPresetDialog` shipped with its
// selected category chip visually indistinguishable from the unselected ones,
// and reached a manual device run before anyone noticed, because nothing could
// compare it against anything.
//
// The inventory meant to prevent that was built by hand twice and was wrong
// twice: once listing screens that already had catalog twins under
// feature-named files, once missing the dialogs entirely because it matched
// `*Screen(` composables. So the list is derived from the sources here, and
// every call site is answered once, in writing, below.
val dialogInventoryAllowlist = mapOf(
    // ── Hosts. The sanctioned arrangement: the body lives in `:catalog` and
    // the host keeps the wrapper, because scrim, IME and navigation behaviour
    // belong to the screen.
    "app/src/main/java/app/knotwork/android/presentation/ui/navigation/KnotworkModalRoute.kt" to
        "Generic modal-route wrapper; it hosts whatever content a route supplies and composes none itself.",
    "app/src/main/java/app/knotwork/android/presentation/ui/prompts/PromptLibraryScreen.kt" to
        "Hosts the catalog's PromptEditorSheetBody.",
    "app/src/main/java/app/knotwork/android/presentation/ui/taskmonitor/TaskMonitorScreen.kt" to
        "Hosts the catalog's TaskMonitorDetailSheetBody.",
    "app/src/main/java/app/knotwork/android/presentation/ui/orchestrator/components/PromptPresetPickerDialog.kt" to
        "Hosts the catalog's PromptPresetPickerSheet.",
    "app/src/main/java/app/knotwork/android/presentation/ui/orchestrator/presets/PresetPickerSheet.kt" to
        "Hosts the catalog's PresetPickerSheetBody.",

    // ── Deliberate deviations. Each composes its own dialog, and each has a
    // reason that is not "nobody got to it".
    "app/src/main/java/app/knotwork/android/presentation/ui/onboarding/OnboardingScreen.kt" to
        "Exit confirmation whose affirmative action is a primary button rather than a text button — " +
        "a deliberate weight difference for the one dialog that closes the app. Folding it into " +
        "ConfirmDialog would change how it looks, which is a design decision and not a refactor.",
    "app/src/main/java/app/knotwork/android/presentation/ui/orchestrator/PipelineLibraryScreen.kt" to
        "Three dialogs that are not yes/no questions: delete-with-dependents renders a pluralised " +
        "warning and the list of pipelines that would be left dangling, and the two import dialogs " +
        "offer a choice among several outcomes rather than a confirmation.",
    "app/src/main/java/app/knotwork/android/presentation/ui/pipeline/editor/PipelineEditorScreen.kt" to
        "The unsaved-changes dialog offers three actions — Save and leave, Discard, Cancel — so it " +
        "is not a ConfirmDialog. Save occupies the confirm slot deliberately; see the note there.",

    // ── Known remaining work, recorded rather than hidden.
    "app/src/main/java/app/knotwork/android/presentation/ui/chat/home/ChatHomeScreen.kt" to
        "Two sheets whose bodies are still private composables in `:app` (rename session, new-thread " +
        "pipeline picker). Found by this gate after the hand inventory had already been declared " +
        "complete — which is the argument for the gate. They are the next two to move.",
)

val verifyDialogInventory by tasks.registering(VerifyDialogInventoryTask::class) {
    group = "verification"
    description = "Fails the build if a dialog or sheet is composed in :app without a recorded reason."
    repositoryRoot.set(rootProject.layout.projectDirectory)
    sources.from(fileTree("$projectDir/src/main/java") { include("**/*.kt") })
    allowed.set(dialogInventoryAllowlist)
    stampFile.set(layout.buildDirectory.file("reports/dialogs/inventory.txt"))
}
tasks.named("check") { dependsOn(verifyDialogInventory) }

// Browser pipeline-editor constant sync automation.
//
// `pipeline-editor.html` mirrors a slice of the Android domain (node types,
// prompt variables, available tools, default prompts, and the bundled presets and
// prompt templates). Those mirrors used to be
// kept in sync by review alone and drifted. `generateBrowserEditorConstants`
// regenerates the `AUTO-GEN` blocks straight from the domain sources;
// `verifyBrowserEditorConstants` (wired into `check`) fails the build if the
// committed HTML has drifted. The pure generation logic lives in
// `buildSrc` (`BrowserEditorConstantsGenerator`) and is unit-tested there.
val browserEditorHtmlFile = file("$rootDir/pipeline-editor.html")
val browserEditorNodeTypeFile =
    file("$projectDir/src/main/java/app/knotwork/android/domain/models/NodeType.kt")
val browserEditorDefaultPromptsFile =
    file("$projectDir/src/main/java/app/knotwork/android/domain/constants/DefaultPrompts.kt")
val browserEditorPromptModuleFile =
    file("$projectDir/src/main/java/app/knotwork/android/di/PromptTemplateModule.kt")
val browserEditorToolsModuleFile =
    file("$projectDir/src/main/java/app/knotwork/android/di/LocalToolsModule.kt")
// Provider `KEY` constants + tool `TOOL_NAME` constants the generator must resolve.
val browserEditorClassSourceFiles: Set<File> = buildSet {
    addAll(fileTree("$projectDir/src/main/java/app/knotwork/android/data/prompt") { include("**/*.kt") }.files)
    addAll(fileTree("$projectDir/src/main/java/app/knotwork/android/data/tools/local") { include("**/*.kt") }.files)
}
// The bundled presets and prompt templates the editor carries, plus the catalogue
// that orders the presets.
val browserEditorPresetFiles: Set<File> =
    fileTree("$projectDir/src/main/assets/presets/pipelines") { include("*.json") }.files
val browserEditorTemplateFiles: Set<File> =
    fileTree("$projectDir/src/main/assets/presets/prompts") { include("*.json") }.files
val browserEditorPresetCatalogFile =
    file("$projectDir/src/main/java/app/knotwork/android/domain/constants/BundledPresetCatalog.kt")
// The app's own reading rules the editor's import is checked against (not generated from).
val browserEditorSerializerFile =
    file("$projectDir/src/main/java/app/knotwork/android/domain/pipelineio/PipelineJsonSerializer.kt")
val browserEditorCloudProviderFile =
    file("$projectDir/src/main/java/app/knotwork/android/domain/models/CloudProvider.kt")
// Every file whose content feeds the generated blocks; drives up-to-date checks.
val browserEditorInputFiles: Set<File> = browserEditorClassSourceFiles + browserEditorPresetFiles +
    browserEditorTemplateFiles + setOf(
        browserEditorNodeTypeFile,
        browserEditorDefaultPromptsFile,
        browserEditorPromptModuleFile,
        browserEditorToolsModuleFile,
        browserEditorPresetCatalogFile,
    )

fun browserEditorPresetSources() = BrowserEditorConstantsGenerator.BundledPresetSources(
    pipelinePresets = browserEditorPresetFiles.associate { it.name to it.readText() },
    presetCatalog = browserEditorPresetCatalogFile.readText(),
    promptTemplates = browserEditorTemplateFiles.associate { it.name to it.readText() },
)

val generateBrowserEditorConstants by tasks.registering {
    group = "build"
    description =
        "Regenerates the AUTO-GEN constant blocks in pipeline-editor.html from the Android domain sources."
    inputs.files(browserEditorInputFiles)
    inputs.file(browserEditorHtmlFile)
    outputs.file(browserEditorHtmlFile)
    doLast {
        val current = browserEditorHtmlFile.readText()
        val rendered = BrowserEditorConstantsGenerator.render(
            html = current,
            nodeTypeSource = browserEditorNodeTypeFile.readText(),
            defaultPromptsSource = browserEditorDefaultPromptsFile.readText(),
            promptTemplateModuleSource = browserEditorPromptModuleFile.readText(),
            localToolsModuleSource = browserEditorToolsModuleFile.readText(),
            classSources = browserEditorClassSourceFiles.associate { it.nameWithoutExtension to it.readText() },
            presets = browserEditorPresetSources(),
        )
        if (rendered != current) {
            browserEditorHtmlFile.writeText(rendered)
            logger.lifecycle("pipeline-editor.html: regenerated AUTO-GEN constants.")
        } else {
            logger.lifecycle("pipeline-editor.html: AUTO-GEN constants already up to date.")
        }
    }
}

// ── Settings help documentation ─────────────────────────────────────────────
// `strings_settings_help.xml` is the canonical source for what a setting means;
// the guide's reference table quotes it rather than restating it. Before this,
// one setting's meaning lived in four places and closed testing found them
// already disagreeing — one copy quoting a threshold no constant in the code
// held. `generateSettingsHelpDocs` rewrites the AUTO-GEN block;
// `verifySettingsHelpDocs` (wired into `check`) fails the build on drift.
val settingsHelpGuideFile = file("$rootDir/docs/user-guide.md")
val settingsHelpRegistryFile =
    file("$projectDir/src/main/java/app/knotwork/android/domain/settings/SettingsRegistry.kt")
val settingsHelpCatalogFile =
    file("$projectDir/src/main/java/app/knotwork/android/presentation/ui/settings/SettingsHelpCatalog.kt")
val settingsSearchCatalogFile =
    file("$projectDir/src/main/java/app/knotwork/android/presentation/ui/settings/SettingsSearchCatalog.kt")
val settingsHelpStringsFile = file("$projectDir/src/main/res/values/strings_settings_help.xml")
val settingsNameStringsFiles = listOf(
    file("$projectDir/src/main/res/values/strings_settings_search.xml"),
    file("$projectDir/src/main/res/values/strings_run_limits.xml"),
)
val settingsHelpInputFiles = files(
    settingsHelpRegistryFile,
    settingsHelpCatalogFile,
    settingsSearchCatalogFile,
    settingsHelpStringsFile,
    settingsNameStringsFiles,
)

val generateSettingsHelpDocs by tasks.registering {
    group = "build"
    description = "Regenerates the settings reference table of docs/user-guide.md from the shipped help strings."
    inputs.files(settingsHelpInputFiles)
    inputs.file(settingsHelpGuideFile)
    outputs.file(settingsHelpGuideFile)
    doLast {
        val rendered = SettingsHelpDocsGenerator.render(
            markdown = settingsHelpGuideFile.readText(),
            registrySource = settingsHelpRegistryFile.readText(),
            helpCatalogSource = settingsHelpCatalogFile.readText(),
            searchCatalogSource = settingsSearchCatalogFile.readText(),
            helpStringsXml = settingsHelpStringsFile.readText(),
            nameStringsXml = settingsNameStringsFiles.map { it.readText() },
        )
        if (rendered != settingsHelpGuideFile.readText()) {
            settingsHelpGuideFile.writeText(rendered)
            logger.lifecycle("Regenerated the settings reference table in docs/user-guide.md.")
        }
    }
}

val verifySettingsHelpDocs by tasks.registering {
    group = "verification"
    description = "Fails the build if the settings reference table in docs/user-guide.md has drifted."
    inputs.files(settingsHelpInputFiles)
    inputs.file(settingsHelpGuideFile)
    doLast {
        val drifted = SettingsHelpDocsGenerator.drift(
            markdown = settingsHelpGuideFile.readText(),
            registrySource = settingsHelpRegistryFile.readText(),
            helpCatalogSource = settingsHelpCatalogFile.readText(),
            searchCatalogSource = settingsSearchCatalogFile.readText(),
            helpStringsXml = settingsHelpStringsFile.readText(),
            nameStringsXml = settingsNameStringsFiles.map { it.readText() },
        )
        if (drifted) {
            throw GradleException(
                "The settings reference table in docs/user-guide.md has drifted from the shipped help strings. " +
                    "Run `./gradlew :app:generateSettingsHelpDocs` and commit the updated docs/user-guide.md.",
            )
        }
    }
}

// A verify task reads the file its sibling generate task writes. Without an
// ordering rule Gradle rejects the pair the moment both are requested in one
// invocation — `./gradlew :app:generateSettingsHelpDocs check`, which is exactly
// how the pair is meant to be used. `mustRunAfter` states the ordering without
// making verification depend on regeneration.
verifySettingsHelpDocs { mustRunAfter(generateSettingsHelpDocs) }
tasks.named("check") { dependsOn(verifySettingsHelpDocs) }

val verifyBrowserEditorConstants by tasks.registering {
    group = "verification"
    description =
        "Fails the build if pipeline-editor.html AUTO-GEN constant blocks have drifted from the Android domain sources."
    inputs.files(browserEditorInputFiles)
    inputs.file(browserEditorHtmlFile)
    inputs.files(browserEditorSerializerFile, browserEditorCloudProviderFile)
    doLast {
        val drifted = BrowserEditorConstantsGenerator.drift(
            html = browserEditorHtmlFile.readText(),
            nodeTypeSource = browserEditorNodeTypeFile.readText(),
            defaultPromptsSource = browserEditorDefaultPromptsFile.readText(),
            promptTemplateModuleSource = browserEditorPromptModuleFile.readText(),
            localToolsModuleSource = browserEditorToolsModuleFile.readText(),
            classSources = browserEditorClassSourceFiles.associate { it.nameWithoutExtension to it.readText() },
            presets = browserEditorPresetSources(),
        )
        if (drifted.isNotEmpty()) {
            throw GradleException(
                "pipeline-editor.html is out of sync with the Android domain sources.\n" +
                    "Drifted AUTO-GEN block(s): ${drifted.joinToString(", ")}.\n" +
                    "Run `./gradlew :app:generateBrowserEditorConstants` and commit the updated pipeline-editor.html.",
            )
        }
        // Outside the generated blocks: the node forms must not offer a control for a
        // field no run reads (docs/decisions/0005) — the same round-trip-only verdicts
        // the cookbook publishes.
        val inert = BrowserEditorInertControlGuard.inertControls(
            html = browserEditorHtmlFile.readText(),
            reach = CookbookDocsGenerator.FIELD_REACH,
        )
        if (inert.isNotEmpty()) {
            throw GradleException(
                "pipeline-editor.html offers controls for fields no run reads: ${inert.joinToString(", ")}.\n" +
                    "Remove them from renderFormFields (and their validation); keep the fields in the " +
                    "envelope encode/decode so files still round-trip. See docs/decisions/0005.",
            )
        }
        // Also outside the generated blocks: everything the editor derives for the
        // run must travel in the exported `config` block — the app's node sheet shows
        // that copy, so a derived field left out is a browser setting lost on import.
        val unexported = BrowserEditorFlatExportGuard.unexportedFlatFields(browserEditorHtmlFile.readText())
        if (unexported.isNotEmpty()) {
            throw GradleException(
                "pipeline-editor.html derives run-time fields it never exports: ${unexported.joinToString(", ")}.\n" +
                    "Add them to the `config` block in exportToJson — the app runs, and shows, only that copy.",
            )
        }
        // Every field the app runs on must survive the editor end to end: read from the
        // file's flat copy, offered as a control, written back into both copies and
        // exported. The fields are the cookbook's Runtime verdicts, not the editor's lists.
        val broken = BrowserEditorRuntimeFieldGuard.brokenLinks(
            html = browserEditorHtmlFile.readText(),
            reach = CookbookDocsGenerator.FIELD_REACH,
        )
        if (broken.isNotEmpty()) {
            throw GradleException(
                "pipeline-editor.html loses run-time fields between import and export:\n" +
                    broken.joinToString("\n") { "  - $it" } + "\n" +
                    "Each field in CookbookDocsGenerator.FIELD_REACH with a Runtime verdict must pass " +
                    "importFromJson → deriveRichFromFlat → renderFormFields → encodeRichEnvelope / " +
                    "richToFlat → exportToJson, and decodeRichEnvelope must not read it from the envelope.",
            )
        }
        // And the editor must read a file by the app's rules: the same config keys, the
        // same Input-data flags, the same provider ids — checked against the app's source.
        val parity = BrowserEditorImportParityGuard.mismatches(
            html = browserEditorHtmlFile.readText(),
            serializerSource = browserEditorSerializerFile.readText(),
            cloudProviderSource = browserEditorCloudProviderFile.readText(),
        )
        if (parity.isNotEmpty()) {
            throw GradleException(
                "pipeline-editor.html reads a pipeline file differently from the app:\n" +
                    parity.joinToString("\n") { "  - $it" } + "\n" +
                    "People inspect a file in the browser before importing it; it must show what the app runs.",
            )
        }
    }
}
verifyBrowserEditorConstants { mustRunAfter(generateBrowserEditorConstants) }
tasks.named("check") { dependsOn(verifyBrowserEditorConstants) }

// External-automation contract documentation sync automation.
//
// `docs/external-automation.md` publishes the action strings, extra keys,
// statuses and refusal reasons of a contract whose callers live in other apps.
// Once a Tasker profile or an `adb` one-liner is written against a key, that key
// is frozen — and documentation that has drifted from the code fails silently on
// the caller's side, since a request built from a stale key merely looks
// malformed to the app. `generateExternalAutomationDocs` regenerates the
// `AUTO-GEN` tables straight from the Kotlin declarations;
// `verifyExternalAutomationDocs` (wired into `check`) fails the build if the
// committed Markdown has drifted. The pure generation logic lives in `buildSrc`
// (`ExternalAutomationDocsGenerator`) and is unit-tested there.
//
// Inputs are resolved into local `val`s and captured by the task actions as
// plain `File` values, so the actions never reach back into the `Project` —
// keeping both tasks configuration-cache compatible.
val externalAutomationDocsFile = file("$rootDir/docs/external-automation.md")
val externalAutomationContractFile =
    file("$projectDir/src/main/java/app/knotwork/android/domain/constants/ExternalAutomationContract.kt")
val externalAutomationStatusFile =
    file("$projectDir/src/main/java/app/knotwork/android/domain/models/ExternalAutomationStatus.kt")
val externalAutomationReasonFile =
    file("$projectDir/src/main/java/app/knotwork/android/domain/models/ExternalAutomationRejectionReason.kt")
val externalAutomationInputFiles: Set<File> = setOf(
    externalAutomationContractFile,
    externalAutomationStatusFile,
    externalAutomationReasonFile,
)

val generateExternalAutomationDocs by tasks.registering {
    group = "build"
    description =
        "Regenerates the AUTO-GEN reference tables in docs/external-automation.md from the contract sources."
    inputs.files(externalAutomationInputFiles)
    inputs.file(externalAutomationDocsFile)
    outputs.file(externalAutomationDocsFile)
    doLast {
        val current = externalAutomationDocsFile.readText()
        val rendered = ExternalAutomationDocsGenerator.render(
            markdown = current,
            contractSource = externalAutomationContractFile.readText(),
            statusSource = externalAutomationStatusFile.readText(),
            reasonSource = externalAutomationReasonFile.readText(),
        )
        if (rendered != current) {
            externalAutomationDocsFile.writeText(rendered)
            logger.lifecycle("docs/external-automation.md: regenerated AUTO-GEN tables.")
        } else {
            logger.lifecycle("docs/external-automation.md: AUTO-GEN tables already up to date.")
        }
    }
}

val verifyExternalAutomationDocs by tasks.registering {
    group = "verification"
    description =
        "Fails the build if docs/external-automation.md AUTO-GEN tables have drifted from the contract sources."
    inputs.files(externalAutomationInputFiles)
    inputs.file(externalAutomationDocsFile)
    doLast {
        val drifted = ExternalAutomationDocsGenerator.drift(
            markdown = externalAutomationDocsFile.readText(),
            contractSource = externalAutomationContractFile.readText(),
            statusSource = externalAutomationStatusFile.readText(),
            reasonSource = externalAutomationReasonFile.readText(),
        )
        if (drifted.isNotEmpty()) {
            throw GradleException(
                "docs/external-automation.md is out of sync with the external-automation contract sources.\n" +
                    "Drifted AUTO-GEN block(s): ${drifted.joinToString(", ")}.\n" +
                    "Run `./gradlew :app:generateExternalAutomationDocs` and commit the updated Markdown.",
            )
        }
    }
}
verifyExternalAutomationDocs { mustRunAfter(generateExternalAutomationDocs) }
tasks.named("check") { dependsOn(verifyExternalAutomationDocs) }

// ── Node-type cookbook reference ────────────────────────────────────────────
// `docs/cookbook.md` is the public per-node reference. Before it existed the
// only one was `node-specs.md`, an internal design document the public guide
// nevertheless pointed readers at — so the reference a reader could reach was
// no reference at all. Written by hand it would have drifted on the first node
// type added after publication, exactly as `FILE_MAP.md` did.
//
// `generateCookbookDocs` rebuilds the AUTO-GEN blocks from the sources that
// define a node — the domain enum, the `:catalog` mirror, the ports factory,
// the context defaults, the config hierarchy and the default prompts;
// `verifyCookbookDocs` (wired into `check`) fails the build on drift. The pure
// generation logic lives in `buildSrc` (`CookbookDocsGenerator`) and is
// unit-tested there.
//
// Inputs are resolved into local `val`s and captured by the task actions as
// plain `File` values, so no action reaches back into the `Project` at
// execution time — the access Gradle 10 turns into a hard error.
//
// That is not the same as being configuration-cache ready, and the comment on
// the sibling pair above claims more than it delivers: measured with
// `--configuration-cache`, all four generate/verify pairs fail to serialize
// ("cannot serialize Gradle script object references"), because a Kotlin-DSL
// lambda referring to a script-level `val` captures the script itself. Closing
// that means real task classes with annotated properties, for all four pairs at
// once; it is tracked rather than half-done here.
val cookbookDocsFile = file("$rootDir/docs/cookbook.md")
val cookbookDomainNodeTypeFile =
    file("$projectDir/src/main/java/app/knotwork/android/domain/models/NodeType.kt")
val cookbookCatalogNodeTypeFile =
    file("$rootDir/catalog/src/main/java/app/knotwork/design/components/pipelineeditor/NodeType.kt")
val cookbookNodePortsFile =
    file("$rootDir/catalog/src/main/java/app/knotwork/design/components/pipelineeditor/NodePorts.kt")
val cookbookNodeContextConfigFile =
    file("$projectDir/src/main/java/app/knotwork/android/domain/models/NodeContextConfig.kt")
val cookbookNodeConfigFile =
    file("$rootDir/catalog/src/main/java/app/knotwork/design/components/pipelineeditor/NodeConfig.kt")
val cookbookDefaultPromptsFile =
    file("$projectDir/src/main/java/app/knotwork/android/domain/constants/DefaultPrompts.kt")
val cookbookInputFiles: Set<File> = setOf(
    cookbookDomainNodeTypeFile,
    cookbookCatalogNodeTypeFile,
    cookbookNodePortsFile,
    cookbookNodeContextConfigFile,
    cookbookNodeConfigFile,
    cookbookDefaultPromptsFile,
)

// Built inline in each task action rather than by a shared helper: a `doLast`
// block that calls a top-level function declared in the same build script is
// not configuration-cache compatible, because the lambda then captures the
// script instance rather than a class reference.
val generateCookbookDocs by tasks.registering {
    group = "build"
    description = "Regenerates the AUTO-GEN node reference in docs/cookbook.md from the node sources."
    inputs.files(cookbookInputFiles)
    inputs.file(cookbookDocsFile)
    outputs.file(cookbookDocsFile)
    doLast {
        val current = cookbookDocsFile.readText()
        val rendered = CookbookDocsGenerator.render(
            markdown = current,
            sources = CookbookDocsGenerator.Sources(
                domainNodeType = cookbookDomainNodeTypeFile.readText(),
                catalogNodeType = cookbookCatalogNodeTypeFile.readText(),
                nodePorts = cookbookNodePortsFile.readText(),
                nodeContextConfig = cookbookNodeContextConfigFile.readText(),
                nodeConfig = cookbookNodeConfigFile.readText(),
                defaultPrompts = cookbookDefaultPromptsFile.readText(),
            ),
        )
        if (rendered != current) {
            cookbookDocsFile.writeText(rendered)
            logger.lifecycle("docs/cookbook.md: regenerated the AUTO-GEN node reference.")
        } else {
            logger.lifecycle("docs/cookbook.md: AUTO-GEN node reference already up to date.")
        }
    }
}

val verifyCookbookDocs by tasks.registering {
    group = "verification"
    description = "Fails the build if the AUTO-GEN node reference in docs/cookbook.md has drifted."
    inputs.files(cookbookInputFiles)
    inputs.file(cookbookDocsFile)
    doLast {
        val drifted = CookbookDocsGenerator.drift(
            markdown = cookbookDocsFile.readText(),
            sources = CookbookDocsGenerator.Sources(
                domainNodeType = cookbookDomainNodeTypeFile.readText(),
                catalogNodeType = cookbookCatalogNodeTypeFile.readText(),
                nodePorts = cookbookNodePortsFile.readText(),
                nodeContextConfig = cookbookNodeContextConfigFile.readText(),
                nodeConfig = cookbookNodeConfigFile.readText(),
                defaultPrompts = cookbookDefaultPromptsFile.readText(),
            ),
        )
        if (drifted.isNotEmpty()) {
            throw GradleException(
                "docs/cookbook.md is out of sync with the node sources.\n" +
                    "Drifted AUTO-GEN block(s): ${drifted.joinToString(", ")}.\n" +
                    "Run `./gradlew :app:generateCookbookDocs` and commit the updated docs/cookbook.md.",
            )
        }
    }
}
verifyCookbookDocs { mustRunAfter(generateCookbookDocs) }
tasks.named("check") { dependsOn(verifyCookbookDocs) }

// Generated file maps.
//
// `FILE_MAP.md` used to be kept in step by a post-write hook, a PR-checklist
// item and a step of the agent workflow — all three of which name the map under
// `app/src/main`, while the repository holds several. Measured before this pair
// existed: that one map was accurate to four entries, the `:catalog` map was
// missing 128 of its own sources, the unit-test map named 44 files of 375 and
// the instrumented map 16 of 60. A map covering an eighth of its directory does
// not read as stale; it reads as complete, which is worse.
//
// So the *structure* of each map is now derived from the source tree, while the
// *descriptions* — which carry design rationale no KDoc holds — are carried
// across by path and only seeded from KDoc when a path has none. The gap count
// is ratcheted in `config/file-map/baseline.properties`.
//
// Unlike the four pairs above, these are typed task classes. Measured, on each
// pair's own task graph: `:app:verifyCookbookDocs --configuration-cache` fails
// with "cannot serialize Gradle script object references" (an ad-hoc `doLast`
// block reading a build-script `val` captures the whole build script), while
// this pair stores and reuses an entry. And an ad-hoc verification task declares
// no output, so Gradle can never treat it as up to date; `VerifyFileMapTask`
// declares a stamp and is skipped while nothing it reads has changed.
val fileMapSpecs = listOf(
    FileMapSpec(
        mapPath = "app/src/main/java/app/knotwork/android/FILE_MAP.md",
        blockId = "FILE_MAP",
        roots = listOf(FileMapSpec.Root("app/src/main/java/app/knotwork/android")),
        baselineKey = "app-main",
    ),
    FileMapSpec(
        mapPath = "app/src/main/java/app/knotwork/android/FILE_MAP.md",
        blockId = "FILE_MAP_SOURCE_SETS",
        roots = listOf("full", "foss", "debug", "testFull", "testFoss").map { sourceSet ->
            FileMapSpec.Root(
                dir = "app/src/$sourceSet/java/app/knotwork/android",
                prefix = "$sourceSet/",
            )
        },
        baselineKey = "app-source-sets",
    ),
    FileMapSpec(
        mapPath = "app/src/test/java/app/knotwork/android/FILE_MAP.md",
        blockId = "FILE_MAP",
        roots = listOf(FileMapSpec.Root("app/src/test/java/app/knotwork/android")),
        baselineKey = "app-test",
    ),
    FileMapSpec(
        mapPath = "app/src/androidTest/java/app/knotwork/android/FILE_MAP.md",
        blockId = "FILE_MAP",
        roots = listOf(FileMapSpec.Root("app/src/androidTest/java/app/knotwork/android")),
        baselineKey = "app-android-test",
    ),
    FileMapSpec(
        mapPath = "catalog/FILE_MAP.md",
        blockId = "FILE_MAP",
        roots = listOf(FileMapSpec.Root("catalog/src/main/java/app/knotwork/design")),
        baselineKey = "catalog",
    ),
    FileMapSpec(
        // The catalog's test tree used to be prose beside a generated block: 42
        // entries describing 83 files, verified by nothing. That is the exact
        // shape the generated maps exist to remove — a map covering half its
        // directory does not read as stale, it reads as complete.
        mapPath = "catalog/FILE_MAP.md",
        blockId = "FILE_MAP_TESTS",
        roots = listOf(FileMapSpec.Root("catalog/src/test/java/app/knotwork/design")),
        baselineKey = "catalog-test",
    ),
)

val fileMapSources: FileCollection = files(
    fileMapSpecs
        .flatMap { spec -> spec.roots.map { it.dir } }
        .distinct()
        .map { dir -> fileTree("$rootDir/$dir") { include("**/*.kt") } },
)

val fileMapFiles: FileCollection = files(fileMapSpecs.map { "$rootDir/${it.mapPath}" }.distinct())

val fileMapBaselineFile = file("$rootDir/config/file-map/baseline.properties")

val generateFileMap by tasks.registering(GenerateFileMapTask::class) {
    group = "build"
    description = "Regenerates the AUTO-GEN source trees of every FILE_MAP.md from the Kotlin sources."
    repositoryRoot.set(rootProject.layout.projectDirectory)
    sources.from(fileMapSources)
    maps.from(fileMapFiles)
    specs.set(fileMapSpecs)
    baselineFile.set(fileMapBaselineFile)
    outputMaps.from(fileMapFiles)
    acceptDroppedDescriptions.set(providers.gradleProperty("acceptFileMapDrops").map { true }.orElse(false))
}

val verifyFileMap by tasks.registering(VerifyFileMapTask::class) {
    group = "verification"
    description = "Fails the build if a FILE_MAP.md has drifted from the Kotlin sources, or gaps grew past the ratchet."
    repositoryRoot.set(rootProject.layout.projectDirectory)
    sources.from(fileMapSources)
    maps.from(fileMapFiles)
    specs.set(fileMapSpecs)
    baselineFile.set(fileMapBaselineFile)
    stampFile.set(layout.buildDirectory.file("reports/file-map/verified.txt"))
}

verifyFileMap { mustRunAfter(generateFileMap) }
tasks.named("check") { dependsOn(verifyFileMap) }

// Documentation-link registry — the list of documents the app links out to.
//
// The list itself lives in `buildSrc` (`DocumentationLinkRegistry`), not here
// and not in `app`, because three readers need it: the app, this gate, and the
// bundled-documentation sync. `buildSrc` cannot see `app` code, so the build
// owns the list and GENERATES the app's copy — the only arrangement where the
// list and the GitHub slug algorithm each have exactly one owner. Parsing the
// Kotlin object back out of `app` was the alternative, and the one precedent
// for that skips records of unexpected shape silently.
//
// `generateDocumentationLinks` rewrites the committed copy;
// `verifyDocumentationLinks` (wired into `check`) fails on drift AND on any
// entry whose heading no longer resolves. Unlike `verifyDocLinks` this one is
// typed and cacheable, because the registry confines every target to `docs/`,
// which makes the declared input set complete.
val documentationLinkSources: FileCollection = files(
    fileTree("$rootDir/docs") { include("**/*.md") },
)

val documentationLinksPackage = "app.knotwork.android.domain.constants"

val documentationLinksFile =
    file("$rootDir/app/src/main/java/app/knotwork/android/domain/constants/DocumentationLinks.kt")

val generateDocumentationLinks by tasks.registering(GenerateDocumentationLinksTask::class) {
    group = "build"
    description = "Regenerates the app-side documentation-link registry from the build-side list."
    repositoryRoot.set(rootProject.layout.projectDirectory)
    documents.from(documentationLinkSources)
    generatedPackage.set(documentationLinksPackage)
    outputSource.set(documentationLinksFile)
}

val verifyDocumentationLinks by tasks.registering(VerifyDocumentationLinksTask::class) {
    group = "verification"
    description = "Fails the build if the documentation-link registry drifted, or an entry's heading moved."
    repositoryRoot.set(rootProject.layout.projectDirectory)
    documents.from(documentationLinkSources)
    generatedPackage.set(documentationLinksPackage)
    committedSource.from(documentationLinksFile)
    stampFile.set(layout.buildDirectory.file("reports/documentation-links/verified.txt"))
}

verifyDocumentationLinks { mustRunAfter(generateDocumentationLinks) }
tasks.named("check") { dependsOn(verifyDocumentationLinks) }

// Bundled documentation — the documents a user reads *inside* the app.
//
// `faq.md` and `troubleshooting.md` are needed exactly when the browser is
// least available: one is the router, the other is "it broke", and one of its
// sections is about the app failing at startup. So they ship in the APK, and
// the copy is produced by the build rather than by hand — a hand-copied
// document is a third edition of the text that nothing keeps honest.
//
// The input set is the `docs` tree PLUS the repository's root Markdown,
// because a bundled document links to `../SECURITY.md` and `../PRIVACY.md`.
// Declaring only `docs` would resolve those against files the task never
// declared, and stay green after one was deleted.
val bundledDocsSources: FileCollection = files(
    fileTree("$rootDir/docs") { include("**/*.md") },
    fileTree(rootDir.toString()) { include("*.md") },
)

val bundledDocsAssetDirectory = layout.projectDirectory.dir("src/main/assets/docs")

val syncBundledDocs by tasks.registering(SyncBundledDocsTask::class) {
    group = "build"
    description = "Copies the bundled documents into the app's assets and regenerates their reader index."
    repositoryRoot.set(rootProject.layout.projectDirectory)
    documents.from(bundledDocsSources)
    assetDirectory.set(bundledDocsAssetDirectory)
}

val verifyBundledDocs by tasks.registering(VerifyBundledDocsTask::class) {
    group = "verification"
    description = "Fails the build if a bundled document drifted from docs/, or stopped rendering in the app."
    repositoryRoot.set(rootProject.layout.projectDirectory)
    documents.from(bundledDocsSources)
    assetDirectory.set(bundledDocsAssetDirectory)
    committedAssets.from(fileTree(bundledDocsAssetDirectory) { include("**/*") })
    stampFile.set(layout.buildDirectory.file("reports/bundled-docs/verified.txt"))
}

verifyBundledDocs { mustRunAfter(syncBundledDocs) }
tasks.named("check") { dependsOn(verifyBundledDocs) }

// Public documentation hygiene guard — moved.
//
// It used to be registered here with a file set of its own: the repository
// root, `NOTICE` and `docs/`. That set left the generated `FILE_MAP.md` files
// outside every rule it enforces, which is where the references into the
// internal tree had accumulated. It now sits with the other documentation
// gates, below `documentationFiles`, and shares that already-declared,
// already-prefix-guarded set.

// Play store-listing length gate. Google rejects an over-length field in the
// Console — after the merge and after the release workflow has signed an
// artefact — so the ceiling has to be enforced where the text is edited.
val verifyStoreListingLengths by tasks.registering {
    group = "verification"
    description = "Fails the build if a Play store-listing field exceeds Google's character limit."
    val rootDirForAction: File = rootDir
    val listingFiles: Set<File> = fileTree("$rootDir/fastlane/metadata") { include("**/*.txt") }.files
    inputs.files(listingFiles)
    doLast {
        val contents = listingFiles.associate { it.relativeTo(rootDirForAction).path to it.readText() }
        val violations = StoreListingLengthChecker.scan(contents)
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Play store listing is over Google's limits:\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }
}

tasks.named("check") { dependsOn(verifyStoreListingLengths) }

// Lint-baseline guard for the demoted version-freshness checks.
//
// Those checks report at informational severity (see the `lint {}` block above),
// which makes the lint report their only signal — and makes the baseline a way to
// delete that signal without failing anything. Lint records informational
// incidents into a regenerated baseline exactly as it records errors (the write
// path filters by issue id, never by severity) and then filters baselined
// incidents out of the reports, so one routine `updateLintBaseline` run for an
// unrelated batch of fixes would quietly empty the drift report and leave `check`
// green. This project has already paid for that once: four such entries had
// accumulated and had to be deleted before the report showed the packages it
// exists to show.
//
// Unlike the checks it protects, this guard is a legitimate gate: its verdict is
// a function of the committed baselines and nothing else.
//
// The pure scanner lives in `buildSrc` (`LintBaselineGuard`) and is unit-tested
// there (`./gradlew -p buildSrc test`). The file set is a SINGLE-level glob on
// purpose: it matches `app/lint-baseline.xml` and `catalog/lint-baseline.xml`
// while never reaching a nested copy of the tree — notably a stale git worktree
// under `.claude/worktrees/` — which would otherwise fail the build with a
// violation that does not exist in this checkout.
val verifyLintBaselineOverrides by tasks.registering {
    group = "verification"
    description =
        "Fails the build if a lint baseline suppresses a check that was demoted to informational severity."
    val rootDirForAction: File = rootDir
    val baselineFiles: Set<File> = fileTree(rootDir) { include("*/lint-baseline.xml") }.files
    inputs.files(baselineFiles)
    doLast {
        val contents = baselineFiles.associate { it.relativeTo(rootDirForAction).path to it.readText() }
        // A guard that scans nothing passes everything, which is the failure mode
        // this task exists to prevent. `:app` always declares a baseline, so an
        // empty match set means the glob stopped finding the modules (a module
        // moved under a nested path, a renamed baseline file) rather than that
        // there is nothing to check.
        if (contents.isEmpty()) {
            throw GradleException(
                "verifyLintBaselineOverrides found no lint baseline to scan. At least " +
                    "`app/lint-baseline.xml` is expected; the single-level `*/lint-baseline.xml` " +
                    "glob has stopped matching the modules it is meant to cover.",
            )
        }
        val violations = LintBaselineGuard.scan(contents)
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Lint baseline suppresses a demoted check (${violations.size} violation(s)):\n" +
                    violations.joinToString(separator = "\n") { it.format() } +
                    "\n\nThese checks are informational so that the lint report keeps showing dependency " +
                    "drift; a baseline entry hides them again. Delete the entries above instead of " +
                    "regenerating the baseline wholesale.",
            )
        }
    }
}
tasks.named("check") { dependsOn(verifyLintBaselineOverrides) }

// `StoreMetadataTest` reads the store listing under `fastlane/metadata/` — the
// text limits, the changelog for the shipping versionCode, and the screenshot
// geometry Play enforces. Those files are not on any compile classpath, so
// without this declaration the test task stays UP-TO-DATE after a metadata edit
// and the guard reports a stale pass: exactly the failure mode it exists to
// prevent, only quieter.
//
// `InstrumentedTestExclusionGuardTest` has the same shape and the same trap. It
// parses the instrumented source set (which is on no unit-test classpath) and
// reads the emulator workflow (which is not a build input at all), so both have
// to be declared or the guard answers from a cached run of the very edit it
// polices — adding an exclusion, or renaming the annotation the workflow names
// as a string. Declaring them costs a unit-test re-run after an `androidTest`
// edit; not declaring them costs the guard its meaning.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("fastlane/metadata"))
        .withPropertyName("storeMetadata")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // `TopBarInsetGuardTest` and `SnackbarHostGuardTest` read the design system's
    // sources as text. A `:catalog` edit usually re-runs this task through the
    // compiled classpath anyway, but an edit that leaves the bytecode as it was (a
    // modifier reordered into the same calls) would not — declared, not assumed.
    inputs.dir(rootProject.file("catalog/src/main/java"))
        .withPropertyName("catalogSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // `AppFunctionServiceManifestGuardTest` reads the probe app's entry point and
    // manifest, which are on no classpath of this module.
    inputs.dir(rootProject.file("tools-probe/src/main"))
        .withPropertyName("toolsProbeSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("tools-probe/build.gradle.kts"))
        .withPropertyName("toolsProbeBuildScript")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(layout.projectDirectory.dir("src/androidTest"))
        .withPropertyName("instrumentedSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file(".github/workflows/instrumented.yml"))
        .withPropertyName("instrumentedWorkflow")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Same trap once more, and it was observed springing: `CookbookRuntimeReachTest`
    // reads the published Markdown and `CookbookRecipeValidationTest` reads the
    // recipe documents, neither of which is on any classpath. Without these two
    // lines a broken recipe passed `check` from a cached run — `verifyCookbookDocs`
    // does not read the recipes at all, so nothing else would have caught it.
    inputs.file(rootProject.file("docs/cookbook.md"))
        .withPropertyName("cookbookDocument")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("docs/recipes"))
        .withPropertyName("cookbookRecipes")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // And twice more, both measured: `AboutLinksTest` reads the privacy policy and
    // `BrowserEditorContextConfigGuardTest` reads the browser editor, both from the
    // repository root. Editing either left this task UP-TO-DATE. What tests read from
    // `src/main` needs no line here — an edit recompiles, or (assets) repackages the
    // resources the Robolectric tests load, and the task re-runs; both were checked the
    // same way.
    inputs.file(rootProject.file("PRIVACY.md"))
        .withPropertyName("privacyPolicy")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("pipeline-editor.html"))
        .withPropertyName("browserEditorHtml")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // `ApprovalPolicyDocumentsTest` pins the approval sentences of the threat model
    // and the user guide to the code; both are read from the repository root.
    inputs.file(rootProject.file("SECURITY.md"))
        .withPropertyName("securityPolicy")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("docs/user-guide.md"))
        .withPropertyName("userGuide")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // `WorkspaceToolsDocumentedTest` reads the tool enumerations of the architecture
    // map and the extension guide (and of SECURITY.md, declared above).
    inputs.file(rootProject.file("docs/architecture.md"))
        .withPropertyName("architectureDocument")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("docs/extending.md"))
        .withPropertyName("extendingGuide")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // `ExportedComponentInventoryTest` reads every source set's manifest. A variant's
    // test task sees only its own overlays, so an export added to `src/full` left
    // `testFossDebugUnitTest` UP-TO-DATE and green — measured, with the guard wrong
    // on disk. The pattern takes in `src/main`'s manifest as well.
    inputs.files(fileTree("src") { include("*/AndroidManifest.xml") })
        .withPropertyName("sourceManifests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // `EntrySurfaceLimitsDocumentsTest` pins the published contract's numbers to the
    // code (SECURITY.md and the user guide are declared above).
    inputs.file(rootProject.file("docs/external-automation.md"))
        .withPropertyName("externalAutomationContract")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Hilt/Dagger reads Kotlin metadata via `kotlin-metadata-jvm`, which is unshaded
// since Dagger 2.57 and therefore resolved through Gradle. Each Kotlin bump raises
// the emitted metadata version, so the processor must use a matching reader or it
// fails with "Provided Metadata instance has version X, while maximum supported
// version is Y". Pin it to the active Kotlin version across every configuration
// (including the Hilt aggregating processor classpath) so a Kotlin bump never
// outruns the metadata reader again.
configurations.configureEach {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
    }
    // Koog's telemetry feature and the OpenTelemetry SDK it brings in: exporters for
    // Langfuse, W&B Weave, Datadog and any OTLP endpoint. The app never installs the
    // feature — it builds no Koog `AIAgent` — so the code only ever sat in the APK
    // unreachable. Excluded in every configuration, so it is absent rather than
    // unused: a later Koog version that calls it from a client the app does use fails
    // at the call instead of sending traces past `ModelNetworkGate` and the privacy
    // indicator. Nothing else on the classpath references it (measured on the
    // release classpath; docs/release.md §5).
    exclude(group = "ai.koog", module = "agents-features-opentelemetry")
    exclude(group = "ai.koog", module = "agents-features-opentelemetry-android")
    exclude(group = "io.opentelemetry")
    exclude(group = "io.opentelemetry.kotlin")
    // The SLF4J provider Koog's Android client module brings in at runtime. It
    // wrote every `INFO`+ line logged through SLF4J to `System.err` — on Android,
    // logcat — past the redaction the app applies to its own logs, and some of
    // Koog's lines are model output: a reasoning trace at `INFO`, a whole response
    // when a provider's reply has no parts. Without a provider, SLF4J 2 falls
    // back to its no-op logger. `verify<Variant>NoSlf4jProvider` keeps any other
    // provider out.
    exclude(group = "org.slf4j", module = "slf4j-simple")
}

dependencies {
    // Design-system module — `KnotworkTheme` (currently a `MaterialTheme`
    // pass-through) plus the ported foundations in Kotlin sources.
    implementation(project(":catalog"))

    // `androidx.core.splashscreen` artefact — backs the platform-side splash
    // (`installSplashScreen(...)`) once the brand mark and accent token land.
    implementation(libs.androidx.core.splashscreen)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // Network
    implementation(libs.okhttp)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.work.testing)
    ksp(libs.hilt.compiler)

    // Logging
    implementation(libs.timber)

    // Process restart for the Settings → restart-required banner.
    implementation(libs.process.phoenix)

    // Local Storage (Room)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore Preferences
    implementation(libs.datastore.preferences)

    // LiteRT LLM Inference
    implementation(libs.litertlm)

    // MediaPipe Tasks Text.
    //
    // It drags `com.google.android.datatransport` in for its own logging, which
    // put three Google components — an alarm receiver, a job service and a
    // backend-discovery holder — into the FOSS manifest. They are removed in
    // `src/foss/AndroidManifest.xml`; the dependency itself stays, because
    // excluding it leaves MediaPipe's own code referencing classes that are no
    // longer there. See `docs/release.md` § FOSS / F-Droid build.
    implementation(libs.mediapipe.tasks.text)

    // MediaPipe brings protobuf-javalite 4.26.1, inside the range of
    // CVE-2024-7254 (unbounded recursion on nested groups, fixed in 4.27.5). The
    // app parses no protobuf of its own — MediaPipe reads the task graph it builds
    // itself — but raising the runtime costs one constraint: protobuf supports
    // gencode of 4.26 on any 4.x or 5.x runtime. Its failure modes are release- and
    // device-only (protobuf instantiates reflectively), which is what the R8 keep
    // rules and `verify<Variant>Instantiable` below exist for.
    constraints {
        implementation(libs.protobuf.javalite) {
            because("CVE-2024-7254: MediaPipe 1.0.0 resolves protobuf-javalite 4.26.1; the fix is 4.27.5")
        }
    }

    // Koog Framework
    implementation(libs.koog.agents)
    implementation(libs.koog.mcp)
    implementation(libs.koog.openai)
    implementation(libs.koog.anthropic)
    implementation(libs.koog.google)
    implementation(libs.koog.deepseek)
    implementation(libs.koog.ollama)
    // Runtime SPI provider — see the `koog-http-client-ktor` entry in
    // `libs.versions.toml` for the rationale. Required for every Koog
    // prompt-executor since 1.0.0; without it the executors throw
    // `No KoogHttpClient.Factory provider found on the runtime classpath`
    // on the first network call.
    implementation(libs.koog.http.client.ktor)

    // SQLCipher for Android (encrypted Room database)
    implementation(libs.sqlcipher.android)

    // AppFunctions
    implementation(libs.androidx.appfunctions)
    ksp(libs.androidx.appfunctions.compiler)

    // Markdown
    implementation(libs.markdown.m3)

    // Image loading (Coil 3) + EXIF orientation for attachment ingest
    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // JSON Serialization
    implementation(libs.gson)

    // Firebase — Crashlytics only. Analytics is deliberately NOT declared:
    // Crashlytics depends on `firebase-measurement-connector`, not on
    // `firebase-analytics`, and the app logs no analytics events. Declaring it
    // used to drag `play-services-measurement` in, which added an advertising-ID
    // permission set to the `full` manifest for a collector nothing ever fed.
    // Scoped to the `full` flavour ONLY (`fullImplementation`) so the `foss`
    // build carries no `com.google.firebase` artifact on any classpath — the
    // hard requirement for F-Droid. The BoM (Bill of Materials) pins
    // inter-library versions; individual modules are intentionally un-versioned.
    // Starting from Firebase BoM 34.0 the `-ktx` artifacts were folded into the
    // base modules and removed.
    "fullImplementation"(platform(libs.firebase.bom))
    "fullImplementation"(libs.firebase.crashlytics)

    testImplementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.work.testing)
    // OkHttp 5 MockWebServer (mockwebserver3 namespace) for HttpRequestExecutor tests.
    testImplementation(libs.okhttp.mockwebserver3)
    // Robolectric is needed for the foreground service,
    // notification builder, and Doze (`ShadowPowerManager`) paths under
    // `data.services`. The version is pinned in `gradle/libs.versions.toml`.
    testImplementation(libs.robolectric)
    // Konsist (Apache-2.0) backs the architecture guard suite under
    // `app/src/test/.../architecture/`: JVM JUnit tests that fail `check`
    // when Clean Architecture layer boundaries regress (e.g. a `domain`
    // class importing `data`/`presentation` or the Android SDK).
    testImplementation(libs.konsist)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.room.testing)
}

// Install the :tools-probe debug APK alongside the agent's test
// APK so `AppFunctionsEndToEndTest` can discover the probe's `echo` AppFunction
// through the system AppFunctionManager. `androidTestUtil(project(":tools-probe"))`
// would be the ergonomic choice, but AGP 9 publishes the probe as a multi-variant
// application module without the disambiguating attributes Gradle needs to pick a
// concrete APK from a configuration-less consumer — sync fails with "Cannot choose
// between debugRuntimeElements / releaseRuntimeElements".
//
// Hooking on both `installDebugAndroidTest` and `connectedDebugAndroidTest`:
//   - `installDebugAndroidTest` covers Android Studio's "Run test" workflow which
//     ultimately goes through that task before invoking the instrumentation.
//   - `connectedDebugAndroidTest` covers CLI runs (`./gradlew :app:connectedDebugAndroidTest`)
//     where AGP installs the SUT/test APKs *inside* the connected-test task and does
//     **not** depend on `installDebugAndroidTest` at all — verified via
//     `./gradlew :app:connectedDebugAndroidTest --dry-run`. Without this branch the
//     CLI run never installs the probe and the e2e test times out with an empty
//     discovery list.
// The `distribution` flavour dimension adds the flavour segment to the
// androidTest task names. Both flavours share all `main` sources, so the e2e
// instrumented suite is meaningful on either; hook the probe install onto the
// install/connected androidTest tasks of BOTH flavours so
// `connectedFossDebugAndroidTest` (used when validating the F-Droid build) also
// installs the probe instead of timing out on an empty discovery list.
val toolsProbeInstall = ":tools-probe:installDebug"
val probeInstallHostTasks = setOf(
    "installFullDebugAndroidTest",
    "connectedFullDebugAndroidTest",
    "installFossDebugAndroidTest",
    "connectedFossDebugAndroidTest",
)
tasks.matching { it.name in probeInstallHostTasks }
    .configureEach { dependsOn(toolsProbeInstall) }

// ─── R8 keep-rule guard (release builds) ─────────────────────────────────────
// Some keep rules protect code whose failure mode is invisible to every test the
// JVM gate can run: `com.google.common.flogger` (pulled in by MediaPipe's
// `tasks-text`) resolves a log site by walking the call stack for a frame of its
// own, so when R8 renames or inlines those frames the first
// `TextEmbedder.createFromOptions` call dies with
// `IllegalStateException: no caller found on the stack for: …` — the on-device
// embedding path, i.e. any message that touches long-term memory. Debug builds
// are not minified, so unit and instrumented tests cannot see the regression.
//
// The mapping R8 emits is the one durable artefact that can: a live keep rule
// leaves the package identity-mapped. This task asserts exactly that after every
// release packaging task — APK and AAB alike — so a dropped rule fails the build
// rather than the user's first message.
val r8ProtectedPackages: List<String> = listOf("com.google.common.flogger.")

// Classes the app never constructs with `new`: protobuf-javalite materialises
// them through `Unsafe.allocateInstance` from `getDefaultInstance()`, which R8
// cannot see. MediaPipe parses its task graph as a protobuf on every
// `TextEmbedder.createFromOptions`, so an abstractified message class breaks
// the on-device embedding path — and therefore all of long-term memory.
//
// The AppFunctions service is KSP output of this app that the platform binds BY
// NAME, from the manifest, to dispatch every `@AppFunction` call. The manifest
// reference keeps it today; a renamed or abstractified class would leave the
// function listed and every call to it failing to bind. Being in the dex under its
// own name, concrete, is exactly what this check asserts. (Until the entry-point
// model, these were the aggregated invoker and inventory the library loaded by name;
// the generated service now reaches its inventory by an ordinary call.)
val r8RequiredInstantiableClasses: List<String> = listOf(
    "com.google.protobuf.Any",
    "com.google.protobuf.UnknownFieldSetLite",
    "app.knotwork.android.data.tools.local.appfunctions.KnotworkAppFunctionService",
)
androidComponents {
    onVariants { variant ->
        if (variant.buildType != "release") return@onVariants
        val mappingFile = variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
        val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
        val verifyKeepRules = tasks.register("verify${variantName}KeepRules") {
            group = "verification"
            description = "Fails the release build if an R8 keep rule stopped pinning a protected package."
            inputs.files(mappingFile).optional().withPropertyName("r8Mapping")
            val protectedPackages = r8ProtectedPackages
            val checkedVariant = variant.name
            doLast {
                val mapping = mappingFile.orNull?.asFile
                // A release variant with no mapping is itself the regression:
                // either minification was switched off for a shipping build, or
                // this guard lost its grip on the artefact. Skipping silently
                // here would be the same vacuous pass the checker refuses.
                if (mapping == null || !mapping.exists()) {
                    throw GradleException(
                        "R8 keep-rule check cannot run for `$checkedVariant`: no obfuscation mapping was produced. " +
                            "Either minification is disabled for a release build, or the mapping artefact moved.",
                    )
                }
                val contents = mapping.readText()
                val violations = protectedPackages.flatMap { prefix ->
                    R8MappingChecker.verifyIdentityMapping(contents, prefix)
                }
                if (violations.isNotEmpty()) {
                    throw GradleException(
                        "R8 keep-rule check failed (${violations.size} violation(s)):\n" +
                            violations.joinToString(separator = "\n") { it.format() },
                    )
                }
            }
        }
        // Second guard, checking a property the first one cannot see. The
        // mapping proves a class kept its NAME; it says nothing about whether
        // the class can still be instantiated. R8 in full mode left
        // `com.google.protobuf.Any` identity-mapped and made it abstract, and
        // since protobuf-javalite instantiates through `Unsafe.allocateInstance`
        // — invisible to R8 — every `TextEmbedder.createFromOptions` threw, so
        // long-term memory failed on every release build while the mapping check
        // stayed green. This one reads the packaged dex.
        val apkDir = variant.artifacts.get(SingleArtifact.APK)
        val verifyInstantiable = tasks.register("verify${variantName}Instantiable") {
            group = "verification"
            description = "Fails the release build if a reflectively-instantiated class was made abstract or removed."
            inputs.files(apkDir).withPropertyName("packagedApk")
            val requiredClasses = r8RequiredInstantiableClasses
            val checkedVariant = variant.name
            val loader = variant.artifacts.getBuiltArtifactsLoader()
            doLast {
                val apk = loader.load(apkDir.get())
                    ?.elements
                    ?.map { File(it.outputFile) }
                    ?.firstOrNull { it.exists() }
                    ?: throw GradleException(
                        "Instantiability check cannot run for `$checkedVariant`: no packaged APK was found.",
                    )
                val dexFiles = ZipFile(apk).use { zip ->
                    zip.entries().asSequence()
                        .filter { it.name.endsWith(".dex") }
                        .map { zip.getInputStream(it).readBytes() }
                        .toList()
                }
                if (dexFiles.isEmpty()) {
                    throw GradleException("Instantiability check found no dex in ${apk.name}.")
                }
                val violations = DexInstantiabilityChecker.verify(dexFiles, requiredClasses)
                if (violations.isNotEmpty()) {
                    throw GradleException(
                        "Reflectively-instantiated classes are unusable in `$checkedVariant` " +
                            "(${violations.size} violation(s)):\n" +
                            violations.joinToString(separator = "\n") { it.format() } +
                            "\n\nAdd or restore the keep rule in `app/proguard-rules.pro`.",
                    )
                }
            }
        }

        // MediaPipe's usage logger must never send. `proguard-rules.pro` removes
        // its one call site with `-assumenosideeffects`; the declaration stays
        // (MediaPipe is kept whole), so the mapping cannot show whether the call
        // is gone — only the instructions can. Read with the SDK's `dexdump`, a
        // line at a time: the disassembly runs to millions of lines.
        val dexdumpSdk = androidComponents.sdkComponents.sdkDirectory
        val buildTools = android.buildToolsVersion
        val verifyNoMediaPipeTelemetry = tasks.register("verify${variantName}NoMediaPipeTelemetry") {
            group = "verification"
            description = "Fails the release build if MediaPipe's usage logger can still send an event."
            inputs.files(apkDir).withPropertyName("packagedApk")
            val checkedVariant = variant.name
            val loader = variant.artifacts.getBuiltArtifactsLoader()
            doLast {
                val apk = loader.load(apkDir.get())
                    ?.elements
                    ?.map { File(it.outputFile) }
                    ?.firstOrNull { it.exists() }
                    ?: throw GradleException(
                        "Telemetry check cannot run for `$checkedVariant`: no packaged APK was found.",
                    )
                val dexdump = File(dexdumpSdk.get().asFile, "build-tools/$buildTools/dexdump")
                if (!dexdump.canExecute()) {
                    throw GradleException("Telemetry check needs `dexdump`, not found at ${dexdump.path}.")
                }
                val logging = "Lcom/google/mediapipe/tasks/core/logging"
                val callSiteClass = "$logging/TasksStatsProtoLogger"
                var callSiteSeen = false
                val calls = mutableListOf<String>()
                ZipFile(apk).use { zip ->
                    zip.entries().asSequence().filter { it.name.endsWith(".dex") }.forEach { entry ->
                        val dex = File(temporaryDir, entry.name)
                        zip.getInputStream(entry).use { input -> dex.outputStream().use { input.copyTo(it) } }
                        val process = ProcessBuilder(dexdump.path, "-d", dex.path).redirectErrorStream(true).start()
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                if (DexInvocationChecker.isClassDefinition(line, callSiteClass)) callSiteSeen = true
                                if (DexInvocationChecker.isInvocation(line, "$logging/LoggingClient", "logEvent") ||
                                    DexInvocationChecker.isInvocation(line, "$logging/RemoteLoggingClient", "logEvent")
                                ) {
                                    calls += line.trim()
                                }
                            }
                        }
                        if (process.waitFor() != 0) throw GradleException("dexdump failed on ${entry.name}.")
                    }
                }
                if (!callSiteSeen) {
                    throw GradleException(
                        "Telemetry check read nothing for `$checkedVariant`: `$callSiteClass` is not in the dex. " +
                            "MediaPipe moved its logger, or the check lost its grip on the artefact.",
                    )
                }
                if (calls.isNotEmpty()) {
                    throw GradleException(
                        "MediaPipe's usage logger can still send in `$checkedVariant` (${calls.size} call site(s)):\n" +
                            calls.joinToString("\n") { "  $it" } +
                            "\n\nThe `-assumenosideeffects` rules for `LoggingClient.logEvent` in " +
                            "`app/proguard-rules.pro` no longer match.",
                    )
                }
            }
        }

        // Third guard, and the only one that runs BEFORE R8: every name in
        // `proguard-rules.pro` must exist on the classpath R8 is about to read.
        // R8 matches a misspelt or moved name with nothing and says nothing —
        // four AppFunctions rules and a LiteRT one protected nothing for as long
        // as they existed. The classes are the variant's own view of what R8
        // consumes (every scope) plus the SDK boot classpath.
        val verifyKeepRuleTargets = tasks.register<VerifyKeepRuleTargetsTask>("verify${variantName}KeepRuleTargets") {
            group = "verification"
            description = "Fails the release build if a keep rule names a class that is not on the classpath."
            rulesFile.set(layout.projectDirectory.file("proguard-rules.pro"))
            bootClasspath.from(androidComponents.sdkComponents.bootClasspath)
            checkedVariant.set(variant.name)
            stampFile.set(layout.buildDirectory.file("reports/keep-rule-targets/${variant.name}.txt"))
        }
        variant.artifacts.forScope(ScopedArtifacts.Scope.ALL)
            .use(verifyKeepRuleTargets)
            .toGet(
                ScopedArtifact.CLASSES,
                VerifyKeepRuleTargetsTask::classJars,
                VerifyKeepRuleTargetsTask::classDirectories,
            )
        tasks.matching { it.name == "minify${variantName}WithR8" }.configureEach { dependsOn(verifyKeepRuleTargets) }

        // Stripping with the pinned NDK, or not at all. AGP never downloads an
        // NDK to strip with: without the pinned one it prints "Unable to strip
        // the following libraries, packaging them as they are" and succeeds, and
        // the release then differs from CI's in its native libraries (measured:
        // 3 of 5). So the release build refuses to start stripping instead.
        val pinnedNdkVersion = libs.versions.ndk.get()
        val sdkDirectory = androidComponents.sdkComponents.sdkDirectory
        val verifyPinnedNdk = tasks.register("verify${variantName}PinnedNdk") {
            group = "verification"
            description = "Fails the release build if the pinned NDK that strips native libraries is not installed."
            val checkedVariant = variant.name
            doLast {
                PinnedNdk.problem(sdkDirectory.get().asFile, pinnedNdkVersion)?.let { problem ->
                    throw GradleException(
                        "`$checkedVariant` strips its native libraries with NDK $pinnedNdkVersion " +
                            "(`ndk` in gradle/libs.versions.toml). $problem\n" +
                            "Install it with: sdkmanager \"ndk;$pinnedNdkVersion\" " +
                            "(Android Studio: SDK Manager → SDK Tools → Show Package Details → NDK (Side by side)).",
                    )
                }
            }
        }
        tasks.matching { it.name == "strip${variantName}DebugSymbols" }.configureEach { dependsOn(verifyPinnedNdk) }

        // Both packaging paths, not just the APK: the distribution artefact for
        // Play is the AAB, and a guard that only watches `assemble` would wave
        // through exactly the build that ships.
        //
        // `tasks.matching`, not `tasks.named`: AGP registers these tasks after
        // this `onVariants` callback runs, so eager lookup fails here.
        val packagingTasks = setOf("assemble$variantName", "bundle$variantName")
        tasks.matching { it.name in packagingTasks }.configureEach { finalizedBy(verifyKeepRules) }
        // The dex guard needs a packaged APK, so it rides `assemble` only;
        // the AAB carries the same dex from the same R8 run.
        tasks.matching { it.name == "assemble$variantName" }.configureEach { finalizedBy(verifyInstantiable) }
        tasks.matching { it.name == "assemble$variantName" }.configureEach { finalizedBy(verifyNoMediaPipeTelemetry) }
    }
}

// ─── Merged-manifest guard ───────────────────────────────────────────────────
// The source manifests are not what ships: the merger folds in every library's
// own manifest, so a dependency bump can add a permission, an exported component
// or a `queries` entry without a line of this repository changing — and the
// privacy policy's permission table and the threat model's list of entry surfaces
// would both be wrong. Each shipping variant's merged manifest is compared with a
// hand-edited expectation in `config/merged-manifest/`. Release variants only,
// because those are what ship; the merge costs seconds and needs no signing or R8.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
        val verifyMergedManifest = tasks.register<VerifyMergedManifestTask>("verify${variantName}MergedManifest") {
            group = "verification"
            description = "Fails the build if the merged `${variant.name}` manifest disagrees with its expectation."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            expectation.set(rootProject.layout.projectDirectory.file("config/merged-manifest/${variant.name}.txt"))
            checkedVariant.set(variant.name)
            repositoryRoot.set(rootProject.layout.projectDirectory)
            stampFile.set(layout.buildDirectory.file("reports/merged-manifest/${variant.name}.txt"))
        }
        tasks.named("check") { dependsOn(verifyMergedManifest) }
    }
}

// ─── No SLF4J provider in a shipping build ──────────────────────────────────
// Koog logs through SLF4J, and one of its modules brought `slf4j-simple` in at
// runtime: every `INFO`+ line logged through SLF4J — among Koog's, a model's
// reasoning trace and a whole response on one provider's warning — went to
// logcat, past the redaction the app applies to its own logs. The module is
// excluded in `configurations.configureEach`; this guard keeps every other
// provider out too. It reads the Java resources of the variant's runtime
// classpath before R8 (the variant API's `ScopedArtifact.JAVA_RES` hands a task
// nothing for the ALL scope, measured on AGP 9.3.1), because R8 turns a
// resolvable `ServiceLoader` lookup into a constructor call and drops the service
// file from the APK — the packaged artefact shows no registration even while it
// ships the provider. In `check`, so a change adding a provider fails there, and ahead of
// R8, so a release build cannot skip it.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
        val verifyNoSlf4jProvider = tasks.register<VerifyNoSlf4jProviderTask>("verify${variantName}NoSlf4jProvider") {
            group = "verification"
            description = "Fails the build if the `${variant.name}` resources register an SLF4J logging provider."
            resources.from(
                variant.runtimeConfiguration.incoming.artifactView {
                    attributes { attribute(Attribute.of("artifactType", String::class.java), "android-java-res") }
                }.files,
            )
            checkedVariant.set(variant.name)
            stampFile.set(layout.buildDirectory.file("reports/slf4j-provider/${variant.name}.txt"))
        }
        tasks.named("check") { dependsOn(verifyNoSlf4jProvider) }
        tasks.matching { it.name == "minify${variantName}WithR8" }.configureEach { dependsOn(verifyNoSlf4jProvider) }
    }
}

// ─── Release tag ↔ versionName agreement ─────────────────────────────────────
// A release is cut by pushing a `v<x>.<y>.<z>` tag, and the release workflow
// names every artefact after that tag — but the version compiled into the APK
// comes from `versionName` above. Nothing else forces the two to agree, and the
// mismatch is invisible until someone reads the About screen of a file called
// `knotwork-0.7.0-full-release.apk` and sees `0.6.0`.
//
// `versionName` stays the source of truth (the F-Droid recipe builds a tag with
// no Gradle properties injected and must still get the right number), so this
// task asserts the agreement rather than overwriting anything. It is invoked
// explicitly by `.github/workflows/release.yml` before the release build, and is
// deliberately NOT wired into `check`: the tag only exists at release time.
tasks.register("verifyReleaseVersion") {
    group = "verification"
    description = "Fails when `-PreleaseTag=<tag>` disagrees with the versionName declared in this build."
    val releaseTag = providers.gradleProperty("releaseTag")
    val declaredVersionName = android.defaultConfig.versionName.orEmpty()
    doLast {
        val tag = releaseTag.orNull
            ?: throw GradleException(
                "`verifyReleaseVersion` needs the release tag: " +
                    "run it as `./gradlew :app:verifyReleaseVersion -PreleaseTag=v<major>.<minor>.<patch>`.",
            )
        ReleaseVersionChecker.verify(tag = tag, declaredVersionName = declaredVersionName)
            ?.let { throw GradleException(it) }
        logger.lifecycle("Release tag `$tag` matches the declared versionName `$declaredVersionName`.")
    }
}

// ─── Documentation gates: links, diagrams, and the version number ────────────
//
// Three checks over the same Markdown set, added together because they answer
// the same class of question — "does the documentation still describe this
// repository?" — and share one file set and one link reader.
//
// The set is assembled from declared roots rather than from `git ls-files`. A
// checker that picks its inputs out of the index cannot see the files the branch
// under review is *adding*, so it validates everything except the change being
// reviewed and reports a clean pass; that is not a hypothetical, it happened
// while this repository's documentation was being written. The `CLAUDE.md`
// family is excluded because it is untracked: including it would make the gate
// read a different corpus locally than in CI.
//
// `requiredPrefixes` closes the mirror-image hole — a glob that stops matching
// makes a scan pass by covering nothing. Each root must contribute at least one
// document, which is self-maintaining in a way a pinned file count is not.
val documentationRoots = listOf("", "docs/", ".github/", "app/", "catalog/", "gradle/")

val documentationFiles: FileCollection = files(
    fileTree(rootDir) {
        include("*.md")
        exclude("CLAUDE.md", "CLAUDE.local.md")
    },
    fileTree("$rootDir/docs") { include("**/*.md") },
    fileTree("$rootDir/.github") { include("**/*.md") },
    fileTree("$rootDir/app") {
        include("**/*.md")
        exclude("build/**")
        // The bundled documentation under `src/main/assets/docs` is a generated
        // COPY of `docs/`, and this gate would read it wrongly: it resolves a
        // relative link against the file's own directory, where `user-guide.md`
        // and `../SECURITY.md` do not exist — the copies are deliberately a
        // subset. Their links are checked by `verifyBundledDocs` instead, which
        // resolves them against the real repository and additionally decides
        // which of them stay inside the app. Checking them here as well would
        // not be stricter, it would be wrong.
        exclude("src/main/assets/docs/**")
    },
    fileTree("$rootDir/catalog") {
        include("**/*.md")
        exclude("build/**")
    },
    fileTree("$rootDir/gradle") { include("**/*.md") },
)

// Internal links — a blocking gate. A relative path or an `#anchor` is a claim
// about this repository, so its verdict is a function of the commit under review
// and a dead one is a defect the build can refuse. So is an inline-code span
// written as a repository path (`domain/…/Foo.kt`).
val verifyDocLinks by tasks.registering(VerifyDocLinksTask::class) {
    group = "verification"
    description = "Fails the build if a relative link, an #anchor or an inline-code repository path in the " +
        "documentation leads nowhere."
    repositoryRoot.set(rootProject.layout.projectDirectory)
    documents.from(documentationFiles)
    requiredPrefixes.set(documentationRoots)
    // History names the files of the tree each entry was written against.
    codePathExemptions.set(listOf("CHANGELOG.md"))
}
tasks.named("check") { dependsOn(verifyDocLinks) }

// External links — a report, never a gate. Their verdict depends on somebody
// else's server and can flip without a commit, so by the same reasoning that
// demoted the dependency version-freshness checks to informational severity,
// this has no business among the conditions for merging. It is run by
// `.github/workflows/docs-links.yml` on a schedule, and on demand locally.
val reportExternalDocLinks by tasks.registering(ReportExternalDocLinksTask::class) {
    group = "verification"
    description = "Probes every external http link in the documentation and writes a report. Never fails the build."
    repositoryRoot.set(rootProject.layout.projectDirectory)
    documents.from(documentationFiles)
    requiredPrefixes.set(documentationRoots)
    timeoutSeconds.set(20)
    reportFile.set(layout.buildDirectory.file("reports/docs-links/external-links.md"))
}

// Mermaid diagrams — a blocking gate, and a structural one. A full parse would
// need Mermaid's own grammar, which means a Node toolchain and a network install
// on the critical path of every build. The rules that are checked instead were
// each written against the real parser: confirmed to reject the defect they
// describe, and confirmed not to reject anything Mermaid accepts. See
// `docs/static-analysis.md` for what that does and does not buy.
val verifyMermaidDiagrams by tasks.registering(VerifyMermaidDiagramsTask::class) {
    group = "verification"
    description = "Fails the build if an embedded Mermaid diagram is structurally broken."
    repositoryRoot.set(rootProject.layout.projectDirectory)
    documents.from(documentationFiles)
    requiredPrefixes.set(documentationRoots)
    stampFile.set(layout.buildDirectory.file("reports/docs-links/mermaid-verified.txt"))
}
tasks.named("check") { dependsOn(verifyMermaidDiagrams) }

// Documentation hygiene — a blocking gate over the same shared set. Two classes
// of defect: a leaked LLM tool-call artifact, and a reference into the internal
// `project_docs/` tree that a public reader cannot open. `CHANGELOG.md` is
// excluded here and only here: its past entries name internal documents as they
// were called at the time, and rewriting history to satisfy a gate would be the
// wrong repair.
val verifyDocsHygiene by tasks.registering(VerifyDocsHygieneTask::class) {
    group = "verification"
    description = "Fails the build if public docs carry LLM tool-call artifacts or internal-document references."
    repositoryRoot.set(rootProject.layout.projectDirectory)
    documents.from(
        files(documentationFiles).asFileTree.matching { exclude("CHANGELOG.md") },
        rootProject.layout.projectDirectory.file("NOTICE"),
    )
    // The generated maps are pinned by their exact paths, not just by the `app/`
    // and `catalog/` roots those roots would be satisfied by any other Markdown
    // under them. These three are the reason the set was widened, and a glob
    // that quietly stops matching them would return the gate to the state this
    // change fixed — passing while covering nothing that mattered.
    requiredPrefixes.set(
        documentationRoots + listOf(
            "app/src/main/java/app/knotwork/android/FILE_MAP.md",
            "app/src/test/java/app/knotwork/android/FILE_MAP.md",
            "catalog/FILE_MAP.md",
        ),
    )
    stampFile.set(layout.buildDirectory.file("reports/docs-links/hygiene-verified.txt"))
}
tasks.named("check") { dependsOn(verifyDocsHygiene) }

// Ordering between every task that REWRITES a committed file and every task that
// READS one.
//
// Gradle's implicit-dependency validation is an error, not a warning: name a
// generator and a consumer of its output in one invocation without an ordering
// and the build fails before either runs. That is precisely the shape the
// contribution workflow prescribes — regenerate, then `check` — so the pairing is
// not exotic, it is the documented way to use these tasks.
//
// This used to be declared pair by pair, as each failure was met. That approach
// kept losing: the sibling rule `verifySettingsHelpDocs.mustRunAfter(
// generateSettingsHelpDocs)` was added with a comment citing
// `./gradlew :app:generateSettingsHelpDocs check` as its reason, and that command
// still failed afterwards — on `verifyBundledDocs`, a different consumer of the
// same file. `verifyDocsHygiene` later joined the shared documentation file set
// and was never added to the ordering rule that stood right beside it. Measured
// on `1f0515cb`: FIVE of the six generators could not be combined with `check` at
// all, over 24 enumerated task pairs plus, for the file maps, every compile and
// analysis task of every variant. The one that could was
// `generateDocumentationLinks` — already fixed the other way, by refusing to
// declare its committed file as an output at all.
//
// So the ordering is declared as the matrix it is. `mustRunAfter` states order
// without dependency: neither side drags the other into a build that did not ask
// for it.
//
// What the combined invocation in `check.yml` catches, stated exactly rather than
// generously: a CONSUMER added without ordering, and a generator named in that
// command but missing from this matrix. What it does NOT catch is a generator
// added to neither — no mechanism here does, because the list of tasks that write
// committed files is a fact about intent, not one Gradle can be asked for. Adding
// a generator means adding it in both places, and the comment on that CI step says
// so too.
//
// `generateFileMap` stays listed even though it no longer declares its maps as
// outputs: untracking removed the validation error, not the reason a verifier must
// not read a map that is about to be rewritten.
val committedFileGenerators = listOf(
    generateFileMap,
    generateBrowserEditorConstants,
    generateSettingsHelpDocs,
    generateExternalAutomationDocs,
    generateCookbookDocs,
    generateDocumentationLinks,
)

val committedFileConsumers = listOf(
    // Documentation gates over the whole Markdown set, `FILE_MAP.md` included.
    verifyDocLinks,
    reportExternalDocLinks,
    verifyMermaidDiagrams,
    verifyDocsHygiene,
    verifyForbiddenVocabulary,
    // The bundled copy inside the APK, and its drift gate. Both read `docs/`,
    // which is where three of the generators write.
    syncBundledDocs,
    verifyBundledDocs,
    // The link registry resolves anchors against the same documents.
    generateDocumentationLinks,
    verifyDocumentationLinks,
)

committedFileConsumers.forEach { consumer ->
    // A task reading what it also writes needs no ordering against itself, and
    // Gradle rejects a self-referencing rule.
    consumer { mustRunAfter(committedFileGenerators.filter { it.name != name }) }
}

// Unit tests read committed files that generators write: `docs/cookbook.md` and
// `docs/recipes/` (the cookbook gates) and `pipeline-editor.html` (the browser
// editor guard) are declared Test inputs for a reason of their own — see the
// `tasks.withType<Test>` block above — and that declaration is what puts them in
// this matrix too.
tasks.withType<Test>().configureEach { mustRunAfter(committedFileGenerators) }

// Robolectric 4.17 sets a `FileDescriptor`'s raw descriptor through
// `jdk.internal.access.SharedSecrets` while it sets up each test's application.
// `java.base` does not export that package, so every Robolectric test failed with
// "Failed to interact with raw FileDescriptor internals" until it is exported to the
// test JVM (robolectric/robolectric#11434: the maintainers' answer is to open the
// modules Robolectric uses, not a library change). Only this one package — the
// upstream build opens a dozen more, for its own javac-based tests.
tasks.withType<Test>().configureEach {
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}

// The version number, in every place a human wrote it down. `versionName` below
// is the single source of truth for the build; the README badge, the topmost
// changelog heading and the two compare links at the foot of the changelog are
// copies maintained by hand at release time, and nothing noticed when one was
// missed. The release checklist did not even mention the badge — which is the
// number a bug reporter quotes.
val verifyVersionSources by tasks.registering(VerifyVersionSourcesTask::class) {
    group = "verification"
    description = "Fails the build if any hand-written copy of the version disagrees with the declared versionName."
    declaredVersionName.set(android.defaultConfig.versionName.orEmpty())
    readmeFile.set(file("$rootDir/README.md"))
    changelogFile.set(file("$rootDir/CHANGELOG.md"))
    securityFile.set(file("$rootDir/SECURITY.md"))
    roadmapFile.set(file("$rootDir/docs/roadmap.md"))
    stampFile.set(layout.buildDirectory.file("reports/docs-links/version-sources-verified.txt"))
}
tasks.named("check") { dependsOn(verifyVersionSources) }

// What the build executes before any code of this repository runs: the GitHub
// Actions every workflow calls, and the Gradle distribution the wrapper fetches.
// Both were referenced by something that can move — a tag, a URL with no checksum
// — in the same job that holds the release signing key. The guard fails when a pin
// disappears (a step copied from a README, a `wrapper` run without a checksum); see
// docs/static-analysis.md § Supply-chain pin guard.
// The keys dependency verification may trust across a namespace (`regex="true"`):
// an organisation's own release keys, for that organisation's groups. Any other key
// is trusted for exactly the groups it signs. Gradle's metadata generator does not
// draw this line — it folded two Google engineers' personal keys into all of
// `com.google.*` — so a key added here is a decision, and its owner is written down.
val dependencyVerificationNamespaceKeys: Map<String, String> = mapOf(
    "0E225917414670F4442C250DFD533C07C264648F" to "Google Maven signing key (Linux Packages Signing Authority)",
    "0F06FF86BEEAF4E71866EE5232EE5355A6BC6E42" to "Google Maven signing key (Linux Packages Signing Authority)",
    "20723A6399BC060154283B37CFAE163B64AC9189" to "JetBrains Compose Team <compose@jetbrains.com>",
    "33FD4BFD33554634053D73C0C2148900BCD3C2AF" to "JetBrains <download@jetbrains.com>",
    "6F538074CCEBF35F28AF9B066A0975F8B1127B83" to "Kotlin Release <kt-a@jetbrains.com>",
    "E7DC75FC24FB3C8DFE8086AD3D5839A2262CBBFB" to "Kotlin Libraries Release <kt-libraries@jetbrains.com>",
)

// The only artifacts dependency verification may skip (`<trusted-artifacts>`): files
// an IDE downloads so a reader can navigate, and the build never executes. Keyed by
// the `<trust>` entry's attributes, sorted by name and joined as `name=value`.
val dependencyVerificationTrustedArtifacts: Map<String, String> = mapOf(
    "file=.*-javadoc[.]jar regex=true" to "API docs the IDE attaches; never on a classpath",
    "file=.*-sources[.]jar regex=true" to "Sources the IDE attaches; never on a classpath",
    "file=gradle-.*-src[.]zip group=gradle name=gradle regex=true" to
        "Gradle's source distribution, fetched by Android Studio's sync for build-script navigation",
)

val verifySupplyChainPins by tasks.registering(VerifySupplyChainPinsTask::class) {
    group = "verification"
    description = "Fails the build if an Action or the Gradle distribution is unpinned, or dependency trust widened."
    repositoryRoot.set(rootProject.layout.projectDirectory)
    workflowFiles.from(fileTree("$rootDir/.github") { include("**/*.yml", "**/*.yaml") })
    wrapperProperties.set(file("$rootDir/gradle/wrapper/gradle-wrapper.properties"))
    verificationMetadata.from("$rootDir/gradle/verification-metadata.xml")
    namespaceKeys.set(dependencyVerificationNamespaceKeys)
    allowedTrust.set(dependencyVerificationTrustedArtifacts)
    stampFile.set(layout.buildDirectory.file("reports/supply-chain/pins-verified.txt"))
}
tasks.named("check") { dependsOn(verifySupplyChainPins) }
