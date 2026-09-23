package app.knotwork.design.components.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi snapshot baseline for [ImageAttachmentCatalogContent] in both
 * themes plus a reduced-motion variant. The content's Coil images resolve
 * through a deterministic preview handler (flat colour), so the baselines never
 * depend on decoding a real file. A separate page (not the main chat catalog)
 * so the existing chat baselines stay untouched.
 *
 * Run locally:
 *  - `./gradlew :catalog:recordRoborazziDebug` writes / updates the baselines.
 *  - `./gradlew :catalog:verifyRoborazziDebug` is the CI gate; pixel diffs fail.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h1600dp-xhdpi")
class ImageAttachmentCatalogPageSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun image_attachments_light() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = false) { ImageAttachmentCatalogContent() }
        }
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/image_attachments_light.png",
        )
    }

    @Test
    fun image_attachments_dark() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = true) { ImageAttachmentCatalogContent() }
        }
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/image_attachments_dark.png",
        )
    }

    @Test
    fun image_attachments_reduced_motion() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = false) {
                CompositionLocalProvider(LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true)) {
                    ImageAttachmentCatalogContent()
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/image_attachments_reduced_motion.png",
        )
    }
}
