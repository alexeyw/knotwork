package app.knotwork.design.components.pipelineeditor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
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
 * Roborazzi baselines for the "Add node" sheet's body, one per state the
 * design draws: the sheet as opened, a search that keeps several groups, one
 * that keeps a single type, a search nothing matches, and the opened and
 * searching sheets at 200 % font scale, where nothing may be cut.
 *
 * The body rather than the sheet: a `ModalBottomSheet` does not lay out under
 * Robolectric, so the host owns the wrapper and the catalog owns this.
 * The keyboard-up state is not photographed — Robolectric draws no IME.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class NodeTypePickerSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun node_type_picker_default_light() = snapshot("default", query = "", dark = false)

    @Test
    fun node_type_picker_default_dark() = snapshot("default", query = "", dark = true)

    @Test
    fun node_type_picker_searching_light() = snapshot("searching", query = "list", dark = false)

    @Test
    fun node_type_picker_searching_dark() = snapshot("searching", query = "list", dark = true)

    @Test
    fun node_type_picker_single_match_light() = snapshot("single_match", query = "cloud", dark = false)

    @Test
    fun node_type_picker_no_match_light() = snapshot("no_match", query = "zzz", dark = false)

    @Test
    fun node_type_picker_no_match_dark() = snapshot("no_match", query = "zzz", dark = true)

    @Test
    fun node_type_picker_default_fs20_light() = snapshot("default_fs20", query = "", dark = false, fontScale = 2f)

    @Test
    fun node_type_picker_searching_fs20_light() =
        snapshot("searching_fs20", query = "list", dark = false, fontScale = 2f)

    private fun snapshot(name: String, query: String, dark: Boolean, fontScale: Float = 1f) {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 2f, fontScale = fontScale),
                LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true),
            ) {
                KnotworkTheme(darkTheme = dark) {
                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        NodeTypePickerSheetBody(
                            query = query,
                            onQueryChange = {},
                            onPick = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }
        val theme = if (dark) "dark" else "light"
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/node_type_picker_${name}_$theme.png",
        )
    }
}
