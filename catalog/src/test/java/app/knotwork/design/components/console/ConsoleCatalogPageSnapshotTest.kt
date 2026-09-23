package app.knotwork.design.components.console

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi snapshot baseline for [ConsoleCatalogContent] in both themes.
 * The console body itself is theme-locked dark — the surrounding surface
 * varies with the parent theme, so both snapshots remain useful regression
 * gates.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h1800dp-xhdpi")
class ConsoleCatalogPageSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun console_light() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = false) { ConsoleCatalogContent() }
        }
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/console_light.png",
        )
    }

    @Test
    fun console_dark() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = true) { ConsoleCatalogContent() }
        }
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/console_dark.png",
        )
    }
}
