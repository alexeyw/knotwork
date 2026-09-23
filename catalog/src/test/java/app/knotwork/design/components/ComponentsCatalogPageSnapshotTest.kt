package app.knotwork.design.components

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
 * Roborazzi snapshot baseline for [ComponentsCatalogPage] in both Knotwork
 * themes. Records two PNGs under `:catalog/src/test/snapshots/`:
 *  - `components_light.png`
 *  - `components_dark.png`
 *
 * Run locally:
 *  - `./gradlew :catalog:recordRoborazziDebug` writes / updates the baselines.
 *  - `./gradlew :catalog:verifyRoborazziDebug` is the CI gate; pixel diffs fail.
 *
 * Configuration mirrors [app.knotwork.design.foundations.FoundationsCatalogPageSnapshotTest].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
class ComponentsCatalogPageSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun components_light() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = false) { ComponentsCatalogPage() }
        }
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/components_light.png",
        )
    }

    @Test
    fun components_dark() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = true) { ComponentsCatalogPage() }
        }
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/components_dark.png",
        )
    }
}
