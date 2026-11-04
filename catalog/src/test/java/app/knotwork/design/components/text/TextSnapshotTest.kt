package app.knotwork.design.components.text

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baselines of the end of a text longer than a text shows: its last lines and
 * the *… N more characters* line under them, at 100 % and 200 % font scale. Nothing else in
 * the snapshot suite draws that line — every other baseline holds short text, drawn exactly
 * as Material draws it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class TextSnapshotTest {

    @get:Rule
    val rule = createComposeRule()

    /** A long error message: prose with spaces, 2 345 characters past the cap. */
    private val text = "Provider answered 502 Bad Gateway while streaming the reply; retrying. "
        .repeat((TEXT_MAX_CHARS + 2_345) / 71 + 1)
        .substring(0, TEXT_MAX_CHARS + 2_345)

    @Test
    fun text_more_chars() = snapshot(name = "text_more_chars", fontScale = 1f)

    @Test
    fun text_more_chars_fs20() = snapshot(name = "text_more_chars_fs20", fontScale = FONT_SCALE_200)

    private fun snapshot(name: String, fontScale: Float) {
        rule.setContent {
            val baseDensity = LocalDensity.current
            KnotworkTheme {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true, fontScale = fontScale),
                    LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
                ) {
                    // Scrolled to the end, where the count line is.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .background(MaterialTheme.colorScheme.surface)
                            .verticalScroll(rememberScrollState(initial = Int.MAX_VALUE))
                            .padding(16.dp),
                    ) {
                        Text(
                            text = text,
                            style = KnotworkTextStyles.BodySm,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        rule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/$name.png",
        )
    }

    private companion object {
        const val FONT_SCALE_200: Float = 2f
    }
}
