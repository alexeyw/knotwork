package app.knotwork.design.screens.splash

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

/**
 * Roborazzi baseline for `SplashContent`. Covers the documented states
 * (Initializing / Loading / Error / DataLocked) in both themes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class SplashContentSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun splash_loading_light() = snapshot(name = "loading", dark = false) {
        SplashContent(appName = "Knotwork Agent", state = SplashPreview.loading())
    }

    @Test
    fun splash_loading_dark() = snapshot(name = "loading", dark = true) {
        SplashContent(appName = "Knotwork Agent", state = SplashPreview.loading())
    }

    @Test
    fun splash_error_light() = snapshot(name = "error", dark = false) {
        SplashContent(appName = "Knotwork Agent", state = SplashPreview.error())
    }

    @Test
    fun splash_error_dark() = snapshot(name = "error", dark = true) {
        SplashContent(appName = "Knotwork Agent", state = SplashPreview.error())
    }

    @Test
    fun splash_data_locked_light() = snapshot(name = "data_locked", dark = false) {
        SplashContent(appName = "Knotwork Agent", state = SplashPreview.dataLocked())
    }

    @Test
    fun splash_data_locked_dark() = snapshot(name = "data_locked", dark = true) {
        SplashContent(appName = "Knotwork Agent", state = SplashPreview.dataLocked())
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
            filePath = "src/test/snapshots/splash_${name}_$themeTag.png",
        )
    }
}

/** Snapshot fixtures backing the splash test suite. */
internal object SplashPreview {
    fun loading(): SplashViewState = SplashViewState.Loading(
        message = "Loading model • Gemma 4 E2B",
        progress = 0.62f,
    )

    fun error(): SplashViewState = SplashViewState.Error(
        message = "Initialisation failed: model file not found. Reinstall the model from Settings.",
    )

    fun dataLocked(): SplashViewState = SplashViewState.DataLocked(
        title = "Your data can’t be unlocked",
        body = "The encryption key protecting your local data could not be read. " +
            "This is often temporary — try again first. If the problem persists, " +
            "the only way forward is erasing all local data.",
        resetLabel = "Erase all data",
    )
}
