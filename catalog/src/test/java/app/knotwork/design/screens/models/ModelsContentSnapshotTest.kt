package app.knotwork.design.screens.models

import androidx.compose.runtime.Composable
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
 * Roborazzi baseline for `ModelsContent`. Covers the four documented
 * visual states (Loading / Empty / Default / Error) in both themes, plus
 * a downloading variant that exercises the in-flight progress row.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ModelsContentSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun models_default_light() = snapshot(name = "default", dark = false) {
        ModelsContent(state = ModelsPreview.default())
    }

    @Test
    fun models_default_dark() = snapshot(name = "default", dark = true) {
        ModelsContent(state = ModelsPreview.default())
    }

    @Test
    fun models_empty_light() = snapshot(name = "empty", dark = false) {
        ModelsContent(state = ModelsPreview.empty())
    }

    @Test
    fun models_empty_dark() = snapshot(name = "empty", dark = true) {
        ModelsContent(state = ModelsPreview.empty())
    }

    @Test
    fun models_error_light() = snapshot(name = "error", dark = false) {
        ModelsContent(state = ModelsPreview.error())
    }

    @Test
    fun models_vision_on_light() = snapshot(name = "vision_on", dark = false) {
        ModelsContent(state = ModelsPreview.visionOn())
    }

    @Test
    fun models_vision_on_dark() = snapshot(name = "vision_on", dark = true) {
        ModelsContent(state = ModelsPreview.visionOn())
    }

    private fun snapshot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true)) {
                    content()
                }
            }
        }
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/models_${name}_$themeTag.png",
        )
    }
}

/** Snapshot fixtures backing the models test suite. */
internal object ModelsPreview {
    private fun active(): ActiveModelRow = ActiveModelRow(
        id = 1L,
        displayName = "gemma-4-E2B-it.litertlm",
        meta = "1.4 GB · NPU · QNN backend",
    )

    private fun presets(): List<PresetRow> = listOf(
        PresetRow(
            id = "gemma_4_e4b",
            name = "Gemma-4-E4B-it",
            source = "huggingface.co/litert-community/gemma-4-E4B-it…",
            status = PresetStatus.Downloading(
                progress = 64,
                metaLine = "64% · 8.4 MB/s · 1.47 / 2.3 GB · ETA 01:38",
            ),
        ),
        PresetRow(
            id = "gemma_4_e2b",
            name = "Gemma-4-E2B-it",
            source = "huggingface.co/litert-community/gemma-4-E2B-it…",
            // Active variant — exercises the inline ACTIVE badge.
            status = PresetStatus.Active(sizeMeta = "1.4 GB · NPU · LiteRT"),
        ),
        PresetRow(
            id = "phi_3_mini",
            name = "Phi-3-mini-4k-it",
            source = "huggingface.co/litert-community/Phi-3-mini-4k…",
            status = PresetStatus.Idle(sizeMeta = "2.1 GB · NPU · QNN · 4 K ctx"),
        ),
    )

    fun default(): ModelsViewState = ModelsViewState(
        visualState = ModelsVisualState.Default,
        active = active(),
        authToken = "",
        customUrl = "https://huggingface.co/litert-community/model.litertlm",
        customUrlEnabled = true,
        presets = presets(),
        downloadedRows = listOf(
            PresetRow(
                id = "custom-llama.litertlm",
                name = "custom-llama.litertlm",
                source = "downloads",
                status = PresetStatus.OnDisk(sizeMeta = "2.6 GB · NPU · LiteRT"),
            ),
        ),
        subtitle = "1 active · 3 on disk · 5.9 GB",
    )

    fun empty(): ModelsViewState = ModelsViewState(
        visualState = ModelsVisualState.Empty,
        subtitle = "0 active · 0 on disk · 0 GB",
    )

    fun error(): ModelsViewState = ModelsViewState(
        visualState = ModelsVisualState.Error,
        errorMessage = "Couldn't read the local model directory: permission denied.",
    )

    /** Default surface with the active model's image-support toggle switched ON. */
    fun visionOn(): ModelsViewState = default().copy(
        active = active().copy(visionSupported = true),
    )
}
