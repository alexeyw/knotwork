package app.knotwork.design.components.misc

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.assertNoAccentText
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Roborazzi snapshot baseline for [KnotworkMiscCatalogContent] in both themes. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h1100dp-xhdpi")
class KnotworkMiscSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun misc_light() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = false) { KnotworkMiscCatalogContent() }
        }
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/misc_light.png",
        )
    }

    @Test
    fun misc_dark() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = true) { KnotworkMiscCatalogContent() }
        }
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/misc_dark.png",
        )
    }
}
