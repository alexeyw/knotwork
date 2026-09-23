package app.knotwork.design.screens.triggers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
 * Roborazzi snapshot baselines for the Triggers surfaces — the list
 * (`TriggersContent`), the full-screen editor (`TriggerEditorContent`) and the
 * delete dialog (`TriggerDeleteDialogContent`) across their documented states in
 * both themes.
 *
 * Reduced-motion is pinned via [FixedKnotworkA11y] so no looping animation
 * randomises a snapshot.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class TriggersContentSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // ── List ─────────────────────────────────────────────────────────────

    @Test
    fun list_populated_light() = snapshot(name = "list_populated", dark = false) {
        TriggersContent(state = TriggersPreview.populated())
    }

    @Test
    fun list_populated_dark() = snapshot(name = "list_populated", dark = true) {
        TriggersContent(state = TriggersPreview.populated())
    }

    @Test
    fun list_row_menu_light() = snapshot(name = "list_row_menu", dark = false) {
        TriggersContent(state = TriggersPreview.rowMenu())
    }

    @Test
    fun list_empty_light() = snapshot(name = "list_empty", dark = false) {
        TriggersContent(state = TriggersPreview.empty())
    }

    @Test
    fun list_empty_dark() = snapshot(name = "list_empty", dark = true) {
        TriggersContent(state = TriggersPreview.empty())
    }

    @Test
    fun list_loading_light() = snapshot(name = "list_loading", dark = false) {
        TriggersContent(state = TriggersPreview.loading())
    }

    @Test
    fun list_error_light() = snapshot(name = "list_error", dark = false) {
        TriggersContent(state = TriggersPreview.error())
    }

    // ── Editor ───────────────────────────────────────────────────────────

    @Test
    fun editor_create_light() = snapshot(name = "editor_create", dark = false) {
        TriggerEditorContent(state = TriggersPreview.editorCreate())
    }

    @Test
    fun editor_daily_light() = snapshot(name = "editor_daily", dark = false) {
        TriggerEditorContent(state = TriggersPreview.editorDaily())
    }

    @Test
    fun editor_daily_dark() = snapshot(name = "editor_daily", dark = true) {
        TriggerEditorContent(state = TriggersPreview.editorDaily())
    }

    @Test
    fun editor_interval_light() = snapshot(name = "editor_interval", dark = false) {
        TriggerEditorContent(state = TriggersPreview.editorInterval())
    }

    @Test
    fun editor_charging_light() = snapshot(name = "editor_charging", dark = false) {
        TriggerEditorContent(state = TriggersPreview.editorCharging())
    }

    @Test
    fun editor_network_light() = snapshot(name = "editor_network", dark = false) {
        TriggerEditorContent(state = TriggersPreview.editorNetwork())
    }

    @Test
    fun editor_network_scoped_light() = snapshot(name = "editor_network_scoped", dark = false) {
        TriggerEditorContent(state = TriggersPreview.editorNetworkScoped())
    }

    @Test
    fun editor_network_scoped_dark() = snapshot(name = "editor_network_scoped", dark = true) {
        TriggerEditorContent(state = TriggersPreview.editorNetworkScoped())
    }

    @Test
    fun editor_invalid_light() = snapshot(name = "editor_invalid", dark = false) {
        TriggerEditorContent(state = TriggersPreview.editorInvalid())
    }

    // ── Detail screen ────────────────────────────────────────────────────

    @Test
    fun detail_populated_light() = snapshot(name = "detail_populated", dark = false) {
        TriggerDetailContent(state = TriggersPreview.detailPopulated())
    }

    @Test
    fun detail_populated_dark() = snapshot(name = "detail_populated", dark = true) {
        TriggerDetailContent(state = TriggersPreview.detailPopulated())
    }

    @Test
    fun detail_stale_light() = snapshot(name = "detail_stale", dark = false) {
        TriggerDetailContent(state = TriggersPreview.detailStale())
    }

    @Test
    fun detail_stale_dark() = snapshot(name = "detail_stale", dark = true) {
        TriggerDetailContent(state = TriggersPreview.detailStale())
    }

    @Test
    fun detail_empty_light() = snapshot(name = "detail_empty", dark = false) {
        TriggerDetailContent(state = TriggersPreview.detailEmpty())
    }

    @Test
    fun detail_empty_dark() = snapshot(name = "detail_empty", dark = true) {
        TriggerDetailContent(state = TriggersPreview.detailEmpty())
    }

    @Test
    fun detail_loading_light() = snapshot(name = "detail_loading", dark = false) {
        TriggerDetailContent(state = TriggersPreview.detailLoading())
    }

    @Test
    fun detail_unbound_light() = snapshot(name = "detail_unbound", dark = false) {
        TriggerDetailContent(state = TriggersPreview.detailUnbound())
    }

    // ── Delete dialog ────────────────────────────────────────────────────

    @Test
    fun delete_light() = snapshot(name = "delete", dark = false) {
        DialogHost { TriggerDeleteDialogContent(state = TriggersPreview.delete()) }
    }

    @Test
    fun delete_dark() = snapshot(name = "delete", dark = true) {
        DialogHost { TriggerDeleteDialogContent(state = TriggersPreview.delete()) }
    }

    /** Pads the dialog card so its drop-shadow and corners fit in the capture. */
    @Composable
    private fun DialogHost(content: @Composable () -> Unit) {
        Box(modifier = Modifier.padding(20.dp)) { content() }
    }

    /**
     * The list at the "Largest" text preset — the only capture that exercises
     * `HealthBadge`'s icon-only collapse, and the one place a long trigger name
     * could squeeze the badge off the row.
     */
    @Test
    fun list_populated_font_scale_2x_light() =
        snapshot(name = "list_populated_font_scale_2x", dark = false, fontScale = LARGE_FONT_SCALE) {
            TriggersContent(state = TriggersPreview.populated())
        }

    /**
     * Wraps the content under the standard test rule, pins reduced-motion so
     * looping animations don't randomise the snapshot, and writes the PNG to
     * `src/test/snapshots/trigger_<name>_<theme>.png`.
     */
    private fun snapshot(name: String, dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            // Inside the theme on purpose: it is the placement that survives the
            // theme ever provisioning `LocalKnotworkA11y` again.
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true, fontScale = fontScale),
                    LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
                ) {
                    content()
                }
            }
        }
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/trigger_${name}_$themeTag.png",
        )
    }

    private companion object {
        /** The "Largest" system text-size preset every layout must survive. */
        const val LARGE_FONT_SCALE = 2.0f
    }
}
