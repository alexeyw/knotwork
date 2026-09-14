package app.knotwork.design.components.chips

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

/** Roborazzi snapshot baseline for [KnotworkChipsCatalogContent] in both themes. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
class KnotworkChipsSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun chips_light() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = false) { KnotworkChipsCatalogContent() }
        }
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/chips_light.png",
        )
    }

    @Test
    fun chips_dark() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = true) { KnotworkChipsCatalogContent() }
        }
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/chips_dark.png",
        )
    }
}
