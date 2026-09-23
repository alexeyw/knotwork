package app.knotwork.design.screens.chat

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
 * Roborazzi baselines for the README hero shots at the canonical pixel
 * resolution promised in `README.md` (1080 × 2400, the de-facto-standard
 * Pixel-class portrait viewport used by every modern Android-store
 * marketing surface).
 *
 * Renders [ChatHomeContent] in the Idle state — the production-typical
 * entry point a new user sees right after onboarding. Both themes are
 * captured so the README can offer a `<picture>` block that honours the
 * reader's `prefers-color-scheme`.
 *
 * The qualifiers (`w360dp-h800dp-xxhdpi`) resolve to 1080 × 2400 device
 * pixels: 360 dp × 3 (xxhdpi) = 1080 px wide, 800 dp × 3 = 2400 px tall.
 *
 * After the test passes, the generated baselines should be copied from
 * `catalog/src/test/snapshots/hero_chat_home_{light,dark}.png` into
 * `docs/images/hero-chat-home{,-dark}.png` so the README renders the same
 * pixel-perfect shot reviewers see when they regenerate Roborazzi locally.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xxhdpi")
class HeroSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun hero_chat_home_light() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = false) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true),
                ) {
                    ChatHomeContent(state = ChatHomePreview.idle())
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/hero_chat_home_light.png",
        )
    }

    @Test
    fun hero_chat_home_dark() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = true) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true),
                ) {
                    ChatHomeContent(state = ChatHomePreview.idle())
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/hero_chat_home_dark.png",
        )
    }
}
