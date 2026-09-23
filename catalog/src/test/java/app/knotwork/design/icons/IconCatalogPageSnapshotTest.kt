package app.knotwork.design.icons

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

/**
 * Roborazzi snapshot baseline for [IconCatalogPage] in both Knotwork themes.
 *
 * Records two PNGs under `:catalog/src/test/snapshots/`:
 *  - `icon_catalog_light.png`
 *  - `icon_catalog_dark.png`
 *
 * Configuration follows `FoundationsCatalogPageSnapshotTest` (sdk 36,
 * 360×640 reference frame, native graphics mode) so baseline drift between
 * the two pages is comparable in code review.
 *
 * Locally:
 *  - `./gradlew :catalog:recordRoborazziDebug` writes / updates the baselines.
 *  - `./gradlew :catalog:verifyRoborazziDebug` compares the current render
 *    against the baselines and fails on any pixel diff.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
class IconCatalogPageSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun icon_catalog_light() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = false) {
                IconCatalogPage()
            }
        }
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/icon_catalog_light.png",
        )
    }

    @Test
    fun icon_catalog_dark() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = true) {
                IconCatalogPage()
            }
        }
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/icon_catalog_dark.png",
        )
    }
}
