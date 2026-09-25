import app.knotwork.android.buildtools.LintBaselineGuard
import app.knotwork.android.buildtools.VerifyNoOrphanedKdocTask
import dev.detekt.gradle.Detekt

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "app.knotwork.design"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            consumerProguardFiles("consumer-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Roborazzi requires Robolectric to load real Android resources during
    // unit tests so Compose can render against actual font / drawable
    // pipelines instead of stubs.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += "META-INF/*"
        }
    }

    lint {
        // Mirror :app's strict-mode posture so design-system
        // code is held to the same gate as the application surface. The baseline is
        // generated lazily — `./gradlew :catalog:updateLintBaseline` writes the file
        // on first run; until then the module has no known issues to grandfather.
        // That command is not free of consequence any more: a regenerated baseline
        // re-absorbs the informational checks below and would delete the
        // dependency-drift report. `verifyLintBaselineOverrides` (registered in
        // `app/build.gradle.kts`, wired into the root `check`) scans this baseline
        // too and fails the build if that happens.
        baseline = file("lint-baseline.xml")
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = true
        htmlReport = true
        xmlReport = true
        // Mirrors `:app` for the version-freshness and deadline checks: they stay
        // enabled but report at INFORMATIONAL severity instead of failing the
        // build, because their verdict depends on an external version index (or
        // on the calendar) rather than on the contents of this repository — see
        // the long rationale in `app/build.gradle.kts` and `decisions.md` §35.
        // This module is not optional to cover: it mirrors `:app`'s strict mode,
        // its own baseline is empty, and the root `check` runs its lint too, so
        // leaving it out would keep the mandatory gate non-deterministic.
        informational += LintBaselineGuard.DEMOTED_ISSUE_IDS
    }
}

detekt {
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    // Mirror :app — any `severity: error` finding fails the build.
    ignoreFailures = false
    basePath.set(rootDir)
    source.setFrom("src/main/java", "src/test/java")
}

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
    // Mirror :app — any ktlint violation that survives `ktlintFormat` fails the build.
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

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // Markdown renderer for catalog-side themed `Markdown(...)` factories
    // (`MarkdownTheme.kt`). `api` so consumers using the themed
    // typography / color factories don't have to redeclare the dep.
    api(libs.markdown.m3)

    // Image loading (Coil 3) for the attachment thumbnail / viewer components.
    // `api` so the app reuses the same `AsyncImage` / preview-handler surface.
    api(libs.coil.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}

// Orphaned KDoc gate — the catalog half. See the fuller note in
// `app/build.gradle.kts`; the short version is that Kotlin attaches a doc block
// to the declaration that follows it and to no other, so two blocks back to back
// mean the first documents nothing.
//
// Registered here as well as in `:app` deliberately. Every instance found when
// the gate was written happened to be in `:app`, but this module is where the
// accident is *most* likely: it is a design system, its declarations carry the
// longest KDoc in the repository, and components are routinely inserted next to
// their neighbours. A gate that covered only the module the first bugs came
// from would be a gate built for the past.
val verifyNoOrphanedKdoc by tasks.registering(VerifyNoOrphanedKdocTask::class) {
    group = "verification"
    description = "Fails the build if a KDoc block documents no declaration."
    repositoryRoot.set(rootProject.layout.projectDirectory)
    sources.from(
        listOf("src/main/java", "src/main/kotlin", "src/test/java", "src/test/kotlin").map { root ->
            fileTree("$projectDir/$root") { include("**/*.kt") }
        },
    )
    stampFile.set(layout.buildDirectory.file("reports/kdoc/no-orphans.txt"))
}
tasks.named("check") { dependsOn(verifyNoOrphanedKdoc) }

// Screenshot baselines are a gate, not a gallery. `check` always rendered every
// snapshot (the unit tests run), but compared none of them with its baseline, so a
// changed screen passed unless someone remembered `verifyRoborazziDebug` by hand.
// Measured before wiring it: 707 tests, 503 baselines, 0 differences, ~1 min on
// macOS; on CI's Linux one half-alpha screen differed by anti-aliasing alone, which
// the shared colour tolerance in `KnotworkRoborazziOptions.kt` absorbs.
//
// The baselines are declared as test inputs because nothing else makes them one:
// they live under `src/test/snapshots`, on no classpath. Measured the hard way —
// replacing a baseline with a different image left `testDebugUnitTest` UP-TO-DATE
// and the verification green.
tasks.withType<Test>().configureEach {
    inputs.dir(layout.projectDirectory.dir("src/test/snapshots"))
        .withPropertyName("roborazziBaselines")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
tasks.named("check") { dependsOn("verifyRoborazziDebug") }

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
