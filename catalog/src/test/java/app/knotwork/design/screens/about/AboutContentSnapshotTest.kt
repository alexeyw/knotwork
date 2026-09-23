package app.knotwork.design.screens.about

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.assertNoAccentText
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class AboutContentSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun about_default_light() = snapshot(name = "default", dark = false) {
        AboutContent(state = AboutPreview.default())
    }

    @Test
    fun about_default_dark() = snapshot(name = "default", dark = true) {
        AboutContent(state = AboutPreview.default())
    }

    @Test
    fun about_no_documentation_light() = snapshot(name = "no_documentation", dark = false) {
        AboutContent(state = AboutPreview.withoutDocumentation())
    }

    private fun snapshot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true)) {
                    content()
                }
            }
        }
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/about_${name}_$themeTag.png",
        )
    }
}

internal object AboutPreview {
    fun default(): AboutViewState = AboutViewState(
        appName = "Knotwork Agent",
        tagline = "An on-device AI agent for Android",
        versionLine = "Version 0.1.0",
        buildLine = "Build 1 · debug",
        commitSha = "abc1234",
        licenseName = "Apache 2.0",
        acknowledgments = listOf(
            AcknowledgmentEntry(name = "Jetpack Compose", license = "Apache 2.0"),
            AcknowledgmentEntry(name = "AndroidX Jetpack", license = "Apache 2.0"),
            AcknowledgmentEntry(name = "Kotlin Coroutines", license = "Apache 2.0"),
            AcknowledgmentEntry(name = "Hilt", license = "Apache 2.0"),
            AcknowledgmentEntry(name = "Room", license = "Apache 2.0"),
            AcknowledgmentEntry(name = "AppFunctions", license = "Apache 2.0"),
            AcknowledgmentEntry(name = "LiteRT-LM", license = "Apache 2.0"),
            AcknowledgmentEntry(name = "MediaPipe", license = "Apache 2.0"),
            AcknowledgmentEntry(name = "Universal Sentence Encoder", license = "Apache 2.0"),
            AcknowledgmentEntry(name = "Koog", license = "Apache 2.0"),
            AcknowledgmentEntry(name = "Ktor", license = "Apache 2.0"),
            AcknowledgmentEntry(name = "OkHttp", license = "Apache 2.0"),
            AcknowledgmentEntry(name = "Gson", license = "Apache 2.0"),
            AcknowledgmentEntry(name = "Multiplatform Markdown Renderer", license = "Apache 2.0"),
            AcknowledgmentEntry(name = "Timber", license = "Apache 2.0"),
            AcknowledgmentEntry(name = "ProcessPhoenix", license = "Apache 2.0"),
            AcknowledgmentEntry(name = "Firebase Crashlytics", license = "Apache 2.0"),
            AcknowledgmentEntry(name = "SQLCipher", license = "BSD-3-Clause"),
            AcknowledgmentEntry(name = "Inter", license = "SIL OFL 1.1"),
            AcknowledgmentEntry(name = "JetBrains Mono", license = "SIL OFL 1.1"),
        ),
        privacyBody = "All sensitive data is processed locally via LiteRT. " +
            "Cloud providers are opt-in. Crash reporting is opt-in and never includes prompts or memory content.",
        documentationSummary = "5 documents · 2 on this device",
    )

    /**
     * The About screen before the documentation section existed.
     *
     * Kept as its own fixture because the section is rendered only when the
     * host supplies a summary, and a snapshot of the populated state alone
     * would not show that the empty case still lays out.
     */
    fun withoutDocumentation(): AboutViewState = default().copy(documentationSummary = "")
}
