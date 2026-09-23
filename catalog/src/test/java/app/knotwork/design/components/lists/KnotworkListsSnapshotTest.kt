package app.knotwork.design.components.lists

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

/** Roborazzi snapshot baseline for [KnotworkListsCatalogContent] in both themes. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h1100dp-xhdpi")
class KnotworkListsSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun lists_light() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = false) { KnotworkListsCatalogContent() }
        }
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/lists_light.png",
        )
    }

    @Test
    fun lists_dark() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = true) { KnotworkListsCatalogContent() }
        }
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/lists_dark.png",
        )
    }
}
