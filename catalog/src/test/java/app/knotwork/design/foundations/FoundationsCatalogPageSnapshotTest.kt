package app.knotwork.design.foundations

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
 * Roborazzi snapshot baseline for the [FoundationsCatalogPage] in both
 * Knotwork themes.
 *
 * Records two PNGs under `:catalog/src/test/snapshots/`:
 *  - `foundations_light.png`
 *  - `foundations_dark.png`
 *
 * The Robolectric configuration:
 *  - `sdk = [36]` — the newest Android release the project supports, not its
 *    `minSdk` (which is 34). These snapshots exercise the design system rather
 *    than any version-gated behaviour, so the render level is a fixed reference
 *    rather than a floor to track: pinning it keeps ~58 snapshot classes and
 *    their committed baselines stable when the floor moves. Robolectric 4.16
 *    needs JDK 21 to render against SDK 36 — that, not `minSdk`, is where the
 *    project's JDK 21 requirement comes from (production code still targets
 *    JVM 17).
 *  - `qualifiers = "w360dp-h640dp-xhdpi"` — a 360×640 reference frame so
 *    snapshots stay reviewable as static images and do not balloon with the
 *    host's screen geometry.
 *  - `GraphicsMode.NATIVE` — required by Roborazzi to use the platform
 *    rendering pipeline rather than the legacy shadow renderer.
 *
 * Locally:
 *  - `./gradlew :catalog:recordRoborazziDebug` writes / updates the baselines.
 *  - `./gradlew :catalog:verifyRoborazziDebug` compares the current render
 *    against the baselines and fails on any pixel diff.
 *
 * The CI gate is the verify task; baselines are committed into version
 * control so reviewers see baseline drift in the same diff as the code
 * change that produced it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
class FoundationsCatalogPageSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun foundations_light() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = false) {
                FoundationsCatalogPage()
            }
        }
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/foundations_light.png",
        )
    }

    @Test
    fun foundations_dark() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = true) {
                FoundationsCatalogPage()
            }
        }
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/foundations_dark.png",
        )
    }
}
