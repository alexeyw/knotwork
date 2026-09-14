package app.knotwork.design.components.buttons

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
 * Roborazzi snapshot baseline for the [KnotworkButtonsCatalogContent] harness
 * in both themes — covers every primary / secondary / text / icon button
 * state in two snapshots (`buttons_light.png`, `buttons_dark.png`).
 *
 * Per-state snapshots are intentionally not split out: the harness already
 * lays every variant out side-by-side, so a single PNG per theme catches
 * regressions across the surface in one diff.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
class KnotworkButtonsSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun buttons_light() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = false) { KnotworkButtonsCatalogContent() }
        }
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/buttons_light.png",
        )
    }

    @Test
    fun buttons_dark() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = true) { KnotworkButtonsCatalogContent() }
        }
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/buttons_dark.png",
        )
    }
}
