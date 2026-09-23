package app.knotwork.design.components.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.assertNoAccentText
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi snapshot baseline for [ChatCatalogContent] in both themes plus a
 * reduced-motion variant that pins [FixedKnotworkA11y] so the long-press
 * scale + composer morph behave deterministically.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h2400dp-xhdpi")
class ChatCatalogPageSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun chat_light() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = false) { ChatCatalogContent() }
        }
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/chat_light.png",
        )
    }

    @Test
    fun chat_dark() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = true) { ChatCatalogContent() }
        }
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/chat_dark.png",
        )
    }

    @Test
    fun chat_reduced_motion() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = false) {
                CompositionLocalProvider(LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true)) {
                    ChatCatalogContent()
                }
            }
        }
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/chat_reduced_motion.png",
        )
    }
}
